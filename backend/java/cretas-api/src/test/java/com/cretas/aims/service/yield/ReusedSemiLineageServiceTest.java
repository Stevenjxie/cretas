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
        // 递归: 来源计划自身也会被查复用行 (P1 无复用 → 空); 1 跳链结果与递归前逐字节一致。
        lenient().when(rowRepository.findByFactoryIdAndPlanId("F006", "plan1234-full-uuid"))
                .thenReturn(List.of());
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

    // ─────────────────────────── 递归多跳 (全链前段) ───────────────────────────

    /**
     * 一个计划节点的出成率卡: 首道自身原料 ownRaw ("0" → 纯复用计划, 无自身前段)。
     * 同时默认桩其复用行为空 (leaf); 有复用的计划由随后的 {@link #stubReuseRows} 覆盖 (Mockito 后桩优先)。
     */
    private void stubPlanNode(String planFullId, String ownRaw) {
        lenient().when(processSheetService.getInventoryYieldCard("F006", planFullId))
                .thenReturn(List.of(ProcessSheetInventoryItem.builder()
                        .processOrder(1).inputQuantity(new BigDecimal(ownRaw))
                        .produced(new BigDecimal(ownRaw)).build()));
        lenient().when(rowRepository.findByFactoryIdAndPlanId("F006", planFullId))
                .thenReturn(List.of());
    }

    /** 一个 SFI 锚 (由 producerFullId 产出): 领它 → 反查到 producerFullId (prefix 唯一命中)。 */
    private void stubAnchor(String anchor, String produced, String producerFullId, String prefix) {
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .factoryId("F006").intermediateBatchNo(anchor).productTypeId("PT1")
                .producedQuantity(new BigDecimal(produced)).batchId(null).build();
        lenient().when(wipRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("F006", anchor))
                .thenReturn(Optional.of(sfi));
        ProductionPlan p = new ProductionPlan();
        p.setId(producerFullId);
        p.setFactoryId("F006");
        lenient().when(productionPlanRepository.findByFactoryIdAndIdStartingWith("F006", prefix))
                .thenReturn(List.of(p));
    }

    /** planFullId 复用给定上游行 (可多行 → 钻石: 同一计划多路径领同一上游)。 */
    private void stubReuseRows(String planFullId, ProcessSheetRow... rows) {
        lenient().when(rowRepository.findByFactoryIdAndPlanId("F006", planFullId))
                .thenReturn(List.of(rows));
    }

    @Test
    void twoHop_deepFrontRawPulledProportionally() throws Exception {
        // PC 复用 P1 的半成品; P1 自身领料 2.0kg 且复用了 P0 全部产出 (P0 前段 8.0kg)。
        //   P1 总前段 = 2.0 (自身) + 8.0 (P0 递归) = 10.0。 PC 领 P1 的 50% → 前段接续 = 10.0 × 5/10 = 5.0。
        //   (旧一层实现: 只接 P1 自身 2.0 × 0.5 = 1.0, 漏了 P0 的 8.0 深层前段。)
        String p1 = "planaa11-uuid", p0 = "planaa00-uuid";
        String a1 = "CLK-SEMI-planaa11-ptaaaaaa";   // P1 产出锚
        String a0 = "CLK-SEMI-planaa00-ptbbbbbb";   // P0 产出锚
        when(rowRepository.findByFactoryIdAndPlanId("F006", "PC"))
                .thenReturn(List.of(p2RowReusing(a1, "5.0")));   // PC 领 P1 5.0 / 产出 10.0 = 50%
        stubAnchor(a1, "10.0", p1, "planaa11");
        stubPlanNode(p1, "2.0");
        stubReuseRows(p1, p2RowReusing(a0, "8.0"));               // P1 领 P0 8.0 / 产出 8.0 = 100%
        stubAnchor(a0, "8.0", p0, "planaa00");
        stubPlanNode(p0, "8.0");

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "PC");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("5.0000");  // (2.0+8.0)×0.5
        assertThat(r.isHasMissingProvenance()).isFalse();
        assertThat(r.getNote()).isNull();
        assertThat(r.getLineages().get(0).isFrontRawIncluded()).isTrue();
    }

    @Test
    void threeHop_ratiosCompoundDownChain() throws Exception {
        // PC → P2 → P1 → P0, 各跳纯复用 (自身前段 0), 逐跳按比例复合:
        //   P0 前段 100; P1 领 P0 50/100=50% → 50; P2 领 P1 25/50=50% → 25; PC 领 P2 25/25=100% → 25。
        String p2 = "planbb22-uuid", p1 = "planbb11-uuid", p0 = "planbb00-uuid";
        String b2 = "CLK-SEMI-planbb22-ptc22222", b1 = "CLK-SEMI-planbb11-ptc11111",
                b0 = "CLK-SEMI-planbb00-ptc00000";
        when(rowRepository.findByFactoryIdAndPlanId("F006", "PC3"))
                .thenReturn(List.of(p2RowReusing(b2, "25.0")));
        stubAnchor(b2, "25.0", p2, "planbb22");
        stubPlanNode(p2, "0");
        stubReuseRows(p2, p2RowReusing(b1, "25.0"));
        stubAnchor(b1, "50.0", p1, "planbb11");
        stubPlanNode(p1, "0");
        stubReuseRows(p1, p2RowReusing(b0, "50.0"));
        stubAnchor(b0, "100.0", p0, "planbb00");
        stubPlanNode(p0, "100.0");

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "PC3");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("25.0000");  // 100×0.5×0.5×1.0
        assertThat(r.isHasMissingProvenance()).isFalse();
    }

    @Test
    void cycle_guarded_noInfiniteLoop_honestNote() throws Exception {
        // 环: 入口 A 复用 B; B 复用 A (回指入口) → 路径守卫检出环, 该分支终止, B 自身前段 3.0 仍计入。
        String a = "planCYaa-uuid", b = "planCYbb-uuid";
        String anchorB = "CLK-SEMI-planCYbb-ptbbbbbb";  // B 产出锚 (A 领它)
        String anchorA = "CLK-SEMI-planCYaa-ptaaaaaa";  // A 产出锚 (B 领它 → 环)
        when(rowRepository.findByFactoryIdAndPlanId("F006", a))
                .thenReturn(List.of(p2RowReusing(anchorB, "6.0")));
        stubAnchor(anchorB, "6.0", b, "planCYbb");
        stubPlanNode(b, "3.0");
        stubReuseRows(b, p2RowReusing(anchorA, "6.0"));
        stubAnchor(anchorA, "6.0", a, "planCYaa");
        // A 节点无需桩: computePlanFrontRaw(A) 在环检测处短路, 不读其卡。

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", a);

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("3.0000");  // 仅 B 自身, 环分支丢弃
        assertThat(r.isHasMissingProvenance()).isTrue();
        assertThat(r.getNote()).contains("循环");
        assertThat(r.getLineages().get(0).isFrontRawIncluded()).isTrue();          // 已知部分仍计入
    }

    @Test
    void diamond_pathScopedVisited_doesNotWronglyPrune() throws Exception {
        // 钻石: P1 经两条路径 (两个 SFI 批次) 复用同一上游 P0。
        //   路径作用域 visited (出栈移除) → 两条都计; 若用全局集会误剪第二条 → 少计一半。
        String p1 = "plandd11-uuid", p0 = "plandd00-uuid";
        String m1 = "CLK-SEMI-plandd11-pte11111";                 // P1 产出锚
        String g1 = "CLK-SEMI-plandd00-ptf00001";                 // P0 产出锚 (路径1)
        String g2 = "CLK-SEMI-plandd00-ptf00002";                 // P0 产出锚 (路径2, 同 producer 不同批)
        when(rowRepository.findByFactoryIdAndPlanId("F006", "PCD"))
                .thenReturn(List.of(p2RowReusing(m1, "10.0")));
        stubAnchor(m1, "10.0", p1, "plandd11");
        stubPlanNode(p1, "0");
        stubReuseRows(p1, p2RowReusing(g1, "5.0"), p2RowReusing(g2, "5.0"));
        stubAnchor(g1, "10.0", p0, "plandd00");
        stubAnchor(g2, "10.0", p0, "plandd00");
        stubPlanNode(p0, "10.0");

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "PCD");

        // P1 总前段 = 10×5/10 + 10×5/10 = 5 + 5 = 10 (两路径都计). PCD 领 100% → 10.0。
        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("10.0000");
        assertThat(r.isHasMissingProvenance()).isFalse();
    }

    @Test
    void missingMidHop_honestNull_partialChainStillCounts() throws Exception {
        // 中间跳 provenance 缺失: PC 复用 P1 (自身前段 4.0); P1 复用一个 SFI 行缺失的 ghost 上游。
        //   → P1 自身 4.0 仍计入, ghost 深层诚实不接 + note 点名, 绝不伪造。
        String p1 = "planmm11-uuid";
        String m = "CLK-SEMI-planmm11-ptg11111";                  // P1 产出锚
        String ghost = "CLK-SEMI-planmm00-ptg00000";              // 上游锚但 SFI 行缺失
        when(rowRepository.findByFactoryIdAndPlanId("F006", "PCM"))
                .thenReturn(List.of(p2RowReusing(m, "5.0")));
        stubAnchor(m, "5.0", p1, "planmm11");
        stubPlanNode(p1, "4.0");
        stubReuseRows(p1, p2RowReusing(ghost, "5.0"));
        lenient().when(wipRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("F006", ghost))
                .thenReturn(Optional.empty());                     // ghost SFI 行缺失

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "PCM");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("4.0000");  // 仅 P1 自身前段
        assertThat(r.isHasMissingProvenance()).isTrue();
        assertThat(r.getNote()).contains("上游").contains("缺失");
        assertThat(r.getLineages().get(0).isFrontRawIncluded()).isTrue();          // 部分已知仍计入
    }

    @Test
    void depthCap_stopsBeyondMaxDepth_honestNote() throws Exception {
        // 病态深链: 入口 → S1(自身前段 5.0) → S2 → ... 每跳纯复用. 超过深度上限的更深前段被截断 + note。
        //   S1 自身 5.0 仍计入; 深层因超上限截断 → hasMissing + "最大深度" note (防病态数据死循环/爆栈)。
        int n = 12;  // > MAX_DEPTH(10)
        for (int i = 1; i <= n; i++) {
            String nn = String.format("%02d", i);
            String planFull = "pchain" + nn + "-uuid";
            String anchor = "CLK-SEMI-pchain" + nn + "-ptz" + nn + "z";
            stubAnchor(anchor, "10", planFull, "pchain" + nn);
            stubPlanNode(planFull, i == 1 ? "5.0" : "0");
            if (i < n) {
                String nextAnchor = "CLK-SEMI-pchain" + String.format("%02d", i + 1) + "-ptz"
                        + String.format("%02d", i + 1) + "z";
                stubReuseRows(planFull, p2RowReusing(nextAnchor, "10"));
            }
        }
        when(rowRepository.findByFactoryIdAndPlanId("F006", "PCHAINTOP"))
                .thenReturn(List.of(p2RowReusing("CLK-SEMI-pchain01-ptz01z", "10")));

        ReusedSemiLineageService.ReusedFrontLineage r = service.resolve("F006", "PCHAINTOP");

        assertThat(r.getTotalIncludedFrontRaw()).isEqualByComparingTo("5.0000");  // 仅 S1 自身, 深层截断
        assertThat(r.isHasMissingProvenance()).isTrue();
        assertThat(r.getNote()).contains("最大深度");
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
