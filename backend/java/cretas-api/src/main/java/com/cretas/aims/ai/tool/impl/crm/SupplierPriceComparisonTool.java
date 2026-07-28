package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SupplierPriceComparisonTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_price_comparison";
    }

    @Override
    public String getDescription() {
        return "供应商价格对比。获取所有活跃供应商并按价格维度进行比较分析。" +
                "适用场景：供应商比价、价格对比、采购价格分析、供应商竞价。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> materialType = new HashMap<>();
        materialType.put("type", "string");
        materialType.put("description", "原料类型，用于筛选特定类别的供应商进行比价");
        properties.put("materialType", materialType);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行供应商价格对比 - 工厂ID: {}, 参数: {}", factoryId, params);

        List<SupplierDTO> suppliers = supplierService.getActiveSuppliers(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("suppliers", suppliers);
        result.put("comparisonType", "price");
        result.put("count", suppliers.size());
        result.put("message", "供应商价格对比获取成功，共" + suppliers.size() + "家供应商");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
