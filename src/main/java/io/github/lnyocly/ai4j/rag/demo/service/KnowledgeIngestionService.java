package io.github.lnyocly.ai4j.rag.demo.service;

import io.github.lnyocly.ai4j.rag.RagDocument;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionPipeline;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionRequest;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionResult;
import io.github.lnyocly.ai4j.rag.ingestion.IngestionSource;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 启动时把 classpath:knowledge/*.md 摄入 PgVector。
 * documentId 用文件名确定性派生（nameUUIDFromBytes），保证重启幂等（upsert 同 chunkId）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final AiService aiService;
    private final PgVectorStore vectorStore;
    private final RagProperties ragProperties;

    @Bean
    @Order(2)
    public ApplicationRunner ingestKnowledge() {
        return args -> {
            IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:knowledge/*.md");
            int total = 0;
            for (Resource resource : resources) {
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String docId = UUID.nameUUIDFromBytes(
                        resource.getFilename().getBytes(StandardCharsets.UTF_8)).toString();
                IngestionResult result = pipeline.ingest(IngestionRequest.builder()
                        .dataset(ragProperties.getDataset())
                        .embeddingModel(ragProperties.getEmbeddingModel())
                        .source(IngestionSource.text(content))
                        .document(RagDocument.builder()
                                .documentId(docId)
                                .sourceName(resource.getFilename())
                                .title(resource.getFilename())
                                .build())
                        .build());
                total += result.getUpsertedCount();
                log.info("Ingested {}: {} chunks", resource.getFilename(), result.getUpsertedCount());
            }
            log.info("Knowledge ingestion done: {} docs, {} chunks total", resources.length, total);
        };
    }
}
