package com.cretas.aims.service.agentops;

import com.cretas.aims.client.agentops.AgentOpsClient;
import com.cretas.aims.client.agentops.AgentOpsClient.TrustedContext;
import com.cretas.aims.client.agentops.AgentOpsClient.UpstreamException;
import com.cretas.aims.dto.agentops.AgentOpsCreateEvalSetRequest;
import com.cretas.aims.dto.agentops.AgentOpsImportRuntimeCorpusRequest;
import com.cretas.aims.dto.agentops.AgentOpsRerunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunRuntimeShadowRequest;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Safe Java facade: trusted principal in, bounded internal AgentOps response out. */
@Service
public class AgentOpsService {
    private static final Set<String> ADMIN_ROLES = Set.of(
            "factory_super_admin", "platform_admin", "permission_admin",
            "restaurant_manager", "restaurant_owner");
    private static final Set<String> SAFE_CONFLICT_CODES = Set.of(
            "EVAL_SET_VERSION_EXISTS", "IDEMPOTENCY_KEY_REUSED", "EVALUATOR_BUILD_UNAVAILABLE");

    private final AgentOpsClient client;
    private final IntentConfigManagementService intentConfigManagementService;

    public AgentOpsService(AgentOpsClient client, IntentConfigManagementService intentConfigManagementService) {
        this.client = client;
        this.intentConfigManagementService = intentConfigManagementService;
    }

    public JsonNode createEvalSet(String factoryId, String userId, String role, String correlationId,
                                  AgentOpsCreateEvalSetRequest body) {
        return call(context(factoryId, userId, role, correlationId), c -> client.createEvalSet(body, c));
    }

    public JsonNode listEvalSets(String factoryId, String userId, String role, String correlationId) {
        return call(context(factoryId, userId, role, correlationId), client::listEvalSets);
    }

    public JsonNode importRuntimeCorpus(String factoryId, String userId, String role,
                                        String correlationId,
                                        AgentOpsImportRuntimeCorpusRequest body) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.importRuntimeCorpus(body, c));
    }

    public JsonNode getEvalSet(String factoryId, String userId, String role, String correlationId,
                               UUID id, int offset, int limit) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.getEvalSet(id, offset, limit, c));
    }

    public JsonNode runExperiment(String factoryId, String userId, String role, String correlationId,
                                  AgentOpsRunExperimentRequest body) {
        return call(context(factoryId, userId, role, correlationId), c -> client.runExperiment(body, c));
    }

    public JsonNode runRuntimeShadow(String factoryId, String userId, String role,
                                     String correlationId,
                                     AgentOpsRunRuntimeShadowRequest body) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.runRuntimeShadow(body, c));
    }

    public JsonNode listExperiments(String factoryId, String userId, String role, String correlationId) {
        return call(context(factoryId, userId, role, correlationId), client::listExperiments);
    }

    public JsonNode getExperiment(String factoryId, String userId, String role, String correlationId,
                                  UUID id, int offset, int limit) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.getExperiment(id, offset, limit, c));
    }

    public JsonNode compare(String factoryId, String userId, String role, String correlationId,
                            UUID id, UUID baselineId) {
        return call(context(factoryId, userId, role, correlationId), c -> client.compareExperiments(id, baselineId, c));
    }

    public JsonNode rerun(String factoryId, String userId, String role, String correlationId,
                          UUID experimentId, AgentOpsRerunExperimentRequest body) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.rerunExperiment(experimentId, body, c));
    }

    public JsonNode trace(String factoryId, String userId, String role, String correlationId,
                          UUID runId, long afterSequence, int limit) {
        return call(context(factoryId, userId, role, correlationId),
                c -> client.getTrace(runId, afterSequence, limit, c));
    }

    private TrustedContext context(String factoryId, String userId, String role, String correlationId) {
        if (factoryId == null || userId == null || role == null || correlationId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TRUSTED_JWT_CONTEXT_REQUIRED");
        }
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        if (!ADMIN_ROLES.contains(normalizedRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AGENT_OPS_ADMIN_REQUIRED");
        }
        final String domain;
        try {
            domain = intentConfigManagementService.resolveBusinessDomain(factoryId);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT_BUSINESS_LOOKUP_FAILED", ex);
        }
        if (!"RESTAURANT".equals(domain)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESTAURANT_BUSINESS_REQUIRED");
        }
        if (!client.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_OPS_SECRET_MISSING");
        }
        TrustedContext context = new TrustedContext(factoryId, userId, normalizedRole, correlationId);
        try {
            context.validate();
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_TRUSTED_JWT_CONTEXT", ex);
        }
        return context;
    }

    private JsonNode call(TrustedContext context, ClientCall action) {
        try {
            return action.apply(context);
        } catch (UpstreamException ex) {
            throw switch (ex.getStatusCode()) {
                case 403 -> "AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED".equals(ex.getDetailCode())
                        ? new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getDetailCode())
                        : new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AGENT_OPS_BAD_UPSTREAM");
                case 409 -> new ResponseStatusException(HttpStatus.CONFLICT, conflictCode(ex));
                case 503 -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AGENT_OPS_RUNTIME_SHADOW_DISABLED".equals(ex.getDetailCode())
                                ? ex.getDetailCode() : "AGENT_OPS_STORE_UNAVAILABLE");
                case 504 -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "RUNTIME_SHADOW_CASE_TIMEOUT");
                default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AGENT_OPS_BAD_UPSTREAM");
            };
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_OPS_UNREACHABLE", ex);
        }
    }

    private String conflictCode(UpstreamException ex) {
        return ex.getDetailCode() != null && SAFE_CONFLICT_CODES.contains(ex.getDetailCode())
                ? ex.getDetailCode() : "AGENT_OPS_CONFLICT";
    }

    @FunctionalInterface
    private interface ClientCall { JsonNode apply(TrustedContext context) throws IOException; }
}
