package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把 OA 实例的 {@code moduleCode} 解析成给人看的「业务类型」中文名。
 *
 * <p><b>为什么要有这个类</b>: 客户截图里待办显示成「未知状态（BUDGET）」。查下来不是漏了
 * BUDGET 一个码 —— 权威表 {@link DecisionTypeMetadataRegistry} 有 30+ 个 moduleCode 且
 * 各自带 chineseName, 而前端 {@code pending.vue} 的 {@code MODULE_LABELS} 手抄了其中 4 个。
 * 另外 20 多个码同样会显示成「未知状态（X）」, 只是还没人点到。
 * 修法是后端按权威表下发 {@code moduleLabel}, 前端不再维护第二份表。
 *
 * <p><b>解析不出来时返回 {@code null}</b> —— 交给前端兜底, 后端不编造。
 */
@Component
public class OaModuleLabelResolver {

    private static final String BUDGET_MODULE = "BUDGET";
    private static final String ACCOUNTING_PERIOD_ENTITY = "ACCOUNTING_PERIOD";
    private static final String ACCOUNTING_PERIOD_LABEL = "会计期间结账";

    @Nullable
    private final DecisionTypeMetadataRegistry registry;

    public OaModuleLabelResolver(@Nullable DecisionTypeMetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param moduleCode OA 实例的 moduleCode
     * @param context    OA 实例的 context_json (已反序列化的 Map), 可为 null
     * @return 业务类型中文名; 解析不出来返回 {@code null}
     */
    @Nullable
    public String resolve(@Nullable String moduleCode, @Nullable Map<String, Object> context) {
        if (moduleCode == null || moduleCode.isBlank() || registry == null) {
            return null;
        }
        // BUDGET 一码多用 —— 其 description 写着「年度/季度/月度预算 + 超预算授权 + 期间结账审批」。
        // 泛称「预算审批」对期间结账不够准, 按 context 的 entityType 细化。
        // 读具体的 entityType 键而不是对整段 JSON 做 contains: 后者会被 periodId 之类
        // 恰好含该串的字段误伤。
        if (BUDGET_MODULE.equals(moduleCode)
                && context != null
                && ACCOUNTING_PERIOD_ENTITY.equals(String.valueOf(context.get("entityType")))) {
            return ACCOUNTING_PERIOD_LABEL;
        }
        DecisionType decisionType = registry.lookupByModuleCode(moduleCode);
        if (decisionType == null) {
            return null;
        }
        DecisionTypeMetadata metadata = registry.get(decisionType);
        if (metadata == null) {
            return null;
        }
        String chineseName = metadata.getChineseName();
        return (chineseName == null || chineseName.isBlank()) ? null : chineseName;
    }
}
