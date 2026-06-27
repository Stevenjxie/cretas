/**
 * Print template schema types — Sprint 3 Track-J C-PRT-EDITOR-1.
 *
 * Stored in FormTemplate.schemaJson wrapped as
 *   {type: "object", properties: {_printSchema: PrintTemplateSchema}}
 * to satisfy FormTemplateServiceImpl.validateSchemaJson() Formily shape
 * without changing validation logic (see design doc §3.2).
 *
 * Coordinate system: PDF points (pt), origin top-left.
 * A4 portrait = 595 x 842 pt.
 */

export type ElementType =
  | 'text' | 'field' | 'table'
  | 'qr' | 'barcode' | 'image' | 'stamp';

export type PrintAlign = 'left' | 'center' | 'right';

export type ValueFormat = 'currency' | 'date' | 'percent' | 'qty';

export interface BaseElement {
  id: string;            // client-side stable id (uuid)
  type: ElementType;
  x: number;             // pt
  y: number;
  width?: number;
  height?: number;
}

export interface TextElement extends BaseElement {
  type: 'text';
  text: string;
  fontSize: number;
  bold?: boolean;
  italic?: boolean;
  color?: string;        // hex like #1f2937
  align?: PrintAlign;
}

export interface FieldElement extends BaseElement {
  type: 'field';
  binding: string;       // e.g. {{order.orderNumber}}
  fontSize: number;
  bold?: boolean;
  color?: string;
  align?: PrintAlign;
  format?: ValueFormat | null;
  dateFormat?: string;
  emptyText?: string;    // fallback for nullish (default '-')
}

export interface TableColumn {
  header: string;
  binding: string;       // {{item.field}} resolved per row
  width: number;
  align?: PrintAlign;
  format?: ValueFormat | null;
}

export interface TableElement extends BaseElement {
  type: 'table';
  width: number;
  binding: string;       // {{entity.items}}
  columns: TableColumn[];
  rowHeight: number;
  headerBg?: string;
  headerFontSize?: number;
  bodyFontSize?: number;
}

export interface QrElement extends BaseElement {
  type: 'qr';
  content: string;       // may contain {{}}
  size: number;          // width == height for QR
}

export interface BarcodeElement extends BaseElement {
  type: 'barcode';
  content: string;
  width: number;
  height: number;
  format?: 'CODE128' | 'EAN13';
}

export interface ImageElement extends BaseElement {
  type: 'image';
  src: string;
  width: number;
  height: number;
}

export interface StampElement extends BaseElement {
  type: 'stamp';
  stampId: string;       // factory setting id; 'default' = factory default stamp
  size: number;
  opacity?: number;
}

export type PrintElement =
  | TextElement | FieldElement | TableElement | QrElement
  | BarcodeElement | ImageElement | StampElement;

export type PrintElementDraft =
  PrintElement extends infer E
    ? E extends PrintElement
      ? Omit<E, 'id'>
      : never
    : never;

export type Orientation = 'portrait' | 'landscape';

export interface PrintCanvas {
  width: number;
  height: number;
  orientation: Orientation;
  marginTop?: number;
  marginBottom?: number;
  marginLeft?: number;
  marginRight?: number;
}

export interface PrintTemplateSchema {
  version: 1;
  canvas: PrintCanvas;
  elements: PrintElement[];
  meta?: {
    name?: string;
    description?: string;
  };
}

export interface FormilyWrappedPrintSchema {
  type: 'object';
  properties: {
    _printSchema: PrintTemplateSchema;
  };
}

export function wrapForStorage(schema: PrintTemplateSchema): FormilyWrappedPrintSchema {
  return { type: 'object', properties: { _printSchema: schema } };
}

export function unwrapFromStorage(raw: unknown): PrintTemplateSchema | null {
  if (raw === null || raw === undefined || raw === '') return null;
  // Issue #719: Spring may deliver schemaJson either as a JSON string (default
  // String column serialization) or as an already-parsed Object (when the DTO
  // field is typed Map<String,Object> / JsonNode). JSON.parse on an object
  // coerces it to "[object Object]" and throws → silent null. Accept both.
  let parsed: unknown;
  if (typeof raw === 'string') {
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      console.error('[unwrapFromStorage] JSON.parse failed', { raw, error: e });
      return null;
    }
  } else if (typeof raw === 'object') {
    parsed = raw;
  } else {
    return null;
  }
  const props = (parsed as { properties?: { _printSchema?: unknown } } | null)?.properties;
  if (props && props._printSchema) {
    return props._printSchema as PrintTemplateSchema;
  }
  return null;
}

export const DEFAULT_A4_PORTRAIT: PrintCanvas = {
  width: 595,
  height: 842,
  orientation: 'portrait',
  marginTop: 40,
  marginBottom: 40,
  marginLeft: 50,
  marginRight: 50,
};

export const ENTITY_TYPES = [
  { code: 'PRINT_SALES_ORDER', label: '销售单', icon: 'Document' },
  { code: 'PRINT_PURCHASE_ORDER', label: '采购单', icon: 'ShoppingCart' },
  { code: 'PRINT_QUOTATION', label: '报价单', icon: 'Money' },
  { code: 'PRINT_PRODUCTION_TASK', label: '生产任务单', icon: 'Operation' },
  { code: 'PRINT_MATERIAL_REQUISITION', label: '领料单', icon: 'Goods' },
  { code: 'PRINT_WEIGHING_SLIP', label: '称重单', icon: 'Scale' },
] as const;

export type PrintEntityType = typeof ENTITY_TYPES[number]['code'];

export function isValidEntityType(code: string): code is PrintEntityType {
  return ENTITY_TYPES.some(et => et.code === code);
}

export function emptySchema(): PrintTemplateSchema {
  return {
    version: 1,
    canvas: { ...DEFAULT_A4_PORTRAIT },
    elements: [],
  };
}
