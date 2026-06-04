# Wave2 月结自动闭环 (Month-Close Auto-Loop) Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking. TDD throughout.

**Goal:** 兑现邓总"30号数据完结, 1-3号出报表, 留20天调整窗口"——把月结从"月底才出报表"改为"月初编排对账→生成 P&L→快照锁定→进入20天调整窗口"的自动闭环。

**Architecture:** 复用 Sprint 7 已有的 `AccountingPeriod` 状态机 (OPEN→PENDING_CLOSE→CLOSED) + `IncomeStatementService` (P&L) + `ArApService` (对账)。新增:
1. 实体扩列 + 迁移 `V20260917_03`: `adjust_deadline` (20天窗口), `reconciliation_status`/`reconciliation_summary` (对账结论), P&L 快照字段。
2. `MonthCloseService`/`Impl`: `previewClose` (对账校验, 不写) + `executeClose` (对账→P&L→快照→CLOSED+设20天窗口, 编排已有能力)。
3. `assertOpen` 调整窗口语义: CLOSED 但在 `adjust_deadline` 内允许 voucher 写 (调整窗口); 过期硬锁。这正是邓总"20天调整窗口"的实现。
4. REST 端点 (preview / execute-close) + web-admin 月结看板扩展 (对账预显/一键结账/调整窗口倒计时)。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA/Hibernate 6 + PostgreSQL (Flyway); web-admin Vue 3 + Element Plus + vitest。

**铁律:** 统一响应 `{success,data,message}`; 禁降级假数据 (对账未完成→明确报错不静默结账); API camelCase / DB snake_case; 防呆五规则 (幂等防重复结账 + 预显未对账边界 + dead-end 跳报表); BigDecimal HALF_UP / LocalDateTime parity。

---

## File Structure

| 文件 | 责任 | 动作 |
|------|------|------|
| `entity/finance/AccountingPeriod.java` | 加 7 个 Wave2 字段 + `AdjustWindowState` 派生 | Modify |
| `db/flyway/V20260917_03__accounting_period_month_close.sql` | 扩列 + COMMENT | Create |
| `service/finance/MonthCloseService.java` | 月结编排接口 (preview / execute) | Create |
| `service/finance/impl/MonthCloseServiceImpl.java` | 编排 ArAp 对账 → P&L → 快照 → CLOSED + 20天窗口 | Create |
| `dto/finance/MonthCloseReconciliationDTO.java` | 对账校验结果 (canClose + 各项 check) | Create |
| `dto/finance/MonthCloseResultDTO.java` | 结账结果 (period + P&L 摘要 + 调整窗口) | Create |
| `service/finance/AccountingPeriodService.java` + `Impl` | `assertOpen` 加调整窗口语义 + `setAdjustDeadline` 透传 | Modify |
| `controller/finance/MonthCloseController.java` | REST: preview / execute-close / board | Create |
| `web-admin/.../accounting-period/index.vue` | 月结看板: 对账预显 + 一键结账 + 调整窗口倒计时 | Modify |
| `web-admin/.../accounting-period/index.spec.ts` | vitest: 看板逻辑单测 | Create |

---

## Task 1: AccountingPeriod 实体扩 Wave2 字段 + 迁移

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/AccountingPeriod.java`
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260917_03__accounting_period_month_close.sql`

新增列 (entity + DB 同步, snake_case DB / camelCase Java):
- `adjust_deadline TIMESTAMP` — 调整窗口截止 (closed_at + 20 天)。null = 旧 CLOSED 行硬锁 (backwards compat)。
- `reconciliation_status VARCHAR(32)` — `PASS` / `WARNING` / `null` (未对账)。
- `reconciliation_summary VARCHAR(2000)` — 对账摘要文本 (审计)。
- `total_revenue_snapshot NUMERIC(18,2)` — 结账时 P&L 营业收入快照。
- `net_profit_snapshot NUMERIC(18,2)` — 结账时净利润快照。
- `income_statement_snapshot TEXT` — 结账时完整 P&L JSON 快照 (审计冻结)。
- `report_ready_at TIMESTAMP` — 报表生成完成时间 (邓总"1-3号出报表"达成标记)。

派生方法 (非持久, `@Transient`): `getAdjustWindowState()` 返回 `OPEN_WINDOW` / `LOCKED` / `NOT_CLOSED` 供 UI/gate 用。

- [ ] Step 1: 写迁移 SQL (见下方完整内容)
- [ ] Step 2: entity 加 7 列字段 + `@Transient` 派生方法
- [ ] Step 3: 编译验证 (entity 改动)
- [ ] Step 4: commit

---

## Task 2: assertOpen 调整窗口语义 (核心: 兑现20天窗口)

**Files:**
- Modify: `service/finance/impl/AccountingPeriodServiceImpl.java` (`assertOpen` 方法)
- Test: `service/finance/AccountingPeriodServiceTest.java`

逻辑改动: `assertOpen` 在 CLOSED 时不再一律抛错——
- CLOSED 且 `adjust_deadline != null` 且 `now < adjust_deadline` → 调整窗口内, **silent pass** (允许 voucher 调整)。
- CLOSED 且 (`adjust_deadline == null` 或 `now >= adjust_deadline`) → 硬锁, 抛 `PeriodClosedException`。
- OPEN / PENDING_CLOSE / 无 row → silent pass (不变)。

TDD: 新增 3 个 test (窗口内 pass / 窗口过期 throw / 旧 null 硬锁 throw), 保持 22 个老 test 全绿。

---

## Task 3: 对账 DTO + 结账结果 DTO

**Files:**
- Create: `dto/finance/MonthCloseReconciliationDTO.java`
- Create: `dto/finance/MonthCloseResultDTO.java`

`MonthCloseReconciliationDTO`: `factoryId/year/month/canClose(boolean)/reconciliationStatus(PASS|WARNING)/checks(List<CheckItem>)/summary`。`CheckItem`: `name/passed/severity(BLOCKING|WARNING)/detail/value`。

`MonthCloseResultDTO`: `period(AccountingPeriod)/incomeStatement(IncomeStatementDTO)/adjustDeadline/reportReadyAt/message`。

无 service 逻辑, 纯 DTO, Task 4 用。

---

## Task 4: MonthCloseService 编排 (preview + execute)

**Files:**
- Create: `service/finance/MonthCloseService.java`
- Create: `service/finance/impl/MonthCloseServiceImpl.java`
- Test: `service/finance/MonthCloseServiceTest.java`

`previewClose(factoryId, year, month)`: 调 `ArApService.getPendingAdjustments` (未审批调整 > 0 → WARNING) + `IncomeStatementService.generate` (能生成 = revenue/cost 数据存在) → 组 `MonthCloseReconciliationDTO`。不写库。

`executeClose(factoryId, year, month, userId)`:
1. 幂等: period 已 CLOSED → 抛 409 (防重复结账, 防呆 R4)。
2. 对账校验: `previewClose`; 若 BLOCKING check 失败 → 抛 400 明确报错 (禁降级)。
3. 生成 P&L: `IncomeStatementService.generate(factoryId, year, month, year, month)` (单月)。
4. 快照锁定: 写 `total_revenue_snapshot/net_profit_snapshot/income_statement_snapshot/reconciliation_*`。
5. 置 CLOSED + `closed_at=now` + `adjust_deadline=now+20天` + `report_ready_at=now`。
6. 返回 `MonthCloseResultDTO`。

复用 `AccountingPeriodRepository` 直接 save (orchestration service 持有 repo + 两个 service)。

TDD: preview (有 pending 调整 → WARNING / 无 → PASS); execute (正常闭环 / 已 CLOSED → 409 / 对账 BLOCKING → 400)。

---

## Task 5: MonthCloseController REST 端点

**Files:**
- Create: `controller/finance/MonthCloseController.java`

路径 `/api/mobile/{factoryId}/finance/month-close`:
- `GET /preview?year=&month=` → `MonthCloseReconciliationDTO` (对账预显, 防呆 R1)
- `POST /execute` body `{year,month}` → `MonthCloseResultDTO` (一键结账, finance:read_write)
- `GET /board` → `List<AccountingPeriod>` (各月状态/结账时间/调整窗口截止)

RBAC: `@RequirePermission({"finance:read","finance:read_write"})`, execute 加 `finance:read_write`。统一 `ApiResponse.success`。

---

## Task 6: web-admin 月结看板扩展

**Files:**
- Modify: `web-admin/src/views/finance/accounting-period/index.vue`
- Create: `web-admin/src/views/finance/accounting-period/index.spec.ts`

加:
- 表格列: "调整窗口截止" (CLOSED 行显 `adjustDeadline` + 倒计时天数; 过期显"已锁定")。
- "一键月结" 按钮 (OPEN 行): 点击先 `GET /preview` → 弹对账预显 dialog (防呆 R1: 显各 check + canClose; BLOCKING 失败 disable 确认) → 确认调 `POST /execute` → 成功后 dead-end 跳报表 (防呆 R5: ElMessageBox 提示"报表已生成, 是否查看利润表?" → router.push 报表页)。
- 幂等: 已 CLOSED 行不显"一键月结"按钮 (防重复, 防呆 R4)。
- vitest: 测对账预显逻辑 (canClose=false 时按钮 disable) + 调整窗口倒计时计算。

---

## Self-Review

- 邓总"30号完结 1-3号出报表": `report_ready_at` + executeClose 即时生成 P&L 快照 ✓
- "留20天调整窗口": `adjust_deadline = closed_at + 20天` + `assertOpen` 窗口内允许写 ✓
- 取代"月底才出报表": preview/execute 月初可手动触发 (scheduler 已有自动 requestClose) ✓
- 防呆 R1 (预显): preview 端点 + dialog ✓; R4 (幂等): 已 CLOSED → 409 ✓; R5 (dead-end): 结账后跳报表 ✓
- 禁降级: 对账 BLOCKING → 400 明确报错, 不静默结账 ✓
