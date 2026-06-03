// web-admin/src/views/restaurant/analytics/__tests__/target-hierarchy.test.ts
import { describe, it, expect } from 'vitest';

// ── Test: API module exports the right shape ─────────────────────────
describe('restaurant-targets API client', () => {
  it('exports upsertTarget, fetchAchievement, fetchAlerts, upsertAlertConfig', async () => {
    const api = await import('@/api/smartbi/restaurant-targets');
    expect(typeof api.upsertTarget).toBe('function');
    expect(typeof api.fetchAchievement).toBe('function');
    expect(typeof api.fetchAlerts).toBe('function');
    expect(typeof api.upsertAlertConfig).toBe('function');
  });
});
