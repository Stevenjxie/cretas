package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentEventV1;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantAgentActionProposalMapperTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final RestaurantAgentActionProposalMapper mapper =
            new RestaurantAgentActionProposalMapper(new ObjectMapper());

    @Test
    void closesEveryProposalReferenceAgainstSameRunClaimAndPersistedEvidence() {
        RestaurantAgentActionProposalContext context = mapper.fromReplay(
                RUN_ID, RestaurantAgentActionProposalMapper.PROPOSAL_CODE, replay("-3.5"));

        assertThat(context.actionCode()).isEqualTo("REVIEW_DISH_COST_DATA");
        assertThat(context.executionMode()).isEqualTo("READ_ONLY_PROPOSAL");
        assertThat(context.rationaleCodes()).contains("DISH_MARGIN_UNAVAILABLE");
        assertThat(context.evidenceReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.evidenceId()).isEqualTo("evidence-1");
            assertThat(reference.factId()).isEqualTo("fact-1");
            assertThat(reference.statementCode()).isEqualTo("GROSS_MARGIN_DECLINE_OBSERVED");
            assertThat(reference.value()).isEqualTo("-3.5");
        });
        assertThat(context.outcomeDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsUnpersistedOrWrongStatementEvidenceAndDigestChangesWithReplay() {
        RestaurantAgentRunReplayResponse wrongStatement = replay("-3.5");
        claim(wrongStatement).put("statementCode", "DISH_MARGIN_UNAVAILABLE");
        assertThatThrownBy(() -> mapper.fromReplay(
                RUN_ID, RestaurantAgentActionProposalMapper.PROPOSAL_CODE, wrongStatement))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_EVIDENCE_NOT_CLOSED");

        RestaurantAgentRunReplayResponse mismatchedFact = replay("-3.5");
        fact(mismatchedFact).put("value", "-2.0");
        assertThatThrownBy(() -> mapper.fromReplay(
                RUN_ID, RestaurantAgentActionProposalMapper.PROPOSAL_CODE, mismatchedFact))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_EVIDENCE_VALUE_MISMATCH");

        String first = mapper.fromReplay(RUN_ID,
                RestaurantAgentActionProposalMapper.PROPOSAL_CODE, replay("-3.5")).outcomeDigest();
        String second = mapper.fromReplay(RUN_ID,
                RestaurantAgentActionProposalMapper.PROPOSAL_CODE, replay("-4.0")).outcomeDigest();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsEvidenceFromAnotherRunEvenWhenIdsAndValuesMatch() {
        RestaurantAgentRunReplayResponse replay = replay("-3.5");
        replay.getEvents().get(0).setRunId("00000000-0000-0000-0000-000000000002");

        assertThatThrownBy(() -> mapper.fromReplay(
                RUN_ID, RestaurantAgentActionProposalMapper.PROPOSAL_CODE, replay))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_EVIDENCE_EVENT_RUN_MISMATCH");
    }

    @Test
    void navigationIsMappedOnlyForApprovedWorkflowInstance() {
        RestaurantAgentActionProposalContext context = mapper.fromReplay(
                RUN_ID, RestaurantAgentActionProposalMapper.PROPOSAL_CODE, replay("-3.5"));
        ApprovalWorkflowInstance running = ApprovalWorkflowInstance.builder()
                .id("workflow-running")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .build();
        ApprovalWorkflowInstance approved = ApprovalWorkflowInstance.builder()
                .id("workflow-approved")
                .status(ApprovalWorkflowInstance.InstanceStatus.APPROVED)
                .build();

        assertThat(mapper.toWorkflowResponse(context, running, false).navigationTarget()).isNull();
        assertThat(mapper.toWorkflowResponse(context, approved, true).navigationTarget())
                .isEqualTo("/restaurant/recipes");
    }

    private RestaurantAgentRunReplayResponse replay(String value) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("factId", "fact-1");
        fact.put("metric", "gross_margin_change_pct");
        fact.put("value", value);
        fact.put("unit", "pct");

        Map<String, Object> evidencePayload = new LinkedHashMap<>();
        evidencePayload.put("evidenceId", "evidence-1");
        evidencePayload.put("factReferences", new ArrayList<>(List.of(fact)));
        RestaurantAgentEventV1 event = new RestaurantAgentEventV1(
                "1.0", RUN_ID.toString(), 1L, "EVIDENCE_RECORDED",
                "analysis", null, evidencePayload);

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("statementCode", "GROSS_MARGIN_DECLINE_OBSERVED");
        claim.put("evidenceId", "evidence-1");
        claim.put("factId", "fact-1");
        claim.put("metric", "gross_margin_change_pct");
        claim.put("value", value);
        claim.put("unit", "pct");

        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("proposalCode", "COMPLETE_DISH_COST_DATA_PROPOSAL");
        proposal.put("actionCode", "REVIEW_DISH_COST_DATA");
        proposal.put("executionMode", "READ_ONLY_PROPOSAL");
        proposal.put("rationaleCodes", List.of("DISH_MARGIN_UNAVAILABLE"));
        proposal.put("evidenceReferences", List.of(Map.of(
                "evidenceId", "evidence-1", "factId", "fact-1")));

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("routeCode", "GROSS_MARGIN_DECLINE_ATTRIBUTION");
        outcome.put("status", "PARTIAL");
        outcome.put("blockers", List.of("DISH_MARGIN_UNAVAILABLE"));
        outcome.put("claims", new ArrayList<>(List.of(claim)));
        outcome.put("actionProposals", List.of(proposal));
        return new RestaurantAgentRunReplayResponse(
                "1.0", RUN_ID.toString(), "PARTIAL", "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                2L, List.of(event), outcome, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claim(RestaurantAgentRunReplayResponse replay) {
        return (Map<String, Object>) ((List<?>) replay.getTerminalOutcome().get("claims")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fact(RestaurantAgentRunReplayResponse replay) {
        return (Map<String, Object>) ((List<?>) replay.getEvents().get(0)
                .getPayload().get("factReferences")).get(0);
    }
}
