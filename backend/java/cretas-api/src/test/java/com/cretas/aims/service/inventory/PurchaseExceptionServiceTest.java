package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveDecisionStatus;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.inventory.impl.PurchaseExceptionServiceImpl;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP6 采购异常单 Service 单元测试
 *
 * <p>覆盖：
 * - generateExceptionsForReceive：超收/少收检测逻辑、责任人字段设置（D-6）
 * - decideException：ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY
 * - D-6 鉴权：责任人可决策 / 非责任人 403 / 主管角色可代为决策
 * - 边界：正常收货无异常单、异常单重复创建防卫
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseExceptionServiceImpl 单元测试 (SP6)")
class PurchaseExceptionServiceTest {

    @Mock
    private PurchaseExceptionRepository exceptionRepository;

    @Mock
    private PurchaseReceiveRecordRepository receiveRecordRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private ReturnOrderService returnOrderService;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @InjectMocks
    private PurchaseExceptionServiceImpl exceptionService;

    private static final String FACTORY_ID = "F006";
    private static final String RECEIVE_ID = "REC-001";
    private static final String PO_ID = "PO-001";

    /** 责任人 userId (ownerUserId) */
    private static final Long OWNER_USER_ID = 42L;
    /** 非责任人 userId */
    private static final Long OTHER_USER_ID = 99L;

    @BeforeEach
    void setup() {
        // Repository save: return the same entity with an id assigned
        when(exceptionRepository.save(any(PurchaseException.class)))
                .thenAnswer(inv -> {
                    PurchaseException e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(java.util.UUID.randomUUID().toString());
                    return e;
                });
    }

    // ───────────────────────────────────────────────────────────────────────
    // generateExceptionsForReceive
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generateExceptionsForReceive")
    class GenerateExceptionsTests {

        @Test
        @DisplayName("正常收货（实收 = PO 量）不生成异常单")
        void noException_whenReceivedEqualsOrdered() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(100), "kg", 1L, null, null);
            assertTrue(result.isEmpty(), "正常收货不应产生异常单");
            verify(exceptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("超收（实收 > PO 量）生成 OVER_RECEIVE 异常单")
        void overReceive_generatesOverException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L, null, null);

            assertEquals(1, result.size());
            PurchaseException ex = result.get(0);
            assertEquals(ReceiveExceptionType.OVER_RECEIVE, ex.getExceptionType());
            assertEquals(0, BigDecimal.TEN.compareTo(ex.getExceptionQty()),
                    "异常数量应为 110 - 100 = 10");
            assertEquals("PENDING", ex.getStatus());
        }

        @Test
        @DisplayName("少收（实收 < PO 量）生成 UNDER_RECEIVE 异常单")
        void underReceive_generatesUnderException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(85), "kg", 1L, null, null);

            assertEquals(1, result.size());
            PurchaseException ex = result.get(0);
            assertEquals(ReceiveExceptionType.UNDER_RECEIVE, ex.getExceptionType());
            assertEquals(0, BigDecimal.valueOf(15).compareTo(ex.getExceptionQty()));
        }

        @Test
        @DisplayName("factoryId 传入正确")
        void factoryIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L, null, null);
            assertEquals(FACTORY_ID, result.get(0).getFactoryId());
        }

        @Test
        @DisplayName("receiveRecordId 传入正确")
        void receiveRecordIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L, null, null);
            assertEquals(RECEIVE_ID, result.get(0).getReceiveRecordId());
        }

        @Test
        @DisplayName("purchaseOrderId 传入正确")
        void purchaseOrderIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L, null, null);
            assertEquals(PO_ID, result.get(0).getPurchaseOrderId());
        }

        @Test
        @DisplayName("D-6：ownerUserId / ownerName 写入异常单")
        void ownerBinding_setsOwnerFields() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(120), "kg",
                    1L, OWNER_USER_ID, "张三");

            assertEquals(1, result.size());
            PurchaseException ex = result.get(0);
            assertEquals(OWNER_USER_ID, ex.getOwnerUserId(), "ownerUserId 应写入异常单");
            assertEquals("张三", ex.getOwnerName(), "ownerName 应写入异常单");
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // decideException
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("decideException")
    class DecideExceptionTests {

        private PurchaseException pendingOverException() {
            PurchaseException ex = new PurchaseException();
            ex.setId("EXC-001");
            ex.setFactoryId(FACTORY_ID);
            ex.setExceptionType(ReceiveExceptionType.OVER_RECEIVE);
            ex.setStatus("PENDING");
            ex.setReceiveRecordId(RECEIVE_ID);
            ex.setPurchaseOrderId(PO_ID);
            // BLOCKER 3: RETURN_OVER 路径需要 supplierId (否则前置校验 422)。
            ex.setSupplierId("SUP-DEFAULT");
            ex.setExceptionQty(BigDecimal.TEN);
            ex.setCreatedBy(1L);
            ex.setOwnerUserId(OWNER_USER_ID);
            ex.setOwnerName("张三");
            return ex;
        }

        private PurchaseException pendingUnderException() {
            PurchaseException ex = new PurchaseException();
            ex.setId("EXC-002");
            ex.setFactoryId(FACTORY_ID);
            ex.setExceptionType(ReceiveExceptionType.UNDER_RECEIVE);
            ex.setStatus("PENDING");
            ex.setReceiveRecordId(RECEIVE_ID);
            ex.setPurchaseOrderId(PO_ID);
            ex.setExceptionQty(BigDecimal.valueOf(15));
            ex.setCreatedBy(1L);
            ex.setOwnerUserId(OWNER_USER_ID);
            ex.setOwnerName("张三");
            return ex;
        }

        @Test
        @DisplayName("ACCEPT_OVER：状态变 RESOLVED，decision 已记录")
        void acceptOver_resolvesException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.ACCEPT_OVER,
                    "接受超收", OWNER_USER_ID, null);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            PurchaseException saved = captor.getValue();
            assertEquals("RESOLVED", saved.getStatus());
            assertEquals(ExceptionDecision.ACCEPT_OVER, saved.getDecision());
        }

        @Test
        @DisplayName("RETURN_OVER：状态变 RESOLVED，decision 已记录")
        void returnOver_resolvesException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenReturn(stubReturnOrder("RO-1", "RT-PUR-20260101-0001"));

            exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                    "退回多余", OWNER_USER_ID, null);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository, atLeastOnce()).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("ACCEPT_SHORT：状态变 RESOLVED")
        void acceptShort_resolvesException() {
            PurchaseException ex = pendingUnderException();
            when(exceptionRepository.findById("EXC-002")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-002", FACTORY_ID, ExceptionDecision.ACCEPT_SHORT,
                    null, OWNER_USER_ID, null);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("REQUEST_RESUPPLY：状态变 RESOLVED")
        void requestResupply_resolvesException() {
            PurchaseException ex = pendingUnderException();
            when(exceptionRepository.findById("EXC-002")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-002", FACTORY_ID, ExceptionDecision.REQUEST_RESUPPLY,
                    null, OWNER_USER_ID, null);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("异常单不存在 → BusinessException")
        void unknownId_throwsBusinessException() {
            when(exceptionRepository.findById("EXC-MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-MISSING", FACTORY_ID,
                            ExceptionDecision.ACCEPT_OVER, null, OWNER_USER_ID, null));
        }

        @Test
        @DisplayName("已 RESOLVED 异常单重复决策 → BusinessException")
        void alreadyResolved_throwsBusinessException() {
            PurchaseException ex = pendingOverException();
            ex.setStatus("RESOLVED");
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-001", FACTORY_ID,
                            ExceptionDecision.ACCEPT_OVER, null, OWNER_USER_ID, null));
        }

        @Test
        @DisplayName("超收异常使用少收决策 → BusinessException（类型不兼容）")
        void overExceptionWithUnderDecision_throwsBusinessException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-001", FACTORY_ID,
                            ExceptionDecision.ACCEPT_SHORT, null, OWNER_USER_ID, null));
        }

        // ── D-6 鉴权测试 ────────────────────────────────────────────────────

        @Test
        @DisplayName("D-6：责任人本人（ownerUserId 匹配）可以决策")
        void owner_canDecide() {
            PurchaseException ex = pendingOverException();  // ownerUserId = OWNER_USER_ID
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            // 责任人本人（非主管角色）决策应成功
            assertDoesNotThrow(() ->
                    exceptionService.decideException("EXC-001", FACTORY_ID,
                            ExceptionDecision.ACCEPT_OVER, null, OWNER_USER_ID, "procurement_staff"));

            verify(exceptionRepository).save(any(PurchaseException.class));
        }

        @Test
        @DisplayName("D-6：非责任人非主管 → BusinessException（403），message 包含责任人信息")
        void nonOwnerNonSupervisor_throwsBusinessException_with_ownerInfo() {
            PurchaseException ex = pendingOverException();  // ownerUserId = OWNER_USER_ID = 42
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-001", FACTORY_ID,
                            ExceptionDecision.ACCEPT_OVER, null, OTHER_USER_ID, "operator"));

            // 错误信息必须指出责任人（Fool-proof Rule 2）
            assertTrue(ex2.getMessage().contains("张三") || ex2.getMessage().contains("42"),
                    "错误信息应包含责任人名称或 userId");
        }

        @Test
        @DisplayName("D-6：主管角色（procurement_manager）可以代为决策")
        void supervisorRole_canDecideForOthers() {
            PurchaseException ex = pendingOverException();  // ownerUserId = OWNER_USER_ID
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            // OTHER_USER_ID 不是责任人，但有 procurement_manager 角色
            assertDoesNotThrow(() ->
                    exceptionService.decideException("EXC-001", FACTORY_ID,
                            ExceptionDecision.ACCEPT_OVER, null, OTHER_USER_ID, "procurement_manager"));

            verify(exceptionRepository, atLeastOnce()).save(any(PurchaseException.class));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // D-BUG3: RETURN_OVER 决策 → 创建 PURCHASE_RETURN 退货单 + 财务审批链可达
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("decideException(RETURN_OVER) 创建采购退货单 (D-BUG3)")
    class ReturnOverCreatesReturnOrderTests {

        private static final String PO_ID = "PO-RT-001";
        private static final String SUPPLIER_ID = "SUP-001";
        private static final String MATERIAL_TYPE_ID = "MAT-猪大肠";

        /** 超收异常: PO 100, 实收 130 → exceptionQty = 30 (超收差值)。 */
        private PurchaseException pendingOverException30() {
            PurchaseException ex = new PurchaseException();
            ex.setId("EXC-RT-001");
            ex.setFactoryId(FACTORY_ID);
            ex.setExceptionNumber("EX-F006-20260101-0001");
            ex.setExceptionType(ReceiveExceptionType.OVER_RECEIVE);
            ex.setStatus("PENDING");
            ex.setReceiveRecordId(RECEIVE_ID);
            ex.setPurchaseOrderId(PO_ID);
            ex.setSupplierId(SUPPLIER_ID);
            ex.setMaterialTypeId(MATERIAL_TYPE_ID);
            ex.setMaterialName("猪大肠");
            ex.setPoQuantity(BigDecimal.valueOf(100));
            ex.setReceivedQuantity(BigDecimal.valueOf(130));
            ex.setExceptionQty(BigDecimal.valueOf(30));
            ex.setUnit("kg");
            ex.setCreatedBy(1L);
            ex.setOwnerUserId(OWNER_USER_ID);
            ex.setOwnerName("张三");
            return ex;
        }

        @Test
        @DisplayName("RETURN_OVER 创建 PURCHASE_RETURN 退货单, DRAFT (不自动审批), 数量=超收差值, 单价来自源 PO")
        void returnOver_createsPurchaseReturnOrder() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));

            // 源采购单该物料行单价 = 10.00
            PurchaseOrderItem poItem = new PurchaseOrderItem();
            poItem.setMaterialTypeId(MATERIAL_TYPE_ID);
            poItem.setUnitPrice(new BigDecimal("10.0000"));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(poItem));

            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(CreateReturnOrderRequest.class), eq(OWNER_USER_ID)))
                    .thenReturn(stubReturnOrder("RO-NEW", "RT-PUR-20260101-0001"));

            exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                    "拒收超收部分", OWNER_USER_ID, null);

            ArgumentCaptor<CreateReturnOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateReturnOrderRequest.class);
            verify(returnOrderService).createReturnOrder(eq(FACTORY_ID), reqCaptor.capture(), eq(OWNER_USER_ID));
            CreateReturnOrderRequest req = reqCaptor.getValue();

            assertEquals(ReturnType.PURCHASE_RETURN.name(), req.getReturnType(), "退货类型应为 PURCHASE_RETURN");
            assertEquals(SUPPLIER_ID, req.getCounterpartyId(), "counterparty 应为供应商");
            assertEquals(PO_ID, req.getSourceOrderId(), "源单应为采购单");
            assertEquals(1, req.getItems().size());

            CreateReturnOrderRequest.ReturnOrderItemDTO item = req.getItems().get(0);
            assertEquals(0, BigDecimal.valueOf(30).compareTo(item.getQuantity()),
                    "退货数量应等于超收差值 130-100=30");
            assertEquals(MATERIAL_TYPE_ID, item.getMaterialTypeId());
            assertEquals(0, new BigDecimal("10.0000").compareTo(item.getUnitPrice()),
                    "退货单价应取自源 PO 行项目");

            // 异常单回写 returnOrderId (关联可追溯)
            assertEquals("RO-NEW", ex.getReturnOrderId(), "异常单应回写退货单 ID");
            // 退货单初始状态由 ReturnOrderService 强制为 DRAFT, 此处不自动审批 (客户红线: 先财务审批)。
        }

        @Test
        @DisplayName("幂等: 已有 returnOrderId 的异常单重复 RETURN_OVER 不重复创建退货单")
        void returnOver_idempotent_doesNotRecreate() {
            PurchaseException ex = pendingOverException30();
            // 模拟历史脏数据 / 并发: 异常单仍 PENDING 但已挂 returnOrderId。
            // (正常路径 PENDING 守卫已拦重复决策; 此为双保险测试。)
            ex.setReturnOrderId("RO-EXISTING");
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                    null, OWNER_USER_ID, null);

            verify(returnOrderService, never()).createReturnOrder(anyString(), any(), anyLong());
            assertEquals("RO-EXISTING", ex.getReturnOrderId(), "returnOrderId 不应被覆盖");
        }

        @Test
        @DisplayName("退货单价: 源 PO 无对应物料行 → 单价 null, 退货单仍创建 (不阻断)")
        void returnOver_noPoUnitPrice_stillCreates() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of()); // 无行
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenReturn(stubReturnOrder("RO-NEW2", "RT-PUR-20260101-0002"));

            exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                    null, OWNER_USER_ID, null);

            ArgumentCaptor<CreateReturnOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateReturnOrderRequest.class);
            verify(returnOrderService).createReturnOrder(eq(FACTORY_ID), reqCaptor.capture(), eq(OWNER_USER_ID));
            assertNull(reqCaptor.getValue().getItems().get(0).getUnitPrice(), "无 PO 单价时退货单价应为 null");
            assertEquals("RO-NEW2", ex.getReturnOrderId());
        }

        @Test
        @DisplayName("退货单创建失败 (REQUIRES_NEW 隔离) → 异常单仍标 RESOLVED, 不回滚")
        void returnOver_returnOrderCreationFails_exceptionStillResolved() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of());
            // 退货单创建抛异常 (模拟 doomed-tx / 校验失败)
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenThrow(new RuntimeException("退货单创建失败 (模拟)"));

            // 异常单决策本身不应抛 (失败被隔离 + catch)
            assertDoesNotThrow(() ->
                    exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                            null, OWNER_USER_ID, null));

            assertEquals("RESOLVED", ex.getStatus(), "退货单创建失败不应回滚异常单 RESOLVED");
            assertNull(ex.getReturnOrderId(), "退货单创建失败, 不应回写 returnOrderId");
        }

        @Test
        @DisplayName("跨租户: 退货单 factoryId 透传异常单 factoryId (ReturnOrderService 强制隔离)")
        void returnOver_passesExceptionFactoryId() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of());
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenReturn(stubReturnOrder("RO-X", "RT-PUR-X"));

            exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                    null, OWNER_USER_ID, null);

            // 退货单创建用的 factoryId 必须 = 异常单 factoryId (不是任意传入), 跨租户安全。
            verify(returnOrderService).createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID));
        }

        @Test
        @DisplayName("ACCEPT_OVER (非退货决策) 不创建退货单")
        void acceptOver_noReturnOrderCreated() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.ACCEPT_OVER,
                    "接受超收全部入库", OWNER_USER_ID, null);

            verify(returnOrderService, never()).createReturnOrder(anyString(), any(), anyLong());
            assertNull(ex.getReturnOrderId());
        }

        // ── BLOCKER 3: null supplierId 不能静默丢退货 ────────────────────────────

        @Test
        @DisplayName("BLOCKER 3: supplierId 为 null → 422, 不变 RESOLVED, 不创建退货单 (禁降级假成功)")
        void returnOver_nullSupplier_throws422_notResolved() {
            PurchaseException ex = pendingOverException30();
            ex.setSupplierId(null); // 无供应商信息
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));

            BusinessException be = assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                            "拒收超收部分", OWNER_USER_ID, null));

            assertEquals(Integer.valueOf(422), be.getCode(), "缺供应商应返 422");
            assertTrue(be.getMessage().contains("供应商"), "错误信息应说明缺供应商");
            // 决策必须在 RESOLVED flip 之前被拦截: 状态保持 PENDING, 无退货单, 无 save。
            assertEquals("PENDING", ex.getStatus(), "校验失败不应翻 RESOLVED");
            assertNull(ex.getReturnOrderId(), "校验失败不应创建退货单");
            verify(returnOrderService, never()).createReturnOrder(anyString(), any(), anyLong());
            verify(exceptionRepository, never()).save(any(PurchaseException.class));
        }

        @Test
        @DisplayName("BLOCKER 3: supplierId 空白串 → 422, 同 null 处理")
        void returnOver_blankSupplier_throws422() {
            PurchaseException ex = pendingOverException30();
            ex.setSupplierId("   "); // 空白
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));

            BusinessException be = assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                            null, OWNER_USER_ID, null));
            assertEquals(Integer.valueOf(422), be.getCode());
            assertEquals("PENDING", ex.getStatus());
            verify(returnOrderService, never()).createReturnOrder(anyString(), any(), anyLong());
        }

        // ── MEDIUM: fail-soft catch 收窄 — 业务规则拒绝 fail-loud ──────────────────

        @Test
        @DisplayName("MEDIUM: 退货单创建抛 BusinessException (业务拒绝) → fail-loud re-throw, 不吞成假成功")
        void returnOver_businessExceptionFromCreate_failLoud() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of());
            // ReturnOrderService 因下游业务规则拒绝 (e.g. 校验失败)
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenThrow(new BusinessException(409, "退货单下游业务校验失败"));

            BusinessException be = assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                            null, OWNER_USER_ID, null));
            assertTrue(be.getMessage().contains("下游业务校验失败"),
                    "应原样抛出下游 BusinessException (fail-loud), 不吞成 RESOLVED 假成功");
        }

        @Test
        @DisplayName("MEDIUM: 退货单创建抛非业务异常 (瞬时基础设施) → best-effort 吞, 异常单仍 RESOLVED")
        void returnOver_runtimeExceptionFromCreate_bestEffortSwallow() {
            PurchaseException ex = pendingOverException30();
            when(exceptionRepository.findById("EXC-RT-001")).thenReturn(Optional.of(ex));
            when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of());
            when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(), eq(OWNER_USER_ID)))
                    .thenThrow(new RuntimeException("瞬时 DB 连接失败"));

            // 非业务异常 best-effort 吞: 决策不抛, 异常单仍 RESOLVED (可手动补建退货单)。
            assertDoesNotThrow(() ->
                    exceptionService.decideException("EXC-RT-001", FACTORY_ID, ExceptionDecision.RETURN_OVER,
                            null, OWNER_USER_ID, null));
            assertEquals("RESOLVED", ex.getStatus());
            assertNull(ex.getReturnOrderId());
        }
    }

    /** 构造一个最小 ReturnOrder stub (DRAFT) 供 mock 返回。 */
    private static ReturnOrder stubReturnOrder(String id, String returnNumber) {
        ReturnOrder ro = new ReturnOrder();
        ro.setId(id);
        ro.setReturnNumber(returnNumber);
        ro.setReturnType(ReturnType.PURCHASE_RETURN);
        ro.setStatus(com.cretas.aims.entity.enums.ReturnOrderStatus.DRAFT);
        return ro;
    }
}
