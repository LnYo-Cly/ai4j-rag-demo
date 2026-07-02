package io.github.lnyocly.ai4j.rag.demo.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.rag.DefaultRagContextAssembler;
import io.github.lnyocly.ai4j.rag.DefaultRagService;
import io.github.lnyocly.ai4j.rag.DenseRetriever;
import io.github.lnyocly.ai4j.rag.RagCitation;
import io.github.lnyocly.ai4j.rag.RagQuery;
import io.github.lnyocly.ai4j.rag.RagResult;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.domain.RagAnswer;
import io.github.lnyocly.ai4j.rag.demo.domain.ReferenceItem;
import io.github.lnyocly.ai4j.rag.demo.rerank.LlmReranker;
import io.github.lnyocly.ai4j.service.IMessagesService;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG 在线问答主链编排。
 *
 * 检索：DenseRetriever（PgVector KNN，带多租户 permissionTag 前置过滤）
 * 重排：LlmReranker（GLM 批量打分；Ollama 无 rerank 端点的兜底方案）
 * 生成：GLM via IMessagesService（Anthropic Messages 协议）
 * 缓存：Caffeine（应用层 answer cache，key = tenant::question）
 *
 * 多租户：tenant=default 只看 permissionTag=public 的知识；
 *         tenant=premium 看 public + premium（运营/风控内部规则）。
 */
@Service
public class RagQueryService {

    private final RagProperties ragProperties;
    private final IMessagesService messagesService;
    private final RagService ragService;
    private final Cache<String, RagAnswer> answerCache;

    public RagQueryService(AiService aiService, PgVectorStore vectorStore, RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.messagesService = aiService.getMessagesService(PlatformType.ANTHROPIC);
        this.ragService = new DefaultRagService(
                new DenseRetriever(aiService.getEmbeddingService(PlatformType.OLLAMA), vectorStore),
                new LlmReranker(this.messagesService, ragProperties.getGlmModel(), ragProperties.getTopK()),
                new DefaultRagContextAssembler());
        this.answerCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    public RagAnswer ask(ChatRequest request) throws Exception {
        String cacheKey = (request.getTenantId() == null ? "default" : request.getTenantId())
                + "::" + normalize(request.getQuestion());
        RagAnswer cached = answerCache.getIfPresent(cacheKey);
        if (cached != null) {
            cached.setCached(true);
            return cached;
        }

        // 多租户：权限标签前置过滤（PgVector metadata jsonb where，KNN 前完成）
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("permissionTag", permissionTagsFor(request.getTenantId()));

        RagQuery query = RagQuery.builder()
                .query(request.getQuestion())
                .dataset(ragProperties.getDataset())
                .embeddingModel(ragProperties.getEmbeddingModel())
                .topK(ragProperties.getTopK())
                .filter(filter)
                .build();

        RagResult result = ragService.search(query);
        String context = result.getContext() == null ? "" : result.getContext();
        boolean degraded = context.isBlank();

        List<ReferenceItem> references = new ArrayList<ReferenceItem>();
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
        RagAnswer ragAnswer = RagAnswer.builder()
                .answer(answer)
                .references(references)
                .hitCount(result.getHits() == null ? 0 : result.getHits().size())
                .degraded(degraded)
                .cached(false)
                .build();
        answerCache.put(cacheKey, ragAnswer);
        return ragAnswer;
    }

    /** 暴露缓存统计（命中率/大小），给可观测用。 */
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("size", answerCache.estimatedSize());
        stats.put("hitRate", answerCache.stats().hitRate());
        stats.put("requestCount", answerCache.stats().requestCount());
        return stats;
    }

    private List<String> permissionTagsFor(String tenant) {
        if ("premium".equals(tenant)) {
            return Arrays.asList("public", "premium");
        }
        return Collections.singletonList("public");
    }

    private String generate(String question, String context, boolean degraded) throws Exception {
        String system = "你是企业电商知识助手。严格根据下方参考资料回答用户问题。"
                + "若资料不足以支撑答案，请明确说明\"根据当前知识库资料无法确认\"，"
                + "不要编造制度、流程或时效。回答要简洁、准确、可执行。";
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

    private String normalize(String q) {
        return q == null ? "" : q.trim().toLowerCase();
    }
}
