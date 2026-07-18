package com.cretas.aims.dto.restaurantagent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Durable run replay response. It intentionally contains no full EvidenceEnvelope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantAgentRunReplayResponse {
    private String schemaVersion;
    private String runId;
    private String state;
    private String routeCode;
    private long nextEventSequence;
    private List<RestaurantAgentEventV1> events;
    private Map<String, Object> terminalOutcome;
    private String failureCode;
}
