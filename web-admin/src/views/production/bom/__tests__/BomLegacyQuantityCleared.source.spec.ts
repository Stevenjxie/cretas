import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(__dirname, '../index.vue'), 'utf-8');

describe('遗留用量列不再参与成本', () => {
  it('材料成本合计只算包材，原料一律不计', () => {
    // 主链路没有数量：原料用多少由报工决定，前端不得据它推成本。
    // 但 standardQuantity 对包材是正经数据（每 1 份成品用量就存在这里），
    // 所以拦的是「有没有按类别过滤」，不是「有没有出现 standardQuantity」。
    const materialTotalBlock = source.slice(
      source.indexOf('const materialCostTotal'),
      source.indexOf('const materialCostTotal') + 900,
    );
    expect(materialTotalBlock).toMatch(/materialCategory !== 'PACKAGING'/);
    expect(materialTotalBlock).toMatch(/return sum;/);
    expect(materialTotalBlock).not.toMatch(/yieldRate/);
  });

  it('不再用 standardQuantity 判断待归集状态', () => {
    expect(source).not.toMatch(/hasPendingActualMaterialUsage/);
  });
});
