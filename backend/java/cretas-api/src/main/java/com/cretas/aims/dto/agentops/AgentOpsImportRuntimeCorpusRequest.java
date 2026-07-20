package com.cretas.aims.dto.agentops;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Imports tenant-bound durable runtime truth; callers cannot supply individual cases. */
@Getter
@Setter
@NoArgsConstructor
public class AgentOpsImportRuntimeCorpusRequest {
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
    @Min(1) @Max(20)
    private int maxCases = 20;

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported runtime-corpus import field: " + field);
    }
}
