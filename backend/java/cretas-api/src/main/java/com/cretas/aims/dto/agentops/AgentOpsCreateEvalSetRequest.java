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

/** Strict client contract for one immutable Eval Set version. */
@Getter
@Setter
@NoArgsConstructor
public class AgentOpsCreateEvalSetRequest {
    @NotBlank @Pattern(regexp = "1\\.0")
    private String schemaVersion;
    @NotNull
    private UUID requestId;
    @NotBlank @Size(max = 96)
    private String name;
    @Min(1) @Max(1_000_000)
    private int version;
    @Size(max = 500)
    private String description = "";
    @NotEmpty @Size(max = 100) @Valid
    private List<EvalCase> cases;

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported AgentOps eval-set field: " + field);
    }

    @Getter @Setter @NoArgsConstructor
    public static class EvalCase {
        @NotBlank @Size(max = 128)
        private String caseId;
        @NotBlank @Pattern(regexp = "GROSS_MARGIN_DECLINE_ATTRIBUTION")
        private String expectedRoute;
        @Size(max = 10)
        private List<@NotBlank @Size(max = 128) String> requiredTools;
        @Size(max = 100)
        private Map<@NotBlank @Size(max = 128) String, @NotBlank @Size(max = 96) String> numericTruthRefs;
        @Min(1) @Max(2)
        private int maxRounds = 2;
        @Min(1) @Max(10)
        private int maxToolCalls = 10;

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unsupported AgentOps eval-case field: " + field);
        }
    }
}
