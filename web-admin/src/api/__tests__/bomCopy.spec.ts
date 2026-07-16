import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../request', () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { get, post } from '../request';
import { bomRecipeApi, type CopyBomToProductRequest } from '../bom';

describe('BOM same-source copy API', () => {
  beforeEach(() => {
    vi.mocked(get).mockReset().mockResolvedValue({ success: true, data: [], message: 'OK' });
    vi.mocked(post).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  });

  it('requests candidates for the selected target product without a global error toast', async () => {
    await bomRecipeApi.getCopyCandidates('F006', 'P350');

    expect(get).toHaveBeenCalledWith('/F006/bom/recipes/copy-candidates', {
      params: { targetProductTypeId: 'P350' },
      _silent: true,
    });
  });

  it('posts explicitly selected rule ids to the draft-only endpoint', async () => {
    const request: CopyBomToProductRequest = {
      targetProductTypeId: 'P350',
      sourceRecipeId: 'R400',
      bomItemIds: [11],
      seasoningItemIds: [21],
      processSeasoningParamIds: [31],
    };
    await bomRecipeApi.copyToProduct('F006', request);

    expect(post).toHaveBeenCalledWith('/F006/bom/recipes/copy-to-draft', request);
  });
});
