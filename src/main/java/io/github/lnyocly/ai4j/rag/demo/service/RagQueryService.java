package io.github.lnyocly.ai4j.rag.demo.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicUsage;
import io.github.lnyocly.ai4j.rag.DefaultRagContextAssembler;
import io.github.lnyocly.ai4j.rag.TokenAwareRagContextAssembler;
import io.github.lnyocly.ai4j.rag.NoopReranker;
import io.github.lnyocly.ai4j.rag.Reranker;
import io.github.lnyocly.ai4j.rag.DefaultRagService;
import io.github.lnyocly.ai4j.rag.Bm25Retriever;
import io.github.lnyocly.ai4j.rag.DenseRetriever;
import io.github.lnyocly.ai4j.rag.HybridRetriever;
import io.github.lnyocly.ai4j.rag.RagContextAssembler;
import io.github.lnyocly.ai4j.rag.RagCitation;
import io.github.lnyocly.ai4j.rag.Retriever;
import io.github.lnyocly.ai4j.rag.RrfFusionStrategy;
import io.github.lnyocly.ai4j.rag.RagGenerationUsage;
import io.github.lnyocly.ai4j.rag.RagHit;
import io.github.lnyocly.ai4j.rag.RagJudgeEvaluation;
import io.github.lnyocly.ai4j.rag.RagOnlineEvaluator;
import io.github.lnyocly.ai4j.rag.RagQueryPlanner;
import io.github.lnyocly.ai4j.rag.RagQuery;
import io.github.lnyocly.ai4j.rag.RagResult;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.RagTrace;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.domain.RagAnswer;
import io.github.lnyocly.ai4j.rag.demo.domain.ReferenceItem;
import io.github.lnyocly.ai4j.rag.demo.rerank.LlmReranker;
import io.github.lnyocly.ai4j.service.IMessagesService;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG 在线问答主链编排。每一步的中间产物都写进 RagAnswer，让一次请求的完整执行轨迹可见：
 * 输入 → 改写（rewrittenQuery）→ 召回（retrievedHits）→ 重排（rerankedHits）→ 上下文（context）→ 生成（answer）→ 输出。
 *
 * 检索：DenseRetriever（PgVector KNN，带多租户 permissionTag 前置过滤）
 * 重排：LlmReranker（GLM 打分）
 * 生成：GLM via IMessagesService（Anthropic Messages，拒答约束）
 * 缓存：Caffeine（key = tenant::originalQuestion）
 */
@Slf4j
@Service
public class RagQueryService {

    private final RagProperties ragProperties;
    private final IMessagesService messagesService;
    private final Cache<String, RagAnswer> answerCache;
    private final QueryRewriteService queryRewriteService;
    private final DenseRetriever denseRetriever;
    private final Reranker reranker;
    private final RagContextAssembler contextAssembler;
    private final RagQueryPlanner planner;
    private final InMemoryCorpus inMemoryCorpus;
    /** 在线评估器（LLM-as-judge，online-eval-enabled=true 时构造；走 ZHIPU OpenAI 协议 paas/v4）。
     *  注意：judge 用的是 OpenAI 协议（IChatService），需要 ai.zhipu.api-key 配 paas/v4 的 key，
     *  与 anthropic 入口的 coding-plan key 不是同一个——coding-plan key 打 paas/v4 会报余额不足。 */
    private final RagOnlineEvaluator onlineEvaluator;

    public RagQueryService(AiService aiService, PgVectorStore vectorStore, RagProperties ragProperties,
                           QueryRewriteService queryRewriteService, InMemoryCorpus inMemoryCorpus) {
        this.ragProperties = ragProperties;
        this.messagesService = aiService.getMessagesService(PlatformType.ANTHROPIC);
        this.queryRewriteService = queryRewriteService;
        this.inMemoryCorpus = inMemoryCorpus;
        this.denseRetriever = new DenseRetriever(aiService.getEmbeddingService(PlatformType.OLLAMA), vectorStore);
        this.reranker = resolveReranker(aiService);
        this.contextAssembler = ragProperties.getMaxContextTokens() > 0
                ? new TokenAwareRagContextAssembler(ragProperties.getContextModel(), ragProperties.getMaxContextTokens())
                : new DefaultRagContextAssembler();
        this.planner = ragProperties.isPlannerEnabled()
                ? aiService.getModelRagQueryPlanner(PlatformType.ANTHROPIC, ragProperties.getGlmModel(),
                        null, ragProperties.getPlannerMaxVariants(), true)
                : null;
        this.onlineEvaluator = ragProperties.isOnlineEvalEnabled()
                ? aiService.getRagOnlineEvaluator(PlatformType.ZHIPU, ragProperties.getGlmModel())
                : null;
        this.answerCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 构造本次请求的 RagService：hybrid-enabled 且 BM25 corpus 非空时用 Hybrid(Dense+BM25+RRF)，
     * 否则纯 Dense。per-request 构造是因为 InMemoryCorpus 在 ApplicationRunner 阶段才填，
     * 单例 BM25 会卡在 startup 空 corpus。BM25 用 publicCorpus（premium 不进 BM25，多租户不泄漏）。
     */
    private RagService buildRagService() {
        Retriever retriever = this.denseRetriever;
        if (ragProperties.isHybridEnabled()) {
            List<RagHit> corpus = inMemoryCorpus.publicCorpus();
            if (!corpus.isEmpty()) {
                retriever = new HybridRetriever(
                        Arrays.asList(denseRetriever, new Bm25Retriever(corpus)),
                        new RrfFusionStrategy());
            }
        }
        return new DefaultRagService(retriever, reranker, contextAssembler, planner);
    }

    public RagAnswer ask(ChatRequest request) {
        String cacheKey = (request.getTenantId() == null ? "default" : request.getTenantId())
                + "::" + normalize(request.getQuestion());
        RagAnswer cached = answerCache.getIfPresent(cacheKey);
        if (cached != null) {
            cached.setCached(true);
            return cached;
        }
        try {
            // ① 多租户：权限标签前置过滤（PgVector metadata jsonb where，KNN 前完成）
            Map<String, Object> filter = new LinkedHashMap<String, Object>();
            filter.put("permissionTag", permissionTagsFor(request.getTenantId()));

            // ② Query Rewrite（planner 关时）；planner 开时由 RagService 内部 plan 生成 variants，跳 rewrite 避免重复
            String rewritten = ragProperties.isPlannerEnabled()
                    ? request.getQuestion()
                    : queryRewriteService.rewrite(request.getQuestion());

            // ③ 检索 + ④ 重排（RagService 内部），开 trace 拿到 retrieved/reranked 两份顺序
            // conversational-enabled=true 时把对话历史带进 RagQuery：planner rewrite 会据此消解 follow-up 指代
            // （如「它的退款」→「订单的退款」），让多轮问答能正确召回。
            RagQuery query = RagQuery.builder()
                    .query(rewritten)
                    .dataset(ragProperties.getDataset())
                    .embeddingModel(ragProperties.getEmbeddingModel())
                    .topK(ragProperties.getTopK())
                    .filter(filter)
                    .history(ragProperties.isConversationalEnabled() ? request.getHistory() : null)
                    .includeTrace(Boolean.TRUE)
                    .build();
            RagResult result = buildRagService().search(query);

            // ⑤ 上下文组装（带 [S1][S2] 引用标记）
            String context = result.getContext() == null ? "" : result.getContext();
            boolean degraded = context.trim().isEmpty();

            // trace：召回原始顺序 + 重排后顺序
            RagTrace trace = result.getTrace();
            List<RagHit> retrievedSrc = (trace != null && trace.getRetrievedHits() != null)
                    ? trace.getRetrievedHits() : result.getHits();
            List<RagHit> rerankedSrc = (trace != null && trace.getRerankedHits() != null)
                    ? trace.getRerankedHits() : result.getHits();
            List<Map<String, Object>> retrievedHits = mapHits(retrievedSrc, false);
            List<Map<String, Object>> rerankedHits = mapHits(rerankedSrc, true);

            // 引用溯源
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

            // ⑥ 生成（GLM，拒答约束）——拿回 response 以提取 token 用量
            AnthropicChatCompletionResponse genResp = generate(request.getQuestion(), context, degraded);
            String answer = extractText(genResp);

            // token 用量回填 trace（SDK 2.4.1 数据通道：RagTrace.generationUsage）
            AnthropicUsage usage = genResp == null ? null : genResp.getUsage();
            if (trace != null && usage != null) {
                trace.setGenerationUsage(RagGenerationUsage.builder()
                        .model(ragProperties.getGlmModel())
                        .inputTokens(usage.getInputTokens())
                        .outputTokens(usage.getOutputTokens())
                        .totalTokens(usage.getInputTokens() + usage.getOutputTokens())
                        .build());
            }

            // ⑦ 在线评估（LLM-as-judge：faithfulness/contextRelevance/answerRelevance）。
            //  非致命：judge 挂了（网络/余额/JSON 解析）不影响主答，scores 留空。
            Map<String, Object> scores = null;
            if (onlineEvaluator != null) {
                try {
                    RagJudgeEvaluation eval = onlineEvaluator.evaluate(result, answer);
                    scores = new LinkedHashMap<String, Object>();
                    scores.put("faithfulnessScore", eval.getFaithfulnessScore());
                    scores.put("contextRelevanceScore", eval.getContextRelevanceScore());
                    scores.put("answerRelevanceScore", eval.getAnswerRelevanceScore());
                    scores.put("reason", eval.getReason());
                } catch (Exception ex) {
                    log.warn("online eval failed (non-fatal, answer still returned): {}", ex.getMessage());
                }
            }

            RagAnswer ragAnswer = RagAnswer.builder()
                    .answer(answer)
                    .references(references)
                    .hitCount(result.getHits() == null ? 0 : result.getHits().size())
                    .degraded(degraded)
                    .cached(false)
                    .rewrittenQuery(rewritten)
                    .retrievedHits(retrievedHits)
                    .rerankedHits(rerankedHits)
                    .context(context)
                    .queryPlan(trace != null ? trace.getQueryPlan() : null)
                    .planningDurationMs(trace != null ? trace.getPlanningDurationMs() : 0L)
                    .retrieveDurationMs(trace != null ? trace.getRetrieveDurationMs() : 0L)
                    .rerankDurationMs(trace != null ? trace.getRerankDurationMs() : 0L)
                    .assembleDurationMs(trace != null ? trace.getAssembleDurationMs() : 0L)
                    .totalDurationMs(trace != null ? trace.getTotalDurationMs() : 0L)
                    .generationModel(ragProperties.getGlmModel())
                    .inputTokens(usage != null ? usage.getInputTokens() : null)
                    .outputTokens(usage != null ? usage.getOutputTokens() : null)
                    .scores(scores)
                    .build();
            answerCache.put(cacheKey, ragAnswer);
            return ragAnswer;
        } catch (Exception ex) {
            // RAG 链路任一步失败（rewrite / retrieve 整体挂 / rerank 挂 / generate 挂）：
            // Hybrid 子路容错已在 SDK 层（PR #179），这里兜整体失败 —— 不让用户看到 500 或幻觉
            log.warn("RAG ask failed, returning degraded answer: {}", ex.getMessage());
            return RagAnswer.builder()
                    .answer("抱歉，知识服务暂时不可用，请稍后重试或联系人工客服。")
                    .references(Collections.<ReferenceItem>emptyList())
                    .hitCount(0)
                    .degraded(true)
                    .degradedReason(ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .cached(false)
                    .retrievedHits(Collections.<Map<String, Object>>emptyList())
                    .rerankedHits(Collections.<Map<String, Object>>emptyList())
                    .context("")
                    .build();
        }
    }

    /**
     * 按 {@code rag.reranker} 配置选重排器——这就是"reranker 取舍"的开关：
     * <ul>
     *   <li><b>none</b>：不重排，直接用检索分数排序（HybridRetriever 的 RRF 融合本身就是不错的排序，
     *       成本敏感 / 召回质量已够时选这个，省一次模型调用）</li>
     *   <li><b>jina</b>：专用 rerank 模型（Jina），精度最高，多一次外部 API 调用（成本+延迟）</li>
     *   <li><b>llm</b>（默认）：用 GLM 对召回结果打分重排，本地无专用 rerank 模型时的兜底</li>
     * </ul>
     * 不是每条链路都需要 reranker——这是 demo 把它做成可选配置的原因。
     */
    private Reranker resolveReranker(AiService aiService) {
        String mode = ragProperties.getReranker() == null ? "llm" : ragProperties.getReranker().trim().toLowerCase();
        if ("none".equals(mode)) {
            return new NoopReranker();
        }
        if ("jina".equals(mode)) {
            return aiService.getModelReranker(PlatformType.JINA, ragProperties.getJinaRerankModel());
        }
        return new LlmReranker(this.messagesService, ragProperties.getGlmModel(), ragProperties.getTopK());
    }

    /** 暴露缓存统计（命中率/大小），给可观测用。 */
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("size", answerCache.estimatedSize());
        stats.put("hitRate", answerCache.stats().hitRate());
        stats.put("requestCount", answerCache.stats().requestCount());
        return stats;
    }

    private List<Map<String, Object>> mapHits(List<RagHit> hits, boolean reranked) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (hits == null) {
            return out;
        }
        for (RagHit h : hits) {
            if (h == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sourceName", h.getSourceName());
            m.put("sectionTitle", h.getSectionTitle());
            if (reranked) {
                if (h.getRerankScore() != null) {
                    m.put("rerankScore", h.getRerankScore());
                }
            } else if (h.getScore() != null) {
                m.put("score", h.getScore());
            }
            out.add(m);
        }
        return out;
    }

    private List<String> permissionTagsFor(String tenant) {
        if ("premium".equals(tenant)) {
            return Arrays.asList("public", "premium");
        }
        return Collections.singletonList("public");
    }

    private AnthropicChatCompletionResponse generate(String question, String context, boolean degraded) throws Exception {
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
        return messagesService.messages(req);
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
