package io.github.lnyocly.ai4j.rag.demo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {
    /** LLM 生成的答案。 */
    private String answer;
    /** 引用来源（溯源用）。 */
    private List<ReferenceItem> references;
    /** 本次检索命中的 chunk 数。 */
    private int hitCount;
    /** 是否降级（检索为空时直接 LLM 兜底）。 */
    private boolean degraded;
}
