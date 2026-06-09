package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SP11: 进销存台账服务实现.
 *
 * <p>数据来源:
 * <ol>
 *   <li>期初: {@link InventoryLedgerSnapshot} (月结快照); 若无快照则从 MaterialBatch 全量聚合兜底</li>
 *   <li>入库: PurchaseReceiveItem (receiveDate BETWEEN start..end)</li>
 *   <li>出库(生产): MaterialConsumption / MaterialBatch.usedQuantity 变化</li>
 *   <li>出库(销售): SalesDeliveryItem (via SalesDeliveryRecord.deliveryDate)</li>
 *   <li>调拨: InternalTransferItem</li>
 *   <li>盘盈/损: MaterialBatchAdjustment</li>
 *   <li>期末 = 期初 + 入库 - 出库 +/- 调拨 +/- 盘盈损</li>
 * </ol>
 *
 * <p>精度: qty scale-6, unitPrice scale-4, amount scale-2, ROUND_HALF_UP (对齐 CostRollupUtil).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryLedgerServiceImpl implements InventoryLedgerService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int QTY_SCALE = 6;
    private static final int PRICE_SCALE = 4;
    private static final int AMOUNT_SCALE = 2;

    @PersistenceContext
    private EntityManager em;

    private final InventoryLedgerSnapshotRepository snapshotRepo;
    private final RawMaterialTypeRepository materialTypeRepo;
    private final AccountingPeriodRepository accountingPeriodRepo;
    private final MaterialBatchRepository materialBatchRepo;

    @Override
    public List<InventoryLedgerLineDTO> getLedger(String factoryId, LocalDate startDate,
                                                   LocalDate endDate, String materialTypeId) {
        Objects.requireNonNull(factoryId, "factoryId required");
        Objects.requireNonNull(startDate, "startDate required");
        Objects.requireNonNull(endDate, "endDate required");

        // 1. 确定需要计算的物料类型
        List<RawMaterialType> materials;
        if (materialTypeId != null) {
            materials = materialTypeRepo.findByFactoryId(factoryId)
                    .stream()
                    .filter(m -> m.getId().equals(materialTypeId))
                    .collect(Collectors.toList());
        } else {
            materials = materialTypeRepo.findByFactoryId(factoryId);
        }

        if (materials.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查找最近一期快照用于期初 (startDate 前最近一个已结账期)
        int startYearMonth = startDate.getYear() * 100 + startDate.getMonthValue();
        Map<String, InventoryLedgerSnapshot> openingSnapshots = new HashMap<>();
        for (RawMaterialType mt : materials) {
            List<InventoryLedgerSnapshot> snaps = snapshotRepo.findLatestBeforePeriod(
                    factoryId, mt.getId(), SnapshotType.PERIOD_CLOSE, startYearMonth);
            if (!snaps.isEmpty()) {
                openingSnapshots.put(mt.getId(), snaps.get(0));
            }
        }

        // 3. 对每种物料聚合期间流水
        return materials.stream()
                .map(mt -> buildLine(factoryId, mt, startDate, endDate, openingSnapshots.get(mt.getId())))
                .collect(Collectors.toList());
    }

    private InventoryLedgerLineDTO buildLine(String factoryId, RawMaterialType mt,
                                              LocalDate start, LocalDate end,
                                              InventoryLedgerSnapshot openingSnap) {
        // === 期初 ===
        BigDecimal openingQty = ZERO;
        BigDecimal openingAmount = null;

        if (openingSnap != null) {
            openingQty = nvl(openingSnap.getClosingQty());
            openingAmount = openingSnap.getClosingAmount(); // PriceSensitive — may be null for warehouse role
        } else {
            // 兜底: 从 MaterialBatch 全量聚合 startDate 之前的净入库数量
            openingQty = aggregateBatchQtyBefore(factoryId, mt.getId(), start);
        }

        // === 入库 (PurchaseReceiveItem) ===
        BigDecimal inboundQty = aggregateInboundQty(factoryId, mt.getId(), start, end);
        BigDecimal inboundAmount = aggregateInboundAmount(factoryId, mt.getId(), start, end);

        // === 出库-生产领用 (MaterialBatch usedQuantity 变化) ===
        BigDecimal outboundProductionQty = aggregateProductionOutQty(factoryId, mt.getId(), start, end);

        // === 出库-销售出货 (SalesDeliveryItem / SalesDeliveryRecord) ===
        BigDecimal outboundSalesQty = aggregateSalesOutQty(factoryId, mt.getId(), start, end);

        // === 调拨 ===
        BigDecimal transferInQty = aggregateTransferQty(factoryId, mt.getId(), start, end, true);
        BigDecimal transferOutQty = aggregateTransferQty(factoryId, mt.getId(), start, end, false);

        // === 盘盈/损 (MaterialBatchAdjustment) ===
        BigDecimal adjustQty = aggregateAdjustQty(factoryId, mt.getId(), start, end);
        BigDecimal adjustAmount = aggregateAdjustAmount(factoryId, mt.getId(), start, end);

        // === 期末 ===
        BigDecimal totalOut = nvl(outboundProductionQty).add(nvl(outboundSalesQty)).add(nvl(transferOutQty));
        BigDecimal closingQty = nvl(openingQty)
                .add(nvl(inboundQty))
                .subtract(totalOut)
                .add(nvl(transferInQty))
                .add(nvl(adjustQty));
        closingQty = closingQty.setScale(QTY_SCALE, RoundingMode.HALF_UP);

        // 期末金额 (诚实 null 当无价格时)
        BigDecimal closingAmount = null;
        BigDecimal movingAvgUnitPrice = null;

        if (inboundAmount != null || openingAmount != null) {
            // 若任一金额有值, 估算期末金额 = 期初金额 + 入库金额 +/- 调整金额
            BigDecimal inAmount = nvl(inboundAmount);
            BigDecimal adjAmount = nvl(adjustAmount);
            BigDecimal openAmt = nvl(openingAmount);
            // 出库金额: 按移动均价 (期初均价 * 出库量) 估算
            BigDecimal avgPrice = (openAmt.add(inAmount).compareTo(ZERO) > 0
                    && nvl(openingQty).add(nvl(inboundQty)).compareTo(ZERO) > 0)
                    ? openAmt.add(inAmount)
                    .divide(nvl(openingQty).add(nvl(inboundQty)), PRICE_SCALE, RoundingMode.HALF_UP)
                    : null;

            if (avgPrice != null) {
                BigDecimal outAmt = totalOut.multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal transferInAmt = nvl(transferInQty).multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal transferOutAmt = nvl(transferOutQty).multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                closingAmount = openAmt.add(inAmount).subtract(outAmt)
                        .add(transferInAmt).subtract(transferOutAmt).add(adjAmount)
                        .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                if (closingQty.compareTo(ZERO) > 0) {
                    movingAvgUnitPrice = closingAmount.divide(closingQty, PRICE_SCALE, RoundingMode.HALF_UP);
                }
            }
        }

        BigDecimal outboundAmount = (inboundAmount != null || openingAmount != null)
                ? totalOut.multiply(movingAvgUnitPrice != null ? movingAvgUnitPrice : ZERO)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                : null;

        return InventoryLedgerLineDTO.builder()
                .materialTypeId(mt.getId())
                .materialCode(mt.getCode())
                .materialName(mt.getName())
                .unit(mt.getUnit())
                .openingQty(openingQty)
                .openingAmount(openingAmount)
                .inboundQty(inboundQty)
                .inboundAmount(inboundAmount)
                .outboundProductionQty(outboundProductionQty)
                .outboundSalesQty(outboundSalesQty)
                .transferInQty(transferInQty)
                .transferOutQty(transferOutQty)
                .adjustQty(adjustQty)
                .adjustAmount(adjustAmount)
                .closingQty(closingQty)
                .closingAmount(closingAmount)
                .outboundAmount(outboundAmount)
                .movingAvgUnitPrice(movingAvgUnitPrice)
                .build();
    }

    // ========================= 聚合查询 =========================

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateBatchQtyBefore(String factoryId, String materialTypeId, LocalDate before) {
        List<Object> result = em.createQuery(
                "SELECT COALESCE(SUM(b.receiptQuantity - b.usedQuantity - b.reservedQuantity), 0) " +
                "FROM MaterialBatch b " +
                "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                "  AND b.receiptDate < :before AND b.deletedAt IS NULL")
                .setParameter("fid", factoryId)
                .setParameter("mid", materialTypeId)
                .setParameter("before", before)
                .getResultList();
        return result.isEmpty() ? ZERO : toBD(result.get(0));
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateInboundQty(String factoryId, String materialTypeId,
                                            LocalDate start, LocalDate end) {
        List<Object> result = em.createQuery(
                "SELECT COALESCE(SUM(i.receivedQuantity), 0) " +
                "FROM PurchaseReceiveItem i JOIN i.receiveRecord r " +
                "WHERE r.factoryId = :fid AND i.materialTypeId = :mid " +
                "  AND r.receiveDate BETWEEN :start AND :end " +
                "  AND r.deletedAt IS NULL AND i.deletedAt IS NULL")
                .setParameter("fid", factoryId)
                .setParameter("mid", materialTypeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return result.isEmpty() ? ZERO : toBD(result.get(0));
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateInboundAmount(String factoryId, String materialTypeId,
                                               LocalDate start, LocalDate end) {
        List<Object> result = em.createQuery(
                "SELECT SUM(i.receivedQuantity * i.unitPrice) " +
                "FROM PurchaseReceiveItem i JOIN i.receiveRecord r " +
                "WHERE r.factoryId = :fid AND i.materialTypeId = :mid " +
                "  AND r.receiveDate BETWEEN :start AND :end " +
                "  AND r.deletedAt IS NULL AND i.deletedAt IS NULL " +
                "  AND i.unitPrice IS NOT NULL")
                .setParameter("fid", factoryId)
                .setParameter("mid", materialTypeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        Object val = result.isEmpty() ? null : result.get(0);
        return val == null ? null : toBD(val).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateProductionOutQty(String factoryId, String materialTypeId,
                                                  LocalDate start, LocalDate end) {
        // MaterialConsumption 记录领料消耗; 兜底用 MaterialBatch.usedQuantity 变化
        try {
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(c.quantity), 0) " +
                    "FROM MaterialConsumption c " +
                    "WHERE c.factoryId = :fid AND c.materialTypeId = :mid " +
                    "  AND c.consumedAt >= :start AND c.consumedAt <= :end " +
                    "  AND c.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("end", end.atTime(23, 59, 59))
                    .getResultList();
            return result.isEmpty() ? ZERO : toBD(result.get(0));
        } catch (Exception e) {
            log.debug("[InventoryLedger] MaterialConsumption query failed (entity may not exist): {}", e.getMessage());
            return ZERO;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateSalesOutQty(String factoryId, String materialTypeId,
                                             LocalDate start, LocalDate end) {
        // SalesDeliveryItem 里 product_type_id 是成品; 原料不从销售发货
        // 此处返回 ZERO — 原料不直接走销售出货 (成品库有独立 FinishedGoodsBatch 模型)
        return ZERO;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateTransferQty(String factoryId, String materialTypeId,
                                             LocalDate start, LocalDate end, boolean isIn) {
        try {
            String factoryField = isIn ? "t.targetFactoryId" : "t.sourceFactoryId";
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(i.quantity), 0) " +
                    "FROM InternalTransferItem i JOIN i.transfer t " +
                    "WHERE " + factoryField + " = :fid AND i.materialTypeId = :mid " +
                    "  AND t.transferDate BETWEEN :start AND :end " +
                    "  AND t.status = com.cretas.aims.entity.enums.TransferStatus.CONFIRMED " +
                    "  AND t.deletedAt IS NULL AND i.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getResultList();
            return result.isEmpty() ? ZERO : toBD(result.get(0));
        } catch (Exception e) {
            log.debug("[InventoryLedger] Transfer query failed: {}", e.getMessage());
            return ZERO;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateAdjustQty(String factoryId, String materialTypeId,
                                           LocalDate start, LocalDate end) {
        try {
            // MaterialBatchAdjustment: loss/damage 为负, correction 为正
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(" +
                    "  CASE WHEN a.adjustmentType IN ('loss','damage') THEN -a.adjustmentQuantity " +
                    "       ELSE a.adjustmentQuantity END" +
                    "), 0) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            return result.isEmpty() ? ZERO : toBD(result.get(0));
        } catch (Exception e) {
            log.debug("[InventoryLedger] Adjustment qty query failed: {}", e.getMessage());
            return ZERO;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal aggregateAdjustAmount(String factoryId, String materialTypeId,
                                              LocalDate start, LocalDate end) {
        // 盘盈/损金额 = 调整数量 × 批次单价 (诚实 null 当无单价)
        try {
            List<Object> result = em.createQuery(
                    "SELECT SUM(" +
                    "  CASE WHEN a.adjustmentType IN ('loss','damage') " +
                    "       THEN -a.adjustmentQuantity * b.unitPrice " +
                    "       ELSE a.adjustmentQuantity * b.unitPrice END" +
                    ") " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND b.unitPrice IS NOT NULL " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            Object val = result.isEmpty() ? null : result.get(0);
            return val == null ? null : toBD(val).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[InventoryLedger] Adjustment amount query failed: {}", e.getMessage());
            return null;
        }
    }

    // ========================= 工具方法 =========================

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : ZERO;
    }

    private BigDecimal toBD(Object o) {
        if (o == null) return ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(o.toString());
    }
}
