# ai4j + PgVector + Ollama：用更少的代码，跑通企业级 RAG

> 一篇《Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战》前段时间在朋友圈刷屏。文章写得非常扎实——从"为什么 RAG demo 一上线就失效"讲起，一路讲到架构、12 条设计原则、生产级代码、高并发、多租户、可观测、部署、真实案例。它几乎是一份企业级 RAG 的施工蓝图。
>
> 本文严格**对照同一套结构**，但换一套技术栈：用另一个 Java AI SDK **ai4j**，配上你大概率已经有的 **PostgreSQL + pgvector**，加上本地免费的 **Ollama embedding**，跑通同一条企业级 RAG 链路。看看换掉 Redis Stack、换掉手写的 EmbeddingGateway / VectorRepository 之后，应用层代码能精简到什么程度，又有哪些工程现实是换什么栈都绕不开的。

---

## 一、为什么很多 RAG Demo 一上线就失效

这一点原文说得非常对，我完全认同，原样保留这个判断：

> RAG 从来不是"向量库 + 大模型"的简单拼装，而是"检索系统 + 生成系统 + 工程治理系统"的组合工程。

很多团队第一版系统做得很快：选个 embedding 模型、读几篇 PDF、切块写进向量库、查询时 TopK 相似度、把结果塞进 prompt 调大模型。十几分钟跑起来，页面效果也往往不错。

但这类 demo 只能说明 "RAG 可以做出来"，不能说明 "RAG 可以稳定上线"。一旦进入真实业务：

- 文档量从几十篇涨到几十万 chunk，索引构建、内存占用、召回噪声同时上升
- 用户问题变复杂后，纯向量召回会漂移，误召回明显增多
- 高并发下，embedding、重排、LLM 调用互相争抢资源，接口 RT 抖动严重
- 知识更新后，新旧索引混用，结果不一致、不可解释
- 多租户过滤放后面，容易越权召回和串库
- 出现错误答案时，团队无法判断是切块、召回、重排还是 prompt 的问题

真正的企业级 RAG，必须同时解决四类问题：**检索质量稳、在线链路快、离线入库可扩展、整体系统可治理**。本文围绕 `Spring Boot + ai4j + PgVector + Ollama`，把一个常见 demo 逐步升级成一套可生产的企业级 RAG。

## 二、本文要解决的核心问题

对照原文，本文同样回答四个问题：

1. **原理层**：embedding、向量检索、RAG pipeline 的本质是什么
2. **架构层**：如何把 RAG 拆成离线摄入链路和在线问答链路
3. **工程层**：如何处理高并发、缓存、限流、熔断、降级、索引演进和多租户隔离
4. **代码层**：如何给出接近生产可用的 Java 代码

为了让讨论足够具体，统一使用和原文一样的案例场景。

## 三、业务场景：电商智能客服知识引擎

假设你负责一个电商平台智能客服系统，业务要求（和原文一致）：

- 日均会话量 100 万+
- 峰值问答 QPS 2000+
- 业务知识类型：退款规则、售后政策、物流时效、营销活动、商家规范
- 文档来源：运营后台、CMS、PDF 规章、知识库系统、工单沉淀 FAQ
- 目标 RT：P95 < 800ms，P99 < 1.5s
- 更新目标：知识变更后 1-3 分钟可查询
- 风险要求：必须支持多租户隔离、来源溯源、故障降级

核心难点不在"模型会不会回答"，而在：回答能不能基于可信证据、高峰期能不能稳定服务、规则更新能不能快速生效、线上出问题时能不能快速定位。

## 四、先把原理讲透：embedding 与 RAG 的本质

### 4.1 embedding 到底在做什么

embedding 的本质，是把文本映射到一个高维稠密向量空间，让"语义相近"的文本在空间中距离更近：

```
"如何申请退款"    -> [0.12, -0.38, 0.54, ...]
"退款流程是什么"  -> [0.10, -0.36, 0.57, ...]
"今天天气不错"    -> [-0.42, 0.16, -0.33, ...]
```

查询时把问题也转成向量，用相似度算法找最接近的 chunk。常见相似度度量：

- **Cosine Similarity**：最常见，关注方向相似性
- **Inner Product**：归一化向量场景常用
- **Euclidean Distance（L2）**：关注向量绝对距离

本文用 pgvector 的 `<=>`（cosine distance）。embedding 模型用 Qwen3-Embedding-0.6B（输出 1024 维）。

### 4.2 RAG 的本质不是"补知识"，而是"补证据"

原文这个判断非常准确：

> RAG 不是给模型补知识，而是给模型补证据。

企业场景需要模型回答的是当前版本的制度、当前生效的政策、私域文档、带权限边界的内部信息——这些都不该依赖模型参数记忆，而该依赖检索得到的外部上下文。

所以一个成熟的 RAG pipeline 不是简单两步，而是多段式：

```
用户问题
  -> Query Normalize / Rewrite
  -> Retrieve
  -> Rerank
  -> Context Build
  -> Generate
  -> Post Process
```

### 4.3 企业 RAG 的两条主链路

**离线链路：知识入库**

```
文档采集 -> 内容解析 -> 清洗标准化 -> 语义切块 -> 元数据补全 -> 向量化 -> 建索引 -> 发布上线
```

**在线链路：问题回答**

```
鉴权 -> 租户识别 -> 查询改写 -> 混合召回 -> 权限过滤 -> 重排 -> Prompt 组装 -> 大模型生成 -> 引用标注 -> 返回结果
```

两条链路必须解耦：入库是重 CPU/IO/内存任务，在线查询是低延迟任务，耦合在一个同步服务里并发一定失稳。

## 五、为什么选择 ai4j + PgVector + Ollama

### 5.1 ai4j 的价值

对于 Java 团队来说，ai4j 的核心价值不是"能调大模型"，而是它把 AI 能力做了**统一抽象**：

1. **模型抽象统一**：`IChatService`（OpenAI Chat）/ `IResponsesService`（OpenAI Responses）/ `IMessagesService`（Anthropic Messages）三套协议并列，外加 `IEmbeddingService`、`VectorStore`、`RagService`、`IngestionPipeline` 等抽象，让模型与底层实现解耦。
2. **RAG 组件开箱即用**：`IngestionPipeline`（加载→切块→embedding→upsert）、`DenseRetriever` / `RagService`（embed query→KNN→rerank→assemble）、`RagContextAssembler`、`Reranker` 全部现成，应用层只管业务编排。
3. **多向量后端**：`VectorStore` SPI 统一了 Pinecone / Qdrant / Milvus / PgVector / Redis Stack 五个后端，业务代码换后端不改主链。

对于一个要长期演进的企业系统，"主链调用方式统一"比"能跑第一个 demo"更重要。

### 5.2 为什么 PgVector 适合作为企业 RAG 的第一站

原文选 Redis Stack 作为"起步方案"，本文换一个角度：**PgVector 是更通用的起步方案**。原因有三：

1. **复用已有基础设施**：大多数 Java 团队本来就有 PostgreSQL 运维经验。装个 pgvector 扩展（`CREATE EXTENSION vector`）就能用，不用额外引入 Redis Stack 这一套。
2. **过滤能力更强**：pgvector 的元数据过滤直接用 SQL `where`（JSONB、普通列、JOIN 都行），比 RediSearch 的 query DSL 自然得多、表达力也强。企业 RAG 的"先过滤再检索"在 PgVector 上更顺。
3. **一库多用**：Postgres 同时承担业务库 + 向量库，少一套中间件。

它也有边界：极大规模向量数据下，专用向量库（Milvus 等）的检索性能更好。但 ai4j 的 `VectorStore` 抽象让你后续从 PgVector 迁移到别的后端时，**业务层主链不改**——这正是原文说的"先把架构接口抽象好，再决定底层组件"。

### 5.3 为什么 Ollama 适合做 embedding

- **本地、免费**：不消耗云 API 额度，开发期零成本
- **中文好**：Qwen3-Embedding-0.6B 等模型对中文语义支持优秀
- **零网络延迟**：embedding 是高频调用，本地省去 RTT

生产高峰期可以切到云端 embedding（ai4j 的 `IEmbeddingService` 换个实现即可），开发期用 Ollama 足够。

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
│ DenseRetriever+rerank │           │ (GLM via Anthropic Messages)│
│ +ContextAssembler     │           │  生成                        │
└──────────────────────┘           └────────────────────────────┘
          │                                    │
          ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔
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
                 │  Ollama embedding + 切块 + upsert │
                 └──────────────────────────────┘
```

### 6.2 生产系统中的核心边界

企业级 RAG 不该只有一个 `RagService` 包打天下，要清晰拆分职责（和原文一致）：

- **Controller**：协议适配、参数校验、身份透传、响应封装
- **RagQueryService**：编排一次问答（检索→生成→降级→引用组装）
- **KnowledgeIngestionService**：独立于在线链路，负责知识解析、切块、入库
- **PgVectorStore**：底层向量检索（ai4j 提供）
- **ai4j 组件**：`IngestionPipeline` / `RagService` / `IMessagesService` 提供 RAG 主链

边界清晰，后续每加一个能力（多租户、重排、灰度索引、混合检索）才不会让问答主服务膨胀。

## 七、核心设计原则：从 Demo 升级到生产必须补齐的 12 件事

原文这 12 条原则我完全认同，**它们与具体技术栈无关**。下面逐条标注本文（ai4j + PgVector）怎么落地。

### 7.1 检索优先于生成
RAG 场景大部分错误回答的根因不在模型，而在召回错误或上下文污染。→ ai4j `RagService` 把检索+组装收进主链，应用层重点盯检索质量。

### 7.2 离线摄入与在线查询彻底解耦
入库异步化，不占在线资源池。→ `KnowledgeIngestionService` 作为独立 `ApplicationRunner`，可改成 MQ 驱动 worker。

### 7.3 元数据是一等公民
每个 chunk 至少有 `dataset / documentId / chunkIndex / sourceName / sectionTitle` 等。→ ai4j `RagChunk` + `RagMetadataKeys` 内置这些；PgVector 用 JSONB 列存。

### 7.4 索引必须版本化
知识更新不直接覆盖线上索引。→ PgVector 用 `dataset` 作为版本边界（如 `ecommerce-kb-v1`），双 dataset 并行 + 原子切换。

### 7.5 权限过滤必须前置
先过滤再检索再生成。→ PgVector 的 `VectorSearchRequest.filter` 在 KNN 前用 SQL where 过滤。

### 7.6 模型调用必须被治理
超时、重试、熔断、限流、降级。→ 应用层 Resilience4j 包 `IMessagesService` 调用（原文同款）。

### 7.7 缓存不是附加优化，而是基础设施
改写结果、embedding、热点检索、最终回答都可缓存。→ 应用层 Caffeine / Redis（本文 demo 省略，原文有详述）。

### 7.8 不要只做向量召回
订单号、规则编号、错误码等需要关键词/结构化过滤。→ PgVector 天然支持（SQL where + 向量 KNN 联合），比纯向量库强。

### 7.9 Prompt 预算必须受控
不能 TopK 全塞。→ ai4j `RagContextAssembler` 控制上下文长度 + 应用层 token 预算。

### 7.10 可观测性必须从第一天开始
改写前后 query、召回候选数、重排前后、上下文 token、模型耗时、引用来源、降级原因。→ 应用层 Micrometer + ai4j 事件流（ai4j 已有 observability/trace）。

### 7.11 质量评估必须标准化
Recall@K / MRR / Grounded Rate / 引用覆盖率。→ 应用层评测集（本文 demo 省略）。

### 7.12 先设计演进路径，再选择组件
不要把 PgVector 当架构本身。→ ai4j `VectorStore` 抽象，后续换 Milvus/Qdrant 主链不改。

## 八、技术选型建议

| 维度 | 原文方案 | 本文方案 | 说明 |
| --- | --- | --- | --- |
| AI SDK | Spring AI Alibaba | **ai4j** | 统一抽象，RAG 组件开箱即用 |
| Embedding | DashScope（云） | **Ollama + Qwen3-Embedding** | 本地免费，中文好 |
| Chat 模型 | 通义千问 | **GLM**（Anthropic Messages） | 走 ai4j `IMessagesService` |
| 向量存储 | Redis Stack | **PgVector** | 复用 Postgres，过滤强 |
| MQ | Kafka / RocketMQ | 同（按需） | 知识摄入异步化 |
| 可观测 | Micrometer + Prometheus | 同 | 指标、告警 |
| 配置中心 | Nacos | 同 | 动态调整 TopK、阈值 |

工程原则不变：**先把架构接口抽象好（ai4j 已经做了），再决定底层组件。**

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

这套结构最重要的价值（和原文一致）：**在线问答和离线摄入分层、领域规则和基础设施隔离**。

## 十、知识摄入架构：离线链路怎么设计才扛得住规模

### 10.1 不要同步入库

很多 demo 在用户上传文档后同步完成解析→切块→embedding→写库。生产环境这会带来三个问题：上传接口 RT 极长、重任务和在线查询争抢资源、失败重试和幂等控制困难。正确做法是把入库拆成事件驱动链路。

### 10.2 推荐的知识摄入时序

```
Admin Upload API -> Object Storage -> Create Ingestion Task
  -> MQ publish(document_uploaded) -> Parser Worker -> Chunker Worker
  -> Embedding Worker -> PgVector upsert -> Publish Version
```

### 10.3 为什么必须有 staging（或版本化）

直接把新版本 chunk 写进线上索引会出现：查询结果新旧混杂、部分文档失败导致结果不完整、回滚困难。更稳妥是双 dataset 模式：

- `ecommerce-kb-v1`（active）
- `ecommerce-kb-v2`（staging）

新版本先写 staging，校验后原子切换。

### 10.4 文档切块策略

切块质量决定召回上限。生产建议：按语义边界切、保留章节标题和路径、控制 chunk 长度和 overlap。中文文档常见实践：chunk 目标长度 300-600 中文字、overlap 50-100 字、标题保留到 metadata。

ai4j 的 `IngestionPipeline` 默认用 `RecursiveTextChunker(1000, 200)`，可替换自定义 `Chunker`。

> 关键误区：chunk 不是为了"切得均匀"，而是为了"被单独召回时仍有足够语义完整性"。

## 十一、在线问答架构：一次请求在系统里如何流转

### 11.1 标准处理链路

一条成熟的问答链路（和原文一致）：

1. 鉴权与租户识别
2. 问题归一化
3. 热点缓存检查
4. Query Rewrite
5. Embedding
6. 向量召回
7. 元数据过滤
8. Rerank
9. 上下文压缩与组装
10. Prompt 构建
11. LLM 生成
12. 引用溯源与后处理
13. 结果缓存

本文 demo 聚焦主链（5/6/7/9/11/12），缓存/改写/重排等增强项按原文思路在应用层补。

### 11.2 为什么 Query Rewrite 值得做

用户原始问题经常很短或上下文模糊（"退款怎么弄""发票"），直接 embedding 语义不足。Rewrite 能补足业务语义、规范化口语、对齐知识库正式术语。

### 11.3 为什么需要重排

向量召回擅长"粗召回"不擅长最终排序：召回内容语义相关但不回答核心问题、多段相关但优先级不对。推荐两段式：向量召回 TopK=20 → 过滤去重 → Rerank TopN=5 → 上下文组装。

ai4j 提供 `Reranker`（含 `NoopReranker` 和 `ModelReranker`，后者接 rerank 模型）。本文 demo 默认 Noop，可接 Ollama reranker。

## 十二、PgVector 向量索引设计

### 12.1 表里存什么

```
ai4j_rag_demo
  id         text primary key     -- chunkId（documentId#chunk-N，确定性派生）
  dataset    text                 -- 知识库边界 / 版本
  content    text                 -- chunk 正文
  metadata   jsonb                -- 元数据（sourceName/sectionTitle/pageNumber...）
  embedding  vector(1024)         -- Qwen3-Embedding 输出维度
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

pgvector 提供两类索引：

- **HNSW**：近似最近邻，查询快，建索引慢、占内存多。中大规模数据首选。本文用这个。
- **IVFFlat**：倒排+精确，建索引快，查询精度依赖参数。

```sql
CREATE INDEX ai4j_rag_demo_emb_idx
  ON ai4j_rag_demo USING hnsw (embedding vector_cosine_ops);
```

### 12.4 不要忽略过滤字段

真实查询往往是"先限定租户、知识库、状态和权限，再做向量搜索"。PgVector 的优势正在于此——过滤直接用 SQL where，和 KNN 在一条 SQL 里完成，比"先全库召回再过滤"安全得多（避免越权召回）。

## 十三、生产级代码实现

下面给出本文 demo 的核心代码（对照原文 13.x，但用 ai4j 大幅简化）。完整代码见 [ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)。

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

> ai4j starter 编译于 SB 2.3 / Java 8，**实测在 Spring Boot 3.2 运行时兼容**（autoconfigure + `@PostConstruct` init 均正常工作）。

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
    }
}
```

> 这里有个坑：**不要用两个 `@Bean ApplicationRunner` + `@Order` 来保证"建表先于摄入"**——`@Order` 对 `@Bean` 方式声明的 runner 不可靠。改用 `@PostConstruct` 建表（在 bean 初始化阶段，早于所有 ApplicationRunner），顺序才确定。

### 13.4 离线摄入（ai4j IngestionPipeline 一行搞定）

```java
IngestionPipeline pipeline = aiService.getIngestionPipeline(PlatformType.OLLAMA, vectorStore);
IngestionResult result = pipeline.ingest(IngestionRequest.builder()
        .dataset(ragProperties.getDataset())
        .embeddingModel(ragProperties.getEmbeddingModel())
        .source(IngestionSource.text(content))           // 知识文本
        .document(RagDocument.builder()
                .documentId(docId)                        // 文件名确定性派生 -> 重启幂等
                .sourceName(filename)
                .title(filename)
                .build())
        .build());
```

`IngestionPipeline` 把"加载→切块→embedding→upsert"全包了。原文手写的 `EmbeddingGateway`（带缓存、批量、超时）在这里被 ai4j 内部吸收了。

### 13.5 在线检索（ai4j RagService 一步到位）

```java
RagResult result = ragService.search(RagQuery.builder()
        .query(question)
        .dataset(dataset)             // 权限/租户前置过滤的硬边界
        .embeddingModel(embeddingModel)
        .topK(5)
        .build());

String context = result.getContext();                  // 组装好的上下文
List<RagCitation> citations = result.getCitations();   // 引用溯源
```

`RagService.search` 一次完成"embed query → KNN 检索 → rerank → 上下文组装"，返回 `context` + `citations`。原文手写的 `VectorRepository` + `ContextAssembler` 在这里被 ai4j 收口。

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

注意 prompt 里的**拒答约束**——这是原文第 19.6 节强调的"没有拒答策略导致幻觉"的对策。

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

这段体现了几个生产思路（和原文一致）：检索分层、检索为空可控降级、最终返回引用来源支持溯源。

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

接口返回不只是答案，还附带 `references` / `hitCount` / `degraded`——直接影响线上排障、前端展示和运营复盘。

## 十四、高并发与可扩展设计

### 14.1 高并发场景的真实瓶颈

常见瓶颈不是单点，而是组合：Query Rewrite 占模型资源、embedding 吞吐有限、向量检索高并发下连接数暴涨、rerank 延迟波动、LLM 生成最慢最贵最不稳。

### 14.2 资源隔离

至少拆分：Web 请求线程池、知识摄入线程池、embedding 线程池、LLM 生成线程池。不要让文档入库和用户问答共享同一套线程资源。

### 14.3 限流、熔断、降级

生产必须假设模型服务会抖动。治理顺序：限流 → 超时 → 熔断 → 降级。降级兜底路径：返回缓存答案、关闭 Rewrite、关闭 Rerank、返回 FAQ 静态答案。用 Resilience4j（和原文同款）。

### 14.4 缓存策略

四层缓存：Rewrite Cache / Embedding Cache / Retrieval Cache / Answer Cache。缓存 key 必须带 `dataset / kbVersion / permissionScope / queryHash`，否则知识更新后用户看到旧答案、带权限差异的答案被错误复用。

### 14.5 横向扩展

在线查询服务无状态化、多副本水平扩展；摄入 worker 独立扩缩容；PgVector 随规模评估读写分离/分库。关键原则：**在线服务无状态，状态下沉到 Postgres / MQ / 缓存**。

## 十五、多租户、安全与权限隔离

RAG 最危险的不是"答错"而是"串库"。

### 15.1 权限过滤的正确顺序

```
识别用户身份 -> 计算可访问范围 -> 查询时附带过滤 -> 检索结果再校验 -> 交给模型生成
```

而不是"先全库召回 → 生成答案 → 再遮罩"。PgVector 的 `VectorSearchRequest.filter` 让"查询时附带过滤"很自然（SQL where + KNN 一条 SQL）。

### 15.2 推荐的权限模型

每个 chunk 打权限标签（`public / employee / customer-service / merchant / finance-admin`），用户进入系统映射为 `AccessScope(tenantId, permissionTags)`，检索层召回前过滤。

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
| `rag.generate.latency` | LLM 生成耗时 |
| `rag.answer.cache.hit` | 答案缓存命中 |
| `rag.degrade.count` | 降级次数 |
| `rag.recall.empty.count` | 空召回次数 |
| `rag.context.length` | 上下文长度 |

### 16.2 日志应该记录什么

traceId / tenantId / userId / 原始 query / 改写后 query / recall count / top references / final prompt size / 是否降级 / 总耗时。注意日志脱敏（用户输入和文档正文片段）。

### 16.3 质量评估

Recall@K / MRR / Hit Rate / **Grounded Rate**（回答多少结论能从引用上下文找到依据）/ Citation Coverage / No Answer Precision。

ai4j 已内置 observability/trace 能力，应用层把它接进 Micrometer 即可。

## 十七、部署与发布

### 17.1 本地试点
单体 Spring Boot + 单实例 Postgres + 本地 Ollama。验证切块、召回、回答效果。

### 17.2 小规模生产
问答服务与摄入服务拆分；Postgres 独立；MQ 引入异步摄入；Prometheus/Grafana 接入。

### 17.3 中大型生产
API Gateway + 多副本问答服务；摄入 worker 集群；Postgres 读写分离；配置中心；索引版本发布平台；离线评测平台。

### 17.4 Kubernetes 关注点
HPA 扩缩容依据、模型调用超时和连接池、Postgres 连接数上限、Pod 内存峰值、摄入与在线分离、滚动发布期间索引版本一致性。

## 十八、真实案例：退款规则问答是如何跑通的

### 18.1 原始知识

知识库三份文档：`refund-rules.md` / `after-sales.md` / `logistics.md`，切块后入库。

### 18.2 用户提问

```
秒杀商品签收后还能申请退款吗？
```

### 18.3 系统链路

1. embedding（Ollama Qwen3）→ PgVector KNN 召回 TopK
2. 召回结果（按相关度）：
   - `refund-rules.md`（秒杀活动商品退款限制）
   - `after-sales.md`（质量问题售后）
   - `logistics.md`（签收认定）
3. 上下文组装 → GLM 生成

### 18.4 GLM 的真实回答

> 关于秒杀商品在签收后的退款政策：
> 1. **不支持 7 天无理由退款**——参与秒杀活动的特殊商品不支持 7 天无理由退款（S1）。
> 2. **存在质量问题可申请退款**——不受活动商品限制约束，可按售后规则申请（S1、S2）。
> 3. **时效**——质量问题售后需在签收后 15 天内申请（S2）；退款审核 1-3 个工作日，到账 3-5 个工作日（S1）。
>
> `references`: [refund-rules.md（秒杀活动商品退款限制）, after-sales.md（质量问题售后）]

### 18.5 这个案例体现了什么

它体现了企业级 RAG 与普通 demo 的本质区别（和原文一致）：

- 不是只召回"最相似一段"，而是把"活动例外规则"和"通用规则"一起组织成可解释答案
- 答案带来源引用，可回溯、可审核
- 检索质量决定答案质量（召回对了，模型才答对）

## 十九、常见问题与高频坑位

### 19.1 只做 TopK 向量检索，不做过滤
后果：多租户串库、新旧版本混召、权限越权。→ PgVector 用 `dataset` + `filter` 前置过滤。

### 19.2 chunk 切得太碎
召回片段缺乏独立语义，模型读完也无法回答。

### 19.3 chunk 切得太大
噪声高、prompt 变长、成本和时延上升。

### 19.4 缓存 key 不带版本
知识更新后用户仍看到旧答案。

### 19.5 只盯着模型，不看检索
不断换模型效果却不稳——根因在召回。

### 19.6 没有拒答策略
知识不足时模型仍努力生成，导致幻觉。→ 本文 system prompt 明确"资料不足就说无法确认"。

## 二十、从 PgVector 起步，未来如何继续演进

### 20.1 当知识量继续增长
PgVector 继续承担检索；ai4j `VectorStore` 接口不变，迁移到 Milvus/Qdrant 业务层不改。

### 20.2 当检索需求更复杂
复杂关键词检索、多条件结构化过滤、大规模混合检索 → 演进到 Elasticsearch/OpenSearch 混合，或专用向量库。ai4j 已支持 Milvus/Qdrant/Pinecone/Redis Stack 多后端。

### 20.3 当业务从单问答升级为智能体系统
RAG 会变成其中一个能力模块。ai4j 的 `ai4j-agent` 提供 ReAct agent + 工具调用 + 记忆 + compaction，可以把 `RagService` 包成一个 tool，让 agent 自己决定何时检索——这正好是原文展望的"未来形态"。

## 二十一、生产落地检查清单

**架构层**
- 是否完成离线摄入和在线问答解耦
- 是否设计了索引版本化和回滚机制
- 是否定义了清晰的领域接口抽象（ai4j 已提供）

**检索层**
- 是否支持租户、版本、权限过滤
- 是否评估过 chunk 策略
- 是否有重排或二次打分
- 是否有拒答阈值

**工程层**
- 是否做了缓存
- 是否做了线程池隔离
- 是否做了限流、超时、熔断、降级
- 是否对模型依赖做了异常演练

**安全层**
- 是否先过滤再生成
- 是否做了敏感信息脱敏
- 是否支持审计和引用追溯

**观测层**
- 是否能看到召回结果和排序变化
- 是否能统计缓存命中率和降级次数
- 是否有离线评测集

## 二十二、总结：企业级 RAG 的核心，不是"接上模型"，而是"做成系统"

回到文章开头：为什么很多 RAG demo 一上线就失效？因为它们只做了最前面的 20%（能解析文档、能写向量库、能回答问题），却没做后面的 80%（索引版本管理、离在线解耦、高并发治理、缓存与降级、权限隔离、可观测、质量评估）。

真正的企业级 RAG，目标从来不是"让模型更会说"，而是：

- 检索有质量
- 生成有依据
- 架构可扩展
- 故障可治理
- 成本可控制
- 风险可收敛

**本文相对原文的增量结论**：这个系统工程的"检索 + 生成"主链，ai4j 已经替你封装好了——`IngestionPipeline` 管摄入，`RagService` 管检索+组装，`IMessagesService` / `IChatService` 管生成，`VectorStore` 统一五个向量后端。应用层只需要做业务编排。剩下的"治理"（缓存/限流/熔断/可观测/多租户）本就该是应用层（Spring Boot + Resilience4j + Micrometer）的事，不是 SDK 的事——这和原文"SDK 统一主链、治理留给工程层"的结论完全一致。

只是换了一套更通用、更轻量的组件（Postgres 你大概率已有、Ollama 本地免费、ai4j 主链开箱即用），同样的企业级 RAG，应用层核心代码从原文的 ~300 行降到了 ~80 行。

---

## 附录：一张图看懂本文完整主线

```
知识上传（knowledge/*.md）
  -> ai4j IngestionPipeline（解析/切块/元数据）
  -> Ollama embedding
  -> PgVector upsert（@PostConstruct 建表保证就绪）

用户提问（POST /api/rag/ask）
  -> 鉴权与租户识别
  -> ai4j RagService.search（embed query -> KNN -> filter -> rerank -> assemble）
  -> 上下文 + 引用
  -> GLM generate（IMessagesService，Anthropic Messages）
  -> 答案 + references 溯源
  -> 返回
```

这条链路里，**ai4j 替你封装了摄入/检索/组装/多后端抽象，Postgres 替你省掉一套 Redis Stack，Ollama 替你省掉 embedding 云账单**——你只需要关注业务编排与工程治理。

> 完整 demo：[github.com/LnYo-Cly/ai4j-rag-demo](https://github.com/LnYo-Cly/ai4j-rag-demo)
> ai4j 本体：[github.com/LnYo-Cly/ai4j](https://github.com/LnYo-Cly/ai4j)
