'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  topologyKey,
  chooseRepresentatives,
} = require('../scenarios/production-plan-routing-readonly');

test('uses logical workflow type and refuses to guess many-to-one from physical alternative roots', () => {
  assert.equal(topologyKey({ workflowType: 'SINGLE_OUTPUT_PRODUCT', rootInputProductTypeIds: ['R1'], terminalOutputs: [{ productTypeId: 'P1' }] }), '1_TO_1');
  assert.equal(topologyKey({ workflowType: 'RAW_MATERIAL_SPLIT', rootInputProductTypeIds: ['R1'], terminalOutputs: [{ productTypeId: 'P1' }, { productTypeId: 'P2' }] }), '1_TO_MANY');
  assert.equal(topologyKey({ workflowType: 'SINGLE_OUTPUT_PRODUCT', rootInputProductTypeIds: ['R1', 'R2'], terminalOutputs: [{ productTypeId: 'P1' }] }), 'MULTI_RAW_TO_1_UNQUALIFIED');
  assert.equal(topologyKey({ workflowType: 'JOINT_PRODUCTION', rootInputProductTypeIds: ['R1', 'R2'], terminalOutputs: [{ productTypeId: 'P1' }, { productTypeId: 'P2' }] }), 'MANY_TO_MANY');
});

test('keeps one representative per topology and an ambiguous selection', () => {
  const inventory = [
    { selection: [{ id: 'P1', name: 'A' }], candidates: [{ workflowId: 1, topology: '1_TO_1' }] },
    { selection: [{ id: 'P2', name: 'B' }], candidates: [{ workflowId: 2, topology: 'MANY_TO_1' }, { workflowId: 3, topology: 'MANY_TO_1' }] },
  ];
  const chosen = chooseRepresentatives(inventory);
  assert.equal(chosen.byTopology['1_TO_1'].selection[0].name, 'A');
  assert.equal(chosen.byTopology.MANY_TO_1.selection[0].name, 'B');
  assert.equal(chosen.ambiguous.selection[0].name, 'B');
});
