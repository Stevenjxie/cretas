package com.cretas.aims.dto.restaurantagent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable acknowledgement for an explicit server-side cancellation request. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantAgentRunCancelResponse {
    private String schemaVersion;
    private String runId;
    private String result;
    private String state;
    private long nextEventSequence;
}
