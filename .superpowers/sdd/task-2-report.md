# Task 2 Report: Seasoning Workspace and Binding API

## Implementation

- Added a recipe-scoped seasoning workspace read model with ordered workflow processes, including processes without bindings.
- Added material summaries that deduplicate material identity while retaining independent per-process usage rows and never aggregating dosage across processes.
- Added anomaly reporting for invalid process links, missing material links, inactive or invalid materials, and missing prices.
- Added binding-scoped create, update, and delete APIs with atomic `seasoningRevision` claims.
- Restricted mutations to editable `DRAFT` recipes and validated workflow membership, same-factory active auxiliary/seasoning materials, positive dosage, and subsequent-pot ratio range.
- Snapshotted authoritative material name and current moving-average price on writes.
- Allowed the same material on different processes while rejecting a duplicate material within the same process with the existing binding ID in the conflict.
- Kept legacy ordinary auxiliary conversion outside this incremental API, as specified.

## API Surface

- `GET /{recipeId}/seasoning/workspace`
- `POST /{recipeId}/seasoning/processes/{workProcessId}/bindings`
- `PUT /{recipeId}/seasoning/bindings/{bindingId}`
- `DELETE /{recipeId}/seasoning/bindings/{bindingId}?expectedRevision=N`

## Verification

`mvn '-Dtest=BomSeasoningBindingIntegrityTest,BomSeasoningWorkspaceServiceTest,BomRecipeSeasoningServiceTest' test`

Result: **BUILD SUCCESS** — 22 tests run, 0 failures, 0 errors, 0 skipped.

`git diff --check` passed before commit.

## Concerns

- The workspace exposes legacy or malformed rows as anomalies instead of silently repairing them; migration of ordinary AUX rows remains a separate concern.
- Material classification accepts the repository's existing auxiliary/seasoning category values and primary code `003`; future category normalization should keep this validation aligned.
