# 结转损益自动凭证 (Profit & Loss Closing) — 设计 spec v2

**日期**: 2026-06-24 (v2 — 经 superpowers 三路对抗审计后重写)
**触发**: 财务模块审计 (`docs/audits/finance-module-gap-analysis-2026-06-24.md`) Tier-1 #1。
现月结只锁期 + 快照报表, **不过结转损益凭证** → 本年利润/未分配利润从不入账, 资产负债表靠
"合成未分配利润行" (`BalanceSheetService` #1100) 绕过, 不是真账。本功能让账真账化。

**范围 (Steve 拍板: 完整)**: 月末结转损益 + 所得税计提 + 年末结转 + 法定盈余公积。
**触发 (Steve 拍板: 方案 A)**: **在期间真正硬锁时 (调整窗口 LOCKED) 自动过结转凭证**, 不在软结账
(CLOSED) 时。理由: CLOSED 后有 20 天调整窗口仍可写业务凭证, 若软结账即结转会被窗口内迟到凭证
搞陈旧 (审计 B#1)。窗口锁定后无业务凭证可入 → 结转一次成型不陈旧。配套**手工"立即锁定结转"**
逃生口 (财务确认无后续调整时, 不必等 20 天)。

---

## 1. 会计模型 (中国 GAAP, CAS) — 审计 A 修订

### 1.1 月末结转损益 (只结 6xxx 损益类, **不结 5xxx**)
**仅结转损益类 (6xxx)**; `5001 生产成本 / 5101 制造费用 / 5201 劳务成本 / 5301 研发支出` 是
**存货资本化科目 (WIP/成本归集)**, 不是期间损益, **绝不结转到本年利润** (审计 A#1: 结转会双重
计成本 + 抹掉在产品资产)。

结转科目集 (来自 seed V20260701_02 的全部损益类):
- 收入类: `6001 主营业务收入 / 6051 其他业务收入 / 6101 投资收益 / 6301 营业外收入`
- 成本费用类: `6401 主营业务成本 / 6402 其他业务成本 / 6403 营业税金及附加 / 6601 销售费用 /
  6602 管理费用 / 6603 财务费用 / 6701 资产减值损失 / 6711 营业外支出 / 6801 所得税费用`
  (审计 A#4: 补回原漏的 6711)

**按余额方向结转, 非硬编码** (审计 A#7): 对每个损益科目取期间 POSTED 净额 (见 §4.3 POSTED-only):
- 净贷方 C>0 (收入正常): 借 该科目 C / 贷 4103 本年利润
- 净借方 D>0 (成本费用正常): 借 4103 本年利润 / 贷 该科目 D
- **异常余额** (收入红冲净借方 / 费用回冲净贷方) 按实际方向反向结平, 不按科目类别硬编码方向
结果: 所有 6xxx 结平为 0; 4103 += 当期净利润 (盈利贷方 / 亏损借方)。

### 1.2 所得税计提 (P2; 税前利润>0; 累计 YTD true-up)
- **税基** (审计 A#3, 必须显式排除 6801, 按 **code** 匹配非按名字): 税前利润 = Σ收入 − Σ成本费用(**不含 6801**)。
- **累计 YTD** (审计 A#5, 防盈利月后亏损月多计税): 应计税额_YTD = max(0, 累计税前利润_YTD) × 税率;
  当月计提 = 应计税额_YTD − 已计提_YTD (**可为负 → 借 2221/贷 6801 冲回**)。
- 凭证: 借 6801 所得税费用 / 贷 2221 应交税费 (负数则反向)。计提在月末结转**之前**, 使 6801 当期一并转入 4103。

### 1.3 年末结转 (P3; 仅 12 月)
- 借 4103 本年利润 (全年累计, 见 §4.4 年度读取窗口) / 贷 4104 利润分配-未分配利润
- **法定盈余公积** (净利>0): 先 **弥补以前年度亏损** (审计 A#6: 若 4104 有借方赤字先抵), 再
  按 (净利 − 弥补额) × 盈余公积率 提取: 借 4104 / 贷 4101 盈余公积。
- **封顶**: 累计法定盈余公积 ≥ 50% × 实收资本(4001) 则停提 (审计 A#6: 封顶针对法定部分;
  任意盈余公积/应付股利不在范围, 故 P3 用 4101 整体近似 + 注明局限)。

科目全部已 seed (4101/4103/4104/6801/2221) → **无需新增科目**。

---

## 2. 触发架构 (方案 A: 锁定时结转)

`AccountingPeriod.getAdjustWindowState()` 派生三态: NOT_CLOSED / OPEN_WINDOW / **LOCKED**
(CLOSED 且 `now ≥ adjustDeadline`)。LOCKED 是**派生**态 (按时间), 无内建事件。本功能:

### 2.1 新增 `AccountingPeriod.closingPostedAt` (LocalDateTime, nullable)
标记该期结转凭证已过账。Flyway 加列 (P1)。NULL = 未结转。

### 2.2 定时 finalize (新 scheduler 任务, 接入 `AccountingPeriodScheduler`)
每日扫描: status=CLOSED 且 closingPostedAt IS NULL 且 `getAdjustWindowState()==LOCKED` 的期间 →
`profitLossClosingService.closePeriod(factory, y, m, SYSTEM_USER)` → 过结转凭证 → set closingPostedAt=now。
单 job 串行处理 (低并发); 唯一约束冲突 catch 成幂等 no-op (审计 B#5)。

### 2.3 手工"立即锁定结转" (逃生口)
财务确认无后续调整 → 一个动作: 设 adjustDeadline=now (强制 LOCKED) → 立即 finalize 该期。
给 SME / F006 demo 不必等 20 天。需 finance 权限。

### 2.4 反结账 (接 `AccountingPeriodServiceImpl.reopenPeriod`, 审计 B#2/C#5)
**唯一反结账入口是 `reopenPeriod` (CLOSED→OPEN); MonthClose 无 reverse 方法。** 在 reopenPeriod 内:
若 closingPostedAt != NULL → `reversePeriodClosing(factory, y, m, userId)` 红冲该期 active 结转凭证 →
清 closingPostedAt + 清 adjustDeadline → setStatus(OPEN)。red-reverse 直接建 POSTED 镜像 (无 gate),
故先反冲后置 OPEN 的顺序不受 gate 影响 (审计 C#5)。

**两条软结账路径 (executeClose / confirmClose) 都只到 CLOSED+窗口**; finalize 按派生 LOCKED 态触发,
与哪条路径无关 → 单一咽喉 = finalize + reopenPeriod (审计 B#2 解)。

---

## 3. 与现有资产负债表自洽 (审计 A#2 修订)

`BalanceSheetService` (#1100) 合成「未分配利润」行 = Σ(credit−debit) over REVENUE/COST/EXPENSE。
- **未锁定期间** (OPEN / CLOSED+窗口): 损益类有余额 → 合成行 = 未结转利润 (现状)。
- **已锁定+结转**: 6xxx 结平 0 → 合成行只剩 5xxx 残值。**关键 (审计 A#2)**: 5xxx (生产成本/制造费用,
  category=COST) **不结转**, 若有 WIP 残值, `BalanceSheetService` 现会把它折进合成行 (负数), 错列为
  负权益而非存货资产。
- **本系统现状**: 存货核算→GL 未实现 (财务审计 ABSENT) → 5xxx 实际**无 posting / 恒 0** → 当前
  无现网影响。但**遗留 backlog 必记**: 一旦上存货核算, 5xxx WIP 须改作资产列示, 不可折进留存收益。
  本 spec **不改 BalanceSheetService** (5xxx 恒 0 前提下安全), 仅在 §10 记此前提与 backlog。

---

## 4. 组件 / 代码现实修订 (审计 C)

### 4.1 新增 `VoucherService.createManual(...)` (审计 C#1 — 当前无此 API)
`service/voucher/VoucherService.java` 现仅有 `createFromBusiness` (需真实业务实体+generator)。结转无实体无
generator → 必须新增:
```
Voucher createManual(String factoryId, VoucherType type, LocalDate voucherDate,
                     List<EntrySpec> entries, String sourceBusinessType, String sourceBusinessId,
                     String description, Long userId)
```
构建 totals → `validateBalanced()` → `generateVoucherNumber()` → 存为 **status=POSTED** 直接落库,
**绕过 `post()`/`assertPeriodOpen` 期间 gate** (锁定期间须能过结转凭证; 仿 `reversePostedVoucher`
直接建 POSTED 的做法)。这是**蓄意 gate 绕过, 仅限系统结转/反冲**, 注释标明。

### 4.2 `VoucherType.PL_CLOSING` + 编译/CHECK 修订 (审计 C#2/extra)
- 加枚举值 `PL_CLOSING`; **必须**给 `VoucherServiceImpl.mapLinkType` 穷举 switch 加 `case PL_CLOSING -> "free"`
  (否则**编译失败**); grep 所有 VoucherType 穷举 switch 补齐。
- **P1 必须 Flyway 迁移扩 `vouchers` 的 `voucher_type` CHECK 约束含 'PL_CLOSING'** (审计 C-extra:
  现 CHECK 不含 → 插入 prod 409; 先例 V20261026_07 给 REVERSED 加过; **mock 单测照不到 CHECK, prod 才暴**,
  见 [[feedback_mock_repo_misses_db_constraints]])。原 v1 "无需迁移" 是错的。

### 4.3 POSTED-only 损益余额读取 (审计 C#6)
`aggregateBySubject` 含 DRAFT (status<>VOID)。结转**不能**把未过账草稿计进 4103 → 新增 repo 方法
`aggregateBySubjectPosted(factory, start, end)` 仅 status=POSTED。closePeriod 用之。

### 4.4 `ProfitLossClosingService`
位置: `service/finance/ProfitLossClosingService.java`。依赖: `VoucherService` (createManual/reverse) ·
`VoucherEntryRepository` (aggregateBySubjectPosted) · `AccountRepository` · `AccountingPeriodRepository`
(读/写 closingPostedAt) · (P2) `FinanceClosingConfigService`。
```
void closePeriod(String factoryId, int year, int month, Long userId)            // 计税(P2)→月末结转→(12月)年结(P3)
void reversePeriodClosing(String factoryId, int year, int month, Long userId)   // 红冲该期 active PL_CLOSING
```
- 月末读 [月初, 月末] POSTED 损益净额; 年末 (§1.3) 读 4103 [本年1-1, 12-31] (审计 B#4: 显式全年窗口)。
- `@Transactional` 加入调用方事务 (finalize/reopen), **传播异常不吞** (审计 C#9: 禁 REQUIRES_NEW/fail-soft;
  一张失败回滚整批)。
- created_by 走 userId 线程参 (禁 SecurityUtils, 见 [[feedback_preauthorize_noop_and_sync_section_asyncpg]]);
  定时器路径用 **SYSTEM 哨兵 userId** (审计 B#7: scheduler 现传 null; 确认 vouchers.created_by 容忍/FK 安全)。

### 4.5 配置 `FinanceClosingConfig` (P2)
application 默认 `cretas.finance.income-tax-rate=0.25` / `surplus-reserve-rate=0.10`; 可选按工厂表 override。

---

## 5. 凭证结构 + 幂等

- `voucher_type = PL_CLOSING`; `voucher_date = atEndOfMonth`; `status = POSTED` (createManual 直建)。
- `source_business_type='PL_CLOSING'`; `source_business_id = closing-{f}-{y}-{m}-{kind}-r{rev}`,
  kind∈{tax,monthly,annual,reserve}; **rev = 该期历史结转批次数** = count(PL_CLOSING WHERE
  original_voucher_id IS NULL) (含 REVERSED)。复用 (type,id) 唯一约束。
- **幂等以 period 状态为准** (审计 B#8): finalize 只处理 closingPostedAt IS NULL 的 LOCKED 期间;
  closePeriod 入口 assert closingPostedAt IS NULL (再加唯一约束兜底 catch 成 no-op)。
- **红冲约定** (审计 A/B#3/C#3/C#7): `reversePeriodClosing` **复用现有 `reversePostedVoucher`** —
  原 PL_CLOSING 置 REVERSED + reversalVoucherId; 镜像 status=POSTED + originalVoucherId +
  `source_business_type='VOUCHER_REVERSAL'` (沿用现约定, **不用** PL_CLOSING type → 不被 rev 计数)。
- **active 结转凭证** = PL_CLOSING AND status=POSTED AND original_voucher_id IS NULL。reverse 只冲 active;
  无 active → no-op (审计 B#6: 防 reopen 未结期/二次反冲抛 409)。
- **REVERSED 不可 VOID** (审计 B#11): 红冲必置 REVERSED (aggregate 含 REVERSED 但排 VOID → 原+镜像净零);
  若误置 VOID 则镜像无对消 → 余额错。invariant 写测试锁。

---

## 6. 边界 / 错误处理 (审计汇总)

| 情况 | 处理 |
|---|---|
| 20 天窗口内业务凭证 | 不影响: 结转只在 LOCKED 后跑 (窗口已过, 无凭证可入) — 方案 A 根治 (B#1) |
| 两条软结账路径 | finalize 按派生 LOCKED 触发, 与 executeClose/confirmClose 无关 (B#2) |
| 利润≤0 (亏损月) | 仍过结转 (4103 转借方=亏损); 仅跳过计税/提公积。**不等于 no-op** (A#10/B#8) |
| 真空期 (收入成本皆 0) | closePeriod no-op (无凭证); 幂等以 closingPostedAt 标记, 仍 set closingPostedAt 避免反复扫 |
| 并发 finalize | 单 job 串行 + 唯一约束 catch 成幂等 no-op; 不让 409 回滚 (B#3/B#5) |
| 反结账无 active | no-op 不报错 (B#6) |
| 二次反冲 | 只冲 active (POSTED + original_voucher_id IS NULL), 不碰已 REVERSED (B#6) |
| 快照顺序 | 利润表快照在 executeClose 软结账时拍 (损益未结), 结转在 ~20 天后锁定时跑 → 快照正确 (C#4 被方案 A 自然解) |
| 中途上线无期初 | 年末封顶读 4001=0 → 跳过提公积; 4103→4104 仅转在系统 YTD 利润, 注明对迁移租户不含历史利润 (B#4) |
| created_by | finalize 用 SYSTEM 哨兵; reopen 用调用方 userId (B#7) |
| 异常余额科目 | 按实际净额方向结转 (A#7) |

---

## 7. 分期实施 (均「完整」范围, 每期 ship + F006 prod 验证)

- **P1 — 月末结转核心 + 锁定触发**:
  ① Flyway: `voucher_type` CHECK 加 PL_CLOSING + `accounting_periods.closing_posted_at` 加列
  ② `VoucherType.PL_CLOSING` + mapLinkType 等穷举 switch 补
  ③ `VoucherService.createManual` (POSTED 直建, 绕 gate)
  ④ `aggregateBySubjectPosted` repo 方法
  ⑤ `ProfitLossClosingService.closePeriod` (月末 6xxx 按余额方向结转→4103) + `reversePeriodClosing`
  ⑥ finalize scheduler (LOCKED 触发) + 手工"立即锁定结转"动作 + reopenPeriod 接反冲
  → 让三大报表真账化。
- **P2 — 所得税**: `FinanceClosingConfig` + 累计 YTD true-up 计税凭证 (借6801/贷2221) 接 closePeriod 月末结转前。
- **P3 — 年末 + 盈余公积**: 12 月 4103→4104 + 弥补亏损 + 法定盈余公积 (含封顶)。

---

## 8. 测试计划

**单测 `ProfitLossClosingServiceTest`** (TDD):
- 月末结转: 收入1000/成本600/费用200 → 4103=200; 6xxx 结平 0; 借贷平
- **亏损月**: 成本800/收入500 → 4103 净借 300; 6xxx 结平 0 (A#10)
- **异常余额**: 收入科目净借方 (红冲) → 按借方反向结平 (A#7)
- **5xxx 不结**: 凭证含 5001 生产成本余额 → closePeriod **不**生成针对 5001 的结转分录 (A#1)
- 幂等: closingPostedAt 已设 → 跳过
- (P2) 计税: 累计 YTD; 盈利月后亏损月**冲回** (借2221/贷6801); 税基排除 6801
- (P3) 年末: 4103全年→4104; 弥补亏损; 盈余公积10%; 封顶
- 反冲: reversePeriodClosing 后 active 净零; 无 active → no-op; 不碰 REVERSED

**prod-schema 集成测试 (非纯 mock, 审计 C-extra/B#5)**:
- voucher_type=PL_CLOSING 真插入不被 CHECK 拒 (rollback-replay 验)
- 并发/重复 closePeriod 唯一约束 → 幂等不抛
- BalanceSheet 锁定后: 6xxx 0、合成行无 6xxx 残、4103/4104 持利润、balanceCheck=true

**prod 验证 (F006)**: 手工"立即锁定结转"某月 → 凭证列表有 PL_CLOSING + 资产负债表 4103 真实利润 + 平衡;
反结账 → 红冲凭证 + 损益恢复 + closingPostedAt 清空。**绝不碰 LIUSHANMEN**。

---

## 9. 🔒 红线
影响 GL 真账 + 全部租户 + 含真实 LIUSHANMEN: worktree off origin/main → TDD → 独立 review → Opus 终审 →
从 main 部署 → F006 活体验证。每 PR 前 `git diff origin/main...HEAD --stat` + Flyway 撞号检查
(`git ls-tree origin/main db/flyway | grep -oE 'V[0-9_]+' | sort | uniq -d`)。created_by 走线程参 (禁 SecurityUtils)。

## 10. 前提与 backlog
- **前提**: 5xxx 生产成本/制造费用 在本系统恒 0 (存货核算→GL 未实现); 结转不碰 5xxx 安全。
- **backlog**: 一旦上存货核算, 5xxx WIP 须在 `BalanceSheetService` 改作资产列示 (现折进合成留存收益, 错) —
  与本功能解耦, 单列。
- **backlog**: 任意盈余公积 / 应付股利 / 利润分配明细 不在范围; P3 封顶用 4101 整体近似。

---

## 附: v1→v2 审计修订记录 (可追溯)
| 审计 | 发现 | v2 修订 |
|---|---|---|
| A#1 | 5xxx 错结转 | §1.1 只结 6xxx; §3/§10 记 WIP 前提 |
| A#3 | tax-on-tax | §1.2 税基显式排 6801 按 code |
| A#4 | 漏 6711 | §1.1 补 6711 |
| A#5 | 月度计税多计 | §1.2 累计 YTD true-up |
| A#6 | 盈余公积 弥补亏损/封顶 | §1.3 补弥补亏损 + 封顶法定 |
| A#7 | 硬编码方向 | §1.1 按余额方向结转 |
| B#1 | 20天窗口陈旧 | §2 方案A 锁定时结转 |
| B#2 | 两结账路径/无reverse | §2.2/2.4 finalize+reopenPeriod 单咽喉 |
| B#3/B#5 | revision/并发 | §5 rev 定义 + 串行 + catch 幂等 |
| B#4 | 年度窗口/期初 | §4.4/§6 全年窗口 + 无期初处理 |
| B#7 | created_by null | §4.4 SYSTEM 哨兵 |
| B#8 | 幂等以凭证 | §5 改以 closingPostedAt/status |
| B#11 | REVERSED不可VOID | §5 invariant + 测试 |
| C#1 | 无 createManual | §4.1 新增 API |
| C#2/extra | enum/CHECK | §4.2 mapLinkType + CHECK 迁移 P1 |
| C#4 | 快照顺序 | 方案A 自然解 (§6) |
| C#6 | DRAFT 计入 | §4.3 POSTED-only |
| C#9 | 事务吞异常 | §4.4 join tx 不 fail-soft |
