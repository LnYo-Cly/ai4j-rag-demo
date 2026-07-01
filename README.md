# ai4j-rag-demo

> 企业级 RAG 开箱即用 demo：**ai4j + PgVector + Ollama embedding + GLM**。
> 对照「Spring Boot + Spring AI Alibaba + Redis」的实战，用更少的代码、更务实的本地组件，跑通同一条 RAG 链路。

## 它演示了什么

一条完整的企业级 RAG 主链：

```
离线摄入：knowledge/*.md → 切块 → Ollama embedding → PgVector upsert
在线问答：用户问题 → embedding → PgVector KNN 检索 → 上下文组装 → GLM 生成 → 答案 + 引用溯源
```

关键技术选型：

| 角色 | 选型 | 说明 |
|---|---|---|
| SDK | **ai4j 2.4.0** | 统一 VectorStore / RAG / 多协议 LLM 接入 |
| 向量库 | **PgVector** | 复用 PostgreSQL，过滤能力强，无需另装 Redis Stack |
| embedding | **Ollama + Qwen3-Embedding-0.6B** | 本地、免费、中文好 |
| 生成 | **GLM（Anthropic Messages 协议）** | 走 coding-plan 兼容入口 |
| 框架 | Spring Boot 3.2 | ai4j starter 实测在 SB 3 可用 |

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

## 换自己的知识

把 `src/main/resources/knowledge/*.md` 换成你的文档，重启即可（documentId 按文件名确定性派生，重启幂等不重复）。

## ai4j 让 RAG 多简洁

对照同一套链路，ai4j 把那篇文章里手写的 EmbeddingGateway / VectorRepository / 检索编排等都收进了 SDK，应用层只剩"编排 + 业务"。详见 [docs/blog.md](docs/blog.md)。
