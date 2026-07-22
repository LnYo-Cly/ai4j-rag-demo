package io.github.lnyocly.ai4j.rag.demo.retriever;

import io.github.lnyocly.ai4j.rag.RagHit;
import io.github.lnyocly.ai4j.rag.RagQuery;
import io.github.lnyocly.ai4j.rag.Retriever;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装饰一个 {@link Retriever}，给每次检索强制注入租户权限过滤（permissionTag）。
 *
 * <p>为什么需要它：SDK 的 {@code RagTool} 内部构造 {@link RagQuery} 时不带 filter
 * （RagTool 没有 filter 字段），导致 agent 路径的 {@code knowledge_search} 会跨租户召回——
 * 比如普通租户经 agent 能拿到 premium 专属文档。本类把 permissionTag filter 强制注入每次
 * 检索，让 agent 路径和主问答路径（{@code /api/rag/ask} 的 RagQueryService 显式带 filter）
 * 在多租户隔离上对齐。
 *
 * <p>用法：{@code TenantFilteredRetriever.forTenant(denseRetriever, tenantId)} 包一层，
 * 再交给 {@code DefaultRagService} → {@code RagTool}。
 */
public class TenantFilteredRetriever implements Retriever {

    private final Retriever delegate;
    private final Map<String, Object> forcedFilter;

    public TenantFilteredRetriever(Retriever delegate, Map<String, Object> forcedFilter) {
        this.delegate = delegate;
        this.forcedFilter = forcedFilter;
    }

    /** 按租户构造：premium 可见 public+premium，其余只可见 public（与 RagQueryService 一致）。 */
    public static TenantFilteredRetriever forTenant(Retriever delegate, String tenantId) {
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("permissionTag", permissionTagsFor(tenantId));
        return new TenantFilteredRetriever(delegate, filter);
    }

    @Override
    public List<RagHit> retrieve(RagQuery query) throws Exception {
        // 合并：原始 query 的 filter + 强制租户 filter（forced 覆盖同名 key）
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        if (query.getFilter() != null) {
            merged.putAll(query.getFilter());
        }
        if (forcedFilter != null) {
            merged.putAll(forcedFilter);
        }
        // RagQuery 没有 toBuilder，按字段重建（把合并后的 filter 塞回去）
        return delegate.retrieve(RagQuery.builder()
                .query(query.getQuery())
                .dataset(query.getDataset())
                .embeddingModel(query.getEmbeddingModel())
                .topK(query.getTopK())
                .finalTopK(query.getFinalTopK())
                .filter(merged.isEmpty() ? null : merged)
                .history(query.getHistory())
                .delimiter(query.getDelimiter())
                .includeCitations(query.isIncludeCitations())
                .includeTrace(query.isIncludeTrace())
                .build());
    }

    private static List<String> permissionTagsFor(String tenant) {
        if ("premium".equals(tenant)) {
            return Arrays.asList("public", "premium");
        }
        return Collections.singletonList("public");
    }
}
