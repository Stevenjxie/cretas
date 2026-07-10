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
