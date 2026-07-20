package com.cretas.aims.service.restaurant;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext.EvidenceReference;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentEventV1;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fail-closed mapper from durable Run replay to the single workflow proposal contract. */
@Component
public class RestaurantAgentActionProposalMapper {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String PROPOSAL_CODE = "COMPLETE_DISH_COST_DATA_PROPOSAL";
    public static final String ACTION_CODE = "REVIEW_DISH_COST_DATA";
    public static final String RATIONALE_CODE = "DISH_MARGIN_UNAVAILABLE";
    public static final String EXECUTION_MODE = "READ_ONLY_PROPOSAL";
    public static final String STATEMENT_CODE = "GROSS_MARGIN_DECLINE_OBSERVED";
    public static final String WORKFLOW_KEY = "restaurant.dish-cost-data-review.v1";
    public static final String NAVIGATION_TARGET = "/restaurant/recipes";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final int MAX_EVENTS = 256;
    private static final int MAX_FACTS_PER_EVENT = 100;
    private static final int MAX_REFERENCES = 8;

    private final ObjectMapper objectMapper;

    public RestaurantAgentActionProposalMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RestaurantAgentActionProposalContext fromReplay(
            UUID expectedRunId,
            String requestedProposalCode,
            RestaurantAgentRunReplayResponse replay) {
        if (!PROPOSAL_CODE.equals(requestedProposalCode)) {
            throw rejected("RESTAURANT_AGENT_ACTION_NOT_ALLOWLISTED");
        }
        if (replay == null
                || !SCHEMA_VERSION.equals(replay.getSchemaVersion())
                || !expectedRunId.toString().equals(replay.getRunId())
                || !RestaurantAgentRunStartRequest.ROUTE_CODE.equals(replay.getRouteCode())
                || !("COMPLETED".equals(replay.getState()) || "PARTIAL".equals(replay.getState()))
                || replay.getFailureCode() != null
                || replay.getTerminalOutcome() == null
                || replay.getEvents() == null
                || replay.getEvents().size() > MAX_EVENTS) {
            throw rejected("RESTAURANT_AGENT_TERMINAL_REPLAY_REQUIRED");
        }

        Map<String, Object> outcome = replay.getTerminalOutcome();
        if (!RestaurantAgentRunStartRequest.ROUTE_CODE.equals(string(outcome.get("routeCode")))
                || !("COMPLETE".equals(string(outcome.get("status")))
                || "PARTIAL".equals(string(outcome.get("status"))))
                || !stringList(outcome.get("blockers"), 16).contains(RATIONALE_CODE)) {
            throw rejected("RESTAURANT_AGENT_ACTION_OUTCOME_REJECTED");
        }

        List<Map<String, Object>> proposals = objectList(outcome.get("actionProposals"), 16);
        List<Map<String, Object>> selected = proposals.stream()
                .filter(proposal -> PROPOSAL_CODE.equals(string(proposal.get("proposalCode"))))
                .toList();
        if (selected.size() != 1) {
            throw rejected("RESTAURANT_AGENT_ACTION_PROPOSAL_AMBIGUOUS");
        }
        Map<String, Object> proposal = selected.get(0);
        List<String> rationaleCodes = stringList(proposal.get("rationaleCodes"), 8);
        if (!ACTION_CODE.equals(string(proposal.get("actionCode")))
                || !EXECUTION_MODE.equals(string(proposal.get("executionMode")))
                || !rationaleCodes.contains(RATIONALE_CODE)) {
            throw rejected("RESTAURANT_AGENT_ACTION_PROPOSAL_REJECTED");
        }

        List<Map<String, Object>> rawReferences = objectList(
                proposal.get("evidenceReferences"), MAX_REFERENCES);
        if (rawReferences.isEmpty()) {
            throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_REQUIRED");
        }

        Map<ReferenceKey, PersistedFact> persistedFacts =
                persistedFacts(replay.getRunId(), replay.getEvents());
        List<Map<String, Object>> claims = objectList(outcome.get("claims"), 100);
        List<EvidenceReference> references = new ArrayList<>();
        Set<ReferenceKey> seenReferences = new HashSet<>();
        for (Map<String, Object> rawReference : rawReferences) {
            String evidenceId = safeId(rawReference.get("evidenceId"));
            String factId = safeId(rawReference.get("factId"));
            ReferenceKey key = new ReferenceKey(evidenceId, factId);
            if (!seenReferences.add(key)) {
                throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_DUPLICATE");
            }

            List<Map<String, Object>> matchingClaims = claims.stream()
                    .filter(claim -> evidenceId.equals(string(claim.get("evidenceId"))))
                    .filter(claim -> factId.equals(string(claim.get("factId"))))
                    .filter(claim -> STATEMENT_CODE.equals(string(claim.get("statementCode"))))
                    .toList();
            PersistedFact persisted = persistedFacts.get(key);
            if (matchingClaims.size() != 1 || persisted == null) {
                throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_NOT_CLOSED");
            }
            Map<String, Object> claim = matchingClaims.get(0);
            String metric = boundedString(claim.get("metric"), 128);
            String value = boundedString(claim.get("value"), 128);
            String unit = nullableBoundedString(claim.get("unit"), 32);
            if (!metric.equals(persisted.metric())
                    || !value.equals(persisted.value())
                    || !Objects.equals(unit, persisted.unit())) {
                throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_VALUE_MISMATCH");
            }
            references.add(new EvidenceReference(
                    evidenceId, factId, STATEMENT_CODE, metric, value, unit));
        }

        ObjectNode digestEnvelope = objectMapper.createObjectNode();
        digestEnvelope.put("schemaVersion", replay.getSchemaVersion());
        digestEnvelope.put("runId", replay.getRunId());
        digestEnvelope.put("state", replay.getState());
        digestEnvelope.put("routeCode", replay.getRouteCode());
        digestEnvelope.set("terminalOutcome", objectMapper.valueToTree(outcome));
        digestEnvelope.set("evidenceClosure", objectMapper.valueToTree(references));
        String outcomeDigest = ToolCommandDigest.parametersHash(digestEnvelope);

        return new RestaurantAgentActionProposalContext(
                replay.getRunId(), PROPOSAL_CODE, ACTION_CODE, EXECUTION_MODE,
                rationaleCodes, references, outcomeDigest);
    }

    public RestaurantAgentActionWorkflowResponse toWorkflowResponse(
            RestaurantAgentActionProposalContext context,
            ApprovalWorkflowInstance instance,
            boolean reused) {
        String navigationTarget = instance.getStatus() == ApprovalWorkflowInstance.InstanceStatus.APPROVED
                ? NAVIGATION_TARGET
                : null;
        return new RestaurantAgentActionWorkflowResponse(
                SCHEMA_VERSION,
                context.runId(),
                context.proposalCode(),
                WORKFLOW_KEY,
                instance.getId(),
                instance.getStatus().name(),
                reused,
                navigationTarget);
    }

    private Map<ReferenceKey, PersistedFact> persistedFacts(
            String expectedRunId,
            List<RestaurantAgentEventV1> events) {
        Map<ReferenceKey, PersistedFact> facts = new HashMap<>();
        for (RestaurantAgentEventV1 event : events) {
            if (event == null
                    || !SCHEMA_VERSION.equals(event.getSchemaVersion())
                    || !expectedRunId.equals(event.getRunId())) {
                throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_EVENT_RUN_MISMATCH");
            }
            if (!"EVIDENCE_RECORDED".equals(event.getEventType())) {
                continue;
            }
            Map<String, Object> payload = event.getPayload();
            if (payload == null) {
                throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_EVENT_INVALID");
            }
            String evidenceId = safeId(payload.get("evidenceId"));
            List<Map<String, Object>> eventFacts = objectList(
                    payload.get("factReferences"), MAX_FACTS_PER_EVENT);
            for (Map<String, Object> eventFact : eventFacts) {
                String factId = safeId(eventFact.get("factId"));
                PersistedFact fact = new PersistedFact(
                        boundedString(eventFact.get("metric"), 128),
                        boundedString(eventFact.get("value"), 128),
                        nullableBoundedString(eventFact.get("unit"), 32));
                if (facts.putIfAbsent(new ReferenceKey(evidenceId, factId), fact) != null) {
                    throw rejected("RESTAURANT_AGENT_ACTION_EVIDENCE_EVENT_DUPLICATE");
                }
            }
        }
        return facts;
    }

    private List<Map<String, Object>> objectList(Object value, int maxSize) {
        if (!(value instanceof List<?> list) || list.size() > maxSize) {
            throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
            }
            Map<String, Object> typed = new HashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
                }
                typed.put(key, entry.getValue());
            }
            result.add(typed);
        }
        return result;
    }

    private List<String> stringList(Object value, int maxSize) {
        if (!(value instanceof List<?> list) || list.size() > maxSize) {
            throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String string = boundedString(item, 128);
            if (!SAFE_CODE.matcher(string).matches()) {
                throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
            }
            result.add(string);
        }
        return result;
    }

    private String safeId(Object value) {
        String id = boundedString(value, 128);
        if (!SAFE_ID.matcher(id).matches()) {
            throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
        }
        return id;
    }

    private String boundedString(Object value, int maxLength) {
        if (!(value instanceof String string) || string.isBlank() || string.length() > maxLength) {
            throw rejected("RESTAURANT_AGENT_ACTION_CONTRACT_INVALID");
        }
        return string;
    }

    private String nullableBoundedString(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        return boundedString(value, maxLength);
    }

    private String string(Object value) {
        return value instanceof String string ? string : null;
    }

    private ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private record ReferenceKey(String evidenceId, String factId) {
    }

    private record PersistedFact(String metric, String value, String unit) {
    }
}
