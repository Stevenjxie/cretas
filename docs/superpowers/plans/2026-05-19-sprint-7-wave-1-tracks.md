# Sprint 7 Wave 1 Detailed Track Briefs (2026-05-19, post Sprint 6 wave 2 dispatch)

> **触发**: Sprint 6 wave 2 (5 agents) dispatched. 这份是 Sprint 7 wave 1 — Sprint 6 wave 2 ship 完即可 dispatch.
> **客户群**: 大企业财务 (复式/期间/报表三表) + F006/通用 (商机/业绩/RBAC E2E) + Boss 演示 (Round 14 demo benchmark).
> **总工时**: ~50d nominal (大企业财务 21d + CRM 10d + RBAC 5d + 数电票 spike-real 4d + Round 14 5d + 缓冲 5d).
> **策略**: 大企业 readiness 完整化 + Boss 演示就绪.

---

## §M Marching Order (dispatch sequence, post Sprint 6 wave 2 merge)

| 优先级 | Track | 依赖 Sprint 6 wave 2 PR | Wall time | 客户群 |
|---|---|---|---|---|
| ⚡ IMMEDIATE | T1 复式记账 F-VOUCHER-2 | 无 (PR #69 W4-A merged) | 8d | 大企业财务 |
| ⚡ IMMEDIATE | T6 RBAC 3-role × 5-scope E2E | 无 (PR #54/#68 merged) | 5d | 大客户测试覆盖 |
| ⏳ QUEUED | T2 期间结账 F-PERIOD | T1 ship | 5d | 大企业财务 |
| ⏳ QUEUED | T3 报表三表 F-3REPORT | T1 + T2 ship | 8d | 大企业财务 + 上市公司 |
| ⏳ QUEUED | T4 商机 8 阶段 CRM funnel | wave 2 W2-C ship | 6d | 大客户 sales |
| ⏳ QUEUED | T5 业绩 6 项 sales target | T4 ship | 4d | F006 销售 |
| ⏳ PARALLEL | T7 Round 14 HJ demo benchmark | Sprint 6 wave 2 all ship | 5d | Boss 演示 |
| 🚫 BLOCKED | T8 W1-A 数电票真集成 | Steve provider 决策 (百望/航天/诺诺) | 10d | 大客户硬需 |

**并行**: T1 + T6 + T7 三个 chat 同时起 OK (T1 = backend, T6 = test, T7 = doc + Playwright). T2/T3/T4/T5 串行 等待依赖.

**第一波 dispatch**: T1 + T6 + T7 (3 chat 并行, ~5-8d wall).
**第二波 dispatch** (T1 ship 后): T2 + T4 (T2 等 T1, T4 独立).
**第三波 dispatch** (T2 ship 后): T3 (大型 + 等 T2).
**第四波 dispatch** (T4 ship 后): T5 (业绩 跟 商机 强依赖).

---

## §T1 Track 复式记账 F-VOUCHER-2 (P0 8d, 大企业财务必上)

### Brief (paste-ready for sister chat)

> Sprint 7 wave 1 Track T1 — 复式记账 F-VOUCHER-2 (P0 8d, 大企业财务必上).
>
> **Context**: Round 12 §G.4 finding — 大企业财务 (会计师事务所/上市公司/工商局) 硬需 双向记账 (借/贷 平衡). Cretas Sprint 5+6 ship Voucher 单向 + 辅助核算 (PR #53 F + #69 W4-A merged), **缺 复式记账平衡**. F006 选做 (中型企业 起步阶段够单向, 但 grow 后 必上).
>
> **Scope (8d)**:
> 1. **Day 1-2 entity refactor (~120 min agent budget)**:
>    - 现 `Voucher` entity 单 `amount` field → 拆 `debitAmount` + `creditAmount` (BigDecimal each, default 0)
>    - 新 `VoucherEntry` (id, voucherId, accountId 科目, debit BigDecimal, credit BigDecimal, summary text, BaseEntity audit)
>    - 平衡约束: `SUM(entries.debit) == SUM(entries.credit)` per voucher (Service-level validation, throw if not 平衡)
>    - Flyway `V20260601_01__voucher_double_entry.sql` (refactor voucher table + add voucher_entry)
> 2. **Day 3-4 generator wiring (~90 min)**:
>    - 7 existing voucher generators (per PR #53 F + #69 W4-A) ship 单向 — refactor each to emit `VoucherEntry` 复式:
>      - Sales: 借 应收 / 贷 收入 + 应交税费
>      - Purchase: 借 库存 + 应交税费 / 贷 应付
>      - Wage: 借 应付职工薪酬 / 贷 银行存款 (or 应付)
>      - 4 others (per existing AuxiliaryType)
>    - Per-generator unit test verify 平衡
> 3. **Day 5-6 frontend (~90 min)**:
>    - VoucherDetail.vue: 拆显 借/贷 两栏 table (借 left, 贷 right, totals bottom show 平衡 ✓ or 不平衡 ✗)
>    - VoucherCreate.vue: 加 entry 时 dropdown 科目 + 选 借 or 贷 + 金额. 实时算 balance, submit button disabled until 平衡 (rule 1 precondition per fool-proof-design.md).
> 4. **Day 7 admin 科目 setup (~60 min)**:
>    - 新 entity `Account` (科目 chart of accounts: id, code 1001 etc, name 现金 etc, parentId for tree, level, balanceType DEBIT_NORMAL/CREDIT_NORMAL, BaseEntity)
>    - Seed standard 中国 GAAP 1-级 科目 (~30 entries) via Flyway `V20260601_02__seed_accounts.sql`
>    - Account CRUD admin UI
> 5. **Day 8 verification + docs (~60 min)**:
>    - E2E: 创建 1 sales order → 凭证 generate → 复式 entries 平衡 ✓
>    - 报表 placeholder for T3 (impl 真表 in T3)
>    - 文档: 复式记账 spec + 7 generator 映射表
>
> **依赖**: PR #69 W4-A 辅助核算 ship (✅ merged). 不依赖 wave 2.
>
> **Spec inputs**:
> - 32-doc §G.4 (Round 12 大企业财务 finding)
> - 33-doc §15 (Round 13 GAAP 科目结构)
> - PR #53 F + PR #69 W4-A (existing Voucher + AuxiliaryType base)
>
> **DOD**:
> - 7 generator 全 emit 复式 entries 平衡 ✓
> - VoucherDetail UI 显 借/贷 两栏
> - 30+ Account seed ship + CRUD UI
> - 1 sales E2E pass (订单 → 凭证 → 平衡)
> - 后向兼容: 现有单向 Voucher data migration (1 column → entry record)
>
> **风险**:
> - Data migration 难: 现有单向 voucher 需 backfill 成 复式 (借应收 / 贷收入). Day 1-2 跑 dry-run.
> - 7 generator 全改 → 风险大. 推荐 1 chat 单做 8d (不 sub-split, 一致性).
>
> **PR**: 1 PR `sprint7/T1-voucher-double-entry`

---

## §T2 Track 期间结账 F-PERIOD (P0 5d, T1 之后)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T2 — 期间结账 F-PERIOD (P0 5d, 大企业财务 closing process).
>
> **Context**: Round 12 §G.5 finding — HJ 期间结账 (月底/季末/年末 锁账, voucher 不可改) Cretas 缺. T1 复式记账 ship 后必上 (没期间锁则 复式 data 可乱改).
>
> **Scope (5d)**:
> 1. **Day 1 entity (~60 min)**:
>    - 新 entity `AccountingPeriod` (id, year, month, status enum OPEN/PENDING_CLOSE/CLOSED, openedAt, closedAt, closedBy, BaseEntity)
>    - Flyway `V20260608_01__accounting_period.sql` (PG, factory_id RLS, unique (factoryId, year, month))
> 2. **Day 2 service (~60 min)**:
>    - `AccountingPeriodService`:
>      - `openPeriod(year, month)` — 新建 (status OPEN)
>      - `requestClose(year, month)` — status PENDING_CLOSE, trigger approval workflow (per decisionType FINANCE_DIRECTOR from W3-B)
>      - `confirmClose(year, month)` — status CLOSED, freeze all vouchers in this period
>      - `reopenPeriod(year, month)` — 反结账 (status CLOSED → OPEN, audit log)
>    - Voucher 写操作 必先 check `period.status == OPEN` (else throw `PeriodClosedException` 400)
> 3. **Day 3 REST + admin UI (~90 min)**:
>    - REST `AccountingPeriodController` (POST openPeriod / POST requestClose / POST confirmClose / POST reopen / GET status)
>    - admin UI `AccountingPeriodList.vue` (Canvas → 财务 → 期间结账 — find or create)
>    - 防呆: rule 1 当前期间 status display (e.g. "2026-05 OPEN, 凭证 N 笔") / rule 5 dead-end (CLOSED 期间 试图改 voucher 时 ElMessageBox.confirm "期间已结账, 是否反结账?" → 调 reopenPeriod)
> 4. **Day 4 scheduled (~60 min)**:
>    - Optional auto-close: `@Scheduled(cron = "0 0 2 1 * ?")` (每月 1 号 02:00) — auto request-close 上月 period (status OPEN → PENDING_CLOSE, no auto confirm — 需 finance director 手工 confirm)
> 5. **Day 5 verification (~60 min)**:
>    - E2E: 5 月 openPeriod → 创建 voucher → 6 月 1 号 auto request-close → finance approval → confirmClose → 试图改 5 月 voucher → 400 PeriodClosedException
>
> **依赖**: T1 ship (复式记账 voucher), PR #54 G RBAC (FINANCE_DIRECTOR role)
>
> **Spec inputs**:
> - 32-doc §G.5 (Round 12 期间结账)
>
> **DOD**: 5 status transition + voucher 写 gated + admin UI + E2E pass + 反结账 audit log.
>
> **PR**: 1 PR `sprint7/T2-accounting-period-close`

---

## §T3 Track 报表三表 F-3REPORT (P0 8d, T2 之后)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T3 — 报表三表 F-3REPORT (P0 8d, 资产负债 + 利润 + 现金流).
>
> **Context**: Round 12 §G.6 finding — 上市公司 / 投资人 / 银行 借贷必看. T1 复式 + T2 期间结账 ship 后 数据基础具备.
>
> **Scope (8d)**:
> 1. **Day 1-2 资产负债表 (~120 min)**:
>    - 计算 service `BalanceSheetService.generate(factoryId, period)` — 按 Account.balanceType + 期末 entries sum 计算
>    - 输出 DTO `BalanceSheetDTO` (assets total / liabilities total / equity total + 平衡 check ✓)
>    - REST `GET /api/mobile/{factoryId}/report/balance-sheet?year=2026&month=5`
>    - Frontend `BalanceSheet.vue` (Canvas → 财务 → 报表 → 资产负债表)
> 2. **Day 3-4 利润表 (~120 min)**:
>    - 计算 service `IncomeStatementService.generate(factoryId, startPeriod, endPeriod)` — 期间 收入 - 成本 - 费用 = 利润
>    - 4-level: 营业收入 / 营业成本 / 营业利润 / 净利润
>    - REST `GET /api/mobile/{factoryId}/report/income-statement?startYear=2026&startMonth=1&endMonth=5`
>    - Frontend `IncomeStatement.vue`
> 3. **Day 5-6 现金流量表 (~120 min)**:
>    - 计算 service `CashFlowService.generate(factoryId, startPeriod, endPeriod)` — 经营/投资/筹资 3 类活动现金流
>    - 计算复杂 (需 trace voucher entries → 现金 account 1001 changes)
>    - REST + Frontend
> 4. **Day 7 export PDF/Excel (~60 min)**:
>    - 3 表统一 export endpoint (复用 PR #65 W3-C printing templates pattern)
>    - PDF 中国 GAAP 标准格式
> 5. **Day 8 verification (~90 min)**:
>    - E2E: T1 复式 → T2 期间 close 5 月 → 3 表 generate → 数据正确 (vs manual calc 对照)
>    - 防呆: rule 2 context (报表 header 显 "2026-05 月报") / rule 5 dead-end (T1 不平衡 / T2 期间 OPEN → 报表显 "WARN: 数据未结账, 可能不准")
>
> **依赖**: T1 ship + T2 ship (强依赖, 不可并行)
>
> **Spec inputs**:
> - 32-doc §G.6 (Round 12 报表三表)
> - 33-doc §15 (Round 13 GAAP 报表结构)
>
> **DOD**: 3 表全 generate + UI 显 + PDF export + 1 E2E full cycle pass.
>
> **风险**: 现金流量表算法 复杂. Day 5-6 可能跑超时, 推荐 1 chat 8d 单做.
>
> **PR**: 1 PR `sprint7/T3-three-financial-reports`

---

## §T4 Track 商机 8 阶段 CRM funnel (P1 6d)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T4 — 商机 8 阶段 CRM funnel (P1 6d, 大客户 sales 必上).
>
> **Context**: Round 13 §15 finding — HJ 商机 8 阶段 (Lead → Qualified → Demo → Proposal → Negotiate → Verbal → Closed-Won / Closed-Lost). Cretas Sprint 5 PR #53 F ship Customer entity, 缺 商机 stage tracking. F006 销售场景: 销售看 pipeline 进度. 大客户 b2b 必上.
>
> **Scope (6d)**:
> 1. **Day 1 entity (~60 min)**:
>    - 新 entity `SalesOpportunity` (id, customerId, title, stage enum LEAD/QUALIFIED/DEMO/PROPOSAL/NEGOTIATE/VERBAL/CLOSED_WON/CLOSED_LOST, valueAmount BigDecimal, probability 0-100, expectedCloseDate Date, ownerId 销售, BaseEntity audit + soft delete)
>    - Flyway `V20260615_01__sales_opportunity.sql`
> 2. **Day 2 service + state machine (~90 min)**:
>    - `SalesOpportunityService` with `transitionStage(id, newStage, reason)` — validate transition (LEAD→QUALIFIED OK, CLOSED_WON→LEAD NOT OK)
>    - Stage history audit log entity `OpportunityStageHistory`
>    - Probability auto-update on stage change (LEAD 10%, QUALIFIED 30%, ... CLOSED_WON 100%) — overridable
> 3. **Day 3 REST + frontend list (~90 min)**:
>    - REST `SalesOpportunityController` (POST/GET pagedList by stage/PUT transition/DELETE)
>    - `SalesOpportunityList.vue` (Canvas → CRM → 商机) — table + filter by stage + 我的商机 toggle
>    - 防呆 rule 3: stage `<el-select>` dropdown (with 转化率 hint)
> 4. **Day 4 frontend kanban view (~90 min)**:
>    - `SalesOpportunityKanban.vue` — 8 columns 拖拽 (drag to transition stage)
>    - 防呆 rule 5: empty kanban "去新增商机" button
> 5. **Day 5 reports (~60 min)**:
>    - Pipeline 总值 report (sum valueAmount × probability per stage)
>    - 漏斗图 chart (Vue echarts)
>    - REST `GET /api/mobile/{factoryId}/sales-opportunity/funnel-stats`
> 6. **Day 6 verification (~60 min)**:
>    - E2E: 创建 商机 LEAD → transit QUALIFIED → DEMO → ... → CLOSED_WON → history audit log + funnel stats update
>
> **依赖**: PR #53 F Customer entity (✅ merged), wave 2 W2-C-1/W2-C-2 微信/通话 (✅ if ship) — 商机 detail 显 微信/通话 history
>
> **Spec inputs**:
> - 33-doc §15 (Round 13 商机 8 阶段 finding)
>
> **DOD**: 8 stage transition + state machine + list + kanban + funnel report + E2E pass.
>
> **PR**: 1 PR `sprint7/T4-sales-opportunity-funnel`

---

## §T5 Track 业绩 6 项 sales target (P2 4d, T4 之后)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T5 — 业绩 6 项 sales target + commission + quota (P2 4d).
>
> **Context**: Round 13 §15 finding — HJ 业绩 6 项 (月/季/年 target, 实际 vs target gap, 销售排名, 提成 commission, 团队 quota, 完成率). Cretas 缺. T4 商机 ship 后必上 (业绩 metric 基于 商机 CLOSED_WON valueAmount).
>
> **Scope (4d)**:
> 1. **Day 1 entity (~60 min)**:
>    - 新 entity `SalesTarget` (id, ownerId 销售, period 月/季/年, year, periodNum, targetAmount BigDecimal, actualAmount BigDecimal computed, BaseEntity)
>    - 新 entity `Commission` (id, salesOpportunityId, percentage, amount, status PENDING/PAID, BaseEntity)
>    - Flyway `V20260622_01__sales_target_commission.sql`
> 2. **Day 2 service (~90 min)**:
>    - `SalesTargetService.computeActual(ownerId, period)` — sum 商机 CLOSED_WON valueAmount in period
>    - `CommissionService.calculate(opportunityId)` — based on CommissionRule (admin config: SKU/客户类型 → percentage)
>    - vflag listener: opportunity CLOSED_WON → auto compute commission
> 3. **Day 3 frontend (~90 min)**:
>    - `MySalesTargetView.vue` (个人 view, default to current user) — 月/季/年 target vs actual + 完成率 + 排名
>    - `SalesLeaderboard.vue` (org-wide ranking)
>    - `CommissionList.vue` + admin CommissionRuleConfig
> 4. **Day 4 verification (~60 min)**:
>    - E2E: 设 target → 商机 CLOSED_WON → commission auto compute + actualAmount update + 排名 reflect
>
> **依赖**: T4 ship
>
> **DOD**: 3 view + state computed + commission auto-trigger + E2E pass.
>
> **PR**: 1 PR `sprint7/T5-sales-target-commission`

---

## §T6 Track RBAC 3-role × 5-scope E2E matrix (P1 5d)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T6 — RBAC 3-role × 5-scope E2E matrix test coverage (P1 5d, 大客户测试覆盖必上).
>
> **Context**: Sprint 5+6 ship PR #54 G RBAC framework + PR #68 W2-B sweep (6 services). **缺 系统测试覆盖** — 大客户 audit 必看 RBAC matrix 全 pass.
>
> **Scope (5d)**:
> 1. **Day 1 fixture setup (~60 min)**:
>    - 创建 15 test fixture (3 role × 5 scope):
>      - Roles: SALES / SALES_MGR / FINANCE_DIRECTOR
>      - Scopes: ALL / DEPT / DEPT_AND_BELOW / SELF / SELF_AND_BELOW
>    - Seed 1 demo org tree (3 levels: 总公司 → 部门 → 班组), 15 users assigned roles × scopes
> 2. **Day 2-3 Playwright E2E (~180 min)**:
>    - 15 test files in `web-admin/tests/rbac/<role>-<scope>.spec.ts`
>    - Each test: login as user → 访问 6 endpoint (customer/PO/invoice/delivery/inventory/voucher) → verify data filter correct (e.g. SALES + DEPT only sees own department's data)
> 3. **Day 4 backend integration tests (~120 min)**:
>    - `RbacIntegrationTest.java` — 15 test methods, JWT mint per fixture user → MockMvc invoke endpoint → assert response data scope correct
> 4. **Day 5 CI integration + docs (~60 min)**:
>    - Add `rbac-e2e` GitHub Actions job (runs all 15 Playwright tests)
>    - Add RBAC matrix doc to `docs/security/rbac-coverage-matrix.md` (15 cells, each cell ✓ or ✗ with PR ref)
>
> **依赖**: PR #54 G + PR #68 W2-B ship (✅ merged)
>
> **DOD**: 15 Playwright + 15 backend integration tests pass + CI job + matrix doc.
>
> **PR**: 1 PR `sprint7/T6-rbac-e2e-matrix`

---

## §T7 Track Round 14 HJ 端到端 demo benchmark (P1 5d, Boss 演示)

### Brief (paste-ready)

> Sprint 7 wave 1 Track T7 — Round 14 HJ 端到端 demo benchmark (P1 5d, Boss 演示就绪).
>
> **Context**: Sprint 6 skeleton §M 提及 "Round 14 Cretas vs HJ 端到端 demo benchmark (Boss 演示就绪)". Sprint 6 wave 2 ship 后 是 Sprint 7 第一波. 不写代码 — 录 demo + 写 comparison doc.
>
> **Scope (5d)**:
> 1. **Day 1 4 场景 script 设计 (~60 min)**:
>    - 场景 1: 销售订单创建 → 审批 → 发货 → 收款
>    - 场景 2: 采购请购 (Sprint 6 W2-A) → PO 转换 → 入库 → 付款
>    - 场景 3: 工资计算 (Sprint 6 W4-B) → 凭证 generate → 审批 → 发放
>    - 场景 4: 财务月结 (Sprint 7 T2 if ship) → 报表三表 (Sprint 7 T3 if ship) → PDF export
> 2. **Day 2-3 HJ Playwright 录制 (~180 min)**:
>    - lyh01/admin/Aa123456 登录 (per memory rules: 不改 admin 密码 / 不删账号 / 邮件填 jx453@cornell.edu)
>    - 4 场景每个录制 Playwright video (mp4 30-60s)
>    - 截图关键步 (~5 screenshots/场景)
> 3. **Day 4 Cretas Playwright 录制 (~120 min)**:
>    - F006 prod account (per memory `reference_f006_liutengmen_prod_accounts`)
>    - 4 场景每个录制 (mp4 30-60s)
>    - 同截图
> 4. **Day 5 comparison doc (~120 min)**:
>    - `宏见竞品分析/04-最终决策/35-ROUND-14-DEMO-BENCHMARK.md`
>    - 4 场景 side-by-side: HJ 步骤数 vs Cretas 步骤数 / HJ 屏数 vs Cretas 屏数 / HJ UI 风格 vs Cretas / HJ 防呆 vs Cretas (per fool-proof-design.md 5 rules)
>    - 总结: Cretas 优势 / HJ 优势 / Boss 演示 highlights
>
> **依赖**: Sprint 6 wave 2 ship (W2-C 微信/通话 / W3-B decisionType / W4-B WagePolicy / W4-C BomVersion) — 部分 wave 2 ship 即可起
>
> **HJ 测试账号约束 (per memory `reference_hongjian_test_account`)**:
> - 仅 lyh01/admin/Aa123456
> - ⛔ 不改 admin 密码 / 不删账号 / 不禁用工厂 / 不绑外部 OAuth / 不充值 / 邮件填 jx453@cornell.edu
> - 命名测试数据 `TEST_R14_*` 便于事后清理
>
> **DOD**: 4 场景 video + screenshots + comparison doc + Boss 演示 deck (PPT 可选).
>
> **PR**: 1 PR `sprint7/T7-round14-demo-benchmark` (含 video assets to OSS, NOT in git repo)

---

## §T8 Track W1-A 数电票真集成 (BLOCKED Steve provider 决策, 10d)

### Brief (paste-ready when unblocked)

> Sprint 7 wave 1 Track T8 — W1-A 数电票真集成 (BLOCKED, 10d).
>
> **Blocker**: Steve 决定 provider (百望 / 航天信息 / 诺诺 / 瑞宏) + sandbox key 申请. Sprint 5 PR #52 B ship spike (interface + NoopImpl `@Primary`) 等真集成.
>
> **Scope (unblocked = 10d)**: per Sprint 6 skeleton §W1-A — Maven baiwang-sdk + 4 method 真 impl + HMAC-SHA256 signing + scheduled polling + Vue UI toggle + InvoiceList status chip + 沙箱 E2E.
>
> **Steve 前置决策**: 1) 选 provider 2) 申请 sandbox key 3) 拿 API spec doc.
>
> **Decision aids**:
> - 百望: 老牌, API 文档全, 价格中等, F006 客户 (会计师事务所) 推荐
> - 航天信息: 政府背景, 大客户偏好, 但 API 文档少
> - 诺诺: 中小客户, 价格低, F006 替代选项
> - 瑞宏: 区域 (深圳/广东) 强, 跨地区客户慎用

---

## §N 并行 dispatch 模式

**第一波 (推荐, post Sprint 6 wave 2 merge)**: 3 chat 并行
- Chat 1: T1 复式记账 (8d, 最大)
- Chat 2: T6 RBAC E2E (5d)
- Chat 3: T7 Round 14 demo benchmark (5d, doc + video)
- (T8 unblocked → +1 chat)
- Total wall ~5-8d

**第二波** (T1 ship 后): 2 chat
- Chat 4: T2 期间结账 (5d, T1 依赖)
- Chat 5: T4 商机 funnel (6d, 独立)

**第三波** (T2 + T4 ship 后): 2 chat
- Chat 6: T3 报表三表 (8d, T2 强依赖)
- Chat 7: T5 业绩 (4d, T4 强依赖)

**Worktree isolation 必须** (per `feedback_concurrent_edit_safety.md` HARD).

**Dispatch 前 check**:
- ☑ Sprint 6 wave 2 5 PRs 全 merged (W2-C-1 / W2-C-2 / W3-B / W4-B / W4-C)
- ☑ T1 entity refactor 不破 Sprint 5+6 ship (1-2 dry-run migration test)
- ☑ Steve sign-off "Sprint 7 wave 1 launch"
- ☑ gh ops budget ≤10/hour (per memory)

**预期 PR 数**: 7 PRs Sprint 7 wave 1 (T1-T7) + 1 PR T8 (unblocked)

---

## §O 后续 (post Sprint 7 wave 1)

| Sprint | 主题 | 工时 | 客户群 |
|---|---|---|---|
| Sprint 7 wave 2 | 序列号 / 设备 lifecycle / 招聘宿舍 / docs 子域 | ~30d | F006 + 中型 |
| Sprint 8 | TV 大屏 / 微服务化 / RBAC 细粒度 (column-level) | ~40d | 大企业 architect |
| Sprint 9+ | HJ APK 实测 (Round 9) + 客户触发 backlog | open | 按需 |

**Round 15+ (post Boss 演示)**: 真客户 onboarding 节奏决定. 9 月 sign-off 节点.

---

## §P 工时累计

| 阶段 | 剩 backlog | 累计 |
|---|---|---|
| Sprint 5 ship | -70d | 收 |
| Sprint 6 wave 1 ship | -50d | 收 |
| **Sprint 6 wave 2 ship (in-flight 5 agents)** | **-13d** | **~3-4d wall** |
| **Sprint 7 wave 1 ship** | **-50d** | **~10-15d wall (3 waves)** |
| Sprint 7 wave 2 (序列号/设备 lifecycle) | -30d | ~1 月 |
| Sprint 8+ | -40d | ~1.5 月 |
| **Total Sprint 5-8 ship** | **~150d 计划** | **~3-4 月 实际 (重并行)** |

vs 9 月 sign-off → Sprint 7 wave 1 ship 后 **~6 周 buffer** for真客户 polish + bug fix.

---

**Sprint 7 wave 1 detailed track briefs v1.0 完成 (2026-05-19, organizer post Sprint 6 wave 2 dispatch)**.

Sprint 6 wave 2 5 PRs merge → fire T1 + T6 + T7 (+ T8 if Steve provider decision).
