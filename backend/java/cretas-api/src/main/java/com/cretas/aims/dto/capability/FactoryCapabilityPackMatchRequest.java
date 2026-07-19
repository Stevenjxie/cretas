package com.cretas.aims.dto.capability;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Query-only body. Tenant, user and role identity are never accepted from JSON. */
public final class FactoryCapabilityPackMatchRequest {
    @NotBlank
    @Size(max = 256)
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("unsupported capability match field: " + field);
    }
}
