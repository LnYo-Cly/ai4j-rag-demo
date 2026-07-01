package io.github.lnyocly.ai4j.rag.demo.controller;

import io.github.lnyocly.ai4j.rag.demo.domain.ChatRequest;
import io.github.lnyocly.ai4j.rag.demo.domain.RagAnswer;
import io.github.lnyocly.ai4j.rag.demo.service.RagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
