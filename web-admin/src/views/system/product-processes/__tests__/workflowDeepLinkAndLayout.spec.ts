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

  it('keeps mode controls and the bottom AI compose dock', () => {
    expect(editorSource).toContain('data-testid="canvas-floating-tools"');
    expect(editorSource).toContain('id="workflow-ai-composer"');
    expect(editorSource).toContain('class="workflow-ai-dock"');
    expect(editorSource).toContain('<WorkProcessAIChatPanel');
    expect(editorSource).toContain('const aiCollapsed = ref(false)');
    expect(editorSource).toContain(':aria-expanded="!aiCollapsed"');
    expect(editorSource).toContain('aria-controls="workflow-ai-composer"');
    expect(editorSource).toContain('max-height: 210px;');
  });

  it('removes the legacy compatibility list from the visible workflow workspace', () => {
    expect(pageSource).not.toContain('保存兼容列表');
    expect(pageSource).toContain('<el-collapse\n      v-if="false"');
  });
});
