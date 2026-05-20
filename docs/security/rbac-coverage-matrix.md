# RBAC Coverage Matrix

**Last updated**: 2026-05-20
**Sprint**: 7 wave 1 Track T6
**Owner**: Cretas team / `backend/java/cretas-api`

This document tracks E2E test coverage of the **3-role × 5-scope** RBAC matrix
introduced in Sprint 5 (PR #54 G — DataScope framework) and swept across
6 service modules in Sprint 6 (PR #68 W2-B).

Each cell links to the test method that exercises that combination and the
date it was last verified.

---

## Matrix (15 cells)

| Role \ Scope            | ALL                              | DEPT                                  | DEPT_AND_BELOW                      | SELF                              | SELF_AND_BELOW                    |
|-------------------------|----------------------------------|---------------------------------------|-------------------------------------|-----------------------------------|-----------------------------------|
| **SALES**               | ✓ `sales_all` 2026-05-20         | ✓ `sales_dept` 2026-05-20 *(CUSTOM)*  | ✓ `sales_dab` 2026-05-20            | ✓ `sales_self` 2026-05-20         | ✓ `sales_sab` 2026-05-20          |
| **SALES_MGR**           | ✓ `sales_mgr_all` 2026-05-20     | ✓ `sales_mgr_dept` 2026-05-20 *(CUSTOM)* | ✓ `sales_mgr_dab` 2026-05-20      | ✓ `sales_mgr_self` 2026-05-20     | ✓ `sales_mgr_sab` 2026-05-20      |
| **FINANCE_DIRECTOR**    | ✓ `fin_dir_all` 2026-05-20       | ✓ `fin_dir_dept` 2026-05-20 *(CUSTOM)*| ✓ `fin_dir_dab` 2026-05-20          | ✓ `fin_dir_self` 2026-05-20       | ✓ `fin_dir_sab` 2026-05-20        |

Legend:
- ✓ = test exists and passes
- ✗ = no coverage
- *(CUSTOM)* = the spec calls this "DEPT" but `DataScope` enum doesn't have a
  bare DEPT level; the implementation maps single-dept-no-children to
  `CUSTOM` which currently falls back to ALL (see Sprint 7 backlog for a real
  per-customer whitelist).

---

## Test class

[`backend/java/cretas-api/src/test/java/com/cretas/aims/rbac/RbacIntegrationTest.java`](../../backend/java/cretas-api/src/test/java/com/cretas/aims/rbac/RbacIntegrationTest.java)

Run locally:

```bash
cd backend/java/cretas-api
./mvnw test -Dtest=RbacIntegrationTest
```

Expected: **15 tests pass** in ~60s on H2 in-memory DB. No external DB or
service dependencies.

---

## Fixtures

Seeded into the test DB by `@BeforeEach`. The corresponding production /
test-env fixture migration is:

- [`backend/java/cretas-api/src/main/resources/db/flyway/V20260701_03__rbac_e2e_fixtures.sql`](../../backend/java/cretas-api/src/main/resources/db/flyway/V20260701_03__rbac_e2e_fixtures.sql)

That migration is idempotent (`ON CONFLICT DO NOTHING`) and gated on `factory_id = 'F006'` existing.

### Fixture users (15)

All prefixed `rbac_test_*` for safe grep + cleanup.

| Username                        | Role code              | Department | Level | reports_to       |
|---------------------------------|------------------------|------------|-------|------------------|
| `rbac_test_sales_all`           | `sales_manager`        | sales      | 10    | —                |
| `rbac_test_sales_dept`          | `sales_manager`        | sales      | 10    | —                |
| `rbac_test_sales_dab`           | `sales_manager`        | sales      | 10    | —                |
| `rbac_test_sales_self`          | `sales_manager`        | sales      | 10    | —                |
| `rbac_test_sales_sab`           | `sales_manager`        | sales      | 10    | `sales_mgr_sab`  |
| `rbac_test_sales_mgr_all`       | `sales_manager`        | sales      | 5     | —                |
| `rbac_test_sales_mgr_dept`      | `sales_manager`        | sales      | 5     | —                |
| `rbac_test_sales_mgr_dab`       | `sales_manager`        | sales      | 5     | —                |
| `rbac_test_sales_mgr_self`      | `sales_manager`        | sales      | 5     | —                |
| `rbac_test_sales_mgr_sab`       | `sales_manager`        | sales      | 5     | —                |
| `rbac_test_fin_dir_all`         | `factory_super_admin`  | finance    | 0     | —                |
| `rbac_test_fin_dir_dept`        | `factory_super_admin`  | finance    | 0     | —                |
| `rbac_test_fin_dir_dab`         | `factory_super_admin`  | finance    | 0     | —                |
| `rbac_test_fin_dir_self`        | `factory_super_admin`  | finance    | 0     | —                |
| `rbac_test_fin_dir_sab`         | `factory_super_admin`  | finance    | 0     | —                |

### Fixture customers (9)

| Code            | Owner (created_by user)    |
|-----------------|----------------------------|
| `RBAC-CUST-001` | `rbac_test_sales_all`      |
| `RBAC-CUST-002` | `rbac_test_sales_dept`     |
| `RBAC-CUST-003` | `rbac_test_sales_dab`      |
| `RBAC-CUST-004` | `rbac_test_sales_self`     |
| `RBAC-CUST-005` | `rbac_test_sales_sab`      |
| `RBAC-CUST-006` | `rbac_test_sales_mgr_sab`  |
| `RBAC-CUST-007` | `rbac_test_fin_dir_all`    |
| `RBAC-CUST-008` | `rbac_test_fin_dir_dept`   |
| `RBAC-CUST-009` | `rbac_test_fin_dir_self`   |

---

## Architecture

See:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/DataScope.java` — 5-level scope enum
- `backend/java/cretas-api/src/main/java/com/cretas/aims/annotation/DataScope.java` — `@DataScope` annotation
- `backend/java/cretas-api/src/main/java/com/cretas/aims/aspect/DataScopeAspect.java` — AOP wrapper
- `backend/java/cretas-api/src/main/java/com/cretas/aims/security/DataScopeResolver.java` — scope resolution + chain BFS
- `backend/java/cretas-api/src/main/java/com/cretas/aims/security/DataScopeContext.java` — ThreadLocal stack

Production sweep (services that consult `DataScopeContext.current()` in queries):

| Service                          | DataScope columns | PR ref     |
|----------------------------------|-------------------|------------|
| `CustomerServiceImpl`            | `created_by`      | PR #68 W2-B |
| `MaterialBatchServiceImpl`       | `created_by`      | PR #68 W2-B |
| `QualityReturnOrderServiceImpl`  | `created_by`      | PR #68 W2-B |
| `PurchaseServiceImpl`            | `created_by`      | PR #68 W2-B |
| `ReturnOrderServiceImpl`         | `created_by`      | PR #68 W2-B |
| `SalesServiceImpl`               | `created_by`      | PR #68 W2-B |

---

## CI integration

The matrix runs in the `RbacIntegrationTest` class — no separate CI job is
needed because the existing Java backend `mvn test` workflow picks it up as
part of the standard Surefire test pass.

For an explicit standalone matrix run (e.g. nightly canary), use:

```bash
cd backend/java/cretas-api
./mvnw test -Dtest=RbacIntegrationTest -DfailIfNoTests=true
```

---

## Open items

| Issue | Why | Sprint |
|-------|-----|--------|
| Add Playwright UI tests under `web-admin/tests/rbac/` | Currently backend-only; UI side-effects not asserted | Sprint 7 W2 |
| Sweep remaining services for `@DataScope` annotation | Audit `SalesOrderServiceImpl`, `InvoiceServiceImpl`, `VoucherServiceImpl`, `DeliveryServiceImpl` | Sprint 7 W2 |
| Real `DEPT` (single-dept no-children) scope semantics | Currently maps to `CUSTOM` fallback ALL | Sprint 8 |
| Department-tree recursive scope (`DEPT_AND_BELOW` true sub-dept) | Today flat dept match only; spec says recursive | Sprint 7 |
| Sub-customer-whitelist `CUSTOM` impl | Currently no-op fallback to ALL | Sprint 8 |
