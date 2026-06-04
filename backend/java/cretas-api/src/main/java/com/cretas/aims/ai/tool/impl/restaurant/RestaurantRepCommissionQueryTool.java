package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.RestaurantCommissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 营销员月度阶梯提成查询工具（#59 Phase 2）。
 *
 * <p>回答 "我的提成 / 本月提成多少 / 营销员提成" 类问题：按月度累计返回当月累计复购业绩、所处档位、
 * 累计提成金额与计业绩到访次数。对应意图 {@code RESTAURANT_REP_COMMISSION_QUERY}。
 *
 * <p><b>RBAC (fail-closed)</b>：AI 工具返回自由文本 {@code message}，{@code PriceFieldResponseAdvice}
 * 识别不了文案里的 ¥。故文本层显式按价权门控：有 {@code finance:read} / {@code finance:read_write} /
 * {@code procurement:price:view} 权限<b>或</b>查询本人提成（targetRepId == 当前 userId）→ 显金额；
 * 否则只显业绩档位 + 到访次数（不泄绝对金额）。userId 缺失 / 用户不存在 / 权限服务异常一律 fail-closed。
 * 镜像 Wave2 #54 {@code RestaurantDishCostQueryTool} 的修复。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
@Slf4j
@Component
public class RestaurantRepCommissionQueryTool extends AbstractBusinessTool {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 与提成/财务一致的金额查看许可集。 */
    private static final String[] AMOUNT_VIEW_PERMISSIONS = {
            "finance:read", "finance:read_write", "procurement:price:view"
    };

    @Autowired
    private RestaurantCommissionService restaurantCommissionService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getToolName() {
        return "restaurant_rep_commission_query";
    }

    @Override
    public String getDescription() {
        return "营销员月度阶梯提成查询: 按月度累计返回某营销员当月累计复购业绩、所处档位(阶梯)、" +
                "累计提成金额与计业绩到访次数。适用场景: 我的提成 / 本月提成多少 / 营销员提成。" +
                "默认查当前用户本人当月; 可指定 repId 与 month(YYYY-MM)。" +
                "提成金额仅财务/价格权限角色或本人可见。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("repId", Map.of("type", "integer", "description", "营销员用户 ID (留空 = 当前登录用户本人)"));
        props.put("month", Map.of("type", "string", "description", "查询月份 YYYY-MM (留空 = 本月)"));
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();  // 无必填: 默认查当前用户本月
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long currentUserId = getUserId(context);

        // 目标营销员: 显式 repId 优先, 否则当前用户本人
        Long paramRepId = getLong(params, "repId");
        Long targetRepId = paramRepId != null ? paramRepId : currentUserId;
        if (targetRepId == null) {
            return buildSimpleResult(
                    "无法识别要查询的营销员: 当前会话缺少用户身份, 请重新登录后再试, 或指定营销员 ID。", null);
        }

        // 月份: 显式 month 优先, 否则本月
        String month = normalizeMonth(getString(params, "month", null));

        Optional<RestaurantRepCommissionSummary> summaryOpt =
                restaurantCommissionService.getRepSummary(factoryId, targetRepId, month);

        // 金额可见: 本人 OR 财务/价权角色
        boolean isSelf = targetRepId.equals(currentUserId);
        boolean canViewAmount = isSelf || hasAmountPermission(currentUserId);

        if (summaryOpt.isEmpty()) {
            String who = isSelf ? "您" : ("营销员 #" + targetRepId);
            return buildSimpleResult(
                    who + " 在 " + month + " 暂无计业绩的复购到访记录, 累计业绩与提成均为 0。" +
                    "提示: 散客第二次复购起才计业绩, 且需先为客户绑定营销员。",
                    Map.of("repId", targetRepId, "month", month, "hasData", false));
        }

        RestaurantRepCommissionSummary s = summaryOpt.get();
        String message = buildMessage(s, isSelf, targetRepId, month, canViewAmount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repId", targetRepId);
        data.put("month", month);
        data.put("hasData", true);
        data.put("attributedVisitCount", s.getAttributedVisitCount());
        data.put("currentTier", s.getCurrentTier());
        // tier 是档位序号 (0-based), 展示为 第N档 (1-based)
        data.put("currentTierLabel", s.getCurrentTier() == null ? "未分档(单一比率)"
                : ("第" + (s.getCurrentTier() + 1) + "档"));
        if (canViewAmount) {
            data.put("cumulativeRevenue", s.getCumulativeRevenue());
        } else {
            data.put("amountMasked", true);
        }
        return buildSimpleResult(message, data);
    }

    private String buildMessage(RestaurantRepCommissionSummary s, boolean isSelf, Long repId,
                                String month, boolean canViewAmount) {
        StringBuilder sb = new StringBuilder();
        sb.append(isSelf ? "您" : ("营销员 #" + repId));
        sb.append(" ").append(month).append(" 月度提成: ");
        sb.append("计业绩复购到访 ").append(s.getAttributedVisitCount() == null ? 0 : s.getAttributedVisitCount()).append(" 次");

        String tierLabel = s.getCurrentTier() == null ? "单一比率(未配阶梯)"
                : ("第" + (s.getCurrentTier() + 1) + "档");
        sb.append("; 当前档位 ").append(tierLabel);

        if (canViewAmount) {
            if (s.getCumulativeRevenue() != null) {
                sb.append("; 当月累计业绩 ¥").append(s.getCumulativeRevenue().toPlainString());
            }
        } else {
            sb.append("; 当前角色无权限查看金额, 仅显示业绩档位与到访次数, 如需金额请联系管理员开通财务查看权限");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 'YYYY-MM' 归一化; 非法/空 → 本月。 */
    private String normalizeMonth(String month) {
        if (month != null && month.trim().matches("\\d{4}-\\d{2}")) {
            return month.trim();
        }
        return LocalDate.now().format(PERIOD_FMT);
    }

    /**
     * 当前用户是否有权查看金额（财务/价权角色）。fail-CLOSED: userId 缺失 / 用户不存在 /
     * 权限服务异常一律视为无权限。本人查本人金额由调用处单独放行（isSelf）。
     */
    private boolean hasAmountPermission(Long userId) {
        try {
            if (userId == null) {
                return false;
            }
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return false;
            }
            for (String perm : AMOUNT_VIEW_PERMISSIONS) {
                if (permissionService.hasPermission(user, perm)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("营销员提成金额权限解析失败, fail-closed 隐藏金额: {}", e.getMessage());
            return false;
        }
    }
}
