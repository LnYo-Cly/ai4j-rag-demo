package io.github.lnyocly.ai4j.rag.demo.config;

import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 启动时确保 PgVector 的扩展、表、HNSW 索引就绪。
 * 用 @PostConstruct（在 bean 初始化阶段、早于所有 ApplicationRunner）建表，
 * 保证 KnowledgeIngestionService 的 ApplicationRunner 摄入前表一定存在。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PgVectorSchemaInitializer {

    private final RagProperties ragProperties;

    @Value("${ai.vector.pgvector.jdbc-url}") private String jdbcUrl;
    @Value("${ai.vector.pgvector.username}") private String username;
    @Value("${ai.vector.pgvector.password}") private String password;
    @Value("${ai.vector.pgvector.table-name}") private String tableName;

    @PostConstruct
    public void initSchema() {
        try (Connection c = DriverManager.getConnection(jdbcUrl, username, password);
             Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE TABLE IF NOT EXISTS " + tableName
                    + " (id text PRIMARY KEY, dataset text, content text, metadata jsonb,"
                    + " embedding vector(" + ragProperties.getVectorDimension() + "))");
            s.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_emb_idx ON " + tableName
                    + " USING hnsw (embedding vector_cosine_ops)");
            log.info("PgVector schema ready: {} (dim={})", tableName, ragProperties.getVectorDimension());
        } catch (Exception e) {
            throw new RuntimeException("Failed to init PgVector schema: " + e.getMessage(), e);
        }
    }
}
