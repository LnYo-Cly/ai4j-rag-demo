package io.github.lnyocly.ai4j.rag.demo.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 售后工单 mock（演示客服 agent 的业务 tool）。
 * 真实生产接工单系统，这里用自增 id 让 demo 可跑。
 */
@Service
public class MockTicketService {

    private final AtomicInteger counter = new AtomicInteger(20000);

    public Map<String, Object> createTicket(String orderId, String reason) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ticketId", "T-" + counter.incrementAndGet());
        r.put("orderId", orderId);
        r.put("reason", reason);
        r.put("status", "created");
        r.put("sla", "1-3 个工作日内客服跟进");
        return r;
    }
}
