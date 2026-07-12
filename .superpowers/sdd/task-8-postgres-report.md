# Task 8 PostgreSQL verification report

Date: 2026-07-11 (Asia/Singapore)

## Result

PASS. The product-process Workflow migration and configuration persistence path were verified on a real local PostgreSQL 17 instance.

- PostgreSQL test result: **3 tests, 0 failures, 0 errors, 0 skipped** (`39.11 s`)
- Datasource-target guard result: **5 tests, 0 failures, 0 errors, 0 skipped**
- Environment gate result without opt-in: **3 tests, 3 skipped, 0 failures, 0 errors**
- Product code changes required: **none**
- Remote hosts, shared tenant databases, SSH, deploy, push, and merge: **not used**

## Isolation and safety

The installed PostgreSQL 17 binaries were used to start a temporary instance bound only to `127.0.0.1` on an ephemeral port. The existing Windows PostgreSQL service and its authentication configuration were not changed.

- Temporary cluster directory prefix: `cretas_workflow_pg_`
- Disposable database prefix: `cretas_workflow_verify_`
- Temporary superuser: `workflow_verify_admin` (trust authentication inside the isolated local instance only)
- Destructive cleanup guards asserted both prefixes before database drop or recursive directory removal.
- No credential value was printed or committed.

The existing `postgresql-x64-17` service remained `Running` after cleanup.

## Reusable test

Test class:

`backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductProcessWorkflowPostgresIntegrationTest.java`

The test uses a real PostgreSQL datasource, actual `ProductProcessWorkflowRepository`, actual `ProductProcessWorkflowServiceImpl`, actual structural validator, actual controller, and `MockMvc`. Product ownership and publish catalog lookup are mocked so the test remains focused on Workflow configuration persistence rather than requiring the full product/work-process catalog fixture graph.

Before Spring registers the datasource URL, `DisposablePostgresTargetGuard` parses and validates it. It permits only:

- the exact `jdbc:postgresql` scheme;
- host `localhost` or `127.0.0.1`;
- one plain database path segment whose name starts with `cretas_workflow_verify_` and has a non-empty safe suffix;
- an optional valid TCP port.

It rejects remote/private-network hosts, IPv6, userinfo, query parameters, fragments, encoded paths, path traversal, extra path segments, a wrong database prefix, malformed URLs, and whitespace padding. Username remains configurable and nonblank for portability; isolation is enforced by the validated local URL and disposable database prefix.

Pure guard test:

```powershell
mvn -q -Dtest=DisposablePostgresTargetGuardTest test
```

Observed result: `5` tests, `0` failures, `0` errors, `0` skipped.

It is disabled unless this explicit opt-in is present:

```text
CRETAS_WORKFLOW_PG_VERIFY=true
```

Datasource inputs:

```text
CRETAS_WORKFLOW_PG_URL=jdbc:postgresql://127.0.0.1:<ephemeral-port>/<disposable-db>
CRETAS_WORKFLOW_PG_USER=workflow_verify_admin
CRETAS_WORKFLOW_PG_PASSWORD=
```

Final command:

```powershell
$env:CRETAS_WORKFLOW_PG_VERIFY = 'true'
$env:CRETAS_WORKFLOW_PG_URL = 'jdbc:postgresql://127.0.0.1:<port>/<cretas_workflow_verify_*>'
$env:CRETAS_WORKFLOW_PG_USER = 'workflow_verify_admin'
$env:CRETAS_WORKFLOW_PG_PASSWORD = ''
mvn -q -Dtest=ProductProcessWorkflowPostgresIntegrationTest test
```

Safe-skip command:

```powershell
Remove-Item Env:CRETAS_WORKFLOW_PG_VERIFY -ErrorAction SilentlyContinue
mvn -q -Dtest=ProductProcessWorkflowPostgresIntegrationTest test
```

## Assertions executed

### 1. Fresh Workflow migration

The exact `V20261027_53__product_process_workflow.sql` resource was executed in a new isolated schema on PostgreSQL.

Verified:

- `product_process_workflows` is created.
- `nodes_json`, `edges_json`, and `viewport_json` are PostgreSQL `jsonb`.
- `lock_version` is `NOT NULL`.
- status check accepts only `DRAFT` and `PUBLISHED`; `INVALID` is rejected with SQLSTATE `23514`.
- `uk_product_process_workflow_active_draft` exists.
- a second live draft for the same factory/product is rejected with SQLSTATE `23505`.
- a published row for that same factory/product remains allowed.

### 2. Existing `work_processes` migration/backfill

A pre-migration `public.work_processes` table was created with two existing rows and without the new column. The exact `V20261027_54__work_process_default_output_material_kind.sql` resource was then executed as one PostgreSQL `DO $$ ... $$` block.

Verified:

- both existing rows were backfilled to `SEMI_FINISHED`.
- `default_output_material_kind` is `NOT NULL`.
- its database default is `SEMI_FINISHED`.
- a new row that omits the field receives `SEMI_FINISHED`.
- an invalid `RAW_MATERIAL` value is rejected with SQLSTATE `23514`.
- the accepted values are `SEMI_FINISHED` and `FINISHED_GOOD`.

The task brief referred generically to `output_material_type`; the implemented schema contract is named `default_output_material_kind`, and that exact production migration was verified.

### 3. Real save/read/conflict/publish/readback

The test exercised the controller over `MockMvc`, backed by the actual service and PostgreSQL JPA repository:

1. `PUT .../{productId}/draft` created a draft and returned `lockVersion=0`.
2. `GET .../{productId}` read the persisted draft.
3. Two copies of version `0` were retained.
4. The first update succeeded and returned `lockVersion=1`.
5. The stale second update returned HTTP `409` and `PRODUCT_PROCESS_WORKFLOW_CONFLICT`.
6. `POST .../{productId}/publish` published version `1`.
7. The editor `GET` read the published definition back after clearing the persistence context.
8. A native PostgreSQL JSONB query in the same transaction confirmed the stored node count, edge count, and conversion expression.

### 4. Multi-input/multi-output field preservation

The persisted graph contained:

- 7 nodes and 6 edges.
- two raw-material inputs into the trim process.
- one trim output into a semi-finished material.
- one cook input and two cook outputs.
- output kinds `FINISHED_GOOD` and `SEMI_FINISHED`.
- SKU references `RM-PIG-A`, `RM-PIG-B`, `SFI-TRIMMED`, `SFI-LOSS`, and `FG-BRAISED-400`.
- per-port `materialNodeId`, `materialKind`, `unit`, and `ordinal`.
- process `inputUnit`/`outputUnit` values including `kg -> box`.
- conversion metadata `mode=FIXED_RATIO` and `expression=200 kg = 400 box`.
- exact source/target handles for all six edges.
- viewport data, including the successful writer's `zoom=1.25`.

All of these values survived save, update, publish, editor readback, and native JSONB readback.

## Additional runtime observation

The repository-wide JPA slice scans all project entities. On a fresh PostgreSQL schema, Hibernate logs unrelated pre-existing schema-generation warnings for MySQL-specific types (for example `MEDIUMBLOB`) and incompatible foreign-key mappings in other modules. Hibernate continues and the Workflow table/repository tests pass. This report does not claim that every unrelated application entity can be recreated cleanly by `ddl-auto=create`; the targeted Workflow migrations and persistence path are the verified scope.

## Cleanup proof

Cleanup command behavior:

- asserted the database name starts with `cretas_workflow_verify_` before `dropdb`;
- queried `pg_database` and received count `0` after the drop;
- stopped the isolated PostgreSQL instance;
- resolved the absolute cluster directory and asserted it is under the temp directory with prefix `cretas_workflow_pg_` before recursive removal;
- removed temporary metadata and local probe files;
- confirmed the cluster directory no longer exists;
- confirmed the normal `postgresql-x64-17` Windows service is still `Running`.

Observed cleanup proof:

```text
CLEANUP_OK database_count=0 cluster_dir_removed=true local_service=Running
```
