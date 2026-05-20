# Sprint 5 Track A — C-MENU-PERSONAL-VIEW Deferred Items Spec

**Status**: Spec stub for follow-up PRs.
**Parent**: `docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md` §A
**MVP Slice (this PR)**: 我创建的工作流 (`my-created.vue`) end-to-end + `my-participated` backend endpoint.
**Source**: Round 12 §D X4 (HJ 6 sub-menu) + Round 13 §10 + 31-doc §I.4

---

## §0 Scope summary

| Sub-menu | Vue path | Backend endpoint | Status (this PR) |
|---|---|---|---|
| 待处理 (approval queue) | `PendingApprovalsWidget.vue` | `GET /workflow/instances/pending` | ✅ Shipped (issue #20) |
| 工作流设置 (designer) | `/platform/canvas-editor` 工作流 Tab | `ApprovalWorkflowController` CRUD | ✅ Shipped (Sprint 3 Track-I) |
| **我创建的工作流** | `/workflow/my-created` | `GET /workflow/instances/my-created` | ✅ Shipped (this PR MVP) |
| **我参与的工作流** | `/workflow/my-participated` (TODO) | `GET /workflow/instances/my-participated` | ⚠️ Backend ✅, Frontend deferred |
| **工作流处理 (admin)** | `/workflow/admin-running` (TODO) | `GET /workflow/instances/admin-running` | ❌ Deferred (Sprint 5 follow-up PR) |
| **流转规则设置** (frontend) | `/workflow/rules` (TODO) | `WorkflowRuleController` (shipped) | ❌ Deferred (Sprint 5 follow-up PR) |

---

## §1 我参与的工作流 frontend (deferred)

### Backend (✅ shipped in this PR)
- `WorkflowEngineService.findParticipatedBy(factoryId, userId, pageable)`
- `WorkflowEngineServiceImpl.findParticipatedBy` — delegates to JPQL `findParticipatedBy` repo query
- `ApprovalWorkflowInstanceRepository.findParticipatedBy` — distinct join via ApprovalHistory.actor_id
- Endpoint: `GET /api/mobile/{factoryId}/workflow/instances/my-participated`

### Frontend (TODO, ~2h)
- Copy `my-created.vue` to `my-participated.vue`
  - Change endpoint to `/workflow/instances/my-participated`
  - Change title "我创建的工作流" → "我参与的工作流"
  - Card empty text "暂无您发起的工作流实例" → "暂无您参与的工作流实例"
- Add route entry in `web-admin/src/router/index.ts` under `workflow` group:
  ```ts
  {
    path: 'my-participated',
    name: 'WorkflowMyParticipated',
    component: () => import('@/views/workflow/my-participated.vue'),
    meta: { requiresAuth: true, title: '我参与的工作流', module: 'workflow' }
  }
  ```

### Why deferred
Backend already shipped. Frontend is mechanical copy + label changes. Defer to keep PR scope focused on MVP slice.

---

## §2 工作流处理 admin UI (deferred — backend + frontend)

### Backend (TODO, ~3h)
- New service method `WorkflowEngineService.listAllRunning(factoryId, pageable)` (super-admin only, no role filter)
- Endpoint `GET /api/mobile/{factoryId}/workflow/instances/admin-running`
- RBAC: gate to `factory_super_admin` + `platform_admin` only
- Reuse `hydrateInstances` helper in `WorkflowInstanceController`

### Frontend (TODO, ~3h)
- `web-admin/src/views/workflow/admin-running.vue` — admin view of all RUNNING instances org-wide
- Additional columns vs personal view: 发起人 username (already in DTO) + 工厂 + 当前 active 节点(s) count
- Bulk actions: 强制 cancel (super-admin reflex), 委派 (delegate to other reviewer)
- Filters: module, initiator, time range

### Why deferred
- Need new service method + RBAC gate review
- UI design: bulk actions need separate confirm dialogs (Rule 2 + Rule 4 of fool-proof-design)
- Out of MVP scope (4 views in 2h = unrealistic)

---

## §3 流转规则设置 frontend (deferred)

### Backend (✅ shipped — Round 11 §I.4 C-WF-RULE-1)
- `WorkflowRuleController` — full CRUD (`GET /workflow-rules`, `POST`, `PUT`, `DELETE`, `POST {id}/test`)
- `WorkflowRule` entity — supports `AMOUNT / DEPT / ROLE / SPEL_CUSTOM` rule types

### Frontend (TODO, ~4h)
Round 13 §9 specifies 4 columns per HJ:
| 列 | 数据源 |
|---|---|
| 规则名称 | `WorkflowRule.name` |
| 默认负责人 | `WorkflowRule.expression` (when ruleType=ROLE) OR derived |
| 排序值 | `WorkflowRule.priority` |
| 操作 | edit / delete / test buttons |

- `web-admin/src/views/workflow/rules.vue` — table + Create/Edit dialog
- Dialog with ruleType select → conditional fields:
  - `AMOUNT` → numeric threshold + operator (>, <, >=, <=, ==)
  - `DEPT` → multi-select departments
  - `ROLE` → multi-select roles (factory_super_admin / finance_manager / etc)
  - `SPEL_CUSTOM` → SpEL expression textarea + Test button (calls `POST {id}/test` with mock context)
- Per fool-proof-design.md Rule 3: ruleType is dropdown not free text. Per Rule 2: Dialog header shows workflow name + node label.

### Why deferred
Frontend is non-trivial (4 ruleType × conditional UI = 4 sub-form patterns). Backend ✅ ship means we could pull this together in a follow-up PR.

---

## §4 DOD recompute (per parent §A DOD)

> DOD (parent): 4 sub-menu Vue 全 navigable in Canvas → 工作流 → side nav. E2E 1 个 demo 流 走完
> (创建 → 我创建的 ↑1 → 流转 → 我参与的 ↑1 → 处理 → admin 看到 → 完成).

| DOD item | This PR | Sprint 5 follow-up |
|---|---|---|
| 我创建的工作流 navigable | ✅ | — |
| 我参与的工作流 navigable | ❌ (backend ✅) | ✅ (~2h, mechanical) |
| 工作流处理 admin navigable | ❌ | ✅ (~6h backend + frontend) |
| 流转规则设置 navigable | ❌ (backend ✅) | ✅ (~4h frontend) |
| E2E demo 流 | ❌ — needs all 4 views | After follow-up PRs |

**MVP coverage**: 1/4 sub-views ship end-to-end. 1/4 has backend ready (frontend mechanical). 2/4 deferred fully.
Total DOD coverage: ~30% (1 view + partial backend for 2nd).

---

## §5 Time budget retrospect

| Item | Estimated | Actual |
|---|---|---|
| Read brief + grep existing canvas/workflow code | 0.5h | 0.4h |
| Backend: 2 repo methods + 2 service methods + 2 endpoints + helper extraction | 1.5h | 1.2h |
| Frontend: my-created.vue + router entry | 0.5h | 0.4h |
| Flyway migration + entity @Index | 0.2h | 0.2h |
| Unit test (7 cases) | 0.4h | 0.3h |
| Java compile + test run | 0.4h | 0.5h (Maven cold start) |
| Spec stub + commit | 0.3h | 0.2h |
| **Total** | **3.8h** | **~3.2h** |

Within ~3h time budget given by Steve.
