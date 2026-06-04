package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.DishCostCardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 菜品成本分析工具 (#57 升级: STUB → 真成本).
 *
 * <p>原 STUB 只返菜品/食材计数 + "精确成本需配置 BOM" 占位。#57 起改为对工厂 active 菜品
 * 逐道经 {@link DishCostCardService} 算真成本卡, 返回 top-N (按毛利率升序, 低毛利优先暴露)
 * 的成本/毛利概览。未配置配方的菜品被跳过并计数提示。
 *
 * <p><b>RBAC (fail-closed)</b>: 同 {@link RestaurantDishCostQueryTool} —— AI 工具的自由文本
 * message advice 剥不到, 无价权角色不在文案写 ¥ 成本, 只给毛利率(占比, 不泄露绝对金额)+ 计数。
 *
 * 对应意图: RESTAURANT_DISH_COST_ANALYSIS
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-03-07 (v2 #57 真成本 2026-06-04)
 */
@Slf4j
@Component
public class RestaurantDishCostAnalysisTool extends AbstractBusinessTool {

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private DishCostCardService dishCostCardService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private UserRepository userRepository;

    private static final int TOP_N = 10;

    /** 与 cost-card REST + 损耗工具一致的价权许可集。 */
    private static final String[] COST_VIEW_PERMISSIONS = {
            "procurement:price:view", "finance:read", "finance:read_write"
    };

    @Override
    public String getToolName() {
        return "restaurant_dish_cost_analysis";
    }

    @Override
    public String getDescription() {
        return "菜品成本分析: 对全部已配置配方的菜品算真食材成本与毛利率, 列出低毛利/高成本菜品 (出菜反推)。" +
                "适用场景: 成本核算、毛利分析、识别高/低毛利菜品、食材成本占比。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        log.info("执行菜品成本分析 (v2 真成本) - 工厂ID: {}", factoryId);

        List<ProductType> dishes = productTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        if (dishes.isEmpty()) {
            return buildSimpleResult("暂无菜品数据，无法进行成本分析。请先在「菜品管理」中录入菜品。", null);
        }

        boolean canViewCost = canViewCost(context);

        List<DishCostCardResponse> withRecipe = new ArrayList<>();
        int noRecipeCount = 0;
        for (ProductType dish : dishes) {
            try {
                withRecipe.add(dishCostCardService.getCostCard(factoryId, dish.getId(), 1));
            } catch (ResourceNotFoundException e) {
                // 菜品无配方 — 跳过计数, 不报错
                noRecipeCount++;
            } catch (Exception e) {
                log.warn("菜品成本卡计算失败 dishId={}: {}", dish.getId(), e.getMessage());
                noRecipeCount++;
            }
        }

        if (withRecipe.isEmpty()) {
            return buildSimpleResult(
                    "共 " + dishes.size() + " 道菜品, 但均未配置配方 (BOM), 无法计算成本。" +
                    "请在「餐饮运营→配方管理」中为菜品录入食材用量与净料率后再试。", null);
        }

        // 按毛利率升序 (低毛利优先暴露, null 毛利排最后)
        withRecipe.sort(Comparator.comparing(
                DishCostCardResponse::getGrossMargin,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<DishCostCardResponse> top = withRecipe.subList(0, Math.min(TOP_N, withRecipe.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("已分析 ").append(withRecipe.size()).append(" 道已配方菜品");
        if (noRecipeCount > 0) {
            sb.append(" (另有 ").append(noRecipeCount).append(" 道未配置配方, 已跳过)");
        }
        sb.append("。低毛利菜品 (按毛利率升序):");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DishCostCardResponse c : top) {
            sb.append("\n· ").append(c.getProductName());
            if (canViewCost && c.getTotalIngredientCost() != null) {
                sb.append(" 食材成本 ¥").append(c.getTotalIngredientCost());
            }
            if (c.getGrossMargin() != null) {
                sb.append(" 毛利率 ").append(formatPercent(c.getGrossMargin()));
            } else {
                sb.append(" 毛利率暂不可计");
            }
            if (Boolean.TRUE.equals(c.getHasMissingPrices())) {
                sb.append(" (部分食材缺单价)");
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productTypeId", c.getProductTypeId());
            row.put("productName", c.getProductName());
            row.put("grossMargin", c.getGrossMargin());
            row.put("hasMissingPrices", c.getHasMissingPrices());
            if (canViewCost) {
                row.put("totalIngredientCost", c.getTotalIngredientCost());
                row.put("sellPrice", c.getSellPrice());
            }
            rows.add(row);
        }

        if (!canViewCost) {
            sb.append("\n当前角色无权限查看金额, 仅显示毛利率, 如需金额请联系管理员开通采购价格查看或财务查看权限。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analyzedCount", withRecipe.size());
        data.put("noRecipeCount", noRecipeCount);
        data.put("costMasked", !canViewCost);
        data.put("dishes", rows);

        log.info("菜品成本分析完成 - 已配方: {}, 无配方: {}", withRecipe.size(), noRecipeCount);
        return buildSimpleResult(sb.toString(), data);
    }

    private String formatPercent(BigDecimal fraction) {
        BigDecimal pct = fraction.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }

    /** fail-CLOSED 价权判定 (镜像 {@link RestaurantWastageSummaryTool#canViewCost})。 */
    private boolean canViewCost(Map<String, Object> context) {
        try {
            Long userId = getUserId(context);
            if (userId == null) {
                return false;
            }
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return false;
            }
            for (String perm : COST_VIEW_PERMISSIONS) {
                if (permissionService.hasPermission(user, perm)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("菜品成本分析价格权限解析失败, fail-closed 隐藏金额: {}", e.getMessage());
            return false;
        }
    }
}
