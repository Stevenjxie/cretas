/**
 * WP-6 (P2): 供应商进货 Excel/CSV 解析结果 → 草稿送货单字段检测 + 映射 (PURE)。
 *
 * 设计依据: docs/superpowers/specs/2026-06-05-restaurant-ingest-inventory-rn-workbench-design.md
 * "P2 供应商进货 Excel/CSV 入草稿"。
 *
 * 职责: 把 SmartBI Excel/CSV 解析得到的 {headers, rows} (与
 * ExcelUpload.vue parseResult.headers / sampleData 同形) 映射到 *现有*
 * supplier delivery note 草稿对象字段。不新建平行表、不写库、不伪造数据。
 *
 * 关键约束:
 * - 纯函数, 无 Vue / 无网络依赖 → 可单元测试。
 * - 低置信字段 (confidence < CONFIDENCE_THRESHOLD) 标记 needsReview=true,
 *   交人工确认, 绝不自动确认入库。
 * - 未匹配 / 无值的字段返回 null + needsReview, 由 UI 显示 "待确认", 不编造值。
 * - 金额 (lineAmount/unitPrice) 的 RBAC fail-closed 由调用方 (UI) 处理,
 *   本模块只负责检测与映射。
 */

/** 草稿送货单可映射的目标字段 key。 */
export type DraftFieldKey =
  | 'supplierName'
  | 'deliveryDate'
  | 'ingredientName'
  | 'spec'
  | 'unit'
  | 'quantity'
  | 'unitPrice'
  | 'lineAmount'
  | 'remark';

/**
 * 现有草稿对象 *已持久化* 的行字段 (见
 * dto/restaurant/SupplierDeliveryNoteDto.LineDto + entity SupplierDeliveryNoteLine)。
 * spec(规格) / remark(备注) 当前草稿对象 **没有** 对应列 — 仍会被检测,
 * 但标记 persisted=false, 仅供人工核对参考, 不会写入草稿 (避免新增 schema)。
 */
export const PERSISTED_LINE_FIELDS: ReadonlyArray<DraftFieldKey> = [
  'ingredientName',
  'unit',
  'quantity',
  'unitPrice',
  'lineAmount',
];

/** 草稿头部 (note 级) 可持久化字段。 */
export const PERSISTED_HEAD_FIELDS: ReadonlyArray<DraftFieldKey> = [
  'supplierName',
  'deliveryDate',
];

/** spec/remark 当前草稿对象不持久化 (需扩展 DTO+entity, 见报告 FLAG)。 */
export const NON_PERSISTED_FIELDS: ReadonlyArray<DraftFieldKey> = ['spec', 'remark'];

/** 低置信阈值 — 与后端 LOW_CONFIDENCE_THRESHOLD 一致 (0.75)。低于此值 → 人工确认。 */
export const CONFIDENCE_THRESHOLD = 0.75;

/**
 * 保守中文关键词桶。顺序敏感: 越靠前优先级越高 (用于消歧, 例如 "单价" 必须
 * 在 "单位"/"金额" 之前被 quantity/price 抢到对应列)。
 */
const KEYWORD_BUCKETS: Record<DraftFieldKey, string[]> = {
  supplierName: ['供应商', '供货商', '供应单位', '供货单位', '厂商', '供方'],
  deliveryDate: ['送货日期', '收货日期', '到货日期', '送货时间', '日期', 'date'],
  unitPrice: ['单价', '进价', '采购价', '含税单价', 'unitprice', 'price'],
  lineAmount: ['金额', '小计', '合计', '总价', '总额', '价税合计', 'amount'],
  quantity: ['数量', '进货量', '采购量', '重量', '件数', '数', 'qty', 'quantity'],
  unit: ['单位', '计量单位', '基本单位', 'unit'],
  spec: ['规格', '型号', '规格型号', 'spec'],
  ingredientName: [
    '食材',
    '原料',
    '物料',
    '品名',
    '商品名称',
    '货品名称',
    '商品',
    '货品',
    '名称',
    '品种',
    '菜品',
    '材料',
  ],
  remark: ['备注', '说明', '注释', 'remark', 'note', 'memo'],
};

/** 字段检测匹配类型 (用于解释 confidence 来源)。 */
export type MatchKind = 'exact' | 'contains' | 'value' | 'none';

/** 单个表头的检测结果。 */
export interface DetectedColumn {
  header: string;
  index: number;
  field: DraftFieldKey | null;
  confidence: number;
  matchKind: MatchKind;
}

/** 头部级 (note) 字段映射结果。 */
export interface HeadFieldResult {
  value: string | null;
  confidence: number;
  needsReview: boolean;
  sourceHeader: string | null;
}

/** 单行行项映射结果。null = 未识别 → UI 显示 "待确认"。 */
export interface MappedDraftLine {
  ingredientName: string | null;
  unit: string | null;
  quantity: number | null;
  unitPrice: number | null;
  lineAmount: number | null;
  /** 检测到但当前草稿不持久化 (规格)。 */
  spec: string | null;
  /** 检测到但当前草稿不持久化 (备注)。 */
  remark: string | null;
  /** 每个目标字段的置信度 (0-1)。 */
  fieldConfidence: Partial<Record<DraftFieldKey, number>>;
  /** 该行是否需要人工确认 (有低置信字段 或 缺关键字段)。 */
  needsReview: boolean;
}

/** 整体映射结果。 */
export interface ImportMappingResult {
  columns: DetectedColumn[];
  supplierName: HeadFieldResult;
  deliveryDate: HeadFieldResult;
  lines: MappedDraftLine[];
  /** 未能映射到任何草稿字段的原始表头 (透明展示)。 */
  unmappedHeaders: string[];
  /** 检测到但当前草稿对象不持久化的字段 (spec/remark)。 */
  detectedNonPersisted: DraftFieldKey[];
  /** 任一行或头部需要人工确认。 */
  overallNeedsReview: boolean;
}

export interface ParsedTable {
  headers: string[];
  /** 行数据: 数组对象 (key=header) 或纯数组 (按列序)。 */
  rows: Array<Record<string, unknown> | unknown[]>;
}

export type ImportFileKind = 'csv' | 'excel';

export function detectImportFileKind(fileName: string, mimeType?: string): ImportFileKind | null {
  const lowerName = fileName.trim().toLowerCase();
  const lowerType = (mimeType || '').trim().toLowerCase();
  if (
    lowerName.endsWith('.xlsx') ||
    lowerName.endsWith('.xls') ||
    lowerType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
    lowerType === 'application/vnd.ms-excel'
  ) {
    return 'excel';
  }
  if (lowerName.endsWith('.csv') || lowerType === 'text/csv' || lowerType === 'application/csv') {
    return 'csv';
  }
  return null;
}

export function tableFromRows(rows: unknown[][]): ParsedTable {
  const nonEmptyRows = rows.filter((row) => row.some((cell) => cellToString(cell) !== null));
  if (nonEmptyRows.length === 0) return { headers: [], rows: [] };

  const headers = nonEmptyRows[0].map((cell) => String(cell ?? '').trim());
  const dataRows = nonEmptyRows.slice(1).map((row) => {
    const obj: Record<string, unknown> = {};
    headers.forEach((header, index) => {
      obj[header] = row[index] ?? '';
    });
    return obj;
  });
  return { headers, rows: dataRows };
}

export async function parseExcelArrayBuffer(buffer: ArrayBuffer): Promise<ParsedTable> {
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(buffer, { type: 'array', cellDates: false });
  const firstSheetName = workbook.SheetNames[0];
  if (!firstSheetName) return { headers: [], rows: [] };

  const sheet = workbook.Sheets[firstSheetName];
  if (!sheet) return { headers: [], rows: [] };

  const rows = XLSX.utils.sheet_to_json<unknown[]>(sheet, {
    header: 1,
    raw: false,
    defval: '',
    blankrows: false,
  });
  return tableFromRows(rows);
}

function normalizeHeader(h: string): string {
  return String(h ?? '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '')
    .replace(/[()（）【】\[\]:：]/g, '');
}

/**
 * 把任意单元格值解析为数字。剥离货币符号 / 千分位逗号 / 单位后缀。
 * 解析不出有效数字返回 null (绝不返回 0 充数)。
 */
export function parseNumeric(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const raw = String(value).trim();
  if (raw === '') return null;
  // 保留数字 / 小数点 / 负号; 去掉 ¥ $ 元 kg 件 等。
  const cleaned = raw.replace(/[^\d.\-]/g, '');
  if (cleaned === '' || cleaned === '-' || cleaned === '.') return null;
  const n = Number(cleaned);
  return Number.isFinite(n) ? n : null;
}

function cellToString(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const s = String(value).trim();
  return s === '' ? null : s;
}

/** 取某行某列的原始值 (兼容对象行 / 数组行)。 */
function getCell(
  row: Record<string, unknown> | unknown[],
  col: DetectedColumn,
): unknown {
  if (Array.isArray(row)) return row[col.index];
  // 对象行: 优先按原始 header key 取值
  if (col.header in (row as Record<string, unknown>)) {
    return (row as Record<string, unknown>)[col.header];
  }
  return undefined;
}

/** 看起来像日期? (用于 value 级启发式 deliveryDate 检测)。 */
function looksLikeDate(s: string): boolean {
  return (
    /^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}/.test(s) ||
    /^\d{4}年\d{1,2}月\d{1,2}日/.test(s) ||
    /^\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}/.test(s)
  );
}

/**
 * 检测一组表头到草稿字段的映射。每个目标字段最多被一个表头占用
 * (置信度最高者胜), 避免 "单价"/"单位" 互抢。
 */
export function detectColumns(headers: string[]): DetectedColumn[] {
  const norm = headers.map((h) => normalizeHeader(h));

  // 候选: 对每个 header × field 评分。
  type Cand = { headerIndex: number; field: DraftFieldKey; confidence: number; matchKind: MatchKind };
  const candidates: Cand[] = [];

  norm.forEach((nh, i) => {
    if (!nh) return;
    (Object.keys(KEYWORD_BUCKETS) as DraftFieldKey[]).forEach((field) => {
      const kws = KEYWORD_BUCKETS[field];
      let best: { confidence: number; matchKind: MatchKind } | null = null;
      for (const kw of kws) {
        const k = kw.toLowerCase();
        if (nh === k) {
          best = { confidence: 0.97, matchKind: 'exact' };
          break;
        }
        if (nh.includes(k)) {
          // 越长的关键词越可信; "单价"(2) 命中 0.88, 单字 "数" 命中略低。
          const conf = k.length >= 2 ? 0.86 : 0.7;
          if (!best || conf > best.confidence) best = { confidence: conf, matchKind: 'contains' };
        }
      }
      if (best) {
        candidates.push({ headerIndex: i, field, confidence: best.confidence, matchKind: best.matchKind });
      }
    });
  });

  // 贪心分配: 置信度从高到低, 每个 header 与每个 field 各只占用一次。
  candidates.sort((a, b) => b.confidence - a.confidence);
  const usedHeader = new Set<number>();
  const usedField = new Set<DraftFieldKey>();
  const headerField = new Map<number, Cand>();
  for (const c of candidates) {
    if (usedHeader.has(c.headerIndex) || usedField.has(c.field)) continue;
    usedHeader.add(c.headerIndex);
    usedField.add(c.field);
    headerField.set(c.headerIndex, c);
  }

  return headers.map((header, index) => {
    const c = headerField.get(index);
    return c
      ? { header, index, field: c.field, confidence: c.confidence, matchKind: c.matchKind }
      : { header, index, field: null, confidence: 0, matchKind: 'none' as MatchKind };
  });
}

function headField(
  field: DraftFieldKey,
  columns: DetectedColumn[],
  rows: ParsedTable['rows'],
): HeadFieldResult {
  const col = columns.find((c) => c.field === field);
  if (!col) {
    return { value: null, confidence: 0, needsReview: true, sourceHeader: null };
  }
  // 头部字段 (供应商/日期) 通常整列同值 → 取首个非空。
  let value: string | null = null;
  for (const row of rows) {
    const s = cellToString(getCell(row, col));
    if (s) {
      value = field === 'deliveryDate' ? normalizeDate(s) : s;
      break;
    }
  }
  let confidence = value ? col.confidence : 0;
  if (field === 'deliveryDate' && value && !looksLikeDate(value)) {
    // 列名像日期但值不像 → 降置信交人工。
    confidence = Math.min(confidence, 0.5);
  }
  return {
    value,
    confidence,
    needsReview: !value || confidence < CONFIDENCE_THRESHOLD,
    sourceHeader: col.header,
  };
}

/** 规整常见日期写法为 YYYY-MM-DD; 无法规整则原样返回 (交人工)。 */
export function normalizeDate(s: string): string {
  const t = s.trim();
  let m = t.match(/^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/);
  if (m) {
    return `${m[1]}-${String(m[2]).padStart(2, '0')}-${String(m[3]).padStart(2, '0')}`;
  }
  m = t.match(/^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})/);
  if (m) {
    return `${m[3]}-${String(m[1]).padStart(2, '0')}-${String(m[2]).padStart(2, '0')}`;
  }
  return t;
}

function isRowEmpty(row: Record<string, unknown> | unknown[], columns: DetectedColumn[]): boolean {
  return columns.every((c) => cellToString(getCell(row, c)) === null);
}

/**
 * 主入口: 解析结果 → 草稿映射。
 *
 * @param table SmartBI/CSV 解析得到的 {headers, rows}。
 * @returns 映射结果 (含每字段置信度 + needsReview 标记)。
 */
export function mapParsedTableToDraft(table: ParsedTable): ImportMappingResult {
  const headers = table.headers || [];
  const rows = (table.rows || []).filter((r) => r != null);
  const columns = detectColumns(headers);

  const colByField = (f: DraftFieldKey) => columns.find((c) => c.field === f) || null;

  const ingredientCol = colByField('ingredientName');
  const unitCol = colByField('unit');
  const qtyCol = colByField('quantity');
  const priceCol = colByField('unitPrice');
  const amountCol = colByField('lineAmount');
  const specCol = colByField('spec');
  const remarkCol = colByField('remark');

  const dataRows = rows.filter((r) => !isRowEmpty(r, columns));

  const lines: MappedDraftLine[] = dataRows.map((row) => {
    const ingredientName = ingredientCol ? cellToString(getCell(row, ingredientCol)) : null;
    const unit = unitCol ? cellToString(getCell(row, unitCol)) : null;
    const quantity = qtyCol ? parseNumeric(getCell(row, qtyCol)) : null;
    const unitPrice = priceCol ? parseNumeric(getCell(row, priceCol)) : null;
    let lineAmount = amountCol ? parseNumeric(getCell(row, amountCol)) : null;
    const spec = specCol ? cellToString(getCell(row, specCol)) : null;
    const remark = remarkCol ? cellToString(getCell(row, remarkCol)) : null;

    // 数字联动: 金额缺失但有 数量×单价 → 推导 (与后端 Rule 3 一致), 标记中等置信。
    let amountDerived = false;
    if (lineAmount === null && quantity !== null && unitPrice !== null) {
      lineAmount = Number((quantity * unitPrice).toFixed(2));
      amountDerived = true;
    }

    const fieldConfidence: Partial<Record<DraftFieldKey, number>> = {};
    if (ingredientCol) fieldConfidence.ingredientName = ingredientName ? ingredientCol.confidence : 0;
    if (unitCol) fieldConfidence.unit = unit ? unitCol.confidence : 0;
    if (qtyCol) fieldConfidence.quantity = quantity !== null ? qtyCol.confidence : 0;
    if (priceCol) fieldConfidence.unitPrice = unitPrice !== null ? priceCol.confidence : 0;
    if (amountCol || amountDerived) {
      fieldConfidence.lineAmount = amountDerived
        ? 0.6
        : lineAmount !== null && amountCol
          ? amountCol.confidence
          : 0;
    }
    if (specCol) fieldConfidence.spec = spec ? specCol.confidence : 0;
    if (remarkCol) fieldConfidence.remark = remark ? remarkCol.confidence : 0;

    // 行需要复核: 缺食材名 (持久化必填) 或 任一持久化字段置信度低于阈值。
    const persistedConfs = PERSISTED_LINE_FIELDS.map((f) => fieldConfidence[f]).filter(
      (v): v is number => v !== undefined,
    );
    const hasLowConf = persistedConfs.some((v) => v < CONFIDENCE_THRESHOLD);
    const needsReview = !ingredientName || hasLowConf;

    return {
      ingredientName,
      unit,
      quantity,
      unitPrice,
      lineAmount,
      spec,
      remark,
      fieldConfidence,
      needsReview,
    };
  });

  const supplierName = headField('supplierName', columns, dataRows);
  const deliveryDate = headField('deliveryDate', columns, dataRows);

  const unmappedHeaders = columns.filter((c) => c.field === null).map((c) => c.header);
  const detectedNonPersisted = NON_PERSISTED_FIELDS.filter((f) => colByField(f) !== null);

  const overallNeedsReview =
    supplierName.needsReview ||
    deliveryDate.needsReview ||
    lines.length === 0 ||
    lines.some((l) => l.needsReview);

  return {
    columns,
    supplierName,
    deliveryDate,
    lines,
    unmappedHeaders,
    detectedNonPersisted,
    overallNeedsReview,
  };
}

/**
 * 极简 CSV 解析 (客户端, 仅用于导入对话框直接读 CSV; Excel 二进制走 SmartBI
 * 解析端点后把 {headers, rows} 喂给 mapParsedTableToDraft)。
 * 支持双引号包裹字段与字段内逗号/换行。返回 {headers, rows(对象)}。
 */
export function parseCsv(text: string): ParsedTable {
  const records: string[][] = [];
  let field = '';
  let record: string[] = [];
  let inQuotes = false;
  const pushField = () => {
    record.push(field);
    field = '';
  };
  const pushRecord = () => {
    pushField();
    records.push(record);
    record = [];
  };
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      pushField();
    } else if (ch === '\r') {
      // 忽略, 由 \n 收尾
    } else if (ch === '\n') {
      pushRecord();
    } else {
      field += ch;
    }
  }
  // 收尾最后一个字段/记录 (无尾换行时)。
  if (field !== '' || record.length > 0) pushRecord();

  const nonEmpty = records.filter((r) => r.some((c) => String(c).trim() !== ''));
  if (nonEmpty.length === 0) return { headers: [], rows: [] };

  const headers = (nonEmpty[0] as string[]).map((h) => String(h).trim());
  const rows = nonEmpty.slice(1).map((r) => {
    const obj: Record<string, unknown> = {};
    headers.forEach((h, idx) => {
      obj[h] = r[idx] ?? '';
    });
    return obj;
  });
  return { headers, rows };
}
