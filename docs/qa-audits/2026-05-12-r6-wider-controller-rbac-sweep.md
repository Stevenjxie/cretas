# R6 — Wider Sweep: Controllers Missing @RequirePermission (Cretas Java)

**Date**: 2026-05-12
**Branch**: `qa/r6-wider-controller-rbac-sweep`
**Worktree**: `C:/Users/Steve/cretas-r6-wider-sweep`
**Base commit**: `16d05e498` (origin/main HEAD)
**Sweep scope**: All 145 controllers under `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/`
**Context**: Chat2 PR #482 found 5 vulnerable controllers (V1–V5) via behavioral probing; chat1 R6 (in flight) is adding `@RequirePermission` to those. Chat3 R6 is sweeping Python `_strip_price_for_role`. Chat4 R6 is gating SmartBIUpload/SmartBIConfig. **This audit asks: are there OTHER controllers like V1–V5 that prior chats missed?**

---

## TL;DR

| Metric | Count |
|---|---|
| Total controllers (`@RestController` / `@Controller`) | **145** |
| Controllers with at least one RBAC annotation (`@RequirePermission` / `@RequireRole` / `@PreAuthorize` / `@PriceSensitive`) | **113** |
| Controllers with **zero** RBAC annotations | **32** |
| Of those 32 — already covered by chat1 R6 (V1/V2/V3) | **3** |
| Of those 32 — **NEW** 🔴 P0 monetary leaks found by this sweep | **2** |
| Of those 32 — 🚨 P1 privacy concern (RTSP credentials) | **1** |
| Of those 32 — ✅ safe by path-policy (intentional `/api/public/`, `/api/platform/`, `/api/internal/`) | **7** |
| Of those 32 — 🟡 needs-verify (low monetary likelihood, defense-in-depth fix) | **19** |

**The two NEW 🔴 P0 findings — both same root cause as chat2 #482's V4/V5**: hand-built `Map<String,Object>` extraction bypasses `PriceFieldResponseAdvice` recurse-strip, leaking monetary values to roles lacking `procurement:price:view` / `finance:read`:

1. **`ProductionAnalyticsController.getBudgetVsActual()`** — `/api/mobile/{factoryId}/production-analytics/budget-vs-actual` leaks 8 cost fields (`estimatedMaterialCost`, `actualMaterialCost`, `estimatedLaborCost`, `actualLaborCost`, `estimatedEquipmentCost`, `actualEquipmentCost`, `estimatedOtherCost`, `actualOtherCost`).
2. **`ReferenceDataController.getProduct()` / `findProducts()`** — `/api/mobile/{factoryId}/reference-data/products[/{id}]` leaks `unitPrice` in the hand-built Map (lines 225, 318).

Plus 1 privacy item:

3. **`IsapiRecordingController`** — `/playback-url?includeAuth=true` returns RTSP URL **with embedded credentials**; available to any logged-in user regardless of role.

---

## Methodology

1. `grep -lE "@RestController|@Controller\b" controller/` → 145 files.
2. `grep -lE "@RequirePermission|@RequireRole|@PreAuthorize|@PriceSensitive" controller/` → 113 files.
3. `comm -23 all gated` → **32 zero-RBAC files** (full list in §Sweep Matrix below).
4. For each of the 32, classified by:
   - **Path policy** (`/api/public/*` bypass, `/api/internal/*` X-Internal-Key, `/api/platform/*` platform_admin gate, `/api/mobile/*` JWT but no permission unless annotated, `/api/ai/*` outside all interceptors).
   - **Endpoint signatures** — count of `@*Mapping` methods, return types, hand-built `Map.put("...", ...)` for sensitive keys.
   - **Sensitivity keyword scan** — case-insensitive grep for `price|cost|amount|balance|revenue|credit|salary|wage|payment|invoice|valuation|profit|margin` in both code and return-type entities.
   - **Cross-reference** with V1–V6 (chat2 PR #482), Bug #318 intentional-open list, and chat1 R6 in-flight PR (#487 not yet opened at audit time).
5. **Did NOT do** behavioral curl probing (out of scope; chat2 PR #482 already produced 52 endpoint-runs against prod for V1–V5).

Security architecture (from `WebMvcConfig.java` + `JwtAuthInterceptor.java:229`):
- `/api/public/**` → JwtAuthInterceptor.preHandle returns true without token (intentional public).
- `/api/internal/**` → X-Internal-Key header (Python→Java only).
- `/api/platform/**` → JwtAuthInterceptor + PermissionInterceptor + RequireRoleInterceptor; **also gated to platform_admin only at line 133-134 (BUG-044 fix)**.
- `/api/mobile/**` → JwtAuthInterceptor + PermissionInterceptor + RequireRoleInterceptor + ModuleEnabledInterceptor; **but `PermissionInterceptor` and `RequireRoleInterceptor` are NO-OPs unless the method has `@RequirePermission` / `@RequireRole`**.
- Anything else (e.g. `/api/ai/complexity`) → outside all interceptor allowlists; reaches controller without JWT check. **Mitigated by**: nginx-139 vhost only forwards `/api/{mobile,admin,platform,public,smartbi}`, plus SG-uf64n0hcl8w37d34zfmy restricts 47:10010 to source 139.196.165.140/32 (per `.claude/rules/aliyun-credentials.md`). Defense-in-depth gap, not externally reachable.

---

## Findings (P0/P1)

### 🔴 P0 NEW — F1: `ProductionAnalyticsController.getBudgetVsActual()` cost leak

**File**: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionAnalyticsController.java:128-158`
**Endpoint**: `GET /api/mobile/{factoryId}/production-analytics/budget-vs-actual`
**Path policy**: `/api/mobile/*` → JWT enforced; **no `@RequirePermission`** → any logged-in user passes.
**Leak vector**: Hand-built `Map<String,Object>` (same as chat2 V4/V5 pattern); `PRICE_CONTAINER_PATH_REGEX` does not match ancestor `data → [item] → {estimatedMaterialCost,...}`.
**Affected fields** (line 144-151):
```java
item.put("estimatedMaterialCost",   p.getEstimatedMaterialCost());
item.put("actualMaterialCost",      p.getActualMaterialCost());
item.put("estimatedLaborCost",      p.getEstimatedLaborCost());
item.put("actualLaborCost",         p.getActualLaborCost());
item.put("estimatedEquipmentCost",  p.getEstimatedEquipmentCost());
item.put("actualEquipmentCost",     p.getActualEquipmentCost());
item.put("estimatedOtherCost",      p.getEstimatedOtherCost());
item.put("actualOtherCost",         p.getActualOtherCost());
```
Plus `productName` (cross-reference field — low sensitivity), `planNumber`, `status`.
**`PRICE_VALUE_KEYS` coverage**: even if PriceFieldResponseAdvice key list were extended, the path regex still excludes this endpoint — the only durable fix is route-level gating.
**Recommended fix**: add `@RequirePermission({"finance:read", "production:read"})` (or equivalent — match what `ReportController.dashboardOverview` ends up with after chat1 R6).
**Effort**: ~5 lines (1 annotation + import). Tests: 1 reflective annotation-presence guard + 1 AOP-level 403 for non-finance role.

Other 9 ProductionAnalyticsController methods (lines 36-126) return `ProductionDashboardResponse` / `EfficiencyDashboardResponse` / `List<Map<String,Object>>`. Their internal shape is **not** examined by this audit — recommend chat1 R6 (or follow-up) verify whether those DTO Maps also contain `cost` / `revenue` / `profit` keys.

---

### 🔴 P0 NEW — F2: `ReferenceDataController` `unitPrice` leak (Canvas DYNAMIC dropdown)

**File**: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ReferenceDataController.java:225,318`
**Endpoints**:
- `GET /api/mobile/{factoryId}/reference-data/products` (line 295-326)
- `GET /api/mobile/{factoryId}/reference-data/products/{id}` (line 212-231)
**Path policy**: `/api/mobile/*` → JWT enforced; **no `@RequirePermission`** by design (per class-level JavaDoc lines 44-55: "minimal lookups... require no module permission beyond authentication — sales-friendly without leaking sensitive HR data").
**Leak vector**: Hand-built Map; class-level decision to skip permission gate predates the `@PriceSensitive` recurse-strip era.
**Affected field** (line 225 and 318):
```java
m.put("unitPrice", p.getUnitPrice());
```
**Severity assessment**: `unitPrice` on `ProductType` is the standard selling-price reference. The class JavaDoc acknowledges this trade-off, but does NOT account for `procurement:price:view` separation enforced elsewhere — e.g. `/material-batches/inventory/valuation` returns 403 to `warehouse_mgr1`, but this dropdown returns the same value via a different shape.
**Behavior on prod**: same as V4/V5 — chat2 #482 confirmed identical role/admin payload on similar shapes. Cross-cohort test not run by this audit; recommend chat1 R6 or a follow-up E2E spot-check.
**Recommended fix path**: two options —
- **Option A (least disruptive)**: strip `unitPrice` from the response Map when the caller lacks `procurement:price:view`. Mirrors the Python-side `_strip_price_for_role` pattern (per chat3 R6 work).
- **Option B (clean cut)**: add `@RequirePermission({"sales:read", "procurement:read"})` and drop `unitPrice` for users without `procurement:price:view`. May affect existing Canvas DYNAMIC dropdowns — needs UX check.
**Effort**: A = ~10 lines (helper + 2 call sites); B = ~5 lines + UX verification.

Other 16 methods on ReferenceDataController return only `id`/`name`/`code`/`contactPerson`/`orderNumber`/`status` — no monetary fields. Class-level design is OK for those.

---

### 🚨 P1 — F3: `IsapiRecordingController` RTSP credential exposure (privacy)

**File**: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/isapi/IsapiRecordingController.java`
**Endpoint**: `GET /api/mobile/{factoryId}/isapi/recordings/playback-url?includeAuth=true` (line 62-101)
**Path policy**: `/api/mobile/*` → JWT enforced; **no `@RequirePermission`** → any logged-in user across any role.
**Leak vector**: When `includeAuth=true`, `recordingService.getPlaybackUrl(...)` returns the RTSP URL with embedded camera credentials. Any factory-scoped logged-in user (warehouse_mgr, restaurant_staff, etc.) can fetch playback for ANY device in their factory.
**Other 3 endpoints** (`/search`, `/recent`, `/by-date`) return `RecordingSearchResponse` listing recording windows — less severe but still surveillance metadata accessible to all roles.
**Recommended fix**: add `@RequirePermission({"camera:read"})` or `@RequireRole({"factory_super_admin", "security_manager"})` to all 4 methods. The `/playback-url?includeAuth=true` variant ideally restricted further to ops/security roles.
**Effort**: ~5 lines (annotation + import) + 4 reflective guards.

---

## Sweep Matrix (full 32 zero-RBAC controllers)

Legend:
- **🔴 P0** — confirmed monetary leak via hand-built Map (NEW or in chat1 R6 in-flight).
- **🚨 P1** — privacy/security risk other than monetary.
- **⏳ chat1 R6** — already in chat2 PR #482 V1–V5; chat1 R6 in-flight fix expected.
- **🟢 SAFE** — path-policy gated at framework level (not annotation-level).
- **🟡 VERIFY** — no monetary leak found in static analysis, but `@RequirePermission` recommended for defense-in-depth.

| # | Controller | Path | Methods | Verdict | Why |
|---|---|---|---|---|---|
| 1 | `AIPublicDemoController` | `/api/public/ai-demo` | 4 | 🟢 SAFE | `/api/public/*` bypassed by `JwtAuthInterceptor.java:229` — intentional public demo. |
| 2 | `ComplexityTrainingController` | `/api/ai/complexity` | 4 | 🟡 VERIFY | Path outside ALL interceptor allowlists. Unauthenticated `/train`, `/test`, `/status`, `/train-from-file`. **Mitigated by**: not proxied through nginx-139 (only `/api/{mobile,admin,platform,public,smartbi}` are forwarded), and SG restricts 47:10010 to source 139/32. Recommend adding `@RequireRole({"factory_super_admin"})` for defense-in-depth — but no current externally-reachable leak. |
| 3 | `DictionaryTestController` | `/api/public/dictionary-test` | 4 | ✅ REMOVED | **2026-07-18 correction:** the prior `🟢 SAFE` verdict was wrong. CORS is not server-side authorization and does not constrain non-browser clients; this was a public write endpoint. PR #1432 removed the controller from production source and added an absence regression test. Production deployment/404 verification remains a separate operational check. |
| 4 | `factory/FactoryWarehouseController` | `/api/mobile/{factoryId}/factory/warehouses` | 1 | 🟡 VERIFY | Returns `List<FactoryWarehouse>` (id/code/name/type only — no monetary). FMR-dropdown-style, similar intent to ReferenceData. Defense-in-depth: add `@RequirePermission({"warehouse:read"})`. |
| 5 | `FactoryBlueprintController` | `/api/platform/blueprints` | 9 | 🟢 SAFE | `/api/platform/*` gated to platform_admin via `JwtAuthInterceptor:133-134` (BUG-044). |
| 6 | `FactoryNetworkController` | `/api/mobile/{factoryId}/factories` | 1 | 🟡 VERIFY | Cross-factory network list. No monetary fields. Defense-in-depth: `@RequirePermission({"factory:read"})`. |
| 7 | `FeatureConfigController` | `/api/mobile/{factoryId}/feature-config` | 3 | 🟡 VERIFY | Returns `FactoryFeatureConfig`. Feature flags can leak business strategy. Recommend `@RequireRole({"factory_super_admin", "permission_admin"})`. |
| 8 | `FieldVisibilityController` | `/api/mobile/{factoryId}` | 4 | 🟡 VERIFY | RBAC config endpoint (returns Factory/User DTO). Should be admin-only — `@RequireRole({"factory_super_admin", "permission_admin"})`. |
| 9 | `FoodKBFeedbackController` | `/api/mobile/{factoryId}/food-kb/feedback` | 3 | 🟡 VERIFY | KB feedback submissions. Low risk. Defense-in-depth fine. |
| 10 | `GenericAIChatController` | `/api/mobile/ai` | 2 | 🟡 VERIFY | Generic AI chat — could expose system prompts via prompt-injection. Defense-in-depth: rate-limit + permission. |
| 11 | `ImageAnalysisTestController` | `/api/mobile/{factoryId}/image-analysis` | 4 | 🟡 VERIFY | Image analysis test. Likely dev-only; if so, restrict to `factory_super_admin`. |
| 12 | `isapi/IsapiRecordingController` | `/api/mobile/{factoryId}/isapi/recordings` | 4 | **🚨 P1** | F3 — see §Findings. RTSP credentials in `?includeAuth=true`. |
| 13 | `MaterialConsumptionController` | `/api/mobile/{factoryId}/processing/material-consumptions` | 9 | **⏳ chat1 R6** | V3 in chat2 #482 — `unitPrice` / `totalCost` leak. Being fixed. |
| 14 | `MobileController` | `/api/mobile` | 36 | 🟡 VERIFY | Auth/health/upload/dashboard/sync/device/version/config/push endpoints. `/dashboard/{factoryId}` returns `MobileDTO.DashboardData` — verify whether it has cost/revenue fields. `/auth/*` correctly public. |
| 15 | `NotificationController` | `/api/mobile/{factoryId}/notifications` | 9 | 🟡 VERIFY | Notification content can include order numbers / amounts. Defense-in-depth: `@RequirePermission({"notification:read"})`. |
| 16 | `OnboardingController` | `/api/internal/onboarding` | 1 | 🟢 SAFE | `/api/internal/*` X-Internal-Key (Python→Java only). |
| 17 | `ProductionAnalyticsController` | `/api/mobile/{factoryId}/production-analytics` | 10 | **🔴 P0 NEW** | F1 — see §Findings. `/budget-vs-actual` leaks 8 cost fields. Other 9 methods need DTO check. |
| 18 | `ProductionProgressDashboardController` | `/api/mobile/{factoryId}/dashboard` | 1 | 🟡 VERIFY | Returns `Map<String,Object>` — shape unknown from controller alone. Recommend chat1 R6 inspect the service-layer Map construction for cost/amount keys. |
| 19 | `ReferenceDataController` | `/api/mobile/{factoryId}/reference-data` | 18 | **🔴 P0 NEW** | F2 — see §Findings. `unitPrice` in `/products` + `/products/{id}`. |
| 20 | `ReportController` | `/api/mobile/{factoryId}/reports` | 25 | **⏳ chat1 R6** | V2 in chat2 #482 — massive leak across `/reports/{inventory,finance,sales,cost-variance,dashboard/*}`. Being fixed. |
| 21 | `restaurant/RestaurantDashboardController` | `/api/mobile/{factoryId}/restaurant-dashboard` | 1 | **⏳ chat1 R6** | V1 in chat2 #482 — `thisMonthWastageCost`. Being fixed. |
| 22 | `RoleController` | `/api/mobile/{factoryId}/roles` | 1 | 🟡 VERIFY | Returns `List<Map<String,Object>>` of role id+name. Low risk; defense-in-depth `@RequirePermission({"role:read"})`. |
| 23 | `RulePackController` | `/api/platform/rule-packs` | 4 | 🟢 SAFE | `/api/platform/*` platform_admin only. |
| 24 | `SchedulingMetricsController` | `/api/mobile/{factoryId}/metrics/scheduling` | 5 | 🟡 VERIFY | Scheduling metrics (no monetary). Defense-in-depth fine. |
| 25 | `SystemController` | `/api/mobile/system` | 3 | 🟡 VERIFY | System status. Should be admin-only — `@RequireRole({"factory_super_admin"})`. |
| 26 | `SystemLogController` | `/api/mobile/{factoryId}/system-logs` | 1 | 🟡 VERIFY | System logs likely have PII / auth events. Must be `@RequireRole({"factory_super_admin", "permission_admin"})`. **Borderline P1 privacy** — flagged for chat1 R6 attention. |
| 27 | `TemplatePackageController` | `/api/platform` | 8 | 🟢 SAFE | `/api/platform/*` platform_admin only. |
| 28 | `TraceabilityController` | `/api/mobile/{factoryId}/traceability/*` (3) + `/api/public/trace/*` (2) | 5 | 🟡 VERIFY | 2 public consumer endpoints (intentional, returns desensitized data per JavaDoc). 3 internal `/api/mobile/*` endpoints return full `TraceabilityDTO`. Need to verify `TraceabilityDTO` doesn't expose supplier prices. |
| 29 | `UserCountController` | `/api/internal/users` | 1 | 🟢 SAFE | `/api/internal/*` X-Internal-Key. |
| 30 | `VoiceRecognitionController` | `/api/mobile` | 12 | 🟡 VERIFY | Voice transcripts may contain sensitive business content. Defense-in-depth: `@RequirePermission({"voice:read"})`. |
| 31 | `WarehouseInventoryController` | `/api/mobile/{factoryId}/inventory` | 2 | 🟡 VERIFY | Returns `List<MaterialBatchDTO>` + `List<FinishedGoodsBatch>`. Cross-factory gate enforced manually (lines 115-140). Strip depends on `@PriceSensitive` coverage of `MaterialBatchDTO` and `FinishedGoodsBatch` — recommend chat1 R6 grep `@PriceSensitive` on those classes. |
| 32 | `WorkflowNodeController` | `/api/mobile/workflow` | 2 | 🟡 VERIFY | Workflow nodes (canvas designer). Likely metadata; defense-in-depth fine. |

---

## Beyond zero-RBAC: did the V4/V5 partial-gate pattern exist elsewhere?

Chat2 #482 V4 (`SupplierAdmissionController`) and V5 (`PriceListController`) both have `@RequirePermission` on **write** methods but NOT on **read** methods — the read methods leaked. Both are in the "with-RBAC" list (113), not the "zero-RBAC" list (32). To find sister cases of this pattern across the 113 with-RBAC controllers would require reading each file method-by-method (out of this audit's 2-3h budget).

Recommendation: **a separate audit pass should run** —
1. For each of the 113 with-RBAC controllers, list all `@GetMapping` methods that **lack** any of `@RequirePermission` / `@RequireRole` / `@PreAuthorize` at the method level.
2. For each ungated read method, grep the response shape for monetary keys (price/cost/amount/balance/revenue/credit).
3. Anything matching → P0/P1 fix ticket.

This audit explicitly did NOT do that work — it focused on **completely ungated** controllers, on the premise that "if the chat2 finding was 5 partial-gate + V6 SmartBIDashboard, the wider sweep should at least cover the 32 fully ungated ones too." Recommend opening a follow-up audit ticket once chat1 R6 #487 ships and the partial-gate exemplars are crystallized.

---

## P0 fix tickets (for chat1 R6 or follow-up)

| # | Controller | Endpoint(s) | Severity | Recommended annotation | Estimated effort |
|---|---|---|---|---|---|
| F1 | `ProductionAnalyticsController` | `/budget-vs-actual` | 🔴 P0 | `@RequirePermission({"finance:read", "production:read"})` | ~5 LOC + 2 tests |
| F2 | `ReferenceDataController` | `/products`, `/products/{id}` | 🔴 P0 | Option A: strip `unitPrice` for non `procurement:price:view` (mirrors Python `_strip_price_for_role`); Option B: `@RequirePermission({"sales:read", "procurement:read"})` + UX check | A=~10 LOC; B=~5 LOC + UX verify |
| F3 | `IsapiRecordingController` | All 4 endpoints (especially `/playback-url?includeAuth=true`) | 🚨 P1 | `@RequirePermission({"camera:read"})` on all 4; consider tighter role for `?includeAuth=true` | ~5 LOC + 4 tests |

**Out-of-scope for this audit** (defense-in-depth):
- 19 🟡 VERIFY controllers — recommend chat1 R6 or follow-up add `@RequirePermission` per the role hints in §Sweep Matrix.
- 113 with-RBAC controllers — V4/V5 partial-gate pattern sweep (separate audit pass).

---

## Acceptance — how this audit was verified

- [x] All 145 controllers enumerated via `grep -lE "@RestController|@Controller\b"` (count matches `find ... -name "*.java" | wc -l` = 145).
- [x] Diff against RBAC marker set → 32 zero-RBAC controllers (math: 145 − 113 = 32 ✓).
- [x] V1–V6 from chat2 PR #482 cross-checked: V1=RestaurantDashboard, V2=Report, V3=MaterialConsumption all present in my 32-list; V4=SupplierAdmission, V5=PriceList live in with-RBAC list (113) because they have `@RequirePermission` on write methods.
- [x] Path-policy framework verified by reading `WebMvcConfig.java:35-72` + `JwtAuthInterceptor.java:133-229`.
- [x] Nginx public exposure verified by reading `ops/nginx-vhosts-139/web-admin.conf:30-160` — only `/api/{mobile,admin,platform,public,smartbi}` forwarded.
- [x] Hand-built Map leak grep run on all 32 candidates → 3 hits (V3 already known + F1 NEW + F2 NEW).
- [x] No code changes in this PR — audit doc + matrix only.

---

## Co-authorship

Co-authored-by: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
