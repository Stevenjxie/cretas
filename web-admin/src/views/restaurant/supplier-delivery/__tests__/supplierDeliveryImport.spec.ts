/**
 * Unit tests for WP-6 (P2) supplier-delivery import field-detection/mapping
 * pure helper (`supplierDeliveryImport.ts`).
 *
 * Focus: the PURE column->field detection + row mapping + confidence/needsReview
 * contract. No Vue, no network. Mirrors restaurant/__tests__ style (vitest).
 *
 * Covered:
 *  - detectColumns: keyword buckets, exact vs contains, 单价/单位 disambiguation,
 *    one-field-per-header greedy assignment, unmapped headers.
 *  - parseNumeric: currency/comma/unit stripping, null for non-numeric (no 0 fill).
 *  - normalizeDate: YYYY/MM/DD, 中文, DD/MM/YYYY → ISO; passthrough on unknown.
 *  - mapParsedTableToDraft: line mapping, derived amount (qty×price), needsReview
 *    for missing ingredient / low confidence, head field (supplier/date) extraction,
 *    non-persisted (spec/remark) detection, no fake values for unmapped fields.
 *  - parseCsv: quoted fields with embedded commas, header/row split.
 */
import { describe, it, expect } from 'vitest';
import {
  detectColumns,
  detectImportFileKind,
  parseNumeric,
  normalizeDate,
  parseExcelArrayBuffer,
  parseCsv,
  mapParsedTableToDraft,
  tableFromRows,
  CONFIDENCE_THRESHOLD,
  type ParsedTable,
} from '../supplierDeliveryImport';

describe('parseNumeric', () => {
  it('strips currency symbols and thousands separators', () => {
    expect(parseNumeric('¥1,234.50')).toBe(1234.5);
    expect(parseNumeric('3.2')).toBe(3.2);
    expect(parseNumeric('50kg')).toBe(50);
    expect(parseNumeric(42)).toBe(42);
  });

  it('returns null for non-numeric (never fabricates 0)', () => {
    expect(parseNumeric('')).toBeNull();
    expect(parseNumeric('待定')).toBeNull();
    expect(parseNumeric(null)).toBeNull();
    expect(parseNumeric(undefined)).toBeNull();
    expect(parseNumeric('-')).toBeNull();
  });
});

describe('normalizeDate', () => {
  it('normalizes common formats to ISO YYYY-MM-DD', () => {
    expect(normalizeDate('2026/6/1')).toBe('2026-06-01');
    expect(normalizeDate('2026-06-01')).toBe('2026-06-01');
    expect(normalizeDate('2026年6月1日')).toBe('2026-06-01');
    expect(normalizeDate('01/06/2026')).toBe('2026-01-06');
  });

  it('passes through unrecognized text (defer to human)', () => {
    expect(normalizeDate('上周三')).toBe('上周三');
  });
});

describe('detectColumns', () => {
  it('maps standard Chinese headers to draft fields', () => {
    const cols = detectColumns(['供应商', '送货日期', '食材名称', '规格', '单位', '数量', '单价', '金额', '备注']);
    const byField = Object.fromEntries(cols.filter((c) => c.field).map((c) => [c.field, c.header]));
    expect(byField.supplierName).toBe('供应商');
    expect(byField.deliveryDate).toBe('送货日期');
    expect(byField.ingredientName).toBe('食材名称');
    expect(byField.spec).toBe('规格');
    expect(byField.unit).toBe('单位');
    expect(byField.quantity).toBe('数量');
    expect(byField.unitPrice).toBe('单价');
    expect(byField.lineAmount).toBe('金额');
    expect(byField.remark).toBe('备注');
  });

  it('disambiguates 单价 (unitPrice) from 单位 (unit)', () => {
    const cols = detectColumns(['单位', '单价']);
    const unit = cols.find((c) => c.header === '单位');
    const price = cols.find((c) => c.header === '单价');
    expect(unit?.field).toBe('unit');
    expect(price?.field).toBe('unitPrice');
  });

  it('assigns each draft field to at most one header (greedy by confidence)', () => {
    const cols = detectColumns(['名称', '商品名称']);
    const ingredientCols = cols.filter((c) => c.field === 'ingredientName');
    expect(ingredientCols).toHaveLength(1);
  });

  it('leaves unknown headers unmapped (field=null)', () => {
    const cols = detectColumns(['门店', '???']);
    expect(cols.every((c) => c.field === null)).toBe(true);
  });
});

describe('mapParsedTableToDraft', () => {
  const fullTable: ParsedTable = {
    headers: ['供应商', '送货日期', '食材名称', '规格', '单位', '数量', '单价', '金额', '备注'],
    rows: [
      { 供应商: '鲜丰农产', 送货日期: '2026/6/1', 食材名称: '土豆', 规格: '中号', 单位: 'kg', 数量: '50', 单价: '3.2', 金额: '160', 备注: '新鲜' },
      { 供应商: '鲜丰农产', 送货日期: '2026/6/1', 食材名称: '西红柿', 规格: '大', 单位: 'kg', 数量: '30', 单价: '4', 金额: '120', 备注: '' },
    ],
  };

  it('maps rows into draft lines with values from detected columns', () => {
    const r = mapParsedTableToDraft(fullTable);
    expect(r.lines).toHaveLength(2);
    expect(r.lines[0].ingredientName).toBe('土豆');
    expect(r.lines[0].unit).toBe('kg');
    expect(r.lines[0].quantity).toBe(50);
    expect(r.lines[0].unitPrice).toBe(3.2);
    expect(r.lines[0].lineAmount).toBe(160);
    expect(r.lines[0].spec).toBe('中号');
    expect(r.lines[0].needsReview).toBe(false);
  });

  it('extracts head fields supplier + date with high confidence', () => {
    const r = mapParsedTableToDraft(fullTable);
    expect(r.supplierName.value).toBe('鲜丰农产');
    expect(r.supplierName.needsReview).toBe(false);
    expect(r.deliveryDate.value).toBe('2026-06-01');
    expect(r.deliveryDate.needsReview).toBe(false);
    expect(r.overallNeedsReview).toBe(false);
  });

  it('flags spec/remark as detected-but-non-persisted', () => {
    const r = mapParsedTableToDraft(fullTable);
    expect(r.detectedNonPersisted).toContain('spec');
    expect(r.detectedNonPersisted).toContain('remark');
  });

  it('derives lineAmount from qty×price when amount column missing (medium confidence)', () => {
    const t: ParsedTable = {
      headers: ['食材名称', '数量', '单价'],
      rows: [{ 食材名称: '鸡蛋', 数量: '10', 单价: '0.8' }],
    };
    const r = mapParsedTableToDraft(t);
    expect(r.lines[0].lineAmount).toBe(8);
    expect(r.lines[0].fieldConfidence.lineAmount).toBeLessThan(CONFIDENCE_THRESHOLD);
  });

  it('marks line needsReview when ingredient name missing (no fabrication)', () => {
    const t: ParsedTable = {
      headers: ['食材名称', '数量', '单位'],
      rows: [{ 食材名称: '', 数量: '5', 单位: '袋' }],
    };
    const r = mapParsedTableToDraft(t);
    expect(r.lines[0].ingredientName).toBeNull();
    expect(r.lines[0].needsReview).toBe(true);
    expect(r.overallNeedsReview).toBe(true);
  });

  it('leaves unmapped head fields null + needsReview (no fake date/supplier)', () => {
    const t: ParsedTable = {
      headers: ['食材名称', '数量'],
      rows: [{ 食材名称: '青菜', 数量: '3' }],
    };
    const r = mapParsedTableToDraft(t);
    expect(r.supplierName.value).toBeNull();
    expect(r.supplierName.needsReview).toBe(true);
    expect(r.deliveryDate.value).toBeNull();
    expect(r.deliveryDate.needsReview).toBe(true);
  });

  it('skips fully-empty rows', () => {
    const t: ParsedTable = {
      headers: ['食材名称', '数量'],
      rows: [{ 食材名称: '豆腐', 数量: '2' }, { 食材名称: '', 数量: '' }],
    };
    const r = mapParsedTableToDraft(t);
    expect(r.lines).toHaveLength(1);
  });

  it('reports unmapped headers transparently', () => {
    const t: ParsedTable = {
      headers: ['食材名称', '门店编号'],
      rows: [{ 食材名称: '鱼', 门店编号: 'S01' }],
    };
    const r = mapParsedTableToDraft(t);
    expect(r.unmappedHeaders).toContain('门店编号');
  });
});

describe('parseCsv', () => {
  it('parses headers and rows, handling quoted commas', () => {
    const csv = '食材名称,数量,备注\n"土豆,精选",50,"今日,到货"\n西红柿,30,';
    const t = parseCsv(csv);
    expect(t.headers).toEqual(['食材名称', '数量', '备注']);
    expect(t.rows).toHaveLength(2);
    expect((t.rows[0] as Record<string, unknown>)['食材名称']).toBe('土豆,精选');
    expect((t.rows[0] as Record<string, unknown>)['备注']).toBe('今日,到货');
  });

  it('returns empty for blank input', () => {
    expect(parseCsv('   ').headers).toHaveLength(0);
  });
});

describe('file import helpers', () => {
  it('detects CSV and Excel file kinds from extension or mime type', () => {
    expect(detectImportFileKind('进货单.xlsx')).toBe('excel');
    expect(detectImportFileKind('进货单.xls')).toBe('excel');
    expect(detectImportFileKind('进货单.csv')).toBe('csv');
    expect(detectImportFileKind('upload.bin', 'application/vnd.ms-excel')).toBe('excel');
    expect(detectImportFileKind('upload.bin', 'text/csv')).toBe('csv');
    expect(detectImportFileKind('进货单.pdf')).toBeNull();
  });

  it('converts table rows into ParsedTable and skips empty rows', () => {
    const table = tableFromRows([
      ['供应商', '送货日期', '食材名称', '数量'],
      ['鲜丰农产', '2026-06-01', '土豆', '50'],
      ['', '', '', ''],
    ]);
    expect(table.headers).toEqual(['供应商', '送货日期', '食材名称', '数量']);
    expect(table.rows).toHaveLength(1);
    expect((table.rows[0] as Record<string, unknown>)['食材名称']).toBe('土豆');
  });

  it('parses first Excel sheet into ParsedTable', async () => {
    const XLSX = await import('xlsx');
    const workbook = XLSX.utils.book_new();
    const sheet = XLSX.utils.aoa_to_sheet([
      ['供应商', '送货日期', '食材名称', '单位', '数量', '单价'],
      ['鲜丰农产', '2026-06-01', '土豆', 'kg', 50, 3.2],
    ]);
    XLSX.utils.book_append_sheet(workbook, sheet, '送货单');
    const buffer = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer;

    const table = await parseExcelArrayBuffer(buffer);
    const mapped = mapParsedTableToDraft(table);

    expect(table.headers).toEqual(['供应商', '送货日期', '食材名称', '单位', '数量', '单价']);
    expect(mapped.supplierName.value).toBe('鲜丰农产');
    expect(mapped.deliveryDate.value).toBe('2026-06-01');
    expect(mapped.lines[0].ingredientName).toBe('土豆');
    expect(mapped.lines[0].lineAmount).toBe(160);
  });
});
