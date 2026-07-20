package com.cretas.aims.dto.supplier;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierImportConfirmResultDTO {
    private String receiptId;
    private String idempotencyKey;
    private Integer createdCount;
    private Integer skippedCount;
    private Integer failedCount;
    private Boolean replayed;
    private List<SupplierDTO> suppliers;
}
