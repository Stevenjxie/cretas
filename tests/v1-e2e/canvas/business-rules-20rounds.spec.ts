/**
 * business-rules-20rounds.spec.ts — 20-round E2E for Canvas Rules Tab (Phase 4a).
 *
 * Backend: BusinessRuleController @ /api/mobile/{factoryId}/canvas-rules
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'rules',
  tabLabel: '业务规则 (Business Rules)',
  listPath: `/${FID}/canvas-rules`,
  createPath: `/${FID}/canvas-rules`,
  updatePath: (id) => `/${FID}/canvas-rules/${id}`,
  deletePath: (id) => `/${FID}/canvas-rules/${id}`,
  togglePath: (id) => `/${FID}/canvas-rules/${id}/toggle`,
  supportsToggle: true,
  identityField: 'ruleCode',
  updateVerifyField: 'ruleName',
  buildCreatePayload: (name) => ({
    ruleCode: name,
    ruleName: `Rule ${name}`,
    scope: 'ORDER',
    conditionSpel: '#input.amount > 100',
    actionType: 'LOG',
    actionConfigJson: { level: 'INFO' },
    priority: 100,
    enabled: true,
  }),
  buildUpdatePayload: (current, newName) => ({
    ruleCode: newName,
    ruleName: `Rule ${newName}`,
    scope: current.scope || 'ORDER',
    conditionSpel: current.conditionSpel || '#input.amount > 100',
    actionType: current.actionType || 'LOG',
    actionConfigJson: current.actionConfigJson || { level: 'INFO' },
    priority: 100,
    enabled: true,
    version: current.version || 0,
  }),
  variantPayloads: (baseName) => [
    { ruleCode: baseName('order'), ruleName: 'R order', scope: 'ORDER', conditionSpel: '#input.amount > 100', actionType: 'LOG', actionConfigJson: { level: 'INFO' }, priority: 100, enabled: true },
    { ruleCode: baseName('inv'), ruleName: 'R inv', scope: 'INVENTORY', conditionSpel: '#input.qty > 0', actionType: 'LOG', actionConfigJson: { level: 'INFO' }, priority: 100, enabled: true },
    { ruleCode: baseName('cust'), ruleName: 'R cust', scope: 'CUSTOMER', conditionSpel: '#input.tier == 1', actionType: 'LOG', actionConfigJson: { level: 'INFO' }, priority: 100, enabled: true },
    { ruleCode: baseName('cstm'), ruleName: 'R cstm', scope: 'CUSTOM', conditionSpel: 'true', actionType: 'LOG', actionConfigJson: { level: 'INFO' }, priority: 100, enabled: true },
    { ruleCode: baseName('order2'), ruleName: 'R order2', scope: 'ORDER', conditionSpel: '#input.priority == "HIGH"', actionType: 'LOG', actionConfigJson: { level: 'WARN' }, priority: 50, enabled: false },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'ruleCode length=256', payload: { ruleCode: 'x'.repeat(256), ruleName: 'r', scope: 'ORDER', conditionSpel: '1==1', actionType: 'LOG', actionConfigJson: {}, priority: 100, enabled: true } },
    { label: 'SpEL 2001 chars', payload: { ruleCode: baseName('bd2'), ruleName: 'r', scope: 'ORDER', conditionSpel: '1+'.repeat(1000) + '1', actionType: 'LOG', actionConfigJson: {}, priority: 100, enabled: true } },
    { label: 'SpEL T() injection', payload: { ruleCode: baseName('bd3'), ruleName: 'r', scope: 'ORDER', conditionSpel: "T(System).getProperty('user.dir')", actionType: 'LOG', actionConfigJson: {}, priority: 100, enabled: true } },
    { label: 'bad scope enum', payload: { ruleCode: baseName('bd4'), ruleName: 'r', scope: 'FAKE_SCOPE', conditionSpel: '1==1', actionType: 'LOG', actionConfigJson: {}, priority: 100, enabled: true } },
    { label: 'priority -1 (out of range)', payload: { ruleCode: baseName('bd5'), ruleName: 'r', scope: 'ORDER', conditionSpel: '1==1', actionType: 'LOG', actionConfigJson: {}, priority: -1, enabled: true } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Business Rules — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'ruleCode');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'ruleCode');
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
