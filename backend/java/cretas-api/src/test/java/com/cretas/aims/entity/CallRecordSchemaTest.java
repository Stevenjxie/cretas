package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.CallType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 6 W2-C-2 — CallRecord entity schema + behavior test.
 *
 * Validates declared fields, audit field inheritance, soft-delete via BaseEntity,
 * builder happy-path, CallType enum membership, transcript constants.
 */
@DisplayName("CallRecord entity (Sprint 6 W2-C-2)")
class CallRecordSchemaTest {

    @Test
    @DisplayName("declares all required columns")
    void shouldDeclareRequiredFields() {
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("id"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("factoryId"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("customerId"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("callTime"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("durationSec"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("callType"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("contactName"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("contactPhone"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("audioOssUrl"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("audioFilename"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("audioSizeBytes"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("audioMimeType"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("transcriptText"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("transcriptStatus"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("remark"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("createdBy"));
        assertDoesNotThrow(() -> CallRecord.class.getDeclaredField("createdByName"));
    }

    @Test
    @DisplayName("inherits BaseEntity audit fields (createdAt/updatedAt/deletedAt)")
    void shouldInheritBaseEntityFields() {
        assertDoesNotThrow(() -> CallRecord.class.getSuperclass().getDeclaredField("createdAt"));
        assertDoesNotThrow(() -> CallRecord.class.getSuperclass().getDeclaredField("updatedAt"));
        assertDoesNotThrow(() -> CallRecord.class.getSuperclass().getDeclaredField("deletedAt"));
    }

    @Test
    @DisplayName("CallType has 3 values: INCOMING / OUTGOING / MISSED")
    void callType_shouldHaveThreeValues() {
        CallType[] values = CallType.values();
        assertEquals(3, values.length);
        assertEquals(CallType.INCOMING, CallType.valueOf("INCOMING"));
        assertEquals(CallType.OUTGOING, CallType.valueOf("OUTGOING"));
        assertEquals(CallType.MISSED, CallType.valueOf("MISSED"));
    }

    @Test
    @DisplayName("CallType has 中文 displayName + description")
    void callType_shouldHaveDisplayMetadata() {
        assertNotNull(CallType.INCOMING.getDisplayName());
        assertFalse(CallType.INCOMING.getDisplayName().isBlank());
        assertNotNull(CallType.OUTGOING.getDescription());
        assertFalse(CallType.OUTGOING.getDescription().isBlank());
        assertNotNull(CallType.MISSED.getDisplayName());
    }

    @Test
    @DisplayName("transcript status constants are stable")
    void transcriptConstants_shouldBeWellKnown() {
        assertEquals("PENDING", CallRecord.TRANSCRIPT_PENDING);
        assertEquals("SUCCESS", CallRecord.TRANSCRIPT_SUCCESS);
        assertEquals("FAILED", CallRecord.TRANSCRIPT_FAILED);
    }

    @Test
    @DisplayName("builder happy-path constructs a valid CallRecord with audio + transcript")
    void builder_shouldConstructValidRecord() {
        LocalDateTime now = LocalDateTime.now();
        CallRecord r = CallRecord.builder()
                .factoryId("F006")
                .customerId("CUST-001")
                .callTime(now)
                .durationSec(125)
                .callType(CallType.OUTGOING)
                .contactName("李采购")
                .contactPhone("13800000001")
                .audioOssUrl("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/audio/2026/05/20/call-001.mp3")
                .audioFilename("call-001.mp3")
                .audioSizeBytes(2_345_678L)
                .audioMimeType("audio/mpeg")
                .transcriptStatus(CallRecord.TRANSCRIPT_PENDING)
                .remark("催收欠款 200 元")
                .createdBy(123L)
                .createdByName("张三")
                .build();

        assertEquals("F006", r.getFactoryId());
        assertEquals("CUST-001", r.getCustomerId());
        assertEquals(now, r.getCallTime());
        assertEquals(125, r.getDurationSec());
        assertEquals(CallType.OUTGOING, r.getCallType());
        assertEquals("李采购", r.getContactName());
        assertEquals("13800000001", r.getContactPhone());
        assertNotNull(r.getAudioOssUrl());
        assertEquals("call-001.mp3", r.getAudioFilename());
        assertEquals(2_345_678L, r.getAudioSizeBytes());
        assertEquals("audio/mpeg", r.getAudioMimeType());
        assertEquals(CallRecord.TRANSCRIPT_PENDING, r.getTranscriptStatus());
        assertNull(r.getTranscriptText());
        assertEquals("催收欠款 200 元", r.getRemark());
        assertEquals(123L, r.getCreatedBy());
        assertEquals("张三", r.getCreatedByName());
        // Not yet deleted
        assertNull(r.getDeletedAt());
        assertFalse(r.isDeleted());
    }

    @Test
    @DisplayName("MISSED call has no audio + transcript_status null")
    void missedCall_shouldNotRequireAudio() {
        CallRecord r = CallRecord.builder()
                .factoryId("F006")
                .customerId("CUST-002")
                .callTime(LocalDateTime.now())
                .durationSec(0)
                .callType(CallType.MISSED)
                .createdBy(1L)
                .build();
        assertEquals(CallType.MISSED, r.getCallType());
        assertNull(r.getAudioOssUrl());
        assertNull(r.getTranscriptStatus());
        assertEquals(0, r.getDurationSec());
    }

    @Test
    @DisplayName("softDelete() sets deletedAt + isDeleted()")
    void softDelete_shouldMarkRecordDeleted() {
        CallRecord r = CallRecord.builder()
                .factoryId("F001")
                .customerId("CUST-1")
                .callTime(LocalDateTime.now())
                .callType(CallType.INCOMING)
                .createdBy(1L)
                .build();
        assertFalse(r.isDeleted());
        r.softDelete();
        assertTrue(r.isDeleted());
        assertNotNull(r.getDeletedAt());
        // restore round-trips
        r.restore();
        assertFalse(r.isDeleted());
        assertNull(r.getDeletedAt());
    }

    @Test
    @DisplayName("default no-arg constructor works (JPA requirement)")
    void noArgConstructor_shouldExist() {
        assertDoesNotThrow(() -> {
            CallRecord r = new CallRecord();
            assertNotNull(r);
        });
    }

    @Test
    @DisplayName("Attachment.EntityType contains CALL_RECORD (Sprint 6 W2-C-2 extension)")
    void attachmentEntityType_shouldIncludeCallRecord() {
        // Ensures Attachment EntityType extension keeps CALL_RECORD entry stable.
        assertDoesNotThrow(() -> com.cretas.aims.entity.Attachment.EntityType.valueOf("CALL_RECORD"));
    }
}
