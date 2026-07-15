package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;

import java.math.BigDecimal;
import java.util.List;

public interface ProductionStockAllocationService {

    List<PlannedAllocation> plan(
            String factoryId,
            List<ProcessSheetRowRequest.MaterialInputTotal> materialInputTotals);

    /** Lock and reserve legacy caller-selected production-stock batches. */
    List<PlannedAllocation> planExplicit(
            String factoryId,
            List<ProcessSheetRowRequest.RawInput> rawMaterialInputs);

    void persist(
            String factoryId,
            String planId,
            Long processSheetRowId,
            Long userId,
            List<PlannedAllocation> allocations);

    List<ProcessSheetRowRequest.RawInput> toRawInputs(List<PlannedAllocation> allocations);

    List<ProcessSheetRowResult.InputAllocation> toResult(List<PlannedAllocation> allocations);

    record PlannedAllocation(
            String materialTypeId,
            String materialBatchId,
            String batchNumber,
            String warehouseId,
            BigDecimal quantity,
            String unit,
            Integer allocationOrder,
            String workflowPortId,
            String materialNodeId) {
    }
}
