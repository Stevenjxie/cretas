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
    private final com.cretas.aims.service.finding.FindingOccurrenceTracker occurrenceTracker;

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
            FindingService.Result result = findingService.detectInline(
                    factoryId, DOMAINS,
                    com.cretas.aims.service.finding.FindingOrdering.ACT_NOW);
            result = dropWhatTheAnswerAlreadySaid(result, answer);
            String hint = findingTextRenderer.renderInline(result);
            hint = appendRepeatNotice(hint, factoryId, result);
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

    /**
     * 去掉**答案里已经讲过**的发现。
     *
     * <p>🔴 Steve 2026-08-08 定的判据：「得确保 AI 知道当前是不是在问这个问题，
     * 如果是的话那就不用提示，如果不是的话就可以提示一下。」
     *
     * <p>顺带提示的价值**只在于说答案没说的**。老板问「损耗怎么样」，答案已经
     * 把变质损耗的金额摆出来了，末尾再挂一条「变质损耗占比过高」——他刚读完的
     * 那段话里就有这个数字，重复一遍只会让他觉得这套提示不长眼。
     *
     * <p>⛔ 判据用的是**发现指向的对象名**（subjectName，如「罗氏虾」「变质」）
     * 是否出现在答案正文里，不是域匹配：
     * <ul>
     *   <li>域匹配会误杀 —— 问营收时挂「折扣侵蚀」是跨域的，但它恰恰改变了
     *       老板对刚才那个营收数字的理解，最该说。</li>
     *   <li>域匹配也会漏杀 —— 同域但答案已详列的，照样重复。</li>
     * </ul>
     * 对象名在不在答案里，是「这件事他刚才读到没有」的直接证据。
     *
     * <p>⚠️ 全部被去掉时返回**发现为空但 checkedRules 保留**的结果 ——
     * 渲染层会照常说「已检查 X，均正常」。这不是假话：那些发现确实已经在
     * 答案里说过了，不是被隐瞒。⛔ 不能连 checkedRules 一起清空，那会让
     * 「查过了」退化成「什么都没查」，三态又塌回两态。
     */
    private FindingService.Result dropWhatTheAnswerAlreadySaid(
            FindingService.Result result, String answer) {
        if (result.findings().isEmpty()) {
            return result;
        }
        java.util.List<com.cretas.aims.service.finding.Finding> kept =
                result.findings().stream()
                        .filter(f -> f.subjectName() == null
                                || f.subjectName().isBlank()
                                || !answer.contains(f.subjectName()))
                        .toList();
        if (kept.size() == result.findings().size()) {
            return result;
        }
        return new FindingService.Result(
                kept, result.checkedRules(), result.totalCount(),
                result.countsByCode(), result.failedRules(), result.skippedRules());
    }

    /**
     * 给已经连续提醒多天的发现加一句「这条已连续提醒 N 天」。
     *
     * <p>🔴 Steve 2026-08-08：「只要问就是有问题啊」—— **重复不该消除**，
     * 但第八天还说一模一样的话是浪费。重复本身应该变成信息。
     *
     * <p>⛔ 措辞与语义**逐字对齐**：说的是「这条提醒连续出现了 N 天」，
     * 不是「这件事持续了 N 天」。我们只知道前者（后者要按天回算规则），
     * 说成后者就是编。而且前者更有用 —— 它指的是老板已经被提醒 N 天还没动。
     *
     * <p>⚠️ 记录/查询失败一律当作「没有这条信息」，提示照常出 ——
     * 连续天数是**附加信息**，它坏了不该让主提示消失。
     */
    private String appendRepeatNotice(String hint, String factoryId,
                                      FindingService.Result result) {
        if (hint == null || hint.isEmpty() || result.findings().isEmpty()) {
            return hint;
        }
        java.util.Map<String, Integer> days =
                occurrenceTracker.recordAndCountConsecutiveDays(factoryId, result.findings());
        // ⚠️ null 也要防: 连续天数是**附加信息**, 它以任何形式失效都不该让主提示消失。
        //    (2026-08-09 全量跑出来的: 追踪器返回 null 时整条提示被吞掉 ——
        //     单跑 tracker 的测试全绿, 是 appender 的测试把它抓出来的。)
        if (days == null || days.isEmpty()) {
            return hint;
        }
        java.util.List<String> notices = new java.util.ArrayList<>();
        for (com.cretas.aims.service.finding.Finding f : result.findings()) {
            Integer n = days.get(com.cretas.aims.service.finding.FindingOccurrenceTracker
                    .key(f.code(), f.subjectId()));
            if (n != null
                    && n >= com.cretas.aims.service.finding.FindingOccurrenceTracker.MIN_DAYS_WORTH_SAYING) {
                notices.add((f.subjectName() == null || f.subjectName().isBlank()
                        ? "这条" : f.subjectName()) + "已连续提醒 " + n + " 天");
            }
        }
        if (notices.isEmpty()) {
            return hint;
        }
        return hint + "\n> （" + String.join("；", notices) + "，一直没处理。）";
    }
}
