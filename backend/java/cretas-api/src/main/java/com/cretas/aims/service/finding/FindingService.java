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
     */
    record Result(
            List<Finding> findings,
            List<String> checkedRules,
            int totalCount,
            Map<String, Integer> countsByCode,
            List<String> failedRules
    ) {
        /** true = 所有匹配 domain 的规则都跑完了；false = 至少一条规则失败，结果不完整。 */
        public boolean complete() {
            return failedRules.isEmpty();
        }
    }
}
