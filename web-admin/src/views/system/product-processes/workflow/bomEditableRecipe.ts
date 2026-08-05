/**
 * 画布上的辅料/包材 cell 要写进去的, 必须是 DRAFT 配方。
 *
 * 事故(2026-08-05 prod 实测): 画布一直用 `by-product/{id}/current` 解析目标 recipe,
 * 而该端点按后端契约只返回 `status=ACTIVE + is_current=TRUE`(BomRecipeController:115)。
 * 后端对非 DRAFT 的调料/包材写入一律拒绝, 于是两端都堵死 ——
 *   没有生效 BOM  → 画布报「产品无生效 BOM」
 *   有生效 BOM    → 后端报「只有 DRAFT BOM 可修改调料」/「只有 DRAFT 状态可加 item」
 * 即使手动克隆出草稿也没用: 画布从不去取草稿, 仍然往生效版写。
 * 实测同一工序节点 id, 写生效版 409 / 写草稿版 200 —— 差别只在 recipeId。
 *
 * 所以画布要自己解析「可编辑版本」: 有草稿就用草稿(显示与写入必须是同一条记录,
 * 否则编辑既有行时行 id 属于另一个版本), 没有草稿才退回生效版只读展示。
 */

export interface RecipeVersionLike {
  id: string;
  version?: number | null;
  status?: string | null;
  isCurrent?: boolean | null;
}

const upper = (value: unknown): string => String(value ?? '').toUpperCase();

/**
 * 从「某产品的全部版本」里挑出画布该显示/该写入的那一条。
 *
 * 顺序: DRAFT > 生效版(ACTIVE 且 is_current) > 任一 ACTIVE。
 * 归档版(ARCHIVED)永远不选 —— 它既不是生产口径也不可写。
 */
export function pickEditableRecipe<T extends RecipeVersionLike>(versions: T[] | null | undefined): T | null {
  if (!Array.isArray(versions) || versions.length === 0) return null;

  // 多个草稿属于异常数据; 取版本号最大的那个, 与 BOM 页「继续编辑最新草稿」一致。
  const drafts = versions.filter((item) => upper(item.status) === 'DRAFT');
  if (drafts.length > 0) {
    return drafts.reduce((best, item) => ((item.version ?? 0) > (best.version ?? 0) ? item : best));
  }

  const current = versions.find((item) => item.isCurrent === true && upper(item.status) === 'ACTIVE');
  if (current) return current;

  return versions.find((item) => upper(item.status) === 'ACTIVE') ?? null;
}

/** cell 上的写入按钮是否可以直接落笔; false 时必须先 ensureDraft。 */
export function isWritableRecipe(recipe: RecipeVersionLike | null | undefined): boolean {
  return upper(recipe?.status) === 'DRAFT';
}

export interface DraftBomNotice {
  productTypeId: string;
  productName: string;
  recipeId: string;
  draftVersion: number | null;
  /** 生产此刻仍在用的版本号; 没有生效版时为 null(全新产品的首版草稿)。 */
  activeVersion: number | null;
}

/**
 * 画布展示的是「可编辑版本」(见 pickEditableRecipe), 一旦它是草稿, 画布上看到的
 * 就不是产线在跑的配方 —— 而画布自己既不显示 BOM 版本状态, 也没有生效入口。
 *
 * 2026-08-05 实测: 从画布加了一条包材后, 画布显示 2 条(草稿 v3), 产线仍是 1 条
 * (生效 v2), 用户没有任何提示。改前写入是响亮失败(409), 改后变成静默不生效 ——
 * 更隐蔽。所以只要解析到草稿, 就必须把这件事说出来并给出生效入口。
 */
export function buildDraftBomNotice<T extends RecipeVersionLike>(
  productTypeId: string,
  productName: string,
  versions: T[] | null | undefined,
): DraftBomNotice | null {
  const editable = pickEditableRecipe(versions);
  if (!editable || !isWritableRecipe(editable)) return null;

  const active = (versions ?? []).find((item) => upper(item.status) === 'ACTIVE');
  return {
    productTypeId,
    productName,
    recipeId: editable.id,
    draftVersion: editable.version ?? null,
    activeVersion: active?.version ?? null,
  };
}
