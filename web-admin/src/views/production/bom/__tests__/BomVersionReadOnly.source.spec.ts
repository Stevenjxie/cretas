import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('BOM version read-only source contract', () => {
  it('uses one DRAFT-only edit predicate across every BOM section', () => {
    expect(source).toContain("canWrite.value && selectedRecipe.value?.status === 'DRAFT'");
    expect(source).toContain('v-if="selectedRecipeEditable"');
    expect(source).toContain('<el-table-column v-if="selectedRecipeEditable" label="操作"');
    expect(source).toContain(':show-readonly-notice="false"');
  });

  it('never creates a draft implicitly from the add-material handler', () => {
    const handler = source.slice(
      source.indexOf('async function handleAddBomItem()'),
      source.indexOf('async function handleEditBomItem'),
    );
    expect(handler).toContain('if (!requireEditableRecipe()) return;');
    expect(handler).not.toContain('ensureEditableDraft');
  });

  it('provides explicit lifecycle actions and text row operations', () => {
    expect(source).toContain('data-testid="bom-version-lifecycle"');
    expect(source).toContain('lifecycleUiState.primaryActionLabel');
    expect(source).toContain("import { buildBomLifecycleUiState, draftEntryLabel }");
    expect(source).toContain('<el-button v-if="canWrite" type="primary" size="small"');
    expect(source).not.toContain('v-if="canWrite && bomRecipes.length > 0"');
    expect(source).toContain('>编辑</el-button>');
    expect(source).toContain('>删除</el-button>');
    expect(source).not.toContain(':icon="Edit"');
    expect(source).not.toContain(':icon="Delete"');
  });

  it('defensively blocks stale labor and overhead dialogs after the version changes', () => {
    for (const handler of [
      'handleAddLaborCost',
      'handleEditLaborCost',
      'submitLaborForm',
      'handleDeleteLaborCost',
      'handleAddOverheadCost',
      'handleEditOverheadCost',
      'submitOverheadForm',
      'handleDeleteOverheadCost',
    ]) {
      const start = source.indexOf(`function ${handler}`);
      const asyncStart = source.indexOf(`async function ${handler}`);
      const handlerStart = Math.max(start, asyncStart);
      expect(handlerStart, handler).toBeGreaterThan(-1);
      expect(source.slice(handlerStart, handlerStart + 220), handler)
        .toContain('requireEditableRecipe()');
    }
  });
});
