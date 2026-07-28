package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.CustomerImportance;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 客户优先级查询工具 (Sprint 8 P1 — 卤味老板 Workdesk V1).
 *
 * <p>聚合 Customer.importance + active SalesOpportunity stage 给销售员一个
 * "按重要性 + 商机阶段" 排序的客户清单. 用于"今天该跟谁?" 类查询.
 *
 * <p>读路径, 无副作用 — 安全 LLM 高频调用.
 *
 * <p>防呆 R2 (context with identity): 每条返回含 customerId + customerName,
 * 避免 LLM 拿 UUID 无法 readable.
 *
 * <p>Intent Code: {@code CUSTOMER_PRIORITY_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class CustomerPriorityQueryTool extends AbstractBusinessTool {

    /** 默认返回客户上限 — 保 5min Workdesk 渲染流畅. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SalesOpportunityRepository salesOpportunityRepository;

    @Override
    public String getToolName() {
        return "customer_priority_query";
    }

    @Override
    public String getDescription() {
        return "按客户重要性 + 商机阶段排序输出客户清单, 含 importance / 活跃商机数 / 最高商机阶段. "
                + "LLM 触发场景: 用户问 '今天该跟谁?' / '按优先级列客户' / '我的重点客户' / "
                + "'今天打谁电话' / '客户跟进清单'. "
                + "可选 includeStages 过滤只显有指定阶段商机的客户. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> includeStages = new HashMap<>();
        includeStages.put("type", "array");
        includeStages.put("description",
                "可选 — 仅显示有指定商机阶段的客户. 留空 = 显示所有客户. "
                        + "合法值: LEAD / QUALIFIED / DEMO / PROPOSAL / NEGOTIATE / VERBAL / CLOSED_WON / CLOSED_LOST");
        Map<String, Object> stageItem = new HashMap<>();
        stageItem.put("type", "string");
        includeStages.put("items", stageItem);
        properties.put("includeStages", includeStages);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回客户上限 (默认 50, 上限 200)");
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
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
        List<String> includeStages = getList(params, "includeStages");
        log.info("customer_priority_query — factory={} limit={} includeStages={}",
                factoryId, limit, includeStages);

        // 1. 拉客户基础清单 (paged) — 取活跃客户优先
        var customerPage = customerRepository.findByFactoryIdAndIsActiveTrue(
                factoryId, PageRequest.of(0, limit));
        List<Customer> customers = customerPage != null
                ? customerPage.getContent()
                : Collections.emptyList();

        // 2. 对每个客户聚合 active opportunities (factoryId + customerId scope)
        List<Map<String, Object>> items = new ArrayList<>(customers.size());
        for (Customer c : customers) {
            List<SalesOpportunity> opportunities =
                    salesOpportunityRepository
                            .findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                                    factoryId, c.getId());

            // includeStages filter (如有)
            if (includeStages != null && !includeStages.isEmpty()) {
                List<String> stageFilter = includeStages;
                opportunities = opportunities.stream()
                        .filter(o -> o.getStage() != null
                                && stageFilter.contains(o.getStage().name()))
                        .toList();
                // 跳过无匹配商机的客户
                if (opportunities.isEmpty()) {
                    continue;
                }
            }

            OpportunityStage highestStage = opportunities.stream()
                    .map(SalesOpportunity::getStage)
                    .filter(s -> s != null && !s.isTerminal())
                    .max((a, b) -> Integer.compare(a.ordinalForFlow(), b.ordinalForFlow()))
                    .orElse(null);

            Map<String, Object> item = new HashMap<>();
            item.put("customerId", c.getId());
            item.put("customerName", c.getName());
            item.put("customerCode", c.getCustomerCode());
            item.put("importance",
                    c.getImportance() != null ? c.getImportance().name() : null);
            item.put("importanceDisplay",
                    c.getImportance() != null ? c.getImportance().getDisplayName() : null);
            item.put("contactPerson", c.getContactPerson());
            item.put("contactPhone", c.getContactPhone());
            item.put("activeOpportunityCount", opportunities.size());
            item.put("highestStage", highestStage != null ? highestStage.name() : null);
            item.put("highestStageDisplay",
                    highestStage != null ? highestStage.getDisplayName() : null);

            // 优先级评分 (越大越优先)
            int priorityScore = scorePriority(c.getImportance(), highestStage,
                    opportunities.size());
            item.put("priorityScore", priorityScore);
            items.add(item);
        }

        // 3. 按 priorityScore 倒序
        items.sort((a, b) -> Integer.compare(
                (int) b.getOrDefault("priorityScore", 0),
                (int) a.getOrDefault("priorityScore", 0)));

        Map<String, Object> data = new HashMap<>();
        data.put("limit", limit);
        data.put("count", items.size());
        data.put("customers", items);

        String message = items.isEmpty()
                ? "未找到符合条件的客户"
                : String.format("共 %d 个客户已按优先级排序", items.size());
        return buildSimpleResult(message, data);
    }

    /**
     * 优先级评分: VIP(40) > IMPORTANT(20) > NORMAL(10) > LOW(0), 加 stage bonus
     * (NEGOTIATE/VERBAL 各 +15, DEMO/PROPOSAL 各 +10, QUALIFIED +5), 加 active count.
     */
    private int scorePriority(CustomerImportance importance, OpportunityStage highest,
            int oppCount) {
        int score = 0;
        if (importance != null) {
            switch (importance) {
                case VIP: score += 40; break;
                case IMPORTANT: score += 20; break;
                case NORMAL: score += 10; break;
                case LOW: score += 0; break;
            }
        }
        if (highest != null) {
            switch (highest) {
                case NEGOTIATE: case VERBAL: score += 15; break;
                case DEMO: case PROPOSAL: score += 10; break;
                case QUALIFIED: score += 5; break;
                default: break;
            }
        }
        score += Math.min(oppCount, 10);  // cap active count contribution
        return score;
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
