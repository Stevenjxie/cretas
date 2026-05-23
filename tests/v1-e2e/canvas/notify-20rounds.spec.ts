/**
 * notify-20rounds.spec.ts — 20-round E2E for Canvas Notify Tab (Phase 3).
 *
 * Backend: NotifyTemplateController @ /api/mobile/{factoryId}/notify/templates
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'notify',
  tabLabel: '通知模板 (Notify)',
  listPath: `/${FID}/notify/templates`,
  createPath: `/${FID}/notify/templates`,
  updatePath: (id) => `/${FID}/notify/templates/${id}`,
  deletePath: (id) => `/${FID}/notify/templates/${id}`,
  identityField: 'templateCode',
  updateVerifyField: 'title',
  buildCreatePayload: (name) => ({
    templateCode: name.toLowerCase(),
    title: `E2E ${name}`,
    bodyTemplate: '{{userName}}, 您好！请处理 {{eventType}}',
    channels: ['IN_APP'],
    variablesSchemaJson: {},
  }),
  buildUpdatePayload: (current, newName) => ({
    title: `Updated ${newName}`,
    bodyTemplate: current.bodyTemplate || '{{name}}',
  }),
  variantPayloads: (baseName) => [
    { templateCode: baseName('inapp').toLowerCase(), title: 'IN_APP', channels: ['IN_APP'], bodyTemplate: '{{n}}', variablesSchemaJson: {} },
    { templateCode: baseName('email').toLowerCase(), title: 'EMAIL', channels: ['EMAIL'], bodyTemplate: '{{n}}', variablesSchemaJson: {} },
    { templateCode: baseName('wechat').toLowerCase(), title: 'WECHAT', channels: ['WECHAT'], bodyTemplate: '{{n}}', variablesSchemaJson: {} },
    { templateCode: baseName('ding').toLowerCase(), title: 'DINGTALK', channels: ['DINGTALK'], bodyTemplate: '{{n}}', variablesSchemaJson: {} },
    { templateCode: baseName('sms').toLowerCase(), title: 'SMS', channels: ['SMS'], bodyTemplate: '{{n}}', variablesSchemaJson: {} },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'templateCode length=256', payload: { templateCode: 'x'.repeat(256), title: 't', bodyTemplate: '{{n}}', channels: ['IN_APP'], variablesSchemaJson: {} } },
    { label: 'bodyTemplate length=10001', payload: { templateCode: baseName('bd2'), title: 't', bodyTemplate: 'x'.repeat(10001), channels: ['IN_APP'], variablesSchemaJson: {} } },
    { label: 'title length=256 (max 200)', payload: { templateCode: baseName('bd3'), title: 'x'.repeat(256), bodyTemplate: '{{n}}', channels: ['IN_APP'], variablesSchemaJson: {} } },
    { label: 'missing required templateCode', payload: { title: 't', bodyTemplate: '{{n}}', channels: ['IN_APP'], variablesSchemaJson: {} } },
    { label: 'duplicate templateCode (idempotency check)', payload: { templateCode: 'will_be_unique_or_dup', title: 't', bodyTemplate: '{{n}}', channels: ['IN_APP'], variablesSchemaJson: {} } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Notify — 20 rounds @canvas-e2e', () => {
  let control: { id: string; nameField: string; nameValue: string } | null = null;

  test.beforeAll(async ({ request }) => {
    control = await snapshotControl(request, adapter.listPath, adapter.identityField || 'templateCode');
  });

  test.afterAll(async ({ request }) => {
    await cleanupE2eEntities(request, adapter.listPath, adapter.deletePath, 'templateCode');
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
