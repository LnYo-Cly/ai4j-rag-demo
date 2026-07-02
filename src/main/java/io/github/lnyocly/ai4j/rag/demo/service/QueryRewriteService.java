package io.github.lnyocly.ai4j.rag.demo.service;

import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletion;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicChatCompletionResponse;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicContentBlock;
import io.github.lnyocly.ai4j.platform.anthropic.chat.entity.AnthropicMessage;
import io.github.lnyocly.ai4j.rag.demo.config.RagProperties;
import io.github.lnyocly.ai4j.service.IMessagesService;
import io.github.lnyocly.ai4j.service.PlatformType;
import io.github.lnyocly.ai4j.service.factory.AiService;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 查询改写（Query Rewrite）：把用户口语问题改写为适合向量检索的正式表达。
 *
 * 例：「退款怎么弄」→「用户发起订单退款申请的条件、入口与审核流程是什么」
 *
 * 价值：用户原始问题常很短或口语化，直接 embedding 语义不足；改写后补足业务主语、
 * 对齐知识库正式术语，召回质量明显提升。生产里高频问题可叠一层规则归一化 + 改写结果缓存。
 *
 * 这里用 GLM（IMessagesService）改写；同样可换 IChatService 走 OpenAI 兼容协议。
 */
@Service
public class QueryRewriteService {

    private final IMessagesService messagesService;
    private final RagProperties ragProperties;

    public QueryRewriteService(AiService aiService, RagProperties ragProperties) {
        this.messagesService = aiService.getMessagesService(PlatformType.ANTHROPIC);
        this.ragProperties = ragProperties;
    }

    public String rewrite(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        AnthropicChatCompletion req = new AnthropicChatCompletion();
        req.setModel(ragProperties.getGlmModel());
        req.setSystem("你是企业电商知识库的查询改写器。把用户的口语化问题改写为一句适合向量检索的正式表达。"
                + "要求：1) 不改变原意；2) 补足业务主语和动作；3) 对齐知识库里的正式术语（如「退款」「售后」「配送」）；"
                + "4) 只输出改写后的一句话，不要解释、不要引号、不要多余内容。");
        req.setMessages(Collections.singletonList(new AnthropicMessage("user", query)));
        req.setMaxTokens(128);
        AnthropicChatCompletionResponse resp = messagesService.messages(req);
        String rewritten = extractText(resp).trim();
        return rewritten.isEmpty() ? query : rewritten;
    }

    private String extractText(AnthropicChatCompletionResponse resp) {
        if (resp == null || resp.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AnthropicContentBlock block : resp.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        return sb.toString();
    }
}
