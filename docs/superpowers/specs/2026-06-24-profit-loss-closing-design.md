# 结转损益自动凭证 (Profit & Loss Closing) — 设计 spec

**日期**: 2026-06-24
**触发**: 财务模块审计 (`docs/audits/finance-module-gap-analysis-2026-06-24.md`) Tier-1 #1。
现 `MonthCloseServiceImpl.executeClose()` 月结只锁期 + 快照报表, **不过结转损益凭证** →
本年利润/未分配利润从不入账, 资产负债表靠"合成未分配利润行"(见 `BalanceSheetService`,
2026-06-24 #1100) 绕过, 不是真账。本功能让月结真账化。

**范围 (用户拍板: 完整)**: 月末结转损益 + 所得税计提 + 年末结转 + 法定盈余公积提取。
**触发 (用户拍板)**: 月结 `executeClose()` 时自动生成并过账 (POSTED); 反结账时自动红冲。

---

## 1. 会计模型 (中国 GAAP)

**期末 (每月)**: 把损益类科目期间余额结转到 `本年利润 (4103)`:
- 借 各收入类 (6001 主营业务收入 / 6051 其他业务收入 / 6101 投资收益 / 6301 营业外收入) / 贷 4103 本年利润
- 借 4103 本年利润 / 贷 各成本费用类 (5001 生产成本 / 5101 制造费用 / 6401 主营业务成本 / 6402 其他业务成本 / 6403 税金及附加 / 6601 销售费用 / 6602 管理费用 / 6603 财务费用 / 6701 资产减值损失 / 6801 所得税费用)
- 结果: 4103 余额 = 当月净利润; 所有损益类科目结平为 0

**所得税计提 (每月, 税前利润>0)**: 借 6801 所得税费用 / 贷 2221 应交税费 = 税前利润 × 所得税率
(6801 随后在月末结转一并转入 4103, 故净利已含税后)

**年末 (仅 12 月)**:
- 借 4103 本年利润 (全年累计) / 贷 4104 利润分配-未分配利润
- 提取法定盈余公积 (净利>0): 借 4104 利润分配 / 贷 4101 盈余公积 = 净利 × 盈余公积率, 封顶 累计盈余公积 ≤ 50% × 实收资本(4001)

**科目全部已 seed** (V20260701_02): 4101/4103/4104/6801/2221 均存在 → **无需新增科目迁移**。

---

## 2. 架构 / 组件

### 2.1 新建 `ProfitLossClosingService`
位置: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/ProfitLossClosingService.java`
单一职责: 给定 (factoryId, year, month) 生成并过账结转凭证; 反结账时红冲。聚焦可独立单测。

依赖: `VoucherEntryRepository` (aggregateBySubject 取损益类余额) · `VoucherService` (建/过凭证) · `AccountRepository` (科目分类/名称) · `FinanceClosingConfigService` (税率/盈余公积率, 见 2.4)。

核心方法:
```
void closePeriod(String factoryId, int year, int month)   // 月结调用: 计税→结转损益→(12月)年结
void reversePeriodClosing(String factoryId, int year, int month)  // 反结账调用: 红冲该期 PL_CLOSING 凭证
```

### 2.2 接入 `MonthCloseServiceImpl`
`executeClose(factoryId, year, month)` 顺序 (结转凭证必须在期间仍可过账时过账, 即置 CLOSED 之前):
1. (现有) 校验期间可结
2. **新增** `profitLossClosingService.closePeriod(...)` — 计税 + 结转损益 (+ 12月年结)
3. (现有) 快照报表 (此时快照已反映结转后的真账)
4. (现有) 置 status = CLOSED

反结账路径 (`AccountingPeriodService` reopen / MonthClose reverse):
1. **新增** `profitLossClosingService.reversePeriodClosing(...)` — 红冲 PL_CLOSING 凭证
2. (现有) 置 status = OPEN

### 2.3 凭证结构
- 新增 `VoucherType.PL_CLOSING` (枚举值, 用于结转凭证)。
- `voucher_date` = 期末 (`YearMonth.of(y,m).atEndOfMonth()`)。
- `source_business_type = 'PL_CLOSING'`; `source_business_id = closing-{factoryId}-{year}-{month}-{kind}-r{revision}`, kind ∈ {tax, monthly, annual, reserve}; revision = 该期已结次数 (0-based, 反结账后重结 +1) → 复用 voucher (businessType,businessId) 唯一约束做幂等。
- `status = POSTED`, `created_by` = 触发结账的 userId (线程传入, 禁用 SecurityUtils, 见 [[feedback_preauthorize_noop_and_sync_section_asyncpg]] pattern#3)。
- 借贷必平 (`Voucher.validateBalanced()`); 金额 BigDecimal(2) HALF_UP。

### 2.4 配置 `FinanceClosingConfig` (P2 才需要)
- application 默认: `cretas.finance.income-tax-rate=0.25`, `cretas.finance.surplus-reserve-rate=0.10`。
- 可选按工厂 override: 轻量表 `finance_closing_config (factory_id PK, income_tax_rate, surplus_reserve_rate)`; 无行则用默认。
- P1 不需要 (P1 只结转损益, 不计税不提公积)。

### 2.5 红冲 (reversePeriodClosing)
对该期每张 PL_CLOSING 凭证, 建镜像冲销凭证: 借贷对调、同金额、`status=POSTED`、
`reversal_voucher_id`↔`original_voucher_id` 关联、`source_business_id=...-reversal`。
原凭证 + 冲销凭证在科目余额上净为 0。**此红冲逻辑顺带成为 Tier-1「红字冲销」的地基**
(后续可推广到任意凭证)。

---

## 3. 与现有资产负债表自洽

`BalanceSheetService` (2026-06-24 #1100) 的合成「未分配利润」行 = Σ(credit−debit) over 损益类(REVENUE/COST/EXPENSE)。
- **未结期间**: 损益类有余额 → 合成行 = 未结转利润, 显示之 (现状)。
- **已结期间**: 结转凭证把损益类结平为 0 → 合成行 = 0 自动消失; 真实 4103/4104 (EQUITY) 持有利润, 作为正式权益行显示。
两态资产负债表都平衡 (双分录恒等式)。**无需改 BalanceSheetService**。

---

## 4. 边界 / 错误处理

| 情况 | 处理 |
|---|---|
| 幂等 (重复结转) | closePeriod 先查该期是否存在 **active** PL_CLOSING 凭证 (reversal_voucher_id IS NULL 且 status≠VOID 且自身非红冲凭证)。有 → 跳过 (已结)。无 → 以 revision = (该期历史结转批次数) 生成新一批, source_business_id 带 -r{revision} 后缀避免与已红冲的旧批撞键 |
| 反结账后重结 | reversePeriodClosing 给旧批每张记 reversal_voucher_id (不删原凭证) → 该期无 active 批 → closePeriod 以 revision+1 重新生成, 键不撞 |
| 税前利润 ≤ 0 | 不计所得税 (税额=0, 不生成计税凭证); 不提盈余公积 |
| 盈余公积封顶 | 累计 4101 余额 ≥ 50% × 4001 实收资本 → 不再提取 |
| 期间不可过账 | closePeriod 在 executeClose 置 CLOSED *之前*调用, 期间仍 OPEN/可过账 |
| 无损益发生额 | 该期无收入成本 → 不生成结转凭证 (no-op) |
| 反结账 | 红冲该期所有 PL_CLOSING 凭证; 重结账重新生成 |
| 12 月非年末场景 | 仅 month==12 触发年结 (4103→4104 + 盈余公积) |

---

## 5. 分期实施 (均在「完整」范围, 每期独立 ship + F006 prod 验证)

- **P1 — 月末结转损益核心**: `ProfitLossClosingService.closePeriod` 月末结转 (损益类→4103) + `VoucherType.PL_CLOSING` + 接入 MonthClose executeClose + `reversePeriodClosing` 红冲 + 反结账接入。**让三大报表真账化** (替换合成未分配利润行)。
- **P2 — 所得税计提**: `FinanceClosingConfig` (税率配置) + 计税凭证 (借6801/贷2221) 接入 closePeriod (在月末结转之前)。
- **P3 — 年末结转 + 盈余公积**: 12 月 4103→4104 + 法定盈余公积提取 (含封顶校验)。

---

## 6. 测试计划

**单测 `ProfitLossClosingServiceTest`** (mock repo, TDD):
- 月末结转: 收入1000/成本600/费用200 → 4103 = 200; 损益类结平为 0; 凭证借贷平
- 幂等: 重复 closePeriod 不重复过账
- 利润≤0: 不计税、不提公积
- 12 月年结: 4103 全年 → 4104; 盈余公积 = 净利×10%; 封顶生效
- 反结账红冲: reversePeriodClosing 后该期 PL_CLOSING 净额为 0
- 所得税: 税前1000 → 6801=250 (25%), 2221+=250

**集成 (mock 或现有 finance test 风格)**:
- `BalanceSheetService` 结后: 损益类 0、合成行消失、4103/4104 持利润、balanceCheck=true
- `MonthCloseServiceImpl` executeClose 调用 closePeriod 顺序正确

**prod 验证 (F006)**: 对某月 executeClose → 查凭证列表有 PL_CLOSING + 资产负债表 4103 显真实利润 + 仍平衡; 反结账 → 红冲凭证生成 + 损益恢复。绝不碰 LIUSHANMEN。

---

## 7. 🔒 红线 (Opus 终审 + 从 main 部署)
影响 GL 真账 + 全部租户 + 含真实 LIUSHANMEN: prod 部署/Flyway(P2 config 表)/凭证过账逻辑 →
worktree off origin/main → TDD → 独立 review → Opus 终审 → 从 main 部署 → F006 活体验证。
created_by 走线程参数 (禁 SecurityUtils)。每 PR 前 `git diff origin/main...HEAD --stat` + Flyway 撞号检查。
