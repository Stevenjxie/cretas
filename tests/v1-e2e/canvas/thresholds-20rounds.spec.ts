/**
 * thresholds-20rounds.spec.ts — 20-round E2E for Canvas Thresholds Tab (Phase A).
 *
 * Backend: CanvasThresholdsController @ /api/mobile/{factoryId}/canvas-thresholds
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'thresholds',
  tabLabel: '阈值参数 (Thresholds)',
  listPath: `/${FID}/canvas-thresholds`,
  createPath: `/${FID}/canvas-thresholds`,
  updatePath: (id) => `/${FID}/canvas-thresholds/${id}`,
  deletePath: (id) => `/${FID}/canvas-thresholds/${id}`,
  identityField: 'thresholdKey',
  updateVerifyField: 'description',
  buildCreatePayload: (name) => ({
    thresholdKey: name,
    category: 'AI',
    valueType: 'DOUBLE',
    thresholdValue: '0.5',
    defaultValue: '0.5',
    description: `E2E ${name}`,
    unit: '比例',
    minValue: '0',
    maxValue: '1',
    enabled: true,
  }),
  buildUpdatePayload: (current, newName) => ({
    thresholdValue: '0.7',
    description: `Updated ${newName}`,
  }),
  variantPayloads: (baseName) => [
    { thresholdKey: baseName('ai_th'), category: 'AI', valueType: 'DOUBLE', thresholdValue: '0.6', defaultValue: '0.6', description: 'AI', unit: '比例', minValue: '0', maxValue: '1', enabled: true },
    { thresholdKey: baseName('inv_th'), category: 'INVENTORY', valueType: 'INTEGER', thresholdValue: '30', defaultValue: '30', description: 'Inv', unit: 'units', minValue: '0', maxValue: '10000', enabled: true },
    { thresholdKey: baseName('iot_th'), category: 'IOT', valueType: 'DOUBLE', thresholdValue: '4.0', defaultValue: '4.0', description: 'IoT temp', unit: '°C', minValue: '-30', maxValue: '50', enabled: true },
    { thresholdKey: baseName('credit_th'), category: 'CREDIT', valueType: 'DOUBLE', thresholdValue: '80.0', defaultValue: '80.0', description: 'Credit', unit: '%', minValue: '0', maxValue: '100', enabled: true },
    { thresholdKey: baseName('bom_th'), category: 'BOM', valueType: 'INTEGER', thresholdValue: '7', defaultValue: '5', description: 'BOM max depth', unit: 'level', minValue: '1', maxValue: '20', enabled: true },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'thresholdKey length=256', payload: { thresholdKey: 'x'.repeat(256), category: 'AI', valueType: 'DOUBLE', thresholdValue: '0.5', defaultValue: '0.5', description: '', unit: '', minValue: '0', maxValue: '1', enabled: true } },
    { label: 'valueType=DOUBLE but value="not_a_number"', payload: { thresholdKey: baseName('bd2'), category: 'AI', valueType: 'DOUBLE', thresholdValue: 'not_a_number', defaultValue: '0.5', description: '', unit: '', minValue: '0', maxValue: '1', enabled: true } },
    { label: 'bad category enum', payload: { thresholdKey: baseName('bd3'), category: 'FAKE_CAT', valueType: 'DOUBLE', thresholdValue: '0.5', defaultValue: '0.5', description: '', unit: '', minValue: '0', maxValue: '1', enabled: true } },
    { label: 'missing required thresholdKey', payload: { category: 'AI', valueType: 'DOUBLE', thresholdValue: '0.5', defaultValue: '0.5', description: '', unit: '', minValue: '0', maxValue: '1', enabled: true } },
    { label: 'value 999 > maxValue 1', payload: { thresholdKey: baseName('bd5'), category: 'AI', valueType: 'DOUBLE', thresholdValue: '999', defaultValue: '0.5', description: '', unit: '', minValue: '0', maxValue: '1', enabled: true } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Thresholds — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'thresholdKey');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'thresholdKey');
  });

  for (const r of rounds) {
    test(`Round ${String(r.idx).padStart(2, '0')}: ${r.label}`, async ({ request }) => {
      const report = await r.run(request, control);
      const summary = [
        `axis1=${report.axis1.passed ? 'PASS' : 'FAIL'} ${report.axis1.detail}`,
        `axis2=${report.axis2.passed ? 'PASS' : 'FAIL'} ${report.axis2.detail}`,
        `axis3=${report.axis3.passed ? 'PASS' : 'FAIL'} ${report.axis3.detail}`,
        `axis4=${report.axis4.passed ? 'PASS' : 'FAIL'} ${report.axis4.detail}`,
      ].join(' | ');
      expect(report.passed, summary).toBe(true);
    });
  }
});
