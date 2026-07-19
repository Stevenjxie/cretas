package com.cretas.aims.dto.python;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed request body for Python {@code /api/chat/general-analysis}.
 *
 * <p>Trusted tenant and interactive-user identities are transported only in
 * headers by {@code PythonSmartBIClient}; they deliberately do not belong to
 * this DTO.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PythonGeneralAnalysisRequest {

    private String message;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("enable_thinking")
    private Boolean enableThinking;

    @JsonProperty("thinking_budget")
    private Integer thinkingBudget;

    @JsonProperty("allow_tenant_data_fallback")
    private Boolean allowTenantDataFallback;
}
