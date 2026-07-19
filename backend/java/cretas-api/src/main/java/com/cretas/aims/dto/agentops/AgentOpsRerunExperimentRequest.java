package com.cretas.aims.dto.agentops;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Strict idempotent rerun request. Tenant and actor identity stay in trusted headers. */
@Getter
@Setter
@NoArgsConstructor
public class AgentOpsRerunExperimentRequest {
    @NotBlank @Pattern(regexp = "1\\.0")
    private String schemaVersion;
    @NotNull
    private UUID requestId;

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported AgentOps rerun field: " + field);
    }
}
