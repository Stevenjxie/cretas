# Product Process Workflow Runtime 2A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit Workflow activation and immutable per-batch runtime snapshots that compile published graphs into existing `WorkProcessTask` records without changing the current reporting state machine.

**Architecture:** A published Workflow remains configuration until a manager explicitly activates it for one factory/product. On the next task-spawn operation, a transactional runtime compiler snapshots the active published graph, creates one existing `WorkProcessTask` for every reportable process node, and persists stable task ports; products without an activation continue through `product_work_processes` unchanged. Runtime snapshots preserve the complete graph, including non-reportable process nodes, while task generation preserves the existing `reportingRequired=false` exemption.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring Data JPA, PostgreSQL JSONB/Flyway, Jackson, JUnit 5/Mockito/MockMvc, Vue 3/TypeScript/Vitest/Element Plus.

## Global Constraints

- Publishing never activates a Workflow; activation is a separate explicit manager action.
- Activation affects only task spawns for new batches; existing tasks and runtime instances are immutable.
- A product without an enabled activation uses the existing `product_work_processes` path byte-for-byte.
- Re-publishing does not switch the active version; a manager must explicitly activate the new published row.
- Runtime compilation reuses `WorkProcessTask.Status`; no `WAITING_INPUT`, `READY`, or parallel reporting state machine is introduced.
- The runtime snapshot contains execution fields only and excludes canvas position and viewport.
- `reportingRequired=false` process nodes remain in snapshot topology but do not create `WorkProcessTask` rows.
- The same `workProcessId` may appear in multiple graph nodes; identity and uniqueness use `workflowNodeId`.
- All factory, product, Workflow, batch, task, and port references are factory-scoped.
- AI remains configuration-only and cannot publish, activate, spawn, report, or access runtime instances.
- Phase 2A does not alter `YieldReportRequest`, inventory posting, lineage, approvals, or RN reporting screens.
- Operator UX requirements remain those in `docs/superpowers/specs/2026-07-10-product-process-workflow-runtime-design.md#8-ux-flow-analysisux-flow-门控产出不可删除`; no operator screen is changed in 2A.

---

## File Structure

### New backend units

- `entity/ProductProcessWorkflowActivation.java`: one current activation pointer per factory/product.
- `entity/workflow/ProductionWorkflowInstance.java`: immutable batch execution snapshot header and JSON graph.
- `entity/workflow/WorkflowTaskPort.java`: stable task input/output contract.
- `repository/ProductProcessWorkflowActivationRepository.java`: factory/product activation lookup.
- `repository/workflow/ProductionWorkflowInstanceRepository.java`: factory/batch snapshot lookup.
- `repository/workflow/WorkflowTaskPortRepository.java`: task-port persistence and ordered reads.
- `service/workflow/ProductProcessWorkflowActivationService.java`: activate, deactivate, and read activation state.
- `service/workflow/ProductProcessWorkflowRuntimeCompiler.java`: pure graph-to-runtime compilation.
- `service/workflow/ProductProcessWorkflowRuntimeService.java`: transactional instance/task/port materialization and runtime reads.
- `dto/workflow/ProductProcessWorkflowActivationDTO.java`: activation request/response contract.
- `dto/workflow/ProductionWorkflowRuntimeDTO.java`: instance, task, port, and edge read model.
- `controller/ProductProcessWorkflowRuntimeController.java`: runtime read endpoint.
- `db/flyway/V20261027_55__product_process_workflow_runtime.sql`: activation, instance, port, and task binding schema.

### Modified units

- `ProductProcessWorkflowRepository.java`: exact published Workflow lookup.
- `ProductProcessWorkflowController.java`: activation/deactivation/status endpoints.
- `WorkProcessTask.java`, `WorkProcessTaskDTO.java`, `WorkProcessTaskRepository.java`: nullable legacy template binding plus Workflow identity.
- `WorkProcessTaskServiceImpl.java`: choose Workflow compilation before legacy linear spawn.
- `workflowApi.ts`, `types.ts`, `ProductProcessWorkflowEditor.vue`: explicit activate/deactivate controls and active-version state.

---

### Task 1: Runtime schema and entity contracts

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_55__product_process_workflow_runtime.sql`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductProcessWorkflowActivation.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workflow/ProductionWorkflowInstance.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workflow/WorkflowTaskPort.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workprocess/WorkProcessTask.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/entity/ProductProcessWorkflowRuntimeSchemaContractTest.java`

**Interfaces:**
- Produces: activation row keyed by `(factoryId, productTypeId)`; instance row keyed by `(factoryId, productionBatchId)`; task binding fields `workflowInstanceId` and `workflowNodeId`; ordered task ports.

- [ ] **Step 1: Write the failing schema contract test**

```java
@Test
void migrationDefinesRuntimeOwnershipAndLegacyFallbackContracts() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/flyway/"
            + "V20261027_55__product_process_workflow_runtime.sql"));
    assertTrue(sql.contains("UNIQUE (factory_id, product_type_id)"));
    assertTrue(sql.contains("UNIQUE (factory_id, production_batch_id)"));
    assertTrue(sql.contains("UNIQUE (workflow_instance_id, workflow_node_id)"));
    assertTrue(sql.contains("UNIQUE (task_id, workflow_port_id)"));
    assertTrue(sql.contains("ALTER COLUMN product_work_process_id DROP NOT NULL"));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeSchemaContractTest" test`

Expected: FAIL because the migration and runtime entities do not exist.

- [ ] **Step 3: Add the migration and minimal entities**

The migration must create:

```sql
CREATE TABLE product_process_workflow_activations (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  product_type_id VARCHAR(64) NOT NULL,
  active_workflow_id BIGINT NOT NULL REFERENCES product_process_workflows(id),
  active_definition_version INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  activated_by BIGINT,
  activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  UNIQUE (factory_id, product_type_id)
);

CREATE TABLE production_workflow_instances (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  production_batch_id BIGINT NOT NULL REFERENCES production_batches(id),
  product_type_id VARCHAR(64) NOT NULL,
  workflow_id BIGINT NOT NULL REFERENCES product_process_workflows(id),
  definition_version INTEGER NOT NULL,
  nodes_json JSONB NOT NULL,
  edges_json JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
  compiled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  UNIQUE (factory_id, production_batch_id)
);

ALTER TABLE work_process_tasks
  ALTER COLUMN product_work_process_id DROP NOT NULL,
  ADD COLUMN workflow_instance_id BIGINT REFERENCES production_workflow_instances(id),
  ADD COLUMN workflow_node_id VARCHAR(128);

CREATE UNIQUE INDEX uk_workflow_task_node
  ON work_process_tasks(workflow_instance_id, workflow_node_id)
  WHERE workflow_instance_id IS NOT NULL AND deleted_at IS NULL;
```

`WorkflowTaskPort` must store `taskId`, `workflowInstanceId`, `workflowPortId`, `direction`, `ordinal`, `materialNodeId`, `materialKind`, `skuId`, `unit`, `required`, `conversionMode`, and `conversionExpression` with unique `(taskId, workflowPortId)`.

- [ ] **Step 4: Run schema and entity tests**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeSchemaContractTest,WorkProcessSchemaContractTest" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: add workflow runtime schema" -- `
  backend/java/cretas-api/src/main/resources/db/flyway/V20261027_55__product_process_workflow_runtime.sql `
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductProcessWorkflowActivation.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workflow/ProductionWorkflowInstance.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workflow/WorkflowTaskPort.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workprocess/WorkProcessTask.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/entity/ProductProcessWorkflowRuntimeSchemaContractTest.java
```

### Task 2: Explicit activation service and API

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductProcessWorkflowActivationRepository.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductProcessWorkflowRepository.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/workflow/ProductProcessWorkflowActivationDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowActivationService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/ProductProcessWorkflowActivationServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductProcessWorkflowController.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowActivationServiceTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ProductProcessWorkflowActivationControllerTest.java`

**Interfaces:**
- Produces: `activate(factoryId, workflowId, operatorId)`, `deactivate(factoryId, productTypeId, expectedLockVersion)`, and `get(factoryId, productTypeId)`.
- Consumes: exact `PUBLISHED` Workflow row and existing catalog validator.

- [ ] **Step 1: Write failing activation tests**

```java
@Test
void activateRequiresExactPublishedOwnedWorkflowAndIsIdempotent() {
    when(workflowRepository.findByIdAndFactoryId(44L, "F006"))
            .thenReturn(Optional.of(publishedWorkflow(44L, "F006", "PT-PIG", 3)));
    ActivationDTO first = service.activate("F006", 44L, 7001L);
    ActivationDTO second = service.activate("F006", 44L, 7001L);
    assertEquals(first.getId(), second.getId());
    assertEquals(3, second.getActiveDefinitionVersion());
    verify(repository, times(1)).saveAndFlush(any());
}
```

Also test draft rejection, cross-factory rejection, new published version not auto-switching, stale deactivation 409, and deactivation preserving existing runtime instances.

- [ ] **Step 2: Run activation tests and verify RED**

Run: `mvn "-Dtest=ProductProcessWorkflowActivationServiceTest,ProductProcessWorkflowActivationControllerTest" test`

Expected: FAIL because activation contracts do not exist.

- [ ] **Step 3: Implement activation service and controller routes**

Add endpoints under the existing controller:

```java
@PutMapping("/{workflowId}/activation")
public ApiResponse<ProductProcessWorkflowActivationDTO> activate(
        @PathVariable String factoryId,
        @PathVariable Long workflowId,
        HttpServletRequest request) { ... }

@DeleteMapping("/activation")
public ApiResponse<ProductProcessWorkflowActivationDTO> deactivate(
        @PathVariable String factoryId,
        @RequestParam String productTypeId,
        @RequestParam Long lockVersion) { ... }

@GetMapping("/{productTypeId}/activation")
public ApiResponse<ProductProcessWorkflowActivationDTO> getActivation(...) { ... }
```

Use the same production write permission/role gates as publish. Return stable codes `WORKFLOW_NOT_PUBLISHED`, `WORKFLOW_ACTIVATION_CONFLICT`, and `WORKFLOW_ACTIVATION_PRODUCT_MISMATCH` with action hints.

- [ ] **Step 4: Run activation tests**

Run: `mvn "-Dtest=ProductProcessWorkflowActivationServiceTest,ProductProcessWorkflowActivationControllerTest" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: activate published product workflows" -- `
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductProcessWorkflowActivationRepository.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductProcessWorkflowRepository.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/workflow/ProductProcessWorkflowActivationDTO.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowActivationService.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/ProductProcessWorkflowActivationServiceImpl.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductProcessWorkflowController.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowActivationServiceTest.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ProductProcessWorkflowActivationControllerTest.java
```

### Task 3: Pure runtime compiler

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompiler.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/CompiledProductProcessWorkflow.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompilerTest.java`

**Interfaces:**
- Consumes: `ProductProcessWorkflowDTO` already validated for publish.
- Produces: `CompiledProductProcessWorkflow(nodesJson, edgesJson, processTasks, ports)` where each task carries `workflowNodeId`, `workProcessId`, stable topological `processOrder`, `plannedUnit`, `estimatedMinutes`, and `reportingRequired`.

- [ ] **Step 1: Write compiler RED tests**

```java
@Test
void compilesRepeatedProcessBranchesAndPortsWithoutCanvasFields() {
    CompiledProductProcessWorkflow compiled = compiler.compile(repeatedBranchedWorkflow());
    assertEquals(List.of("cook-a", "cook-b", "pack"),
            compiled.reportableTasks().stream().map(CompiledTask::workflowNodeId).toList());
    assertEquals(2, compiled.reportableTasks().stream()
            .filter(t -> t.workProcessId().equals("COOK")).count());
    assertFalse(compiled.nodesJson().contains("position"));
    assertEquals(List.of("in-raw", "out-cooked"),
            compiled.portsFor("cook-a").stream().map(CompiledPort::workflowPortId).toList());
}
```

Cover linear, split, merge, repeated `workProcessId`, multi-input/multi-output, deterministic same-layer node-id ordering, non-reportable node retained in JSON but absent from `reportableTasks`, missing material edge rejection, and cycle rejection.

- [ ] **Step 2: Run compiler test and verify RED**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeCompilerTest" test`

Expected: FAIL because compiler types do not exist.

- [ ] **Step 3: Implement deterministic compilation**

Use Kahn topological ordering with node ID as the same-layer tie-breaker. Strip `position` and `viewport`. Derive each port from the process node's declared port plus its bound material node; never infer port identity from SKU. Preserve two ports that reference the same SKU.

- [ ] **Step 4: Run compiler and existing validator tests**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeCompilerTest,ProductProcessWorkflowValidatorTest,ProductProcessWorkflowCatalogValidatorTest" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: compile product workflow runtime snapshots" -- `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompiler.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/CompiledProductProcessWorkflow.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompilerTest.java
```

### Task 4: Transactional snapshot, task, and port materialization

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workflow/ProductionWorkflowInstanceRepository.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workflow/WorkflowTaskPortRepository.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/workflow/ProductionWorkflowRuntimeDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/ProductProcessWorkflowRuntimeServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/WorkProcessTaskDTO.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workprocess/WorkProcessTaskRepository.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeServiceTest.java`

**Interfaces:**
- Produces: `Optional<List<WorkProcessTaskDTO>> materializeIfActive(factoryId, batchId, productTypeId)` and `getRuntime(factoryId, batchId)`.
- Consumes: enabled activation, exact published Workflow, compiler, `WorkProcessTaskRepository`, and port repositories.

- [ ] **Step 1: Write failing transactional materialization tests**

```java
@Test
void materializesOneImmutableInstanceAndTasksByWorkflowNodeIdentity() {
    Optional<List<WorkProcessTaskDTO>> result = service.materializeIfActive("F006", 901L, "PT-PIG");
    assertTrue(result.isPresent());
    assertEquals(List.of("trim-1", "trim-2", "pack"),
            savedTasks.stream().map(WorkProcessTask::getWorkflowNodeId).toList());
    assertTrue(savedTasks.stream().allMatch(t -> t.getProductWorkProcessId() == null));
    assertEquals(6, savedPorts.size());
}
```

Also prove no activation returns `Optional.empty()`, repeated call returns existing tasks, Workflow changed after instance creation does not mutate snapshot, cross-factory batch is rejected, and a port save failure rolls back instance/tasks/ports.

- [ ] **Step 2: Run runtime service test and verify RED**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeServiceTest" test`

Expected: FAIL because repositories/service do not exist.

- [ ] **Step 3: Implement one-transaction materialization**

Annotate `materializeIfActive` with `@Transactional`. First return existing instance/tasks if present. Otherwise load activation and published Workflow, compile, persist instance, persist reportable tasks with `productWorkProcessId=null`, persist ports for those tasks, then return existing DTOs enriched with Workflow IDs. Catch no exception inside the transaction.

- [ ] **Step 4: Run service tests**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimeServiceTest,ProductProcessWorkflowRuntimeCompilerTest" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: materialize workflow batch snapshots" -- `
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workflow/ProductionWorkflowInstanceRepository.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workflow/WorkflowTaskPortRepository.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/workflow/ProductionWorkflowRuntimeDTO.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeService.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/ProductProcessWorkflowRuntimeServiceImpl.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/WorkProcessTaskDTO.java `
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workprocess/WorkProcessTaskRepository.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeServiceTest.java
```

### Task 5: Integrate Workflow-first spawn with exact legacy fallback

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workprocess/impl/WorkProcessTaskServiceImpl.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workprocess/impl/WorkProcessTaskServiceImplTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowSpawnCompatibilityTest.java`

**Interfaces:**
- Consumes: `runtimeService.materializeIfActive(...)`.
- Preserves: both existing `spawnTasks` overloads, two-point skip behavior, assignee behavior, and no-config fallback.

- [ ] **Step 1: Write failing compatibility tests**

```java
@Test
void activeWorkflowWinsButInactiveProductExecutesOriginalLegacyBranch() {
    when(runtimeService.materializeIfActive("F006", 901L, "PT-PIG"))
            .thenReturn(Optional.of(workflowTasks()));
    assertEquals("wf-node-trim", service.spawnTasks("F006", 901L, "PT-PIG").get(0).getWorkflowNodeId());

    when(runtimeService.materializeIfActive("F006", 902L, "PT-CHICKEN"))
            .thenReturn(Optional.empty());
    assertEquals(legacyTask(), service.spawnTasks("F006", 902L, "PT-CHICKEN").get(0));
}
```

Cover explicit `skipProcessReporting=true` continuing to create the two sentinel tasks even if a Workflow activation exists, already-spawned batch idempotency, repeated same work process nodes, and disabled activation falling back to legacy.

- [ ] **Step 2: Run compatibility tests and verify RED**

Run: `mvn "-Dtest=ProductProcessWorkflowSpawnCompatibilityTest,WorkProcessTaskServiceImplTest" test`

Expected: FAIL because spawn does not consult the runtime service.

- [ ] **Step 3: Add the narrow branch**

The ordering inside the main overload must be:

```java
if (alreadySpawned(...)) return existingTasks(...);
if (Boolean.TRUE.equals(skipProcessReporting)) return spawnBatchLevelTwoPointTasks(...);
Optional<List<WorkProcessTaskDTO>> workflow =
        workflowRuntimeService.materializeIfActive(factoryId, batchId, productTypeId);
if (workflow.isPresent()) return workflow.get();
return spawnFromLegacyProductWorkProcesses(...);
```

Do not change the private legacy construction logic beyond extracting it into a named method if required.

- [ ] **Step 4: Run spawn and regression tests**

Run: `mvn "-Dtest=ProductProcessWorkflowSpawnCompatibilityTest,WorkProcessTaskServiceImplTest,YieldReportServiceImplTest" test`

Expected: PASS with legacy assertions unchanged.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: spawn active workflow tasks with legacy fallback" -- `
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workprocess/impl/WorkProcessTaskServiceImpl.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workprocess/impl/WorkProcessTaskServiceImplTest.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowSpawnCompatibilityTest.java
```

### Task 6: Runtime read endpoint and manager activation UI

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductProcessWorkflowRuntimeController.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ProductProcessWorkflowRuntimeControllerTest.java`
- Modify: `web-admin/src/views/system/product-processes/workflow/types.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/workflowApi.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Create: `web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.activation.spec.ts`

**Interfaces:**
- Backend produces: `GET /api/mobile/{factoryId}/production-batches/{batchId}/workflow-runtime`.
- Web Admin consumes: activation GET/PUT/DELETE endpoints; it never activates automatically after publish.

- [ ] **Step 1: Write failing controller and UI tests**

```ts
it('publishing does not activate and activation requires a second confirmation', async () => {
  publishApi.mockResolvedValue({ success: true, data: publishedDefinition });
  activateApi.mockResolvedValue({ success: true, data: activeV3 });
  const wrapper = mountEditor();
  await wrapper.get('[data-testid="publish-workflow"]').trigger('click');
  expect(activateApi).not.toHaveBeenCalled();
  await wrapper.get('[data-testid="activate-workflow"]').trigger('click');
  expect(activateApi).toHaveBeenCalledWith('F006', publishedDefinition.id);
});
```

Backend tests must prove factory isolation, no-instance `data=null`, ordered tasks/ports, and no canvas `position`/`viewport` fields in runtime responses.

- [ ] **Step 2: Run tests and verify RED**

Run backend: `mvn "-Dtest=ProductProcessWorkflowRuntimeControllerTest" test`

Run frontend: `npm test -- --run src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.activation.spec.ts`

Expected: FAIL because routes and UI controls do not exist.

- [ ] **Step 3: Implement the read endpoint and explicit controls**

The activation button must be disabled for drafts and display `启用版本 v{definitionVersion}`. Confirmation text must state: `只影响之后新建的生产批次；正在生产的批次不会变化。` Deactivation text must state: `停用后新批次恢复旧工序配置；已有批次继续当前 Workflow。` Do not combine publish and activation into one action.

- [ ] **Step 4: Run controller, frontend, and type tests**

Run backend: `mvn "-Dtest=ProductProcessWorkflowRuntimeControllerTest,ProductProcessWorkflowActivationControllerTest" test`

Run frontend: `npm test -- --run src/views/system/product-processes/workflow/__tests__`

Run type check: `npx vue-tsc --noEmit -p tsconfig.app.json`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git commit --only -m "feat: expose workflow runtime activation controls" -- `
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductProcessWorkflowRuntimeController.java `
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ProductProcessWorkflowRuntimeControllerTest.java `
  web-admin/src/views/system/product-processes/workflow/types.ts `
  web-admin/src/views/system/product-processes/workflow/workflowApi.ts `
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue `
  web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.activation.spec.ts
```

### Task 7: Real PostgreSQL and compatibility verification

**Files:**
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductProcessWorkflowRuntimePostgresIntegrationTest.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/DisposablePostgresTargetGuard.java` only if a second explicit opt-in variable is required.
- Create: `docs/qa-audits/2026-07-11-product-process-workflow-runtime-2a-verification.md`

**Interfaces:**
- Verifies actual Flyway V55 SQL and real JPA repositories/services/controllers against a guarded disposable PostgreSQL database.

- [ ] **Step 1: Add opt-in real PostgreSQL scenarios**

The integration test must cover:

```java
@Test
void activationNewBatchSnapshotAndLegacyFallbackRoundTrip() {
    // publish v1 -> activate v1 -> spawn batch A -> assert instance v1
    // publish v2 without activation -> spawn batch B -> assert instance v1
    // activate v2 -> spawn batch C -> assert instance v2
    // disable -> spawn batch D -> assert no instance and legacy task binding
    // re-read batch A -> assert immutable v1 nodes, edges, tasks, and ports
}
```

Also assert repeated process nodes produce separate tasks, a second materialization is idempotent, and a simulated port constraint failure rolls back the instance and tasks.

- [ ] **Step 2: Run without opt-in**

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimePostgresIntegrationTest" test`

Expected: all tests skipped, zero connections attempted.

- [ ] **Step 3: Run against a guarded disposable local PostgreSQL instance**

Use only a URL accepted by `DisposablePostgresTargetGuard`, with database name prefix `cretas_workflow_verify_`.

Run: `mvn "-Dtest=ProductProcessWorkflowRuntimePostgresIntegrationTest" test`

Expected: PASS with activation, snapshot, task, port, version pinning, deactivation fallback, and rollback assertions.

- [ ] **Step 4: Run final configuration and runtime regression**

Backend:

```powershell
mvn "-Dtest=ProductProcessWorkflow*Test,WorkProcessTaskServiceImplTest,WorkProcessServiceImplTest,CanvasAIWorkflowConfigTest,GlobalExceptionHandlerOptimisticLockTest" test
```

Frontend:

```powershell
npm test -- --run src/views/system/product-processes/workflow/__tests__ src/api/__tests__/request.workflowConflict.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
npm run build
```

Expected: all focused tests, type check, and production build pass.

- [ ] **Step 5: Write the verification report and commit**

The report must distinguish: 2A activation/task-snapshot proof; existing route-mocked canvas proof; and deferred 2B reporting/inventory/lineage plus 2C RN/F006 E2E.

```powershell
git commit --only -m "test: verify workflow runtime activation on PostgreSQL" -- `
  backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductProcessWorkflowRuntimePostgresIntegrationTest.java `
  docs/qa-audits/2026-07-11-product-process-workflow-runtime-2a-verification.md
```

---

## Self-Review Results

- **Spec coverage:** 2A activation, version pinning, immutable snapshot, repeated processes, branches/merges, task reuse, explicit manager action, and legacy fallback each map to a task above.
- **Scope boundary:** Reporting DTOs, inventory, lineage, approval, RN rendering, and F006 production-chain E2E are intentionally excluded and remain 2B/2C.
- **Type consistency:** `workflowInstanceId`, `workflowNodeId`, `workflowPortId`, `activeDefinitionVersion`, and `materializeIfActive` use the same names throughout all tasks.
- **No unresolved markers:** Every code-changing task includes exact files, RED/GREEN commands, expected results, and a scoped commit.
