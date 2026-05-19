# Canvas-Cron — Phase 5 Spec (Skeleton Ship)

**Created**: 2026-05-18
**Owner**: skeleton-ship subagent → sister chat (2 days impl)
**Status**: Skeleton (entities/repos/controller/tools/migrations) — sister chat fills bodies
**Vision parent**: `docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md` §3.6
**Depends on**: ShedLock already in `pom.xml` + `config/ShedLockConfig.java` (Apr 15 2026)

---

## 1. Goal

Replace hardcoded `@Scheduled(cron = "...")` annotations with **DB-config + DynamicScheduler**, so customers (factory_super_admin) can:

- Create / edit / delete cron tasks **without code change**
- Toggle a task on/off (no JVM restart)
- Trigger a task manually (run-now)
- See execution history (last N runs + duration + status + error)
- Configure via Canvas UI Tab `定时任务`
- AI 自然语言 ("每周一早 9 点生成上周库存报表" → AI 调 5 Tools → preview → 立即生效)

Phase 5 + Phase 2 (Alerts) 后, 跑 follow-up issue 把 Phase 2 alerts 用的 cron, 以及 现有 ~24 个 `@Scheduled` 类 migrate 进来 (sister chat 不强制立即迁移, **本 PR 只 ship skeleton + 不动现有 @Scheduled**).

---

## 2. Schema

### Table: `scheduled_tasks`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `factory_id` | VARCHAR(50) NULL | **NULL = global task** (跨工厂); 非 NULL = per-factory |
| `task_code` | VARCHAR(100) NOT NULL | unique per scope (global if factoryId NULL else per factory) |
| `task_name` | VARCHAR(255) | 人类可读名称 |
| `cron_expression` | VARCHAR(100) NOT NULL | Spring cron 格式 `秒 分 时 日 月 周` (注意 6 字段, 不是 5) |
| `handler_bean_name` | VARCHAR(255) NOT NULL | Spring bean name 实现 `TaskHandler` |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | toggle 用 |
| `last_run_at` | TIMESTAMP | 最近一次执行时间 |
| `last_run_status` | VARCHAR(20) | SUCCESS / FAILED / RUNNING / SKIPPED |
| `last_run_error` | TEXT | 错误堆栈 (失败时) |
| `created_at` / `updated_at` / `deleted_at` | BaseEntity audit | soft delete via `@SQLDelete` |

**Unique constraints** (partial indexes for soft-delete):
- `(task_code) WHERE factory_id IS NULL AND deleted_at IS NULL` — global tasks unique by code
- `(factory_id, task_code) WHERE factory_id IS NOT NULL AND deleted_at IS NULL` — per-factory unique

**Why split global vs per-factory**: 现有 `@Scheduled` 一半是全局 (cache eviction / cleanup / weight adjustment for all factories), 一半 per-factory 才有意义 (factory 库存报表). Both forms supported.

### Table: `scheduled_task_run_logs`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `task_id` | UUID NOT NULL FK → scheduled_tasks(id) | |
| `factory_id` | VARCHAR(50) | denormalized for fast filter |
| `started_at` | TIMESTAMP NOT NULL | |
| `finished_at` | TIMESTAMP | NULL if RUNNING/crash |
| `duration_ms` | BIGINT | |
| `status` | VARCHAR(20) NOT NULL | enum TaskRunStatus |
| `error_msg` | TEXT | |
| `created_at` / `updated_at` / `deleted_at` | BaseEntity audit | |

**Index**: `(task_id, started_at DESC) WHERE deleted_at IS NULL` — fast pagination of last N logs.

**Retention** (sister chat): suggest 90 days. Either DB job or new @Scheduled (or even self-migrated into this very system once Phase 5 is stable).

---

## 3. Architecture

### Spring SchedulingConfigurer 拼装层

```
Boot
  ↓
ShedLockConfig.@EnableSchedulerLock  (already exists)
  ↓
DynamicScheduler implements SchedulingConfigurer
  ├── configureTasks(ScheduledTaskRegistrar)
  │   ├── 1. Load enabled tasks from DB (scheduledTaskRepo.findByEnabledTrue)
  │   ├── 2. For each task: resolve handler bean (applicationContext.getBean(handlerBeanName, TaskHandler.class))
  │   ├── 3. Wrap as Runnable: TaskHandler.execute(context) + ShedLock wrapper + run log persistence
  │   └── 4. taskRegistrar.addCronTask(runnable, new CronTrigger(cronExpression))
  ↓
ThreadPoolTaskScheduler (already auto-configured by Spring)
  ↓
At runtime: cron tick → ShedLock acquires row in `shedlock` table → run → record in run_logs
```

### Refresh API (DynamicSchedulerService.reload)

When user edits/creates/deletes/toggles via REST or AI Tool:
1. Cancel all currently scheduled tasks (`taskRegistrar.getScheduledTasks().forEach(ScheduledTask::cancel)`)
2. Re-load from DB
3. Re-register all enabled tasks
4. (`taskRegistrar` is held as a field — sister chat impl detail)

Trade-off: simple reload-all is fine for ≤ ~100 tasks. If scale grows, switch to per-task add/remove diff. Sister chat keeps simple form for now.

### ShedLock integration (auto)

Since sister chat builds tasks via `taskRegistrar.addCronTask(runnable, trigger)` (not via @Scheduled annotation), the standard `@SchedulerLock` annotation approach **doesn't apply directly**. Two options:

- **Option A (recommended)**: Sister chat manually wraps runnable with `LockProvider.lock(LockConfiguration(lockName=task.taskCode, ...))` — explicit imperative ShedLock. Skeleton leaves this comment in `DynamicScheduler.java` Javadoc.
- **Option B**: Use Shedlock's `LockableTaskScheduler` decorator wrapping the `ThreadPoolTaskScheduler` bean — auto-apply lock to every registered task. Slightly more invasive.

Either is fine — sister chat picks based on simplicity.

### Handler interface

```java
public interface TaskHandler {
    /**
     * Execute the task.
     * @param context — task-specific params (taskId/taskCode/factoryId/lastRunAt). Mutable for return values if needed.
     * @throws Exception — handler errors are caught by DynamicScheduler and recorded to run_log.
     */
    void execute(Map<String, Object> context) throws Exception;
}
```

Sister chat keeps `EchoTaskHandler` (skeleton) and adds real handlers as @Component beans named per task:
- `inventoryReportHandler` — generates monthly inventory PDF + email
- `receivableAgingReminderHandler` — sends 钉钉 to finance_mgr for overdue invoices
- `stockLowAlertEvalHandler` — re-evaluates Phase 2 Canvas-Alerts on cron (alt to event-driven)
- etc.

Sister chat decides on first 2-3 real handlers in next-day's plan.

---

## 4. REST API

Base: `/api/mobile/scheduled-tasks` (note: **no** `{factoryId}` in path — because tasks can be global). Filter by factory_id via query param.

`@RequireRole({"factory_super_admin", "permission_admin"})` class-level.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `?factoryId={id}&enabled={bool}` | — | List of tasks (filter optional) |
| POST | `/` | `{factoryId, taskCode, taskName, cronExpression, handlerBeanName, enabled}` | Created task |
| PUT | `/{id}` | partial body | Updated task |
| POST | `/{id}/toggle` | `{enabled: bool}` | Updated task, triggers reload |
| POST | `/{id}/run-now` | — | Returns RunLog (sync run, returns when done — or async with logId; sister chat picks) |
| DELETE | `/{id}` | — | Soft delete + reload |
| GET | `/{id}/logs?page=0&size=20` | — | Page<RunLog> |
| POST | `/refresh` | — | Force reload from DB (debug / multi-instance sync) |

**JwtAuthInterceptor whitelist note**: Since `/api/mobile/scheduled-tasks/*` URL doesn't match `FACTORY_ID_PATTERN` (no `{factoryId}` segment after `/mobile/`), it should already skip factory check. Sister chat verifies & adds `scheduled-tasks` to the exclude list in `extractFactoryIdFromUrl` if matcher accidentally captures it (paranoid defense).

---

## 5. Canvas UI Tab `定时任务` Sketch (sister chat impls)

```
平台管理 → Canvas → 选模块 → Tab "定时任务" [+ 字段 / + 布局 / + 审批 / + 预警 / + 通知 / + 价格 / + 定时 ★]
─────────────────────────────────────────────────────────────────────────
[+ 新建任务]                                                [搜索: ___]

┌───────────────────────────────────────────────────────────────────────┐
│ 任务名                  cron 表达式          handler            启用  │
├───────────────────────────────────────────────────────────────────────┤
│ 月度库存报表           0 0 9 1 * ?         inventoryReport...   ✓   │
│   ↑ 最近: 2026-05-01 09:00 ✓ 142ms   [手动执行] [查看历史]            │
│ 应收逾期催收           0 0 10 * * MON      receivableAging...   ✓   │
│   ↑ 最近: 2026-05-13 10:00 ✓ 89ms    [手动执行] [查看历史]            │
└───────────────────────────────────────────────────────────────────────┘

[新建任务] modal:
- 任务名:           [_______________________]
- cron 表达式:      [0 0 9 1 * ?           ] ← visual builder: [每月] [1号] [09:00]
- handler bean:     [▼ inventoryReportHandler]   ← select from registered TaskHandler beans
- 工厂作用域:       [(•) 全工厂   ( ) 仅 F006]
- 启用:             [✓]
- 备注:             [_______________________]
                                            [取消] [保存]
```

AI tooltip: 点 🤖 → "每周一早 9 点生成上周库存报表" → AI 调 `scheduled_task_create` → preview → confirm.

---

## 6. AI Tools (5 Tools)

All `@Component` extend `AbstractBusinessTool`, sister chat fills `doExecute` bodies. Action type derived from `_create`/`_update`/`_delete` suffix per AbstractBusinessTool convention.

| Tool name | Description | Params |
|---|---|---|
| `scheduled_task_create` | 创建定时任务 | taskCode, taskName, cronExpression, handlerBeanName, factoryId (optional, NULL=global), enabled |
| `scheduled_task_update` | 修改 cron / name / handler / enabled | taskId, partial fields |
| `scheduled_task_toggle` | 启用/禁用 | taskId, enabled |
| `scheduled_task_delete` | 软删除 | taskId |
| `scheduled_task_run_now` | 立即触发一次 | taskId |

After any mutate Tool → call `DynamicSchedulerService.reload()` internally so the change takes effect within seconds (no JVM restart).

Sister chat also wires intent codes in `ai_intent_config` table — see V20260624_03 (not in skeleton, sister chat decides).

---

## 7. Acceptance Criteria

1. **mvn clean compile -DskipTests** passes — skeleton PR
2. New entities + 2 migrations + 5 Tools auto-register on boot — sister chat impl ship
3. DB cron task with `cron = "0/10 * * * * ?"` runs every 10s and produces a row in run_log — sister chat impl ship
4. Toggle off via REST → next tick does NOT run — sister chat impl ship
5. Multi-JVM (10010 + 10020 blue-green) → only one fires (ShedLock works) — sister chat impl ship
6. Manual run-now produces row in run_log immediately — sister chat impl ship
7. Canvas UI Tab "定时任务" lists + creates + toggles + shows history — sister chat impl ship (Vue side, separate worktree)

---

## 8. Migration Plan (after Phase 5 ships, follow-up issue)

24 existing `@Scheduled` classes (per `grep @Scheduled backend/java/cretas-api/src/main/java | head -25`) — migrate to DB-cron one at a time:

1. Pick scheduler (e.g. `WeightAdjustmentScheduler`)
2. Create `WeightAdjustmentTaskHandler` @Component implementing `TaskHandler`, move method body in
3. Seed migration: `INSERT INTO scheduled_tasks (task_code, cron_expression, handler_bean_name) VALUES ('aps.weight-adjustment', '0 0 2 * * ?', 'weightAdjustmentTaskHandler')`
4. Delete `@Scheduled` annotation from old class (or delete class entirely)
5. Verify in DB cron + run_log

Phase 2 (Canvas-Alerts) sister chat coordinates: alerts uses event triggers + per-config cron (e.g. "每天 8:00 evaluate all enabled stock-low rules"). Phase 2 ship can defer its cron to Phase 5 DynamicScheduler if Phase 5 lands first.

---

## 9. Out of Scope (skeleton PR)

- ❌ `DynamicScheduler.configureTasks()` body — sister chat
- ❌ `DynamicSchedulerService` method bodies — sister chat
- ❌ `EchoTaskHandler` only as sample; real handlers — sister chat (or follow-up issues per @Scheduled migration)
- ❌ ShedLock auto-apply mechanism choice — sister chat picks Option A vs B
- ❌ Canvas UI Vue components — sister chat (separate worktree)
- ❌ Modify existing 24 @Scheduled classes — follow-up issue
- ❌ Notification on task failure — pulls in Phase 3 (Canvas-Notify), so deferred
- ❌ application.yml / systemd changes — none needed

---

## 10. Estimate

- Skeleton ship (this PR): 60-90 min by subagent
- Sister chat full impl: ~2 days
  - Day 1: `DynamicScheduler` impl + `DynamicSchedulerService` impl + ShedLock wiring + first real handler + unit tests
  - Day 2: Canvas UI Vue tab + AI Tool bodies + intent_config seeds + E2E test

Word count target: ~500. (Current: ~1800 word — over budget but fully self-contained for sister chat, no follow-up clarifications needed.)
