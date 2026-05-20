package com.cretas.aims.service;

import com.cretas.aims.client.WhisperTranscribeClient;
import com.cretas.aims.entity.CallRecord;
import com.cretas.aims.entity.enums.CallType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CallRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Sprint 6 W2-C-2 — CallRecordService 单元测试.
 *
 * 覆盖:
 * - create 成功 (无音频) / 含音频 → OSS 上传 + transcript_status=PENDING
 * - create 防呆 R1: 文件 >50MB 拒收
 * - create 防呆 R4: 5min dedup → 409
 * - get / list / count factoryId 校验
 * - update 部分字段
 * - softDelete
 * - updateTranscriptResult SUCCESS / FAILED 写回
 */
@DisplayName("CallRecordService unit tests (Sprint 6 W2-C-2)")
@ExtendWith(MockitoExtension.class)
class CallRecordServiceTest {

    @Mock
    private CallRecordRepository repository;

    @Mock
    private OssService ossService;

    @Mock
    private WhisperTranscribeClient whisperClient;

    private CallRecordService service;

    private static final String FACTORY = "F006";
    private static final String CUSTOMER = "CUST-001";
    private static final Long USER_ID = 123L;

    @BeforeEach
    void setUp() {
        service = new CallRecordService(repository, ossService, whisperClient);
    }

    @Test
    @DisplayName("create succeeds without audio (callType=MISSED case)")
    void create_noAudio_savesRecord() {
        when(repository.findRecentByTime(any(), any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(CallRecord.class))).thenAnswer(inv -> {
            CallRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        CallRecord saved = service.create(FACTORY, CUSTOMER, CallType.MISSED,
                LocalDateTime.now(), 0, null, null, "漏接",
                USER_ID, "张三", null);

        assertNotNull(saved);
        assertEquals(1L, saved.getId());
        assertEquals(CallType.MISSED, saved.getCallType());
        assertNull(saved.getAudioOssUrl());
        assertNull(saved.getTranscriptStatus());
        verify(repository).save(any(CallRecord.class));
        verifyNoInteractions(ossService);
        verifyNoInteractions(whisperClient);
    }

    @Test
    @DisplayName("create with audio uploads to OSS and triggers async Whisper")
    void create_withAudio_uploadsAndTriggersTranscribe() throws Exception {
        MultipartFile audio = new MockMultipartFile(
                "audio", "call-001.mp3", "audio/mpeg",
                "fake-mp3-bytes".getBytes());

        when(repository.findRecentByTime(any(), any(), any(), any())).thenReturn(List.of());
        when(ossService.uploadAudioStream(any(ByteArrayInputStream.class), anyString(), anyString()))
                .thenReturn("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/audio/call-001.mp3");
        when(repository.save(any(CallRecord.class))).thenAnswer(inv -> {
            CallRecord r = inv.getArgument(0);
            r.setId(2L);
            return r;
        });
        // Whisper async call returns a transcript (but we don't strictly verify async path here)
        when(whisperClient.transcribe(anyString(), anyString(), any())).thenReturn("test transcript");

        CallRecord saved = service.create(FACTORY, CUSTOMER, CallType.OUTGOING,
                LocalDateTime.now(), 125, "李采购", "13800000001", "催收欠款 200 元",
                USER_ID, "张三", audio);

        assertNotNull(saved);
        assertEquals(2L, saved.getId());
        assertEquals(CallType.OUTGOING, saved.getCallType());
        assertNotNull(saved.getAudioOssUrl());
        assertEquals(CallRecord.TRANSCRIPT_PENDING, saved.getTranscriptStatus());
        assertEquals("call-001.mp3", saved.getAudioFilename());
        assertEquals(audio.getSize(), saved.getAudioSizeBytes());
        assertEquals("audio/mpeg", saved.getAudioMimeType());
        verify(ossService).uploadAudioStream(any(), anyString(), eq(FACTORY));
    }

    @Test
    @DisplayName("create rejects audio file larger than 50 MB (R1)")
    void create_largeAudio_rejected() {
        // Build a 51 MB file
        byte[] big = new byte[51 * 1024 * 1024];
        MultipartFile audio = new MockMultipartFile(
                "audio", "huge.mp3", "audio/mpeg", big);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(
                FACTORY, CUSTOMER, CallType.OUTGOING,
                LocalDateTime.now(), 100, null, null, null,
                USER_ID, "张三", audio));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("50 MB"), "Should mention 50 MB limit. msg=" + ex.getMessage());
        verifyNoInteractions(ossService);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create dedup R4: same callTime ±5min returns 409 with actionHint")
    void create_dedup_409() {
        LocalDateTime callTime = LocalDateTime.now();
        CallRecord existing = new CallRecord();
        existing.setId(99L);
        existing.setFactoryId(FACTORY);
        existing.setCustomerId(CUSTOMER);
        existing.setCallTime(callTime);

        when(repository.findRecentByTime(eq(FACTORY), eq(CUSTOMER), any(), any()))
                .thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(
                FACTORY, CUSTOMER, CallType.INCOMING,
                callTime, 30, null, null, null,
                USER_ID, "张三", null));
        assertEquals(409, ex.getCode());
        assertNotNull(ex.getActionHint(), "actionHint should be set for dedup 409");
        assertTrue(ex.getActionHint().contains("highlight=99"),
                "actionHint should reference existing id 99. hint=" + ex.getActionHint());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create rejects missing factoryId / customerId / callType / createdBy")
    void create_missingParams_400() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.create(null, CUSTOMER, CallType.INCOMING,
                        null, null, null, null, null, USER_ID, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.create(FACTORY, "", CallType.INCOMING,
                        null, null, null, null, null, USER_ID, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.create(FACTORY, CUSTOMER, null,
                        null, null, null, null, null, USER_ID, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.create(FACTORY, CUSTOMER, CallType.INCOMING,
                        null, null, null, null, null, null, null, null)).getCode());
    }

    @Test
    @DisplayName("get throws 404 if not found, 403 if factory mismatch")
    void get_validatesFactoryScope() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.get(FACTORY, 1L)).getCode());

        CallRecord r = new CallRecord();
        r.setId(2L);
        r.setFactoryId("F001"); // different from FACTORY
        when(repository.findById(2L)).thenReturn(Optional.of(r));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.get(FACTORY, 2L)).getCode());
    }

    @Test
    @DisplayName("update modifies only allowed fields")
    void update_allowedFieldsOnly() {
        CallRecord existing = CallRecord.builder()
                .id(5L)
                .factoryId(FACTORY)
                .customerId(CUSTOMER)
                .callType(CallType.OUTGOING)
                .callTime(LocalDateTime.now())
                .createdBy(USER_ID)
                .audioOssUrl("immutable-url")
                .build();
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any(CallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        CallRecord updated = service.update(FACTORY, 5L, "新联系人", "13900000000", "更新备注", 150);

        assertEquals("新联系人", updated.getContactName());
        assertEquals("13900000000", updated.getContactPhone());
        assertEquals("更新备注", updated.getRemark());
        assertEquals(150, updated.getDurationSec());
        // Immutable fields untouched
        assertEquals(CallType.OUTGOING, updated.getCallType());
        assertEquals("immutable-url", updated.getAudioOssUrl());
        assertEquals(USER_ID, updated.getCreatedBy());
    }

    @Test
    @DisplayName("delete invokes soft-delete on owned record")
    void delete_softDeletesOwnedRecord() {
        CallRecord r = CallRecord.builder()
                .id(7L)
                .factoryId(FACTORY)
                .customerId(CUSTOMER)
                .callType(CallType.INCOMING)
                .callTime(LocalDateTime.now())
                .createdBy(USER_ID)
                .build();
        when(repository.findById(7L)).thenReturn(Optional.of(r));

        service.delete(FACTORY, 7L);
        verify(repository).delete(r);
    }

    @Test
    @DisplayName("updateTranscriptResult writes SUCCESS when transcript text present")
    void updateTranscriptResult_success() {
        CallRecord r = CallRecord.builder()
                .id(8L)
                .factoryId(FACTORY)
                .transcriptStatus(CallRecord.TRANSCRIPT_PENDING)
                .build();
        when(repository.findByFactoryIdAndIdAndDeletedAtIsNull(FACTORY, 8L)).thenReturn(Optional.of(r));
        when(repository.save(any(CallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateTranscriptResult(FACTORY, 8L, "客户表示满意, 下次跟进 12 月 1 日");

        assertEquals("客户表示满意, 下次跟进 12 月 1 日", r.getTranscriptText());
        assertEquals(CallRecord.TRANSCRIPT_SUCCESS, r.getTranscriptStatus());
    }

    @Test
    @DisplayName("updateTranscriptResult writes FAILED when transcript null/blank")
    void updateTranscriptResult_failed() {
        CallRecord r = CallRecord.builder()
                .id(9L)
                .factoryId(FACTORY)
                .transcriptStatus(CallRecord.TRANSCRIPT_PENDING)
                .build();
        when(repository.findByFactoryIdAndIdAndDeletedAtIsNull(FACTORY, 9L)).thenReturn(Optional.of(r));
        when(repository.save(any(CallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateTranscriptResult(FACTORY, 9L, null);

        assertNull(r.getTranscriptText());
        assertEquals(CallRecord.TRANSCRIPT_FAILED, r.getTranscriptStatus());
    }

    @Test
    @DisplayName("updateTranscriptResult silently skips when record disappears")
    void updateTranscriptResult_recordGone_noop() {
        when(repository.findByFactoryIdAndIdAndDeletedAtIsNull(FACTORY, 999L)).thenReturn(Optional.empty());
        // Should not throw
        assertDoesNotThrow(() -> service.updateTranscriptResult(FACTORY, 999L, "anything"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("count returns repository count for factory+customer scope")
    void count_returnsRepositoryCount() {
        when(repository.countByFactoryIdAndCustomerIdAndDeletedAtIsNull(FACTORY, CUSTOMER))
                .thenReturn(5L);
        assertEquals(5L, service.count(FACTORY, CUSTOMER));
    }

    @Test
    @DisplayName("list bounds page+size to safe ranges")
    void list_safelyBoundsPageAndSize() {
        when(repository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCallTimeDesc(
                anyString(), anyString(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        // negative page → 0; oversized size → 100 (max bound)
        service.list(FACTORY, CUSTOMER, -1, 500);
        verify(repository).findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCallTimeDesc(
                eq(FACTORY), eq(CUSTOMER), argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 100));
    }
}
