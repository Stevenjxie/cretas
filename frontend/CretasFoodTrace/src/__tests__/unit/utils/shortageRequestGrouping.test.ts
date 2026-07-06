import {
  groupShortageRequests,
  normalizeShortageQuantity,
} from '../../../utils/shortageRequestGrouping';

describe('shortageRequestGrouping', () => {
  it('groups identical product and quantity entries into one request group', () => {
    const entries = Array.from({ length: 51 }, (_, idx) => ({
      batchId: idx + 1,
      productTypeId: '1d7fbd73-8797-4933-83f1-46413a45992d',
      plannedQuantity: 109,
    }));

    const groups = groupShortageRequests(entries);

    expect(groups).toHaveLength(1);
    const group = groups[0]!;
    expect(group).toMatchObject({
      productTypeId: '1d7fbd73-8797-4933-83f1-46413a45992d',
      quantity: 109,
    });
    expect(group.batchIds).toHaveLength(51);
  });

  it('keeps different products or quantities in separate request groups', () => {
    const groups = groupShortageRequests([
      { batchId: 1, productTypeId: 'p1', plannedQuantity: 10 },
      { batchId: 2, productTypeId: 'p1', plannedQuantity: 12 },
      { batchId: 3, productTypeId: 'p2', plannedQuantity: 10 },
      { batchId: 4, productTypeId: null, plannedQuantity: 10 },
    ]);

    expect(groups.map((g) => g.key).sort()).toEqual(['p1|10', 'p1|12', 'p2|10']);
  });

  it('normalizes missing or invalid quantities to one', () => {
    expect(normalizeShortageQuantity(undefined)).toBe(1);
    expect(normalizeShortageQuantity(null)).toBe(1);
    expect(normalizeShortageQuantity(0)).toBe(1);
    expect(normalizeShortageQuantity(Number.NaN)).toBe(1);
    expect(normalizeShortageQuantity(2.5)).toBe(2.5);
  });
});
