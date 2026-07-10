# Whole-Branch Review Fix B Implementer Report

## Status

DONE

## Scope implemented

- Strengthened `ProductProcessWorkflowValidator.validateForDraft` as the single graph-semantic boundary used by normal draft save, publish, and AI candidate preview.
- Enforced process-local unique port IDs, direction-local unique non-negative integer ordinals, exact material Cell identity, exact material/process handles and directions, exactly one edge per declared port, and rejection of ghost/duplicate/self/material-to-material/process-to-process edges.
- Enforced optional `materialKind` equality with the referenced material Cell kind.
- Preserved incomplete draft boundaries: a single unbound raw-material Cell with no process or edge remains valid. Every port that exists must be fully bound and exactly connected.
- Removed the AI tool's duplicate private graph-semantic implementation. Its patch whitelist, shape/value sanitizer, atomic candidate application ordering, immutable node-kind boundary, cycle check, preview-only execution boundary, and controller composition remain in place; candidate validation now ends at the shared validator.
- Added `ProductTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)` as the first save/publish guard. Missing and foreign-factory route products fail with `PRODUCT_PROCESS_WORKFLOW_PRODUCT_INVALID`, an action hint, and no Workflow persistence/status mutation.
- Kept owner identity separate from output SKU identity. The valid publish service fixture still owns `PT-PIG-TROTTER` while producing `FG-BRAISED-400`, so multiple finished variants remain legal.
- No runtime reporting, production-task generation, inventory posting, traceability, fake data, fallback, push, merge, or deploy behavior was added.

## TDD evidence

### RED

Command:

```powershell
mvn "-Dtest=ProductProcessWorkflowValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowConfigToolTest" test
```

Result before production changes:

- 23 tests run; 9 failures; 0 errors; BUILD FAILURE.
- Shared validator accepted negative/fractional/duplicate ordinals and a second edge for one input port.
- Normal `saveDraft` accepted mismatched input `materialNodeId`.
- AI public preview accepted a second edge targeting the same input handle.
- Save/publish did not return `PRODUCT_PROCESS_WORKFLOW_PRODUCT_INVALID` for an unresolved owner product.

### GREEN

Final focused command:

```powershell
mvn "-Dtest=ProductProcessWorkflowValidatorTest,ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowConfigToolTest,CanvasAIWorkflowConfigTest" test
```

Result:

- 66 tests run; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS.
- `ProductProcessWorkflowValidatorTest`: 4 passed.
- `ProductProcessWorkflowCatalogValidatorTest`: 36 passed.
- `ProductProcessWorkflowServiceImplTest`: 12 passed.
- `ProductProcessWorkflowConfigToolTest`: 7 passed.
- `CanvasAIWorkflowConfigTest`: 7 passed.

Compile command:

```powershell
mvn -DskipTests compile
```

Result: BUILD SUCCESS; 3,695 main sources compiled. Maven emitted only the repository's existing model/Lombok/deprecation warnings.

## Service-path coverage

Both `saveDraft` and `publish` reject, before save/status mutation:

- mismatched input and output `materialNodeId`;
- duplicate input-handle edges;
- missing and duplicate port IDs;
- missing ordinal plus duplicate input/output ordinals;
- ghost process edges;
- wrong input-source and output-target material handles;
- material-to-material, process-to-process, and self-loop edges;
- mismatched `materialKind`.

The same service fixtures prove a valid two-input process and valid multi-output process continue through save/publish.

## Catalog fixture adaptation

Two existing positive catalog tests explicitly call the shared structural validator before catalog validation. Their valid chain helper previously omitted input-port `materialNodeId/materialKind` even though matching input edges existed. The helper was updated only for those positive paths:

- raw -> cut input now names the raw Cell;
- semi-finished -> pack/quality inputs now name the semi-finished Cell.

Catalog production code was not changed or relaxed. The valid two-process chain and one semi-finished Cell feeding multiple downstream processes both pass the final matrix.

## Files

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowValidator.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowValidatorTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java`

## Commit

- Message: `fix: enforce workflow graph ownership invariants`
- Commit: the commit containing this report; exact hash is recorded in the parent handoff.

## Reviewer follow-up: collision-free process/port keys

### Finding

The first implementation counted matched edges with `processId + "::" + portId`. Because `::` is legal in both IDs, distinct bindings `p/x::y` and `p::x/y` both serialized to `p::x::y`. One real edge for `p::x/y` could therefore hide the missing edge for `p/x::y`.

### TDD RED

The regression graph contains:

- `p/x::y` bound to `raw-1` with no matching edge;
- `p::x/y` bound to `raw-2` with a matching edge;
- separate valid output ports/edges for both processes;
- an additional valid `raw-1 -> p::x/other` edge so publish completeness cannot reject an unrelated disconnected material first.

Command:

```powershell
mvn "-Dtest=ProductProcessWorkflowValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowConfigToolTest" test
```

Result before the production fix: 27 tests run; 4 failures; 0 errors; BUILD FAILURE. The shared validator, `saveDraft`, `publish`, and AI public preview all accepted the collision graph.

### Fix

- Replaced `Map<String, Integer>` with `Map<PortKey, Integer>`.
- Added `record PortKey(String processId, String portId)` and used it for both edge-count insertion and declared-port lookup.
- Did not reserve or reject `::`; all existing nonblank IDs remain legal and are compared structurally.

### GREEN

Final focused command:

```powershell
mvn "-Dtest=ProductProcessWorkflowValidatorTest,ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowConfigToolTest,CanvasAIWorkflowConfigTest" test
```

Result: 70 tests run; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS.

- `ProductProcessWorkflowValidatorTest`: 5 passed.
- `ProductProcessWorkflowCatalogValidatorTest`: 36 passed.
- `ProductProcessWorkflowServiceImplTest`: 14 passed.
- `ProductProcessWorkflowConfigToolTest`: 8 passed.
- `CanvasAIWorkflowConfigTest`: 7 passed.

Fresh `mvn -DskipTests compile`: BUILD SUCCESS; 3,695 main sources compiled.

### Follow-up commit

- Message: `fix: use collision-free workflow port keys`
- Commit: the new commit containing this follow-up; exact hash is recorded in the parent handoff. The original `b85283bf5` commit was not amended.
