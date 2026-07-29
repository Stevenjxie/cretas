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
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionStockAllocationServiceImpl implements ProductionStockAllocationService {

    private static final String KG = "kg";

    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionInputAllocationRepository allocationRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final WarehouseResolver warehouseResolver;
    private final UnitContractService unitContractService;

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
            validateInput(factoryId, input);
            String materialTypeId = input.getMaterialTypeId().trim();
            // 质量单位自动换算成 kg; 其他量纲(只/件/袋…)按 Workflow 端口声明的单位原样记账。
            // 强行把计数单位折成 kg 需要一个"每只多少公斤"的口径, 那是配置里没有的东西。
            String inputUnit = canonicalNativeUnit(factoryId, normalizeUnit(input.getUnit()));
            boolean massInput = isCanonicalMassUnit(inputUnit);
            String allocationUnit = massInput ? KG : inputUnit;
            BigDecimal required = massInput
                    ? reportingQuantityToKg(input.getQuantity(), input.getUnit())
                    : input.getQuantity();
            List<MaterialBatch> batches = batchesByMaterial.computeIfAbsent(
                    materialTypeId,
                    key -> findEligibleBatchesForUpdate(factoryId, plan, key, workshopId));

            BigDecimal remaining = required;
            BigDecimal availableForInput = BigDecimal.ZERO;
            for (MaterialBatch batch : batches) {
                ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                        plan, batch, "生产报工投料");
                // 非质量单位不做跨单位折算, 只吃单位一致的批次 —— 与 BOM 自动投料同一口径
                String batchUnit = canonicalNativeUnit(factoryId, batch.getQuantityUnit());
                if (massInput ? !isCanonicalMassUnit(batchUnit)
                        : !Objects.equals(inputUnit, batchUnit)) {
                    continue;
                }
                BigDecimal available = availableByBatch.computeIfAbsent(batch.getId(), ignored -> {
                    BigDecimal pending = nz(allocationRepository
                            .sumPendingQuantityByMaterialBatchId(factoryId, batch.getId()));
                    BigDecimal stock = massInput
                            ? storageQuantityToKg(
                                    nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber())
                            : nz(batch.getCurrentQuantity());
                    return stock.subtract(pending).max(BigDecimal.ZERO);
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
                            allocationUnit,
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
                        null,
                        "RAW_MATERIAL",
                        required,
                        availableForInput,
                        remaining,
                        allocationUnit));
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
                    required, available, shortage,
                    aggregateShortageUnit(shortageItems), List.copyOf(shortageItems)));
        }

        return List.copyOf(allocations);
    }

    /**
     * 汇总口径的单位。多个物料单位不一致时(比如一道工序同时投「只」和 kg), 数值相加本就
     * 没有物理意义, 汇总单位留空让前端只展示明细行, 而不是随便标一个 kg 让人误读。
     */
    private String aggregateShortageUnit(List<ProductionStockShortageDTO.Item> items) {
        Set<String> units = items.stream()
                .map(ProductionStockShortageDTO.Item::getUnit)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return units.size() == 1 ? units.iterator().next() : null;
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
            // 与自动分摊同一口径: 质量单位折算成 kg, 其余量纲按批次自身单位记账。
            // 手选批次时数量就是照着这个批次填的, 所以投料单位以批次库存单位为准;
            // 声明了单位就必须一致 —— 拿「只」的数量去扣一个 kg 批次是无声的错账。
            String batchUnit = canonicalNativeUnit(factoryId, batch.getQuantityUnit());
            if (batchUnit == null) {
                throw new BusinessException(409, "投料批次 " + batch.getBatchNumber() + " 缺少库存单位")
                        .withCode("PRODUCTION_INPUT_BATCH_UNIT_REQUIRED")
                        .withSeverity("BLOCKING");
            }
            String declaredUnit = canonicalNativeUnit(factoryId, metadata.getUnit());
            if (declaredUnit != null && !Objects.equals(declaredUnit, batchUnit)) {
                throw new BusinessException(409, "投料单位与批次库存单位不一致: 报工按 "
                        + metadata.getUnit() + ", 批次 " + batch.getBatchNumber()
                        + " 存的是 " + batch.getQuantityUnit())
                        .withCode("PRODUCTION_INPUT_BATCH_UNIT_MISMATCH")
                        .withHint("请改选同单位的批次, 或先在工序里把该物料的报工单位对齐库存单位")
                        .withSeverity("BLOCKING");
            }
            boolean massBatch = isCanonicalMassUnit(batchUnit);
            String allocationUnit = massBatch ? KG : batchUnit;
            if (metadata.getSkuId() != null && !metadata.getSkuId().isBlank()
                    && !Objects.equals(metadata.getSkuId(), batch.getMaterialTypeId())) {
                throw new BusinessException(409, "投料批次与物料不匹配")
                        .withCode("PRODUCTION_INPUT_BATCH_MATERIAL_MISMATCH")
                        .withSeverity("BLOCKING");
            }
            BigDecimal pending = nz(allocationRepository
                    .sumPendingQuantityByMaterialBatchId(factoryId, batchId));
            BigDecimal stock = massBatch
                    ? storageQuantityToKg(
                            nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber())
                    : nz(batch.getCurrentQuantity());
            BigDecimal available = stock.subtract(pending).max(BigDecimal.ZERO);
            if (batch.getStatus() != com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE
                    || available.compareTo(required) < 0) {
                shortageItems.add(new ProductionStockShortageDTO.Item(
                        batch.getMaterialTypeId(), null,
                        metadata.getSourceType() == null ? "RAW_MATERIAL" : metadata.getSourceType(),
                        required, available,
                        required.subtract(available).max(BigDecimal.ZERO), allocationUnit));
                continue;
            }
            allocations.add(new PlannedAllocation(
                    batch.getMaterialTypeId(), batchId, batch.getBatchNumber(), workshopId,
                    required, allocationUnit, allocationOrder++, metadata.getWorkflowPortId(),
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
                    required, available, shortage,
                    aggregateShortageUnit(shortageItems), List.copyOf(shortageItems)));
        }
        return List.copyOf(allocations);
    }

    @Override
    public List<PlannedAllocation> planNative(
            String factoryId,
            String planId,
            List<AutomaticRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        ProductionPlan plan = requirePlan(factoryId, planId);
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        if (workshopId == null || workshopId.isBlank()) {
            throw new BusinessException(500, "未配置生产库，不能自动分摊包材/调料批次")
                    .withCode("WORKSHOP_WAREHOUSE_NOT_CONFIGURED")
                    .withSeverity("BLOCKING");
        }

        Map<String, BigDecimal> availableByBatch = new HashMap<>();
        List<PlannedAllocation> allocations = new ArrayList<>();
        List<ProductionStockShortageDTO.Item> shortages = new ArrayList<>();
        int allocationOrder = 10_000;
        for (AutomaticRequirement requirement : requirements) {
            if (requirement == null || requirement.materialTypeId() == null
                    || requirement.materialTypeId().isBlank()
                    || requirement.quantity() == null || requirement.quantity().signum() <= 0) {
                throw new BusinessException(409, "BOM 自动投料需求不完整")
                        .withCode("AUTOMATIC_MATERIAL_REQUIREMENT_INVALID")
                        .withSeverity("BLOCKING");
            }
            String requiredUnit = canonicalNativeUnit(factoryId, requirement.unit());
            if (requiredUnit == null) {
                throw new BusinessException(409, "BOM 自动投料缺少计量单位: " + requirement.materialName())
                        .withCode("AUTOMATIC_MATERIAL_UNIT_REQUIRED")
                        .withSeverity("BLOCKING");
            }
            boolean massRequirement = isCanonicalMassUnit(requiredUnit);
            String allocationUnit = massRequirement ? KG : requiredUnit;
            BigDecimal requiredQuantity = massRequirement
                    ? toKg(requirement.quantity(), requiredUnit)
                    : requirement.quantity();
            BigDecimal remaining = requiredQuantity;
            BigDecimal availableForItem = BigDecimal.ZERO;
            List<MaterialBatch> batches = findEligibleBatchesForUpdate(
                    factoryId, plan, requirement.materialTypeId(), workshopId);
            for (MaterialBatch batch : batches) {
                ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(plan, batch, "生产报工自动投料");
                String batchUnit = canonicalNativeUnit(factoryId, batch.getQuantityUnit());
                if (massRequirement ? !isCanonicalMassUnit(batchUnit)
                        : !Objects.equals(requiredUnit, batchUnit)) {
                    continue;
                }
                BigDecimal available = availableByBatch.computeIfAbsent(batch.getId(), ignored -> {
                    BigDecimal pending = nz(allocationRepository
                            .sumPendingQuantityByMaterialBatchId(factoryId, batch.getId()));
                    BigDecimal stock = massRequirement
                            ? storageQuantityToKg(
                                    nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber())
                            : nz(batch.getCurrentQuantity());
                    return stock.subtract(pending).max(BigDecimal.ZERO);
                });
                if (available.signum() <= 0) {
                    continue;
                }
                BigDecimal take = available.min(remaining);
                if (take.signum() > 0) {
                    BigDecimal costQuantity = massRequirement
                            ? kgToStorageQuantity(take, batchUnit)
                            : take;
                    BigDecimal totalCost = batch.getUnitPrice() == null
                            ? null : batch.getUnitPrice().multiply(costQuantity);
                    BigDecimal allocationUnitPrice = batch.getUnitPrice() == null
                            ? null : (massRequirement
                                    ? batch.getUnitPrice().multiply(
                                            "g".equals(batchUnit) ? new BigDecimal("1000") : BigDecimal.ONE)
                                    : batch.getUnitPrice());
                    allocations.add(new PlannedAllocation(
                            requirement.materialTypeId(),
                            batch.getId(),
                            batch.getBatchNumber(),
                            workshopId,
                            take,
                            allocationUnit,
                            allocationOrder++,
                            null,
                            null,
                            requirement.materialName(),
                            requirement.sourceType(),
                            allocationUnitPrice,
                            totalCost,
                            true));
                    availableByBatch.put(batch.getId(), available.subtract(take));
                    availableForItem = availableForItem.add(take);
                    remaining = remaining.subtract(take);
                }
                if (remaining.signum() == 0) {
                    break;
                }
            }
            if (remaining.signum() > 0) {
                shortages.add(new ProductionStockShortageDTO.Item(
                        requirement.materialTypeId(),
                        requirement.materialName(),
                        requirement.sourceType(),
                        requiredQuantity,
                        availableForItem,
                        remaining,
                        allocationUnit));
            }
        }
        if (!shortages.isEmpty()) {
            Set<String> shortageUnits = shortages.stream()
                    .map(ProductionStockShortageDTO.Item::getUnit)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (shortageUnits.size() == 1) {
                BigDecimal required = shortages.stream()
                        .map(ProductionStockShortageDTO.Item::getRequired)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal available = shortages.stream()
                        .map(ProductionStockShortageDTO.Item::getAvailable)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal shortage = shortages.stream()
                        .map(ProductionStockShortageDTO.Item::getShortage)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                throw new ProductionStockShortageException(new ProductionStockShortageDTO(
                        required, available, shortage,
                        shortageUnits.iterator().next(), List.copyOf(shortages)));
            }
            throw new ProductionStockShortageException(new ProductionStockShortageDTO(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "mixed", List.copyOf(shortages)));
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
                            left.materialNodeId(),
                            left.materialName(),
                            left.sourceType(),
                            left.unitPrice(),
                            left.totalCost() == null || right.totalCost() == null
                                    ? null : left.totalCost().add(right.totalCost()),
                            left.automatic() || right.automatic()));
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
            input.setUnit(allocation.unit());
            input.setSourceType(allocation.sourceType());
            input.setAutomatic(allocation.automatic());
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
            result.setMaterialName(allocation.materialName());
            result.setSourceType(allocation.sourceType());
            result.setUnitPrice(allocation.unitPrice());
            result.setTotalCost(allocation.totalCost());
            result.setAutomatic(allocation.automatic());
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

    private void validateInput(String factoryId, ProcessSheetRowRequest.MaterialInputTotal input) {
        if (input == null || input.getMaterialTypeId() == null || input.getMaterialTypeId().isBlank()) {
            throw new BusinessException(400, "投料物料不能为空")
                    .withCode("PRODUCTION_INPUT_MATERIAL_REQUIRED");
        }
        if (input.getQuantity() == null || input.getQuantity().signum() <= 0) {
            throw new BusinessException(400, "投料量必须大于 0")
                    .withCode("PRODUCTION_INPUT_QUANTITY_INVALID")
                    .withHintTarget("投料量");
        }
        if (canonicalNativeUnit(factoryId, normalizeUnit(input.getUnit())) == null) {
            throw new BusinessException(400, "生产投料总量缺少计量单位")
                    .withCode("PRODUCTION_INPUT_UNIT_REQUIRED")
                    .withHint("单位由 Workflow 投入端口固定，请先在工序里配置该物料的报工单位")
                    .withHintTarget("投料单位");
        }
    }

    private String normalizeUnit(String unit) {
        return unit == null || unit.isBlank() ? KG : unit.trim();
    }

    /**
     * 单位归一 —— 以全局单位契约为准。
     *
     * 这里原本是一份本地 switch, 和 {@code UnitContractService} 各归各的:
     * 契约把 只/个/件/pcs 全归到 {@code pcs}, 本地 switch 却把 pcs/个/片 归到
     * {@code slice}、把未登记的「只」原样返回。于是同一个物料的投料单位算成 slice、
     * 库存批次存着「只」, 两个字符串不等 —— 明明有 201 只库存却报 "需要 1slice,
     * 可用 0slice"。同义单位必须只有一套 code, 否则匹配逻辑再对也没用。
     *
     * 契约认不出来的单位保持原样(小写去空格): 未登记不等于非法, 至少让同样写法的
     * 投料与批次还能对上, 而不是直接判定为 null 把报工整个挡死。
     */
    private String canonicalNativeUnit(String factoryId, String unit) {
        if (unit == null || unit.isBlank()) return null;
        String code = unitContractService.normalize(factoryId, unit).code();
        return code != null ? code : unit.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isCanonicalMassUnit(String unit) {
        return "kg".equals(unit) || "g".equals(unit);
    }

    private BigDecimal toKg(BigDecimal quantity, String canonicalUnit) {
        return "g".equals(canonicalUnit) ? quantity.movePointLeft(3) : quantity;
    }

    private BigDecimal kgToStorageQuantity(BigDecimal quantityKg, String canonicalStorageUnit) {
        return "g".equals(canonicalStorageUnit) ? quantityKg.movePointRight(3) : quantityKg;
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
