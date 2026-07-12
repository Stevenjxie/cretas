# Product Process Workflow Runtime 2A Verification

Date: 2026-07-11

## Scope and result

Runtime 2A is implemented and the focused backend/frontend regression suite is green. The verified scope is explicit manager activation, version-pinned per-batch snapshots, Workflow-first task spawn, immutable existing batches, repeated process-node identity, stable task ports, and exact legacy fallback when no enabled activation exists.

This report does **not** claim reporting/inventory/lineage integration (2B) or RN/F006 operator E2E (2C). AI remains limited to Workflow configuration assistance and has no runtime activation, task-spawn, reporting, or inventory authority.

## PostgreSQL verification harness

Added `ProductProcessWorkflowRuntimePostgresIntegrationTest`, guarded by all of the following:

- `CRETAS_WORKFLOW_PG_VERIFY=true` explicit opt-in;
- URL accepted by `DisposablePostgresTargetGuard`;
- host restricted to `localhost` or `127.0.0.1`;
- database name restricted to prefix `cretas_workflow_verify_`;
- no URL userinfo, query string, fragment, or encoded path.

The test covers:

- actual V55 SQL in an isolated scratch schema, including JSONB snapshot columns, nullable legacy binding, node uniqueness, and port NOT NULL enforcement;
- publish v1 -> activate v1 -> batch A snapshot v1;
- publish v2 without activation -> batch B remains pinned to v1;
- explicit v2 activation -> batch C snapshot v2;
- deactivation -> batch D uses the existing legacy `product_work_process_id` binding and has no runtime instance;
- batch A remains an immutable v1 snapshot after later activation changes;
- repeated `workProcessId` nodes produce separate tasks through `workflowNodeId`;
- repeated materialization is idempotent;
- invalid port persistence rolls back instance, tasks, and ports.

### Environment result

The opt-out run passed with 2/2 tests skipped before Spring datasource creation, proving the default run makes no PostgreSQL connection attempt.

A local PostgreSQL 17 service was present, but no disposable-local authentication credentials were available through the approved local sources. Remote/shared/production credentials were intentionally not used. Therefore the opt-in PostgreSQL scenarios are compiled but were not executed in this run; this is an environment-credential gap, not a green PostgreSQL runtime claim.

## Executed evidence

### Backend

Test compilation:

```text
mvn -DskipTests test-compile
BUILD SUCCESS
```

PostgreSQL opt-out safety:

```text
mvn surefire:test -Dtest=ProductProcessWorkflowRuntimePostgresIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

Focused configuration/runtime regression:

```text
mvn surefire:test -Dtest=ProductProcessWorkflow*Test,WorkProcessTaskServiceImplTest,WorkProcessServiceImplTest,CanvasAIWorkflowConfigTest,GlobalExceptionHandlerOptimisticLockTest
Tests run: 184, Failures: 0, Errors: 0, Skipped: 5
BUILD SUCCESS
```

The five skips are the two explicitly opt-in PostgreSQL classes (three configuration tests plus two runtime tests). The in-memory real-JPA rollback test ran and passed in the same suite.

### Web Admin

```text
npm test -- --run src/views/system/product-processes/workflow/__tests__ src/api/__tests__/request.workflowConflict.spec.ts
Test Files: 8 passed
Tests: 74 passed

npx vue-tsc --noEmit -p tsconfig.app.json
exit 0

npm run build
4377 modules transformed
built in 1m 9s
```

The Web Admin proof is component/API route-mocked proof of separate publish and activation actions, active-version state, deactivation, and stale-response isolation. It is not a live backend browser E2E.

## Runtime 2A boundary

Verified now:

- publishing never activates;
- activation changes only future batch task spawns;
- existing runtime instances remain version-pinned;
- `skipProcessReporting=true` still wins before Workflow materialization;
- absent/disabled activation executes the legacy spawn branch;
- runtime read responses contain execution topology and omit canvas position/viewport;
- manager activation UI is explicit and separate from publishing.

Deferred:

- 2B: port-aware reporting payloads, inventory posting, batch lineage, approvals, and reversal behavior;
- 2C: RN operator screens and headed F006 production-chain E2E;
- opt-in real PostgreSQL execution once disposable-local credentials are supplied.
