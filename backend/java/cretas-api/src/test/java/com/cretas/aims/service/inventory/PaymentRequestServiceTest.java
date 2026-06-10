package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PaymentRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP6 付款申请单 Service 单元测试
 *
 * <p>覆盖：
 * - create()：幂等性检查（同 PO 有活跃申请 → 409）
 * - P0 状态机：PENDING → FINANCE_REVIEW → APPROVED → PAID
 * - markPaid 三写原子：状态 + ArApTransaction + Supplier.currentBalance
 * - reject()：终态保护
 *
 * <p>D-9 gap 测试：
 * - G2：非预付类 PO 未完成入库 → 422 拒绝；PREPAID 豁免；COMPLETED 通过
 * - G3-test：markPaid null supplier balance → no NPE, balanceAfter = -amount
 * - G7：create() 继承 PO.settlementType → PR.settlementType
 * - G1：listApprovedForPaymentWithDetails 返回 DTO（含供应商名/PO单号/明细行），N+1 安全
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentRequestServiceImpl 单元测试 (SP6 + D-9)")
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private ArApTransactionRepository arApTransactionRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @InjectMocks
    private PaymentRequestServiceImpl paymentRequestService;

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-001";
    private static final String SUPPLIER_ID = "SUP-001";

    @BeforeEach
    void setup() {
        when(paymentRequestRepository.save(any(PaymentRequest.class)))
                .thenAnswer(inv -> {
                    PaymentRequest pr = inv.getArgument(0);
                    if (pr.getId() == null) pr.setId(java.util.UUID.randomUUID().toString());
                    return pr;
                });
        // Default: PO not found (降级降级，不阻塞) — override in specific tests
        when(purchaseOrderRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    // ═══════════════════════════════════════════════════════════════════
    // create() 幂等性
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("create() 幂等性检查")
    class CreateIdempotencyTests {

        @Test
        @DisplayName("同 PO 已有 PENDING 申请 → BusinessException (409)")
        void existingPendingRequest_throwsBusinessException() {
            PaymentRequest existing = new PaymentRequest();
            existing.setId("PR-001");
            existing.setStatus(PaymentRequestStatus.PENDING);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of(existing));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        @Test
        @DisplayName("同 PO 已有 APPROVED 申请 → BusinessException (409)")
        void existingApprovedRequest_throwsBusinessException() {
            PaymentRequest existing = new PaymentRequest();
            existing.setId("PR-001");
            existing.setStatus(PaymentRequestStatus.APPROVED);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of(existing));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        @Test
        @DisplayName("同 PO 无活跃申请、无 PO 记录（降级）→ 正常创建")
        void noActiveRequest_createSucceeds() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of());
            // PO not found → 降级 skip G2 check

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
            assertEquals(PaymentRequestStatus.PENDING, result.getStatus());
        }

        @Test
        @DisplayName("新建申请金额正确保存")
        void createSetsAmountCorrectly() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of());

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(8888.88), "transfer", 1L, null);

            assertEquals(0, BigDecimal.valueOf(8888.88).compareTo(result.getAmount()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // D-9 G2：全入库前置检查
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D-9 G2：全入库前置检查（非预付类须 PO COMPLETED）")
    class G2FullReceiptCheckTests {

        @Test
        @DisplayName("G2：非 PREPAID、PO 未完成（DRAFT）→ 422 BusinessException，含友好 hint")
        void nonPrepaid_poNotCompleted_throws422() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.DRAFT, SettlementType.CREDIT_PERIOD);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));

            // Verify 422 status code
            assertThat(ex.getCode()).isEqualTo(422);
            // Verify the hint contains PO number and current status (fool-proof 4-element)
            assertThat(ex.getMessage()).contains("PO-TEST-001");
            assertThat(ex.getMessage()).contains("已完成");  // expected target state
            assertThat(ex.getMessage()).contains("草稿");    // current status displayName
        }

        @Test
        @DisplayName("G2：非 PREPAID、PO PARTIAL_RECEIVED（未全入库）→ 拒绝")
        void nonPrepaid_poInPartialReceived_throws422() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.PARTIAL_RECEIVED, SettlementType.MONTHLY);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        @Test
        @DisplayName("G2：非 PREPAID、PO COMPLETED → 允许创建")
        void nonPrepaid_poCompleted_allowsCreate() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.COMPLETED, SettlementType.CREDIT_PERIOD);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
            assertEquals(PaymentRequestStatus.PENDING, result.getStatus());
        }

        @Test
        @DisplayName("G2：PREPAID 结算类型 + PO 未完成 → 豁免，允许创建（先付后货）")
        void prepaid_poNotCompleted_exempt_allowsCreate() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.DRAFT, SettlementType.PREPAID);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
            assertEquals(PaymentRequestStatus.PENDING, result.getStatus());
        }

        @Test
        @DisplayName("G2：PO 不存在 → 降级跳过检查，允许创建")
        void poNotFound_gracefulDegradation_allowsCreate() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
        }

        @Test
        @DisplayName("G2：PO settlementType 为 null（老数据）+ PO COMPLETED → 允许（null 不豁免但 COMPLETED 通过）")
        void nullSettlementType_poCompleted_allowsCreate() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.COMPLETED, null);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            // null settlementType is not in PREPAID_EXEMPT → must be COMPLETED → passes
            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
        }

        @Test
        @DisplayName("G2：PO settlementType 为 null + PO DRAFT → 拒绝（null 视为普通结算类型，要求全入库）")
        void nullSettlementType_poNotCompleted_throws() {
            PurchaseOrder po = makePo(PO_ID, PurchaseOrderStatus.DRAFT, null);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        private PurchaseOrder makePo(String id, PurchaseOrderStatus status, SettlementType st) {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(id);
            po.setOrderNumber("PO-TEST-001");
            po.setStatus(status);
            po.setSettlementType(st);
            return po;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // D-9 G7：settlementType 继承
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D-9 G7：create() 继承 PO.settlementType")
    class G7SettlementTypeInheritanceTests {

        @Test
        @DisplayName("G7：PO 有 MONTHLY settlementType → PR.settlementType = MONTHLY")
        void inheritMonthlySettlementType() {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-TEST-002");
            po.setStatus(PurchaseOrderStatus.COMPLETED);
            po.setSettlementType(SettlementType.MONTHLY);

            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertEquals(SettlementType.MONTHLY, result.getSettlementType());
        }

        @Test
        @DisplayName("G7：PO 有 CREDIT_PERIOD settlementType → PR 继承")
        void inheritCreditPeriodSettlementType() {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-TEST-003");
            po.setStatus(PurchaseOrderStatus.COMPLETED);
            po.setSettlementType(SettlementType.CREDIT_PERIOD);

            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(3000), "bank", 1L, null);

            assertEquals(SettlementType.CREDIT_PERIOD, result.getSettlementType());
        }

        @Test
        @DisplayName("G7：PO 未找到 → PR.settlementType = null（降级，不阻塞）")
        void poNotFound_settlementTypeNull() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNull(result.getSettlementType(),
                    "G7 降级：PO不存在时 settlementType 应为 null，不抛异常");
        }

        @Test
        @DisplayName("G7：PO.settlementType 为 null → PR.settlementType = null（老数据兼容）")
        void poNullSettlementType_inheritsNull() {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-TEST-004");
            po.setStatus(PurchaseOrderStatus.COMPLETED);
            po.setSettlementType(null);  // 老数据无结算方式

            when(paymentRequestRepository.findActiveByPurchaseOrderId(eq(PO_ID), anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNull(result.getSettlementType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // D-9 G3-test：markPaid null supplier balance → no NPE
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D-9 G3-test：markPaid null supplier balance NPE 回归测试（#675 修复验证）")
    class G3NullBalanceRegressionTests {

        @Test
        @DisplayName("G3：supplier.currentBalance = null → 不抛 NPE，tx.balanceAfter = -amount")
        void nullSupplierBalance_noNpe_balanceAfterEqualsNegativeAmount() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-G3");
            pr.setFactoryId(FACTORY_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setStatus(PaymentRequestStatus.APPROVED);
            pr.setAmount(BigDecimal.valueOf(5000));

            // supplier with null balance (未初始化账户 — #675 root cause)
            Supplier supplier = new Supplier();
            supplier.setId(SUPPLIER_ID);
            supplier.setCurrentBalance(null);   // ← 触发原始 NPE 的条件

            when(paymentRequestRepository.findById("PR-G3")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> {
                var tx = inv.getArgument(0);
                ((com.cretas.aims.entity.finance.ArApTransaction) tx).setId("TX-001");
                return tx;
            });

            // Must not throw NPE (#675 regression guard)
            assertDoesNotThrow(() -> paymentRequestService.markPaid("PR-G3", 1L, null));

            // tx.balanceAfter must be -5000 (0 - 5000)
            ArgumentCaptor<com.cretas.aims.entity.finance.ArApTransaction> txCaptor =
                    ArgumentCaptor.forClass(com.cretas.aims.entity.finance.ArApTransaction.class);
            verify(arApTransactionRepository).save(txCaptor.capture());
            BigDecimal expected = BigDecimal.ZERO.subtract(BigDecimal.valueOf(5000));
            assertThat(txCaptor.getValue().getBalanceAfter())
                    .as("G3: tx.balanceAfter must be -5000 when starting from null balance")
                    .isEqualByComparingTo(expected);

            // status must be PAID
            ArgumentCaptor<PaymentRequest> prCaptor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository, atLeastOnce()).save(prCaptor.capture());
            boolean hasPaid = prCaptor.getAllValues().stream()
                    .anyMatch(p -> PaymentRequestStatus.PAID.equals(p.getStatus()));
            assertTrue(hasPaid, "G3: PR status must become PAID");
        }

        @Test
        @DisplayName("G3：supplier.currentBalance = ZERO → 不抛 NPE，tx.balanceAfter = -amount")
        void zeroSupplierBalance_noNpe_balanceAfterNegative() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-G3B");
            pr.setFactoryId(FACTORY_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setStatus(PaymentRequestStatus.APPROVED);
            pr.setAmount(BigDecimal.valueOf(3000));

            Supplier supplier = new Supplier();
            supplier.setId(SUPPLIER_ID);
            supplier.setCurrentBalance(BigDecimal.ZERO);

            when(paymentRequestRepository.findById("PR-G3B")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> {
                var tx = inv.getArgument(0);
                ((com.cretas.aims.entity.finance.ArApTransaction) tx).setId("TX-002");
                return tx;
            });

            assertDoesNotThrow(() -> paymentRequestService.markPaid("PR-G3B", 1L, null));

            ArgumentCaptor<com.cretas.aims.entity.finance.ArApTransaction> txCaptor =
                    ArgumentCaptor.forClass(com.cretas.aims.entity.finance.ArApTransaction.class);
            verify(arApTransactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getBalanceAfter())
                    .isEqualByComparingTo(BigDecimal.valueOf(-3000));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // D-9 G1：listApprovedForPaymentWithDetails
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D-9 G1：listApprovedForPaymentWithDetails 出纳视图 DTO")
    class G1ApprovedDtoTests {

        @Test
        @DisplayName("G1：返回 DTO 包含供应商名称和 PO 单号（非裸 UUID）")
        void returnsDtoWithSupplierNameAndPoNumber() {
            PaymentRequest pr = makeApprovedPr("PR-G1-001");

            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-F006-20261014-01");

            Supplier supplier = new Supplier();
            supplier.setId(SUPPLIER_ID);
            supplier.setName("六扇门肉类供应商");

            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(List.of(pr));
            when(purchaseOrderRepository.findAllById(anyList()))
                    .thenReturn(List.of(po));
            when(supplierRepository.findAllById(anyList()))
                    .thenReturn(List.of(supplier));
            when(purchaseOrderItemRepository.findByPurchaseOrderIdIn(anyList()))
                    .thenReturn(Collections.emptyList());

            List<PaymentRequestApprovedDTO> results =
                    paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            assertThat(results).hasSize(1);
            PaymentRequestApprovedDTO dto = results.get(0);
            assertThat(dto.getSupplierName()).isEqualTo("六扇门肉类供应商");
            assertThat(dto.getPurchaseOrderNumber()).isEqualTo("PO-F006-20261014-01");
            assertThat(dto.getSupplierId()).isEqualTo(SUPPLIER_ID);
        }

        @Test
        @DisplayName("G1：返回 DTO 包含原料行明细（materialName/quantity/unit/unitPrice/lineAmount）")
        void returnsDtoWithItemLines() {
            PaymentRequest pr = makeApprovedPr("PR-G1-002");

            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-F006-20261014-02");

            Supplier supplier = new Supplier();
            supplier.setId(SUPPLIER_ID);
            supplier.setName("供应商A");

            PurchaseOrderItem item1 = new PurchaseOrderItem();
            item1.setId(101L);
            item1.setPurchaseOrderId(PO_ID);
            item1.setMaterialName("猪前腿肉");
            item1.setQuantity(BigDecimal.valueOf(100));
            item1.setUnit("kg");
            item1.setUnitPrice(BigDecimal.valueOf(25.5));

            PurchaseOrderItem item2 = new PurchaseOrderItem();
            item2.setId(102L);
            item2.setPurchaseOrderId(PO_ID);
            item2.setMaterialName("盐");
            item2.setQuantity(BigDecimal.valueOf(10));
            item2.setUnit("kg");
            item2.setUnitPrice(BigDecimal.valueOf(2.0));

            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(List.of(pr));
            when(purchaseOrderRepository.findAllById(anyList()))
                    .thenReturn(List.of(po));
            when(supplierRepository.findAllById(anyList()))
                    .thenReturn(List.of(supplier));
            when(purchaseOrderItemRepository.findByPurchaseOrderIdIn(anyList()))
                    .thenReturn(List.of(item1, item2));

            List<PaymentRequestApprovedDTO> results =
                    paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            assertThat(results).hasSize(1);
            List<PaymentRequestApprovedDTO.ItemLine> items = results.get(0).getItems();
            assertThat(items).hasSize(2);

            PaymentRequestApprovedDTO.ItemLine line1 = items.stream()
                    .filter(i -> "猪前腿肉".equals(i.getMaterialName())).findFirst().orElseThrow();
            assertThat(line1.getQuantity()).isEqualByComparingTo("100");
            assertThat(line1.getUnit()).isEqualTo("kg");
            assertThat(line1.getUnitPrice()).isEqualByComparingTo("25.5");
            // lineAmount = 100 * 25.5 = 2550
            assertThat(line1.getLineAmount()).isEqualByComparingTo("2550");
        }

        @Test
        @DisplayName("G1：N+1 安全：多个 PR 仅触发 1 次 items batch query（非逐条查询）")
        void n1Safe_batchLoadItems_singleQuery() {
            // 3 PRs, 3 different POs
            PaymentRequest pr1 = makeApprovedPr("PR-N1-001");
            pr1.setPurchaseOrderId("PO-A");
            PaymentRequest pr2 = makeApprovedPr("PR-N1-002");
            pr2.setPurchaseOrderId("PO-B");
            PaymentRequest pr3 = makeApprovedPr("PR-N1-003");
            pr3.setPurchaseOrderId("PO-C");

            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(List.of(pr1, pr2, pr3));
            when(purchaseOrderRepository.findAllById(anyList()))
                    .thenReturn(Collections.emptyList());
            when(supplierRepository.findAllById(anyList()))
                    .thenReturn(Collections.emptyList());
            when(purchaseOrderItemRepository.findByPurchaseOrderIdIn(anyList()))
                    .thenReturn(Collections.emptyList());

            paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            // Must call findByPurchaseOrderIdIn exactly ONCE (batch) — NOT 3 separate calls
            verify(purchaseOrderItemRepository, times(1))
                    .findByPurchaseOrderIdIn(anyList());
        }

        @Test
        @DisplayName("G1：空列表 → 返回空 list，不查 DB 第二步")
        void emptyApproved_returnsEmptyList() {
            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(Collections.emptyList());

            List<PaymentRequestApprovedDTO> result =
                    paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            assertThat(result).isEmpty();
            // no batch queries needed
            verifyNoInteractions(purchaseOrderRepository);
            verifyNoInteractions(purchaseOrderItemRepository);
        }

        @Test
        @DisplayName("G1：跨工厂隔离：仅查本工厂 APPROVED 单据")
        void crossFactoryIsolation_onlyQueriesOwnFactory() {
            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(Collections.emptyList());

            paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            // Verify the repository was queried with the correct factoryId
            verify(paymentRequestRepository).findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED);
        }

        @Test
        @DisplayName("G1：DTO 包含 settlementType 和显示名（供出纳核实结算方式）")
        void dtoContainsSettlementTypeAndDisplayName() {
            PaymentRequest pr = makeApprovedPr("PR-G1-ST");
            pr.setSettlementType(SettlementType.MONTHLY);

            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setOrderNumber("PO-G1-ST");

            Supplier supplier = new Supplier();
            supplier.setId(SUPPLIER_ID);
            supplier.setName("月结供应商");

            when(paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                    FACTORY_ID, PaymentRequestStatus.APPROVED))
                    .thenReturn(List.of(pr));
            when(purchaseOrderRepository.findAllById(anyList())).thenReturn(List.of(po));
            when(supplierRepository.findAllById(anyList())).thenReturn(List.of(supplier));
            when(purchaseOrderItemRepository.findByPurchaseOrderIdIn(anyList()))
                    .thenReturn(Collections.emptyList());

            List<PaymentRequestApprovedDTO> results =
                    paymentRequestService.listApprovedForPaymentWithDetails(FACTORY_ID);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getSettlementType()).isEqualTo(SettlementType.MONTHLY);
            assertThat(results.get(0).getSettlementTypeDisplayName())
                    .isEqualTo(SettlementType.MONTHLY.getDisplayName());
        }

        private PaymentRequest makeApprovedPr(String id) {
            PaymentRequest pr = new PaymentRequest();
            pr.setId(id);
            pr.setFactoryId(FACTORY_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setStatus(PaymentRequestStatus.APPROVED);
            pr.setAmount(BigDecimal.valueOf(5000));
            pr.setApprovedBy(2L);
            pr.setApprovedAt(LocalDateTime.now());
            pr.setCreatedBy(1L);
            pr.setRequestNumber("PR-" + id);
            return pr;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // P0 状态机（保留原有测试）
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P0 状态机转换")
    class StateMachineTests {

        private PaymentRequest pendingRequest() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-001");
            pr.setFactoryId(FACTORY_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setStatus(PaymentRequestStatus.PENDING);
            pr.setAmount(BigDecimal.valueOf(5000));
            pr.setCreatedBy(1L);
            return pr;
        }

        private PaymentRequest financeReviewRequest() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
            return pr;
        }

        private PaymentRequest approvedRequest() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.APPROVED);
            return pr;
        }

        @Test
        @DisplayName("PENDING → submit → FINANCE_REVIEW")
        void submit_transitionsToPendingFinanceReview() {
            PaymentRequest pr = pendingRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.submit("PR-001", 1L);

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.FINANCE_REVIEW, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("FINANCE_REVIEW → financeApprove → APPROVED")
        void financeApprove_transitionsToApproved() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.financeApprove("PR-001", 2L, "财务确认金额无误");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.APPROVED, captor.getValue().getStatus());
            assertEquals(2L, captor.getValue().getFinanceReviewedBy());
        }

        @Test
        @DisplayName("APPROVED → reject → REJECTED")
        void reject_transitionsToRejected() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.reject("PR-001", 2L, "金额有误");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.REJECTED, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("PAID 状态不能再提交 → BusinessException")
        void submitOnPaid_throwsBusinessException() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.PAID);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.submit("PR-001", 1L));
        }

        @Test
        @DisplayName("REJECTED 状态不能再 financeApprove → BusinessException")
        void financeApproveOnRejected_throwsBusinessException() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.REJECTED);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.financeApprove("PR-001", 2L, null));
        }

        @Test
        @DisplayName("申请单不存在 → BusinessException")
        void unknownId_throwsBusinessException() {
            when(paymentRequestRepository.findById("MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.submit("MISSING", 1L));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // markPaid 三写原子（保留原有测试，补充 null balance）
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markPaid 三写原子事务")
    class MarkPaidTests {

        private PaymentRequest approvedRequest() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-001");
            pr.setFactoryId(FACTORY_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setStatus(PaymentRequestStatus.APPROVED);
            pr.setAmount(BigDecimal.valueOf(5000));
            pr.setCreatedBy(1L);
            return pr;
        }

        @Test
        @DisplayName("markPaid：申请单状态变 PAID")
        void markPaid_statusBecomePaid() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(
                    makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000))));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, "付款截图");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository, atLeastOnce()).save(captor.capture());
            boolean hasPaid = captor.getAllValues().stream()
                    .anyMatch(p -> PaymentRequestStatus.PAID.equals(p.getStatus()));
            assertTrue(hasPaid);
        }

        @Test
        @DisplayName("markPaid：ArApTransaction 已保存（AP_PAYMENT 类型）")
        void markPaid_createsArApTransaction() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(
                    makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000))));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, null);

            // ArApTransaction must be saved with correct amount
            verify(arApTransactionRepository).save(argThat(tx ->
                    tx.getAmount().compareTo(BigDecimal.valueOf(5000)) == 0));
        }

        @Test
        @DisplayName("markPaid：供应商账户余额扣减（10000 - 5000 = 5000）")
        void markPaid_decreasesSupplierBalance() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            Supplier supplier = makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, null);

            // Supplier balance should be reduced by 5000
            verify(supplierRepository).save(argThat(s ->
                    s.getCurrentBalance().compareTo(BigDecimal.valueOf(5000)) == 0));
        }

        @Test
        @DisplayName("markPaid：非 APPROVED 状态 → BusinessException，无写入")
        void markPaidOnNonApproved_throwsBusinessException() {
            PaymentRequest pr = approvedRequest();
            pr.setStatus(PaymentRequestStatus.PENDING);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.markPaid("PR-001", 3L, null));

            verifyNoInteractions(arApTransactionRepository, supplierRepository);
        }

        @Test
        @DisplayName("markPaid：申请单不存在 → BusinessException")
        void markPaidMissingId_throwsBusinessException() {
            when(paymentRequestRepository.findById("MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.markPaid("MISSING", 3L, null));
        }

        private Supplier makeSupplier(String id, BigDecimal balance) {
            Supplier s = new Supplier();
            s.setId(id);
            s.setCurrentBalance(balance);
            return s;
        }
    }
}
