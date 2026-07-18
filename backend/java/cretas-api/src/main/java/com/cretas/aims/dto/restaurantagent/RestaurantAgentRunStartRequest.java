package com.cretas.aims.dto.restaurantagent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Strict v1 request for the single bounded restaurant route. */
@Getter
@Setter
@NoArgsConstructor
public class RestaurantAgentRunStartRequest {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String ROUTE_CODE = "GROSS_MARGIN_DECLINE_ATTRIBUTION";

    @NotBlank
    @Pattern(regexp = "1\\.0")
    private String schemaVersion;

    @NotBlank
    @Pattern(regexp = "GROSS_MARGIN_DECLINE_ATTRIBUTION")
    private String routeCode;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @NotNull
    @Min(1)
    @Max(50)
    private Integer storeTopN = 20;

    @NotNull
    @Min(1)
    @Max(20)
    private Integer dishTopN = 10;

    @AssertTrue(message = "startDate must not be after endDate")
    @JsonIgnore
    public boolean isDateWindowValid() {
        return startDate == null || endDate == null || !startDate.isAfter(endDate);
    }

    /**
     * Global Jackson configuration tolerates unknown fields for legacy APIs.
     * This security boundary must remain strict, so reject every extra field
     * locally (including body-supplied tenant or actor identity).
     */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("Unsupported restaurant agent field: " + fieldName);
    }
}
