package com.cretas.aims.service.yield;

import com.cretas.aims.dto.production.InterimSettleReversalRequestDTO;
import com.cretas.aims.entity.InterimSettleReversalRequest;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InterimSettleReversalRequestRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.yield.impl.InterimSettleReversalRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
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
 * 撤销小结治理 TDD — 申请→审批→执行, 1天窗, 角色, 幂等, 审批前零副作用, 执行侧下游守卫仍 fire。
 *
 * <p>真正的逆转 ({@link InterimSettleReversalService}) mock 掉 (它自己有 55 test 覆盖); 此处只验治理编排。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterimSettleReversalRequestServiceTest - 撤销治理 申请→审批→执行")
class InterimSettleReversalRequestServiceTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN1";
    private static final String REQ_ID = "req-1";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionInterimSettlementRepository settlementRepository;
    @Mock private InterimSettleReversalRequestRepository requestRepository;
    @Mock private InterimSettleReversalService reversalService;

    private InterimSettleReversalRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterimSettleReversalRequestServiceImpl(
                planRepository, settlementRepository, requestRepository, reversalService);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 默认无重复待审批申请
        when(requestRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    // ── ① 申请: 创建 PENDING, 零库存副作用 ──
    @Test
    @DisplayName("① 撤销申请 → 创建 PENDING_APPROVAL, 零库存副作用 (reversalService 不被调用)")
    void requestCreatesPendingNoInventoryMutation() {
        stubSettlement(1, LocalDateTime.now().minusHours(2)); // 窗内

        InterimSettleReversalRequestDTO dto = service.requestReverse(FACTORY, PLAN_ID, 1, "录错了", 7L);

        assertThat(dto.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(dto.getSessionSeq()).isEqualTo(1);
        assertThat(dto.getReason()).isEqualTo("录错了");
        // 🔴 零副作用: 不执行任何逆转
        verify(reversalService, never()).reverseInterimSettle(any(), any(), any(), any());
        ArgumentCaptor<InterimSettleReversalRequest> cap = ArgumentCaptor.forClass(InterimSettleReversalRequest.class);
        verify(requestRepository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(InterimSettleReversalRequest.Status.PENDING_APPROVAL);
        assertThat(cap.getValue().getExecutedAt()).isNull();
    }

    // ── ② 24h 窗 (申请端): >24h → WINDOW_EXPIRED ──
    @Test
    @DisplayName("② 申请端超24h → 409 INTERIM_REVERSE_WINDOW_EXPIRED (走盘点调整)")
    void requestBeyondWindowRejected() {
        stubSettlement(1, LocalDateTime.now().minusHours(25)); // 超窗

        assertThatThrownBy(() -> service.requestReverse(FACTORY, PLAN_ID, 1, "太晚了", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过24小时")
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_WINDOW_EXPIRED"));
        verify(requestRepository, never()).save(any());
    }

    // ── 申请: reason 必填 ──
    @Test
    @DisplayName("申请 reason 为空 → 400 REASON_REQUIRED")
    void requestReasonRequired() {
        stubSettlement(1, LocalDateTime.now().minusHours(1));
        assertThatThrownBy(() -> service.requestReverse(FACTORY, PLAN_ID, 1, "  ", 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_REASON_REQUIRED"));
    }

    // ── 申请幂等: 已有 PENDING → 拒绝 ──
    @Test
    @DisplayName("申请幂等: 同小结已有待审批申请 → 409 REQUEST_PENDING")
    void requestDuplicatePendingRejected() {
        stubSettlement(1, LocalDateTime.now().minusHours(1));
        when(requestRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndStatus(
                FACTORY, PLAN_ID, 1, InterimSettleReversalRequest.Status.PENDING_APPROVAL))
                .thenReturn(Optional.of(new InterimSettleReversalRequest()));
        assertThatThrownBy(() -> service.requestReverse(FACTORY, PLAN_ID, 1, "重复", 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_REQUEST_PENDING"));
    }

    // ── 非 SAFETY_STOCK → 400 ──
    @Test
    @DisplayName("非存货生产计划申请撤销 → 400")
    void requestNonStockPlan() {
        ProductionPlan byOrder = new ProductionPlan();
        byOrder.setId(PLAN_ID);
        byOrder.setFactoryId(FACTORY);
        byOrder.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(byOrder));
        assertThatThrownBy(() -> service.requestReverse(FACTORY, PLAN_ID, 1, "x", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅存货生产计划");
    }

    // ── ③ 审批通过 → 执行逆转 + EXECUTED + affectedBatchNumbers 快照 ──
    @Test
    @DisplayName("③ 审批通过 → 内联执行逆转, 状态 EXECUTED, affectedBatchNumbers 快照")
    void approveExecutesReversal() {
        InterimSettleReversalRequest req = pendingRequest(1, LocalDateTime.now().minusHours(2));
        when(requestRepository.findByIdAndFactoryId(REQ_ID, FACTORY)).thenReturn(Optional.of(req));
        Map<String, Object> reverseResult = new LinkedHashMap<>();
        reverseResult.put("reversedSessionSeq", 1);
        reverseResult.put("affectedBatchNumbers", List.of("CLK-SEMI-A", "FG-PP-001-S2"));
        when(reversalService.reverseInterimSettle(FACTORY, PLAN_ID, 1, 99L)).thenReturn(reverseResult);

        InterimSettleReversalRequestDTO dto = service.approve(REQ_ID, FACTORY, 99L, "finance_manager");

        // 执行了真正的逆转
        verify(reversalService, times(1)).reverseInterimSettle(FACTORY, PLAN_ID, 1, 99L);
        assertThat(dto.getStatus()).isEqualTo("EXECUTED");
        assertThat(dto.getApprovedBy()).isEqualTo(99L);
        assertThat(dto.getExecutedAt()).isNotNull();
        assertThat(dto.getAffectedBatchNumbers()).isEqualTo("CLK-SEMI-A,FG-PP-001-S2");
    }

    // ── ③ 角色: 非审批角色 → 403, 不执行 ──
    @Test
    @DisplayName("审批角色不足 (operator) → 403, 不执行逆转")
    void approveNonApproverRole403() {
        assertThatThrownBy(() -> service.approve(REQ_ID, FACTORY, 99L, "operator"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(reversalService, never()).reverseInterimSettle(any(), any(), any(), any());
    }

    // ── ③ 审批端 24h 再校验: 审批时超窗 → WINDOW_EXPIRED, 不执行 ──
    @Test
    @DisplayName("审批端超24h (审批太慢) → 409 WINDOW_EXPIRED, 不执行逆转")
    void approveBeyondWindowRejected() {
        InterimSettleReversalRequest req = pendingRequest(1, LocalDateTime.now().minusHours(26)); // settle 26h 前
        when(requestRepository.findByIdAndFactoryId(REQ_ID, FACTORY)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(REQ_ID, FACTORY, 99L, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_WINDOW_EXPIRED"));
        verify(reversalService, never()).reverseInterimSettle(any(), any(), any(), any());
    }

    // ── ③ 执行侧下游守卫仍 fire: 审批不绕过 ──
    @Test
    @DisplayName("审批执行时下游已消耗 → reversalService 抛 → 传播, 申请不置 EXECUTED (守卫不被审批绕过)")
    void approveDownstreamConsumedStillLoudFail() {
        InterimSettleReversalRequest req = pendingRequest(1, LocalDateTime.now().minusHours(2));
        when(requestRepository.findByIdAndFactoryId(REQ_ID, FACTORY)).thenReturn(Optional.of(req));
        doThrow(new BusinessException(409, "半成品已被下游消耗")
                .withCode("SFI_DOWNSTREAM_CONSUMED"))
                .when(reversalService).reverseInterimSettle(FACTORY, PLAN_ID, 1, 99L);

        assertThatThrownBy(() -> service.approve(REQ_ID, FACTORY, 99L, "platform_admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_DOWNSTREAM_CONSUMED"));
        // 未置 EXECUTED (抛在 setStatus 前; @Transactional 回滚)
        assertThat(req.getStatus()).isEqualTo(InterimSettleReversalRequest.Status.PENDING_APPROVAL);
        assertThat(req.getExecutedAt()).isNull();
    }

    // ── ④ 驳回 → REJECTED, 零副作用 ──
    @Test
    @DisplayName("④ 驳回 → REJECTED, reversalService 不被调用 (零副作用)")
    void rejectClosesRequestNoSideEffect() {
        InterimSettleReversalRequest req = pendingRequest(1, LocalDateTime.now().minusHours(2));
        when(requestRepository.findByIdAndFactoryId(REQ_ID, FACTORY)).thenReturn(Optional.of(req));

        InterimSettleReversalRequestDTO dto = service.reject(REQ_ID, FACTORY, "不同意", 99L, "finance_manager");

        assertThat(dto.getStatus()).isEqualTo("REJECTED");
        assertThat(dto.getRejectReason()).isEqualTo("不同意");
        verify(reversalService, never()).reverseInterimSettle(any(), any(), any(), any());
    }

    @Test
    @DisplayName("驳回角色不足 → 403")
    void rejectNonApproverRole403() {
        assertThatThrownBy(() -> service.reject(REQ_ID, FACTORY, "x", 99L, "operator"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("审批已非 PENDING 的申请 → 409 NOT_PENDING")
    void approveNonPendingRejected() {
        InterimSettleReversalRequest req = pendingRequest(1, LocalDateTime.now().minusHours(1));
        req.setStatus(InterimSettleReversalRequest.Status.EXECUTED);
        when(requestRepository.findByIdAndFactoryId(REQ_ID, FACTORY)).thenReturn(Optional.of(req));
        assertThatThrownBy(() -> service.approve(REQ_ID, FACTORY, 99L, "finance_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("INTERIM_REVERSE_REQUEST_NOT_PENDING"));
        verify(reversalService, never()).reverseInterimSettle(any(), any(), any(), any());
    }

    // ── builders ──

    private void stubSettlement(int seq, LocalDateTime postedAt) {
        ProductionInterimSettlement s = new ProductionInterimSettlement();
        s.setId("s" + seq);
        s.setFactoryId(FACTORY);
        s.setProductionPlanId(PLAN_ID);
        s.setSessionSeq(seq);
        s.setPostedAt(postedAt);
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(FACTORY, PLAN_ID, seq))
                .thenReturn(Optional.of(s));
    }

    private InterimSettleReversalRequest pendingRequest(int seq, LocalDateTime settlementPostedAt) {
        return InterimSettleReversalRequest.builder()
                .id(REQ_ID)
                .factoryId(FACTORY)
                .productionPlanId(PLAN_ID)
                .sessionSeq(seq)
                .settlementPostedAt(settlementPostedAt)
                .reason("录错了")
                .status(InterimSettleReversalRequest.Status.PENDING_APPROVAL)
                .requestedBy(7L)
                .requestedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }
}
