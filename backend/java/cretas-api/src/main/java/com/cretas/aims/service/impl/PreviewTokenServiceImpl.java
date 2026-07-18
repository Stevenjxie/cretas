package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Atomic, command-bound implementation of the preview confirmation lifecycle. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewTokenServiceImpl implements PreviewTokenService {

    private static final int MAX_RESOLUTION_MESSAGE_LENGTH = 500;

    private final IntentPreviewTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public IntentPreviewToken createBoundToken(BoundTokenRequest request) {
        validateBoundRequest(request);
        LocalDateTime now = LocalDateTime.now();
        JsonNode parametersNode = objectMapper.valueToTree(request.parameters());
        String parametersHash = ToolCommandDigest.parametersHash(parametersNode);
        String commandDigest = ToolCommandDigest.commandDigest(
                request.factoryId(), request.userId(), request.toolName(),
                request.descriptorVersion(), request.executionMode(), parametersNode);

        tokenRepository.cancelPreviousTokens(
                request.factoryId(), request.userId(), request.intentCode(), now);

        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .tenantId(request.factoryId())
                .userId(request.userId())
                .username(request.username())
                .intentCode(request.intentCode())
                .intentName(request.intentName())
                .toolName(request.toolName())
                .descriptorVersion(request.descriptorVersion())
                .executionMode(request.executionMode())
                .parametersHash(parametersHash)
                .commandDigest(commandDigest)
                .entityType(request.entityType())
                .entityId(request.entityId())
                .operation(request.operation())
                .status(TokenStatus.PENDING)
                .expiresAt(now.plusSeconds(request.expiresInSeconds()))
                .build();

        try {
            token.setPreviewData(objectMapper.writeValueAsString(request.parameters()));
            token.setCurrentValues(objectMapper.writeValueAsString(request.currentValues()));
            token.setNewValues(objectMapper.writeValueAsString(request.newValues()));
        } catch (Exception e) {
            throw new IllegalArgumentException("创建绑定预览令牌失败: 数据序列化错误", e);
        }

        IntentPreviewToken saved = tokenRepository.save(token);
        log.info("创建绑定预览令牌: tokenFingerprint={}, factory={}, user={}, intent={}, tool={}, version={}, expires={}s",
                fingerprint(saved.getToken()), request.factoryId(), request.userId(),
                request.intentCode(), request.toolName(), request.descriptorVersion(),
                request.expiresInSeconds());
        return saved;
    }

    /**
     * Compatibility-only issuance. It deliberately omits command bindings, so the new claim path
     * will reject it fail-closed. No production caller currently uses this method.
     */
    @Override
    @Deprecated(forRemoval = false)
    @Transactional
    public IntentPreviewToken createToken(String factoryId, Long userId, String username,
                                           String intentCode, String intentName,
                                           String entityType, String entityId, String operation,
                                           Map<String, Object> previewData,
                                           Map<String, Object> currentValues,
                                           Map<String, Object> newValues,
                                           int expiresInSeconds) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.cancelPreviousTokens(factoryId, userId, intentCode, now);
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .userId(userId)
                .username(username)
                .intentCode(intentCode)
                .intentName(intentName)
                .entityType(entityType)
                .entityId(entityId)
                .operation(operation)
                .status(TokenStatus.PENDING)
                .expiresAt(now.plusSeconds(expiresInSeconds))
                .build();
        serializeLegacyPayload(token, previewData, currentValues, newValues);
        IntentPreviewToken saved = tokenRepository.save(token);
        log.warn("创建旧版不可执行预览令牌: tokenFingerprint={}, factory={}, user={}, intent={}",
                fingerprint(saved.getToken()), factoryId, userId, intentCode);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntentPreviewToken> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<IntentPreviewToken> tokenOpt = tokenRepository.findByTokenAndStatus(token, TokenStatus.PENDING);
        if (tokenOpt.isEmpty() || tokenOpt.get().isExpired()) {
            log.debug("令牌不可用: tokenFingerprint={}", fingerprint(token));
            return Optional.empty();
        }
        return tokenOpt;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntentPreviewToken> validateTokenForUser(String token, Long userId) {
        return validateToken(token)
                .filter(previewToken -> previewToken.getUserId().equals(userId));
    }

    @Override
    @Deprecated(forRemoval = false)
    public ConfirmResult confirmToken(String token, Long userId) {
        log.warn("拒绝旧版非原子确认入口: tokenFingerprint={}, user={}", fingerprint(token), userId);
        return ConfirmResult.failure("旧版确认入口已停用，请重新发起预览");
    }

    @Override
    @Transactional
    public ClaimResult claimToken(String token, String factoryId, Long userId) {
        return claimTokenInternal(token, factoryId, userId, null);
    }

    @Override
    @Transactional
    public ClaimResult claimToken(String token, String factoryId, Long userId,
                                  String proofCommandDigest) {
        return claimTokenInternal(token, factoryId, userId, proofCommandDigest);
    }

    private ClaimResult claimTokenInternal(String token, String factoryId, Long userId,
                                           String proofCommandDigest) {
        String tokenFingerprint = fingerprint(token);
        if (token == null || token.isBlank() || factoryId == null || factoryId.isBlank() || userId == null) {
            return ClaimResult.failure("令牌无效或已过期");
        }

        Optional<IntentPreviewToken> existingOpt = tokenRepository.findByToken(token);
        if (existingOpt.isEmpty()) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=not-found", tokenFingerprint);
            return ClaimResult.failure("令牌无效或已过期");
        }

        IntentPreviewToken existing = existingOpt.get();
        if (!factoryId.equals(existing.getFactoryId())
                || !factoryId.equals(existing.getTenantId())
                || !userId.equals(existing.getUserId())) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=principal-mismatch", tokenFingerprint);
            return ClaimResult.failure("令牌与当前工厂或用户不匹配");
        }
        if (existing.getStatus() != TokenStatus.PENDING) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=status-{}",
                    tokenFingerprint, existing.getStatus());
            return ClaimResult.failure("令牌已被处理: " + existing.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(existing.getExpiresAt())) {
            tokenRepository.expirePendingToken(token, factoryId, userId, now,
                    TokenStatus.PENDING, TokenStatus.EXPIRED);
            return ClaimResult.failure("令牌已过期，请重新发起预览");
        }
        if (!existing.isBoundForExecution() || existing.getExecutionMode() != ToolExecutionMode.EXECUTE) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=unbound-or-mode", tokenFingerprint);
            return ClaimResult.failure("预览令牌未绑定可执行命令，请重新发起预览");
        }

        JsonNode parametersNode;
        try {
            parametersNode = objectMapper.readTree(existing.getPreviewData());
        } catch (Exception e) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=invalid-parameters", tokenFingerprint);
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        if (parametersNode == null || !parametersNode.isObject()) {
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }

        String expectedParametersHash = ToolCommandDigest.parametersHash(parametersNode);
        String expectedCommandDigest = ToolCommandDigest.commandDigest(
                existing.getFactoryId(), existing.getUserId(), existing.getToolName(),
                existing.getDescriptorVersion(), existing.getExecutionMode(), parametersNode);
        if (!constantTimeEquals(expectedParametersHash, existing.getParametersHash())
                || !constantTimeEquals(expectedCommandDigest, existing.getCommandDigest())) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=digest-mismatch", tokenFingerprint);
            return ClaimResult.failure("预览命令完整性校验失败，请重新发起预览");
        }
        if (proofCommandDigest != null) {
            if (proofCommandDigest.isBlank()
                    || !constantTimeEquals(expectedCommandDigest, proofCommandDigest)) {
                log.warn("确认认领失败: tokenFingerprint={}, reason=proof-digest-mismatch", tokenFingerprint);
                return ClaimResult.failure("确认凭据与预览命令不匹配");
            }
        }

        String claimId = UUID.randomUUID().toString();
        int claimed = tokenRepository.claimForExecution(
                token, factoryId, factoryId, userId, expectedCommandDigest,
                expectedParametersHash, existing.getToolName(), existing.getDescriptorVersion(),
                existing.getExecutionMode(), claimId, now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING);
        if (claimed != 1) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=concurrent-or-stale", tokenFingerprint);
            return ClaimResult.failure("令牌已被其他确认请求处理");
        }

        Optional<IntentPreviewToken> claimedTokenOpt = tokenRepository.findByTokenAndClaimIdAndStatus(
                token, claimId, TokenStatus.EXECUTING);
        if (claimedTokenOpt.isEmpty()) {
            resolveClaim(token, claimId, false, "认领后确认状态读取失败");
            return ClaimResult.failure("确认状态读取失败，请勿重复提交");
        }
        IntentPreviewToken claimedToken = claimedTokenOpt.get();
        JsonNode claimedParametersNode;
        try {
            claimedParametersNode = objectMapper.readTree(claimedToken.getPreviewData());
        } catch (Exception e) {
            resolveClaim(token, claimId, false, "认领后参数解析失败");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        if (claimedParametersNode == null || !claimedParametersNode.isObject()) {
            resolveClaim(token, claimId, false, "认领后参数不是JSON对象");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        String claimedDigest = ToolCommandDigest.commandDigest(
                claimedToken.getFactoryId(), claimedToken.getUserId(), claimedToken.getToolName(),
                claimedToken.getDescriptorVersion(), claimedToken.getExecutionMode(), claimedParametersNode);
        if (!constantTimeEquals(claimedDigest, claimedToken.getCommandDigest())) {
            resolveClaim(token, claimId, false, "认领后命令完整性校验失败");
            return ClaimResult.failure("预览命令完整性校验失败，请重新发起预览");
        }
        Map<String, Object> parameters;
        try {
            parameters = parseParameters(claimedToken.getPreviewData());
        } catch (IllegalArgumentException e) {
            resolveClaim(token, claimId, false, "认领后执行参数解析失败");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }

        log.info("确认令牌已原子认领: tokenFingerprint={}, claimFingerprint={}, factory={}, user={}, tool={}",
                tokenFingerprint, fingerprint(claimId), factoryId, userId, claimedToken.getToolName());
        return ClaimResult.success(claimedToken, claimId, parameters);
    }

    @Override
    @Transactional
    public boolean resolveClaim(String token, String claimId, boolean success, String message) {
        if (token == null || token.isBlank() || claimId == null || claimId.isBlank()) {
            return false;
        }
        TokenStatus resolvedStatus = success ? TokenStatus.CONFIRMED : TokenStatus.FAILED;
        int updated = tokenRepository.resolveClaim(
                token, claimId, TokenStatus.EXECUTING, resolvedStatus,
                LocalDateTime.now(), truncateMessage(message));
        log.info("确认令牌完成: tokenFingerprint={}, claimFingerprint={}, status={}, updated={}",
                fingerprint(token), fingerprint(claimId), resolvedStatus, updated);
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean cancelToken(String token, String factoryId, Long userId, String reason) {
        int cancelled = tokenRepository.cancelPendingToken(
                token, factoryId, userId, truncateMessage(reason != null ? reason : "用户取消"),
                LocalDateTime.now(), TokenStatus.PENDING, TokenStatus.CANCELLED);
        log.info("取消预览令牌: tokenFingerprint={}, cancelled={}", fingerprint(token), cancelled);
        return cancelled == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getPreviewData(String token) {
        return validateToken(token).flatMap(previewToken -> {
            try {
                return Optional.of(parseParameters(previewToken.getPreviewData()));
            } catch (IllegalArgumentException e) {
                log.warn("解析预览数据失败: tokenFingerprint={}", fingerprint(token));
                return Optional.empty();
            }
        });
    }

    @Override
    @Transactional
    @Scheduled(fixedRate = 60000)
    public int processExpiredTokens() {
        int expired = tokenRepository.expireOldTokens(LocalDateTime.now());
        if (expired > 0) {
            log.info("处理过期令牌: count={}", expired);
        }
        return expired;
    }

    @Override
    @Transactional
    public int cleanupOldTokens(int daysToKeep) {
        int deleted = tokenRepository.deleteOldResolvedTokens(LocalDateTime.now().minusDays(daysToKeep));
        if (deleted > 0) {
            log.info("清理历史令牌: deleted={}, keepDays={}", deleted, daysToKeep);
        }
        return deleted;
    }

    private void validateBoundRequest(BoundTokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("bound token request is required");
        }
        requireNonBlank(request.factoryId(), "factoryId");
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        requireNonBlank(request.intentCode(), "intentCode");
        requireNonBlank(request.toolName(), "toolName");
        requireNonBlank(request.descriptorVersion(), "descriptorVersion");
        if (request.executionMode() != ToolExecutionMode.EXECUTE) {
            throw new IllegalArgumentException("confirmation tokens require EXECUTE mode");
        }
        if (request.expiresInSeconds() <= 0) {
            throw new IllegalArgumentException("expiresInSeconds must be positive");
        }
    }

    private void serializeLegacyPayload(IntentPreviewToken token,
                                        Map<String, Object> previewData,
                                        Map<String, Object> currentValues,
                                        Map<String, Object> newValues) {
        try {
            if (previewData != null) {
                token.setPreviewData(objectMapper.writeValueAsString(previewData));
            }
            if (currentValues != null) {
                token.setCurrentValues(objectMapper.writeValueAsString(currentValues));
            }
            if (newValues != null) {
                token.setNewValues(objectMapper.writeValueAsString(newValues));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("创建预览令牌失败: 数据序列化错误", e);
        }
    }

    private Map<String, Object> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid preview parameters", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String truncateMessage(String message) {
        String safe = message == null || message.isBlank() ? "未提供结果说明" : message;
        return safe.length() <= MAX_RESOLUTION_MESSAGE_LENGTH
                ? safe
                : safe.substring(0, MAX_RESOLUTION_MESSAGE_LENGTH);
    }

    private static String fingerprint(String value) {
        return ToolCommandDigest.tokenFingerprint(value);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
