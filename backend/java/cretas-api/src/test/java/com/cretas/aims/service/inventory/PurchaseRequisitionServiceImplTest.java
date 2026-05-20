package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import com.cretas.aims.service.inventory.impl.PurchaseRequisitionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sprint 5 Track D — PurchaseRequisitionServiceImpl tests.
 *
 * <p>Covers state transition guards + convertToPO idempotency + cross-factory 403.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseRequisitionService 状态机 + convertToPO 测试")
class PurchaseRequisitionServiceImplTest {

    @Mock private PurchaseRequisitionRepository repo;
    @Mock private PurchaseService purchaseService;

    private PurchaseRequisitionServiceImpl service;

    private static final String FACTORY = "F001";
    private static final String OTHER_FACTORY = "F999";
    private static final String PR_ID = "PR-uuid-001";
    private static final Long REQUESTER_ID = 100L;
    private static final Long APPROVER_ID = 200L;
    private static final String SUPPLIER_ID = "SUP-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseRequisitionServiceImpl(repo, purchaseService);
    }

    private PurchaseRequisition buildPr(PurchaseRequisitionStatus status, String factoryId) {
        PurchaseRequisition pr = new PurchaseRequisition();
        pr.setId(PR_ID);
        pr.setFactoryId(factoryId);
        pr.setRequisitionNumber("PR-20260519-001");
        pr.setRequesterId(REQUESTER_ID);
        pr.setStatus(status);
        Map<String, Object> item = new HashMap<>();
        item.put("materialTypeId", "MAT-001");
        item.put("materialName", "盐");
        item.put("quantity", new BigDecimal("100"));
        item.put("unit", "kg");
        pr.setRequestedItems(List.of(item));
        return pr;
    }

    // ==================== State transitions ====================

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {

        @Test
        @DisplayName("submitRequisition DRAFT → PENDING_APPROVAL by requester self ✓")
        void submitDraftByRequester() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseRequisition result = service.submitRequisition(FACTORY, PR_ID, REQUESTER_ID);

            assertEquals(PurchaseRequisitionStatus.PENDING_APPROVAL, result.getStatus());
            verify(repo).save(pr);
        }

        @Test
        @DisplayName("submitRequisition by 非 requester → 403")
        void submitByNonRequesterRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.submitRequisition(FACTORY, PR_ID, 999L));
            assertEquals(403, ex.getCode());
            assertNotNull(ex.getActionHint(), "应携带 actionHint");
        }

        @Test
        @DisplayName("approveRequisition PENDING_APPROVAL → APPROVED ✓")
        void approveOk() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.PENDING_APPROVAL, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseRequisition result = service.approveRequisition(FACTORY, PR_ID, APPROVER_ID);

            assertEquals(PurchaseRequisitionStatus.APPROVED, result.getStatus());
            assertEquals(APPROVER_ID, result.getApproverId());
            assertNotNull(result.getApprovedAt());
        }

        @Test
        @DisplayName("approveRequisition DRAFT → APPROVED 非法转换 400")
        void approveDraftRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.approveRequisition(FACTORY, PR_ID, APPROVER_ID));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("非法转换"),
                    "应抛非法转换错误, 实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("rejectRequisition with 空 reason → 400")
        void rejectWithoutReason() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rejectRequisition(FACTORY, PR_ID, APPROVER_ID, ""));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("驳回原因"));
        }

        @Test
        @DisplayName("rejectRequisition PENDING_APPROVAL → REJECTED ✓")
        void rejectOk() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.PENDING_APPROVAL, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseRequisition result = service.rejectRequisition(FACTORY, PR_ID, APPROVER_ID, "数量超预算");

            assertEquals(PurchaseRequisitionStatus.REJECTED, result.getStatus());
            assertEquals("数量超预算", result.getRejectionReason());
        }

        @Test
        @DisplayName("跨工厂访问 → 403 + actionHint")
        void crossFactoryRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, OTHER_FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getById(FACTORY, PR_ID));
            assertEquals(403, ex.getCode());
            assertNotNull(ex.getActionHint());
        }

        @Test
        @DisplayName("CONVERTED_TO_PO → APPROVED 非法 (终态)")
        void terminalStateRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.CONVERTED_TO_PO, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.approveRequisition(FACTORY, PR_ID, APPROVER_ID));
            assertEquals(400, ex.getCode());
        }
    }

    // ==================== deleteDraft (Bug #7 prod QA, 2026-05-20) ====================

    @Nested
    @DisplayName("deleteDraft (Bug #7 — DELETE DRAFT 草稿)")
    class DeleteDraft {

        @Test
        @DisplayName("DRAFT by requester self → softDelete + repo.delete called ✓")
        void deleteDraftByRequesterAccepted() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID);

            verify(repo).delete(pr);
        }

        @Test
        @DisplayName("SUBMITTED (PENDING_APPROVAL) → 400 + actionHint 指向 reject")
        void deleteSubmittedRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.PENDING_APPROVAL, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("DRAFT"),
                    "应说明仅 DRAFT 可删, 实际: " + ex.getMessage());
            assertNotNull(ex.getActionHint(), "应携带 actionHint 指向 reject 操作");
            assertTrue(ex.getActionHint().contains("reject") || ex.getActionHint().contains("驳回"),
                    "actionHint 应引导用户使用 reject, 实际: " + ex.getActionHint());
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("APPROVED → 400 (终态前后均拒)")
        void deleteApprovedRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.APPROVED, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            assertEquals(400, ex.getCode());
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("CONVERTED_TO_PO → 400 (终态)")
        void deleteConvertedRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.CONVERTED_TO_PO, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            assertEquals(400, ex.getCode());
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("REJECTED → 400 (终态)")
        void deleteRejectedTerminalRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.REJECTED, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            assertEquals(400, ex.getCode());
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("DRAFT by 非 requester → 403 + actionHint")
        void deleteDraftByNonRequesterRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, 999L));

            assertEquals(403, ex.getCode());
            assertNotNull(ex.getActionHint(), "应携带 actionHint");
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("跨工厂 → 403")
        void deleteCrossFactoryRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, OTHER_FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            assertEquals(403, ex.getCode());
            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }

        @Test
        @DisplayName("not found → ResourceNotFoundException (Controller 层映射 404)")
        void deleteNotFound() {
            when(repo.findById(PR_ID)).thenReturn(Optional.empty());

            assertThrows(com.cretas.aims.exception.ResourceNotFoundException.class,
                    () -> service.deleteDraft(FACTORY, PR_ID, REQUESTER_ID));

            verify(repo, never()).delete(any(PurchaseRequisition.class));
        }
    }

    // ==================== convertToPO ====================

    @Nested
    @DisplayName("convertToPO")
    class ConvertToPO {

        @Test
        @DisplayName("APPROVED → 调 purchaseService.createPurchaseOrder + 写 convertedPoId ✓")
        void approvedConvertsAndLinks() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.APPROVED, FACTORY);
            pr.setExpectedDate(LocalDate.of(2026, 5, 25));
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrder newPo = new PurchaseOrder();
            newPo.setId("PO-uuid-001");
            newPo.setOrderNumber("PO-20260519-001");
            newPo.setStatus(PurchaseOrderStatus.DRAFT);
            when(purchaseService.createPurchaseOrder(eq(FACTORY), any(CreatePurchaseOrderRequest.class), eq(APPROVER_ID)))
                    .thenReturn(newPo);

            PurchaseOrder result = service.convertToPO(FACTORY, PR_ID, APPROVER_ID, SUPPLIER_ID);

            assertEquals("PO-uuid-001", result.getId());
            assertEquals(PurchaseRequisitionStatus.CONVERTED_TO_PO, pr.getStatus());
            assertEquals("PO-uuid-001", pr.getConvertedPoId(), "linkno 必须回填");
            assertNotNull(pr.getConvertedAt());

            // 验证下游 PO request 字段
            ArgumentCaptor<CreatePurchaseOrderRequest> captor =
                    ArgumentCaptor.forClass(CreatePurchaseOrderRequest.class);
            verify(purchaseService).createPurchaseOrder(eq(FACTORY), captor.capture(), eq(APPROVER_ID));
            CreatePurchaseOrderRequest req = captor.getValue();
            assertEquals(SUPPLIER_ID, req.getSupplierId());
            assertEquals(LocalDate.now(), req.getOrderDate());
            assertEquals(LocalDate.of(2026, 5, 25), req.getExpectedDeliveryDate());
            assertNotNull(req.getRemark());
            assertTrue(req.getRemark().contains("PR-20260519-001"), "remark 应含 PR number 反向 trace");
            assertEquals(1, req.getItems().size());
            assertEquals("MAT-001", req.getItems().get(0).getMaterialTypeId());
            assertEquals(new BigDecimal("100"), req.getItems().get(0).getQuantity());
            assertEquals("kg", req.getItems().get(0).getUnit());
        }

        @Test
        @DisplayName("DRAFT → convertToPO 非法转换 400")
        void draftConvertRejected() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.DRAFT, FACTORY);
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.convertToPO(FACTORY, PR_ID, APPROVER_ID, SUPPLIER_ID));
            assertEquals(400, ex.getCode());
            verifyNoInteractions(purchaseService);
        }

        @Test
        @DisplayName("supplierId 空 → 400")
        void emptySupplier() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.convertToPO(FACTORY, PR_ID, APPROVER_ID, " "));
            assertEquals(400, ex.getCode());
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("CONVERTED_TO_PO 重复 invoke → 返已有 PO 不再创建 (幂等)")
        void idempotentReturnsExistingPo() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.CONVERTED_TO_PO, FACTORY);
            pr.setConvertedPoId("PO-existing-001");
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            PurchaseOrder existing = new PurchaseOrder();
            existing.setId("PO-existing-001");
            when(purchaseService.getPurchaseOrderById(FACTORY, "PO-existing-001")).thenReturn(existing);

            PurchaseOrder result = service.convertToPO(FACTORY, PR_ID, APPROVER_ID, SUPPLIER_ID);

            assertEquals("PO-existing-001", result.getId());
            verify(purchaseService, never()).createPurchaseOrder(any(), any(), any());
        }

        @Test
        @DisplayName("requestedItems 含 quantity=0 行 → 该行被跳过, 仅有效行入 PO")
        void zeroQuantityFiltered() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.APPROVED, FACTORY);
            // 加 1 个 quantity=0 行
            Map<String, Object> zeroItem = new HashMap<>();
            zeroItem.put("materialTypeId", "MAT-002");
            zeroItem.put("materialName", "废料");
            zeroItem.put("quantity", BigDecimal.ZERO);
            zeroItem.put("unit", "kg");
            List<Map<String, Object>> items = new java.util.ArrayList<>(pr.getRequestedItems());
            items.add(zeroItem);
            pr.setRequestedItems(items);

            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrder newPo = new PurchaseOrder();
            newPo.setId("PO-uuid-001");
            when(purchaseService.createPurchaseOrder(eq(FACTORY), any(), eq(APPROVER_ID))).thenReturn(newPo);

            service.convertToPO(FACTORY, PR_ID, APPROVER_ID, SUPPLIER_ID);

            ArgumentCaptor<CreatePurchaseOrderRequest> captor =
                    ArgumentCaptor.forClass(CreatePurchaseOrderRequest.class);
            verify(purchaseService).createPurchaseOrder(eq(FACTORY), captor.capture(), eq(APPROVER_ID));
            assertEquals(1, captor.getValue().getItems().size(), "quantity=0 行应被过滤");
            assertEquals("MAT-001", captor.getValue().getItems().get(0).getMaterialTypeId());
        }

        @Test
        @DisplayName("requestedItems 全 quantity=0 → 400")
        void allZeroQuantity() {
            PurchaseRequisition pr = buildPr(PurchaseRequisitionStatus.APPROVED, FACTORY);
            Map<String, Object> zeroItem = new HashMap<>();
            zeroItem.put("materialTypeId", "MAT-002");
            zeroItem.put("quantity", BigDecimal.ZERO);
            zeroItem.put("unit", "kg");
            pr.setRequestedItems(List.of(zeroItem));
            when(repo.findById(PR_ID)).thenReturn(Optional.of(pr));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.convertToPO(FACTORY, PR_ID, APPROVER_ID, SUPPLIER_ID));
            assertEquals(400, ex.getCode());
            verifyNoInteractions(purchaseService);
        }
    }

    // ==================== createRequisition ====================

    @Test
    @DisplayName("createRequisition 生成 PR-YYYYMMDD-NNN 单号 + DRAFT 状态")
    void createDraft() {
        when(repo.countByFactoryIdAndDate(eq(FACTORY), any())).thenReturn(5L);
        when(repo.save(any())).thenAnswer(inv -> {
            PurchaseRequisition saved = inv.getArgument(0);
            saved.setId("new-id");
            return saved;
        });

        Map<String, Object> item = Map.of(
                "materialTypeId", "MAT-001",
                "quantity", new BigDecimal("50"),
                "unit", "kg");
        CreatePurchaseRequisitionRequest req = new CreatePurchaseRequisitionRequest(
                "DEPT-WH", List.of(item), LocalDate.now().plusDays(7), "缺货", "test");

        PurchaseRequisition result = service.createRequisition(FACTORY, REQUESTER_ID, req);

        assertEquals(PurchaseRequisitionStatus.DRAFT, result.getStatus());
        assertEquals(REQUESTER_ID, result.getRequesterId());
        assertEquals(FACTORY, result.getFactoryId());
        assertTrue(result.getRequisitionNumber().startsWith("PR-"));
        assertTrue(result.getRequisitionNumber().endsWith("-006"),
                "5+1=6, sequence should be -006, actual: " + result.getRequisitionNumber());
    }

    @Test
    @DisplayName("createRequisition with empty items → 400")
    void createEmptyItemsRejected() {
        CreatePurchaseRequisitionRequest req = new CreatePurchaseRequisitionRequest(
                null, List.of(), null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRequisition(FACTORY, REQUESTER_ID, req));
        assertEquals(400, ex.getCode());
    }
}
