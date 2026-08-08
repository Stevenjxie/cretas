package com.cretas.aims.service.finding;

import java.util.List;
import java.util.Map;

/** 发现层统一入口。同步顺带提示 / 定时日报 / 告警三个出口共用此层。 */
public interface FindingService {

    /**
     * 为「顺带提示」出口检测异常。
     *
     * @param factoryId 工厂
     * @param domain    领域，如 {@code inventory}
     */
    Result detectInline(String factoryId, String domain);

    /**
     * 同时取多个 domain 的发现，排序与 inline 截断在**合并之后**做一次。
     *
     * <p>🔴 2026-08-08 起餐饮的「顺带提示」要用它：低库存发现由
     * {@code LowStockFindingProvider} 提供，而它声明的 domain 是
     * {@code "inventory"} —— 与餐饮那三条(谜题菜品/损耗集中/损耗占比突增)
     * 不同域。原来的单域调用写死 {@code "restaurant"}，**库存异常因此永远
     * 到不了店长眼前**：能力在、数据通道在，只差这一根线没连。
     *
     * <p>⛔ 不能用「调两次再拼」代替：inline 上限要在合并后的全集上截断，
     * 分别截断会让两个域各占名额，把真正最要紧的那条挤掉。
     */
    Result detectInline(String factoryId, java.util.Collection<String> domains);

    /**
     * @param findings     已排序并截断到 inline 上限的发现（可能为空）
     * @param checkedRules **实际成功跑完**的规则名。抛异常的规则不在此列——
     *                     否则 UI 会说出「已检查 X，均正常」这种假话。
     * @param totalCount   截断前的发现总数，用于「还有 N 项」
     * @param countsByCode 按 code 分组的**截断前**计数，供调用方复用
     *                     （如 lowStockCount = countsByCode.get("LOW_STOCK")）
     * @param failedRules  执行时抛异常、因而被从 {@code checkedRules} 剔除的规则名。
     *                     非空表示本次结果不完整——消费方不得把 countsByCode /
     *                     findings 当作「已确认无异常」来展示（禁止降级处理）。
     *                     用 {@link #complete()} 判断。
     * @param skippedRules 数据不足以判断而**诚实跳过**的规则（见
     *                     {@link FindingNotApplicableException}）。与
     *                     {@code failedRules} 严格区分：跳过不是故障，
     *                     **不影响** {@link #complete()}。
     */
    record Result(
            List<Finding> findings,
            List<String> checkedRules,
            int totalCount,
            Map<String, Integer> countsByCode,
            List<String> failedRules,
            List<SkippedRule> skippedRules
    ) {
        /**
         * 5 参数重载。既有 12 处构造点（inventory 域与其测试）全是 5 参数，
         * 保持它们逐字不变 —— 那些断言是对的，不该为了新字段去改。
         */
        public Result(List<Finding> findings, List<String> checkedRules, int totalCount,
                      Map<String, Integer> countsByCode, List<String> failedRules) {
            this(findings, checkedRules, totalCount, countsByCode, failedRules, List.of());
        }

        /** true = 所有匹配 domain 的规则都跑完了；false = 至少一条规则失败，结果不完整。 */
        public boolean complete() {
            return failedRules.isEmpty();
        }
    }

    /** 一条被诚实跳过的规则：名字 + 为什么。只有名字说不出为什么。 */
    record SkippedRule(String ruleName, String reason) {}
}
