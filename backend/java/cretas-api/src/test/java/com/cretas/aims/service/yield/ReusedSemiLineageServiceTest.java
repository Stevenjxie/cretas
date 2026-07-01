package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ①d 复用半成品前段反查/血缘服务单测 — 覆盖:
 * (a) 全量复用 → 前段接续 = 来源计划全部前段;
 * (b) 部分领用 → 前段按比例 (只领 40% 只接 40%);
 * (c) provenance 缺失 (无 SFI 行) → honest-null + note, 不伪造;
 * (d) 无复用 → empty。
 */
@ExtendWith(MockitoExtension.class)
class ReusedSemiLineageServiceTest {

    @Mock ProcessSheetRowRepository rowRepository;
    @Mock SemiFinishedInventoryRepository wipRepository;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProcessSheetService processSheetService;

    ReusedSemiLineageService service;
    final ObjectMapper mapper = new ObjectMapper();

    private static final String X = "CLK-SEMI-plan1234-pt567890";  // 被复用 SFI 锚 (planId8=plan1234)

    @BeforeEach
    void setUp() {
        service = new ReusedSemiLineageService(rowRepository, wipRepository,
                productionBatchRepository, productionPlanRepository, processSheetService, mapper);
    }

    /** P2 的一行: 复用外部 SFI 批次 X, 领用 feedKg (semiFinished=true)。 */
    private ProcessSheetRow p2RowReusing(String sourceBatch, String feedKg) throws Exception {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r1");
        req.setProcessCode("gunrou");
        req.setProcessOrder(1);
        req.setProductTypeId("PT1");
        req.setOutputQuantity(new BigDecimal("5"));
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(sourceBatch);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(true);
        req.setUpstreamSources(List.of(ref));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchNumber("CLK-B-p2-1");
        row.setProcessOrder(1);
        row.setRowStatus(ProcessSheetRow.STATUS_SAVED_SFI);
        row.setRowPayload(mapper.writeValueAsString(req));
        return row;
    }

    private SemiFinishedInventory sfiAnchor(String produced) {
        return SemiFinishedInventory.builder()
                .factoryId("F006").intermediateBatchNo(X).productTypeId("PT1")
                .producedQuantity(new BigDecimal(produced))
                .batchId(null)  // 小结锚: 无 batchId → 走 planId8 前缀反查
                .build();
    }

    /** 来源计划 P1 的出成率卡: 首道原料投入 rawKg。 */
    private List<ProcessSheetInventoryItem> p1YieldCard(String rawKg) {
        return List.of(
                ProcessSheetInventoryItem.builder().processOrder(1).inputQuantity(new BigDecimal(rawKg))
                        .produced(new BigDecimal(rawKg)).build(),
                ProcessSheetInventoryItem.builder().processOrder(2).inputQuantity(null)
                        .produced(new BigDecimal("6.0")).build());
    }

    private void stubSourcePlanResolves() {
        when(wipRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("F006", X))
                .thenReturn(Optional.of(sfiAnchor("6.0")));
        ProductionPlan p1 = new ProductionPlan();
        p1.setId("plan1234-full-uuid");
        p1.setFactoryId("F006");
        when(productionPlanRepository.findByFactoryIdAndIdStartingWith("F006", "plan1234"))
                .thenReturn(List.of(p1));
        when(processSheetService.getInventoryYieldCard("F006", "plan1234-full-uuid"))
                .thenReturn(p1YieldCard("8.0"));
    }

    @Test
    void fullReuse_chainsEntireSourceFrontRaw() throws Exception {
        // 领 6.0kg / X 产出 6.0kg → 领用比例 100% → 前段接续 = P1 全部前段 8.0kg。
        when(rowRepository.findByFactoryIdAndPlanId("F006", "P2"))
                .thenReturn(List.of(p2RowReusing(X, "6.0")));
        stubSourcePlanResolves();

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "P2");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("8.0000");
        assertThat(r.isHasMissingProvenance()).isFalse();
        assertThat(r.getNote()).isNull();
        assertThat(r.getLineages()).hasSize(1);
        ProductionSummaryDTO_line line = ProductionSummaryDTO_line.of(r);
        assertThat(line.included).isTrue();
        assertThat(line.frontRaw).isEqualByComparingTo("8.0000");
        assertThat(line.sourcePlanId).isEqualTo("plan1234-full-uuid");  // 血缘: 来源计划可见
    }

    @Test
    void partialDraw_chainsProportionalFrontRaw() throws Exception {
        // 领 3.0kg / X 产出 6.0kg → 领用比例 50% → 前段接续 = 8.0 × 3.0/6.0 = 4.0kg。
        when(rowRepository.findByFactoryIdAndPlanId("F006", "P2"))
                .thenReturn(List.of(p2RowReusing(X, "3.0")));
        stubSourcePlanResolves();

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "P2");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("4.0000");
        ProductionSummaryDTO_line line = ProductionSummaryDTO_line.of(r);
        assertThat(line.included).isTrue();
        assertThat(line.frontRaw).isEqualByComparingTo("4.0000");
    }

    @Test
    void missingSfiRow_honestNull_noFabrication() throws Exception {
        // SFI 行缺失 → 前段无法反查 → honest-null + note 点名, 不计入分母, 不伪造。
        when(rowRepository.findByFactoryIdAndPlanId("F006", "P2"))
                .thenReturn(List.of(p2RowReusing(X, "6.0")));
        lenient().when(wipRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("F006", X))
                .thenReturn(Optional.empty());

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "P2");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("0");
        assertThat(r.isHasMissingProvenance()).isTrue();
        assertThat(r.getNote()).contains(X).contains("前段数据缺失");
        ProductionSummaryDTO_line line = ProductionSummaryDTO_line.of(r);
        assertThat(line.included).isFalse();
        assertThat(line.frontRaw).isNull();  // 诚实 null, 不伪造前段数
    }

    @Test
    void noReuse_returnsEmpty() {
        when(rowRepository.findByFactoryIdAndPlanId("F006", "P2")).thenReturn(List.of());

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "P2");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("0");
        assertThat(r.getLineages()).isEmpty();
        assertThat(r.isHasMissingProvenance()).isFalse();
        assertThat(r.getNote()).isNull();
    }

    /** 小工具: 取第一条血缘明细的关键字段。 */
    private static final class ProductionSummaryDTO_line {
        final boolean included;
        final BigDecimal frontRaw;
        final String sourcePlanId;
        ProductionSummaryDTO_line(boolean included, BigDecimal frontRaw, String sourcePlanId) {
            this.included = included; this.frontRaw = frontRaw; this.sourcePlanId = sourcePlanId;
        }
        static ProductionSummaryDTO_line of(ReusedSemiLineageService.ReusedFrontLineage r) {
            var l = r.getLineages().get(0);
            return new ProductionSummaryDTO_line(l.isFrontRawIncluded(), l.getFrontRawInput(), l.getSourcePlanId());
        }
    }
}
