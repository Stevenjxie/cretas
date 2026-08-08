package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import com.cretas.aims.service.finding.FindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingServiceImpl}. */
class FindingServiceImplTest {

    private static final String FACTORY_ID = "F006";

    /** 固定返回给定 finding 的假 provider。 */
    private static FindingProvider stub(String domain, String ruleName, Finding... findings) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) { return List.of(findings); }
        };
    }

    /** 必定抛异常的假 provider。 */
    private static FindingProvider exploding(String domain, String ruleName) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static Finding finding(String code, String name, Finding.Severity severity) {
        return new Finding(code, "inventory", severity, 50, "id-" + name, name, Map.of());
    }

    @Test
    @DisplayName("UT-FSI-01: 只跑同 domain 的 provider")
    void filtersByDomain() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存", finding("LOW_STOCK", "鲈鱼", Finding.Severity.WARNING)),
                stub("salesOrder", "逾期未发货", finding("OVERDUE", "SO-1", Finding.Severity.CRITICAL))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules());
        assertEquals(1, r.findings().size());
        assertEquals("鲈鱼", r.findings().get(0).subjectName());
    }

    @Test
    @DisplayName("UT-FSI-02: 按 rankScore 降序排序")
    void sortsByRankScoreDescending() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "低", Finding.Severity.INFO),
                        finding("LOW_STOCK", "高", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "中", Finding.Severity.WARNING))
        ), 3);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("高", "中", "低"),
                r.findings().stream().map(Finding::subjectName).toList());
    }

    @Test
    @DisplayName("UT-FSI-03: 截断到 inlineMax，totalCount 仍是截断前总数")
    void truncatesToInlineMax() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "A", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "B", Finding.Severity.WARNING),
                        finding("LOW_STOCK", "C", Finding.Severity.INFO),
                        finding("LOW_STOCK", "D", Finding.Severity.INFO))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(2, r.findings().size());
        assertEquals(4, r.totalCount());
    }

    @Test
    @DisplayName("UT-FSI-04: countsByCode 统计的是截断前的数量")
    void countsByCodeUsesPreTruncationTotals() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "A", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "B", Finding.Severity.WARNING),
                        finding("LOW_STOCK", "C", Finding.Severity.INFO)),
                stub("inventory", "临期", finding("EXPIRING", "D", Finding.Severity.WARNING))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(3, r.countsByCode().get("LOW_STOCK"));
        assertEquals(1, r.countsByCode().get("EXPIRING"));
    }

    @Test
    @DisplayName("UT-FSI-05: 🔴 provider 抛异常时，该规则不得出现在 checkedRules 里")
    void failedProviderIsNotReportedAsChecked() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存"),
                exploding("inventory", "临期")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules(),
                "炸掉的规则若留在 checkedRules 里，UI 会说出「已检查 临期，均正常」这种假话");
        assertTrue(r.findings().isEmpty());
    }

    @Test
    @DisplayName("UT-FSI-06: 无 provider 匹配时返回空 Result 而不是 null")
    void noMatchingProviderReturnsEmptyResult() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("salesOrder", "逾期未发货")), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertNotNull(r);
        assertTrue(r.findings().isEmpty());
        assertTrue(r.checkedRules().isEmpty());
        assertEquals(0, r.totalCount());
        assertTrue(r.countsByCode().isEmpty());
    }

    @Test
    @DisplayName("UT-FSI-07: 🔴 provider 抛异常时记录进 failedRules，result 不再 complete()")
    void failedProviderIsRecordedAndResultIsIncomplete() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存"),
                exploding("inventory", "临期")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("临期"), r.failedRules(),
                "炸掉的规则必须被记录进 failedRules，否则消费方无法区分「零发现」与「查不出」");
        assertFalse(r.complete(), "有规则失败时 complete() 必须为 false");
    }

    @Test
    @DisplayName("UT-FSI-08: 全部 provider 成功时 failedRules 为空且 complete() 为 true")
    void allProvidersSucceedMeansComplete() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存", finding("LOW_STOCK", "鲈鱼", Finding.Severity.WARNING))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertTrue(r.failedRules().isEmpty());
        assertTrue(r.complete());
    }

    /** 必定诚实跳过的假 provider。 */
    private static FindingProvider skipping(String domain, String ruleName, String reason) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) {
                throw new com.cretas.aims.service.finding.FindingNotApplicableException(reason);
            }
        };
    }

    @Test
    @DisplayName("UT-FSI-09: 🔴 跳过的规则进 skippedRules，不进 checkedRules 也不进 failedRules")
    void skippedRuleLandsInItsOwnBucket() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存"),
                skipping("inventory", "食材损耗离群", "基线历史不足: 仅 6 天")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules(),
                "跳过的规则留在 checkedRules 里, UI 会说「已检查 食材损耗离群, 均正常」");
        assertTrue(r.failedRules().isEmpty(),
                "数据不足不是故障, 混进 failedRules 会让用户以为服务坏了");
        assertEquals(1, r.skippedRules().size());
        assertEquals("食材损耗离群", r.skippedRules().get(0).ruleName());
        assertEquals("基线历史不足: 仅 6 天", r.skippedRules().get(0).reason());
    }

    @Test
    @DisplayName("UT-FSI-10: 跳过不影响 complete()")
    void skippedKeepsResultComplete() {
        FindingService svc = new FindingServiceImpl(List.of(
                skipping("inventory", "食材损耗离群", "两期食材名单不可比")), 2);

        assertTrue(svc.detectInline(FACTORY_ID, "inventory").complete());
    }

    @Test
    @DisplayName("UT-FSI-11: 跳过与失败可以同时发生，各归各的桶")
    void skippedAndFailedCoexist() {
        FindingService svc = new FindingServiceImpl(List.of(
                skipping("inventory", "食材损耗离群", "基线历史不足"),
                exploding("inventory", "损耗类型集中度")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("食材损耗离群"),
                r.skippedRules().stream().map(FindingService.SkippedRule::ruleName).toList());
        assertEquals(List.of("损耗类型集中度"), r.failedRules());
        assertFalse(r.complete());
    }

    @Test
    @DisplayName("UT-FSI-10: 多域调用把两个域的发现合并后再排序截断")
    void multiDomainMergesBeforeCapping() {
        // 🔴 2026-08-08: 餐饮「顺带提示」原本写死单域 "restaurant", 而低库存发现
        //    由 LowStockFindingProvider 提供、domain 是 "inventory" ——
        //    域对不上, **库存异常永远到不了店长眼前**。能力在、数据通道在,
        //    只差这一根线。这条钉住多域合并本身。
        // ⛔ 不能用「调两次再拼」代替: inline 上限要在**合并后的全集**上截断,
        //    分别截断会让两个域各占名额, 把真正最要紧的那条挤掉 ——
        //    下面 cap=2 时必须是全局 top2(90/80), 而不是每域各一条。
        FindingServiceImpl service = new FindingServiceImpl(List.of(
                stub("restaurant", "rest-rule", ranked("REST_HIGH", 80), ranked("REST_LOW", 10)),
                stub("inventory", "stock-rule", ranked("STOCK_TOP", 90), ranked("STOCK_LOW", 20))
        ), 2);

        FindingService.Result r = service.detectInline(FACTORY_ID, List.of("restaurant", "inventory"));

        assertEquals(List.of("STOCK_TOP", "REST_HIGH"),
                r.findings().stream().map(Finding::code).toList(),
                "必须是合并后的全局 top2, 不是每域各一条");
        assertEquals(4, r.totalCount());
        assertEquals(java.util.Set.of("rest-rule", "stock-rule"),
                java.util.Set.copyOf(r.checkedRules()));
    }

    @Test
    @DisplayName("UT-FSI-11: 单域重载与只传一个域的多域调用行为一致")
    void singleDomainOverloadStillWorks() {
        FindingServiceImpl service = new FindingServiceImpl(List.of(
                stub("restaurant", "rest-rule", ranked("REST_HIGH", 80)),
                stub("inventory", "stock-rule", ranked("STOCK_TOP", 90))
        ), 5);

        assertEquals(List.of("REST_HIGH"),
                service.detectInline(FACTORY_ID, "restaurant")
                        .findings().stream().map(Finding::code).toList());
        assertEquals(List.of("REST_HIGH"),
                service.detectInline(FACTORY_ID, List.of("restaurant"))
                        .findings().stream().map(Finding::code).toList());
    }

    /** 指定 rankScore 的发现, 用来验证排序是在合并之后做的。 */
    private static Finding ranked(String code, int rankScore) {
        return new Finding(code, "inventory", Finding.Severity.WARNING, rankScore,
                "id-" + code, code, Map.of());
    }

    @Test
    @DisplayName("UT-FSI-12: 🔴 ACT_NOW 让「今天还来得及」压过「已经无可挽回」")
    void actNowPutsWhatYouCanStillFixFirst() {
        // 🔴 这是原 rankScore 的结构性缺陷: severity 3/2/1 × 100 + actionability 0-99
        //    => **最高的 WARNING 是 299, 最低的 CRITICAL 是 300**。
        //    任何 CRITICAL 永远压过所有 WARNING, 不管可行动性差多少。
        //    而「已经发生完的事」(食材已过期、钱已经亏了)最容易被评成 CRITICAL:
        //    损失确凿。结果是**已无可挽回的事稳定霸占同步提示那 2 个名额**,
        //    把今天动手还来得及的挤掉 —— 与顺带提示的目的正好相反。
        Finding expired = new Finding("EXPIRED", "inventory",
                Finding.Severity.CRITICAL, 0, "s1", "已过期的三文鱼", Map.of());
        Finding nearExpiry = new Finding("NEAR_EXPIRY", "inventory",
                Finding.Severity.WARNING, 90, "s2", "还剩2天的牛腩", Map.of());

        // 旧口径: 已过期(300) 压过 临期(290)
        assertTrue(expired.rankScore() > nearExpiry.rankScore(),
                "前提: 旧口径下已过期确实压过临期");

        FindingServiceImpl service = new FindingServiceImpl(
                List.of(stub("inventory", "库存", expired, nearExpiry)), 1);

        assertEquals(List.of("还剩2天的牛腩"),
                service.detectInline(FACTORY_ID, List.of("inventory"),
                        com.cretas.aims.service.finding.FindingOrdering.ACT_NOW)
                        .findings().stream().map(Finding::subjectName).toList(),
                "同步提示只有 1 个名额时, 该给今天还能救的那条");

        assertEquals(List.of("已过期的三文鱼"),
                service.detectInline(FACTORY_ID, List.of("inventory"),
                        com.cretas.aims.service.finding.FindingOrdering.IMPACT_FIRST)
                        .findings().stream().map(Finding::subjectName).toList(),
                "复盘出口反过来: 已经漏掉的钱才是要说的");
    }

    @Test
    @DisplayName("UT-FSI-13: ⛔ ACT_NOW 不是纯按可行动性 —— 严重度仍要参与")
    void actNowStillWeighsSeverity() {
        // 纯按可行动性排会让「很容易做但无关紧要」压过「既严重又还来得及」。
        // 乘法让两者都参与, 谁也不绝对主导。
        Finding trivialEasy = new Finding("TRIVIAL", "inventory",
                Finding.Severity.INFO, 95, "s1", "小事一桩", Map.of());
        Finding severeDoable = new Finding("SEVERE", "inventory",
                Finding.Severity.CRITICAL, 70, "s2", "大事还来得及", Map.of());

        FindingServiceImpl service = new FindingServiceImpl(
                List.of(stub("inventory", "库存", trivialEasy, severeDoable)), 1);

        assertEquals(List.of("大事还来得及"),
                service.detectInline(FACTORY_ID, List.of("inventory"),
                        com.cretas.aims.service.finding.FindingOrdering.ACT_NOW)
                        .findings().stream().map(Finding::subjectName).toList(),
                "3x70=210 > 1x95=95");
    }

    @Test
    @DisplayName("UT-FSI-14: ⛔ 不传排序的重载, 行为与既有逐字一致")
    void defaultOrderingIsUnchanged() {
        // 现存 5 个调用点(物料工具/损耗工具/REST 端点/行动方案/顺带提示之外的)
        // 都没传排序, 它们的顺序不该因为这次改动而变。
        Finding a = new Finding("A", "inventory", Finding.Severity.CRITICAL, 0, "s1", "甲", Map.of());
        Finding b = new Finding("B", "inventory", Finding.Severity.WARNING, 99, "s2", "乙", Map.of());
        FindingServiceImpl service = new FindingServiceImpl(
                List.of(stub("inventory", "库存", a, b)), 5);

        assertEquals(
                service.detectInline(FACTORY_ID, List.of("inventory"),
                        com.cretas.aims.service.finding.FindingOrdering.IMPACT_FIRST)
                        .findings().stream().map(Finding::code).toList(),
                service.detectInline(FACTORY_ID, List.of("inventory"))
                        .findings().stream().map(Finding::code).toList());
    }
}
