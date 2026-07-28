package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerTrackingRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.CustomerTrackingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 客户跟进记录查询工具 (Sprint 5 Tool 2).
 *
 * <p>包装 {@link CustomerTrackingRecordRepository} (PR #774 shipped), 供 AIChat 查询
 * 客户最近的拜访/电话/邮件等跟进历史. Read-only — 无副作用.
 *
 * <p>防呆 R2 (context with identity): 返回 payload 含 customerId + customerName 及每条
 * 记录的 recordTime / recorderName / content 摘要 (~80 chars), 让 LLM 输出明确上下文.
 *
 * <p>Intent Code: {@code CUSTOMER_TRACKING_RECENT_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Slf4j
@Component
public class CustomerTrackingRecentQueryTool extends AbstractBusinessTool {

    /** 内容摘要截断长度. */
    private static final int CONTENT_SUMMARY_LEN = 80;

    /** 默认返回最近 5 条. */
    private static final int DEFAULT_LIMIT = 5;

    /** 默认回看 30 天. */
    private static final int DEFAULT_DAYS_BACK = 30;

    /** 上限保护 — 防止 LLM 拉爆. */
    private static final int MAX_LIMIT = 50;
    private static final int MAX_DAYS_BACK = 365;

    @Autowired
    private CustomerTrackingRecordRepository customerTrackingRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "customer_tracking_recent_query";
    }

    @Override
    public String getDescription() {
        return "查询客户最近的跟进记录 (拜访 / 电话 / 邮件等沟通历史). "
                + "适用场景: 用户问 '客户 X 最近联系过吗?' / '上次跟进记录是什么?' / "
                + "'查 [客户名] 最近 N 次跟进' / '客户回访记录' / '上次跟客户聊了什么'. "
                + "返回按时间倒序的跟进记录, 含跟进人 / 跟进内容摘要 / 联系人. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 UUID, 必填. 可由 customer_search Tool 解析客户名得到.");
        properties.put("customerId", customerId);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回条数 (默认 5, 上限 50)");
        limit.put("default", DEFAULT_LIMIT);
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        properties.put("limit", limit);

        Map<String, Object> daysBack = new HashMap<>();
        daysBack.put("type", "integer");
        daysBack.put("description", "回看天数 (默认 30, 上限 365)");
        daysBack.put("default", DEFAULT_DAYS_BACK);
        daysBack.put("minimum", 1);
        daysBack.put("maximum", MAX_DAYS_BACK);
        properties.put("daysBack", daysBack);

        schema.put("properties", properties);
        schema.put("required", List.of("customerId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("customerId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("customerId".equals(paramName)) {
            return "请问您要查询哪位客户的跟进记录？请提供客户名称或 ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("customerId".equals(paramName)) {
            return "客户ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
        int daysBack = clamp(getInteger(params, "daysBack", DEFAULT_DAYS_BACK), 1, MAX_DAYS_BACK);

        log.info("customer_tracking_recent_query — factory={} customer={} limit={} daysBack={}",
                factoryId, customerId, limit, daysBack);

        // 防呆 R2: 先解析客户名, 即使没有记录也能回出明确上下文 "X 客户最近 30 天无跟进"
        String customerName = resolveCustomerName(factoryId, customerId);
        // 防呆 R2 (user-facing display): name 解析失败时 fall back to "该客户" 而非裸 UUID.
        String customerNameForDisplay = (customerName != null && !customerName.isBlank())
                ? customerName : "该客户";

        LocalDateTime since = LocalDateTime.now().minusDays(daysBack);
        List<CustomerTrackingRecord> records = customerTrackingRecordRepository.findRecentForCustomer(
                factoryId, customerId, since, PageRequest.of(0, limit));

        List<Map<String, Object>> items = new ArrayList<>(records.size());
        for (CustomerTrackingRecord r : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.getId());
            item.put("recordTime", r.getRecordTime());
            item.put("recorderName", r.getRecorderName());
            item.put("contactPerson", r.getContactPerson());
            item.put("contactPhone", r.getContactPhone());
            item.put("contentSummary", truncate(r.getContent(), CONTENT_SUMMARY_LEN));
            items.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("customerId", customerId);
        data.put("customerName", customerName);
        data.put("daysBack", daysBack);
        data.put("limit", limit);
        data.put("count", items.size());
        data.put("records", items);

        String message;
        if (items.isEmpty()) {
            message = String.format("%s 最近 %d 天暂无跟进记录",
                    customerNameForDisplay, daysBack);
        } else {
            message = String.format("%s 最近 %d 天共 %d 条跟进记录",
                    customerNameForDisplay, daysBack, items.size());
        }
        return buildSimpleResult(message, data);
    }

    /** Resolve customerId → customer name (best-effort; returns null if not found / different factory). */
    private String resolveCustomerName(String factoryId, String customerId) {
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt.map(Customer::getName).orElse(null);
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed for customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
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
