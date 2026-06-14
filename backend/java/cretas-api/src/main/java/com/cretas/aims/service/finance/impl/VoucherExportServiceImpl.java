package com.cretas.aims.service.finance.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.finance.VoucherExportConfigRepository;
import com.cretas.aims.repository.finance.VoucherExportRecordRepository;
import com.cretas.aims.service.finance.VoucherExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * SP11 voucher export implementation.
 *
 * <p>F006: subject balance export now carries opening balance from the previous
 * CLOSED accounting period. Voucher source caliber is still pending finance
 * confirmation, so the export marks that explicitly instead of hiding it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherExportServiceImpl implements VoucherExportService {

    private static final String TYPE_SEQUENTIAL = "SEQUENTIAL_LEDGER";
    private static final String TYPE_SUBJECT_BALANCE = "SUBJECT_BALANCE";
    private static final LocalDate EPOCH_START = LocalDate.of(1970, 1, 1);
    private static final String VOUCHER_SOURCE_CALIBER_PENDING = "待财务确认";

    private final VoucherRepository voucherRepo;
    private final VoucherEntryRepository entryRepo;
    private final AccountRepository accountRepo;
    private final AccountingPeriodRepository accountingPeriodRepo;
    private final VoucherExportConfigRepository exportConfigRepo;
    private final VoucherExportRecordRepository exportRecordRepo;

    @Override
    @Transactional
    public String exportSequentialLedger(String factoryId, VoucherExportRequestDTO req,
                                          Long userId, OutputStream out) throws Exception {
        Optional<VoucherExportRecord> recent = exportRecordRepo.findRecentExport(
                factoryId, TYPE_SEQUENTIAL, req.getStartDate(), req.getEndDate(),
                LocalDateTime.now().minusMinutes(5));
        if (recent.isPresent()) {
            log.info("[SP11] Dedup hit: return existing exportRecord id={}", recent.get().getId());
        }

        VoucherExportConfig config = resolveConfig(factoryId, req.getTargetSystem());
        List<Voucher> vouchers = voucherRepo.findByFactoryIdAndDateRange(
                factoryId, req.getStartDate(), req.getEndDate());

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(
                config.getColVoucherNo(),
                config.getColDate(),
                config.getColSummary(),
                config.getColSubjectCode(),
                config.getColSubjectName(),
                config.getColDebit(),
                config.getColCredit(),
                config.getColAuxiliary(),
                config.getColCurrency()
        ));

        for (Voucher v : vouchers) {
            List<VoucherEntry> entries = entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc(v.getId());
            for (VoucherEntry e : entries) {
                rows.add(List.of(
                        nvlStr(v.getVoucherNumber()),
                        v.getVoucherDate().toString(),
                        nvlStr(e.getDescription()),
                        nvlStr(e.getSubjectCode()),
                        nvlStr(e.getSubjectName()),
                        scale2(e.getDebit()),
                        scale2(e.getCredit()),
                        nvlStr(e.getAuxiliaryEntityId()),
                        "CNY"
                ));
            }
        }

        writeRawRows(out, rows);

        String fileName = buildFileName("voucher-ledger", factoryId, req.getStartDate(), req.getEndDate());
        int rowCount = Math.max(0, rows.size() - 1);

        VoucherExportRecord record = VoucherExportRecord.builder()
                .factoryId(factoryId)
                .exportType(TYPE_SEQUENTIAL)
                .targetSystem(req.getTargetSystem())
                .periodStart(req.getStartDate())
                .periodEnd(req.getEndDate())
                .rowCount(rowCount)
                .fileName(fileName)
                .exportedBy(userId)
                .build();
        exportRecordRepo.save(record);
        log.info("[SP11] exportSequentialLedger: factoryId={} rows={} file={}", factoryId, rowCount, fileName);
        return fileName;
    }

    @Override
    @Transactional
    public String exportSubjectBalance(String factoryId, VoucherExportRequestDTO req,
                                        Long userId, OutputStream out) throws Exception {
        Optional<VoucherExportRecord> recent = exportRecordRepo.findRecentExport(
                factoryId, TYPE_SUBJECT_BALANCE, req.getStartDate(), req.getEndDate(),
                LocalDateTime.now().minusMinutes(5));
        if (recent.isPresent()) {
            log.info("[SP11] Dedup hit subjectBalance: existingId={}", recent.get().getId());
        }

        VoucherExportConfig config = resolveConfig(factoryId, req.getTargetSystem());
        List<SubjectAggregateRow> openingAggregates = loadOpeningAggregates(factoryId, req.getStartDate());
        List<SubjectAggregateRow> currentAggregates = entryRepo.aggregateBySubject(
                factoryId, req.getStartDate(), req.getEndDate());
        List<SubjectBalanceLine> balanceLines = buildSubjectBalanceLines(
                factoryId, openingAggregates, currentAggregates);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(
                config.getColSubjectCode(),
                config.getColSubjectName(),
                "期初余额",
                config.getColDebit(),
                config.getColCredit(),
                "期末余额",
                "凭证来源口径"
        ));

        for (SubjectBalanceLine line : balanceLines) {
            rows.add(List.of(
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    line.openingBalance(),
                    line.periodDebit(),
                    line.periodCredit(),
                    line.closingBalance(),
                    VOUCHER_SOURCE_CALIBER_PENDING
            ));
        }

        writeRawRows(out, rows);

        String fileName = buildFileName("subject-balance", factoryId, req.getStartDate(), req.getEndDate());
        int rowCount = Math.max(0, rows.size() - 1);

        VoucherExportRecord record = VoucherExportRecord.builder()
                .factoryId(factoryId)
                .exportType(TYPE_SUBJECT_BALANCE)
                .targetSystem(req.getTargetSystem())
                .periodStart(req.getStartDate())
                .periodEnd(req.getEndDate())
                .rowCount(rowCount)
                .fileName(fileName)
                .exportedBy(userId)
                .build();
        exportRecordRepo.save(record);
        log.info("[SP11] exportSubjectBalance: factoryId={} rows={}", factoryId, rowCount);
        return fileName;
    }

    private List<SubjectAggregateRow> loadOpeningAggregates(String factoryId, LocalDate startDate) {
        YearMonth previousMonth = YearMonth.from(startDate).minusMonths(1);
        boolean previousClosed = accountingPeriodRepo
                .findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(
                        factoryId, previousMonth.getYear(), previousMonth.getMonthValue())
                .map(period -> period.getStatus() == AccountingPeriod.Status.CLOSED)
                .orElse(false);

        if (!previousClosed) {
            return List.of();
        }

        return entryRepo.aggregateBySubject(factoryId, EPOCH_START, previousMonth.atEndOfMonth());
    }

    private List<SubjectBalanceLine> buildSubjectBalanceLines(
            String factoryId,
            List<SubjectAggregateRow> openingAggregates,
            List<SubjectAggregateRow> currentAggregates) {
        Map<String, MutableSubjectBalance> byCode = new TreeMap<>();

        for (SubjectAggregateRow row : openingAggregates) {
            MutableSubjectBalance line = byCode.computeIfAbsent(row.getSubjectCode(), MutableSubjectBalance::new);
            line.subjectName = row.getSubjectName();
            line.openingDebit = nvl(row.getTotalDebit());
            line.openingCredit = nvl(row.getTotalCredit());
        }

        for (SubjectAggregateRow row : currentAggregates) {
            MutableSubjectBalance line = byCode.computeIfAbsent(row.getSubjectCode(), MutableSubjectBalance::new);
            if (row.getSubjectName() != null && !row.getSubjectName().isBlank()) {
                line.subjectName = row.getSubjectName();
            }
            line.periodDebit = nvl(row.getTotalDebit());
            line.periodCredit = nvl(row.getTotalCredit());
        }

        List<SubjectBalanceLine> result = new ArrayList<>();
        for (MutableSubjectBalance line : byCode.values()) {
            AccountBalanceType balanceType = resolveBalanceType(factoryId, line.subjectCode);
            BigDecimal opening = normalBalance(line.openingDebit, line.openingCredit, balanceType);
            BigDecimal closing = balanceType == AccountBalanceType.CREDIT_NORMAL
                    ? opening.add(line.periodCredit).subtract(line.periodDebit)
                    : opening.add(line.periodDebit).subtract(line.periodCredit);
            result.add(new SubjectBalanceLine(
                    line.subjectCode,
                    line.subjectName,
                    scale2(opening),
                    scale2(line.periodDebit),
                    scale2(line.periodCredit),
                    scale2(closing)
            ));
        }
        return result;
    }

    private AccountBalanceType resolveBalanceType(String factoryId, String subjectCode) {
        if (subjectCode == null || subjectCode.isBlank()) {
            return AccountBalanceType.DEBIT_NORMAL;
        }

        List<Account> accounts = accountRepo.findByCodeForFactory(factoryId, subjectCode);
        if (!accounts.isEmpty() && accounts.get(0).getBalanceType() != null) {
            return accounts.get(0).getBalanceType();
        }

        log.warn("[SP11] subject balance export missing Account balanceType: factoryId={} subjectCode={}; use DEBIT_NORMAL",
                factoryId, subjectCode);
        return AccountBalanceType.DEBIT_NORMAL;
    }

    private BigDecimal normalBalance(BigDecimal debit, BigDecimal credit, AccountBalanceType balanceType) {
        return balanceType == AccountBalanceType.CREDIT_NORMAL
                ? nvl(credit).subtract(nvl(debit))
                : nvl(debit).subtract(nvl(credit));
    }

    private VoucherExportConfig resolveConfig(String factoryId, VoucherTargetSystem targetSystem) {
        return exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(factoryId, targetSystem)
                .orElseGet(() -> VoucherExportConfig.builder()
                        .factoryId(factoryId)
                        .targetSystem(targetSystem != null ? targetSystem : VoucherTargetSystem.KINGDEE)
                        .build());
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
        WriteSheet sheet = EasyExcel.writerSheet("Sheet1").build();
        writer.write(dataRows, sheet);
        writer.finish();
    }

    private String buildFileName(String prefix, String factoryId,
                                  LocalDate start, LocalDate end) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("%s_%s_%s_%s_%s.xlsx", prefix, factoryId,
                start.toString().replace("-", ""), end.toString().replace("-", ""), ts);
    }

    private BigDecimal scale2(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String nvlStr(Object v) {
        return v == null ? "" : v.toString();
    }

    private static class MutableSubjectBalance {
        private final String subjectCode;
        private String subjectName;
        private BigDecimal openingDebit = BigDecimal.ZERO;
        private BigDecimal openingCredit = BigDecimal.ZERO;
        private BigDecimal periodDebit = BigDecimal.ZERO;
        private BigDecimal periodCredit = BigDecimal.ZERO;

        private MutableSubjectBalance(String subjectCode) {
            this.subjectCode = subjectCode;
        }
    }

    private record SubjectBalanceLine(
            String subjectCode,
            String subjectName,
            BigDecimal openingBalance,
            BigDecimal periodDebit,
            BigDecimal periodCredit,
            BigDecimal closingBalance) {
    }
}
