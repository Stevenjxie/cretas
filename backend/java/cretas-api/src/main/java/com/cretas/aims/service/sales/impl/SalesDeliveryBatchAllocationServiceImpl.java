package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
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
import java.util.EnumSet;
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
    private final SalesDeliveryRecordRepository deliveryRecordRepository;

    /**
     * 允许修改批次分配的发货单状态 (发货前). 已发货/已签收/已退回后分配记录冻结 (溯源 + 防重复释放预留).
     * 见 {@link #assertDeliveryEditable} —— 防止已发货行被再次分配/清空导致预留重复释放 (available 虚高).
     */
    private static final Set<SalesDeliveryStatus> EDITABLE_STATUSES = EnumSet.of(
            SalesDeliveryStatus.DRAFT,
            SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM,
            SalesDeliveryStatus.PICKED);

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

        // 🔴 预留隔离 (fix/fg-allocation-reserved-isolation): 已发货/已签收/已退回的发货单其分配记录已冻结
        // (溯源闭环 + 已在发货时把预留转为已发)。此时再改分配会重复释放预留 → available 虚高 → 允许超分。
        // 发货前 (DRAFT/待仓库确认/已拣货) 才可改分配。
        assertDeliveryEditable(item, "分配批次");

        // 🔴 预留隔离: 分配总量必须等于发货行数量 —— 提前校验, 在任何库存预留写入前 fail-fast,
        // 避免对一批已通过校验的批次写了预留、却因后续总量不符而回滚 (@Transactional 兜底, 但 fail-fast 更清晰)。
        BigDecimal requestedTotal = BigDecimal.ZERO;
        for (BatchAllocationDTO dto : allocations) {
            if (dto.getFinishedGoodsBatchId() == null || dto.getAllocatedQty() == null) {
                throw new BusinessException(400, "批次分配字段不完整")
                        .withHint("请填写成品批次 ID 和分配数量").withHintTarget("allocations");
            }
            if (dto.getAllocatedQty().signum() <= 0) {
                throw new BusinessException(400, "分配数量必须大于 0")
                        .withHint("请输入大于 0 的分配数量").withHintTarget("allocatedQty");
            }
            requestedTotal = requestedTotal.add(dto.getAllocatedQty());
        }
        if (requestedTotal.compareTo(item.getDeliveredQuantity()) != 0) {
            throw new BusinessException(400, "批次分配总量 " + requestedTotal
                    + " 不等于发货行数量 " + item.getDeliveredQuantity())
                    .withHint("分配总量必须等于发货行数量").withHintTarget("allocations");
        }

        // 🔴 预留隔离: 先释放本发货行<b>旧</b>分配占用的预留 (re-allocate / 幂等重放), 再按新分配重新预留 ——
        // 保证 reserved 不因反复分配而叠加 (reserved == Σ 本行当前 live 分配的原生量, 单一真相)。
        // release 在 reserve 之前, 让「改分配到同一批」的可用量校验不误把本行旧预留算进占用。
        releaseReservedForLine(factoryId, deliveryItemId);
        allocationRepository.deleteByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);

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

        // 2. 校验每一条 allocation：batch 存在、同工厂、warehouse 匹配、有足够可用库存；并<b>预留</b>该批。
        // 🔴 预留隔离: 用悲观写锁 (findByIdAndFactoryIdForUpdate) 读取批次 —— 串行化并发分配 (两个发货行
        // 同时分配同一批) 的 read-check-write 三步, 让第二个分配看到第一个已写的 reserved → 可用不足 409,
        // 杜绝两单各自"分配成功"同一物理库存 (超分)。锁与发货扣减 (deductByAllocations 同一锁方法) 同路径,
        // 不造竞态。
        List<SalesDeliveryItemBatchAllocation> toPersist = new ArrayList<>();
        for (BatchAllocationDTO dto : allocations) {
            FinishedGoodsBatch batch = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(dto.getFinishedGoodsBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "成品批次不存在或不属于当前工厂: "
                            + dto.getFinishedGoodsBatchId())
                            .withHint("请刷新成品库存后重新选择本工厂的成品批次").withHintTarget("finishedGoodsBatchId"));
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
            // 🔴 C1: convert batch-native available into the delivery line's unit before comparing
            // against dto.getAllocatedQty() (always item.getUnit()).
            BigDecimal available = FgQuantityUnitConverter.convert(
                    availableNative, batch.getUnit(), item.getUnit(), gramsPerUnit);
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

            // 🔴 预留隔离: 把本次分配量 (发货行单位) 换算回批次原生单位, 累加到 reserved_quantity。
            // 之后其他发货行/订单读到的 available = produced − shipped − reserved 就已扣掉本行占用 → 不能超分。
            // reserve 的原生量与发货扣减时释放的原生量 (deductByAllocations → applyShipment releaseReserved)
            // 用同一换算口径, 保证 allocate→ship 对称、发货后 reserved 回到基线。
            BigDecimal reserveNative = FgQuantityUnitConverter.convert(
                    dto.getAllocatedQty(), item.getUnit(), batch.getUnit(), gramsPerUnit);
            if (reserveNative == null) {
                // available 换算已成功却此处失败不可能; 防御性 loud-fail, 绝不静默跳过预留 (会导致超分)。
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 预留量无法换算到批次单位（" + batch.getUnit() + "）")
                        .withHint("请联系管理员在产品资料中补充「每盒/份标准克重」").withHintTarget("finishedGoodsBatchId");
            }
            BigDecimal currentReserved = batch.getReservedQuantity() == null
                    ? BigDecimal.ZERO : batch.getReservedQuantity();
            batch.setReservedQuantity(currentReserved.add(reserveNative));
            finishedGoodsBatchRepository.save(batch);

            SalesDeliveryItemBatchAllocation alloc = new SalesDeliveryItemBatchAllocation();
            alloc.setFactoryId(factoryId);
            alloc.setDeliveryItemId(deliveryItemId);
            alloc.setFinishedGoodsBatchId(batch.getId());
            alloc.setBatchNumber(batch.getBatchNumber());
            alloc.setAllocatedQty(dto.getAllocatedQty());
            alloc.setUnit(item.getUnit());
            toPersist.add(alloc);
        }

        // 旧分配记录已在预留释放后删除 (见方法上半段 releaseReservedForLine + delete), 此处仅写入新记录。
        allocationRepository.saveAll(toPersist);

        log.info("销售发货批次分配(含预留): factoryId={}, deliveryItemId={}, allocations={}, total={}",
                factoryId, deliveryItemId, toPersist.size(), requestedTotal);
    }

    @Override
    public List<SalesDeliveryItemBatchAllocation> listByDeliveryItem(String factoryId, String deliveryItemId) {
        return allocationRepository.findByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
    }

    @Override
    @Transactional
    public void clearAllocations(String factoryId, String deliveryItemId) {
        // 🔴 预留隔离: 取消/清空分配 = 释放本行占用的成品批次预留 (release on cancel), 再删记录。
        // 已发货的发货行其分配已在发货时把预留转已发, 不能再清空 (否则重复释放 → available 虚高)。
        Long itemIdLong = null;
        try {
            if (deliveryItemId != null && !deliveryItemId.isBlank()) {
                itemIdLong = Long.valueOf(deliveryItemId);
            }
        } catch (NumberFormatException ignore) {
            itemIdLong = null;
        }
        if (itemIdLong != null) {
            SalesDeliveryItem item = deliveryItemRepository.findById(itemIdLong).orElse(null);
            if (item != null) {
                assertDeliveryEditable(item, "清空批次分配");
            }
        }
        releaseReservedForLine(factoryId, deliveryItemId);
        allocationRepository.deleteByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
    }

    /**
     * 🔴 预留隔离: 释放某发货行<b>当前</b>分配记录占用的成品批次预留 (reserved_quantity)。
     *
     * <p>用于 re-allocate (allocateBatches 先释放旧再预留新) 与 cancel/clear (clearAllocations)。
     * 对每条现存分配记录: 悲观锁定其批次 → 按同一换算口径把分配量 (记录单位) 换回批次原生单位 →
     * {@code reserved -= 该原生量} (max(0) 守不变式)。批次已删除则跳过 (无预留可释放)。
     *
     * <p><b>幂等/防超分</b>: 释放量 = 分配记录里 allocatedQty 的原生换算 (与 allocate 时累加的量一致),
     * 保证「分配→释放」严格对称, reserved 精确回到基线, 既不残留幻影预留 (有货发不出), 也不多释放
     * (会让 available 虚高再次允许超分)。
     */
    private void releaseReservedForLine(String factoryId, String deliveryItemId) {
        List<SalesDeliveryItemBatchAllocation> existing =
                allocationRepository.findByFactoryIdAndDeliveryItemId(factoryId, deliveryItemId);
        for (SalesDeliveryItemBatchAllocation ex : existing) {
            if (ex.getFinishedGoodsBatchId() == null || ex.getAllocatedQty() == null
                    || ex.getAllocatedQty().signum() <= 0) {
                continue;
            }
            FinishedGoodsBatch batch = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(ex.getFinishedGoodsBatchId(), factoryId)
                    .orElse(null);
            if (batch == null) {
                // 批次已被删除 —— 无 reserved 可释放 (删除路径本身要求 reserved==0)。
                continue;
            }
            BigDecimal gramsPerUnit = productTypeRepository.findById(batch.getProductTypeId())
                    .map(ProductType::getGramsPerUnit).orElse(null);
            String allocUnit = ex.getUnit() != null ? ex.getUnit() : batch.getUnit();
            BigDecimal releaseNative = FgQuantityUnitConverter.convert(
                    ex.getAllocatedQty(), allocUnit, batch.getUnit(), gramsPerUnit);
            if (releaseNative == null) {
                // 极端: 预留时可换算, 现在产品克重被删导致不可换算。诚实 loud-fail, 不静默残留幻影预留。
                throw new BusinessException(409, "无法释放成品批次 " + batch.getBatchNumber()
                        + " 的预留（分配单位 " + allocUnit + " 与批次单位 " + batch.getUnit() + " 不可换算）")
                        .withHint("请在产品资料中补充「每盒/份标准克重」后重试").withHintTarget("finishedGoodsBatchId");
            }
            BigDecimal currentReserved = batch.getReservedQuantity() == null
                    ? BigDecimal.ZERO : batch.getReservedQuantity();
            batch.setReservedQuantity(currentReserved.subtract(releaseNative).max(BigDecimal.ZERO));
            finishedGoodsBatchRepository.save(batch);
        }
    }

    /**
     * 🔴 预留隔离: 断言发货单处于<b>发货前</b>状态 (可改分配)。已发货/已签收/已退回后分配冻结,
     * 拒绝再分配/清空 —— 防止已发货行的分配被再次操作导致预留重复释放 (available 虚高 → 超分)。
     *
     * <p>fail-open: 发货行无 deliveryRecordId (临时/内部草稿行) 或记录/状态缺失时不阻断 —— 预留记账本身
     * 已幂等对称, 守卫只是额外防线, 不因元数据缺失误伤正常分配。
     */
    private void assertDeliveryEditable(SalesDeliveryItem item, String action) {
        String recordId = item.getDeliveryRecordId();
        if (recordId == null || recordId.isBlank()) {
            return;
        }
        SalesDeliveryRecord record = deliveryRecordRepository.findById(recordId).orElse(null);
        if (record == null || record.getStatus() == null) {
            return;
        }
        if (!EDITABLE_STATUSES.contains(record.getStatus())) {
            throw new BusinessException(409, "发货单当前状态（" + record.getStatus().getDisplayName()
                    + "）不允许" + action)
                    .withHint("已发货/已签收的发货单批次分配已冻结, 如需调整请走退货或撤销流程")
                    .withHintTarget("发货记录 Tab");
        }
    }

    @Override
    public List<Map<String, Object>> recommendFifo(String factoryId, String productTypeId, BigDecimal requiredQty, String unit, String sourceWarehouseCode) {
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
                    : FgQuantityUnitConverter.convert(availableNative, batch.getUnit(), targetUnit, gramsPerUnit);
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
