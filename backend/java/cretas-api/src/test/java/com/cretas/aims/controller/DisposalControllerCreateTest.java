package com.cretas.aims.controller;

import com.cretas.aims.dto.disposal.CreateDisposalRecordRequest;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.service.DisposalRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for
 * {@link DisposalController#createDisposalRecord} (Issue #384 batch 5).
 *
 * <p>Asserts that the Controller correctly:
 * <ol>
 *   <li>Binds {@link CreateDisposalRecordRequest} instead of the
 *       {@link DisposalRecord} entity directly (Rule 17.1 anti-pattern fix)</li>
 *   <li>Maps the DTO to the Entity at the controller boundary, leaving
 *       service-owned defaults (disposalDate now-fallback, isApproved false,
 *       approval-state fields) untouched by the mapper</li>
 *   <li>Sets {@code factoryId} from {@code @PathVariable} after mapping</li>
 *   <li>Does NOT duplicate service-owned defaults (single source of truth
 *       lives in {@code DisposalRecordService.createDisposalRecord})</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DisposalControllerCreateTest {

    @Mock DisposalRecordService disposalRecordService;

    @InjectMocks DisposalController controller;

    @Test
    void createDisposalRecord_minimumBody_factoryIdFromPath_serviceDefaultsNotLeakedToMapper() {
        // Minimum wire body per CreateDisposalRecordRequest: only the
        // required fields. NO factoryId (path-variable). NO disposalDate
        // (service falls back to now()). NO isApproved (service forces false).
        // NO approval-state fields (set later via approveDisposal flow).
        CreateDisposalRecordRequest req = new CreateDisposalRecordRequest();
        req.setDisposalQuantity(new BigDecimal("100.50"));
        req.setDisposalType("SCRAP");

        when(disposalRecordService.createDisposalRecord(any(DisposalRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.createDisposalRecord("F001", req);

        ArgumentCaptor<DisposalRecord> captor = ArgumentCaptor.forClass(DisposalRecord.class);
        Mockito.verify(disposalRecordService).createDisposalRecord(captor.capture());
        DisposalRecord mapped = captor.getValue();

        assertNotNull(resp);
        // Wire-side fields propagated by mapper
        assertEquals(new BigDecimal("100.50"), mapped.getDisposalQuantity());
        assertEquals("SCRAP", mapped.getDisposalType());
        // factoryId from path-variable (set AFTER mapper)
        assertEquals("F001", mapped.getFactoryId());
        // Mapper does NOT pre-fill service-owned defaults (single source of truth)
        assertNull(mapped.getDisposalDate(), "disposalDate now-fallback belongs to service");
        // isApproved gets entity-level field default `= false` from new DisposalRecord()
        // — that's a JPA entity initializer, NOT a mapper-injected default
        assertEquals(false, mapped.getIsApproved(),
                "isApproved entity-default false (from `private Boolean isApproved = false`) — mapper does not set");
        assertNull(mapped.getApprovedBy(), "approvedBy set later via approveDisposal");
        assertNull(mapped.getApprovedByName(), "approvedByName set later via approveDisposal");
        assertNull(mapped.getApprovalDate(), "approvalDate set later via approveDisposal");
        // Optional wire fields default to null
        assertNull(mapped.getDisposalReason());
        assertNull(mapped.getDisposalMethod());
        assertNull(mapped.getQualityInspectionId());
        assertNull(mapped.getReworkRecordId());
        assertNull(mapped.getProductionBatchId());
        assertNull(mapped.getMaterialBatchId());
        assertNull(mapped.getEstimatedLoss());
        assertNull(mapped.getRecoveryValue());
        assertNull(mapped.getEvidenceImages(), "evidenceImages null when not provided — no silent default");
        assertNull(mapped.getNotes());
    }

    @Test
    void createDisposalRecord_happyPath_allFieldsPropagatedByMapper() {
        // Full wire body — every CreateDisposalRecordRequest field set
        LocalDateTime explicitDate = LocalDateTime.of(2026, 5, 11, 10, 30, 0);
        CreateDisposalRecordRequest req = new CreateDisposalRecordRequest();
        req.setDisposalQuantity(new BigDecimal("50.00"));
        req.setDisposalType("RECYCLE");
        req.setDisposalReason("过期食品");
        req.setDisposalDate(explicitDate);
        req.setDisposalMethod("送至回收商");
        req.setQualityInspectionId("QI-001");
        req.setReworkRecordId(99L);
        req.setProductionBatchId("PB-F001-001");
        req.setMaterialBatchId("MB-F001-001");
        req.setEstimatedLoss(new BigDecimal("5000.00"));
        req.setRecoveryValue(new BigDecimal("1500.00"));
        req.setEvidenceImages("https://cdn.example.com/a.jpg,https://cdn.example.com/b.jpg");
        req.setNotes("可回收为有机肥");

        when(disposalRecordService.createDisposalRecord(any(DisposalRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.createDisposalRecord("F002", req);

        ArgumentCaptor<DisposalRecord> captor = ArgumentCaptor.forClass(DisposalRecord.class);
        Mockito.verify(disposalRecordService).createDisposalRecord(captor.capture());
        DisposalRecord mapped = captor.getValue();

        assertNotNull(resp);
        assertEquals("F002", mapped.getFactoryId());
        assertEquals(new BigDecimal("50.00"), mapped.getDisposalQuantity());
        assertEquals("RECYCLE", mapped.getDisposalType());
        assertEquals("过期食品", mapped.getDisposalReason());
        assertEquals(explicitDate, mapped.getDisposalDate());
        assertEquals("送至回收商", mapped.getDisposalMethod());
        assertEquals("QI-001", mapped.getQualityInspectionId());
        assertEquals(99L, mapped.getReworkRecordId());
        assertEquals("PB-F001-001", mapped.getProductionBatchId());
        assertEquals("MB-F001-001", mapped.getMaterialBatchId());
        assertEquals(new BigDecimal("5000.00"), mapped.getEstimatedLoss());
        assertEquals(new BigDecimal("1500.00"), mapped.getRecoveryValue());
        assertEquals("https://cdn.example.com/a.jpg,https://cdn.example.com/b.jpg", mapped.getEvidenceImages());
        assertEquals("可回收为有机肥", mapped.getNotes());
    }

    @Test
    void createDisposalRecord_responseEnvelope_successTrueDataPresent() {
        // Verify the controller wraps the service response in the standard
        // { success: true, data, message } envelope.
        CreateDisposalRecordRequest req = new CreateDisposalRecordRequest();
        req.setDisposalQuantity(new BigDecimal("10.00"));
        req.setDisposalType("DESTROY");

        DisposalRecord saved = new DisposalRecord();
        saved.setId(42L);
        saved.setFactoryId("F001");
        saved.setDisposalType("DESTROY");
        when(disposalRecordService.createDisposalRecord(any(DisposalRecord.class)))
                .thenReturn(saved);

        ResponseEntity<?> resp = controller.createDisposalRecord("F001", req);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals("报废记录创建成功", body.get("message"));
        assertEquals(saved, body.get("data"));
    }
}
