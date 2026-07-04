package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.CostCarryoverSummary;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.service.finance.CostCarryoverService;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 结转成本 (期末 COGS 权责化) 实现。见 {@link CostCarryoverService}。
 *
 * <p>借 6401 主营业务成本 / 贷 1405 库存商品 = Σ 期内已发货成品 (发货数量 × 批次 unitCost)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostCarryoverServiceImpl implements CostCarryoverService {

    private final SalesDeliveryItemRepository deliveryItemRepo;
    private final VoucherService voucherService;
    private final VoucherRepository voucherRepo;

    private static final String COGS_CODE = "6401";
    private static final String COGS_NAME = "主营业务成本";
    private static final String INVENTORY_CODE = "1405";
    private static final String INVENTORY_NAME = "库存商品";
    private static final String SOURCE_TYPE = "COST_CARRYOVER";

    @Override
    @Transactional
    public CostCarryoverSummary carryCost(String factoryId, int year, int month, Long userId) {
        LocalDate start = YearMonth.of(year, month).atDay(1);
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();

        Object[] costed = deliveryItemRepo.aggregateShippedCogs(factoryId, start, end);
        BigDecimal cogs = toDecimal(costed, 0).setScale(2, RoundingMode.HALF_UP);
        long costedCount = toLong(costed, 2);

        Object[] missing = deliveryItemRepo.aggregateMissingCost(factoryId, start, end);
        BigDecimal missingQty = toDecimal(missing, 0);
        long missingCount = toLong(missing, 1);

        if (missingCount > 0) {
            // 诚实 null: 绝不按 ¥0 伪造 — 暴露笔数让财务追成本传导缺口。
            log.warn("[CostCarryover] {}-{}-{} ⚠️ {} 笔已发货成品无单位成本、未结转 COGS (数量合计={}) — honest-null 排除",
                    factoryId, year, month, missingCount, missingQty);
        }

        if (cogs.signum() <= 0) {
            log.info("[CostCarryover] {}-{}-{} 无成本可结转 (COGS={}), no-op (未结转笔数={})",
                    factoryId, year, month, cogs, missingCount);
            return new CostCarryoverSummary(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    costedCount, missingQty, missingCount);
        }

        // 借 6401 主营业务成本 / 贷 1405 库存商品 (内部自平, 同一 cogs 值)。
        List<VoucherEntrySpec> entries = List.of(
                new VoucherEntrySpec(COGS_CODE, COGS_NAME, cogs, null, "结转销售成本"),
                new VoucherEntrySpec(INVENTORY_CODE, INVENTORY_NAME, null, cogs, "结转销售成本-库存转出"));

        int revision = currentRevision(factoryId, year, month);
        String sourceId = String.format("cost-%s-%d-%d-cogs-r%d", factoryId, year, month, revision);
        voucherService.createManual(factoryId, VoucherType.COST_CARRYOVER, end, entries,
                SOURCE_TYPE, sourceId, String.format("%d-%02d 结转销售成本", year, month), userId);

        log.info("[CostCarryover] {}-{}-{} 结转完成: COGS=¥{} ({} 笔), 未结转={} 笔 (rev{})",
                factoryId, year, month, cogs, costedCount, missingCount, revision);
        return new CostCarryoverSummary(cogs, costedCount, missingQty, missingCount);
    }

    @Override
    @Transactional
    public void reverseCostCarryover(String factoryId, int year, int month, Long userId) {
        String prefix = String.format("cost-%s-%d-%d-cogs-r%%", factoryId, year, month);
        List<Voucher> active = voucherRepo.findActiveCostCarryoverVouchers(factoryId, prefix);
        if (active.isEmpty()) {
            log.info("[CostCarryover] {}-{}-{} 无 active 结转成本凭证, 反冲 no-op", factoryId, year, month);
            return;
        }
        for (Voucher v : active) {
            voucherService.voidVoucher(factoryId, v.getId(), "反结账自动红冲(结转成本)", userId);
        }
        log.info("[CostCarryover] {}-{}-{} 反冲 {} 张结转成本凭证", factoryId, year, month, active.size());
    }

    /** rev = 该期历史结转成本批次数 (含已 REVERSED, 排红冲镜像), 用于 source_business_id 避免撞键。 */
    private int currentRevision(String factoryId, int year, int month) {
        String prefix = String.format("cost-%s-%d-%d-cogs-r%%", factoryId, year, month);
        return (int) voucherRepo.countCostCarryoverBatches(factoryId, prefix);
    }

    private static BigDecimal toDecimal(Object[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return BigDecimal.ZERO;
        Object v = row[idx];
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    private static long toLong(Object[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return 0L;
        Object v = row[idx];
        return (v instanceof Number n) ? n.longValue() : 0L;
    }
}
