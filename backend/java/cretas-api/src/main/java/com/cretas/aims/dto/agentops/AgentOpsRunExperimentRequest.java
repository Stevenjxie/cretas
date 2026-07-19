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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict data-only experiment request. It cannot carry tenant or actor identity. */
@Getter
@Setter
@NoArgsConstructor
public class AgentOpsRunExperimentRequest {
    @NotBlank @Pattern(regexp = "1\\.0")
    private String schemaVersion;
    @NotNull
    private UUID requestId;
    @NotNull
    private UUID evalSetId;
    @NotEmpty @Size(max = 32)
    private Map<String, Object> configSnapshot;
    @NotEmpty @Size(max = 100) @Valid
    private Map<@NotBlank @Size(max = 128) String, ActualSnapshot> actualSnapshots;
    @NotNull @Valid
    private RunnerBounds bounds = new RunnerBounds();

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported AgentOps experiment field: " + field);
    }

    @Getter @Setter @NoArgsConstructor
    public static class ActualSnapshot {
        @NotBlank @Size(max = 128)
        private String routeCode;
        @Size(max = 10)
        private List<@NotBlank @Size(max = 128) String> tools;
        @Size(max = 100)
        private Map<@NotBlank @Size(max = 128) String, @NotBlank @Size(max = 96) String> numericTruthRefs;
        @Min(0) @Max(2)
        private int roundsUsed;
        @Min(0) @Max(10)
        private int toolCallsUsed;

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unsupported AgentOps snapshot field: " + field);
        }
    }

    @Getter @Setter @NoArgsConstructor
    public static class RunnerBounds {
        @Min(1) @Max(100)
        private int maxCases = 100;
        @Min(1) @Max(4)
        private int maxConcurrency = 4;
        @Min(50) @Max(5000)
        private int perCaseTimeoutMs = 1000;

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unsupported AgentOps bounds field: " + field);
        }
    }
}
