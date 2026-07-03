package com.cretas.aims.service.yield;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.InterimSettleReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤销小结 (interim-settle reversal) 服务级 TDD — 固化"逆转每笔库存动作 + 下游守卫 loud-fail + 幂等原子"。
 *
 * <p>逆转依据 = settle 写入的 {@code summary.reversalDetail} 逐笔明细 (此处直接手搓 detail 模拟 settle 产物)。
 * 各底层动作 (reverseClerkOutput / reverseInterimCreate / restoreClerkSemi / restoreForFeed) mock 掉,
 * 验证编排调用参数 + 原料还回 + 清行戳 + 硬删小结 + 顺序 (下游守卫先跑, 抛则后续不发生)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterimSettleReversalServiceTest - 撤销小结 逆转 + 下游守卫 + 幂等")
class InterimSettleReversalServiceTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN1";
    private static final String ANCHOR = "CLK-SEMI-PLAN1234-PT123456";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionInterimSettlementRepository settlementRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProcessSheetRowRepository rowRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private InterimSettleReversalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterimSettleReversalServiceImpl(
                planRepository, settlementRepository, consumptionRepository,
                materialBatchRepository, rowRepository, wipInventoryService, finishedGoodsFeedService);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        // 默认无原料/行 (个别测试覆写)。原料还回按 (factory, production_batch_id ∈ 本计划各道) 反查 (mirror 扣减侧)。
        when(rowRepository.findByFactoryIdAndPlanId(any(), any()))
                .thenReturn(new ArrayList<>());
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(rowRepository.findByFactoryIdAndPlanIdAndInterimSettledAt(any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(consumptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── (a) SFI-output 行撤销 → reverseClerkOutput 逆转 + 清行戳 + 硬删 ──
    @Test
    @DisplayName("(a) 撤销含 SFI IN 的小结 → reverseClerkOutput(anchor, net, totalCost); 行清戳; 硬删小结")
    void reverseSfiInRow() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(
                List.of(sfiIn(ANCHOR, "60", "900")),   // net 60 @ totalCost 900 (unitCost 15)
                List.of(), List.of(), List.of(), List.of());
        ProductionInterimSettlement s = settlement("s1", 1, postedAt, detail);
        stubTarget(1, s);

        ProcessSheetRow settledRow = settledRow(3L, postedAt);
        when(rowRepository.findByFactoryIdAndPlanIdAndInterimSettledAt(FACTORY, PLAN_ID, postedAt))
                .thenReturn(new ArrayList<>(List.of(settledRow)));

        Map<String, Object> r = service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L);

        verify(wipInventoryService, times(1)).reverseClerkOutput(
                eq(FACTORY), eq(ANCHOR), eq(new BigDecimal("60")), eq(new BigDecimal("900")), eq(7L));
        // 行恢复未结 (可再编辑)
        assertThat(settledRow.getInterimSettledAt()).isNull();
        // 硬删小结 (物理释放 seq)
        verify(settlementRepository, times(1)).hardDeleteById("s1");
        assertThat(r.get("reversedSessionSeq")).isEqualTo(1);
        assertThat(r.get("sfiInReversed")).isEqualTo(1);
        assertThat(r.get("rowsUnstamped")).isEqualTo(1);
    }

    // ── (a') totalCost null (诚实): 当时成本未知 → reverseClerkOutput 收 null ──
    @Test
    @DisplayName("(a') SFI IN 当时成本未知 → reverseClerkOutput totalCost=null (诚实, 不减 accumulatedCost)")
    void reverseSfiInNullCost() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(
                List.of(sfiIn(ANCHOR, "40", null)),
                List.of(), List.of(), List.of(), List.of());
        stubTarget(1, settlement("s1", 1, postedAt, detail));

        service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L);

        verify(wipInventoryService, times(1)).reverseClerkOutput(
                eq(FACTORY), eq(ANCHOR), eq(new BigDecimal("40")), eq(null), eq(7L));
    }

    // ── (b) FG 成品行撤销 → reverseInterimCreate ──
    @Test
    @DisplayName("(b) 撤销含 FG 入库的小结 → reverseInterimCreate(batchNumber, qty); 硬删小结")
    void reverseFgRow() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(
                List.of(),
                List.of(fgCreated("FG-PP-001-S2", "9")),
                List.of(), List.of(), List.of());
        stubTarget(2, settlement("s2", 2, postedAt, detail));

        Map<String, Object> r = service.reverseInterimSettle(FACTORY, PLAN_ID, 2, 7L);

        verify(finishedGoodsFeedService, times(1))
                .reverseInterimCreate(eq(FACTORY), eq("FG-PP-001-S2"), eq(new BigDecimal("9")), eq(7L));
        assertThat(r.get("fgCreatedReversed")).isEqualTo(1);
        verify(settlementRepository, times(1)).hardDeleteById("s2");
    }

    // ── (c) 还回被扣的上游 SFI/FG + 原料 ──
    @Test
    @DisplayName("(c) 撤销还回消耗: restoreClerkSemi(严格+anchor) + restoreForFeed + 原料 usedQuantity 还回 + 清消耗戳")
    void reverseRestoresConsumedUpstreamAndRaw() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(
                List.of(),
                List.of(),
                List.of(qty("batchNo", "SFI-STANDING-1", "30")),  // strict SFI feed 还回
                List.of(qty("anchor", ANCHOR, "60")),             // priorStocked anchor 还回
                List.of(qty("batchNo", "FG-STANDING-1", "20")));  // FG feed 还回
        stubTarget(2, settlement("s2", 2, postedAt, detail));

        // 原料: 一笔 RB1 消耗 100 (小结时 usedQuantity 已 +100 → 现 150), 挂在本计划某道 WIP 批次 555。
        //   撤销侧按 (factory, production_batch_id ∈ 本计划各道 batchId) 反查 (mirror 扣减侧)。
        ProcessSheetRow matRow = settledRow(9L, postedAt);
        matRow.setBatchId(555L);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(new ArrayList<>(List.of(matRow)));
        MaterialConsumption mc = consumption("RB1", new BigDecimal("100"), postedAt);
        mc.setProductionBatchId(555L);
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
                eq(FACTORY), eq(List.of(555L)), eq(postedAt)))
                .thenReturn(new ArrayList<>(List.of(mc)));
        MaterialBatch rb1 = new MaterialBatch();
        rb1.setId("RB1");
        rb1.setFactoryId(FACTORY);
        rb1.setReceiptQuantity(new BigDecimal("200"));
        rb1.setUsedQuantity(new BigDecimal("150"));
        rb1.setReservedQuantity(BigDecimal.ZERO);
        rb1.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("RB1", FACTORY)).thenReturn(Optional.of(rb1));

        Map<String, Object> r = service.reverseInterimSettle(FACTORY, PLAN_ID, 2, 7L);

        // 上游还回
        verify(wipInventoryService, times(1)).restoreClerkSemi(eq(FACTORY), eq("SFI-STANDING-1"), eq(new BigDecimal("30")), eq(7L));
        verify(wipInventoryService, times(1)).restoreClerkSemi(eq(FACTORY), eq(ANCHOR), eq(new BigDecimal("60")), eq(7L));
        verify(finishedGoodsFeedService, times(1)).restoreForFeed(eq(FACTORY), eq("FG-STANDING-1"), eq(new BigDecimal("20")), eq(7L));
        assertThat(r.get("sfiOutRestored")).isEqualTo(2);
        assertThat(r.get("fgFeedRestored")).isEqualTo(1);
        // 原料: usedQuantity 150 → 50 (还回 100); 有余量 → USED_UP 恢复 AVAILABLE; 消耗戳清空
        assertThat(rb1.getUsedQuantity()).isEqualByComparingTo("50");
        assertThat(rb1.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(mc.getInterimSettledAt()).isNull();
        assertThat(r.get("rawRestored")).isEqualTo(1);
    }

    // ── (f) 🔒🔒 幻库存回归 (mirror #1167): 逐工序首/中间道 raw 消耗 production_plan_id=null (只有
    //         production_batch_id 有值) → 撤销必须按 production_batch_id 反查还回, 否则永久幻扣减 ──
    @Test
    @DisplayName("(f) 🔒🔒 null-plan-id 在制道 raw 消耗: 撤销按 production_batch_id 反查还回 usedQuantity+清戳+rawRestored 正确 (mirror #1167 扣减侧)")
    void restoresNullPlanIdConsumptionByProductionBatchId() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(List.of(), List.of(), List.of(), List.of(), List.of());
        stubTarget(1, settlement("s1", 1, postedAt, detail));

        // 本计划一道物化行, production_batch_id = 555 (raw 消耗挂此 WIP 批次, 逐工序道 plan_id 故意 null)。
        ProcessSheetRow row = settledRow(3L, postedAt);
        row.setBatchId(555L);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(new ArrayList<>(List.of(row)));

        // 该道 raw 消耗: production_plan_id == null (逐工序首/中间道防成本双计), production_batch_id == 555。
        //   撤销侧按 (factory, production_batch_id ∈ {555}, interim_settled_at=postedAt) 反查命中。
        //   ⚠️ 旧代码按 production_plan_id 反查 → planId 非 null 但此行 plan_id=null → 永远漏掉 (bug)。
        MaterialConsumption mc = consumption("RB1", new BigDecimal("50"), postedAt);
        mc.setProductionPlanId(null);          // 🔴 关键: null-plan-id 在制道消耗
        mc.setProductionBatchId(555L);
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
                eq(FACTORY), eq(List.of(555L)), eq(postedAt)))
                .thenReturn(new ArrayList<>(List.of(mc)));

        // 来源批次 RB1: 小结时 usedQuantity 已 +50 → 现 50; 撤销应还回到 0。
        MaterialBatch rb1 = new MaterialBatch();
        rb1.setId("RB1");
        rb1.setFactoryId(FACTORY);
        rb1.setReceiptQuantity(new BigDecimal("100"));
        rb1.setUsedQuantity(new BigDecimal("50"));
        rb1.setReservedQuantity(BigDecimal.ZERO);
        rb1.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("RB1", FACTORY)).thenReturn(Optional.of(rb1));

        Map<String, Object> r = service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L);

        // 修复前: null-plan 行不被反查 → usedQuantity 仍 50 (永久幻扣减), rawRestored=0。
        // 修复后: 按 production_batch_id 反查 → usedQuantity 50→0, 戳清空, 有余量 100 → 恢复 AVAILABLE, rawRestored=1。
        assertThat(rb1.getUsedQuantity()).isEqualByComparingTo("0");
        assertThat(rb1.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(mc.getInterimSettledAt()).isNull();
        assertThat(r.get("rawRestored")).isEqualTo(1);
    }

    // ── (d) 下游已消耗 → reverseClerkOutput 抛 → 整体 loud-fail, 后续不发生 (原子) ──
    @Test
    @DisplayName("(d) 下游已消耗: reverseClerkOutput 抛 SFI_DOWNSTREAM_CONSUMED → 整体抛, 原料/行/硬删都不发生")
    void reverseDownstreamConsumedLoudFail() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(
                List.of(sfiIn(ANCHOR, "60", "900")),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        stubTarget(1, settlement("s1", 1, postedAt, detail));
        // 一笔原料 (若未回滚会被还回) — 用于验证抛后未触及
        MaterialConsumption mc = consumption("RB1", new BigDecimal("100"), postedAt);
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
                eq(FACTORY), any(), eq(postedAt)))
                .thenReturn(new ArrayList<>(List.of(mc)));

        doThrow(new BusinessException(409, "半成品 " + ANCHOR + " 已被下游消耗 40, 无法撤销小结")
                .withCode("SFI_DOWNSTREAM_CONSUMED"))
                .when(wipInventoryService).reverseClerkOutput(eq(FACTORY), eq(ANCHOR), any(), any(), any());

        assertThatThrownBy(() -> service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被下游消耗")
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_DOWNSTREAM_CONSUMED"));

        // 下游守卫先跑并抛 → 原料还回 / 清消耗戳 / 硬删 都不发生 (原子, @Transactional 回滚)
        verify(materialBatchRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        assertThat(mc.getInterimSettledAt()).isNotNull();
        verify(settlementRepository, never()).hardDeleteById(any());
    }

    // ── (e) 双撤幂等: 指定 seq 已撤 (硬删) → 找不到 → 409 拒绝 ──
    @Test
    @DisplayName("(e) 双撤幂等: 指定 seq 已撤销 → 409 INTERIM_SETTLE_NOT_FOUND (不重复逆转)")
    void doubleReverseIdempotentReject() {
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(FACTORY, PLAN_ID, 1))
                .thenReturn(Optional.empty());   // 已被首次撤销硬删

        assertThatThrownBy(() -> service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或已撤销")
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_SETTLE_NOT_FOUND"));

        verify(wipInventoryService, never()).reverseClerkOutput(any(), any(), any(), any(), any());
        verify(settlementRepository, never()).hardDeleteById(any());
    }

    // ── 旧小结无 reversalDetail → 诚实拒绝 (不猜) ──
    @Test
    @DisplayName("旧小结无 reversalDetail (上线前创建) → 409 INTERIM_REVERSE_NO_DETAIL (诚实, 不猜)")
    void rejectsLegacySettlementWithoutDetail() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionSeq", 1);   // 无 reversalDetail
        ProductionInterimSettlement s = settlement("sOld", 1, LocalDateTime.now(), null);
        s.setSummary(summary);
        stubTarget(1, s);

        assertThatThrownBy(() -> service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法自动撤销")
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_NO_DETAIL"));
    }

    // ── 🔒 悲观锁: 小结经 PESSIMISTIC_WRITE for-update 方法加载 (串行化并发双撤) ──
    @Test
    @DisplayName("🔒 小结经悲观写锁 findByIdAndFactoryIdForUpdate 加载 (串行化并发双撤锁点)")
    void loadsSettlementUnderPessimisticLock() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(List.of(sfiIn(ANCHOR, "60", "900")), List.of(), List.of(), List.of(), List.of());
        stubTarget(1, settlement("s1", 1, postedAt, detail));

        service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L);

        // 断言: orchestrator 通过悲观锁 for-update 方法重读小结 (并发双撤的串行化锁点)
        verify(settlementRepository, times(1)).findByIdAndFactoryIdForUpdate("s1", FACTORY);
    }

    // ── 🔒 并发败者: 锁获得前赢者已硬删 → for-update 重读 empty → NOT_FOUND, 不双还原料 ──
    @Test
    @DisplayName("🔒 并发双撤败者: 悲观锁重读 empty (赢者已硬删) → NOT_FOUND, 原料/行/硬删均不发生 (不双还幽灵库存)")
    void concurrentLoserReReadsEmptyRejects() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(List.of(), List.of(), List.of(), List.of(), List.of()); // 纯原料 (无 SFI/FG 入库)
        ProductionInterimSettlement s = settlement("s1", 1, postedAt, detail);
        // 初次解析命中, 但悲观锁重读 empty (赢者事务已提交+硬删该行)
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(FACTORY, PLAN_ID, 1))
                .thenReturn(Optional.of(s));
        when(settlementRepository.findByIdAndFactoryIdForUpdate("s1", FACTORY)).thenReturn(Optional.empty());
        // 若未被锁挡住会去还的原料 (用于验证败者未触及)
        MaterialConsumption mc = consumption("RB1", new BigDecimal("100"), postedAt);
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
                eq(FACTORY), any(), eq(postedAt)))
                .thenReturn(new ArrayList<>(List.of(mc)));

        assertThatThrownBy(() -> service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_SETTLE_NOT_FOUND"));

        // 败者不双还原料 usedQuantity, 不清消耗戳, 不硬删
        verify(materialBatchRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        assertThat(mc.getInterimSettledAt()).isNotNull();
        verify(settlementRepository, never()).hardDeleteById(any());
    }

    // ── 非 SAFETY_STOCK → 400 ──
    @Test
    @DisplayName("非存货生产计划撤销小结 → 400")
    void rejectsNonStockPlan() {
        ProductionPlan byOrder = new ProductionPlan();
        byOrder.setId(PLAN_ID);
        byOrder.setFactoryId(FACTORY);
        byOrder.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(byOrder));

        assertThatThrownBy(() -> service.reverseInterimSettle(FACTORY, PLAN_ID, 1, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅存货生产计划可撤销小结");
    }

    // ── 缺省 seq → 撤销最近一次 ──
    @Test
    @DisplayName("缺省 sessionSeq → 撤销最近一次小结 (findTop)")
    void defaultToLatestSession() {
        LocalDateTime postedAt = LocalDateTime.now();
        Map<String, Object> detail = detail(List.of(sfiIn(ANCHOR, "60", "900")), List.of(), List.of(), List.of(), List.of());
        ProductionInterimSettlement latest = settlement("s3", 3, postedAt, detail);
        when(settlementRepository
                .findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(FACTORY, PLAN_ID))
                .thenReturn(Optional.of(latest));
        when(settlementRepository.findByIdAndFactoryIdForUpdate("s3", FACTORY)).thenReturn(Optional.of(latest));

        Map<String, Object> r = service.reverseInterimSettle(FACTORY, PLAN_ID, null, 7L);

        assertThat(r.get("reversedSessionSeq")).isEqualTo(3);
        verify(settlementRepository, times(1)).hardDeleteById("s3");
    }

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

    private void stubTarget(int seq, ProductionInterimSettlement s) {
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(FACTORY, PLAN_ID, seq))
                .thenReturn(Optional.of(s));
        // 悲观锁重读 (串行化并发双撤): 默认返回同一 settlement (个别测试覆写为 empty 模拟并发败者)。
        when(settlementRepository.findByIdAndFactoryIdForUpdate(s.getId(), FACTORY))
                .thenReturn(Optional.of(s));
    }

    private ProductionInterimSettlement settlement(String id, int seq, LocalDateTime postedAt,
                                                   Map<String, Object> reversalDetail) {
        ProductionInterimSettlement s = new ProductionInterimSettlement();
        s.setId(id);
        s.setFactoryId(FACTORY);
        s.setProductionPlanId(PLAN_ID);
        s.setSessionSeq(seq);
        s.setPostedAt(postedAt);
        if (reversalDetail != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sessionSeq", seq);
            summary.put("reversalDetail", reversalDetail);
            s.setSummary(summary);
        }
        return s;
    }

    private Map<String, Object> detail(List<Map<String, Object>> sfiIn, List<Map<String, Object>> fgCreated,
                                       List<Map<String, Object>> sfiOutStrict, List<Map<String, Object>> sfiOutAnchor,
                                       List<Map<String, Object>> fgFeed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sfiIn", sfiIn);
        d.put("fgCreated", fgCreated);
        d.put("sfiOutStrict", sfiOutStrict);
        d.put("sfiOutAnchor", sfiOutAnchor);
        d.put("fgFeed", fgFeed);
        return d;
    }

    private Map<String, Object> sfiIn(String anchor, String qty, String totalCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor", anchor);
        m.put("qty", qty);
        m.put("totalCost", totalCost);   // null tolerated (honest unknown cost)
        return m;
    }

    private Map<String, Object> fgCreated(String batchNumber, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchNumber", batchNumber);
        m.put("qty", qty);
        return m;
    }

    private Map<String, Object> qty(String keyName, String id, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(keyName, id);
        m.put("qty", qty);
        return m;
    }

    private ProcessSheetRow settledRow(Long id, LocalDateTime settledAt) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode("p" + id);
        row.setClientRowId("c" + id);
        row.setBatchNumber("CLK-W-" + id);
        row.setRowStatus("SAVED");
        row.setInterimSettledAt(settledAt);
        return row;
    }

    private MaterialConsumption consumption(String batchId, BigDecimal qty, LocalDateTime settledAt) {
        MaterialConsumption mc = new MaterialConsumption();
        mc.setFactoryId(FACTORY);
        mc.setProductionPlanId(PLAN_ID);
        mc.setBatchId(batchId);
        mc.setQuantity(qty);
        mc.setInterimSettledAt(settledAt);
        return mc;
    }
}
