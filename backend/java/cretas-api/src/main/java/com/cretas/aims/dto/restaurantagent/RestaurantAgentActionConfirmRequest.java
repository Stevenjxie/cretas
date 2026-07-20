package com.cretas.aims.dto.restaurantagent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The bearer preview token is the only client-supplied confirmation value. */
@Getter
@Setter
@NoArgsConstructor
public class RestaurantAgentActionConfirmRequest {

    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private String previewToken;

    /** Reject client attempts to inject action codes, evidence or workflow parameters. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException(
                "Unsupported restaurant agent action confirmation field: " + fieldName);
    }
}
