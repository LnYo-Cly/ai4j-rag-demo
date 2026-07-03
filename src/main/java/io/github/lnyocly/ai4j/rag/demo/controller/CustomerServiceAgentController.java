package io.github.lnyocly.ai4j.rag.demo.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.github.lnyocly.ai4j.agent.Agent;
import io.github.lnyocly.ai4j.agent.Agents;
import io.github.lnyocly.ai4j.agent.rag.RagTool;
import io.github.lnyocly.ai4j.agent.replay.InMemoryIoCaptureSink;
import io.github.lnyocly.ai4j.agent.replay.NodeIoRecord;
import io.github.lnyocly.ai4j.agent.tool.StaticToolRegistry;
import io.github.lnyocly.ai4j.agent.tool.ToolExecutor;
import io.github.lnyocly.ai4j.platform.openai.tool.Tool;
import io.github.lnyocly.ai4j.rag.RagService;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.service.MockOrderService;
import io.github.lnyocly.ai4j.rag.demo.service.MockTicketService;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商客服 agent：RAG 知识检索 + 订单查询 + 创建工单，agent 自主编排。
 *
 * 这就是"RAG 在 agent 里用"的完整生产形态——RAG 不再是独立链路，
 * 而是和业务 tool 平级的一个能力，整条客服链路（思考+多tool）进 IoCaptureSink，
 * 可重放/恢复/审计（见博客 20.1）。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class CustomerServiceAgentController {

    private final AiService aiService;
    private final PgVectorStore vectorStore;
    private final RagProperties ragProperties;
    private final MockOrderService orderService;
    private final MockTicketService ticketService;

    @Value("${ai.anthropic.api-key:}") private String glmKey;
    @Value("${ai.anthropic.api-host:}") private String glmBaseUrl;

    @PostMapping("/customer-service")
    public Map<String, Object> ask(@RequestBody ChatRequest request) throws Exception {
        InMemoryIoCaptureSink sink = new InMemoryIoCaptureSink();

        // ① 知识检索 tool（RAG）
        RagService ragService = aiService.getRagService(PlatformType.OLLAMA, vectorStore);
        RagTool knowledgeTool = RagTool.builder(ragService)
                .dataset(ragProperties.getDataset())
                .embeddingModel(ragProperties.getEmbeddingModel())
                .topK(ragProperties.getTopK())
                .build();

        // ② 订单查询 tool
        Tool orderTool = makeTool("query_order", "查订单状态、物流、金额。输入 orderId。",
                "orderId", "string", "订单号，如 ORD-12345");

        // ③ 创建工单 tool
        Tool ticketTool = makeToolTwoArgs("create_ticket", "为用户创建售后/退款工单。",
                "orderId", "string", "订单号",
                "reason", "string", "工单原因（退款/换货/投诉等）");

        // 合并三个 tool 的执行器（按 tool name 分发）
        ToolExecutor merged = call -> {
            String name = call.getName();
            String args = call.getArguments();
            JSONObject arg = (args == null || args.trim().isEmpty())
                    ? new JSONObject() : JSON.parseObject(args);
            if ("knowledge_search".equals(name)) {
                return knowledgeTool.executor().execute(call);
            }
            if ("query_order".equals(name)) {
                return JSON.toJSONString(orderService.queryOrder(arg.getString("orderId")));
            }
            if ("create_ticket".equals(name)) {
                return JSON.toJSONString(ticketService.createTicket(
                        arg.getString("orderId"), arg.getString("reason")));
            }
            return "unknown tool: " + name;
        };

        // 客服 agent：自主编排
        Agent agent = Agents.react()
                .anthropicMessages(glmKey, glmBaseUrl)
                .model(ragProperties.getGlmModel())
                .maxOutputTokens(1024)
                .systemPrompt("你是电商客服助手。根据用户问题自主决定调用哪个工具：\n"
                        + "- query_order：查订单状态/物流（用户给了订单号时）\n"
                        + "- knowledge_search：查知识库（退款规则、售后政策、物流时效等）\n"
                        + "- create_ticket：用户明确要退款/投诉/换货，且符合规则时建工单\n"
                        + "可以连续调用多个工具。回答要基于工具返回的真实信息，不要编造。")
                .toolRegistry(new StaticToolRegistry(Arrays.asList(
                        knowledgeTool.tool(), orderTool, ticketTool)))
                .toolExecutor(merged)
                .capture(sink)
                .build();

        io.github.lnyocly.ai4j.agent.AgentResult result =
                agent.newSession().run(request.getQuestion());

        // 展平捕获节点：完整 input/output + latency + step 因果（合格 trace 该有的）
        List<NodeIoRecord> records = sink.records();
        List<Map<String, Object>> capturedNodes = new ArrayList<Map<String, Object>>();
        int modelNodes = 0, toolNodes = 0;
        long firstTs = records.isEmpty() ? 0L : records.get(0).getCapturedAtEpochMs();
        long prevTs = firstTs;
        for (int idx = 0; idx < records.size(); idx++) {
            NodeIoRecord rec = records.get(idx);
            long ts = rec.getCapturedAtEpochMs();
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("seq", idx);
            n.put("nodeType", rec.getNodeType());
            n.put("step", rec.getStep());
            n.put("turnId", rec.getTurnId());
            n.put("durationMs", rec.getDurationMs());            // per-node 精准执行耗时（startedAt → capturedAt）
            n.put("latencyFromStartMs", ts - firstTs);          // 距首个节点（累计时间线）
            n.put("input", JSON.toJSONString(rec.getInputs()));   // 完整 input，不截断
            n.put("output", JSON.toJSONString(rec.getOutputs())); // 完整 output，不截断
            n.put("reasoningText", rec.getReasoningText());       // MODEL 思维链（GLM thinking，TOOL 为 null）
            n.put("retryCount", rec.getRetryCount());             // MODEL 重试次数（MODEL_RETRY 计数）
            n.put("inputTokens", rec.getInputTokens());           // MODEL 输入 token（成本核算，从 usage 解析）
            n.put("outputTokens", rec.getOutputTokens());         // MODEL 输出 token（成本核算，从 usage 解析）
            capturedNodes.add(n);
            prevTs = ts;
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private Tool makeTool(String name, String desc, String paramName, String paramType, String paramDesc) {
        Tool.Function fn = new Tool.Function();
        fn.setName(name);
        fn.setDescription(desc);
        Tool.Function.Parameter param = new Tool.Function.Parameter();
        param.setType("object");
        Map<String, Tool.Function.Property> props = new HashMap<String, Tool.Function.Property>();
        Tool.Function.Property p = new Tool.Function.Property();
        p.setType(paramType);
        p.setDescription(paramDesc);
        props.put(paramName, p);
        param.setProperties(props);
        param.setRequired(Collections.singletonList(paramName));
        fn.setParameters(param);
        return new Tool("function", fn);
    }

    private Tool makeToolTwoArgs(String name, String desc,
                                 String n1, String t1, String d1,
                                 String n2, String t2, String d2) {
        Tool.Function fn = new Tool.Function();
        fn.setName(name);
        fn.setDescription(desc);
        Tool.Function.Parameter param = new Tool.Function.Parameter();
        param.setType("object");
        Map<String, Tool.Function.Property> props = new HashMap<String, Tool.Function.Property>();
        Tool.Function.Property p1 = new Tool.Function.Property();
        p1.setType(t1);
        p1.setDescription(d1);
        props.put(n1, p1);
        Tool.Function.Property p2 = new Tool.Function.Property();
        p2.setType(t2);
        p2.setDescription(d2);
        props.put(n2, p2);
        param.setProperties(props);
        param.setRequired(Arrays.asList(n1, n2));
        fn.setParameters(param);
        return new Tool("function", fn);
    }
}
