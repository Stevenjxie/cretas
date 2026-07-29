package com.cretas.aims.ai.tool.impl.config;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ConversionDTO;
import com.cretas.aims.service.ConversionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 转化率配置更新工具
 *
 * 更新原材料到成品的转化率配置。
 * 用于设置特定原材料类型与产品类型之间的转化比例。
 *
 * Intent Code: CONVERSION_RATE_UPDATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ConversionRateUpdateTool extends AbstractBusinessTool {

    @Autowired
    private ConversionService conversionService;

    @Override
    public String getToolName() {
        return "conversion_rate_update";
    }

    @Override
    public String getDescription() {
        return "更新原材料到成品的转化率配置。设置特定原材料类型与产品类型之间的转化比例。" +
                "适用场景：调整生产转化率、配置新产品的原料消耗比例。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // rawMaterialTypeId: 原材料类型ID（必需）
        Map<String, Object> rawMaterialTypeId = new HashMap<>();
        rawMaterialTypeId.put("type", "string");
        rawMaterialTypeId.put("description", "原材料类型ID");
        properties.put("rawMaterialTypeId", rawMaterialTypeId);

        // productTypeId: 产品类型ID（必需）
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID");
        properties.put("productTypeId", productTypeId);

        // conversionRate: 转化率（必需）
        Map<String, Object> conversionRate = new HashMap<>();
        conversionRate.put("type", "number");
        conversionRate.put("description", "转化率，表示生产1单位产品需要多少单位原材料");
        conversionRate.put("minimum", 0.001);
        conversionRate.put("maximum", 1000);
        properties.put("conversionRate", conversionRate);

        // effectiveDate: 生效日期（可选）
        Map<String, Object> effectiveDate = new HashMap<>();
        effectiveDate.put("type", "string");
        effectiveDate.put("description", "生效日期，格式 YYYY-MM-DD，默认立即生效");
        properties.put("effectiveDate", effectiveDate);

        // remark: 备注（可选）
        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "配置变更备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("rawMaterialTypeId", "productTypeId", "conversionRate"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("rawMaterialTypeId", "productTypeId", "conversionRate");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String rawMaterialTypeId = getString(params, "rawMaterialTypeId");
        String productTypeId = getString(params, "productTypeId");
        Object rateObj = params.get("conversionRate");
        BigDecimal conversionRate = rateObj instanceof Number
                ? BigDecimal.valueOf(((Number) rateObj).doubleValue())
                : new BigDecimal(rateObj.toString());
        String remark = getString(params, "remark", null);

        try {
            log.info("更新转化率 - 工厂ID: {}, 原料: {}, 产品: {}, 转化率: {}",
                    factoryId, rawMaterialTypeId, productTypeId, conversionRate);

            // Check if an existing conversion already exists for this material+product pair
            ConversionDTO existing = null;
            try {
                existing = conversionService.getConversionRate(factoryId, rawMaterialTypeId, productTypeId);
            } catch (Exception ignored) {
                // Not found — will create
            }

            ConversionDTO dto = ConversionDTO.builder()
                    .materialTypeId(rawMaterialTypeId)
                    .productTypeId(productTypeId)
                    .conversionRate(conversionRate)
                    .isActive(true)
                    .notes(remark)
                    .build();

            ConversionDTO result;
            if (existing != null && existing.getId() != null) {
                result = conversionService.updateConversion(factoryId, existing.getId(), dto);
                return buildSimpleResult("转化率已更新", result);
            } else {
                result = conversionService.createConversion(factoryId, dto);
                return buildSimpleResult("转化率已创建", result);
            }
        } catch (Exception e) {
            log.error("更新转化率失败 - 工厂ID: {}", factoryId, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "rawMaterialTypeId", "请问要配置哪种原材料的转化率？请提供原材料类型ID。",
            "productTypeId", "请问是生产哪种产品？请提供产品类型ID。",
            "conversionRate", "请问转化率是多少？（即生产1单位产品需要多少单位原材料）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "rawMaterialTypeId", "原材料类型ID",
            "productTypeId", "产品类型ID",
            "conversionRate", "转化率",
            "effectiveDate", "生效日期",
            "remark", "备注"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
