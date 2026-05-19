# Canvas-Rules Phase 4a — Implementation Spec

**Created**: 2026-05-18
**Phase**: 4a — Canvas-Rules (业务规则引擎, pure auto rules, no human, no approval)
**Vision parent**: `docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md` §3.5
**Status**: Skeleton PR shipped (this PR). Impl owed to sister chat. 2-3 days estimate.
**Phase 1 dependency**: WorkflowEngineService (Canvas-Workflow) not yet merged. This skeleton stubs `WorkflowEngineFacade` so Phase 4a can ship & compile in parallel; wire after Phase 1 merge.

---

## 1. Scope

Pure-auto business rules engine for cross-entity validation, mutation, rejection, or downstream workflow triggering. **No human-in-the-loop** (审批 / 通知 / 价格 handled by Phase 1 / 3 / 5 respectively). Examples:

| 业务 | 触发 | Condition (SpEL) | Action |
|---|---|---|---|
| PO 黑名单供应商 | submitOrder | `supplier.blacklisted == true` | `REJECT` reason="供应商在黑名单" |
| VIP 自动折扣 | createOrder | `customer.tier == 'VIP' and totalAmount > 50000` | `MODIFY` field=discount value=0.05 |
| 库存不足触发调拨 | updateStock | `currentStock < safetyStock` | `TRIGGER_WORKFLOW` workflowCode=TRANSFER_REQUEST |
| 客户年累计升级 | order.complete | `customer.ytdAmount > 1000000 and customer.tier != 'PLATINUM'` | `MODIFY` field=tier value='PLATINUM' |
| 高额订单日志 | createOrder | `totalAmount > 100000` | `LOG` level=INFO |

---

## 2. Architecture

```
@RuleEvaluate(scope="ORDER")           ← annotation on Service method
  └ PurchaseServiceImpl.submitOrder()
       ↓ (intercepted)
  RuleEvaluateAspect (@Around)
       ↓
  RuleEngine.evaluate(factoryId, ORDER, inputObject)
       ↓
  ┌──────────────────────────────────────┐
  │ load active rules by scope+factory   │ ORDER BY priority ASC
  │ for each rule:                        │
  │   SpelExpressionParser.parse(cond)    │
  │   if matches → apply action           │
  │     ├ LOG → log + RuleExecutionLog    │
  │     ├ REJECT → throw RuleViolation,   │
  │     │           short-circuit         │
  │     ├ MODIFY → record modification,   │
  │     │           apply via reflection  │
  │     └ TRIGGER_WORKFLOW →              │
  │           WorkflowEngineFacade.start()│
  │ aggregate → RuleEvaluationResult     │
  └──────────────────────────────────────┘
       ↓
  (caller sees mutated inputObject OR RuleViolationException 400)
```

### 2.1 Scope enum

| Scope | Hooked into | Sister chat attaches `@RuleEvaluate("ORDER")` to |
|---|---|---|
| `ORDER` | PurchaseServiceImpl.submitOrder, SalesServiceImpl.createOrder | both |
| `INVENTORY` | InventoryServiceImpl.updateStock, MaterialBatchServiceImpl.consume | both |
| `CUSTOMER` | CustomerServiceImpl.update, SalesOrderServiceImpl.confirmComplete (ytd recalc) | both |
| `CUSTOM` | any Service method opting in | per-rule |

### 2.2 Action types

| Action | Semantics | Action config JSON |
|---|---|---|
| `LOG` | Append to `rule_execution_logs`, no side effect | `{"level":"INFO","message":"高额单据 {orderNumber}"}` |
| `REJECT` | Throw `RuleViolationException` → 400 with reason, **short-circuit downstream rules**. Per fool-proof Rule 5: response includes `actionHint` (跳哪个页面解决) | `{"reason":"供应商在黑名单","actionHint":"/system/suppliers/{supplierId}"}` |
| `MODIFY` | Reflectively set field on inputObject. Caller observes mutated arg after aspect | `{"field":"discount","value":0.05}` or `{"field":"discount","valueSpel":"totalAmount * 0.05"}` |
| `TRIGGER_WORKFLOW` | Call `WorkflowEngineFacade.startWorkflow(workflowCode, ctx)` (stubbed in this PR, sister chat wires post Phase 1 merge) | `{"workflowCode":"TRANSFER_REQUEST","ctx":{"materialId":"#materialId"}}` |

### 2.3 Priority + short-circuit

- Rules of the same scope sorted `priority ASC` (smaller = earlier).
- Default priority = 100. Custom rules can use 1-99 to force ordering.
- **REJECT short-circuits**: first REJECT aborts evaluation, returns to caller. (LOG / MODIFY / TRIGGER_WORKFLOW are cumulative.)

---

## 3. Database schema

See migrations `backend/java/cretas-api/src/main/resources/db/flyway/V20260622_01__create_business_rules.sql` and `V20260622_02__create_rule_execution_logs.sql`. (Note: `db/flyway/` not `db/migration/` — see `spring.flyway.locations=classpath:db/flyway` in `application-pg.properties`.)

`business_rules` keyed by `(factory_id, rule_code)` unique. `rule_execution_logs` keyed by rule_id + factory_id + executed_at for audit replay.

---

## 4. Canvas Tab UI sketch

`web-admin/src/views/platform/canvas-editor/tabs/BusinessRulesTab.vue` (sister chat creates):

```
[筛选] [Scope: ORDER ▾] [启用状态: 全部 ▾]                  [+ 新建规则]
┌──────────────────────────────────────────────────────────┐
│ Priority │ Code             │ Name           │ Scope │ Action      │ Enabled │ Ops │
│ 10       │ po_blacklist     │ 黑名单供应商拒单 │ ORDER │ REJECT      │ ✓       │ ⚙   │
│ 20       │ vip_discount     │ VIP 自动 5% 折扣│ ORDER │ MODIFY      │ ✓       │ ⚙   │
│ 30       │ stock_transfer   │ 缺货自动调拨     │ INVENTORY│ TRIGGER_WF│ ✗     │ ⚙   │
└──────────────────────────────────────────────────────────┘
```

Edit dialog: Monaco editor for SpEL condition + JSON for actionConfig + "测试评估" button (calls `POST /test-evaluate` with sample input).

---

## 5. AI Tools

5 tools under `ai/tool/impl/rules/`:

| Tool name | Action |
|---|---|
| `rule_create` | Insert new BusinessRule. Required: ruleCode, scope, actionType, conditionSpel, actionConfigJson |
| `rule_update` | Mutate existing rule (priority / condition / action / etc.) |
| `rule_toggle` | Enable/disable single rule |
| `rule_delete` | Soft-delete rule by id |
| `rule_test_evaluate` | Dry-run a rule against sample input (no side effects, no log) |

Per `.claude/rules/ai-intent-tool-skill-architecture.md`: each tool extends `AbstractBusinessTool`, `@Component` for auto-registration, no IntentHandler.

LLM examples:
- "黑名单供应商不能下单" → `rule_create` (REJECT)
- "VIP 客户订单超 5 万自动 5% 折扣" → `rule_create` (MODIFY)
- "把 vip_discount 规则停掉" → `rule_toggle`

---

## 6. Spring AOP integration

```java
@RuleEvaluate("ORDER")
@Transactional
public PurchaseOrder submitOrder(PurchaseOrderRequest req) { ... }
```

`RuleEvaluateAspect` executes **before** the method body (`@Around` pattern):
1. Resolve `factoryId` from request / SecurityContext
2. Pick the first non-primitive arg as `inputObject` (or use SpEL `#root.args[0]` semantics)
3. Call `ruleEngine.evaluate(factoryId, scope, inputObject)`
4. If `result.shouldReject` → throw `RuleViolationException(ruleCode, reason)` (mapped to 400 via existing `@ControllerAdvice` chain)
5. MODIFY applied directly to `inputObject` via reflection BEFORE method body executes
6. Proceed with method invocation, returning original result

**Skeleton scope**: this PR ships the aspect signature + UnsupportedOperationException body. Sister chat implements actual SpEL parsing + reflection + reject logic.

**Sister chat MUST attach `@RuleEvaluate(...)` annotations to PurchaseServiceImpl.submitOrder / SalesServiceImpl.createOrder / InventoryServiceImpl.updateStock as part of impl** — not this PR (avoids concurrent edits with Phase 1 sister chat touching the same Service files).

---

## 7. Phase 1 dependency (WorkflowEngineFacade stub)

This PR provides `WorkflowEngineFacade.startWorkflow(...)` as a `@Component` that throws `UnsupportedOperationException`. Reason: Canvas-Workflow (Phase 1) is a separate skeleton being shipped in parallel; injecting its `WorkflowEngineService` directly here would race with Phase 1's PR on the same imports.

**Sister chat impl plan**:
1. Wait for Phase 1 PR merge (Canvas-Workflow ships `WorkflowEngineService`).
2. In Phase 4a impl, replace facade body with `@Autowired WorkflowEngineService workflowEngineService;` + delegate.
3. Until then, `TRIGGER_WORKFLOW` rules can be created but will throw at execution time (acceptable for skeleton — UI prevents enabling).

---

## 8. Acceptance criteria

1. **Schema**: `business_rules` + `rule_execution_logs` migrations apply cleanly on PG (test + prod). `UNIQUE(factory_id, rule_code)` enforced.
2. **REJECT short-circuit**: A rule with `actionType=REJECT` matching input causes `submitOrder` to fail with HTTP 400, JSON body `{success:false, message:"<reason>", code:"RULE_VIOLATION", actionHint:"..."}`. Lower-priority rules NOT evaluated.
3. **MODIFY mutation**: A rule `MODIFY field=discount value=0.05` on `ORDER` scope mutates the `discount` field of `SalesOrderRequest` before the Service body persists it. Reflective set works on nested paths (e.g. `items[0].unitPrice` via SpEL).
4. **LOG persistence**: Every matched rule (including REJECT/MODIFY) creates a `rule_execution_logs` row with `input_json` snapshot + `result_json` (which actions applied). Query API returns paged history.
5. **TRIGGER_WORKFLOW deferred-OK**: With Phase 1 merged + facade wired, a TRIGGER_WORKFLOW rule successfully starts a workflow. Skeleton (this PR): rule can be saved/listed but throws at execution (UI surfaces via Rule 5 dead-end nav: "请先合并 Phase 1 Canvas-Workflow").
6. **AI tools**: 5 tools registered in ToolRegistry on startup (`✅ 注册工具: name=rule_create / rule_update / rule_toggle / rule_delete / rule_test_evaluate`). Each tool's `doExecute` correctly invokes the Service layer + returns `buildSimpleResult` payload.
7. **Canvas UI**: "业务规则" tab shows all rules for current factory + scope filter. Create/edit dialog with Monaco SpEL editor + Test Evaluate button. Per fool-proof Rule 2: dialog header shows scope + rule name context. Per Rule 5: empty state nav-links to spec doc.

---

## 9. Out of scope (this PR)

- SpEL evaluator body (UnsupportedOpEx in this PR)
- AOP @Around aspect body (UnsupportedOpEx)
- @RuleEvaluate attached to PurchaseService / SalesService / InventoryService (sister chat, to avoid Phase 1 concurrent file collisions)
- Real WorkflowEngineService injection (sister chat after Phase 1 merge)
- Canvas UI Vue files (sister chat)
- npm / vite / playwright artifacts

---

## 10. Sister chat estimate

**2-3 days** breakdown:
- Day 1 (8h): SpEL evaluator impl + AOP aspect body + LOG/REJECT actions + unit tests (RuleEngineImplTest)
- Day 2 (8h): MODIFY action via reflection + TRIGGER_WORKFLOW wire (after Phase 1 merge) + 5 AI tool bodies + tool unit tests + @RuleEvaluate attachment to 3 Service methods
- Day 3 (4-8h): Canvas Vue tab + integration tests + E2E (single F006 factory smoke) + ship

**Dependencies**: Phase 1 Canvas-Workflow merge gates TRIGGER_WORKFLOW. Phase 4a otherwise ships independently.

---

## 11. References

- Vision: `docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md` §3.5
- AbstractBusinessTool: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/AbstractBusinessTool.java`
- AI Tool architecture: `.claude/rules/ai-intent-tool-skill-architecture.md`
- BaseEntity / migration conventions: `.claude/rules/database-entity-sync.md`
- UI conventions: `.claude/rules/fool-proof-design.md` Rule 2/5
- F006 customer feedback (origin): vision doc §opening
