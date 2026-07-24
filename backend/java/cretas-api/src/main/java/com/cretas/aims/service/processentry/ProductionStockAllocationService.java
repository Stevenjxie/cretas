package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;

import java.math.BigDecimal;
import java.util.List;

public interface ProductionStockAllocationService {

    List<PlannedAllocation> plan(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.MaterialInputTotal> materialInputTotals);

    /** Lock and reserve legacy caller-selected production-stock batches. */
    List<PlannedAllocation> planExplicit(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.RawInput> rawMaterialInputs);

    /** FEFO-lock and reserve BOM-derived packaging/seasoning requirements in their native stock units. */
    List<PlannedAllocation> planNative(
            String factoryId,
            String planId,
            List<AutomaticRequirement> requirements);

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
            String materialNodeId,
            String materialName,
            String sourceType,
            BigDecimal unitPrice,
            BigDecimal totalCost,
            boolean automatic) {

        public PlannedAllocation(
                String materialTypeId,
                String materialBatchId,
                String batchNumber,
                String warehouseId,
                BigDecimal quantity,
                String unit,
                Integer allocationOrder,
                String workflowPortId,
                String materialNodeId) {
            this(materialTypeId, materialBatchId, batchNumber, warehouseId, quantity, unit,
                    allocationOrder, workflowPortId, materialNodeId,
                    materialTypeId, "RAW_MATERIAL", null, null, false);
        }
    }

    record AutomaticRequirement(
            String materialTypeId,
            String materialName,
            BigDecimal quantity,
            String unit,
            String sourceType) {
    }
}
