package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class CustomerDeleteTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_delete";
    }

    @Override
    public String getDescription() {
        return "删除客户。需要提供客户ID。" +
                "适用场景：删除客户、移除客户、停用客户。";
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

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("customerId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("customerId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行客户删除 - 工厂ID: {}, 参数: {}", factoryId, params);

        String customerId = getString(params, "customerId");
        customerService.deleteCustomer(factoryId, customerId);

        Map<String, Object> result = new HashMap<>();
        result.put("customerId", customerId);
        result.put("message", "客户(ID: " + customerId + ")已删除");
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("customerId".equals(paramName)) {
            return "请问您要删除哪个客户？请提供客户ID。";
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

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
