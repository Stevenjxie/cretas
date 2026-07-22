package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionInputAllocation;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.processentry.ProductionStockAllocationService;
import com.cretas.aims.service.processentry.ProductionStockShortageException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class ProductionStockAllocationServiceImpl implements ProductionStockAllocationService {

    private static final String KG = "kg";

    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionInputAllocationRepository allocationRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final WarehouseResolver warehouseResolver;

    @Override
    public List<PlannedAllocation> plan(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.MaterialInputTotal> materialInputTotals) {
        if (materialInputTotals == null || materialInputTotals.isEmpty()) {
            return List.of();
        }

        ProductionPlan plan = requirePlan(factoryId, planId);

        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        if (workshopId == null || workshopId.isBlank()) {
            throw new BusinessException(500, "未配置生产库，不能自动分摊投料批次")
                    .withCode("WORKSHOP_WAREHOUSE_NOT_CONFIGURED")
                    .withHint("请先配置生产库后再正式提交")
                    .withSeverity("BLOCKING");
        }

        Map<String, List<MaterialBatch>> batchesByMaterial = new HashMap<>();
        Map<String, BigDecimal> availableByBatch = new HashMap<>();
        List<PlannedAllocation> allocations = new ArrayList<>();
        List<ProductionStockShortageDTO.Item> shortageItems = new ArrayList<>();
        int allocationOrder = 0;

        for (ProcessSheetRowRequest.MaterialInputTotal input : materialInputTotals) {
            validateInput(input);
            String materialTypeId = input.getMaterialTypeId().trim();
            BigDecimal required = reportingQuantityToKg(input.getQuantity(), input.getUnit());
            List<MaterialBatch> batches = batchesByMaterial.computeIfAbsent(
                    materialTypeId,
                    key -> findEligibleBatchesForUpdate(factoryId, plan, key, workshopId));

            BigDecimal remaining = required;
            BigDecimal availableForInput = BigDecimal.ZERO;
            for (MaterialBatch batch : batches) {
                ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                        plan, batch, "生产报工投料");
                BigDecimal available = availableByBatch.computeIfAbsent(batch.getId(), ignored -> {
                    BigDecimal pending = nz(allocationRepository
                            .sumPendingQuantityByMaterialBatchId(factoryId, batch.getId()));
                    BigDecimal stockKg = storageQuantityToKg(
                            nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber());
                    return stockKg.subtract(pending).max(BigDecimal.ZERO);
                });
                if (available.signum() <= 0) {
                    continue;
                }
                BigDecimal take = available.min(remaining);
                availableForInput = availableForInput.add(take);
                if (take.signum() > 0) {
                    allocations.add(new PlannedAllocation(
                            materialTypeId,
                            batch.getId(),
                            batch.getBatchNumber(),
                            workshopId,
                            take,
                            KG,
                            allocationOrder++,
                            input.getWorkflowPortId(),
                            input.getMaterialNodeId()));
                    availableByBatch.put(batch.getId(), available.subtract(take));
                    remaining = remaining.subtract(take);
                }
                if (remaining.signum() == 0) {
                    break;
                }
            }
            if (remaining.signum() > 0) {
                shortageItems.add(new ProductionStockShortageDTO.Item(
                        materialTypeId,
                        required,
                        availableForInput,
                        remaining,
                        KG));
            }
        }

        if (!shortageItems.isEmpty()) {
            BigDecimal required = shortageItems.stream()
                    .map(ProductionStockShortageDTO.Item::getRequired)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal available = shortageItems.stream()
                    .map(ProductionStockShortageDTO.Item::getAvailable)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortage = shortageItems.stream()
                    .map(ProductionStockShortageDTO.Item::getShortage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            throw new ProductionStockShortageException(new ProductionStockShortageDTO(
                    required, available, shortage, KG, List.copyOf(shortageItems)));
        }

        return List.copyOf(allocations);
    }

    @Override
    public List<PlannedAllocation> planExplicit(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.RawInput> rawMaterialInputs) {
        if (rawMaterialInputs == null || rawMaterialInputs.isEmpty()) {
            return List.of();
        }
        ProductionPlan plan = requirePlan(factoryId, planId);
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        if (workshopId == null || workshopId.isBlank()) {
            throw new BusinessException(500, "未配置生产库，不能锁定投料批次")
                    .withCode("WORKSHOP_WAREHOUSE_NOT_CONFIGURED")
                    .withSeverity("BLOCKING");
        }

        Map<String, BigDecimal> requiredByBatch = new LinkedHashMap<>();
        Map<String, ProcessSheetRowRequest.RawInput> metadataByBatch = new LinkedHashMap<>();
        for (ProcessSheetRowRequest.RawInput input : rawMaterialInputs) {
            if (input == null || input.getMaterialBatchId() == null || input.getMaterialBatchId().isBlank()) {
                throw new BusinessException(400, "投料批次不能为空")
                        .withCode("PRODUCTION_INPUT_BATCH_REQUIRED");
            }
            if (input.getQuantity() == null || input.getQuantity().signum() <= 0) {
                throw new BusinessException(400, "投料量必须大于 0")
                        .withCode("PRODUCTION_INPUT_QUANTITY_INVALID");
            }
            String batchId = input.getMaterialBatchId().trim();
            requiredByBatch.merge(batchId, input.getQuantity(), BigDecimal::add);
            metadataByBatch.putIfAbsent(batchId, input);
        }

        // Stable lock order prevents two legacy submissions with reversed batch
        // order from deadlocking each other.
        Map<String, MaterialBatch> lockedByBatch = new HashMap<>();
        for (String batchId : new TreeSet<>(requiredByBatch.keySet())) {
            MaterialBatch batch = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> new BusinessException(409, "投料批次不存在或不属于当前工厂")
                            .withCode("PRODUCTION_INPUT_BATCH_NOT_FOUND")
                            .withSeverity("BLOCKING"));
            ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                    plan, batch, "生产报工投料");
            lockedByBatch.put(batchId, batch);
        }

        List<PlannedAllocation> allocations = new ArrayList<>();
        List<ProductionStockShortageDTO.Item> shortageItems = new ArrayList<>();
        int allocationOrder = 0;
        for (Map.Entry<String, BigDecimal> entry : requiredByBatch.entrySet()) {
            String batchId = entry.getKey();
            BigDecimal required = entry.getValue();
            MaterialBatch batch = lockedByBatch.get(batchId);
            ProcessSheetRowRequest.RawInput metadata = metadataByBatch.get(batchId);
            if (!Objects.equals(workshopId, batch.getWarehouseId())
                    || "PRODUCTION_BATCH".equals(batch.getSourceDocType())) {
                throw new BusinessException(409, "所选批次不在可投料的生产库中")
                        .withCode("PRODUCTION_INPUT_BATCH_NOT_IN_WORKSHOP")
                        .withSeverity("BLOCKING");
            }
            if (!isMassStorageUnit(batch.getQuantityUnit())) {
                throw new BusinessException(409, "所选投料批次不是 kg 计量，不能直接报工")
                        .withCode("PRODUCTION_INPUT_BATCH_UNIT_INVALID")
                        .withSeverity("BLOCKING");
            }
            if (metadata.getSkuId() != null && !metadata.getSkuId().isBlank()
                    && !Objects.equals(metadata.getSkuId(), batch.getMaterialTypeId())) {
                throw new BusinessException(409, "投料批次与物料不匹配")
                        .withCode("PRODUCTION_INPUT_BATCH_MATERIAL_MISMATCH")
                        .withSeverity("BLOCKING");
            }
            BigDecimal pending = nz(allocationRepository
                    .sumPendingQuantityByMaterialBatchId(factoryId, batchId));
            BigDecimal available = storageQuantityToKg(
                    nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber())
                    .subtract(pending).max(BigDecimal.ZERO);
            if (batch.getStatus() != com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE
                    || available.compareTo(required) < 0) {
                shortageItems.add(new ProductionStockShortageDTO.Item(
                        batch.getMaterialTypeId(), required, available,
                        required.subtract(available).max(BigDecimal.ZERO), KG));
                continue;
            }
            allocations.add(new PlannedAllocation(
                    batch.getMaterialTypeId(), batchId, batch.getBatchNumber(), workshopId,
                    required, KG, allocationOrder++, metadata.getWorkflowPortId(),
                    metadata.getMaterialNodeId()));
        }
        if (!shortageItems.isEmpty()) {
            BigDecimal required = shortageItems.stream().map(ProductionStockShortageDTO.Item::getRequired)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal available = shortageItems.stream().map(ProductionStockShortageDTO.Item::getAvailable)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortage = shortageItems.stream().map(ProductionStockShortageDTO.Item::getShortage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            throw new ProductionStockShortageException(new ProductionStockShortageDTO(
                    required, available, shortage, KG, List.copyOf(shortageItems)));
        }
        return List.copyOf(allocations);
    }

    @Override
    public void persist(
            String factoryId,
            String planId,
            Long processSheetRowId,
            Long userId,
            List<PlannedAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return;
        }
        Map<String, PlannedAllocation> mergedByBatch = new LinkedHashMap<>();
        for (PlannedAllocation allocation : allocations) {
            mergedByBatch.merge(allocation.materialBatchId(), allocation, (left, right) ->
                    new PlannedAllocation(
                            left.materialTypeId(),
                            left.materialBatchId(),
                            left.batchNumber(),
                            left.warehouseId(),
                            left.quantity().add(right.quantity()),
                            left.unit(),
                            Math.min(left.allocationOrder(), right.allocationOrder()),
                            left.workflowPortId(),
                            left.materialNodeId()));
        }
        List<ProductionInputAllocation> entities = mergedByBatch.values().stream()
                .map(allocation -> toEntity(
                        factoryId, planId, processSheetRowId, userId, allocation))
                .toList();
        allocationRepository.saveAll(entities);
    }

    @Override
    public List<ProcessSheetRowRequest.RawInput> toRawInputs(List<PlannedAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return List.of();
        }
        return allocations.stream().map(allocation -> {
            ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
            input.setMaterialBatchId(allocation.materialBatchId());
            input.setQuantity(allocation.quantity());
            input.setSkuId(allocation.materialTypeId());
            input.setWorkflowPortId(allocation.workflowPortId());
            input.setMaterialNodeId(allocation.materialNodeId());
            return input;
        }).toList();
    }

    @Override
    public List<ProcessSheetRowResult.InputAllocation> toResult(List<PlannedAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return List.of();
        }
        return allocations.stream().map(allocation -> {
            ProcessSheetRowResult.InputAllocation result = new ProcessSheetRowResult.InputAllocation();
            result.setMaterialTypeId(allocation.materialTypeId());
            result.setMaterialBatchId(allocation.materialBatchId());
            result.setBatchNumber(allocation.batchNumber());
            result.setQuantity(allocation.quantity());
            result.setUnit(allocation.unit());
            result.setAllocationOrder(allocation.allocationOrder());
            return result;
        }).toList();
    }

    private ProductionInputAllocation toEntity(
            String factoryId,
            String planId,
            Long processSheetRowId,
            Long userId,
            PlannedAllocation allocation) {
        ProductionInputAllocation entity = new ProductionInputAllocation();
        entity.setFactoryId(factoryId);
        entity.setProductionPlanId(planId);
        entity.setProcessSheetRowId(processSheetRowId);
        entity.setMaterialTypeId(allocation.materialTypeId());
        entity.setMaterialBatchId(allocation.materialBatchId());
        entity.setWarehouseId(allocation.warehouseId());
        entity.setQuantity(allocation.quantity());
        entity.setUnit(allocation.unit());
        entity.setAllocationOrder(allocation.allocationOrder());
        entity.setStatus("ALLOCATED");
        entity.setCreatedBy(userId);
        return entity;
    }

    private ProductionPlan requirePlan(String factoryId, String planId) {
        if (planId == null || planId.isBlank()) {
            throw new BusinessException(409, "生产报工缺少生产计划归属")
                    .withCode("PRODUCTION_PLAN_OWNERSHIP_CONTEXT_REQUIRED")
                    .withSeverity("BLOCKING");
        }
        return productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(409, "生产计划不存在或不属于当前工厂")
                        .withCode("PRODUCTION_PLAN_NOT_FOUND")
                        .withSeverity("BLOCKING"));
    }

    private List<MaterialBatch> findEligibleBatchesForUpdate(String factoryId,
                                                              ProductionPlan plan,
                                                              String materialTypeId,
                                                              String warehouseId) {
        if (plan.getMaterialSupplyMode() != MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            return materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                    factoryId, materialTypeId, warehouseId);
        }

        ProductionInventoryOwnershipGuard.requireCustomerSuppliedPlanLineage(
                plan, "生产报工投料");
        return materialBatchRepository
                .findAvailableCustomerSuppliedBatchesFEFOByWarehouseForUpdate(
                        factoryId,
                        materialTypeId,
                        warehouseId,
                        plan.getCustomerId(),
                        plan.getSourceOrderId())
                .stream()
                .filter(batch -> isCompatibleSalesOrderItem(plan, batch))
                .toList();
    }

    private boolean isCompatibleSalesOrderItem(ProductionPlan plan, MaterialBatch batch) {
        String planItemId = plan.getSourceOrderItemId();
        String batchItemId = batch.getSourceSalesOrderItemId();
        return planItemId == null || planItemId.isBlank()
                || batchItemId == null || batchItemId.isBlank()
                || Objects.equals(planItemId, batchItemId);
    }

    private void validateInput(ProcessSheetRowRequest.MaterialInputTotal input) {
        if (input == null || input.getMaterialTypeId() == null || input.getMaterialTypeId().isBlank()) {
            throw new BusinessException(400, "投料物料不能为空")
                    .withCode("PRODUCTION_INPUT_MATERIAL_REQUIRED");
        }
        if (input.getQuantity() == null || input.getQuantity().signum() <= 0) {
            throw new BusinessException(400, "投料量必须大于 0")
                    .withCode("PRODUCTION_INPUT_QUANTITY_INVALID")
                    .withHintTarget("投料量");
        }
        if (!isMassStorageUnit(normalizeUnit(input.getUnit()))) {
            throw new BusinessException(400, "生产投料总量单位必须为可换算的质量单位")
                    .withCode("PRODUCTION_INPUT_UNIT_INVALID")
                    .withHint("当前支持 g/kg（含克、千克、公斤），单位由 Workflow 投入端口固定")
                    .withHintTarget("投料单位");
        }
    }

    private String normalizeUnit(String unit) {
        return unit == null || unit.isBlank() ? KG : unit.trim();
    }

    private BigDecimal reportingQuantityToKg(BigDecimal quantity, String reportingUnit) {
        String normalized = normalizeUnit(reportingUnit).toLowerCase(java.util.Locale.ROOT);
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return quantity.movePointLeft(3);
        }
        if ("kg".equals(normalized) || "千克".equals(normalized) || "公斤".equals(normalized)) {
            return quantity;
        }
        throw new BusinessException(400, "投料单位“" + reportingUnit + "”不能换算为 kg")
                .withCode("PRODUCTION_INPUT_UNIT_INVALID")
                .withHint("当前仅支持 g/kg 质量换算")
                .withSeverity("BLOCKING");
    }

    private boolean isMassStorageUnit(String unit) {
        if (unit == null || unit.isBlank()) return false;
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT);
        return "kg".equals(normalized) || "g".equals(normalized)
                || "千克".equals(normalized) || "公斤".equals(normalized) || "克".equals(normalized);
    }

    /**
     * Allocations and pending reservations use reporting kg even when an older inventory batch
     * is stored in g. The process-sheet edge resolver converts the eventual deduction back to
     * the batch storage unit.
     */
    private BigDecimal storageQuantityToKg(BigDecimal quantity, String storageUnit, String batchNumber) {
        if (!isMassStorageUnit(storageUnit)) {
            throw invalidBatchUnit(batchNumber, storageUnit);
        }
        String normalized = storageUnit.trim().toLowerCase(java.util.Locale.ROOT);
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return quantity.movePointLeft(3);
        }
        return quantity;
    }

    private BusinessException invalidBatchUnit(String batchNumber, String unit) {
        return new BusinessException(409,
                "生产库批次 " + batchNumber + " 的库存单位“" + unit + "”不能换算为 kg")
                .withCode("PRODUCTION_INPUT_BATCH_UNIT_INVALID")
                .withHint("仅支持 g 与 kg 的质量换算；请先补全或修正库存批次单位")
                .withSeverity("BLOCKING");
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
