# 为你的 JDK8 老项目，增加企业级的 RAG 系统

> 很多团队的核心业务还跑在 JDK 8 + Spring Boot 2.x 上——升级 JDK 17 + SB 3 牵一发而动全身，短期不动；但 AI/RAG 又是必须补的能力。本文就用 **Spring Boot 2.7（JDK 8 末代 LTS）+ ai4j + PostgreSQL/pgvector + 本地 Ollama + GLM**，给一个 JDK8 老项目加上一整套企业级 RAG：摄入、Query Rewrite、混合检索、重排、多租户、评估、缓存、可观测 trace、引用溯源——配套 [开箱即用的 demo](https://github.com/LnYo-Cly/ai4j-rag-demo)，每个能力都真实跑过，下面所有运行结果都是 demo 实际输出。

> 本文用一套更轻、更通用的技术栈——Java AI SDK **ai4j** + 你大概率已经有的 **PostgreSQL/pgvector** + 本地免费的 **Ollama embedding** + **GLM** 生成——从原理、架构、12 条设计原则、生产代码、高并发、多租户、可观测、部署一路讲到真实案例与检索质量评估。配套 [开箱即用的 demo](https://github.com/LnYo-Cly/ai4j-rag-demo) 里，**每一个能力都真实跑过**，下面所有运行结果都是 demo 实际输出，不是杜撰。

---

## 一、为什么很多 RAG Demo 一上线就失效

很多团队第一版做得很快：选 embedding 模型、读 PDF/Word、切块写进向量库、查询 TopK、塞进 prompt 调大模型。十几分钟跑起来，演示效果不错。但这类 demo 只能说明"RAG 可以做出来"，不能说明"RAG 可以稳定上线"。一旦进入真实业务：

- 文档量涨到几十万 chunk，索引构建、内存占用、召回噪声同时上升
- 用户问题变复杂，纯向量召回漂移（尤其订单号、错误码、规则编号这类强关键词）
- 高并发下 embedding、重排、LLM 互相争抢资源，RT 抖动
- 知识更新后新旧索引混用，结果不一致
- 多租户过滤放后面，越权召回和串库
- 出现错误答案时无法定位是切块、召回、重排还是 prompt 的问题

根因：**RAG 不是"向量库 + 大模型"的简单拼装，而是"检索系统 + 生成系统 + 工程治理系统"的组合工程。** 真正的企业级 RAG 必须同时解决：检索质量稳、在线链路快、离线入库可扩展、整体系统可治理。

## 二、本文要解决的核心问题

1. 原理层：embedding、向量检索、RAG pipeline 的本质
2. 架构层：离线摄入 + 在线问答两条链路如何解耦
3. 工程层：高并发、缓存、限流、熔断、降级、索引演进、多租户隔离
4. 代码层：接近生产可用的 Java 代码（配套 demo 可直接跑）

## 三、业务场景：电商智能客服知识引擎

假设你负责一个电商智能客服系统：日均会话 100 万+、峰值 QPS 2000+、知识类型涵盖退款/售后/物流/营销/商家规范，要求多租户隔离、来源溯源、故障降级。

## 四、先把原理讲透：embedding 与 RAG 的本质

### 4.1 embedding
把文本映射到高维稠密向量空间，让语义相近的文本距离更近。查询时把问题也转向量，用相似度（Cosine / IP / L2）找最近邻 chunk。本文用 Qwen3-Embedding-0.6B（1024 维，中文好）。

### 4.2 RAG 的本质不是"补知识"而是"补证据"
企业要的是当前版本制度、生效政策、私域文档、带权限边界的内部信息——这些不该靠模型参数记忆，而该靠检索得到的外部上下文。所以成熟 pipeline 是多段式：`Query Rewrite → Retrieve（向量+关键词+融合）→ Rerank → Context Build → Generate → Post Process（引用/脱敏）`。

### 4.3 两条主链路
**离线**：`采集 → 解析（Tika/OCR）→ 切块 → 元数据 → embedding → 建索引 → 发布`
**在线**：`鉴权 → 租户识别 → 改写 → 混合召回 → 权限过滤 → 重排 → Prompt 组装 → 生成 → 引用标注 → 缓存`
两条必须解耦——入库重 CPU/IO，在线要低延迟。

## 五、为什么选择 ai4j + PgVector + Ollama

### 5.1 ai4j
- **三套 LLM 协议并列**：`IChatService`（OpenAI Chat）/ `IResponsesService`（OpenAI Responses）/ `IMessagesService`（Anthropic Messages）。GLM coding-plan key 走 Anthropic 兼容入口，直接 `IMessagesService` 原生协议。
- **RAG 组件开箱即用**：`IngestionPipeline`、`DenseRetriever`/`Bm25Retriever`/`HybridRetriever`（RRF/RSF/DBSF 融合）、`ModelReranker`/`Reranker` 接口、`RagService`、`RagEvaluator`（Recall/MRR/NDCG）、`RagTrace`+`RagScoreDetail`。
- **向量后端统一**：`VectorStore` SPI 收口 Pinecone/Qdrant/Milvus/PgVector/Redis Stack，换后端主链不改。

### 5.2 PgVector
复用已有 PostgreSQL（`CREATE EXTENSION vector`），过滤用 SQL where（JSONB/列/JOIN），表达力远强于专用向量库的 query DSL；一库多用。大规模再迁专用库，主链不改。

### 5.3 Ollama embedding
本地、免费、零网络延迟、中文好。生产高峰可切云端（`IEmbeddingService` 换实现）。

### 5.4 plugin 生态（extension-api）：core 精简 + 能力扩展

ai4j 不把所有能力塞进 core，而是提供 `ai4j-extension-api` 扩展点（plugin 机制，`ServiceLoaderExtensionLoader` SPI 自动发现，加 jar 即生效）：

- `AgentListener` / `ObserveHook`：订阅 agent 事件（做 OTel/Micrometer 桥接，发 trace 到外部可观测平台）
- `ToolInterceptor` / `PromptInterceptor`：拦截工具调用 / prompt（审批、改写、block）
- `ExtensionGuardrail`：输入/输出护栏（PII 脱敏、prompt injection 防护）
- `PromptRegistry`：prompt 资源管理（版本化）
- `VectorStore` / `IEmbeddingService` / `Reranker` / `DocumentLoader` 接口：实现新后端 / 新模型 / OCR

**core 只管 RAG/agent 主链 + 数据通道（`RagTrace` / `IoCaptureSink`）**，其余能力（OTel 桥接 / Weaviate / guardrail / embedding cache / 多模态）走 plugin —— 对比参考文章的 Spring AI（治理靠 Spring 生态外接），ai4j 把扩展点内建为 extension-api，plugin 是一等公民（已有例子：`ai4j-plugin-ask-user`）。这意味着本文没讲的治理（限流/熔断/灾备）和安全（脱敏/injection），都能用 plugin 补，不用改 core。

## 六、企业级 RAG 架构设计

```
┌──────────────────────────────────────────────────────────┐
│  ChatController / EvaluationController（接入层）          │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│  RagQueryService（应用编排：缓存→检索→重排→生成→引用）   │
└──────────────────────────────────────────────────────────┘
        │                              │
        ▼                              ▼
┌──────────────────────┐    ┌───────────────────────────┐
│ ai4j RagService       │    │ ai4j IMessagesService      │
│ DenseRetriever(PgVec) │    │ (GLM via Anthropic)        │
│ +LlmReranker(GLM打分) │    │  生成 + LlmReranker 复用    │
│ +ContextAssembler     │    └───────────────────────────┘
└──────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────┐
│ PgVectorStore (PostgreSQL+pgvector) — 向量检索+权限前置过滤│
└──────────────────────────────────────────────────────────┘
        ▲
        │
┌──────────────────────────────────────────────────────────┐
│ IngestionPipeline（离线）：Tika/OCR+切块+Ollama embedding │
│ + InMemoryCorpus（供 Bm25Retriever/评估端点）             │
└──────────────────────────────────────────────────────────┘
```

核心边界：Controller / RagQueryService（在线编排）/ KnowledgeIngestionService（离线摄入）/ EvaluationController（质量评估）/ ai4j 组件（主链）/ PgVectorStore（底层）。

## 七、12 条设计原则与本文落地

1. **检索优先于生成** → `RagService` 收口检索+组装
2. **离线/在线解耦** → `KnowledgeIngestionService` 独立 ApplicationRunner，可改 MQ worker
3. **元数据是一等公民** → `RagChunk`+`RagMetadataKeys`，PgVector JSONB
4. **索引版本化** → PgVector `dataset` 列作版本边界（`ecommerce-kb-v1`/`v2`）
5. **权限过滤前置** → `VectorSearchRequest.filter` 在 KNN 前用 SQL where
6. **模型调用被治理** → 应用层 Resilience4j 包 `IMessagesService`
7. **缓存是基础设施** → ⚠️ ai4j 不提供 RAG 缓存（有意），demo 用 Caffeine（应用层）
8. **不只向量召回** → `HybridRetriever`（Dense+Bm25+RRF），评估端点实测
9. **Prompt 预算受控** → `RagContextAssembler` + token 预算
10. **可观测从第一天** → RAG 级 `RagTrace`、应用级 `cacheStats`、agent 级 `IoCaptureSink`（见十六章三层）
11. **质量评估标准化** → `RagEvaluator`（Recall/MRR/NDCG），评估端点实测
12. **先设计演进路径** → `VectorStore` 抽象，PgVector→Milvus/Qdrant 主链不改

## 八、技术选型

| 维度 | 方案 |
|---|---|
| Web 框架 | Spring Boot 2.7（JDK 8 末代 LTS） |
| AI SDK | ai4j 2.4.2-SNAPSHOT（starter；已发 2.4.1，main 在 2.4.2-SNAPSHOT） |
| Embedding | Ollama + Qwen3-Embedding-0.6B（1024 维） |
| Chat | GLM（Anthropic Messages，`IMessagesService`） |
| 向量库 | PostgreSQL + pgvector |
| 重排 | LlmReranker（GLM 打分；专用 rerank 用 ai4j `ModelReranker` 接 Jina/Doubao） |
| 缓存 | Caffeine（应用层 answer cache） |
| 可观测 | RagTrace + cacheStats + Micrometer（agent 级 ai4j 自带 IoCaptureSink） |

## 九、项目结构

```
ai4j-rag-demo
├── pom.xml
├── README.md
├── docs/blog.md
└── src/main/
    ├── java/io/github/lnyocly/ai4j/rag/demo/
    │   ├── RagDemoApplication.java
    │   ├── config/   (RagProperties, PgVectorSchemaInitializer)
    │   ├── domain/   (ChatRequest, RagAnswer, ReferenceItem)
    │   ├── rerank/   (LlmReranker)
    │   ├── service/  (KnowledgeIngestionService, RagQueryService, InMemoryCorpus)
    │   └── controller/ (ChatController, EvaluationController)
    └── resources/
        ├── application.yml
        └── knowledge/
            ├── *.md           (public：refund/logistics/after-sales)
            └── premium/*.md   (premium：marketing/merchant，仅 premium 租户)
```

### 9.1 知识库（5 个文档，分多租户权限）

**public（所有租户可见）**：
- `refund-rules.md`：退款时效 7 天、秒杀商品退款限制、审核到账
- `logistics.md`：标准配送 2-5 工作日、偏远 5-10、签收认定、节假日
- `after-sales.md`：质量问题售后 15 天、7 天无理由、换货

**premium（仅 premium 租户：运营/风控内部）**：
- `marketing.md`：秒杀价不叠加优惠券、大促时效延长、**内部营销预算**（保密）
- `merchant.md`：入驻条件、违规三级处理、**保证金扣罚**（保密）

## 十、知识摄入架构

### 10.1 不要同步入库
事件驱动：`Upload → Object Storage → MQ → Parser/Chunker/Embedding Worker → PgVector upsert → 发布版本`。

### 10.2 ai4j IngestionPipeline 收口摄入主链
`IngestionPipeline` 内部完成"加载→切块→metadata 富化→批量 embedding→upsert"。内置生产级组件（都在 `io.github.lnyocly.ai4j.rag.ingestion`）：
- `TikaDocumentLoader`：PDF/Word/Excel/HTML 几十种格式
- `OcrTextExtractingDocumentProcessor`/`OcrNoiseCleaningDocumentProcessor`：**扫描件 OCR**（很多企业知识是图片 PDF）
- `RecursiveTextChunker`：递归语义切块（1000/200 可调）
- `DefaultMetadataEnricher`：自动补 documentId/sourceName/...
- `WhitespaceNormalizingDocumentProcessor`：空白归一

都可替换：实现 `Chunker`/`DocumentLoader`/`MetadataEnricher` 接口注入。

### 10.3 摄入代码（多租户 permissionTag + 内存 corpus 收集）
```java
// public 知识
ingest(pipeline, "classpath:knowledge/*.md", "public");
// premium 内部知识
ingest(pipeline, "classpath:knowledge/premium/*.md", "premium");

// ingest() 内部：document.metadata.put("permissionTag", tag)
// 摄入后把每个 chunk 收集到 InMemoryCorpus（供 Bm25Retriever / 评估端点）
for (VectorRecord record : result.getRecords()) {
    inMemoryCorpus.add(permissionTag, RagHit.builder()
            .id(record.getId()).content(record.getContent())
            .sourceName(filename).build());
}
```
documentId 用文件名确定性派生（`UUID.nameUUIDFromBytes`），重启幂等（upsert 同 chunkId，PgVector `ON CONFLICT DO UPDATE`）。

### 10.4 staging（版本化）
双 dataset（`ecommerce-kb-v1` active / `v2` staging）并行 + 原子切换。PgVector `dataset` 列天然支持。

### 10.5 增量 ingest：content-hash 跳过未变更 chunk（省 embed，PR #187）

10.3 的「重启幂等」靠 upsert（同 chunkId 覆盖）—— 但**每次重启都重新 embed 全部 chunk**（embed 是成本大头），浪费。生产知识库大时（几万 chunk），全量 re-embed 慢且贵。

ai4j PR #187 给 `IngestionPipeline` 加 **content-hash 增量**：自动给每个 chunk 的 metadata 写 `contentHash = SHA-256(content)`；`IngestionRequest.skipExistingContentHash=true` 时，upsert 前先 `vectorStore.exists(filter={contentHash})` 查重 —— 已存在就 skip（**embed + upsert 两步都省**），`IngestionResult.skippedCount` 统计跳过数：

```java
pipeline.ingest(IngestionRequest.builder()
        .dataset("ecommerce-kb")
        .skipExistingContentHash(true)   // ← 增量：content 未变的 chunk 跳过 embed+upsert
        // ...
        .build());
// result.getSkippedCount() = N（未变更 chunk 数，省了 N 次 embed 调用）
```

`VectorStore.exists` 是可选 metadata-only lookup（后端声明 `VectorStoreCapabilities.metadataLookup`）：PgVector / Qdrant / Milvus / Redis 支持（走 metadata 索引），Pinecone 不支持（默认 skip 无效，退化为全量）。

**和 10.3 重启幂等的区别**：upsert idempotent 省的是「写」（同 chunkId 覆盖），content-hash 增量省的是「**embed**」（embedding API 调用，成本大头）。生产 re-ingest 时，未变更文档的 chunk 直接 skip embed，只对真正改动的文档付出 embed 成本。

## 十一、在线问答架构

标准 13 步链路（鉴权→租户→缓存→改写→embedding→混合召回→权限过滤→重排→组装→生成→引用→缓存）。demo 主链覆盖检索/重排/组装/生成/引用/缓存。

### 11.1 Query Rewrite（demo 实装：QueryRewriteService）

用户原始问题常很短或口语化（"退款怎么弄"），直接 embedding 语义不足。demo 的 `QueryRewriteService` 用 GLM 把口语改写为适合检索的正式表达：

```java
public String rewrite(String query) throws Exception {
    AnthropicChatCompletion req = new AnthropicChatCompletion();
    req.setModel(ragProperties.getGlmModel());
    req.setSystem("你是企业电商知识库的查询改写器。把口语化问题改写为一句适合向量检索的正式表达："
            + "1) 不改变原意；2) 补足业务主语和动作；3) 对齐知识库正式术语；4) 只输出一句话。");
    req.setMessages(Collections.singletonList(new AnthropicMessage("user", query)));
    req.setMaxTokens(128);
    return extractText(messagesService.messages(req)).trim();
}
```

`RagQueryService` 主链在检索前先改写，并把改写前后都暴露在 `RagAnswer.rewrittenQuery`。**实测效果**（见 18.7）：

| 原始（口语） | 改写后（正式） |
|---|---|
| 退款怎么弄 | 退款操作流程 |

改写后精准命中 `refund-rules.md`。生产里高频问题可叠一层规则归一化 + 改写结果缓存（避免每次 LLM 调用）。

### 11.2 混合召回、重排，以及"到底要不要 reranker"

先说一个常被讲模糊的判断：**reranker 不是 RAG 的必选项**。很多 demo 一上来就 TopK→rerank，好像不重排就不专业——其实未必。

**为什么未必？** 向量召回本身就有相似度分数排序；混合检索（Dense 向量 + Bm25 关键词 + RRF 融合）更进一步——两路召回的交叉验证已经是很强的排序信号。ai4j 的 `HybridRetriever` 就是这么做的：把 `DenseRetriever`（向量）和 `Bm25Retriever`（关键词，纯内存、构造时建倒排、不需要 ES）的结果用 `FusionStrategy`（默认 RRF 倒数排名融合）合并，每个 hit 的最终分是两路的融合分，还带 `RagScoreDetail`（哪个 retriever 贡献多少）——排序可解释，不靠黑盒。

**那 reranker 什么时候才值？** 当：
- 召回 TopK 噪声大（知识库杂、query 模糊），前几名里有明显不相关
- 精度敏感（客服答错代价高、合规要求引用准确）
- 你愿意为每个请求多付一次模型调用（成本 + 延迟）

reranker 的本质是"用更强的模型（专用 rerank 或 LLM）对 query-hit 对做相关性打分"。它是**第二道排序**，用算力换精度，所以有成本——Jina/LLM rerank 是额外一次 API/模型调用，P99 延迟会涨。

**demo 把它做成可选配置**，就是要让你直接对比这个取舍：

```yaml
rag:
  reranker: llm   # none（不重排，靠混合检索排序）/ jina（专用 rerank 模型）/ llm（GLM 打分兜底）
```

- `none`：`NoopReranker`，直接用检索分数（HybridRetriever 的 RRF 融合分）排序——**成本最低、延迟最短**。知识库干净、query 清晰时，这一档往往就够了。
- `jina`：`ModelReranker` 接 Jina `jina-reranker-v2-base-multilingual`（ai4j `JinaRerankService`），专用 rerank 模型，**精度最高**，多一次外部 API 调用（要 Jina key + 网络 + 计费）。
- `llm`（默认）：`LlmReranker`，用 GLM 对每个 hit 打分重排。**没有专用 rerank 模型时的兜底**——本地 Ollama 原生没有 `/api/rerank` 端点（实测 404），所以用 chat 模型打分是通用替代。

代码上，`RagQueryService` 按配置选：

```java
private Reranker resolveReranker(AiService aiService) {
    String mode = ragProperties.getReranker();
    if ("none".equals(mode)) return new NoopReranker();
    if ("jina".equals(mode)) return aiService.getModelReranker(PlatformType.JINA, ragProperties.getJinaRerankModel());
    return new LlmReranker(messagesService, ragProperties.getGlmModel(), ragProperties.getTopK());
}
```

> **实践建议**：先 `none` 跑通，用 `RagEvaluator` 量 Recall@K / MRR / NDCG（见 13.6 / 18.3）。指标达标就别上 reranker（省成本）；不达标再开 jina/llm，再量一次看提升值不值这次模型调用的代价。**让数据决定，别拍脑袋。**

### 11.3 Conversational RAG：多轮对话的 query rewrite（带 history）

11.1 的 `QueryRewriteService` 是**单轮**的（只看当前 query）。但生产是**多轮对话**：用户 Q1「退款怎么弄」答完，Q2 follow-up「那时效呢」—— 单轮 rewrite 只看到「时效」，检索会跑偏到「物流时效」。

ai4j 2.4.1（PR #190）把 history 接进 **retrieve 层**：`RagQuery` 加 `history: List<ChatMemoryItem>`，`ModelRagQueryPlanner.plan` 把 history 拼进 LLM prompt，生成 variant 时消解 follow-up 指代：

```java
RagQuery query = RagQuery.builder()
        .query("时效呢")
        .history(chatMemory.items())   // ← 带对话历史
        .build();
// ModelRagQueryPlanner 内部 prompt："Conversation history: [Q1:退款怎么弄, A1:...] query: 时效呢"
//   → variant "退款时效"（消解指代）→ retrieve 命中退款时效，不跑偏
```

**关键区别**：这是 retrieve 层带 history rewrite，不是 generate 层。generate 层（agent memory）是 LLM 看 history + **跑偏的 context** 硬推断；retrieve 层（Conversational RAG）是 query 先消解指代再 retrieve，**context 一开始就对**。参考文章讲的 Spring AI rewrite 不带 history，这是 ai4j 的增量。

### 11.4 TokenAware context：token 级预算（不是字符截断）

参考文章 13.10 讲 Prompt 预算（`MAX_CONTEXT_CHARS` 字符级截断）。字符级的问题：中英文 token 密度不同（中文 1 字 ≈ 1-2 token，英文 1 word ≈ 1 token），字符截断不准 —— 可能超 LLM `max_tokens` 报错，或留太多余量浪费。

ai4j 2.4.1（PR #188）`TokenAwareRagContextAssembler`：**token 级预算**（jtokkit 精确 BPE 计数），超 budget 停追加（reranked 高相关在前，保留前 N），第一个 hit 自己超长才截断（至少答一个，不全空），citations 只保留实际进 context 的来源：

```java
new DefaultRagService(retriever, reranker,
        new TokenAwareRagContextAssembler("gpt-4", 4096),   // ← token 预算（jtokkit 精确）
        planner);
```

PR #189 进一步把 encoding fallback 做成三层（普通传模型名 / 高级 `withEncoding` / 未知模型降级不失败）—— 国产模型 jtokkit 不认时降级 `CL100K_BASE` BPE 近似，不抛异常。token 级预算比字符级精确，context 既不超 `max_tokens`，也不留余量浪费。

## 十二、PgVector 索引设计：把过滤前置到召回之前

PgVector 是这套架构的检索地基。它的设计决定了召回质量、并发能力、以及多租户隔离的实现方式。

### 12.1 表结构（demo 实际）

```sql
CREATE TABLE ai4j_rag_demo (
  id text PRIMARY KEY,           -- chunkId = documentId#chunk-N（确定性派生，幂等 upsert）
  dataset text,                  -- 知识库边界/版本（ecommerce-kb / ecommerce-kb-v2）
  content text,                  -- chunk 正文
  metadata jsonb,                -- permissionTag/sourceName/sectionTitle/pageNumber/...
  embedding vector(1024)         -- Qwen3-Embedding 输出维度
);
```

设计要点：
- **`id` 用确定性派生的 chunkId**（`UUID.nameUUIDFromBytes(filename)#chunk-N`）——重启摄入幂等（PgVector `ON CONFLICT DO UPDATE`），不会重复累积。
- **`dataset` 列**——天然支持版本化（双 dataset 并行 + 原子切换，见 10.3）和多知识库隔离。
- **`metadata jsonb`**——ai4j 的 `RagMetadataKeys`（documentId/sourceName/sectionTitle/pageNumber/permissionTag…）全存这里，供过滤和引用溯源。

### 12.2 HNSW 还是 IVFFlat（取舍）

| | HNSW | IVFFlat |
|---|---|---|
| 原理 | 图近似最近邻 | 倒排 + 精确 |
| 查询 | 快（近似） | 精度高 |
| 建索引 | 慢、占内存多 | 快 |
| 适合 | 中大规模（10 万~百万 chunk） | 小规模、精度优先 |

demo 用 HNSW（企业 RAG 中大规模首选）：

```sql
CREATE INDEX ai4j_rag_demo_emb_idx
  ON ai4j_rag_demo USING hnsw (embedding vector_cosine_ops);
```

`vector_cosine_ops` 是距离操作符（对应 cosine `<=>`）。用 L2 则 `vector_l2_ops`、内积 `vector_ip_ops`——**必须和查询的距离操作符一致**，否则索引用不上（退化全表扫描，这是新手最常踩的坑）。

### 12.3 维度怎么选

维度不是越高越好——高维度单向量内存大、索引慢、传输贵。Qwen3-Embedding-0.6B 输出 1024 维，是精度与成本的平衡点。建表 `vector(1024)` 要和 embedding 模型输出**严格一致**，否则插入报错。部分模型支持 Matryoshka（可降维 1024→512→256，精度换成本），如需降维在 embedding 调用层处理。

### 12.4 过滤前置：PgVector 的杀手锏

这是 PgVector 相对专用向量库的最大优势。专用向量库的过滤往往是"先 KNN 再过滤"或独立 filter DSL；PgVector 把过滤用 SQL `where` 和 KNN 在**一条 SQL** 完成——**先过滤再检索**：

```sql
SELECT id, content, embedding <=> $1 AS distance
FROM ai4j_rag_demo
WHERE dataset = 'ecommerce-kb'
  AND metadata->>'permissionTag' IN ('public', 'premium')  -- 先过滤
ORDER BY embedding <=> $1                                     -- 再 KNN
LIMIT 5;
```

ai4j 的 `VectorSearchRequest.filter` + `RagQuery.dataset` 就是翻译成这个 where。价值：**多租户越权从召回阶段就杜绝**（15.2 实测 default 看不到 premium），不是事后遮罩。高频过滤字段（tenant/version/permission）可加 btree/gi 索引加速过滤。

### 12.5 索引维护（生产）

- **版本化**：用 `dataset` 列隔离版本（v1 active / v2 staging），不直接覆盖线上
- **重建**：知识大批量更新时，建新 dataset → 重建 → 原子切换（改配置 `dataset=ecommerce-kb-v2`）
- **HNSW 参数调优**：`m`（连边数，高精度高内存）、`ef_construction`（建索引质量）、`ef_runtime`（查询精度）——压测找平衡，别照搬默认

## 十三、生产级代码实现

### 13.1 依赖（SB 2.7 + JDK 8 + ai4j starter + pg + caffeine）
```xml
<dependency>
  <groupId>io.github.lnyo-cly</groupId>
  <artifactId>ai4j-spring-boot-starter</artifactId>
  <version>2.4.0</version>
</dependency>
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
```
ai4j starter 编译于 SB 2.3/Java 8，**demo 用 SB 2.7（同属 2.x，原生兼容，无需任何适配）**。如果你是 SB 3 / JDK 17 项目，starter 也实测可用（见 22 章）。

### 13.2 application.yml
```yaml
ai:
  vector:
    pgvector: { enabled: true, jdbc-url: jdbc:postgresql://localhost:5432/postgres,
                 username: postgres, password: postgres, table-name: ai4j_rag_demo,
                 distance-operator: "<=>" }
  ollama: { api-host: http://localhost:11434/, embedding-url: api/embed }
  anthropic: { api-host: https://open.bigmodel.cn/api/anthropic/,
               api-key: ${GLM_API_KEY}, chat-completion-url: v1/messages }
rag: { dataset: ecommerce-kb, embedding-model: hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:latest,
       vector-dimension: 1024, glm-model: glm-4.6, top-k: 5 }
```

### 13.3 schema 初始化（@PostConstruct）
```java
@PostConstruct
public void initSchema() {
    try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
         Statement s = c.createStatement()) {
        s.execute("CREATE EXTENSION IF NOT EXISTS vector");
        s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id text PK, dataset text, "
                + "content text, metadata jsonb, embedding vector(" + dim + "))");
        s.execute("CREATE INDEX IF NOT EXISTS " + table + "_emb_idx ON " + table
                + " USING hnsw (embedding vector_cosine_ops)");
    } catch (Exception e) { throw new RuntimeException(e); }
}
```
> 坑：不要用两个 `@Bean ApplicationRunner`+`@Order` 保证建表先于摄入——`@Order` 对 `@Bean` runner 不可靠。用 `@PostConstruct`（早于所有 ApplicationRunner）。

### 13.4 主链 RagQueryService（Dense + LlmReranker + 多租户 + Caffeine 缓存）
```java
public RagQueryService(AiService aiService, PgVectorStore vectorStore, RagProperties props) {
    this.messagesService = aiService.getMessagesService(PlatformType.ANTHROPIC);
    this.ragService = new DefaultRagService(
            new DenseRetriever(aiService.getEmbeddingService(PlatformType.OLLAMA), vectorStore),
            new LlmReranker(messagesService, props.getGlmModel(), props.getTopK()),
            new DefaultRagContextAssembler());
    this.answerCache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).recordStats().build();
}

public RagAnswer ask(ChatRequest req) throws Exception {
    String key = req.getTenantId() + "::" + normalize(req.getQuestion());
    RagAnswer cached = answerCache.getIfPresent(key);
    if (cached != null) { cached.setCached(true); return cached; }

    // 多租户：权限标签前置过滤
    Map<String,Object> filter = new LinkedHashMap<>();
    filter.put("permissionTag", permissionTagsFor(req.getTenantId())); // default→[public], premium→[public,premium]

    RagResult result = ragService.search(RagQuery.builder()
            .query(req.getQuestion()).dataset(props.getDataset())
            .embeddingModel(props.getEmbeddingModel()).topK(props.getTopK())
            .filter(filter).build());
    String context = result.getContext() == null ? "" : result.getContext();
    String answer = generate(req.getQuestion(), context, context.isBlank()); // GLM，带拒答约束
    RagAnswer a = RagAnswer.builder().answer(answer)
            .references(mapCitations(result.getCitations()))
            .hitCount(result.getHits().size()).degraded(context.isBlank()).cached(false).build();
    answerCache.put(key, a);
    return a;
}
```

### 13.5 LlmReranker（无专用 rerank 时的兜底）
```java
public List<RagHit> rerank(String query, List<RagHit> hits) throws Exception {
    // 构造 prompt：query + 每个 hit content，让 GLM 返回 [{index, score}]
    // 解析 JSON 分数，按分排序，写回 rerankScore
    ...
}
```
实现 `Reranker` 接口。生产有专用 rerank（Jina/Doubao）时换 `ai4j.getModelReranker(platform, model)`，主链不变。

### 13.6 评估端点 EvaluationController（HybridRetriever + RagEvaluator）
```java
Retriever dense = new DenseRetriever(embeddingService, vectorStore);
Retriever hybrid = new HybridRetriever(
        Arrays.asList(dense, new Bm25Retriever(inMemoryCorpus.publicCorpus())),
        new RrfFusionStrategy());
RagService svc = new DefaultRagService(hybrid, new NoopReranker(), new DefaultRagContextAssembler());

for (String[] c : EVAL_SET) {  // {query, 期望文件名}
    RagResult r = svc.search(RagQuery.builder().query(c[0])...topK(5).build());
    String relevantId = chunkIdFor(c[1]); // UUID.nameUUIDFromBytes(filename)#chunk-0
    RagEvaluation e = new RagEvaluator().evaluate(r.getHits(), List.of(relevantId), 5);
    // 收集 recallAt5 / mrr / ndcg
}
```

### 13.7 生成（GLM + 拒答约束）
system prompt 明确："若资料不足以支撑答案，请明确说明无法确认，不要编造制度、流程或时效"。这是抑制幻觉的关键。

## 十四、高并发与可扩展：RAG 上线后最容易出事的地方

这是 demo 没直接演示（单体跑通即可）、但**生产必踩**的一块。RAG 的并发问题和普通 Web 服务不一样——它的瓶颈不是单点，是**链路组合**。

### 14.1 真实瓶颈在哪（不是单点，是组合）

一条 RAG 请求经过：Query Rewrite（LLM）→ embedding（模型）→ 向量检索（Postgres）→ rerank（模型，可选）→ 组装 → 生成（LLM）。每步资源特征完全不同：

| 步骤 | 资源特征 | 高并发下的表现 |
|---|---|---|
| Query Rewrite | LLM 调用 | 占模型额度、RRT 长 |
| embedding | 模型吞吐有限 | 排队，本地 Ollama 单卡吞吐封顶 |
| 向量检索 | Postgres 连接 + CPU | 连接池打满、HNSW 查询 CPU 涨 |
| rerank | 额外模型调用 | 延迟叠加（11.2 讲的成本） |
| 生成 | LLM，最慢最贵 | P99 主要贡献者，最易抖动 |

**关键认知**：生成是最慢最贵的（秒级），它决定 P99；embedding/检索是毫秒级但高频。**如果不隔离，一次生成卡住会通过池化资源拖垮整条链路**——这是 RAG 高并发事故最常见的根因。

### 14.2 资源池隔离（第一道防线）

至少拆分这些线程池，别让它们互相饿死：Web 请求池、摄入 worker 池（离线入库，重 CPU/IO）、embedding/rerank 池、生成池。**摄入和在线必须分池**——否则一次大批量入库会把在线问答的 embedding 挤死。demo 是单体没拆，但生产这是硬要求。

### 14.3 限流 / 超时 / 熔断 / 降级（假设模型服务会抖动）

生产必须假设 GLM/Ollama 会抖动（超时、限流、宕机）。治理顺序：
1. **限流**：网关层限 RAG QPS，保护下游
2. **每阶段独立超时**：rewrite 3s、检索 200ms、生成 2s——别只网关统一超时（统一超时会让慢的生成挤掉快的检索）
3. **熔断**：模型连续失败时快速失败（Resilience4j `CircuitBreaker`）
4. **降级**：检索空 → 直接 LLM 兜底（demo 的 `degraded` 标记就是这个）；rerank 失败 → 用检索原始排序；全挂 → 返回缓存答案 / FAQ

demo 的 `RagAnswer.degraded` 就是降级标记——检索为空时 context 为空，生成走拒答/兜底 prompt。

### 14.4 缓存（基础设施，不是优化）

四层缓存，**每层 key 必须带版本维度**，否则知识更新后用户看到旧答案：
- Rewrite 缓存（高频 query 改写复用）
- Embedding 缓存（同 query 向量复用）
- Retrieval 缓存（热点政策 query 的召回结果）
- Answer 缓存（稳定问答直接返回）

demo 用 Caffeine 做了 Answer 缓存（key = `tenant::originalQuestion`，实测第二次同问 `cached=true`）。生产 key 设计的要点：**带 tenant + kbVersion + permissionScope + queryHash**，不带版本就是埋雷。

### 14.5 横向扩展

在线问答服务**无状态化**（状态下沉到 Postgres/MQ/缓存），多副本水平扩展；摄入 worker 独立扩缩容（消息驱动）；Postgres 读写分离 / 分库（数据规模到瓶颈时）。一句话：**在线服务无状态，状态下沉**——这是横向扩展的前提。

## 十五、多租户、安全与权限隔离：RAG 最危险的不是答错，是串库

RAG 系统最危险的问题不是"答错"，是**"串库"**——A 租户看到 B 租户的私密知识，或者普通用户看到内部规则。这种事故的代价远高于答错一个退款问题。

### 15.1 权限过滤的正确顺序（前置，不是后置）

正确：`识别身份 → 计算可访问范围 → 查询时附带过滤 → 检索结果再校验 → 生成`
错误：`先全库召回 → 生成答案 → 再遮罩/脱敏`

后一种在 RAG 里**根本不生效**——敏感内容一旦进了 context，模型已经"看到"并可能反映在答案里，事后遮罩遮不住。所以必须**在召回阶段就过滤**，敏感内容压根不进 context。

### 15.2 demo 的多租户实测（public/premium 越权隔离）

demo 把知识分两级：`permissionTag=public`（退款/物流/售后，所有租户可见）+ `permissionTag=premium`（营销内部预算、商家保证金规则，仅 premium 租户）。摄入时写进 metadata，查询时按租户身份带 filter：

```java
private List<String> permissionTagsFor(String tenant) {
    if ("premium".equals(tenant)) return Arrays.asList("public", "premium");
    return Collections.singletonList("public");  // default 只看 public
}
// RagQuery.filter.put("permissionTag", permissionTagsFor(tenant))
```

PgVector 把这个 filter 翻译成 `metadata jsonb where`，和 KNN 在**一条 SQL** 里完成——**先过滤再检索**，从召回阶段就隔离。

**实测对比**（同一个问题"秒杀价格能和优惠券叠加吗"，见 18.2）：
- **premium 租户** → 召回 `marketing.md` + `merchant.md`（内部规则）+ public → 能答出"秒杀价不叠加券、商家违规扣保证金"
- **default 租户** → 召回**只有 public**（refund/after-sales/logistics），marketing/merchant **压根不在结果里** → 答不出内部规则

这就是"前置过滤"的实证——不是事后遮罩，是召回阶段就看不到 premium 内容。

### 15.3 权限模型建议

每个 chunk 打权限标签（如 `public/employee/customer-service/merchant/finance-admin`），用户进系统映射成 `AccessScope(tenantId, permissionTags)`，检索层召回前过滤。召回结果还可以再校验一遍（防 SDK bug / 配置错误）——多一道防线。

### 15.4 敏感信息脱敏（第二层保护，不等于权限隔离）

回传给模型的 context 里如果有手机号/身份证/银行卡，建议脱敏。但注意：**脱敏是第二层保护**，不能替代 15.1 的权限前置过滤——脱敏防的是"无意泄露"，权限过滤防的是"越权访问"，两个层面。

## 十六、可观测性：让一次请求的每一步都看得见

可观测性的核心不是"加了日志/指标"这么轻巧——它是**线上出 bug 时能定位到具体是哪一步的锅**。RAG 链路长（改写→召回→重排→组装→生成），任何一步都可能是错误源头；agent 链路更长（多步思考 + 多 tool 调用）。可观测要解决的就是"快速定位到那一步"。

ai4j 在这一层给了两种**互补**的 trace，对应"独立 RAG 流程"和"RAG 接入 agent 后的整体"。

### 16.1 RAG 级 per-step trace（每一步中间产物可见，排障检索质量）

`RagService.search` 返回的 `RagResult` 带 `RagTrace`，字段现在完整覆盖全链：`queryPlan`（Query Planning 的 variants + fallback）+ `planningDurationMs` / `retrieveDurationMs` / `rerankDurationMs` / `assembleDurationMs` / `totalDurationMs`（各步耗时）+ `retrievedHits`（召回原始顺序）+ `rerankedHits`（重排后顺序）；每个 hit 带 `RagScoreDetail`（融合分来源）+ `RagCitation`（引用）。demo 的 `/api/rag/ask` 把这些全暴露到响应（`queryPlan` / `planningDurationMs` / `rewrittenQuery` / `retrievedHits` / `rerankedHits` / `context` / `answer`），一次请求每一步的中间产物 + 耗时全可见（真实输出见 18.7）：

```
STEP 1  input        : 退款怎么弄
STEP 2  planning     : planningMs=1327  fallback=false                ← Query Planning（GLM 生成 variants 多路召回融合）
                        variant[0] ORIGINAL  退款怎么弄
                        variant[1] REWRITE   退款操作流程
STEP 3  retrievedHits（召回，rerank 前）   retrieveMs=341:
          - refund-rules.md   score=0.491
          - after-sales.md    score=0.353
          - logistics.md      score=0.314
STEP 4  rerankedHits（重排后）             rerankMs=210:
          - refund-rules.md   rerankScore=0.491
          - after-sales.md    rerankScore=0.353
          - logistics.md      rerankScore=0.314
STEP 5  context      : [S1] refund-rules.md\n# 退款规则\n## 退款申请时效\n...   (assembleMs=2)
STEP 6  answer       : 根据参考资料，退款操作流程如下：1. 发起申请（注意时效）：订单签收 7 天内…
                        totalMs=1880（planning+retrieve+rerank+assemble，generate 另算）
```

**这套 trace 的排障价值**——假设线上有个 case 答错了，你按顺序质疑每一步：

- STEP 2（queryPlan / planning）：planning 生成的 variants 跑偏没？（把"退款"扩成无关词）/ fallback 了几次（GLM planning 抖动）/ `planningDurationMs` 占比过高（planning 比检索还慢就得不偿失）
- STEP 3（retrievedHits）：是不是召回就没召对？（refund-rules 没进 TopK / 顺序错）
- STEP 4（rerankedHits）：是不是重排把对的挤下去了？
- STEP 5（context）：组装的上下文是不是塞了噪声 / 截断了关键段？
- STEP 6（answer）：上下文对，但模型生成跑偏？（prompt / 拒答约束问题）

每一步都能单独质疑。没有这套 trace，你只能"答错了→不知道哪步错→瞎调 prompt"。

### 16.2 agent 级整体可观测（RAG 作为 tool，进 IoCaptureSink，可重放/恢复/审计）

如果 RAG 不是独立调用，而是**接入 agent**（比如客服 agent 用 RAG tool 检索知识 + 订单 tool 查物流）——那 RAG 检索就是 agent 的一个 TOOL 节点。ai4j 的 `IoCaptureAgentListener` 会把 agent 跑的**每个节点**（MODEL 思考的完整 prompt→response、TOOL 调用含 RAG 检索的 input/output）捕获到 `IoCaptureSink`，可重放、可从失败点恢复、可审计。

这就是 `RagTool`（PR #172）的价值——一行 `.capture(sink)` 把整条 agent 链路（思考→调检索 tool→生成）全捕获，**RAG 不再是 agent 可观测之外的黑盒**：

```java
RagTool ragTool = RagTool.builder(ragService)
        .dataset("ecommerce-kb").embeddingModel("...").topK(5).build();
Agent agent = Agents.react().anthropicMessages(key, baseUrl).model("glm-4.6")
        .toolRegistry(new StaticToolRegistry(Collections.singletonList(ragTool.tool())))
        .toolExecutor(ragTool.executor())
        .capture(new InMemoryIoCaptureSink())   // ← MODEL + TOOL(含 RAG 检索) 全捕获
        .build();
```

demo `/api/agent/ask` 实测：agent 自主决定调 `knowledge_search`，capture 录到 **1 TOOL（=RAG 检索）+ 2 MODEL** 节点，每个节点的 input/output 都在 sink 里（真实输出见 18.6）。

**每个节点捕获什么**（`NodeIoRecord`，本 PR 持续增强）：

| 字段 | MODEL 节点 | TOOL 节点 |
|---|---|---|
| `input` / `output` | 完整 AgentPrompt / raw response | AgentToolCall / AgentToolResult |
| `reasoningText` | GLM 思维链（需开 `thinking` 参数，否则 null） | — |
| `inputTokens` / `outputTokens` | per-step 成本（从 raw response usage 解析） | — |
| `durationMs` | 精准耗时（`startedAt` → `capturedAt`） | 同左 |
| `retryCount` | 该步 MODEL_RETRY 次数 | — |
| **`trace`（sub-trace）** | — | **RAG 检索的 `RagResult`（hits/citations/RagTrace）** |

节点按 `startedAtEpochMs` **因果排序**（MODEL 决策 → TOOL 执行 → MODEL 生成），不是 flush 序——否则 TOOL 会排在决定它的 MODEL 前面，因果倒置。

**RAG sub-trace 不再丢**（本 PR 关键）：`RagTool` 实现 `TraceableToolExecutor`，把检索的 `RagResult`（含 `retrievedHits` / `rerankedHits` / 各步耗时）作为 sub-trace 流进 TOOL 节点。所以 agent 里的 `knowledge_search` 不再是「query → context」黑盒——召回了哪些、重排耗时、retrieve/rerank 各步多久，全在 IoCapture 里。这才是「RAG 不割裂」的真正兑现（不只节点级统一，连检索内部步骤都进来了）。

**这两层 trace 互补，不是二选一**：

| 层 | 看什么 | 解决什么问题 |
|---|---|---|
| **RAG 级**（16.1，`RagTrace`） | 检索内部：改写 / 召回 / 重排 / 上下文 | 排障**检索质量**（召回对不对、重排好不好） |
| **agent 级**（16.2，`IoCaptureSink`） | 整条 agent 链路：多步思考 / 多 tool 调用 / **RAG sub-trace（hits + 各步耗时）** | 排障**agent 决策 + 检索质量** + 重放 / 从失败点恢复 / 审计 |

一句话：独立 RAG 用 16.1 看检索内部；RAG 进 agent 用 16.2 看整体决策——后者还能重放恢复，是生产级 agent 的命脉。

### 16.3 online evaluation：不只排障，还量化每答质量（LLM-as-judge）

参考文章 16.3 讲「质量评估指标」（Recall@K / MRR / Grounded Rate）—— 但那是**离线评测集**（offline，固定 query + 人工标注）。生产运行时**每个回答**的质量，传统只能靠用户反馈（点赞/点踩），滞后且稀疏。

ai4j 2.4.1 引入 `RagOnlineEvaluator` + `RagJudge`（可替换接口）+ 默认 `ChatRagJudge`（GLM-as-judge），**每个回答自动打分**：

- `faithfulness`：答案每句是否忠于检索 context（检测幻觉）
- `relevance`：检索 context 是否相关 query（检测召回跑偏）
- `answerRelevance`：答案是否回应了 query

结果挂 `RagTrace.judgeEvaluation`（per-request 质量分，0-1）。这是 LangSmith/Langfuse 强调的「beyond debuggable」（超越可调试 → 可量化）—— **不只定位「哪步错」，还量化「这次答得有多好」**，可做质量趋势告警。

```java
// ChatRagJudge 走 OpenAI 协议（IChatService），所以用 ZHIPU paas/v4 入口（不是 anthropic 那个）
RagOnlineEvaluator evaluator = aiService.getRagOnlineEvaluator(PlatformType.ZHIPU, "glm-4.6");
RagJudgeEvaluation scores = evaluator.evaluate(ragResult, answer);  // 三项分，顺带挂到 trace.judgeEvaluation
```

demo 里 `RagQueryService` 用 `rag.online-eval-enabled` 开关门控（默认关）：开了才构造 evaluator，每答多一次 GLM 调用打分，分挂在 `RagAnswer.scores`（+ `RagTrace.judgeEvaluation`）。judge 挂了非致命（网络/余额/JSON 解析），主答照常返回。

> ⚠️ 注意一个 key 陷阱：`ChatRagJudge` 用 **OpenAI 协议**（`IChatService`），要走 GLM 的 `paas/v4` 入口；而主答走 **Anthropic 协议**（`IMessagesService`），用 `api/anthropic` 入口。**coding-plan key 只对 anthropic 入口有效**，拿它打 paas/v4 会报余额不足 —— judge 要单独配 `ai.zhipu.api-key`（paas/v4 常规 key）。这是 GLM 双入口的真实约束，不是 SDK 问题。

`RagJudge` 是接口（对称 `Reranker` 可换 jina/llm）—— 不锁死算法，想接 Ragas 或换更强的 judge 模型，实现接口即可。

### 16.4 replay + 断点续跑：agent 失败从断点恢复（IoCapture，行业少有）

16.2 的 `IoCaptureSink` 不只审计，还能**重放** + **断点续跑**：

- `NodeReplayer.replayModelLive(record, client)`：用 captured 的 `AgentPrompt` 重新调 model（复现/对比不同模型）
- `NodeReplayer.replayModelMock(record)`：不调 model，直接返 captured output（快速重放，0 成本）
- `ResumableModelClient` / `ResumableToolExecutor`：agent 失败后，**从失败的 MODEL/TOOL 节点续跑**（成功步不重调），省 token + 审计

这是生产 agent 的命脉 —— 一个 10 步 agent 跑到第 8 步失败，传统要重跑全部（10 步 model/tool 都重调，贵）；IoCapture 续跑只重调第 8-10 步。**LangSmith 有 replay 但没有断点续跑**（它只看 trace，不接管 runtime）；ai4j 这点是真正超越的。

### 16.5 cost：全链 token + $ 可见

| 路径 | token 来源 | cost |
|---|---|---|
| agent | IoCapture `NodeIoRecord.inputTokens/outputTokens`（per MODEL 节点） | `TraceMetrics`（token × pricing，#186） |
| 独立 RAG | `RagGenerationUsage`（#200，generate usage 上层回填） | `generationUsage.inputCost/totalCost`（上层算） |

全链成本归因到每步（agent 每 MODEL 节点 / RAG generate），对标参考「成本可控制」但 ai4j **per-request per-step $ 可见**（不只总量）。注意 core 不塞 pricing 表（易变 + 业务决策），只给数据通道（`RagGenerationUsage`），price 由 demo/plugin 填 —— 保持 core 精简。


> **OpenTelemetry 不在 ai4j core 引入**——保持"零基础设施依赖"哲学（像 PgVector 用 JDK `java.sql`、Redis 用 optional Jedis）。`RagTrace` / `IoCaptureSink` 已暴露完整数据，应用层 wrap 一下发到 OTel/Micrometer 即可（几行桥接）。如果将来需求强，可像 LangChain4j 那样出独立可选模块 `ai4j-observability-otel`，不污染 core。这是设计取舍，不是缺失。

## 十七、部署与发布

演进路径：本地试点（单体 + 单 Postgres + 本地 Ollama）→ 小规模（问答/摄入拆分 + MQ + Prometheus）→ 中大型（多副本 + worker 集群 + 读写分离 + 配置中心）。本节给出中规模 K8s 部署的最小可用配置，聚焦 RAG 上线最容易踩的几个点。

### 17.1 问答服务 Deployment（无状态，可水平扩）

问答服务是纯查询链路（检索 + 重排 + 生成），无本地状态，直接多副本：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rag-query
spec:
  replicas: 3
  selector:
    matchLabels: { app: rag-query }
  template:
    metadata:
      labels: { app: rag-query }
    spec:
      containers:
      - name: query
        image: registry.example.com/ai4j-rag-demo:2.4.2
        ports: [{ containerPort: 8080 }]
        env:
        - name: GLM_API_KEY
          valueFrom: { secretKeyRef: { name: ai-secrets, key: glm-key } }
        - name: SPRING_DATASOURCE_URL   # 覆盖 application.yml，走集群 Postgres
          value: jdbc:postgresql://pg-primary:5432/rag?currentSchema=rag
        - name: JAVA_TOOL_OPTIONS       # JDK8 容器内存感知（不加的话 K8s 限内存会被 OOMKilled）
          value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
        resources:
          requests: { cpu: "500m", memory: "1Gi" }
          limits:   { cpu: "2",    memory: "2Gi" }
        readinessProbe:                # 就绪探针：Caffeine/连接池/模型 client 都起来才接流量
          httpGet: { path: /actuator/health/readiness, port: 8080 }
          initialDelaySeconds: 30
        livenessProbe:
          httpGet: { path: /actuator/health/liveness, port: 8080 }
          initialDelaySeconds: 60
---
apiVersion: v1
kind: Service
metadata:
  name: rag-query
spec:
  selector: { app: rag-query }
  ports: [{ port: 80, targetPort: 8080 }]
```

**几个要点**：
- **`MaxRAMPercentage=75`**：JDK8 默认不认 cgroup 内存限制，不加 `-XX:+UseContainerSupport` 会按宿主机内存算堆，被 K8s OOMKilled。留 25% 给堆外（OkHttp 连接池、JIT、DirectBuffer）。
- **就绪探针 ≠ 活性探针**：就绪探针失败只是从 Service 摘除流量（不重启），摄入没跑完/模型 client 没建好时不要接请求；活性探针失败才重启 Pod。
- **GLM key 走 Secret 不走 ConfigMap**：key 泄漏是真实事故，Secret 至少能配合 RBAC 限访问。

### 17.2 摄入 worker Deployment（和问答拆开）

摄入是写链路（embed + upsert），和问答拆开部署——写链路挂了不影响线上问答，问答扩容也不带动昂贵的 embedding 计算：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rag-ingest-worker
spec:
  replicas: 1                    # 摄入靠 content-hash 幂等（10.5），多副本并发写同 chunk 也安全，但单副本够用
  selector:
    matchLabels: { app: rag-ingest }
  template:
    metadata:
      labels: { app: rag-ingest }
    spec:
      containers:
      - name: ingest
        image: registry.example.com/ai4j-rag-demo:2.4.2
        env:
        - name: RAG_ROLE
          value: ingest           # demo 用 profile 开关：ingest profile 只跑 ApplicationRunner 不暴露 /api/rag
        - name: OLLAMA_HOST
          value: http://ollama-svc:11434   # embedding 模型独立部署，不和问答抢 GPU/CPU
```

> demo 当前是单体（问答+摄入一个进程）。生产拆两个 Deployment：摄入用 MQ（Kafka/RocketMQ）触发，ApplicationRunner 改成 MQ consumer；问答服务不打包摄入逻辑。拆分后 embedding 模型（Ollama）也可以独占一台机器，不被问答的 GLM 调用抢占。

### 17.3 HPA（按 QPS 自动扩问答副本）

问答服务的瓶颈是 GLM 调用延迟（P99 可能到几秒），不是 CPU——CPU-based HPA 在等 IO 时会误判。用自定义指标（每副本 QPS）扩容更准：

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: rag-query
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: rag-query }
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Pods
    pods:
      metric: { name: rag_requests_per_second }   # Prometheus Adapter 暴露的自定义指标
      target: { type: AverageValue, averageValue: "5" }   # 每副本 5 QPS 触发扩容
  behavior:                       # 防抖：扩容快、缩容慢，避免流量波动反复伸缩
    scaleUp:   { stabilizationWindowSeconds: 0,   policies: [{ type: Percent, value: 100, periodSeconds: 30 }] }
    scaleDown: { stabilizationWindowSeconds: 300, policies: [{ type: Percent, value: 25,  periodSeconds: 60 }] }
```

> demo 暴露了 `cacheStats()`（命中率/请求数），接 Micrometer → Prometheus → Prometheus Adapter 就能做 QPS 指标。HPA 用 CPU 指标是反模式：RAG 大部分时间在等 GLM/PG，CPU 低，按 CPU 扩会扩不动。

### 17.4 上线最容易踩的几个配置

| 配置 | 坑 | 正解 |
|---|---|---|
| PgVector 连接池 | 多副本 × 每副本默认 HikariCP 10 连接，3 副本就 30 连接，PG 默认 max_connections=100 很快打满 | HikariCP `maximumPoolSize` 按副本收敛（如 8），或上 PgBouncer |
| GLM 超时 | OkHttp 默认超时 10s，GLM 长 context 生成常 >10s，偶发 SocketTimeoutException | OkHttp `readTimeout` 调到 60s+，配合 Resilience4j 超时熔断 |
| 滚动发布索引一致性 | 新版本 embedding 模型（如 Qwen3 → bge-m3）维度变了，新老副本读同一张表会维度不匹配 | **索引版本化**：`dataset=ecommerce-kb-v2`，蓝绿切，老副本读 v1、新副本写 v2，切完再下 v1 |
| Embedding 模型升级 | 直接换模型会让新向量和新查询向量不同空间，召回质量暴跌 | 双写期：新查询同时 embed 新旧两套，分别召回，灰度切流量 |
| 内存 | JDK8 不认容器限制 | `-XX:+UseContainerSupport`（JDK8u191+）或升 JDK11 |

**索引版本化是 RAG 上线和普通 Web 服务最大的区别**：Web 服务无状态，滚动发布天然安全；RAG 的向量索引是有状态的共享数据，模型/分词变了就必须版本化，否则新老副本读到不一致的向量空间，召回质量无声暴跌。

## 十八、真实运行结果（demo 实际输出）

### 18.1 启动日志（5 文档分级摄入）
```
PgVectorSchemaInitializer : PgVector schema ready: ai4j_rag_demo (dim=1024)
RagDemoApplication        : Started RagDemoApplication in 3.196 seconds
KnowledgeIngestionService : Ingested [public] after-sales.md: 1 chunks
KnowledgeIngestionService : Ingested [public] logistics.md: 1 chunks
KnowledgeIngestionService : Ingested [public] refund-rules.md: 1 chunks
KnowledgeIngestionService : Ingested [premium] marketing.md: 1 chunks
KnowledgeIngestionService : Ingested [premium] merchant.md: 1 chunks
KnowledgeIngestionService : Knowledge ingestion done: 5 chunks total
```

### 18.2 多租户隔离（Q2 premium vs Q3 default，同一类问题）

**Q2 premium：秒杀价格能和优惠券叠加吗？商家违规怎么处理？**
```json
{
  "references": [
    {"sourceName": "marketing.md"},   // ← premium 内部规则
    {"sourceName": "merchant.md"},    // ← premium 内部规则
    {"sourceName": "refund-rules.md"},
    {"sourceName": "after-sales.md"},
    {"sourceName": "logistics.md"}
  ],
  "degraded": false
}
```
premium 租户能看到 `marketing.md`（秒杀不叠加券）+ `merchant.md`（违规处理）内部规则。

**Q3 default：秒杀价格能和优惠券叠加吗？**（同样的问题，default 租户）
```json
{
  "references": [
    {"sourceName": "refund-rules.md"},
    {"sourceName": "after-sales.md"},
    {"sourceName": "logistics.md"}
  ],
  "degraded": false
}
```
**default 租户的 references 里完全没有 marketing.md / merchant.md**——权限前置过滤生效，premium 内部规则对 default 不可见，即使问题相关。这就是"先过滤再检索"的价值：从召回阶段就隔离，不靠事后遮罩。

### 18.3 检索质量评估（Hybrid Dense+Bm25+RRF）
`GET /api/rag/evaluate`：
```json
{
  "retriever": "hybrid(dense+bm25+rrf)",
  "cases": 3,
  "meanRecallAt5": 1.0,
  "meanMrr": 1.0,
  "meanNdcg": 1.0,
  "perQuery": [
    {"query": "秒杀商品签收后还能申请退款吗？", "expected": "refund-rules.md",
     "recallAt5": 1.0, "mrr": 1.0, "ndcg": 1.0, "hitIds": ["refund-rules.md", ...]},
    {"query": "标准配送一般几天能送达？",       "expected": "logistics.md",   "...": 1.0},
    {"query": "质量问题多久内能申请售后？",     "expected": "after-sales.md", "...": 1.0}
  ]
}
```
3 个 case 全部命中（Recall/MRR/NDCG = 1.0）。`RagEvaluator` 让你每次调切块/embedding 模型/融合策略都能回归——不靠"用户感觉"。

### 18.4 缓存命中
问过的同一问题（同 tenant+question）再问：
```json
{ "cached": true, "answer": "...", "references": [...] }
```
直接走 Caffeine 缓存，不调 embedding/检索/重排/生成。`cacheStats()` 暴露命中率/大小给可观测。

### 18.5 重排（LlmReranker）
每个在线问答都过 `LlmReranker`（GLM 对召回的 hit 批量打分重排）——日志能看到 rerank 前后顺序变化，hit 带 `rerankScore`。Ollama 无 rerank 端点时这是通用兜底。

### 18.6 agent 端点：RAG 接入 agent 可观测链路

`POST /api/agent/ask`（`RagTool` + `.capture()`）——agent 自主决定何时检索，检索作为 TOOL 节点被捕获，整链进 `IoCaptureSink`：

```json
{
  "answer": "...（基于检索资料的回答）",
  "steps": 2,
  "toolCalls": 1,
  "inputTokens": 867,
  "outputTokens": 223,
  "capturedNodeCount": 3,
  "capturedModelNodes": 2,
  "capturedToolNodes": 1,
  "capturedNodes": [
    {"nodeType": "TOOL",  "step": 0, "hasInput": true, "hasOutput": true},
    {"nodeType": "MODEL", "step": 0, "hasInput": true, "hasOutput": true},
    {"nodeType": "MODEL", "step": 1, "hasInput": true, "hasOutput": true}
  ]
}
```

`capturedToolNodes=1` 就是 RAG 检索的 TOOL 节点——它和 MODEL 节点一起进了 `IoCaptureSink`，**整链统一可观测**（可重放、可从失败点恢复、可审计）。对比 `/api/rag/ask`（RAG 直调，检索只在 `RagTrace`），这是"RAG 接入 agent 可观测链路"的实证——不再割裂。

### 18.7 完整执行 trace（一次请求，每一步可见）

`/api/rag/ask` 把一次请求的完整执行轨迹写进响应（`rewrittenQuery` / `retrievedHits` / `rerankedHits` / `context` / `answer`），排障/可观测时一眼看清每步发生了什么。以"退款怎么弄"为例（demo 实际输出）：

```
STEP 1  input        : 退款怎么弄
STEP 2  rewritten    : 退款操作流程                    ← Query Rewrite（口语→正式术语）
STEP 3  retrievedHits（retrieve 后、rerank 前）:
          - refund-rules.md   score=0.491
          - after-sales.md    score=0.353
          - logistics.md      score=0.314
STEP 4  rerankedHits （LlmReranker 后）:
          - refund-rules.md   rerankScore=0.491
          - after-sales.md    rerankScore=0.353
          - logistics.md      rerankScore=0.314
STEP 5  context      : [S1] refund-rules.md\n# 退款规则\n## 退款申请时效\n用户在订单签收后 7 天内…
STEP 6  answer       : 根据参考资料，退款操作流程如下：1. 发起申请（注意时效）：订单签收 7 天内…
         hitCount=3   cached=false   degraded=false
```

**每一步的中间产物**——改写后 query、召回原始顺序 + 分数、重排后顺序 + 分数、组装的上下文（带 `[S1]` 引用）、最终答案——都在响应里。线上出问题时不用猜是改写、召回、重排还是生成哪一步的锅，看 trace 一目了然。

> 这是 RAG 级 trace（`RagResult.getTrace()` 的 `retrievedHits`/`rerankedHits`）。如果走 agent 端点（18.6），还会加上 MODEL 节点的 prompt/response 捕获——两层 trace 互补。

## 十九、常见坑位
1. 只 TopK 向量不过滤 → 串库/越权（用 dataset+filter 前置）
2. chunk 切太碎/太大
3. 缓存 key 不带版本 → 知识更新后旧答案
4. 只盯模型不看检索
5. 没有拒答策略 → 幻觉（system prompt 约束）
6. **`@Order` 对 `@Bean ApplicationRunner` 不可靠**（demo 实测建表没跑、摄入先跑报表不存在）→ 改 `@PostConstruct`
7. **starter 默认装配多个 VectorStore bean**（PgVector/Pinecone）→ 注入具体类型 `PgVectorStore` 而非接口
8. **Ollama 无 `/api/rerank`** → 用 LlmReranker 兜底，或接 cloud rerank

## 二十、演进

**基础设施演进**：
- 知识量涨 → PgVector→Milvus/Qdrant（`VectorStore` 不变）
- 复杂检索 → `HybridRetriever` 接 ES 做 `Bm25Retriever`

**能力演进（走 plugin，不改 core，见 5.4 extension-api）**：
- 可观测聚合 → 写 `ai4j-observability-otel` plugin（实现 `AgentListener`，把 `RagTrace`/`IoCapture` 发到 OTel/Prometheus，做 p99/cost trend/告警 dashboard）
- 新向量后端 → `ai4j-vector-weaviate` / `ai4j-vector-chroma`（实现 `VectorStore` + `VectorStoreCapabilities`）
- 安全护栏 → `ai4j-guardrail-pii` / `ai4j-guardrail-injection`（实现 `ExtensionGuardrail`，输入/输出拦截）
- 多模态 RAG → 新 `IEmbeddingService`（图片向量，CLIP）+ `DocumentLoader`（OCR）
- embedding cache → 装饰 `IEmbeddingService`
- rerank 软失败 / 独立 RAG cost → 装饰 `Reranker` / 填 `RagGenerationUsage`

参考文章 20 章讲「Redis → ES/Milvus → 智能体」三层演进；ai4j 的演进分两条：**基础设施换后端（`VectorStore` SPI）** + **能力加 plugin（extension-api）**。后者是 ai4j 独有 —— core 不动，治理/安全/可观测/多模态都靠 plugin 扩展，避免 core 膨胀。这也是为什么本文 14/15 章讲的治理（限流/熔断/灾备）和安全（脱敏/injection）没塞进 core，而是留给 plugin 生态。

### 20.1 从 RAG 问答到电商客服 agent（把整条链路串起来）

demo 的 `/api/agent/ask` 已经展示了 RAG 作为**单个 tool** 接入 agent（`RagTool` + `.capture()`）。但真正的电商客服远不止"查知识库"——用户问"我昨天下的单什么时候到，不想要了能退吗"，客服 agent 要**串联多个能力**：查订单 tool（订单状态/物流）+ 检索知识 tool（退款规则）+ 可能的创建工单 tool。

这就是 RAG"在 agent 里用"的完整形态——RAG 不再是独立链路，而是 agent 的一个能力模块，和业务 tool 平级。用 ai4j 这样搭：

```java
// ① 知识检索 tool（RAG）
RagTool knowledgeTool = RagTool.builder(ragService)
        .dataset("ecommerce-kb").embeddingModel("...").topK(5).build();

// ② 订单查询 tool（接业务系统）
Tool orderTool = Tool.function("query_order", "查订单状态/物流", orderParams);
ToolExecutor orderExecutor = call -> orderService.query(extract(call, "orderId"));

// ③ 创建工单 tool
Tool ticketTool = Tool.function("create_ticket", "为用户创建售后工单", ticketParams);
ToolExecutor ticketExecutor = call -> ticketService.create(...);

// 客服 agent：自主编排这三个能力
Agent customerService = Agents.react().anthropicMessages(key, baseUrl).model("glm-4.6")
        .system("你是电商客服。根据用户问题，自主决定查订单、查知识库、或建工单。")
        .toolRegistry(new StaticToolRegistry(Arrays.asList(
                knowledgeTool.tool(), orderTool, ticketTool)))
        .toolExecutor(merge(knowledgeTool.executor(), orderExecutor, ticketExecutor))
        .capture(new InMemoryIoCaptureSink())   // 整条客服链路（思考+多tool）全捕获
        .build();

AgentResult r = customerService.newSession().run("我昨天下的单什么时候到，不想要了能退吗？");
// agent 自主：query_order 查物流 → knowledge_search 查退款规则 → 综合回答（必要时 create_ticket）
```

**这套架构的威力**：
- **自主编排**：用户一句话，agent 决定调哪些 tool、什么顺序，不用硬编码 if/else 流程
- **RAG 是其中一个 tool**，和业务 tool 平级——RAG 的可观测**自动融进** agent 的 `IoCaptureSink`（不再有"RAG trace vs agent trace 割裂"）
- **整条客服链路可重放/恢复/审计**：哪个 tool 出错、哪步决策错，capture 里一目了然

这就是本文反复强调的"RAG 在哪都能用、agent 里也能接入"的落地形态：
- `/api/rag/ask` —— RAG 独立用（per-step trace，排障检索质量）
- `/api/agent/ask` —— RAG 进 agent（统一 capture，单 tool 场景）
- **客服 agent**（本节）—— RAG + 多 tool 的完整生产形态（自主编排 + 整体重放/审计）

### 20.2 真实运行（demo `/api/agent/customer-service`）

用户一句话："订单 ORD-12345 什么时候到？我不想要了能退吗？"

agent 自主编排（没硬编码流程）：

```
steps: 3 | toolCalls: 2          ← 自主调了 2 个 tool（开 thinking）
captured: total=5  MODEL=3  TOOL=2   ← 整链进 IoCaptureSink（因果序）

answer:
📦 订单状态：
- 订单 ORD-12345 已发货
- 预计送达时间：2026年7月4日
- 当前未签收

🔍 退款情况分析：
很抱歉，您的订单目前无法办理无理由退款，原因如下：
- 您的订单尚未签收，根据售后政策，退款申请需在签收后7天内发起
- 等您签收后，如果该商品非秒杀/活动商品且保持完好，可以在7天内申请无理由退款
```

agent 调了 `query_order`（查到 mock 订单：已发货/7月4日/未签收）+ `knowledge_search`（查到退款规则：签收后7天内），基于**两个 tool 的真实返回**综合回答——订单状态 + 退款政策 + 为什么现在不能退。整条链路（2 MODEL + 2 TOOL）全在 `IoCaptureSink`，可重放、可审计。

### 20.3 trace 的真正价值：每个节点的 input/output 都看得见

光说"捕获了 4 个节点"不够——trace 的价值在于**每个节点的实际 input/output 内容都可见**，排查时精准定位"哪一环不足"。demo 的 `capturedNodes` 把每步的 input/output 都序列化暴露了。上面那次客服请求的完整 trace：

```
NODE MODEL step=0  dur=2500ms  inTok=464 outTok=28     ← 第一次决策
  reasoning: "用户给了 ORD-12345 想退款…先调 query_order 查状态"
  output: [thinking, text, tool_use:query_order]

NODE TOOL  step=0  dur=3ms  (query_order)              ← 业务 tool
  input : {"orderId":"ORD-12345"}
  output: {"status":"shipped","signedAt":"未签收","amount":"¥299"}

NODE MODEL step=1  dur=13590ms  inTok=100 outTok=120  ← 基于订单,决策查退款规则
  reasoning: "shipped 未签收,用户要退款→查退款规则"
  output: [thinking, tool_use:knowledge_search]

NODE TOOL  step=1  dur=342ms  (knowledge_search = RAG) ← RAG sub-trace 可见,不再是黑盒!
  input : {"query":"退款规则"}
  RagResult: hits=5 citations=5
  RagTrace: retrieveMs=341 rerankMs=0 assembleMs=0 totalMs=342 | retrievedHits=5 rerankedHits=5
  output(context): "[S1] refund-rules.md # 退款规则 ## 退款申请时效 用户在订单签收后7天内..."

NODE MODEL step=2  dur=2530ms  inTok=719 outTok=110   ← 综合生成
  reasoning: "订单已发货未签收 + 退款规则(签收后7天)…综合回复"
  output: [thinking, text]
```

**节点按因果序排**（MODEL 决策 → TOOL 执行 → MODEL 再决策），不是 flush 序；**每个 MODEL 节点带 reasoning/token/duration**；**RAG TOOL 节点带 sub-trace**（hits + retrieve/rerank 各步耗时）——这是 ai4j 可观测的完整形态。

**这就是 trace 优势的落地**——假设这个客服 case 答错了，你逐节点质疑：
- TOOL `query_order`：参数对不对（orderId 传对没）？返回对不对（订单系统数据准没）？
- TOOL `knowledge_search`：RAG 检索的 query 对不对（改写/原始）？召回的 chunk 对不对（refund-rules 进了没）？
- MODEL step0：第一次决策对不对（该调哪些 tool、顺序对没）？
- MODEL step1：最终生成对不对（有没有基于 tool 结果、有没有幻觉）？

**每一环都能单独审视，精准定位不足**——这就是可观测系统对 agent 的价值，也是 `IoCaptureSink` 的意义（还能重放、从失败点恢复、审计）。

## 二十一、生产落地检查清单
架构（离在线解耦/版本化/接口抽象）/ 检索（过滤/chunk/rerank/拒答）/ 工程（缓存/线程池/限流熔断降级/异常演练）/ 安全（先过滤再生成/脱敏/审计）/ 观测（RagTrace 看召回排序/缓存命中/评测集）。

## 二十二、总结 + ai4j 当前真实不足

企业级 RAG 目标：检索有质量、生成有依据、架构可扩展、故障可治理、成本可控制、风险可收敛。

**ai4j 替你封装了主链**：`IngestionPipeline`（Tika/OCR/切块/embedding/upsert）、`DenseRetriever`/`Bm25Retriever`/`HybridRetriever`（RRF/RSF/DBSF）、`ModelReranker`/`Reranker` 接口、`RagService`、`RagContextAssembler`、`IMessagesService`/`IChatService`、`RagTrace`+`RagScoreDetail`、`RagEvaluator`、`VectorStore` 多后端、agent 级可观测（IoCaptureSink 重放/恢复/审计）。应用层只做业务编排 + 治理（缓存/限流/多租户/指标）。

**ai4j 当前真实不足（诚实，demo 都绕过或自补了）**：

1. **Query Rewrite**：demo 已实装（`QueryRewriteService`，GLM 改写，实测"退款怎么弄"→"退款操作流程"）；ai4j core 暂未提供统一 `QueryRewriter` 接口（按需补，当前应用层几十行即可）
2. **RAG 链路缓存无**（有意）——失效/key 维度强业务相关，留给应用层（demo 用 Caffeine）
3. ~~RAG-as-Agent-Tool 无现成封装~~ **已解决（ai4j 2.4.0）**：`RagTool`（PR #172）+ `AgentBuilder.capture()` 让 RAG 作为 agent tool 接入统一可观测链路
4. **Ollama 无 `/api/rerank` 端点**——`OllamaRerankService` 在 Ollama 上 404，本地 rerank 要用 LlmReranker 兜底或接 cloud（Jina/Doubao）
5. **starter 默认装配多个 VectorStore bean**——注入接口 `VectorStore` 会歧义，要注入具体类型
6. **starter 编译于 Spring Boot 2.3**（Java 8 约束）——在 SB 2.x（如本文的 2.7）原生兼容；SB 3 / JDK 17 项目也实测可用
7. **`@PostConstruct` 用 javax**（starter 内）——SB 3 靠 `javax.annotation-api` 兜，实测可用但非 jakarta 原生
8. **starter 的 pineconeVectorStore 无 `@ConditionalOnProperty` 守卫**——总被创建（导致上述多 bean 歧义）

这些不是"不能用于生产"，而是**用之前要知道、绕过或自补**。前 3 个是 SDK 能力缺口（QueryRewrite/Cache 可补，RAG-as-tool 看场景）；后 5 个是 starter 的工程小毛病（多 bean 守卫、SB 版本、jakarta 迁移），改起来不难。

---

## 附录：完整主线一张图

```
知识上传（md/PDF/Word/扫描件，分 public/premium）
  → ai4j IngestionPipeline（Tika/OCR + RecursiveTextChunker + DefaultMetadataEnricher
       + Ollama Qwen3-embedding + PgVector upsert 幂等）
  → InMemoryCorpus（供 Bm25Retriever/评估）

在线问答 POST /api/rag/ask
  → Caffeine 缓存命中？→ 直接返回（cached=true）
  → 否则：多租户 filter（permissionTag 前置）
       → DenseRetriever（PgVector KNN）
       → LlmReranker（GLM 打分重排）
       → DefaultRagContextAssembler（[S1][S2] 引用）
       → GLM generate（IMessagesService，拒答约束）
       → 答案 + references + 写缓存

质量评估 GET /api/rag/evaluate
  → HybridRetriever（Dense + Bm25 内存 + RRF 融合）
  → RagEvaluator（Recall@5 / MRR / NDCG）→ 评测集回归
```

ai4j 封装了摄入/检索/重排/组装/多后端/可观测/评估；Postgres 省掉专用向量库；Ollama 省掉 embedding 云账单。你只关注业务编排与工程治理。

> 完整 demo：[github.com/LnYo-Cly/ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)
> ai4j 本体：[github.com/LnYo-Cly/ai4j](https://github.com/LnYo-Cly/ai4j)
