# ai4j + PgVector + Ollama：用更少的代码，跑通企业级 RAG

> 一篇《Spring Boot + Spring AI Alibaba + Redis 企业级 RAG 实战》前段时间在朋友圈刷屏。文章很扎实，把"为什么 RAG demo 一上线就失效"讲透了。但它选的 Redis Stack、手写的 `EmbeddingGateway` / `VectorRepository` / 检索编排，对很多团队其实门槛不低——你得先装一套 Redis Stack（Windows 上甚至只能走 Docker），还得自己写不少基础设施。
>
> 本文换一个组合：**ai4j + PgVector + Ollama**，跑通同一条 RAG 链路，看看应用层代码能精简到什么程度。结论先行：核心编排（摄入 + 检索 + 生成）只要几十行。

## 一、选型：为什么是 ai4j + PgVector + Ollama

| 角色 | 那篇选型 | 本文选型 | 理由 |
|---|---|---|---|
| AI SDK | Spring AI Alibaba | **ai4j** | 统一 `VectorStore` / RAG / 多协议 LLM，RAG 组件开箱即用 |
| 向量库 | Redis Stack | **PgVector** | 大多数团队已有 PostgreSQL；过滤能力直接用 SQL；不用额外装 Redis Stack |
| Embedding | DashScope（云） | **Ollama + Qwen3-Embedding** | 本地、免费、中文好；不消耗云额度 |
| 生成 | 通义千问 | **GLM** | 走 ai4j 的 Anthropic Messages 协议 |

不是 Redis 不好，而是：**如果你已经有 Postgres，再装一套 Redis Stack 只为存向量，有点重。** PgVector 让你在熟悉的数据库里就把向量检索办了，元数据过滤直接用 SQL `where`——比 RediSearch 的 query DSL 自然得多。这也是原文自己说的"Redis 是起步方案，大规模要演进到 ES/Milvus"——既然要演进，不如一开始就用更通用的 Postgres。

## 二、两条链路（和那篇一模一样）

```
离线摄入：knowledge/*.md → 切块 → Ollama embedding → PgVector upsert
在线问答：问题 → embedding → PgVector KNN 检索 → 上下文组装 → GLM 生成 → 答案 + 引用溯源
```

唯一区别：那篇这些步骤大多手写，本文大量复用 ai4j 的现成组件。

## 三、代码：ai4j 让 RAG 多简洁

### 3.1 配置（`application.yml`）

```yaml
ai:
  vector:
    pgvector:
      enabled: true
      jdbc-url: jdbc:postgresql://localhost:5432/postgres
      username: postgres
      password: postgres
      table-name: ai4j_rag_demo
  ollama:
    api-host: http://localhost:11434/
  anthropic:
    api-host: https://open.bigmodel.cn/api/anthropic/   # GLM coding-plan 兼容入口
    api-key: ${GLM_API_KEY}
```

ai4j 的 Spring Boot starter 自动装配 PgVector / Ollama / Anthropic 客户端。**实测在 Spring Boot 3.2 下可用**（starter 虽编译于 SB 2.3 / Java 8，但运行时与 SB 3 兼容）。

### 3.2 离线摄入（启动时自动跑）

```java
IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
pipeline.ingest(IngestionRequest.builder()
        .dataset(ragProperties.getDataset())
        .embeddingModel(ragProperties.getEmbeddingModel())
        .source(IngestionSource.text(content))           // 知识文本
        .document(RagDocument.builder().documentId(docId).sourceName(filename).build())
        .build());
```

ai4j 的 `IngestionPipeline` 把"加载 → 切块 → embedding → upsert"全包了，应用层只管"给文档 + dataset"。`documentId` 用文件名确定性派生，重启幂等（`upsert` 同 chunkId，不重复累积）。

### 3.3 在线问答：检索 + 生成

```java
// 检索 + 上下文组装（一步到位：embed query → KNN → rerank → assemble context）
RagResult result = ragService.search(RagQuery.builder()
        .query(question)
        .dataset(dataset)
        .embeddingModel(embeddingModel)
        .topK(5)
        .build());
String context = result.getContext();          // 组装好的上下文
List<RagCitation> citations = result.getCitations();   // 引用

// 生成（GLM via Anthropic Messages）
AnthropicChatCompletion req = new AnthropicChatCompletion();
req.setModel("glm-4.6");
req.setSystem("你是企业电商知识助手。严格根据参考资料回答……");
req.setMessages(Collections.singletonList(new AnthropicMessage("user", context + "\n\n问题：" + question)));
req.setMaxTokens(1024);
String answer = extractText(messagesService.messages(req));
```

`RagService.search` 一步完成"embedding query → KNN 检索 → rerank → 上下文组装"，返回 `context` + `citations`。生成就是一次 `IMessagesService.messages` 调用。**整条在线链路，应用层核心就这么几行。**

## 四、真实跑通：秒杀退款问答

启动后摄入 `refund-rules.md` / `after-sales.md` / `logistics.md` 三个知识文档，问一个那篇原文同款的问题：

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json;charset=UTF-8" \
  -d '{"question":"秒杀商品签收后还能申请退款吗？"}'
```

GLM 的真实回答：

> 关于秒杀商品在签收后的退款政策：
> 1. **不支持 7 天无理由退款**——参与秒杀活动的特殊商品不支持 7 天无理由退款（S1）。
> 2. **存在质量问题可申请退款**——不受活动商品限制约束，可按售后规则申请（S1、S2）。
> 3. **时效**——质量问题售后需在签收后 15 天内申请（S2）；退款审核 1-3 个工作日，到账 3-5 个工作日（S1）。
>
> `references`: [refund-rules.md（秒杀活动商品退款限制）, after-sales.md（质量问题售后）]

注意几个细节，恰好印证那篇讲的"企业级"要点：

- **检索优先于生成**：答案每个结论都能溯源到 `references`，不是模型瞎编
- **拒答约束**：system prompt 明确"资料不足就说无法确认，不要编造"
- **多文档组合**：秒杀规则（refund-rules）+ 质量售后（after-sales）被一起召回并组织成一个可解释的答案——正是原文强调的"不是只召回最相似一段，而是把活动例外规则和通用规则一起组织"

## 五、和那篇的对比

| 维度 | 那篇（手写） | 本文（ai4j） |
|---|---|---|
| 应用层核心代码 | ~300 行（EmbeddingGateway / VectorRepository / RewriteService / RerankService / ContextAssembler / ApplicationService） | **~80 行**（KnowledgeIngestionService + RagQueryService + Controller） |
| 基础设施依赖 | Redis Stack（RediSearch 模块） | PostgreSQL + pgvector（多数团队已有） |
| Embedding | 云 API（消耗额度） | 本地 Ollama（免费） |
| Windows 跑起来 | 要 Docker（Redis Stack 无原生 Windows 版） | Postgres + Ollama 都有原生 Windows |

声明：这不是说 ai4j "吊打" Spring AI Alibaba——后者生态更全、与 Spring 集成更深。本文只说明：**如果你的诉求是"用最少的代码、最通用的组件跑通企业级 RAG"，ai4j + PgVector 是一条非常务实的路径。**

## 六、小结：SDK 统一主链，治理留给工程层

那篇原文讲清了一件事：**RAG 不是"向量库 + 大模型"的拼装，而是"检索 + 生成 + 治理"的系统工程。** 这个判断完全对。

本文的补充是：这个系统工程的"检索 + 生成"部分，ai4j 已经替你封装好了——`IngestionPipeline` 管摄入，`RagService` 管检索+组装，`IMessagesService` / `IChatService` 管生成。应用层只需要做业务编排。

剩下的"治理"（缓存 / 限流 / 熔断 / 可观测 / 多租户权限 / 索引版本）——ai4j 不替你做，因为那本就该是应用层（Spring Boot + Resilience4j + Micrometer）的事，不是 SDK 的事。**这和那篇的结论一致：SDK 统一主链调用，存储现实与工程治理仍然存在。**

> 完整 demo：[github.com/LnYo-Cly/ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)
> ai4j 本体：[github.com/LnYo-Cly/ai4j](https://github.com/LnYo-Cly/ai4j)
