package io.github.lnyocly.ai4j.rag.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ai4j 企业级 RAG demo 入口。
 * Spring Boot 3.2 + ai4j-spring-boot-starter 2.4.0 + PgVector + Ollama embedding + GLM。
 */
@SpringBootApplication
public class RagDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagDemoApplication.class, args);
    }
}
