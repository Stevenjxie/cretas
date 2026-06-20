package com.cretas.aims.controller;

import com.cretas.aims.dto.disposal.UpdateDisposalRecordRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for
 * {@link DisposalController#updateDisposalRecord} (Issue #384 batch 5).
 *
 * <p>Asserts that the Controller correctly:
 * <ol>
 *   <li>Binds {@link UpdateDisposalRecordRequest} instead of the
 *       {@link DisposalRecord} entity directly (Rule 17.1 anti-pattern fix)</li>
 *   <li>Maps the DTO to a partial Entity, passing only mutable fields through</li>
 *   <li>Leaves unprovided fields as null so the service's {@code if (... != null)}
 *       guards preserve existing DB values</li>
 *   <li>Does NOT touch approval-state or audit fields (already-approved guard
 *       lives in service)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DisposalControllerUpdateTest {

    @Mock DisposalRecordService disposalRecordService;

    @InjectMocks DisposalController controller;

    @Test
    void updateDisposalRecord_minimumBody_unprovidedFieldsRemainNull() {
        // Empty wire body — every field is optional per UpdateDisposalRecordRequest.
        UpdateDisposalRecordRequest req = new UpdateDisposalRecordRequest();

        when(disposalRecordService.updateDisposalRecord(eq("F001"), eq(7L), any(DisposalRecord.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ResponseEntity<?> resp = controller.updateDisposalRecord("F001", 7L, req);

        ArgumentCaptor<DisposalRecord> captor = ArgumentCaptor.forClass(DisposalRecord.class);
        Mockito.verify(disposalRecordService).updateDisposalRecord(eq("F001"), eq(7L), captor.capture());
        DisposalRecord mapped = captor.getValue();

        assertNotNull(resp);
        // All optional fields stay null — service's if(...!=null) guards skip them
        assertNull(mapped.getDisposalQuantity());
        assertNull(mapped.getDisposalType());
        assertNull(mapped.getDisposalReason());
        assertNull(mapped.getDisposalMethod());
        assertNull(mapped.getEstimatedLoss());
        assertNull(mapped.getRecoveryValue());
        assertNull(mapped.getEvidenceImages(), "evidenceImages null when not provided — service if(!=null) guard preserves existing");
        assertNull(mapped.getNotes());
        // Critical: approval-state / audit fields NOT touched by mapper.
        // (isApproved gets entity-level field default `= false` from `new DisposalRecord()`,
        // NOT from any mapper logic — service uses id+findById to identify and won't
        // copy this field over the persisted record because update only iterates the
        // 7 mutable fields above.)
        assertFalse(mapped.getIsApproved(), "isApproved entity-default false — mapper does not touch");
        assertNull(mapped.getApprovedBy());
        assertNull(mapped.getApprovedByName());
        assertNull(mapped.getApprovalDate());
        assertNull(mapped.getFactoryId(), "mapper does not touch factoryId — service identifies by id only");
        assertNull(mapped.getId(), "mapper does not set id — service uses path variable");
    }

    @Test
    void updateDisposalRecord_happyPath_mutableFieldsPropagatedByMapper() {
        // All mutable fields set per the service's update list.
        UpdateDisposalRecordRequest req = new UpdateDisposalRecordRequest();
        req.setDisposalQuantity(new BigDecimal("75.00"));
        req.setDisposalType("RETURN");
        req.setDisposalReason("已过保质期");
        req.setDisposalMethod("退回供应商");
        req.setEstimatedLoss(new BigDecimal("3000.00"));
        req.setRecoveryValue(new BigDecimal("2000.00"));
        req.setEvidenceImages("https://cdn.example.com/c.jpg");
        req.setNotes("供应商已确认接收");

        when(disposalRecordService.updateDisposalRecord(eq("F001"), eq(7L), any(DisposalRecord.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ResponseEntity<?> resp = controller.updateDisposalRecord("F001", 7L, req);

        ArgumentCaptor<DisposalRecord> captor = ArgumentCaptor.forClass(DisposalRecord.class);
        Mockito.verify(disposalRecordService).updateDisposalRecord(eq("F001"), eq(7L), captor.capture());
        DisposalRecord mapped = captor.getValue();

        assertNotNull(resp);
        assertEquals(new BigDecimal("75.00"), mapped.getDisposalQuantity());
        assertEquals("RETURN", mapped.getDisposalType());
        assertEquals("已过保质期", mapped.getDisposalReason());
        assertEquals("退回供应商", mapped.getDisposalMethod());
        assertEquals(new BigDecimal("3000.00"), mapped.getEstimatedLoss());
        assertEquals(new BigDecimal("2000.00"), mapped.getRecoveryValue());
        assertEquals("https://cdn.example.com/c.jpg", mapped.getEvidenceImages());
        assertEquals("供应商已确认接收", mapped.getNotes());
    }
}
