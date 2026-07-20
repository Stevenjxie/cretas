package com.cretas.aims.service.restaurant;

import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionPreviewResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionPreviewResponse.EvidenceReference;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Preview/confirm bridge from one read-only agent proposal to one human approval workflow. */
@Service
public class RestaurantAgentActionWorkflowService {

    private static final String INTENT_CODE = "RESTAURANT_AGENT_ACTION_REVIEW";
    private static final String TOOL_BINDING = "restaurant_agent_action_workflow";
    private static final String ENTITY_TYPE = "RESTAURANT_AGENT_RUN";
    private static final String OPERATION = "START_HUMAN_REVIEW";
    private static final int TOKEN_TTL_SECONDS = 300;
    private static final Set<String> TOKEN_PARAMETER_KEYS = Set.of(
            "runId", "proposalCode", "outcomeDigest", "workflowKey");

    private final RestaurantAgentRunService runService;
    private final RestaurantAgentActionProposalMapper mapper;
    private final PreviewTokenService previewTokenService;
    private final WorkflowEngineService workflowEngineService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final boolean enabled;

    public RestaurantAgentActionWorkflowService(
            RestaurantAgentRunService runService,
            RestaurantAgentActionProposalMapper mapper,
            PreviewTokenService previewTokenService,
            WorkflowEngineService workflowEngineService,
            ApprovalWorkflowService approvalWorkflowService,
            @Value("${cretas.restaurant-agent.action-workflow-enabled:false}") boolean enabled) {
        this.runService = runService;
        this.mapper = mapper;
        this.previewTokenService = previewTokenService;
        this.workflowEngineService = workflowEngineService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.enabled = enabled;
    }

    public RestaurantAgentActionPreviewResponse preview(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            UUID runId,
            String proposalCode) {
        requireEnabled();
        Long actorId = trustedActorId(userId);
        RestaurantAgentActionProposalContext context = replayContext(
                factoryId, userId, role, correlationId, runId, proposalCode);

        IntentPreviewToken token = previewTokenService.createBoundToken(new BoundTokenRequest(
                factoryId,
                actorId,
                null,
                INTENT_CODE,
                "Restaurant agent action review",
                TOOL_BINDING,
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                ToolExecutionMode.EXECUTE,
                ENTITY_TYPE,
                businessEntityId(context),
                OPERATION,
                tokenParameters(context),
                Map.of(),
                Map.of(),
                TOKEN_TTL_SECONDS));

        List<EvidenceReference> evidenceReferences = context.evidenceReferences().stream()
                .map(reference -> new EvidenceReference(
                        reference.evidenceId(), reference.factId(), reference.statementCode()))
                .toList();
        return new RestaurantAgentActionPreviewResponse(
                RestaurantAgentActionProposalMapper.SCHEMA_VERSION,
                context.runId(),
                context.proposalCode(),
                context.actionCode(),
                context.executionMode(),
                context.rationaleCodes(),
                evidenceReferences,
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                token.getToken(),
                token.getExpiresAt());
    }

    public RestaurantAgentActionWorkflowResponse confirm(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            UUID runId,
            String proposalCode,
            String previewToken) {
        requireEnabled();
        Long actorId = trustedActorId(userId);
        ClaimResult claim = previewTokenService.claimToken(previewToken, factoryId, actorId);
        if (!claim.isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "RESTAURANT_AGENT_ACTION_PREVIEW_TOKEN_REJECTED");
        }

        try {
            TokenBinding binding = requireTokenBinding(claim, runId, proposalCode);
            RestaurantAgentActionProposalContext context = replayContext(
                    factoryId, userId, role, correlationId, runId, proposalCode);
            if (!binding.outcomeDigest().equals(context.outcomeDigest())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "RESTAURANT_AGENT_ACTION_OUTCOME_CHANGED");
            }

            WorkflowStartResult workflow = startOrReuse(factoryId, actorId, context);
            if (!previewTokenService.resolveClaim(
                    previewToken,
                    claim.getClaimId(),
                    true,
                    "WORKFLOW_INSTANCE:" + workflow.instance().getId())) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "RESTAURANT_AGENT_ACTION_PREVIEW_RESOLUTION_FAILED");
            }
            return mapper.toWorkflowResponse(context, workflow.instance(), workflow.reused());
        } catch (RuntimeException ex) {
            previewTokenService.resolveClaim(
                    previewToken,
                    claim.getClaimId(),
                    false,
                    controlledFailure(ex));
            throw ex;
        }
    }

    private RestaurantAgentActionProposalContext replayContext(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            UUID runId,
            String proposalCode) {
        RestaurantAgentRunReplayResponse replay = runService.replay(
                factoryId, userId, role, correlationId, runId, 0);
        return mapper.fromReplay(runId, proposalCode, replay);
    }

    private WorkflowStartResult startOrReuse(
            String factoryId,
            Long actorId,
            RestaurantAgentActionProposalContext context) {
        String entityId = businessEntityId(context);
        ApprovalWorkflowInstance existing = workflowEngineService
                .getCurrentInstance(
                        factoryId, RestaurantAgentActionProposalMapper.WORKFLOW_KEY, entityId)
                .orElse(null);
        if (existing != null) {
            return new WorkflowStartResult(existing, true);
        }

        ApprovalWorkflow configured = approvalWorkflowService
                .getActiveByDecisionType(factoryId, DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .filter(workflow -> RestaurantAgentActionProposalMapper.WORKFLOW_KEY
                        .equals(workflow.getName()))
                .filter(workflow -> "published".equals(workflow.getPublishStatus()))
                .filter(workflow -> Boolean.TRUE.equals(workflow.getEnabled()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "RESTAURANT_AGENT_ACTION_WORKFLOW_NOT_CONFIGURED"));
        if (configured.getStartNodeId() == null || configured.getStartNodeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID");
        }

        try {
            ApprovalWorkflowInstance started = workflowEngineService.startWorkflow(
                    factoryId,
                    RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                    entityId,
                    workflowContext(context),
                    actorId);
            return new WorkflowStartResult(started, false);
        } catch (RuntimeException startFailure) {
            ApprovalWorkflowInstance raced = workflowEngineService
                    .getCurrentInstance(
                            factoryId, RestaurantAgentActionProposalMapper.WORKFLOW_KEY, entityId)
                    .orElse(null);
            if (raced != null) {
                return new WorkflowStartResult(raced, true);
            }
            throw startFailure;
        }
    }

    private TokenBinding requireTokenBinding(
            ClaimResult claim,
            UUID requestedRunId,
            String requestedProposalCode) {
        IntentPreviewToken token = claim.getToken();
        Map<String, Object> parameters = claim.getParameters();
        if (token == null
                || parameters == null
                || !TOKEN_PARAMETER_KEYS.equals(parameters.keySet())
                || !INTENT_CODE.equals(token.getIntentCode())
                || !TOOL_BINDING.equals(token.getToolName())
                || !RestaurantAgentActionProposalMapper.WORKFLOW_KEY.equals(token.getDescriptorVersion())
                || token.getExecutionMode() != ToolExecutionMode.EXECUTE
                || !ENTITY_TYPE.equals(token.getEntityType())
                || !OPERATION.equals(token.getOperation())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RESTAURANT_AGENT_ACTION_PREVIEW_BINDING_INVALID");
        }
        String runId = stringParameter(parameters, "runId");
        String proposalCode = stringParameter(parameters, "proposalCode");
        String outcomeDigest = stringParameter(parameters, "outcomeDigest");
        String workflowKey = stringParameter(parameters, "workflowKey");
        if (!requestedRunId.toString().equals(runId)
                || !requestedProposalCode.equals(proposalCode)
                || !RestaurantAgentActionProposalMapper.WORKFLOW_KEY.equals(workflowKey)
                || !businessEntityId(runId, proposalCode).equals(token.getEntityId())
                || !outcomeDigest.matches("^[0-9a-f]{64}$")) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RESTAURANT_AGENT_ACTION_PREVIEW_BINDING_INVALID");
        }
        return new TokenBinding(outcomeDigest);
    }

    private Map<String, Object> tokenParameters(RestaurantAgentActionProposalContext context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("runId", context.runId());
        parameters.put("proposalCode", context.proposalCode());
        parameters.put("outcomeDigest", context.outcomeDigest());
        parameters.put("workflowKey", RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        return parameters;
    }

    private Map<String, Object> workflowContext(RestaurantAgentActionProposalContext context) {
        List<Map<String, String>> references = context.evidenceReferences().stream()
                .map(reference -> Map.of(
                        "evidenceId", reference.evidenceId(),
                        "factId", reference.factId(),
                        "statementCode", reference.statementCode()))
                .toList();
        Map<String, Object> workflowContext = new LinkedHashMap<>();
        workflowContext.put("workflowKey", RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        workflowContext.put("runId", context.runId());
        workflowContext.put("proposalCode", context.proposalCode());
        workflowContext.put("actionCode", context.actionCode());
        workflowContext.put("executionMode", context.executionMode());
        workflowContext.put("rationaleCodes", context.rationaleCodes());
        workflowContext.put("evidenceReferences", references);
        workflowContext.put("outcomeDigest", context.outcomeDigest());
        return workflowContext;
    }

    private Long trustedActorId(String userId) {
        try {
            long parsed = Long.parseLong(userId);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_TRUSTED_JWT_USER_ID");
        }
    }

    private String stringParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String string) || string.isBlank() || string.length() > 191) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RESTAURANT_AGENT_ACTION_PREVIEW_BINDING_INVALID");
        }
        return string;
    }

    private String businessEntityId(RestaurantAgentActionProposalContext context) {
        return businessEntityId(context.runId(), context.proposalCode());
    }

    private String businessEntityId(String runId, String proposalCode) {
        return "restaurant-agent:" + runId + ":" + proposalCode;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT_AGENT_ACTION_WORKFLOW_OFF");
        }
    }

    private String controlledFailure(RuntimeException ex) {
        if (ex instanceof ResponseStatusException response && response.getReason() != null) {
            return response.getReason();
        }
        return "RESTAURANT_AGENT_ACTION_CONFIRMATION_FAILED";
    }

    private record TokenBinding(String outcomeDigest) {
    }

    private record WorkflowStartResult(ApprovalWorkflowInstance instance, boolean reused) {
    }
}
