package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.workflow.OaActionIdempotencyLedger;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.OaActionIdempotencyLedgerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class OaActionIdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final OaActionIdempotencyLedgerRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> execute(ActionContext context,
                                       Supplier<Map<String, Object>> action) {
        acquireScopeLock(context.factoryId(), context.instanceId(), context.idempotencyKey());
        String fingerprint = fingerprint(context);
        var existing = repository.findByFactoryIdAndInstanceIdAndIdempotencyKey(
                context.factoryId(), context.instanceId(), context.idempotencyKey());
        if (existing.isPresent()) {
            OaActionIdempotencyLedger ledger = existing.get();
            if (!fingerprint.equals(ledger.getRequestFingerprint())) {
                throw new BusinessException(409, "同一幂等键不能用于不同的审批请求")
                        .withCode("OA_IDEMPOTENCY_KEY_REUSED")
                        .withHint("请刷新待办并为新的审批操作生成新的幂等键");
            }
            if (COMPLETED.equals(ledger.getCompletionState())) {
                return new LinkedHashMap<>(ledger.getResultJson());
            }
            throw new BusinessException(409, "该审批请求正在处理中，请勿重复提交")
                    .withCode("OA_ACTION_IN_PROGRESS");
        }

        OaActionIdempotencyLedger ledger = OaActionIdempotencyLedger.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(context.factoryId())
                .instanceId(context.instanceId())
                .idempotencyKey(context.idempotencyKey())
                .expectedNodeId(context.expectedNodeId())
                .action(context.action())
                .operatorId(context.operatorId())
                .operatorRole(context.operatorRole())
                .requestFingerprint(fingerprint)
                .completionState(IN_PROGRESS)
                .build();
        repository.saveAndFlush(ledger);

        Map<String, Object> result = action.get();
        ledger.setResultJson(new LinkedHashMap<>(result));
        ledger.setCompletionState(COMPLETED);
        repository.save(ledger);
        return result;
    }

    private void acquireScopeLock(String factoryId, String instanceId, String idempotencyKey) {
        long lockKey = ByteBuffer.wrap(digest(factoryId + "\u0000" + instanceId + "\u0000" + idempotencyKey))
                .getLong();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", lockKey)
                .getSingleResult();
    }

    private String fingerprint(ActionContext context) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("expectedNodeId", context.expectedNodeId());
            payload.put("action", context.action());
            payload.put("operatorId", context.operatorId());
            payload.put("operatorRole", context.operatorRole());
            payload.put("notes", context.notes());
            return toHex(digest(objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成 OA 审批请求指纹", e);
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    public record ActionContext(
            String factoryId,
            String instanceId,
            String idempotencyKey,
            String expectedNodeId,
            String action,
            Long operatorId,
            String operatorRole,
            String notes) {}
}
