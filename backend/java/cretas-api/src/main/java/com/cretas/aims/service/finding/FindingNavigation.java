package com.cretas.aims.service.finding;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发现 → 「下一步去哪」的**唯一映射**。
 *
 * <p>为什么需要它（第 ⑤ 块的真实形状）：仓里唯一的动作提案
 * {@code COMPLETE_DISH_COST_DATA_PROPOSAL} 的执行模式是
 * {@code READ_ONLY_PROPOSAL} —— 它**从不写任何数据**，批准之后交给用户的就是一个
 * {@code navigationTarget}（{@code /restaurant/recipes}），改价改品是人在那个页面做的。
 * 所以「⑤ 对话改价改品」这个说法是错的：AI 只负责提议「去复核菜品成本数据」。
 *
 * <p>由此，⑤ 的**非对话出口**不是再造一个写接口（本来就没有写接口可造），而是让
 * 发现层的每条发现自己带上同一个「下一步去哪」，直接渲染在落地页上 —— 不必先开口
 * 说话、不必先起一个 agent run（preview/confirm 都以 {@code runId} 为键，没有对话就
 * 根本调不到）。
 *
 * <p>⛔ 一个映射只能有一处定义。{@code RestaurantAgentActionProposalMapper} 的
 * {@code NAVIGATION_TARGET} 必须引用这里的常量，不许自己再写一份字面量 ——
 * 两处各写一个，某天改了页面路径就会出现「对话里跳对了、卡片上跳 404」。
 *
 * <p>⚠️ 未登记的 code 返回 {@code null}，**不猜**。猜错的代价是把用户送到一个跟他
 * 看到的那句话无关的页面，比没有按钮更糟。
 *
 * <p>⚠️ {@code module} 必须一并给出：前端要拿它做权限判断，否则会渲染出一个点进去
 * 就 403 的入口（同 {@code routedPagesAreReachable} 那道闸想防的事）。
 */
public final class FindingNavigation {

    private FindingNavigation() {
    }

    /** 复核菜品成本数据的落点。⚠️ agent 动作提案与发现层卡片**共用这一个**。 */
    public static final String DISH_COST_REVIEW_TARGET = "/restaurant/recipes";

    /**
     * 一个落点。
     *
     * @param target 前端路由完整路径（嵌套路由要写全，`/restaurant/wastage` 而不是 `wastage`）
     * @param module 权限模块名，前端据此判断该用户能不能打开
     * @param label  按钮上的字，说清「去那儿干什么」，不是页面名
     */
    public record Destination(String target, String module, String label) {
    }

    /**
     * 一条可执行的下一步（发现 + 落点的投影）。
     *
     * @param code        发现码，前端用它做 key，也便于日志对账
     * @param subjectName 具体对象（哪道菜 / 哪类损耗），让按钮自解释，不依赖与摘要行对齐
     */
    public record NextStep(String code, String subjectName, String label, String target, String module) {
    }

    private static final Map<String, Destination> DESTINATIONS = Map.of(
            "DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME",
            new Destination(DISH_COST_REVIEW_TARGET, "restaurantOps", "去核对配方与成本"),
            "WASTAGE_SHARE_SPIKE",
            new Destination("/restaurant/wastage", "restaurantOps", "去看损耗明细"),
            "WASTAGE_TYPE_CONCENTRATION",
            new Destination("/restaurant/wastage", "restaurantOps", "去看损耗明细"));

    /** 未登记返回 {@code null} —— 调用方据此**不渲染**按钮，而不是给个默认页。 */
    public static Destination destinationFor(String code) {
        return code == null ? null : DESTINATIONS.get(code);
    }

    /** 已登记的全部发现码（给断言闸用）。 */
    public static Set<String> knownCodes() {
        return new LinkedHashSet<>(DESTINATIONS.keySet());
    }

    /**
     * 把发现投影成「今天可以去做的事」。
     *
     * <p>⚠️ 按 {@code rankScore} 降序，与摘要行同序 —— 但**不与摘要行按下标对齐**：
     * 每条自带 {@code subjectName}，能独立读懂。摘要行的条数受渲染器裁剪影响，
     * 按下标配对迟早会把「罗氏虾」的按钮配到「牛腩」那句话上（本轮在行动建议里
     * 已经栽过一次同型的归因错）。
     *
     * <p>同一个落点只保留 {@code rankScore} 最高的那条：三条损耗发现给三个一模一样的
     * 「去看损耗明细」按钮，等于没有信息。
     */
    public static List<NextStep> nextSteps(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, NextStep> byTarget = new LinkedHashMap<>();
        findings.stream()
                .sorted((a, b) -> Integer.compare(b.rankScore(), a.rankScore()))
                .forEach(f -> {
                    Destination d = destinationFor(f.code());
                    if (d == null) {
                        return;
                    }
                    byTarget.putIfAbsent(d.target(), new NextStep(
                            f.code(),
                            f.subjectName(),
                            d.label(),
                            d.target(),
                            d.module()));
                });
        return List.copyOf(byTarget.values());
    }
}
