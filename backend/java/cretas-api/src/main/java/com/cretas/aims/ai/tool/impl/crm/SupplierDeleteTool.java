package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SupplierDeleteTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_delete";
    }

    @Override
    public String getDescription() {
        return "删除供应商。需要提供供应商ID。" +
                "适用场景：删除供应商、移除供应商、停用供应商。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商ID");
        properties.put("supplierId", supplierId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("supplierId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("supplierId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行供应商删除 - 工厂ID: {}, 参数: {}", factoryId, params);

        String supplierId = getString(params, "supplierId");
        supplierService.deleteSupplier(factoryId, supplierId);

        Map<String, Object> result = new HashMap<>();
        result.put("supplierId", supplierId);
        result.put("message", "供应商(ID: " + supplierId + ")已删除");
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("supplierId".equals(paramName)) {
            return "请问您要删除哪个供应商？请提供供应商ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("supplierId".equals(paramName)) {
            return "供应商ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
