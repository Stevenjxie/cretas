package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesDeliveryBatchAllocationServiceImpl implements SalesDeliveryBatchAllocationService {

    private final SalesDeliveryItemBatchAllocationRepository allocationRepository;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final WarehouseResolver warehouseResolver;

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

        // 1. 查发货行
        Long itemIdLong;
        try {
            itemIdLong = Long.valueOf(deliveryItemId);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "非法的 deliveryItemId: " + deliveryItemId)
                    .withHint("发货行 ID 应为数字").withHintTarget("deliveryItemId");
        }
        SalesDeliveryItem item = deliveryItemRepository.findById(itemIdLong)
                .orElseThrow(() -> new BusinessException(404, "发货行不存在: " + deliveryItemId)
                        .withHint("请刷新发货单后重新选择").withHintTarget("deliveryItemId"));
        if (item.getDeliveredQuantity() == null) {
            throw new BusinessException(409, "发货行数量未设置: " + deliveryItemId)
                    .withHint("请先在发货单中设置发货数量").withHintTarget("deliveredQuantity");
        }

        // T4-D5 (#572): resolve the expected source warehouse for this delivery line.
        // sourceWarehouseCode is per-row (PR #547/#564); null/blank → WH-LOG (legacy default).
        String expectedWarehouseId;
        String expectedWarehouseCode = item.getSourceWarehouseCode();
        if (expectedWarehouseCode != null && !expectedWarehouseCode.isBlank()) {
            expectedWarehouseId = warehouseResolver.resolveId(factoryId, expectedWarehouseCode);
        } else {
            expectedWarehouseId = warehouseResolver.resolveLogisticsId(factoryId);
            expectedWarehouseCode = "WH-LOG";
        }

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
            FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(dto.getFinishedGoodsBatchId())
                    .orElseThrow(() -> new BusinessException(404, "成品批次不存在: " + dto.getFinishedGoodsBatchId())
                            .withHint("请刷新成品库存后重新选择").withHintTarget("finishedGoodsBatchId"));
            if (!factoryId.equals(batch.getFactoryId())) {
                throw new BusinessException(403, "成品批次不属于当前工厂: " + dto.getFinishedGoodsBatchId())
                        .withHint("跨工厂调用被拒绝, 请选择本工厂的成品批次").withHintTarget("finishedGoodsBatchId");
            }
            // T4-D5 (#572): batch.warehouseId must match line's declared sourceWarehouseCode.
            // Blocks manual pickers from bypassing the line's intended warehouse.
            if (!expectedWarehouseId.equals(batch.getWarehouseId())) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 所在仓库与发货行声明的来源仓库 " + expectedWarehouseCode + " 不匹配")
                        .withHint("请选择 " + expectedWarehouseCode + " 仓库内的成品批次, 或修改发货行的来源仓库")
                        .withHintTarget("finishedGoodsBatchId");
            }
            BigDecimal available = batch.getProducedQuantity()
                    .subtract(batch.getShippedQuantity() == null ? BigDecimal.ZERO : batch.getShippedQuantity())
                    .subtract(batch.getReservedQuantity() == null ? BigDecimal.ZERO : batch.getReservedQuantity());
            if (available.compareTo(dto.getAllocatedQty()) < 0) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 可用库存不足（可用=" + available + "，申请=" + dto.getAllocatedQty() + "）")
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
    public List<Map<String, Object>> recommendFifo(String factoryId, String productTypeId, BigDecimal requiredQty, String sourceWarehouseCode) {
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

        // T4-D5 (#572): honor caller-supplied sourceWarehouseCode (PR #547/#564 data contract).
        // Legacy callers passing null fall back to WH-LOG — preserves D5 default.
        // 用途拆分 (2026-07-02): 默认来源走 resolveSalesOutboundWh (SALES_OUTBOUND_DEFAULT),
        // 未配置回退 WH-LOG = 现状。
        String warehouseId = (sourceWarehouseCode != null && !sourceWarehouseCode.isBlank())
                ? warehouseResolver.resolveId(factoryId, sourceWarehouseCode)
                : warehouseResolver.resolveSalesOutboundWh(factoryId);
        var batches = finishedGoodsBatchRepository
                .findAvailableBatchesFifoByWarehouse(factoryId, productTypeId, warehouseId);

        List<Map<String, Object>> result = new ArrayList<>();
        BigDecimal remaining = requiredQty;

        for (var batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = batch.getAvailableQuantity();
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal allocate = remaining.min(available);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("batchId", batch.getId());
            rec.put("batchNumber", batch.getBatchNumber());
            rec.put("productionDate", batch.getProductionDate() != null ? batch.getProductionDate().toString() : null);
            rec.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
            rec.put("availableQuantity", available);
            rec.put("recommendedQuantity", allocate);
            result.add(rec);
            remaining = remaining.subtract(allocate);
        }

        log.info("FIFO 成品推荐: factoryId={}, productTypeId={}, requiredQty={}, batches={}, fulfilled={}",
                factoryId, productTypeId, requiredQty, result.size(),
                remaining.compareTo(BigDecimal.ZERO) <= 0);

        return result;
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
