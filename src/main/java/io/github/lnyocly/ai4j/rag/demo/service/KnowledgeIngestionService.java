package io.github.lnyocly.ai4j.rag.demo.service;

import io.github.lnyocly.ai4j.rag.RagDocument;
import io.github.lnyocly.ai4j.rag.RagHit;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionPipeline;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionRequest;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionResult;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionSource;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.VectorRecord;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 启动时把知识摄入 PgVector，按目录区分多租户权限标签：
 *   classpath:knowledge/*.md         -> permissionTag = public   (所有租户可见)
 *   classpath:knowledge/premium/*.md -> permissionTag = premium  (仅 premium 租户)
 *
 * 同时把每个 chunk 收集到 InMemoryCorpus（供 Bm25Retriever / 评估端点用）。
 * documentId 用文件名确定性派生（nameUUIDFromBytes），保证重启幂等（upsert 同 chunkId）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final AiService aiService;
    private final PgVectorStore vectorStore;
    private final RagProperties ragProperties;
    private final InMemoryCorpus inMemoryCorpus;

    @Bean
    public ApplicationRunner ingestKnowledge() {
        return args -> {
            IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
            int total = 0;
            total += ingest(pipeline, "classpath:knowledge/*.md", "public");
            total += ingest(pipeline, "classpath:knowledge/premium/*.md", "premium");
            log.info("Knowledge ingestion done: {} chunks total", total);
        };
    }

    private int ingest(IngestionPipeline pipeline, String pattern, String permissionTag) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        int total = 0;
        for (Resource resource : resources) {
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String docId = UUID.nameUUIDFromBytes(
                    resource.getFilename().getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("permissionTag", permissionTag);
            IngestionResult result = pipeline.ingest(IngestionRequest.builder()
                    .dataset(ragProperties.getDataset())
                    .embeddingModel(ragProperties.getEmbeddingModel())
                    .source(IngestionSource.text(content))
                    .document(RagDocument.builder()
                            .documentId(docId)
                            .sourceName(resource.getFilename())
                            .title(resource.getFilename())
                            .metadata(metadata)
                            .build())
                    .build());

            // 收集 chunk 到内存语料库（供 Bm25Retriever / 评估端点）
            if (result.getRecords() != null) {
                for (VectorRecord record : result.getRecords()) {
                    inMemoryCorpus.add(permissionTag, RagHit.builder()
                            .id(record.getId())
                            .content(record.getContent())
                            .sourceName(resource.getFilename())
                            .build());
                }
            }
            total += result.getUpsertedCount();
            log.info("Ingested [{}] {}: {} chunks", permissionTag, resource.getFilename(), result.getUpsertedCount());
        }
        return total;
    }
}
