/**
 * #57 — Unit test for the dish-cost-card API client.
 *
 * Mocks `../request` and asserts getDishCostCard builds the correct Java
 * backend URL + portions param. The URL is load-bearing (a typo = 404).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../request', () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { get } from '../request';
import { getDishCostCard } from '../restaurant';

const F = 'RES_3101_009';

beforeEach(() => {
  vi.mocked(get).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
});

describe('getDishCostCard API client', () => {
  it('GETs the cost-card URL with default portions=1', async () => {
    await getDishCostCard(F, 'dish-1');
    expect(get).toHaveBeenCalledWith(
      `/${F}/restaurant/dishes/dish-1/cost-card`,
      { params: { portions: 1 } },
    );
  });

  it('passes an explicit portions value', async () => {
    await getDishCostCard(F, 'dish-1', 50);
    expect(get).toHaveBeenCalledWith(
      `/${F}/restaurant/dishes/dish-1/cost-card`,
      { params: { portions: 50 } },
    );
  });
});
