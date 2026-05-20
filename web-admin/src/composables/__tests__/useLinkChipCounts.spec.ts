/**
 * Sprint 6 W3-A — unit test for useLinkChipCounts composable.
 *
 * Pins the contract between the batch 3-chip endpoint
 * (POST /attachments/batch-3chip-counts) and the row template:
 * - response array → Map<entityId, {fileCount, imageCount, contractCount}>
 * - missing entityId → ZERO_COUNTS (chip renders "-")
 * - request failure → silent, map cleared (chip renders "-")
 *
 * Mocks @/api/request POST so we control the envelope shape (similar to
 * useListSummary.spec.ts pattern).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ref, nextTick } from 'vue';

// ── Top-level mocks (vi.mock is hoisted) ──────────────────────────────

const postMock = vi.fn();
vi.mock('@/api/request', () => ({
  post: (...args: unknown[]) => postMock(...args),
  get: vi.fn(),
}));

// Import composable AFTER mocks are set up.
import { useLinkChipCounts } from '../useLinkChipCounts';

// ── Fixtures ──────────────────────────────────────────────────────────

const FACTORY_F006 = ref<string | null>('F006');

const THREE_CHIP_RESPONSE = {
  success: true,
  data: [
    { entityId: 'SO-001', fileCount: 3, imageCount: 2, contractCount: 1 },
    { entityId: 'SO-002', fileCount: 0, imageCount: 5, contractCount: 0 },
    // SO-003 absent — should render ZERO_COUNTS
  ],
};

// ── Tests ─────────────────────────────────────────────────────────────

describe('useLinkChipCounts', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('maps backend response into per-entity 3-chip counts', async () => {
    postMock.mockResolvedValue(THREE_CHIP_RESPONSE);

    const { fetchLinkChipCounts, countsFor, totalFor } =
      useLinkChipCounts(FACTORY_F006, 'SALES_ORDER');

    await fetchLinkChipCounts(['SO-001', 'SO-002', 'SO-003']);
    await nextTick();

    // POST endpoint + body shape (replaces Sprint 5 PR #58 unified endpoint).
    expect(postMock).toHaveBeenCalledOnce();
    expect(postMock.mock.calls[0][0]).toBe('/F006/attachments/batch-3chip-counts');
    expect(postMock.mock.calls[0][1]).toEqual({
      entityType: 'SALES_ORDER',
      entityIds: ['SO-001', 'SO-002', 'SO-003'],
    });

    // SO-001 — all 3 chips populated.
    expect(countsFor('SO-001')).toEqual({ fileCount: 3, imageCount: 2, contractCount: 1 });
    expect(totalFor('SO-001')).toBe(6);

    // SO-002 — only image chip.
    expect(countsFor('SO-002')).toEqual({ fileCount: 0, imageCount: 5, contractCount: 0 });
    expect(totalFor('SO-002')).toBe(5);

    // SO-003 — absent from response, falls back to zeros (chip renders "-").
    expect(countsFor('SO-003')).toEqual({ fileCount: 0, imageCount: 0, contractCount: 0 });
    expect(totalFor('SO-003')).toBe(0);
  });

  it('silently swallows network failure and falls back to zero counts', async () => {
    postMock.mockRejectedValue(new Error('Network 500'));

    const { fetchLinkChipCounts, countsFor, totalFor } =
      useLinkChipCounts(FACTORY_F006, 'INVENTORY');

    // Must not throw — list itself must not be blocked by chip request.
    await expect(fetchLinkChipCounts(['INV-001'])).resolves.toBeUndefined();

    expect(countsFor('INV-001')).toEqual({ fileCount: 0, imageCount: 0, contractCount: 0 });
    expect(totalFor('INV-001')).toBe(0);
  });

  it('skips API call when entityIds is empty or factoryId missing', async () => {
    const { fetchLinkChipCounts } = useLinkChipCounts(FACTORY_F006, 'PURCHASE_ORDER');

    await fetchLinkChipCounts([]);
    expect(postMock).not.toHaveBeenCalled();

    const nullFactory = ref<string | null>(null);
    const { fetchLinkChipCounts: fetchNull } = useLinkChipCounts(nullFactory, 'PURCHASE_ORDER');
    await fetchNull(['PO-001']);
    expect(postMock).not.toHaveBeenCalled();
  });

  it('handles null/undefined entityId in countsFor() gracefully', () => {
    const { countsFor, totalFor } = useLinkChipCounts(FACTORY_F006, 'SALES_ORDER');

    expect(countsFor(null)).toEqual({ fileCount: 0, imageCount: 0, contractCount: 0 });
    expect(countsFor(undefined)).toEqual({ fileCount: 0, imageCount: 0, contractCount: 0 });
    expect(totalFor(null)).toBe(0);
  });

  it('caps batch size at 500 ids per request', async () => {
    postMock.mockResolvedValue({ success: true, data: [] });

    const { fetchLinkChipCounts } = useLinkChipCounts(FACTORY_F006, 'SALES_ORDER');

    const lots = Array.from({ length: 750 }, (_, i) => `SO-${i}`);
    await fetchLinkChipCounts(lots);

    expect(postMock).toHaveBeenCalledOnce();
    const body = postMock.mock.calls[0][1] as { entityIds: string[] };
    expect(body.entityIds.length).toBe(500);
    expect(body.entityIds[0]).toBe('SO-0');
    expect(body.entityIds[499]).toBe('SO-499');
  });
});
