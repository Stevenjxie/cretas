import { describe, expect, it } from 'vitest';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';
import { applyWorkflowPatches } from '../workflowModel';
import { reconcileWorkflowUnits, type WorkflowUnitContext } from '../workflowUnits';

describe('reconcileWorkflowUnits', () => {
  it('aligns bound material, port and primary output hint to canonical g', () => {
    const input = definitionWith('件', 'g', '件');
    const result = reconcileWorkflowUnits(input, context('g'));

    expect(materialUnit(result.definition)).toBe('g');
    expect(processData(result.definition).outputUnit).toBe('g');
    expect(processData(result.definition).ports[0].unit).toBe('g');
    expect(result.errors).toEqual([]);
    expect(materialUnit(input)).toBe('件');
  });

  it('normalizes aliases without treating them as product conversions', () => {
    const result = reconcileWorkflowUnits(definitionWith('克', '克', '克'), context('g'));
    expect(materialUnit(result.definition)).toBe('g');
    expect(processData(result.definition).outputUnit).toBe('g');
    expect(result.errors).toEqual([]);
  });

  it('blocks a cross-unit port even when the stale process hint already matches', () => {
    const result = reconcileWorkflowUnits(definitionWith('g', '件', '件'), context('g'));
    expect(result.errors.map((error) => error.code)).toEqual(['CONVERSION_REQUIRED']);
  });

  it('retains a report unit backed by the exact conversion id and version', () => {
    const input = definitionWith('g', '件', '件');
    processData(input).ports[0].conversionRefId = 'C1';
    processData(input).ports[0].conversionVersion = 3;
    const result = reconcileWorkflowUnits(input, context('g', [{
      id: 'C1', version: 3, productTypeId: 'P1', fromUnitCode: 'pcs', toUnitCode: 'g',
    }]));
    expect(processData(result.definition).outputUnit).toBe('pcs');
    expect(processData(result.definition).ports[0].unit).toBe('pcs');
    expect(result.errors).toEqual([]);
  });

  it('automatically binds the only applicable product conversion', () => {
    const input = definitionWith('g', '件', '件');
    const result = reconcileWorkflowUnits(input, context('g', [{
      id: 'C1', version: 3, productTypeId: 'P1', fromUnitCode: 'pcs', toUnitCode: 'g',
    }]));

    expect(processData(result.definition).ports[0]).toMatchObject({
      unit: 'pcs',
      conversionRefId: 'C1',
      conversionVersion: 3,
    });
    expect(result.errors).toEqual([]);
  });

  it('does not guess when multiple applicable conversions exist', () => {
    const input = definitionWith('g', '件', '件');
    const result = reconcileWorkflowUnits(input, context('g', [
      { id: 'C1', version: 1, productTypeId: 'P1', fromUnitCode: 'pcs', toUnitCode: 'g' },
      { id: 'C2', version: 2, productTypeId: 'P1', fromUnitCode: 'g', toUnitCode: 'pcs' },
    ]));

    expect(processData(result.definition).ports[0].conversionRefId).toBeUndefined();
    expect(result.errors.map((error) => error.code)).toEqual(['CONVERSION_REQUIRED']);
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

  it('derives the shared process hint from the smallest output ordinal', () => {
    const input = definitionWith('g', 'g', 'box');
    input.nodes.splice(1, 0, {
      id: 'material:box', kind: 'FINISHED_GOOD', position: { x: 1, y: 2 }, data: {
        name: '盒装成品', skuId: 'P2', bound: true, baseUnit: 'box',
      },
    });
    const process = input.nodes[2].data as ProcessNodeData;
    process.ports.push({ id: 'output:box', direction: 'OUTPUT', materialNodeId: 'material:box',
      unit: 'box', ordinal: 1 });
    const multiContext = context('g');
    multiContext.products.P2 = { productTypeId: 'P2', primaryUnit: 'box', conversions: [] };

    const result = reconcileWorkflowUnits(input, multiContext);

    expect(processData(result.definition).outputUnit).toBe('g');
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
): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      { id: 'material:finished', kind: 'FINISHED_GOOD', position: { x: 1, y: 1 }, data: {
        name: 'SHH0713香辣孜然羊排', skuId: 'P1', bound: true, baseUnit: materialUnitValue,
      } },
      { id: 'process:cold', kind: 'PROCESS', position: { x: 0, y: 1 }, data: {
        workProcessId: 'cold', processName: '冷冻', inputUnit: 'g', outputUnit,
        ports: [{ id: 'output:final', direction: 'OUTPUT', materialNodeId: 'material:finished',
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
