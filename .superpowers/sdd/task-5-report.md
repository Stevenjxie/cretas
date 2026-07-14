# Task 5 Report: BOM Integration and Legacy Navigation

## Delivered

- Integrated `BomAuxiliaryWorkspace` into the BOM host when the active material category is `AUXILIARY`.
- Added explicit recipe selection with DRAFT-first, current-ACTIVE fallback behavior; the exact recipe ID and status are passed into the workspace.
- Kept ACTIVE/ARCHIVED versions read-only through the workspace contract and wired clone requests to select the returned DRAFT before reloading.
- Removed AUXILIARY from the generic BOM item create dialog to prevent dual writes into legacy `bom_items`.
- Preserved historical ordinary auxiliary rows and surfaced a warning that they are unbound and may be counted twice; conversion remains visibly disabled because no conversion endpoint exists.
- Reduced the top-level BOM tabs to `原辅料配方` and `转换率`.
- Redirected both legacy `?tab=recipe` and `/production/product-recipes` entries to `/production/bom?tab=materials&category=AUXILIARY&auxView=process`, preserving incoming query values such as `productTypeId`.
- Removed the now-unreferenced `ProductRecipeView.vue`, legacy seasoning form helper, and their superseded tests.

## Verification

- `npm test -- src/views/production/bom/seasoning/__tests__`: 3 files passed, 11 tests passed.
- `npx vue-tsc -b --pretty false`: no errors in Task 5 files; command remains red on four pre-existing unrelated errors in production plans, logistics location picker, logistics route engine, and Smart BI.
- `git diff --check`: passed.
- Full frontend/backend builds intentionally deferred to combined verification per the task brief.
