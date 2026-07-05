package io.github.lnyocly.ai4j.rag.demo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    /** LLM 生成的答案。 */
    private String answer;
    /** 引用来源（溯源用）。 */
    private List<ReferenceItem> references;
    /** 本次检索命中的 chunk 数。 */
    private int hitCount;
    /** 是否降级（检索为空时直接 LLM 兜底；或 RAG 链路异常时整体兜底）。 */
    private boolean degraded;
    /** 降级原因（异常降级时填「异常类型: message」；检索为空时为 null）。 */
    private String degradedReason;
    /** 是否命中答案缓存。 */
    private boolean cached;
    /** 查询改写后的表达（Query Rewrite，展示改写前后对比）。 */
    private String rewrittenQuery;

    // ---- 完整执行 trace（每一步都可见，便于排障/可观测）----

    /** 召回原始顺序（retrieve 后、rerank 前）：每条 {sourceName, sectionTitle, score}。 */
    private List<Map<String, Object>> retrievedHits;
    /** 重排后顺序（rerank 后）：每条 {sourceName, sectionTitle, rerankScore}。 */
    private List<Map<String, Object>> rerankedHits;
    /** 组装给模型的上下文（带 [S1][S2] 引用标记）。 */
    private String context;

    /** Query Planning 结果（variants + fallback，planner 开时填；来自 SDK RagTrace.queryPlan）。 */
    private io.github.lnyocly.ai4j.rag.RagQueryPlan queryPlan;

    /** Query Planning 耗时（毫秒，planner 开时填）。 */
    private long planningDurationMs;
}
