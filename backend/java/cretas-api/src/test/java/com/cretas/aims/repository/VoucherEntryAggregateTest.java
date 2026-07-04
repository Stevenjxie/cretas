package com.cretas.aims.repository;

import com.cretas.aims.dto.finance.AuxiliaryAggregateRow;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import com.cretas.aims.dto.finance.SubjectAggregateRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 6 W4-A: 验证 VoucherEntryRepository.aggregateByAuxiliary 聚合 SQL
 * 正确性 — 按 (auxiliaryType, auxiliaryEntityId) 分组、计算 SUM(debit/credit)、
 * netBalance、entryCount, 同时尊重 factory_id / voucherDate 范围 / status != VOID
 * 过滤.
 *
 * <p>使用 H2 PG-compat in-memory DB (与 PricingEngineIntegrationTest 同 slice 模式).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("VoucherEntryRepository.aggregateByAuxiliary — JPA slice integration")
class VoucherEntryAggregateTest {

    private static final String F1 = "F-AGG-1";
    private static final String F2 = "F-AGG-2";  // different factory; isolation check

    @Autowired private VoucherRepository voucherRepo;
    @Autowired private VoucherEntryRepository entryRepo;

    @Test
    @DisplayName("3 客户 × 2-3 凭证 → CUSTOMER 聚合返 3 行, netBalance 正确, factory 隔离")
    void aggregateByCustomer_groupsCorrectlyAndIsolatesFactory() {
        // F1 — 客户 A: 2 借方 (应收挂账) + 1 贷方 (收款) → net debit 7000 (3000+5000-1000)
        persistVoucher(F1, LocalDate.of(2026, 5, 1), VoucherStatus.POSTED,
                debitAux("1122", "cust-A", "3000"));
        persistVoucher(F1, LocalDate.of(2026, 5, 2), VoucherStatus.POSTED,
                debitAux("1122", "cust-A", "5000"));
        persistVoucher(F1, LocalDate.of(2026, 5, 3), VoucherStatus.DRAFT,
                creditAux("1122", "cust-A", "1000"));

        // F1 — 客户 B: 1 借方 → net 200
        persistVoucher(F1, LocalDate.of(2026, 5, 4), VoucherStatus.POSTED,
                debitAux("1122", "cust-B", "200"));

        // F1 — 客户 C: 1 借方 → net 100 (但被 VOID 排除)
        persistVoucher(F1, LocalDate.of(2026, 5, 5), VoucherStatus.VOID,
                debitAux("1122", "cust-C", "100"));

        // F1 — 客户 D: 在期间外 (不应统计)
        persistVoucher(F1, LocalDate.of(2026, 6, 10), VoucherStatus.POSTED,
                debitAux("1122", "cust-D", "999"));

        // F2 — 客户 E: 不同 factory (不应统计 F1)
        persistVoucher(F2, LocalDate.of(2026, 5, 1), VoucherStatus.POSTED,
                debitAux("1122", "cust-E", "8888"));

        // Aggregate F1 CUSTOMER for May 2026
        List<AuxiliaryAggregateRow> rows = entryRepo.aggregateByAuxiliary(
                F1, AuxiliaryType.CUSTOMER, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        // 应包含 cust-A + cust-B (2 行). cust-C VOID 排除, cust-D 期间外排除, cust-E F2 排除.
        assertEquals(2, rows.size(), "应只含 cust-A + cust-B (VOID/期间外/跨工厂全排除)");

        // Order by entryCount DESC → cust-A (3 entries) 在前
        AuxiliaryAggregateRow custA = rows.get(0);
        assertEquals(AuxiliaryType.CUSTOMER, custA.getAuxiliaryType());
        assertEquals("cust-A", custA.getAuxiliaryEntityId());
        assertEquals(0, new BigDecimal("8000").compareTo(custA.getTotalDebit()),
                "cust-A debit = 3000 + 5000 = 8000");
        assertEquals(0, new BigDecimal("1000").compareTo(custA.getTotalCredit()),
                "cust-A credit = 1000");
        assertEquals(0, new BigDecimal("7000").compareTo(custA.getNetBalance()),
                "cust-A net = 8000 - 1000 = 7000 (借方余额, 客户欠款)");
        assertEquals(3L, custA.getEntryCount());

        AuxiliaryAggregateRow custB = rows.get(1);
        assertEquals("cust-B", custB.getAuxiliaryEntityId());
        assertEquals(0, new BigDecimal("200").compareTo(custB.getNetBalance()));
        assertEquals(1L, custB.getEntryCount());
    }

    @Test
    @DisplayName("SUPPLIER 聚合 — 应付账款贷方为正余额表'我们欠供应商'")
    void aggregateBySupplier_creditPositiveMeansWeOwe() {
        persistVoucher(F1, LocalDate.of(2026, 5, 1), VoucherStatus.POSTED,
                creditAux("2202", "sup-X", "5000"));
        persistVoucher(F1, LocalDate.of(2026, 5, 10), VoucherStatus.POSTED,
                debitAux("2202", "sup-X", "1500"));  // 付款冲减

        List<AuxiliaryAggregateRow> rows = entryRepo.aggregateByAuxiliary(
                F1, AuxiliaryType.SUPPLIER, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertEquals(1, rows.size());
        AuxiliaryAggregateRow supX = rows.get(0);
        assertEquals("sup-X", supX.getAuxiliaryEntityId());
        assertEquals(0, new BigDecimal("1500").compareTo(supX.getTotalDebit()));
        assertEquals(0, new BigDecimal("5000").compareTo(supX.getTotalCredit()));
        // netBalance = debit - credit = 1500 - 5000 = -3500 (贷方余额, 我们欠 3500)
        assertEquals(0, new BigDecimal("-3500").compareTo(supX.getNetBalance()));
    }

    @Test
    @DisplayName("空结果 — 期间无任何凭证返空 list (非 null)")
    void aggregateReturnsEmptyListWhenNoMatch() {
        List<AuxiliaryAggregateRow> rows = entryRepo.aggregateByAuxiliary(
                F1, AuxiliaryType.PROJECT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "无匹配应返空 list");
    }

    @Test
    @DisplayName("无辅助核算的分录不参与聚合 (auxiliary_entity_id IS NULL)")
    void aggregateExcludesUnauxiliariedEntries() {
        // 一个 voucher: 借方挂 INVENTORY=mat-1, 贷方无辅助 (normal cost_center)
        Voucher v = makeVoucher(F1, LocalDate.of(2026, 5, 1), VoucherStatus.POSTED);
        VoucherEntry debit = VoucherEntry.builder()
                .lineNo(1).subjectCode("1405").subjectName("库存商品")
                .debit(new BigDecimal("500")).credit(BigDecimal.ZERO)
                .auxiliaryType(AuxiliaryType.INVENTORY).auxiliaryEntityId("mat-1")
                .voucher(v).build();
        VoucherEntry credit = VoucherEntry.builder()
                .lineNo(2).subjectCode("6602.01").subjectName("管理费用")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("500"))
                .voucher(v).build();
        v.setTotalDebit(new BigDecimal("500"));
        v.setTotalCredit(new BigDecimal("500"));
        v.setEntries(List.of(debit, credit));
        voucherRepo.saveAndFlush(v);

        List<AuxiliaryAggregateRow> rows = entryRepo.aggregateByAuxiliary(
                F1, AuxiliaryType.INVENTORY, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        // Only the debit line (mat-1) should appear; credit line ignored
        assertEquals(1, rows.size());
        assertEquals("mat-1", rows.get(0).getAuxiliaryEntityId());
        assertEquals(1L, rows.get(0).getEntryCount());
        assertEquals(0, new BigDecimal("500").compareTo(rows.get(0).getNetBalance()));
    }

    @Test
    @DisplayName("aggregateBySubjectPosted 排除 PL_CLOSING 凭证 — 结转/红冲镜像不污染再结转聚合 (reopen→reclose 双计 bug)")
    void aggregateBySubjectPosted_excludesClosingVouchers() {
        final String F = "F-PLX";
        final LocalDate may = LocalDate.of(2026, 5, 15);
        final LocalDate end = LocalDate.of(2026, 5, 31);

        // 业务凭证: 收入 6001 贷 1000, 成本 6401 借 600 → 净利 400, 必须参与结转聚合
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.POSTED, may,
                line("6001", "0", "1000"), line("1122", "1000", "0"));
        persistPnl(F, VoucherType.EXPENSE, VoucherStatus.POSTED, may,
                line("6401", "600", "0"), line("1122", "0", "600"));

        // 结转凭证 (PL_CLOSING POSTED): 借6001 1000 / 贷6401 600 / 贷4103 400 — 结转产物, 必须排除
        persistPnl(F, VoucherType.PL_CLOSING, VoucherStatus.POSTED, end,
                line("6001", "1000", "0"), line("6401", "0", "600"), line("4103", "0", "400"));
        // 红冲镜像 (PL_CLOSING POSTED, reopen 反结账产生): 借贷互换 — 也必须排除
        // 旧 bug: 原结转被置 REVERSED 排除, 但镜像 POSTED 被计入 → 再结转双计 6xxx
        persistPnl(F, VoucherType.PL_CLOSING, VoucherStatus.POSTED, end,
                line("6001", "0", "1000"), line("6401", "600", "0"), line("4103", "400", "0"));

        List<SubjectAggregateRow> rows = entryRepo.aggregateBySubjectPosted(
                F, LocalDate.of(2026, 5, 1), end);
        Map<String, SubjectAggregateRow> byCode = rows.stream()
                .collect(Collectors.toMap(SubjectAggregateRow::getSubjectCode, r -> r));

        // 6001 只剩业务收入 (贷 1000, 借 0) — 不含结转的借1000/镜像的贷1000
        assertNotNull(byCode.get("6001"), "6001 应有业务行");
        assertEquals(0, new BigDecimal("1000").compareTo(byCode.get("6001").getTotalCredit()), "6001 业务贷=1000");
        assertEquals(0, BigDecimal.ZERO.compareTo(byCode.get("6001").getTotalDebit()), "6001 不含结转/红冲借方");
        // 6401 只剩业务成本 (借 600, 贷 0)
        assertEquals(0, new BigDecimal("600").compareTo(byCode.get("6401").getTotalDebit()), "6401 业务借=600");
        assertEquals(0, BigDecimal.ZERO.compareTo(byCode.get("6401").getTotalCredit()), "6401 不含结转贷方");
        // 4103 只存在于被排除的结转凭证 → 聚合不应出现
        assertFalse(byCode.containsKey("4103"), "4103 仅在结转凭证里, 排除后聚合不应出现");
    }

    @Test
    @DisplayName("aggregateBySubjectPosted 差异化 COST_CARRYOVER — 主凭证 6401 计入 (进 4103), 红冲镜像排除 (reopen→reclose 不抵消)")
    void aggregateBySubjectPosted_includesCostCarryoverPrimary_excludesMirror() {
        final String F = "F-CCX";
        final LocalDate end = LocalDate.of(2026, 5, 31);

        // 结转成本 主凭证 (POSTED, originalVoucherId=null): 借6401 600 / 贷1405 600 — 真实 COGS, 必须计入
        persistPnl(F, VoucherType.COST_CARRYOVER, VoucherStatus.POSTED, end,
                line("6401", "600", "0"), line("1405", "0", "600"));
        // 结转成本 红冲镜像 (POSTED, originalVoucherId 非空, reopen 反结账产生): 借1405 600 / 贷6401 600
        //   旧隐患: 若不排镜像, 再结转时 6401 的 贷600 会抵消主凭证的 借600 → 净 0, COGS 丢失
        persistPnlWithOriginal(F, VoucherType.COST_CARRYOVER, VoucherStatus.POSTED, end, "orig-cc-1",
                line("6401", "0", "600"), line("1405", "600", "0"));

        List<SubjectAggregateRow> rows = entryRepo.aggregateBySubjectPosted(
                F, LocalDate.of(2026, 5, 1), end);
        Map<String, SubjectAggregateRow> byCode = rows.stream()
                .collect(Collectors.toMap(SubjectAggregateRow::getSubjectCode, r -> r));

        // 6401: 只剩主凭证的 借 600 (镜像的贷 600 被排), 净额供结转损益进 4103
        assertNotNull(byCode.get("6401"), "6401 主凭证应计入 (COGS 进 4103)");
        assertEquals(0, new BigDecimal("600").compareTo(byCode.get("6401").getTotalDebit()),
                "6401 借=600 (主凭证计入)");
        assertEquals(0, BigDecimal.ZERO.compareTo(byCode.get("6401").getTotalCredit()),
                "6401 贷=0 (红冲镜像被排, 不抵消主凭证 → reopen→reclose 不丢 COGS)");
        // 1405: 同理只剩主凭证的 贷 600
        assertEquals(0, new BigDecimal("600").compareTo(byCode.get("1405").getTotalCredit()),
                "1405 贷=600 (主凭证)");
        assertEquals(0, BigDecimal.ZERO.compareTo(byCode.get("1405").getTotalDebit()),
                "1405 借=0 (镜像被排)");
    }

    @Test
    @DisplayName("F006 财务审计 Bug 6 (2026-07-04): aggregateBySubjectExcludingDraft 排除 DRAFT, 保留 REVERSED+红冲镜像正确抵消")
    void aggregateBySubjectExcludingDraft_excludesDraftButKeepsReversedPairNetting() {
        final String F = "F-EXDRAFT";
        final LocalDate d = LocalDate.of(2026, 5, 10);

        // 业务凭证 (POSTED): 1001 借 1000 — 必须计入
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.POSTED, d,
                line("1001", "1000", "0"), line("6001", "0", "1000"));

        // 已红字冲销的原凭证 (REVERSED, 金蝶规范: 财务效应不清零, 必须计入) + 其冲销镜像 (POSTED, 借贷互换)
        // 二者合起来应净额为 0 (等价于"该笔交易从未发生"), 而不是被 REVERSED 状态整体排除.
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.REVERSED, d,
                line("1001", "500", "0"), line("6001", "0", "500"));
        persistPnlWithOriginal(F, VoucherType.SALES_RECEIPT, VoucherStatus.POSTED, d, "orig-reversed-1",
                line("1001", "0", "500"), line("6001", "500", "0"));

        // 待审草稿 (DRAFT): 1001 借 9999 — 未经财务审核, 绝不能计入官方报表
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.DRAFT, d,
                line("1001", "9999", "0"), line("6001", "0", "9999"));

        List<SubjectAggregateRow> rows = entryRepo.aggregateBySubjectExcludingDraft(
                F, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        Map<String, SubjectAggregateRow> byCode = rows.stream()
                .collect(Collectors.toMap(SubjectAggregateRow::getSubjectCode, r -> r));

        SubjectAggregateRow acc1001 = byCode.get("1001");
        assertNotNull(acc1001, "1001 应有聚合行");
        // debit = 1000 (业务) + 500 (REVERSED 原凭证, 仍计入) = 1500; DRAFT 的 9999 绝不计入
        assertEquals(0, new BigDecimal("1500").compareTo(acc1001.getTotalDebit()),
                "1001 借方 = 1000(业务) + 500(REVERSED 原凭证) — 不含 DRAFT 的 9999");
        // credit = 500 (冲销镜像)
        assertEquals(0, new BigDecimal("500").compareTo(acc1001.getTotalCredit()),
                "1001 贷方 = 500(冲销镜像)");
        // net = 1500 - 500 = 1000 = 纯业务发生额 (红字冲销那笔净额归零, 未被"倍数冲销")
        assertEquals(0, new BigDecimal("1000").compareTo(
                        acc1001.getTotalDebit().subtract(acc1001.getTotalCredit())),
                "净额=1000, 证明 REVERSED+镜像正确抵消为0, 不是被排除REVERSED后镜像单边多计成'倍数冲销'");
    }

    @Test
    @DisplayName("F006 财务审计 Bug 6 (2026-07-04): aggregateBySubjectExcludingDraftAndClosing 排除结转噪音, 保留 REVERSED 抵消")
    void aggregateBySubjectExcludingDraftAndClosing_excludesClosingAndDraft_keepsReversedPair() {
        final String F = "F-EXDRAFTCLOSE";
        final LocalDate may = LocalDate.of(2026, 5, 15);
        final LocalDate end = LocalDate.of(2026, 5, 31);

        // 业务凭证: 收入 6001 贷 1000
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.POSTED, may,
                line("6001", "0", "1000"), line("1122", "1000", "0"));

        // 结转凭证 (PL_CLOSING POSTED): 借6001 1000 — 结转产物, 必须排除 (否则已结账期利润表显示营收=0)
        persistPnl(F, VoucherType.PL_CLOSING, VoucherStatus.POSTED, end,
                line("6001", "1000", "0"), line("4103", "0", "1000"));

        // 待审草稿: 6001 贷 9999 — 不计入
        persistPnl(F, VoucherType.SALES_RECEIPT, VoucherStatus.DRAFT, may,
                line("6001", "0", "9999"), line("1122", "9999", "0"));

        List<SubjectAggregateRow> rows = entryRepo.aggregateBySubjectExcludingDraftAndClosing(
                F, LocalDate.of(2026, 5, 1), end);
        Map<String, SubjectAggregateRow> byCode = rows.stream()
                .collect(Collectors.toMap(SubjectAggregateRow::getSubjectCode, r -> r));

        SubjectAggregateRow acc6001 = byCode.get("6001");
        assertNotNull(acc6001, "6001 应有业务行 (未被结转清零, 未含 DRAFT)");
        assertEquals(0, new BigDecimal("1000").compareTo(acc6001.getTotalCredit()),
                "6001 贷=1000 (纯业务, 不含 DRAFT 的 9999)");
        assertEquals(0, BigDecimal.ZERO.compareTo(acc6001.getTotalDebit()),
                "6001 借=0 (结转凭证的借1000被voucherType排除, 不是被状态排除)");
        assertFalse(byCode.containsKey("4103"), "4103 仅出现在结转凭证 — 排除后不应出现");
    }

    private String[] line(String code, String debit, String credit) {
        return new String[]{code, debit, credit};
    }

    /** 持久化一个带 originalVoucherId 的凭证 (模拟红冲镜像). */
    @SafeVarargs
    private void persistPnlWithOriginal(String factoryId, VoucherType type, VoucherStatus status,
            LocalDate date, String originalVoucherId, String[]... lines) {
        Voucher v = makeVoucher(factoryId, date, status);
        v.setVoucherType(type);
        v.setOriginalVoucherId(originalVoucherId);
        List<VoucherEntry> entries = new ArrayList<>();
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        int ln = 1;
        for (String[] l : lines) {
            BigDecimal d = new BigDecimal(l[1]);
            BigDecimal c = new BigDecimal(l[2]);
            entries.add(VoucherEntry.builder().lineNo(ln++).subjectCode(l[0]).subjectName(l[0])
                    .debit(d).credit(c).voucher(v).build());
            td = td.add(d);
            tc = tc.add(c);
        }
        v.setTotalDebit(td);
        v.setTotalCredit(tc);
        v.setEntries(entries);
        voucherRepo.saveAndFlush(v);
    }

    /** 持久化一个 P&L 凭证 (指定 voucherType/status + 任意分录行). */
    @SafeVarargs
    private void persistPnl(String factoryId, VoucherType type, VoucherStatus status,
            LocalDate date, String[]... lines) {
        Voucher v = makeVoucher(factoryId, date, status);
        v.setVoucherType(type);
        List<VoucherEntry> entries = new ArrayList<>();
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        int ln = 1;
        for (String[] l : lines) {
            BigDecimal d = new BigDecimal(l[1]);
            BigDecimal c = new BigDecimal(l[2]);
            entries.add(VoucherEntry.builder().lineNo(ln++).subjectCode(l[0]).subjectName(l[0])
                    .debit(d).credit(c).voucher(v).build());
            td = td.add(d);
            tc = tc.add(c);
        }
        v.setTotalDebit(td);
        v.setTotalCredit(tc);
        v.setEntries(entries);
        voucherRepo.saveAndFlush(v);
    }

    // ==================== Helpers ====================

    /** Single-aux debit entry (subject code → 自动选 auxiliary type 按映射约定). */
    private VoucherEntry debitAux(String subjCode, String auxId, String amount) {
        return VoucherEntry.builder()
                .lineNo(1).subjectCode(subjCode).subjectName(subjCode)
                .debit(new BigDecimal(amount)).credit(BigDecimal.ZERO)
                .auxiliaryType(auxTypeForSubject(subjCode))
                .auxiliaryEntityId(auxId)
                .build();
    }

    private VoucherEntry creditAux(String subjCode, String auxId, String amount) {
        return VoucherEntry.builder()
                .lineNo(1).subjectCode(subjCode).subjectName(subjCode)
                .debit(BigDecimal.ZERO).credit(new BigDecimal(amount))
                .auxiliaryType(auxTypeForSubject(subjCode))
                .auxiliaryEntityId(auxId)
                .build();
    }

    /** 1122 应收 → CUSTOMER, 2202 应付 → SUPPLIER, 1405 库存 → INVENTORY, etc. */
    private AuxiliaryType auxTypeForSubject(String code) {
        return switch (code) {
            case "1122" -> AuxiliaryType.CUSTOMER;
            case "2202" -> AuxiliaryType.SUPPLIER;
            case "1405" -> AuxiliaryType.INVENTORY;
            case "2211" -> AuxiliaryType.EMPLOYEE;
            case "6602.02" -> AuxiliaryType.DEPT;
            default -> AuxiliaryType.CUSTOMER;
        };
    }

    /**
     * 创建一个 single-entry voucher + 平衡补行 (cost_center, no auxiliary)
     * 用于聚合测试. saveAndFlush 后 cascade 写 entries.
     */
    private void persistVoucher(String factoryId, LocalDate date, VoucherStatus status,
            VoucherEntry auxEntry) {
        Voucher v = makeVoucher(factoryId, date, status);
        auxEntry.setVoucher(v);

        // Balance compensating line (no auxiliary — opposite side)
        BigDecimal amount = auxEntry.getDebit().compareTo(BigDecimal.ZERO) > 0
                ? auxEntry.getDebit() : auxEntry.getCredit();
        boolean auxOnDebit = auxEntry.getDebit().compareTo(BigDecimal.ZERO) > 0;
        VoucherEntry balance = VoucherEntry.builder()
                .lineNo(2).subjectCode("6001").subjectName("Balance")
                .debit(auxOnDebit ? BigDecimal.ZERO : amount)
                .credit(auxOnDebit ? amount : BigDecimal.ZERO)
                .voucher(v)
                .build();

        v.setTotalDebit(amount);
        v.setTotalCredit(amount);
        v.setEntries(List.of(auxEntry, balance));
        voucherRepo.saveAndFlush(v);
    }

    private Voucher makeVoucher(String factoryId, LocalDate date, VoucherStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return Voucher.builder()
                .factoryId(factoryId)
                .voucherNumber("V-AGG-" + suffix)
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(date)
                .sourceBusinessType("TEST")
                .sourceBusinessId("test-" + suffix)
                .status(status)
                .totalDebit(BigDecimal.ZERO)
                .totalCredit(BigDecimal.ZERO)
                .build();
    }
}
