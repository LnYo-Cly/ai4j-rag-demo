package io.github.lnyocly.ai4j.rag.demo.evaluator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.rag.RagJudge;
import io.github.lnyocly.ai4j.rag.RagJudgeEvaluation;
import io.github.lnyocly.ai4j.rag.RagJudgeRequest;
import io.github.lnyocly.ai4j.service.IMessagesService;

import java.util.Collections;

/**
 * 用 Anthropic Messages 协议（IMessagesService）实现 RagJudge —— 复用主答的同一个 GLM 入口
 * （api/anthropic + coding-plan key），不依赖 SDK 自带 ChatRagJudge 要求的 paas/v4（OpenAI 协议）入口。
 *
 * 为什么 demo 自己实现而不是直接用 ChatRagJudge：
 *   ChatRagJudge 走 OpenAI 协议（IChatService）→ 必须打 GLM paas/v4；而 GLM 的 coding-plan key
 *   只对 api/anthropic 有效，打 paas/v4 会报「余额不足」(实测 HTTP 429 code 1113)。
 *   demo 的 key 是 coding-plan 的，用 ChatRagJudge 每次必败。换成 anthropic 通道就通——
 *   同时正好例证 SDK 的 RagJudge SPI「实现接口即可替换 judge 算法/通道」（见 blog 16.3）。
 *
 * 评估逻辑与 SDK 的 ChatRagJudge 等价（同样的 system prompt + 三项打分），只换了传输协议。
 */
public class AnthropicRagJudge implements RagJudge {

    private static final String SYSTEM = "You are a strict RAG evaluator. Return only JSON with "
            + "faithfulnessScore, contextRelevanceScore, answerRelevanceScore, reason. "
            + "Scores must be numbers from 0 to 1.";

    private final IMessagesService messagesService;
    private final String model;

    public AnthropicRagJudge(IMessagesService messagesService, String model) {
        if (messagesService == null) {
            throw new IllegalArgumentException("messagesService is required");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("model is required");
        }
        this.messagesService = messagesService;
        this.model = model;
    }

    @Override
    public RagJudgeEvaluation judge(RagJudgeRequest request) throws Exception {
        AnthropicChatCompletion req = new AnthropicChatCompletion();
        req.setModel(model);
        req.setSystem(SYSTEM);
        req.setMessages(Collections.singletonList(new AnthropicMessage("user", userPrompt(request))));
        req.setMaxTokens(512);
        AnthropicChatCompletionResponse resp = messagesService.messages(req);
        String output = extractText(resp);
        JSONObject json = JSON.parseObject(jsonObjectText(output));
        return RagJudgeEvaluation.builder()
                .faithfulnessScore(score(json, "faithfulnessScore"))
                .contextRelevanceScore(score(json, "contextRelevanceScore"))
                .answerRelevanceScore(score(json, "answerRelevanceScore"))
                .reason(json.getString("reason"))
                .rawOutput(output)
                .build();
    }

    private String userPrompt(RagJudgeRequest r) {
        return "Question:\n" + safe(r == null ? null : r.getQuery())
                + "\n\nAnswer:\n" + safe(r == null ? null : r.getAnswer())
                + "\n\nRetrieved context:\n" + safe(r == null ? null : r.getContext())
                + "\n\nJudge:\n"
                + "- faithfulnessScore: answer is supported by retrieved context.\n"
                + "- contextRelevanceScore: retrieved context is relevant to the question.\n"
                + "- answerRelevanceScore: answer directly addresses the question.";
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

    private Double score(JSONObject json, String key) {
        if (json == null || !json.containsKey(key)) {
            return null;
        }
        double v = json.getDoubleValue(key);
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String jsonObjectText(String output) {
        if (output == null) {
            return "{}";
        }
        int s = output.indexOf('{');
        int e = output.lastIndexOf('}');
        return (s >= 0 && e >= s) ? output.substring(s, e + 1) : "{}";
    }
}
