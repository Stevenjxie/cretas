package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.IntentFeedbackRequest;
import com.cretas.aims.entity.intent.IntentMatchRecord;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.IntentFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意图反馈服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentFeedbackServiceImpl implements IntentFeedbackService {

    private final TrainingSampleRepository trainingSampleRepository;
    private final IntentMatchRecordRepository intentMatchRecordRepository;

    @Override
    @Transactional
    public void recordPositiveFeedback(String factoryId, String intentCode, List<String> matchedKeywords) {
        log.debug("Recording positive feedback for intent: {} in factory: {}", intentCode, factoryId);

        // 更新最近的训练样本为已确认正确
        trainingSampleRepository.findTopByFactoryIdAndMatchedIntentCodeOrderByCreatedAtDesc(factoryId, intentCode)
                .ifPresent(sample -> {
                    sample.setIsCorrect(true);
                    sample.setFeedbackAt(LocalDateTime.now());
                    trainingSampleRepository.save(sample);
                    log.info("Positive feedback recorded for sample: {}", sample.getId());
                });
    }

    @Override
    @Transactional
    public void recordNegativeFeedback(String factoryId, String rejectedIntentCode,
                                        String selectedIntentCode, List<String> matchedKeywords) {
        log.debug("Recording negative feedback: rejected={}, selected={} in factory: {}",
                rejectedIntentCode, selectedIntentCode, factoryId);

        // 更新最近的训练样本为错误，并记录正确意图
        trainingSampleRepository.findTopByFactoryIdAndMatchedIntentCodeOrderByCreatedAtDesc(factoryId, rejectedIntentCode)
                .ifPresent(sample -> {
                    sample.setIsCorrect(false);
                    sample.setCorrectIntentCode(selectedIntentCode);
                    sample.setFeedbackAt(LocalDateTime.now());
                    trainingSampleRepository.save(sample);
                    log.info("Negative feedback recorded for sample: {}, correct intent: {}",
                            sample.getId(), selectedIntentCode);
                });
    }

    @Override
    @Transactional
    public void processIntentFeedback(String factoryId, Long userId, IntentFeedbackRequest request) {
        log.debug("Processing intent feedback from user {} in factory {}: {}",
                userId, factoryId, request);

        if (Boolean.TRUE.equals(request.getIsCorrect())) {
            recordPositiveFeedback(factoryId, request.getMatchedIntentCode(), java.util.List.of());
        } else {
            recordNegativeFeedback(factoryId, request.getMatchedIntentCode(),
                    request.getCorrectIntentCode(), java.util.List.of());
        }
        updateLatestIntentMatchFeedback(factoryId, userId, request);
    }

    private void updateLatestIntentMatchFeedback(String factoryId, Long userId, IntentFeedbackRequest request) {
        if (factoryId == null || userId == null || request == null
                || request.getMatchedIntentCode() == null || request.getUserInput() == null) {
            return;
        }
        String normalizedInput = request.getUserInput().toLowerCase().trim();
        PageRequest first = PageRequest.of(0, 1);
        List<IntentMatchRecord> records = intentMatchRecordRepository.findLatestForFeedbackByUserInput(
                factoryId,
                userId,
                request.getMatchedIntentCode(),
                request.getUserInput(),
                first);
        if (records.isEmpty()) {
            records = intentMatchRecordRepository.findLatestForFeedback(
                factoryId,
                userId,
                request.getMatchedIntentCode(),
                normalizedInput,
                    first);
        }
        if (records.isEmpty()) {
            records = intentMatchRecordRepository.findLatestForFeedbackByIntent(
                    factoryId,
                    userId,
                    request.getMatchedIntentCode(),
                    first);
        }
        if (records.isEmpty()) {
            log.info("No intent_match_records row found; creating feedback-only record factory={} user={} intent={} input={}",
                    factoryId, userId, request.getMatchedIntentCode(), normalizedInput);
            IntentMatchRecord record = buildFeedbackOnlyRecord(factoryId, userId, request, normalizedInput);
            intentMatchRecordRepository.save(record);
            return;
        }
        IntentMatchRecord record = records.get(0);
        applyFeedbackToRecord(record, request);
        intentMatchRecordRepository.save(record);
        log.info("Intent feedback linked to match record id={} intent={} correct={}",
                record.getId(), record.getMatchedIntentCode(), Boolean.TRUE.equals(request.getIsCorrect()));
    }

    private IntentMatchRecord buildFeedbackOnlyRecord(String factoryId,
                                                      Long userId,
                                                      IntentFeedbackRequest request,
                                                      String normalizedInput) {
        IntentMatchRecord record = new IntentMatchRecord();
        record.setFactoryId(factoryId);
        record.setUserId(userId);
        record.setSessionId(request.getSessionId());
        record.setUserInput(request.getUserInput());
        record.setNormalizedInput(normalizedInput);
        record.setMatchedIntentCode(request.getMatchedIntentCode());
        record.setMatchedIntentName(request.getMatchedIntentCode());
        record.setMatchedIntentCategory("FEEDBACK");
        record.setConfidenceScore(java.math.BigDecimal.ONE);
        record.setMatchMethod(IntentMatchRecord.MatchMethod.PHRASE_MATCH);
        record.setLlmCalled(false);
        record.setExecutionStatus(IntentMatchRecord.ExecutionStatus.EXECUTED);
        record.setExecutionResult("Feedback-only record created from visible AI answer feedback");
        record.setExecutedAt(LocalDateTime.now());
        applyFeedbackToRecord(record, request);
        return record;
    }

    private void applyFeedbackToRecord(IntentMatchRecord record, IntentFeedbackRequest request) {
        boolean correct = Boolean.TRUE.equals(request.getIsCorrect());
        record.setUserConfirmed(correct);
        record.setUserSelectedIntent(correct
                ? request.getMatchedIntentCode()
                : request.getCorrectIntentCode());
        record.setUserFeedback(request.getUserFeedback());
        record.setConfirmedAt(LocalDateTime.now());
    }
}
