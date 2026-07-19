package com.cretas.aims.dto.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Typed response from the Python root {@code /health} endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonServiceHealthResponse {

    private String status;

    public boolean isHealthy() {
        return "healthy".equals(status);
    }
}
