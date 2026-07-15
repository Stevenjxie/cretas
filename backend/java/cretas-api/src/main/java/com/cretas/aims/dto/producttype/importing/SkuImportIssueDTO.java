package com.cretas.aims.dto.producttype.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuImportIssueDTO {
    private String sheetName;
    private Integer rowNumber;
    private String field;
    private String code;
    private String message;
}
