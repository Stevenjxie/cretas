# 餐饮采购到付款闭环 Gap Closure Plan

Scope: 邓总餐饮场景里“供应商送货 -> 仓管验收 -> 入库 -> 成本/应付/对账”的剩余闭环。当前优先收口子系统 A：送货验收入库过账。

## Current Baseline

- Branch/worktree: `feat/restaurant-inbound-posting` at `C:\Users\Steve\cretas-restaurant-inbound-posting`
- Rebased on current `origin/main` on 2026-06-05.
- Current A commit after rebase: `e6ded0b1e feat(restaurant): supplier delivery inbound posting`
- Flyway frontier after rebase: `V20260927_03`

## Parallel Execution Plan

### Track A1 Backend Posting Truth

Owner: Agent A

Goal: prove and harden that a supplier delivery confirmation creates real inventory batches.

Tasks:
- Decide warehouse consistency rule:
  - Preferred P0: force supplier delivery posting to default `WH-LOG`, because RN currently has no warehouse selector.
  - If supporting custom warehouse now, fix `PurchaseServiceImpl#createMaterialBatchFromReceiveItem` so batch warehouse follows `PurchaseReceiveRecord.warehouseId`.
- Add tests for:
  - `DRAFT -> CONFIRMED` creates receive record and binds `materialBatchId`.
  - duplicate confirm rejects without extra batch.
  - invalid material fails and persists `posting_status=FAILED`.
  - warehouse recorded on note/receive/batch is consistent.
- Verification:
  - `mvn -Dtest=RestaurantInventoryPostingServiceImplTest,SupplierDeliveryNoteServiceTest test`
  - `mvn -DskipTests compile`

### Track A2 RN Warehouse Usability

Owner: Agent B

Goal: make the warehouse clerk flow usable without typing UUIDs.

Tasks:
- Add supplier select/search path for create screen.
- Add material select/search path for line items.
- Keep manual fallback only as advanced/error recovery, not primary UX.
- Add draft operations:
  - edit line items
  - reject delivery note
  - delete draft
- Add clear status/action copy:
  - unmatched material -> choose material
  - failed posting -> retry after correction
  - confirmed note -> read-only with batch ids
- Verification:
  - narrow TS check covering supplier delivery screens, `restaurantApiClient.ts`, and `types/restaurant.ts`

### Track A3 API Contract And RBAC

Owner: Agent C

Goal: make mobile API contract complete and permission-safe.

Tasks:
- Add explicit `@RequireModule("restaurant")` to read endpoints.
- Add read permission to `limits/list/detail`.
- Confirm write permissions remain `warehouse:read_write`.
- Add POST-compatible mobile endpoints where needed for RN:
  - reject
  - update lines
  - delete draft if mobile client cannot reliably use DELETE with bodyless route
- Keep API response shape in `{ success, data, message }`.
- Tests:
  - non-DRAFT edit/reject/delete guarded.
  - invalid status list parameter returns honest error.

## Integration Steps

1. Wait for Agents A/B/C to finish.
2. Review changed files and reject overlap or unrelated edits.
3. Integrate in this order:
   - A3 controller/API changes
   - A1 backend service/test changes
   - A2 RN client/screens
4. Run:
   - `git diff --check`
   - Flyway duplicate check
   - `mvn -Dtest=SupplierDeliveryNoteServiceTest,RestaurantInventoryPostingServiceImplTest test`
   - `mvn -DskipTests compile`
   - RN narrow TS check
5. Commit integrated patch with explicit paths.

## Business E2E Acceptance For Subsystem A

Use `qhj_prod / 123456` or local/test equivalent data.

Scenario:
1. Warehouse clerk opens 餐饮端 -> 待验收入库.
2. Create delivery note:
   - supplier: real qhj supplier
   - material: real qhj raw material, e.g. 青花椒
   - quantity: 10 kg
   - unit price: 40
3. Save draft and read it back in list/detail.
4. Confirm inbound after preview.
5. Verify:
   - note status `CONFIRMED`
   - posting status `POSTED`
   - receive record id present
   - each line has material batch id
   - inventory batch quantity increased
   - repeated confirm returns 409 and no new batch
6. Capture API results and screenshots.

## Post-A Subsystems

These are not required for A merge, but are part of the full procure-to-pay closure.

### E Payable Posting

Goal: supplier delivery confirmation creates payable draft/account entry without purchase order.

Dependencies:
- A `receiveRecordId`
- supplier id and amount

Tasks:
- Add payable posting service for restaurant delivery notes.
- RBAC amount masking.
- Finance UI for payable confirmation.
- Tests for no double payable on retry.

### F Monthly Supplier Reconciliation

Goal: support supplier monthly statement matching delivery notes, payables, payments, adjustments.

Tasks:
- Monthly reconciliation entity/view.
- Supplier statement import or manual match.
- Difference handling and audit trail.
- Lock after finance confirmation.

### C Delivery Evidence

Goal: attach photos/videos to delivery note for disputes and clerk accountability.

Tasks:
- RN photo/video evidence capture.
- OSS upload reuse.
- Web/RN preview.
- Size/type guard and audit metadata.

### D Excel/CSV Import

Goal: let universal upload classify a supplier delivery file and route into delivery draft creation.

Tasks:
- Add upload classification target.
- Map supplier/material/date/quantity/price columns.
- Create draft with unmatched material review queue.

### B Stall/Department Cost Attribution

Goal: connect inbound ingredients to kitchen/stall/department consumption.

Dependencies:
- requisition/wastage/stocktaking deduction posting.

Tasks:
- Add cost ownership fields to issue/wastage flows.
- Deduct material batches on approved requisition/wastage.
- Department/stall cost dashboard.

## Stop Conditions

Do not merge/deploy A if:
- Flyway version collides.
- Backend compile fails.
- Confirm inbound can create a receive record without material batch ids.
- Repeated confirm creates duplicate inventory.
- RN primary create path requires typing raw UUIDs only.
- Read endpoints expose restaurant data without restaurant module guard.
