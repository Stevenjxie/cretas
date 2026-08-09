package com.cretas.aims.service.finding;

import java.util.Comparator;

/**
 * 发现的排序口径 —— **由出口选择，不记在发现上**。
 *
 * <p>🔴 为什么要拆出来（2026-08-08）：{@code Finding} 刻意不带展示文案，理由是
 * 同一条发现会被多个出口渲染、措辞需求不同。但它们此前共用 {@code rankScore()}，
 * 等于在**排序**这一维上又耦合回去了 —— 而不同出口要的顺序恰恰相反：
 *
 * <ul>
 *   <li><b>同步顺带提示</b>是打断式的、只有 2 个名额，要的是「你现在能做点什么」；</li>
 *   <li><b>日报/复盘</b>要的是「这个月钱漏在哪」，已经无可挽回的事恰恰最该说。</li>
 * </ul>
 *
 * <p>⛔ 原来的 {@code severity.weight()*100 + actionability} 有个结构性后果：
 * 严重度取值 3/2/1、可行动性 0–99，所以**最高的 WARNING 是 299，最低的
 * CRITICAL 是 300** —— 任何一条 CRITICAL 永远压过所有 WARNING，不管可行动性
 * 差多少。而「已经发生完的事」（食材已过期、钱已经亏了）最容易被评成 CRITICAL：
 * 损失确凿。结果是**已经无可挽回的事稳定霸占同步提示那 2 个名额，把今天动手
 * 还来得及的事永远挤掉** —— 与顺带提示存在的目的正好相反。
 */
public enum FindingOrdering {

    /**
     * 打断式出口（同步顺带提示）：**严重度 × 可行动性**。
     *
     * <p>用乘法而不是加法，为的是让**不可行动的直接归零**：食材已经烂了，
     * 在老板问「今天生意怎么样」时打断他说这件事，他什么也做不了 ——
     * 那条该进日报，不该占用同步的名额。
     *
     * <p>⚠️ 也不能纯按可行动性排：一条「很容易做但无关紧要」的 INFO 会压过
     * 「既严重又还来得及」的 CRITICAL。乘法让两者都参与，谁也不绝对主导。
     */
    ACT_NOW(Comparator
            .comparingInt((Finding f) -> f.severity().weight() * f.actionability())
            .reversed()
            // 同分时按严重度兜底，保证顺序稳定可复现（否则同分的两条每次顺序可能不同）
            .thenComparing(Comparator.comparingInt((Finding f) -> f.severity().weight()).reversed())
            .thenComparing(Finding::subjectName, Comparator.nullsLast(Comparator.naturalOrder()))),

    /**
     * 复盘式出口（日报 / 明细列表）：沿用 {@link Finding#rankScore()}，
     * 严重度主导。**已经发生完的损失在这里就该排前面** —— 复盘要的正是
     * 「钱漏在哪」，而不是「现在能做什么」。
     *
     * <p>这是既有行为，所有未显式选择排序的调用点都走它，逐字不变。
     */
    IMPACT_FIRST(Comparator.comparingInt(Finding::rankScore).reversed()
            .thenComparing(Finding::subjectName, Comparator.nullsLast(Comparator.naturalOrder())));

    private final Comparator<Finding> comparator;

    FindingOrdering(Comparator<Finding> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Finding> comparator() {
        return comparator;
    }
}
