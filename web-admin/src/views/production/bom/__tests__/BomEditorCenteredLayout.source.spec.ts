import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('BOM editor-centered layout source contract', () => {
  it('uses the approved lifecycle-first information hierarchy', () => {
    expect(source).toContain('class="bom-hero-card"');
    expect(source).toContain('class="bom-lifecycle-card"');
    expect(source).toContain('class="bom-summary-grid"');
    expect(source).toContain('class="bom-workspace"');
    expect(source).toContain('class="bom-side-stack"');
    expect(source).toContain('class="bom-draft-bar"');
  });

  it('keeps version history secondary and advanced costs collapsed', () => {
    expect(source).toContain('versionHistoryVisible = !versionHistoryVisible');
    expect(source).toContain('v-if="selectedProductTypeId && versionHistoryVisible"');
    expect(source).toContain(':aria-expanded="costDetailsExpanded"');
    expect(source).toContain('v-show="costDetailsExpanded"');
    expect(source).toContain('高级配置 · 日常配置原料、辅料和包材时无需展开');
  });

  it('shows one clear activation checklist and uses text operation buttons', () => {
    expect(source).toContain("label: '至少配置 1 项原料'");
    expect(source).toContain("label: '辅料和包材可选'");
    expect(source).toContain('检查并生效');
    expect(source).not.toContain(':icon=');
  });
});
