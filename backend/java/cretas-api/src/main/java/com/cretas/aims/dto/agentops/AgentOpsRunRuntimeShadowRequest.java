package com.cretas.aims.dto.agentops;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

/** Runtime shadow request; actual snapshots and identity are always server-generated. */
@Getter
@Setter
@NoArgsConstructor
public class AgentOpsRunRuntimeShadowRequest {
    @NotBlank @Pattern(regexp = "1\\.0")
    private String schemaVersion;
    @NotNull
    private UUID requestId;
    @NotNull
    private UUID evalSetId;
    @NotEmpty @Size(max = 32)
    private Map<String, Object> configSnapshot;
    @NotNull @Valid
    private RuntimeShadowBounds bounds = new RuntimeShadowBounds();

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported runtime-shadow experiment field: " + field);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RuntimeShadowBounds {
        @Min(1) @Max(20)
        private int maxCases = 20;
        @Min(1) @Max(2)
        private int maxConcurrency = 2;
        @Min(1_000) @Max(75_000)
        private int perCaseTimeoutMs = 75_000;

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unsupported runtime-shadow bounds field: " + field);
        }
    }
}
