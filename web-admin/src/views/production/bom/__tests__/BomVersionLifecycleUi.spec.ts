import { describe, expect, it } from 'vitest';
import type { BomRecipeSummary } from '@/api/bom';
import { buildBomLifecycleUiState, draftEntryLabel } from '../bomVersionLifecycleUi';

function recipe(overrides: Partial<BomRecipeSummary> = {}): BomRecipeSummary {
  return {
    id: 'R1',
    factoryId: 'F006',
    recipeCode: 'BOM-1',
    productTypeId: 'SKU-1',
    productName: '测试产品',
    version: 1,
    isCurrent: false,
    status: 'DRAFT',
    items: [],
    ...overrides,
  };
}

describe('BOM version lifecycle UI', () => {
  it('allows editing only for a selected draft', () => {
    const draft = recipe({ version: 2 });
    const state = buildBomLifecycleUiState(draft, draft, true);

    expect(state.editable).toBe(true);
    expect(state.title).toContain('正在编辑 v2 草稿');
    expect(state.primaryAction).toBe('ACTIVATE_DRAFT');
    expect(state.primaryActionLabel).toBe('激活此版本');
  });

  it('locks the active version and offers an explicit clone when no draft exists', () => {
    const active = recipe({ status: 'ACTIVE', isCurrent: true });
    const state = buildBomLifecycleUiState(active, null, true);

    expect(state.editable).toBe(false);
    expect(state.title).toContain('当前生效，内容已锁定');
    expect(state.primaryAction).toBe('CLONE_ACTIVE');
    expect(state.primaryActionLabel).toBe('克隆为新版本修改');
  });

  it('routes an active or archived selection to the one existing draft', () => {
    const active = recipe({ status: 'ACTIVE', isCurrent: true });
    const archived = recipe({ id: 'R0', status: 'ARCHIVED', version: 0 });
    const draft = recipe({ id: 'R2', status: 'DRAFT', version: 2 });

    expect(buildBomLifecycleUiState(active, draft, true).primaryActionLabel)
      .toBe('前往 v2 草稿修改');
    expect(buildBomLifecycleUiState(archived, draft, true).primaryAction)
      .toBe('GO_TO_DRAFT');
  });

  it('never exposes a mutation action to read-only users', () => {
    const active = recipe({ status: 'ACTIVE', isCurrent: true });
    const state = buildBomLifecycleUiState(active, null, false);

    expect(state.editable).toBe(false);
    expect(state.primaryAction).toBeNull();
    expect(state.primaryActionLabel).toBe('');
  });

  it('does not tell a read-only user that a draft is being edited', () => {
    const draft = recipe({ status: 'DRAFT', version: 2 });
    const state = buildBomLifecycleUiState(draft, draft, false);

    expect(state.title).toBe('v2 草稿，只读查看');
    expect(state.description).toContain('没有生产配置写权限');
    expect(state.primaryAction).toBeNull();
  });

  it('uses business-facing draft entry labels', () => {
    const active = recipe({ status: 'ACTIVE', isCurrent: true });
    const draft = recipe({ id: 'R2', version: 2 });

    expect(draftEntryLabel([active, draft], draft)).toBe('前往 v2 草稿修改');
    expect(draftEntryLabel([active], null)).toBe('克隆当前生效版本修改');
    expect(draftEntryLabel([], null)).toBe('创建首版 BOM');
  });
});
