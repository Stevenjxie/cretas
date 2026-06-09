package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.repository.AccountingPeriodRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import com.cretas.aims.service.inventory.InventoryLedgerSnapshotService;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * SP11: 进销存期末快照服务实现.
 *
 * <p>在 {@link com.cretas.aims.service.impl.AccountingPeriodServiceImpl#confirmClose}
 * 完成结账后调用, 将当期期末结存写入 {@code inventory_ledger_snapshots}.
 *
 * <p>幂等: 同 factoryId + accountingPeriodId + materialTypeId 唯一键, ON CONFLICT 时覆盖.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryLedgerSnapshotServiceImpl implements InventoryLedgerSnapshotService {

    private final InventoryLedgerSnapshotRepository snapshotRepo;
    private final AccountingPeriodRepository accountingPeriodRepo;
    private final RawMaterialTypeRepository materialTypeRepo;
    private final InventoryLedgerService ledgerService;

    @Override
    @Transactional
    public void createForPeriod(String factoryId, String accountingPeriodId) {
        // 1. 取会计期
        AccountingPeriod period = accountingPeriodRepo.findById(accountingPeriodId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccountingPeriod not found: " + accountingPeriodId));

        if (!period.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException(
                    "AccountingPeriod " + accountingPeriodId + " 不属于工厂 " + factoryId);
        }

        // 2. 计算期间范围
        YearMonth ym = YearMonth.of(period.getYear(), period.getMonth());
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();

        // 3. 调用台账服务获取期末数据
        List<InventoryLedgerLineDTO> lines = ledgerService.getLedger(
                factoryId, periodStart, periodEnd, null);

        int upsertCount = 0;
        for (InventoryLedgerLineDTO line : lines) {
            if (line.getClosingQty() == null && BigDecimal.ZERO.compareTo(
                    line.getClosingQty() != null ? line.getClosingQty() : BigDecimal.ZERO) == 0) {
                continue; // 跳过期末为零且无金额的行 (减少噪声快照)
            }

            // 幂等 upsert: 查 existing
            List<InventoryLedgerSnapshot> existing = snapshotRepo.findLatestBeforePeriod(
                    factoryId, line.getMaterialTypeId(), SnapshotType.PERIOD_CLOSE,
                    period.getYear() * 100 + period.getMonth() + 1); // 查本期 = 下一月前的最近快照
            // 找到本期快照 (同 accountingPeriodId)
            InventoryLedgerSnapshot snap = existing.stream()
                    .filter(s -> accountingPeriodId.equals(s.getAccountingPeriodId()))
                    .findFirst()
                    .orElseGet(() -> {
                        InventoryLedgerSnapshot s = new InventoryLedgerSnapshot();
                        s.setId(UUID.randomUUID().toString());
                        s.setFactoryId(factoryId);
                        s.setAccountingPeriodId(accountingPeriodId);
                        s.setMaterialTypeId(line.getMaterialTypeId());
                        s.setSnapshotType(SnapshotType.PERIOD_CLOSE);
                        return s;
                    });

            snap.setMaterialCode(line.getMaterialCode());
            snap.setMaterialName(line.getMaterialName());
            snap.setUnit(line.getUnit());
            snap.setClosingQty(line.getClosingQty() != null ? line.getClosingQty() : BigDecimal.ZERO);
            snap.setClosingUnitPrice(line.getMovingAvgUnitPrice()); // null if price-sensitive
            snap.setClosingAmount(line.getClosingAmount());         // null if price-sensitive

            snapshotRepo.save(snap);
            upsertCount++;
        }

        log.info("[SP11] createForPeriod: factoryId={} accountingPeriodId={} period={}/{} upserted={}",
                factoryId, accountingPeriodId, period.getYear(), period.getMonth(), upsertCount);
    }
}
