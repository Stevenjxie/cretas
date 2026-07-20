import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('order creation contracts', () => {
  const purchase = source('src/views/procurement/orders/list.vue');
  const sales = source('src/views/sales/orders/list.vue');
  const startPurchase = source('src/components/dialog/StartPurchaseDialog.vue');
  const mergePurchase = source('src/components/dialog/MergePurchaseDialog.vue');
  const bom = source('src/views/production/bom/index.vue');

  it('never posts header-only purchase or sales orders', () => {
    expect(purchase).toContain(':disabled-modes="[\'quick\', \'batch\']"');
    expect(sales).toContain(':disabled-modes="[\'quick\', \'batch\']"');
    expect(purchase).not.toContain('items: [] as unknown[]');
    expect(sales).not.toContain('items: [] as unknown[]');
    expect(purchase).toContain('采购订单必须至少包含 1 项物料明细');
    expect(sales).toContain('批量销售订单必须逐单填写商品明细');
  });

  it('uses the Shanghai factory-local calendar date for purchase creation', () => {
    for (const text of [purchase, startPurchase, mergePurchase]) {
      expect(text).toContain("new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' })");
    }
    expect(purchase).toContain('orderDate: isEditing.value ? editingOrderDate.value : factoryToday()');
    expect(startPurchase).not.toContain("new Date().toISOString().slice(0, 10)");
    expect(mergePurchase).not.toContain("new Date().toISOString().slice(0, 10)");
  });

  it('preserves an explicit zero tax rate when editing a BOM line', () => {
    expect(bom).toContain('taxRate: row.taxRate ?? 13');
    expect(bom).not.toContain('taxRate: row.taxRate || 13');
  });
});
