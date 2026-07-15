import type {
  MaterialNodeData,
  ProcessNodeData,
  ProcessPort,
  ProductProcessWorkflowDefinition,
} from './types';
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

const SYSTEM_ALIASES: Record<string, string> = {
  mg: 'mg', '毫克': 'mg', g: 'g', '克': 'g', kg: 'kg', '公斤': 'kg', '千克': 'kg',
  t: 't', '吨': 't', jin: 'jin', '斤': 'jin', ml: 'ml', '毫升': 'ml', l: 'l', '升': 'l',
  pcs: 'pcs', '件': 'pcs', '个': 'pcs', '只': 'pcs', portion: 'portion', '份': 'portion',
  box: 'box', '盒': 'box', case: 'case', '箱': 'case', bag: 'bag', '袋': 'bag',
  bottle: 'bottle', '瓶': 'bottle',
};

const CHINESE_UNIT_LABELS: Record<string, string> = {
  pcs: '件', portion: '份', box: '盒', case: '箱', bag: '袋', bottle: '瓶',
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
  if (materialKind === 'RAW_MATERIAL' || materialKind === 'SEMI_FINISHED') return 'kg';
  return displayUnit(skuBaseUnit, normalizedAliases(customAliases));
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
      warnings.push({
        code: 'SKU_UNIT_UNKNOWN',
        message: `SKU ${material.skuId} 缺少规范主单位`,
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
    const process = processNode.data as ProcessNodeData;
    const primaryInput = primaryPort(process.ports, 'INPUT');
    const primaryOutput = primaryPort(process.ports, 'OUTPUT');
    if (primaryInput) process.inputUnit = primaryInput.unit;
    if (primaryOutput) process.outputUnit = primaryOutput.unit;
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

function displayUnit(value: string | null | undefined, aliases: Record<string, string>): string {
  const code = normalizeUnit(value, aliases);
  if (!code) return '';
  return CHINESE_UNIT_LABELS[code] || code;
}

function key(value: string): string {
  return value.trim().toLowerCase();
}
