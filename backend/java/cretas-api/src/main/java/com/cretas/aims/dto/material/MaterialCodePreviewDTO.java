package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only material code contract used by both the create form and the save boundary.
 * {@code code} remains the legacy 16-digit classification code for backward compatibility;
 * {@code businessCode} is the human-readable immutable code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCodePreviewDTO {

    private String code;
    private String businessCode;
    private String businessCodePrefix;
    private String businessCodePrefixSource;
    private String businessCodePrefixSourceSegment;
    private String classificationSegmentCode;
    private Boolean selectable;
    private String guidance;
}
