package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;

@ExtendWith(MockitoExtension.class)
class ProductionSummaryServiceTest {
    @Mock ProcessSheetService processSheetService;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock OrderCostBreakdownService orderCostBreakdownService;
    @Mock ReusedSemiLineageService reusedSemiLineageService;
    @InjectMocks ProductionSummaryService service;

    /** 默认: 无复用半成品 → empty lineage (每个测试可覆盖)。lenient 避免未触发 resolve 的边角测试报未用桩。 */
    @BeforeEach
    void stubNoReuseByDefault() {
        lenient().when(reusedSemiLineageService.resolve(anyString(), anyString()))
                .thenReturn(ReusedSemiLineageService.ReusedFrontLineage.empty());
    }

    private ProcessSheetInventoryItem item(int order, String status, BigDecimal input,
                                           BigDecimal produced, BigDecimal remaining, BigDecimal cumYield) {
        return item(order, status, input, produced, remaining, cumYield, null);
    }

    private ProcessSheetInventoryItem item(int order, String status, BigDecimal input,
                                           BigDecimal produced, BigDecimal remaining, BigDecimal cumYield,
                                           BigDecimal productWeight) {
        ProcessSheetInventoryItem i = new ProcessSheetInventoryItem();
        i.setProcessOrder(order); i.setStatus(status); i.setInputQuantity(input);
        i.setProduced(produced); i.setRemaining(remaining); i.setCumulativeYieldRate(cumYield);
        i.setProductWeight(productWeight);
        return i;
    }

    private ProductionBatch clkB(BigDecimal qty) {
        ProductionBatch b = new ProductionBatch(); b.setBatchType("REGULAR"); b.setQuantity(qty); return b;
    }

    @Test
    void totalRawInput_sumsFirstProcessAcrossBatches() {
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of(
                item(1, "IN_PROGRESS", new BigDecimal("2.0"), new BigDecimal("1.8"), new BigDecimal("0.2"), null),
                item(1, "IN_PROGRESS", new BigDecimal("3.0"), new BigDecimal("2.7"), new BigDecimal("0.3"), null),
                item(2, "IN_PROGRESS", new BigDecimal("1.8"), new BigDecimal("1.6"), new BigDecimal("1.6"), new BigDecimal("90"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(clkB(new BigDecimal("1.5")), clkB(new BigDecimal("0.5"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("5.0");
        assertThat(dto.getTotalFinishedOutput()).isEqualByComparingTo("2.0");
    }

    @Test
    void totalCost_sumsComputeByBatch() {
        // maskPrice=false: totalCost should sum two batches (100 + 50 = 150)
        OrderCostBreakdownDTO cb1 = OrderCostBreakdownDTO.builder().totalCost(new BigDecimal("100")).build();
        OrderCostBreakdownDTO cb2 = OrderCostBreakdownDTO.builder().totalCost(new BigDecimal("50")).build();
        ProductionBatch b1 = clkB(new BigDecimal("1")); b1.setBatchNumber("CLK-B-1");
        ProductionBatch b2 = clkB(new BigDecimal("1")); b2.setBatchNumber("CLK-B-2");
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of());
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(b1, b2));
        when(orderCostBreakdownService.computeByBatch("F006", "CLK-B-1", false)).thenReturn(cb1);
        when(orderCostBreakdownService.computeByBatch("F006", "CLK-B-2", false)).thenReturn(cb2);

        assertThat(service.computeSummary("F006", "P1", false).getTotalCost())
                .isEqualByComparingTo("150");
    }

    @Test
    void totalCost_nullWhenMaskPrice() {
        // maskPrice=true: totalCost must be null and computeByBatch must NOT be called
        ProductionBatch b1 = clkB(new BigDecimal("1")); b1.setBatchNumber("CLK-B-1");
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of());
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(b1));

        assertThat(service.computeSummary("F006", "P1", true).getTotalCost()).isNull();
        verify(orderCostBreakdownService, never()).computeByBatch(anyString(), anyString(), anyBoolean());
    }

    @org.junit.jupiter.api.Test
    void realYield_isFinishedWeightOverRawInput_andRemainingSemiIsRawSum() {
        // 首道投入 10 kg; 第2道(在制)剩余 0.9 kg (半成品本身); 成品 productWeight=0.7 kg
        // 真实出成率 = 0.7 / 10 * 100 = 7.00%; 剩余半成品 = 0.9 (不折算)
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(java.util.List.of(
                item(1, "ACTIVE", new java.math.BigDecimal("10.0"), new java.math.BigDecimal("9.0"), new java.math.BigDecimal("0"), null),
                item(2, "ACTIVE", null, new java.math.BigDecimal("0.9"), new java.math.BigDecimal("0.9"), new java.math.BigDecimal("90")),
                item(3, "COMPLETED", null, new java.math.BigDecimal("8.0"), new java.math.BigDecimal("0"), new java.math.BigDecimal("80"),
                        new java.math.BigDecimal("0.7"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(java.util.List.of(clkB(new java.math.BigDecimal("8.0"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        // non-COMPLETED rows: row1 remaining=0, row2 remaining=0.9 → sum=0.9
        assertThat(dto.getRemainingSemiFinished()).isEqualByComparingTo("0.9");
        // weight-based: 0.7 * 100 / 10.0 = 7.00
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("7.00");
        assertThat(dto.getTotalFinishedWeight()).isEqualByComparingTo("0.7");
        assertThat(dto.getYieldNote()).isNull();
    }

    @org.junit.jupiter.api.Test
    void realYield_nullWhenProductWeightNotRecorded_andYieldNoteSet() {
        // COMPLETED 行无 productWeight → realYieldRate null, yieldNote 有提示
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(java.util.List.of(
                item(1, "ACTIVE", new java.math.BigDecimal("10.0"), new java.math.BigDecimal("9.0"), new java.math.BigDecimal("0"), null),
                item(2, "COMPLETED", null, new java.math.BigDecimal("8.0"), new java.math.BigDecimal("0"), new java.math.BigDecimal("80"))
                // productWeight not set → null
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(java.util.List.of(clkB(new java.math.BigDecimal("8.0"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        assertThat(dto.getRealYieldRate()).isNull();
        assertThat(dto.getTotalFinishedWeight()).isNull();
        assertThat(dto.getYieldNote()).isNotNull().contains("成品重量未录入");
    }

    // ─────────────────────────────────────────────────────────────
    // ①d 复用半成品前段接续 (summary 层: 分母增补 + 血缘可见 + honest-null note)
    // ─────────────────────────────────────────────────────────────

    private ProductionSummaryDTO.ReusedSemiLineage lineageLine(String batch, String drawn, String produced,
                                                               String frontRaw, boolean included, String note) {
        return ProductionSummaryDTO.ReusedSemiLineage.builder()
                .sourceBatchNumber(batch)
                .drawnQuantity(new BigDecimal(drawn))
                .sourceProducedQuantity(new BigDecimal(produced))
                .frontRawInput(frontRaw == null ? null : new BigDecimal(frontRaw))
                .frontRawIncluded(included)
                .note(note)
                .build();
    }

    @Test
    void realYield_includesReusedSemiFrontRaw_inDenominator() {
        // P2 起于滚揉复用外部 SFI: P2 自身原料 2.0kg; 复用批次前段接续 8.0kg → 分母 10.0kg。
        // 成品重 0.7kg → 出成率 = 0.7*100/10.0 = 7.00% (若不接前段: 0.7*100/2.0 = 35% 虚高)。
        when(processSheetService.getInventoryYieldCard("F006", "P2")).thenReturn(List.of(
                item(1, "ACTIVE", new BigDecimal("2.0"), new BigDecimal("1.9"), new BigDecimal("0"), null),
                item(2, "COMPLETED", null, new BigDecimal("1.7"), new BigDecimal("0"), new BigDecimal("85"),
                        new BigDecimal("0.7"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P2"))
                .thenReturn(List.of(clkB(new BigDecimal("1.0"))));
        when(reusedSemiLineageService.resolve("F006", "P2")).thenReturn(
                ReusedSemiLineageService.ReusedFrontLineage.builder()
                        .totalIncludedFrontRaw(new BigDecimal("8.0"))
                        .lineages(List.of(lineageLine("CLK-SEMI-aaaaaaaa-bbbbbbbb", "6.0", "6.0", "8.0", true, null)))
                        .hasMissingProvenance(false)
                        .note(null)
                        .build());

        ProductionSummaryDTO dto = service.computeSummary("F006", "P2", false);

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("2.0");           // P2 自身原料 (含义不变)
        assertThat(dto.getReusedFrontRawInput()).isEqualByComparingTo("8.0");     // 接续前段
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("10.0");    // 分母 = 2.0 + 8.0
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("7.00");          // 接前段后真实出成率
        assertThat(dto.getYieldNote()).isNull();
        assertThat(dto.getReusedSemiLineages()).hasSize(1);
        assertThat(dto.getReusedSemiLineages().get(0).getSourceBatchNumber())
                .isEqualTo("CLK-SEMI-aaaaaaaa-bbbbbbbb");                          // 血缘可见
        assertThat(dto.getReusedSemiLineages().get(0).isFrontRawIncluded()).isTrue();
    }

    @Test
    void realYield_missingProvenance_excludesFrontRaw_withNote_notWrongNumber() {
        // 复用批次前段 provenance 缺失 → 前段不计入分母 (totalIncludedFrontRaw=0) + note 点名。
        // 出成率仍以 P2 自身原料算 (偏保守), 但 note 明确告知未含前段 → 诚实, 不伪造前段数。
        when(processSheetService.getInventoryYieldCard("F006", "P2")).thenReturn(List.of(
                item(1, "ACTIVE", new BigDecimal("2.0"), new BigDecimal("1.9"), new BigDecimal("0"), null),
                item(2, "COMPLETED", null, new BigDecimal("1.7"), new BigDecimal("0"), new BigDecimal("85"),
                        new BigDecimal("0.7"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P2"))
                .thenReturn(List.of(clkB(new BigDecimal("1.0"))));
        when(reusedSemiLineageService.resolve("F006", "P2")).thenReturn(
                ReusedSemiLineageService.ReusedFrontLineage.builder()
                        .totalIncludedFrontRaw(BigDecimal.ZERO)
                        .lineages(List.of(lineageLine("CLK-SEMI-cccccccc-dddddddd", "6.0", "6.0", null, false,
                                "来源计划前段原料投入未录, 前段未接入")))
                        .hasMissingProvenance(true)
                        .note("复用批次 CLK-SEMI-cccccccc-dddddddd 前段数据缺失，出成率未含其前段")
                        .build());

        ProductionSummaryDTO dto = service.computeSummary("F006", "P2", false);

        assertThat(dto.getReusedFrontRawInput()).isEqualByComparingTo("0");
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("2.0");     // 仅 P2 自身原料
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("35.00");         // 0.7*100/2.0 (偏保守但诚实)
        assertThat(dto.getYieldNote()).isNotNull().contains("前段数据缺失");     // 诚实告知
        assertThat(dto.getReusedSemiLineages().get(0).isFrontRawIncluded()).isFalse();
        assertThat(dto.getReusedSemiLineages().get(0).getFrontRawInput()).isNull(); // 诚实 null, 不伪造
    }

    @Test
    void realYield_noReuse_denominatorUnchanged_regression() {
        // 无复用 (default empty lineage) → 分母 = totalRawInput, 行为与 ①d 之前完全一致。
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of(
                item(1, "ACTIVE", new BigDecimal("10.0"), new BigDecimal("9.0"), new BigDecimal("0"), null),
                item(2, "COMPLETED", null, new BigDecimal("8.0"), new BigDecimal("0"), new BigDecimal("80"),
                        new BigDecimal("0.7"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(clkB(new BigDecimal("8.0"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        assertThat(dto.getReusedFrontRawInput()).isEqualByComparingTo("0");
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("10.0");
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("7.00");          // 0.7*100/10.0 不变
        assertThat(dto.getYieldNote()).isNull();
        assertThat(dto.getReusedSemiLineages()).isNullOrEmpty();
    }
}
