# ai4j + PgVector + Ollama：用 Java 跑通一套企业级 RAG

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
| Web 框架 | Spring Boot 3.2 |
| AI SDK | ai4j 2.4.0（starter） |
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

### 11.2 混合召回与重排
- **`HybridRetriever`**：多 retriever（Dense 向量 + Bm25 关键词）结果用 `FusionStrategy`（RRF 最常用）融合，每个 hit 带 `RagScoreDetail`（哪个 retriever 贡献多少）。`Bm25Retriever` 是纯内存实现（构造时建倒排），不需要 ES——demo 评估端点用它。
- **重排**：召回 TopK=20 → rerank TopN=5。ai4j `ModelReranker` 接 Jina/Doubao/Standard。⚠️ **Ollama 原生没有 `/api/rerank` 端点**（实测 404），所以本地场景 demo 用 **`LlmReranker`**（让 GLM 对每个 hit 打分重排）作为兜底——这是没有专用 rerank 模型时的通用方案。

## 十二、PgVector 索引设计

```sql
CREATE TABLE ai4j_rag_demo (
  id text PRIMARY KEY,           -- chunkId = documentId#chunk-N
  dataset text,                  -- 知识库边界/版本
  content text,
  metadata jsonb,                -- permissionTag/sourceName/sectionTitle/...
  embedding vector(1024)
);
CREATE INDEX ... USING hnsw (embedding vector_cosine_ops);
```
真实查询"先限定租户/权限再 KNN"——PgVector 把过滤用 SQL where 和 KNN 在一条 SQL 完成，**先过滤再检索**，从根上避免越权召回。

## 十三、生产级代码实现

### 13.1 依赖（SB 3.2 + ai4j starter + pg + caffeine）
```xml
<dependency>
  <groupId>io.github.lnyo-cly</groupId>
  <artifactId>ai4j-spring-boot-starter</artifactId>
  <version>2.4.0</version>
</dependency>
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
```
ai4j starter 编译于 SB 2.3/Java 8，**实测 SB 3.2 运行时兼容**。

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

## 十四、高并发与可扩展
真实瓶颈是组合（Rewrite 占模型/embedding 吞吐/检索连接/rerank 延迟/LLM 最慢）。治理：资源池隔离（Web/摄入/embedding/rerank/LLM 独立线程池）、Resilience4j 限流熔断降级、四层缓存（Rewrite/Embedding/Retrieval/Answer，key 带 dataset+kbVersion+permissionScope+queryHash）、横向扩展（在线无状态、状态下沉 Postgres/MQ/缓存）。

## 十五、多租户、安全与权限隔离
正确顺序：`识别身份→计算可访问范围→查询附带过滤→结果再校验→生成`，不是"先全库召回再遮罩"。demo 用 `permissionTag`（public/premium）+ PgVector metadata jsonb where 前置过滤。**实测**：premium 租户问题能召回 marketing/merchant 内部规则，default 租户同样的问题**只能召回 public 文档，看不到 premium**（见十八章 Q2 vs Q3）。

## 十六、可观测性建设（统一链路）

ai4j 的完整可观测（节点 I/O 捕获 / 重放 / 失败恢复 / 断点续跑 / tamper-evident 审计）在 agent runtime。**关键：RAG 通过 `RagTool`（ai4j PR #172）作为 agent tool 接入后，检索就是 TOOL 节点，自动进 `IoCaptureSink`——RAG 与 agent 可观测统一，不再割裂。**

接入只需几行：

```java
RagTool ragTool = RagTool.builder(ragService)
        .dataset("ecommerce-kb").embeddingModel("...").topK(5).build();
Agent agent = Agents.react().anthropicMessages(key, baseUrl).model("glm-4.6")
        .toolRegistry(new StaticToolRegistry(Collections.singletonList(ragTool.tool())))  // RAG 作为 tool
        .toolExecutor(ragTool.executor())
        .capture(new InMemoryIoCaptureSink())   // MODEL + TOOL(含 RAG 检索) 节点全捕获
        .build();
```

demo `/api/agent/ask` 实测：agent 自主调 `knowledge_search`，capture 录到 **1 TOOL（RAG 检索）+ 2 MODEL** 节点（见 18.6）。

| 场景 | 能力 | 接入方式 |
|---|---|---|
| **agent + RAG 统一** | 节点 I/O 捕获、重放、恢复、断点续跑、审计 | `RagTool` + `.capture(IoCaptureSink)`（RAG 作为 tool 自动进 capture，PR #172） |
| **独立 RAG**（非 agent） | 检索过程可解释 | `RagResult.getTrace()`（retrievedHits/rerankedHits）+ `RagScoreDetail`（每 hit 融合分来源） |
| **应用级** | 指标/缓存 | `cacheStats()`（命中率/大小）+ Micrometer |

> OpenTelemetry 不在 ai4j core 引入（保持"零基础设施依赖"哲学，像 PgVector 用 JDK `java.sql`）；`RagTrace` / `IoCaptureSink` 已暴露完整数据，应用层桥接到 OTel/Micrometer（几行代码）。这是设计取舍，不是缺失。

## 十七、部署与发布
本地试点（单体+单 Postgres+本地 Ollama）→ 小规模（问答/摄入拆分+MQ+Prometheus）→ 中大型（多副本+worker 集群+读写分离+配置中心）。K8s 关注 HPA/连接池/内存/滚动发布索引一致性。

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
- 知识量涨 → PgVector→Milvus/Qdrant（`VectorStore` 不变）
- 复杂检索 → `HybridRetriever` 接 ES 做 `Bm25Retriever`
- 单问答→智能体 → ai4j-agent（ReAct+工具+记忆+compaction）；RAG 通过 `RagTool`（ai4j PR #172）作为 agent tool 接入，自动进 agent 可观测链路（重放/恢复/审计），不再割裂

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
6. **starter 锁 Spring Boot 2.3**（Java 8 约束）——实测 SB 3 运行时兼容，但编译期绑定 2.3
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
