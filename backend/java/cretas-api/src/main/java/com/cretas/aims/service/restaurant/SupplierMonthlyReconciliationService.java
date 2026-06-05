package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.restaurant.SupplierMonthlyReconciliationDto;

import java.time.YearMonth;

public interface SupplierMonthlyReconciliationService {

    SupplierMonthlyReconciliationDto createOrRefreshDraft(
            String factoryId, String supplierId, YearMonth month, Long userId);

    SupplierMonthlyReconciliationDto confirm(String factoryId, String reconciliationId, Long userId);

    SupplierMonthlyReconciliationDto getById(String factoryId, String reconciliationId);

    PageResponse<SupplierMonthlyReconciliationDto> list(
            String factoryId, String supplierId, int page, int size);
}
