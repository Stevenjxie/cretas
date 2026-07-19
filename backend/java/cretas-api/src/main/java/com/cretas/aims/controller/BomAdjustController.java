package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fixed HTTP entry for governed {@code bom_adjust} preview and execution. */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/adjust")
@Tag(name = "BOM对话微调", description = "BOM 微调的显式预览与确认入口")
@RequiredArgsConstructor
public class BomAdjustController {

    private static final String TOOL_NAME = "bom_adjust";
    private static final String DESCRIPTOR_VERSION = "1.0.0";
    private static final int CONFIRMATION_TTL_SECONDS = 300;
    private static final Pattern SAFE_FACTORY_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SAFE_ROLE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final ToolExecutionGateway toolExecutionGateway;
    private final AuthenticatedToolPrincipalFactory principalFactory;
    private final PreviewTokenService previewTokenService;
    private final ObjectMapper objectMapper;

    @PostMapping("/preview")
    @Operation(summary = "预览 BOM 微调，不写入业务数据")
    public ApiResponse<Object> preview(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        AuthenticatedIdentity identity = authenticatedIdentity(factoryId, request);
        ToolExecutionResult result = executeGateway(
                body, ToolExecutionMode.PREVIEW, identity, Optional.empty());
        Map<String, Object> data = successfulPayload(result, "BOM adjustment preview failed");

        IntentPreviewToken token = previewTokenService.createBoundToken(
                new PreviewTokenService.BoundTokenRequest(
                        factoryId,
                        identity.userId(),
                        identity.username(),
                        "BOM_ADJUST",
                        "BOM adjustment",
                        TOOL_NAME,
                        DESCRIPTOR_VERSION,
                        ToolExecutionMode.EXECUTE,
                        "BOM_RECIPE",
                        text(body.get("productTypeId")),
                        "UPDATE",
                        body,
                        Map.of(),
                        body,
                        CONFIRMATION_TTL_SECONDS));
        data.put("confirmationToken", token.getToken());
        data.put("confirmationExpiresAt", token.getExpiresAt().toString());
        return ApiResponse.success(data);
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "确认并执行 BOM 微调")
    public ApiResponse<Object> apply(
            @PathVariable String factoryId,
            @RequestHeader("X-Cretas-Confirmation-Token") String confirmationToken,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        AuthenticatedIdentity identity = authenticatedIdentity(factoryId, request);
        IntentPreviewToken token = requireBoundToken(
                confirmationToken, factoryId, body, identity.userId());
        ConfirmationProof proof = new ConfirmationProof(
                token.getToken(),
                token.getCommandDigest(),
                token.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
        String idempotencyKey = "fixed-http-"
                + ToolCommandDigest.persistentSecretFingerprint(token.getToken());
        ToolExecutionResult result = executeGateway(
                body,
                ToolExecutionMode.EXECUTE,
                identity,
                Optional.of(new ExecutionEvidence(idempotencyKey, proof)));
        return ApiResponse.success(successfulPayload(result, "BOM adjustment failed"));
    }

    private ToolExecutionResult executeGateway(
            Map<String, Object> body,
            ToolExecutionMode mode,
            AuthenticatedIdentity identity,
            Optional<ExecutionEvidence> evidence) {
        try {
            String callId = "bom-adjust-" + UUID.randomUUID();
            ToolExecutionCommand command = new ToolExecutionCommand(
                    callId,
                    callId,
                    callId + "-trace",
                    TOOL_NAME,
                    DESCRIPTOR_VERSION,
                    objectMapper.valueToTree(body),
                    identity.principal(),
                    ToolExecutionSource.HTTP_CONTROLLER,
                    mode,
                    evidence.map(ExecutionEvidence::idempotencyKey),
                    evidence.map(ExecutionEvidence::confirmationProof),
                    Optional.empty(),
                    Instant.now().plusSeconds(30));
            return toolExecutionGateway.execute(command);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[BOM-ADJUST] Gateway {} failed: {}",
                    mode, exception.getMessage(), exception);
            throw new BusinessException(500, "BOM 微调失败，请稍后重试");
        }
    }

    private AuthenticatedIdentity authenticatedIdentity(
            String factoryId,
            HttpServletRequest request) {
        String trustedFactoryId = stringAttribute(request, "factoryId");
        String trustedUserId = stringAttribute(request, "userId");
        String trustedRole = stringAttribute(request, "role");
        if (trustedFactoryId == null || trustedUserId == null || trustedRole == null) {
            throw new BusinessException(401, "Trusted authentication context is required");
        }
        if (!factoryId.equals(trustedFactoryId)) {
            throw new BusinessException(403,
                    "Authenticated tenant does not match request tenant");
        }
        String normalizedRole = trustedRole.toLowerCase(Locale.ROOT);
        if (!SAFE_FACTORY_ID.matcher(factoryId).matches()
                || !SAFE_USER_ID.matcher(trustedUserId).matches()
                || !SAFE_ROLE.matcher(normalizedRole).matches()) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        long parsedUserId;
        try {
            parsedUserId = Long.parseLong(trustedUserId);
        } catch (NumberFormatException invalidUserId) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        if (parsedUserId <= 0) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        ExecutionPrincipal principal = principalFactory.create(
                factoryId, parsedUserId, normalizedRole);
        return new AuthenticatedIdentity(
                parsedUserId, boundedUsername(stringAttribute(request, "username")), principal);
    }

    private IntentPreviewToken requireBoundToken(
            String proofToken,
            String factoryId,
            Map<String, Object> body,
            Long userId) {
        if (proofToken == null || proofToken.isBlank() || proofToken.length() > 2048) {
            throw confirmationRejected();
        }
        IntentPreviewToken token = previewTokenService.validateTokenForUser(proofToken, userId)
                .filter(IntentPreviewToken::isBoundForExecution)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .filter(candidate -> factoryId.equals(candidate.getTenantId()))
                .filter(candidate -> TOOL_NAME.equals(candidate.getToolName()))
                .filter(candidate -> DESCRIPTOR_VERSION.equals(candidate.getDescriptorVersion()))
                .filter(candidate -> candidate.getExecutionMode() == ToolExecutionMode.EXECUTE)
                .orElseThrow(this::confirmationRejected);
        String expectedDigest = ToolCommandDigest.commandDigest(
                factoryId,
                userId,
                TOOL_NAME,
                DESCRIPTOR_VERSION,
                ToolExecutionMode.EXECUTE,
                objectMapper.valueToTree(body));
        if (!expectedDigest.equals(token.getCommandDigest())) {
            throw confirmationRejected();
        }
        return token;
    }

    private BusinessException confirmationRejected() {
        return new BusinessException(
                409, "Preview confirmation is missing, expired, or no longer matches")
                .withCode("INVALID_CONFIRMATION_PROOF")
                .withSeverity("BLOCKING");
    }

    private Map<String, Object> successfulPayload(
            ToolExecutionResult result,
            String fallbackMessage) {
        if (result.status() != ToolExecutionStatus.SUCCEEDED || !result.payload().isObject()) {
            String message = result.payload().path("message").asText(result.message());
            int code = result.status() == ToolExecutionStatus.DENIED ? 403 : 400;
            throw new BusinessException(code, message.isBlank() ? fallbackMessage : message);
        }
        Map<String, Object> payload = objectMapper.convertValue(
                result.payload(), new TypeReference<Map<String, Object>>() {});
        Object inner = payload.get("data");
        if (inner instanceof Map<?, ?> innerMap) {
            return new LinkedHashMap<>(objectMapper.convertValue(
                    innerMap, new TypeReference<Map<String, Object>>() {}));
        }
        return new LinkedHashMap<>(payload);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String stringAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    private String boundedUsername(String username) {
        return username != null && username.length() <= 100 ? username : null;
    }

    private record AuthenticatedIdentity(
            Long userId,
            String username,
            ExecutionPrincipal principal) {
    }

    private record ExecutionEvidence(
            String idempotencyKey,
            ConfirmationProof confirmationProof) {
    }
}
