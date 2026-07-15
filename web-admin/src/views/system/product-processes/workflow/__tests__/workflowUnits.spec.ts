import { describe, expect, it } from 'vitest';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';
import { applyWorkflowPatches } from '../workflowModel';
import {
  forkWorkflowUnitReviewDraft,
  parseFixedRatioQuantities,
  reconcileWorkflowUnits,
  type WorkflowUnitContext,
} from '../workflowUnits';

describe('reconcileWorkflowUnits', () => {
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
