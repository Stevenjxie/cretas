package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * HTTP 出站调用工具 (Round 4 Fix P1-14)
 *
 * 通用 HTTP 请求工具, 可从 TriggerChain 配置中调用外部 API.
 *
 * 使用场景:
 *   - 草原鲜牧: 订单创建时调用外部价格 API 快照市场价
 *   - 门店库存同步: 生产完成时 POST 到 POS 系统
 *   - 第三方 IoT: 设备告警时 POST 到监控系统
 *
 * 参数:
 *   - url:     (required) 目标 URL
 *   - method:  (optional) GET/POST/PUT/DELETE, default GET
 *   - headers: (optional) Map<String, String>
 *   - body:    (optional) 请求体 JSON 字符串 or Map
 *   - timeout: (optional) 超时毫秒, default 5000
 */
@Slf4j
@Component
public class HttpCallTool extends AbstractBusinessTool {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getToolName() {
        return "http_call";
    }

    @Override
    public String getDescription() {
        return "通用 HTTP 出站调用工具。从 TriggerChain 配置中调用外部 API, 支持 GET/POST/PUT/DELETE, 自定义 headers 和 body。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("url", Map.of("type", "string", "description", "目标 URL"));
        properties.put("method", Map.of("type", "string", "description", "HTTP 方法, 默认 GET", "enum", List.of("GET", "POST", "PUT", "DELETE", "PATCH")));
        properties.put("headers", Map.of("type", "object", "description", "请求头 key-value"));
        properties.put("body", Map.of("type", "object", "description", "请求体 (POST/PUT/PATCH 使用)"));
        properties.put("timeout", Map.of("type", "integer", "description", "超时毫秒, 默认 5000"));
        return Map.of("type", "object", "properties", properties, "required", List.of("url"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("url");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String url = getString(params, "url");
        String methodStr = params.containsKey("method") ? getString(params, "method") : "GET";
        HttpMethod method = HttpMethod.valueOf(methodStr.toUpperCase());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Cretas-Factory", factoryId);
        if (params.get("headers") instanceof Map<?, ?> hdrs) {
            for (Map.Entry<?, ?> e : hdrs.entrySet()) {
                headers.set(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }

        HttpEntity<?> entity = new HttpEntity<>(params.get("body"), headers);

        log.info("HttpCallTool [factory={}] {} {}", factoryId, method, url);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, method, entity, Map.class);
            Map<String, Object> result = new HashMap<>();
            result.put("status", response.getStatusCodeValue());
            result.put("headers", response.getHeaders().toSingleValueMap());
            result.put("body", response.getBody());
            return buildSimpleResult("HTTP 调用成功", result);
        } catch (Exception e) {
            log.warn("HttpCallTool failed: {}", e.getMessage());
            throw new RuntimeException("HTTP 调用失败: " + e.getMessage(), e);
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
