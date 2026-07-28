package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.dto.customer.UpdateCustomerRequest;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class CustomerUpdateTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_update";
    }

    @Override
    public String getDescription() {
        return "更新客户信息。根据客户ID更新客户的联系人、电话、地址、类型等字段, " +
                "以及 Sprint 4 W2 S-CRM-FULL-1 新增的 CRM 字段 (生命周期阶段 / 重要程度 / 来源渠道 / 最近接洽时间) " +
                "和 S-INVOICE-CLIENT-1 客户级开票默认 (税率 / 开票类型). " +
                "适用场景: 修改客户信息、销售跟进推进客户阶段 (如 QUOTING→SIGNING)、" +
                "标记 VIP/重要客户、记录刚接洽完时间、设置客户专属开票默认.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID");
        properties.put("customerId", customerId);

        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "客户名称");
        properties.put("name", name);

        Map<String, Object> contactPerson = new HashMap<>();
        contactPerson.put("type", "string");
        contactPerson.put("description", "联系人姓名");
        properties.put("contactPerson", contactPerson);

        Map<String, Object> phone = new HashMap<>();
        phone.put("type", "string");
        phone.put("description", "联系电话");
        properties.put("phone", phone);

        Map<String, Object> shippingAddress = new HashMap<>();
        shippingAddress.put("type", "string");
        shippingAddress.put("description", "收货地址");
        properties.put("shippingAddress", shippingAddress);

        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        type.put("description", "客户类型");
        properties.put("type", type);

        // Sprint 4 W2 S-CRM-FULL-1 — 4 CRM 字段
        Map<String, Object> customerStatus = new HashMap<>();
        customerStatus.put("type", "string");
        customerStatus.put("description", "客户生命周期阶段 (LEAD/INITIAL_CONTACT/SAMPLE_SENT/" +
                "QUOTING/NEGOTIATING/SIGNING/RECURRING/INACTIVE/LOST/BLACKLIST/RECOVERED)");
        properties.put("customerStatus", customerStatus);

        Map<String, Object> importance = new HashMap<>();
        importance.put("type", "string");
        importance.put("description", "客户重要程度 (VIP/IMPORTANT/NORMAL/LOW)");
        properties.put("importance", importance);

        Map<String, Object> source = new HashMap<>();
        source.put("type", "string");
        source.put("description", "客户来源渠道 (EXHIBITION/REFERRAL/WEBSITE/SEARCH_ENGINE/" +
                "WECHAT/PHONE/COLD_VISIT/PLATFORM/PARTNER/REPEAT_PURCHASE/OTHER)");
        properties.put("source", source);

        Map<String, Object> lastContactedAt = new HashMap<>();
        lastContactedAt.put("type", "string");
        lastContactedAt.put("description", "最近接洽时间 ISO 8601 格式 (e.g. 2026-05-17T14:30:00). 记录销售刚接洽完用.");
        properties.put("lastContactedAt", lastContactedAt);

        // Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认
        Map<String, Object> defaultTaxRate = new HashMap<>();
        defaultTaxRate.put("type", "number");
        defaultTaxRate.put("description", "客户级默认税率 (%). 新建销售单时自动 prefill 到行项目.");
        properties.put("defaultTaxRate", defaultTaxRate);

        Map<String, Object> defaultInvoiceType = new HashMap<>();
        defaultInvoiceType.put("type", "string");
        defaultInvoiceType.put("description", "客户级默认开票类型 (NORMAL/SPECIAL/DIGITAL/RECEIPT/NONE/OTHER)");
        properties.put("defaultInvoiceType", defaultInvoiceType);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("customerId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("customerId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");

        UpdateCustomerRequest request = new UpdateCustomerRequest();
        String name = getString(params, "name");
        if (name != null) request.setName(name);
        String contactPerson = getString(params, "contactPerson");
        if (contactPerson != null) request.setContactPerson(contactPerson);
        String phone = getString(params, "phone");
        if (phone != null) request.setPhone(phone);
        String shippingAddress = getString(params, "shippingAddress");
        if (shippingAddress != null) request.setShippingAddress(shippingAddress);
        String type = getString(params, "type");
        if (type != null) request.setType(type);

        // Sprint 4 W2 S-CRM-FULL-1 — 4 CRM fields (enum 校验自动由 valueOf 触发, invalid → IllegalArgumentException)
        String customerStatus = getString(params, "customerStatus");
        if (customerStatus != null && !customerStatus.isBlank()) {
            request.setCustomerStatus(com.cretas.aims.entity.enums.CustomerStatus.valueOf(customerStatus));
        }
        String importance = getString(params, "importance");
        if (importance != null && !importance.isBlank()) {
            request.setImportance(com.cretas.aims.entity.enums.CustomerImportance.valueOf(importance));
        }
        String source = getString(params, "source");
        if (source != null && !source.isBlank()) {
            request.setSource(com.cretas.aims.entity.enums.CustomerSource.valueOf(source));
        }
        String lastContactedAt = getString(params, "lastContactedAt");
        if (lastContactedAt != null && !lastContactedAt.isBlank()) {
            request.setLastContactedAt(java.time.LocalDateTime.parse(lastContactedAt));
        }

        // Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认
        Object taxRateRaw = params.get("defaultTaxRate");
        if (taxRateRaw instanceof Number) {
            request.setDefaultTaxRate(new java.math.BigDecimal(taxRateRaw.toString()));
        } else if (taxRateRaw instanceof String && !((String) taxRateRaw).isBlank()) {
            request.setDefaultTaxRate(new java.math.BigDecimal((String) taxRateRaw));
        }
        String defaultInvoiceType = getString(params, "defaultInvoiceType");
        if (defaultInvoiceType != null && !defaultInvoiceType.isBlank()) {
            request.setDefaultInvoiceType(com.cretas.aims.entity.enums.InvoiceType.valueOf(defaultInvoiceType));
        }

        CustomerDTO customer = customerService.updateCustomer(factoryId, customerId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("customer", customer);
        result.put("message", "客户信息更新成功: " + customer.getName());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
