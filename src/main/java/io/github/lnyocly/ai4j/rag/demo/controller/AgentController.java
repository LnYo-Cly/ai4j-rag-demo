package io.github.lnyocly.ai4j.rag.demo.controller;

import io.github.lnyocly.ai4j.agent.Agent;
import io.github.lnyocly.ai4j.agent.Agents;
import io.github.lnyocly.ai4j.agent.rag.RagTool;
import io.github.lnyocly.ai4j.agent.replay.InMemoryIoCaptureSink;
import io.github.lnyocly.ai4j.agent.replay.NodeIoRecord;
import io.github.lnyocly.ai4j.agent.tool.StaticToolRegistry;
import io.github.lnyocly.ai4j.rag.DefaultRagContextAssembler;
import io.github.lnyocly.ai4j.rag.DefaultRagService;
import io.github.lnyocly.ai4j.rag.DenseRetriever;
import io.github.lnyocly.ai4j.rag.NoopReranker;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.Retriever;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.retriever.TenantFilteredRetriever;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import io.github.lnyocly.ai4j.vector.store.pgvector.PgVectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 端点：把 RAG 作为 agent tool（RagTool），agent 自主决定何时检索，
 * 检索过程作为 TOOL 节点被 IoCaptureAgentListener 捕获 → 整链统一可观测。
 *
 * 对比 /api/rag/ask（RAG 直调，检索只在 RagTrace）：
 *   - /api/agent/ask 走 agent runtime，MODEL + TOOL(含 RAG 检索) 节点全部进 IoCaptureSink，
 *     可重放/恢复/审计——这就是"RAG 接入 agent 可观测链路"。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AiService aiService;
    private final PgVectorStore vectorStore;
    private final RagProperties ragProperties;

    @Value("${ai.anthropic.api-key:}") private String glmKey;
    @Value("${ai.anthropic.api-host:}") private String glmBaseUrl;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody ChatRequest request) throws Exception {
        InMemoryIoCaptureSink sink = new InMemoryIoCaptureSink();

        // 1. RAG 作为 agent tool ——租户过滤 retriever 包一层，避免 agent 跨租户召回
        DenseRetriever dense = new DenseRetriever(aiService.getEmbeddingService(PlatformType.OLLAMA), vectorStore);
        Retriever filtered = TenantFilteredRetriever.forTenant(dense, request.getTenantId());
        RagService ragService = new DefaultRagService(filtered, new NoopReranker(), new DefaultRagContextAssembler());
        RagTool ragTool = RagTool.builder(ragService)
                .dataset(ragProperties.getDataset())
                .embeddingModel(ragProperties.getEmbeddingModel())
                .topK(ragProperties.getTopK())
                .build();

        // 2. Agent + capture(sink)：MODEL/TOOL 节点 I/O 全捕获
        Agent agent = Agents.react()
                .anthropicMessages(glmKey, glmBaseUrl)
                .model(ragProperties.getGlmModel())
                .maxOutputTokens(1024)
                .toolRegistry(new StaticToolRegistry(Collections.singletonList(ragTool.tool())))
                .toolExecutor(ragTool.executor())
                .capture(sink)
                .build();

        // 3. 运行
        io.github.lnyocly.ai4j.agent.AgentResult result = agent.newSession().run(request.getQuestion());

        // 4. 展平捕获的节点（MODEL = 思考/生成；TOOL = 含 RAG 检索）
        List<Map<String, Object>> capturedNodes = new ArrayList<Map<String, Object>>();
        int modelNodes = 0;
        int toolNodes = 0;
        for (NodeIoRecord rec : sink.records()) {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("nodeType", rec.getNodeType());
            n.put("nodeId", rec.getNodeId());
            n.put("step", rec.getStep());
            n.put("hasInput", rec.getInputs() != null);
            n.put("hasOutput", rec.getOutputs() != null);
            capturedNodes.add(n);
            if (rec.getNodeType() == NodeIoRecord.NodeType.MODEL) {
                modelNodes++;
            } else {
                toolNodes++;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("answer", result.getOutputText());
        resp.put("steps", result.getSteps());
        resp.put("toolCalls", result.getToolCalls() == null ? 0 : result.getToolCalls().size());
        resp.put("inputTokens", result.getInputTokens());
        resp.put("outputTokens", result.getOutputTokens());
        resp.put("capturedNodeCount", sink.size());
        resp.put("capturedModelNodes", modelNodes);
        resp.put("capturedToolNodes", toolNodes);
        resp.put("capturedNodes", capturedNodes);
        return resp;
    }
}
