package io.github.lnyocly.ai4j.rag.demo.rerank;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.rag.RagHit;
import io.github.lnyocly.ai4j.rag.Reranker;
import io.github.lnyocly.ai4j.service.IMessagesService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM-based reranker：让 GLM 对每个召回 chunk 按 query 相关度打分（0-1），按分重排。
 *
 * 为什么用它：Ollama 原生没有 /api/rerank 端点，ai4j 的 OllamaRerankService 调的 api/rerank 会 404。
 * 在没有专用 rerank 模型（Jina/Doubao/Standard）可用时，用 chat 模型批量打分是通用兜底方案。
 * 生产环境如果有专用 rerank，换 ai4j 的 ModelReranker 即可（Reranker 接口不变）。
 */
public class LlmReranker implements Reranker {

    private final IMessagesService messagesService;
    private final String model;
    private final int topN;

    public LlmReranker(IMessagesService messagesService, String model, int topN) {
        this.messagesService = messagesService;
        this.model = model;
        this.topN = topN;
    }

    @Override
    public List<RagHit> rerank(String query, List<RagHit> hits) throws Exception {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        if (query == null || query.trim().isEmpty()) {
            return hits;
        }

        StringBuilder docs = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            String c = hits.get(i).getContent();
            if (c == null) {
                c = "";
            }
            if (c.length() > 200) {
                c = c.substring(0, 200);
            }
            docs.append("[").append(i).append("] ").append(c).append("\n");
        }
        String system = "你是检索重排器。对每个文档,按与用户问题的语义相关度打分(0.0-1.0,1.0 最相关)。"
                + "只返回 JSON 数组,如 [{\"index\":0,\"score\":0.9}],不要任何其他内容。";
        String user = "用户问题:" + query + "\n\n文档:\n" + docs;

        AnthropicChatCompletion req = new AnthropicChatCompletion();
        req.setModel(model);
        req.setSystem(system);
        req.setMessages(Collections.singletonList(new AnthropicMessage("user", user)));
        req.setMaxTokens(1024);
        AnthropicChatCompletionResponse resp = messagesService.messages(req);
        double[] scores = parseScores(extractText(resp), hits.size());

        List<RagHit> ranked = new ArrayList<RagHit>(hits);
        ranked.sort((a, b) -> Double.compare(scores[hits.indexOf(b)], scores[hits.indexOf(a)]));
        for (RagHit h : ranked) {
            h.setRerankScore((float) scores[hits.indexOf(h)]);
        }
        return topN > 0 && ranked.size() > topN
                ? new ArrayList<RagHit>(ranked.subList(0, topN))
                : ranked;
    }

    private double[] parseScores(String text, int n) {
        double[] scores = new double[n];
        try {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                JSONArray arr = JSON.parseArray(text.substring(start, end + 1));
                for (int i = 0; i < arr.size(); i++) {
                    com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
                    int idx = o.getIntValue("index");
                    double score = o.getDoubleValue("score");
                    if (idx >= 0 && idx < n) {
                        scores[idx] = score;
                    }
                }
            }
        } catch (Exception ignore) {
            for (int i = 0; i < n; i++) {
                scores[i] = 1.0 - (i * 0.1);
            }
        }
        return scores;
    }

    private String extractText(AnthropicChatCompletionResponse resp) {
        if (resp == null || resp.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AnthropicContentBlock block : resp.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        return sb.toString();
    }
}
