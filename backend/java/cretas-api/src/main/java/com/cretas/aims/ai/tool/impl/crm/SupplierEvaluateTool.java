package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 供应商评价工具
 *
 * 对供应商进行评级和评价。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SupplierEvaluateTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_evaluate";
    }

    @Override
    public String getDescription() {
        return "评价供应商。对指定供应商进行评级，可添加评价备注。" +
                "适用场景：更新供应商评分、进行供应商考核、记录供应商表现评价。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // supplierId: 供应商ID（必需）
        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商ID，用于指定要评价的供应商");
        properties.put("supplierId", supplierId);

        // rating: 评级（必需）
        Map<String, Object> rating = new HashMap<>();
        rating.put("type", "integer");
        rating.put("description", "供应商评级，1-5分，5分为最高");
        rating.put("minimum", 1);
        rating.put("maximum", 5);
        properties.put("rating", rating);

        // notes: 评价备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "评价备注说明");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("supplierId", "rating"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("supplierId", "rating");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "supplierId":
                return "请问您要评价哪个供应商？请提供供应商ID或供应商名称。";
            case "rating":
                return "请问您给这个供应商评几分？（1-5分，5分最高）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "supplierId":
                return "供应商ID";
            case "rating":
                return "评级分数";
            case "notes":
                return "评价备注";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行供应商评价 - 工厂ID: {}, 参数: {}", factoryId, params);

        String supplierId = getString(params, "supplierId");
        Integer rating = getInteger(params, "rating");
        String notes = getString(params, "notes", "");

        // 验证评级范围
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("评级必须在1-5分之间");
        }

        SupplierDTO updatedSupplier = supplierService.updateSupplierRating(factoryId, supplierId, rating, notes);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", String.format("供应商评价成功，评级: %d分", rating));
        result.put("supplier", updatedSupplier);
        result.put("supplierId", supplierId);
        result.put("rating", rating);
        result.put("notes", notes);

        log.info("供应商评价完成 - 供应商ID: {}, 评级: {}", supplierId, rating);

        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
