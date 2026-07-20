package com.cretas.aims.dto.material;

import lombok.Builder;
import lombok.Data;

/** Explainable suggestion of an existing reusable L1/L2/L3 path. */
@Data
@Builder
public class MaterialTaxonomyCandidateDTO {
    private String l1Code;
    private String l1Label;
    private String l2Code;
    private String l2Label;
    private String l3Code;
    private String l3Label;
    private String confidence;
    private String reason;
}
