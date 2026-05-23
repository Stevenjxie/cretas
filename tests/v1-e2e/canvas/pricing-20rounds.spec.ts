/**
 * pricing-20rounds.spec.ts — 20-round E2E for Canvas Pricing Tab (Phase 4b).
 *
 * Backend: PricingStrategyController @ /api/mobile/{factoryId}/pricing/strategies
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'pricing',
  tabLabel: '价格策略 (Pricing)',
  listPath: `/${FID}/pricing/strategies`,
  createPath: `/${FID}/pricing/strategies`,
  updatePath: (id) => `/${FID}/pricing/strategies/${id}`,
  deletePath: (id) => `/${FID}/pricing/strategies/${id}`,
  togglePath: (id) => `/${FID}/pricing/strategies/${id}/toggle`,
  supportsToggle: true,
  identityField: 'strategyName',
  buildCreatePayload: (name) => ({
    strategyCode: name,
    strategyName: name,
    strategyType: 'TIERED',
    rulesJson: { tiers: [{ minQty: 0, maxQty: 100, discountPct: 100.0 }] },
    priority: 100,
    enabled: true,
    validFrom: '2026-01-01',
    validTo: '2027-12-31',
  }),
  buildUpdatePayload: (current, newName) => ({
    strategyCode: current.strategyCode,
    strategyName: newName,
    strategyType: current.strategyType,
    rulesJson: current.rulesJson,
    priority: 100,
    enabled: true,
    validFrom: current.validFrom,
    validTo: current.validTo,
    version: current.version || 0,
  }),
  variantPayloads: (baseName) => [
    { strategyCode: baseName('tiered'), strategyName: baseName('tiered'), strategyType: 'TIERED', rulesJson: { tiers: [{ minQty: 0, maxQty: 100, discountPct: 100 }, { minQty: 101, maxQty: 1000, discountPct: 90 }] }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' },
    { strategyCode: baseName('promo'), strategyName: baseName('promo'), strategyType: 'PROMOTION', rulesJson: { discountPct: 95 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' },
    { strategyCode: baseName('member'), strategyName: baseName('member'), strategyType: 'MEMBER', rulesJson: { vipDiscount: 85 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' },
    { strategyCode: baseName('bundle'), strategyName: baseName('bundle'), strategyType: 'BUNDLE', rulesJson: { items: ['SKU_A', 'SKU_B'], discountPct: 80 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' },
    { strategyCode: baseName('cycle'), strategyName: baseName('cycle'), strategyType: 'CYCLE', rulesJson: { cycle: 'MONTHLY', discountPct: 90 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'strategyName length=256', payload: { strategyCode: baseName('bd1'), strategyName: 'x'.repeat(256), strategyType: 'TIERED', rulesJson: {}, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' } },
    { label: 'discountPct 101% (out of range)', payload: { strategyCode: baseName('bd2'), strategyName: baseName('bd2'), strategyType: 'PROMOTION', rulesJson: { discountPct: 101 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' } },
    { label: 'discountPct -10 (negative)', payload: { strategyCode: baseName('bd3'), strategyName: baseName('bd3'), strategyType: 'PROMOTION', rulesJson: { discountPct: -10 }, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' } },
    { label: 'validFrom > validTo (reverse dates)', payload: { strategyCode: baseName('bd4'), strategyName: baseName('bd4'), strategyType: 'PROMOTION', rulesJson: { discountPct: 90 }, priority: 100, enabled: true, validFrom: '2027-12-31', validTo: '2026-01-01' } },
    { label: 'bad strategyType enum', payload: { strategyCode: baseName('bd5'), strategyName: baseName('bd5'), strategyType: 'FAKE_TYPE', rulesJson: {}, priority: 100, enabled: true, validFrom: '2026-01-01', validTo: '2027-12-31' } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Pricing — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'strategyName');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'strategyName');
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
