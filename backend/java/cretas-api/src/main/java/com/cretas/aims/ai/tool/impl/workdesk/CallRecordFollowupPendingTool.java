package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.CallRecord;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.CallType;
import com.cretas.aims.repository.CallRecordRepository;
import com.cretas.aims.repository.CustomerRepository;
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
 * 待跟进通话查询工具 (Sprint 8 P1 — 卤味老板 Workdesk V1).
 *
 * <p>给销售员/老板拉 "近 N 天 (默认 7 天) 未接电话清单 / 需回访清单".
 * 用于"今日需要打谁电话?" / "回电待办"场景.
 *
 * <p>读路径无副作用. 防呆 R2 — 每条含 customerName / contactPhone / 几天前.
 *
 * <p>Intent Code: {@code CALL_FOLLOWUP_PENDING}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class CallRecordFollowupPendingTool extends AbstractBusinessTool {

    private static final int DEFAULT_DAYS_SINCE = 7;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_DAYS_SINCE = 90;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private CallRecordRepository callRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "call_record_followup_pending";
    }

    @Override
    public String getDescription() {
        return "查询近 N 天 (默认 7 天) 待回访的通话记录. 默认查 MISSED (漏接), 也可指定 INCOMING/OUTGOING. "
                + "LLM 触发场景: 用户问 '哪些未接电话需要回?' / '近 7 天 missed call' / "
                + "'回电列表' / '电话跟进待办' / '今天打谁电话'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> daysSince = new HashMap<>();
        daysSince.put("type", "integer");
        daysSince.put("description", "查询多少天内通话 (默认 7, 上限 90)");
        daysSince.put("default", DEFAULT_DAYS_SINCE);
        daysSince.put("minimum", 1);
        daysSince.put("maximum", MAX_DAYS_SINCE);
        properties.put("daysSince", daysSince);

        Map<String, Object> callType = new HashMap<>();
        callType.put("type", "string");
        callType.put("description",
                "可选 — 过滤通话类型. 默认 MISSED. 合法: MISSED / INCOMING / OUTGOING");
        callType.put("enum", List.of("MISSED", "INCOMING", "OUTGOING"));
        callType.put("default", "MISSED");
        properties.put("callType", callType);

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
        String callTypeStr = getString(params, "callType", "MISSED");
        CallType filterType;
        try {
            filterType = CallType.valueOf(callTypeStr);
        } catch (IllegalArgumentException ex) {
            log.warn("call_record_followup_pending — invalid callType={}, fallback MISSED", callTypeStr);
            filterType = CallType.MISSED;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(daysSince);

        log.info("call_record_followup_pending — factory={} daysSince={} type={} limit={}",
                factoryId, daysSince, filterType, limit);

        // 拉 factory 全 recent, 按类型/时间窗过滤 in-memory (单 factory 单业务员 7d 内 missed call 数据量小)
        List<CallRecord> all = callRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCallTimeDesc(factoryId);

        List<CallRecord> filtered = new ArrayList<>(Math.min(limit, all.size()));
        for (CallRecord c : all) {
            if (filtered.size() >= limit) break;
            if (c.getCallType() != filterType) continue;
            if (c.getCallTime() == null) continue;
            if (!c.getCallTime().isAfter(since)) continue;
            filtered.add(c);
        }

        // 客户名 cache
        Map<String, String> nameCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>(filtered.size());
        for (CallRecord c : filtered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("customerId", c.getCustomerId());
            item.put("customerName", resolveCustomerName(factoryId, c.getCustomerId(), nameCache));
            item.put("callTime", c.getCallTime().toString());
            item.put("daysAgo", ChronoUnit.DAYS.between(c.getCallTime(), now));
            item.put("callType", c.getCallType().name());
            item.put("callTypeDisplay", c.getCallType().getDisplayName());
            item.put("durationSec", c.getDurationSec());
            item.put("contactName", c.getContactName());
            item.put("contactPhone", c.getContactPhone());
            items.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("daysSince", daysSince);
        data.put("callType", filterType.name());
        data.put("count", items.size());
        data.put("records", items);

        String message = items.isEmpty()
                ? String.format("近 %d 天暂无 %s 类型通话需要跟进", daysSince, filterType.getDisplayName())
                : String.format("近 %d 天共 %d 条 %s 通话, 建议尽快回访",
                        daysSince, items.size(), filterType.getDisplayName());
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

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
