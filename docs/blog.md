# ai4j + PgVector + Ollama：用 Java 跑通一套企业级 RAG

> RAG（Retrieval-Augmented Generation）已经成为企业知识问答、智能客服、制度助手、研发 Copilot 的标配能力。但大量团队的第一个 RAG 系统都是照着"选个 embedding → 切块写库 → TopK 相似度 → 塞进 prompt 调大模型"的模板做的，十几分钟跑起来，演示效果不错，一上线就翻车。
>
> 本文用一套**更轻、更通用**的技术栈——Java AI SDK **ai4j** + 你大概率已经有的 **PostgreSQL/pgvector** + 本地免费的 **Ollama embedding** + **GLM** 生成——从原理、架构、12 条设计原则、生产代码、高并发、多租户、可观测、部署一路讲到真实案例。配套有一个[开箱即用的 demo](https://github.com/LnYo-Cly/ai4j-rag-demo)，每个结论都能在代码里找到对应。

---

## 一、为什么很多 RAG Demo 一上线就失效

这是判断一切后续工作的前提，必须先讲透。

很多团队第一版做得很快：选 embedding 模型、读 PDF/Word、切块写进向量库、查询 TopK、把结果塞进 prompt 调大模型。十几分钟跑起来，页面效果也往往不错。但这类 demo 只能说明"RAG 可以做出来"，不能说明"RAG 可以稳定上线"。一旦进入真实业务，问题很快暴露：

- **文档量从几十篇涨到几十万 chunk**，索引构建、内存占用、召回噪声同时上升
- **用户问题变复杂后**，纯向量召回会漂移，误召回明显增多（尤其订单号、错误码、规则编号这类强关键词内容）
- **高并发下**，embedding、重排、LLM 调用互相争抢资源，接口 RT 抖动严重
- **知识更新后**，新旧索引混用，结果不一致、不可解释
- **多租户过滤放后面**，很容易越权召回和串库
- **出现错误答案时**，团队无法判断是切块问题、召回问题、重排问题还是 prompt 问题

根因在于：**RAG 从来不是"向量库 + 大模型"的简单拼装，而是"检索系统 + 生成系统 + 工程治理系统"的组合工程。**

真正的企业级 RAG 必须同时解决四类问题：

- 检索质量是否稳
- 在线链路是否快
- 离线入库是否可扩展
- 整体系统是否可治理

本文围绕 `Spring Boot + ai4j + PgVector + Ollama + GLM`，把一个 demo 逐步升级成一套可生产的企业级 RAG。

## 二、本文要解决的核心问题

1. **原理层**：embedding、向量检索、RAG pipeline 的本质
2. **架构层**：如何拆成离线摄入链路和在线问答链路
3. **工程层**：高并发、缓存、限流、熔断、降级、索引演进、多租户隔离
4. **代码层**：给出接近生产可用的 Java 代码（配套 demo 可直接跑）

为让讨论具体，统一使用一个真实感较强的电商场景。

## 三、业务场景：电商智能客服知识引擎

假设你负责一个电商平台智能客服系统：

- 日均会话量 100 万+
- 峰值问答 QPS 2000+
- 业务知识：退款规则、售后政策、物流时效、营销活动、商家规范
- 文档来源：运营后台、CMS、PDF 规章、知识库系统、工单沉淀 FAQ
- 目标 RT：P95 < 800ms，P99 < 1.5s
- 更新目标：知识变更后 1-3 分钟可查询
- 风险要求：必须支持多租户隔离、来源溯源、故障降级

核心难点不是"模型会不会回答"，而是：**回答能不能基于可信证据、高峰期能不能稳定服务、规则更新能不能快速生效、线上出问题能不能快速定位。**

## 四、先把原理讲透：embedding 与 RAG 的本质

### 4.1 embedding 到底在做什么

embedding 把文本映射到一个高维稠密向量空间，让"语义相近"的文本在空间中距离更近：

```
"如何申请退款"    -> [0.12, -0.38, 0.54, ...]
"退款流程是什么"  -> [0.10, -0.36, 0.57, ...]
"今天天气不错"    -> [-0.42, 0.16, -0.33, ...]
```

查询时把问题也转成向量，用相似度算法找最接近的 chunk。常见度量：

- **Cosine Similarity**（本文用，pgvector `<=>`）：关注方向相似性
- **Inner Product**：归一化向量常用
- **Euclidean Distance（L2）**：关注绝对距离

工程上要在召回质量和成本之间平衡——维度越高，单向量内存越大、索引构建越慢、传输成本越高。本文用 Qwen3-Embedding-0.6B（1024 维，中文好）。

### 4.2 RAG 的本质不是"补知识"，而是"补证据"

更准确的理解：**RAG 不是给模型补知识，而是给模型补证据。**

企业场景要模型回答的是当前版本的制度、当前生效的政策、私域文档、带权限边界的内部信息——这些都不该依赖模型参数记忆，而该依赖检索得到的外部上下文。

所以一个成熟的 RAG pipeline 不是简单两步，而是多段式：

```
用户问题
  -> Query Normalize / Rewrite
  -> Retrieve（向量召回 + 关键词召回 + 混合融合）
  -> Rerank
  -> Context Build
  -> Generate
  -> Post Process（引用标注、脱敏）
```

### 4.3 企业 RAG 的两条主链路

**离线链路（知识入库）**

```
文档采集 -> 内容解析 -> 清洗标准化 -> 语义切块 -> 元数据补全 -> 向量化 -> 建索引 -> 发布上线
```

**在线链路（问题回答）**

```
鉴权 -> 租户识别 -> 查询改写 -> 混合召回 -> 权限过滤 -> 重排 -> Prompt 组装 -> 大模型生成 -> 引用标注 -> 返回结果
```

两条链路必须解耦：入库是重 CPU/IO/内存任务，在线查询是低延迟任务；耦合在一个同步服务里，并发一定失稳。

## 五、为什么选择 ai4j + PgVector + Ollama

### 5.1 ai4j 的价值

ai4j 的核心价值不是"能调大模型"，而是把 AI 能力做了**统一抽象**：

1. **模型协议三套并列**：`IChatService`（OpenAI Chat）/ `IResponsesService`（OpenAI Responses）/ `IMessagesService`（Anthropic Messages）。GLM 的 coding-plan key 走 Anthropic 兼容入口，直接用 `IMessagesService` 原生协议，零格式转换。
2. **RAG 组件开箱即用**：`IngestionPipeline`（加载→切块→embedding→upsert）、`DenseRetriever` / `Bm25Retriever` / `HybridRetriever`（含 RRF/RSF/DBSF 三种融合策略）、`ModelReranker`（接 Ollama/Jina/Doubao rerank）、`RagService`（检索+重排+组装一步到位）、`RagEvaluator`（Recall/MRR/NDCG 质量评估）。
3. **向量后端统一**：`VectorStore` SPI 收口 Pinecone / Qdrant / Milvus / PgVector / Redis Stack 五个后端，业务主链换后端不改代码。

对一个要长期演进的企业系统，"主链调用方式统一"比"跑通第一个 demo"重要得多。

### 5.2 为什么 PgVector 适合作为企业 RAG 的第一站

1. **复用已有基础设施**：大多数 Java 团队本来就有 PostgreSQL。`CREATE EXTENSION vector` 就能用，不用额外引入一套中间件。
2. **过滤能力更强**：pgvector 的元数据过滤直接用 SQL `where`（JSONB、普通列、JOIN 都行），表达力远强于专用向量库的 query DSL。企业 RAG 的"先过滤再检索"在 PgVector 上最自然。
3. **一库多用**：Postgres 同时承担业务库 + 向量库，少一套中间件、少一套运维。

它也有边界：极大规模向量数据下，专用向量库（Milvus 等）的检索性能更好。但 ai4j 的 `VectorStore` 抽象让你后续迁移时**业务主链不改**。

### 5.3 为什么 Ollama 适合做 embedding

- **本地、免费**：不消耗云 API 额度，开发期零成本
- **中文好**：Qwen3-Embedding-0.6B 对中文语义支持优秀
- **零网络延迟**：embedding 是高频调用，本地省 RTT

生产高峰期可以切云端 embedding（`IEmbeddingService` 换实现即可），开发期用 Ollama 足够。

## 六、企业级 RAG 架构设计

### 6.1 总体分层架构

```
┌───────────────────────────────────────────────────────────────┐
│                        接入层 / Controller                      │
│   ChatController：协议适配、参数校验、身份透传、响应封装          │
└───────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌───────────────────────────────────────────────────────────────┐
│                  RagQueryService（应用编排）                    │
│   检索→上下文组装→生成→引用组装；缓存协同、超时、降级            │
└───────────────────────────────────────────────────────────────┘
          │                                    │
          ▼                                    ▼
┌──────────────────────┐           ┌────────────────────────────┐
│ ai4j RagService       │           │ ai4j IMessagesService       │
│ HybridRetriever       │           │ (GLM via Anthropic Messages)│
│ +ModelReranker        │           │  生成                        │
│ +ContextAssembler     │           └────────────────────────────┘
└──────────────────────┘
          │
          ▼
                 ┌──────────────────────────────┐
                 │  PgVectorStore (PostgreSQL)   │
                 │  向量检索 + 元数据过滤         │
                 └──────────────────────────────┘
                               ▲
                               │
                 ┌──────────────────────────────┐
                 │  IngestionPipeline（离线摄入） │
                 │  Tika/OCR 解析 + 切块 +        │
                 │  Ollama embedding + upsert     │
                 └──────────────────────────────┘
```

### 6.2 生产系统中的核心边界

企业级 RAG 不该只有一个 `RagService` 包打天下，要清晰拆分：

- **Controller**：协议适配、参数校验、身份透传、响应封装
- **RagQueryService**：编排一次问答（检索→生成→降级→引用组装）
- **KnowledgeIngestionService**：独立于在线链路，负责解析、切块、入库
- **ai4j 组件**：`IngestionPipeline` / `RagService` / `IMessagesService` 提供 RAG 主链
- **PgVectorStore**：底层向量检索

边界清晰，后续每加一个能力（多租户、重排、灰度索引、混合检索）才不会让问答主服务膨胀。

## 七、核心设计原则：从 Demo 升级到生产必须补齐的 12 件事

这 12 条与具体技术栈无关，是 RAG 走向生产的通用底线。每条标注本文如何落地。

**7.1 检索优先于生成**——大部分错误回答根因在召回错误或上下文污染。ai4j `RagService` 把检索+组装收进主链，应用层重点盯检索质量。

**7.2 离线摄入与在线查询彻底解耦**——`KnowledgeIngestionService` 独立跑，可改 MQ 驱动 worker。

**7.3 元数据是一等公民**——每个 chunk 至少有 `dataset/documentId/chunkIndex/sourceName/sectionTitle`。ai4j `RagChunk` + `RagMetadataKeys` 内置；PgVector 用 JSONB 存。

**7.4 索引必须版本化**——PgVector 用 `dataset` 作为版本边界（`ecommerce-kb-v1` / `ecommerce-kb-v2`），双 dataset 并行 + 原子切换。

**7.5 权限过滤必须前置**——PgVector 的 `VectorSearchRequest.filter` 在 KNN 前用 SQL where 过滤，先过滤再检索再生成。

**7.6 模型调用必须被治理**——超时/重试/熔断/限流/降级，应用层 Resilience4j 包 `IMessagesService`。

**7.7 缓存是基础设施**——Rewrite/Embedding/Retrieval/Answer 四层缓存，key 带 `dataset/kbVersion/permissionScope/queryHash`。⚠️ **ai4j 当前不提供 RAG 缓存**，留给应用层（Caffeine/Redis），下文会明确指出。

**7.8 不要只做向量召回**——订单号、错误码、规则编号需要关键词检索。ai4j 的 `HybridRetriever`（向量 `DenseRetriever` + 关键词 `Bm25Retriever` + RRF 融合）正是为此而生。

**7.9 Prompt 预算必须受控**——ai4j `RagContextAssembler` 组装上下文 + 应用层 token 预算裁剪。

**7.10 可观测性从第一天开始**——ai4j 的 `RagResult` 自带 `RagTrace`（retrievedHits/rerankedHits）和 `RagScoreDetail`（每个 hit 的融合分数来源），应用层接进 Micrometer。

**7.11 质量评估必须标准化**——ai4j 内置 `RagEvaluator`，算 Recall@K / Precision / MRR / NDCG / F1。建评测集持续评估。

**7.12 先设计演进路径，再选组件**——不要把 PgVector 当架构本身。ai4j `VectorStore` 抽象让你后续换 Milvus/Qdrant 主链不改。

## 八、技术选型建议

| 维度 | 方案 | 说明 |
| --- | --- | --- |
| Web 框架 | Spring Boot 3.2 | Java 企业主流，便于接治理体系 |
| AI 接入 | ai4j 2.4.0 | 统一 LLM/Embedding/VectorStore/RAG 抽象 |
| Embedding | Ollama + Qwen3-Embedding-0.6B | 本地免费，中文好，1024 维 |
| Chat 模型 | GLM（Anthropic Messages 协议） | 走 `IMessagesService` |
| 向量存储 | PostgreSQL + pgvector | 复用 Postgres，过滤强 |
| 重排 | Ollama / Jina / Doubao rerank | `ModelReranker` 接 |
| MQ | Kafka / RocketMQ（按需） | 知识摄入异步化 |
| 可观测 | Micrometer + Prometheus + Grafana | 指标、告警 |
| 配置中心 | Nacos | 动态调 TopK、阈值、模型开关 |

工程原则：**先把架构接口抽象好（ai4j 已经做了），再决定底层组件。**

## 九、项目结构设计

```
ai4j-rag-demo
├── pom.xml
├── README.md
├── docs/blog.md
├── src/main/java/io/github/lnyocly/ai4j/rag/demo/
│   ├── RagDemoApplication.java          # Spring Boot 入口
│   ├── config/
│   │   ├── RagProperties.java           # 业务配置（dataset/model/topK）
│   │   └── PgVectorSchemaInitializer.java  # 建表/索引（@PostConstruct）
│   ├── domain/
│   │   ├── ChatRequest.java
│   │   ├── RagAnswer.java
│   │   └── ReferenceItem.java
│   ├── service/
│   │   ├── KnowledgeIngestionService.java  # 离线摄入（IngestionPipeline）
│   │   └── RagQueryService.java            # 在线编排（RagService + GLM）
│   └── controller/
│       └── ChatController.java             # REST: POST /api/rag/ask
└── src/main/resources/
    ├── application.yml
    └── knowledge/                          # 样例知识（退款/物流/售后）
```

这套结构的核心价值：**在线问答和离线摄入分层、领域规则和基础设施隔离**。

## 十、知识摄入架构：离线链路怎么设计才扛得住规模

### 10.1 不要同步入库

同步完成解析→切块→embedding→写库，在生产环境会带来：上传接口 RT 极长、重任务和在线查询争抢资源、失败重试和幂等控制困难。正确做法是事件驱动：

```
Admin Upload API -> Object Storage -> Create Ingestion Task
  -> MQ publish(document_uploaded) -> Parser Worker -> Chunker Worker
  -> Embedding Worker -> PgVector upsert -> Publish Version
```

### 10.2 ai4j 的 IngestionPipeline 把摄入主链收口了

ai4j 的 `IngestionPipeline` 内部完成"加载 → 切块 → metadata 富化 → 批量 embedding → upsert"，应用层只管喂文档：

```java
IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
pipeline.ingest(IngestionRequest.builder()
        .dataset("ecommerce-kb")
        .embeddingModel("hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:latest")
        .source(IngestionSource.text(content))           // 也支持 file()/uri()
        .document(RagDocument.builder()
                .documentId(docId)                        // 文件名确定性派生 -> 重启幂等
                .sourceName(filename)
                .title(filename)
                .build())
        .build());
```

它内置了几个生产级组件（都在 `io.github.lnyocly.ai4j.rag.ingestion`）：

- **`TikaDocumentLoader`**：基于 Apache Tika，支持 PDF / Word / Excel / HTML 等几十种格式
- **`OcrTextExtractingDocumentProcessor` / `OcrNoiseCleaningDocumentProcessor`**：图片型 PDF 的 OCR 提取与去噪——很多企业知识是扫描件，这块不补等于残废
- **`RecursiveTextChunker`**：递归语义切块（默认 1000 字符、200 overlap，可调）
- **`DefaultMetadataEnricher`**：自动补全 documentId/sourceName/sourcePath 等元数据
- **`WhitespaceNormalizingDocumentProcessor`**：空白归一化

> 这些组件都可替换：实现 `Chunker` / `DocumentLoader` / `MetadataEnricher` / `LoadedDocumentProcessor` 接口注入即可。

### 10.3 为什么必须有 staging（版本化）

直接把新版本 chunk 写进线上索引会出现：查询结果新旧混杂、部分文档失败导致结果不完整、回滚困难。稳妥做法是双 dataset：

- `ecommerce-kb-v1`（active）
- `ecommerce-kb-v2`（staging）

新版本先写 staging，校验通过后原子切换。PgVector 用 `dataset` 列天然支持这种隔离。

### 10.4 文档切块策略

切块质量决定召回上限。生产建议：

1. 按语义边界切，而不是简单按字符数
2. 保留章节标题和路径到 metadata
3. 控制 chunk 长度和 overlap

中文文档常见实践：chunk 目标 300-600 中文字、overlap 50-100 字、标题前缀进 chunk content。

> 关键误区：chunk 不是为了"切得均匀"，而是为了"被单独召回时仍有足够语义完整性"。

## 十一、在线问答架构：一次请求在系统里如何流转

### 11.1 标准处理链路

```
1. 鉴权与租户识别
2. 问题归一化
3. 热点缓存检查
4. Query Rewrite
5. Embedding
6. 混合召回（向量 + 关键词 + 融合）
7. 元数据过滤（前置）
8. Rerank
9. 上下文压缩与组装
10. Prompt 构建
11. LLM 生成
12. 引用溯源与后处理
13. 结果缓存
```

本文 demo 聚焦主链（5/6/7/9/11/12），缓存/改写等增强项在生产中补齐。

### 11.2 Query Rewrite

用户原始问题经常很短或上下文模糊（"退款怎么弄""发票""为什么不支持"）。Rewrite 价值：补充业务语义、规范化口语、对齐知识库正式术语。

> ⚠️ **诚实说明：ai4j 当前不提供开箱的 Query Rewrite 组件。** 应用层自己实现（用 `IMessagesService`/`IChatService` 调一次 LLM 改写，或先做规则归一化）。高频问题走规则、长尾走 LLM 改写。这是 ai4j 当前的真实缺口之一。

### 11.3 混合召回与重排

纯向量召回对订单号、错误码这类强关键词内容效果差。ai4j 给了完整方案：

**混合检索 `HybridRetriever`**：把多个 retriever（如 `DenseRetriever` 向量 + `Bm25Retriever` 关键词）的结果用融合策略合并：

```java
Retriever hybrid = new HybridRetriever(
        Arrays.asList(denseRetriever, bm25Retriever),
        new RrfFusionStrategy()    // 倒数排名融合；另有 RsfFusionStrategy / DbsfFusionStrategy
);
```

`HybridRetriever` 内部对每个 retriever 的结果按 `FusionStrategy`（RRF 最常用）算贡献分、合并去重、按融合分数排序，每个 hit 还带 `RagScoreDetail`（哪个 retriever 贡献多少）——可解释性直接拉满。

**重排 `ModelReranker`**：召回 TopK=20 → rerank TopN=5。接 Ollama/Jina/Doubao rerank 模型：

```java
Reranker reranker = aiService.getModelReranker(PlatformType.OLLAMA, "qwen3-reranker:0.6b");
// 或 new ModelReranker(rerankService, model, topN, instruction, ...)
```

`ModelReranker` 内部把每个 hit 包装成 `RerankDocument`（带 title/content/metadata），调 `IRerankService.rerank`，按 `relevanceScore` 重排，写回 `rerankScore`。

> 推荐两段式：向量+关键词混合召回 TopK=20 → 过滤去重 → Rerank TopN=5 → 上下文组装。没有专用 rerank 模型时，先用 `NoopReranker`，或写基于业务规则的 `Reranker` 实现。

## 十二、PgVector 向量索引设计

### 12.1 表里存什么

```sql
CREATE TABLE ai4j_rag_demo (
  id         text PRIMARY KEY,      -- chunkId（documentId#chunk-N，确定性派生）
  dataset    text,                  -- 知识库边界 / 版本
  content    text,                  -- chunk 正文
  metadata   jsonb,                 -- 元数据（sourceName/sectionTitle/pageNumber...）
  embedding  vector(1024)           -- Qwen3-Embedding 输出维度
);
```

### 12.2 chunk 元数据建议

| 字段 | 作用 |
| --- | --- |
| `dataset` | 多租户 / 版本隔离 |
| `documentId` | 文档定位 |
| `chunkIndex` | chunk 序号 |
| `sourceName` | 引用展示 |
| `sectionTitle` | 引用展示 |
| `pageNumber` | PDF 定位 |

ai4j 的 `RagChunk` + `RagMetadataKeys` 已内置这些约定。

### 12.3 HNSW 还是 IVFFlat

- **HNSW**：近似最近邻，查询快、建索引慢、占内存多。中大规模首选。本文用这个。
- **IVFFlat**：倒排+精确，建索引快，查询精度依赖参数。

```sql
CREATE INDEX ai4j_rag_demo_emb_idx
  ON ai4j_rag_demo USING hnsw (embedding vector_cosine_ops);
```

### 12.4 不要忽略过滤字段

真实查询往往是"先限定租户、知识库、状态和权限，再做向量搜索"。PgVector 的优势正在于此——过滤直接用 SQL where，和 KNN 在一条 SQL 里完成。ai4j `PgVectorStore` 的 `VectorSearchRequest.filter` 会被翻译成 JSONB 上的 `where` 条件，**先过滤再 KNN**，从根上避免越权召回。

## 十三、生产级代码实现

完整代码见 [ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)。下面给出关键部分。

### 13.1 Maven 依赖

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.lnyo-cly</groupId>
        <artifactId>ai4j-spring-boot-starter</artifactId>
        <version>2.4.0</version>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
</dependencies>
```

ai4j starter 编译于 SB 2.3 / Java 8，**实测在 Spring Boot 3.2 运行时兼容**——autoconfigure 装配、`@PostConstruct` init、`AiService` bean 全部正常工作。

### 13.2 application.yml

```yaml
ai:
  vector:
    pgvector:
      enabled: true
      jdbc-url: jdbc:postgresql://localhost:5432/postgres
      username: postgres
      password: postgres
      table-name: ai4j_rag_demo
      distance-operator: "<=>"
  ollama:
    api-host: http://localhost:11434/
    embedding-url: api/embed
  anthropic:
    api-host: https://open.bigmodel.cn/api/anthropic/   # GLM coding-plan 兼容入口
    api-key: ${GLM_API_KEY}
    chat-completion-url: v1/messages

rag:
  dataset: ecommerce-kb
  embedding-model: hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:latest
  vector-dimension: 1024
  glm-model: glm-4.6
  top-k: 5
```

### 13.3 PgVector schema 初始化（@PostConstruct，保证摄入前表存在）

```java
@PostConstruct
public void initSchema() {
    try (Connection c = DriverManager.getConnection(jdbcUrl, username, password);
         Statement s = c.createStatement()) {
        s.execute("CREATE EXTENSION IF NOT EXISTS vector");
        s.execute("CREATE TABLE IF NOT EXISTS " + tableName
                + " (id text PRIMARY KEY, dataset text, content text, metadata jsonb,"
                + " embedding vector(" + dim + "))");
        s.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_emb_idx ON " + tableName
                + " USING hnsw (embedding vector_cosine_ops)");
    } catch (Exception e) {
        throw new RuntimeException("Failed to init PgVector schema: " + e.getMessage(), e);
    }
}
```

> 踩过的坑：**不要用两个 `@Bean ApplicationRunner` + `@Order` 来保证"建表先于摄入"**——`@Order` 对 `@Bean` 方式声明的 runner 不可靠，实测会出现摄入先跑、报表不存在的错误。改用 `@PostConstruct` 建表（在 bean 初始化阶段，早于所有 ApplicationRunner），顺序才确定。

### 13.4 离线摄入（ai4j IngestionPipeline）

```java
IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
IngestionResult result = pipeline.ingest(IngestionRequest.builder()
        .dataset(ragProperties.getDataset())
        .embeddingModel(ragProperties.getEmbeddingModel())
        .source(IngestionSource.text(content))
        .document(RagDocument.builder()
                .documentId(docId)                        // 文件名确定性派生 -> 重启幂等
                .sourceName(filename)
                .title(filename)
                .build())
        .build());
```

`IngestionPipeline` 把"加载→切块→metadata 富化→批量 embedding→upsert"全包了。`documentId` 用文件名确定性派生（`UUID.nameUUIDFromBytes`），保证重启幂等——upsert 走 PgVector 的 `ON CONFLICT (id) DO UPDATE`，同 chunkId 不会重复累积。

### 13.5 在线检索（ai4j RagService，含 rerank + 组装）

最简形式（默认 NoopReranker）：

```java
RagService ragService = aiService.getRagService(PlatformType.OLLAMA, vectorStore);
RagResult result = ragService.search(RagQuery.builder()
        .query(question)
        .dataset(dataset)             // 权限/租户前置过滤的硬边界
        .embeddingModel(embeddingModel)
        .topK(5)
        .build());

String context = result.getContext();                  // 组装好的上下文（带 [S1] [S2] 引用标记）
List<RagCitation> citations = result.getCitations();   // 引用溯源
List<RagHit> hits = result.getHits();                  // 命中的 chunk
RagTrace trace = result.getTrace();                    // retrievedHits + rerankedHits（可观测）
```

要开启**模型重排 + 混合检索**，自己组装 `DefaultRagService`：

```java
Retriever dense = new DenseRetriever(embeddingService, vectorStore);
Retriever bm25  = /* Bm25Retriever（需要索引支撑，如接 ES/MariaDB 全文索引） */;
Retriever hybrid = new HybridRetriever(Arrays.asList(dense, bm25), new RrfFusionStrategy());
Reranker reranker = aiService.getModelReranker(PlatformType.OLLAMA, "qwen3-reranker:0.6b");
RagService ragService = new DefaultRagService(hybrid, reranker, new DefaultRagContextAssembler());
```

`DefaultRagContextAssembler` 会自动给每条命中生成 `S1/S2/...` 引用编号、带 sourceName/sectionTitle/pageNumber，组装成带引用标记的上下文文本——LLM 生成时就能引用 `S1`、`S2`，前端展示也直接用 `citations`。

### 13.6 生成（GLM via Anthropic Messages）

```java
AnthropicChatCompletion req = new AnthropicChatCompletion();
req.setModel(ragProperties.getGlmModel());
req.setSystem("你是企业电商知识助手。严格根据下方参考资料回答用户问题。"
            + "若资料不足以支撑答案，请明确说明\"根据当前知识库资料无法确认\"，"
            + "不要编造制度、流程或时效。回答要简洁、准确、可执行。");
req.setMessages(Collections.singletonList(
        new AnthropicMessage("user", "参考资料：\n" + context + "\n\n用户问题：" + question)));
req.setMaxTokens(1024);
AnthropicChatCompletionResponse resp = messagesService.messages(req);
String answer = extractText(resp);   // 遍历 content blocks 取 text
```

注意 prompt 里的**拒答约束**——知识不足时明确说"无法确认"，这是抑制幻觉的关键。

### 13.7 应用编排（RagQueryService）

```java
public RagAnswer ask(ChatRequest request) throws Exception {
    RagResult result = ragService.search(buildQuery(request));
    String context = result.getContext() == null ? "" : result.getContext();
    boolean degraded = context.isBlank();

    List<ReferenceItem> references = result.getCitations().stream()
            .map(c -> ReferenceItem.builder()
                    .sourceName(c.getSourceName())
                    .sectionTitle(c.getSectionTitle())
                    .snippet(c.getSnippet())
                    .build())
            .collect(Collectors.toList());

    String answer = generate(request.getQuestion(), context, degraded);
    return RagAnswer.builder()
            .answer(answer)
            .references(references)
            .hitCount(result.getHits().size())
            .degraded(degraded)
            .build();
}
```

这段体现的生产思路：检索分层、检索为空可控降级、最终返回引用来源支持溯源、degraded 标记方便排障。

### 13.8 对外接口

```java
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class ChatController {
    private final RagQueryService ragQueryService;

    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody ChatRequest request) throws Exception {
        return ragQueryService.ask(request);
    }
}
```

接口返回不只是答案，还附带 `references` / `hitCount` / `degraded`——这些信息直接影响线上排障、前端展示和运营复盘。

## 十四、高并发与可扩展设计

这是 RAG 上线后最容易出事故的地方，必须认真对待。

### 14.1 高并发场景的真实瓶颈

RAG 系统瓶颈通常不是单点，而是几个组合：

- Query Rewrite 占用模型资源
- embedding 调用吞吐有限
- 向量检索高并发下数据库连接数暴涨
- Rerank 模型延迟波动
- LLM 生成最慢、最贵、最不稳定

### 14.2 资源隔离

至少拆分这些线程池：Web 请求、知识摄入、embedding、rerank、LLM 生成。不要让文档入库和用户问答共享同一套线程资源。

```java
@Bean("ingestionExecutor")
public Executor ingestionExecutor() { /* ... */ }

@Bean("generationExecutor")
public Executor generationExecutor() { /* ... */ }
```

### 14.3 限流、熔断、降级

生产必须假设模型服务会抖动。治理顺序：

1. **限流**：避免瞬时流量打爆模型调用和 Postgres
2. **超时**：每个阶段独立超时，不只网关层
3. **熔断**：外部模型连续失败时快速失败
4. **降级**：返回缓存答案 / 关闭 Rewrite / 关闭 Rerank / 返回 FAQ 静态答案

Resilience4j 配置示意：

```yaml
resilience4j:
  timelimiter:
    instances:
      ragGenerate:
        timeout-duration: 2s
  circuitbreaker:
    instances:
      ragGenerate:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

### 14.4 缓存策略

推荐四层缓存：Rewrite Cache、Embedding Cache、Retrieval Cache、Answer Cache。

> ⚠️ **诚实说明：ai4j 当前不提供 RAG 链路缓存。** 这是有意为之——缓存策略（失效、key 维度、序列化）和业务强相关，SDK 不该替你决定。应用层用 Caffeine（本地）或 Redis（分布式）实现。缓存 key 必须带 `dataset / kbVersion / permissionScope / queryHash`，否则知识更新后用户看到旧答案、带权限差异的答案被错误复用——这是 RAG 缓存最容易踩的坑。

### 14.5 横向扩展

沿三个方向：问答服务无状态化多副本、摄入 worker 独立扩缩容、Postgres 读写分离/分库。关键原则：**在线服务无状态，状态下沉到 Postgres / MQ / 缓存**。

## 十五、多租户、安全与权限隔离

RAG 最危险的不是"答错"而是"串库"。

### 15.1 权限过滤的正确顺序

```
识别用户身份 -> 计算可访问范围 -> 查询时附带过滤 -> 检索结果再校验 -> 交给模型生成
```

而不是"先全库召回 → 生成答案 → 再遮罩"。PgVector + ai4j 的组合让"查询时附带过滤"很自然：

```java
RagQuery query = RagQuery.builder()
        .query(question)
        .dataset(tenantKbVersion)                         // 租户 + 版本硬边界
        .topK(5)
        .filter(permissionFilterMap(accessScope))          // 权限标签前置过滤
        .build();
```

`filter` 会被翻译成 PgVector metadata JSONB 上的 `where` 条件，和 KNN 在一条 SQL 里完成。

### 15.2 推荐的权限模型

每个 chunk 打权限标签（`public / employee / customer-service / merchant / finance-admin`），用户进入系统映射为 `AccessScope(tenantId, permissionTags)`。检索层召回前过滤，召回结果可以再校验一遍（防 SDK bug 或配置错误）。

### 15.3 敏感信息脱敏

回传给模型的上下文建议做手机号/身份证/银行卡/邮箱脱敏。这不等于权限隔离，是第二层保护。

## 十六、可观测性建设

企业 RAG 不可观测就不可治理。

### 16.1 指标体系

| 指标 | 说明 |
| --- | --- |
| `rag.query.qps` | 问答请求量 |
| `rag.query.latency` | 全链路耗时 |
| `rag.retrieve.latency` | 向量召回耗时 |
| `rag.rerank.latency` | 重排耗时 |
| `rag.generate.latency` | LLM 生成耗时 |
| `rag.answer.cache.hit` | 答案缓存命中 |
| `rag.degrade.count` | 降级次数 |
| `rag.recall.empty.count` | 空召回次数 |
| `rag.context.length` | 上下文长度 |

### 16.2 用 ai4j 自带的 RagTrace 做请求级追溯

ai4j 的 `RagResult.getTrace()` 返回 `RagTrace`，里面有 `retrievedHits`（召回原始顺序）和 `rerankedHits`（重排后顺序）。配合 `RagHit` 上的 `RagScoreDetail`（哪个 retriever 贡献多少分、rerankScore 多少），一次请求的检索过程**完全可解释**。把它写进结构化日志：

```
traceId / tenantId / userId / 原始 query / 改写后 query
/ recall count / top references / rerank 前后变化
/ final prompt size / 是否降级 / 总耗时
```

注意日志脱敏（用户输入和文档正文片段）。

### 16.3 质量评估：用 ai4j RagEvaluator 建评测闭环

很多团队上线后就再没评估过检索质量，全靠"用户感觉"。ai4j 内置 `RagEvaluator`，建一份离线评测集（query + 相关 chunkId），定期跑：

```java
RagEvaluator evaluator = new RagEvaluator();
RagEvaluation eval = evaluator.evaluate(result.getHits(), relevantChunkIds, topK);
// eval.getRecallAtK() / getPrecisionAtK() / getMrr() / getNdcg() / getF1AtK()
```

`RagEvaluation` 给出 Recall@K、Precision@K、F1、MRR、NDCG——每次调切块/embedding 模型/rerank，都用这套指标回归，避免改 A 坏 B。其中 **MRR 和 NDCG** 比单纯 Recall 更能反映"相关结果排得够不够靠前"。

## 十七、部署与发布

### 17.1 本地试点
单体 Spring Boot + 单实例 Postgres（pgvector）+ 本地 Ollama。先验证切块、召回、回答效果。这一步本文 demo 就是。

### 17.2 小规模生产
问答服务与摄入服务拆分；Postgres 独立部署；MQ 引入异步摄入；Prometheus/Grafana 接入。

### 17.3 中大型生产
API Gateway + 多副本问答服务；摄入 worker 集群；Postgres 读写分离；配置中心动态治理；索引版本发布平台；离线评测平台。

### 17.4 Kubernetes 关注点
HPA 扩缩容依据、模型调用超时和连接池、Postgres 连接数上限、Pod 内存峰值、摄入与在线分离、滚动发布期间索引版本一致性。

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rag-query-service
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: app
          image: registry.example.com/rag-query-service:1.0.0
          env:
            - name: GLM_API_KEY
              valueFrom: { secretKeyRef: { name: rag-secrets, key: glm-api-key } }
          resources:
            requests: { cpu: "500m", memory: "1Gi" }
            limits: { cpu: "2", memory: "4Gi" }
```

## 十八、真实案例：退款规则问答是如何跑通的

### 18.1 原始知识

知识库三份文档：`refund-rules.md` / `after-sales.md` / `logistics.md`，启动时由 `KnowledgeIngestionService` 自动摄入（切块 → Qwen3 embedding → PgVector upsert，幂等）。

### 18.2 用户提问

```
秒杀商品签收后还能申请退款吗？
```

### 18.3 系统链路

1. embedding（Ollama Qwen3-0.6B）→ PgVector cosine KNN 召回 TopK
2. 召回结果（按相关度）：
   - `refund-rules.md`（秒杀活动商品退款限制）
   - `after-sales.md`（质量问题售后）
   - `logistics.md`（签收认定）
3. `DefaultRagContextAssembler` 组装带 `[S1] [S2]` 引用标记的上下文
4. GLM 生成

### 18.4 GLM 的真实回答

> 关于秒杀商品在签收后的退款政策：
> 1. **不支持 7 天无理由退款**——参与秒杀活动的特殊商品不支持 7 天无理由退款（S1）。
> 2. **存在质量问题可申请退款**——不受活动商品限制约束，可按售后规则申请（S1、S2）。
> 3. **时效**——质量问题售后需在签收后 15 天内申请（S2）；退款审核 1-3 个工作日，到账 3-5 个工作日（S1）。
>
> `references`: [refund-rules.md（秒杀活动商品退款限制）, after-sales.md（质量问题售后）]

### 18.5 这个案例体现了什么

- 不是只召回"最相似一段"，而是把"活动例外规则"和"通用规则"一起组织成可解释答案
- 答案带来源引用（S1/S2），可回溯、可审核
- 检索质量决定答案质量——召回对了，模型才答对；拒答约束让它在资料不足时说"无法确认"而不是瞎编

## 十九、常见问题与高频坑位

**19.1 只做 TopK 向量检索，不做过滤**——多租户串库、新旧版本混召、权限越权。→ PgVector 用 `dataset` + `filter` 前置过滤。

**19.2 chunk 切得太碎**——召回片段缺乏独立语义，模型读完也无法回答。

**19.3 chunk 切得太大**——噪声高、prompt 变长、成本和时延上升。

**19.4 缓存 key 不带版本**——知识更新后用户仍看到旧答案。

**19.5 只盯着模型，不看检索**——不断换模型效果却不稳，根因在召回。

**19.6 没有拒答策略**——知识不足时模型仍努力生成，导致幻觉。→ system prompt 明确"资料不足就说无法确认"。

**19.7 用 `@Order` 控制 `@Bean ApplicationRunner` 顺序**——不可靠，会出现建表没跑、摄入先跑报表不存在。→ 改用 `@PostConstruct` 做强前置依赖。

**19.8 把 starter 当只能配一个 VectorStore**——ai4j starter 默认会装配多个 VectorStore bean（PgVector/Pinecone 等），注入时用具体类型（`PgVectorStore`）而不是接口（`VectorStore`），否则歧义。

## 二十、从 PgVector 起步，未来如何继续演进

### 20.1 当知识量继续增长
PgVector 继续承担检索；ai4j `VectorStore` 接口不变，迁移到 Milvus/Qdrant 业务层不改。

### 20.2 当检索需求更复杂
复杂关键词检索、大规模混合检索 → `HybridRetriever` 接 ES/OpenSearch 作为 `Bm25Retriever` 的实现，主链不变。

### 20.3 当业务从单问答升级为智能体系统
RAG 会变成其中一个能力模块。ai4j 的 `ai4j-agent` 提供 ReAct agent + 工具调用 + 记忆 + compaction。

> ⚠️ **诚实说明：ai4j 当前没有"RAG-as-Agent-Tool"的现成集成**——把 `RagService` 包成一个 agent tool 需要用户自己写一层薄封装（实现 tool 接口、内部调 `ragService.search`）。这是 ai4j 当前可以补强的方向之一。

## 二十一、生产落地检查清单

**架构层**
- 是否完成离线摄入和在线问答解耦
- 是否设计了索引版本化和回滚机制
- 是否定义了清晰的领域接口抽象（ai4j 已提供）

**检索层**
- 是否支持租户、版本、权限过滤
- 是否评估过 chunk 策略
- 是否有重排或二次打分（`ModelReranker`）
- 是否有拒答阈值

**工程层**
- 是否做了缓存（应用层）
- 是否做了线程池隔离
- 是否做了限流、超时、熔断、降级
- 是否对模型依赖做了异常演练

**安全层**
- 是否先过滤再生成
- 是否做了敏感信息脱敏
- 是否支持审计和引用追溯（`RagCitation`）

**观测层**
- 是否能看到召回结果和排序变化（`RagTrace`）
- 是否能统计缓存命中率和降级次数
- 是否有离线评测集（`RagEvaluator`）

## 二十二、总结：企业级 RAG 的核心，不是"接上模型"，而是"做成系统"

回到开头：为什么很多 RAG demo 一上线就失效？因为它们只做了最前面的 20%（能解析文档、能写向量库、能回答问题），却没做后面的 80%（索引版本管理、离在线解耦、高并发治理、缓存与降级、权限隔离、可观测、质量评估）。

真正的企业级 RAG，目标从来不是"让模型更会说"，而是：检索有质量、生成有依据、架构可扩展、故障可治理、成本可控制、风险可收敛。

**ai4j 在这套系统工程里的定位**：它把"检索 + 生成"主链封装好了——

- `IngestionPipeline` 管摄入（Tika/OCR 解析 + 切块 + metadata + embedding + upsert）
- `DenseRetriever` / `Bm25Retriever` / `HybridRetriever`（RRF/RSF/DBSF 融合）管召回
- `ModelReranker`（Ollama/Jina/Doubao）管重排
- `DefaultRagContextAssembler` 管上下文 + 引用组装
- `IMessagesService` / `IChatService` 管生成
- `RagTrace` + `RagScoreDetail` 管检索可观测
- `RagEvaluator`（Recall/MRR/NDCG）管质量评估
- `VectorStore` 统一五个向量后端

应用层只需要做业务编排。剩下的"治理"（缓存/限流/熔断/可观测指标/多租户权限）本就该是应用层（Spring Boot + Resilience4j + Micrometer）的事。

**ai4j 当前真实的三个缺口**（已在对应章节标注）：

1. **Query Rewrite**：没有开箱组件，应用层自己实现（LLM 改写或规则归一化）
2. **RAG 链路缓存**：有意不提供，留给应用层（失效/key 维度强业务相关）
3. **RAG-as-Agent-Tool**：agent 模块没有现成 RAG tool 封装，用户自己包一层

这些缺口是设计取舍（保持 SDK 克制）还是待补能力，取决于实际诉求——但它们是真实的，不该在宣传里假装没有。

只是换了一套更通用、更轻量的组件（Postgres 你大概率已有、Ollama 本地免费、ai4j 主链开箱即用），同样的企业级 RAG，应用层核心代码能从几百行降到 ~80 行，而该补的工程治理一样不少。

---

## 附录：一张图看懂本文完整主线

```
知识上传（knowledge/*.md，或 PDF/Word/扫描件）
  -> ai4j IngestionPipeline
       TikaDocumentLoader / OcrTextExtractingDocumentProcessor（解析/OCR）
       RecursiveTextChunker（语义切块）
       DefaultMetadataEnricher（元数据补全）
       Ollama embedding（Qwen3-0.6B）
       PgVectorStore upsert（幂等，documentId 确定性派生）
  -> @PostConstruct 建表/索引保证就绪

用户提问（POST /api/rag/ask）
  -> 鉴权与租户识别
  -> ai4j RagService.search
       DenseRetriever（+ Bm25Retriever -> HybridRetriever RRF 融合）
       ModelReranker（Ollama rerank）
       DefaultRagContextAssembler（带 [S1][S2] 引用）
       返回 RagResult（context + citations + hits + trace）
  -> GLM generate（IMessagesService，Anthropic Messages，拒答约束）
  -> 答案 + references 溯源
  -> 返回

线下：
  RagEvaluator（Recall/MRR/NDCG）用评测集持续回归检索质量
```

这条链路里，**ai4j 替你封装了摄入/检索/重排/组装/多后端抽象/可观测/评估，Postgres 替你省掉一套专用向量库，Ollama 替你省掉 embedding 云账单**——你只需要关注业务编排与工程治理。

> 完整 demo：[github.com/LnYo-Cly/ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)
> ai4j 本体：[github.com/LnYo-Cly/ai4j](https://github.com/LnYo-Cly/ai4j)
