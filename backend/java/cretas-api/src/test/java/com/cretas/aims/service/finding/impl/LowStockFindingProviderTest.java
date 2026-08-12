package com.cretas.aims.service.finding.impl;

import com.cretas.aims.repository.MaterialBatchRepository;
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

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    /**
     * 那几条「只测映射」的用例统一放行 —— 它们要的是形状转换, 不是过滤。
     *
     * <p>🔴 2026-08-12 加过滤时这 4 条全红了(Mockito 默认返回空表 ⇒ 全被滤掉),
     * **那个红是对的**: 过滤从此是必经的。⛔ 没有用「查不到就放行」去消掉它 ——
     * 那会把噪音原样放回来。空表在生产上的真实含义是「这个工厂一条批次都没有」,
     * 那时所有低库存告警确实都是种子残留, 全滤掉是对的。
     */
    private void allEverStocked(String... ids) {
        when(materialBatchRepository.findMaterialTypeIdsEverStocked(anyString()))
                .thenReturn(List.of(ids));
    }

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
        allEverStocked("M001");

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
        allEverStocked("M001", "M002", "M003");

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
        allEverStocked("M001");

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
        allEverStocked("M001");

        assertFalse(provider.detect(FACTORY_ID).get(0).facts().containsKey("preferredSupplier"),
                "getLowStockWarnings 从不产出 preferredSupplier，facts 不得凭空造出该字段");
    }

    @Test
    @DisplayName("UT-LSF-10: 从没进过货的物料不报 —— 那是种子数据残留, 不是缺货")
    void neverStockedMaterialIsNotAFinding() {
        // 🔴 prod 实测(cretas_prod_db, 库名取自活 jar 进程 environ):
        //    MOCK_REST 的 25 个物料里 24 个有进货历史, 只有「罗氏虾」一条批次都没有,
        //    却挂着安全线 2288.42 —— 每条回答末尾都在报它, 缺口恰等于安全线全额。
        //    上一轮 LLM-judge 量出的「同一条发现重复 19 次、命中率 100%」就来自它。
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(
                warning("mt-shrimp", "罗氏虾", "CRITICAL", "0", "2288.42", "2288.42", 0L),
                warning("mt-beef", "牛肉", "WARNING", "120", "1844.29", "1724.29", 6L)));
        // 只有牛肉进过货
        when(materialBatchRepository.findMaterialTypeIdsEverStocked(anyString()))
                .thenReturn(List.of("mt-beef"));

        List<Finding> found = provider.detect(FACTORY_ID);

        assertEquals(1, found.size(), "从没进过货的物料仍然被报成缺货: " + found);
        assertEquals("牛肉", found.get(0).subjectName());
    }

    @Test
    @DisplayName("UT-LSF-11: 买过、用光了照旧报 —— 判据是进货历史不是当前余额")
    void stockedButNowEmptyIsStillAFinding() {
        // ⛔ 阴性对照。没有这一条, 上一条可以用「余额为 0 就不报」实现而照样绿 ——
        //    而那会把**真缺货**一起干掉(真缺货余额也是 0)。
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(
                warning("mt-beef", "牛肉", "CRITICAL", "0", "1844.29", "1844.29", 0L)));
        when(materialBatchRepository.findMaterialTypeIdsEverStocked(anyString()))
                .thenReturn(List.of("mt-beef"));

        List<Finding> found = provider.detect(FACTORY_ID);

        assertEquals(1, found.size(),
                "买过但用光的物料被消音了 —— 那是真缺货, 判据用错成了「余额是不是 0」");
        assertEquals("牛肉", found.get(0).subjectName());
    }
}
