# Canvas-Alerts Phase 2 — Implementation Spec

**Created**: 2026-05-18
**Status**: Skeleton shipped (entities + AI Tools), impl pending sister chat
**Vision**: [docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md §3.2]
**Phase 1 Reference**: PR #862 (Canvas-Workflow)
**Estimated**: 3-4 days (sister chat full impl)

---

## 1. Scope

8 alert types covering all factory operational risk surfaces:

| Code | Type | Trigger | Typical context |
|---|---|---|---|
| `INVENTORY_LOW` | 低库存 | scheduled scan | material/finished_good `< minStockLevel` |
| `INVENTORY_EXPIRING` | 临期 | scheduled scan | `expiryDate <= today + warningDays` |
| `QUALITY_ANOMALY` | 质量异常 | event-triggered | quality inspection FAIL / pass rate < threshold |
| `PO_AMOUNT_THRESHOLD` | 采购金额超限 | event-triggered | PurchaseOrder amount >= threshold |
| `SO_AMOUNT_THRESHOLD` | 销售金额异常 | event-triggered | SalesOrder amount >= threshold |
| `SALES_DECLINE` | 销售下滑 | scheduled scan | period-over-period sales < threshold% |
| `CUSTOMER_PAYMENT_OVERDUE` | 客户应收逾期 | scheduled scan | receivable_aging_days > N |
| `SUPPLIER_PAYABLE_DUE` | 供应商应付到期 | scheduled scan | payable_due_date <= today + N |

---

## 2. Trigger mechanism

Two complementary pathways:

### 2.1 Event-driven (immediate)

Business services publish `BusinessEvent` via Spring `ApplicationEventPublisher`. `AlertEventListener` filters by `(factoryId, alertType)`, evaluates SpEL `triggerConditionSpel` against business context, creates `AlertEvent` if rule matches.

- `INVENTORY_LOW` may also fire here on every WMS write (in-warehouse / out-warehouse / adjust)
- `QUALITY_ANOMALY`, `PO_AMOUNT_THRESHOLD`, `SO_AMOUNT_THRESHOLD` fire on insert / status transition

### 2.2 Scheduled (sweep)

`@Scheduled` `evaluateScheduled(factoryId)` runs every 15 minutes (configurable per factory), iterates enabled rules for batch scans:
- expiry / aging / receivable / payable / sales-decline (period rollup)
- inventory_low fallback for missed events
- Uses `@SchedulerLock` (ShedLock) for clustered safety

---

## 3. Schema

### `alert_rules` (V20260620_01)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PRIMARY KEY | `gen_random_uuid()` |
| `factory_id` | VARCHAR(50) NOT NULL | tenant key |
| `alert_type` | VARCHAR(50) NOT NULL | enum mirror |
| `rule_name` | VARCHAR(255) NOT NULL | display name |
| `trigger_condition_spel` | TEXT | SpEL expression on `#context.*` |
| `severity` | VARCHAR(10) NOT NULL DEFAULT 'MID' | LOW/MID/HIGH |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | toggleable |
| `notify_channels` | JSONB DEFAULT `[]` | list<String>: WECHAT/DINGTALK/EMAIL/IN_APP |
| `notify_roles` | JSONB DEFAULT `[]` | list<String>: role codes |
| audit cols | TIMESTAMP × 3 | created_at / updated_at / deleted_at |

UNIQUE(factory_id, rule_name). Partial index on `(factory_id, enabled) WHERE deleted_at IS NULL`.

### `alert_events` (V20260620_02)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PRIMARY KEY | |
| `rule_id` | UUID REFERENCES alert_rules(id) | FK |
| `factory_id` | VARCHAR(50) NOT NULL | tenant key |
| `business_entity_type` | VARCHAR(50) | e.g. MATERIAL_BATCH, PURCHASE_ORDER |
| `business_entity_id` | VARCHAR(191) | id of the entity that tripped |
| `severity` | VARCHAR(10) NOT NULL | snapshot from rule (rule may change) |
| `message` | TEXT NOT NULL | "冻猪蹄库存 25kg 已低于阈值 30kg" |
| `status` | VARCHAR(20) NOT NULL DEFAULT 'OPEN' | OPEN/ACKNOWLEDGED/RESOLVED |
| `acked_by_user_id` | BIGINT | who clicked acknowledge |
| `acked_at` | TIMESTAMP | |
| `resolved_by_user_id` | BIGINT | who marked resolved |
| `resolved_at` | TIMESTAMP | |
| audit cols | TIMESTAMP × 3 | |

Index on `(factory_id, status, created_at DESC)` for OPEN-events dashboard.

---

## 4. Canvas Tab "预警规则" (UI sketch — sister chat impl)

Mounted inside Canvas Editor as 3rd tab beside "字段配置" / "审批流程":

```
┌─ 预警规则 ────────────────────────────────────────────┐
│ [+ 新建规则]                          搜索: [_____]   │
│                                                       │
│ ┌─ INVENTORY_LOW · 冻猪蹄低库存预警 · HIGH ──────────┐│
│ │ 触发: 库存 < 30kg                                  ││
│ │ 通知: 采购员 (微信+钉钉)                            ││
│ │ [启用 ✓] [编辑] [删除]                             ││
│ └────────────────────────────────────────────────────┘│
│ ┌─ PO_AMOUNT_THRESHOLD · 大额采购预警 · MID ─────────┐│
│ │ 触发: amount >= 50000                               ││
│ │ 通知: factory_super_admin (微信)                    ││
│ │ [启用 ✓] [编辑] [删除]                             ││
│ └────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────┘
```

Top tabs: `规则列表` / `事件历史` (paginated AlertEvent list with ack/resolve actions).

---

## 5. AI Tools (6, all `@Component` extending `AbstractBusinessTool`)

| Tool | Description | Required params |
|---|---|---|
| `alert_rule_create` | 创建告警规则 (8 类型) | `alertType, ruleName` |
| `alert_rule_update` | 更新告警规则 | `ruleId` |
| `alert_rule_toggle` | 启用/禁用规则 | `ruleId` |
| `alert_rule_delete` | 删除告警规则 (soft) | `ruleId` |
| `alert_event_query` | 查询告警事件 | (none — defaults to OPEN, page=0, size=20) |
| `alert_event_acknowledge` | 确认告警事件 | `eventId` |

All 6 register via Spring `@Component` → `ToolRegistry` auto-collect.

---

## 6. Endpoints (controller skeleton, all 501)

Base path: `/api/mobile/{factoryId}/alerts`. Class-level `@RequireRole({"factory_super_admin", "permission_admin"})`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/rules` | list factory's rules |
| POST | `/rules` | create rule |
| PUT | `/rules/{id}` | update rule |
| POST | `/rules/{id}/toggle` | toggle enabled |
| DELETE | `/rules/{id}` | soft delete |
| GET | `/events` | paginated event history (status filter) |

Skeleton returns `ApiResponse.error("Phase 2 sister chat impl pending", "NOT_IMPLEMENTED")` for all.

---

## 7. Acceptance criteria (sister chat exit)

1. **All 6 endpoints functional**: CRUD on alert_rules, paginated events.
2. **Event-driven trigger working**: at least 1 alert type fires via `BusinessEvent` publish (recommend `PO_AMOUNT_THRESHOLD` smoke).
3. **Scheduled trigger working**: `@Scheduled` evaluator runs every 15min with ShedLock, at least 1 alert type covered (recommend `INVENTORY_LOW`).
4. **SpEL evaluation**: `triggerConditionSpel = "#context.amount >= 50000"` correctly evaluates against business context.
5. **Notification dispatch**: `notify_channels=[WECHAT]` + `notify_roles=[procurement_manager]` correctly resolves recipient users and dispatches via existing `NotificationService`.
6. **AI Tools functional**: All 6 tools execute end-to-end (LLM → tool_call → preview → execute → DB).
7. **Canvas UI tab live**: "预警规则" tab in `canvas-editor` shows list + create dialog + event history.

---

## 8. Phase 2 follow-ups (NOT in skeleton, NOT in sister chat unless time permits)

- **Migration**: harvest existing hardcoded alert callsites into rules (e.g. `MaterialBatchService.checkExpiry`).
- **Multi-channel notification fan-out**: today only IN_APP works reliably; WECHAT/DINGTALK/EMAIL require existing adapter.
- **AI subscribe**: "冻猪蹄库存提醒我" → AI 调 alert_rule_create.
- **Alert grouping / dedup**: 1 hour window dedup same (rule_id, business_entity_id).
- **SpEL sandbox hardening**: restrict expression to known context vars only.

---

## 9. Sister chat dispatch summary

- **Skeleton scope (this PR)**: entities + repos + service interface + skeleton impl + controller (all 501) + 6 AI Tools (all UnsupportedOp) + 2 Flyway migrations + spec doc.
- **Sister chat scope**: service impl (event listener + scheduler + SpEL evaluator + notification dispatch) + AI Tool doExecute() bodies + Canvas UI tab (Vue) + E2E smoke for at least 2 alert types.
- **Estimated**: 3-4 days (B-1: service core 1.5d / B-2: AI tools impl + UI tab 1.5d / B-3: E2E + polish 0.5-1d).

---

## Appendix — Phase 1 reference

`approval_workflow_instances` (V20260607_02) shows the audit-field convention, JSONB usage, optimistic locking via `@Version`. Phase 2 reuses identical conventions for `alert_rules` + `alert_events`. The `notify_channels/notify_roles` JSONB pattern mirrors workflow `current_node_ids` JSONB pattern.

Phase 1 also established that `@MappedSuperclass` `@Where` is silently ignored — Phase 2 adds per-entity `@Where(clause = "deleted_at IS NULL")` on AlertRule and AlertEvent.
