import { describe, expect, it } from 'vitest';
import { defaultUnitCode, findDuplicateUnit, normalizeUnitIdentity } from '../systemUnits';

const units = [
  { unitCode: 'pcs', unitName: '只', unitSymbol: '只', aliases: ['个', 'piece'] },
  { unitCode: 'kg', unitName: '千克', unitSymbol: 'kg', aliases: '公斤,千克' },
];

describe('system unit identity', () => {
  it('detects duplicates by code, name, symbol, and alias', () => {
    expect(findDuplicateUnit(units, ['PCS'])?.unitName).toBe('只');
    expect(findDuplicateUnit(units, [' 个 '])?.unitName).toBe('只');
    expect(findDuplicateUnit(units, ['KG'])?.unitName).toBe('千克');
    expect(findDuplicateUnit(units, ['公斤'])?.unitName).toBe('千克');
  });

  it('normalizes full-width text and creates a valid compact default code', () => {
    expect(normalizeUnitIdentity(' ＫＧ ')).toBe('kg');
    expect(defaultUnitCode(' 托 盘 ')).toBe('托_盘');
  });
});
