package io.github.lnyocly.ai4j.rag.demo.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单查询 mock（演示客服 agent 的业务 tool）。
 * 真实生产接订单系统（DB/RPC），这里用内存假数据让 demo 可跑。
 */
@Service
public class MockOrderService {

    public Map<String, Object> queryOrder(String orderId) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("orderId", orderId);
        r.put("status", "shipped");
        r.put("logistics", "已发货，预计 2026-07-04 送达");
        r.put("signedAt", "未签收");
        r.put("amount", "299.00");
        return r;
    }
}
