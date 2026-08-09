package com.cretas.aims.dto.material;

import lombok.Builder;
import lombok.Data;

/** Explainable suggestion of an existing reusable L1/L2/L3 path. */
@Data
@Builder
public class MaterialTaxonomyCandidateDTO {
    private Long l1Id;
    private String l1Label;
    private Long l2Id;
    private String l2Label;
    private Long l3Id;
    private String l3Label;
    private String confidence;
    private String reason;
}
