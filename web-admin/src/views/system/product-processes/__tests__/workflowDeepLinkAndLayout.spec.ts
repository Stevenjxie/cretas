import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const pageSource = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');
const editorSource = readFileSync(
  resolve(import.meta.dirname, '../workflow/ProductProcessWorkflowEditor.vue'),
  'utf8',
);

describe('workflow deep link and workspace layout', () => {
  it('keeps the selected product synchronized with productTypeId route changes', () => {
    expect(pageSource).toContain('watch(() => route.query.productTypeId');
    expect(pageSource).toContain('applyRouteProductSelection');
  });

  it('starts with the AI panel collapsed and only restores an explicit expanded preference', () => {
    expect(editorSource).toContain('const aiCollapsed = ref(true)');
    expect(editorSource).toContain("localStorage.getItem(aiStorageKey.value) !== 'false'");
    expect(editorSource).toContain(':aria-expanded="!aiCollapsed"');
    expect(editorSource).toContain('aria-controls="workflow-ai-panel"');
    expect(editorSource).toContain('grid-template-columns: minmax(0, 1fr);');
    expect(editorSource).toContain('position: absolute;');
  });
});
