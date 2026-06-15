package com.cretas.aims.service.restaurant;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.client.SupplierPriceGoldClient;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.PriceAnomalyApprovalStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteLineRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import com.cretas.aims.service.restaurant.impl.SupplierDeliveryNoteServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SupplierDeliveryNoteServiceImpl 单元测试 (G7 Tier A)。
 *
 * Coverage (per spec §测试计划):
 * <ol>
 *   <li>parseAndDraft — 重复 hash → 409 BusinessException (Rule 4)</li>
 *   <li>parseAndDraft — 低置信 → DRAFT + ocrConfidence 写入</li>
 *   <li>parseAndDraft — 有效照片 → 正确解析行项 + 软匹配 rawMaterialTypeId</li>
 *   <li>parseAndDraft — Vision 不可用 → 503-style BusinessException</li>
 *   <li>confirmNote — 调 gold upsert</li>
 *   <li>confirmNote — gold upsert 失败不回滚 confirm (fail-soft)</li>
 *   <li>confirmNote — 非 DRAFT → BusinessException</li>
 *   <li>rejectNote — 非法状态 → BusinessException</li>
 *   <li>getLimits — 返回当月计数</li>
 * </ol>
 *
 * @since 2026-06-03 (G7)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupplierDeliveryNoteServiceImpl 单元测试")
class SupplierDeliveryNoteServiceTest {

    @Mock SupplierDeliveryNoteRepository noteRepository;
    @Mock SupplierDeliveryNoteLineRepository lineRepository;
    @Mock DashScopeVisionClient visionClient;
    @Mock OssService ossService;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock SupplierPriceGoldClient goldClient;
    @Mock RestaurantInventoryPostingService postingService;
    @Mock ArApService arApService;
    @Mock NotificationService notificationService;
    @Mock IntentConfigManagementService intentConfigManagementService;

    @InjectMocks SupplierDeliveryNoteServiceImpl service;

    private static final String FACTORY = "F006";
    private static final byte[] PHOTO = "fake-jpeg-bytes".getBytes();

    @org.junit.jupiter.api.BeforeEach
    void wireLazyField() {
        // @Lazy @Autowired 字段不是构造器参数 → Mockito @InjectMocks 不会注入, 手动注入。
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "intentConfigManagementService", intentConfigManagementService);
    }

    // ---- 1. duplicate hash → 409 ----
    @Test
    @DisplayName("parseAndDraft 重复照片 hash 抛 409")
    void parseAndDraft_duplicateHash_returns409() {
        SupplierDeliveryNote existing = new SupplierDeliveryNote();
        existing.setId("SDN-EXIST");
        when(noteRepository.findByPhotoHash(anyString())).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.parseAndDraft(FACTORY, 1L, PHOTO, "image/jpeg", LocalDate.now(), null));
        assertEquals(409, ex.getCode());
        assertEquals("DUPLICATE_PHOTO", ex.getErrorCode());
        assertEquals("SDN-EXIST", ex.getHintTarget());
        verify(visionClient, never()).analyzeImage(anyString(), anyString());
    }

    // ---- 4. vision unavailable → BusinessException ----
    @Test
    @DisplayName("parseAndDraft Vision 不可用抛 503")
    void parseAndDraft_visionUnavailable_throws() {
        when(noteRepository.findByPhotoHash(anyString())).thenReturn(Optional.empty());
        when(visionClient.isAvailable()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.parseAndDraft(FACTORY, 1L, PHOTO, "image/jpeg", LocalDate.now(), null));
        assertEquals(503, ex.getCode());
    }

    // ---- 2 & 3. valid photo low confidence → DRAFT + lines soft-matched ----
    @Test
    @DisplayName("parseAndDraft 有效照片低置信解析行项 + 软匹配")
    void parseAndDraft_validLowConfidence_parsesLinesAndMatches() {
        when(noteRepository.findByPhotoHash(anyString())).thenReturn(Optional.empty());
        when(visionClient.isAvailable()).thenReturn(true);
        when(ossService.isAvailable()).thenReturn(true);
        when(ossService.uploadBytes(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://oss/x.jpg");
        // Vision returns a low-confidence JSON (0.6 < 0.75) with one item
        String json = "{\"note_number\":\"SD-1\",\"delivery_date\":\"2026-06-01\"," +
                "\"supplier_name\":\"鑫农\",\"items\":[{\"ingredient_name\":\"猪肉\"," +
                "\"quantity\":10,\"unit\":\"kg\",\"unit_price\":12.5,\"line_amount\":125}]," +
                "\"total_amount\":125,\"confidence\":0.6}";
        when(visionClient.analyzeImage(anyString(), anyString())).thenReturn(json);

        // soft-match returns a raw material type for "猪肉"
        RawMaterialType rmt = new RawMaterialType();
        rmt.setId("rmt-pork");
        rmt.setName("猪肉");
        when(rawMaterialTypeRepository.searchMaterialTypes(eq(FACTORY), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(rmt)));

        when(noteRepository.save(any(SupplierDeliveryNote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SupplierDeliveryNote result = service.parseAndDraft(
                FACTORY, 1L, PHOTO, "image/jpeg", LocalDate.now(), null);

        assertEquals(DeliveryNoteStatus.DRAFT, result.getStatus());
        assertEquals(0, result.getOcrConfidence().compareTo(new java.math.BigDecimal("0.6")));
        assertEquals(1, result.getLines().size());
        SupplierDeliveryNoteLine line = result.getLines().get(0);
        assertEquals("猪肉", line.getIngredientName());
        assertEquals("rmt-pork", line.getRawMaterialTypeId());
        assertEquals(0, line.getUnitPrice().compareTo(new java.math.BigDecimal("12.5")));
    }

    // ---- Chunk 1: OCR prompt 业态化 ----
    @Test
    @DisplayName("parseAndDraft FACTORY 业态选用物料中性 prompt")
    void parseAndDraft_factoryDomain_usesNeutralPrompt() {
        when(noteRepository.findByPhotoHash(anyString())).thenReturn(Optional.empty());
        when(visionClient.isAvailable()).thenReturn(true);
        when(ossService.isAvailable()).thenReturn(true);
        when(ossService.uploadBytes(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://oss/x.jpg");
        when(intentConfigManagementService.resolveBusinessDomain("F999")).thenReturn("FACTORY");
        when(visionClient.analyzeImage(anyString(), anyString()))
                .thenReturn("{\"items\":[],\"confidence\":0.9}");
        when(noteRepository.save(any(SupplierDeliveryNote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.parseAndDraft("F999", 1L, PHOTO, "image/jpeg", LocalDate.now(), null);

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(visionClient).analyzeImage(anyString(), promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("物料名称"), "工厂 prompt 应含『物料名称』");
        assertFalse(prompt.contains("中餐厅供应链单据识别专家"), "工厂 prompt 不应含中餐厅措辞");
    }

    @Test
    @DisplayName("parseAndDraft RESTAURANT 业态保持中餐厅 prompt (字节不变)")
    void parseAndDraft_restaurantDomain_usesRestaurantPrompt() {
        when(noteRepository.findByPhotoHash(anyString())).thenReturn(Optional.empty());
        when(visionClient.isAvailable()).thenReturn(true);
        when(ossService.isAvailable()).thenReturn(true);
        when(ossService.uploadBytes(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://oss/x.jpg");
        when(intentConfigManagementService.resolveBusinessDomain(FACTORY)).thenReturn("RESTAURANT");
        when(visionClient.analyzeImage(anyString(), anyString()))
                .thenReturn("{\"items\":[],\"confidence\":0.9}");
        when(noteRepository.save(any(SupplierDeliveryNote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.parseAndDraft(FACTORY, 1L, PHOTO, "image/jpeg", LocalDate.now(), null);

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(visionClient).analyzeImage(anyString(), promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("中餐厅供应链单据识别专家"));
    }

    // ---- 5 & 6. confirm calls gold; gold failure does not roll back confirm ----
    @Test
    @DisplayName("confirmNote 调 gold upsert 并标 CONFIRMED")
    void confirmNote_callsGoldUpsert() throws IOException {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        note.setConfirmedBy(9L);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draftWithLine()));
        when(postingService.postSupplierDeliveryToInventory(FACTORY, "N1", 9L)).thenReturn(note);
        when(arApService.recordPayableFromSource(eq(FACTORY), eq("SUP1"), eq("SUPPLIER_DELIVERY_NOTE"),
                eq("N1"), any(), any(), eq(9L), anyString())).thenReturn(payableTxn("AP1"));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(goldClient.upsertSupplierPriceBatch(eq(FACTORY), eq("N1"), anyList())).thenReturn(1);

        SupplierDeliveryNote result = service.confirmNote(FACTORY, "N1", 9L);

        assertEquals(DeliveryNoteStatus.CONFIRMED, result.getStatus());
        assertEquals(9L, result.getConfirmedBy());
        assertEquals("AP1", result.getPayableTransactionId());
        verify(arApService).recordPayableFromSource(eq(FACTORY), eq("SUP1"), eq("SUPPLIER_DELIVERY_NOTE"),
                eq("N1"), any(), any(), eq(9L), contains("餐饮送货验收入库自动挂账"));
        verify(goldClient).upsertSupplierPriceBatch(eq(FACTORY), eq("N1"), anyList());
    }

    @Test
    @DisplayName("confirmNote gold upsert 失败不回滚确认 (fail-soft D5)")
    void confirmNote_goldUpsertFails_doesNotRollbackConfirm() throws IOException {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        note.setConfirmedBy(9L);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draftWithLine()));
        when(postingService.postSupplierDeliveryToInventory(FACTORY, "N1", 9L)).thenReturn(note);
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(arApService.recordPayableFromSource(eq(FACTORY), eq("SUP1"), eq("SUPPLIER_DELIVERY_NOTE"),
                eq("N1"), any(), any(), eq(9L), anyString())).thenReturn(payableTxn("AP1"));
        when(goldClient.upsertSupplierPriceBatch(anyString(), anyString(), anyList()))
                .thenThrow(new IOException("gold down"));

        SupplierDeliveryNote result = service.confirmNote(FACTORY, "N1", 9L);

        // Confirm still succeeds; error recorded
        assertEquals(DeliveryNoteStatus.CONFIRMED, result.getStatus());
        assertNotNull(result.getOcrErrorMessage());
        assertTrue(result.getOcrErrorMessage().contains("gold"));
    }

    @Test
    @DisplayName("confirmNote 非 DRAFT 抛异常")
    void confirmNote_invalidStatus_throws() {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        assertThrows(BusinessException.class, () -> service.confirmNote(FACTORY, "N1", 9L));
        verifyNoInteractions(postingService);
    }

    @Test
    @DisplayName("confirmNote missing amount fails before AP creates zero payable")
    void confirmNote_missingAmount_failsClosed() throws IOException {
        SupplierDeliveryNote note = draftWithLine();
        note.setTotalAmount(null);
        note.getLines().get(0).setLineAmount(null);
        note.getLines().get(0).setUnitPrice(null);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draftWithLine()));
        when(postingService.postSupplierDeliveryToInventory(FACTORY, "N1", 9L)).thenReturn(note);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmNote(FACTORY, "N1", 9L));

        assertEquals(400, ex.getCode());
        assertEquals("PAYABLE_AMOUNT_REQUIRED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("缺少金额"));
        verifyNoInteractions(arApService);
        verify(goldClient, never()).upsertSupplierPriceBatch(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("confirmNote 进价异常无解释时阻断，不过账")
    void confirmNote_priceAnomalyWithoutExplanation_blocksPosting() {
        SupplierDeliveryNote note = draftWithLine();
        note.getLines().get(0).setRawMaterialTypeId("rmt-pork");
        note.getLines().get(0).setUnitPrice(new java.math.BigDecimal("13.00"));
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmNote(FACTORY, "N1", 9L));

        assertEquals(409, ex.getCode());
        assertEquals("PRICE_ANOMALY_EXPLANATION_REQUIRED", ex.getErrorCode());
        assertEquals("priceAnomalyExplanation", ex.getHintTarget());
        assertTrue(note.getLines().get(0).getPriceAnomalyFlag());
        assertEquals(0, note.getLines().get(0).getPriceVarianceRate()
                .compareTo(new java.math.BigDecimal("0.3000")));
        verifyNoInteractions(postingService);
        verifyNoInteractions(arApService);
    }

    @Test
    @DisplayName("confirmNote 进价异常已有解释但未审批时阻断")
    void confirmNote_priceAnomalyWithExplanationWithoutApproval_blocksPosting() {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmNote(FACTORY, "N1", 9L));

        assertEquals(400, ex.getCode());
        assertEquals("PRICE_ANOMALY_APPROVAL_REQUIRED", ex.getErrorCode());
        verifyNoInteractions(postingService);
    }

    @Test
    @DisplayName("confirmNote 进价异常已批准后允许过账")
    void confirmNote_priceAnomalyApproved_allowsPosting() throws IOException {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        draft.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.APPROVED);
        SupplierDeliveryNote posted = draftWithLine();
        posted.setStatus(DeliveryNoteStatus.CONFIRMED);
        posted.setConfirmedBy(9L);

        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));
        when(postingService.postSupplierDeliveryToInventory(FACTORY, "N1", 9L)).thenReturn(posted);
        when(arApService.recordPayableFromSource(eq(FACTORY), eq("SUP1"), eq("SUPPLIER_DELIVERY_NOTE"),
                eq("N1"), any(), any(), eq(9L), anyString())).thenReturn(payableTxn("AP1"));
        when(goldClient.upsertSupplierPriceBatch(eq(FACTORY), eq("N1"), anyList())).thenReturn(1);

        SupplierDeliveryNote result = service.confirmNote(FACTORY, "N1", 9L);

        assertEquals(DeliveryNoteStatus.CONFIRMED, result.getStatus());
        verify(postingService).postSupplierDeliveryToInventory(FACTORY, "N1", 9L);
    }

    @Test
    @DisplayName("submit 审批后 confirm 仍被 PENDING 阻断")
    void confirmNote_afterSubmitApproval_blocksPosting() {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        draft.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.PENDING);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmNote(FACTORY, "N1", 9L));

        assertEquals(409, ex.getCode());
        assertEquals("PRICE_ANOMALY_PENDING_APPROVAL", ex.getErrorCode());
        assertEquals("priceAnomalyApprovalStatus", ex.getHintTarget());
        verifyNoInteractions(postingService);
    }

    @Test
    @DisplayName("submitPriceAnomalyApproval 设置 PENDING 并通知老板/财务/店长")
    void submitPriceAnomalyApproval_setsPending() {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));

        SupplierDeliveryNote result = service.submitPriceAnomalyApproval(FACTORY, "N1", 9L);

        assertEquals(PriceAnomalyApprovalStatus.PENDING, result.getPriceAnomalyApprovalStatus());
        assertEquals(9L, result.getPriceAnomalySubmittedBy());
        verify(notificationService).notifyRole(eq(FACTORY), eq(FactoryUserRole.factory_super_admin.name()), anyString(), anyString());
        verify(notificationService).notifyRole(eq(FACTORY), eq(FactoryUserRole.finance_manager.name()), anyString(), anyString());
        verify(notificationService).notifyRole(eq(FACTORY), eq(FactoryUserRole.restaurant_manager.name()), anyString(), anyString());
    }

    @Test
    @DisplayName("approvePriceAnomaly 非老板角色 403")
    void approvePriceAnomaly_forbiddenRole() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approvePriceAnomaly(FACTORY, "N1", "ok", 9L, "warehouse_manager"));

        assertEquals(403, ex.getCode());
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("approvePriceAnomaly 重复批准 409")
    void approvePriceAnomaly_duplicateApproved() {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        draft.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.APPROVED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approvePriceAnomaly(FACTORY, "N1", "ok", 9L, "factory_super_admin"));

        assertEquals(409, ex.getCode());
        assertEquals("PRICE_ANOMALY_ALREADY_APPROVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("rejectPriceAnomaly 后 confirm 失败")
    void confirmNote_afterReject_blocksPosting() {
        SupplierDeliveryNote draft = draftWithAnomalyLine();
        draft.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.REJECTED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(draft));
        when(lineRepository.averageRecentConfirmedPriceByMaterial(
                eq(FACTORY), eq("rmt-pork"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(new java.math.BigDecimal("10.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmNote(FACTORY, "N1", 9L));

        assertEquals(409, ex.getCode());
        assertEquals("PRICE_ANOMALY_REJECTED", ex.getErrorCode());
        verifyNoInteractions(postingService);
    }

    // ---- 8. reject invalid status ----
    @Test
    @DisplayName("rejectNote 非 DRAFT 抛异常")
    void rejectNote_invalidStatus_throws() {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.rejectNote(FACTORY, "N1", "IMAGE_BLUR", null, 9L));
        assertEquals(409, ex.getCode());
        assertEquals("INVALID_STATUS", ex.getErrorCode());
        assertEquals("status", ex.getHintTarget());
        assertNotNull(ex.getActionHint());
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejectNote DRAFT → REJECTED + 原因码")
    void rejectNote_draft_setsRejected() {
        SupplierDeliveryNote note = draftWithLine();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierDeliveryNote result = service.rejectNote(FACTORY, "N1", "IMAGE_BLUR", "太糊了", 9L);
        assertEquals(DeliveryNoteStatus.REJECTED, result.getStatus());
        assertEquals("IMAGE_BLUR", result.getRejectReasonCode());
    }

    @Test
    @DisplayName("rejectNote missing reason returns field-targeted 400")
    void rejectNote_missingReason_returnsStructuredError() {
        SupplierDeliveryNote note = draftWithLine();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.rejectNote(FACTORY, "N1", " ", null, 9L));

        assertEquals(400, ex.getCode());
        assertEquals("REJECT_REASON_REQUIRED", ex.getErrorCode());
        assertEquals("rejectReasonCode", ex.getHintTarget());
        assertNotNull(ex.getActionHint());
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateLines non-DRAFT returns structured 409 and does not save")
    void updateLines_invalidStatus_returnsStructured409() {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateLines(FACTORY, "N1", List.of(lineDto())));

        assertEquals(409, ex.getCode());
        assertEquals("INVALID_STATUS", ex.getErrorCode());
        assertEquals("status", ex.getHintTarget());
        assertNotNull(ex.getActionHint());
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateLines empty lines returns field-targeted 400")
    void updateLines_emptyLines_returnsStructured400() {
        SupplierDeliveryNote note = draftWithLine();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateLines(FACTORY, "N1", List.of()));

        assertEquals(400, ex.getCode());
        assertEquals("LINES_REQUIRED", ex.getErrorCode());
        assertEquals("lines", ex.getHintTarget());
        assertNotNull(ex.getActionHint());
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteDraft non-DRAFT returns structured 409 and does not save")
    void deleteDraft_invalidStatus_returnsStructured409() {
        SupplierDeliveryNote note = draftWithLine();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.deleteDraft(FACTORY, "N1"));

        assertEquals(409, ex.getCode());
        assertEquals("INVALID_STATUS", ex.getErrorCode());
        assertEquals("status", ex.getHintTarget());
        assertNotNull(ex.getActionHint());
        verify(noteRepository, never()).save(any());
    }

    // ---- 9. getLimits ----
    @Test
    @DisplayName("getLimits 返回当月确认/草稿计数")
    void getLimits_returnsCounts() {
        when(noteRepository.countByFactoryIdAndStatusAndMonth(eq(FACTORY),
                eq(DeliveryNoteStatus.CONFIRMED), any(), any())).thenReturn(5L);
        when(noteRepository.countByFactoryIdAndStatusAndMonth(eq(FACTORY),
                eq(DeliveryNoteStatus.DRAFT), any(), any())).thenReturn(2L);

        var limits = service.getLimits(FACTORY, LocalDate.of(2026, 6, 15));
        assertEquals(5L, limits.get("confirmedCount"));
        assertEquals(2L, limits.get("draftCount"));
        assertEquals("2026-06", limits.get("month"));
    }

    // ---- helper ----
    private SupplierDeliveryNote draftWithAnomalyLine() {
        SupplierDeliveryNote note = draftWithLine();
        SupplierDeliveryNoteLine line = note.getLines().get(0);
        line.setRawMaterialTypeId("rmt-pork");
        line.setUnitPrice(new java.math.BigDecimal("13.00"));
        line.setPriceAnomalyFlag(true);
        line.setPriceAnomalyReasonCode("MARKET_PRICE_UP");
        line.setPriceAnomalyExplanation("供应商解释为批发市场涨价");
        return note;
    }

    private SupplierDeliveryNote draftWithLine() {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId("N1");
        note.setFactoryId(FACTORY);
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setSupplierId("SUP1");
        note.setSupplierName("鑫农");
        note.setDeliveryDate(LocalDate.of(2026, 6, 1));
        SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
        line.setIngredientName("猪肉");
        line.setQuantity(new java.math.BigDecimal("10"));
        line.setUnit("kg");
        line.setUnitPrice(new java.math.BigDecimal("12.5"));
        line.setLineAmount(new java.math.BigDecimal("125"));
        note.addLine(line);
        return note;
    }

    private com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto.LineDto lineDto() {
        return com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto.LineDto.builder()
                .ingredientName("鐚倝")
                .rawMaterialTypeId("rmt-pork")
                .quantity(new java.math.BigDecimal("10"))
                .unit("kg")
                .unitPrice(new java.math.BigDecimal("12.5"))
                .build();
    }

    private ArApTransaction payableTxn(String id) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        return transaction;
    }
}
