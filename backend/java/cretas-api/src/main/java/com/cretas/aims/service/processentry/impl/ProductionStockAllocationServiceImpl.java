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
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.processentry.ProductionStockAllocationService;
import com.cretas.aims.service.processentry.ProductionStockShortageException;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@lombok.extern.slf4j.Slf4j
public class ProductionStockAllocationServiceImpl implements ProductionStockAllocationService {

    private static final String KG = "kg";

    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionInputAllocationRepository allocationRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final WarehouseResolver warehouseResolver;
    private final UnitContractService unitContractService;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

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
            // 非科学单位不归一, 所以匹配值就是用户写的那个字 —— 展示与比较天然一致
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
                // 非质量单位不做跨单位折算, 只吃单位一致的批次 —— 与 BOM 自动投料同一口径。
                // 🔴 2026-08-03: 这里原来是 unitMatches/batchAvailable 的<b>内联复制</b>
                // (那两个 helper 的注释还写着「与 plan() 逐字同一条件」)。改成直接复用,
                // 一条规则一个承载点。⛔ 用的是<b>严格版</b>: 按箱/袋存量的批次<b>不进可投量</b>,
                // 因为扣减侧 kgToStorageQuantity 只会 g↔kg, 对「箱」是原样返回 ——
                // 让 100kg 的分配去扣一个只有 10 箱的批次会<b>超扣 10 倍</b>。
                // 展示侧(过期提醒/原料仓另有)用 unitMatchesForDisplay, 见那两处。
                if (!unitMatches(factoryId, batch, inputUnit, massInput)) {
                    continue;
                }
                BigDecimal available = availableByBatch.computeIfAbsent(
                        batch.getId(), ignored -> batchAvailable(factoryId, batch, massInput));
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
                        materialName(factoryId, materialTypeId),
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
                    .withHint("请先配置生产库后再正式提交")
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
            // 两个成因此前合并成一句「不在可投料的生产库中」且**不带 actionHint** ——
            // 报工现场只知道被拦, 不知道下一步该干什么。分开说, 各给动作。
            if ("PRODUCTION_BATCH".equals(batch.getSourceDocType())) {
                throw new BusinessException(409,
                        "批次 " + batch.getBatchNumber() + " 是生产产出批次，不能再作为投料")
                        .withCode("PRODUCTION_INPUT_BATCH_IS_OUTPUT")
                        .withHint("请改选采购入库或领料进入生产仓的原料批次；"
                                + "产出批次若要再加工，需先建下一道工序的生产计划")
                        .withHintTarget("rawMaterialInputs")
                        .withSeverity("BLOCKING");
            }
            if (!Objects.equals(workshopId, batch.getWarehouseId())) {
                throw new BusinessException(409,
                        "批次 " + batch.getBatchNumber() + " 还在原料仓，尚未领到生产仓，不能投料")
                        .withCode("PRODUCTION_INPUT_BATCH_NOT_IN_WORKSHOP")
                        .withHint("请先前往「生产管理 → 领料」把该批次从原料仓领到生产仓，再来报工")
                        .withHintTarget("rawMaterialInputs")
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
                        batch.getMaterialTypeId(),
                        materialName(factoryId, batch.getMaterialTypeId()),
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
                    .withHint("请先配置生产库后再正式提交")
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

    /**
     * 只读可投量 —— 与 {@link #plan} 同一段口径, 只是不加锁、不写库。
     *
     * <p>逐条对齐 plan(): 同一个 {@code resolveWorkshopId} 生产仓 / 同一套
     * {@code canonicalNativeUnit} 单位归一 (质量折 kg, 计数按字面) / 同样减去
     * {@code sumPendingQuantityByMaterialBatchId} 待占用 / 同样过
     * {@code ProductionInventoryOwnershipGuard}。任何一处走偏, 界面又会和提交打架。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<PortAvailability> availability(
            String factoryId, String planId,
            List<ProcessSheetRowRequest.MaterialInputTotal> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        ProductionPlan plan = requirePlan(factoryId, planId);
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        // 生产仓没配 = 一条都投不了。这里**不抛异常** (只是渲染用), 返回 0 + 别处存量,
        // 让界面照常显示"生产仓 0, 主仓另有 X"; 真正的拦截仍由 plan() 在提交时给出。
        List<PortAvailability> out = new ArrayList<>();
        for (ProcessSheetRowRequest.MaterialInputTotal port : ports) {
            String materialTypeId = port.getMaterialTypeId() == null ? null : port.getMaterialTypeId().trim();
            if (materialTypeId == null || materialTypeId.isEmpty()) {
                continue;
            }
            String inputUnit = canonicalNativeUnit(factoryId, normalizeUnit(port.getUnit()));
            boolean massInput = isCanonicalMassUnit(inputUnit);

            BigDecimal available = BigDecimal.ZERO;
            if (workshopId != null && !workshopId.isBlank()) {
                for (MaterialBatch batch : findEligibleBatches(factoryId, plan, materialTypeId, workshopId)) {
                    if (!ownershipAllows(plan, batch)) {
                        continue;
                    }
                    if (!unitMatches(factoryId, batch, inputUnit, massInput)) {
                        continue;
                    }
                    available = available.add(batchAvailable(factoryId, batch, massInput));
                }
            }

            // 同物料在别的仓 —— 「有货但没调过来」和「真没货」必须分得开
            Map<String, BigDecimal> byWarehouse = new LinkedHashMap<>();
            for (MaterialBatch batch : materialBatchRepository.findAvailableBatchesFEFO(factoryId, materialTypeId)) {
                if (Objects.equals(batch.getWarehouseId(), workshopId)) {
                    continue;
                }
                // 展示口径: 按箱/袋存量的批次也要报出来, 否则「原料仓另有 100kg」会整条消失
                if (!ownershipAllows(plan, batch)
                        || !unitMatchesForDisplay(factoryId, batch, inputUnit, massInput)) {
                    continue;
                }
                String name = warehouseResolver.displayName(factoryId, batch.getWarehouseId());
                if (name == null || name.isBlank()) {
                    continue;   // 说不出在哪个仓就不提 —— 「别处还有」但答不上"哪儿"等于没说
                }
                byWarehouse.merge(name, batchAvailable(factoryId, batch, massInput), BigDecimal::add);
            }
            List<ElsewhereStock> elsewhere = byWarehouse.entrySet().stream()
                    .filter(e -> e.getValue().signum() > 0)
                    .map(e -> new ElsewhereStock(e.getKey(), e.getValue(),
                            massInput ? KG : inputUnit))
                    .toList();

            // 生产仓里过期但仍有余量的部分 —— 单独报出来, 不进 available。
            // 目的是让「真没货」和「货过期了」在界面上分得开, 并提示去处理。
            BigDecimal expired = BigDecimal.ZERO;
            for (MaterialBatch batch : materialBatchRepository.findExpiredBatchesByWarehouse(
                    factoryId, materialTypeId, workshopId, java.time.LocalDate.now())) {
                // 展示口径: 同上 —— 过期提醒本就是纯信息, 漏掉按箱存量的批次等于瞒着仓管
                if (!ownershipAllows(plan, batch)
                        || !unitMatchesForDisplay(factoryId, batch, inputUnit, massInput)) {
                    continue;
                }
                expired = expired.add(batchAvailable(factoryId, batch, massInput));
            }

            // 生产仓以外各仓的过期存量 —— expired 只看生产仓、elsewhere 只看 AVAILABLE,
            // 「别的仓有货但过期了」这一种形态原来两边都不覆盖, 整批从界面消失。
            Map<String, BigDecimal> expiredByWarehouse = new LinkedHashMap<>();
            for (MaterialBatch batch : materialBatchRepository.findExpiredBatchesOutsideWarehouse(
                    factoryId, materialTypeId, workshopId, java.time.LocalDate.now())) {
                if (!ownershipAllows(plan, batch)
                        || !unitMatchesForDisplay(factoryId, batch, inputUnit, massInput)) {
                    continue;
                }
                String name = warehouseResolver.displayName(factoryId, batch.getWarehouseId());
                if (name == null || name.isBlank()) {
                    continue;   // 说不出在哪个仓就不提 —— 与 elsewhere 同一条口径
                }
                expiredByWarehouse.merge(name, batchAvailable(factoryId, batch, massInput), BigDecimal::add);
            }
            List<ElsewhereStock> expiredElsewhere = expiredByWarehouse.entrySet().stream()
                    .filter(e -> e.getValue().signum() > 0)
                    .map(e -> new ElsewhereStock(e.getKey(), e.getValue(),
                            massInput ? KG : inputUnit))
                    .toList();

            out.add(new PortAvailability(port.getWorkflowPortId(), materialTypeId,
                    available, massInput ? KG : inputUnit, elsewhere, expired, expiredElsewhere));
        }
        return out;
    }

    /** plan() 用带锁的那个; 这里是同一条件的非锁定版 (渲染路径不该锁行)。 */
    private List<MaterialBatch> findEligibleBatches(String factoryId, ProductionPlan plan,
                                                    String materialTypeId, String warehouseId) {
        if (plan.getMaterialSupplyMode() != MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            return materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                    factoryId, materialTypeId, warehouseId);
        }
        return materialBatchRepository
                .findAvailableCustomerSuppliedBatchesFEFOByWarehouse(
                        factoryId, materialTypeId, warehouseId,
                        plan.getCustomerId(), plan.getSourceOrderId())
                .stream()
                .filter(batch -> isCompatibleSalesOrderItem(plan, batch))
                .toList();
    }

    /** 归属守卫在 plan() 里是抛异常; 渲染路径只需要"能不能用"这个布尔。 */
    private boolean ownershipAllows(ProductionPlan plan, MaterialBatch batch) {
        try {
            ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(plan, batch, "生产报工投料");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * <b>严格版</b> —— 可投量与自动分配用。质量维度互认(仅 g/kg), 其余按字面。
     *
     * <p>⛔ 刻意<b>不</b>认「按箱/袋等包装单位存量」的批次: 扣减侧
     * {@link #kgToStorageQuantity} 只做 g↔kg, 对「箱」是<b>原样返回</b> ——
     * 把 100kg 的分配落到一个只有 10 箱的批次上会<b>超扣 10 倍</b>。
     * 要放开必须先让扣减侧也会走包装规格反算, 那是独立的一步。
     */
    private boolean unitMatches(String factoryId, MaterialBatch batch, String inputUnit, boolean massInput) {
        String batchUnit = canonicalNativeUnit(factoryId, batch.getQuantityUnit());
        return massInput ? isCanonicalMassUnit(batchUnit) : Objects.equals(inputUnit, batchUnit);
    }

    /**
     * <b>展示版</b> —— 只用于「过期 X, 不可投料」与「原料仓另有 X」这类<b>纯信息</b>口径。
     *
     * <p>🔴 2026-08-03: 按包装单位存量的批次原来被<b>整批跳过</b>, 既不进可投量, 也不进过期提醒,
     * 更不进「原料仓另有」—— 不是显示成 0, 是根本不在集合里。prod 实证: F006 SHH0713羊排
     * 原料仓 {@code MT-20260716-3809} 存着 10 箱, 而物料档案写着 1 箱 = 10 kg,
     * 即 <b>100 kg 完全不可见</b>, 仓管无从知道那批货存在。
     *
     * <p>与 P0-1(档案 {@code box} / 批次「盒」的<b>写法</b>不一致)不是同一类: 那类靠单位归一
     * 就能解决, 这类是<b>跨量纲</b>, 必须查包装规格。
     *
     * <p>⚠️ 展示口径比分配口径宽是<b>有意的</b>, 不是遗漏: 让仓管看得见「有这批货但当前不能直接投」,
     * 好过让它彻底消失。放进可投量则会导致超扣(见 {@link #unitMatches})。
     */
    private boolean unitMatchesForDisplay(
            String factoryId, MaterialBatch batch, String inputUnit, boolean massInput) {
        return unitMatches(factoryId, batch, inputUnit, massInput)
                || (massInput && packagedQuantityToKg(factoryId, batch).isPresent());
    }

    /** 与 plan() 同一算法: 库存减去待占用, 不小于 0。 */
    private BigDecimal batchAvailable(String factoryId, MaterialBatch batch, boolean massInput) {
        BigDecimal pending = nz(allocationRepository
                .sumPendingQuantityByMaterialBatchId(factoryId, batch.getId()));
        BigDecimal stock = massInput
                ? massStockToKg(factoryId, batch)
                : nz(batch.getCurrentQuantity());
        return stock.subtract(pending).max(BigDecimal.ZERO);
    }

    /**
     * 批次存量折成 kg —— 质量单位直折; <b>按包装单位存量的批次</b>走物料档案的包装规格。
     *
     * <p>🔴 2026-08-03 修复: 原来只认 g/kg, 于是「按箱存量」的批次<b>整批被跳过</b>,
     * 既不进可投量, 也不进「过期」提醒, 更不进「原料仓另有」—— 不是显示成 0, 是根本不在集合里。
     * prod 实证: F006 SHH0713羊排 原料仓 {@code MT-20260716-3809} 存着 10 箱,
     * 而 {@code material_packaging_specs} 明确写着 1 箱 = 10 kg, 即 <b>100 kg 完全不可见</b>。
     *
     * <p>这与 P0-1(档案 {@code box} / 批次「盒」的<b>写法</b>不一致)<b>不是同一类</b>:
     * 那类靠单位归一就能解决, 这类是<b>跨量纲</b>, 必须查包装规格换算。
     * 换算不自己算 —— {@code UnitContractService#convert} 已实现「物料档案直供包装规则
     * (如 1 case = 10 kg)」, 手搓一份等于再造一张会漂的私表。
     *
     * @return 折算后的 kg; 该批次不是「有换算的包装单位」时返回 empty
     */
    private java.util.Optional<BigDecimal> packagedQuantityToKg(String factoryId, MaterialBatch batch) {
        String rawUnit = batch.getQuantityUnit();
        if (unitContractService == null || rawUnit == null || rawUnit.isBlank()
                || batch.getMaterialTypeId() == null) {
            return java.util.Optional.empty();
        }
        try {
            com.cretas.aims.service.unit.UnitConversionResult result = unitContractService.convert(
                    nz(batch.getCurrentQuantity()),
                    new com.cretas.aims.service.unit.UnitConversionContext(
                            factoryId, batch.getMaterialTypeId(), rawUnit, KG,
                            // ⚠️ at 不能为 null: convert() 在 at==null 时直接返回
                            // PRODUCT_CONVERSION_MISSING, <b>走不到</b>物料包装规格那段。
                            java.time.LocalDateTime.now(), null, null, null));
            if (result == null || !result.succeeded() || result.quantity() == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(result.quantity());
        } catch (RuntimeException e) {
            // 换算不可用不该把整条可用量查询打挂 —— 与「认不出就跳过该批」同义
            log.warn("批次 {} 单位 {} 折 kg 失败, 按不可折处理: {}",
                    batch.getBatchNumber(), rawUnit, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** massInput 分支的存量取数: 先试质量直折, 再试包装规格; 都不行才报错。 */
    private BigDecimal massStockToKg(String factoryId, MaterialBatch batch) {
        if (isMassStorageUnit(batch.getQuantityUnit())) {
            return storageQuantityToKg(
                    nz(batch.getCurrentQuantity()), batch.getQuantityUnit(), batch.getBatchNumber());
        }
        return packagedQuantityToKg(factoryId, batch)
                .orElseThrow(() -> invalidBatchUnit(batch.getBatchNumber(), batch.getQuantityUnit()));
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
    /**
     * 单位归一 —— **只对有真实换算系数的科学单位生效**。
     *
     * <p>质量(g/kg)、体积(ml/L)之间存在恒定换算, 归一到等价码是有物理意义的。
     * 计数与包装单位不同: 只 / 件 / 个 / 袋 / 盒 / 箱 之间没有普适换算 —— 一只不等于
     * 一件, 一袋几只随物料而变。给它们编一个共同等价码, 等于让系统替工厂断定"两个
     * 不同的东西是同一个东西"; 工厂以后新建一个「扇」或「提」时, 系统也无从判断该把
     * 它挂进哪个族。</p>
     *
     * <p>所以非科学单位一律按字面比较: 写法相同才是同一个单位。要跨单位投料, 得有
     * 显式的每物料换算(每袋几只), 而不是靠一张全局别名表猜。</p>
     */
    private String canonicalNativeUnit(String factoryId, String unit) {
        if (unit == null || unit.isBlank()) return null;
        // 契约认不出时回落字面比较, 但必须先折大小写 ——
        // KG 与 kg 本就是同一个单位, 字面直比会把它们当成两样东西。
        // 中文单位不受影响; 拉丁字母写法则不再因大小写失配。
        String trimmed = unit.trim();
        return unitContractService.describe(factoryId, trimmed)
                .filter(canonical -> canonical.dimension() == UnitDimension.MASS
                        || canonical.dimension() == UnitDimension.VOLUME)
                .map(com.cretas.aims.service.unit.CanonicalUnit::code)
                .orElseGet(() -> trimmed.toLowerCase(java.util.Locale.ROOT));
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
                .withHint("支持 g/kg 直接换算；按箱、袋等包装单位存量的批次，请先在物料档案里"
                        + "补全该包装规格（如 1 箱 = 10 kg）后重试")
                .withSeverity("BLOCKING");
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 缺料明细里的物料名。
     *
     * 这个字段 DTO 里一直有, 但三处构造全传的 null —— 于是缺料提示只会说"投料量不足",
     * 不说是哪个物料。客户因此把生产仓里另一个同类物料(元益漫黄油鸡)当成了工序要投的
     * 温氏黄油鸡, 跑去仓库比对才发现根本是两样东西。名字必须报出来。
     */
    private String materialName(String factoryId, String materialTypeId) {
        if (materialTypeId == null || materialTypeId.isBlank()) return null;
        return rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId)
                .map(com.cretas.aims.entity.RawMaterialType::getName)
                .orElse(null);
    }


}
