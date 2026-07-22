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
 * 菜品成本卡查询工具 (#57 成本卡/出菜反推).
 *
 * <p>按菜名模糊匹配 → {@link DishCostCardService} 真成本卡 → 可读成本/毛利消息。
 * 对应意图: {@code RESTAURANT_DISH_COST_QUERY}.
 *
 * <p><b>RBAC (fail-closed)</b>: REST 路径靠 {@code PriceFieldResponseAdvice} 自动剥离
 * {@code @PriceSensitive} 字段, 但 AI 工具返回的是自由文本 {@code message} —— 那条 ¥ 文案
 * advice 识别不了。故本工具必须在文本层显式按价权门控: 无 {@code procurement:price:view} /
 * {@code finance:read} / {@code finance:read_write} 权限 (或 userId 缺失 / 用户不存在 /
 * 权限服务异常) 一律不在 message 里写 ¥ 成本, 只给毛利率(口径占比不泄露绝对金额)与计数 +
 * 提示。镜像 Wave2 #54 {@code RestaurantWastageSummaryTool} 的修复。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
@Slf4j
@Component
public class RestaurantDishCostQueryTool extends AbstractBusinessTool {

    @Autowired
    private TieredIntentDelegate tieredDelegate;

    @Autowired
    private DishCostCardService dishCostCardService;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private UserRepository userRepository;

    /** 与 cost-card REST + 损耗工具一致的价权许可集。 */
    private static final String[] COST_VIEW_PERMISSIONS = {
            "procurement:price:view", "finance:read", "finance:read_write"
    };

    @Override
    public String getToolName() {
        return "restaurant_dish_cost_query";
    }

    @Override
    public String getDescription() {
        return "菜品成本卡查询: 按菜名查某道菜的逐料食材成本拆解、食材总成本、净料率与毛利率 (出菜反推)。" +
                "适用场景: 这道菜成本多少 / 毛利率多少 / 食材成本占比 / 哪些料贵。" +
                "需要该菜品已配置配方 (BOM); 未配置则提示去配方管理录入。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("productName", Map.of("type", "string", "description", "菜品名称 (支持模糊匹配, 如 \"白卤猪舌\" 或 \"猪舌\")"));
        props.put("portions", Map.of("type", "integer", "description", "份数 (默认 1)"));
        schema.put("properties", props);
        schema.put("required", List.of("productName"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productName");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Map<String, Object> delegated = tieredDelegate.tryDelegate(factoryId, params, context, getToolName());
        if (delegated != null) {
            return delegated;
        }
        String productName = getString(params, "productName");
        int portions = Math.max(1, getInteger(params, "portions", 1));

        ProductType dish = resolveDish(factoryId, productName);
        if (dish == null) {
            return buildSimpleResult(
                    "未找到名为「" + productName + "」的菜品。请确认菜名, 或在「菜品管理」中先录入该菜品。", null);
        }

        DishCostCardResponse card;
        try {
            card = dishCostCardService.getCostCard(factoryId, dish.getId(), portions);
        } catch (ResourceNotFoundException e) {
            // 菜品存在但没配方 — 防呆 next action
            return buildSimpleResult(
                    "「" + dish.getName() + "」还没有配置配方 (BOM), 无法计算成本卡。" +
                    "请在「餐饮运营→配方管理」中为该菜品录入食材用量与净料率后再试。", null);
        }

        boolean canViewCost = canViewCost(context);
        String message = buildMessage(card, canViewCost);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productTypeId", card.getProductTypeId());
        data.put("productName", card.getProductName());
        data.put("portions", card.getPortions());
        data.put("recipeLineCount", card.getRecipeLineCount());
        data.put("hasMissingPrices", card.getHasMissingPrices());
        data.put("grossMargin", card.getGrossMargin());
        if (canViewCost) {
            data.put("totalIngredientCost", card.getTotalIngredientCost());
            data.put("sellPrice", card.getSellPrice());
            data.put("ingredients", card.getIngredients());
        } else {
            data.put("costMasked", true);
        }

        return buildSimpleResult(message, data);
    }

    /** 精确名匹配优先, 否则在 active 菜品里 contains 模糊匹配 (取第一个)。 */
    private ProductType resolveDish(String factoryId, String productName) {
        String name = productName == null ? "" : productName.trim();
        if (name.isEmpty()) {
            return null;
        }
        Optional<ProductType> exact = productTypeRepository.findByFactoryIdAndName(factoryId, name);
        if (exact.isPresent()) {
            return exact.get();
        }
        List<ProductType> active = productTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        // 先找菜名包含输入, 再找输入包含菜名 (双向 contains, 处理 "猪舌" ↔ "白卤猪舌")
        for (ProductType p : active) {
            if (p.getName() != null && p.getName().contains(name)) {
                return p;
            }
        }
        for (ProductType p : active) {
            if (p.getName() != null && name.contains(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private String buildMessage(DishCostCardResponse card, boolean canViewCost) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.getProductName());
        if (card.getPortions() != null && card.getPortions() > 1) {
            sb.append(" ").append(card.getPortions()).append("份");
        } else {
            sb.append(" 1份");
        }

        if (canViewCost) {
            if (card.getTotalIngredientCost() != null) {
                sb.append(" 食材成本 ¥").append(card.getTotalIngredientCost());
            } else {
                sb.append(" 食材成本暂不可计 (部分食材未配单价)");
            }
            if (card.getSellPrice() != null) {
                sb.append(" 售价 ¥").append(card.getSellPrice());
            }
        }

        if (card.getGrossMargin() != null) {
            sb.append(" 毛利率 ").append(formatPercent(card.getGrossMargin()));
        } else if (canViewCost) {
            sb.append(" 毛利率暂不可计 (缺成本或售价)");
        } else {
            sb.append(" 毛利率暂不可计");
        }

        sb.append("。配方共 ").append(card.getRecipeLineCount() == null ? 0 : card.getRecipeLineCount()).append(" 种食材");

        if (Boolean.TRUE.equals(card.getHasMissingPrices())) {
            sb.append("; 注意: 部分食材未配单价, 总成本不完整, 请到「原料管理」补充单价");
        }
        if (!canViewCost) {
            sb.append("; 当前角色无权限查看金额, 仅显示毛利率与配方信息, 如需金额请联系管理员开通采购价格查看或财务查看权限");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 毛利率 fraction(0–1) → 百分比文本, scale 1 HALF_UP。 */
    private String formatPercent(BigDecimal fraction) {
        BigDecimal pct = fraction.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }

    /**
     * 当前用户是否有权查看金额。fail-CLOSED: userId 缺失 / 用户不存在 / 权限服务异常一律视为无权限。
     * 镜像 {@link RestaurantWastageSummaryTool#canViewCost}。
     */
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
            log.warn("菜品成本卡价格权限解析失败, fail-closed 隐藏金额: {}", e.getMessage());
            return false;
        }
    }
}
