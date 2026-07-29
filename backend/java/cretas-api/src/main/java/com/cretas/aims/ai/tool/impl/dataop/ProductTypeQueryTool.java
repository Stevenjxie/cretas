package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.service.ProductTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 产品类型查询工具
 *
 * 提供产品类型的分页查询功能，支持按名称、类别、状态等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ProductTypeQueryTool extends AbstractBusinessTool {

    @Autowired
    private ProductTypeService productTypeService;

    @Override
    public String getToolName() {
        return "product_type_query";
    }

    @Override
    public String getDescription() {
        return "查询产品类型列表。支持按产品名称、类别、状态进行筛选，支持分页。" +
                "适用场景：查看产品目录、查找特定产品类型、查询某类别下的所有产品。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // productName: 产品名称（可选）
        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称，支持模糊查询");
        properties.put("productName", productName);

        // productCode: 产品编码（可选）
        Map<String, Object> productCode = new HashMap<>();
        productCode.put("type", "string");
        productCode.put("description", "产品编码，精确匹配");
        properties.put("productCode", productCode);

        // category: 产品类别（可选）
        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "产品类别，如：肉类、蔬菜、水产等");
        properties.put("category", category);

        // status: 状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "产品状态筛选");
        status.put("enum", Arrays.asList(
                "ACTIVE",       // 在产
                "INACTIVE",     // 停产
                "DEVELOPING",   // 开发中
                "DISCONTINUED"  // 已下架
        ));
        properties.put("status", status);

        // shelfLife: 保质期范围（可选）
        Map<String, Object> shelfLifeMin = new HashMap<>();
        shelfLifeMin.put("type", "integer");
        shelfLifeMin.put("description", "最小保质期（天）");
        shelfLifeMin.put("minimum", 0);
        properties.put("shelfLifeMin", shelfLifeMin);

        Map<String, Object> shelfLifeMax = new HashMap<>();
        shelfLifeMax.put("type", "integer");
        shelfLifeMax.put("description", "最大保质期（天）");
        properties.put("shelfLifeMax", shelfLifeMax);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);

        // 查询类Tool无必需参数
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 查询类Tool无必需参数
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("productName", "请问您要查询什么产品？请提供产品名称。");
        questions.put("productCode", "请问产品编码是什么？");
        questions.put("category", "请问要查询哪个类别的产品？（如：肉类、蔬菜、水产等）");
        questions.put("status", "请问要查询什么状态的产品？（在产/停产/开发中/已下架）");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("productName", "产品名称");
        displayNames.put("productCode", "产品编码");
        displayNames.put("category", "产品类别");
        displayNames.put("status", "产品状态");
        displayNames.put("shelfLifeMin", "最小保质期");
        displayNames.put("shelfLifeMax", "最大保质期");
        displayNames.put("page", "页码");
        displayNames.put("size", "每页数量");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String keyword = getString(params, "productName");
        if (keyword == null) keyword = getString(params, "productCode");

        int page = 1;
        int size = 20;
        Object pageObj = params.get("page");
        if (pageObj instanceof Number) page = ((Number) pageObj).intValue();
        Object sizeObj = params.get("size");
        if (sizeObj instanceof Number) size = ((Number) sizeObj).intValue();

        try {
            if (keyword != null && !keyword.isBlank()) {
                PageRequest pageRequest = PageRequest.of(page, size);
                PageResponse<ProductTypeDTO> result = productTypeService.searchProductTypes(factoryId, keyword, pageRequest);
                return buildSimpleResult("产品类型查询成功", result);
            } else {
                // No keyword — return all active types or paginated list
                List<ProductTypeDTO> activeTypes = productTypeService.getActiveProductTypes(factoryId);
                return buildSimpleResult("产品类型查询成功", activeTypes);
            }
        } catch (Exception e) {
            log.error("产品类型查询失败: factoryId={}, keyword={}", factoryId, keyword, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
