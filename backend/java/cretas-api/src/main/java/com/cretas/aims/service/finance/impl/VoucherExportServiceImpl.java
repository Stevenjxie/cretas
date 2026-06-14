package com.cretas.aims.service.finance.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.VoucherStatus;
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
import java.util.Comparator;
import java.util.HashMap;
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
    private static final String TYPE_CHRONOLOGICAL = "CHRONOLOGICAL_LEDGER";
    private static final String TYPE_GENERAL_LEDGER = "GENERAL_LEDGER";
    private static final String TYPE_SUBSIDIARY_LEDGER = "SUBSIDIARY_LEDGER";
    private static final String TYPE_TRIAL_BALANCE = "TRIAL_BALANCE";
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

    @Override
    @Transactional
    public String exportChronologicalLedger(String factoryId, VoucherExportRequestDTO req,
                                            Long userId, OutputStream out) throws Exception {
        List<VoucherLine> lines = loadVoucherLines(factoryId, req);
        Map<String, BigDecimal> runningBySubject = openingBalanceBySubject(factoryId, req.getStartDate());

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("日期", "凭证字号", "摘要", "科目编码", "科目名称", "借方金额", "贷方金额", "方向", "余额"));

        for (VoucherLine line : lines) {
            AccountBalanceType balanceType = resolveBalanceType(factoryId, line.subjectCode());
            BigDecimal current = runningBySubject.getOrDefault(line.subjectCode(), BigDecimal.ZERO);
            BigDecimal next = balanceType == AccountBalanceType.CREDIT_NORMAL
                    ? current.add(nvl(line.credit())).subtract(nvl(line.debit()))
                    : current.add(nvl(line.debit())).subtract(nvl(line.credit()));
            runningBySubject.put(line.subjectCode(), next);
            BalanceSide side = splitNormalBalance(next, balanceType);
            rows.add(List.of(
                    line.date().toString(),
                    nvlStr(line.voucherNumber()),
                    nvlStr(line.summary()),
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    amountText(line.debit()),
                    amountText(line.credit()),
                    side.direction(),
                    amountText(side.amount())
            ));
        }

        appendEmptyStateIfNeeded(rows, "暂无符合条件的凭证明细");
        appendSourceFooter(rows);
        writeRawRows(out, rows);
        String fileName = buildFileName("chronological-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_CHRONOLOGICAL, req, userId, fileName, lines.size());
        return fileName;
    }

    @Override
    @Transactional
    public String exportGeneralLedger(String factoryId, VoucherExportRequestDTO req,
                                      Long userId, OutputStream out) throws Exception {
        List<SubjectLedgerLine> lines = buildSubjectLedgerLines(
                factoryId,
                loadOpeningAggregates(factoryId, req.getStartDate()),
                entryRepo.aggregateBySubject(factoryId, req.getStartDate(), req.getEndDate()),
                entryRepo.aggregateBySubject(factoryId, LocalDate.of(req.getStartDate().getYear(), 1, 1), req.getEndDate()));

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("科目编码", "科目名称", "期初(借/贷)", "本期借方", "本期贷方", "本年累计", "期末(借/贷)"));
        for (SubjectLedgerLine line : lines) {
            rows.add(List.of(
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    directionalText(line.openingBalance(), line.balanceType()),
                    amountText(line.periodDebit()),
                    amountText(line.periodCredit()),
                    directionalText(line.yearToDateBalance(), line.balanceType()),
                    directionalText(line.closingBalance(), line.balanceType())
            ));
        }

        appendEmptyStateIfNeeded(rows, "暂无符合条件的科目发生额");
        appendSourceFooter(rows);
        writeRawRows(out, rows);
        String fileName = buildFileName("general-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_GENERAL_LEDGER, req, userId, fileName, lines.size());
        return fileName;
    }

    @Override
    @Transactional
    public String exportSubsidiaryLedger(String factoryId, VoucherExportRequestDTO req,
                                         Long userId, OutputStream out) throws Exception {
        List<VoucherLine> voucherLines = loadVoucherLines(factoryId, req);
        Map<String, BigDecimal> openingBySubject = openingBalanceBySubject(factoryId, req.getStartDate());
        Map<String, List<VoucherLine>> bySubject = new TreeMap<>();
        for (VoucherLine line : voucherLines) {
            bySubject.computeIfAbsent(line.subjectCode(), ignored -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, BigDecimal> opening : openingBySubject.entrySet()) {
            bySubject.computeIfAbsent(opening.getKey(), ignored -> new ArrayList<>());
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("科目编码/名称分段", "日期", "凭证字号", "摘要", "借方", "贷方", "方向", "余额"));
        int dataRows = 0;
        for (Map.Entry<String, List<VoucherLine>> entry : bySubject.entrySet()) {
            String subjectCode = entry.getKey();
            List<VoucherLine> subjectLines = entry.getValue();
            String subjectName = subjectLines.stream()
                    .map(VoucherLine::subjectName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(resolveAccountName(factoryId, subjectCode));
            AccountBalanceType balanceType = resolveBalanceType(factoryId, subjectCode);
            BigDecimal running = openingBySubject.getOrDefault(subjectCode, BigDecimal.ZERO);
            BalanceSide openingSide = splitNormalBalance(running, balanceType);
            rows.add(List.of(subjectCode + " " + nvlStr(subjectName), "", "", "", "", "", "", ""));
            rows.add(List.of("", "", "", "期初", "", "", openingSide.direction(), amountText(openingSide.amount())));
            dataRows += 2;

            BigDecimal periodDebit = BigDecimal.ZERO;
            BigDecimal periodCredit = BigDecimal.ZERO;
            for (VoucherLine line : subjectLines) {
                periodDebit = periodDebit.add(nvl(line.debit()));
                periodCredit = periodCredit.add(nvl(line.credit()));
                running = balanceType == AccountBalanceType.CREDIT_NORMAL
                        ? running.add(nvl(line.credit())).subtract(nvl(line.debit()))
                        : running.add(nvl(line.debit())).subtract(nvl(line.credit()));
                BalanceSide side = splitNormalBalance(running, balanceType);
                rows.add(List.of(
                        "",
                        line.date().toString(),
                        nvlStr(line.voucherNumber()),
                        nvlStr(line.summary()),
                        amountText(line.debit()),
                        amountText(line.credit()),
                        side.direction(),
                        amountText(side.amount())
                ));
                dataRows++;
            }

            BalanceSide closingSide = splitNormalBalance(running, balanceType);
            rows.add(List.of("", "", "", "本期合计", amountText(periodDebit), amountText(periodCredit), "", ""));
            rows.add(List.of("", "", "", "期末", "", "", closingSide.direction(), amountText(closingSide.amount())));
            dataRows += 2;
        }

        appendEmptyStateIfNeeded(rows, "暂无符合条件的科目明细");
        appendSourceFooter(rows);
        writeRawRows(out, rows);
        String fileName = buildFileName("subsidiary-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_SUBSIDIARY_LEDGER, req, userId, fileName, dataRows);
        return fileName;
    }

    @Override
    @Transactional
    public String exportTrialBalance(String factoryId, VoucherExportRequestDTO req,
                                     Long userId, OutputStream out) throws Exception {
        List<SubjectLedgerLine> lines = buildSubjectLedgerLines(
                factoryId,
                loadOpeningAggregates(factoryId, req.getStartDate()),
                entryRepo.aggregateBySubject(factoryId, req.getStartDate(), req.getEndDate()),
                List.of());
        validateTrialBalance(lines);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("科目编码", "科目名称", "期初借方", "期初贷方", "本期借方", "本期贷方", "期末借方", "期末贷方"));

        TrialTotals totals = new TrialTotals();
        for (SubjectLedgerLine line : lines) {
            TrialSide opening = trialSide(line.openingBalance(), line.balanceType());
            TrialSide closing = trialSide(line.closingBalance(), line.balanceType());
            totals.add(opening, line.periodDebit(), line.periodCredit(), closing);
            rows.add(List.of(
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    amountText(opening.debit()),
                    amountText(opening.credit()),
                    amountText(line.periodDebit()),
                    amountText(line.periodCredit()),
                    amountText(closing.debit()),
                    amountText(closing.credit())
            ));
        }
        rows.add(List.of(
                "合计",
                "",
                amountText(totals.openingDebit),
                amountText(totals.openingCredit),
                amountText(totals.periodDebit),
                amountText(totals.periodCredit),
                amountText(totals.closingDebit),
                amountText(totals.closingCredit)
        ));

        appendSourceFooter(rows);
        writeRawRows(out, rows);
        String fileName = buildFileName("trial-balance", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_TRIAL_BALANCE, req, userId, fileName, lines.size());
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

    private List<SubjectLedgerLine> buildSubjectLedgerLines(
            String factoryId,
            List<SubjectAggregateRow> openingAggregates,
            List<SubjectAggregateRow> currentAggregates,
            List<SubjectAggregateRow> yearToDateAggregates) {
        Map<String, MutableSubjectBalance> byCode = new TreeMap<>();
        mergeAggregates(byCode, openingAggregates, AggregateBucket.OPENING);
        mergeAggregates(byCode, currentAggregates, AggregateBucket.PERIOD);
        mergeAggregates(byCode, yearToDateAggregates, AggregateBucket.YEAR_TO_DATE);

        List<SubjectLedgerLine> result = new ArrayList<>();
        for (MutableSubjectBalance line : byCode.values()) {
            AccountBalanceType balanceType = resolveBalanceType(factoryId, line.subjectCode);
            BigDecimal opening = normalBalance(line.openingDebit, line.openingCredit, balanceType);
            BigDecimal closing = balanceType == AccountBalanceType.CREDIT_NORMAL
                    ? opening.add(line.periodCredit).subtract(line.periodDebit)
                    : opening.add(line.periodDebit).subtract(line.periodCredit);
            BigDecimal yearToDate = normalBalance(line.yearToDateDebit, line.yearToDateCredit, balanceType);
            String subjectName = line.subjectName;
            if (subjectName == null || subjectName.isBlank()) {
                subjectName = resolveAccountName(factoryId, line.subjectCode);
            }
            result.add(new SubjectLedgerLine(
                    line.subjectCode,
                    subjectName,
                    balanceType,
                    scale2(opening),
                    scale2(line.periodDebit),
                    scale2(line.periodCredit),
                    scale2(yearToDate),
                    scale2(closing)
            ));
        }
        return result;
    }

    private void mergeAggregates(Map<String, MutableSubjectBalance> byCode,
                                 List<SubjectAggregateRow> rows,
                                 AggregateBucket bucket) {
        for (SubjectAggregateRow row : rows) {
            MutableSubjectBalance line = byCode.computeIfAbsent(row.getSubjectCode(), MutableSubjectBalance::new);
            if (row.getSubjectName() != null && !row.getSubjectName().isBlank()) {
                line.subjectName = row.getSubjectName();
            }
            switch (bucket) {
                case OPENING -> {
                    line.openingDebit = nvl(row.getTotalDebit());
                    line.openingCredit = nvl(row.getTotalCredit());
                }
                case PERIOD -> {
                    line.periodDebit = nvl(row.getTotalDebit());
                    line.periodCredit = nvl(row.getTotalCredit());
                }
                case YEAR_TO_DATE -> {
                    line.yearToDateDebit = nvl(row.getTotalDebit());
                    line.yearToDateCredit = nvl(row.getTotalCredit());
                }
            }
        }
    }

    private Map<String, BigDecimal> openingBalanceBySubject(String factoryId, LocalDate startDate) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (SubjectAggregateRow row : loadOpeningAggregates(factoryId, startDate)) {
            AccountBalanceType balanceType = resolveBalanceType(factoryId, row.getSubjectCode());
            result.put(row.getSubjectCode(), normalBalance(row.getTotalDebit(), row.getTotalCredit(), balanceType));
        }
        return result;
    }

    private List<VoucherLine> loadVoucherLines(String factoryId, VoucherExportRequestDTO req) {
        List<Voucher> vouchers = voucherRepo.findByFactoryIdAndDateRange(
                        factoryId, req.getStartDate(), req.getEndDate()).stream()
                .filter(v -> v.getStatus() != VoucherStatus.VOID)
                .sorted(Comparator
                        .comparing(Voucher::getVoucherDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Voucher::getVoucherNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<VoucherLine> lines = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            List<VoucherEntry> entries = entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc(voucher.getId());
            entries.stream()
                    .sorted(Comparator.comparing(VoucherEntry::getLineNo, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(entry -> lines.add(new VoucherLine(
                            voucher.getVoucherDate(),
                            voucher.getVoucherNumber(),
                            entry.getDescription(),
                            entry.getSubjectCode(),
                            entry.getSubjectName(),
                            scale2(entry.getDebit()),
                            scale2(entry.getCredit())
                    )));
        }
        return lines;
    }

    private void validateTrialBalance(List<SubjectLedgerLine> lines) {
        TrialTotals totals = new TrialTotals();
        for (SubjectLedgerLine line : lines) {
            totals.add(trialSide(line.openingBalance(), line.balanceType()),
                    line.periodDebit(), line.periodCredit(),
                    trialSide(line.closingBalance(), line.balanceType()));
        }

        List<String> failures = new ArrayList<>();
        if (totals.openingDebit.compareTo(totals.openingCredit) != 0) {
            failures.add("期初差额=" + amountText(totals.openingDebit.subtract(totals.openingCredit).abs())
                    + ", 科目: " + imbalanceSubjects(lines, ImbalanceGroup.OPENING));
        }
        if (totals.periodDebit.compareTo(totals.periodCredit) != 0) {
            failures.add("本期差额=" + amountText(totals.periodDebit.subtract(totals.periodCredit).abs())
                    + ", 科目: " + imbalanceSubjects(lines, ImbalanceGroup.PERIOD));
        }
        if (totals.closingDebit.compareTo(totals.closingCredit) != 0) {
            failures.add("期末差额=" + amountText(totals.closingDebit.subtract(totals.closingCredit).abs())
                    + ", 科目: " + imbalanceSubjects(lines, ImbalanceGroup.CLOSING));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("试算平衡不平: " + String.join("; ", failures));
        }
    }

    private String imbalanceSubjects(List<SubjectLedgerLine> lines, ImbalanceGroup group) {
        List<String> subjects = new ArrayList<>();
        for (SubjectLedgerLine line : lines) {
            TrialSide side = switch (group) {
                case OPENING -> trialSide(line.openingBalance(), line.balanceType());
                case PERIOD -> new TrialSide(line.periodDebit(), line.periodCredit());
                case CLOSING -> trialSide(line.closingBalance(), line.balanceType());
            };
            if (side.debit().compareTo(BigDecimal.ZERO) == 0 && side.credit().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String sideLabel = side.debit().compareTo(BigDecimal.ZERO) != 0 ? "借方 " + amountText(side.debit())
                    : "贷方 " + amountText(side.credit());
            subjects.add(nvlStr(line.subjectCode()) + " " + nvlStr(line.subjectName()) + " " + sideLabel);
        }
        return subjects.isEmpty() ? "无发生额科目" : String.join(", ", subjects);
    }

    private TrialSide trialSide(BigDecimal normalBalance, AccountBalanceType balanceType) {
        BigDecimal balance = scale2(normalBalance);
        if (balance.compareTo(BigDecimal.ZERO) == 0) {
            return new TrialSide(scale2(BigDecimal.ZERO), scale2(BigDecimal.ZERO));
        }
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            return balanceType == AccountBalanceType.CREDIT_NORMAL
                    ? new TrialSide(scale2(BigDecimal.ZERO), balance)
                    : new TrialSide(balance, scale2(BigDecimal.ZERO));
        }
        BigDecimal amount = balance.abs();
        return balanceType == AccountBalanceType.CREDIT_NORMAL
                ? new TrialSide(amount, scale2(BigDecimal.ZERO))
                : new TrialSide(scale2(BigDecimal.ZERO), amount);
    }

    private BalanceSide splitNormalBalance(BigDecimal normalBalance, AccountBalanceType balanceType) {
        TrialSide side = trialSide(normalBalance, balanceType);
        if (side.debit().compareTo(BigDecimal.ZERO) != 0) {
            return new BalanceSide("借", side.debit());
        }
        if (side.credit().compareTo(BigDecimal.ZERO) != 0) {
            return new BalanceSide("贷", side.credit());
        }
        return new BalanceSide("平", scale2(BigDecimal.ZERO));
    }

    private String directionalText(BigDecimal normalBalance, AccountBalanceType balanceType) {
        BalanceSide side = splitNormalBalance(normalBalance, balanceType);
        return side.direction() + " " + amountText(side.amount());
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

    private String resolveAccountName(String factoryId, String subjectCode) {
        if (subjectCode == null || subjectCode.isBlank()) {
            return "";
        }
        List<Account> accounts = accountRepo.findByCodeForFactory(factoryId, subjectCode);
        if (!accounts.isEmpty() && accounts.get(0).getName() != null) {
            return accounts.get(0).getName();
        }
        return "";
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

    private void appendEmptyStateIfNeeded(List<List<Object>> rows, String message) {
        if (rows.size() == 1) {
            rows.add(List.of(message));
        }
    }

    private void appendSourceFooter(List<List<Object>> rows) {
        rows.add(List.of("凭证来源口径=" + VOUCHER_SOURCE_CALIBER_PENDING));
    }

    private void saveExportRecord(String factoryId, String exportType, VoucherExportRequestDTO req,
                                  Long userId, String fileName, int rowCount) {
        VoucherExportRecord record = VoucherExportRecord.builder()
                .factoryId(factoryId)
                .exportType(exportType)
                .targetSystem(req.getTargetSystem())
                .periodStart(req.getStartDate())
                .periodEnd(req.getEndDate())
                .rowCount(Math.max(0, rowCount))
                .fileName(fileName)
                .exportedBy(userId)
                .build();
        exportRecordRepo.save(record);
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

    private String amountText(BigDecimal v) {
        return scale2(v).toPlainString();
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String nvlStr(Object v) {
        return v == null ? "" : v.toString();
    }

    private enum AggregateBucket {
        OPENING,
        PERIOD,
        YEAR_TO_DATE
    }

    private enum ImbalanceGroup {
        OPENING,
        PERIOD,
        CLOSING
    }

    private static class MutableSubjectBalance {
        private final String subjectCode;
        private String subjectName;
        private BigDecimal openingDebit = BigDecimal.ZERO;
        private BigDecimal openingCredit = BigDecimal.ZERO;
        private BigDecimal periodDebit = BigDecimal.ZERO;
        private BigDecimal periodCredit = BigDecimal.ZERO;
        private BigDecimal yearToDateDebit = BigDecimal.ZERO;
        private BigDecimal yearToDateCredit = BigDecimal.ZERO;

        private MutableSubjectBalance(String subjectCode) {
            this.subjectCode = subjectCode;
        }
    }

    private record VoucherLine(
            LocalDate date,
            String voucherNumber,
            String summary,
            String subjectCode,
            String subjectName,
            BigDecimal debit,
            BigDecimal credit) {
    }

    private record SubjectLedgerLine(
            String subjectCode,
            String subjectName,
            AccountBalanceType balanceType,
            BigDecimal openingBalance,
            BigDecimal periodDebit,
            BigDecimal periodCredit,
            BigDecimal yearToDateBalance,
            BigDecimal closingBalance) {
    }

    private record BalanceSide(String direction, BigDecimal amount) {
    }

    private record TrialSide(BigDecimal debit, BigDecimal credit) {
    }

    private static class TrialTotals {
        private BigDecimal openingDebit = BigDecimal.ZERO;
        private BigDecimal openingCredit = BigDecimal.ZERO;
        private BigDecimal periodDebit = BigDecimal.ZERO;
        private BigDecimal periodCredit = BigDecimal.ZERO;
        private BigDecimal closingDebit = BigDecimal.ZERO;
        private BigDecimal closingCredit = BigDecimal.ZERO;

        private void add(TrialSide opening, BigDecimal debit, BigDecimal credit, TrialSide closing) {
            openingDebit = openingDebit.add(opening.debit());
            openingCredit = openingCredit.add(opening.credit());
            periodDebit = periodDebit.add(debit);
            periodCredit = periodCredit.add(credit);
            closingDebit = closingDebit.add(closing.debit());
            closingCredit = closingCredit.add(closing.credit());
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
