import { describe, expect, it } from 'vitest';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';
import { applyWorkflowPatches } from '../workflowModel';
import {
  forkWorkflowUnitReviewDraft,
  parseFixedRatioQuantities,
  reconcileProcessPortQuantities,
  reconcileWorkflowUnits,
  workflowAutoConversionEquation,
  workflowSkuSpecificationEquation,
  workflowUnitDimension,
  type WorkflowUnitContext,
} from '../workflowUnits';

describe('reconcileWorkflowUnits', () => {
  it('formats scientific conversions and SKU standard weights as read-only business equations', () => {
    expect(workflowAutoConversionEquation('kg', 'kg')).toBe('同单位，无需换算');
    expect(workflowAutoConversionEquation('kg', 'g')).toBe('1kg = 1000g');
    expect(workflowAutoConversionEquation('g', 'kg')).toBe('1g = 0.001kg');
    expect(workflowAutoConversionEquation('只', '半只')).toBeNull();
    expect(workflowSkuSpecificationEquation('盒', 800)).toBe('1盒 = 800g');
  });

  it('recognizes MASS, VOLUME, and LENGTH as system physical dimensions', () => {
    expect(workflowUnitDimension('公斤')).toBe('MASS');
    expect(workflowUnitDimension('ml')).toBe('VOLUME');
    expect(workflowUnitDimension('厘米')).toBe('LENGTH');
    expect(workflowUnitDimension('盒')).toBe('UNKNOWN');
  });

  it('keeps only units even when the server catalog can physically convert them', () => {
    const catalog = [
      { code: 'oz', label: '盎司', dimension: 'MASS' as const, baseCode: 'g', displayScale: 3 },
      { code: 'lb', label: '磅', dimension: 'MASS' as const, baseCode: 'g', displayScale: 3 },
    ];
    const reconciled = reconcileProcessPortQuantities({
      workProcessId: 'weigh', processName: '称重', inputUnit: 'oz', outputUnit: 'lb',
      ports: [
        { id: 'in', direction: 'INPUT', unit: 'oz', ordinal: 0 },
        { id: 'out', direction: 'OUTPUT', unit: 'lb', ordinal: 0 },
      ],
      conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
    }, catalog);

    expect(reconciled.ports[0]).not.toHaveProperty('quantityMode');
    expect(reconciled.ports[1]).not.toHaveProperty('quantityMode');
    expect(reconciled.conversionRule).toEqual({ mode: 'ACTUAL_WEIGHT' });
  });

  it('drops a legacy planned ratio because actual quantities belong to reporting', () => {
    const migrated = reconcileProcessPortQuantities({
      workProcessId: 'cut', processName: '切分', inputUnit: '只', outputUnit: '盒',
      ports: [
        { id: 'in', direction: 'INPUT', unit: '只', ordinal: 0 },
        { id: 'out', direction: 'OUTPUT', unit: '盒', ordinal: 0 },
      ],
      conversionRule: { mode: 'FIXED_RATIO', expression: '2 只 = 6 盒' },
      reportingRequired: true,
    });

    expect(migrated.ports[0]).not.toHaveProperty('quantityMode');
    expect(migrated.ports[0]).not.toHaveProperty('standardQuantity');
    expect(migrated.ports[1]).not.toHaveProperty('quantityMode');
    expect(migrated.ports[1]).not.toHaveProperty('standardQuantity');
    expect(migrated.conversionRule).toEqual({ mode: 'ACTUAL_WEIGHT' });
  });

  it('clears planned quantities from every input and output port', () => {
    const reconciled = reconcileProcessPortQuantities({
      workProcessId: 'split', processName: '分流', inputUnit: 'kg', outputUnit: 'g',
      ports: [
        { id: 'main', direction: 'INPUT', unit: 'kg', ordinal: 0 },
        { id: 'seasoning', direction: 'INPUT', unit: 'g', ordinal: 1 },
        { id: 'mass-output', direction: 'OUTPUT', unit: 'g', ordinal: 0 },
        { id: 'box-output', direction: 'OUTPUT', unit: '盒', ordinal: 1, standardQuantity: 4 },
      ],
      conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
    });

    expect(reconciled.ports.map(({ id, quantityMode, standardQuantity }) => ({
      id, quantityMode, standardQuantity,
    }))).toEqual([
      { id: 'main', quantityMode: undefined, standardQuantity: undefined },
      { id: 'seasoning', quantityMode: undefined, standardQuantity: undefined },
      { id: 'mass-output', quantityMode: undefined, standardQuantity: undefined },
      { id: 'box-output', quantityMode: undefined, standardQuantity: undefined },
    ]);
  });

  it('does not revive a planned quantity after unit changes', () => {
    const automatic = reconcileProcessPortQuantities({
      workProcessId: 'pack', processName: '包装', inputUnit: 'kg', outputUnit: 'g',
      ports: [
        { id: 'in', direction: 'INPUT', unit: 'kg', ordinal: 0 },
        { id: 'out', direction: 'OUTPUT', unit: 'g', ordinal: 0, quantityMode: 'FIXED_RATIO', standardQuantity: 12 },
      ],
      conversionRule: { mode: 'FIXED_RATIO', expression: '1 kg = 12 盒' }, reportingRequired: true,
    });
    expect(automatic.ports[1]).not.toHaveProperty('quantityMode');
    expect(automatic.ports[1]).not.toHaveProperty('standardQuantity');

    automatic.ports[1].unit = '盒';
    const fixedAgain = reconcileProcessPortQuantities(automatic);
    expect(fixedAgain.ports[1]).not.toHaveProperty('quantityMode');
    expect(fixedAgain.ports[1]).not.toHaveProperty('standardQuantity');
  });

  it('parses both canonical fixed-ratio quantities when the output unit ends the expression', () => {
    expect(parseFixedRatioQuantities('1.5 只 = 2.25 件')).toEqual({
      inputQuantity: 1.5,
      outputQuantity: 2.25,
    });
    expect(parseFixedRatioQuantities('1 只 = 2 件 ')).toEqual({ inputQuantity: 1, outputQuantity: 2 });
  });

  it('forks a reviewed published definition into a new unsaved draft version', () => {
    const published = definitionWith('g', 'g', 'g');
    published.id = 42;
    published.lockVersion = 7;
    published.status = 'PUBLISHED';
    published.version = 3;
    published.unitReviewRequired = true;

    const draft = forkWorkflowUnitReviewDraft(published);
    expect(draft).toMatchObject({
      status: 'DRAFT',
      version: 4,
      unitReviewRequired: false,
    });
    expect(draft).not.toHaveProperty('id');
    expect(draft).not.toHaveProperty('lockVersion');
  });

  it('normalizes raw-material inventory and reporting units to kg', () => {
    const input = definitionWith('克', 'g', '克', 'RAW_MATERIAL', 'INPUT');
    const result = reconcileWorkflowUnits(input, context('g'));

    expect(materialUnit(result.definition)).toBe('kg');
    expect(processData(result.definition).inputUnit).toBe('kg');
    expect(processData(result.definition).ports[0].unit).toBe('kg');
    expect(result.errors).toEqual([]);
    expect(materialUnit(input)).toBe('克');
  });

  it('keeps a non-weight semi-finished SKU unit instead of forcing kg', () => {
    const input = definitionWith('g', '件', '件', 'SEMI_FINISHED');
    processData(input).ports[0].conversionRefId = 'STALE-CONVERSION';
    processData(input).ports[0].conversionVersion = 3;

    const result = reconcileWorkflowUnits(input, context('件'));

    expect(materialUnit(result.definition)).toBe('件');
    expect(processData(result.definition).outputUnit).toBe('件');
    expect(processData(result.definition).ports[0]).toMatchObject({ unit: '件' });
    expect(processData(result.definition).ports[0].conversionRefId).toBeUndefined();
    expect(processData(result.definition).ports[0].conversionVersion).toBeUndefined();
    expect(result.errors).toEqual([]);
  });

  it('keeps a count-based raw-material unit such as 只 on every connected input port', () => {
    const input = definitionWith('kg', 'kg', 'kg', 'RAW_MATERIAL', 'INPUT');
    const result = reconcileWorkflowUnits(input, context('只'));

    expect(materialUnit(result.definition)).toBe('只');
    expect(processData(result.definition).inputUnit).toBe('只');
    expect(processData(result.definition).ports[0].unit).toBe('只');
    expect(result.errors).toEqual([]);
  });

  it('forces a finished-good output to the SKU base unit even when a weight conversion was selected', () => {
    const input = definitionWith('g', 'g', 'g');
    processData(input).ports[0].conversionRefId = 'BOX-WEIGHT';
    processData(input).ports[0].conversionVersion = 1;

    const result = reconcileWorkflowUnits(input, context('盒', [{
      id: 'BOX-WEIGHT', version: 1, productTypeId: 'P1', fromUnitCode: 'box', toUnitCode: 'g',
    }]));

    expect(materialUnit(result.definition)).toBe('盒');
    expect(processData(result.definition).outputUnit).toBe('盒');
    expect(processData(result.definition).ports[0]).toMatchObject({ unit: '盒' });
    expect(processData(result.definition).ports[0].conversionRefId).toBeUndefined();
    expect(processData(result.definition).ports[0].conversionVersion).toBeUndefined();
    expect(result.errors).toEqual([]);
  });

  it('warns instead of guessing for an unbound material', () => {
    const input = definitionWith('件', '件', '件');
    const data = input.nodes[0].data as { skuId: string; bound: boolean };
    data.skuId = '';
    data.bound = false;
    const result = reconcileWorkflowUnits(input, context('g'));
    expect(result.warnings.map((warning) => warning.code)).toEqual(['SKU_UNIT_UNKNOWN']);
    expect(materialUnit(result.definition)).toBe('件');
  });

  it('blocks persistence when a bound SKU has no authoritative unit contract', () => {
    const input = definitionWith('g', 'g', 'g');
    const result = reconcileWorkflowUnits(input, { products: {} });

    expect(result.errors).toEqual([expect.objectContaining({
      code: 'SKU_UNIT_UNKNOWN',
      nodeId: 'material:finished',
    })]);
    expect(result.errors[0].message).toContain('缺少规范主单位');
  });

  it('derives the shared process hint from the smallest output ordinal', () => {
    const input = definitionWith('g', 'g', 'box');
    input.nodes.splice(1, 0, {
      id: 'material:box', kind: 'FINISHED_GOOD', position: { x: 1, y: 2 }, data: {
        name: '盒装成品', skuId: 'P2', bound: true, baseUnit: 'box',
      },
    });
    const process = input.nodes[2].data as ProcessNodeData;
    process.ports.push({ id: 'output:box', direction: 'OUTPUT', materialNodeId: 'material:box',
      unit: '箱', ordinal: 1 });
    const multiContext = context('g');
    multiContext.products.P2 = { productTypeId: 'P2', primaryUnit: 'case', conversions: [] };

    const result = reconcileWorkflowUnits(input, multiContext);

    expect(processData(result.definition).outputUnit).toBe('g');
    expect(processData(result.definition).ports.map((port) => port.unit)).toEqual(['g', '箱']);
    expect(result.errors).toEqual([]);
  });

  it('rejects malformed conversion reference types in workflow patches', () => {
    const input = definitionWith('g', 'g', 'g');
    const malformed = structuredClone(input.nodes[1]) as unknown as Record<string, unknown>;
    const data = malformed.data as { ports: Array<Record<string, unknown>> };
    data.ports[0].conversionRefId = {};
    data.ports[0].conversionVersion = '3';

    const patched = applyWorkflowPatches(input, [{ op: 'UPSERT_NODE', node: malformed }]);

    expect(patched.errors).toEqual(['Workflow patch batch contains an invalid member']);
  });
});

function context(
  primaryUnit: string,
  conversions: WorkflowUnitContext['products'][string]['conversions'] = [],
): WorkflowUnitContext {
  return { products: { P1: { productTypeId: 'P1', primaryUnit, conversions } } };
}

function definitionWith(
  materialUnitValue: string,
  portUnit: string,
  outputUnit: string,
  materialKind: 'RAW_MATERIAL' | 'SEMI_FINISHED' | 'FINISHED_GOOD' = 'FINISHED_GOOD',
  direction: 'INPUT' | 'OUTPUT' = 'OUTPUT',
): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      { id: 'material:finished', kind: materialKind, position: { x: 1, y: 1 }, data: {
        name: 'SHH0713香辣孜然羊排', skuId: 'P1', bound: true, baseUnit: materialUnitValue,
      } },
      { id: 'process:cold', kind: 'PROCESS', position: { x: 0, y: 1 }, data: {
        workProcessId: 'cold', processName: '冷冻', inputUnit: 'g', outputUnit,
        ports: [{ id: `${direction.toLowerCase()}:final`, direction, materialNodeId: 'material:finished',
          unit: portUnit, ordinal: 0 }],
        conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
      } },
    ],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function materialUnit(definition: ProductProcessWorkflowDefinition): string | undefined {
  return (definition.nodes[0].data as { baseUnit?: string }).baseUnit;
}

function processData(definition: ProductProcessWorkflowDefinition): ProcessNodeData {
  return definition.nodes.find((node) => node.kind === 'PROCESS')?.data as ProcessNodeData;
}
