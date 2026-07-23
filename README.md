# ai4j-rag-demo

> 企业级 RAG 开箱即用 demo：**ai4j + PgVector + Ollama embedding + GLM**。
> 用更少的代码、更务实的本地组件，跑通一条完整的企业级 RAG 链路（摄入 → 多租户检索 → 重排 → 生成 → 可观测）。

## 它演示了什么

一条完整的企业级 RAG 主链：

```
离线摄入：knowledge/*.md → 切块 → Ollama embedding → PgVector upsert
在线问答：用户问题 → embedding → PgVector KNN 检索 → 上下文组装 → GLM 生成 → 答案 + 引用溯源
```

关键技术选型：

| 角色 | 选型 | 说明 |
|---|---|---|
| SDK | **ai4j 2.4.2**（Central 已发布，clone-and-run） | 统一 VectorStore / RAG / 多协议 LLM 接入 |
| 向量库 | **PgVector** | 复用 PostgreSQL，过滤能力强，无需另装向量服务 |
| embedding | **Ollama + Qwen3-Embedding-0.6B** | 本地、免费、中文好（1024 维） |
| 生成 | **GLM（Anthropic Messages 协议）** | 走 coding-plan 兼容入口（api/anthropic） |
| 框架 | Spring Boot 2.7.18（JDK 8） | ai4j starter 原生兼容 SB 2.x；SB 3 / JDK 17 也实测可用 |

## 前置条件

1. **PostgreSQL + pgvector 扩展**
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
   （demo 启动时会自动建表 + 索引，库本身要能装 vector 扩展）
2. **Ollama + embedding 模型**
   ```bash
   ollama pull hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:latest
   ```
3. **GLM API key**（智谱 coding-plan，走 Anthropic 兼容入口）

## 配置

`src/main/resources/application.yml` 里的关键项：

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
    api-host: https://open.bigmodel.cn/api/anthropic/
    api-key: ${GLM_API_KEY}      # 从环境变量读
```

### 可开关的特性（`rag.*`）

| 开关 | 默认 | 作用 |
|---|---|---|
| `rag.planner-enabled` | true | Query Planning（GLM 生成 query variants 多路召回融合） |
| `rag.hybrid-enabled` | true | Hybrid 检索（Dense + 内存 BM25 + RRF 融合，子路容错） |
| `rag.online-eval-enabled` | true | 在线评估（每答打 faithfulness/contextRelevance 分，走 ANTHROPIC 通道） |
| `rag.conversational-enabled` | true | 多轮对话（RagQuery 带 history，planner 消解 follow-up 指代） |
| `rag.max-context-tokens` | 4000 | TokenAware 上下文预算（0=默认组装器） |
| `rag.reranker` | llm | 重排策略：none / llm（GLM 打分）/ jina（专用模型，国内需 proxy） |

全默认开箱即用；想关某项改 `application.yml` 或传环境变量即可。

## 跑起来

```bash
export GLM_API_KEY=你的智谱coding-plan-key
mvn spring-boot:run
```

启动时会自动：建表 → 摄入 `knowledge/*.md`（幂等）→ 监听 8080。

## 试用

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "秒杀商品签收后还能申请退款吗？", "tenantId": "default"}'
```

返回（带引用溯源）：

```json
{
  "answer": "秒杀商品签收后是否可退款取决于商品规则...",
  "references": [
    {"sourceName": "refund-rules.md", "sectionTitle": "秒杀活动商品退款限制", "snippet": "..."},
    {"sourceName": "after-sales.md", "sectionTitle": "质量问题售后", "snippet": "..."}
  ],
  "hitCount": 5,
  "degraded": false
}
```

### 走 agent 端点（RAG 作为 agent tool + 统一可观测）

`/api/agent/ask` 用 ai4j 的 `RagTool` 把 RAG 接成 agent tool，agent 自主决定何时检索；检索作为 TOOL 节点被 `.capture(IoCaptureSink)` 捕获，整链（思考 + 检索 + 生成）统一可观测/可重放/可审计：

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "秒杀商品签收后还能申请退款吗？", "tenantId": "default"}'
```

返回里的 `capturedNodes` 含 `MODEL`（思考/生成）+ `TOOL`（RAG 检索）节点——证明 RAG 接入了 agent 的可观测链路（需 ai4j-agent，含 `RagTool`）。

## 换自己的知识

把 `src/main/resources/knowledge/*.md` 换成你的文档，重启即可（documentId 按文件名确定性派生，重启幂等不重复）。

## ai4j 让 RAG 多简洁

ai4j 把 embedding 接入 / 向量检索 / 重排 / 上下文组装 / agent 可观测等都收进了 SDK，应用层只剩"编排 + 业务"。详见 [docs/blog.md](docs/blog.md)。
