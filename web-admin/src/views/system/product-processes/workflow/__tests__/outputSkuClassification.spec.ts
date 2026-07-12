import { describe, expect, it } from 'vitest';
import { classifyOutputSkuCategory, matchOutputSkuByName, type OutputSkuCandidate } from '../outputSkuClassification';

describe('workflow output SKU classification', () => {
  it.each([
    ['SEMI_FINISHED', 'SEMI_FINISHED'],
    ['FINISHED_PRODUCT', 'FINISHED_GOOD'],
    ['CONTRACT_MANUFACTURING', 'FINISHED_GOOD'],
    ['CUSTOMER_MATERIAL', 'FINISHED_GOOD'],
    ['DISH', 'FINISHED_GOOD'],
    ['COMBO', 'FINISHED_GOOD'],
  ] as const)('maps %s to %s', (productCategory, expectedKind) => {
    expect(classifyOutputSkuCategory(productCategory)).toBe(expectedKind);
  });

  it.each([
    'RAW_MATERIAL',
    'PACKAGING',
    'SEASONING',
    'ADD_ON',
    undefined,
    'UNKNOWN_CATEGORY',
  ])('rejects %s as a workflow output category', (productCategory) => {
    expect(classifyOutputSkuCategory(productCategory)).toBeNull();
  });
});

// #5 AI 建流程自动绑定产出 SKU 的纯匹配逻辑
describe('matchOutputSkuByName (#5 AI 自动绑定产出 SKU)', () => {
  const pool: OutputSkuCandidate[] = [
    { id: 's1', name: '卤味半成品', code: 'SFI-1', unit: 'kg', productCategory: 'SEMI_FINISHED' },
    { id: 'f1', name: '切片成品', code: 'FG-1', unit: '盒', productCategory: 'FINISHED_PRODUCT' },
    { id: 'f2', name: '切片成品', code: 'FG-2', unit: '盒', productCategory: 'FINISHED_PRODUCT' }, // 同名歧义
    { id: 'r1', name: '原料鸭', productCategory: 'RAW_MATERIAL' },
  ];

  it('binds a unique exact-name match of the right kind', () => {
    expect(matchOutputSkuByName('卤味半成品', 'SEMI_FINISHED', pool)?.id).toBe('s1');
  });

  it('normalizes whitespace before matching (中文不 lowercase)', () => {
    expect(matchOutputSkuByName('  卤味 半成品 ', 'SEMI_FINISHED', pool)?.id).toBe('s1');
  });

  it('returns null when no name is given', () => {
    expect(matchOutputSkuByName('', 'SEMI_FINISHED', pool)).toBeNull();
    expect(matchOutputSkuByName(undefined, 'FINISHED_GOOD', pool)).toBeNull();
  });

  it('returns null when the target kind differs (半成品名 vs 成品 kind)', () => {
    expect(matchOutputSkuByName('卤味半成品', 'FINISHED_GOOD', pool)).toBeNull();
  });

  it('returns null on ambiguous duplicates (≥2 同名 → 留手动, 防呆不乱绑)', () => {
    expect(matchOutputSkuByName('切片成品', 'FINISHED_GOOD', pool)).toBeNull();
  });

  it('never matches a raw material even if kind hint is finished', () => {
    expect(matchOutputSkuByName('原料鸭', 'FINISHED_GOOD', pool)).toBeNull();
    expect(matchOutputSkuByName('原料鸭', 'SEMI_FINISHED', pool)).toBeNull();
  });
});
