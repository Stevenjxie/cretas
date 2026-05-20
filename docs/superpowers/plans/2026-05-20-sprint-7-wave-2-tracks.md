# Sprint 7 Wave 2 Detailed Track Briefs (2026-05-20, post Sprint 7 wave 1 dispatch)

> **触发**: Sprint 7 wave 1 (T1 复式 / T6 RBAC E2E / T7 Round 14) dispatched. 这是 wave 2 — wave 1 PR merge 后即可 dispatch.
> **客户群**: 大企业财务深度 (T2 + T3) + 大客户 CRM (T4 + T5)
> **总工时**: ~23d nominal (T2 5d + T3 8d + T4 6d + T5 4d) — ~5-7d wall 4 chat 并行
> **依赖**: T1 ship (T2 + T3 需 复式 Voucher), T4 + T5 独立

---

## §M Marching Order (post Sprint 7 wave 1 merge)

| 优先级 | Track | 依赖 wave 1 | Wall time | 客户群 |
|---|---|---|---|---|
| ⚡ T2 期间结账 F-PERIOD | T1 ship (复式 Voucher) | 5d | 大企业财务 closing |
| ⚡ T4 商机 8 阶段 CRM funnel | 无 (independent) | 6d | 大客户 sales |
| ⏳ T3 报表三表 F-3REPORT | T1 + T2 ship | 8d | 大企业财务 + 上市公司 |
| ⏳ T5 业绩 6 项 sales target | T4 ship | 4d | F006 销售 |

**第二波 dispatch** (post T1 ship): T2 + T4 (parallel, both independent of each other).
**第三波 dispatch** (post T2 + T4 ship): T3 + T5 (parallel).

**并行可能**: T2 + T4 互独立 (前者 finance, 后者 CRM). T3 + T5 互独立. 第二/三波各 2 agent.

---

## §T2 Track 期间结账 F-PERIOD (P0 5d, T1 之后)

### Brief (paste-ready, dispatch after T1 ship)

> Sprint 7 wave 2 Track T2 — 期间结账 F-PERIOD (P0 5d, 大企业财务 closing process).
>
> **⛔ CRITICAL — Worktree isolation** (per `feedback_agent_worktree_isolation_cwd_drift.md` HARD):
> - DO NOT `cd C:\Users\Steve\my-prototype-logistics` (main repo)
> - First 2 commands: `pwd` (confirm worktree path) + `git branch --show-current` (confirm worktree-agent-*)
> - Branch from worktree: `git checkout -b sprint7/T2-accounting-period-close`
> - Stay in worktree throughout
>
> **Context**: Round 12 §G.5 finding — HJ 期间结账 (月底/季末/年末 锁账, voucher 不可改). Cretas 缺. T1 ship 复式记账 后必上 (没期间锁则 复式 data 可乱改).
>
> **Required reading**:
> 1. Spec: `<worktree>/docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md` §T2
> 2. T1 ship Voucher + VoucherEntry entities: grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Voucher*.java`
> 3. PR #54 G RBAC for FINANCE_DIRECTOR role: grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/User.java` for role enum
> 4. ShedLock infra: `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/config/ShedLockConfig.java`
> 5. W3-B DecisionType enum: `PERIOD_CLOSE_APPROVAL` if shipped (or pick closest equivalent like `BUDGET_APPROVAL`)
>
> **Scope (5d → 120 min budget for MVP)**:
> 1. **Phase A entity (~20 min)**: New `AccountingPeriod` (id, factoryId, year, month, status enum OPEN/PENDING_CLOSE/CLOSED, openedAt, openedBy, closedAt, closedBy, reopenedAt, reopenedBy, reopenReason, BaseEntity audit). Flyway `V20260710_01__accounting_period.sql` (PG, factory_id RLS, UNIQUE on factoryId+year+month).
> 2. **Phase B service (~30 min)**: `AccountingPeriodService` 5 transitions:
>    - `openPeriod(year, month)` — status OPEN
>    - `requestClose(year, month)` — status PENDING_CLOSE + trigger approval workflow via ApprovalWorkflowExecutor (decisionType=BUDGET_APPROVAL or FINANCE_DIRECTOR if exists)
>    - `confirmClose(year, month)` — status CLOSED, freeze voucher writes in period
>    - `reopenPeriod(year, month, reason)` — status CLOSED → OPEN, audit log
>    - `getStatus(year, month)` — query helper
>    - Voucher 写操作 必先 check `period.status == OPEN` (else throw `PeriodClosedException` extends BusinessException(400) with message "{year}-{month} 期间已结账, 凭证不可修改. 是否反结账?" + actionHint to reopen URL)
> 3. **Phase C REST + admin UI (~40 min)**:
>    - `AccountingPeriodController` (POST openPeriod / POST requestClose / POST confirmClose / POST reopen / GET status)
>    - `web-admin/src/views/finance/accounting-period/index.vue` (Canvas → 财务 → 期间结账)
>    - 防呆: R2 context "{year}-{month} 期间 — N 笔凭证" / R5 dead-end (CLOSED 期间 试图改 voucher 时 ElMessageBox.confirm "期间已结账, 是否反结账?" + router.push)
> 4. **Phase D scheduled (~15 min)**: `@Scheduled(cron = "0 0 2 1 * ?") + @SchedulerLock` (每月 1 号 02:00) auto request-close 上月 period (status OPEN → PENDING_CLOSE, NOT auto confirm — 需 finance director 手工 confirm)
> 5. **Phase E verification (~15 min)**: unit tests + E2E (5 月 openPeriod → 创建 voucher → 6 月 1 号 auto request-close → confirmClose → 试图改 5 月 voucher → 400 PeriodClosedException)
>
> **依赖**: T1 ship (complex 复式 Voucher entity must exist), PR #54 G RBAC
>
> **DOD**: 5 status transition + voucher 写 gated + admin UI + scheduled job + E2E pass + 反结账 audit log.
>
> **Constraints**: DO NOT use gh CLI (organizer creates PR). Concurrent edit safety rule 5b (`git commit -- <files>`). Backwards compat — existing factories with no AccountingPeriod default treated as OPEN (no period gate enforced until first openPeriod call).
>
> **Flyway version**: V20260710_01 (T1 used V20260701_01/02, T6 used V20260701_03, T7 doc-only no migration — V20260710 safe).

---

## §T3 Track 报表三表 F-3REPORT (P0 8d, T1+T2 之后)

### Brief (paste-ready, dispatch after T1 + T2 ship)

> Sprint 7 wave 2 Track T3 — 报表三表 F-3REPORT (P0 8d, 资产负债 + 利润 + 现金流, 上市公司 / 投资人 / 银行借贷必看).
>
> **⛔ CRITICAL — Worktree isolation** (per HARD rule, see T2 brief).
> Branch: `sprint7/T3-three-financial-reports`.
>
> **Context**: Round 12 §G.6 finding — 上市公司 / 投资人 / 银行 借贷必看. T1 复式 + T2 期间结账 ship 后 数据基础具备.
>
> **Required reading**:
> 1. Spec: `<worktree>/docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md` §T3
> 2. T1 ship `VoucherEntry` + `Account` + `Account.balanceType`: grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/{VoucherEntry,Account}.java`
> 3. T2 ship `AccountingPeriod`: grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/AccountingPeriod.java`
> 4. Existing PDF generation pattern (Sprint 6 W3-C 打印 P1 templates merged): grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/service -name "*PrintService*" -o -name "*PdfGenerator*"`
>
> **Scope (8d → 150 min budget — big track, may need ship MVP + follow-up)**:
> 1. **Phase A 资产负债表 (~40 min)**:
>    - `BalanceSheetService.generate(factoryId, year, month)`:
>      - Query VoucherEntry sum by Account, filter by period <= year/month
>      - Group by Account.balanceType: DEBIT_NORMAL → assets; CREDIT_NORMAL → liabilities + equity
>      - Compute balance check: total assets == total liabilities + equity ✓
>    - DTO `BalanceSheetDTO` (4 sections: 流动资产 / 非流动资产 / 流动负债 / 非流动负债 / 所有者权益, each with line items)
>    - REST `GET /api/mobile/{factoryId}/report/balance-sheet?year=2026&month=5`
>    - Frontend `web-admin/src/views/finance/report/BalanceSheet.vue`
> 2. **Phase B 利润表 (~40 min)**:
>    - `IncomeStatementService.generate(factoryId, startYear, startMonth, endYear, endMonth)`:
>      - Period 收入 (5xxx accounts) - 成本 (6xxx) - 费用 (66xx) = 利润
>      - 4-level: 营业收入 / 营业成本 / 营业利润 / 净利润
>    - DTO + REST + Frontend
> 3. **Phase C 现金流量表 (~50 min, MOST COMPLEX)**:
>    - `CashFlowService.generate(factoryId, startPeriod, endPeriod)`:
>      - 3 类活动: 经营活动现金流 / 投资活动现金流 / 筹资活动现金流
>      - Need trace voucher entries → 现金账户 (1001/1002) changes → categorize by counterpart account
>      - Algorithm complex — recommend借助 existing reference (Java BigDecimal arithmetic, no Python required)
>    - DTO + REST + Frontend
> 4. **Phase D export PDF (~20 min)**:
>    - 3 表统一 export endpoint via Sprint 6 W3-C `PrintService` pattern
>    - 中国 GAAP 标准 PDF template
> 5. **Phase E verification + 防呆 (~30 min)**:
>    - E2E: T1 复式 → T2 close 5 月 → 3 表 generate → vs manual calc 对照
>    - 防呆 R2 context: 报表 header "2026-05 月报"
>    - 防呆 R5 dead-end: T1 不平衡 / T2 期间 OPEN → 报表显 "WARN: 数据未结账, 可能不准" with link to period close UI
>
> **依赖**: T1 ship + T2 ship (强依赖, MUST 串行 after both)
>
> **DOD**: 3 表 generate + UI + PDF export + 1 E2E full cycle pass.
>
> **风险**: 现金流量表 algorithm 复杂. Phase A+B 优先 ship, Phase C 单做单 PR if time runs out.
>
> **Flyway version**: V20260720_01 if any DDL needed (likely report only queries existing entities, NO new tables — skip Flyway).

---

## §T4 Track 商机 8 阶段 CRM funnel (P1 6d, independent)

### Brief (paste-ready, dispatch parallel with T2)

> Sprint 7 wave 2 Track T4 — 商机 8 阶段 CRM funnel (P1 6d, 大客户 sales必上).
>
> **⛔ CRITICAL — Worktree isolation** (per HARD rule, see T2 brief).
> Branch: `sprint7/T4-sales-opportunity-funnel`.
>
> **Context**: Round 13 §15 finding — HJ 商机 8 阶段 (Lead → Qualified → Demo → Proposal → Negotiate → Verbal → Closed-Won / Closed-Lost). Cretas Sprint 5 PR #53 F ship Customer entity, 缺 商机 stage tracking. F006 销售场景 + 大客户 b2b 必上.
>
> **Required reading**:
> 1. Spec: `<worktree>/docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md` §T4
> 2. Customer entity (PR #53 F merged): `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Customer.java`
> 3. W2-C-1 WechatRecord + W2-C-2 CallRecord (Sprint 6 wave 2 merged): mirror pattern for SalesOpportunity entity
> 4. Existing Vue kanban examples: grep `<worktree>/web-admin/src/views -name "*Kanban*"` for drag-and-drop pattern reference
>
> **Scope (6d → 120 min budget)**:
> 1. **Phase A entity + state machine (~30 min)**:
>    - New entity `SalesOpportunity` (id, customerId @ManyToOne Customer, title, stage enum [LEAD, QUALIFIED, DEMO, PROPOSAL, NEGOTIATE, VERBAL, CLOSED_WON, CLOSED_LOST], valueAmount BigDecimal, probability int 0-100, expectedCloseDate, ownerId User, BaseEntity)
>    - `OpportunityStageHistory` audit entity (opportunityId, fromStage, toStage, reason, changedBy, changedAt)
>    - Flyway `V20260710_02__sales_opportunity.sql` (PG, factory_id RLS, indexes on customerId + stage + ownerId)
>    - `SalesOpportunityService.transitionStage(id, newStage, reason)` with validation (LEAD→QUALIFIED OK / CLOSED_WON→LEAD NOT OK / 8x8 transition matrix)
>    - Probability auto-update on stage change (defaults: LEAD 10% / QUALIFIED 30% / DEMO 50% / PROPOSAL 70% / NEGOTIATE 85% / VERBAL 95% / CLOSED_WON 100% / CLOSED_LOST 0%) — manual override allowed
> 2. **Phase B REST + list view (~30 min)**:
>    - REST `SalesOpportunityController` (POST/GET pagedList by stage/PUT transition/DELETE)
>    - `web-admin/src/views/crm/opportunity/SalesOpportunityList.vue` (Canvas → CRM → 商机) — table + filter by stage + "我的商机" toggle
>    - 防呆 R3: stage `<el-select>` dropdown (with 转化率% hint per option)
> 3. **Phase C kanban view (~30 min)**:
>    - `SalesOpportunityKanban.vue` — 8 columns, drag-and-drop to transition stage
>    - On drop, call `transitionStage` REST with reason prompt
>    - 防呆 R5: empty kanban → "去新增商机" button (router.push to create dialog)
> 4. **Phase D funnel reports (~20 min)**:
>    - Pipeline 总值: sum(valueAmount × probability/100) per stage
>    - 漏斗图 chart (Vue echarts existing)
>    - REST `GET /api/mobile/{factoryId}/sales-opportunity/funnel-stats`
> 5. **Phase E verification (~10 min)**:
>    - E2E: 创建商机 LEAD → transit QUALIFIED → DEMO → ... → CLOSED_WON → audit log + funnel stats update
>    - Unit tests on transition matrix (illegal moves throw)
>
> **依赖**: PR #53 F Customer entity (merged ✅), Sprint 6 W2-C-1+W2-C-2 (merged) — opportunity detail可显 微信/通话 history
>
> **DOD**: 8 stage transition state machine + list + kanban + funnel chart + E2E pass.
>
> **Flyway version**: V20260710_02 (parallel with T2 using V20260710_01 — no collision).

---

## §T5 Track 业绩 6 项 sales target + commission + quota (P2 4d, T4 之后)

### Brief (paste-ready, dispatch after T4 ship)

> Sprint 7 wave 2 Track T5 — 业绩 6 项 sales target + commission + quota (P2 4d, F006 + 大客户 sales).
>
> **⛔ CRITICAL — Worktree isolation** (per HARD rule, see T2 brief).
> Branch: `sprint7/T5-sales-target-commission`.
>
> **Context**: Round 13 §15 finding — HJ 业绩 6 项 (月/季/年 target / 实际 vs target gap / 销售排名 / 提成 commission / 团队 quota / 完成率). Cretas 缺. T4 商机 ship 后必上 (业绩 metric 基于 商机 CLOSED_WON valueAmount).
>
> **Required reading**:
> 1. Spec: `<worktree>/docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md` §T5
> 2. T4 ship `SalesOpportunity` entity: grep `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/SalesOpportunity.java`
> 3. W4-B WagePolicy pattern (Sprint 6 wave 2 merged) — similar 月底 @Scheduled trigger
>
> **Scope (4d → 90 min budget)**:
> 1. **Phase A entity (~20 min)**:
>    - New entity `SalesTarget` (id, ownerId User, period enum MONTH/QUARTER/YEAR, year, periodNum 1-12 for month, targetAmount BigDecimal, actualAmount BigDecimal computed @Transient, BaseEntity)
>    - New entity `CommissionRule` (id, salesId User nullable, customerType nullable, percentage BigDecimal, effectiveFrom Date, effectiveTo Date, BaseEntity)
>    - New entity `Commission` (id, salesOpportunityId, percentage, amount, status PENDING/PAID, paidAt, BaseEntity)
>    - Flyway `V20260730_01__sales_target_commission.sql`
> 2. **Phase B service (~25 min)**:
>    - `SalesTargetService.computeActual(ownerId, period, year, periodNum)`: sum SalesOpportunity.valueAmount WHERE stage=CLOSED_WON AND closedAt in [start, end]
>    - `CommissionService.calculate(opportunityId)`: lookup applicable CommissionRule → percentage × valueAmount → save Commission(PENDING)
>    - vflag listener: SalesOpportunity stage CLOSED_WON → auto fire CommissionService.calculate
> 3. **Phase C frontend (~30 min)**:
>    - `MySalesTargetView.vue` (个人 view, default current user) — 月/季/年 target vs actual + 完成率 chart + 排名 (org-wide rank)
>    - `SalesLeaderboard.vue` (org-wide ranking, all sales)
>    - `CommissionList.vue` + admin `CommissionRuleConfig.vue`
>    - 防呆 R1: target amount max ¥10M (display "目标上限 ¥10M, 防误输")
> 4. **Phase D verification (~15 min)**:
>    - E2E: 设 target → 商机 CLOSED_WON → commission auto compute + actualAmount update + leaderboard reflect
>
> **依赖**: T4 ship (strong dep, MUST after T4)
>
> **DOD**: 3 view + state computed + commission auto-trigger + leaderboard ranking + E2E pass.
>
> **Flyway version**: V20260730_01 (after T2 V20260710_01, T4 V20260710_02).

---

## §N 并行 dispatch 模式 wave 2

**第二波 (post Sprint 7 wave 1 ship)**:
- Chat 1: T2 期间结账 (5d, 120 min budget)
- Chat 2: T4 商机 funnel (6d, 120 min budget)
- 同时跑, ~2 min apart dispatch for安全

**第三波 (post T2 + T4 merge)**:
- Chat 3: T3 报表三表 (8d, 150 min budget — biggest)
- Chat 4: T5 业绩 (4d, 90 min budget)

**Wave 2 总 wall time**: ~3-4 工作日 (第二波 1-2d + 第三波 1-2d).

---

## §O 后续 (post Sprint 7 wave 2)

Sprint 8 候选 (~30d):
- 序列号 sub-tracking (per Round 13 §15 finding)
- 设备 lifecycle (设备 → 维护 → 退役)
- 招聘宿舍 (HR depth)
- docs 子域 (auto-gen PRD from code patterns)

Sprint 9+ (~40d, customer-triggered):
- TV 大屏 (warehouse dashboard)
- 微服务化 (split monolith if scaling needed)
- RBAC column-level (per-field permission)
- HJ APK 实测 (Round 9 27-doc verify mobile parity)

**Round 15+ (post Boss 演示)**: 真客户 onboarding 节奏决定. 9 月 sign-off 节点.

---

## §P 工时累计 (post Sprint 7 wave 2)

| 阶段 | 累计 ship | 剩 backlog |
|---|---|---|
| Sprint 5 ship | 70d | — |
| Sprint 6 wave 1 + wave 2 ship | +50d | — |
| Sprint 7 wave 1 ship | +18d | — |
| **Sprint 7 wave 2 ship** | **+23d** | **~70d Sprint 8+ left** |
| **Total ship (5+6+7)** | **~161d** | **~70d (~2 月 buffer)** |

vs 9 月 sign-off → Sprint 7 wave 2 ship 后 **3 月 buffer** (Sprint 8+ optional polish + 客户 onboarding).

---

**Sprint 7 wave 2 detailed track briefs v1.0 完成 (2026-05-20, organizer post Sprint 7 wave 1 dispatch)**.

Sprint 7 wave 1 PRs merge → fire T2 + T4 (2 chats parallel) → after merge fire T3 + T5 (2 chats parallel).
