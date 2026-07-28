package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.WechatRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信记录近期查询工具 (Sprint 8 P1 — 卤味老板 Workdesk V1).
 *
 * <p>给销售员/老板查 "近 N 天 (默认 7 天) 的微信跟进情况", 可按客户过滤.
 * 用于"哪些客户还没回微信?" / "微信跟进列表" 类查询.
 *
 * <p>读路径, 无副作用. 防呆 R2 — 每条含 customerName / direction / 消息预览 / 几天前.
 *
 * <p>Intent Code: {@code WECHAT_RECENT_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class WechatRecordRecentQueryTool extends AbstractBusinessTool {

    /** 内容摘要截断长度. */
    private static final int CONTENT_SUMMARY_LEN = 80;
    private static final int DEFAULT_DAYS_SINCE = 7;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_DAYS_SINCE = 90;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private WechatRecordRepository wechatRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "wechat_record_recent_query";
    }

    @Override
    public String getDescription() {
        return "查询近 N 天 (默认 7 天) 微信跟进记录, 按时间倒序. "
                + "LLM 触发场景: 用户问 '哪些客户还没回微信?' / '微信跟进列表' / "
                + "'我最近 7 天的微信记录' / '查 [客户名] 的微信' / '微信待办'. "
                + "可选 customerId 过滤单客户. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> daysSince = new HashMap<>();
        daysSince.put("type", "integer");
        daysSince.put("description", "查询多少天内的微信记录 (默认 7, 上限 90)");
        daysSince.put("default", DEFAULT_DAYS_SINCE);
        daysSince.put("minimum", 1);
        daysSince.put("maximum", MAX_DAYS_SINCE);
        properties.put("daysSince", daysSince);

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "可选 — 仅查指定客户 UUID");
        properties.put("customerId", customerId);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回上限 (默认 50, 上限 200)");
        limit.put("default", DEFAULT_LIMIT);
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        int daysSince = clamp(getInteger(params, "daysSince", DEFAULT_DAYS_SINCE),
                1, MAX_DAYS_SINCE);
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
        String customerId = getString(params, "customerId", null);
        LocalDateTime since = LocalDateTime.now().minusDays(daysSince);

        log.info("wechat_record_recent_query — factory={} daysSince={} customerId={} limit={}",
                factoryId, daysSince, customerId, limit);

        List<WechatRecord> records;
        if (customerId != null && !customerId.isBlank()) {
            // 按 customer + 时间窗
            records = wechatRecordRepository.findByCustomerInTimeRange(
                    factoryId, customerId, since, LocalDateTime.now(),
                    org.springframework.data.domain.PageRequest.of(0, limit));
        } else {
            // 全 factory recent, 自行过滤时间窗
            List<WechatRecord> all = wechatRecordRepository
                    .findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(factoryId);
            records = new ArrayList<>(Math.min(limit, all.size()));
            for (WechatRecord w : all) {
                if (records.size() >= limit) break;
                if (w.getRecordTime() != null && w.getRecordTime().isAfter(since)) {
                    records.add(w);
                }
            }
        }

        // 客户名解析 cache — 同一查询多条同客户避免重复 DB hit
        Map<String, String> customerNameCache = new HashMap<>();

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>(records.size());
        for (WechatRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("customerId", r.getCustomerId());
            item.put("customerName", resolveCustomerName(factoryId,
                    r.getCustomerId(), customerNameCache));
            item.put("direction", r.getDirection() != null ? r.getDirection().name() : null);
            item.put("directionDisplay",
                    r.getDirection() != null ? r.getDirection().getDisplayName() : null);
            item.put("messageSummary", truncate(r.getMessageContent(), CONTENT_SUMMARY_LEN));
            item.put("recordTime", r.getRecordTime() != null ? r.getRecordTime().toString() : null);
            item.put("daysAgo", r.getRecordTime() != null
                    ? ChronoUnit.DAYS.between(r.getRecordTime(), now)
                    : null);
            item.put("createdByName", r.getCreatedByName());
            item.put("contactPerson", r.getContactPerson());
            items.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("daysSince", daysSince);
        data.put("customerId", customerId);
        data.put("count", items.size());
        data.put("records", items);

        String message = items.isEmpty()
                ? String.format("近 %d 天暂无微信记录", daysSince)
                : String.format("近 %d 天共 %d 条微信记录", daysSince, items.size());
        return buildSimpleResult(message, data);
    }

    private String resolveCustomerName(String factoryId, String customerId,
            Map<String, String> cache) {
        if (customerId == null) return null;
        if (cache.containsKey(customerId)) return cache.get(customerId);
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            String name = opt.map(Customer::getName).orElse(null);
            cache.put(customerId, name);
            return name;
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            cache.put(customerId, null);
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
