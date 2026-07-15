package com.cretas.aims.dto.producttype.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuImportPreviewRowDTO {
    private String sheetName;
    private Integer rowNumber;
    private String skuCategory;
    private String skuCode;
    private String name;
    private String unit;
    private String specification;
    private String imageUrl;
    private String imageFileName;
    private String matchedImageName;
    private String status;
    private List<SkuImportIssueDTO> errors;
}
