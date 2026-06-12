package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.restaurant.SupplierDeliveryNoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD unit tests for {@link MyTodoAggregatorServiceImpl}.
 *
 * <p>B2 Step 1: Write failing tests (service not yet implemented → all tests FAIL)
 * <p>B2 Step 4: Run again → all tests PASS after implementation
 *
 * @since 2026-06-12 (OA 待办聚合 B2)
 */
@ExtendWith(MockitoExtension.class)
class MyTodoAggregatorServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 12, 10, 0, 0);

    @Mock
    private PurchaseService purchaseService;

    @Mock
    private SalesService salesService;

    @Mock
    private SupplierDeliveryNoteService supplierDeliveryNoteService;

    @Mock
    private FactoryStocktakeRepository stocktakeRepository;

    @Mock
    private PaymentRequestService paymentRequestService;

    private MyTodoAggregatorService service;

    // ─── helpers ─────────────────────────────────────────────────────────────

    private PurchaseOrder fakePurchaseOrder(String id, String orderNumber, BigDecimal amount, String supplierName) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setOrderNumber(orderNumber);
        po.setTotalAmount(amount);
        po.setSupplierName(supplierName);
        po.setFactoryId(FACTORY_ID);
        po.setStatus(PurchaseOrderStatus.PENDING_FINANCE_REVIEW);
        po.setCreatedAt(NOW.minusHours(3));
        po.setUpdatedAt(NOW.minusHours(3));
        return po;
    }

    private SalesOrder fakeSalesOrder(String id, String orderNumber, BigDecimal amount, String customerName) {
        SalesOrder so = new SalesOrder();
        so.setId(id);
        so.setOrderNumber(orderNumber);
        so.setTotalAmount(amount);
        so.setCustomerName(customerName);
        so.setFactoryId(FACTORY_ID);
        so.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        so.setCreatedAt(NOW.minusHours(2));
        so.setUpdatedAt(NOW.minusHours(2));
        return so;
    }

    private SupplierDeliveryNote fakePriceAnomalyNote(String id, String noteNumber, BigDecimal totalAmount, String supplierName) {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId(id);
        note.setNoteNumber(noteNumber);
        note.setTotalAmount(totalAmount);
        note.setSupplierName(supplierName);
        note.setFactoryId(FACTORY_ID);
        note.setPriceAnomalySubmittedAt(NOW.minusHours(1));
        return note;
    }

    private FactoryStocktake fakeStocktake(String id, String stocktakeNo) {
        FactoryStocktake s = new FactoryStocktake();
        s.setId(id);
        s.setStocktakeNo(stocktakeNo);
        s.setFactoryId(FACTORY_ID);
        s.setWarehouseId("WH-001");
        s.setPeriodMonth("2026-06");
        s.setStatus(FactoryStocktake.Status.PENDING_APPROVAL);
        s.setSubmittedAt(NOW.minusMinutes(30));
        s.setCreatedAt(NOW.minusMinutes(30));
        s.setUpdatedAt(NOW.minusMinutes(30));
        return s;
    }

    private PaymentRequest fakePaymentRequest(String id, String requestNumber, BigDecimal amount, String supplierId) {
        PaymentRequest pr = new PaymentRequest();
        pr.setId(id);
        pr.setRequestNumber(requestNumber);
        pr.setAmount(amount);
        pr.setSupplierId(supplierId);
        pr.setCreatedAt(NOW.minusHours(1));
        pr.setUpdatedAt(NOW.minusHours(1));
        return pr;
    }

    @BeforeEach
    void setUp() {
        service = new MyTodoAggregatorServiceImpl(
                purchaseService,
                salesService,
                supplierDeliveryNoteService,
                stocktakeRepository,
                paymentRequestService
        );
    }

    // ─── Role mapping tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("B2-T1: finance_manager 返回 4 类待办")
    class FinanceManagerRole {

        @Test
        @DisplayName("finance_manager: 4 类来源各 1 条 → listTodos 返 4 条")
        void listTodos_financeManager_returns4Types() {
            // arrange
            when(purchaseService.getPurchaseOrdersByStatus(eq(FACTORY_ID),
                    eq(PurchaseOrderStatus.PENDING_FINANCE_REVIEW), eq(1), eq(Integer.MAX_VALUE)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                            List.of(fakePurchaseOrder("po-1", "PO-001", new BigDecimal("3000"), "北京飞熊")), 1, 1, 1L));

            when(salesService.getSalesOrdersByStatus(eq(FACTORY_ID),
                    eq(SalesOrderStatus.PENDING_FINANCE_REVIEW), eq(1), eq(Integer.MAX_VALUE)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                            List.of(fakeSalesOrder("so-1", "SO-001", new BigDecimal("2000"), "叮咚买菜")), 1, 1, 1L));

            when(supplierDeliveryNoteService.listPendingPriceAnomalyApprovals(
                    eq(FACTORY_ID), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(
                            fakePriceAnomalyNote("dn-1", "DN-001", new BigDecimal("800"), "广州鲜食"))));

            when(stocktakeRepository.findByFactoryIdAndOptionalStatus(
                    eq(FACTORY_ID), eq(FactoryStocktake.Status.PENDING_APPROVAL), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(fakeStocktake("st-1", "ST-202606-001"))));

            // act
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            // assert
            assertThat(result).hasSize(4);
            // cashier endpoint should NOT be called for finance_manager
            verifyNoInteractions(paymentRequestService);
            assertThat(result.stream().map(TodoItemDTO::getType))
                    .containsExactlyInAnyOrder(
                            TodoType.PURCHASE_FINANCE_REVIEW,
                            TodoType.SALES_FINANCE_REVIEW,
                            TodoType.PRICE_ANOMALY,
                            TodoType.STOCKTAKE_APPROVAL);
            // payment NOT in result
            assertThat(result.stream().map(TodoItemDTO::getType))
                    .doesNotContain(TodoType.PAYMENT_DISBURSE);
        }
    }

    @Nested
    @DisplayName("B2-T2: cashier 只返付款类")
    class CashierRole {

        @Test
        @DisplayName("cashier: 只调 payment domain → 返 PAYMENT_DISBURSE")
        void listTodos_cashier_returnsOnlyPayment() {
            // arrange
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(
                            buildPaymentEntity("pr-1", "PR-001", new BigDecimal("15000"), "北京飞熊")));

            // act
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(TodoType.PAYMENT_DISBURSE);
            assertThat(result.get(0).getAmount()).isEqualByComparingTo("15000");

            // other domains should NOT be queried for cashier
            verifyNoInteractions(purchaseService);
            verifyNoInteractions(salesService);
            verifyNoInteractions(supplierDeliveryNoteService);
            verifyNoInteractions(stocktakeRepository);
        }

        private com.cretas.aims.entity.inventory.PaymentRequest buildPaymentEntity(
                String id, String requestNumber, BigDecimal amount, String supplierId) {
            com.cretas.aims.entity.inventory.PaymentRequest pr =
                    new com.cretas.aims.entity.inventory.PaymentRequest();
            pr.setId(id);
            pr.setRequestNumber(requestNumber);
            pr.setAmount(amount);
            pr.setSupplierId(supplierId);
            pr.setCreatedAt(NOW.minusHours(5));
            pr.setUpdatedAt(NOW.minusHours(5));
            return pr;
        }
    }

    @Nested
    @DisplayName("B2-T3: 其他角色返空")
    class OtherRole {

        @Test
        @DisplayName("operator 角色 → 空列表（无任何 service 调用）")
        void listTodos_otherRole_returnsEmpty() {
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "operator");
            assertThat(result).isEmpty();

            // No domain queries fired
            verifyNoInteractions(purchaseService, salesService,
                    supplierDeliveryNoteService, stocktakeRepository, paymentRequestService);
        }

        @Test
        @DisplayName("null 角色 → 空列表")
        void listTodos_nullRole_returnsEmpty() {
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("B2-T4: needDetail 阈值边界")
    class NeedDetailThreshold {

        @Test
        @DisplayName("amount=6000(>5000) → needDetail=true")
        void needDetail_aboveThreshold_true() {
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-hi", "PR-HI", new BigDecimal("6000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isTrue();
        }

        @Test
        @DisplayName("amount=4000(<5000) → needDetail=false")
        void needDetail_belowThreshold_false() {
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-lo", "PR-LO", new BigDecimal("4000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isFalse();
        }

        @Test
        @DisplayName("amount=5000(==threshold) → needDetail=false（边界含等于）")
        void needDetail_exactThreshold_false() {
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-eq", "PR-EQ", new BigDecimal("5000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isFalse();
        }

        @Test
        @DisplayName("amount=null → needDetail=true（保守）")
        void needDetail_nullAmount_true() {
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-null", "PR-NULL", null)));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isTrue();
        }

        private com.cretas.aims.entity.inventory.PaymentRequest buildPR(
                String id, String rn, BigDecimal amount) {
            com.cretas.aims.entity.inventory.PaymentRequest pr =
                    new com.cretas.aims.entity.inventory.PaymentRequest();
            pr.setId(id);
            pr.setRequestNumber(rn);
            pr.setAmount(amount);
            pr.setCreatedAt(NOW);
            pr.setUpdatedAt(NOW);
            return pr;
        }
    }

    @Nested
    @DisplayName("B2-T5: fan-out fail-soft（单域失败不拖垮整体）")
    class FanOutFailSoft {

        @Test
        @DisplayName("采购查询抛异常 → 其他 3 类正常返回，不 500")
        void fanOut_purchaseFails_othersStillReturn() {
            when(purchaseService.getPurchaseOrdersByStatus(anyString(), any(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("DB connection timeout"));

            when(salesService.getSalesOrdersByStatus(eq(FACTORY_ID),
                    eq(SalesOrderStatus.PENDING_FINANCE_REVIEW), eq(1), eq(Integer.MAX_VALUE)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                            List.of(fakeSalesOrder("so-2", "SO-002", new BigDecimal("1000"), "客户A")), 1, 1, 1L));

            when(supplierDeliveryNoteService.listPendingPriceAnomalyApprovals(
                    eq(FACTORY_ID), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            when(stocktakeRepository.findByFactoryIdAndOptionalStatus(
                    eq(FACTORY_ID), eq(FactoryStocktake.Status.PENDING_APPROVAL), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Should NOT throw, should return items from the non-failing domains
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            // Purchase failed → 0 purchase items; sales succeeded → 1 sales item; anomaly+stocktake empty
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(TodoType.SALES_FINANCE_REVIEW);
        }
    }

    @Nested
    @DisplayName("B2-T6: countTodos")
    class CountTodos {

        @Test
        @DisplayName("cashier + 2 付款项 → count=2")
        void countTodos_cashier_returns2() {
            when(paymentRequestService.listApprovedForPayment(FACTORY_ID))
                    .thenReturn(List.of(
                            buildPR("pr-a", new BigDecimal("1000")),
                            buildPR("pr-b", new BigDecimal("2000"))));

            int count = service.countTodos(FACTORY_ID, "cashier");

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("unknown role → count=0")
        void countTodos_unknownRole_zero() {
            int count = service.countTodos(FACTORY_ID, "production_manager");
            assertThat(count).isZero();
        }

        private com.cretas.aims.entity.inventory.PaymentRequest buildPR(String id, BigDecimal amount) {
            com.cretas.aims.entity.inventory.PaymentRequest pr =
                    new com.cretas.aims.entity.inventory.PaymentRequest();
            pr.setId(id);
            pr.setRequestNumber("PR-" + id);
            pr.setAmount(amount);
            pr.setCreatedAt(NOW);
            pr.setUpdatedAt(NOW);
            return pr;
        }
    }

    @Nested
    @DisplayName("B2-T7: 排序 (submittedAt 倒序)")
    class SortingOrder {

        @Test
        @DisplayName("finance_manager: 多条 todo 按 submittedAt 倒序排列")
        void listTodos_financeManager_sortedBySubmittedAtDesc() {
            LocalDateTime older = NOW.minusHours(5);
            LocalDateTime newer = NOW.minusHours(1);

            PurchaseOrder po1 = fakePurchaseOrder("po-old", "PO-OLD", new BigDecimal("1000"), "供应商1");
            po1.setCreatedAt(older);
            po1.setUpdatedAt(older);

            SalesOrder so1 = fakeSalesOrder("so-new", "SO-NEW", new BigDecimal("2000"), "客户B");
            so1.setCreatedAt(newer);
            so1.setUpdatedAt(newer);

            when(purchaseService.getPurchaseOrdersByStatus(eq(FACTORY_ID),
                    eq(PurchaseOrderStatus.PENDING_FINANCE_REVIEW), eq(1), eq(Integer.MAX_VALUE)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                            List.of(po1), 1, 1, 1L));

            when(salesService.getSalesOrdersByStatus(eq(FACTORY_ID),
                    eq(SalesOrderStatus.PENDING_FINANCE_REVIEW), eq(1), eq(Integer.MAX_VALUE)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                            List.of(so1), 1, 1, 1L));

            when(supplierDeliveryNoteService.listPendingPriceAnomalyApprovals(
                    eq(FACTORY_ID), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            when(stocktakeRepository.findByFactoryIdAndOptionalStatus(
                    eq(FACTORY_ID), eq(FactoryStocktake.Status.PENDING_APPROVAL), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            assertThat(result).hasSize(2);
            // newer SO should come first
            assertThat(result.get(0).getType()).isEqualTo(TodoType.SALES_FINANCE_REVIEW);
            assertThat(result.get(1).getType()).isEqualTo(TodoType.PURCHASE_FINANCE_REVIEW);
        }
    }
}
