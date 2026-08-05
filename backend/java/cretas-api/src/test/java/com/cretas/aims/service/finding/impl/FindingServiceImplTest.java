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
}
