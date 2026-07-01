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
}
