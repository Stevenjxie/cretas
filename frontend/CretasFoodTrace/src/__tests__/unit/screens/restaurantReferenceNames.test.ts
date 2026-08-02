jest.mock('../../../services/api/materialTypeApiClient', () => ({
  materialTypeApiClient: { getMaterialTypes: jest.fn() },
}));

jest.mock('../../../services/api/productTypeApiClient', () => ({
  productTypeApiClient: { getProductTypes: jest.fn() },
}));

import {
  extractRestaurantReferenceItems,
  resolveRestaurantReferenceName,
} from '../../../screens/restaurant/hooks/useRestaurantReferenceNames';

describe('resolveRestaurantReferenceName', () => {
  it('prefers the name returned with the business record', () => {
    expect(resolveRestaurantReferenceName('鲜牛肉', 'RM-001', { 'RM-001': '牛肉' }, '待同步')).toBe('鲜牛肉');
  });

  it('resolves a missing record name from reference master data', () => {
    expect(resolveRestaurantReferenceName(undefined, 'RM-001', { 'RM-001': '鲜牛肉' }, '待同步')).toBe('鲜牛肉');
  });

  it('uses a user-facing fallback instead of exposing the internal id', () => {
    expect(resolveRestaurantReferenceName(undefined, 'RM-INTERNAL-001', {}, '食材信息待同步')).toBe('食材信息待同步');
  });
});

describe('extractRestaurantReferenceItems', () => {
  const item = { id: 'RM-001', name: '鲜牛肉' };

  it.each([
    ['array', [item]],
    ['data array', { data: [item] }],
    ['paged data', { data: { content: [item] } }],
    ['paged content', { content: [item] }],
  ])('normalizes %s responses', (_name, input) => {
    expect(extractRestaurantReferenceItems(input)).toEqual([item]);
  });
});
