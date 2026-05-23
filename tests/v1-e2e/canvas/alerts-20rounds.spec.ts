/**
 * alerts-20rounds.spec.ts — 20-round E2E for Canvas Alerts Tab (Phase 2).
 *
 * Backend: CanvasAlertController @ /api/mobile/{factoryId}/alerts/rules
 * Spec ref: docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md
 *
 * Round breakdown:
 *   01-05 CRUD lifecycle (create, read, update, disable, soft-delete + recreate)
 *   06-10 5 alertType variants (INVENTORY_LOW / EXPIRING / QUALITY / PO_AMT / SO_AMT)
 *   11-15 boundary: 256-char ruleName, 2001-char SpEL, SpEL T() injection,
 *                   bad alertType, missing required field
 *   16-20 wire-through: toggle cycles + no-regression on control entity
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities, E2E_PREFIX } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'alerts',
  tabLabel: '预警规则 (Alerts)',
  listPath: `/${FID}/alerts/rules`,
  createPath: `/${FID}/alerts/rules`,
  updatePath: (id) => `/${FID}/alerts/rules/${id}`,
  deletePath: (id) => `/${FID}/alerts/rules/${id}`,
  togglePath: (id) => `/${FID}/alerts/rules/${id}/toggle`,
  supportsToggle: true,
  identityField: 'ruleName',
  buildCreatePayload: (name) => ({
    alertType: 'INVENTORY_LOW',
    ruleName: name,
    triggerConditionSpel: '#context.currentStock < 30',
    severity: 'MID',
    enabled: true,
    notifyChannels: ['IN_APP'],
    notifyRoles: ['factory_super_admin'],
  }),
  buildUpdatePayload: (current, newName) => ({
    ruleName: newName,
    severity: current.severity === 'MID' ? 'HIGH' : 'MID',
  }),
  variantPayloads: (baseName) => [
    {
      alertType: 'INVENTORY_LOW',
      ruleName: baseName('inv_low'),
      triggerConditionSpel: '#context.currentStock < 50',
      severity: 'MID',
      enabled: true,
      notifyChannels: ['IN_APP'],
      notifyRoles: ['factory_super_admin'],
    },
    {
      alertType: 'INVENTORY_EXPIRING',
      ruleName: baseName('inv_exp'),
      triggerConditionSpel: '#context.daysToExpiry <= 7',
      severity: 'HIGH',
      enabled: true,
      notifyChannels: ['IN_APP', 'EMAIL'],
      notifyRoles: ['factory_super_admin'],
    },
    {
      alertType: 'QUALITY_ANOMALY',
      ruleName: baseName('qa'),
      triggerConditionSpel: '#context.passRate < 90',
      severity: 'HIGH',
      enabled: true,
      notifyChannels: ['IN_APP'],
      notifyRoles: ['factory_super_admin'],
    },
    {
      alertType: 'PO_AMOUNT_THRESHOLD',
      ruleName: baseName('po_amt'),
      triggerConditionSpel: '#context.amount >= 100000',
      severity: 'MID',
      enabled: true,
      notifyChannels: ['IN_APP'],
      notifyRoles: ['factory_super_admin'],
    },
    {
      alertType: 'SO_AMOUNT_THRESHOLD',
      ruleName: baseName('so_amt'),
      triggerConditionSpel: '#context.amount >= 200000',
      severity: 'LOW',
      enabled: true,
      notifyChannels: ['IN_APP'],
      notifyRoles: ['factory_super_admin'],
    },
  ],
  boundaryPayloads: (baseName) => [
    {
      label: 'ruleName length=256 (must be < 200)',
      payload: {
        alertType: 'INVENTORY_LOW',
        ruleName: 'x'.repeat(256),
        severity: 'MID',
        enabled: true,
        notifyChannels: ['IN_APP'],
        notifyRoles: ['factory_super_admin'],
      },
    },
    {
      label: 'SpEL 2001 chars (must be < 2000)',
      payload: {
        alertType: 'INVENTORY_LOW',
        ruleName: baseName('spel'),
        triggerConditionSpel: '1+'.repeat(1000) + '1',
        severity: 'MID',
        enabled: true,
        notifyChannels: ['IN_APP'],
        notifyRoles: ['factory_super_admin'],
      },
    },
    {
      label: 'SpEL T() Java type injection (security)',
      payload: {
        alertType: 'INVENTORY_LOW',
        ruleName: baseName('inj1'),
        triggerConditionSpel: "T(java.lang.Runtime).getRuntime().exec('whoami')",
        severity: 'MID',
        enabled: true,
        notifyChannels: ['IN_APP'],
        notifyRoles: ['factory_super_admin'],
      },
    },
    {
      label: 'bad alertType enum',
      payload: {
        alertType: 'NOT_A_REAL_TYPE',
        ruleName: baseName('bad_type'),
        severity: 'MID',
        enabled: true,
        notifyChannels: ['IN_APP'],
        notifyRoles: ['factory_super_admin'],
      },
    },
    {
      label: 'missing required alertType',
      payload: {
        ruleName: baseName('missing_type'),
        severity: 'MID',
        enabled: true,
        notifyChannels: ['IN_APP'],
        notifyRoles: ['factory_super_admin'],
      },
    },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Alerts — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'ruleName');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(
      request,
      adapter.listPath,
      adapter.deletePath,
      'ruleName',
    );
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
