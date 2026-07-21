package io.github.lnyocly.ai4j.rag.demo.domain;

import io.github.lnyocly.ai4j.memory.ChatMemoryItem;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    /** 用户问题。 */
    private String question;
    /** 租户标识（多租户隔离用，对应 dataset 边界）。 */
    private String tenantId = "default";
    /** 对话历史（conversational-enabled=true 时用；planner rewrite 据此消解 follow-up 指代）。
     *  由调用方维护（demo 不内置 session 存储），每轮把上一轮 user/assistant 追加进来即可。 */
    private List<ChatMemoryItem> history;
}
