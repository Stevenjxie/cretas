package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
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
 * 商机阶段超 SLA 警告工具 (Sprint 8 P1 — 卤味老板 Workdesk V1).
 *
 * <p>给销售员/老板拉 "在 X 天内 (默认 21 天) 未推进 stage 的商机清单",
 * 默认排除已 terminal (CLOSED_WON/LOST) 的. 用于"哪些商机停滞了?" / "商机预警"场景.
 *
 * <p>读路径无副作用. 防呆 R2 — 每条含 customerName / title / 当前阶段 / 已停滞天数.
 *
 * <p>Intent Code: {@code OPPORTUNITY_STAGE_ALERT}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class OpportunityStageAlertTool extends AbstractBusinessTool {

    private static final int DEFAULT_SLA_DAYS = 21;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_SLA_DAYS = 365;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private SalesOpportunityRepository salesOpportunityRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "opportunity_stage_alert";
    }

    @Override
    public String getDescription() {
        return "查询超过 SLA 天数 (默认 21 天) 未推进阶段的活跃商机, 按停滞天数倒序. "
                + "LLM 触发场景: 用户问 '哪些商机超过 21 天没推进?' / '商机预警列表' / "
                + "'商机停滞清单' / 'stale opportunities' / '需要催的商机'. "
                + "可选 ownerId 过滤业务员. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> slaDays = new HashMap<>();
        slaDays.put("type", "integer");
        slaDays.put("description", "SLA 天数阈值 (默认 21, 上限 365)");
        slaDays.put("default", DEFAULT_SLA_DAYS);
        slaDays.put("minimum", 1);
        slaDays.put("maximum", MAX_SLA_DAYS);
        properties.put("slaDays", slaDays);

        Map<String, Object> ownerId = new HashMap<>();
        ownerId.put("type", "integer");
        ownerId.put("description", "可选 — 仅查指定业务员 User.id");
        properties.put("ownerId", ownerId);

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
        int slaDays = clamp(getInteger(params, "slaDays", DEFAULT_SLA_DAYS), 1, MAX_SLA_DAYS);
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
        Long ownerId = getLong(params, "ownerId");
        LocalDateTime threshold = LocalDateTime.now().minusDays(slaDays);

        log.info("opportunity_stage_alert — factory={} slaDays={} ownerId={} limit={}",
                factoryId, slaDays, ownerId, limit);

        // 拉 factory 全 active opportunities, in-memory 过滤
        // (用 ownerId 时走带索引的 queries)
        org.springframework.data.domain.Page<SalesOpportunity> opps;
        if (ownerId != null) {
            opps = salesOpportunityRepository.findByFactoryIdAndOwnerIdAndDeletedAtIsNull(
                    factoryId, ownerId, PageRequest.of(0, MAX_LIMIT));
        } else {
            opps = salesOpportunityRepository.findByFactoryIdAndDeletedAtIsNull(
                    factoryId, PageRequest.of(0, MAX_LIMIT));
        }

        Map<String, String> nameCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (SalesOpportunity opp : opps.getContent()) {
            if (items.size() >= limit) break;
            if (opp.getStage() == null || opp.getStage().isTerminal()) continue;
            // 用 updatedAt (最近 stage 转换) 判断 stale
            LocalDateTime lastChange = opp.getUpdatedAt();
            if (lastChange == null) lastChange = opp.getCreatedAt();
            if (lastChange == null) continue;
            if (lastChange.isAfter(threshold)) continue;  // 仍在 SLA 内
            long stagnantDays = ChronoUnit.DAYS.between(lastChange, now);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", opp.getId());
            item.put("title", opp.getTitle());
            item.put("customerId", opp.getCustomerId());
            item.put("customerName",
                    resolveCustomerName(factoryId, opp.getCustomerId(), nameCache));
            item.put("stage", opp.getStage().name());
            item.put("stageDisplay", opp.getStage().getDisplayName());
            item.put("probability", opp.getProbability());
            item.put("valueAmount", opp.getValueAmount());
            item.put("ownerId", opp.getOwnerId());
            item.put("expectedCloseDate",
                    opp.getExpectedCloseDate() != null
                            ? opp.getExpectedCloseDate().toString() : null);
            item.put("lastChangeAt", lastChange.toString());
            item.put("stagnantDays", stagnantDays);
            items.add(item);
        }

        // 按停滞天数倒序
        items.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("stagnantDays", 0)).longValue(),
                ((Number) a.getOrDefault("stagnantDays", 0)).longValue()));

        Map<String, Object> data = new HashMap<>();
        data.put("slaDays", slaDays);
        data.put("ownerId", ownerId);
        data.put("count", items.size());
        data.put("opportunities", items);

        String message = items.isEmpty()
                ? String.format("没有商机停滞超过 %d 天", slaDays)
                : String.format("%d 个商机停滞超 %d 天, 建议立刻推进或更新阶段",
                        items.size(), slaDays);
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
