/**
 * food-safety-20rounds.spec.ts — 20-round E2E for Canvas Food Safety Tab (Phase A).
 *
 * Backend: CanvasFoodSafetyController @ /api/mobile/{factoryId}/canvas-food-safety/haccp/checkpoints
 *
 * Note: this Tab uses Long IDs (not UUIDs) and the CCP code field instead of UUID.
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities, E2E_PREFIX } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'food-safety',
  tabLabel: '食品安全 (Food Safety / HACCP)',
  listPath: `/${FID}/canvas-food-safety/haccp/checkpoints`,
  createPath: `/${FID}/canvas-food-safety/haccp/checkpoints`,
  updatePath: (id) => `/${FID}/canvas-food-safety/haccp/checkpoints/${id}`,
  deletePath: (id) => `/${FID}/canvas-food-safety/haccp/checkpoints/${id}`,
  identityField: 'checkpointCode',
  updateVerifyField: 'name',
  buildCreatePayload: (name) => ({
    checkpointCode: name.slice(0, 50),
    name: `E2E HACCP ${name}`,
    hazardType: 'BIOLOGICAL',
    description: 'E2E test point',
    criticalLimitMin: 0,
    criticalLimitMax: 100,
    unit: '°C',
    monitoringProcedure: 'each 30min',
    correctiveAction: 'rework',
    verificationProcedure: 'daily',
    recordKeeping: '180 days',
    active: true,
  }),
  buildUpdatePayload: (current, newName) => ({
    name: `Updated ${newName}`,
    monitoringProcedure: 'updated proc',
  }),
  variantPayloads: (baseName) => [
    { checkpointCode: baseName('bio').slice(0, 50), name: 'BIO', hazardType: 'BIOLOGICAL', description: 'b', criticalLimitMin: 0, criticalLimitMax: 100, unit: '°C', monitoringProcedure: 'p', correctiveAction: 'a', verificationProcedure: 'v', recordKeeping: 'k', active: true },
    { checkpointCode: baseName('chem').slice(0, 50), name: 'CHEM', hazardType: 'CHEMICAL', description: 'c', criticalLimitMin: 0, criticalLimitMax: 50, unit: 'mg/kg', monitoringProcedure: 'p', correctiveAction: 'a', verificationProcedure: 'v', recordKeeping: 'k', active: true },
    { checkpointCode: baseName('phy').slice(0, 50), name: 'PHY', hazardType: 'PHYSICAL', description: 'p', criticalLimitMin: 0, criticalLimitMax: 1, unit: 'mm', monitoringProcedure: 'p', correctiveAction: 'a', verificationProcedure: 'v', recordKeeping: 'k', active: true },
    { checkpointCode: baseName('inact').slice(0, 50), name: 'INACT', hazardType: 'BIOLOGICAL', description: 'd', criticalLimitMin: 0, criticalLimitMax: 75, unit: '°C', monitoringProcedure: 'p', correctiveAction: 'a', verificationProcedure: 'v', recordKeeping: 'k', active: false },
    { checkpointCode: baseName('wide').slice(0, 50), name: 'WIDE', hazardType: 'CHEMICAL', description: 'd', criticalLimitMin: -50, criticalLimitMax: 200, unit: 'ppm', monitoringProcedure: 'p', correctiveAction: 'a', verificationProcedure: 'v', recordKeeping: 'k', active: true },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'checkpointCode length=256', payload: { checkpointCode: 'x'.repeat(256), name: 'n', hazardType: 'BIOLOGICAL', criticalLimitMin: 0, criticalLimitMax: 100, unit: '°C' } },
    { label: 'bad hazardType enum', payload: { checkpointCode: baseName('bd2').slice(0, 50), name: 'n', hazardType: 'FAKE_HAZARD', criticalLimitMin: 0, criticalLimitMax: 100, unit: '°C' } },
    { label: 'criticalMin > criticalMax (inverted limits)', payload: { checkpointCode: baseName('bd3').slice(0, 50), name: 'n', hazardType: 'BIOLOGICAL', criticalLimitMin: 100, criticalLimitMax: 0, unit: '°C' } },
    { label: 'missing required checkpointCode', payload: { name: 'n', hazardType: 'BIOLOGICAL', criticalLimitMin: 0, criticalLimitMax: 100, unit: '°C' } },
    { label: 'unit length=256', payload: { checkpointCode: baseName('bd5').slice(0, 50), name: 'n', hazardType: 'BIOLOGICAL', criticalLimitMin: 0, criticalLimitMax: 100, unit: 'x'.repeat(256) } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Food Safety — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'checkpointCode');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'checkpointCode');
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
