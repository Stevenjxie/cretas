package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * R4 (2026-07-04) 深测: {@link OrderCostBreakdownService} 补计 SFI/FG 投料成本 (出厂核算 == FG.unitCost)。
 *
 * <p>SFI/FG 投料边不写 MaterialConsumption → traceCost 失明 → 此前 totalCost 少一个投料桶,
 * 与小结算的 {@code FinishedGoodsBatch.unitCost} (含 SFI/FG 投料) 差一个 SFI 金额 (live: 16.33 vs 4.67)。
 * 本测固化: process-row 的 semiFinished/finishedGoods 投料成本进 semiFeedCost + totalCost;
 * 诚实 null (未计价投料 → costComplete=false + 派生成本字段 null); 无投料零回归。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderCostBreakdown SFI/FG 投料成本补计 (R4)")
class OrderCostBreakdownSfiFeedTest {

    private static final String F = "F006";
    private static final String BN = "CLK-B-FGDONE";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private YieldReportService yieldReportService;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderCostBreakdownService service;

    private final Long batchId = 900L;

    @BeforeEach
    void setUp() {
        service = new OrderCostBreakdownService(
                planRepository, batchRepository, consumptionRepository, materialBatchRepository,
                productionReportRepository, yieldReportService,
                processSheetRowRepository, wipInventoryService, finishedGoodsFeedService, objectMapper);

        ProductionBatch b = new ProductionBatch();
        b.setId(batchId);
        b.setFactoryId(F);
        b.setQuantity(new BigDecimal("10"));   // 入库 10 (盒/kg 皆按此摊)
        when(batchRepository.findByFactoryIdAndBatchNumber(F, BN)).thenReturn(Optional.of(b));

        // 本道人工 80, 无原料/调料/包装报工 → base = 80 (raw 由 traceCost 承载, 本测无 raw 边)
        when(yieldReportService.getYield(F, batchId))
                .thenReturn(BatchYieldDTO.builder().totalLaborCost(new BigDecimal("80")).steps(List.of()).build());
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(batchId, F))
                .thenReturn(new ArrayList<>());
    }

    /** process-row: 单条投料边 (semiFinished 或 finishedGoods)。 */
    private ProcessSheetRow rowWithFeed(String srcBatchNo, String feedKg, boolean semi, boolean fg) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r1");
        req.setProcessCode("qidiao");
        req.setProcessOrder(9);
        req.setProductTypeId("PT-FG");
        req.setFinished(true);
        req.setOutputQuantity(new BigDecimal("10"));
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(srcBatchNo);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(semi);
        ref.setFinishedGoods(fg);
        req.setUpstreamSources(List.of(ref));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setBatchId(batchId);
        row.setBatchNumber(BN);
        try {
            row.setRowPayload(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    @Test
    @DisplayName("R4 SFI 投料入 totalCost: labor80 + SFI 40kg@5=200 → semiFeedCost=200, total=280, perBox=28 (出厂核算==FG.unitCost×qty)")
    void sfiFeedIncludedInBreakdownTotal() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(List.of(rowWithFeed("SFI-COSTED", "40", true, false)));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(F), eq("SFI-COSTED"), any()))
                .thenAnswer(inv -> inv.getArgument(2));   // kg 源 → 原值
        when(wipInventoryService.getSemiUnitCost(F, "SFI-COSTED")).thenReturn(new BigDecimal("5"));

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, false);

        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getSemiFeedCost()).isEqualByComparingTo("200");
        assertThat(dto.getLaborCost()).isEqualByComparingTo("80");
        // total = labor 80 + raw 0 + SFI feed 200 = 280; 此前 (失明) 仅 80 → 与 FG.unitCost(28)×10 对不上
        assertThat(dto.getTotalCost()).isEqualByComparingTo("280");
        assertThat(dto.getPerBoxCost()).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("R4 FG 投料(①c)入 totalCost: labor80 + FG 20kg@12=240 → semiFeedCost=240, total=320")
    void fgFeedIncludedInBreakdownTotal() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(List.of(rowWithFeed("FG-STOCK", "20", false, true)));
        when(finishedGoodsFeedService.resolveFeedQtyInSourceUnit(eq(F), eq("FG-STOCK"), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(finishedGoodsFeedService.getFeedUnitCost(F, "FG-STOCK")).thenReturn(new BigDecimal("12"));

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, false);

        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getSemiFeedCost()).isEqualByComparingTo("240");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("320");
    }

    @Test
    @DisplayName("R4 诚实 null: SFI 投料 unitCost 未知 → costComplete=false, total/perBox/semiFeedCost 全 null, 料/工分桶保留")
    void honestNullWhenFeedUnitCostUnknown() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(List.of(rowWithFeed("SFI-LEGACY", "40", true, false)));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(F), eq("SFI-LEGACY"), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(wipInventoryService.getSemiUnitCost(F, "SFI-LEGACY")).thenReturn(null);  // 未接通成本

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, false);

        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getSemiFeedCost()).isNull();
        assertThat(dto.getTotalCost()).isNull();     // 整批成本诚实未知 (不伪造 ¥0 稀释)
        assertThat(dto.getPerBoxCost()).isNull();
        assertThat(dto.getNetTotalCost()).isNull();
        assertThat(dto.getLaborCost()).isEqualByComparingTo("80");   // 已知分桶仍展示
    }

    @Test
    @DisplayName("R4 诚实 null: 盒装 SFI 缺每盒克重 (resolve 折算 null) → costComplete=false, total null")
    void honestNullWhenFeedConversionUnknown() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(List.of(rowWithFeed("SFI-BOX", "40", true, false)));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(F), eq("SFI-BOX"), any()))
                .thenReturn(null);   // 盒装来源缺每盒克重, 无法折算

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, false);

        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getTotalCost()).isNull();
    }

    @Test
    @DisplayName("R4 零回归: 无 SFI/FG 投料 (process-row 无投料边) → semiFeedCost null, costComplete=true, total=labor80")
    void noRegressionWhenNoStockFeed() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(new ArrayList<>());   // 无 process-row (或无投料边)

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, false);

        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getSemiFeedCost()).isNull();    // 无投料 → N/A
        assertThat(dto.getTotalCost()).isEqualByComparingTo("80");
        assertThat(dto.getPerBoxCost()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("R4 脱敏: maskPrice → semiFeedCost 与其它金额一并 null (物理量保留)")
    void maskPriceNullsSemiFeedCost() {
        when(processSheetRowRepository.findByFactoryIdAndBatchId(F, batchId))
                .thenReturn(List.of(rowWithFeed("SFI-COSTED", "40", true, false)));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(F), eq("SFI-COSTED"), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(wipInventoryService.getSemiUnitCost(F, "SFI-COSTED")).thenReturn(new BigDecimal("5"));

        OrderCostBreakdownDTO dto = service.computeByBatch(F, BN, true);

        assertThat(dto.isPriceMasked()).isTrue();
        assertThat(dto.getSemiFeedCost()).isNull();
        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.getBoxCount()).isEqualTo(10);   // 物理量保留
    }
}
