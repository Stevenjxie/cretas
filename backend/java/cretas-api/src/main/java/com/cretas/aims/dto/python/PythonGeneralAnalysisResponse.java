package com.cretas.aims.dto.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Typed response from Python {@code /api/chat/general-analysis}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonGeneralAnalysisResponse {

    private boolean success;
    private String error;
    private String answer;
    private String aiAnalysis;
    private String reasoningContent;
    private Boolean thinkingEnabled;
    private String sessionId;
    private Integer messageCount;
    private Integer tokensUsed;
    private List<Map<String, Object>> insights;
    private List<Map<String, Object>> charts;

    @JsonProperty("processing_time_ms")
    private Integer processingTimeMs;

    public boolean hasAnalysis() {
        return !isBlank(answer) || !isBlank(aiAnalysis);
    }

    public String getEffectiveAnalysis() {
        return !isBlank(aiAnalysis) ? aiAnalysis : answer;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
