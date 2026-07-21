package io.github.lnyocly.ai4j.rag.demo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.lnyocly.ai4j.rag.demo.domain.RagAnswer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 把 demo 的 answer cache + ask 计数接进 Micrometer —— /actuator/prometheus 暴露 cache 命中率 +
 * 请求量，供 K8s HPA（17.3 自定义指标）和 Grafana 用。
 *
 * cache 做成 @Bean 而不是 RagQueryService 内部 new，是为了这里能在构建时绑定 CaffeineCacheMetrics
 * （recordStats() 已开，绑定后命中率/eviction/加载耗时自动进 metrics）。
 */
@Configuration
public class RagMetrics {

    /** ask 请求计数器名（HPA 用 rate(rag_ask_requests_total[1m]) 算 QPS）。 */
    public static final String ASK_COUNTER = "rag.ask.requests";

    @Bean
    public Cache<String, RagAnswer> answerCache(MeterRegistry registry) {
        Cache<String, RagAnswer> cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
        // 绑定后 → rag_cache_size / rag_cache_hit_total / rag_cache_miss_total / rag_cache_eviction_total 等
        CaffeineCacheMetrics.monitor(registry, cache, "rag_cache");
        return cache;
    }
}
