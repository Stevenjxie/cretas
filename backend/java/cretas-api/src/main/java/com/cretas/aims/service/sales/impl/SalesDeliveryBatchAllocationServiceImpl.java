package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesDeliveryBatchAllocationServiceImpl implements SalesDeliveryBatchAllocationService {

    private final SalesDeliveryItemBatchAllocationRepository allocationRepository;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final WarehouseResolver warehouseResolver;
    private final ProductTypeRepository productTypeRepository;

    @Override
    @Transactional
    public void allocateBatches(String factoryId, String deliveryItemId, List<BatchAllocationDTO> allocations) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 不能为空")
                    .withHint("请重新登录获取有效的工厂上下文").withHintTarget("factoryId");
        }
        if (deliveryItemId == null || deliveryItemId.isBlank()) {
            throw new BusinessException(400, "deliveryItemId 不能为空")
                    .withHint("请选择具体的发货行").withHintTarget("deliveryItemId");
        }
        if (allocations == null || allocations.isEmpty()) {
            throw new BusinessException(400, "批次分配列表不能为空")
                    .withHint("请添加至少 1 条批次分配").withHintTarget("allocations");
        }
        Set<String> requestedBatchIds = new HashSet<>();
        for (BatchAllocationDTO allocation : allocations) {
            String batchId = allocation != null ? allocation.getFinishedGoodsBatchId() : null;
            if (batchId != null && !requestedBatchIds.add(batchId)) {
                throw new BusinessException(400, "同一成品批次在一次分配中只能出现一次")
                        .withCode("DUPLICATE_BATCH_ALLOCATION")
                        .withHintTarget("finishedGoodsBatchId");
            }
        }

        // 1. 查发货行
        Long itemIdLong;
        try {
            itemIdLong = Long.valueOf(deliveryItemId);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "非法的 deliveryItemId: " + deliveryItemId)
                    .withHint("发货行 ID 应为数字").withHintTarget("deliveryItemId");
        }
        SalesDeliveryItem item = deliveryItemRepository.findByIdForUpdate(itemIdLong)
                .orElseThrow(() -> new BusinessException(404, "发货行不存在: " + deliveryItemId)
                        .withHint("请刷新发货单后重新选择").withHintTarget("deliveryItemId"));
        if (item.getDeliveredQuantity() == null) {
            throw new BusinessException(409, "发货行数量未设置: " + deliveryItemId)
                    .withHint("请先在发货单中设置发货数量").withHintTarget("deliveredQuantity");
        }
        if (item.getDeliveryRecord() != null) {
            String role = item.getDeliveryRecord().getRecordRole();
            if ("MASTER".equals(role)) {
                throw new BusinessException(409, "库存批次必须分配到子发运单，不能直接分配到母发货单")
                        .withCode("DELIVERY_SHIPMENT_REQUIRED");
            }
            var status = item.getDeliveryRecord().getStatus();
            if (status != com.cretas.aims.entity.enums.SalesDeliveryStatus.DRAFT
                    && status != com.cretas.aims.entity.enums.SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM
                    && status != com.cretas.aims.entity.enums.SalesDeliveryStatus.PICKED) {
                throw new BusinessException(409, "当前发货单状态不允许新增或修改批次分配")
                        .withCode("BATCH_ALLOCATION_FROZEN");
            }
        }

        List<SalesDeliveryItemBatchAllocation> current = allocationRepository
                .findByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
        if (sameAllocationPayload(current, allocations)) {
            log.info("销售发货批次分配幂等重放: factoryId={}, deliveryItemId={}", factoryId, deliveryItemId);
            return;
        }

        // T4-D5 (#572) + 🔴 G1 (2026-07-03): resolve the expected source warehouse for this line.
        // sourceWarehouseCode is per-row (PR #547/#564).
        //   - EXPLICIT (non-blank): batch must match that warehouse exactly (409 guard preserved —
        //     blocks manual pickers from bypassing the line's declared source warehouse).
        //   - BLANK (common case): NO declared source → NO single-warehouse constraint. Accept a
        //     batch from ANY shippable (non-RD) warehouse — mirrors recommendFifo / deduct discovery
        //     so 「推荐能选 → 分配能过 → 发货能扣」三段一致. This is NOT relaxing the explicit-source
        //     guard; it removes the phantom WH-LOG default that was never an explicit user choice and
        //     caused FG in WH-WKS/FINISHED to be un-shippable (Steve #1 named bug).
        String explicitWarehouseCode = item.getSourceWarehouseCode();
        boolean hasExplicitSource = explicitWarehouseCode != null && !explicitWarehouseCode.isBlank();
        String expectedWarehouseId = hasExplicitSource
                ? warehouseResolver.resolveId(factoryId, explicitWarehouseCode)
                : null;
        // For the blank case, resolve RD warehouse id to exclude non-saleable trial batches.
        // Defensive: factories without a WH-RD seed → null (nothing to exclude).
        String rdWarehouseId = null;
        if (!hasExplicitSource) {
            try {
                rdWarehouseId = warehouseResolver.resolveRdId(factoryId);
            } catch (BusinessException ignore) {
                rdWarehouseId = null;
            }
        }

        // 🔴 C1 (2026-07-05): FG batches for one product may be recorded in DIFFERENT native units
        // (one 小结'd with productWeight → kg, another without → 盒/件). allocatedQty (persisted
        // alongside alloc.unit=item.getUnit()) is always in the DELIVERY LINE's unit — so a batch's
        // native available quantity must be converted into item.getUnit() before comparison, never
        // compared/summed as raw numbers across units.
        BigDecimal gramsPerUnit = productTypeRepository.findById(item.getProductTypeId())
                .map(ProductType::getGramsPerUnit).orElse(null);

        // 2. 校验每一条 allocation：batch 存在、同工厂、warehouse 匹配、有足够可用库存
        BigDecimal total = BigDecimal.ZERO;
        List<SalesDeliveryItemBatchAllocation> toPersist = new ArrayList<>();
        for (BatchAllocationDTO dto : allocations) {
            if (dto.getFinishedGoodsBatchId() == null || dto.getAllocatedQty() == null) {
                throw new BusinessException(400, "批次分配字段不完整")
                        .withHint("请填写成品批次 ID 和分配数量").withHintTarget("allocations");
            }
            if (dto.getAllocatedQty().signum() <= 0) {
                throw new BusinessException(400, "分配数量必须大于 0")
                        .withHint("请输入大于 0 的分配数量").withHintTarget("allocatedQty");
            }
            FinishedGoodsBatch batch = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(dto.getFinishedGoodsBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "成品批次不存在: " + dto.getFinishedGoodsBatchId())
                            .withHint("请刷新成品库存后重新选择").withHintTarget("finishedGoodsBatchId"));
            if (!factoryId.equals(batch.getFactoryId())) {
                throw new BusinessException(403, "成品批次不属于当前工厂: " + dto.getFinishedGoodsBatchId())
                        .withHint("跨工厂调用被拒绝, 请选择本工厂的成品批次").withHintTarget("finishedGoodsBatchId");
            }
            // T4-D5 (#572) + 🔴 G1: warehouse guard.
            if (hasExplicitSource) {
                // EXPLICIT source → batch must be in that exact warehouse (409 guard preserved).
                if (!expectedWarehouseId.equals(batch.getWarehouseId())) {
                    throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                            + " 所在仓库与发货行声明的来源仓库 " + explicitWarehouseCode + " 不匹配")
                            .withHint("请选择 " + explicitWarehouseCode + " 仓库内的成品批次, 或修改发货行的来源仓库")
                            .withHintTarget("finishedGoodsBatchId");
                }
            } else if (rdWarehouseId != null && rdWarehouseId.equals(batch.getWarehouseId())) {
                // BLANK source → any warehouse OK EXCEPT the R&D/trial warehouse (non-saleable).
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 位于研发/中试库, 不可用于销售出货")
                        .withHint("研发/中试批次不混入可售库存, 请选择其他仓库的成品批次")
                        .withHintTarget("finishedGoodsBatchId");
            }
            BigDecimal availableNative = batch.getProducedQuantity()
                    .subtract(batch.getShippedQuantity() == null ? BigDecimal.ZERO : batch.getShippedQuantity())
                    .subtract(batch.getReservedQuantity() == null ? BigDecimal.ZERO : batch.getReservedQuantity());
            BigDecimal activeAllocatedNative = activeAllocatedNativeExcludingCurrent(
                    factoryId, batch, deliveryItemId, item.getProductTypeId(), gramsPerUnit);
            BigDecimal allocatableNative = availableNative.subtract(activeAllocatedNative).max(BigDecimal.ZERO);
            // 🔴 C1: convert batch-native available into the delivery line's unit before comparing
            // against dto.getAllocatedQty() (always item.getUnit()).
            BigDecimal available = convertBatchToDeliveryUnit(
                    allocatableNative, batch, item, item.getUnit(), gramsPerUnit);
            if (available == null) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 的单位（" + batch.getUnit() + "）与发货单位（" + item.getUnit() + "）不一致, 且缺少产品「每盒/份克重」配置无法换算")
                        .withHint("请联系管理员在产品资料中补充「每盒/份标准克重」, 或改用与发货单位一致的批次")
                        .withHintTarget("finishedGoodsBatchId");
            }
            if (available.compareTo(dto.getAllocatedQty()) < 0) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 可用库存不足（可用=" + available + item.getUnit() + "，申请=" + dto.getAllocatedQty() + item.getUnit() + "）")
                        .withHint("请减少分配数量, 或选择其他批次").withHintTarget("allocatedQty");
            }

            SalesDeliveryItemBatchAllocation alloc = new SalesDeliveryItemBatchAllocation();
            alloc.setFactoryId(factoryId);
            alloc.setDeliveryItemId(deliveryItemId);
            alloc.setFinishedGoodsBatchId(batch.getId());
            alloc.setBatchNumber(batch.getBatchNumber());
            alloc.setAllocatedQty(dto.getAllocatedQty());
            alloc.setUnit(item.getUnit());
            toPersist.add(alloc);
            total = total.add(dto.getAllocatedQty());
        }

        // 3. 总量必须等于发货行数量
        if (total.compareTo(item.getDeliveredQuantity()) != 0) {
            throw new BusinessException(400, "批次分配总量 " + total
                    + " 不等于发货行数量 " + item.getDeliveredQuantity())
                    .withHint("分配总量必须等于发货行数量").withHintTarget("allocations");
        }

        // 4. 先清空旧分配，再写入
        allocationRepository.deleteByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
        allocationRepository.saveAll(toPersist);

        log.info("销售发货批次分配: factoryId={}, deliveryItemId={}, allocations={}, total={}",
                factoryId, deliveryItemId, toPersist.size(), total);
    }

    @Override
    public List<SalesDeliveryItemBatchAllocation> listByDeliveryItem(String factoryId, String deliveryItemId) {
        return allocationRepository.findByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
    }

    @Override
    @Transactional
    public void clearAllocations(String factoryId, String deliveryItemId) {
        allocationRepository.deleteByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
    }

    @Override
    public List<Map<String, Object>> recommendFifo(
            String factoryId, String deliveryItemId, String productTypeId, BigDecimal requiredQty,
            String unit, String sourceWarehouseCode) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 不能为空")
                    .withHint("请重新登录获取有效的工厂上下文").withHintTarget("factoryId");
        }
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new BusinessException(400, "productTypeId 不能为空")
                    .withHint("请指定产品类型").withHintTarget("productTypeId");
        }
        if (requiredQty == null || requiredQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "requiredQty 必须大于 0")
                    .withHint("请输入大于 0 的需求数量").withHintTarget("requiredQty");
        }

        // 🔴 C1 (2026-07-05): resolve the target unit for this recommendation (the delivery line's
        // unit — requiredQty is expressed in it). Falls back to the product's default unit when the
        // caller didn't pass one (backward compat for older callers). gramsPerUnit drives cross-unit
        // conversion (FinishedGoodsBatch.unit may differ per-batch, see class javadoc on
        // FgQuantityUnitConverter).
        ProductType productType = productTypeRepository.findById(productTypeId).orElse(null);
        String targetUnit = (unit != null && !unit.isBlank())
                ? unit
                : (productType != null ? productType.getUnit() : null);
        BigDecimal gramsPerUnit = productType != null ? productType.getGramsPerUnit() : null;
        SalesDeliveryItem deliveryItem = null;
        if (deliveryItemId != null && !deliveryItemId.isBlank()) {
            try {
                deliveryItem = deliveryItemRepository.findById(Long.valueOf(deliveryItemId)).orElse(null);
            } catch (NumberFormatException ignored) {
                throw new BusinessException(400, "非法的 deliveryItemId: " + deliveryItemId)
                        .withHintTarget("deliveryItemId");
            }
            if (deliveryItem != null && !productTypeId.equals(deliveryItem.getProductTypeId())) {
                throw new BusinessException(400, "发货行与产品不匹配")
                        .withCode("DELIVERY_ITEM_PRODUCT_MISMATCH");
            }
        }

        // T4-D5 (#572) + 🔴 G1 (2026-07-03): warehouse discovery.
        //   - EXPLICIT sourceWarehouseCode → FIFO within that warehouse (respect explicit choice).
        //   - BLANK (common case) → FEFO across ALL shippable (non-RD) warehouses, so FG produced
        //     into WH-WKS / FINISHED / transferred to WH-LOG is discoverable. Fixes Steve #1 bug
        //     where a blank source hard-defaulted to a single WH-LOG and returned empty.
        boolean hasExplicitSource = sourceWarehouseCode != null && !sourceWarehouseCode.isBlank();
        List<FinishedGoodsBatch> batches;
        if (hasExplicitSource) {
            String warehouseId = warehouseResolver.resolveId(factoryId, sourceWarehouseCode);
            batches = finishedGoodsBatchRepository
                    .findAvailableBatchesFifoByWarehouse(factoryId, productTypeId, warehouseId);
        } else {
            batches = finishedGoodsBatchRepository
                    .findAvailableBatchesFefoAllWarehousesExcluding(factoryId, productTypeId, WarehouseCodes.WH_RD);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        BigDecimal remaining = requiredQty;
        int skippedUnitMismatch = 0;

        for (var batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal availableNative = batch.getAvailableQuantity();
            if (availableNative.compareTo(BigDecimal.ZERO) <= 0) continue;

            // 🔴 C1: convert this batch's native available quantity into targetUnit BEFORE it's
            // compared/summed against `remaining` (also in targetUnit). Batches whose native unit
            // can't be converted (缺 gramsPerUnit / 不兼容计数单位) are skipped — honest-null, never
            // silently mixed into the FEFO math.
            BigDecimal availableInTargetUnit = targetUnit == null
                    ? availableNative // 目标单位未知(极端: 产品无默认单位且调用方未传) — 向后兼容, 不换算
                    : convertBatchToDeliveryUnit(
                            availableNative, batch, deliveryItem, targetUnit, gramsPerUnit);
            if (availableInTargetUnit == null) {
                skippedUnitMismatch++;
                log.warn("FIFO 推荐跳过批次(单位不可换算): factoryId={}, batchId={}, batchUnit={}, targetUnit={}, gramsPerUnit={}",
                        factoryId, batch.getId(), batch.getUnit(), targetUnit, gramsPerUnit);
                continue;
            }
            if (availableInTargetUnit.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal allocate = remaining.min(availableInTargetUnit);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("batchId", batch.getId());
            rec.put("batchNumber", batch.getBatchNumber());
            rec.put("productionDate", batch.getProductionDate() != null ? batch.getProductionDate().toString() : null);
            rec.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
            // 🔴 UX2: expose the batch's own native unit + the (converted, comparable) available/
            // recommended quantities in targetUnit, so the allocation dialog never shows a bare
            // number without a unit, and never mixes units across rows.
            rec.put("unit", targetUnit != null ? targetUnit : batch.getUnit());
            rec.put("batchNativeUnit", batch.getUnit());
            rec.put("availableQuantity", availableInTargetUnit);
            rec.put("recommendedQuantity", allocate);
            result.add(rec);
            remaining = remaining.subtract(allocate);
        }

        log.info("FIFO 成品推荐: factoryId={}, productTypeId={}, requiredQty={}, unit={}, batches={}, skippedUnitMismatch={}, fulfilled={}",
                factoryId, productTypeId, requiredQty, targetUnit, result.size(), skippedUnitMismatch,
                remaining.compareTo(BigDecimal.ZERO) <= 0);

        return result;
    }

    /**
     * Counts soft allocations held by other active delivery lines while the batch row is locked.
     * This serializes concurrent allocators without mutating the order-reservation ledger.
     */
    private BigDecimal activeAllocatedNativeExcludingCurrent(
            String factoryId,
            FinishedGoodsBatch batch,
            String currentDeliveryItemId,
            String expectedProductTypeId,
            BigDecimal gramsPerUnit) {
        BigDecimal total = BigDecimal.ZERO;
        List<SalesDeliveryItemBatchAllocation> existing = allocationRepository
                .findByFactoryIdAndFinishedGoodsBatchId(factoryId, batch.getId());
        for (SalesDeliveryItemBatchAllocation allocation : existing) {
            if (currentDeliveryItemId.equals(allocation.getDeliveryItemId())) continue;
            Long otherItemId;
            try {
                otherItemId = Long.valueOf(allocation.getDeliveryItemId());
            } catch (NumberFormatException error) {
                throw new BusinessException(409, "批次存在无法识别的历史发货占用，不能继续分配")
                        .withCode("BATCH_ALLOCATION_IDENTITY_INVALID");
            }
            SalesDeliveryItem otherItem = deliveryItemRepository.findById(otherItemId)
                    .orElseThrow(() -> new BusinessException(409, "批次存在失去发货行关联的占用记录")
                            .withCode("BATCH_ALLOCATION_IDENTITY_INVALID"));
            var delivery = otherItem.getDeliveryRecord();
            if (delivery != null) {
                var status = delivery.getStatus();
                if (status != com.cretas.aims.entity.enums.SalesDeliveryStatus.DRAFT
                        && status != com.cretas.aims.entity.enums.SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM
                        && status != com.cretas.aims.entity.enums.SalesDeliveryStatus.PICKED) {
                    continue;
                }
            }
            if (!expectedProductTypeId.equals(otherItem.getProductTypeId())) {
                throw new BusinessException(409, "同一成品批次存在产品身份不一致的发货占用")
                        .withCode("BATCH_ALLOCATION_PRODUCT_MISMATCH");
            }
            String allocationUnit = allocation.getUnit() != null
                    ? allocation.getUnit() : otherItem.getUnit();
            BigDecimal nativeQuantity = FgQuantityUnitConverter.convertWithPackaging(
                    allocation.getAllocatedQty(),
                    allocationUnit,
                    batch.getUnit(),
                    gramsPerUnit,
                    otherItem.getPackagingUnit(),
                    otherItem.getPackagingBaseUnit(),
                    otherItem.getPackagingFactor(),
                    batch.getPackagingUnit(),
                    batch.getPackagingBaseUnit(),
                    batch.getPackagingFactor());
            if (nativeQuantity == null) {
                throw new BusinessException(409, "批次存在单位无法换算的有效发货占用")
                        .withCode("ACTIVE_BATCH_ALLOCATION_UNIT_UNRESOLVED");
            }
            total = total.add(nativeQuantity);
        }
        return total;
    }

    private BigDecimal convertBatchToDeliveryUnit(
            BigDecimal quantity,
            FinishedGoodsBatch batch,
            SalesDeliveryItem deliveryItem,
            String targetUnit,
            BigDecimal gramsPerUnit) {
        return FgQuantityUnitConverter.convertWithPackaging(
                quantity,
                batch.getUnit(),
                targetUnit,
                gramsPerUnit,
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor(),
                deliveryItem != null ? deliveryItem.getPackagingUnit() : null,
                deliveryItem != null ? deliveryItem.getPackagingBaseUnit() : null,
                deliveryItem != null ? deliveryItem.getPackagingFactor() : null);
    }

    private boolean sameAllocationPayload(
            List<SalesDeliveryItemBatchAllocation> current, List<BatchAllocationDTO> requested) {
        if (current == null || requested == null || current.size() != requested.size()) return false;
        Map<String, BigDecimal> currentByBatch = current.stream().collect(java.util.stream.Collectors.toMap(
                SalesDeliveryItemBatchAllocation::getFinishedGoodsBatchId,
                SalesDeliveryItemBatchAllocation::getAllocatedQty,
                BigDecimal::add));
        Map<String, BigDecimal> requestedByBatch = requested.stream()
                .filter(dto -> dto.getFinishedGoodsBatchId() != null && dto.getAllocatedQty() != null)
                .collect(java.util.stream.Collectors.toMap(
                        BatchAllocationDTO::getFinishedGoodsBatchId,
                        BatchAllocationDTO::getAllocatedQty,
                        BigDecimal::add));
        if (currentByBatch.size() != requestedByBatch.size()) return false;
        return currentByBatch.entrySet().stream().allMatch(entry -> {
            BigDecimal requestedQty = requestedByBatch.get(entry.getKey());
            return requestedQty != null && requestedQty.compareTo(entry.getValue()) == 0;
        });
    }

    @Override
    public List<String> warehousesWithAvailableStock(String factoryId, String productTypeId) {
        if (factoryId == null || factoryId.isBlank() || productTypeId == null || productTypeId.isBlank()) {
            return List.of();
        }
        return finishedGoodsBatchRepository
                .findWarehouseCodesWithAvailableStock(factoryId, productTypeId, WarehouseCodes.WH_RD);
    }

    @Override
    public boolean isFullyAllocated(String factoryId, String deliveryItemId) {
        BigDecimal sum = allocationRepository.sumAllocatedQtyByDeliveryItemId(factoryId, deliveryItemId);
        if (sum == null || sum.signum() == 0) {
            return false;
        }
        Long itemIdLong;
        try {
            itemIdLong = Long.valueOf(deliveryItemId);
        } catch (NumberFormatException e) {
            return false;
        }
        SalesDeliveryItem item = deliveryItemRepository.findById(itemIdLong).orElse(null);
        if (item == null || item.getDeliveredQuantity() == null) {
            return false;
        }
        return sum.compareTo(item.getDeliveredQuantity()) == 0;
    }
}
