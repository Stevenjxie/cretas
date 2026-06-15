package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ApprovalStatus;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ReasonType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesPriceAdjustmentRecordRepository;
import com.cretas.aims.service.inventory.impl.SalesPriceAdjustmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 销售订单行改价服务单元测试
 *
 * <p>覆盖 4 条核心路径:
 * <ol>
 *   <li>改价成功 — 历史记录留痕 (old/new price, 操作人, 原因)</li>
 *   <li>降价 >10% → 触发审批 (approvalRequired=true)</li>
 *   <li>已发货行 → 拒绝改价 (BusinessException 409)</li>
 *   <li>改价后 SO 总额正确重算 (BigDecimal HALF_UP)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SalesPriceAdjustmentServiceTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private SalesOrderItemRepository salesOrderItemRepository;
    @Mock
    private SalesPriceAdjustmentRecordRepository adjustmentRecordRepository;
    @Mock
    private UserRepository userRepository;

    private SalesPriceAdjustmentServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID   = "SO-001";
    private static final Long   LINE_ID    = 42L;
    private static final Long   USER_ID    = 99L;

    @BeforeEach
    void setUp() {
        service = new SalesPriceAdjustmentServiceImpl(
                salesOrderRepository, salesOrderItemRepository,
                adjustmentRecordRepository, userRepository);
        // No ApprovalChainService injected by default (optional field)
    }

    // =================================================================
    // Test 1: adjustPrice_recordsHistory_whenSuccessful
    // =================================================================

    @Test
    @DisplayName("PA-01: 改价成功 — 历史记录包含 old/new 价格、操作人、原因类型")
    void adjustPrice_recordsHistory_whenSuccessful() {
        // given
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));
        User user = buildUser(USER_ID, "张三");

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("105.00"), ReasonType.NEGOTIATION, null);

        // when
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then
        assertThat(response.oldPrice()).isEqualByComparingTo("100.0000");
        assertThat(response.newPrice()).isEqualByComparingTo("105.0000");
        assertThat(response.approvalRequired()).isFalse();
        assertThat(response.effectiveImmediately()).isTrue();

        // Verify record persisted with correct fields
        ArgumentCaptor<SalesPriceAdjustmentRecord> captor =
                ArgumentCaptor.forClass(SalesPriceAdjustmentRecord.class);
        verify(adjustmentRecordRepository).save(captor.capture());
        SalesPriceAdjustmentRecord saved = captor.getValue();

        assertThat(saved.getOldUnitPrice()).isEqualByComparingTo("100.0000");
        assertThat(saved.getNewUnitPrice()).isEqualByComparingTo("105.0000");
        assertThat(saved.getAdjustedBy()).isEqualTo(USER_ID);
        assertThat(saved.getAdjustedByName()).isEqualTo("张三");
        assertThat(saved.getAdjustmentReasonType()).isEqualTo(ReasonType.NEGOTIATION);
        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(saved.isApprovalRequired()).isFalse();
    }

    // =================================================================
    // Test 2: adjustPrice_triggersApproval_whenOverThreshold
    // =================================================================

    @Test
    @DisplayName("PA-02: 降价 >10% → approvalRequired=true, 价格未立即生效")
    void adjustPrice_triggersApproval_whenOverDecreaseThreshold() {
        // given: original price 100, new price 85 → decrease 15% > 10% threshold
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));
        User user = buildUser(USER_ID, "李四");

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("85.00"), ReasonType.CUSTOMER_REQUEST, null);

        // when
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.approvalStatus()).isEqualTo("PENDING");
        assertThat(response.effectiveImmediately()).isFalse();

        // Price must NOT be applied to the line (pending approval)
        verify(salesOrderItemRepository, never()).save(any());

        // Record must be saved with PENDING status
        ArgumentCaptor<SalesPriceAdjustmentRecord> captor =
                ArgumentCaptor.forClass(SalesPriceAdjustmentRecord.class);
        verify(adjustmentRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(captor.getValue().isApprovalRequired()).isTrue();
    }

    @Test
    @DisplayName("PA-02b: 涨价 >20% → approvalRequired=true")
    void adjustPrice_triggersApproval_whenOverIncreaseThreshold() {
        // given: original price 100, new price 125 → increase 25% > 20% threshold
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));
        User user = buildUser(USER_ID, "王五");

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("125.00"), ReasonType.MARKET_CHANGE, null);

        // when
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.approvalStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("PA-02c: 降价 10% (等于阈值) → 不触发审批")
    void adjustPrice_noApproval_whenExactlyAtDecreaseThreshold() {
        // given: original price 100, new price 90 → decrease exactly 10%
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("90.00"), ReasonType.PROMOTION, null);

        // when
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then: exactly 10% decrease does NOT exceed threshold (> not >=)
        assertThat(response.approvalRequired()).isFalse();
    }

    // =================================================================
    // Test 3: adjustPrice_rejectsShippedLine
    // =================================================================

    @Test
    @DisplayName("PA-03: 已发货行 → 抛出 BusinessException 409, 拒绝改价")
    void adjustPrice_rejectsShippedLine() {
        // given: line has deliveredQuantity = 5 (already shipped)
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), new BigDecimal("5.0000"));
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("95.00"), ReasonType.ERROR_CORRECTION, null);

        // when + then
        assertThatThrownBy(() -> service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有发货数量");

        // No record should be saved
        verify(adjustmentRecordRepository, never()).save(any());
        verify(salesOrderItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("PA-03b: 未发货行 (deliveredQty=0) → 允许改价")
    void adjustPrice_allowsUnshippedLine() {
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("105.00"), ReasonType.NEGOTIATION, null);

        // should not throw
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);
        assertThat(response).isNotNull();
    }

    // =================================================================
    // Test 4: adjustPrice_recalculatesTotalAmount
    // =================================================================

    @Test
    @DisplayName("PA-04: 改价后 SO 总额正确重算 (qty×newPrice 汇总, HALF_UP)")
    void adjustPrice_recalculatesTotalAmount() {
        // given: line qty=10, old price=100, new price=105 → line total = 1050.00
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        line.setQuantity(new BigDecimal("10.0000"));

        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        // After price is updated (line.setUnitPrice called), return updated line for recalculation
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenAnswer(inv -> {
            // Return the line with the new price (service already set it at this point)
            return List.of(line);
        });
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("105.00"), ReasonType.NEGOTIATION, null);

        // when
        service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then: order.totalAmount should have been updated
        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(orderCaptor.capture());
        BigDecimal updatedTotal = orderCaptor.getValue().getTotalAmount();
        // qty=10 × newPrice=105 = 1050.00
        assertThat(updatedTotal).isEqualByComparingTo("1050.00");
    }

    @Test
    @DisplayName("PA-04b: 改价后总额计算应用折扣率 (HALF_UP)")
    void adjustPrice_recalculatesTotalAmount_withDiscountRate() {
        // given: line qty=10, new price=100, discount=10% → lineAmt=900.00
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("90.0000"), BigDecimal.ZERO);
        line.setQuantity(new BigDecimal("10.0000"));
        line.setDiscountRate(new BigDecimal("10.00")); // 10% discount

        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("900.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.empty());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // price change: 90 → 100 (increase ~11.1%, within 20% threshold)
        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("100.00"), ReasonType.NEGOTIATION, null);

        // when
        service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then: 10 × 100 × (1 - 0.10) = 900.00
        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("900.00");
    }

    // =================================================================
    // Test 5: fool-proof Rule 3 - OTHER reason requires detail
    // =================================================================

    @Test
    @DisplayName("PA-05: reasonType=OTHER 且 reasonDetail 为空 → 400 Bad Request")
    void adjustPrice_rejectsOtherReasonWithoutDetail() {
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        // Note: idempotency check is not reached — OTHER+no-detail fails before it

        // OTHER reason with no detail
        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("105.00"), ReasonType.OTHER, null);

        assertThatThrownBy(() -> service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reasonDetail");
    }

    // =================================================================
    // Test 6: Idempotency — existing PENDING returns it (Rule 4)
    // =================================================================

    @Test
    @DisplayName("PA-06: 同一行已有 PENDING 审批 → 幂等返回已有记录 (Rule 4)")
    void adjustPrice_idempotent_whenPendingExists() {
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        SalesPriceAdjustmentRecord existing = new SalesPriceAdjustmentRecord();
        existing.setId("existing-record-id");
        existing.setSalesOrderLineId(LINE_ID);
        existing.setSalesOrderId(ORDER_ID);
        existing.setOldUnitPrice(new BigDecimal("100.0000"));
        existing.setNewUnitPrice(new BigDecimal("80.0000"));
        existing.setApprovalRequired(true);
        existing.setApprovalStatus(ApprovalStatus.PENDING);

        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(adjustmentRecordRepository.findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
                LINE_ID, ApprovalStatus.PENDING)).thenReturn(Optional.of(existing));

        AdjustPriceRequest request = new AdjustPriceRequest(
                new BigDecimal("80.00"), ReasonType.CUSTOMER_REQUEST, null);

        // when
        AdjustPriceResponse response = service.adjustLinePrice(FACTORY_ID, ORDER_ID, LINE_ID, request, USER_ID);

        // then: existing record returned, no new save
        assertThat(response.adjustmentRecordId()).isEqualTo("existing-record-id");
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.approvalStatus()).isEqualTo("PENDING");
        verify(adjustmentRecordRepository, never()).save(any());
    }

    // =================================================================
    // Test 7: approve — applies held price change + recalc total
    // =================================================================

    @Test
    @DisplayName("PA-07: approve → PENDING 改价应用到行 + 重算总额, 状态 APPROVED")
    void approve_appliesHeldPriceChange_andRecalcTotal() {
        // given: a PENDING record holding newPrice=80, submitted by USER_ID (99)
        SalesOrderItem line = buildLine(LINE_ID, ORDER_ID, new BigDecimal("100.0000"), BigDecimal.ZERO);
        line.setQuantity(new BigDecimal("10.0000"));
        SalesOrder order = buildOrder(ORDER_ID, FACTORY_ID, new BigDecimal("1000.00"));

        SalesPriceAdjustmentRecord pending = buildPendingRecord(
                "rec-1", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);

        Long approverId = 7L;
        when(adjustmentRecordRepository.findById("rec-1")).thenReturn(Optional.of(pending));
        when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userRepository.findById(approverId)).thenReturn(Optional.of(buildUser(approverId, "审批员")));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        var dto = service.approvePriceAdjustment(FACTORY_ID, "rec-1", approverId);

        // then: price applied to line
        ArgumentCaptor<SalesOrderItem> lineCaptor = ArgumentCaptor.forClass(SalesOrderItem.class);
        verify(salesOrderItemRepository).save(lineCaptor.capture());
        assertThat(lineCaptor.getValue().getUnitPrice()).isEqualByComparingTo("80.0000");

        // total recalculated: 10 × 80 = 800.00
        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("800.00");

        // record marked APPROVED + approver tracked
        assertThat(dto.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(pending.getApprovedBy()).isEqualTo(approverId);
        assertThat(pending.getApprovedByName()).isEqualTo("审批员");
        assertThat(pending.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("PA-07b: approve 非 PENDING 记录 → 409")
    void approve_rejectsNonPendingRecord() {
        SalesPriceAdjustmentRecord approved = buildPendingRecord(
                "rec-x", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        approved.setApprovalStatus(ApprovalStatus.APPROVED);
        when(adjustmentRecordRepository.findById("rec-x")).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approvePriceAdjustment(FACTORY_ID, "rec-x", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING");
        verify(salesOrderItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("PA-07c: approve 其他工厂的记录 → 404 (租户隔离)")
    void approve_rejectsCrossFactoryRecord() {
        SalesPriceAdjustmentRecord other = buildPendingRecord(
                "rec-of", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        other.setFactoryId("F999");
        when(adjustmentRecordRepository.findById("rec-of")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.approvePriceAdjustment(FACTORY_ID, "rec-of", 7L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("不存在");
    }

    // =================================================================
    // Test 8: 4-eyes — approver cannot equal submitter
    // =================================================================

    @Test
    @DisplayName("PA-08: approve 审批人 == 提交人 → 403 (4 眼原则)")
    void approve_rejectsSameUserAsSubmitter() {
        SalesPriceAdjustmentRecord pending = buildPendingRecord(
                "rec-2", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        when(adjustmentRecordRepository.findById("rec-2")).thenReturn(Optional.of(pending));

        // approver == submitter (USER_ID)
        assertThatThrownBy(() -> service.approvePriceAdjustment(FACTORY_ID, "rec-2", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("4 眼原则");
        verify(salesOrderItemRepository, never()).save(any());
        verify(adjustmentRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("PA-08b: reject 审批人 == 提交人 → 403 (4 眼原则)")
    void reject_rejectsSameUserAsSubmitter() {
        SalesPriceAdjustmentRecord pending = buildPendingRecord(
                "rec-3", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        when(adjustmentRecordRepository.findById("rec-3")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.rejectPriceAdjustment(FACTORY_ID, "rec-3", USER_ID, "价格太低"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("4 眼原则");
        verify(adjustmentRecordRepository, never()).save(any());
    }

    // =================================================================
    // Test 9: reject — discards price change, no line update
    // =================================================================

    @Test
    @DisplayName("PA-09: reject → 状态 REJECTED, 改价不应用, 行价格不变")
    void reject_discardsPriceChange() {
        SalesPriceAdjustmentRecord pending = buildPendingRecord(
                "rec-4", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        Long approverId = 7L;
        when(adjustmentRecordRepository.findById("rec-4")).thenReturn(Optional.of(pending));
        when(userRepository.findById(approverId)).thenReturn(Optional.of(buildUser(approverId, "审批员")));
        when(adjustmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.rejectPriceAdjustment(FACTORY_ID, "rec-4", approverId, "价格低于成本");

        // line price NOT applied (no line/order save)
        verify(salesOrderItemRepository, never()).save(any());
        verify(salesOrderRepository, never()).save(any());

        assertThat(dto.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(pending.getRejectReason()).isEqualTo("价格低于成本");
        assertThat(pending.getApprovedBy()).isEqualTo(approverId);
        assertThat(pending.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("PA-09b: reject 原因为空 → 400")
    void reject_rejectsBlankReason() {
        // reason blank check happens before record load (fail fast)
        assertThatThrownBy(() -> service.rejectPriceAdjustment(FACTORY_ID, "rec-5", 7L, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因");
        verify(adjustmentRecordRepository, never()).save(any());
    }

    // =================================================================
    // Test 10: listPendingApprovals — OA aggregator source
    // =================================================================

    @Test
    @DisplayName("PA-10: listPendingApprovals → 返回工厂内 PENDING 记录")
    void listPendingApprovals_returnsPendingRecords() {
        SalesPriceAdjustmentRecord pending = buildPendingRecord(
                "rec-6", new BigDecimal("100.0000"), new BigDecimal("80.0000"), USER_ID);
        when(adjustmentRecordRepository.findByFactoryIdAndApprovalStatusOrderByCreatedAtDesc(
                FACTORY_ID, ApprovalStatus.PENDING)).thenReturn(List.of(pending));

        var result = service.listPendingApprovals(FACTORY_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("rec-6");
    }

    // =================================================================
    // Helpers
    // =================================================================

    private SalesPriceAdjustmentRecord buildPendingRecord(
            String id, BigDecimal oldPrice, BigDecimal newPrice, Long adjustedBy) {
        SalesPriceAdjustmentRecord r = new SalesPriceAdjustmentRecord();
        r.setId(id);
        r.setSalesOrderLineId(LINE_ID);
        r.setSalesOrderId(ORDER_ID);
        r.setFactoryId(FACTORY_ID);
        r.setOldUnitPrice(oldPrice);
        r.setNewUnitPrice(newPrice);
        r.setAdjustmentReasonType(ReasonType.CUSTOMER_REQUEST);
        r.setAdjustedBy(adjustedBy);
        r.setApprovalRequired(true);
        r.setApprovalStatus(ApprovalStatus.PENDING);
        return r;
    }

    private SalesOrderItem buildLine(Long id, String orderId, BigDecimal unitPrice, BigDecimal deliveredQty) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(id);
        item.setSalesOrderId(orderId);
        item.setProductTypeId("PT-001");
        item.setProductName("测试产品");
        item.setQuantity(new BigDecimal("10.0000"));
        item.setUnit("kg");
        item.setUnitPrice(unitPrice);
        item.setDeliveredQuantity(deliveredQty);
        item.setLockedQty(BigDecimal.ZERO);
        item.setReservedQty(BigDecimal.ZERO);
        item.setDiscountRate(BigDecimal.ZERO);
        return item;
    }

    private SalesOrder buildOrder(String orderId, String factoryId, BigDecimal totalAmount) {
        SalesOrder order = new SalesOrder();
        order.setId(orderId);
        order.setFactoryId(factoryId);
        order.setOrderNumber("SO-TEST-001");
        order.setCustomerId("CUST-001");
        order.setTotalAmount(totalAmount);
        return order;
    }

    private User buildUser(Long id, String fullName) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setFullName(fullName);
        return user;
    }
}
