package com.cretas.aims.dto.producttype.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuImportConfirmResultDTO {
    private int totalRows;
    private int createdCount;
}
