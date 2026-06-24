# 结转损益自动凭证 P1 (月末结转核心 + 锁定触发) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 月末损益类(6xxx)在期间硬锁(LOCKED)时自动结转到本年利润(4103)，让凭证三大报表真账化；含反结账红冲。

**Architecture:** 新建 `ProfitLossClosingService.closePeriod`（按 POSTED 余额方向把 6xxx 结平到 4103，5xxx 不动）+ `reversePeriodClosing`（复用现有红冲）。结转由 finalize scheduler 在 `AdjustWindowState==LOCKED` 时触发（非软结账），+ 手工"立即锁定结转"逃生口。新增 `VoucherService.createManual` 直建 POSTED 凭证（绕期间 gate）。幂等以 `AccountingPeriod.closingPostedAt` 为准。

**Tech Stack:** Java 21, Spring Boot 3.2, JPA/Hibernate, PostgreSQL, Flyway, JUnit 5 + Mockito, Maven。

**前置规则:** worktree 已在 `C:\Users\Steve\cretas-pl-closing` (branch `feat/pl-closing`, off origin/main)。🔒 红线 (GL 真账/全租户)：每 PR 前 `git diff origin/main...HEAD --stat` + Flyway 撞号检查；created_by 走线程参禁 SecurityUtils；从 main 部署 + F006 活体验证，绝不碰 LIUSHANMEN。spec: `docs/superpowers/specs/2026-06-24-profit-loss-closing-design.md`。

测试运行约定：`mvn` 在 `C:\tools\apache-maven-3.9.6\bin`；PATH 加它后在 `backend/java/cretas-api` 下 `mvn -q -o test "-Dtest=<Class>"`，结果看 `target/surefire-reports/<fqcn>.txt`。

---

## File Structure

**Create:**
- `db/flyway/V20261027_15__voucher_type_check_add_pl_closing.sql` — 放开 voucher_type CHECK
- `db/flyway/V20261027_16__accounting_period_closing_posted_at.sql` — 加 closing_posted_at 列
- `dto/finance/VoucherEntrySpec.java` — createManual 入参分录 record
- `service/finance/ProfitLossClosingService.java` — 结转核心
- `service/finance/impl/ProfitLossClosingServiceImpl.java`
- `service/finance/ProfitLossClosingServiceTest.java` (test)
- `service/voucher/impl/VoucherServiceManualTest.java` (test for createManual)

**Modify:**
- `entity/enums/VoucherType.java` — 加 PL_CLOSING
- `service/voucher/impl/VoucherServiceImpl.java:138-149` — mapLinkType 加 case
- `service/voucher/VoucherService.java` — 加 createManual 签名
- `service/voucher/impl/VoucherServiceImpl.java` — 加 createManual 实现
- `repository/VoucherEntryRepository.java` — 加 aggregateBySubjectPosted
- `entity/finance/AccountingPeriod.java` — 加 closingPostedAt 字段
- `service/finance/impl/AccountingPeriodServiceImpl.java:144-170` — reopenPeriod 接 reversePeriodClosing
- `scheduler/AccountingPeriodScheduler.java` — 加 finalizeLockedPeriods 任务
- `service/finance/AccountingPeriodService.java` + impl — 加 forceLockAndClose（手工逃生口）
- `controller/finance/MonthCloseController.java` (或 AccountingPeriodController) — 加 force-lock 端点

---

## Task 1: Flyway 迁移 — voucher_type CHECK + closing_posted_at

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_15__voucher_type_check_add_pl_closing.sql`
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_16__accounting_period_closing_posted_at.sql`

- [ ] **Step 1: 撞号检查**

Run:
```bash
cd /c/Users/Steve/cretas-pl-closing && git ls-tree -r --name-only origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE "V20261027_1[56]" | sort
```
Expected: 无输出 (15/16 未被占用)。若有输出 → 改用下一个空号并同步本计划文件名。

- [ ] **Step 2: 写 voucher_type CHECK 迁移** (镜像 REVERSED 先例 V20261026_07)

Create `V20261027_15__voucher_type_check_add_pl_closing.sql`:
```sql
-- 结转损益自动凭证 (P1): voucher_type 加 'PL_CLOSING'。
-- 现 vouchers 的 voucher_type CHECK (V20260602_01) 只允许 7 类业务凭证 →
-- 插入 PL_CLOSING 触发 DataIntegrityViolation 409。单测 mock repo 照不到 DB CHECK,
-- 仅 prod/PG 才暴 (同 V20261026_07 给 REVERSED 加的教训)。幂等 (DROP IF EXISTS + ADD)。

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_voucher_type_check;

ALTER TABLE vouchers ADD CONSTRAINT vouchers_voucher_type_check
    CHECK (voucher_type::text = ANY (ARRAY[
        'SALES_RECEIPT'::varchar,
        'PURCHASE_PAYMENT'::varchar,
        'INVENTORY_TRANSFER'::varchar,
        'EXPENSE'::varchar,
        'WAGE'::varchar,
        'RETURN'::varchar,
        'DEPRECATION'::varchar,
        'PL_CLOSING'::varchar
    ]::text[]));
```
> 注: 原始约束名可能是 `vouchers_voucher_type_check`（PG 对内联 CHECK 自动命名 `<table>_<col>_check`）。`DROP IF EXISTS` 安全；若 prod 实际名不同，迁移仍会 ADD 新约束（旧的内联约束需在 Step 5 prod 验证时确认是否还在——见 Task 11）。

- [ ] **Step 3: 写 closing_posted_at 迁移**

Create `V20261027_16__accounting_period_closing_posted_at.sql`:
```sql
-- 结转损益自动凭证 (P1): accounting_periods 加 closing_posted_at。
-- 标记该期结转凭证是否已过账 (方案A: 期间 LOCKED 后由 finalize scheduler 过账)。
-- NULL = 未结转; finalize 只处理 NULL 的 LOCKED 期间 (幂等)。反结账时清回 NULL。
-- 幂等 (IF NOT EXISTS)。

ALTER TABLE accounting_periods ADD COLUMN IF NOT EXISTS closing_posted_at TIMESTAMP;
```

- [ ] **Step 4: 本地编译确认迁移文件被识别 (不跑 DB)**

Run:
```bash
cd /c/Users/Steve/cretas-pl-closing && ls backend/java/cretas-api/src/main/resources/db/flyway/V20261027_1[56]*
```
Expected: 两个文件都列出。

- [ ] **Step 5: Commit**
```bash
git add backend/java/cretas-api/src/main/resources/db/flyway/V20261027_15__voucher_type_check_add_pl_closing.sql backend/java/cretas-api/src/main/resources/db/flyway/V20261027_16__accounting_period_closing_posted_at.sql
git commit -m "feat(finance): Flyway — voucher_type CHECK 加 PL_CLOSING + accounting_periods.closing_posted_at" -- backend/java/cretas-api/src/main/resources/db/flyway/V20261027_15__voucher_type_check_add_pl_closing.sql backend/java/cretas-api/src/main/resources/db/flyway/V20261027_16__accounting_period_closing_posted_at.sql
```

---

## Task 2: VoucherType.PL_CLOSING 枚举 + mapLinkType

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/VoucherType.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java:138-149`

- [ ] **Step 1: 加枚举值**

在 `VoucherType.java` 枚举常量末尾加 `PL_CLOSING`（含一行注释 `/** 结转损益凭证 (期末自动). */`）。保持现有值不动。

- [ ] **Step 2: 编译——确认 mapLinkType 穷举 switch 报错 (RED)**

Run:
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o compile 2>&1 | tail -5
```
Expected: 编译失败，`mapLinkType` 的 switch "not exhaustive" / missing case PL_CLOSING。

- [ ] **Step 3: 给 mapLinkType 加 case (GREEN)**

修改 `VoucherServiceImpl.java` 的 `mapLinkType` switch（lines 138-149），加一行：
```java
            case WAGE, EXPENSE, DEPRECATION, PL_CLOSING -> "free";
```
（即把 `PL_CLOSING` 并入返回 `"free"` 的那个 case。）

- [ ] **Step 4: grep 其它 VoucherType 穷举 switch**

Run:
```bash
cd /c/Users/Steve/cretas-pl-closing && grep -rn "switch.*[Vv]oucherType\|case SALES_RECEIPT\|case DEPRECATION" backend/java/cretas-api/src/main/java --include=*.java
```
对每个无 `default` 的 VoucherType 穷举 switch，补 `PL_CLOSING` 到合适分支（结转凭证非业务单，归最中性的分支）。

- [ ] **Step 5: 编译通过**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o compile 2>&1 | tail -3
```
Expected: BUILD SUCCESS（无输出错误）。

- [ ] **Step 6: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/VoucherType.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java
git commit -m "feat(finance): VoucherType.PL_CLOSING + mapLinkType case" -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/VoucherType.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java
```

---

## Task 3: VoucherService.createManual (直建 POSTED 凭证, 绕期间 gate)

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/finance/VoucherEntrySpec.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/VoucherService.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/voucher/impl/VoucherServiceManualTest.java`

- [ ] **Step 1: 写 VoucherEntrySpec record**

Create `dto/finance/VoucherEntrySpec.java`:
```java
package com.cretas.aims.dto.finance;

import java.math.BigDecimal;

/**
 * createManual 入参: 一条凭证分录的规格 (debit XOR credit, 另一方传 null 或 0)。
 */
public record VoucherEntrySpec(
        String subjectCode,
        String subjectName,
        BigDecimal debit,
        BigDecimal credit,
        String description) {
}
```

- [ ] **Step 2: 写失败测试**

Create `VoucherServiceManualTest.java`:
```java
package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.voucher.VoucherGeneratorRegistry;
import com.cretas.aims.service.voucher.LinkArrayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceManualTest {

    @Mock VoucherRepository voucherRepo;
    @Mock VoucherGeneratorRegistry registry;
    @Mock LinkArrayService linkArrayService;
    @Mock SalesOrderRepository salesOrderRepo;
    @Mock PurchaseOrderRepository purchaseOrderRepo;
    @Mock ReturnOrderRepository returnOrderRepo;
    @Mock InternalTransferRepository internalTransferRepo;
    @Mock WastageRecordRepository wastageRecordRepo;
    @Mock PayrollRecordRepository payrollRecordRepo;
    @Mock ProductionPlanRepository productionPlanRepo;
    @Mock AccountingPeriodService accountingPeriodService;

    @InjectMocks VoucherServiceImpl service;

    @Test
    void createManual_buildsBalancedPostedVoucher_bypassesPeriodGate() {
        when(voucherRepo.countByFactoryIdAndYear("F006", "2026")).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(i -> i.getArgument(0));

        List<VoucherEntrySpec> entries = List.of(
                new VoucherEntrySpec("6001", "主营业务收入", new BigDecimal("1000.00"), null, "结转收入"),
                new VoucherEntrySpec("4103", "本年利润", null, new BigDecimal("1000.00"), "转入本年利润"));

        Voucher v = service.createManual("F006", VoucherType.PL_CLOSING,
                LocalDate.of(2026, 5, 31), entries,
                "PL_CLOSING", "closing-F006-2026-5-monthly-r0", "5月结转损益", 1309L);

        assertEquals(VoucherStatus.POSTED, v.getStatus());
        assertEquals(new BigDecimal("1000.00"), v.getTotalDebit());
        assertEquals(new BigDecimal("1000.00"), v.getTotalCredit());
        assertEquals(2, v.getEntries().size());
        assertEquals(Long.valueOf(1309L), v.getCreatedBy());
        assertEquals("V-2026-0001", v.getVoucherNumber());
        v.validateBalanced(); // 不抛
        // 关键: 绕过期间 gate — 从不调用 assertOpen
        verify(accountingPeriodService, never()).assertOpen(any(), any(), any());
    }

    @Test
    void createManual_unbalanced_throws() {
        List<VoucherEntrySpec> entries = List.of(
                new VoucherEntrySpec("6001", "主营业务收入", new BigDecimal("1000.00"), null, null),
                new VoucherEntrySpec("4103", "本年利润", null, new BigDecimal("900.00"), null));
        assertThrows(RuntimeException.class, () -> service.createManual("F006", VoucherType.PL_CLOSING,
                LocalDate.of(2026, 5, 31), entries, "PL_CLOSING", "x", "d", 1L));
    }
}
```
> 注: `@InjectMocks` 注入全部 final 字段 + `@Autowired(required=false) accountingPeriodService`。若构造器字段顺序变动导致 Mockito 注入失败，改用显式 `new VoucherServiceImpl(...)`。

- [ ] **Step 3: 跑测试确认失败 (RED)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=VoucherServiceManualTest" 2>&1 | tail -5
```
Expected: 编译失败 (createManual 不存在) 或测试失败。

- [ ] **Step 4: 加接口签名**

在 `VoucherService.java` 接口加：
```java
    /**
     * 直建已过账凭证 (手工指定分录)。用于系统结转损益/红冲等无业务单来源的凭证。
     * ⚠️ 蓄意绕过期间结账 gate (assertPeriodOpen) — 仅限系统结转 (锁定期间须能过结转凭证)。
     * 借贷必平 (validateBalanced); 直接 status=POSTED。
     */
    com.cretas.aims.entity.finance.Voucher createManual(
            String factoryId, com.cretas.aims.entity.enums.VoucherType type,
            java.time.LocalDate voucherDate, java.util.List<com.cretas.aims.dto.finance.VoucherEntrySpec> entries,
            String sourceBusinessType, String sourceBusinessId, String description, Long userId);
```

- [ ] **Step 5: 写实现** (镜像 reversePostedVoucher 的直建 POSTED 模式，但不调 assertPeriodOpen)

在 `VoucherServiceImpl.java` 加（import `VoucherEntrySpec`, `VoucherEntry`, `VoucherType`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `List`）：
```java
    @Override
    @Transactional
    public Voucher createManual(String factoryId, VoucherType type, LocalDate voucherDate,
                                List<VoucherEntrySpec> entries, String sourceBusinessType,
                                String sourceBusinessId, String description, Long userId) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("createManual: entries 不能为空");
        }
        // ⚠️ 不调 assertPeriodOpen — 结转凭证须能过进 LOCKED 期间 (见接口注释)。
        Voucher voucher = Voucher.builder()
                .factoryId(factoryId)
                .voucherNumber(generateVoucherNumber(factoryId, voucherDate))
                .voucherType(type)
                .voucherDate(voucherDate)
                .sourceBusinessType(sourceBusinessType)
                .sourceBusinessId(sourceBusinessId)
                .status(VoucherStatus.POSTED)
                .createdBy(userId)
                .approvedBy(userId)
                .approvedAt(LocalDateTime.now())
                .description(description)
                .build();

        int lineNo = 1;
        for (VoucherEntrySpec spec : entries) {
            VoucherEntry e = VoucherEntry.builder()
                    .lineNo(lineNo++)
                    .subjectCode(spec.subjectCode())
                    .subjectName(spec.subjectName())
                    .debit(nz(spec.debit()))
                    .credit(nz(spec.credit()))
                    .description(spec.description())
                    .voucher(voucher)
                    .build();
            voucher.getEntries().add(e);
        }
        BigDecimal totalDebit = voucher.getEntries().stream()
                .map(e -> nz(e.getDebit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = voucher.getEntries().stream()
                .map(e -> nz(e.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        voucher.setTotalDebit(totalDebit);
        voucher.setTotalCredit(totalCredit);
        voucher.validateBalanced();

        Voucher saved = voucherRepo.save(voucher);
        log.info("✅ 手工凭证 (POSTED): {} type={} source={}/{} total={}",
                saved.getVoucherNumber(), type, sourceBusinessType, sourceBusinessId, totalDebit);
        return saved;
    }
```
> `nz(...)` 私有助手已存在 (reversePostedVoucher 用的 `private static BigDecimal nz`)。

- [ ] **Step 6: 跑测试 (GREEN)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=VoucherServiceManualTest" 2>&1 | tail -3
cat target/surefire-reports/com.cretas.aims.service.voucher.impl.VoucherServiceManualTest.txt | grep "Tests run"
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 7: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/dto/finance/VoucherEntrySpec.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/VoucherService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/voucher/impl/VoucherServiceManualTest.java
git commit -m "feat(finance): VoucherService.createManual — 直建 POSTED 凭证绕期间 gate" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/finance/VoucherEntrySpec.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/VoucherService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/voucher/impl/VoucherServiceManualTest.java
```

---

## Task 4: aggregateBySubjectPosted (POSTED-only 损益余额)

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/VoucherEntryRepository.java`

- [ ] **Step 1: 加 POSTED-only 查询方法** (镜像 aggregateBySubject，把 `status <> VOID` 改 `status = POSTED`)

在 `VoucherEntryRepository.java` 加：
```java
    /**
     * 结转损益专用: 仅 POSTED 凭证按 subjectCode 聚合 [startDate, endDate]。
     * 与 aggregateBySubject 区别: 排除 DRAFT/REVERSED (只算已过账), 防把草稿计进结转。
     */
    @Query("SELECT new com.cretas.aims.dto.finance.SubjectAggregateRow(" +
            "  e.subjectCode, MAX(e.subjectName), " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.subjectCode " +
            "ORDER BY e.subjectCode ASC")
    List<com.cretas.aims.dto.finance.SubjectAggregateRow> aggregateBySubjectPosted(
            @Param("factoryId") String factoryId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);
```

- [ ] **Step 2: 编译**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o compile 2>&1 | tail -3
```
Expected: BUILD SUCCESS。
> 这是 JPQL，单测无 DB 不易验；正确性在 Task 11 prod-schema 集成测试 + F006 验证。

- [ ] **Step 3: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/repository/VoucherEntryRepository.java
git commit -m "feat(finance): aggregateBySubjectPosted — 结转只读 POSTED 余额" -- backend/java/cretas-api/src/main/java/com/cretas/aims/repository/VoucherEntryRepository.java
```

---

## Task 5: AccountingPeriod.closingPostedAt 字段

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/AccountingPeriod.java`

- [ ] **Step 1: 加字段** (实体是 Lombok `@Data`，自动生成 getter/setter)

在 `AccountingPeriod.java` 的字段区（紧邻 `adjustDeadline` 之后）加：
```java
    /** 结转损益凭证已过账时间 (方案A: LOCKED 后 finalize 过账)。NULL=未结转, 反结账时清回 NULL。 */
    @Column(name = "closing_posted_at")
    private LocalDateTime closingPostedAt;
```

- [ ] **Step 2: 编译**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o compile 2>&1 | tail -3
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/AccountingPeriod.java
git commit -m "feat(finance): AccountingPeriod.closingPostedAt 字段" -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/AccountingPeriod.java
```

---

## Task 6: ProfitLossClosingService.closePeriod (月末 6xxx 按余额方向 → 4103)

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/ProfitLossClosingService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/ProfitLossClosingServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/ProfitLossClosingServiceTest.java`

**核心算法** (spec §1.1): 读 `aggregateBySubjectPosted([月初,月末])`；对每个**损益类 6xxx** 科目 (按 `Account.category ∈ {REVENUE, EXPENSE} 或 code 以 '6' 开头` 判定，**排除 5xxx**)，取净额 net = credit − debit：
- net > 0 (净贷, 收入正常): 加分录 借 该科目 net + 贷 4103 net
- net < 0 (净借, 成本费用正常): 加分录 贷 该科目 |net| + 借 4103 |net|
- net == 0: 跳过该科目
所有 6xxx 分录合成**一张** PL_CLOSING 凭证 (4103 借贷各汇总成一行净额)。若无任何 6xxx 净额 → no-op 不建凭证。

- [ ] **Step 1: 写接口**

Create `ProfitLossClosingService.java`:
```java
package com.cretas.aims.service.finance;

/**
 * 结转损益自动凭证 (P1: 月末损益类 6xxx → 本年利润 4103)。
 * 方案A: 由 finalize scheduler 在期间 LOCKED 时调 closePeriod; 反结账调 reversePeriodClosing。
 */
public interface ProfitLossClosingService {

    /**
     * 月末结转: 把 [月初,月末] POSTED 的损益类 6xxx 净额结平到 4103 本年利润。
     * 5xxx (生产成本/制造费用) 是存货资本化科目, 不结转。无损益发生额则 no-op。
     * 直建 POSTED 凭证 (绕期间 gate)。created_by=userId (系统触发传 null)。
     */
    void closePeriod(String factoryId, int year, int month, Long userId);

    /**
     * 反结账红冲: 把该期 active 的 PL_CLOSING 凭证红冲 (复用现有红冲)。无 active → no-op。
     */
    void reversePeriodClosing(String factoryId, int year, int month, Long userId);
}
```

- [ ] **Step 2: 写失败测试**

Create `ProfitLossClosingServiceTest.java`:
```java
package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.service.finance.impl.ProfitLossClosingServiceImpl;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfitLossClosingServiceTest {

    @Mock VoucherService voucherService;
    @Mock VoucherEntryRepository voucherEntryRepo;
    @Mock AccountRepository accountRepo;
    @InjectMocks ProfitLossClosingServiceImpl service;

    @Captor ArgumentCaptor<List<VoucherEntrySpec>> entriesCap;

    private SubjectAggregateRow row(String code, String name, String debit, String credit) {
        return SubjectAggregateRow.builder().subjectCode(code).subjectName(name)
                .totalDebit(new BigDecimal(debit)).totalCredit(new BigDecimal(credit)).entryCount(1L).build();
    }
    private Account acc(String code, String name, AccountCategory cat) {
        return Account.builder().code(code).name(name).category(cat)
                .balanceType(cat == AccountCategory.REVENUE ? AccountBalanceType.CREDIT_NORMAL : AccountBalanceType.DEBIT_NORMAL)
                .build();
    }

    @Test
    void closePeriod_profit_movesNetToRetainedEarnings() {
        // 收入1000(贷) - 成本600(借) - 费用200(借) = 净利200 → 4103 贷200
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "1000.00"),
                row("6401", "主营业务成本", "600.00", "0.00"),
                row("6601", "销售费用", "200.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("6401", "主营业务成本", AccountCategory.COST),
                acc("6601", "销售费用", AccountCategory.EXPENSE)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(eq("F006"), eq(VoucherType.PL_CLOSING),
                eq(LocalDate.of(2026, 5, 31)), entriesCap.capture(),
                eq("PL_CLOSING"), contains("closing-F006-2026-5"), any(), eq(1309L));
        List<VoucherEntrySpec> es = entriesCap.getValue();
        // 4103 净额 = 收入1000(借, 结平收入) - 成本600(贷) - 费用200(贷) → 4103 贷200
        BigDecimal r4103Debit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.debit() == null ? BigDecimal.ZERO : e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal r4103Credit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.credit() == null ? BigDecimal.ZERO : e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("200.00").compareTo(r4103Credit.subtract(r4103Debit)), "4103 净贷=净利200");
        // 借贷平
        BigDecimal d = es.stream().map(e -> e.debit()==null?BigDecimal.ZERO:e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c = es.stream().map(e -> e.credit()==null?BigDecimal.ZERO:e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, d.compareTo(c), "借贷平");
    }

    @Test
    void closePeriod_doesNotClose5xxxWip() {
        // 含 5001 生产成本余额 → 不应出现在结转分录
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "1000.00"),
                row("5001", "生产成本", "300.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("5001", "生产成本", AccountCategory.COST)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(any(), any(), any(), entriesCap.capture(), any(), any(), any(), any());
        boolean has5001 = entriesCap.getValue().stream().anyMatch(e -> e.subjectCode().startsWith("5"));
        assertFalse(has5001, "5xxx WIP 不结转");
    }

    @Test
    void closePeriod_lossMonth_4103Debit() {
        // 成本800(借)收入500(贷) → 净亏300 → 4103 借300
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "500.00"),
                row("6401", "主营业务成本", "800.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("6401", "主营业务成本", AccountCategory.COST)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(any(), any(), any(), entriesCap.capture(), any(), any(), any(), any());
        List<VoucherEntrySpec> es = entriesCap.getValue();
        BigDecimal r4103Debit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.debit()==null?BigDecimal.ZERO:e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal r4103Credit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.credit()==null?BigDecimal.ZERO:e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("300.00").compareTo(r4103Debit.subtract(r4103Credit)), "4103 净借=亏损300");
    }

    @Test
    void closePeriod_noPnl_noOp() {
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of());
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of());
        service.closePeriod("F006", 2026, 5, 1309L);
        verify(voucherService, never()).createManual(any(), any(), any(), anyList(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 3: 跑测试确认失败 (RED)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=ProfitLossClosingServiceTest" 2>&1 | tail -5
```
Expected: 编译失败 (ProfitLossClosingServiceImpl 不存在)。

- [ ] **Step 4: 写实现**

Create `service/finance/impl/ProfitLossClosingServiceImpl.java`:
```java
package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.service.finance.ProfitLossClosingService;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结转损益 (P1): 月末损益类 6xxx → 本年利润 4103。见 spec §1.1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitLossClosingServiceImpl implements ProfitLossClosingService {

    private final VoucherService voucherService;
    private final VoucherEntryRepository voucherEntryRepo;
    private final AccountRepository accountRepo;

    private static final String RETAINED_EARNINGS_CODE = "4103";
    private static final String RETAINED_EARNINGS_NAME = "本年利润";

    @Override
    @Transactional
    public void closePeriod(String factoryId, int year, int month, Long userId) {
        LocalDate start = YearMonth.of(year, month).atDay(1);
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();

        List<SubjectAggregateRow> rows = voucherEntryRepo.aggregateBySubjectPosted(factoryId, start, end);
        Map<String, Account> byCode = new HashMap<>();
        for (Account a : accountRepo.findVisibleToFactory(factoryId)) {
            byCode.merge(a.getCode(), a, (ex, in) -> ex.getFactoryId() != null ? ex : in);
        }

        List<VoucherEntrySpec> entries = new ArrayList<>();
        BigDecimal retained = BigDecimal.ZERO; // 4103 净额: 正=贷(盈利), 负=借(亏损)

        for (SubjectAggregateRow r : rows) {
            String code = r.getSubjectCode();
            if (code == null) continue;
            Account acc = byCode.get(code);
            boolean isPnl = (acc != null)
                    ? (acc.getCategory() == AccountCategory.REVENUE || acc.getCategory() == AccountCategory.EXPENSE)
                    : code.startsWith("6"); // 未绑科目: 仅 6xxx 损益; 5xxx (生产成本/制造费用) 绝不结转
            if (!isPnl) continue;

            BigDecimal debit = r.getTotalDebit() != null ? r.getTotalDebit() : BigDecimal.ZERO;
            BigDecimal credit = r.getTotalCredit() != null ? r.getTotalCredit() : BigDecimal.ZERO;
            BigDecimal net = credit.subtract(debit).setScale(2, RoundingMode.HALF_UP); // 贷-借
            if (net.signum() == 0) continue;
            String name = (acc != null) ? acc.getName() : r.getSubjectName();

            if (net.signum() > 0) {
                // 净贷 (收入): 借 该科目 net 结平 / 4103 贷 net
                entries.add(new VoucherEntrySpec(code, name, net, null, "结转 " + name));
                retained = retained.add(net);
            } else {
                // 净借 (成本费用): 贷 该科目 |net| 结平 / 4103 借 |net|
                BigDecimal abs = net.abs();
                entries.add(new VoucherEntrySpec(code, name, null, abs, "结转 " + name));
                retained = retained.subtract(abs);
            }
        }

        if (entries.isEmpty()) {
            log.info("[PLClosing] {}-{}-{} 无损益发生额, no-op", factoryId, year, month);
            return;
        }

        // 4103 汇总一行: retained>0 → 4103 贷; retained<0 → 4103 借
        if (retained.signum() > 0) {
            entries.add(new VoucherEntrySpec(RETAINED_EARNINGS_CODE, RETAINED_EARNINGS_NAME, null, retained, "结转本年利润"));
        } else {
            entries.add(new VoucherEntrySpec(RETAINED_EARNINGS_CODE, RETAINED_EARNINGS_NAME, retained.abs(), null, "结转本年利润(亏损)"));
        }

        int revision = currentRevision(factoryId, year, month);
        String sourceId = String.format("closing-%s-%d-%d-monthly-r%d", factoryId, year, month, revision);
        voucherService.createManual(factoryId, VoucherType.PL_CLOSING, end, entries,
                "PL_CLOSING", sourceId, String.format("%d-%02d 结转损益", year, month), userId);
        log.info("[PLClosing] {}-{}-{} 结转完成: 4103 净额={} (rev{})", factoryId, year, month, retained, revision);
    }

    @Override
    @Transactional
    public void reversePeriodClosing(String factoryId, int year, int month, Long userId) {
        // 在 Task 7 实现
        throw new UnsupportedOperationException("Task 7");
    }

    /** rev = 该期历史结转批次数 (含已 REVERSED), 用于 source_business_id 避免撞键。Task 7 完善。 */
    private int currentRevision(String factoryId, int year, int month) {
        return 0; // P1 Task 6: 首次结转 r0; Task 7 加 reopen 后用真实计数
    }
}
```
> 注: `accountRepo.findVisibleToFactory(factoryId)` 已存在 (BalanceSheetService 用)。`Account` 是 Lombok @Data+@Builder，`getFactoryId()` 存在。

- [ ] **Step 5: 跑测试 (GREEN)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=ProfitLossClosingServiceTest" 2>&1 | tail -3
cat target/surefire-reports/com.cretas.aims.service.finance.ProfitLossClosingServiceTest.txt | grep "Tests run"
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/ProfitLossClosingService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/ProfitLossClosingServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/ProfitLossClosingServiceTest.java
git commit -m "feat(finance): ProfitLossClosingService.closePeriod — 月末6xxx按余额方向结转4103 (5xxx不结)" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/ProfitLossClosingService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/ProfitLossClosingServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/ProfitLossClosingServiceTest.java
```

---

## Task 7: reversePeriodClosing (反结账红冲) + revision 计数

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/ProfitLossClosingServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/VoucherRepository.java` (加查询)
- Test: 追加到 `ProfitLossClosingServiceTest.java`

- [ ] **Step 1: 加 VoucherRepository 查询** — 该期 active PL_CLOSING + revision 计数

在 `VoucherRepository.java` 加：
```java
    /** 该期 active 结转凭证 (POSTED + 非红冲镜像), 反结账时红冲它们。 */
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM Voucher v WHERE v.factoryId = :factoryId " +
        "  AND v.sourceBusinessType = 'PL_CLOSING' " +
        "  AND v.sourceBusinessId LIKE :prefix " +
        "  AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
        "  AND v.originalVoucherId IS NULL " +
        "  AND v.deletedAt IS NULL")
    java.util.List<com.cretas.aims.entity.finance.Voucher> findActiveClosingVouchers(
            @org.springframework.data.repository.query.Param("factoryId") String factoryId,
            @org.springframework.data.repository.query.Param("prefix") String prefix);

    /** 该期历史结转批次数 (含 REVERSED, 排镜像) — 算 revision。 */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = :factoryId " +
        "  AND v.sourceBusinessType = 'PL_CLOSING' " +
        "  AND v.sourceBusinessId LIKE :prefix " +
        "  AND v.originalVoucherId IS NULL " +
        "  AND v.deletedAt IS NULL")
    long countClosingBatches(
            @org.springframework.data.repository.query.Param("factoryId") String factoryId,
            @org.springframework.data.repository.query.Param("prefix") String prefix);
```
> prefix 形如 `closing-F006-2026-5-monthly-r%`。

- [ ] **Step 2: 写失败测试** (追加到 ProfitLossClosingServiceTest)

加 `@Mock VoucherRepository voucherRepo;` 字段，并加测试：
```java
    @Test
    void reversePeriodClosing_redReversesActiveBatch() {
        com.cretas.aims.entity.finance.Voucher active = com.cretas.aims.entity.finance.Voucher.builder()
                .id("vc1").factoryId("F006").build();
        when(voucherRepo.findActiveClosingVouchers(eq("F006"), contains("closing-F006-2026-5-monthly-r")))
                .thenReturn(List.of(active));

        service.reversePeriodClosing("F006", 2026, 5, 1309L);

        verify(voucherService).reverseVoucher("F006", "vc1", "反结账自动红冲", 1309L);
    }

    @Test
    void reversePeriodClosing_noActive_noOp() {
        when(voucherRepo.findActiveClosingVouchers(eq("F006"), anyString())).thenReturn(List.of());
        service.reversePeriodClosing("F006", 2026, 5, 1309L);
        verifyNoInteractions(voucherService);
    }
```
> `reverseVoucher` 是 VoucherService 现有公开红冲入口 (内部调 reversePostedVoucher)。先 grep 确认其确切签名：
```bash
cd /c/Users/Steve/cretas-pl-closing && grep -rn "reverseVoucher\|reversePostedVoucher" backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/VoucherService.java
```
若接口无公开 `reverseVoucher`，需在 VoucherService 接口暴露 `void reverseVoucher(String factoryId, String voucherId, String reason, Long userId)`（impl 调私有 reversePostedVoucher，含跨租户 findByIdAndFactoryId 校验）— 加该公开方法 + 改测试名一致。

- [ ] **Step 3: 跑测试确认失败 (RED)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=ProfitLossClosingServiceTest" 2>&1 | tail -5
```
Expected: 失败 (reversePeriodClosing 抛 UnsupportedOperation / reverseVoucher 不存在)。

- [ ] **Step 4: 实现 reversePeriodClosing + revision**

把 `ProfitLossClosingServiceImpl` 加 `private final VoucherRepository voucherRepo;` 字段，替换 `currentRevision` 与 `reversePeriodClosing`：
```java
    private int currentRevision(String factoryId, int year, int month) {
        String prefix = String.format("closing-%s-%d-%d-monthly-r%%", factoryId, year, month);
        return (int) voucherRepo.countClosingBatches(factoryId, prefix);
    }

    @Override
    @Transactional
    public void reversePeriodClosing(String factoryId, int year, int month, Long userId) {
        String prefix = String.format("closing-%s-%d-%d-monthly-r%%", factoryId, year, month);
        List<com.cretas.aims.entity.finance.Voucher> active =
                voucherRepo.findActiveClosingVouchers(factoryId, prefix);
        if (active.isEmpty()) {
            log.info("[PLClosing] {}-{}-{} 无 active 结转凭证, 反冲 no-op", factoryId, year, month);
            return;
        }
        for (com.cretas.aims.entity.finance.Voucher v : active) {
            voucherService.reverseVoucher(factoryId, v.getId(), "反结账自动红冲", userId);
        }
        log.info("[PLClosing] {}-{}-{} 反冲 {} 张结转凭证", factoryId, year, month, active.size());
    }
```
若 Step 2 需新增 `VoucherService.reverseVoucher` 公开方法，在此一并加（impl 内：`findByIdAndFactoryIdAndDeletedAtIsNull` 取凭证 → 校验 status==POSTED → 调 `reversePostedVoucher(v, reason, userId)`）。

- [ ] **Step 5: 跑测试 (GREEN)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=ProfitLossClosingServiceTest" 2>&1 | tail -3
cat target/surefire-reports/com.cretas.aims.service.finance.ProfitLossClosingServiceTest.txt | grep "Tests run"
```
Expected: `Tests run: 6, Failures: 0`。

- [ ] **Step 6: Commit**
```bash
git add -A && git status --short
git commit -m "feat(finance): reversePeriodClosing 反结账红冲 + revision 计数" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/ProfitLossClosingServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/VoucherRepository.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/ProfitLossClosingServiceTest.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/VoucherService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java
```
> commit 前先看 `git status --short`，只提交本任务文件（并发安全）。

---

## Task 8: reopenPeriod 接 reversePeriodClosing

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java:144-170`

- [ ] **Step 1: 注入 ProfitLossClosingService + 在 reopenPeriod 反冲 + 清 closingPostedAt**

`AccountingPeriodServiceImpl` 加字段 `@org.springframework.beans.factory.annotation.Autowired(required=false) private com.cretas.aims.service.finance.ProfitLossClosingService profitLossClosingService;`（required=false 避免循环依赖/测试缺 bean）。

在 `reopenPeriod` 的 `p.setStatus(OPEN)` **之前**加（红冲直建 POSTED 不受 gate 影响，顺序仅为清晰）：
```java
        // 结转损益: 若已结转, 反结账时红冲结转凭证 + 清结转标记
        if (profitLossClosingService != null && p.getClosingPostedAt() != null) {
            profitLossClosingService.reversePeriodClosing(factoryId, year, month, userId);
            p.setClosingPostedAt(null);
        }
```

- [ ] **Step 2: 编译 + 跑现有 AccountingPeriod 测试 (无回归)**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=AccountingPeriodServiceImplTest" 2>&1 | tail -3
```
Expected: 现有测试全绿（无该测试类则跑 `-Dtest=*AccountingPeriod*`）。

- [ ] **Step 3: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java
git commit -m "feat(finance): reopenPeriod 反结账时红冲结转凭证 + 清 closingPostedAt" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java
```

---

## Task 9: finalize scheduler (LOCKED 触发自动结转)

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/scheduler/AccountingPeriodScheduler.java`

- [ ] **Step 1: 加 finalizeLockedPeriods 任务**

`AccountingPeriodScheduler` 注入 `AccountingPeriodRepository periodRepo` + `ProfitLossClosingService profitLossClosingService`（构造器字段）。加方法（每日 03:00 跑）：
```java
    @Scheduled(cron = "${accounting-period.finalize.cron:0 0 3 * * ?}")
    @SchedulerLock(
            name = "AccountingPeriodScheduler.finalizeLockedPeriods",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    @org.springframework.transaction.annotation.Transactional
    public void finalizeLockedPeriods() {
        if (!enabled) return;
        List<String> factoryIds;
        try {
            factoryIds = factoryRepository.findAllActiveFactoryIds();
        } catch (Exception e) {
            log.error("[Finalize] 取 active factory 失败: {}", e.getMessage(), e);
            return;
        }
        int closed = 0;
        for (String factoryId : factoryIds) {
            List<AccountingPeriod> periods =
                    periodRepo.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, AccountingPeriod.Status.CLOSED);
            for (AccountingPeriod p : periods) {
                if (p.getClosingPostedAt() != null) continue; // 已结转
                if (p.getAdjustWindowState() != AccountingPeriod.AdjustWindowState.LOCKED) continue; // 窗口未过
                try {
                    profitLossClosingService.closePeriod(factoryId, p.getYear(), p.getMonth(), null); // 系统触发 userId=null
                    p.setClosingPostedAt(java.time.LocalDateTime.now());
                    periodRepo.save(p);
                    closed++;
                } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                    // 并发/重复: 唯一约束撞 → 幂等 no-op, 仍标记已结转
                    p.setClosingPostedAt(java.time.LocalDateTime.now());
                    periodRepo.save(p);
                    log.warn("[Finalize] {}-{}-{} 唯一约束撞, 幂等跳过", factoryId, p.getYear(), p.getMonth());
                } catch (Exception e) {
                    log.error("[Finalize] {}-{}-{} 结转失败: {}", factoryId, p.getYear(), p.getMonth(), e.getMessage(), e);
                }
            }
        }
        if (closed > 0) log.info("[Finalize] 本轮结转 {} 个期间", closed);
    }
```
import `AccountingPeriod`、`AccountingPeriodRepository`。

- [ ] **Step 2: 编译**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o compile 2>&1 | tail -3
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/scheduler/AccountingPeriodScheduler.java
git commit -m "feat(finance): finalize scheduler — 期间LOCKED时自动结转损益" -- backend/java/cretas-api/src/main/java/com/cretas/aims/scheduler/AccountingPeriodScheduler.java
```

---

## Task 10: 手工"立即锁定结转"逃生口

**Files:**
- Modify: `service/finance/AccountingPeriodService.java` + `impl/AccountingPeriodServiceImpl.java`
- Modify: `controller/finance/MonthCloseController.java`

- [ ] **Step 1: 接口加 forceLockAndClose**

`AccountingPeriodService` 加：
```java
    /**
     * 手工立即锁定结转: 把 CLOSED 期间 adjustDeadline 设为 now (强制 LOCKED) 并立即结转损益。
     * 财务确认无后续调整时用, 不必等 20 天窗口。需 finance 权限 (Controller 守门)。
     */
    AccountingPeriod forceLockAndClose(String factoryId, Integer year, Integer month, Long userId);
```

- [ ] **Step 2: impl 实现**

`AccountingPeriodServiceImpl` 加：
```java
    @Override
    @Transactional
    public AccountingPeriod forceLockAndClose(String factoryId, Integer year, Integer month, Long userId) {
        validateInput(factoryId, year, month);
        AccountingPeriod p = repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .orElseThrow(() -> new BusinessException(404,
                        String.format("%d-%02d 期间不存在", year, month)));
        if (p.getStatus() != AccountingPeriod.Status.CLOSED) {
            throw new BusinessException(400,
                    String.format("%d-%02d 期间状态=%s, 仅 CLOSED 可立即锁定结转", year, month, p.getStatus()));
        }
        if (p.getClosingPostedAt() != null) {
            return p; // 幂等: 已结转
        }
        p.setAdjustDeadline(LocalDateTime.now()); // 强制 LOCKED
        if (profitLossClosingService != null) {
            profitLossClosingService.closePeriod(factoryId, year, month, userId);
        }
        p.setClosingPostedAt(LocalDateTime.now());
        AccountingPeriod saved = repo.save(p);
        log.info("[AccountingPeriod] 手工立即锁定结转 {}-{}-{} by user={}", factoryId, year, month, userId);
        return saved;
    }
```

- [ ] **Step 3: Controller 端点** (镜像现有 MonthCloseController 的 @RequirePermission + path 风格)

先看现有端点风格：
```bash
cd /c/Users/Steve/cretas-pl-closing && sed -n '1,60p' backend/java/cretas-api/src/main/java/com/cretas/aims/controller/finance/MonthCloseController.java
```
按其 path 前缀 + `@RequirePermission({"finance:read_write"})` + userId 取法 (`mobileService.getUserFromToken(token).getId()` 或同款) 加：
```java
    @PostMapping("/force-lock-close")
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<AccountingPeriod>> forceLockAndClose(
            @PathVariable String factoryId,
            @RequestParam Integer year, @RequestParam Integer month,
            @RequestHeader("Authorization") String token) {
        Long userId = mobileService.getUserFromToken(token).getId();
        return ResponseEntity.ok(ApiResponse.success(
                accountingPeriodService.forceLockAndClose(factoryId, year, month, userId)));
    }
```
> 确切 userId 取法 / ApiResponse 包装 / token 解析按本 controller 既有方法对齐 (见 sed 输出)。

- [ ] **Step 4: 编译 + 全 finance 套件无回归**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=*Finance*,*AccountingPeriod*,*Voucher*,ProfitLossClosingServiceTest,BalanceSheetServiceTest,IncomeStatementServiceTest,CashFlowServiceTest" 2>&1 | tail -5
```
Expected: 全绿。

- [ ] **Step 5: Commit**
```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/AccountingPeriodService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/controller/finance/MonthCloseController.java
git commit -m "feat(finance): 手工立即锁定结转端点 (逃生口)" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/AccountingPeriodService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/controller/finance/MonthCloseController.java
```

---

## Task 11: prod-schema 集成验证 + F006 活体验证 + 终审部署

**Files:** 无新代码 (验证 + 部署)。

- [ ] **Step 1: 全模块编译 + 全量相关测试**
```bash
cd /c/Users/Steve/cretas-pl-closing/backend/java/cretas-api && C:/tools/apache-maven-3.9.6/bin/mvn -q -o test "-Dtest=ProfitLossClosingServiceTest,VoucherServiceManualTest,BalanceSheetServiceTest,IncomeStatementServiceTest,CashFlowServiceTest,FinanceReportExportTest" 2>&1 | tail -8
```
Expected: 全绿。

- [ ] **Step 2: PR scope + Flyway 撞号 check**
```bash
cd /c/Users/Steve/cretas-pl-closing && git diff origin/main...HEAD --stat
git fetch origin -q && git ls-tree -r --name-only origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE "V20261027_1[56]" | sort | uniq -c
```
Expected: scope 仅本功能文件; 撞号 check 无 origin/main 重号 (若有→rebase + 改号)。

- [ ] **Step 3: 🔒 Opus 独立终审** — 红线 (GL 真账/全租户/含 LIUSHANMEN)。请 Opus organizer 终审 diff (尤其 createManual 绕 gate、closePeriod 账务、scheduler 幂等、CHECK 迁移)。**通过后才 merge + 部署。**

- [ ] **Step 4: merge main + 从 main 部署 prod (Flyway 自动应用 2 个迁移)**
```bash
# Opus 终审通过后:
gh pr create --base main --head feat/pl-closing --title "feat(finance): 结转损益自动凭证 P1" --body "..."
# merge 后, 在 deploy worktree:
cd /c/Users/Steve/cretas-liushanmen-main-deploy && git checkout main && git pull origin main
bash scripts/deploy/deploy-backend.sh --env all
```
Expected: 部署成功; 启动日志确认 Flyway 应用 V20261027_15/_16 (无 "more than one migration" / CHECK 报错)。

- [ ] **Step 5: F006 prod 活体验证 (绝不碰 LIUSHANMEN)**
经 139:8086 gateway 登录 f006_admin，对一个有损益的月 (如 2026-05) 调手工 `force-lock-close` → 验证:
```bash
# (a) 凭证列表出现 PL_CLOSING 凭证, 借贷平
curl -s "http://139.196.165.140:8086/api/mobile/F006/finance/vouchers?type=PL_CLOSING" -H "Authorization: Bearer $TOKEN"
# (b) 资产负债表 4103 本年利润 显真实利润; 6xxx 结平; balanceCheck=true
curl -s "http://139.196.165.140:8086/api/mobile/F006/finance/report/balance-sheet?year=2026&month=5" -H "Authorization: Bearer $TOKEN"
# (c) 反结账 → PL_CLOSING 被红冲 (出现 VOUCHER_REVERSAL 镜像) + closingPostedAt 清空 + 损益恢复
```
Expected: (a) PL_CLOSING 凭证存在且平; (b) 4103 持利润、6xxx=0、balanceCheck=true; (c) 红冲镜像生成、可重结。
**prod-schema 真验**: PL_CLOSING 真插入未被 voucher_type CHECK 拒 (证明 Task 1 迁移生效)。

- [ ] **Step 6: 更新 memory + 清理 worktree**
记 P1 上线 + 关键决策 (方案A/createManual/CHECK 迁移); `git worktree remove` 清理。

---

## Self-Review (作者已核)

**Spec 覆盖**: §1.1 月末按余额方向结转 6xxx 不结 5xxx → Task 6 ✓; §2 LOCKED 触发 + 手工逃生 → Task 9/10 ✓; §2.4 反结账红冲 → Task 7/8 ✓; §4.1 createManual → Task 3 ✓; §4.2 enum+CHECK → Task 1/2 ✓; §4.3 POSTED-only → Task 4 ✓; §2.1 closingPostedAt → Task 5 ✓; §5 幂等以 closingPostedAt/revision → Task 7/9 ✓; §8 测试(亏损/5xxx不结/no-op/反冲) → Task 6/7 ✓; §9 红线终审+F006 → Task 11 ✓。
**P2/P3 不在本计划** (税/年末/盈余公积) — 各自独立 spec→plan。
**类型一致**: createManual 签名在 Task 3 定义、Task 6 调用一致; VoucherEntrySpec record 访问器 `.subjectCode()/.debit()` 全程一致; closingPostedAt getter/setter (Lombok @Data) 全程一致; reverseVoucher 在 Task 7 Step 2 标注"若接口无则补公开方法"。
**占位符**: 无 TBD; 唯 2 处"按现有风格对齐"(Task 10 controller userId 取法 / Task 7 reverseVoucher 签名) 给了 grep 命令 + 兜底加法, 非空占位。
