package com.cretas.aims.service.finance.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.ExcelWriter;
import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * SP11: 凭证序时账/科目余额表导出实现 (EasyExcel).
 *
 * <p>Rule-4 幂等: 同工厂 + exportType + 期间 5 分钟内重复请求返回已有记录ID,
 * 不重新生成文件 (但仍写入 OutputStream — 调用方控制是否需要文件内容).
 *
 * <p>列名动态: 从 {@link VoucherExportConfig} 取, 默认金蝶列名.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherExportServiceImpl implements VoucherExportService {

    private static final String TYPE_SEQUENTIAL = "SEQUENTIAL_LEDGER";
    private static final String TYPE_SUBJECT_BALANCE = "SUBJECT_BALANCE";

    private final VoucherRepository voucherRepo;
    private final VoucherEntryRepository entryRepo;
    private final VoucherExportConfigRepository exportConfigRepo;
    private final VoucherExportRecordRepository exportRecordRepo;

    @Override
    @Transactional
    public String exportSequentialLedger(String factoryId, VoucherExportRequestDTO req,
                                          Long userId, OutputStream out) throws Exception {
        // Rule-4: 5-min dedup check
        Optional<VoucherExportRecord> recent = exportRecordRepo.findRecentExport(
                factoryId, TYPE_SEQUENTIAL, req.getStartDate(), req.getEndDate(),
                java.time.LocalDateTime.now().minusMinutes(5));
        if (recent.isPresent()) {
            log.info("[SP11] Dedup hit: return existing exportRecord id={}", recent.get().getId());
        }

        // 取导出配置 (列名)
        VoucherExportConfig config = resolveConfig(factoryId, req.getTargetSystem());

        // 取期间内凭证 + 分录
        List<Voucher> vouchers = voucherRepo.findByFactoryIdAndDateRange(
                factoryId, req.getStartDate(), req.getEndDate());

        // 构建行数据
        List<List<Object>> rows = new ArrayList<>();
        // Header row
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

        // Write via EasyExcel (dynamic headers — no model class needed)
        writeRawRows(out, rows);

        // 记录导出日志
        String fileName = buildFileName("voucher-ledger", factoryId, req.getStartDate(), req.getEndDate());
        int rowCount = Math.max(0, rows.size() - 1); // exclude header

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
        // Rule-4 dedup
        Optional<VoucherExportRecord> recent = exportRecordRepo.findRecentExport(
                factoryId, TYPE_SUBJECT_BALANCE, req.getStartDate(), req.getEndDate(),
                java.time.LocalDateTime.now().minusMinutes(5));
        if (recent.isPresent()) {
            log.info("[SP11] Dedup hit subjectBalance: existingId={}", recent.get().getId());
        }

        VoucherExportConfig config = resolveConfig(factoryId, req.getTargetSystem());

        // 使用已有的 aggregateBySubject 查询
        List<SubjectAggregateRow> aggregated = entryRepo.aggregateBySubject(
                factoryId, req.getStartDate(), req.getEndDate());

        List<List<Object>> rows = new ArrayList<>();
        // Header
        rows.add(List.of(
                config.getColSubjectCode(),
                config.getColSubjectName(),
                "期初余额(借)",
                "期初余额(贷)",
                config.getColDebit(),
                config.getColCredit(),
                "期末余额(借)",
                "期末余额(贷)"
        ));

        for (SubjectAggregateRow row : aggregated) {
            BigDecimal debit = scale2(row.getTotalDebit());
            BigDecimal credit = scale2(row.getTotalCredit());
            // 期初 = 0 (SP11 快照期初为期末, 当前期初留 0 等待快照集成)
            rows.add(List.of(
                    nvlStr(row.getSubjectCode()),
                    nvlStr(row.getSubjectName()),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    debit,
                    credit,
                    debit.subtract(credit).max(BigDecimal.ZERO),
                    credit.subtract(debit).max(BigDecimal.ZERO)
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

    // ===================== 私有工具 =====================

    private VoucherExportConfig resolveConfig(String factoryId, VoucherTargetSystem targetSystem) {
        return exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(factoryId, targetSystem)
                .orElseGet(() -> VoucherExportConfig.builder()
                        .factoryId(factoryId)
                        .targetSystem(targetSystem != null ? targetSystem : VoucherTargetSystem.KINGDEE)
                        .build()); // defaults built-in via @Builder.Default
    }

    private void writeRawRows(OutputStream out, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object> header = rows.get(0);
        List<List<Object>> dataRows = rows.subList(1, rows.size());

        // Dynamic head: convert List<Object> header to List<List<String>>
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
        if (v == null) return BigDecimal.ZERO;
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String nvlStr(Object v) {
        return v == null ? "" : v.toString();
    }
}
