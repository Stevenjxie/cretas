package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * G7 — 供应商进价 gold 写入客户端。
 *
 * <p>把已确认的送货单行项 POST 到 Python gold 层
 * {@code /api/smartbi/gold/supplier-price/batch-upsert} → agg_supplier_price (smartbi_db)。</p>
 *
 * <p>Auth: 复用 INTERNAL_API_SECRET + X-Factory-Id 模式 (同 GoldFinanceClient)。
 * 转发 X-User-Role 让 Python RBAC 正确 (写路径本身不脱敏, 但读 trend/latest 会)。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Slf4j
@Component
public class SupplierPriceGoldClient {

    private final PythonSmartBIConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String internalSecret = System.getenv("INTERNAL_API_SECRET") != null
            ? System.getenv("INTERNAL_API_SECRET") : "";

    public SupplierPriceGoldClient(PythonSmartBIConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    @PostConstruct
    public void init() {
        log.info("SupplierPriceGoldClient initialized; python_url={} has_secret={}",
                config.getUrl(), !internalSecret.isEmpty());
    }

    private String currentUserRole() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest req = servletAttrs.getRequest();
                Object role = req.getAttribute("role");
                return role != null ? role.toString() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("currentUserRole resolve failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量写供应商进价行到 gold 表。
     *
     * @param factoryId 工厂 (租户) id
     * @param noteId    来源送货单 id (写入 source_note_id)
     * @param lines     行项 list of {ingredientName, normalizedName, rawMaterialTypeId,
     *                  supplierId, supplierName, deliveryDate(YYYY-MM-DD), unitPrice,
     *                  quantity, unit, lineAmount}
     * @return 插入行数 (data.count)
     * @throws IOException gold 不可达 / 非 2xx / 解析失败 — 调用方 fail-soft 处理 (D5)。
     */
    public int upsertSupplierPriceBatch(String factoryId, String noteId,
            List<Map<String, Object>> lines) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("factoryId", factoryId);
        payload.put("noteId", noteId);
        payload.put("lines", lines != null ? lines : List.of());

        String json = objectMapper.writeValueAsString(payload);
        Request.Builder reqBuilder = new Request.Builder()
                .url(config.getUrl() + "/api/smartbi/gold/supplier-price/batch-upsert")
                .post(RequestBody.create(json, JSON));
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            String body = resp.body() != null ? resp.body().string() : "{}";
            if (!resp.isSuccessful()) {
                throw new IOException("supplier-price batch-upsert HTTP " + resp.code()
                        + " in " + elapsed + "ms: " + body);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            if (!Boolean.TRUE.equals(parsed.get("success"))) {
                throw new IOException("supplier-price batch-upsert returned success=false: "
                        + parsed.get("message"));
            }
            Object data = parsed.get("data");
            int count = 0;
            if (data instanceof Map<?, ?> dataMap) {
                Object c = dataMap.get("count");
                if (c instanceof Number n) {
                    count = n.intValue();
                }
            }
            log.info("Gold supplier-price upsert factory={} note={} count={} in {}ms",
                    factoryId, noteId, count, elapsed);
            return count;
        }
    }
}
