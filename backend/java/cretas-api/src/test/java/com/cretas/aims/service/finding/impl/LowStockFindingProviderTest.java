package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LowStockFindingProvider}. */
@ExtendWith(MockitoExtension.class)
class LowStockFindingProviderTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private LowStockFindingProvider provider;

    @Mock
    private MaterialBatchService materialBatchService;

    /** 严格复刻 MaterialBatchServiceImpl#getLowStockWarnings 的 key 集合与类型。 */
    private Map<String, Object> warning(String id, String name, String level,
                                        String current, String safety, String gap, long ratio) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("materialTypeId", id);
        w.put("materialName", name);
        w.put("materialCode", "MC-" + id);
        w.put("category", "水产");
        w.put("currentStock", new BigDecimal(current));
        w.put("safetyStock", new BigDecimal(safety));
        w.put("unit", "kg");
        w.put("gap", new BigDecimal(gap));
        w.put("stockRatio", ratio);
        w.put("warningLevel", level);
        return w;
    }

    @Test
    @DisplayName("UT-LSF-01: domain=inventory, ruleName=低库存")
    void metadata() {
        assertEquals("inventory", provider.domain());
        assertEquals("低库存", provider.ruleName());
    }

    @Test
    @DisplayName("UT-LSF-02: 把 warning map 映射成 Finding，facts 保留数值字段")
    void mapsWarningToFinding() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "WARNING", "12", "50", "38", 24L)));

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("LOW_STOCK", f.code());
        assertEquals("inventory", f.domain());
        assertEquals(Finding.Severity.WARNING, f.severity());
        assertEquals("M001", f.subjectId());
        assertEquals("鲈鱼", f.subjectName());
        assertEquals(new BigDecimal("12"), f.facts().get("currentStock"));
        assertEquals(new BigDecimal("50"), f.facts().get("safetyStock"));
        assertEquals(new BigDecimal("38"), f.facts().get("gap"));
        assertEquals("kg", f.facts().get("unit"));
        assertEquals(24L, f.facts().get("stockRatio"));
    }

    @Test
    @DisplayName("UT-LSF-03: warningLevel 三值分别映射到对应 Severity")
    void mapsAllSeverityLevels() {
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(
                warning("M001", "A", "CRITICAL", "0", "50", "50", 0L),
                warning("M002", "B", "WARNING", "20", "50", "30", 40L),
                warning("M003", "C", "INFO", "35", "50", "15", 70L)));

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertEquals(Finding.Severity.CRITICAL, findings.get(0).severity());
        assertEquals(Finding.Severity.WARNING, findings.get(1).severity());
        assertEquals(Finding.Severity.INFO, findings.get(2).severity());
    }

    @Test
    @DisplayName("UT-LSF-04: 未知 warningLevel 降级为 INFO 而不是抛异常")
    void unknownLevelFallsBackToInfo() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "SOMETHING_NEW", "12", "50", "38", 24L)));

        assertEquals(Finding.Severity.INFO, provider.detect(FACTORY_ID).get(0).severity());
    }

    @Test
    @DisplayName("UT-LSF-05: 无预警时返回空列表（不是 null）")
    void emptyWhenNoWarnings() {
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of());

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("UT-LSF-06: 话术不得引用供应商 —— facts 里禁止出现 preferredSupplier")
    void factsMustNotClaimSupplier() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "WARNING", "12", "50", "38", 24L)));

        assertFalse(provider.detect(FACTORY_ID).get(0).facts().containsKey("preferredSupplier"),
                "getLowStockWarnings 从不产出 preferredSupplier，facts 不得凭空造出该字段");
    }
}
