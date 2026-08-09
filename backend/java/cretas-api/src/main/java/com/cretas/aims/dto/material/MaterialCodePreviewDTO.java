package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only short material-code suggestion used by the create form.
 * The optional classification ID is metadata and never participates in {@code code}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCodePreviewDTO {

    private String code;
    private Long classificationId;
    private Boolean selectable;
    private String guidance;
}
