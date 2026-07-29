package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerQualityStandard;
import com.cretas.aims.repository.CustomerQualityStandardRepository;
import com.cretas.aims.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 客户质量标准查询 Tool — Sprint 8 P4c (质量主管 Workdesk).
 *
 * <p>查指定 customer 的质量标准要求 (产品规格/COA 要求/接收检测项).
 *
 * <p><b>Sprint 9 P1.1 重构 (2026-05-21)</b>: 接入独立 {@link CustomerQualityStandard} entity,
 * 优先级:
 * <ol>
 *   <li><b>CUSTOMER_REGISTERED</b>: RDB 查 {@code customer_quality_standards} 有 active 条目 → 返登记标准</li>
 *   <li><b>CUSTOMER_NOTES_PLUS_FACTORY_DEFAULT</b>: 客户 notes 含"质量/标准/COA/检测"关键字 → 返 notes + 工厂默认</li>
 *   <li><b>FACTORY_DEFAULT</b>: 无任何客户级数据 → 返工厂默认 + actionHint 引导去 CRM 登记</li>
 * </ol>
 *
 * <p>R5 dead-end (fallback) 路径保留以保证 backwards-compat (Sprint 8 行为不破坏).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"X 客户的质量标准是什么"</li>
 *   <li>"鲜湘缘要什么 COA"</li>
 *   <li>"客户接收检测项"</li>
 *   <li>"客户质量要求"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code CUSTOMER_QUALITY_STANDARD}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4c)
 * @since 2026-05-21 Sprint 9 P1.1 接入 CustomerQualityStandard entity
 */
@Slf4j
@Component
public class CustomerQualityStandardTool extends AbstractBusinessTool {

    /** 工厂默认质量标准 (fallback). */
    private static final List<Map<String, Object>> DEFAULT_STANDARDS = List.of(
            standard("微生物总数", "≤ 10⁵ CFU/g", "GB 4789.2"),
            standard("亚硝酸钠残留", "≤ 30 mg/kg", "GB 2760-2014"),
            standard("感官", "色泽正常, 无异味, 无杂质", "厂内标准"),
            standard("水分", "≤ 70%", "厂内标准"),
            standard("中心温度", "煮制 ≥ 75°C", "HACCP CCP-2")
    );

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerQualityStandardRepository customerQualityStandardRepository;

    @Override
    public String getToolName() {
        return "customer_quality_standard";
    }

    @Override
    public String getDescription() {
        return "客户质量标准查询 — 查指定 customer 的产品质量要求 / COA / 接收检测项. "
                + "优先查独立 CustomerQualityStandard entity (Sprint 9 P1.1), "
                + "若客户暂无登记标准则返回工厂默认标准 + actionHint 引导去 CRM 配置. "
                + "LLM 触发: 'X 客户的质量标准是什么' / '鲜湘缘要什么 COA' / "
                + "'客户接收检测项' / '客户质量要求'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 ID (必填), UUID 形式");
        properties.put("customerId", customerId);

        schema.put("properties", properties);
        schema.put("required", List.of("customerId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("customerId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");
        log.info("customer_quality_standard — factory={} customer={}", factoryId, customerId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);

        Optional<Customer> customerOpt = customerRepository
                .findByIdAndFactoryId(customerId, factoryId);

        if (customerOpt.isEmpty()) {
            data.put("customerFound", false);
            data.put("standards", DEFAULT_STANDARDS);
            data.put("source", "FACTORY_DEFAULT");
            data.put("actionHint", "/crm/customers — 客户未找到或非本工厂, 请确认 customerId");
            return buildSimpleResult(
                    String.format("⚠️ 客户 %s 未找到, 使用工厂默认质量标准 (%d 项)",
                            customerId, DEFAULT_STANDARDS.size()),
                    data);
        }

        Customer customer = customerOpt.get();
        data.put("customerFound", true);
        data.put("customerName", customer.getName());
        data.put("customerType", customer.getCustomerType());
        data.put("industry", customer.getIndustry());

        // ===== Sprint 9 P1.1: 优先查独立 CustomerQualityStandard entity =====
        List<CustomerQualityStandard> registered = customerQualityStandardRepository
                .findByFactoryIdAndCustomerIdAndActiveTrue(factoryId, customerId);

        if (!registered.isEmpty()) {
            List<Map<String, Object>> registeredStandards = new ArrayList<>(registered.size());
            for (CustomerQualityStandard s : registered) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("standardCode", s.getStandardCode());
                entry.put("name", s.getStandardName());
                entry.put("category", s.getCategory());
                entry.put("description", s.getDescription());
                entry.put("limit", formatLimit(s.getLimitValue(), s.getUnit()));
                entry.put("limitValue", s.getLimitValue());
                entry.put("unit", s.getUnit());
                registeredStandards.add(entry);
            }
            data.put("standards", registeredStandards);
            data.put("source", "CUSTOMER_REGISTERED");
            data.put("registeredCount", registeredStandards.size());
            return buildSimpleResult(
                    String.format("✅ 客户 %s 登记标准 %d 项",
                            customer.getName(), registeredStandards.size()),
                    data);
        }

        // ===== R5 fallback (Sprint 8 行为, backwards-compat) =====
        // 尝试从 notes 提取关键词, 否则返默认 + actionHint
        boolean hasCustomerStandards = false;
        String standardsText = null;
        if (customer.getNotes() != null && !customer.getNotes().isBlank()) {
            // 简单提取: notes 含"质量/标准/COA"关键字时, 视为客户标准
            String notes = customer.getNotes();
            if (notes.contains("质量") || notes.contains("标准")
                    || notes.contains("COA") || notes.contains("检测")) {
                standardsText = notes;
                hasCustomerStandards = true;
            }
        }

        if (hasCustomerStandards) {
            data.put("standards", DEFAULT_STANDARDS); // 仍提供基础标准
            data.put("customerSpecificText", standardsText);
            data.put("source", "CUSTOMER_NOTES_PLUS_FACTORY_DEFAULT");
            data.put("actionHint", "/crm/customers/" + customerId
                    + "/quality-standards — 建议把 notes 中的质量要求迁移到独立标准登记");
            return buildSimpleResult(
                    String.format("✅ 客户 %s 标准已找到 (notes 中含质量要求)", customer.getName()),
                    data);
        }

        // 完全无客户标准 — R5 dead-end + actionHint
        data.put("standards", DEFAULT_STANDARDS);
        data.put("source", "FACTORY_DEFAULT");
        data.put("actionHint", "/crm/customers/" + customerId + "/quality-standards"
                + " — 客户暂无质量标准登记, 建议去客户详情页质量标准 tab 登记客户特殊要求");
        return buildSimpleResult(
                String.format("ℹ️ 客户 %s 暂无自定义质量标准, 使用工厂默认 %d 项. " +
                        "建议去客户详情页登记客户特殊要求",
                        customer.getName(), DEFAULT_STANDARDS.size()),
                data);
    }

    /** Format limit_value + unit 为人类可读字符串, e.g. "≤ 10.0000 CFU/g" or "—" if NULL. */
    private static String formatLimit(BigDecimal limit, String unit) {
        if (limit == null) {
            return unit == null ? "—" : unit;
        }
        return unit == null ? limit.toPlainString() : "≤ " + limit.toPlainString() + " " + unit;
    }

    private static Map<String, Object> standard(String name, String limit, String basis) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("limit", limit);
        s.put("basis", basis);
        return s;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
