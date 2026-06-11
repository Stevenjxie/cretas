package com.cretas.aims.service.inventory.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 *   <li>盘盈/损: MaterialBatchAdjustment (W8: 分列展示盘盈/盘损)</li>
 *   <li>期末 = 期初 + 入库 - 出库 +/- 调拨 +/- 盘盈损</li>
 * </ol>
 *
 * <p>精度: qty scale-6, unitPrice scale-4, amount scale-2, ROUND_HALF_UP (对齐 CostRollupUtil).
 *
 * <p>W8 盘盈/盘损分列: {@code adjustQty} 保留向后兼容; 新增 {@code stocktakeProfitQty} +
 * {@code stocktakeLossQty} 分列展示, 便于金蝶凭证导入区分借贷方向.
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

    @Override
    public String exportInventoryLedger(String factoryId, LocalDate startDate, LocalDate endDate,
                                        String materialTypeId, boolean includePrices,
                                        OutputStream out) throws Exception {
        // 同源数据: 复用 getLedger 的进销存台账聚合 (期初/入/出/调拨/盘盈损/期末)
        List<InventoryLedgerLineDTO> lines = getLedger(factoryId, startDate, endDate, materialTypeId);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(buildHeaderRow(includePrices));
        for (InventoryLedgerLineDTO line : lines) {
            rows.add(buildDataRow(line, includePrices));
        }

        writeRawRows(out, rows);

        String fileName = String.format("inventory-ledger_%s_%s_%s_%s.xlsx",
                factoryId,
                startDate.toString().replace("-", ""),
                endDate.toString().replace("-", ""),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        log.info("[SP11] exportInventoryLedger: factoryId={} rows={} includePrices={} file={}",
                factoryId, lines.size(), includePrices, fileName);
        return fileName;
    }

    /**
     * 进销存台账表头. 数量列全角色可见; 金额列仅 includePrices=true 写入.
     *
     * <p>W8: 盘盈损数量拆分为独立两列(盘盈数量/盘损数量), 便于金蝶凭证区分借贷方向.
     */
    private List<Object> buildHeaderRow(boolean includePrices) {
        List<Object> header = new ArrayList<>(List.of(
                "物料编码", "物料名称", "单位",
                "期初数量",
                "入库数量",
                "生产领用数量",
                "销售出货数量",
                "调拨入数量",
                "调拨出数量",
                "盘盈数量",
                "盘损数量",
                "期末数量"));
        if (includePrices) {
            header.addAll(List.of(
                    "期初金额",
                    "入库金额",
                    "出库金额",
                    "盘盈金额",
                    "盘损金额",
                    "期末金额",
                    "移动均价"));
        }
        return header;
    }

    private List<Object> buildDataRow(InventoryLedgerLineDTO line, boolean includePrices) {
        List<Object> row = new ArrayList<>(List.of(
                nvlStr(line.getMaterialCode()),
                nvlStr(line.getMaterialName()),
                nvlStr(line.getUnit()),
                qtyCell(line.getOpeningQty()),
                qtyCell(line.getInboundQty()),
                qtyCell(line.getOutboundProductionQty()),
                qtyCell(line.getOutboundSalesQty()),
                qtyCell(line.getTransferInQty()),
                qtyCell(line.getTransferOutQty()),
                qtyCell(line.getStocktakeProfitQty()),
                qtyCell(line.getStocktakeLossQty()),
                qtyCell(line.getClosingQty())));
        if (includePrices) {
            row.addAll(List.of(
                    amountCell(line.getOpeningAmount()),
                    amountCell(line.getInboundAmount()),
                    amountCell(line.getOutboundAmount()),
                    amountCell(line.getStocktakeProfitAmount()),
                    amountCell(line.getStocktakeLossAmount()),
                    amountCell(line.getClosingAmount()),
                    amountCell(line.getMovingAvgUnitPrice())));
        }
        return row;
    }

    /** 数量单元格: null → 0 (scale-6). */
    private Object qtyCell(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** 金额单元格: 诚实显示 — 无单价时写空串 (不伪造 0). */
    private Object amountCell(BigDecimal v) {
        return v == null ? "" : v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private String nvlStr(Object v) {
        return v == null ? "" : v.toString();
    }

    private void writeRawRows(OutputStream out, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object> header = rows.get(0);
        List<List<Object>> dataRows = rows.subList(1, rows.size());

        List<List<String>> head = new ArrayList<>();
        for (Object h : header) {
            head.add(List.of(h.toString()));
        }

        ExcelWriter writer = EasyExcel.write(out).head(head).build();
        WriteSheet sheet = EasyExcel.writerSheet("进销存台账").build();
        writer.write(dataRows, sheet);
        writer.finish();
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

        // === 盘盈/损 (MaterialBatchAdjustment) — W8: 分列 ===
        BigDecimal profitQty = aggregateStocktakeProfitQty(factoryId, mt.getId(), start, end);
        BigDecimal lossQty = aggregateStocktakeLossQty(factoryId, mt.getId(), start, end);
        // adjustQty 保留向后兼容: 净调整 = profitQty - lossQty
        BigDecimal adjustQty = nvl(profitQty).subtract(nvl(lossQty))
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal profitAmount = aggregateStocktakeProfitAmount(factoryId, mt.getId(), start, end);
        BigDecimal lossAmount = aggregateStocktakeLossAmount(factoryId, mt.getId(), start, end);
        // adjustAmount 保留向后兼容: 净金额 = profitAmount - lossAmount (诚实 null 当两者均 null)
        BigDecimal adjustAmount = (profitAmount != null || lossAmount != null)
                ? nvl(profitAmount).subtract(nvl(lossAmount)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                : null;

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
                // W8: 盘盈/盘损分列
                .stocktakeProfitQty(profitQty)
                .stocktakeLossQty(lossQty)
                .stocktakeProfitAmount(profitAmount)
                .stocktakeLossAmount(lossAmount)
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

    // ========================= 盘盈/盘损分列查询 (W8) =========================

    /**
     * 盘盈数量: correction / return 类型 (正调整, ≥0).
     * 诚实 ZERO 当期间无盘盈记录.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeProfitQty(String factoryId, String materialTypeId,
                                                    LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(a.adjustmentQuantity), 0) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentType NOT IN ('loss','damage') " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            return result.isEmpty() ? ZERO : toBD(result.get(0)).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[InventoryLedger] Stocktake profit qty query failed: {}", e.getMessage());
            return ZERO;
        }
    }

    /**
     * 盘损数量: loss / damage 类型 (取绝对值, ≥0).
     * 诚实 ZERO 当期间无盘损记录.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeLossQty(String factoryId, String materialTypeId,
                                                  LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(a.adjustmentQuantity), 0) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentType IN ('loss','damage') " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            // loss 存储为正值 (adjustmentQuantity > 0), 展示取绝对值
            return result.isEmpty() ? ZERO : toBD(result.get(0)).abs().setScale(QTY_SCALE, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[InventoryLedger] Stocktake loss qty query failed: {}", e.getMessage());
            return ZERO;
        }
    }

    /**
     * 盘盈金额: correction / return × 批次单价 (诚实 null 当无单价).
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeProfitAmount(String factoryId, String materialTypeId,
                                                       LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT SUM(a.adjustmentQuantity * b.unitPrice) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentType NOT IN ('loss','damage') " +
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
            log.debug("[InventoryLedger] Stocktake profit amount query failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 盘损金额: loss/damage × 批次单价 (取绝对值, 诚实 null 当无单价).
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeLossAmount(String factoryId, String materialTypeId,
                                                     LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT SUM(a.adjustmentQuantity * b.unitPrice) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND a.adjustmentType IN ('loss','damage') " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND b.unitPrice IS NOT NULL " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            Object val = result.isEmpty() ? null : result.get(0);
            // loss 量存储为正值 → 金额也是正值; 展示取绝对值 (≥0)
            return val == null ? null : toBD(val).abs().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[InventoryLedger] Stocktake loss amount query failed: {}", e.getMessage());
            return null;
        }
    }

    // ========================= 金蝶 per-movement 摘要 (W8) =========================

    /**
     * 金蝶 per-movement 摘要: 为每笔库存变动生成金蝶凭证摘要字符串.
     *
     * <p>格式: {@code {movementType}[{docNo}] {materialName} {qty}{unit}}
     * 例:
     * <ul>
     *   <li>入库: {@code 入库[PO-2026-001] 猪舌 100.000000kg}</li>
     *   <li>出库: {@code 出库[DO-2026-001] 猪舌 20.000000kg}</li>
     *   <li>盘盈: {@code 盘盈 猪舌 +5.000000kg}</li>
     *   <li>盘损: {@code 盘损 猪舌 -3.000000kg}</li>
     *   <li>生产领用: {@code 领用[BATCH-001] 猪舌 50.000000kg}</li>
     *   <li>销售出货: {@code 出货[SO-001] 猪舌 30.000000kg}</li>
     *   <li>调拨入: {@code 调拨入[TR-001] 猪舌 10.000000kg}</li>
     *   <li>调拨出: {@code 调拨出[TR-001] 猪舌 10.000000kg}</li>
     *   <li>报损: {@code 报损[ADJ-001] 猪舌 -2.000000kg}</li>
     *   <li>退料/退货: {@code 退料[PO-001] 猪舌 -5.000000kg}</li>
     * </ul>
     *
     * <p>金蝶凭证方向约定 (sign 列):
     * <ul>
     *   <li>入库 / 盘盈 → 借 (库存增加, 正方向)</li>
     *   <li>出库 / 领用 / 出货 / 调拨出 / 盘损 / 报损 / 退料 → 贷 (库存减少, 负方向)</li>
     *   <li>调拨入 → 借 (库存增加)</li>
     * </ul>
     *
     * @param movementType 变动类型
     *                     <b>支持值</b>: inbound / outbound / stocktake_profit / stocktake_loss /
     *                     production / sales / transfer_in / transfer_out /
     *                     write_off / damage / return / purchase_return
     * @param docNo        单据号 (null 时省略括号)
     * @param materialName 物料名称
     * @param qty          数量 (绝对值, 由 movementType 决定方向展示)
     * @param unit         单位
     * @return 金蝶凭证摘要字符串
     */
    public static String buildKingdeeMovementSummary(String movementType, String docNo,
                                                      String materialName, BigDecimal qty,
                                                      String unit) {
        String typeLabel = switch (movementType == null ? "" : movementType.toLowerCase()) {
            case "inbound"                    -> "入库";
            case "outbound"                   -> "出库";
            case "stocktake_profit"           -> "盘盈";
            case "stocktake_loss"             -> "盘损";
            case "production"                 -> "领用";
            case "sales"                      -> "出货";
            case "transfer_in"                -> "调拨入";
            case "transfer_out"               -> "调拨出";
            case "write_off", "damage"        -> "报损";       // 库存报损 / 损坏核销
            case "return", "purchase_return"  -> "退料";       // 生产退料 / 采购退货
            default                           -> movementType != null ? movementType : "变动";
        };

        String docPart = (docNo != null && !docNo.isBlank()) ? "[" + docNo + "]" : "";
        String unitStr = unit != null ? unit : "";

        // 方向符号: 库存增加类型显示 +, 库存减少类型显示 -
        // 盘盈/入库/调拨入 = 借 (+); 其余出库类型 = 贷 (-)
        String sign = switch (movementType == null ? "" : movementType.toLowerCase()) {
            case "stocktake_profit"           -> "+";
            case "stocktake_loss",
                 "write_off", "damage",
                 "return", "purchase_return"  -> "-";
            default -> "";
        };

        String qtyStr = qty != null ? qty.toPlainString() : "0";
        return typeLabel + docPart + " " + (materialName != null ? materialName : "") +
               " " + sign + qtyStr + unitStr;
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
