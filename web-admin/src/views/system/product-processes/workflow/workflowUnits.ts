import type {
  MaterialNodeData,
  ProcessNodeData,
  ProcessPort,
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowNode,
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

export function reconcileWorkflowUnits(
  input: ProductProcessWorkflowDefinition,
  context: WorkflowUnitContext,
): WorkflowUnitReconciliationResult {
  const definition = toPlainWorkflowValue(input);
  const errors: WorkflowUnitIssue[] = [];
  const warnings: WorkflowUnitIssue[] = [];
  const aliases = normalizedAliases(context.aliases);
  const processNodes = definition.nodes.filter((node) => node.kind === 'PROCESS');

  definition.nodes.filter((node) => node.kind !== 'PROCESS').forEach((materialNode) => {
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
    const targetUnit = normalizeUnit(product?.primaryUnit, aliases);
    if (!product || !targetUnit) {
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
          reconcilePort(processNode, process, port, product, targetUnit, aliases, errors);
        }
      });
    });
  });

  return { definition, errors, warnings };
}

function reconcilePort(
  processNode: ProductProcessWorkflowNode,
  process: ProcessNodeData,
  port: ProcessPort,
  product: WorkflowSkuUnitContract,
  targetUnit: string,
  aliases: Record<string, string>,
  errors: WorkflowUnitIssue[],
): void {
  const hintKey = port.direction === 'OUTPUT' ? 'outputUnit' : 'inputUnit';
  const hintUnit = normalizeUnit(process[hintKey], aliases);
  const portUnit = normalizeUnit(port.unit, aliases);
  if (hintUnit === targetUnit) {
    process[hintKey] = targetUnit;
    port.unit = targetUnit;
    delete port.conversionRefId;
    delete port.conversionVersion;
    return;
  }

  const reportUnit = hintUnit || portUnit;
  const conversion = product.conversions.find((candidate) =>
    candidate.id === port.conversionRefId
      && candidate.version === port.conversionVersion
      && candidate.productTypeId === product.productTypeId);
  if (!conversion) {
    errors.push({
      code: port.conversionRefId ? 'CONVERSION_STALE' : 'CONVERSION_REQUIRED',
      message: `工序「${process.processName}」单位 ${reportUnit || port.unit} 与 SKU 主单位 ${targetUnit} 不一致，且无有效换算关系`,
      nodeId: processNode.id,
      portId: port.id,
    });
    return;
  }

  const from = normalizeUnit(conversion.fromUnitCode, aliases);
  const to = normalizeUnit(conversion.toUnitCode, aliases);
  const connectsUnits = reportUnit != null
    && ((from === reportUnit && to === targetUnit) || (to === reportUnit && from === targetUnit));
  if (!connectsUnits) {
    errors.push({
      code: 'CONVERSION_STALE',
      message: `换算关系 ${conversion.id} 不适用于 ${reportUnit || port.unit} 与 ${targetUnit}`,
      nodeId: processNode.id,
      portId: port.id,
    });
    return;
  }
  process[hintKey] = reportUnit;
  port.unit = reportUnit;
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

function key(value: string): string {
  return value.trim().toLowerCase();
}
