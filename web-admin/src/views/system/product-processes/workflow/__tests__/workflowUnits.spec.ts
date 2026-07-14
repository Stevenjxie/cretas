import { describe, expect, it } from 'vitest';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';
import { reconcileWorkflowUnits, type WorkflowUnitContext } from '../workflowUnits';

describe('reconcileWorkflowUnits', () => {
  it('aligns bound material, port and primary output hint to canonical g', () => {
    const input = definitionWith('件', '件', 'g');
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

  it('blocks a cross-unit report hint without a conversion reference', () => {
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

  it('warns instead of guessing for an unbound material', () => {
    const input = definitionWith('件', '件', '件');
    const data = input.nodes[0].data as { skuId: string; bound: boolean };
    data.skuId = '';
    data.bound = false;
    const result = reconcileWorkflowUnits(input, context('g'));
    expect(result.warnings.map((warning) => warning.code)).toEqual(['SKU_UNIT_UNKNOWN']);
    expect(materialUnit(result.definition)).toBe('件');
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
  return definition.nodes[1].data as ProcessNodeData;
}
