package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.scale.ScaleBrandModel;
import com.cretas.aims.repository.ScaleBrandModelRepository;
import com.cretas.aims.util.ScaleBrandMatcher;
import com.cretas.aims.util.ScaleBrandMatcher.BrandMatchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 秤型号添加工具
 *
 * 添加新的秤品牌型号到系统中，支持自动品牌识别。
 *
 * Intent Code: SCALE_ADD_MODEL
 *
 * factoryId 隔离豁免说明: scale_brand_models 是全局共享主数据 (品牌/型号库,
 * 不同工厂共用一个品牌), 不按工厂分区. scaleBrandModelRepository.findByBrandCodeAndModelCode
 * 是全局查重 (避免重复添加), 不涉及跨工厂业务数据读取.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleAddModelTool extends AbstractBusinessTool {

    @Autowired
    private ScaleBrandModelRepository scaleBrandModelRepository;

    @Autowired
    private ScaleBrandMatcher brandMatcher;

    @Override
    public String getToolName() {
        return "scale_add_model";
    }

    @Override
    public String getDescription() {
        return "添加新的秤品牌型号。注册新的电子秤品牌和型号信息到系统中，支持自动品牌识别。" +
                "适用场景：注册新秤型号、添加品牌型号配置。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> brandCode = new HashMap<>();
        brandCode.put("type", "string");
        brandCode.put("description", "品牌代码，例如 KELI (柯力), YAOHUA (耀华)");
        properties.put("brandCode", brandCode);

        Map<String, Object> brandName = new HashMap<>();
        brandName.put("type", "string");
        brandName.put("description", "品牌名称，例如 柯力, 耀华");
        properties.put("brandName", brandName);

        Map<String, Object> modelCode = new HashMap<>();
        modelCode.put("type", "string");
        modelCode.put("description", "型号代码，例如 D2008, XK3190-A9");
        properties.put("modelCode", modelCode);

        Map<String, Object> modelName = new HashMap<>();
        modelName.put("type", "string");
        modelName.put("description", "型号名称");
        properties.put("modelName", modelName);

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户自然语言输入，用于自动识别品牌和型号");
        properties.put("userInput", userInput);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // brandCode and modelCode are logically required, but can be extracted from userInput
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行添加秤型号 - 工厂ID: {}, 参数: {}", factoryId, params);

        String brandCode = getString(params, "brandCode");
        String brandName = getString(params, "brandName");
        String modelCode = getString(params, "modelCode");
        String modelName = getString(params, "modelName");
        String userInput = getString(params, "userInput");

        // Try to extract from userInput if not provided
        if (brandCode == null && userInput != null) {
            BrandMatchResult brandMatch = brandMatcher.matchBrandFromInput(userInput);
            if (brandMatch.isMatched()) {
                brandCode = brandMatch.getBrandCode();
                brandName = brandMatch.getBrandName();
            }
        }
        if (modelCode == null && userInput != null) {
            modelCode = brandMatcher.parseModelCode(userInput);
        }

        if (brandCode == null || modelCode == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "无法识别品牌或型号信息。请提供 brandCode 和 modelCode，或提供详细的自然语言描述。");
            result.put("example", "添加柯力D2008电子秤");
            return result;
        }

        // Check if already exists
        Optional<ScaleBrandModel> existing = scaleBrandModelRepository.findByBrandCodeAndModelCode(brandCode, modelCode);
        if (existing.isPresent()) {
            Map<String, Object> result = new HashMap<>();
            result.put("alreadyExists", true);
            result.put("existingModel", Map.of(
                    "id", existing.get().getId(),
                    "brandCode", existing.get().getBrandCode(),
                    "modelCode", existing.get().getModelCode()
            ));
            result.put("message", "该型号已存在: " + brandCode + " " + modelCode);
            return result;
        }

        // Create new brand model
        ScaleBrandModel.ScaleType scaleType = userInput != null ? brandMatcher.parseScaleType(userInput) : ScaleBrandModel.ScaleType.PLATFORM;
        ScaleBrandModel newModel = ScaleBrandModel.builder()
                .id(UUID.randomUUID().toString())
                .brandCode(brandCode)
                .brandName(brandName != null ? brandName : brandCode)
                .modelCode(modelCode)
                .modelName(modelName != null ? modelName : (brandName != null ? brandName : brandCode) + " " + modelCode)
                .scaleType(scaleType)
                .hasSerialPort(true)
                .isVerified(false)
                .isRecommended(false)
                .sortOrder(100)
                .build();

        scaleBrandModelRepository.save(newModel);
        log.info("添加秤型号成功: brandCode={}, modelCode={}", brandCode, modelCode);

        return buildSimpleResult(
                "成功添加秤型号: " + newModel.getModelName(),
                Map.of(
                        "modelId", newModel.getId(),
                        "brandCode", brandCode,
                        "modelCode", modelCode,
                        "modelName", newModel.getModelName()
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "brandCode", "请提供品牌代码，例如 KELI (柯力), YAOHUA (耀华)",
                "modelCode", "请提供型号代码，例如 D2008, XK3190-A9",
                "userInput", "请描述要添加的秤型号，例如「添加柯力D2008电子秤」"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "brandCode", "品牌代码",
                "brandName", "品牌名称",
                "modelCode", "型号代码",
                "modelName", "型号名称",
                "userInput", "用户输入"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
