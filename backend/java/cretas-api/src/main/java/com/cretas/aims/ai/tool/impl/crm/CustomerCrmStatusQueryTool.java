package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Sprint 4 W2 S-CRM-FULL-1 — 客户 CRM 详情查询工具.
 *
 * 返回 客户生命周期阶段 / 重要程度 / 来源渠道 / 最近接洽时间 / 客户级开票默认.
 *
 * 防呆 R2: result 包含 customer name + customerCode 完整身份信息.
 */
@Slf4j
@Component
public class CustomerCrmStatusQueryTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_crm_status_query";
    }

    @Override
    public String getDescription() {
        return "查询客户 CRM 信息: 生命周期阶段 (LEAD/RECURRING/LOST 等 11 阶段) / 重要程度 " +
                "(VIP/IMPORTANT/NORMAL/LOW) / 来源渠道 / 最近接洽时间 / 客户级开票默认 " +
                "(税率 + 开票类型). 适用场景: 销售跟进前查客户状态、判断重要程度排资源优先级、" +
                "查上次接洽时间识别沉睡客户、新建销售单时确认默认税率/开票类型.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 ID — 优先使用此参数精确定位");
        properties.put("customerId", customerId);

        Map<String, Object> customerName = new HashMap<>();
        customerName.put("type", "string");
        customerName.put("description", "客户名称 — customerId 不提供时按名称精确匹配单个客户");
        properties.put("customerName", customerName);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId", null);
        String customerName = getString(params, "customerName", null);
        log.info("执行客户 CRM 状态查询 - 工厂={}, customerId={}, name={}",
                factoryId, customerId, customerName);

        CustomerDTO customer = null;
        if (customerId != null && !customerId.isBlank()) {
            customer = customerService.getCustomerById(factoryId, customerId);
        } else if (customerName != null && !customerName.isBlank()) {
            List<CustomerDTO> matches = customerService.searchCustomersByName(factoryId, customerName);
            if (matches != null && matches.size() == 1) {
                customer = matches.get(0);
            } else if (matches != null && matches.size() > 1) {
                return buildSimpleResult(
                        String.format("找到 %d 个匹配 \"%s\" 的客户, 请提供 customerId 或更精确的名称",
                                matches.size(), customerName),
                        Map.of("matches", matches.size(), "candidates", matches));
            }
        }

        if (customer == null) {
            return buildSimpleResult("未找到客户, 请确认 customerId 或 customerName", null);
        }

        // 防呆 R2: result 全身份信息 — name + customerCode + 4 CRM 字段 + 2 开票默认.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customer.getId());
        data.put("customerCode", customer.getCustomerCode());
        data.put("customerName", customer.getName());
        data.put("customerStatus", customer.getCustomerStatus() != null
                ? customer.getCustomerStatus().getDisplayName() + " (" + customer.getCustomerStatus().name() + ")"
                : "未分类");
        data.put("importance", customer.getImportance() != null
                ? customer.getImportance().getDisplayName()
                : "未分级");
        data.put("source", customer.getSource() != null
                ? customer.getSource().getDisplayName()
                : "未记录");
        data.put("lastContactedAt", customer.getLastContactedAt() != null
                ? customer.getLastContactedAt().toString()
                : "从未接洽");
        data.put("defaultTaxRate", customer.getDefaultTaxRate() != null
                ? customer.getDefaultTaxRate().toString() + "%"
                : "未设 (开票时按行项目)");
        data.put("defaultInvoiceType", customer.getDefaultInvoiceType() != null
                ? customer.getDefaultInvoiceType().getDisplayName()
                : "未设 (默认普通发票)");

        return buildSimpleResult(
                String.format("客户 %s (%s) — %s, %s",
                        customer.getName(),
                        customer.getCustomerCode(),
                        data.get("customerStatus"),
                        data.get("importance")),
                data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
