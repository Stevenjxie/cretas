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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SP11: 进销存台账服务实现.
 *
 * <p>数据来源:
 * <ol>
 *   <li>期初: {@link InventoryLedgerSnapshot} (月结快照) 滚算至 startDate 前一天 — 若快照所在
 *       期与查询起点之间存在未结账缺口(缺口月无快照), 用同口径流水聚合把缺口补齐, 不直接
 *       透传"最近一次已结账快照"这个可能过期的值(见 {@link #resolveOpening} 2026-07 修复:
 *       之前 findLatestBeforePeriod 只按 year*100+month 找"≤ 查询月的最近快照", 若紧邻月未
 *       结账会静默跳过继续往前找更老的快照, 造成期初虚高/虚低且无任何提示); 若无任何快照则从
 *       MaterialBatch 全量聚合兜底</li>
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

    // ==================== P11: 按物料大类(原料/辅料/包材)筛选 ====================

    @Override
    public List<InventoryLedgerLineDTO> getLedgerByKind(String factoryId, LocalDate startDate,
                                                         LocalDate endDate, String materialKind) {
        Objects.requireNonNull(factoryId, "factoryId required");
        Objects.requireNonNull(startDate, "startDate required");
        Objects.requireNonNull(endDate, "endDate required");

        // 1. 按 category 字段过滤物料 (null/空 = 全部, 不区分大小写)
        List<RawMaterialType> materials = materialTypeRepo.findByFactoryId(factoryId);
        if (materialKind != null && !materialKind.isBlank()) {
            final String kindFilter = materialKind.trim();
            materials = materials.stream()
                    .filter(m -> kindFilter.equalsIgnoreCase(m.getCategory()))
                    .collect(Collectors.toList());
        }

        if (materials.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查找期初快照
        int startYearMonth = startDate.getYear() * 100 + startDate.getMonthValue();
        Map<String, InventoryLedgerSnapshot> openingSnapshots = new HashMap<>();
        for (RawMaterialType mt : materials) {
            List<InventoryLedgerSnapshot> snaps = snapshotRepo.findLatestBeforePeriod(
                    factoryId, mt.getId(), SnapshotType.PERIOD_CLOSE, startYearMonth);
            if (!snaps.isEmpty()) {
                openingSnapshots.put(mt.getId(), snaps.get(0));
            }
        }

        // 3. 聚合期间流水
        return materials.stream()
                .map(mt -> buildLine(factoryId, mt, startDate, endDate, openingSnapshots.get(mt.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public String exportInventoryLedgerByKind(String factoryId, LocalDate startDate, LocalDate endDate,
                                               String materialKind, boolean includePrices,
                                               OutputStream out) throws Exception {
        List<InventoryLedgerLineDTO> lines = getLedgerByKind(factoryId, startDate, endDate, materialKind);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(buildHeaderRow(includePrices));
        for (InventoryLedgerLineDTO line : lines) {
            rows.add(buildDataRow(line, includePrices));
        }

        writeRawRows(out, rows);

        String kindSuffix = (materialKind != null && !materialKind.isBlank()) ? "_" + materialKind : "";
        String fileName = String.format("inventory-ledger%s_%s_%s_%s_%s.xlsx",
                kindSuffix, factoryId,
                startDate.toString().replace("-", ""),
                endDate.toString().replace("-", ""),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        log.info("[P11] exportInventoryLedgerByKind: factoryId={} materialKind={} rows={} file={}",
                factoryId, materialKind, lines.size(), fileName);
        return fileName;
    }

    // ==================== End P11 ====================

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
     * <p>#730 SP12: includePrices=true 时追加「金蝶凭证摘要参考」列,
     *   由 {@link #buildKingdeeMovementSummary} 生成各变动类型的参考摘要字符串 (用于金蝶手工对账).
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
                    "移动均价",
                    // #730 SP12: 金蝶凭证摘要参考列 (入库/领用/出货/调拨入/调拨出/盘盈/盘损)
                    "金蝶凭证摘要参考"));
        }
        return header;
    }

    /**
     * #730 SP12: 为进销存台账行生成金蝶凭证摘要参考字符串.
     * 将本期各类变动汇总成一行可粘贴到金蝶摘要字段的文本, 方便财务手工对账.
     */
    private String buildKingdeeReferenceSummary(InventoryLedgerLineDTO line) {
        List<String> parts = new ArrayList<>();
        String name = nvlStr(line.getMaterialName());
        String unit = nvlStr(line.getUnit());
        if (line.getInboundQty() != null && line.getInboundQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("inbound", null, name, line.getInboundQty(), unit));
        }
        if (line.getOutboundProductionQty() != null && line.getOutboundProductionQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("production", null, name, line.getOutboundProductionQty(), unit));
        }
        if (line.getOutboundSalesQty() != null && line.getOutboundSalesQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("sales", null, name, line.getOutboundSalesQty(), unit));
        }
        if (line.getTransferInQty() != null && line.getTransferInQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("transfer_in", null, name, line.getTransferInQty(), unit));
        }
        if (line.getTransferOutQty() != null && line.getTransferOutQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("transfer_out", null, name, line.getTransferOutQty(), unit));
        }
        if (line.getStocktakeProfitQty() != null && line.getStocktakeProfitQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("stocktake_profit", null, name, line.getStocktakeProfitQty(), unit));
        }
        if (line.getStocktakeLossQty() != null && line.getStocktakeLossQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            parts.add(buildKingdeeMovementSummary("stocktake_loss", null, name, line.getStocktakeLossQty(), unit));
        }
        return parts.isEmpty() ? "" : String.join("; ", parts);
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
                    amountCell(line.getMovingAvgUnitPrice()),
                    // #730 SP12: 金蝶凭证摘要参考 (buildKingdeeMovementSummary 汇总)
                    buildKingdeeReferenceSummary(line)));
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
        // === 期初 (2026-07 修复: 滚算至 start 前一天真实结存, 不透传可能过期的快照) ===
        OpeningBalance opening = resolveOpening(factoryId, mt.getId(), start, openingSnap);
        BigDecimal openingQty = opening.qty();
        BigDecimal openingAmount = opening.amount(); // PriceSensitive — may be null for warehouse role

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

    // ========================= 期初解析 (2026-07 修复: 滚算至 startDate 前一天) =========================

    /** 期初结存 (数量 + 金额, 金额诚实 null 当无法计算价格时). */
    private record OpeningBalance(BigDecimal qty, BigDecimal amount) {
    }

    /**
     * 解析期初结存 = startDate 前一天(含)的真实库存状态.
     *
     * <p><b>2026-07 修复背景</b>: 修复前直接把 {@code findLatestBeforePeriod} 找到的"最近一次
     * 已结账快照"当期初, 但该查询只按 {@code year*100+month < 查询月} 匹配, 不校验快照是不是
     * "紧邻查询起点的上一期". 若紧邻的上一期还没结账(常态 — 大多数查询发生在当月未结账时),
     * 会静默跳过继续往前找更老的快照, 导致期初把中间所有未结账期的入/出/调拨/盘点流水全部
     * 丢失(F006 DDY002 实测: 2026-07-01~03 查询期初错误回退到 05-31 快照 2009.12, 真实应为
     * 06-30 结存 1166.39, 虚高 72%). 同样地, 即使快照就是紧邻上一期, 若查询起点不是月初(如
     * 06-15), 也需要把该月 06-01~06-14 的流水补进期初, 否则漏计"半个月"的流水.
     *
     * <p>修复: 快照期末(period end) 到 startDate 前一天之间若存在缺口(含部分月), 用 buildLine
     * 同一套流水聚合方法(入库/生产领用/销售出货/调拨/盘盈损)把缺口滚算进期初, 而不是静默透传
     * 一个可能已经过期的数字. 若期间记录本身缺失(数据异常), 诚实退回原始快照值(不新增虚假精度),
     * 而不是抛异常阻断整张报表.
     */
    private OpeningBalance resolveOpening(String factoryId, String materialTypeId, LocalDate start,
                                           InventoryLedgerSnapshot openingSnap) {
        if (openingSnap == null) {
            // 兜底: 从 MaterialBatch 全量聚合 startDate 之前的净入库数量 (无快照时唯一数据源,
            // 已经是 as-of-start 的诚实聚合, 不需要额外滚算)
            return new OpeningBalance(aggregateBatchQtyBefore(factoryId, materialTypeId, start), null);
        }

        LocalDate periodEnd = resolveSnapshotPeriodEnd(openingSnap);
        if (periodEnd == null) {
            // 防御: 快照关联的 AccountingPeriod 找不到(测试桩或数据异常) — 无法校验缺口,
            // 退回快照原始值(不是新 bug, 是维持旧行为而非抛异常阻断整张报表)
            log.warn("[InventoryLedger] AccountingPeriod {} not found for snapshot {} (material={}) "
                            + "— cannot verify opening-balance gap, falling back to raw snapshot value",
                    openingSnap.getAccountingPeriodId(), openingSnap.getId(), materialTypeId);
            return new OpeningBalance(nvl(openingSnap.getClosingQty()), openingSnap.getClosingAmount());
        }

        LocalDate gapStart = periodEnd.plusDays(1);
        LocalDate gapEnd = start.minusDays(1);
        if (gapStart.isAfter(gapEnd)) {
            // 快照期末紧邻查询起点 (下期第 1 天开始查询) — 无缺口, 快照值即真实期初
            return new OpeningBalance(nvl(openingSnap.getClosingQty()), openingSnap.getClosingAmount());
        }

        // 缺口存在 (跨未结账月 和/或 查询起点非月初) — 用同口径流水聚合滚算补齐
        return rollForwardOpening(factoryId, materialTypeId,
                nvl(openingSnap.getClosingQty()), openingSnap.getClosingAmount(), gapStart, gapEnd);
    }

    /** 快照所属 AccountingPeriod 的期末日期 (该月最后一天); 期间记录缺失时返回 null. */
    private LocalDate resolveSnapshotPeriodEnd(InventoryLedgerSnapshot snap) {
        if (snap.getAccountingPeriodId() == null) {
            return null;
        }
        return accountingPeriodRepo.findById(snap.getAccountingPeriodId())
                .map(p -> YearMonth.of(p.getYear(), p.getMonth()).atEndOfMonth())
                .orElse(null);
    }

    /**
     * 把 [gapStart, gapEnd] 区间的流水滚算进 baseQty/baseAmount, 算出滚算后的结存.
     * 复用与 {@link #buildLine} 完全相同的聚合口径与精度规则(scale/HALF_UP), 保证
     * "期初(滚算) + 本期流水 = 期末" 的算术恒等式对任意查询窗口都成立.
     */
    private OpeningBalance rollForwardOpening(String factoryId, String materialTypeId,
                                               BigDecimal baseQty, BigDecimal baseAmount,
                                               LocalDate gapStart, LocalDate gapEnd) {
        BigDecimal inboundQty = aggregateInboundQty(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal inboundAmount = aggregateInboundAmount(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal outProdQty = aggregateProductionOutQty(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal outSalesQty = aggregateSalesOutQty(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal transferInQty = aggregateTransferQty(factoryId, materialTypeId, gapStart, gapEnd, true);
        BigDecimal transferOutQty = aggregateTransferQty(factoryId, materialTypeId, gapStart, gapEnd, false);
        BigDecimal profitQty = aggregateStocktakeProfitQty(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal lossQty = aggregateStocktakeLossQty(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal profitAmount = aggregateStocktakeProfitAmount(factoryId, materialTypeId, gapStart, gapEnd);
        BigDecimal lossAmount = aggregateStocktakeLossAmount(factoryId, materialTypeId, gapStart, gapEnd);

        BigDecimal totalOut = nvl(outProdQty).add(nvl(outSalesQty)).add(nvl(transferOutQty));
        BigDecimal adjustQty = nvl(profitQty).subtract(nvl(lossQty));
        BigDecimal newQty = nvl(baseQty).add(nvl(inboundQty)).subtract(totalOut)
                .add(nvl(transferInQty)).add(adjustQty)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);

        BigDecimal newAmount = null;
        if (inboundAmount != null || baseAmount != null) {
            BigDecimal inAmt = nvl(inboundAmount);
            BigDecimal baseAmt = nvl(baseAmount);
            BigDecimal adjAmt = (profitAmount != null || lossAmount != null)
                    ? nvl(profitAmount).subtract(nvl(lossAmount))
                    : ZERO;
            // 移动均价估算 (同 buildLine 出库金额估算口径): (期初金额+入库金额) / (期初数量+入库数量)
            BigDecimal avgPrice = (baseAmt.add(inAmt).compareTo(ZERO) > 0
                    && nvl(baseQty).add(nvl(inboundQty)).compareTo(ZERO) > 0)
                    ? baseAmt.add(inAmt)
                    .divide(nvl(baseQty).add(nvl(inboundQty)), PRICE_SCALE, RoundingMode.HALF_UP)
                    : null;
            if (avgPrice != null) {
                BigDecimal outAmt = totalOut.multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal transferInAmt = nvl(transferInQty).multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal transferOutAmt = nvl(transferOutQty).multiply(avgPrice).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                newAmount = baseAmt.add(inAmt).subtract(outAmt)
                        .add(transferInAmt).subtract(transferOutAmt).add(adjAmt)
                        .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            }
            // avgPrice == null (base+inbound 均为 0 或数量为 0): 诚实 null, 不伪造金额
        }
        return new OpeningBalance(newQty, newAmount);
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
     * 盘盈数量: correction / return / STOCKTAKE 等非 loss/damage 类型 **且实际调整量为正** (≥0).
     * 诚实 ZERO 当期间无盘盈记录.
     *
     * <p>2026-07 修复: 之前只按 {@code adjustmentType NOT IN ('loss','damage')} 分桶, 未校验
     * 符号. {@link com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl} 的盘点
     * 生效路径把 {@code adjustmentType="STOCKTAKE"} 且 {@code adjustmentQuantity=差异值}(带符号,
     * 盘亏为负) 写入同一张表 — 负差异会被静默计入"盘盈"桶, 实测 F006 DZT001 出现
     * {@code stocktakeProfitQty=-0.02} 违反"盘盈≥0"不变式. 加 {@code adjustmentQuantity > 0}
     * 守卫, 负值调整量归入 {@link #aggregateStocktakeLossQty} (按符号而非仅按类型分桶).
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
                    "  AND a.adjustmentQuantity > 0 " +
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
     * 盘损数量: loss / damage 类型 (任意符号) **或** 其他类型但调整量为负 (盘亏) — 逐行取绝对值
     * 后求和, 取绝对值 (≥0). 诚实 ZERO 当期间无盘损记录.
     *
     * <p>2026-07 修复: 加 {@code OR a.adjustmentQuantity < 0} 分支, 承接 STOCKTAKE 等类型的负
     * 差异(见 {@link #aggregateStocktakeProfitQty} 注释); 并改为逐行 {@code ABS()} 后再
     * {@code SUM}(而非先 SUM 再对总和取绝对值), 避免同期正负混合调整互相抵消导致金额偏小.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeLossQty(String factoryId, String materialTypeId,
                                                  LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT COALESCE(SUM(ABS(a.adjustmentQuantity)), 0) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND (a.adjustmentType IN ('loss','damage') OR a.adjustmentQuantity < 0) " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            return result.isEmpty() ? ZERO : toBD(result.get(0)).abs().setScale(QTY_SCALE, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[InventoryLedger] Stocktake loss qty query failed: {}", e.getMessage());
            return ZERO;
        }
    }

    /**
     * 盘盈金额: correction / return / STOCKTAKE(正差异) × 批次单价 (诚实 null 当无单价).
     * 2026-07 修复: 同 {@link #aggregateStocktakeProfitQty} 加 {@code adjustmentQuantity > 0} 守卫.
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
                    "  AND a.adjustmentQuantity > 0 " +
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
     * 盘损金额: loss/damage 类型 或 负差异 × 批次单价, 逐行取绝对值后求和 (≥0, 诚实 null 当无单价).
     * 2026-07 修复: 同 {@link #aggregateStocktakeLossQty} 加 {@code OR adjustmentQuantity < 0} 分支
     * + 逐行 ABS 求和(避免同期正负混合抵消).
     */
    @SuppressWarnings("unchecked")
    private BigDecimal aggregateStocktakeLossAmount(String factoryId, String materialTypeId,
                                                     LocalDate start, LocalDate end) {
        try {
            List<Object> result = em.createQuery(
                    "SELECT SUM(ABS(a.adjustmentQuantity) * b.unitPrice) " +
                    "FROM MaterialBatchAdjustment a JOIN a.batch b " +
                    "WHERE b.factoryId = :fid AND b.materialTypeId = :mid " +
                    "  AND (a.adjustmentType IN ('loss','damage') OR a.adjustmentQuantity < 0) " +
                    "  AND a.adjustmentTime >= :start AND a.adjustmentTime < :endPlus1 " +
                    "  AND b.unitPrice IS NOT NULL " +
                    "  AND a.deletedAt IS NULL AND b.deletedAt IS NULL")
                    .setParameter("fid", factoryId)
                    .setParameter("mid", materialTypeId)
                    .setParameter("start", start.atStartOfDay())
                    .setParameter("endPlus1", end.plusDays(1).atStartOfDay())
                    .getResultList();
            Object val = result.isEmpty() ? null : result.get(0);
            // ABS() 已在 SQL 层逐行取绝对值; Java 侧 .abs() 作双重防御 (理论上 unitPrice 若为负会破坏此不变式)
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
