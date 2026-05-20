package com.cretas.aims.service;

import com.cretas.aims.client.WhisperTranscribeClient;
import com.cretas.aims.entity.CallRecord;
import com.cretas.aims.entity.enums.CallType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 通话记录 service (Sprint 6 W2-C-2, 2026-05-19).
 *
 * <p>提供 CRUD + 录音上传 + 异步转写触发. 业务逻辑:
 * <ul>
 *   <li>factoryId 校验 (defense-in-depth)</li>
 *   <li>callType enum 白名单校验</li>
 *   <li>5min dedup window (per fool-proof-design.md R4) — 维度 callTime ±5min</li>
 *   <li>OSS 音频上传 (via {@link OssService#uploadAudioStream})</li>
 *   <li>Whisper 转写异步触发 (fire-and-forget, REST 不阻塞)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordService {

    /** 防呆 R1: 单个录音文件最大 50 MB. */
    public static final long MAX_AUDIO_FILE_SIZE = 50L * 1024 * 1024;

    /** 防呆 R4: 5min dedup window. */
    public static final int DEDUP_WINDOW_MINUTES = 5;

    private final CallRecordRepository repository;
    private final OssService ossService;
    private final WhisperTranscribeClient whisperClient;

    /** 列表查询 — factoryId + customerId 双 scope. */
    public Page<CallRecord> list(String factoryId, String customerId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        return repository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCallTimeDesc(
                factoryId, customerId, PageRequest.of(safePage, safeSize));
    }

    /** 单记录获取 + factoryId 校验. */
    public CallRecord get(String factoryId, Long id) {
        CallRecord r = repository.findById(id).orElseThrow(
                () -> new BusinessException(404, "通话记录不存在: " + id));
        if (!factoryId.equals(r.getFactoryId())) {
            throw new BusinessException(403, "无权操作此记录 (factoryId mismatch)");
        }
        return r;
    }

    /** 单 customer 当前 count — 防呆 R2 context. */
    public long count(String factoryId, String customerId) {
        return repository.countByFactoryIdAndCustomerIdAndDeletedAtIsNull(factoryId, customerId);
    }

    /**
     * 创建通话记录 — 含可选 audio 上传 + 防呆 R4 dedup.
     *
     * @param audioFile 可选录音文件 (MultipartFile, null 表示无录音 e.g. MISSED)
     * @throws BusinessException 409 if dedup hit (with actionHint pointing to existing record)
     */
    @Transactional
    public CallRecord create(String factoryId, String customerId, CallType callType,
                              LocalDateTime callTime, Integer durationSec,
                              String contactName, String contactPhone, String remark,
                              Long createdBy, String createdByName,
                              MultipartFile audioFile) {
        // === 参数校验 ===
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(400, "customerId 必填");
        }
        if (callType == null) {
            throw new BusinessException(400, "callType 必填 (INCOMING/OUTGOING/MISSED)");
        }
        if (createdBy == null) {
            throw new BusinessException(400, "createdBy 必填");
        }

        LocalDateTime effectiveCallTime = callTime != null ? callTime : LocalDateTime.now();

        // === 防呆 R1: 文件大小校验 ===
        if (audioFile != null && !audioFile.isEmpty()) {
            if (audioFile.getSize() > MAX_AUDIO_FILE_SIZE) {
                throw new BusinessException(400,
                        String.format("录音文件大小 %.2f MB 超过 50 MB 限制",
                                audioFile.getSize() / 1024.0 / 1024.0));
            }
        }

        // === 防呆 R4: 5min dedup window ===
        LocalDateTime timeFrom = effectiveCallTime.minusMinutes(DEDUP_WINDOW_MINUTES);
        LocalDateTime timeTo = effectiveCallTime.plusMinutes(DEDUP_WINDOW_MINUTES);
        List<CallRecord> recent = repository.findRecentByTime(factoryId, customerId, timeFrom, timeTo);
        if (!recent.isEmpty()) {
            Long existingId = recent.get(0).getId();
            throw new BusinessException(409,
                    "5 分钟内已创建过通话记录 (id=" + existingId + "). 请避免重复提交.")
                    .withHint("/sales/customers/" + customerId + "?tab=audio&highlight=" + existingId);
        }

        // === OSS 上传 (可选, audioFile != null 时) ===
        String audioOssUrl = null;
        String audioFilename = null;
        Long audioSizeBytes = null;
        String audioMimeType = null;
        String transcriptStatus = null;
        if (audioFile != null && !audioFile.isEmpty()) {
            try {
                audioFilename = audioFile.getOriginalFilename();
                audioSizeBytes = audioFile.getSize();
                audioMimeType = audioFile.getContentType();
                audioOssUrl = ossService.uploadAudioStream(
                        audioFile.getInputStream(),
                        audioFilename != null ? audioFilename : "call-record.audio",
                        factoryId);
                transcriptStatus = CallRecord.TRANSCRIPT_PENDING;
                log.info("OSS upload success: factory={} customer={} url={}",
                        factoryId, customerId, audioOssUrl);
            } catch (IOException ex) {
                log.error("Failed to read audio file (factory={}, customer={}): {}",
                        factoryId, customerId, ex.getMessage());
                throw new BusinessException(500, "录音文件读取失败: " + ex.getMessage());
            } catch (Exception ex) {
                // OSS service down → 不阻塞创建, 标 FAILED transcript status
                log.warn("OSS upload failed (factory={}, customer={}): {}",
                        factoryId, customerId, ex.getMessage());
                throw new BusinessException(503,
                        "录音上传失败 (OSS 服务暂不可用). 请稍后重试或先创建无录音记录.");
            }
        }

        // === 持久化 ===
        CallRecord r = CallRecord.builder()
                .factoryId(factoryId)
                .customerId(customerId)
                .callTime(effectiveCallTime)
                .durationSec(durationSec)
                .callType(callType)
                .contactName(contactName)
                .contactPhone(contactPhone)
                .audioOssUrl(audioOssUrl)
                .audioFilename(audioFilename)
                .audioSizeBytes(audioSizeBytes)
                .audioMimeType(audioMimeType)
                .transcriptStatus(transcriptStatus)
                .remark(remark)
                .createdBy(createdBy)
                .createdByName(createdByName)
                .build();

        CallRecord saved = repository.save(r);
        log.info("Created call record {} for customer {} (factory={}) callType={} hasAudio={}",
                saved.getId(), customerId, factoryId, callType, audioOssUrl != null);

        // === 异步 Whisper 转写 (fire-and-forget, 不阻塞 REST 响应) ===
        if (audioOssUrl != null) {
            triggerTranscriptionAsync(saved.getId(), saved.getFactoryId(), audioOssUrl);
        }

        return saved;
    }

    /**
     * 部分更新 — 仅修改 contactName / contactPhone / remark / durationSec.
     * 不动 factoryId / customerId / callType / callTime / audioOssUrl / createdBy.
     */
    @Transactional
    public CallRecord update(String factoryId, Long id, String contactName, String contactPhone,
                              String remark, Integer durationSec) {
        CallRecord existing = get(factoryId, id);
        if (existing.getDeletedAt() != null) {
            throw new BusinessException(410, "记录已删除, 无法修改");
        }
        if (contactName != null) existing.setContactName(contactName);
        if (contactPhone != null) existing.setContactPhone(contactPhone);
        if (remark != null) existing.setRemark(remark);
        if (durationSec != null) existing.setDurationSec(durationSec);

        CallRecord saved = repository.save(existing);
        log.info("Updated call record {} (factory={})", id, factoryId);
        return saved;
    }

    /** 软删除. */
    @Transactional
    public void delete(String factoryId, Long id) {
        CallRecord r = get(factoryId, id);
        repository.delete(r);
        log.info("Deleted call record {} (factory={})", id, factoryId);
    }

    /**
     * Async Whisper transcription trigger — fire-and-forget.
     *
     * <p>Spring @Async dispatches to a separate thread so REST POST response is
     * not blocked. On success / failure, updates {@code transcriptText} +
     * {@code transcriptStatus} on the CallRecord row in a fresh transaction.
     *
     * <p>Failure mode: WhisperTranscribeClient already swallows exceptions and
     * returns null; we just write FAILED status. Frontend polls via GET /{id}.
     */
    @Async
    public void triggerTranscriptionAsync(Long callRecordId, String factoryId, String audioOssUrl) {
        log.info("Triggering async Whisper transcription: callRecordId={} factory={}",
                callRecordId, factoryId);
        try {
            String transcript = whisperClient.transcribe(audioOssUrl, factoryId, callRecordId);
            updateTranscriptResult(factoryId, callRecordId, transcript);
        } catch (Exception ex) {
            log.warn("Async transcription unexpected error callRecordId={}: {}",
                    callRecordId, ex.getMessage());
            try {
                updateTranscriptResult(factoryId, callRecordId, null);
            } catch (Exception updateEx) {
                log.error("Failed to mark FAILED status callRecordId={}: {}",
                        callRecordId, updateEx.getMessage());
            }
        }
    }

    @Transactional
    public void updateTranscriptResult(String factoryId, Long callRecordId, String transcript) {
        CallRecord r = repository.findByFactoryIdAndIdAndDeletedAtIsNull(factoryId, callRecordId)
                .orElse(null);
        if (r == null) {
            log.warn("CallRecord disappeared during async transcribe: id={} factory={}",
                    callRecordId, factoryId);
            return;
        }
        if (transcript != null && !transcript.isBlank()) {
            r.setTranscriptText(transcript);
            r.setTranscriptStatus(CallRecord.TRANSCRIPT_SUCCESS);
            log.info("Transcription success: callRecordId={} length={}", callRecordId, transcript.length());
        } else {
            r.setTranscriptStatus(CallRecord.TRANSCRIPT_FAILED);
            log.info("Transcription failed: callRecordId={} (Whisper returned null)", callRecordId);
        }
        repository.save(r);
    }
}
