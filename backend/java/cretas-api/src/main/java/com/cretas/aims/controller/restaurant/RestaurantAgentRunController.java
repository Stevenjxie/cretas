package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunCancelResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionConfirmRequest;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionPreviewResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowService;
import com.cretas.aims.service.restaurant.RestaurantAgentRunService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Mobile/web JWT facade for one explicit restaurant analysis action. */
@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant-agent/runs")
@RequirePermission("procurement:price:view")
public class RestaurantAgentRunController {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SAFE_ROLE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final RestaurantAgentRunService service;
    private final RestaurantAgentActionWorkflowService actionWorkflowService;

    public RestaurantAgentRunController(
            RestaurantAgentRunService service,
            RestaurantAgentActionWorkflowService actionWorkflowService) {
        this.service = service;
        this.actionWorkflowService = actionWorkflowService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> start(
            @PathVariable String factoryId,
            @Valid @RequestBody RestaurantAgentRunStartRequest body,
            HttpServletRequest servletRequest) {
        TrustedRequest trusted = trustedRequest(factoryId, servletRequest);
        RestaurantAgentRunService.StreamResult result = service.start(
                trusted.factoryId(), trusted.userId(), trusted.role(), trusted.correlationId(), body);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no");
        response.header("X-Agent-Run-Id", result.runId().toString());
        return response.body(result.emitter());
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestaurantAgentRunReplayResponse> replay(
            @PathVariable String factoryId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
            HttpServletRequest servletRequest) {
        TrustedRequest trusted = trustedRequest(factoryId, servletRequest);
        RestaurantAgentRunReplayResponse response = service.replay(
                trusted.factoryId(), trusted.userId(), trusted.role(), trusted.correlationId(),
                runId, afterSequence);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping(value = "/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestaurantAgentRunCancelResponse> cancel(
            @PathVariable String factoryId,
            @PathVariable UUID runId,
            HttpServletRequest servletRequest) {
        TrustedRequest trusted = trustedRequest(factoryId, servletRequest);
        RestaurantAgentRunCancelResponse response = service.cancel(
                trusted.factoryId(), trusted.userId(), trusted.role(), trusted.correlationId(), runId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping(
            value = "/{runId}/action-proposals/{proposalCode}/preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestaurantAgentActionPreviewResponse> previewActionProposal(
            @PathVariable String factoryId,
            @PathVariable UUID runId,
            @PathVariable String proposalCode,
            HttpServletRequest servletRequest) {
        TrustedRequest trusted = trustedRequest(factoryId, servletRequest);
        RestaurantAgentActionPreviewResponse response = actionWorkflowService.preview(
                trusted.factoryId(), trusted.userId(), trusted.role(), trusted.correlationId(),
                runId, proposalCode);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping(
            value = "/{runId}/action-proposals/{proposalCode}/confirm",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestaurantAgentActionWorkflowResponse> confirmActionProposal(
            @PathVariable String factoryId,
            @PathVariable UUID runId,
            @PathVariable String proposalCode,
            @Valid @RequestBody RestaurantAgentActionConfirmRequest body,
            HttpServletRequest servletRequest) {
        TrustedRequest trusted = trustedRequest(factoryId, servletRequest);
        RestaurantAgentActionWorkflowResponse response = actionWorkflowService.confirm(
                trusted.factoryId(), trusted.userId(), trusted.role(), trusted.correlationId(),
                runId, proposalCode, body.getPreviewToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private TrustedRequest trustedRequest(String pathFactoryId, HttpServletRequest request) {
        String tokenFactoryId = stringAttribute(request, "factoryId");
        String userId = stringAttribute(request, "userId");
        String role = stringAttribute(request, "role");
        if (tokenFactoryId == null || userId == null || role == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TRUSTED_JWT_CONTEXT_REQUIRED");
        }
        if (!pathFactoryId.equals(tokenFactoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FACTORY_CONTEXT_MISMATCH");
        }
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(pathFactoryId).matches()
                || !SAFE_USER_ID.matcher(userId).matches()
                || !SAFE_ROLE.matcher(normalizedRole).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_TRUSTED_JWT_CONTEXT");
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else if (!SAFE_ID.matcher(correlationId).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CORRELATION_ID");
        }
        return new TrustedRequest(pathFactoryId, userId, normalizedRole, correlationId);
    }

    private String stringAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    private record TrustedRequest(String factoryId, String userId, String role, String correlationId) {
    }
}
