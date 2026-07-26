import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(import.meta.dirname, 'PendingPurchaseReceivingPanel.vue'), 'utf8');

describe('采购收货多包装与基本单位落账契约', () => {
  it('实际到货包装可选，并即时展示折合基本量', () => {
    expect(source).toContain('label="实际到货包装"');
    expect(source).toContain('v-model="row.packagingKey"');
    expect(source).toContain('baseQuantityPreview(row)');
    expect(source).toContain('materialPackagingSpecId: item.materialPackagingSpecId');
  });

  it('收货上限按采购基本量与所选包装系数换算', () => {
    expect(source).toContain('remainingReceivableQuantity || 0) * orderFactor / selectedFactor');
    expect(source).toContain(':max="remainingLimit(row)"');
  });
});
