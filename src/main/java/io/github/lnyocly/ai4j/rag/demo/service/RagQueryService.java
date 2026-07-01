package io.github.lnyocly.ai4j.rag.demo.service;

import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.rag.RagCitation;
import io.github.lnyocly.ai4j.rag.RagQuery;
import io.github.lnyocly.ai4j.rag.RagResult;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.domain.RagAnswer;
import io.github.lnyocly.ai4j.rag.demo.domain.ReferenceItem;
import io.github.lnyocly.ai4j.service.IMessagesService;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 在线问答编排：检索（ai4j RagService）→ 上下文组装 → 生成（GLM via Anthropic Messages）。
 */
@Service
public class RagQueryService {

    private final RagProperties ragProperties;
    private final IMessagesService messagesService;
    private final RagService ragService;

    public RagQueryService(AiService aiService, PgVectorStore vectorStore, RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.messagesService = aiService.getMessagesService(PlatformType.ANTHROPIC);
        this.ragService = aiService.getRagService(PlatformType.OLLAMA, vectorStore);
    }

    public RagAnswer ask(ChatRequest request) throws Exception {
        RagQuery query = RagQuery.builder()
                .query(request.getQuestion())
                .dataset(ragProperties.getDataset())
                .embeddingModel(ragProperties.getEmbeddingModel())
                .topK(ragProperties.getTopK())
                .build();

        RagResult result = ragService.search(query);
        String context = result.getContext() == null ? "" : result.getContext();
        boolean degraded = context.isBlank();

        List<ReferenceItem> references = new ArrayList<>();
        List<RagCitation> citations = result.getCitations() == null
                ? Collections.<RagCitation>emptyList() : result.getCitations();
        for (RagCitation citation : citations) {
            references.add(ReferenceItem.builder()
                    .sourceName(citation.getSourceName())
                    .sectionTitle(citation.getSectionTitle())
                    .snippet(citation.getSnippet())
                    .build());
        }

        String answer = generate(request.getQuestion(), context, degraded);
        return RagAnswer.builder()
                .answer(answer)
                .references(references)
                .hitCount(result.getHits() == null ? 0 : result.getHits().size())
                .degraded(degraded)
                .build();
    }

    private String generate(String question, String context, boolean degraded) throws Exception {
        String system = "你是企业电商知识助手。严格根据下方参考资料回答用户问题。"
                + "若资料不足以支撑答案，请明确说明\"根据当前知识库资料无法确认\"，不要编造制度、流程或时效。"
                + "回答要简洁、准确、可执行，并优先引用参考资料中的明确规则。";
        String user = (degraded ? "（未检索到相关资料）\n\n" : "参考资料：\n" + context + "\n\n")
                + "用户问题：" + question;
        AnthropicChatCompletion req = new AnthropicChatCompletion();
        req.setModel(ragProperties.getGlmModel());
        req.setSystem(system);
        req.setMessages(Collections.singletonList(new AnthropicMessage("user", user)));
        req.setMaxTokens(1024);
        AnthropicChatCompletionResponse resp = messagesService.messages(req);
        return extractText(resp);
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
