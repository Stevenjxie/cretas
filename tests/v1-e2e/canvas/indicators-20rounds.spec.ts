/**
 * indicators-20rounds.spec.ts — 20-round E2E for Canvas Indicators Tab (Phase A).
 *
 * Backend: CanvasIndicatorsController @ /api/mobile/{factoryId}/canvas-indicators
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'indicators',
  tabLabel: '指标中心 (Indicators)',
  listPath: `/${FID}/canvas-indicators`,
  createPath: `/${FID}/canvas-indicators`,
  updatePath: (id) => `/${FID}/canvas-indicators/${id}`,
  deletePath: (id) => `/${FID}/canvas-indicators/${id}`,
  identityField: 'code',
  updateVerifyField: 'name',
  buildCreatePayload: (name) => ({
    code: name,
    name: `E2E ${name}`,
    category: 'FACTORY',
    description: 'E2E test indicator',
    unit: '%',
    computeStrategy: 'CACHED',
    cacheTtlSeconds: 300,
    displayOrder: 100,
    config: {},
  }),
  buildUpdatePayload: (current, newName) => ({
    name: `Updated ${newName}`,
    cacheTtlSeconds: 600,
  }),
  variantPayloads: (baseName) => [
    { code: baseName('factory'), name: 'F', category: 'FACTORY', unit: '%', computeStrategy: 'CACHED', cacheTtlSeconds: 300, displayOrder: 100, config: {} },
    { code: baseName('rest'), name: 'R', category: 'RESTAURANT', unit: '%', computeStrategy: 'REALTIME', cacheTtlSeconds: 0, displayOrder: 100, config: {} },
    { code: baseName('qa'), name: 'Q', category: 'QUALITY', unit: 'count', computeStrategy: 'PRECOMPUTED', cacheTtlSeconds: 3600, displayOrder: 100, config: {} },
    { code: baseName('fin'), name: 'Fi', category: 'FINANCE', unit: 'CNY', computeStrategy: 'CACHED', cacheTtlSeconds: 1800, displayOrder: 100, config: {} },
    { code: baseName('inv'), name: 'I', category: 'INVENTORY', unit: 'units', computeStrategy: 'CACHED', cacheTtlSeconds: 600, displayOrder: 100, config: {} },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'name length=256 (max 200)', payload: { code: baseName('bd1'), name: 'x'.repeat(256), category: 'FACTORY', unit: '%', computeStrategy: 'CACHED', cacheTtlSeconds: 300, displayOrder: 100, config: {} } },
    { label: 'bad category enum', payload: { code: baseName('bd2'), name: 'n', category: 'FAKE_CAT', unit: '%', computeStrategy: 'CACHED', cacheTtlSeconds: 300, displayOrder: 100, config: {} } },
    { label: 'bad computeStrategy', payload: { code: baseName('bd3'), name: 'n', category: 'FACTORY', unit: '%', computeStrategy: 'INSTANT', cacheTtlSeconds: 300, displayOrder: 100, config: {} } },
    { label: 'missing required code', payload: { name: 'n', category: 'FACTORY', unit: '%', computeStrategy: 'CACHED', cacheTtlSeconds: 300, displayOrder: 100, config: {} } },
    { label: 'missing required name', payload: { code: baseName('bd5'), category: 'FACTORY', unit: '%', computeStrategy: 'CACHED', cacheTtlSeconds: 300, displayOrder: 100, config: {} } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Indicators — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'code');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'code');
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
