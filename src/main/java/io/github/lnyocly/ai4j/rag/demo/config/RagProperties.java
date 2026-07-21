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

    /** 是否启用 Hybrid 检索（Dense + 内存 BM25 + RRF 融合；BM25 用 public corpus，多租户不泄漏）。 */
    private boolean hybridEnabled = false;

    /** context 组装 token 预算（TokenAwareRagContextAssembler，超则停追加；0=用默认 DefaultRagContextAssembler）。 */
    private int maxContextTokens = 0;

    /** token 计数模型名（jtokkit encoding 近似，国产模型传 gpt-4 即可）。 */
    private String contextModel = "gpt-4";

    /** 是否启用 online evaluation（LLM-as-judge 给每答打 faithfulness/relevance 分）。 */
    private boolean onlineEvalEnabled = false;

    /** 是否启用 Conversational RAG（RagQuery 带 chat history，planner rewrite 时消解 follow-up 指代）。 */
    private boolean conversationalEnabled = false;
}
