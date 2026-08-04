import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(__dirname, '../index.vue'), 'utf-8');

describe('遗留用量列不再参与成本', () => {
  it('材料成本合计不读 standardQuantity', () => {
    // 主链路没有数量：原料用多少由报工决定，前端不得据 standardQuantity 推成本
    const materialTotalBlock = source.slice(
      source.indexOf('const materialCostTotal'),
      source.indexOf('const materialCostTotal') + 600,
    );
    expect(materialTotalBlock).not.toMatch(/standardQuantity/);
  });

  it('不再用 standardQuantity 判断待归集状态', () => {
    expect(source).not.toMatch(/hasPendingActualMaterialUsage/);
  });
});
