package com.cretas.aims.dto.restaurantagent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Persisted Event v1 returned by the bounded Python runtime. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantAgentEventV1 {
    private String schemaVersion;
    private String runId;
    private long sequence;
    private String eventType;
    private String stepId;
    private String toolName;
    private Map<String, Object> payload;
}
