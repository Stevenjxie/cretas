package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F006 双出成率: ProcessSheetService.getInventoryYieldCard 单元测试 (Mockito, 无 Spring 上下文)。
 *
 * <p>测试目标:
 * <ol>
 *   <li>线性链三道: 拆包100→修油90→焯水80; stepYield 焯水=88.89%, cumulative=80%</li>
 *   <li>首道 step/cumulative 相等 (都 = 首道产出/首道原料投入)</li>
 *   <li>跨单位无 gramsPerUnit → cumulativeYieldRate=null</li>
 *   <li>首道 inputQuantity=0 → stepYieldRate=null, cumulativeYieldRate=null</li>
 *   <li>无生产批次 → 空列表</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetYieldCardTest - F006 双出成率 getInventoryYieldCard")
class ProcessSheetYieldCardTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-TEST-001";
    private static final long   BATCH_ID = 42L;

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository finishedGoodsBatchRepo;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                objectMapper, wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo);
    }

    // ─────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────

    private ProductionBatch batch(long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setProductionPlanId(PLAN_ID);
        return b;
    }

    private SemiFinishedInventory wip(long batchId, int order, String produced, String consumed,
                                      String available, String unit, Long taskId) {
        return SemiFinishedInventory.builder()
                .id((long) order)
                .factoryId(FACTORY)
                .batchId(batchId)
                .intermediateBatchNo("WIP-" + batchId + "-" + order)
                .processOrder(order)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit(unit)
                .status("AVAILABLE")
                .sourceWorkProcessTaskId(taskId)
                .build();
    }

    private ProductionReport yieldReport(int processOrder, String inputQty) {
        ProductionReport r = new ProductionReport();
        r.setProcessOrder(processOrder);
        r.setInputQuantity(new BigDecimal(inputQty));
        r.setOutputQuantity(new BigDecimal("0"));
        return r;
    }

    private ProcessSheetRow sheetRow(int order, long batchId, String batchNumber) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(order == 1 ? "xiuyou" : "chaoshui");
        row.setProcessOrder(order);
        row.setClientRowId("row-" + order);
        row.setBatchId(batchId);
        row.setBatchNumber(batchNumber);
        row.setRowStatus("SAVED");
        row.setRowPayload("payload-" + order);
        return row;
    }

    private ProcessSheetRowRequest sheetPayload(String input, String output) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProductTypeId("PT-001");
        req.setInputQuantity(new BigDecimal(input));
        req.setOutputQuantity(new BigDecimal(output));
        req.setUnit("kg");
        return req;
    }

    private MaterialBatch materialWip(long batchId, String batchNumber, String receipt, String unitPrice) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId("wip-" + batchId);
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber("WIP-" + batchNumber);
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(batchId));
        mb.setReceiptQuantity(new BigDecimal(receipt));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setUnitPrice(new BigDecimal(unitPrice));
        return mb;
    }

    @Test
    @DisplayName("YIELD-CARD-0: process-sheet rows without productionPlanId still render saved WIP")
    void processSheetRows_withoutProductionPlanId_renderYieldCard() throws Exception {
        ProcessSheetRow row1 = sheetRow(1, 9108L, "CLK-W-1");
        ProcessSheetRow row2 = sheetRow(2, 9110L, "CLK-W-2");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(row1, row2));
        when(objectMapper.readValue("payload-1", ProcessSheetRowRequest.class))
                .thenReturn(sheetPayload("100", "95"));
        when(objectMapper.readValue("payload-2", ProcessSheetRowRequest.class))
                .thenReturn(sheetPayload("95", "85"));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9108"))
                .thenReturn(Optional.of(materialWip(9108L, "CLK-W-1", "95", "33.68")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9110"))
                .thenReturn(Optional.of(materialWip(9110L, "CLK-W-2", "85", "35.66")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getBatchNumber()).isEqualTo("CLK-W-1");
        assertThat(result.get(0).getStepYieldRate()).isEqualByComparingTo("95.0000");
        assertThat(result.get(1).getBatchNumber()).isEqualTo("CLK-W-2");
        assertThat(result.get(1).getStepYieldRate()).isEqualByComparingTo("89.4737");
        assertThat(result.get(1).getCumulativeYieldRate()).isEqualByComparingTo("85.0000");
    }

    @Test
    @DisplayName("YIELD-CARD-0-DATE: processDate threaded from row payload to yield card item (张权 UX: 流程日期列)")
    void processSheetRows_processDate_threadedFromPayloadToYieldCardItem() throws Exception {
        // Real F006 prod row_payload (plan PLAN-1782635211587-C1AB35C2, verified via prod DB):
        // {"processDate":"2026-06-28", ...}. Confirms getProcessDate() flows unchanged through
        // Jackson deserialize -> builder -> DTO, matching the value the customer entered in 逐工序录入.
        ProcessSheetRow row1 = sheetRow(1, 9108L, "CLK-W-1");
        ProcessSheetRow row2 = sheetRow(2, 9110L, "CLK-W-2");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(row1, row2));

        ProcessSheetRowRequest payload1 = sheetPayload("100", "95");
        payload1.setProcessDate(java.time.LocalDate.of(2026, 6, 28));
        ProcessSheetRowRequest payload2 = sheetPayload("95", "85");
        payload2.setProcessDate(java.time.LocalDate.of(2026, 6, 29));

        when(objectMapper.readValue("payload-1", ProcessSheetRowRequest.class)).thenReturn(payload1);
        when(objectMapper.readValue("payload-2", ProcessSheetRowRequest.class)).thenReturn(payload2);
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9108"))
                .thenReturn(Optional.of(materialWip(9108L, "CLK-W-1", "95", "33.68")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9110"))
                .thenReturn(Optional.of(materialWip(9110L, "CLK-W-2", "85", "35.66")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProcessDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 28));
        assertThat(result.get(1).getProcessDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 29));
    }

    @Test
    @DisplayName("YIELD-CARD-0B: partial upstream WIP consumption inherits raw-equivalent quantity by consumed ratio")
    void processSheetRows_partialUpstreamConsumption_usesInheritedRawEquivalent() throws Exception {
        ProcessSheetRow rollingRow = sheetRow(3, 9201L, "CLK-W-ROLL");
        rollingRow.setProcessCode("gunrou");
        ProcessSheetRow blanchRow = sheetRow(4, 9202L, "CLK-W-BLANCH");
        blanchRow.setProcessCode("chaoshui");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rollingRow, blanchRow));

        ProcessSheetRowRequest rollingPayload = sheetPayload("1440", "1571.19");
        rollingPayload.setProcessName("滚揉");

        ProcessSheetRowRequest blanchPayload = sheetPayload("765.19", "604.5");
        blanchPayload.setProcessName("焯水");
        ProcessSheetRowRequest.UpstreamRef upstream = new ProcessSheetRowRequest.UpstreamRef();
        upstream.setSourceBatchNumber("CLK-W-ROLL");
        upstream.setFeedQuantityKg(new BigDecimal("765.19"));
        blanchPayload.setUpstreamSources(List.of(upstream));

        when(objectMapper.readValue("payload-3", ProcessSheetRowRequest.class)).thenReturn(rollingPayload);
        when(objectMapper.readValue("payload-4", ProcessSheetRowRequest.class)).thenReturn(blanchPayload);
        when(productionBatchRepo.findAllById(any())).thenReturn(List.of());
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9201"))
                .thenReturn(Optional.of(materialWip(9201L, "CLK-W-ROLL", "1571.19", "20")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9202"))
                .thenReturn(Optional.of(materialWip(9202L, "CLK-W-BLANCH", "604.5", "25")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem blanch = result.get(1);
        assertThat(blanch.getSourceBatchNumber()).isEqualTo("CLK-W-ROLL");
        assertThat(blanch.getFeedQuantity()).isEqualByComparingTo("765.19");
        assertThat(blanch.getSourceProducedQuantity()).isEqualByComparingTo("1571.19");
        assertThat(blanch.getSourceConsumedRatio()).isEqualByComparingTo("48.7013");
        assertThat(blanch.getInheritedRawEquivalentQuantity()).isEqualByComparingTo("701.2988");
        assertThat(blanch.getInputQuantity()).isEqualByComparingTo("765.19");
        assertThat(blanch.getStepYieldRate()).isEqualByComparingTo("79.0000");
        assertThat(blanch.getCumulativeYieldRate()).isEqualByComparingTo("86.1972");
    }

    @Test
    @DisplayName("YIELD-CARD-0C: same SKU multi-batch mixing keeps each source row's own raw input")
    void processSheetRows_sameSkuMultiBatchMixing_keepsEachSourceRawInput() throws Exception {
        ProcessSheetRow sourceA = sheetRow(1, 9301L, "CLK-W-A");
        ProcessSheetRow sourceB = sheetRow(1, 9302L, "CLK-W-B");
        ProcessSheetRow mixed = sheetRow(2, 9303L, "CLK-W-MIX");
        mixed.setProcessCode("hunhe");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(sourceA, sourceB, mixed));

        ProcessSheetRowRequest sourceAPayload = sheetPayload("100", "90");
        sourceAPayload.setProcessName("source-a");
        ProcessSheetRowRequest sourceBPayload = sheetPayload("50", "45");
        sourceBPayload.setProcessName("source-b");
        ProcessSheetRowRequest mixedPayload = sheetPayload("90", "80");
        mixedPayload.setProcessName("mix");
        ProcessSheetRowRequest.UpstreamRef upstreamA = new ProcessSheetRowRequest.UpstreamRef();
        upstreamA.setSourceBatchNumber("CLK-W-A");
        upstreamA.setFeedQuantityKg(new BigDecimal("45"));
        ProcessSheetRowRequest.UpstreamRef upstreamB = new ProcessSheetRowRequest.UpstreamRef();
        upstreamB.setSourceBatchNumber("CLK-W-B");
        upstreamB.setFeedQuantityKg(new BigDecimal("45"));
        mixedPayload.setUpstreamSources(List.of(upstreamA, upstreamB));

        when(objectMapper.readValue("payload-1", ProcessSheetRowRequest.class))
                .thenReturn(sourceAPayload)
                .thenReturn(sourceBPayload);
        when(objectMapper.readValue("payload-2", ProcessSheetRowRequest.class)).thenReturn(mixedPayload);
        when(productionBatchRepo.findAllById(any())).thenReturn(List.of());
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9301"))
                .thenReturn(Optional.of(materialWip(9301L, "CLK-W-A", "90", "10")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9302"))
                .thenReturn(Optional.of(materialWip(9302L, "CLK-W-B", "45", "20")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9303"))
                .thenReturn(Optional.of(materialWip(9303L, "CLK-W-MIX", "80", "25")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getInheritedRawEquivalentQuantity()).isEqualByComparingTo("100");
        assertThat(result.get(1).getInheritedRawEquivalentQuantity()).isEqualByComparingTo("50");

        ProcessSheetInventoryItem mix = result.get(2);
        assertThat(mix.getSourceBatchNumber()).isEqualTo("CLK-W-A, CLK-W-B");
        assertThat(mix.getFeedQuantity()).isEqualByComparingTo("90");
        assertThat(mix.getSourceProducedQuantity()).isEqualByComparingTo("135");
        assertThat(mix.getSourceConsumedRatio()).isEqualByComparingTo("66.6667");
        assertThat(mix.getInheritedRawEquivalentQuantity()).isEqualByComparingTo("100.0000");
        assertThat(mix.getInheritedCost()).isEqualByComparingTo("1350.0000");
        assertThat(mix.getCumulativeYieldRate()).isEqualByComparingTo("80.0000");
        assertThat(mix.getSourceBreakdowns()).hasSize(2);
        assertThat(mix.getSourceBreakdowns().get(0).getInheritedRawEquivalentQuantity())
                .isEqualByComparingTo("50.0000");
        assertThat(mix.getSourceBreakdowns().get(1).getInheritedRawEquivalentQuantity())
                .isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("YIELD-CARD-0D: 继承成本逐边 scale-2, addedCost 不出现负数/亚分级舍入噪音 (回归: 1.92 vs 1.9206)")
    void processSheetRows_inheritedCostScale2_addedCostNoSubCentNoise() throws Exception {
        // 复刻 prod F006 真实场景: 上游 A 单价 10.67, 本道 C 消耗 0.18 → 10.67×0.18 = 1.9206.
        // 修复前: inheritedCost 留全精度 1.9206, rowTotalCost = 1.92 (scale-2),
        //         addedCost = 1.92 - 1.9206 = -0.0006 (负数 + 亚分级噪音, 污染"0成本排查").
        // 修复后: inheritedCost 逐边 setScale(2)=1.92, addedCost = 0.00.
        ProcessSheetRow sourceA = sheetRow(1, 9501L, "CLK-W-DA");
        ProcessSheetRow midC = sheetRow(2, 9502L, "CLK-W-DC");
        midC.setProcessCode("chaoshui");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(sourceA, midC));

        ProcessSheetRowRequest sourceAPayload = sheetPayload("0.32", "0.30");
        sourceAPayload.setProcessName("xiuyou-a");
        ProcessSheetRowRequest midCPayload = sheetPayload("0.18", "0.16");
        midCPayload.setProcessName("chaoshui-c");
        ProcessSheetRowRequest.UpstreamRef upstreamA = new ProcessSheetRowRequest.UpstreamRef();
        upstreamA.setSourceBatchNumber("CLK-W-DA");
        upstreamA.setFeedQuantityKg(new BigDecimal("0.18"));
        midCPayload.setUpstreamSources(List.of(upstreamA));

        when(objectMapper.readValue("payload-1", ProcessSheetRowRequest.class)).thenReturn(sourceAPayload);
        when(objectMapper.readValue("payload-2", ProcessSheetRowRequest.class)).thenReturn(midCPayload);
        when(productionBatchRepo.findAllById(any())).thenReturn(List.of());
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9501"))
                .thenReturn(Optional.of(materialWip(9501L, "CLK-W-DA", "0.30", "10.67")));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9502"))
                .thenReturn(Optional.of(materialWip(9502L, "CLK-W-DC", "0.16", "12")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem c = result.get(1);
        assertThat(c.getBatchNumber()).isEqualTo("CLK-W-DC");
        // 逐边 scale-2: 10.67 × 0.18 = 1.9206 → 1.92 (与持久化 edgeCost setScale(2) 对齐)
        assertThat(c.getInheritedCost()).isEqualByComparingTo("1.92");
        assertThat(c.getRowTotalCost()).isEqualByComparingTo("1.92");
        // 继承成本永不超过行总成本
        assertThat(c.getInheritedCost().compareTo(c.getRowTotalCost()))
                .as("inheritedCost <= rowTotalCost")
                .isLessThanOrEqualTo(0);
        // addedCost 恒非负, 且无亚分级噪音 (本道无新增成本 → 0.00, 修复前为 -0.0006)
        assertThat(c.getAddedCost())
                .as("addedCost 不出现负数/舍入噪音")
                .isEqualByComparingTo("0");
        assertThat(c.getAddedCost().signum()).isGreaterThanOrEqualTo(0);
        assertThat(c.getSourceBreakdowns()).hasSize(1);
        assertThat(c.getSourceBreakdowns().get(0).getInheritedCost()).isEqualByComparingTo("1.92");
    }

    @Test
    @DisplayName("YIELD-CARD-0D: missing upstream source does not fabricate inherited raw or cumulative yield")
    void processSheetRows_missingUpstreamSource_doesNotFabricateYield() throws Exception {
        ProcessSheetRow row = sheetRow(2, 9401L, "CLK-W-MISSING-SOURCE");
        row.setProcessCode("hunhe");
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(row));

        ProcessSheetRowRequest payload = sheetPayload("30", "25");
        payload.setProcessName("mix");
        ProcessSheetRowRequest.UpstreamRef upstream = new ProcessSheetRowRequest.UpstreamRef();
        upstream.setSourceBatchNumber("CLK-W-NOT-FOUND");
        upstream.setFeedQuantityKg(new BigDecimal("30"));
        payload.setUpstreamSources(List.of(upstream));

        when(objectMapper.readValue("payload-2", ProcessSheetRowRequest.class)).thenReturn(payload);
        when(productionBatchRepo.findAllById(any())).thenReturn(List.of());
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9401"))
                .thenReturn(Optional.of(materialWip(9401L, "CLK-W-MISSING-SOURCE", "25", "25")));
        when(consumptionRepo.findByFactoryIdAndBatchId(eq(FACTORY), anyString())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(1);
        ProcessSheetInventoryItem item = result.get(0);
        assertThat(item.getStepYieldRate()).isEqualByComparingTo("83.3333");
        assertThat(item.getSourceBatchNumber()).isNull();
        assertThat(item.getFeedQuantity()).isNull();
        assertThat(item.getSourceConsumedRatio()).isNull();
        assertThat(item.getInheritedRawEquivalentQuantity()).isNull();
        assertThat(item.getCumulativeYieldRate()).isNull();
        assertThat(item.getSourceBreakdowns()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-1: 线性三道 (拆包100→修油90→焯水80)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-1: 三道 stepYield 正确; 焯水 step=88.89% cumulative=80%")
    void threeStepLinearChain_yieldRatesCorrect() {
        // Given: 一个批次三道工序
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        // WIP 行: 拆包=100kg, 修油=90kg, 焯水=80kg
        List<SemiFinishedInventory> wips = List.of(
                wip(BATCH_ID, 1, "100", "90", "10", "kg", null),
                wip(BATCH_ID, 2, "90",  "80", "10", "kg", null),
                wip(BATCH_ID, 3, "80",  "0",  "80", "kg", null)
        );
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(wips);

        // 首道报工: processOrder=1, inputQuantity=100
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));

        // 无 task (processName 不测) → 空列表
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        // 同单位 kg → gramsPerUnit 不需要 (但 productTypeId=null → 不查)
        when(productTypeRepo.findById(anyString())).thenReturn(Optional.empty());

        // When
        List<ProcessSheetInventoryItem> result =
                service.getInventoryYieldCard(FACTORY, PLAN_ID);

        // Then
        assertThat(result).hasSize(3);

        ProcessSheetInventoryItem step1 = result.get(0);
        assertThat(step1.getProcessOrder()).isEqualTo(1);
        // step1: produced=100 / firstInput=100 = 100%
        assertThat(step1.getStepYieldRate())
                .as("拆包 stepYieldRate = 100/100 = 100%")
                .isEqualByComparingTo("100.0000");
        assertThat(step1.getCumulativeYieldRate())
                .as("拆包 cumulativeYieldRate = 100/100 = 100%")
                .isEqualByComparingTo("100.0000");

        ProcessSheetInventoryItem step2 = result.get(1);
        assertThat(step2.getProcessOrder()).isEqualTo(2);
        // step2: produced=90, input=prev_produced=100 → 90%
        assertThat(step2.getStepYieldRate())
                .as("修油 stepYieldRate = 90/100 = 90%")
                .isEqualByComparingTo("90.0000");
        // cumulative: 90/100 = 90%
        assertThat(step2.getCumulativeYieldRate())
                .as("修油 cumulativeYieldRate = 90/100 = 90%")
                .isEqualByComparingTo("90.0000");

        ProcessSheetInventoryItem step3 = result.get(2);
        assertThat(step3.getProcessOrder()).isEqualTo(3);
        // step3: produced=80, input=prev_produced=90 → 88.8889%
        assertThat(step3.getStepYieldRate())
                .as("焯水 stepYieldRate = 80/90 ≈ 88.89%")
                .isEqualByComparingTo("88.8889");
        // cumulative: 80/100 = 80%
        assertThat(step3.getCumulativeYieldRate())
                .as("焯水 cumulativeYieldRate = 80/100 = 80%")
                .isEqualByComparingTo("80.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-2: 首道 step == cumulative
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-2: 首道 stepYieldRate == cumulativeYieldRate")
    void firstStep_stepEquals累ulative() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(wip(BATCH_ID, 1, "90", "0", "90", "kg", null)));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(1);
        ProcessSheetInventoryItem item = result.get(0);
        assertThat(item.getStepYieldRate()).isEqualByComparingTo(item.getCumulativeYieldRate());
        assertThat(item.getStepYieldRate()).isEqualByComparingTo("90.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-3: 跨单位无 gramsPerUnit → cumulativeYieldRate=null
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-3: 跨单位无折算系数 → cumulativeYieldRate=null (诚实)")
    void crossUnit_noGramsPerUnit_cumulativeNull() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        // 首道 kg, 末道 份
        List<SemiFinishedInventory> wips = List.of(
                wip(BATCH_ID, 1, "100", "0", "100", "kg", null),
                wip(BATCH_ID, 2, "120", "0", "120", "份",  "PT-BOX-001", null)
        );
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(wips);
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        // ProductType without gramsPerUnit → gramsPerUnit = null
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(null);
        when(productTypeRepo.findById("PT-BOX-001")).thenReturn(Optional.of(pt));

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem step2 = result.get(1);
        assertThat(step2.getCumulativeYieldRate())
                .as("跨单位无 gramsPerUnit → cumulativeYieldRate 诚实为 null")
                .isNull();
        // stepYieldRate is still computable (unit mismatch only affects cumulative)
        // step2 input = step1 producedQuantity = 100 (kg); step2 output = 120 份
        // Both are different units but stepYieldRate is just arithmetic ratio
        assertThat(step2.getStepYieldRate()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-4: 首道 inputQuantity=0 → null
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-4: 首道原料投入=0 → stepYieldRate=null, cumulativeYieldRate=null")
    void firstInputZero_bothNull() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(wip(BATCH_ID, 1, "100", "0", "100", "kg", null)));
        // inputQuantity = 0
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "0")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(1);
        ProcessSheetInventoryItem item = result.get(0);
        assertThat(item.getStepYieldRate())
                .as("首道投入=0 → stepYieldRate=null")
                .isNull();
        assertThat(item.getCumulativeYieldRate())
                .as("首道投入=0 → cumulativeYieldRate=null")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-5: 无批次 → 空列表
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-5: 无生产批次 → 空列表")
    void noBatches_returnsEmpty() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-6: 有批次无 WIP → 空列表
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-6: 有批次但无 WIP 行 → 空列表")
    void batchExistsNoWip_returnsEmpty() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-7: 有 gramsPerUnit 时 cumulativeYieldRate 正确折算
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-7: 跨单位有 gramsPerUnit=200 → cumulativeYieldRate 正确")
    void crossUnit_withGramsPerUnit_cumulativeCorrect() {
        // 100kg 原料 → 首道 100kg (kg) → 末道 450份 × 200g/份 = 90kg 等价
        // cumulative = 90/100 = 90%
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        SemiFinishedInventory step1 = wip(BATCH_ID, 1, "100", "0", "100", "kg", null);
        SemiFinishedInventory step2wip = SemiFinishedInventory.builder()
                .id(2L)
                .factoryId(FACTORY)
                .batchId(BATCH_ID)
                .intermediateBatchNo("WIP-42-2")
                .processOrder(2)
                .producedQuantity(new BigDecimal("450"))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("450"))
                .unit("份")
                .status("AVAILABLE")
                .productTypeId("PT-BOX-001")
                .build();

        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(step1, step2wip));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200")); // 200g/份
        when(productTypeRepo.findById("PT-BOX-001")).thenReturn(Optional.of(pt));

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem finalStep = result.get(1);
        // 450份 × 200g/1000 = 90kg; 90/100 = 90%
        assertThat(finalStep.getCumulativeYieldRate())
                .as("450份×200g=90kg / 100kg = 90%")
                .isEqualByComparingTo("90.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: 3-field wip overload (no taskId)
    // ─────────────────────────────────────────────────────────────

    private SemiFinishedInventory wip(long batchId, int order, String produced, String consumed,
                                      String available, String unit, String productTypeId,
                                      Long taskId) {
        return SemiFinishedInventory.builder()
                .id((long) order)
                .factoryId(FACTORY)
                .batchId(batchId)
                .intermediateBatchNo("WIP-" + batchId + "-" + order)
                .processOrder(order)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit(unit)
                .status("AVAILABLE")
                .productTypeId(productTypeId)
                .sourceWorkProcessTaskId(taskId)
                .build();
    }
}
