package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CreateCustomerRequest;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class CustomerCreateTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_create";
    }

    @Override
    public String getDescription() {
        return "创建新客户。需要客户名称，可选联系人、电话、地址、类型等信息。" +
                "适用场景：新增客户、录入客户、添加客户。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

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
        type.put("description", "客户类型（如：经销商、直营店、电商平台）");
        properties.put("type", type);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("name"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("name");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest();
        request.setName(getString(params, "name"));
        request.setContactPerson(getString(params, "contactPerson"));
        request.setPhone(getString(params, "phone"));
        request.setShippingAddress(getString(params, "shippingAddress"));
        request.setType(getString(params, "type"));

        Long userId = getLong(context, "userId");
        CustomerDTO customer = customerService.createCustomer(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("customer", customer);
        result.put("message", "客户创建成功: " + customer.getName());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
