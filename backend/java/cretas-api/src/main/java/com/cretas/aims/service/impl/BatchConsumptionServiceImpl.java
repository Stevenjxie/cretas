package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BatchConsumptionServiceImpl implements BatchConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(BatchConsumptionServiceImpl.class);

    private static final String SOURCE_AUTO_BOM = "AUTO_BOM";
    private static final String SOURCE_ADJUSTMENT = "ADJUSTMENT";

    private final BomExpansionService bomExpansionService;
    private final MaterialBatchService materialBatchService;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final WarehouseResolver warehouseResolver;

    @Override
    @Transactional
    public void autoConsumeForBatch(ProductionBatch batch) {
        String factoryId = batch.getFactoryId();
        // recorded_by 是 NOT NULL + FK 到 users; 硬编码 0L 是无效用户, save 会抛 FK 违例并污染
        // Hibernate Session, 导致同事务后续 createFinishedGoodsFromBatch 无法 flush -> 自动入库静默失败
        // (prod E2E 抓到: 凡有 BOM 的成品 plain-complete 都不会自动建成品批次). 用批次主管/创建人兜底.
        Long recordedBy = batch.getSupervisorId() != null ? batch.getSupervisorId()
                : (batch.getCreatedBy() != null ? batch.getCreatedBy() : 1L);
        BigDecimal productionQty = batch.getActualQuantity() != null
                ? batch.getActualQuantity()
                : batch.getPlannedQuantity();

        if (productionQty == null || productionQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("批次无产出数量，跳过自动扣料: batchId={}", batch.getId());
            return;
        }

        // 幂等检查：已有AUTO_BOM记录则跳过
        List<MaterialConsumption> existing = consumptionRepository
                .findByProductionBatchIdAndSourceType(batch.getId(), SOURCE_AUTO_BOM);
        if (!existing.isEmpty()) {
            log.info("批次已执行过自动扣料，跳过: batchId={}, existingRecords={}", batch.getId(), existing.size());
            return;
        }

        // 1. BOM展开
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(
                factoryId, batch.getProductTypeId(), productionQty);
        if (requirements.isEmpty()) {
            log.info("产品无BOM配置，跳过自动扣料: batchId={}, productTypeId={}",
                    batch.getId(), batch.getProductTypeId());
            return;
        }

        // 2. 物料检查 + FEFO分配
        MaterialCheckResult checkResult = bomExpansionService.checkMaterialAvailability(
                factoryId, requirements);

        // 构建物料需求的快速查询map
        Map<String, MaterialRequirement> reqMap = requirements.stream()
                .collect(Collectors.toMap(MaterialRequirement::getMaterialTypeId, r -> r));

        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        // 3. 按分配方案逐批次消耗
        // 按materialTypeId分组allocations
        Map<String, List<MaterialAllocation>> allocationsByType = checkResult.getAllocations().stream()
                .collect(Collectors.groupingBy(MaterialAllocation::getMaterialTypeId));

        for (Map.Entry<String, List<MaterialAllocation>> entry : allocationsByType.entrySet()) {
            String materialTypeId = entry.getKey();
            List<MaterialAllocation> allocations = entry.getValue();
            MaterialRequirement req = reqMap.get(materialTypeId);
            BigDecimal plannedQtyForType = req != null ? req.getRequiredQuantity() : BigDecimal.ZERO;

            for (MaterialAllocation alloc : allocations) {
                try {
                    // 扣减物料批次库存
                    materialBatchService.useBatchQuantity(
                            factoryId, alloc.getMaterialBatchId(), alloc.getAllocatedQuantity());

                    // 获取批次单价
                    BigDecimal unitPrice = getUnitPrice(alloc.getMaterialBatchId(), materialTypeId, factoryId);
                    BigDecimal cost = alloc.getAllocatedQuantity().multiply(unitPrice)
                            .setScale(2, RoundingMode.HALF_UP);

                    // 创建消耗记录
                    MaterialConsumption consumption = new MaterialConsumption();
                    consumption.setFactoryId(factoryId);
                    consumption.setProductionPlanId(batch.getProductionPlanId());
                    consumption.setProductionBatchId(batch.getId());
                    consumption.setBatchId(alloc.getMaterialBatchId());
                    consumption.setMaterialTypeId(materialTypeId);
                    consumption.setQuantity(alloc.getAllocatedQuantity());
                    consumption.setPlannedQuantity(plannedQtyForType);
                    consumption.setUnitPrice(unitPrice);
                    consumption.setTotalCost(cost);
                    consumption.setConsumptionTime(LocalDateTime.now());
                    consumption.setConsumedAt(LocalDateTime.now());
                    consumption.setRecordedBy(recordedBy); // 系统自动 (批次主管/创建人, 不用无效 0L)
                    consumption.setSourceType(SOURCE_AUTO_BOM);
                    consumption.setNotes("BOM自动扣料");

                    consumptionRepository.save(consumption);
                    totalMaterialCost = totalMaterialCost.add(cost);

                    // 同一物料类型的后续批次，plannedQuantity置0（计划量只在首条记录上）
                    plannedQtyForType = BigDecimal.ZERO;

                } catch (Exception e) {
                    log.error("自动扣料失败: batchId={}, materialBatch={}, error={}",
                            batch.getId(), alloc.getMaterialBatchId(), e.getMessage());
                    // 单个物料批次扣减失败不中断整体流程
                }
            }
        }

        // 4. 处理库存不足的物料（使用移动均价记录计划消耗）
        for (var shortfall : checkResult.getShortfalls()) {
            BigDecimal unitPrice = getMovingAvgPrice(shortfall.getMaterialTypeId(), factoryId);
            BigDecimal partialQty = shortfall.getAvailableQuantity();
            BigDecimal cost = partialQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            MaterialRequirement req = reqMap.get(shortfall.getMaterialTypeId());

            MaterialConsumption consumption = new MaterialConsumption();
            consumption.setFactoryId(factoryId);
            consumption.setProductionPlanId(batch.getProductionPlanId());
            consumption.setProductionBatchId(batch.getId());
            consumption.setBatchId("SHORTFALL"); // 标记为缺料
            consumption.setMaterialTypeId(shortfall.getMaterialTypeId());
            consumption.setQuantity(partialQty);
            consumption.setPlannedQuantity(req != null ? req.getRequiredQuantity() : BigDecimal.ZERO);
            consumption.setUnitPrice(unitPrice);
            consumption.setTotalCost(cost);
            consumption.setConsumptionTime(LocalDateTime.now());
            consumption.setConsumedAt(LocalDateTime.now());
            consumption.setRecordedBy(recordedBy);
            consumption.setSourceType(SOURCE_AUTO_BOM);
            consumption.setNotes("BOM自动扣料(库存不足: 需" + shortfall.getRequiredQuantity()
                    + ", 可用" + shortfall.getAvailableQuantity() + ")");

            consumptionRepository.save(consumption);
            totalMaterialCost = totalMaterialCost.add(cost);
        }

        // 5. 更新批次物料成本
        batch.setMaterialCost(totalMaterialCost);
        batch.calculateMetrics();
        productionBatchRepository.save(batch);

        log.info("自动扣料完成: batchId={}, 物料种类={}, 总成本={}",
                batch.getId(), allocationsByType.size() + checkResult.getShortfalls().size(),
                totalMaterialCost);
    }

    @Override
    @Transactional
    public void adjustConsumption(String factoryId, Long batchId, String materialTypeId,
                                  BigDecimal actualQuantity, String reason) {
        // 查找现有消耗记录
        List<MaterialConsumption> existing = consumptionRepository
                .findByProductionBatchIdAndMaterialTypeId(batchId, materialTypeId);

        BigDecimal currentTotal = existing.stream()
                .filter(c -> !"ADJUSTMENT".equals(c.getSourceType()) || c.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(MaterialConsumption::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal delta = actualQuantity.subtract(currentTotal);
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return; // 无差异
        }

        BigDecimal unitPrice = existing.isEmpty()
                ? getMovingAvgPrice(materialTypeId, factoryId)
                : existing.get(0).getUnitPrice();

        ProductionBatch batch = productionBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("生产批次", "id", batchId));
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请切换到该批次所属的工厂后再操作");
        }

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            // D1: warehouse strategy per PR #310 §5 — 报工消耗 WH-WKS fixed (报工只在车间).
            String warehouseId = warehouseResolver.resolveWorkshopId(factoryId);
            List<MaterialBatch> fefoBatches = materialBatchRepository
                    .findAvailableBatchesFEFOByWarehouse(factoryId, materialTypeId, warehouseId);
            BigDecimal remaining = delta;

            for (MaterialBatch mb : fefoBatches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal available = mb.getReceiptQuantity()
                        .subtract(mb.getUsedQuantity() != null ? mb.getUsedQuantity() : BigDecimal.ZERO)
                        .subtract(mb.getReservedQuantity() != null ? mb.getReservedQuantity() : BigDecimal.ZERO);
                BigDecimal alloc = remaining.min(available);

                if (alloc.compareTo(BigDecimal.ZERO) > 0) {
                    materialBatchService.useBatchQuantity(factoryId, mb.getId(), alloc);
                    BigDecimal batchUnitPrice = mb.getUnitPrice() != null ? mb.getUnitPrice() : unitPrice;

                    MaterialConsumption adj = new MaterialConsumption();
                    adj.setFactoryId(factoryId);
                    adj.setProductionPlanId(batch.getProductionPlanId());
                    adj.setProductionBatchId(batchId);
                    adj.setBatchId(mb.getId());
                    adj.setMaterialTypeId(materialTypeId);
                    adj.setQuantity(alloc);
                    adj.setPlannedQuantity(BigDecimal.ZERO);
                    adj.setUnitPrice(batchUnitPrice);
                    adj.setTotalCost(alloc.multiply(batchUnitPrice).setScale(2, RoundingMode.HALF_UP));
                    adj.setConsumptionTime(LocalDateTime.now());
                    adj.setConsumedAt(LocalDateTime.now());
                    adj.setRecordedBy(1L);
                    adj.setSourceType(SOURCE_ADJUSTMENT);
                    adj.setNotes("差异调整(+): " + reason);
                    consumptionRepository.save(adj);

                    remaining = remaining.subtract(alloc);
                }
            }
        } else {
            // 退回: 创建负数调整记录
            MaterialConsumption adj = new MaterialConsumption();
            adj.setFactoryId(factoryId);
            adj.setProductionPlanId(batch.getProductionPlanId());
            adj.setProductionBatchId(batchId);
            adj.setBatchId(existing.isEmpty() ? "ADJUSTMENT" : existing.get(0).getBatchId());
            adj.setMaterialTypeId(materialTypeId);
            adj.setQuantity(delta); // 负数
            adj.setPlannedQuantity(BigDecimal.ZERO);
            adj.setUnitPrice(unitPrice);
            adj.setTotalCost(delta.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
            adj.setConsumptionTime(LocalDateTime.now());
            adj.setConsumedAt(LocalDateTime.now());
            adj.setRecordedBy(1L);
            adj.setSourceType(SOURCE_ADJUSTMENT);
            adj.setNotes("差异调整(-): " + reason);
            consumptionRepository.save(adj);
        }

        // 重算批次成本
        BigDecimal newCost = consumptionRepository.calculateTotalCostByProductionBatch(batchId);
        batch.setMaterialCost(newCost);
        batch.calculateMetrics();
        productionBatchRepository.save(batch);

        log.info("差异调整完成: batchId={}, materialType={}, delta={}, newCost={}",
                batchId, materialTypeId, delta, newCost);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConsumptionSummary(String factoryId, Long batchId) {
        List<MaterialConsumption> consumptions = consumptionRepository
                .findByProductionBatchIdAndFactoryId(batchId, factoryId);

        // 按物料类型聚合
        Map<String, List<MaterialConsumption>> byType = consumptions.stream()
                .filter(c -> c.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(MaterialConsumption::getMaterialTypeId));

        // 批量查询物料类型名称
        Set<String> typeIds = byType.keySet();
        Map<String, String> typeNameMap = new HashMap<>();
        if (!typeIds.isEmpty()) {
            for (String typeId : typeIds) {
                rawMaterialTypeRepository.findById(typeId)
                        .ifPresent(t -> typeNameMap.put(typeId, t.getName()));
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalPlannedCost = BigDecimal.ZERO;
        BigDecimal totalActualCost = BigDecimal.ZERO;

        for (Map.Entry<String, List<MaterialConsumption>> entry : byType.entrySet()) {
            String typeId = entry.getKey();
            List<MaterialConsumption> typeConsumptions = entry.getValue();

            BigDecimal plannedQty = typeConsumptions.stream()
                    .map(c -> c.getPlannedQuantity() != null ? c.getPlannedQuantity() : BigDecimal.ZERO)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal actualQty = typeConsumptions.stream()
                    .map(MaterialConsumption::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal cost = typeConsumptions.stream()
                    .map(c -> c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Weighted average unit price = totalCost / totalActualQty
            BigDecimal weightedUnitPrice = actualQty.compareTo(BigDecimal.ZERO) > 0
                    ? cost.divide(actualQty, 4, RoundingMode.HALF_UP)
                    : typeConsumptions.stream()
                        .map(MaterialConsumption::getUnitPrice)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(BigDecimal.ZERO);

            BigDecimal variancePercent = plannedQty.compareTo(BigDecimal.ZERO) > 0
                    ? actualQty.subtract(plannedQty)
                        .divide(plannedQty, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialTypeId", typeId);
            item.put("materialTypeName", typeNameMap.getOrDefault(typeId, typeId));
            item.put("plannedQty", plannedQty);
            item.put("actualQty", actualQty);
            item.put("variancePercent", variancePercent);
            item.put("unitPrice", weightedUnitPrice);
            item.put("cost", cost);
            items.add(item);

            BigDecimal plannedCost = plannedQty.multiply(weightedUnitPrice).setScale(2, RoundingMode.HALF_UP);
            totalPlannedCost = totalPlannedCost.add(plannedCost);
            totalActualCost = totalActualCost.add(cost);
        }

        BigDecimal achievementRate;
        if (totalPlannedCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deviation = totalActualCost.subtract(totalPlannedCost).abs()
                    .divide(totalPlannedCost, 4, RoundingMode.HALF_UP);
            achievementRate = BigDecimal.ONE.subtract(deviation)
                    .max(BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
        } else {
            achievementRate = BigDecimal.valueOf(100);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("totalPlannedCost", totalPlannedCost);
        result.put("totalActualCost", totalActualCost);
        result.put("overallAchievementRate", achievementRate);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateBatchMaterialCost(Long batchId) {
        return consumptionRepository.calculateTotalCostByProductionBatch(batchId);
    }

    // ===== 内部方法 =====

    private BigDecimal getUnitPrice(String materialBatchId, String materialTypeId, String factoryId) {
        return materialBatchRepository.findById(materialBatchId)
                .filter(b -> b.getUnitPrice() != null)
                .map(MaterialBatch::getUnitPrice)
                .orElseGet(() -> getMovingAvgPrice(materialTypeId, factoryId));
    }

    private BigDecimal getMovingAvgPrice(String materialTypeId, String factoryId) {
        return rawMaterialTypeRepository.findById(materialTypeId)
                .map(t -> {
                    if (t.getMovingAvgPrice() != null) return t.getMovingAvgPrice();
                    if (t.getUnitPrice() != null) return t.getUnitPrice();
                    return BigDecimal.ZERO;
                })
                .orElse(BigDecimal.ZERO);
    }
}
