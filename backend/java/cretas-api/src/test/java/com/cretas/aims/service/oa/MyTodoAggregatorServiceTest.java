package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
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
import java.util.Optional;

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
    private FactoryWarehouseRepository warehouseRepository;

    @Mock
    private ReturnOrderRepository returnOrderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private PaymentRequestService paymentRequestService;

    @Mock
    private SalesPriceAdjustmentService salesPriceAdjustmentService;

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

    private ReturnOrder fakeReturnOrder(String id, String returnNumber, BigDecimal amount, String counterpartyId) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setReturnNumber(returnNumber);
        order.setTotalAmount(amount);
        order.setCounterpartyId(counterpartyId);
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setFactoryId(FACTORY_ID);
        order.setStatus(ReturnOrderStatus.APPROVED);
        order.setApprovedAt(NOW.minusMinutes(20));
        order.setCreatedAt(NOW.minusHours(4));
        order.setUpdatedAt(NOW.minusMinutes(20));
        return order;
    }

    private PaymentRequestApprovedDTO fakePaymentRequest(String id, String requestNumber, BigDecimal amount, String supplierName) {
        return PaymentRequestApprovedDTO.builder()
                .id(id)
                .requestNumber(requestNumber)
                .amount(amount)
                .supplierName(supplierName)
                .createdAt(NOW.minusHours(1))
                .build();
    }

    private FactoryWarehouse fakeWarehouse(String id, String name) {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId(id);
        warehouse.setFactoryId(FACTORY_ID);
        warehouse.setCode(id);
        warehouse.setName(name);
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        return warehouse;
    }

    private Customer fakeCustomer(String id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFactoryId(FACTORY_ID);
        customer.setCode("C-" + id);
        customer.setCustomerCode("C-" + id);
        customer.setName(name);
        return customer;
    }

    private Supplier fakeSupplier(String id, String name) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId(FACTORY_ID);
        supplier.setCode("S-" + id);
        supplier.setSupplierCode("S-" + id);
        supplier.setName(name);
        return supplier;
    }

    @BeforeEach
    void setUp() {
        service = new MyTodoAggregatorServiceImpl(
                purchaseService,
                salesService,
                supplierDeliveryNoteService,
                stocktakeRepository,
                warehouseRepository,
                returnOrderRepository,
                customerRepository,
                supplierRepository,
                paymentRequestService,
                salesPriceAdjustmentService
        );
    }

    // ─── Role mapping tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("B2-T1: finance_manager 返回 4 类待办")
    class FinanceManagerRole {

        @Test
        @DisplayName("finance_manager: 4 类来源各 1 条 → listTodos 返 4 条")
        void listTodos_financeManager_returns5Types() {
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
            when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WH-001", FACTORY_ID))
                    .thenReturn(Optional.of(fakeWarehouse("WH-001", "成品仓")));

            when(returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    eq(FACTORY_ID), eq(ReturnOrderStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(
                            fakeReturnOrder("ro-1", "RO-001", new BigDecimal("900"), "CP-001"))));
            when(customerRepository.findByIdAndFactoryId("CP-001", FACTORY_ID))
                    .thenReturn(Optional.of(fakeCustomer("CP-001", "叮咚买菜")));

            // act
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            // assert
            assertThat(result).hasSize(5);
            // cashier endpoint should NOT be called for finance_manager
            verifyNoInteractions(paymentRequestService);
            assertThat(result.stream().map(TodoItemDTO::getType))
                    .containsExactlyInAnyOrder(
                            TodoType.PURCHASE_FINANCE_REVIEW,
                            TodoType.SALES_FINANCE_REVIEW,
                            TodoType.PRICE_ANOMALY,
                            TodoType.STOCKTAKE_APPROVAL,
                            TodoType.RETURN_FINANCE_REVIEW);
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
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
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
            verifyNoInteractions(returnOrderRepository);
        }

        private PaymentRequestApprovedDTO buildPaymentEntity(
                String id, String requestNumber, BigDecimal amount, String supplierName) {
            return PaymentRequestApprovedDTO.builder()
                    .id(id)
                    .requestNumber(requestNumber)
                    .amount(amount)
                    .supplierName(supplierName)
                    .createdAt(NOW.minusHours(5))
                    .build();
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
                    supplierDeliveryNoteService, stocktakeRepository, returnOrderRepository, paymentRequestService);
        }

        @Test
        @DisplayName("null 角色 → 空列表")
        void listTodos_nullRole_returnsEmpty() {
            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("RN-R1: 待办 counterparty 必须给人话名称")
    class ReadableCounterparty {

        @Test
        @DisplayName("盘点审批: counterparty 显示仓库名, 不显示 warehouseId")
        void stocktakeTodo_usesWarehouseName() {
            when(stocktakeRepository.findByFactoryIdAndOptionalStatus(
                    eq(FACTORY_ID), eq(FactoryStocktake.Status.PENDING_APPROVAL), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(fakeStocktake("st-readable", "ST-202606-READ"))));
            when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WH-001", FACTORY_ID))
                    .thenReturn(Optional.of(fakeWarehouse("WH-001", "成品仓")));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            TodoItemDTO item = result.stream()
                    .filter(t -> t.getType() == TodoType.STOCKTAKE_APPROVAL)
                    .findFirst()
                    .orElseThrow();
            assertThat(item.getCounterparty()).isEqualTo("成品仓");
            assertThat(item.getCounterparty()).isNotEqualTo("WH-001");
        }

        @Test
        @DisplayName("退货财审: 销售退货显示客户名, 不显示 customerId")
        void returnTodo_usesCustomerNameForSalesReturn() {
            when(returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    eq(FACTORY_ID), eq(ReturnOrderStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(
                            fakeReturnOrder("ro-readable", "RO-READ", new BigDecimal("900"), "CP-001"))));
            when(customerRepository.findByIdAndFactoryId("CP-001", FACTORY_ID))
                    .thenReturn(Optional.of(fakeCustomer("CP-001", "叮咚买菜")));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            TodoItemDTO item = result.stream()
                    .filter(t -> t.getType() == TodoType.RETURN_FINANCE_REVIEW)
                    .findFirst()
                    .orElseThrow();
            assertThat(item.getCounterparty()).isEqualTo("叮咚买菜");
            assertThat(item.getCounterparty()).isNotEqualTo("CP-001");
        }

        @Test
        @DisplayName("退货财审: 采购退货显示供应商名, 不显示 supplierId")
        void returnTodo_usesSupplierNameForPurchaseReturn() {
            ReturnOrder order = fakeReturnOrder("ro-supplier", "RO-SUP", new BigDecimal("1200"), "SUP-001");
            order.setReturnType(ReturnType.PURCHASE_RETURN);
            when(returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    eq(FACTORY_ID), eq(ReturnOrderStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(order)));
            when(supplierRepository.findByIdAndFactoryId("SUP-001", FACTORY_ID))
                    .thenReturn(Optional.of(fakeSupplier("SUP-001", "北京飞熊")));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            TodoItemDTO item = result.stream()
                    .filter(t -> t.getType() == TodoType.RETURN_FINANCE_REVIEW)
                    .findFirst()
                    .orElseThrow();
            assertThat(item.getCounterparty()).isEqualTo("北京飞熊");
            assertThat(item.getCounterparty()).isNotEqualTo("SUP-001");
        }

        @Test
        @DisplayName("出纳付款: counterparty 显示供应商名, 不显示 supplierId")
        void paymentTodo_usesApprovedDetailSupplierName() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(List.of(fakePaymentRequest(
                            "pr-readable", "PR-READ", new BigDecimal("15000"), "北京飞熊")));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            TodoItemDTO item = result.get(0);
            assertThat(item.getCounterparty()).isEqualTo("北京飞熊");
            assertThat(item.getCounterparty()).isNotEqualTo("SUP-001");
            verify(paymentRequestService).listApprovedForPaymentWithDetails(FACTORY_ID);
            verify(paymentRequestService, never()).listApprovedForPayment(FACTORY_ID);
        }
    }

    @Nested
    @DisplayName("B2-T4: needDetail 阈值边界")
    class NeedDetailThreshold {

        @Test
        @DisplayName("amount=6000(>5000) → needDetail=true")
        void needDetail_aboveThreshold_true() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-hi", "PR-HI", new BigDecimal("6000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isTrue();
        }

        @Test
        @DisplayName("amount=4000(<5000) → needDetail=false")
        void needDetail_belowThreshold_false() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-lo", "PR-LO", new BigDecimal("4000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isFalse();
        }

        @Test
        @DisplayName("amount=5000(==threshold) → needDetail=false（边界含等于）")
        void needDetail_exactThreshold_false() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-eq", "PR-EQ", new BigDecimal("5000"))));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isFalse();
        }

        @Test
        @DisplayName("amount=null → needDetail=true（保守）")
        void needDetail_nullAmount_true() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(List.of(buildPR("pr-null", "PR-NULL", null)));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "cashier");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isNeedDetail()).isTrue();
        }

        private PaymentRequestApprovedDTO buildPR(
                String id, String rn, BigDecimal amount) {
            return PaymentRequestApprovedDTO.builder()
                    .id(id)
                    .requestNumber(rn)
                    .amount(amount)
                    .supplierName("北京飞熊")
                    .createdAt(NOW)
                    .build();
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
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
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

        private PaymentRequestApprovedDTO buildPR(String id, BigDecimal amount) {
            return PaymentRequestApprovedDTO.builder()
                    .id(id)
                    .requestNumber("PR-" + id)
                    .amount(amount)
                    .supplierName("北京飞熊")
                    .createdAt(NOW)
                    .build();
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

    @Nested
    @DisplayName("B2-T8: 销售改价 PENDING 露出待办 (#917 审批闭环)")
    class SalesPriceAdjustmentSource {

        @Test
        @DisplayName("finance_manager: PENDING 改价记录 → 露出 SALES_PRICE_ADJUSTMENT 待办")
        void listTodos_financeManager_exposesPendingPriceAdjustment() {
            // arrange: only the price-adjustment domain returns a PENDING record;
            // 其他域返空 (null/empty) — fail-soft 处理.
            when(salesPriceAdjustmentService.listPendingApprovals(FACTORY_ID))
                    .thenReturn(List.of(fakePendingAdjustment(
                            "spa-1", "SO-001", new BigDecimal("100.0000"), new BigDecimal("80.0000"), "张三")));

            List<TodoItemDTO> result = service.listTodos(FACTORY_ID, "finance_manager");

            // assert: the price-adjustment item is present
            List<TodoItemDTO> priceItems = result.stream()
                    .filter(t -> t.getType() == TodoType.SALES_PRICE_ADJUSTMENT)
                    .toList();
            assertThat(priceItems).hasSize(1);
            TodoItemDTO item = priceItems.get(0);
            assertThat(item.getRefId()).isEqualTo("spa-1");
            assertThat(item.getRefNumber()).isEqualTo("SO-001");
            assertThat(item.getTitle()).contains("降价");
            assertThat(item.isNeedDetail()).isTrue();  // 改价审批强制进详情
            assertThat(item.getSubmittedBy()).isEqualTo("张三");
            assertThat(item.getDetailPath()).isEqualTo("TodoDetail/salesPriceAdjustment/spa-1");
        }

        @Test
        @DisplayName("cashier 角色不查改价域 (改价仅 finance_manager 可见)")
        void listTodos_cashier_doesNotQueryPriceAdjustment() {
            when(paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID))
                    .thenReturn(Collections.emptyList());

            service.listTodos(FACTORY_ID, "cashier");

            verifyNoInteractions(salesPriceAdjustmentService);
        }

        private SalesPriceAdjustmentRecord fakePendingAdjustment(
                String id, String orderId, BigDecimal oldPrice, BigDecimal newPrice, String adjustedByName) {
            SalesPriceAdjustmentRecord r = new SalesPriceAdjustmentRecord();
            r.setId(id);
            r.setSalesOrderId(orderId);
            r.setSalesOrderLineId(42L);
            r.setFactoryId(FACTORY_ID);
            r.setOldUnitPrice(oldPrice);
            r.setNewUnitPrice(newPrice);
            r.setAdjustedBy(99L);
            r.setAdjustedByName(adjustedByName);
            r.setApprovalRequired(true);
            r.setApprovalStatus(SalesPriceAdjustmentRecord.ApprovalStatus.PENDING);
            r.setCreatedAt(NOW.minusMinutes(10));
            r.setUpdatedAt(NOW.minusMinutes(10));
            return r;
        }
    }
}
