import type {
  MaterialNodeData,
  ProcessNodeData,
  ProcessPort,
  ProductProcessWorkflowDefinition,
} from './types';
import type { UnitCatalogItem, UnitDimension } from '@/api/unitContract';
import { toPlainWorkflowValue } from './workflowModel';

export interface WorkflowUnitConversionRef {
  id: string;
  version: number;
  productTypeId: string;
  fromUnitCode: string;
  toUnitCode: string;
}

export interface WorkflowSkuUnitContract {
  productTypeId: string;
  primaryUnit: string;
  conversions: WorkflowUnitConversionRef[];
}

export interface WorkflowUnitContext {
  aliases?: Record<string, string>;
  catalog?: UnitCatalogItem[];
  products: Record<string, WorkflowSkuUnitContract>;
}

export interface WorkflowUnitIssue {
  code: 'SKU_UNIT_UNKNOWN' | 'CONVERSION_REQUIRED' | 'CONVERSION_STALE';
  message: string;
  nodeId: string;
  portId?: string;
}

export interface WorkflowUnitReconciliationResult {
  definition: ProductProcessWorkflowDefinition;
  errors: WorkflowUnitIssue[];
  warnings: WorkflowUnitIssue[];
}

export interface FixedRatioQuantities {
  inputQuantity: number;
  outputQuantity: number;
}

const SYSTEM_ALIASES: Record<string, string> = {
  mg: 'mg', '毫克': 'mg', g: 'g', '克': 'g', kg: 'kg', '公斤': 'kg', '千克': 'kg',
  t: 't', '吨': 't', jin: 'jin', '斤': 'jin', ml: 'ml', '毫升': 'ml', l: 'l', '升': 'l',
  pcs: 'pcs', '件': 'pcs', '个': 'pcs', '只': 'pcs', portion: 'portion', '份': 'portion',
  box: 'box', '盒': 'box', case: 'case', '箱': 'case', bag: 'bag', '袋': 'bag',
  bottle: 'bottle', '瓶': 'bottle',
  mm: 'mm', '毫米': 'mm', cm: 'cm', '厘米': 'cm', m: 'm', '米': 'm', km: 'km', '千米': 'km',
};

const PHYSICAL_DIMENSIONS = new Set<UnitDimension>(['MASS', 'VOLUME', 'LENGTH']);
const FALLBACK_DIMENSIONS: Record<string, UnitDimension> = {
  mg: 'MASS', g: 'MASS', kg: 'MASS', t: 'MASS', jin: 'MASS',
  ml: 'VOLUME', l: 'VOLUME',
  mm: 'LENGTH', cm: 'LENGTH', m: 'LENGTH', km: 'LENGTH',
};

const PHYSICAL_BASE_FACTORS: Record<string, number> = {
  mg: 0.001, g: 1, kg: 1000, t: 1_000_000, jin: 500,
  ml: 1, l: 1000,
  mm: 1, cm: 10, m: 1000, km: 1_000_000,
};

/**
 * 规范码 → 中文显示名。
 *
 * ⚠️ 这张表必须与后端 `UnitDisplayNames.java` **逐条一致** —— 它是同一份权威的第二份拷贝。
 * 2026-08-12 实测: 后端 16 条, 这里只抄了 6 条, `pack` / `roll` / `crate` / `can` 等全缺,
 * 而 F006 与 LIUSHANMEN 正在用 `pack`(成品) 和 `roll`(透明气调膜) —— 缺的那些会**原样显示英文**。
 * 判据: 手写清单就是缺陷源; 改这里必须同时对一遍后端那份。
 */
const CHINESE_UNIT_LABELS: Record<string, string> = {
  pcs: '件', portion: '份', box: '盒', case: '箱', bag: '袋', pack: '包',
  bottle: '瓶', can: '罐', crate: '框', pail: '桶', roll: '卷', slice: '片',
  sheet: '张', tray: '托盘', plate: '板', item: '项',
};

export function forkWorkflowUnitReviewDraft(
  published: ProductProcessWorkflowDefinition,
): ProductProcessWorkflowDefinition {
  const draft = toPlainWorkflowValue(published);
  delete draft.id;
  delete draft.lockVersion;
  draft.status = 'DRAFT';
  draft.version = published.version + 1;
  draft.unitReviewRequired = false;
  return draft;
}

export function workflowReportingUnit(
  materialKind: 'RAW_MATERIAL' | 'SEMI_FINISHED' | 'FINISHED_GOOD',
  skuBaseUnit?: string | null,
  customAliases?: Record<string, string>,
): string {
  const aliases = normalizedAliases(customAliases);
  if ((materialKind === 'RAW_MATERIAL' || materialKind === 'SEMI_FINISHED')
    && isWorkflowWeightUnit(skuBaseUnit, aliases)) return 'kg';
  return displayUnit(skuBaseUnit, aliases);
}

/** Weight-based production is reported in kg. Count/package units must keep the SKU unit verbatim. */
export function isWorkflowWeightUnit(
  unit: string | null | undefined,
  aliases: Record<string, string> = normalizedAliases(),
): boolean {
  const code = normalizeUnit(unit, aliases);
  return code !== null && ['mg', 'g', 'kg', 't', 'jin'].includes(code);
}

/** Reads only the two quantities from the canonical “N unit = N unit” expression. */
export function parseFixedRatioQuantities(expression: string | null | undefined): FixedRatioQuantities | null {
  if (!expression) return null;
  const sides = expression.split('=');
  if (sides.length !== 2) return null;
  const inputQuantity = leadingPositiveNumber(sides[0]);
  const outputQuantity = leadingPositiveNumber(sides[1]);
  if (inputQuantity === null || outputQuantity === null) return null;
  return { inputQuantity, outputQuantity };
}

/** Returns a system physical dimension. COUNT/PACKAGE/unknown units intentionally return UNKNOWN. */
export function workflowUnitDimension(
  unit: string | null | undefined,
  catalog: UnitCatalogItem[] = [],
  customAliases?: Record<string, string>,
): UnitDimension {
  const aliases = normalizedAliases(customAliases);
  const code = normalizeUnit(unit, aliases);
  if (!code) return 'UNKNOWN';
  const catalogItem = catalog.find((item) => {
    const candidates = [item.code, item.label, item.baseCode]
      .map((value) => normalizeUnit(value, aliases));
    return candidates.includes(code);
  });
  return catalogItem?.dimension || FALLBACK_DIMENSIONS[code] || 'UNKNOWN';
}

/** Only physical units in the same system dimension can omit a manually configured ratio. */
export function areWorkflowUnitsAutoConvertible(
  fromUnit: string | null | undefined,
  toUnit: string | null | undefined,
  catalog: UnitCatalogItem[] = [],
  customAliases?: Record<string, string>,
): boolean {
  const fromDimension = workflowUnitDimension(fromUnit, catalog, customAliases);
  const toDimension = workflowUnitDimension(toUnit, catalog, customAliases);
  return PHYSICAL_DIMENSIONS.has(fromDimension) && fromDimension === toDimension;
}

export function workflowAutoConversionEquation(
  fromUnit: string | null | undefined,
  toUnit: string | null | undefined,
  customAliases?: Record<string, string>,
): string | null {
  const aliases = normalizedAliases(customAliases);
  const fromCode = normalizeUnit(fromUnit, aliases);
  const toCode = normalizeUnit(toUnit, aliases);
  if (!fromCode || !toCode) return null;
  if (fromCode === toCode) return '同单位，无需换算';
  const fromFactor = PHYSICAL_BASE_FACTORS[fromCode];
  const toFactor = PHYSICAL_BASE_FACTORS[toCode];
  if (!fromFactor || !toFactor || workflowUnitDimension(fromCode) !== workflowUnitDimension(toCode)) return null;
  return `1${displayUnit(fromUnit, aliases)} = ${formatQuantity(fromFactor / toFactor)}${displayUnit(toUnit, aliases)}`;
}

export function workflowSkuSpecificationEquation(
  unit: string | null | undefined,
  gramsPerUnit: number | null | undefined,
): string | null {
  const grams = Number(gramsPerUnit);
  const label = String(unit || '').trim();
  if (!label || !Number.isFinite(grams) || grams <= 0) return null;
  return `1${label} = ${formatQuantity(grams)}g`;
}

/**
 * Keeps unit contracts separate from production facts:
 * every process port carries its bound SKU reporting unit only. Actual input, output and yield are
 * production-reporting facts. Finished-SKU gramsPerUnit and packaging conversions remain SKU master
 * data and are consumed later by planning/reporting/settlement instead of being copied into Workflow.
 * No historical-data migration is required because standardQuantity/quantityMode are nullable.
 */
export function reconcileProcessPortQuantities(
  input: ProcessNodeData,
  _catalog: UnitCatalogItem[] = [],
): ProcessNodeData {
  const process = toPlainWorkflowValue(input);
  const ports = process.ports.map(withoutQuantityRelationship);

  const nextPrimaryInput = primaryPort(ports, 'INPUT');
  const nextPrimaryOutput = primaryPort(ports, 'OUTPUT');
  if (nextPrimaryInput) process.inputUnit = nextPrimaryInput.unit;
  if (nextPrimaryOutput) process.outputUnit = nextPrimaryOutput.unit;
  process.ports = ports;
  process.conversionRule = { mode: 'ACTUAL_WEIGHT' };
  return process;
}

export function reconcileWorkflowUnits(
  input: ProductProcessWorkflowDefinition,
  context: WorkflowUnitContext,
): WorkflowUnitReconciliationResult {
  const definition = toPlainWorkflowValue(input);
  const errors: WorkflowUnitIssue[] = [];
  const warnings: WorkflowUnitIssue[] = [];
  const processNodes = definition.nodes.filter((node) => node.kind === 'PROCESS');

  definition.nodes.forEach((materialNode) => {
    if (materialNode.kind === 'PROCESS') return;
    const material = materialNode.data as MaterialNodeData;
    if (!material.skuId || material.bound === false) {
      warnings.push({
        code: 'SKU_UNIT_UNKNOWN',
        message: `物料「${material.name}」尚未绑定 SKU，单位无法自动校准`,
        nodeId: materialNode.id,
      });
      return;
    }
    const product = context.products[material.skuId];
    const targetUnit = workflowReportingUnit(materialNode.kind, product?.primaryUnit, context.aliases);
    if (!targetUnit) {
      errors.push({
        code: 'SKU_UNIT_UNKNOWN',
        message: `SKU ${material.skuId} 缺少规范主单位，请先维护 SKU 单位后再发布`,
        nodeId: materialNode.id,
      });
      return;
    }

    material.baseUnit = targetUnit;
    processNodes.forEach((processNode) => {
      const process = processNode.data as ProcessNodeData;
      process.ports.forEach((port) => {
        if (port.materialNodeId === materialNode.id) {
          port.unit = targetUnit;
          delete port.conversionRefId;
          delete port.conversionVersion;
        }
      });
    });
  });

  processNodes.forEach((processNode) => {
    processNode.data = reconcileProcessPortQuantities(
      processNode.data as ProcessNodeData,
      context.catalog || [],
    );
  });

  return { definition, errors, warnings };
}

function primaryPort(ports: ProcessPort[], direction: 'INPUT' | 'OUTPUT'): ProcessPort | undefined {
  return ports.filter((port) => port.direction === direction)
    .sort((left, right) => left.ordinal - right.ordinal)[0];
}

function normalizedAliases(custom?: Record<string, string>): Record<string, string> {
  const result = { ...SYSTEM_ALIASES };
  Object.entries(custom || {}).forEach(([alias, code]) => {
    result[key(alias)] = key(code);
  });
  return result;
}

function normalizeUnit(value: string | null | undefined, aliases: Record<string, string>): string | null {
  if (!value?.trim()) return null;
  const normalized = key(value);
  return aliases[normalized] || normalized;
}

/**
 * 🔴 2026-08-12 (Steve 实测「一会儿显示 kg→box, 一会儿又变回盒产出」):
 *
 * 画布各处直接渲染**原始存储字符串** —— `product_types.unit` 存的是规范码 `box`,
 * 而 `work_processes.unit` / 端口上存的是中文 `盒`。两处都对(后端 `alias(box,盒)` 把它们
 * 归一到同一个码, 发布闸/报工/结算都过), **只是显示层没统一**, 于是同一个单位在同一张画布上
 * 两种写法, 看着像在反复横跳。
 *
 * prod 实测受影响的不只是 F006: LIUSHANMEN(真人在用)有 2 张启用画布共 4 个端口
 * 是 `端口=盒 / SKU=box`。
 *
 * 这里把已有的 displayUnit 导出给画布组件用 —— 中文原样保留, 规范码翻成中文, 两端都收敛成「盒」。
 */
export function workflowDisplayUnit(
  value: string | null | undefined,
  aliases: Record<string, string> = {},
): string {
  return displayUnit(value, aliases);
}

function displayUnit(value: string | null | undefined, aliases: Record<string, string>): string {
  const code = normalizeUnit(value, aliases);
  if (!code) return '';
  const original = value?.trim() || '';
  // Preserve an authoritative Chinese SKU label such as “只”; only translate canonical codes.
  if (original && /[^\x00-\x7F]/.test(original)) return original;
  return CHINESE_UNIT_LABELS[code] || code;
}

function key(value: string): string {
  return value.trim().toLowerCase();
}

function leadingPositiveNumber(value: string): number | null {
  const match = value.match(/^\s*([0-9]+(?:\.[0-9]+)?)(?:\s+|$)/);
  if (!match) return null;
  const parsed = Number(match[1]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function withoutQuantityRelationship(port: ProcessPort): ProcessPort {
  const next = { ...port };
  delete next.quantityMode;
  delete next.standardQuantity;
  return next;
}

function formatQuantity(value: number): string {
  return Number(value.toFixed(6)).toString();
}
