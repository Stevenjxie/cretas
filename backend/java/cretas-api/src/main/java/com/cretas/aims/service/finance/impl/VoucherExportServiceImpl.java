package com.cretas.aims.service.finance.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherExportFileFormat;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Department;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.DepartmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.finance.VoucherExportConfigRepository;
import com.cretas.aims.repository.finance.VoucherExportRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.finance.VoucherExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.data.domain.PageRequest;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String TYPE_INCOME_STATEMENT = "INCOME_STATEMENT";
    private static final String TYPE_QUANTITY_AMOUNT_LEDGER = "QUANTITY_AMOUNT_LEDGER";
    private static final String TYPE_KINGDEE_IMPORT_TEMPLATE = "KINGDEE_IMPORT_TEMPLATE";
    private static final LocalDate EPOCH_START = LocalDate.of(1970, 1, 1);
    private static final int INVENTORY_EXPORT_PAGE_SIZE = 10_000;
    private static final String SOURCE_FOOTER_TEXT = "凭证来源口径=待财务确认";
    private static final String VOUCHER_SOURCE_CALIBER_PENDING = "待财务确认";
    private static final String KINGDEE_CLOUD_VOUCHER_WORD = "记";
    private static final String KINGDEE_CLOUD_CURRENCY = "人民币";
    private static final String KINGDEE_CLOUD_EXCHANGE_RATE = "1";
    private static final String KINGDEE_CLOUD_SHEET_NAME = "凭证导入";
    // KIS/K3 "标准格式凭证" 列集与云星空不同 (附单据数/数量/单价/原币金额/核算类别/核算编码/制单人
    // 均是云星空模板没有的字段) — 见 buildKisImportRows 头部 comment 引用来源。
    private static final String KIS_BLANK = "";
    // 金蝶 KIS/K3 DBF 交换文件常量 (见 buildKisDbf javadoc 的来源引用).
    private static final String KINGDEE_DBF_VOUCHER_WORD = "记";
    private static final String KINGDEE_DBF_CURRENCY_CODE = "RMB"; // KIS 币别代码 (人民币), 与 xlsx 的币别名"人民币"区分
    private static final String KINGDEE_DBF_EXCHANGE_RATE = "1.0000";
    private static final String KINGDEE_DBF_DC_DEBIT = "1";  // FDC: 1=借
    private static final String KINGDEE_DBF_DC_CREDIT = "0"; // FDC: 0=贷

    private final VoucherRepository voucherRepo;
    private final VoucherEntryRepository entryRepo;
    private final AccountRepository accountRepo;
    private final AccountingPeriodRepository accountingPeriodRepo;
    private final VoucherExportConfigRepository exportConfigRepo;
    private final VoucherExportRecordRepository exportRecordRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final MaterialConsumptionRepository materialConsumptionRepo;
    private final SemiFinishedInventoryRepository semiFinishedInventoryRepo;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    // 辅助核算 (核算名称/核算类别) 名称解析 — 把 auxiliaryEntityId (UUID/PK) 翻成实体显示名, 见 buildAuxIndex.
    private final CustomerRepository customerRepo;
    private final SupplierRepository supplierRepo;
    private final DepartmentRepository departmentRepo;
    private final ProductTypeRepository productTypeRepo;
    private final UserRepository userRepo;

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

        // 防呆 R4: 仅在 5min 窗口内无重复时落审计记录 — 重复请求 (recent 命中) 仍正常导出文件,
        // 但不再插入重复 export_record (消除 dedup 命中却仍 save 的 no-op)。
        if (recent.isEmpty()) {
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
        }
        log.info("[SP11] exportSequentialLedger: factoryId={} rows={} file={} dedupReused={}",
                factoryId, rowCount, fileName, recent.isPresent());
        return fileName;
    }

    @Override
    @Transactional
    public String exportKingdeeImportTemplate(String factoryId, VoucherExportRequestDTO req,
                                              Long userId, OutputStream out) throws Exception {
        // 六膳门需要 KIS/K3 和 云星空 两种金蝶版本 (列集不同, 见下方两个 build*ImportRows).
        // 未传 targetSystem (老 caller) 或非 KIS 一律走云星空, 保持向后兼容.
        VoucherTargetSystem target = req.getTargetSystem() == VoucherTargetSystem.KINGDEE_KIS
                ? VoucherTargetSystem.KINGDEE_KIS
                : VoucherTargetSystem.KINGDEE_YXSKY;

        VoucherExportFileFormat format = req.getExportFormat() != null
                ? req.getExportFormat()
                : VoucherExportFileFormat.XLSX;

        VoucherExportRequestDTO kingdeeReq = VoucherExportRequestDTO.builder()
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .targetSystem(target)
                .exportFormat(format)
                .build();
        VoucherExportConfig config = resolveConfig(factoryId, target);
        List<VoucherImportLine> lines = loadVoucherImportLines(factoryId, req);
        // 辅助核算/核算名称 UUID→NAME 解析 — 两个 profile (云星空/KIS) + DBF 都复用 (见 buildAuxIndex).
        AuxIndex auxIndex = buildAuxIndex(factoryId, lines);

        String fileName;
        if (format == VoucherExportFileFormat.DBF) {
            // DBF 是 KIS标准版/迷你版/K3 的原生凭证交换格式 — 一律走 KIS DBF schema (与 target 无关).
            byte[] dbf = buildKisDbf(lines, auxIndex);
            out.write(dbf);
            fileName = buildFileName("kingdee-kis-voucher", factoryId,
                    req.getStartDate(), req.getEndDate(), "dbf");
        } else {
            List<List<Object>> rows = target == VoucherTargetSystem.KINGDEE_KIS
                    ? buildKisImportRows(config, lines, auxIndex)
                    : buildYxskyImportRows(config, lines, auxIndex);
            String filePrefix = target == VoucherTargetSystem.KINGDEE_KIS
                    ? "kingdee-kis-import-template"
                    : "kingdee-yxsky-import-template";
            if (format == VoucherExportFileFormat.XLS) {
                // KIS专业版 "引入标准格式凭证" 认 legacy .xls (BIFF), 不认 .xlsx.
                writeRawRowsXls(out, rows, KINGDEE_CLOUD_SHEET_NAME);
                fileName = buildFileName(filePrefix, factoryId,
                        req.getStartDate(), req.getEndDate(), "xls");
            } else {
                writeRawRows(out, rows, KINGDEE_CLOUD_SHEET_NAME);
                fileName = buildFileName(filePrefix, factoryId,
                        req.getStartDate(), req.getEndDate(), "xlsx");
            }
        }
        saveExportRecord(factoryId, TYPE_KINGDEE_IMPORT_TEMPLATE, kingdeeReq, userId, fileName, lines.size());
        return fileName;
    }

    /** 金蝶云星空 凭证导入 sheet — 11 列 (凭证字/凭证号/日期/摘要/科目编码/科目名称/借方/贷方/币别/汇率/辅助核算). */
    private List<List<Object>> buildYxskyImportRows(VoucherExportConfig config, List<VoucherImportLine> lines,
                                                    AuxIndex auxIndex) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(
                "凭证字",
                config.getColVoucherNo(),
                config.getColDate(),
                config.getColSummary(),
                config.getColSubjectCode(),
                config.getColSubjectName(),
                config.getColDebit(),
                config.getColCredit(),
                config.getColCurrency(),
                "汇率",
                config.getColAuxiliary()
        ));

        for (VoucherImportLine line : lines) {
            ResolvedAux aux = resolveAuxDisplay(auxIndex, line.auxiliaryType(), line.auxiliary());
            rows.add(List.of(
                    KINGDEE_CLOUD_VOUCHER_WORD,
                    nvlStr(line.voucherNumber()),
                    line.date() == null ? "" : line.date().toString(),
                    nvlStr(line.summary()),
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    blankIfZero(line.debit()),
                    blankIfZero(line.credit()),
                    KINGDEE_CLOUD_CURRENCY,
                    KINGDEE_CLOUD_EXCHANGE_RATE,
                    yxskyAuxiliaryCell(aux)
            ));
        }
        return rows;
    }

    /**
     * 金蝶 KIS专业版/标准版/K3 "标准格式凭证" Excel 引入模板 — 18 列, 列集跟云星空不同.
     *
     * <p>KIS/K3 系列 (专业版/标准版/旗舰版/精斗云共享同一份 "引入标准格式凭证" 工具) 的规范列集
     * 反复出现在金蝶官方及第三方教程中 (凭证字/凭证号/附单据数/核算类别/核算编码/核算名称/制单人
     * 是云星空模板没有、KIS 系列特有的字段):
     * <pre>
     * 日期,凭证字,凭证号,附单据数,摘要,科目编码,科目名称,借方金额,贷方金额,
     * 币别,汇率,原币金额,数量,单价,核算类别,核算编码,核算名称,制单人
     * </pre>
     * 来源: 金蝶 KIS专业版 "文件→引出/引入标准格式凭证" 工具说明 + 多篇第三方教程交叉确认
     * (知乎《金蝶专业版/商贸版/旗舰版K3/精斗云批量导入凭证模板操作》,
     * CSDN《金蝶专业版日记账批量生成凭证导入操作》等) — 该字段集合跨 KIS 专业版/标准版/K3/精斗云
     * 版本共享, 是行业内公认的"标准格式凭证"结构。
     *
     * <p>本系统无多币种/辅助核算分类/数量单价/制单人姓名解析等维度, 这些列按 Cretas 现有数据诚实留空
     * (禁止降级处理: 不编造假数据) — 客户导入 KIS 时按需在 Excel 里手工补齐, 或后续按需求接入。
     * 汇率/币别沿用云星空同款单币种(人民币/1)常量, 因为两个 profile 目前都只服务人民币记账。
     */
    private List<List<Object>> buildKisImportRows(VoucherExportConfig config, List<VoucherImportLine> lines,
                                                  AuxIndex auxIndex) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(
                config.getColDate(),
                "凭证字",
                config.getColVoucherNo(),
                "附单据数",
                config.getColSummary(),
                config.getColSubjectCode(),
                config.getColSubjectName(),
                config.getColDebit(),
                config.getColCredit(),
                config.getColCurrency(),
                "汇率",
                "原币金额",
                "数量",
                "单价",
                "核算类别",
                "核算编码",
                config.getColAuxiliary(),
                "制单人"
        ));

        for (VoucherImportLine line : lines) {
            ResolvedAux aux = resolveAuxDisplay(auxIndex, line.auxiliaryType(), line.auxiliary());
            rows.add(List.of(
                    line.date() == null ? "" : line.date().toString(),
                    KINGDEE_CLOUD_VOUCHER_WORD,
                    nvlStr(line.voucherNumber()),
                    KIS_BLANK,
                    nvlStr(line.summary()),
                    nvlStr(line.subjectCode()),
                    nvlStr(line.subjectName()),
                    blankIfZero(line.debit()),
                    blankIfZero(line.credit()),
                    KINGDEE_CLOUD_CURRENCY,
                    KINGDEE_CLOUD_EXCHANGE_RATE,
                    KIS_BLANK,
                    KIS_BLANK,
                    KIS_BLANK,
                    aux.category(),   // 核算类别 (客户/供应商/...) — 结构化 auxiliaryType 翻中文
                    aux.code(),       // 核算编码 — 实体 code (无则留空, 不编造)
                    aux.name(),       // 核算名称 — 实体名 (无则留空, 绝不写 UUID)
                    KIS_BLANK
            ));
        }
        return rows;
    }

    /**
     * 金蝶 KIS标准版/迷你版/K3 原生凭证 DBF (dBASE III) 交换文件.
     *
     * <p><b>为什么要 DBF</b>: KIS标准版/迷你版/K3 的 "文件→引入" 走 .dbf 凭证交换文件, 不认现代 .xlsx。
     * (KIS专业版走 legacy .xls, 云星空走 .xlsx — 见 {@link VoucherExportFileFormat}.)
     *
     * <p><b>字段 schema 来源</b>: 金蝶 K3/KIS "标准格式凭证" DBF 库结构, 见
     * <a href="https://blog.csdn.net/weixin_34337381/article/details/92580927">K3凭证标准格式凭证库结构说明</a>
     * 及 <a href="https://www.suokunlong.cn/wp/kingdee-kis-dbf-standard-voucher-fields/">金蝶KIS标准凭证(dbf格式)字段名称及含义</a>
     * (两处交叉一致)。关键约定:
     * <ul>
     *   <li>{@code FDATE}(D 业务日期), {@code FTRANSDATE}(D 记账日期), {@code FPERIOD}(N 会计期间=凭证月份)</li>
     *   <li>{@code FGROUP}(C10 凭证字), {@code FNUM}(N 凭证号), {@code FENTRYID}(N 分录号, 同一凭证内从 0 起)</li>
     *   <li>{@code FEXP}(C 摘要), {@code FACCTID}(C40 科目代码, 点分级次)</li>
     *   <li>{@code FCLSNAME1}(C 核算类别名), {@code FOBJID1}(C 核算项目代码), {@code FOBJNAME1}(C 核算项目名) — 第 1 维辅助核算</li>
     *   <li>{@code FDC}(C1 借贷方向: <b>1=借 / 0=贷</b>, 见上述 K3 结构说明)</li>
     *   <li>{@code FCYID}(C 币别代码 RMB), {@code FEXCHRATE}(N 汇率), {@code FFCYAMT}(N 原币金额),
     *       {@code FDEBIT}/{@code FCREDIT}/{@code FAMOUNT}(N 借/贷/金额)</li>
     *   <li>{@code FQUANTITY}/{@code FPRICE}(N 数量/单价 — 本系统无数量核算维度, 诚实写 0), {@code FPREPARE}(C 制单人 — 未接入, 留空)</li>
     * </ul>
     * 中文按 GBK 编码 (LDID=0x7A/936), 见 {@link DbfWriter}。单币种系统: 币别恒 RMB, 汇率恒 1。
     */
    private byte[] buildKisDbf(List<VoucherImportLine> lines, AuxIndex auxIndex) {
        List<DbfWriter.Field> fields = kisDbfFields();
        List<List<String>> records = new ArrayList<>();

        String currentVoucher = null;
        int entryId = 0;
        int voucherSeq = 0; // FNUM fallback when 凭证号非数字
        for (VoucherImportLine line : lines) {
            String voucherNo = nvlStr(line.voucherNumber());
            if (!voucherNo.equals(currentVoucher)) {
                currentVoucher = voucherNo;
                entryId = 0;
                voucherSeq++;
            } else {
                entryId++;
            }

            boolean isDebit = nvl(line.debit()).compareTo(BigDecimal.ZERO) != 0;
            BigDecimal amount = isDebit ? nvl(line.debit()) : nvl(line.credit());
            ResolvedAux aux = resolveAuxDisplay(auxIndex, line.auxiliaryType(), line.auxiliary());
            String dateStr = line.date() == null ? "" : dbfDate(line.date());
            String period = line.date() == null ? "0" : String.valueOf(line.date().getMonthValue());

            List<String> record = new ArrayList<>();
            record.add(dateStr);                                  // FDATE
            record.add(dateStr);                                  // FTRANSDATE
            record.add(period);                                   // FPERIOD
            record.add(KINGDEE_DBF_VOUCHER_WORD);                 // FGROUP
            record.add(numericVoucherNo(voucherNo, voucherSeq));  // FNUM
            record.add(String.valueOf(entryId));                  // FENTRYID
            record.add(nvlStr(line.summary()));                   // FEXP
            record.add(nvlStr(line.subjectCode()));               // FACCTID
            record.add(aux.category());                           // FCLSNAME1 (核算类别名)
            record.add(aux.code());                               // FOBJID1   (核算项目代码)
            record.add(aux.name());                               // FOBJNAME1 (核算项目名)
            record.add(isDebit ? KINGDEE_DBF_DC_DEBIT : KINGDEE_DBF_DC_CREDIT); // FDC
            record.add(KINGDEE_DBF_CURRENCY_CODE);                // FCYID
            record.add(KINGDEE_DBF_EXCHANGE_RATE);                // FEXCHRATE
            record.add(dbfAmount(amount));                        // FFCYAMT (原币=本币, 单币种)
            record.add(dbfAmount(nvl(line.debit())));             // FDEBIT
            record.add(dbfAmount(nvl(line.credit())));            // FCREDIT
            record.add(dbfAmount(amount));                        // FAMOUNT
            record.add("0.00");                                   // FQUANTITY (无数量维度, 诚实 0)
            record.add("0.0000");                                 // FPRICE
            record.add(KIS_BLANK);                                // FPREPARE (未接入制单人)
            records.add(record);
        }
        return DbfWriter.write(fields, records);
    }

    /** KIS/K3 标准格式凭证 DBF 字段定义. 顺序与 {@link #buildKisDbf} 记录列一一对应. */
    private List<DbfWriter.Field> kisDbfFields() {
        return List.of(
                new DbfWriter.Field("FDATE", DbfWriter.Type.DATE, 8, 0),
                new DbfWriter.Field("FTRANSDATE", DbfWriter.Type.DATE, 8, 0),
                new DbfWriter.Field("FPERIOD", DbfWriter.Type.NUMERIC, 2, 0),
                new DbfWriter.Field("FGROUP", DbfWriter.Type.CHARACTER, 10, 0),
                new DbfWriter.Field("FNUM", DbfWriter.Type.NUMERIC, 10, 0),
                new DbfWriter.Field("FENTRYID", DbfWriter.Type.NUMERIC, 10, 0),
                new DbfWriter.Field("FEXP", DbfWriter.Type.CHARACTER, 100, 0),
                new DbfWriter.Field("FACCTID", DbfWriter.Type.CHARACTER, 40, 0),
                new DbfWriter.Field("FCLSNAME1", DbfWriter.Type.CHARACTER, 40, 0),
                new DbfWriter.Field("FOBJID1", DbfWriter.Type.CHARACTER, 40, 0),
                new DbfWriter.Field("FOBJNAME1", DbfWriter.Type.CHARACTER, 80, 0),
                new DbfWriter.Field("FDC", DbfWriter.Type.CHARACTER, 1, 0),
                new DbfWriter.Field("FCYID", DbfWriter.Type.CHARACTER, 10, 0),
                new DbfWriter.Field("FEXCHRATE", DbfWriter.Type.NUMERIC, 12, 4),
                new DbfWriter.Field("FFCYAMT", DbfWriter.Type.NUMERIC, 16, 2),
                new DbfWriter.Field("FDEBIT", DbfWriter.Type.NUMERIC, 16, 2),
                new DbfWriter.Field("FCREDIT", DbfWriter.Type.NUMERIC, 16, 2),
                new DbfWriter.Field("FAMOUNT", DbfWriter.Type.NUMERIC, 16, 2),
                new DbfWriter.Field("FQUANTITY", DbfWriter.Type.NUMERIC, 16, 2),
                new DbfWriter.Field("FPRICE", DbfWriter.Type.NUMERIC, 16, 4),
                new DbfWriter.Field("FPREPARE", DbfWriter.Type.CHARACTER, 40, 0)
        );
    }

    private String dbfDate(LocalDate date) {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE); // YYYYMMDD
    }

    private String dbfAmount(BigDecimal v) {
        return scale2(v).toPlainString();
    }

    /** FNUM 是数字字段. 尽力从凭证号取数字; 全非数字时回落到该凭证在本次导出内的序号. */
    private String numericVoucherNo(String voucherNumber, int fallbackSeq) {
        if (voucherNumber != null) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < voucherNumber.length(); i++) {
                char c = voucherNumber.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                }
            }
            if (digits.length() > 0) {
                // 去前导零但保留至少一位
                String s = digits.toString().replaceFirst("^0+(?=\\d)", "");
                return s;
            }
        }
        return String.valueOf(fallbackSeq);
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
        // F006 财务审计 Bug 6 (2026-07-04): 官方报表切 POSTED-only (排除 DRAFT 未审草稿), 见
        // aggregateBySubjectExcludingDraft javadoc。
        List<SubjectAggregateRow> currentAggregates = entryRepo.aggregateBySubjectExcludingDraft(
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

        // rowCount (审计记录) 用 balanceLines.size() 而非 rows.size()-1, 避免下方 footer 行计入行数。
        int rowCount = balanceLines.size();
        appendPendingDraftFooter(rows, factoryId, req.getStartDate(), req.getEndDate());
        writeRawRows(out, rows);

        String fileName = buildFileName("subject-balance", factoryId, req.getStartDate(), req.getEndDate());

        // 防呆 R4: 仅在无 5min 窗口重复时落审计记录 — 重复请求仍正常导出, 不插重复 export_record。
        if (recent.isEmpty()) {
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
        }
        log.info("[SP11] exportSubjectBalance: factoryId={} rows={} dedupReused={}",
                factoryId, rowCount, recent.isPresent());
        return fileName;
    }

    @Override
    @Transactional
    public String exportChronologicalLedger(String factoryId, VoucherExportRequestDTO req,
                                            Long userId, OutputStream out) throws Exception {
        // 序时账 (chronological ledger) 是本系统内的账簿视图, 保持 DRAFT 可见 (见 loadVoucherLines
        // javadoc) — 与 F006 财务审计 Bug 6 (2026-07-04) 明确点名 POSTED-only 的"总账/明细账"不同。
        List<VoucherLine> lines = loadVoucherLines(factoryId, req, /*excludeDraft=*/false);
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
        // F006 财务审计 Bug 6 (2026-07-04): 官方报表切 POSTED-only (排除 DRAFT 未审草稿).
        List<SubjectLedgerLine> lines = buildSubjectLedgerLines(
                factoryId,
                loadOpeningAggregates(factoryId, req.getStartDate()),
                entryRepo.aggregateBySubjectExcludingDraft(factoryId, req.getStartDate(), req.getEndDate()),
                entryRepo.aggregateBySubjectExcludingDraft(
                        factoryId, LocalDate.of(req.getStartDate().getYear(), 1, 1), req.getEndDate()));

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
        appendPendingDraftFooter(rows, factoryId, req.getStartDate(), req.getEndDate());
        writeRawRows(out, rows);
        String fileName = buildFileName("general-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_GENERAL_LEDGER, req, userId, fileName, lines.size());
        return fileName;
    }

    @Override
    @Transactional
    public String exportSubsidiaryLedger(String factoryId, VoucherExportRequestDTO req,
                                         Long userId, OutputStream out) throws Exception {
        // F006 财务审计 Bug 6 (2026-07-04): 明细账是官方账簿, 排除 DRAFT (未审草稿).
        List<VoucherLine> voucherLines = loadVoucherLines(factoryId, req, /*excludeDraft=*/true);
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
        appendPendingDraftFooter(rows, factoryId, req.getStartDate(), req.getEndDate());
        writeRawRows(out, rows);
        String fileName = buildFileName("subsidiary-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_SUBSIDIARY_LEDGER, req, userId, fileName, dataRows);
        return fileName;
    }

    @Override
    @Transactional
    public String exportTrialBalance(String factoryId, VoucherExportRequestDTO req,
                                     Long userId, OutputStream out) throws Exception {
        // F006 财务审计 Bug 6 (2026-07-04): 官方报表切 POSTED-only (排除 DRAFT 未审草稿).
        List<SubjectLedgerLine> lines = buildSubjectLedgerLines(
                factoryId,
                loadOpeningAggregates(factoryId, req.getStartDate()),
                entryRepo.aggregateBySubjectExcludingDraft(factoryId, req.getStartDate(), req.getEndDate()),
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
        appendPendingDraftFooter(rows, factoryId, req.getStartDate(), req.getEndDate());
        writeRawRows(out, rows);
        String fileName = buildFileName("trial-balance", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_TRIAL_BALANCE, req, userId, fileName, lines.size());
        return fileName;
    }

    @Override
    @Transactional
    public String exportIncomeStatement(String factoryId, VoucherExportRequestDTO req,
                                        Long userId, OutputStream out) throws Exception {
        // F006 财务审计 Bug 6 (2026-07-04): 排除 DRAFT (未审草稿) + 排除结转产物 (PL_CLOSING/
        // COST_CARRYOVER 红冲镜像) — 否则已结账期间会显示营收/成本=0, 见
        // aggregateBySubjectExcludingDraftAndClosing javadoc。
        IncomeAmounts period = buildIncomeAmounts(factoryId,
                entryRepo.aggregateBySubjectExcludingDraftAndClosing(factoryId, req.getStartDate(), req.getEndDate()));
        IncomeAmounts yearToDate = buildIncomeAmounts(factoryId,
                entryRepo.aggregateBySubjectExcludingDraftAndClosing(
                        factoryId, LocalDate.of(req.getStartDate().getYear(), 1, 1), req.getEndDate()));

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("项目", "本期金额", "本年累计金额"));
        addIncomeRow(rows, "一、营业收入", period.revenue, yearToDate.revenue);
        addIncomeRow(rows, "减：营业成本", period.cost, yearToDate.cost);
        addIncomeRow(rows, "税金及附加", period.taxAndSurcharge, yearToDate.taxAndSurcharge);
        addIncomeRow(rows, "二、毛利", period.grossProfit(), yearToDate.grossProfit());
        addIncomeRow(rows, "减：销售费用", period.sellingExpense, yearToDate.sellingExpense);
        addIncomeRow(rows, "减：管理费用", period.adminExpense, yearToDate.adminExpense);
        addIncomeRow(rows, "减：财务费用", period.financeExpense, yearToDate.financeExpense);
        addIncomeRow(rows, "三、营业利润", period.operatingProfit(), yearToDate.operatingProfit());
        addIncomeRow(rows, "加：营业外收入", period.nonOperatingIncome, yearToDate.nonOperatingIncome);
        addIncomeRow(rows, "减：营业外支出", period.nonOperatingExpense, yearToDate.nonOperatingExpense);
        addIncomeRow(rows, "四、利润总额", period.totalProfit(), yearToDate.totalProfit());
        addIncomeRow(rows, "减：所得税", period.incomeTax, yearToDate.incomeTax);
        addIncomeRow(rows, "五、净利润", period.netProfit(), yearToDate.netProfit());
        rows.add(List.of(SOURCE_FOOTER_TEXT));
        appendPendingDraftFooter(rows, factoryId, req.getStartDate(), req.getEndDate());

        writeRawRows(out, rows);
        String fileName = buildFileName("income-statement", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_INCOME_STATEMENT, req, userId, fileName, rows.size() - 2);
        return fileName;
    }

    @Override
    @Transactional
    public String exportQuantityAmountLedger(String factoryId, VoucherExportRequestDTO req,
                                             Long userId, OutputStream out) throws Exception {
        List<InventoryMovement> movements = buildInventoryMovements(factoryId, req);
        movements.sort(Comparator
                .comparing(InventoryMovement::date, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InventoryMovement::voucherNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InventoryMovement::summary, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, RunningInventoryBalance> runningByItem = new HashMap<>();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("日期", "凭证字号", "摘要", "收入数量", "收入单价", "收入金额",
                "发出数量", "发出单价", "发出金额", "结存数量", "结存单价", "结存金额"));

        for (InventoryMovement movement : movements) {
            RunningInventoryBalance running = runningByItem.computeIfAbsent(movement.itemKey(),
                    ignored -> new RunningInventoryBalance());
            running.quantity = running.quantity.add(nvl(movement.inQuantity())).subtract(nvl(movement.outQuantity()));
            running.amount = running.amount.add(nvl(movement.inAmount())).subtract(nvl(movement.outAmount()));
            rows.add(List.of(
                    movement.date() == null ? "" : movement.date().toString(),
                    nvlStr(movement.voucherNo()),
                    nvlStr(movement.summary()),
                    quantityText(movement.inQuantity()),
                    amountText(movement.inUnitPrice()),
                    amountText(movement.inAmount()),
                    quantityText(movement.outQuantity()),
                    amountText(movement.outUnitPrice()),
                    amountText(movement.outAmount()),
                    quantityText(running.quantity),
                    amountText(running.unitPrice()),
                    amountText(running.amount)
            ));
        }

        appendEmptyStateIfNeeded(rows, "暂无符合条件的库存数量金额流水");
        rows.add(List.of(SOURCE_FOOTER_TEXT));
        writeRawRows(out, rows);
        String fileName = buildFileName("quantity-amount-ledger", factoryId, req.getStartDate(), req.getEndDate());
        saveExportRecord(factoryId, TYPE_QUANTITY_AMOUNT_LEDGER, req, userId, fileName, movements.size());
        return fileName;
    }

    private IncomeAmounts buildIncomeAmounts(String factoryId, List<SubjectAggregateRow> aggregates) {
        Map<String, Account> accountByCode = new HashMap<>();
        for (Account account : accountRepo.findVisibleToFactory(factoryId)) {
            if (account.getCode() == null) {
                continue;
            }
            accountByCode.merge(account.getCode(), account, (existing, incoming) ->
                    existing.getFactoryId() != null ? existing : incoming);
        }

        IncomeAmounts amounts = new IncomeAmounts();
        for (SubjectAggregateRow row : aggregates) {
            String code = row.getSubjectCode();
            Account account = accountByCode.get(code);
            String name = account != null && account.getName() != null ? account.getName() : row.getSubjectName();
            AccountCategory category = account != null ? account.getCategory() : inferAccountCategory(code);

            if (isNonOperatingIncome(code, name)) {
                amounts.nonOperatingIncome = amounts.nonOperatingIncome.add(safeSubtract(row.getTotalCredit(), row.getTotalDebit()));
            } else if (isNonOperatingExpense(code, name)) {
                amounts.nonOperatingExpense = amounts.nonOperatingExpense.add(safeSubtract(row.getTotalDebit(), row.getTotalCredit()));
            } else if (isIncomeTax(code, name)) {
                amounts.incomeTax = amounts.incomeTax.add(safeSubtract(row.getTotalDebit(), row.getTotalCredit()));
            } else if (isTaxAndSurcharge(code, name)) {
                amounts.taxAndSurcharge = amounts.taxAndSurcharge.add(safeSubtract(row.getTotalDebit(), row.getTotalCredit()));
            } else if (category == AccountCategory.REVENUE) {
                amounts.revenue = amounts.revenue.add(safeSubtract(row.getTotalCredit(), row.getTotalDebit()));
            } else if (category == AccountCategory.COST || isOperatingCost(code, name)) {
                amounts.cost = amounts.cost.add(safeSubtract(row.getTotalDebit(), row.getTotalCredit()));
            } else if (category == AccountCategory.EXPENSE) {
                BigDecimal expense = safeSubtract(row.getTotalDebit(), row.getTotalCredit());
                if (isSellingExpense(code, name)) {
                    amounts.sellingExpense = amounts.sellingExpense.add(expense);
                } else if (isFinanceExpense(code, name)) {
                    amounts.financeExpense = amounts.financeExpense.add(expense);
                } else {
                    amounts.adminExpense = amounts.adminExpense.add(expense);
                }
            }
        }
        amounts.scale();
        return amounts;
    }

    private void addIncomeRow(List<List<Object>> rows, String item, BigDecimal period, BigDecimal yearToDate) {
        rows.add(List.of(item, amountText(period), amountText(yearToDate)));
    }

    private List<InventoryMovement> buildInventoryMovements(String factoryId, VoucherExportRequestDTO req) {
        List<InventoryMovement> movements = new ArrayList<>();
        LocalDateTime startTime = req.getStartDate().atStartOfDay();
        LocalDateTime endTime = req.getEndDate().plusDays(1).atStartOfDay().minusNanos(1);

        for (MaterialBatch batch : materialBatchRepo.findByFactoryId(factoryId, PageRequest.of(0, INVENTORY_EXPORT_PAGE_SIZE)).getContent()) {
            if (batch.getReceiptDate() == null || batch.getReceiptDate().isBefore(req.getStartDate())
                    || batch.getReceiptDate().isAfter(req.getEndDate())) {
                continue;
            }
            BigDecimal quantity = nvl(batch.getReceiptQuantity());
            BigDecimal unitPrice = nvl(batch.getUnitPrice());
            movements.add(InventoryMovement.inbound(
                    batch.getReceiptDate(),
                    firstNonBlank(batch.getSourceDocId(), batch.getBatchNumber()),
                    "原料入库 " + nvlStr(batch.getMaterialTypeId()) + " " + nvlStr(batch.getBatchNumber()),
                    "RAW:" + nvlStr(batch.getMaterialTypeId()),
                    quantity,
                    unitPrice,
                    quantity.multiply(unitPrice)
            ));
        }

        for (MaterialConsumption consumption : materialConsumptionRepo.findByTimeRange(factoryId, startTime, endTime)) {
            LocalDate date = consumption.getConsumptionTime() == null ? null : consumption.getConsumptionTime().toLocalDate();
            BigDecimal quantity = nvl(consumption.getQuantity());
            BigDecimal amount = nvl(consumption.getTotalCost());
            BigDecimal unitPrice = resolveUnitPrice(quantity, consumption.getUnitPrice(), amount);
            movements.add(InventoryMovement.outbound(
                    date,
                    firstNonBlank(consumption.getProductionPlanId(), consumption.getBatchId()),
                    "原料发出 " + nvlStr(consumption.getMaterialTypeId()) + " " + nvlStr(consumption.getNotes()),
                    "RAW:" + firstNonBlank(consumption.getMaterialTypeId(), consumption.getBatchId()),
                    quantity,
                    unitPrice,
                    amount
            ));
        }

        for (SemiFinishedInventory wip : semiFinishedInventoryRepo.findByFactoryIdForWeightView(factoryId)) {
            LocalDate date = wip.getCreatedAt() == null ? null : wip.getCreatedAt().toLocalDate();
            if (!within(date, req.getStartDate(), req.getEndDate())) {
                continue;
            }
            String itemKey = "WIP:" + firstNonBlank(wip.getProductTypeId(), wip.getIntermediateBatchNo());
            BigDecimal unitCost = nvl(wip.getUnitCost());
            BigDecimal produced = nvl(wip.getProducedQuantity());
            if (produced.compareTo(BigDecimal.ZERO) > 0) {
                movements.add(InventoryMovement.inbound(date, wip.getIntermediateBatchNo(),
                        "半成品入库 " + nvlStr(wip.getProductTypeId()) + " " + nvlStr(wip.getIntermediateBatchNo()),
                        itemKey, produced, unitCost, produced.multiply(unitCost)));
            }
            BigDecimal consumed = nvl(wip.getConsumedQuantity());
            if (consumed.compareTo(BigDecimal.ZERO) > 0) {
                movements.add(InventoryMovement.outbound(date, wip.getIntermediateBatchNo(),
                        "半成品发出 " + nvlStr(wip.getProductTypeId()) + " " + nvlStr(wip.getIntermediateBatchNo()),
                        itemKey, consumed, unitCost, consumed.multiply(unitCost)));
            }
        }

        for (FinishedGoodsBatch batch : finishedGoodsBatchRepo.findByFactoryIdOrderByCreatedAtDesc(
                factoryId, PageRequest.of(0, INVENTORY_EXPORT_PAGE_SIZE)).getContent()) {
            LocalDate date = batch.getProductionDate();
            if (!within(date, req.getStartDate(), req.getEndDate())) {
                continue;
            }
            String itemKey = "FG:" + firstNonBlank(batch.getProductTypeId(), batch.getBatchNumber());
            BigDecimal unitPrice = nvl(batch.getUnitPrice());
            BigDecimal produced = nvl(batch.getProducedQuantity());
            if (produced.compareTo(BigDecimal.ZERO) > 0) {
                movements.add(InventoryMovement.inbound(date, batch.getBatchNumber(),
                        "成品入库 " + firstNonBlank(batch.getProductName(), batch.getProductTypeId()),
                        itemKey, produced, unitPrice, produced.multiply(unitPrice)));
            }
            BigDecimal shipped = nvl(batch.getShippedQuantity());
            if (shipped.compareTo(BigDecimal.ZERO) > 0) {
                movements.add(InventoryMovement.outbound(date, batch.getBatchNumber(),
                        "成品发出 " + firstNonBlank(batch.getProductName(), batch.getProductTypeId()),
                        itemKey, shipped, unitPrice, shipped.multiply(unitPrice)));
            }
        }
        return movements;
    }

    private boolean within(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    private BigDecimal resolveUnitPrice(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        if (unitPrice != null) {
            return unitPrice;
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return nvl(amount).divide(quantity, 6, RoundingMode.HALF_UP);
    }

    private AccountCategory inferAccountCategory(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (code.startsWith("60") || code.startsWith("5")) {
            return AccountCategory.REVENUE;
        }
        if (code.startsWith("64")) {
            return AccountCategory.COST;
        }
        if (code.startsWith("66") || code.startsWith("67") || code.startsWith("68")) {
            return AccountCategory.EXPENSE;
        }
        return null;
    }

    private boolean isOperatingCost(String code, String name) {
        return startsWithAny(code, "6401", "6402") || containsAny(name, "营业成本", "主营业务成本");
    }

    private boolean isTaxAndSurcharge(String code, String name) {
        return startsWithAny(code, "6403") || containsAny(name, "税金及附加");
    }

    private boolean isSellingExpense(String code, String name) {
        return startsWithAny(code, "6601") || containsAny(name, "销售费用");
    }

    private boolean isFinanceExpense(String code, String name) {
        return startsWithAny(code, "6603") || containsAny(name, "财务费用");
    }

    private boolean isNonOperatingIncome(String code, String name) {
        return startsWithAny(code, "6301") || containsAny(name, "营业外收入");
    }

    private boolean isNonOperatingExpense(String code, String name) {
        return startsWithAny(code, "6711") || containsAny(name, "营业外支出");
    }

    private boolean isIncomeTax(String code, String name) {
        return startsWithAny(code, "6801") || containsAny(name, "所得税");
    }

    private boolean startsWithAny(String value, String... prefixes) {
        if (value == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... parts) {
        if (value == null) {
            return false;
        }
        for (String part : parts) {
            if (value.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        return nvl(a).subtract(nvl(b));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
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

        // F006 财务审计 Bug 6 (2026-07-04): 期初余额是上期已 CLOSED 的真实账面, 排除 DRAFT
        // (未审草稿绝不该出现在"已关账期"的期初余额里)。
        return entryRepo.aggregateBySubjectExcludingDraft(factoryId, EPOCH_START, previousMonth.atEndOfMonth());
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

    /**
     * F006 财务审计 Bug 6 (2026-07-04): {@code excludeDraft} 让调用方选择口径 ——
     * 明细账 (exportSubsidiaryLedger) 是官方账簿, 传 {@code true} 排除 DRAFT (未审草稿);
     * 序时账 (exportChronologicalLedger) 是本系统内的账簿视图, 传 {@code false} 保持 DRAFT 可见
     * (未财审草稿仍可在此追溯查看)。两者都始终排除 VOID (作废凭证从未过账, 无财务效应)。
     */
    private List<VoucherLine> loadVoucherLines(String factoryId, VoucherExportRequestDTO req, boolean excludeDraft) {
        List<Voucher> vouchers = voucherRepo.findByFactoryIdAndDateRange(
                        factoryId, req.getStartDate(), req.getEndDate()).stream()
                .filter(v -> v.getStatus() != VoucherStatus.VOID)
                .filter(v -> !excludeDraft || v.getStatus() != VoucherStatus.DRAFT)
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

    private List<VoucherImportLine> loadVoucherImportLines(String factoryId, VoucherExportRequestDTO req) {
        // 🔒🔒 出货闸 (2026-07-04): 金蝶导入模板只能携带业务确已过账 (POSTED) 的凭证。
        //
        // 旧行为无状态过滤 → DRAFT (未财审) / VOID (作废) / 幽灵凭证 全部被喂进客户真实金蝶账套。
        // 对比 loadVoucherLines (序时账) 只排 VOID (因为序时账是本系统内的账簿视图, DRAFT/REVERSED 仍应可见)。
        // 但金蝶导入模板是把数据写进客户外部真实账套 — 只有业务真正过账的凭证才允许流出:
        //   - DRAFT: generator 刚生成、财务未审核 → 绝不能进真实账套 (否则未复核草稿泄漏)
        //   - VOID:  已作废 (对应取消的订单/退货) → 幽灵, 绝不能进真实账套
        //   - REVERSED: 已被红字冲销的原凭证 → 冲销凭证 (POSTED) 自身仍会被导出对冲, 原凭证不再重复流出
        // 凭证生命周期: DRAFT (createFromBusiness) → POSTED (finance post) / 直建 POSTED (结转/红冲)。
        // 故导入模板锁定 POSTED-only。
        List<Voucher> vouchers = voucherRepo.findByFactoryIdAndDateRange(
                        factoryId, req.getStartDate(), req.getEndDate()).stream()
                .filter(v -> v.getStatus() == VoucherStatus.POSTED)
                .toList();

        List<VoucherImportLine> lines = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            List<VoucherEntry> entries = entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc(voucher.getId());
            entries.stream()
                    .sorted(Comparator.comparing(VoucherEntry::getLineNo, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(entry -> lines.add(new VoucherImportLine(
                            voucher.getVoucherDate(),
                            voucher.getVoucherNumber(),
                            entry.getDescription(),
                            entry.getSubjectCode(),
                            entry.getSubjectName(),
                            entry.getDebit(),
                            entry.getCredit(),
                            entry.getAuxiliaryType(),
                            entry.getAuxiliaryEntityId()
                    )));
        }
        return lines;
    }

    // ============================================================
    // 辅助核算 (核算名称/核算类别) 名称解析 — 把 auxiliaryEntityId(UUID/PK) 翻成实体显示名 + 类别中文.
    //
    // 修复: 之前导出把 auxiliaryEntityId 原样 (UUID) 写进 辅助核算/核算名称 列, 金蝶按名称匹配 → UUID 匹配不上;
    // auxiliaryType 也没接进导出 → 核算类别恒空. 现按 (type,id) 批量查实体名 (无 N+1), 类别翻中文.
    // 诚实原则: 实体查不到 → 留空 (绝不写 UUID). PROJECT/OUTSOURCER 无对应实体表 → 名称留空但类别仍给出.
    // ============================================================

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** 批量解析结果: key = type + "::" + id -> 名称 / 编码. */
    private record AuxIndex(Map<String, String> nameByKey, Map<String, String> codeByKey) {
        String name(AuxiliaryType type, String id) {
            return nameByKey.getOrDefault(key(type, id), "");
        }

        String code(AuxiliaryType type, String id) {
            return codeByKey.getOrDefault(key(type, id), "");
        }

        static String key(AuxiliaryType type, String id) {
            return type.name() + "::" + id;
        }
    }

    /** 批量查各辅助核算实体名 — 按 type 分组, 每 type 一次 findAllById (无 N+1). */
    private AuxIndex buildAuxIndex(String factoryId, List<VoucherImportLine> lines) {
        Map<AuxiliaryType, Set<String>> idsByType = new EnumMap<>(AuxiliaryType.class);
        for (VoucherImportLine line : lines) {
            if (line.auxiliaryType() != null && line.auxiliary() != null && !line.auxiliary().isBlank()) {
                idsByType.computeIfAbsent(line.auxiliaryType(), t -> new HashSet<>()).add(line.auxiliary());
            }
        }

        Map<String, String> nameByKey = new HashMap<>();
        Map<String, String> codeByKey = new HashMap<>();
        for (Map.Entry<AuxiliaryType, Set<String>> e : idsByType.entrySet()) {
            AuxiliaryType type = e.getKey();
            Set<String> ids = e.getValue();
            switch (type) {
                case CUSTOMER -> customerRepo.findAllById(ids).forEach(c -> {
                    if (matchesFactory(c.getFactoryId(), factoryId)) {
                        putAux(nameByKey, codeByKey, type, c.getId(), c.getName(),
                                firstNonBlank(c.getCode(), c.getCustomerCode()));
                    }
                });
                case SUPPLIER -> supplierRepo.findAllById(ids).forEach(s -> {
                    if (matchesFactory(s.getFactoryId(), factoryId)) {
                        putAux(nameByKey, codeByKey, type, s.getId(), s.getName(),
                                firstNonBlank(s.getCode(), s.getSupplierCode()));
                    }
                });
                case INVENTORY -> productTypeRepo.findAllById(ids).forEach(p -> {
                    if (matchesFactory(p.getFactoryId(), factoryId)) {
                        putAux(nameByKey, codeByKey, type, p.getId(), p.getName(), p.getCode());
                    }
                });
                case DEPT -> {
                    List<Integer> intIds = parseIntIds(ids);
                    if (!intIds.isEmpty()) {
                        departmentRepo.findAllById(intIds).forEach(d -> {
                            if (matchesFactory(d.getFactoryId(), factoryId)) {
                                putAux(nameByKey, codeByKey, type, String.valueOf(d.getId()),
                                        d.getName(), d.getCode());
                            }
                        });
                    }
                }
                case EMPLOYEE -> {
                    List<Long> longIds = parseLongIds(ids);
                    if (!longIds.isEmpty()) {
                        userRepo.findAllById(longIds).forEach(u ->
                                putAux(nameByKey, codeByKey, type, String.valueOf(u.getId()),
                                        firstNonBlank(u.getFullName(), u.getUsername()), ""));
                    }
                }
                // PROJECT / OUTSOURCER: 无对应基础资料实体表 — 名称无法解析, 诚实留空 (类别仍会给出).
                case PROJECT, OUTSOURCER -> { }
            }
        }
        return new AuxIndex(nameByKey, codeByKey);
    }

    private void putAux(Map<String, String> nameByKey, Map<String, String> codeByKey,
                        AuxiliaryType type, String id, String name, String code) {
        if (id == null) {
            return;
        }
        String key = AuxIndex.key(type, id);
        if (name != null && !name.isBlank()) {
            nameByKey.put(key, name);
        }
        if (code != null && !code.isBlank()) {
            codeByKey.put(key, code);
        }
    }

    /** (type,id) → 核算类别中文 / 核算名称 / 核算编码. 见类顶部说明的诚实原则. */
    private ResolvedAux resolveAuxDisplay(AuxIndex auxIndex, AuxiliaryType type, String rawId) {
        if (type != null) {
            return new ResolvedAux(categoryLabel(type), auxIndex.name(type, rawId), auxIndex.code(type, rawId));
        }
        // 无结构化 type (legacy 自由文本). UUID 样式 → 留空 (绝不导出 UUID); 其余当自由文本原样透出.
        if (rawId == null || rawId.isBlank() || UUID_PATTERN.matcher(rawId).matches()) {
            return new ResolvedAux("", "", "");
        }
        return new ResolvedAux("", rawId, "");
    }

    /** 云星空单列辅助核算: 类别+名称 → "客户:张三"; 仅名称(legacy) → 名称; 都无 → 空 (诚实, 不写 UUID). */
    private String yxskyAuxiliaryCell(ResolvedAux aux) {
        if (!aux.category().isBlank() && !aux.name().isBlank()) {
            return aux.category() + ":" + aux.name();
        }
        if (!aux.name().isBlank()) {
            return aux.name();
        }
        return "";
    }

    private static String categoryLabel(AuxiliaryType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case CUSTOMER -> "客户";
            case SUPPLIER -> "供应商";
            case DEPT -> "部门";
            case EMPLOYEE -> "职员";
            case PROJECT -> "项目";
            case INVENTORY -> "存货";
            case OUTSOURCER -> "委外商";
        };
    }

    private boolean matchesFactory(String entityFactoryId, String factoryId) {
        // 实体无 factoryId (跨工厂共享) 时不阻断; 有则必须一致, 防跨租户名称泄漏.
        return entityFactoryId == null || entityFactoryId.equals(factoryId);
    }

    private List<Integer> parseIntIds(Set<String> ids) {
        List<Integer> result = new ArrayList<>();
        for (String id : ids) {
            try {
                result.add(Integer.valueOf(id.trim()));
            } catch (NumberFormatException ignored) {
                // 非整数 id 无法对应 Department PK — 跳过 (名称留空, 诚实).
            }
        }
        return result;
    }

    private List<Long> parseLongIds(Set<String> ids) {
        List<Long> result = new ArrayList<>();
        for (String id : ids) {
            try {
                result.add(Long.valueOf(id.trim()));
            } catch (NumberFormatException ignored) {
                // 非 Long id 无法对应 User PK — 跳过.
            }
        }
        return result;
    }

    private String blankIfZero(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) {
            return "";
        }
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
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
                .orElseGet(() -> defaultExportConfig(factoryId, targetSystem));
    }

    private VoucherExportConfig defaultExportConfig(String factoryId, VoucherTargetSystem targetSystem) {
        VoucherTargetSystem resolvedTarget = targetSystem != null ? targetSystem : VoucherTargetSystem.KINGDEE;
        VoucherExportConfig.VoucherExportConfigBuilder builder = VoucherExportConfig.builder()
                .factoryId(factoryId)
                .targetSystem(resolvedTarget);
        if (resolvedTarget == VoucherTargetSystem.KINGDEE_YXSKY) {
            builder.colVoucherNo("凭证号")
                    .colDate("日期")
                    .colSummary("摘要")
                    .colSubjectCode("科目编码")
                    .colSubjectName("科目名称")
                    .colDebit("借方金额")
                    .colCredit("贷方金额")
                    .colCurrency("币别")
                    .colAuxiliary("辅助核算");
        } else if (resolvedTarget == VoucherTargetSystem.KINGDEE_KIS) {
            // KIS/K3 canonical field naming differs from云星空 (核算名称 not 辅助核算) — see
            // buildKisImportRows() javadoc for the full column-set citation.
            builder.colVoucherNo("凭证号")
                    .colDate("日期")
                    .colSummary("摘要")
                    .colSubjectCode("科目编码")
                    .colSubjectName("科目名称")
                    .colDebit("借方金额")
                    .colCredit("贷方金额")
                    .colCurrency("币别")
                    .colAuxiliary("核算名称");
        }
        return builder.build();
    }

    private void writeRawRows(OutputStream out, List<List<Object>> rows) {
        writeRawRows(out, rows, "Sheet1");
    }

    private void writeRawRows(OutputStream out, List<List<Object>> rows, String sheetName) {
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
        WriteSheet sheet = EasyExcel.writerSheet(sheetName).build();
        writer.write(dataRows, sheet);
        writer.finish();
    }

    /**
     * 写 legacy .xls (BIFF/HSSF) — 金蝶 KIS专业版 "引入标准格式凭证" 认 .xls 不认 .xlsx.
     * 所有 KIS/云星空 导入模板单元格本就是字符串 (金额经 blankIfZero → String), 统一按字符串写入.
     */
    private void writeRawRowsXls(OutputStream out, List<List<Object>> rows, String sheetName)
            throws java.io.IOException {
        if (rows.isEmpty()) {
            return;
        }
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<Object> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Object v = cells.get(c);
                    row.createCell(c).setCellValue(v == null ? "" : v.toString());
                }
            }
            workbook.write(out);
        }
    }

    private void appendEmptyStateIfNeeded(List<List<Object>> rows, String message) {
        if (rows.size() == 1) {
            rows.add(List.of(message));
        }
    }

    private void appendSourceFooter(List<List<Object>> rows) {
        rows.add(List.of("凭证来源口径=" + VOUCHER_SOURCE_CALIBER_PENDING));
    }

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 官方报表已切 POSTED-only, 本行告知财务人员期间内还有
     * 多少张 DRAFT (待审) 凭证<b>未计入</b>本报表 —— 防止"报表数字看着少/为0"却不知道为什么
     * (fool-proof-design Rule 5: 空/低数据必须给出原因 + next action, 而不是让人猜)。
     */
    private void appendPendingDraftFooter(List<List<Object>> rows, String factoryId,
                                          LocalDate startDate, LocalDate endDate) {
        long pendingCount = voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                factoryId, VoucherStatus.DRAFT, startDate, endDate);
        rows.add(List.of("本报表仅含已过账(POSTED)凭证; 另有 " + pendingCount + " 张凭证待过账(未计入本报表)"));
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
        return buildFileName(prefix, factoryId, start, end, "xlsx");
    }

    private String buildFileName(String prefix, String factoryId,
                                  LocalDate start, LocalDate end, String ext) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("%s_%s_%s_%s_%s.%s", prefix, factoryId,
                start.toString().replace("-", ""), end.toString().replace("-", ""), ts, ext);
    }

    private BigDecimal scale2(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String amountText(BigDecimal v) {
        return scale2(v).toPlainString();
    }

    private BigDecimal scale3(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        return v.setScale(3, RoundingMode.HALF_UP);
    }

    private String quantityText(BigDecimal v) {
        return scale3(v).toPlainString();
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

    private record VoucherImportLine(
            LocalDate date,
            String voucherNumber,
            String summary,
            String subjectCode,
            String subjectName,
            BigDecimal debit,
            BigDecimal credit,
            AuxiliaryType auxiliaryType,
            String auxiliary) {
    }

    /** 解析后的辅助核算三元组: 核算类别中文名 / 核算名称 / 核算编码. 任一无法解析则该项为空串 (诚实, 绝不回退 UUID). */
    private record ResolvedAux(String category, String name, String code) {
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

    private static class IncomeAmounts {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal taxAndSurcharge = BigDecimal.ZERO;
        private BigDecimal sellingExpense = BigDecimal.ZERO;
        private BigDecimal adminExpense = BigDecimal.ZERO;
        private BigDecimal financeExpense = BigDecimal.ZERO;
        private BigDecimal nonOperatingIncome = BigDecimal.ZERO;
        private BigDecimal nonOperatingExpense = BigDecimal.ZERO;
        private BigDecimal incomeTax = BigDecimal.ZERO;

        private BigDecimal grossProfit() {
            return revenue.subtract(cost);
        }

        private BigDecimal operatingProfit() {
            return grossProfit()
                    .subtract(taxAndSurcharge)
                    .subtract(sellingExpense)
                    .subtract(adminExpense)
                    .subtract(financeExpense);
        }

        private BigDecimal totalProfit() {
            return operatingProfit().add(nonOperatingIncome).subtract(nonOperatingExpense);
        }

        private BigDecimal netProfit() {
            return totalProfit().subtract(incomeTax);
        }

        private void scale() {
            revenue = revenue.setScale(2, RoundingMode.HALF_UP);
            cost = cost.setScale(2, RoundingMode.HALF_UP);
            taxAndSurcharge = taxAndSurcharge.setScale(2, RoundingMode.HALF_UP);
            sellingExpense = sellingExpense.setScale(2, RoundingMode.HALF_UP);
            adminExpense = adminExpense.setScale(2, RoundingMode.HALF_UP);
            financeExpense = financeExpense.setScale(2, RoundingMode.HALF_UP);
            nonOperatingIncome = nonOperatingIncome.setScale(2, RoundingMode.HALF_UP);
            nonOperatingExpense = nonOperatingExpense.setScale(2, RoundingMode.HALF_UP);
            incomeTax = incomeTax.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static class RunningInventoryBalance {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;

        private BigDecimal unitPrice() {
            if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return amount.divide(quantity, 6, RoundingMode.HALF_UP);
        }
    }

    private record InventoryMovement(
            LocalDate date,
            String voucherNo,
            String summary,
            String itemKey,
            BigDecimal inQuantity,
            BigDecimal inUnitPrice,
            BigDecimal inAmount,
            BigDecimal outQuantity,
            BigDecimal outUnitPrice,
            BigDecimal outAmount) {

        private static InventoryMovement inbound(LocalDate date, String voucherNo, String summary, String itemKey,
                                                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
            return new InventoryMovement(date, voucherNo, summary, itemKey,
                    quantity, unitPrice, amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        private static InventoryMovement outbound(LocalDate date, String voucherNo, String summary, String itemKey,
                                                  BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
            return new InventoryMovement(date, voucherNo, summary, itemKey,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, quantity, unitPrice, amount);
        }
    }
}
