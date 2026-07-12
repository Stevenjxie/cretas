# Product Process Workflow Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Workflow configuration plane production-correct: a new graph starts with one raw-material Cell, selecting a process atomically creates the process and an automatically typed output Cell, multi-output has two discoverable entry points, and AI can only propose reviewable Workflow graph patches.

**Architecture:** Add an explicit default output material kind to WorkProcess master data, copy that value into newly created Workflow nodes, and validate the copied value again on publish. Keep graph mutations in pure TypeScript helpers so Vue interactions are thin and testable. Replace the legacy linear-process AI route with a dedicated preview-only Workflow patch route; no runtime production, reporting, inventory, approval, save, publish, activation, or SKU creation action is exposed to AI.

**Tech Stack:** Java 21, Spring Boot 3.2.12, JPA/Hibernate 6, PostgreSQL/Flyway, JUnit 5/Mockito, Vue 3, TypeScript 5.9, Element Plus, Vue Flow 1.48, Vitest 4, Vue Test Utils.

## Global Constraints

- New WorkProcess default output kind is exactly `SEMI_FINISHED` or `FINISHED_GOOD`; database default is `SEMI_FINISHED`.
- A new empty Workflow contains one raw-material Cell without requiring the clerk to create it.
- Selecting a WorkProcess creates the process Cell, one output Cell, and both edges in one undoable graph mutation.
- A `FINISHED_GOOD` process automatically binds the selected product SKU; a `SEMI_FINISHED` process leaves its output SKU ready for existing selection or inline creation.
- Neither the Workflow clerk nor the reporting operator can manually select half-finished versus finished type.
- The process Cell keeps the inline `＋ 添加产出`; a selected or hovered process also shows a visually distinct edge `＋`; both invoke the same action.
- AI receives only the current Workflow definition and selected Cell context, returns only whitelisted `WorkflowPatch[]`, and cannot save, publish, activate, create SKU, or touch production/reporting/inventory/approval data.
- Existing `INPUT → SEGMENT → OUTPUT` reporting behavior is out of scope for this configuration-plane plan and must remain unchanged.
- No Preview/batch simulation feature is added.
- No deployment, push, merge, or production database change is performed by this plan.

---

## File Structure

### Backend

- `entity/enums/WorkProcessOutputMaterialKind.java`: strong enum for process-master output classification.
- `entity/WorkProcess.java`, `dto/WorkProcessDTO.java`, `service/impl/WorkProcessServiceImpl.java`: persist and expose the classification.
- `db/flyway/V20261027_54__work_process_default_output_material_kind.sql`: non-null schema column and check constraint.
- `service/validation/ProductProcessWorkflowCatalogValidator.java`: factory-scoped publish validation against WorkProcess and ProductType catalogs.
- `ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java`: preview-only patch sanitizer.
- `controller/CanvasAIController.java`: dedicated `product_process_workflow_config` route that calls the LLM only for patch generation and then sanitizes patches.

### Web Admin

- `views/system/work-processes/workProcessOutputKind.ts`: pure options/default/visibility helpers.
- `views/system/work-processes/index.vue`: master-data selector for default output kind.
- `views/system/product-processes/workflow/workflowModel.ts`: pure branch construction and existing patch application.
- `views/system/product-processes/workflow/WorkflowProcessNode.vue`: remove type select and add hybrid output-add gesture.
- `views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`: consume the process master default, atomically build branches, and apply Workflow AI patches.
- `api/processProduction.ts`, `workflow/types.ts`: typed contracts.

---

### Task 1: Add WorkProcess Default Output Material Kind

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/WorkProcessOutputMaterialKind.java`
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_54__work_process_default_output_material_kind.sql`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/WorkProcess.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/WorkProcessDTO.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/WorkProcessServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/WorkProcessServiceImplTest.java`

**Interfaces:**
- Produces: `WorkProcessOutputMaterialKind { SEMI_FINISHED, FINISHED_GOOD }`.
- Produces: `WorkProcessDTO.defaultOutputMaterialKind` and `WorkProcess.defaultOutputMaterialKind`.
- Compatibility: missing create payload resolves to `SEMI_FINISHED`; update payload `null` leaves the stored value unchanged.

- [ ] **Step 1: Write failing create/update mapping tests**

Add tests that assert the default and explicit values survive service mapping:

```java
@Test
void create_defaultsOutputMaterialKindToSemiFinished() {
    WorkProcessDTO request = validRequest();
    request.setDefaultOutputMaterialKind(null);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    WorkProcessDTO result = service.create(FACTORY_ID, request);

    assertEquals(WorkProcessOutputMaterialKind.SEMI_FINISHED,
            result.getDefaultOutputMaterialKind());
}

@Test
void create_keepsExplicitFinishedGoodOutputKind() {
    WorkProcessDTO request = validRequest();
    request.setDefaultOutputMaterialKind(WorkProcessOutputMaterialKind.FINISHED_GOOD);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    WorkProcessDTO result = service.create(FACTORY_ID, request);

    assertEquals(WorkProcessOutputMaterialKind.FINISHED_GOOD,
            result.getDefaultOutputMaterialKind());
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `backend/java/cretas-api`:

```powershell
mvn "-Dtest=WorkProcessServiceImplTest" test
```

Expected: test compilation fails because `WorkProcessOutputMaterialKind` and `defaultOutputMaterialKind` do not exist.

- [ ] **Step 3: Add the enum, migration, entity, DTO, and service mapping**

Use this enum:

```java
public enum WorkProcessOutputMaterialKind {
    SEMI_FINISHED,
    FINISHED_GOOD
}
```

Use this migration:

```sql
ALTER TABLE work_processes
    ADD COLUMN IF NOT EXISTS default_output_material_kind VARCHAR(32);

UPDATE work_processes
SET default_output_material_kind = 'SEMI_FINISHED'
WHERE default_output_material_kind IS NULL;

ALTER TABLE work_processes
    ALTER COLUMN default_output_material_kind SET DEFAULT 'SEMI_FINISHED',
    ALTER COLUMN default_output_material_kind SET NOT NULL;

ALTER TABLE work_processes
    DROP CONSTRAINT IF EXISTS chk_work_process_output_material_kind;
ALTER TABLE work_processes
    ADD CONSTRAINT chk_work_process_output_material_kind
    CHECK (default_output_material_kind IN ('SEMI_FINISHED', 'FINISHED_GOOD'));
```

Map the entity as an enum string and use this create fallback:

```java
.defaultOutputMaterialKind(dto.getDefaultOutputMaterialKind() != null
        ? dto.getDefaultOutputMaterialKind()
        : WorkProcessOutputMaterialKind.SEMI_FINISHED)
```

In update, only call `entity.setDefaultOutputMaterialKind(...)` when the DTO value is non-null. Add the field to `toDTO`.

- [ ] **Step 4: Run the focused test and verify GREEN**

```powershell
mvn "-Dtest=WorkProcessServiceImplTest" test
```

Expected: all `WorkProcessServiceImplTest` tests pass.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/WorkProcessOutputMaterialKind.java backend/java/cretas-api/src/main/java/com/cretas/aims/entity/WorkProcess.java backend/java/cretas-api/src/main/java/com/cretas/aims/dto/WorkProcessDTO.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/WorkProcessServiceImpl.java backend/java/cretas-api/src/main/resources/db/flyway/V20261027_54__work_process_default_output_material_kind.sql backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/WorkProcessServiceImplTest.java
git commit -m "feat: classify work process default outputs"
```

### Task 2: Configure the Default in WorkProcess Admin

**Files:**
- Create: `web-admin/src/views/system/work-processes/workProcessOutputKind.ts`
- Create: `web-admin/src/views/system/work-processes/__tests__/workProcessOutputKind.spec.ts`
- Modify: `web-admin/src/api/processProduction.ts`
- Modify: `web-admin/src/views/system/work-processes/index.vue`

**Interfaces:**
- Consumes: `WorkProcessOutputMaterialKind` values serialized as strings.
- Produces: `WORK_PROCESS_OUTPUT_KIND_OPTIONS`, `normalizeOutputMaterialKind(value)`, and `usesSemiFinishedCode(kind)`.

- [ ] **Step 1: Write failing helper tests**

```ts
import { describe, expect, it } from 'vitest';
import {
  normalizeOutputMaterialKind,
  usesSemiFinishedCode,
} from '../workProcessOutputKind';

describe('work process output kind', () => {
  it('defaults missing legacy values to SEMI_FINISHED', () => {
    expect(normalizeOutputMaterialKind(undefined)).toBe('SEMI_FINISHED');
  });

  it('only keeps semi code controls for semi-finished output', () => {
    expect(usesSemiFinishedCode('SEMI_FINISHED')).toBe(true);
    expect(usesSemiFinishedCode('FINISHED_GOOD')).toBe(false);
  });
});
```

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run from `web-admin`:

```powershell
npm test -- --run src/views/system/work-processes/__tests__/workProcessOutputKind.spec.ts
```

Expected: module resolution fails for `workProcessOutputKind`.

- [ ] **Step 3: Implement typed helpers and the form selector**

Add to `WorkProcessItem`:

```ts
export type WorkProcessOutputMaterialKind = 'SEMI_FINISHED' | 'FINISHED_GOOD';

defaultOutputMaterialKind: WorkProcessOutputMaterialKind;
semiFinishedOutputCode?: string | null;
```

Helper implementation:

```ts
export const WORK_PROCESS_OUTPUT_KIND_OPTIONS = [
  { label: '半成品工序', value: 'SEMI_FINISHED' },
  { label: '成品出品工序', value: 'FINISHED_GOOD' },
] as const;

export function normalizeOutputMaterialKind(
  value?: string | null,
): WorkProcessOutputMaterialKind {
  return value === 'FINISHED_GOOD' ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
}

export function usesSemiFinishedCode(kind: WorkProcessOutputMaterialKind): boolean {
  return kind === 'SEMI_FINISHED';
}
```

Replace the ambiguous “本工序产出半成品” switch with an `el-select` labelled “默认产出类型”. When `FINISHED_GOOD` is selected, set `semiFinishedOutputCode = null`; when `SEMI_FINISHED` is selected, keep the existing optional code flow. Initialize new and edit forms through `normalizeOutputMaterialKind`.

- [ ] **Step 4: Run test, type-check, and build**

```powershell
npm test -- --run src/views/system/work-processes/__tests__/workProcessOutputKind.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
npm run build
```

Expected: helper tests pass, Vue type-check exits 0, and Vite build exits 0.

- [ ] **Step 5: Commit Task 2**

```powershell
git add -- web-admin/src/api/processProduction.ts web-admin/src/views/system/work-processes/index.vue web-admin/src/views/system/work-processes/workProcessOutputKind.ts web-admin/src/views/system/work-processes/__tests__/workProcessOutputKind.spec.ts
git commit -m "feat: configure work process output kind"
```

### Task 3: Build Process and Output Cells Atomically

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/types.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/workflowModel.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`

**Interfaces:**
- Consumes: `WorkProcessItem.defaultOutputMaterialKind`.
- Produces: `createProcessBranch(input): { processNode, outputNode, edges }`.
- The returned finished output binds `productTypeId`; the returned semi-finished output has an empty SKU.

- [ ] **Step 1: Write failing branch-construction tests**

```ts
it('creates a green semi-finished output for a normal process', () => {
  const branch = createProcessBranch({
    source: rawNode,
    workProcess: processOption({ defaultOutputMaterialKind: 'SEMI_FINISHED' }),
    productTypeId: 'PT-PIG',
    productName: '五香去骨猪蹄',
    timestamp: 100,
  });
  expect(branch.outputNode.kind).toBe('SEMI_FINISHED');
  expect(branch.outputNode.data.skuId).toBe('');
});

it('creates a terminal purple finished-good output for an output process', () => {
  const branch = createProcessBranch({
    source: semiNode,
    workProcess: processOption({ defaultOutputMaterialKind: 'FINISHED_GOOD' }),
    productTypeId: 'PT-PIG-400',
    productName: '五香去骨猪蹄 400g',
    timestamp: 101,
  });
  expect(branch.outputNode.kind).toBe('FINISHED_GOOD');
  expect(branch.outputNode.data.skuId).toBe('PT-PIG-400');
  expect(branch.processNode.data.ports[1].materialKind).toBe('FINISHED_GOOD');
});
```

- [ ] **Step 2: Run workflow model tests and verify RED**

```powershell
npm test -- --run src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
```

Expected: `createProcessBranch` is not exported.

- [ ] **Step 3: Implement `createProcessBranch` and use it in the editor**

Define the input contract explicitly:

```ts
export interface ProcessBranchInput {
  source: ProductProcessWorkflowNode;
  workProcess: {
    id: string;
    processName: string;
    unit: string;
    outputUnit?: string | null;
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD';
  };
  productTypeId: string;
  productName: string;
  timestamp: number;
}
```

Return plain Workflow nodes and edges. For `FINISHED_GOOD`, use the current product name/id/code and `bound: true`; for `SEMI_FINISHED`, use `${processName}后半成品`, empty SKU, and `bound: false`. Replace the inline construction in `confirmAddProcess()` with this helper and push all returned objects inside the existing single `mutate()` call.

Keep `createWorkflowFromLegacy` behavior for existing linear configurations; only newly selected processes use the master default.

- [ ] **Step 4: Run model tests and type-check**

```powershell
npm test -- --run src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
```

Expected: model tests pass and type-check exits 0.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- web-admin/src/views/system/product-processes/workflow/types.ts web-admin/src/views/system/product-processes/workflow/workflowModel.ts web-admin/src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue
git commit -m "feat: derive workflow outputs from process defaults"
```

### Task 4: Add the Hybrid Multi-Output Gesture and Remove Manual Type Selection

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Create: `web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts`

**Interfaces:**
- Consumes: existing `addOutput` event.
- Removes: `changeOutputKind` event and the manual kind `el-select`.
- Produces: `[data-testid="add-output-inline"]` and `[data-testid="add-output-edge"]`, both emitting `addOutput` once.

- [ ] **Step 1: Write failing component interaction tests**

Mount `WorkflowProcessNode` with Element Plus/Vue Flow components stubbed and assert both controls share one event:

```ts
it.each(['add-output-inline', 'add-output-edge'])(
  '%s emits the same addOutput action',
  async (testId) => {
    const wrapper = mount(WorkflowProcessNode, {
      props: { data: processData, selected: true, canWrite: true, skuOptions: [] },
      global: {
        plugins: [ElementPlus],
        stubs: { Handle: true },
      },
    });
    await wrapper.get(`[data-testid="${testId}"]`).trigger('click');
    expect(wrapper.emitted('addOutput')).toHaveLength(1);
  },
);
```

Add a separate assertion that no select with `data-testid="output-kind-select"` exists.

- [ ] **Step 2: Run the component test and verify RED**

```powershell
npm test -- --run src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts
```

Expected: the edge add control is missing and the old kind select still exists.

- [ ] **Step 3: Implement the two controls and one action**

Keep the current inline button, give it `data-testid="add-output-inline"`, and add this edge control inside the process root:

```vue
<button
  v-if="canWrite && selected"
  type="button"
  class="edge-output-add nodrag"
  data-testid="add-output-edge"
  aria-label="添加一个产出 Cell"
  @click.stop="emit('addOutput')"
>＋</button>
```

CSS requirements:

```css
.edge-output-add {
  position: absolute;
  top: 50%;
  right: -14px;
  width: 28px;
  height: 28px;
  border: 2px solid #1b65a8;
  border-radius: 50%;
  background: #fff;
  color: #1b65a8;
}
```

Set `.process-node { position: relative; }`. Remove the manual material-kind `el-select`, `changeOutputKind` emit declaration, editor listener, and `changeOutputKind()` method. Keep `addOutputToProcess()` as the single mutation used by both controls, including its existing 160px vertical offset and 16px snap.

- [ ] **Step 4: Run component/model tests and type-check**

```powershell
npm test -- --run src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
```

Expected: tests pass, both controls emit once, and no TypeScript reference to `changeOutputKind` remains.

- [ ] **Step 5: Commit Task 4**

```powershell
git add -- web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts
git commit -m "feat: add hybrid workflow output gesture"
```

### Task 5: Validate Process, Port, Cell, and SKU Classification on Publish

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidator.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java`

**Interfaces:**
- Consumes: factory-scoped `WorkProcessRepository` and `ProductTypeRepository`.
- Produces: `validateForPublish(String factoryId, String productTypeId, ProductProcessWorkflowDTO definition)`.
- Throws: `PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH` with a Cell-specific message and action hint.

- [ ] **Step 1: Write failing catalog validation tests**

Cover these exact cases:

```java
@Test
void rejectsFinishedProcessWhosePrimaryOutputIsSemiFinished() {
    when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "WP-PACK"))
            .thenReturn(Optional.of(workProcess(FINISHED_GOOD)));

    BusinessException error = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                    workflowWithPrimaryOutput("WP-PACK", "SEMI_FINISHED", "SFI-1")));

    assertEquals("PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH", error.getErrorCode());
}

@Test
void acceptsFinishedProcessBoundToCurrentProductSku() {
    when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "WP-PACK"))
            .thenReturn(Optional.of(workProcess(FINISHED_GOOD)));
    when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
            .thenReturn(Optional.of(product(PRODUCT_ID, ProductCategory.FINISHED_PRODUCT)));

    validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
            workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID));
}
```

Also reject: missing WorkProcess, port `materialKind` different from its material node kind, and `FINISHED_GOOD` node bound to a `SEMI_FINISHED` ProductType.

- [ ] **Step 2: Run validator tests and verify RED**

```powershell
mvn "-Dtest=ProductProcessWorkflowCatalogValidatorTest" test
```

Expected: class does not exist.

- [ ] **Step 3: Implement catalog validation and call it only during publish**

Rules:

```java
// primary output = OUTPUT port with the smallest ordinal
// primary material node kind must equal WorkProcess.defaultOutputMaterialKind
// every output port.materialKind must equal its material node.kind
// SEMI_FINISHED node SKU must have ProductCategory.SEMI_FINISHED
// FINISHED_GOOD node SKU must not have ProductCategory.SEMI_FINISHED
// FINISHED_GOOD output SKU may differ from the Workflow productTypeId so one
// workflow can produce multiple finished-product versions; validate category
// consistency, factory ownership, and port/node identity instead of SKU equality
```

Batch-load WorkProcesses with `findByFactoryIdAndIdIn` to avoid N+1, then load only the distinct output SKU ids with `findByIdIn` and reject any returned ProductType whose `factoryId` differs. Call the new validator after the existing structural `validateForPublish` and before changing draft status.

- [ ] **Step 4: Run focused service and validator tests**

```powershell
mvn "-Dtest=ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest" test
```

Expected: both test classes pass; existing structural graph tests remain green.

- [ ] **Step 5: Commit Task 5**

```powershell
git add -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidator.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java
git commit -m "feat: validate workflow output catalog bindings"
```

### Task 6: Replace Legacy Linear AI with a Workflow-Only Patch Assistant

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/CanvasAIController.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/CanvasAIWorkflowConfigTest.java`
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts`

**Interfaces:**
- New module code: `product_process_workflow_config`.
- New tool name: `canvas_product_process_workflow_config`.
- Input: `{ message, definition, selectedNodeId, patches }`.
- Output diff: `{ type: 'PRODUCT_PROCESS_WORKFLOW_PATCH', params: { patches: WorkflowPatch[] } }`.
- Preview only: `execute` always fails with `WORKFLOW_AI_PREVIEW_ONLY`.

- [ ] **Step 1: Write failing backend safety tests**

```java
@Test
void previewKeepsOnlyWhitelistedWorkflowPatchOperations() {
    String arguments = objectMapper.writeValueAsString(Map.of(
        "patches", List.of(
            Map.of("op", "SET_NODE_FIELD", "nodeId", "p1",
                "path", "conversionRule.mode", "value", "ACTUAL_WEIGHT"),
            Map.of("op", "ACTIVATE_WORKFLOW", "workflowId", 9))));
    ToolCall call = ToolCall.of("preview-1", tool.getToolName(), arguments);

    Map<String, Object> envelope = objectMapper.readValue(
        tool.preview(call, Map.of("factoryId", "F006")), new TypeReference<>() {});
    Map<String, Object> data = (Map<String, Object>) envelope.get("data");

    assertTrue((Boolean) envelope.get("success"));
    assertEquals(1, ((List<?>) data.get("patches")).size());
}

@Test
void executeIsAlwaysRejected() {
    ToolCall call = ToolCall.of("execute-1", tool.getToolName(),
        objectMapper.writeValueAsString(Map.of("patches", List.of())));

    Map<String, Object> envelope = objectMapper.readValue(
        tool.execute(call, Map.of("factoryId", "F006")), new TypeReference<>() {});

    assertFalse((Boolean) envelope.get("success"));
    assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
}
```

These tests must call the real public `ToolExecutor.preview(...)` and
`ToolExecutor.execute(...)` entry points. Do not add production-only helper methods
such as `previewData` or `executeData` merely to make tests easier.
The execute rejection must preserve the semantic `errorCode` in the public JSON
envelope; a generic human-readable error string is not sufficient for this safety boundary.

Controller test: send `moduleCode=product_process_workflow_config` and verify `ToolExecutor.preview(...)` is called, `execute(...)` is never called, and `AIResponse.applied` is false.

- [ ] **Step 2: Run focused backend AI tests and verify RED**

```powershell
mvn "-Dtest=ProductProcessWorkflowConfigToolTest,CanvasAIWorkflowConfigTest" test
```

Expected: tool and controller route do not exist.

- [ ] **Step 3: Implement the preview-only tool and dedicated controller branch**

Whitelist only:

```java
Set.of("UPSERT_NODE", "REMOVE_NODE", "UPSERT_EDGE", "REMOVE_EDGE", "SET_NODE_FIELD")
```

For `SET_NODE_FIELD`, whitelist roots:

```java
Set.of("name", "skuId", "skuCode", "specification", "ports",
       "conversionRule", "reportingRequired")
```

The dedicated controller branch must:

1. read `definition` and `selectedNodeId` from request params;
2. call `dashScopeClient.chatLowTemp` with a Workflow-only system prompt;
3. parse a JSON `WorkflowPatch[]` array;
4. pass it through `canvas_product_process_workflow_config.preview`;
5. return one `PRODUCT_PROCESS_WORKFLOW_PATCH` diff with `applied=false`;
6. never fall through to generic Canvas autopilot/plan/action handling.

- [ ] **Step 4: Write failing frontend patch-application test**

```ts
it('applies a workflow AI patch to local draft only', () => {
  const result = applyWorkflowPatches(definition, [{
    op: 'SET_NODE_FIELD',
    nodeId: 'process:1',
    path: 'conversionRule.mode',
    value: 'SUM_OUTPUTS',
  }]);
  expect((result.definition.nodes[1].data as ProcessNodeData).conversionRule.mode)
    .toBe('SUM_OUTPUTS');
  expect(result.definition.status).toBe('DRAFT');
});
```

- [ ] **Step 5: Wire the editor to the new module and patch handler**

Change the panel to:

```vue
module-code="product_process_workflow_config"
@apply-draft="applyWorkflowAIDraft"
```

Include `currentDefinition()` and `selectedNodeId` in context. `applyWorkflowAIDraft` must accept only `payload.patches`, call `applyWorkflowPatches`, show the returned summary in an `ElMessageBox.confirm`, then hydrate the returned definition and set `dirty=true`. It must not call any save, publish, activation, ProductType creation, report, task, or inventory API. Remove `applyLegacyAIDraft` from the Workflow editor; keep the legacy AI component behavior for the old compatibility list outside this editor.

- [ ] **Step 6: Run AI tests, frontend tests, type-check, and build**

```powershell
mvn "-Dtest=ProductProcessWorkflowConfigToolTest,CanvasAIWorkflowConfigTest" test
cd ../../../web-admin
npm test -- --run src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
npm run build
```

Expected: backend AI safety tests pass; frontend patch tests pass; type-check and build exit 0.

- [ ] **Step 7: Commit Task 6**

```powershell
git add -- backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java backend/java/cretas-api/src/main/java/com/cretas/aims/controller/CanvasAIController.java backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java backend/java/cretas-api/src/test/java/com/cretas/aims/controller/CanvasAIWorkflowConfigTest.java web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue web-admin/src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
git commit -m "feat: add workflow-only AI patch assistant"
```

### Task 7: Configuration-Plane Regression and Browser Verification

**Files:**
- Modify only if a defect is found: files changed in Tasks 1-6.
- Evidence is local and ignored under `.superpowers/` or the project E2E results directory; do not commit access tokens or temporary mock data.

**Interfaces:**
- Verifies the configuration plane only; runtime task generation and reporting remain unchanged.

- [ ] **Step 1: Run all focused backend tests together**

```powershell
cd backend/java/cretas-api
mvn "-Dtest=WorkProcessServiceImplTest,ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowConfigToolTest,CanvasAIWorkflowConfigTest" test
```

Expected: all selected tests pass with zero failures and zero errors.

- [ ] **Step 2: Run frontend tests, type-check, and production build**

```powershell
cd ../../../web-admin
npm test -- --run src/views/system/work-processes/__tests__/workProcessOutputKind.spec.ts src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts
npx vue-tsc --noEmit -p tsconfig.app.json
npm run build
```

Expected: all focused tests pass; type-check and build exit 0.

- [ ] **Step 3: Run browser verification with the existing workflow mock API**

Verify these exact scenarios at a 1920×1080 viewport:

1. Select a product with no saved graph: one raw-material Cell is visible.
2. Add a `SEMI_FINISHED` WorkProcess: one blue process Cell and one green semi-finished Cell appear in one click.
3. Undo once: process Cell, output Cell, and both edges disappear together; redo restores all.
4. Add a `FINISHED_GOOD` WorkProcess: its output is a purple terminal Cell bound to the current product SKU.
5. Confirm no half-finished/finished type selector exists inside the process Cell.
6. Click inline `＋ 添加产出`: a second output port, Cell, and edge appear.
7. Undo, then click edge `＋`: the same graph result appears.
8. Send an AI prompt: only a reviewable Workflow patch appears; applying it marks the local graph dirty but does not call save/publish/activation/product/report/inventory endpoints.
9. Refresh before save: the AI/local changes disappear, proving AI did not persist them.

Expected depth: `medium` for configuration because the browser applies a local draft and verifies requests, but does not exercise production/reporting downstream state.

- [ ] **Step 4: Perform same-cause sweep**

Search for remaining manual output-kind controls and legacy Workflow AI usage:

```powershell
git grep -n -E "changeOutputKind|output-kind-select|product_work_process_config|applyLegacyAIDraft" -- web-admin/src/views/system/product-processes backend/java/cretas-api/src/main/java/com/cretas/aims/controller/CanvasAIController.java
```

Expected: no manual type control remains in the Workflow editor; legacy module references remain only in the compatibility/legacy product-process UI, not the Workflow component.

- [ ] **Step 5: Commit any verification fixes**

If verification required code changes in the Workflow editor, stage only the affected scoped files and commit:

```powershell
git add -- web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue web-admin/src/views/system/product-processes/workflow/workflowModel.ts web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts web-admin/src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts
git commit -m "fix: close workflow configuration regressions"
```

If no code changes were required, do not create an empty commit.

---

## Deferred Follow-On Plans

This configuration-plane plan intentionally stops before production runtime. After it passes, create and review separate implementation plans for:

1. Workflow activation, immutable production-batch snapshots, and task/node binding.
2. Compatibility-first reporting fields (`workflowPortId`, multi-WIP refs, configured multi-output lines), inventory posting, and lineage.
3. RN three-stage reporting presentation and F006 deep E2E with submit, approval, fresh readback, inventory, and traceability verification.

Keeping these plans separate ensures the editor and process-master contract are stable before the production/reporting path consumes them.
