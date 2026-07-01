package io.github.lnyocly.ai4j.rag.demo.domain;

import lombok.Data;

@Data
public class ChatRequest {
    /** 用户问题。 */
    private String question;
    /** 租户标识（多租户隔离用，对应 dataset 边界）。 */
    private String tenantId = "default";
}
