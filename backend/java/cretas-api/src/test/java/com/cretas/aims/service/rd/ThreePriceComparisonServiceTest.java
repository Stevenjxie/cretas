package com.cretas.aims.service.rd;

import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.ProductMidQuoteRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.rd.impl.ThreePriceComparisonServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityNotFoundException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SP10: ThreePriceComparisonService 单元测试.
 *
 * <p>覆盖:
 * - 仅预报价: midQuote=null, actualCost=null
 * - 预报价+中报价: actualCost=null
 * - varianceAlerts 包含 PRE_TO_MID entry
 * - 样品不存在 → dto preQuote=null (task=null)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP10 ThreePriceComparisonService 单元测试")
class ThreePriceComparisonServiceTest {

    @Mock QuotationTaskRepository quotationTaskRepository;
    @Mock ProductMidQuoteRepository midQuoteRepository;

    private ThreePriceComparisonServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ThreePriceComparisonServiceImpl(
                quotationTaskRepository, midQuoteRepository);
    }

    private QuotationTask buildTask(String sampleId, BigDecimal totalCost) {
        QuotationTask task = new QuotationTask();
        task.setFactoryId("F006");
        task.setSampleId(sampleId);
        task.setStatus("QUOTED");
        task.setQuoteStage("PRE");
        task.setLaborPerKg(new BigDecimal("10.0000"));
        task.setTotalCost(totalCost);
        return task;
    }

    private ProductMidQuote buildMidQuote(BigDecimal totalCostPerKg, BigDecimal trialOutputKg,
                                          BigDecimal threshold) {
        ProductMidQuote q = new ProductMidQuote();
        q.setStatus("CALCULATED");
        q.setTotalCostPerKg(totalCostPerKg);
        q.setMaterialCostPerKg(new BigDecimal("30.0000"));
        q.setLaborCostPerKg(new BigDecimal("12.5000"));
        q.setOverheadCostPerKg(new BigDecimal("5.0000"));
        q.setCostVariancePct(new BigDecimal("13.00"));
        q.setTrialOutputKg(trialOutputKg);
        q.setVarianceThresholdPct(threshold);
        q.setVarianceAlert(false);
        return q;
    }

    @Test
    @DisplayName("仅预报价: midQuote=null → midQuote字段为null, actualCost=null")
    void threePrice_onlyPreQuote() {
        when(quotationTaskRepository.findBySampleIdAndDeletedAtIsNull("sample-001"))
                .thenReturn(buildTask("sample-001", new BigDecimal("2000.00")));
        when(midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc("F006", "sample-001"))
                .thenReturn(Optional.empty());

        ThreePriceComparisonDTO dto = service.getThreePriceComparison("F006", "sample-001");

        // preQuote = totalCost as-is (no batch qty to divide by)
        assertNotNull(dto.getPreQuote(), "预报价不应为 null (fallback=totalCost)");
        assertNull(dto.getMidQuote(), "中报价未汇算时应为 null");
        assertNull(dto.getActualCost(), "实际成本未计算时应为 null");
        assertTrue(dto.getVarianceAlerts().isEmpty(), "无中报价时不应有偏差告警");
    }

    @Test
    @DisplayName("预报价+中报价: preQuote=totalCost/trialOutputKg, actualCost=null")
    void threePrice_preAndMid() {
        when(quotationTaskRepository.findBySampleIdAndDeletedAtIsNull("sample-001"))
                .thenReturn(buildTask("sample-001", new BigDecimal("2000.00")));
        ProductMidQuote mid = buildMidQuote(new BigDecimal("47.5000"), new BigDecimal("50.0"), null);
        when(midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc("F006", "sample-001"))
                .thenReturn(Optional.of(mid));

        ThreePriceComparisonDTO dto = service.getThreePriceComparison("F006", "sample-001");

        // preQuote = 2000 / 50 = 40.0000
        assertNotNull(dto.getPreQuote());
        assertEquals(0, new BigDecimal("40.0000").compareTo(dto.getPreQuote()),
                "预报价 per kg 应为 2000/50=40");
        // midQuote = 47.5000
        assertNotNull(dto.getMidQuote());
        assertEquals(0, new BigDecimal("47.5000").compareTo(dto.getMidQuote()),
                "中报价 per kg 应为 47.5");
        assertNull(dto.getActualCost());
    }

    @Test
    @DisplayName("varianceAlerts 包含 PRE_TO_MID entry")
    void threePrice_varianceAlert_preToMid_entryExists() {
        when(quotationTaskRepository.findBySampleIdAndDeletedAtIsNull("sample-001"))
                .thenReturn(buildTask("sample-001", new BigDecimal("2000.00")));
        // preQuotePerKg = 2000/50 = 40, midQuote = 50 → variance = (50-40)/40*100 = 25%
        // threshold = 10% → alert=true
        ProductMidQuote mid = buildMidQuote(new BigDecimal("50.0000"), new BigDecimal("50.0"),
                new BigDecimal("10.00"));
        mid.setVarianceAlert(true);
        when(midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc("F006", "sample-001"))
                .thenReturn(Optional.of(mid));

        ThreePriceComparisonDTO dto = service.getThreePriceComparison("F006", "sample-001");

        assertFalse(dto.getVarianceAlerts().isEmpty(), "应有偏差告警条目");
        var preToMid = dto.getVarianceAlerts().stream()
                .filter(a -> "PRE_TO_MID".equals(a.getStage()))
                .findFirst();
        assertTrue(preToMid.isPresent(), "应有 PRE_TO_MID 条目");
        assertTrue(preToMid.get().isAlert(), "超阈值时 alert 应为 true");
    }

    @Test
    @DisplayName("样品报价任务不存在 → preQuote=null, midQuote=null")
    void threePrice_taskNotFound_returnsNulls() {
        when(quotationTaskRepository.findBySampleIdAndDeletedAtIsNull("no-such"))
                .thenReturn(null);
        when(midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc("F006", "no-such"))
                .thenReturn(Optional.empty());

        ThreePriceComparisonDTO dto = service.getThreePriceComparison("F006", "no-such");

        assertNull(dto.getPreQuote(), "任务不存在时 preQuote 应为 null");
        assertNull(dto.getMidQuote(), "任务不存在时 midQuote 应为 null");
    }

    // ==================== getThreePriceComparisonByTaskId 测试 ====================

    @Test
    @DisplayName("getThreePriceComparisonByTaskId() — 通过 taskId 解析 sampleId 并返回三价对比")
    void threePriceByTaskId_resolvesSampleId() {
        // 模拟 QuotationTask → sampleId 解析
        QuotationTask task = buildTask("sample-001", new BigDecimal("2000.00"));
        task.setId("task-abc");
        when(quotationTaskRepository.findById("task-abc")).thenReturn(Optional.of(task));

        // 中报价有数据
        ProductMidQuote mid = buildMidQuote(new BigDecimal("47.5000"), new BigDecimal("50.0"), null);
        when(midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc("F006", "sample-001"))
                .thenReturn(Optional.of(mid));
        when(quotationTaskRepository.findBySampleIdAndDeletedAtIsNull("sample-001"))
                .thenReturn(task);

        ThreePriceComparisonDTO dto = service.getThreePriceComparisonByTaskId("F006", "task-abc");

        assertNotNull(dto);
        // 确认不是用 taskId 当 sampleId 查到的 (taskId≠sampleId → 若出 preQuote 说明路由正确)
        assertNotNull(dto.getMidQuote(), "通过 taskId 解析后应取到中报价");
    }

    @Test
    @DisplayName("getThreePriceComparisonByTaskId() — 报价任务不存在 → EntityNotFoundException")
    void threePriceByTaskId_taskNotFound_throws() {
        when(quotationTaskRepository.findById("no-task")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.getThreePriceComparisonByTaskId("F006", "no-task"),
                "报价任务不存在应抛出 EntityNotFoundException");
    }
}
