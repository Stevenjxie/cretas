package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ①d 双计修复 — <b>非-mock 集成测试</b> (reviewer 强制)。
 *
 * <p>不 hand-set totalRawInput、不 mock getInventoryYieldCard: 用<b>真实</b> {@link ProcessSheetServiceImpl}
 * 从真实 process_sheet_rows payload 组装出成率卡, 再喂进真实 {@link ProductionSummaryService}, 断言
 * {@code yieldRawDenominator == frontRaw(X)} (而非旧 bug 的 {@code usedWeight + frontRaw(X)} 双计)。
 *
 * <p>只 mock 仓储边界 (rowRepo 返回真实行) + {@link ReusedSemiLineageService} (前段接续 8.0 已在专测验证);
 * 关键的"卡组装 → totalRawInput"链路全走真实生产代码, 这正是原双计 bug 逃过 mock 单测的地方。
 */
@ExtendWith(MockitoExtension.class)
class ProductionSummaryReuseIntegrationTest {

    // ProcessSheetServiceImpl 依赖 (仅 rowRepo 需显式桩; 其余默认 empty/empty-Optional)
    @Mock ClerkProcessEntryService clerkService;
    @Mock ProcessSheetRowRepository rowRepo;
    @Mock MaterialBatchRepository materialBatchRepo;
    @Mock ProductionBatchRepository productionBatchRepo;
    @Mock MaterialConsumptionRepository consumptionRepo;
    @Mock ProductionReportRepository reportRepo;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock SemiFinishedInventoryRepository wipRepo;
    @Mock WorkProcessTaskRepository taskRepo;
    @Mock WorkProcessRepository processRepo;
    @Mock ProductWorkProcessRepository productWorkProcessRepo;
    @Mock ProductTypeRepository productTypeRepo;

    // ProductionSummaryService 依赖
    @Mock ProductionBatchRepository summaryBatchRepo;
    @Mock OrderCostBreakdownService orderCostBreakdownService;
    @Mock ReusedSemiLineageService reusedSemiLineageService;

    final ObjectMapper mapper = new ObjectMapper();
    ProcessSheetServiceImpl processSheetService;
    ProductionSummaryService summaryService;

    private static final String X = "CLK-SEMI-plan1234-pt567890";

    @BeforeEach
    void setUp() {
        processSheetService = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo, consumptionRepo,
                reportRepo, productionPlanRepository, changeLogRepo, mapper, wipRepo, taskRepo,
                processRepo, productWorkProcessRepo, productTypeRepo);
        summaryService = new ProductionSummaryService(
                processSheetService, summaryBatchRepo, orderCostBreakdownService, reusedSemiLineageService);
    }

    /** 一行真实 process_sheet_row (materialized → batchId 非空 → 进出成率卡)。 */
    private ProcessSheetRow row(long batchId, int order, boolean finished, String inputQty,
                                String outputQty, String productWeight,
                                List<ProcessSheetRowRequest.RawInput> rawInputs,
                                String sfiFeedKg) throws Exception {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r" + batchId);
        req.setProcessCode(finished ? "qitiao" : "gunrou");
        req.setProcessName(finished ? "气调成品" : "滚揉");
        req.setProcessOrder(order);
        req.setProductTypeId("PT1");
        req.setUnit("kg");
        req.setFinished(finished);
        req.setInputQuantity(inputQty == null ? null : new BigDecimal(inputQty));
        req.setOutputQuantity(new BigDecimal(outputQty));
        if (productWeight != null) {
            req.setProductWeight(new BigDecimal(productWeight));
        }
        req.setRawMaterialInputs(rawInputs);
        if (sfiFeedKg != null) {
            ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
            ref.setSourceBatchNumber(X);
            ref.setFeedQuantityKg(new BigDecimal(sfiFeedKg));
            ref.setSemiFinished(true);
            req.setUpstreamSources(List.of(ref));
        }

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(batchId);
        row.setBatchNumber("CLK-B-" + batchId);
        row.setProcessOrder(order);
        row.setProcessCode(req.getProcessCode());
        row.setRowPayload(mapper.writeValueAsString(req));
        return row;
    }

    private ProcessSheetRowRequest.RawInput rawInput(String batchId, String qty) {
        ProcessSheetRowRequest.RawInput ri = new ProcessSheetRowRequest.RawInput();
        ri.setMaterialBatchId(batchId);
        ri.setQuantity(new BigDecimal(qty));
        return ri;
    }

    private void stubFrontRaw(String frontRaw) {
        lenient().when(reusedSemiLineageService.resolve("F006", "P2")).thenReturn(
                ReusedSemiLineageService.ReusedFrontLineage.builder()
                        .totalIncludedFrontRaw(new BigDecimal(frontRaw))
                        .lineages(List.of(ProductionSummaryDTO.ReusedSemiLineage.builder()
                                .sourceBatchNumber(X).drawnQuantity(new BigDecimal("6.0"))
                                .sourceProducedQuantity(new BigDecimal("6.0"))
                                .frontRawInput(new BigDecimal(frontRaw)).frontRawIncluded(true).build()))
                        .hasMissingProvenance(false).note(null).build());
    }

    @Test
    void flagship_externalSfiToFinishedRow_denominatorIsFrontRawOnly_notDoubled() throws Exception {
        // 气调成品 由外部 SFI X 投料 6.0kg 起 (无 rawMaterialInputs); productWeight 0.7kg。
        // 旧 bug: totalRawInput = inputQuantity = 6.0 (=usedWeight) → 分母 6.0+8.0 = 14.0 (X 双计)。
        // 修复后: freshRawInput = 0 → totalRawInput = 0 → 分母 = frontRaw(X) = 8.0。
        when(rowRepo.findByFactoryIdAndPlanId("F006", "P2")).thenReturn(List.of(
                row(1L, 1, true, "6.0", "10", "0.7", null, "6.0")));
        stubFrontRaw("8.0");

        ProductionSummaryDTO dto = summaryService.computeSummary("F006", "P2", true);

        // 先证 bug 前提: 真实卡里该行 inputQuantity=6.0 但 freshRawInput=0
        List<ProcessSheetInventoryItem> card = processSheetService.getInventoryYieldCard("F006", "P2");
        assertThat(card).hasSize(1);
        assertThat(card.get(0).getInputQuantity()).isEqualByComparingTo("6.0");
        assertThat(card.get(0).getFreshRawInput()).isEqualByComparingTo("0");

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("0");          // 新鲜原料 0 (纯 SFI 投料)
        assertThat(dto.getReusedFrontRawInput()).isEqualByComparingTo("8.0");
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("8.0");   // == frontRaw(X), 非 14.0
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("8.75");        // 0.7*100/8.0 (非 0.7*100/14.0=5.00)
    }

    @Test
    void mixedBatch_freshRawPlusExternalSfi_denominatorIsFreshRawPlusFrontRaw() throws Exception {
        // 混批首道: 新鲜原料 3.0kg (rawMaterialInputs) + 外部 SFI X 投料 6.0kg; inputQuantity=9.0 (原料+投料)。
        // 修复后: freshRawInput = 3.0 (仅原料) → 分母 = 3.0 + frontRaw(X) 8.0 = 11.0 (非 9.0+8.0=17.0)。
        when(rowRepo.findByFactoryIdAndPlanId("F006", "P2")).thenReturn(List.of(
                row(1L, 1, true, "9.0", "10", "0.7", List.of(rawInput("MB1", "3.0")), "6.0")));
        stubFrontRaw("8.0");

        ProductionSummaryDTO dto = summaryService.computeSummary("F006", "P2", true);

        List<ProcessSheetInventoryItem> card = processSheetService.getInventoryYieldCard("F006", "P2");
        assertThat(card.get(0).getInputQuantity()).isEqualByComparingTo("9.0");
        assertThat(card.get(0).getFreshRawInput()).isEqualByComparingTo("3.0");  // 仅新鲜原料

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("3.0");
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("11.0");   // 3.0 + 8.0, 非 17.0
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("6.36");         // 0.7*100/11.0
    }

    @Test
    void normalPlan_leadingRawInput_denominatorUnchanged_byteIdentical() throws Exception {
        // 正常领料首道 (无 SFI 投料): rawMaterialInputs 3.0 == inputQuantity 3.0; 无复用。
        // freshRawInput = inputQuantity = 3.0 → 分母 = 3.0 (与 ①d/双计修复前逐字节一致)。
        when(rowRepo.findByFactoryIdAndPlanId("F006", "P2")).thenReturn(List.of(
                row(1L, 1, true, "3.0", "10", "0.7", List.of(rawInput("MB1", "3.0")), null)));
        lenient().when(reusedSemiLineageService.resolve("F006", "P2"))
                .thenReturn(ReusedSemiLineageService.ReusedFrontLineage.empty());

        ProductionSummaryDTO dto = summaryService.computeSummary("F006", "P2", true);

        List<ProcessSheetInventoryItem> card = processSheetService.getInventoryYieldCard("F006", "P2");
        assertThat(card.get(0).getFreshRawInput()).isEqualByComparingTo("3.0");
        assertThat(card.get(0).getInputQuantity()).isEqualByComparingTo("3.0"); // 领料道两者相等

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("3.0");
        assertThat(dto.getReusedFrontRawInput()).isEqualByComparingTo("0");
        assertThat(dto.getYieldRawDenominator()).isEqualByComparingTo("3.0");
        assertThat(dto.getRealYieldRate()).isEqualByComparingTo("23.33");        // 0.7*100/3.0
    }
}
