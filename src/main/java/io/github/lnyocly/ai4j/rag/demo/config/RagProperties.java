package io.github.lnyocly.ai4j.rag.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * demo 业务配置（非 ai4j starter 配置）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 知识库 dataset（PgVector 的硬边界 + 多租户隔离单位）。 */
    private String dataset = "ecommerce-kb";

    /** Ollama embedding 模型（Qwen3-Embedding-0.6B，中文好，1024 维）。 */
    private String embeddingModel = "hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:latest";

    /** 向量维度（要与建表 vector(N) 的 N 一致）。 */
    private int vectorDimension = 1024;

    /** GLM 生成模型（走 Anthropic Messages 协议）。 */
    private String glmModel = "glm-4.6";

    /** 检索 topK。 */
    private int topK = 5;

    /** 重排策略：none（不重排，靠检索排序）/ jina（专用 rerank 模型）/ llm（GLM 打分兜底）。 */
    private String reranker = "llm";

    /** Jina rerank 模型（reranker=jina 时用）。 */
    private String jinaRerankModel = "jina-reranker-v2-base-multilingual";

    /** 是否启用 Query Planning（ModelRagQueryPlanner：GLM 生成 query variants 多路召回融合，替代 QueryRewriteService）。 */
    private boolean plannerEnabled = false;

    /** Query Planning 最大 variant 数（planner-enabled=true 时生效）。 */
    private int plannerMaxVariants = 3;
}
