package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 下架菜品工具（写操作，软删除 isActive=false）
 *
 * 对应意图: RESTAURANT_DISH_DELETE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class RestaurantDishDeleteTool extends AbstractBusinessTool {

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Override
    public String getToolName() {
        return "restaurant_dish_delete";
    }

    @Override
    public String getDescription() {
        return "下架菜品（软删除），将菜品状态设为不可用。需提供菜品名称或ID。" +
                "适用场景：下架菜品、停售菜品。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> id = new HashMap<>();
        id.put("type", "string");
        id.put("description", "菜品ID（id和name至少提供一个）");
        properties.put("id", id);

        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "菜品名称（id和name至少提供一个）");
        properties.put("name", name);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList(); // id or name needed, validated in doExecute
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.UPDATE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.HIGH;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    /**
     * Legacy role-only callers cannot prove the permission-code contract, so they must not
     * advertise or execute this tool. The central RBAC path uses {@link #getRequiredPermissions()}.
     */
    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("restaurant:read_write");
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("restaurant", "menu", "product-master");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行下架菜品 - factoryId={}", factoryId);

        String dishId = getString(params, "id");
        String dishName = getString(params, "name");

        if (dishId == null && dishName == null) {
            throw new IllegalArgumentException("请提供需要下架的菜品名称或ID");
        }

        ProductType dish = null;
        if (dishId != null) {
            dish = productTypeRepository.findByIdAndFactoryId(dishId, factoryId).orElse(null);
        }
        if (dish == null && dishName != null) {
            dish = productTypeRepository.findByFactoryIdAndName(factoryId, dishName.trim()).orElse(null);
        }

        if (dish == null) {
            return buildSimpleResult(
                    String.format("未找到菜品「%s」，请确认菜品名称或ID。",
                            dishName != null ? dishName : dishId), null);
        }

        if (!Boolean.TRUE.equals(dish.getIsActive())) {
            return buildSimpleResult(
                    String.format("菜品「%s」已处于下架状态，无需重复操作。", dish.getName()), null);
        }

        dish.setIsActive(false);
        productTypeRepository.save(dish);
        log.info("下架菜品成功: factoryId={}", factoryId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", String.format("菜品「%s」已成功下架。如需重新上架，请在菜品管理中操作。", dish.getName()));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "name", "请问要下架哪道菜品？请提供菜品名称。",
            "id", "请问要下架的菜品ID是什么？"
        );
        return questions.get(paramName);
    }
}
