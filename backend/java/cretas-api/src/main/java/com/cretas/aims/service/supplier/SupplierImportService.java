package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierImportConfirmRequest;
import com.cretas.aims.dto.supplier.SupplierImportConfirmResultDTO;
import com.cretas.aims.dto.supplier.SupplierImportPreviewDTO;

import java.util.List;
import java.util.Map;

public interface SupplierImportService {
    byte[] generateTemplate();
    SupplierImportPreviewDTO preview(String factoryId, byte[] fileBytes, String mode,
                                     Map<String, String> columnMapping);
    SupplierImportConfirmResultDTO confirm(String factoryId, SupplierImportConfirmRequest request, Long userId);
    byte[] generateErrorReport(List<SupplierImportPreviewDTO.Row> rows);
}
