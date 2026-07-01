package io.github.lnyocly.ai4j.rag.demo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceItem {
    private String sourceName;
    private String sectionTitle;
    private String snippet;
}
