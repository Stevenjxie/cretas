package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SupplierCreateTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_create";
    }

    @Override
    public String getDescription() {
        return "创建新供应商。需要供应商名称，可选联系人、电话、邮箱、地址、供应材料类型等信息。" +
                "适用场景：新增供应商、录入供应商、添加供应商。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "供应商名称");
        properties.put("name", name);

        Map<String, Object> contactPerson = new HashMap<>();
        contactPerson.put("type", "string");
        contactPerson.put("description", "联系人姓名");
        properties.put("contactPerson", contactPerson);

        Map<String, Object> phone = new HashMap<>();
        phone.put("type", "string");
        phone.put("description", "联系电话");
        properties.put("phone", phone);

        Map<String, Object> email = new HashMap<>();
        email.put("type", "string");
        email.put("description", "邮箱地址");
        properties.put("email", email);

        Map<String, Object> address = new HashMap<>();
        address.put("type", "string");
        address.put("description", "供应商地址");
        properties.put("address", address);

        Map<String, Object> suppliedMaterials = new HashMap<>();
        suppliedMaterials.put("type", "string");
        suppliedMaterials.put("description", "供应材料类型（如：肉类、蔬菜、调味料）");
        properties.put("suppliedMaterials", suppliedMaterials);

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
        log.info("执行供应商创建 - 工厂ID: {}, 参数: {}", factoryId, params);

        CreateSupplierRequest request = new CreateSupplierRequest();
        request.setName(getString(params, "name"));
        request.setContactPerson(getString(params, "contactPerson"));
        request.setPhone(getString(params, "phone"));
        request.setEmail(getString(params, "email"));
        request.setAddress(getString(params, "address"));
        request.setSuppliedMaterials(getString(params, "suppliedMaterials"));

        Long userId = getLong(context, "userId");
        SupplierDTO supplier = supplierService.createSupplier(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("supplier", supplier);
        result.put("message", "供应商创建成功: " + supplier.getName());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("name".equals(paramName)) {
            return "请问新供应商叫什么名称？";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("name".equals(paramName)) {
            return "供应商名称";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
