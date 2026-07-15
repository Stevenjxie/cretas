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
public class SkuImportPreviewDTO {
    private String previewToken;
    private String fileSha256;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private List<SkuImportPreviewRowDTO> rows;
    private List<SkuImportIssueDTO> errors;
}
