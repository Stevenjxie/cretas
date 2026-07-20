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

  it('uses a fixed viewport work area instead of allowing the graph to grow the document', () => {
    expect(editorSource).toContain('--workflow-editor-height: calc(100dvh - var(--header-height, 64px) - 156px);');
    expect(editorSource).toContain('min-height: 0;');
    expect(editorSource).toContain('overflow: hidden;');
    expect(editorSource).toContain('position: sticky; top: 0; z-index: 40;');
    expect(editorSource).toContain('flex: 1; min-height: 0; height: 0; overflow: hidden;');
  });

  it('keeps mode controls and the AI compose input in independently scrollable workspace regions', () => {
    expect(editorSource).toContain('data-testid="canvas-floating-tools"');
    expect(editorSource).toContain('id="workflow-ai-composer"');
    expect(editorSource).toContain('const aiCollapsed = ref(false)');
    expect(editorSource).toContain(':aria-expanded="!aiCollapsed"');
    expect(editorSource).toContain('aria-controls="workflow-ai-composer"');
    expect(editorSource).toContain('grid-template-columns: minmax(0, 1fr) clamp(320px, 23vw, 380px);');
    expect(editorSource).toContain('overflow-y: auto;');
  });

  it('keeps the legacy compatibility chain collapsed by default and closes it with Escape', () => {
    expect(pageSource).toContain('const legacyCompatibilityExpanded = ref<string[]>([])');
    expect(pageSource).toContain('v-model="legacyCompatibilityExpanded"');
    expect(pageSource).toContain("event.key !== 'Escape'");
    expect(pageSource).toContain('legacyCompatibilityExpanded.value = []');
    expect(pageSource).toContain('position: absolute; left: 12px; bottom: 12px; z-index: 48;');
  });
});
