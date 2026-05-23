/**
 * cron-20rounds.spec.ts — 20-round E2E for Canvas Cron Tab (Phase 5).
 *
 * Backend: BusinessRuleController @ /api/mobile/{factoryId}/config/v2/scheduler
 *
 * Cron Tab is UPDATE-only (existing taskCode list) — no create/delete. Rounds
 * adapt to read + update + idempotent re-read + boundary validation.
 */

import { test, expect } from '@playwright/test';
import { TEST_FACTORY_ID, callApi } from './canvas-base';
import { axis1ModifyAccepted, axis4FourInOneUx, composeReport } from './four-axis-helper';

const FID = TEST_FACTORY_ID;
const LIST_PATH = `/${FID}/config/v2/scheduler`;
const UPDATE_PATH = (taskCode: string) => `/${FID}/config/v2/scheduler/${taskCode}`;

test.describe('Canvas Cron — 20 rounds @canvas-e2e', () => {

  let firstTask: any = null;
  let originalCron = '';

  test.beforeAll(async ({ request }) => {
    const r = await callApi(request, 'GET', LIST_PATH);
    const items: any[] = r.body?.data || [];
    firstTask = items[0] || null;
    if (firstTask) originalCron = firstTask.cronExpression || '';
  });

  test.afterAll(async ({ request }) => {
    if (firstTask && originalCron) {
      // Restore original cron
      await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
        cronExpression: originalCron,
        enabled: firstTask.enabled,
      });
    }
  });

  // Helper to build a report wrapper
  function reportOf(passed: boolean, detail: string) {
    return composeReport({ axis1: { passed, detail } });
  }

  // ── Rounds 01-05: CRUD-equivalent for scheduler ──
  test('Round 01: READ scheduler list, has tasks', async ({ request }) => {
    const r = await callApi(request, 'GET', LIST_PATH);
    expect(r.status).toBe(200);
    const items = r.body?.data || [];
    expect(Array.isArray(items)).toBe(true);
    expect(items.length, `expected >=1 task, got ${items.length}`).toBeGreaterThan(0);
  });

  test('Round 02: READ first task fields are complete', async ({ request }) => {
    const r = await callApi(request, 'GET', LIST_PATH);
    const t = r.body.data[0];
    expect(t.taskCode).toBeTruthy();
    expect(t.cronExpression).toBeTruthy();
    expect(typeof t.enabled).toBe('boolean');
  });

  test('Round 03: UPDATE task cron to "0 0 3 * * ?" (valid)', async ({ request }) => {
    if (!firstTask) test.skip();
    const newCron = '0 0 3 * * ?';
    const upd = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
      cronExpression: newCron,
      enabled: firstTask.enabled,
    });
    expect(upd.status, `update body=${JSON.stringify(upd.body)}`).toBe(200);
    const refetch = await callApi(request, 'GET', LIST_PATH);
    const found = refetch.body.data.find((x: any) => x.taskCode === firstTask.taskCode);
    expect(found.cronExpression).toBe(newCron);
  });

  test('Round 04: TOGGLE enabled=false', async ({ request }) => {
    if (!firstTask) test.skip();
    const upd = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
      cronExpression: firstTask.cronExpression,
      enabled: false,
    });
    expect(upd.status).toBe(200);
    const refetch = await callApi(request, 'GET', LIST_PATH);
    const found = refetch.body.data.find((x: any) => x.taskCode === firstTask.taskCode);
    expect(found.enabled).toBe(false);
  });

  test('Round 05: TOGGLE enabled=true (recover)', async ({ request }) => {
    if (!firstTask) test.skip();
    const upd = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
      cronExpression: firstTask.cronExpression,
      enabled: true,
    });
    expect(upd.status).toBe(200);
  });

  // ── Rounds 06-10: 5 cron pattern variants ──
  const validCronPatterns = [
    { label: 'every minute', cron: '0 * * * * ?' },
    { label: 'every hour', cron: '0 0 * * * ?' },
    { label: 'daily 2am', cron: '0 0 2 * * ?' },
    { label: 'every weekday 9am', cron: '0 0 9 ? * MON-FRI' },
    { label: 'monthly first 0am', cron: '0 0 0 1 * ?' },
  ];
  for (let i = 0; i < validCronPatterns.length; i++) {
    const p = validCronPatterns[i];
    test(`Round ${String(6 + i).padStart(2, '0')}: VARIANT cron "${p.label}"`, async ({ request }) => {
      if (!firstTask) test.skip();
      const r = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
        cronExpression: p.cron,
        enabled: true,
      });
      expect(r.status, `body=${JSON.stringify(r.body)}`).toBe(200);
    });
  }

  // ── Rounds 11-15: boundary / security ──
  const badCronPatterns = [
    { label: 'cron seconds out of range (60)', cron: '60 0 0 * * ?' },
    { label: 'cron malformed (only 3 fields)', cron: '0 0 0' },
    { label: 'cron with seconds rate "*/5" (too frequent)', cron: '*/5 * * * * ?' },
    { label: 'empty cron string', cron: '' },
    { label: 'SQL injection attempt in cron', cron: "0 0 0 * * ?'; DROP TABLE users; --" },
  ];
  for (let i = 0; i < badCronPatterns.length; i++) {
    const p = badCronPatterns[i];
    test(`Round ${String(11 + i).padStart(2, '0')}: BOUNDARY "${p.label}"`, async ({ request }) => {
      if (!firstTask) test.skip();
      const r = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
        cronExpression: p.cron,
        enabled: true,
      });
      // Backend should reject bad cron with 4xx
      const axis4 = axis4FourInOneUx(r.status, r.body);
      const summary = `axis4=${axis4.passed ? 'PASS' : 'FAIL'} ${axis4.detail}`;
      expect(axis4.passed, summary).toBe(true);
    });
  }

  // ── Rounds 16-20: wire-through / idempotency ──
  for (let i = 0; i < 5; i++) {
    test(`Round ${String(16 + i).padStart(2, '0')}: WIRE idempotent update cycle ${i + 1}`, async ({ request }) => {
      if (!firstTask) test.skip();
      const cron = '0 0 4 * * ?';
      const r1 = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
        cronExpression: cron,
        enabled: true,
      });
      const r2 = await callApi(request, 'PUT', UPDATE_PATH(firstTask.taskCode), {
        cronExpression: cron,
        enabled: true,
      });
      // Both writes succeed (idempotent)
      expect(r1.status).toBe(200);
      expect(r2.status).toBe(200);
      const refetch = await callApi(request, 'GET', LIST_PATH);
      const found = refetch.body.data.find((x: any) => x.taskCode === firstTask.taskCode);
      expect(found.cronExpression).toBe(cron);
    });
  }
});
