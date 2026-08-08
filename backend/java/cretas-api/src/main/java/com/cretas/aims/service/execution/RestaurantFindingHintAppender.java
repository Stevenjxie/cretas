package com.cretas.aims.service.execution;

import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 餐饮委派回答的「顺带提示」：店长问任何问题时，末尾带出最重要的 1–2 条发现。
 *
 * <p>挂载点是 {@code IntentExecutionOrchestrator#tryRestaurantTieredDelegate} ——
 * 那个私有方法有 7 个调用点，但**全部汇入它一处**组装
 * {@code IntentExecuteResponse}，所以在那里拼一次就覆盖了餐饮的全部提问入口。
 *
 * <p>⛔ 为什么不像工厂端那样挂在单个 Tool 上：2026-08-06 实测，餐饮租户的提问
 * 在到达 Java Tool 之前就被 tiered 路由委派给 Python gold 链了
 * （{@code RESTAURANT_WASTAGE_ANOMALY} → Skill {@code restaurant-wastage-anomaly}
 * 不存在 → no-tool 出口 → tiered 接管）。挂在 Tool 上等于挂在没人走的路上。
 *
 * <p>条数上限由 {@code cretas.finding.inline-max}（默认 2）控制，本类不再截断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantFindingHintAppender {

    /**
     * 顺带提示要覆盖的发现域。
     *
     * <p>🔴 2026-08-08 补上 {@code "inventory"}：低库存发现由
     * {@code LowStockFindingProvider} 提供，而它声明的 domain 是 inventory，
     * 与餐饮那三条(谜题菜品/损耗集中/损耗占比突增)不同域。此前这里写死
     * {@code "restaurant"} 单域，`FindingServiceImpl` 又是
     * {@code provider.domain().equals(domain)} 逐字比对 —— **库存异常因此
     * 永远到不了店长眼前**。能力在、数据通道在，只差这一根线。
     *
     * <p>⛔ 店长关心的「库存」就是食材库存，与工厂端读的是同一套
     * material_batches / raw_material_types，口径无需另立。
     *
     * <p>⚠️ 没有异常时 provider 自然返回空、或抛
     * {@code FindingNotApplicableException} 被诚实跳过 —— **不带出来是对的**，
     * 不是失效。
     */
    private static final java.util.List<String> DOMAINS =
            java.util.List.of("restaurant", "inventory");

    private final FindingService findingService;
    private final FindingTextRenderer findingTextRenderer;

    /**
     * @param answer               Python 委派回来的回答原文
     * @param factoryId            餐饮租户
     * @param awaitingClarification 本次回答是**反问澄清**（还没给出答案）。为 true 时
     *                              不挂提示 —— 在「你想看哪家门店的损耗？」下面接一条
     *                              「变质损耗占 37%」会让店长不知道该先回答哪个。
     * @return 拼好提示的回答；无提示 / 澄清态 / 空回答时返回原文（逐字不变）
     */
    public String append(String answer, String factoryId, boolean awaitingClarification) {
        if (answer == null || answer.isBlank() || awaitingClarification) {
            return answer;
        }
        try {
            FindingService.Result result = findingService.detectInline(factoryId, DOMAINS);
            String hint = findingTextRenderer.renderInline(result);
            if (hint.isEmpty()) {
                return answer;
            }
            return answer + "\n\n" + hint;
        } catch (Exception e) {
            // 顺带提示是**附加物**：它坏了不该让店长连主回答都拿不到。
            // ⚠️ 这不是「把失败渲染成正常」——规则级失败（某条 provider 抛异常）已由
            // FindingServiceImpl 隔离进 failedRules，并由 renderInline 渲染成
            // 「检查失败，暂无法判断」照常送达。走到这个 catch 只剩基础设施级故障
            // （发现层本身炸了），此时静默降级并留 WARN 日志是刻意取舍。
            log.warn("[RestaurantFindingHint] 发现层不可用, 本次不挂顺带提示: factoryId={}",
                    factoryId, e);
            return answer;
        }
    }
}
