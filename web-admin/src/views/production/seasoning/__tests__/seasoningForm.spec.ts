import { describe, expect, it } from 'vitest';
import {
  applySeasoningMaterial,
  filterSeasoningMaterials,
  isPotSequencingEnabled,
  percentToRatio,
  ratioToPercent,
  validatePotRatio,
  validateSeasoningRows,
  type SeasoningMaterialOption,
} from '../seasoningForm';

const materials: SeasoningMaterialOption[] = [
  { id: 'A1', name: '辣椒粉', materialCategory: 'AUXILIARY', unit: 'g', movingAvgPrice: 18.5 },
  { id: 'R1', name: '猪肉', materialCategory: 'RAW', unit: 'kg', movingAvgPrice: 24 },
  { id: 'A2', name: '复合香辛料', category: '调料', unit: '克', movingAvgPrice: null },
  { id: 'P1', name: '包装袋', materialCategory: 'PACKAGING', unit: 'pcs', movingAvgPrice: 0.2 },
];

describe('seasoning form helpers', () => {
  it('only keeps auxiliary and seasoning materials', () => {
    expect(filterSeasoningMaterials(materials).map((item) => item.id)).toEqual(['A1', 'A2']);
  });

  it('uses the shared material big-category rules for all auxiliary and seasoning aliases', () => {
    const aliases: SeasoningMaterialOption[] = [
      { id: '1', name: '辅材', category: '辅材' },
      { id: '2', name: '添加剂', category: '添加剂' },
      { id: '3', name: '调味料', category: '调味料' },
      { id: '4', name: '调味品', category: '调味品' },
      { id: '5', name: '服务端大类', category: '未知', materialCategory: 'AUXILIARY' },
      { id: '6', name: '主材', category: '主材' },
    ];
    expect(filterSeasoningMaterials(aliases).map((item) => item.id)).toEqual(['1', '2', '3', '4', '5']);
  });

  it('applies canonical material fields and one moving-average price snapshot', () => {
    expect(applySeasoningMaterial(materials[0])).toEqual({
      materialTypeId: 'A1',
      name: '辣椒粉',
      unit: 'g',
      priceSource1: 18.5,
      priceSource2: null,
    });
  });

  it('treats a non-null ratio, including zero, as explicit pot sequencing', () => {
    expect(isPotSequencingEnabled(null)).toBe(false);
    expect(isPotSequencingEnabled(0)).toBe(true);
    expect(isPotSequencingEnabled(0.5)).toBe(true);
  });

  it('converts the operator-facing percentage to the persisted 0-1 ratio', () => {
    expect(percentToRatio(50)).toBe(0.5);
    expect(ratioToPercent(0.3333)).toBeCloseTo(33.33);
    expect(percentToRatio(null)).toBeNull();
    expect(ratioToPercent(null)).toBeNull();
  });

  it('blocks historical free-text rows and incomplete new rows before save', () => {
    expect(validateSeasoningRows([
      { name: '旧辣椒粉', materialTypeId: null, dosagePerKgG: 5 },
    ])).toEqual(['「旧辣椒粉」是历史调料，请重新选择物料']);

    expect(validateSeasoningRows([
      { name: '辣椒粉', materialTypeId: 'A1', dosagePerKgG: null, priceSource1: 18.5 },
    ])).toEqual(['「辣椒粉」未填写每 1 kg 本工序投入用量']);

    expect(validateSeasoningRows([
      { name: '复合香辛料', materialTypeId: 'A2', dosagePerKgG: 3, priceSource1: null },
    ])).toEqual([]);
  });

  it('requires an enabled pot percentage to stay within 0-100', () => {
    expect(validatePotRatio(false, null)).toBeNull();
    expect(validatePotRatio(true, null)).toBe('请填写后续锅占第一锅的百分比');
    expect(validatePotRatio(true, 101)).toBe('后续锅比例必须在 0% 到 100% 之间');
    expect(validatePotRatio(true, 50)).toBeNull();
  });
});
