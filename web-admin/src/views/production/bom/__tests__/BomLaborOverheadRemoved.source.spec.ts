import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(
  resolve(__dirname, '../index.vue'),
  'utf-8',
);

describe('BOM 页不再承载人工与均摊费用', () => {
  it('没有人工成本的状态与请求', () => {
    expect(source).not.toMatch(/laborCosts/);
    expect(source).not.toMatch(/\/bom\/labor/);
  });

  it('没有均摊费用的状态与请求', () => {
    expect(source).not.toMatch(/overheadCosts/);
    expect(source).not.toMatch(/\/bom\/overhead/);
  });

  it('没有「人工与均摊费用」区块标题', () => {
    expect(source).not.toContain('人工与均摊费用');
    expect(source).not.toContain('均摊费用表');
  });

  it('成本卡不再展示人工/均摊小计', () => {
    expect(source).not.toMatch(/laborCostTotal/);
    expect(source).not.toMatch(/overheadCostTotal/);
  });
});
