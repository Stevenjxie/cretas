import { describe, expect, it } from 'vitest';
import {
  defaultUnitCode,
  findDuplicateUnit,
  mergeSystemUnitSources,
  normalizeUnitIdentity,
} from '../systemUnits';

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

  it('keeps box=盒 and case=箱 distinct when a historic global row mislabeled box as 箱', () => {
    const merged = mergeSystemUnitSources(
      [{ unitCode: 'box', unitName: '箱', unitSymbol: '箱', isSystem: true, isActive: true }],
      [
        { code: 'box', label: '盒', dimension: 'PACKAGE', baseCode: 'box', displayScale: 0 },
        { code: 'case', label: '箱', dimension: 'PACKAGE', baseCode: 'case', displayScale: 0 },
      ],
    );

    expect(merged.map(({ unitCode, unitName }) => ({ unitCode, unitName }))).toEqual([
      { unitCode: 'box', unitName: '盒' },
      { unitCode: 'case', unitName: '箱' },
    ]);
    expect(findDuplicateUnit(merged, ['盒'])?.unitCode).toBe('box');
    expect(findDuplicateUnit(merged, ['箱'])?.unitCode).toBe('case');
  });
});
