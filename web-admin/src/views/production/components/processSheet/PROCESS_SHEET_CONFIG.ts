/**
 * SP-F 逐工序电子表格 — 配置描述符
 *
 * 切片 3 道工序 (修油 + 焯水 + 熟制):
 *   - 修油 (xiuyou):  首道, 原料领料 → RAW MaterialConsumption, WIP 批生成.
 *   - 焯水 (chaoshui): 单上游 WIP 库存扣减, 出成率.  切片折叠: 真实链有滚揉在中间.
 *   - 熟制 (shuzhi):  混锅多来源分摊 + 调料 + labor.  切片折叠: 真实链有去舌苔在中间.
 *
 * 「纯加配置铺全 6 道」的边界说明 (spec §6.2 审计):
 *   - 滚揉可纯加配置 (单上游, 无特殊公式).
 *   - 去舌苔 / 气调需新增 AutoCalc 类型 (投入=碎肉+产出反推 / 单盒克重/每盒人工等),
 *     不是纯配置. AutoCalc union 设计为可扩展 string literal.
 *
 * clientRowId 全局唯一要求:
 *   后端删除 finder 按 (factory, plan, clientRowId) 查, **不含 processCode**,
 *   因此不同工序/不同行之间 clientRowId 不能碰撞. genClientRowId() 在 rowId 内
 *   编码了 processCode + timestamp + random 来保证唯一性.
 */

// =========================================================================
// 类型定义
// =========================================================================

/**
 * 列的输入类型.
 *
 * - dropdown : 从库存/批次列表选择 (上游批次/原料批次).
 * - number   : 数字输入框.
 * - date     : 日期选择器.
 * - auto     : 客户端自动计算 (即时反馈); 权威值来自后端响应.
 * - readonly : 只读显示 (系统生成的批次号 / 派生的剩余量).
 * - text     : 自由文本输入.
 */
export type ColType = 'dropdown' | 'number' | 'date' | 'auto' | 'readonly' | 'text';

/**
 * 自动计算公式标识.
 * 扩展点: 去舌苔 / 气调需新增 'reverseInput' | 'perBoxCost' | ... 等类型.
 *
 * - yield      : 出成率 = 产出 / 投入 × 100
 * - remaining  : 剩余量 — 派生自后端库存端点 (§5), 前端只读展示, 不本地计算.
 * - totalHours : 总工时 = Σ (时段时长 × 人数), 来自工时子表 laborSegments.
 */
export type AutoCalc = 'yield' | 'remaining' | 'totalHours';

/**
 * 单列描述符.
 *
 * @param key       - payload 字段名 (与 ProcessSheetRowRequest 对应).
 * @param label     - 表头显示文字.
 * @param type      - 列输入类型.
 * @param upstream  - dropdown 上游工序代码 (对应 PROCESS_SHEET_CONFIG 的 key),
 *                    指示该列的选项来自哪道工序的库存端点.
 * @param autoCalc  - type='auto' 时的公式标识.
 */
export interface ColDef {
  key: string;
  label: string;
  type: ColType;
  upstream?: string;
  autoCalc?: AutoCalc;
}

// =========================================================================
// 切片 3 道工序配置
// =========================================================================

/**
 * 配置表: 工序代码 → 列描述符数组.
 * 顺序即表格列顺序.
 *
 * 切片折叠接线 (spec §2.2 说明):
 *   - 焯水的上游写 'xiuyou' (真实链中间有滚揉, 切片省略).
 *   - 熟制的上游写 'chaoshui' (真实链中间有去舌苔, 切片省略).
 *   滚揉 / 去舌苔 / 气调后续加入时, 只需在此加条目并修正 upstream 接线.
 */
export const PROCESS_SHEET_CONFIG: Record<string, ColDef[]> = {
  // -----------------------------------------------------------------------
  // 修油 (xiuyou) — 首道, 无上游工序 (直接消耗原料 MaterialBatch)
  // rawBatch → rawMaterialInputs[]; outWeight → rawInput.quantity
  // 切片不录肥油(byproduct), defer Q6
  // -----------------------------------------------------------------------
  xiuyou: [
    { key: 'rawBatch',    type: 'dropdown', label: '原料批次' },           // → rawMaterialInputs (选原料 MaterialBatch)
    { key: 'outWeight',   type: 'number',   label: '出库重量(kg)' },        // → rawInput.quantity
    { key: 'batch',       type: 'readonly', label: '修油批次' },            // 系统生成, 作下游下拉项
    { key: 'prodDate',    type: 'date',     label: '生产日期' },
    { key: 'output',      type: 'number',   label: '产出数量(kg)' },        // → outputQuantity
    { key: 'feedWeight',  type: 'auto',     label: '投料重量(kg)' },        // 前端 = outWeight (即时反馈)
    { key: 'yieldRate',   type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'totalHours',  type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 焯水 (chaoshui) — 单上游 (切片折叠: 真实链上游是滚揉, 切片接修油)
  // upstreamBatch → upstreamSources[0]; before → inputQuantity; after → outputQuantity
  // 剩余量 (remain) 只读, 由后端库存端点派生 (spec §6.3 审计 F-1)
  // -----------------------------------------------------------------------
  chaoshui: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'xiuyou',  label: '修油批次' },  // 切片折叠 (真实=滚揉批)
    { key: 'batch',         type: 'readonly',                       label: '焯水批次' },   // 系统生成
    { key: 'date',          type: 'date',                           label: '焯水日期' },
    { key: 'before',        type: 'number',                         label: '焯水前(kg)' }, // → inputQuantity
    { key: 'after',         type: 'number',                         label: '焯水后(kg)' }, // → outputQuantity
    { key: 'yieldRate',     type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'remain',        type: 'auto',     autoCalc: 'remaining',  label: '剩余量(kg)' }, // 后端派生, 只读
    { key: 'totalHours',    type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 熟制 (shuzhi) — 混锅多来源 (切片折叠: 真实链上游是去舌苔, 切片接焯水)
  // upstreamBatch 多选 → upstreamSources[]; input → inputQuantity; output → outputQuantity
  // 剩余量只读 (同焯水)
  // -----------------------------------------------------------------------
  shuzhi: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'chaoshui', label: '焯水批次(混锅)' }, // 切片折叠 (真实=去舌苔批), 多选
    { key: 'batch',         type: 'readonly',                        label: '熟制批次' },        // 系统生成
    { key: 'date',          type: 'date',                            label: '日期' },
    { key: 'input',         type: 'number',                          label: '投入(kg)' },        // → inputQuantity
    { key: 'output',        type: 'number',                          label: '产出(kg)' },        // → outputQuantity
    { key: 'yieldRate',     type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'remain',        type: 'auto',     autoCalc: 'remaining',  label: '剩余(kg)' },       // 后端派生, 只读
    { key: 'totalHours',    type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],
};

// =========================================================================
// clientRowId 生成工具
// =========================================================================

/**
 * 生成全局唯一的 clientRowId.
 *
 * 格式: `{processCode}-{timestamp}-{random6hex}`
 *
 * 为什么 processCode 必须编码进 id:
 *   后端删除端点 `DELETE .../row/{clientRowId}` 的 finder 以
 *   (factory, plan, clientRowId) 查行, **不含 processCode**.
 *   若修油和焯水各有一行均用 `row-{timestamp}` 等无前缀 id,
 *   在毫秒级并发下存在碰撞风险. 编码 processCode 后不同工序
 *   之间天然不会碰撞.
 *
 * @param processCode - 工序代码 (如 "xiuyou" / "chaoshui" / "shuzhi").
 */
export function genClientRowId(processCode: string): string {
  const ts = Date.now().toString(36);                      // base36 timestamp
  const rnd = Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, '0');
  return `${processCode}-${ts}-${rnd}`;
}
