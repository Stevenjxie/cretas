package com.cretas.aims.service.restaurant;

import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestaurantAgentActionWorkflowServiceTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROPOSAL = "COMPLETE_DISH_COST_DATA_PROPOSAL";
    private static final String TOKEN = "00000000-0000-0000-0000-000000000099";

    private final RestaurantAgentRunService runService = mock(RestaurantAgentRunService.class);
    private final RestaurantAgentActionProposalMapper mapper = mock(RestaurantAgentActionProposalMapper.class);
    private final PreviewTokenService previewTokenService = mock(PreviewTokenService.class);
    private final WorkflowEngineService workflowEngineService = mock(WorkflowEngineService.class);
    private final ApprovalWorkflowService approvalWorkflowService = mock(ApprovalWorkflowService.class);
    private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
    private final RestaurantAgentActionWorkflowProvisioner workflowProvisioner =
            mock(RestaurantAgentActionWorkflowProvisioner.class);

    @Test
    void featureFlagDefaultsFailClosedBeforeReplayOrPersistence() {
        RestaurantAgentActionWorkflowService service = service(false);

        assertThatThrownBy(() -> service.preview(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_WORKFLOW_OFF");

        verifyNoInteractions(runService, mapper, previewTokenService,
                workflowEngineService, approvalWorkflowService);
    }

    @Test
    void previewIssuesServerBoundTokenFromReplayedContextOnly() {
        RestaurantAgentActionWorkflowService service = service(true);
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        RestaurantAgentActionProposalContext context = context("a".repeat(64));
        IntentPreviewToken token = mock(IntentPreviewToken.class);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context);
        when(previewTokenService.createBoundToken(any())).thenReturn(token);
        when(token.getToken()).thenReturn(TOKEN);

        service.preview("R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL);

        ArgumentCaptor<BoundTokenRequest> request = ArgumentCaptor.forClass(BoundTokenRequest.class);
        verify(previewTokenService).createBoundToken(request.capture());
        assertThat(request.getValue().factoryId()).isEqualTo("R001");
        assertThat(request.getValue().userId()).isEqualTo(42L);
        assertThat(request.getValue().executionMode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(request.getValue().entityId()).isEqualTo(
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL);
        assertThat(request.getValue().parameters()).containsOnlyKeys(
                "runId", "proposalCode", "outcomeDigest", "workflowKey");
        assertThat(request.getValue().parameters()).doesNotContainKeys(
                "actionCode", "evidenceReferences", "navigationTarget");
    }

    @Test
    void confirmClaimsThenReplaysAndStartsOnlyHumanReviewWorkflow() {
        RestaurantAgentActionWorkflowService service = service(true);
        String digest = "a".repeat(64);
        IntentPreviewToken token = boundToken(digest);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.success(token, "claim-1", tokenParameters(digest)));
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        RestaurantAgentActionProposalContext context = context(digest);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context);
        when(workflowEngineService.getCurrentInstance(
                "R001", RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL))
                .thenReturn(Optional.empty());
        ApprovalWorkflow workflow = ApprovalWorkflow.builder()
                .name(RestaurantAgentActionProposalMapper.WORKFLOW_KEY)
                .publishStatus("published")
                .enabled(true)
                .startNodeId("start")
                .build();
        when(approvalWorkflowService.getActiveByDecisionType(
                "R001", DecisionType.RESTAURANT_AGENT_ACTION_REVIEW))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowInstance instance = ApprovalWorkflowInstance.builder()
                .id("workflow-instance-1")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .build();
        when(workflowEngineService.startWorkflowWithDefinition(
                eq("R001"), eq(RestaurantAgentActionProposalMapper.WORKFLOW_KEY),
                eq("restaurant-agent:" + RUN_ID + ":" + PROPOSAL), any(), eq(42L), eq(workflow)))
                .thenReturn(instance);
        when(previewTokenService.resolveClaim(
                TOKEN, "claim-1", true, "WORKFLOW_INSTANCE:workflow-instance-1"))
                .thenReturn(true);
        RestaurantAgentActionWorkflowResponse response = new RestaurantAgentActionWorkflowResponse(
                "1.0", RUN_ID.toString(), PROPOSAL,
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "workflow-instance-1", "RUNNING", false, null);
        when(mapper.toWorkflowResponse(context, instance, false)).thenReturn(response);

        assertThat(service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isSameAs(response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> workflowContext = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngineService).startWorkflowWithDefinition(
                eq("R001"), eq(RestaurantAgentActionProposalMapper.WORKFLOW_KEY),
                eq("restaurant-agent:" + RUN_ID + ":" + PROPOSAL),
                workflowContext.capture(), eq(42L), eq(workflow));
        assertThat(workflowContext.getValue()).containsEntry("executionMode", "READ_ONLY_PROPOSAL");
        assertThat(workflowContext.getValue()).doesNotContainKeys(
                "navigationTarget", "recipeId", "price", "toolName");
    }

    @Test
    void confirmReusesExistingActiveInstanceAfterCanonicalConfigurationValidation() {
        RestaurantAgentActionWorkflowService service = service(true);
        String digest = "a".repeat(64);
        IntentPreviewToken token = boundToken(digest);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.success(token, "claim-1", tokenParameters(digest)));
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        RestaurantAgentActionProposalContext context = context(digest);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context);
        ApprovalWorkflowInstance existing = ApprovalWorkflowInstance.builder()
                .id("workflow-instance-existing")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .build();
        when(workflowEngineService.getCurrentInstance(
                "R001", RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL))
                .thenReturn(Optional.of(existing));
        ApprovalWorkflow canonical = ApprovalWorkflow.builder()
                .name(RestaurantAgentActionProposalMapper.WORKFLOW_KEY)
                .publishStatus("published")
                .enabled(true)
                .startNodeId("start")
                .build();
        when(approvalWorkflowService.getActiveByDecisionType(
                "R001", DecisionType.RESTAURANT_AGENT_ACTION_REVIEW))
                .thenReturn(Optional.of(canonical));
        when(previewTokenService.resolveClaim(
                TOKEN, "claim-1", true, "WORKFLOW_INSTANCE:workflow-instance-existing"))
                .thenReturn(true);
        RestaurantAgentActionWorkflowResponse response = new RestaurantAgentActionWorkflowResponse(
                "1.0", RUN_ID.toString(), PROPOSAL,
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "workflow-instance-existing", "RUNNING", true, null);
        when(mapper.toWorkflowResponse(context, existing, true)).thenReturn(response);

        assertThat(service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isSameAs(response);

        verify(workflowProvisioner).isCanonical(canonical);
        verify(workflowEngineService, never()).startWorkflowWithDefinition(
                any(), any(), any(), any(), any(), any());
        verify(mapper).toWorkflowResponse(context, existing, true);
    }

    @Test
    void concurrentStartFailureRereadsWinnerAndReturnsReusedInstance() {
        RestaurantAgentActionWorkflowService service = service(true);
        String digest = "a".repeat(64);
        IntentPreviewToken token = boundToken(digest);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.success(token, "claim-1", tokenParameters(digest)));
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        RestaurantAgentActionProposalContext context = context(digest);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context);
        ApprovalWorkflowInstance winner = ApprovalWorkflowInstance.builder()
                .id("workflow-instance-winner")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .build();
        when(workflowEngineService.getCurrentInstance(
                "R001", RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(approvalWorkflowService.getActiveByDecisionType(
                "R001", DecisionType.RESTAURANT_AGENT_ACTION_REVIEW))
                .thenReturn(Optional.of(ApprovalWorkflow.builder()
                        .name(RestaurantAgentActionProposalMapper.WORKFLOW_KEY)
                        .publishStatus("published")
                        .enabled(true)
                        .startNodeId("start")
                        .build()));
        when(workflowEngineService.startWorkflowWithDefinition(
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("unique active instance race"));
        when(previewTokenService.resolveClaim(
                TOKEN, "claim-1", true, "WORKFLOW_INSTANCE:workflow-instance-winner"))
                .thenReturn(true);
        RestaurantAgentActionWorkflowResponse response = new RestaurantAgentActionWorkflowResponse(
                "1.0", RUN_ID.toString(), PROPOSAL,
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "workflow-instance-winner", "RUNNING", true, null);
        when(mapper.toWorkflowResponse(context, winner, true)).thenReturn(response);

        assertThat(service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isSameAs(response);

        verify(workflowEngineService).startWorkflowWithDefinition(
                eq("R001"), eq(RestaurantAgentActionProposalMapper.WORKFLOW_KEY),
                eq("restaurant-agent:" + RUN_ID + ":" + PROPOSAL), any(), eq(42L), any());
        verify(mapper).toWorkflowResponse(context, winner, true);
    }

    @Test
    void rejectedClaimPerformsZeroReplayAndZeroWorkflowWork() {
        RestaurantAgentActionWorkflowService service = service(true);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.failure("already claimed"));

        assertThatThrownBy(() -> service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_PREVIEW_TOKEN_REJECTED");

        verifyNoInteractions(runService, mapper, workflowEngineService, approvalWorkflowService);
        verify(previewTokenService, never()).resolveClaim(any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @CsvSource({"FACTORY,true", "RESTAURANT,false"})
    void confirmFailsClosedForNonRestaurantOrInactiveTenant(
            FactoryType factoryType, boolean active) {
        RestaurantAgentActionWorkflowService service = service(true);
        String digest = "a".repeat(64);
        IntentPreviewToken token = boundToken(digest);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.success(token, "claim-1", tokenParameters(digest)));
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context(digest));
        Factory ineligible = new Factory();
        ineligible.setId("R001");
        ineligible.setType(factoryType);
        ineligible.setIsActive(active);
        when(factoryRepository.findById("R001")).thenReturn(Optional.of(ineligible));

        assertThatThrownBy(() -> service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_WORKFLOW_TENANT_NOT_ELIGIBLE");

        verify(previewTokenService).resolveClaim(
                TOKEN, "claim-1", false,
                "RESTAURANT_AGENT_ACTION_WORKFLOW_TENANT_NOT_ELIGIBLE");
        verifyNoInteractions(workflowProvisioner, approvalWorkflowService);
        verify(workflowEngineService, never()).startWorkflowWithDefinition(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void changedReplayDigestFailsBeforeAnyWorkflowWriteAndResolvesLeaseFailed() {
        RestaurantAgentActionWorkflowService service = service(true);
        String issuedDigest = "a".repeat(64);
        IntentPreviewToken token = boundToken(issuedDigest);
        when(previewTokenService.claimToken(TOKEN, "R001", 42L))
                .thenReturn(ClaimResult.success(token, "claim-1", tokenParameters(issuedDigest)));
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        when(runService.replay("R001", "42", "restaurant_owner", "corr-1", RUN_ID, 0L))
                .thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context("b".repeat(64)));

        assertThatThrownBy(() -> service.confirm(
                "R001", "42", "restaurant_owner", "corr-1", RUN_ID, PROPOSAL, TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_OUTCOME_CHANGED");

        verify(previewTokenService).resolveClaim(
                TOKEN, "claim-1", false, "RESTAURANT_AGENT_ACTION_OUTCOME_CHANGED");
        verify(workflowEngineService, never()).startWorkflowWithDefinition(
                any(), any(), any(), any(), any(), any());
        verifyNoInteractions(approvalWorkflowService);
    }

    private RestaurantAgentActionWorkflowService service(boolean enabled) {
        Factory restaurant = new Factory();
        restaurant.setId("R001");
        restaurant.setType(FactoryType.RESTAURANT);
        restaurant.setIsActive(true);
        when(factoryRepository.findById("R001")).thenReturn(Optional.of(restaurant));
        when(workflowProvisioner.isCanonical(any())).thenReturn(true);
        return new RestaurantAgentActionWorkflowService(
                runService, mapper, previewTokenService,
                workflowEngineService, approvalWorkflowService,
                factoryRepository, workflowProvisioner, enabled);
    }

    private RestaurantAgentActionProposalContext context(String digest) {
        return new RestaurantAgentActionProposalContext(
                RUN_ID.toString(), PROPOSAL, "REVIEW_DISH_COST_DATA", "READ_ONLY_PROPOSAL",
                List.of("DISH_MARGIN_UNAVAILABLE"),
                List.of(new RestaurantAgentActionProposalContext.EvidenceReference(
                        "evidence-1", "fact-1", "GROSS_MARGIN_DECLINE_OBSERVED",
                        "gross_margin_change_pct", "-3.5", "pct")),
                digest);
    }

    private IntentPreviewToken boundToken(String digest) {
        IntentPreviewToken token = mock(IntentPreviewToken.class);
        when(token.getIntentCode()).thenReturn("RESTAURANT_AGENT_ACTION_REVIEW");
        when(token.getToolName()).thenReturn("restaurant_agent_action_workflow");
        when(token.getDescriptorVersion()).thenReturn(
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        when(token.getExecutionMode()).thenReturn(ToolExecutionMode.EXECUTE);
        when(token.getEntityType()).thenReturn("RESTAURANT_AGENT_RUN");
        when(token.getEntityId()).thenReturn(
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL);
        when(token.getOperation()).thenReturn("START_HUMAN_REVIEW");
        return token;
    }

    private Map<String, Object> tokenParameters(String digest) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("runId", RUN_ID.toString());
        parameters.put("proposalCode", PROPOSAL);
        parameters.put("outcomeDigest", digest);
        parameters.put("workflowKey", RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        return parameters;
    }
}
