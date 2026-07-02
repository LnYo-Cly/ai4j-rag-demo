package io.github.lnyocly.ai4j.rag.demo.service;

import io.github.lnyocly.ai4j.rag.RagHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存 chunk 语料库（供 Bm25Retriever / 评估端点用）。
 *
 * ai4j 的 Bm25Retriever 是纯内存实现（构造时建倒排索引），不需要 ES。
 * 摄入时把每个 chunk 收集进来；评估端点用它构建 Bm25Retriever，和 DenseRetriever 组成 HybridRetriever。
 *
 * 生产规模下 BM25 应换成 ES/OpenSearch（同样作为 Retriever 实现），主链不变。
 */
@Component
public class InMemoryCorpus {

    private final List<RagHit> publicHits = new ArrayList<RagHit>();
    private final List<RagHit> premiumHits = new ArrayList<RagHit>();

    public void add(String permissionTag, RagHit hit) {
        if ("premium".equals(permissionTag)) {
            premiumHits.add(hit);
        } else {
            publicHits.add(hit);
        }
    }

    /** public 语料（评估端点用：只评估公开知识的检索质量）。 */
    public List<RagHit> publicCorpus() {
        return new ArrayList<RagHit>(publicHits);
    }
}
