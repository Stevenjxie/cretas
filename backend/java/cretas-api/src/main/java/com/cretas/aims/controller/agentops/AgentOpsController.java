package com.cretas.aims.controller.agentops;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.agentops.AgentOpsCreateEvalSetRequest;
import com.cretas.aims.dto.agentops.AgentOpsRerunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunExperimentRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.service.agentops.AgentOpsService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** JWT facade over internal AgentOps; never trusts path/body identity. */
@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}/agent-ops")
@RequirePermission({"analytics:read_write", "system:read_write"})
public class AgentOpsController {
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private final AgentOpsService service;

    public AgentOpsController(AgentOpsService service) { this.service = service; }

    @PostMapping("/eval-sets")
    public ApiResponse<JsonNode> createEvalSet(@PathVariable String factoryId,
                                               @Valid @RequestBody AgentOpsCreateEvalSetRequest body,
                                               HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return ApiResponse.success(service.createEvalSet(trusted.factoryId, trusted.userId,
                trusted.role, trusted.correlationId, body));
    }

    @GetMapping("/eval-sets")
    public ResponseEntity<ApiResponse<JsonNode>> listEvalSets(@PathVariable String factoryId, HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.listEvalSets(trusted.factoryId, trusted.userId, trusted.role, trusted.correlationId));
    }

    @GetMapping("/eval-sets/{id}")
    public ResponseEntity<ApiResponse<JsonNode>> getEvalSet(@PathVariable String factoryId, @PathVariable UUID id,
                                                            @RequestParam(defaultValue = "0") @Min(0) int offset,
                                                            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
                                                            HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.getEvalSet(trusted.factoryId, trusted.userId, trusted.role,
                trusted.correlationId, id, offset, limit));
    }

    @PostMapping("/experiments")
    public ApiResponse<JsonNode> runExperiment(@PathVariable String factoryId,
                                               @Valid @RequestBody AgentOpsRunExperimentRequest body,
                                               HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return ApiResponse.success(service.runExperiment(trusted.factoryId, trusted.userId,
                trusted.role, trusted.correlationId, body));
    }

    @GetMapping("/experiments")
    public ResponseEntity<ApiResponse<JsonNode>> listExperiments(@PathVariable String factoryId, HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.listExperiments(trusted.factoryId, trusted.userId, trusted.role, trusted.correlationId));
    }

    @GetMapping("/experiments/{id}")
    public ResponseEntity<ApiResponse<JsonNode>> getExperiment(@PathVariable String factoryId, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "0") @Min(0) int offset,
                                                               @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
                                                               HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.getExperiment(trusted.factoryId, trusted.userId, trusted.role,
                trusted.correlationId, id, offset, limit));
    }

    @GetMapping("/experiments/{id}/compare")
    public ResponseEntity<ApiResponse<JsonNode>> compare(@PathVariable String factoryId, @PathVariable UUID id,
                                                         @RequestParam UUID baselineId, HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.compare(trusted.factoryId, trusted.userId, trusted.role,
                trusted.correlationId, id, baselineId));
    }

    @PostMapping("/experiments/{id}/rerun")
    public ApiResponse<JsonNode> rerun(@PathVariable String factoryId, @PathVariable UUID id,
                                       @Valid @RequestBody AgentOpsRerunExperimentRequest body,
                                       HttpServletRequest request) {
        TrustedRequest trusted = trusted(factoryId, request);
        return ApiResponse.success(service.rerun(trusted.factoryId, trusted.userId, trusted.role,
                trusted.correlationId, id, body));
    }

    @GetMapping("/traces/{runId}")
    public ResponseEntity<ApiResponse<JsonNode>> trace(@PathVariable String factoryId, @PathVariable UUID runId,
                                                       @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
                                                       @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
                                                       HttpServletRequest request) {
        if (afterSequence < 0 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACE_LIMIT_OUT_OF_BOUNDS");
        }
        TrustedRequest trusted = trusted(factoryId, request);
        return noStore(service.trace(trusted.factoryId, trusted.userId, trusted.role,
                trusted.correlationId, runId, afterSequence, limit));
    }

    private ResponseEntity<ApiResponse<JsonNode>> noStore(JsonNode data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    private TrustedRequest trusted(String pathFactoryId, HttpServletRequest request) {
        String factoryId = attribute(request, "factoryId");
        String userId = attribute(request, "userId");
        String role = attribute(request, "role");
        if (factoryId == null || userId == null || role == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TRUSTED_JWT_CONTEXT_REQUIRED");
        }
        if (!pathFactoryId.equals(factoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FACTORY_CONTEXT_MISMATCH");
        }
        role = role.toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(factoryId).matches() || !SAFE_ID.matcher(userId).matches() || !SAFE_ID.matcher(role).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_TRUSTED_JWT_CONTEXT");
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) correlationId = UUID.randomUUID().toString();
        if (!SAFE_ID.matcher(correlationId).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CORRELATION_ID");
        }
        return new TrustedRequest(factoryId, userId, role, correlationId);
    }

    private String attribute(HttpServletRequest request, String key) {
        Object value = request.getAttribute(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record TrustedRequest(String factoryId, String userId, String role, String correlationId) {}
}
