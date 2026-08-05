/**
 * BOM 行标记体系 —— 默认状态不标，只标异常。
 *
 * 设计规则：一行 BOM 携带若干属性，全部渲染出来画布就退化成表格；全部隐藏
 * 画布又什么都看不出。所以约定的默认值（成本 SHARED / 不按锅序 / 非可选 /
 * 非按份 / 无替代 / 计入成本 / 无包装层级）一律不产生标记，只有偏离默认值
 * 的行才标 —— 扫一眼画布就知道哪几行有特殊规则。
 *
 * ⛔ 不要把标记表原样 export 给调用方自己筛选——判定逻辑必须集中在
 * markersForAuxiliaryRow / markersForPackagingRow 这两个函数里，否则各调用
 * 点会各自实现一份判定条件，逐渐漂移出「默认不标」这条规则。
 */

export interface BomRowMarker {
  glyph: string;
  kind: string;
  title: string;
}

export interface AuxiliaryRowInput {
  subsequentPotRatio: number | null;
  countInSeasoning: boolean;
  substituteCount: number;
  costScope: 'SHARED' | 'OUTPUT_GROUP' | 'OUTPUT_EXCLUSIVE' | string | null;
}

export interface PackagingRowInput {
  substituteCount: number;
  isOptional: boolean;
  perPortion: boolean;
  packagingSpecId: string | null;
  packagingSpecNameSnapshot?: string | null;
}

/**
 * 辅料行标记，固定顺序：◷ 按锅序 → ⊘ 不计入成本 → ⇄ 有替代 → ◑ 成本不共享。
 */
export function markersForAuxiliaryRow(row: AuxiliaryRowInput): BomRowMarker[] {
  const markers: BomRowMarker[] = [];

  if (row.subsequentPotRatio != null) {
    const percent = Number((row.subsequentPotRatio * 100).toFixed(2));
    markers.push({
      glyph: '◷',
      kind: 'pot',
      title: `按锅序投料 · 后续锅次 ${percent}%`,
    });
  }
  // ⚠️ SHOULD-FIX #5 已知限制(未修, 无后端改动的前提下无法对齐): 这个 ◷ 标记按
  // workflowProcessNodeId(当前工艺图里这一个具体节点)判定, 是精确的。但生产计划
  // 报工表(ProcessSheet.vue#resolveSeasoningProcesses)按 workProcessId(工序模板,
  // 同一工序类型在图里出现几次都共用一个 id)判定"锅数"输入框要不要显示 ——
  // bomSeasoningApi.getByProduct 的响应里没有 workflowProcessNodeId 字段, 只有
  // workProcessId, 这里拿不到对齐所需的信息。后果: 联合生产/同工序类型在同一张图里
  // 出现两次时, 若只有其中一个节点绑了续锅比例, 画布正确地只在那一个 cell 上显示 ◷,
  // 但报工表会把两个"腌制"步骤都当同一 workProcessId, 两边一起显示锅数输入框 ——
  // 报工表比这里更"宽松", 不是更精确。若要根治需要后端在 SeasoningItem 响应里加
  // workflowProcessNodeId 字段, 不在本轮范围(No backend change 约束)。


  if (row.countInSeasoning === false) {
    markers.push({
      glyph: '⊘',
      kind: 'free',
      title: '不计入成本',
    });
  }

  if (row.substituteCount > 0) {
    markers.push({
      glyph: '⇄',
      kind: 'sub',
      title: `有 ${row.substituteCount} 个替代物料`,
    });
  }

  if (row.costScope != null && row.costScope !== 'SHARED') {
    markers.push({
      glyph: '◑',
      kind: 'excl',
      title: '成本只算部分产出',
    });
  }

  return markers;
}

/**
 * 包材行标记，固定顺序：⇄ 有替代 → ○ 可选 → ⊞ 按份数 → ▤ 包装层级。
 */
export function markersForPackagingRow(row: PackagingRowInput): BomRowMarker[] {
  const markers: BomRowMarker[] = [];

  if (row.substituteCount > 0) {
    markers.push({
      glyph: '⇄',
      kind: 'sub',
      title: `有 ${row.substituteCount} 个替代物料`,
    });
  }

  if (row.isOptional === true) {
    markers.push({
      glyph: '○',
      kind: 'opt',
      title: '配方可选项',
    });
  }

  if (row.perPortion === true) {
    markers.push({
      glyph: '⊞',
      kind: 'portion',
      title: '按份数投料',
    });
  }

  if (row.packagingSpecId != null) {
    const levelName = row.packagingSpecNameSnapshot ?? '包装层级';
    markers.push({
      glyph: '▤',
      kind: 'lvl',
      title: levelName,
    });
  }

  return markers;
}
