/**
 * SP-F 逐工序电子表格 — 配置描述符
 *
 * 工序配置 (修油 + 滚揉 + 焯水 + 去舌苔 + 熟制):
 *   - 修油 (xiuyou):     首道, 原料领料 → RAW MaterialConsumption, WIP 批生成.
 *   - 滚揉 (gunrou):     单上游 (修油), 出成率. G1 新增.
 *   - 焯水 (chaoshui):   单上游 (滚揉), 出成率.
 *   - 去舌苔 (qushetou): 单上游 (焯水), reverseInput 反推: input = scrap + output. G2 新增.
 *   - 熟制 (shuzhi):     混锅多来源分摊 + 调料 + labor.
 *
 * 「纯加配置铺全 6 道」的边界说明 (spec §6.2 审计):
 *   - 滚揉可纯加配置 (单上游, 无特殊公式). ✅ G1 完成.
 *   - 去舌苔需新增 'reverseInput' AutoCalc 类型 (投入=碎肉+产出反推). ✅ G2 完成.
 *   - 气调需新增更多 AutoCalc 类型 (单盒克重/每盒人工等), defer.
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
 * - dropdown   : 从库存/批次列表选择 (上游批次/原料批次).
 * - number     : 数字输入框.
 * - date       : 日期选择器 (单日).
 * - daterange  : 日期范围选择器 (开始日期 ~ 结束日期); 存为 [start, end] 字符串数组.
 * - auto       : 客户端自动计算 (即时反馈); 权威值来自后端响应.
 * - readonly   : 只读显示 (系统生成的批次号 / 派生的剩余量).
 * - text       : 自由文本输入.
 */
export type ColType = 'dropdown' | 'number' | 'date' | 'daterange' | 'auto' | 'readonly' | 'text';

/**
 * 自动计算公式标识.
 *
 * - yield        : 出成率 = 产出 / 投入 × 100
 * - remaining    : 剩余量 — 派生自后端库存端点 (§5), 前端只读展示, 不本地计算.
 * - totalHours   : 总工时 = Σ (时段时长 × 人数), 来自工时子表 laborSegments.
 * - reverseInput : 反推投入量 = scrap + output (去舌苔). G2 新增.
 */
export type AutoCalc = 'yield' | 'remaining' | 'totalHours' | 'reverseInput';

/**
 * 单列描述符.
 *
 * @param key       - payload 字段名 (与 ProcessSheetRowRequest 对应).
 * @param label     - 表头显示文字.
 * @param type      - 列输入类型.
 * @param upstream  - dropdown 上游工序代码 (对应 PROCESS_SHEET_CONFIG 的 key),
 *                    指示该列的选项来自哪道工序的库存端点.
 * @param source    - dropdown 数据来源:
 *                      'raw'      → 从 GET /material-batches/status/AVAILABLE 拉原料批次 (首道).
 *                      'upstream' → 从上游工序库存端点拉 WIP 批次 (默认, 省略时即此值).
 * @param autoCalc  - type='auto' 时的公式标识.
 */
export interface ColDef {
  key: string;
  label: string;
  type: ColType;
  upstream?: string;
  /** 'raw' = 原料批次下拉 (修油首道); 'upstream' = 上游 WIP 批次下拉 (默认). */
  source?: 'raw' | 'upstream';
  autoCalc?: AutoCalc;
}

// =========================================================================
// 工序配置
// =========================================================================

/**
 * 配置表: 工序代码 → 列描述符数组.
 * 顺序即表格列顺序.
 *
 * G0 动态接线: ProcessSheet.vue 按产品 ProductWorkProcess 动态解析工序链,
 * 按工序名关键词子串匹配 → 列定义 key. upstream 字段此处写静态默认值,
 * G0 动态链解析会按链序自动将"前一道"传入 upstreamItems, 此处 upstream
 * 仅作 fallback 或文档说明, 不影响 G0 动态接线.
 */
export const PROCESS_SHEET_CONFIG: Record<string, ColDef[]> = {
  // -----------------------------------------------------------------------
  // 修油 (xiuyou) — 首道, 无上游工序 (直接消耗原料 MaterialBatch)
  // rawBatch → rawMaterialInputs[]; outWeight → rawInput.quantity
  // 切片不录肥油(byproduct), defer Q6
  // -----------------------------------------------------------------------
  xiuyou: [
    { key: 'rawBatch',    type: 'dropdown', label: '原料批次', source: 'raw' }, // → rawMaterialInputs (选原料 MaterialBatch)
    { key: 'outWeight',   type: 'number',   label: '出库重量(kg)' },        // → rawInput.quantity
    { key: 'batch',       type: 'readonly', label: '修油批次' },            // 系统生成, 作下游下拉项
    { key: 'prodDate',    type: 'daterange', label: '生产日期' },
    { key: 'output',      type: 'number',   label: '产出数量(kg)' },        // → outputQuantity
    { key: 'feedWeight',  type: 'auto',     label: '投料重量(kg)' },        // 前端 = outWeight (即时反馈)
    { key: 'yieldRate',   type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'totalHours',  type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 滚揉 (gunrou) — 单上游 (修油), 镜像焯水结构. G1 新增.
  // upstreamBatch → upstreamSources[0]; before → inputQuantity; after → outputQuantity
  // 剩余量只读, 由后端库存端点派生.
  // -----------------------------------------------------------------------
  gunrou: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'xiuyou',  label: '修油批次' },   // G0 动态接线: 前道传入
    { key: 'batch',         type: 'readonly',                       label: '滚揉批次' },    // 系统生成
    { key: 'date',          type: 'daterange',                      label: '滚揉日期' },
    { key: 'before',        type: 'number',                         label: '滚揉前(kg)' },  // → inputQuantity
    { key: 'after',         type: 'number',                         label: '滚揉后(kg)' },  // → outputQuantity
    { key: 'yieldRate',     type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'remain',        type: 'auto',     autoCalc: 'remaining',  label: '剩余量(kg)' }, // 后端派生, 只读
    { key: 'totalHours',    type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 焯水 (chaoshui) — 单上游 (滚揉, G0 动态接线; 无滚揉时接修油)
  // upstreamBatch → upstreamSources[0]; before → inputQuantity; after → outputQuantity
  // 剩余量 (remain) 只读, 由后端库存端点派生 (spec §6.3 审计 F-1)
  // -----------------------------------------------------------------------
  chaoshui: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'gunrou',   label: '滚揉批次' },   // G0 动态接线: 前道传入
    { key: 'batch',         type: 'readonly',                        label: '焯水批次' },   // 系统生成
    { key: 'date',          type: 'daterange',                       label: '焯水日期' },
    { key: 'before',        type: 'number',                          label: '焯水前(kg)' }, // → inputQuantity
    { key: 'after',         type: 'number',                          label: '焯水后(kg)' }, // → outputQuantity
    { key: 'yieldRate',     type: 'auto',     autoCalc: 'yield',      label: '出成率(%)' },
    { key: 'remain',        type: 'auto',     autoCalc: 'remaining',  label: '剩余量(kg)' }, // 后端派生, 只读
    { key: 'totalHours',    type: 'auto',     autoCalc: 'totalHours', label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 去舌苔 (qushetou) — 单上游 (焯水), reverseInput 反推. G2 新增.
  // upstreamBatch → upstreamSources[0]; input = scrap + output (反推); output → outputQuantity
  // feedQuantityKg = scrap + output (领用量 = 投入量)
  // -----------------------------------------------------------------------
  qushetou: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'chaoshui', label: '焯水批次' },   // G0 动态接线: 前道传入
    { key: 'batch',         type: 'readonly',                        label: '去舌苔批次' }, // 系统生成
    { key: 'date',          type: 'daterange',                       label: '去舌苔日期' },
    { key: 'scrap',         type: 'number',                          label: '碎肉(kg)' },   // → 反推分量 (scrap)
    { key: 'output',        type: 'number',                          label: '产出(kg)' },   // → outputQuantity
    { key: 'input',         type: 'auto',     autoCalc: 'reverseInput', label: '投入(kg)' }, // = scrap + output, 只读
    { key: 'yieldRate',     type: 'auto',     autoCalc: 'yield',        label: '出成率(%)' }, // = output / input × 100
    { key: 'remain',        type: 'auto',     autoCalc: 'remaining',    label: '剩余(kg)' }, // 后端派生, 只读
    { key: 'totalHours',    type: 'auto',     autoCalc: 'totalHours',   label: '总工时(h)' },
  ],

  // -----------------------------------------------------------------------
  // 熟制 (shuzhi) — 混锅多来源 (G0 动态接线: 前道传入, 默认=去舌苔/焯水批)
  // upstreamBatch 多选 → upstreamSources[]; input → inputQuantity; output → outputQuantity
  // 剩余量只读 (同焯水)
  // -----------------------------------------------------------------------
  shuzhi: [
    { key: 'upstreamBatch', type: 'dropdown', upstream: 'qushetou', label: '去舌苔批次(混锅)' }, // G0 动态接线: 前道传入, 多选
    { key: 'batch',         type: 'readonly',                        label: '熟制批次' },        // 系统生成
    { key: 'date',          type: 'daterange',                       label: '日期' },
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
 * @param processCode - 工序代码 (如 "xiuyou" / "gunrou" / "chaoshui" / "qushetou" / "shuzhi").
 */
export function genClientRowId(processCode: string): string {
  const ts = Date.now().toString(36);                      // base36 timestamp
  const rnd = Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, '0');
  return `${processCode}-${ts}-${rnd}`;
}
