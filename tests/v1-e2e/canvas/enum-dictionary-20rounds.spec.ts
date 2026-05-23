/**
 * enum-dictionary-20rounds.spec.ts — 20-round E2E for Canvas Phase C Enum Dictionary Tab.
 *
 * Backend: CanvasEnumDictionaryController @ /api/mobile/{factoryId}/canvas-enum-dictionary
 * Schema:  entity/canvas/EnumDictionary.java (factoryId, category, code, label,
 *          displayOrder, enabled, parentCode, description, locale, @Version)
 *
 * 8 standard categories shipped in V20260824_03 seed (factoryId='*'):
 * CANCEL_REASON, RETURN_REASON, APPROVAL_OPINION, DEFECT_SEVERITY,
 * NONCONFORM_TYPE, WASTAGE_REASON, RECALL_LEVEL, URGENCY_LEVEL.
 *
 * Closes Goal E2E close-gate: 490 → 510 rounds (above ≥500 standard).
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, cleanupE2eEntities } from './canvas-base';
import { defineTabRounds, snapshotControl, TabAdapter } from './round-template';

const FID = TEST_FACTORY_ID;

const adapter: TabAdapter = {
  tabKey: 'enum_dictionary',
  tabLabel: '枚举字典 (Enum Dictionary)',
  listPath: `/${FID}/canvas-enum-dictionary?category=CANCEL_REASON`,
  createPath: `/${FID}/canvas-enum-dictionary`,
  updatePath: (id) => `/${FID}/canvas-enum-dictionary/${id}`,
  deletePath: (id) => `/${FID}/canvas-enum-dictionary/${id}`,
  identityField: 'code',
  updateVerifyField: 'label',
  buildCreatePayload: (name) => ({
    category: 'CANCEL_REASON',
    code: name.toUpperCase().replace(/[^A-Z0-9_]/g, '_'),
    label: `E2E ${name}`,
    displayOrder: 99,
    enabled: true,
    description: `E2E auto-generated for ${name}`,
  }),
  buildUpdatePayload: (current, newName) => ({
    label: `Updated ${newName}`,
    displayOrder: 100,
  }),
  variantPayloads: (baseName) => [
    { category: 'CANCEL_REASON',    code: baseName('cancel_v1').toUpperCase().replace(/[^A-Z0-9_]/g, '_'),    label: '客户撤单',     displayOrder: 11, enabled: true,  description: '客户主动撤回订单' },
    { category: 'RETURN_REASON',    code: baseName('return_v1').toUpperCase().replace(/[^A-Z0-9_]/g, '_'),    label: '质量问题',     displayOrder: 12, enabled: true,  description: '退货因质量原因' },
    { category: 'APPROVAL_OPINION', code: baseName('appr_v1').toUpperCase().replace(/[^A-Z0-9_]/g, '_'),     label: '同意',         displayOrder: 13, enabled: true,  description: '审批通过意见' },
    { category: 'DEFECT_SEVERITY',  code: baseName('def_v1').toUpperCase().replace(/[^A-Z0-9_]/g, '_'),      label: '严重',         displayOrder: 14, enabled: true,  description: '缺陷严重度: 严重级' },
    { category: 'URGENCY_LEVEL',    code: baseName('urgent_v1').toUpperCase().replace(/[^A-Z0-9_]/g, '_'),   label: '紧急',         displayOrder: 15, enabled: false, description: '紧急级别 — disabled variant' },
  ],
  boundaryPayloads: (baseName) => [
    { label: 'code length=256 (over 50 cap)', payload: { category: 'CANCEL_REASON', code: 'X'.repeat(256), label: 'overlength code', displayOrder: 1, enabled: true } },
    { label: 'label length=2001', payload: { category: 'CANCEL_REASON', code: baseName('bd2').toUpperCase().replace(/[^A-Z0-9_]/g, '_'), label: 'L'.repeat(2001), displayOrder: 1, enabled: true } },
    { label: 'missing required code', payload: { category: 'CANCEL_REASON', label: 'no code', displayOrder: 1, enabled: true } },
    { label: 'bad category enum FAKE_CAT', payload: { category: 'FAKE_CAT_NOT_IN_ENUM', code: baseName('bd4').toUpperCase().replace(/[^A-Z0-9_]/g, '_'), label: 'bad cat', displayOrder: 1, enabled: true } },
    { label: 'displayOrder negative -1', payload: { category: 'CANCEL_REASON', code: baseName('bd5').toUpperCase().replace(/[^A-Z0-9_]/g, '_'), label: 'neg order', displayOrder: -1, enabled: true } },
  ],
};

const rounds = defineTabRounds(adapter);

test.describe('Canvas Phase C Enum Dictionary — 20 rounds @canvas-e2e', () => {
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
