package com.cretas.aims.service.finding;

import java.util.Map;

/**
 * 一条「发现」——由规则（{@link FindingProvider}）产出的结构化异常事实。
 *
 * <p>⛔ 刻意不含任何展示文案。同一条 Finding 会被多个出口渲染（同步顺带提示 /
 * 定时日报 / 告警），各出口措辞需求不同；文案一旦塞进这里，出口分叉时就会开始
 * 往 Finding 里加 if。渲染一律由出口侧的 renderer 负责。
 *
 * @param code          稳定机器码，如 {@code LOW_STOCK}。渲染层按它分派模板。
 * @param domain        领域，取值对齐 {@code ListSummaryService.SUPPORTED}
 *                      （inventory / salesOrder / qualityInspection / ...），
 *                      以便将来挂到列表页 footer 时天然对齐。
 * @param severity      严重度，由 provider 自评。
 * @param actionability 可行动性 0–99：「现在动手还来不来得及」。与 severity 正交
 *                      ——已过期是高 severity 低 actionability，临期 3 天是中
 *                      severity 高 actionability。v1 只有一个 provider，排序由
 *                      severity 主导；本字段先建好，等第 2 个 provider 进来再调权重。
 * @param subjectId     指向的具体对象 ID（如 materialTypeId）。
 * @param subjectName   对象名称（如 鲈鱼）。
 * @param facts         结构化事实，渲染层取值用。
 */
public record Finding(
        String code,
        String domain,
        Severity severity,
        int actionability,
        String subjectId,
        String subjectName,
        Map<String, Object> facts
) {

    public enum Severity {
        CRITICAL(3),
        WARNING(2),
        INFO(1);

        private final int weight;

        Severity(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    /** 排序分。severity 主导（×100），actionability 作为同级内的次序。 */
    public int rankScore() {
        return severity.weight() * 100 + actionability;
    }
}
