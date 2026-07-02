package io.github.lnyocly.ai4j.rag.demo.controller;

import io.github.lnyocly.ai4j.rag.Bm25Retriever;
import io.github.lnyocly.ai4j.rag.DefaultRagContextAssembler;
import io.github.lnyocly.ai4j.rag.DefaultRagService;
import io.github.lnyocly.ai4j.rag.DenseRetriever;
import io.github.lnyocly.ai4j.rag.HybridRetriever;
import io.github.lnyocly.ai4j.rag.NoopReranker;
import io.github.lnyocly.ai4j.rag.RagEvaluation;
import io.github.lnyocly.ai4j.rag.RagEvaluator;
import io.github.lnyocly.ai4j.rag.RagHit;
import io.github.lnyocly.ai4j.rag.RagQuery;
import io.github.lnyocly.ai4j.rag.RagResult;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.RrfFusionStrategy;
import io.github.lnyocly.ai4j.rag.Retriever;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.service.InMemoryCorpus;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索质量评估端点：GET /api/rag/evaluate
 *
 * 用 HybridRetriever（DenseRetriever 向量召回 + Bm25Retriever 关键词召回 + RRF 融合）
 * 跑一份离线评测集，用 ai4j 的 RagEvaluator 算 Recall@K / MRR / NDCG。
 *
 * 展示 ai4j 的混合检索 + 融合策略 + 质量评估三件套——博客十六章讲的能力，这里真实跑。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class EvaluationController {

    private final AiService aiService;
    private final PgVectorStore vectorStore;
    private final InMemoryCorpus inMemoryCorpus;
    private final RagProperties ragProperties;

    /** 评测集：{query, 期望命中的知识文件名}。chunkId 由文件名确定性派生，所以可静态声明。 */
    private static final String[][] EVAL_SET = {
            {"秒杀商品签收后还能申请退款吗？", "refund-rules.md"},
            {"标准配送一般几天能送达？", "logistics.md"},
            {"质量问题多久内能申请售后？", "after-sales.md"},
    };

    @GetMapping("/evaluate")
    public Map<String, Object> evaluate() throws Exception {
        Retriever dense = new DenseRetriever(
                aiService.getEmbeddingService(PlatformType.OLLAMA), vectorStore);
        List<RagHit> corpus = inMemoryCorpus.publicCorpus();

        // 混合检索：向量 + 内存 BM25 + RRF 融合（corpus 为空时退化为纯向量）
        Retriever hybrid = corpus.isEmpty()
                ? dense
                : new HybridRetriever(Arrays.asList(dense, new Bm25Retriever(corpus)), new RrfFusionStrategy());
        RagService svc = new DefaultRagService(hybrid, new NoopReranker(), new DefaultRagContextAssembler());

        RagEvaluator evaluator = new RagEvaluator();
        List<Map<String, Object>> perQuery = new ArrayList<Map<String, Object>>();
        double sumRecall = 0, sumMrr = 0, sumNdcg = 0;
        for (String[] c : EVAL_SET) {
            String query = c[0];
            String relevantId = chunkIdFor(c[1]);
            RagResult r = svc.search(RagQuery.builder()
                    .query(query)
                    .dataset(ragProperties.getDataset())
                    .embeddingModel(ragProperties.getEmbeddingModel())
                    .topK(5)
                    .build());
            RagEvaluation e = evaluator.evaluate(r.getHits(), Collections.singletonList(relevantId), 5);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("query", query);
            row.put("expected", c[1]);
            row.put("recallAt5", e.getRecallAtK());
            row.put("mrr", e.getMrr());
            row.put("ndcg", e.getNdcg());
            row.put("hitIds", topHitSources(r.getHits()));
            perQuery.add(row);
            sumRecall += e.getRecallAtK();
            sumMrr += e.getMrr();
            sumNdcg += e.getNdcg();
        }

        int n = EVAL_SET.length;
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("retriever", corpus.isEmpty() ? "dense" : "hybrid(dense+bm25+rrf)");
        summary.put("cases", n);
        summary.put("meanRecallAt5", round(sumRecall / n));
        summary.put("meanMrr", round(sumMrr / n));
        summary.put("meanNdcg", round(sumNdcg / n));
        summary.put("perQuery", perQuery);
        return summary;
    }

    private List<String> topHitSources(List<RagHit> hits) {
        List<String> sources = new ArrayList<String>();
        if (hits == null) {
            return sources;
        }
        for (int i = 0; i < Math.min(5, hits.size()); i++) {
            sources.add(hits.get(i).getSourceName());
        }
        return sources;
    }

    /** chunkId = documentId#chunk-0（documentId 由文件名确定性派生，和摄入时一致）。 */
    private String chunkIdFor(String filename) {
        String docId = UUID.nameUUIDFromBytes(filename.getBytes(StandardCharsets.UTF_8)).toString();
        return docId + "#chunk-0";
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
