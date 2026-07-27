import type { BomRecipeSummary } from '@/api/bom';

export type BomLifecyclePrimaryAction =
  | 'CREATE_DRAFT'
  | 'GO_TO_DRAFT'
  | 'CLONE_ACTIVE'
  | 'ACTIVATE_DRAFT'
  | null;

export interface BomLifecycleUiState {
  tone: 'success' | 'warning' | 'info';
  title: string;
  description: string;
  editable: boolean;
  primaryAction: BomLifecyclePrimaryAction;
  primaryActionLabel: string;
}

export function buildBomLifecycleUiState(
  selected: BomRecipeSummary | null,
  draft: BomRecipeSummary | null,
  canWrite: boolean,
): BomLifecycleUiState {
  if (!selected) {
    return {
      tone: 'info',
      title: '尚未创建 BOM 版本',
      description: '创建首版草稿后即可配置原料；辅料、包材、人工和均摊费用均为可选项。',
      editable: false,
      primaryAction: canWrite ? 'CREATE_DRAFT' : null,
      primaryActionLabel: canWrite ? '创建首版 BOM' : '',
    };
  }

  if (selected.status === 'DRAFT') {
    return {
      tone: 'info',
      title: canWrite ? `正在编辑 v${selected.version} 草稿` : `v${selected.version} 草稿，只读查看`,
      description: canWrite
        ? '当前版本可以修改。激活后内容将锁定，后续调整需创建新的草稿版本。'
        : '你当前没有生产配置写权限，可以查看该草稿，但不能修改或激活。',
      editable: canWrite,
      primaryAction: canWrite ? 'ACTIVATE_DRAFT' : null,
      primaryActionLabel: canWrite ? '激活此版本' : '',
    };
  }

  const goToDraft = draft && draft.id !== selected.id;
  const action = !canWrite
    ? null
    : goToDraft
      ? 'GO_TO_DRAFT'
      : 'CLONE_ACTIVE';
  const actionLabel = !canWrite
    ? ''
    : goToDraft
      ? `前往 v${draft.version} 草稿修改`
      : '克隆为新版本修改';

  if (selected.status === 'ACTIVE') {
    return {
      tone: 'success',
      title: `v${selected.version} 当前生效，内容已锁定`,
      description: goToDraft
        ? `生产计划继续使用当前版本；所有修改请在 v${draft.version} 草稿中完成，激活草稿前不会影响生产。`
        : '生产计划继续使用当前版本；如需调整，请先完整克隆为新草稿，当前版本不会被改写。',
      editable: false,
      primaryAction: action,
      primaryActionLabel: actionLabel,
    };
  }

  return {
    tone: 'warning',
    title: `v${selected.version} 为历史版本，仅供查看`,
    description: goToDraft
      ? `该历史版本不会再变化；请前往 v${draft.version} 草稿继续修改。`
      : '该历史版本不会再变化；创建修改版时将以当前生效版本为基础，不会改写历史记录。',
    editable: false,
    primaryAction: action,
    primaryActionLabel: actionLabel,
  };
}

export function draftEntryLabel(
  recipes: BomRecipeSummary[],
  draft: BomRecipeSummary | null,
): string {
  if (draft) return `前往 v${draft.version} 草稿修改`;
  if (recipes.some((recipe) => recipe.status === 'ACTIVE' && recipe.isCurrent)) {
    return '克隆当前生效版本修改';
  }
  return '创建首版 BOM';
}
