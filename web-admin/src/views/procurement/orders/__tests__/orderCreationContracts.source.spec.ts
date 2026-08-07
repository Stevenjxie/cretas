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
  // 2026-08-07 阶段 5: BOM 页已删, 行级税率现在只在画布的包材/辅料 cell 上编辑。
  // 契约不丢, 只是换了载体。
  const packagingCell = source(
    'src/views/system/product-processes/workflow/PackagingBindingDialog.vue',
  );

  it('never posts header-only purchase or sales orders', () => {
    expect(purchase).toContain('请至少添加一行原料明细');
    expect(purchase).not.toContain('items: [] as unknown[]');
    expect(sales).not.toContain('items: [] as unknown[]');
    expect(sales).toContain('items: [emptyOrderItem()] as OrderItem[]');
  });

  it('opens the only supported normal sales form directly and keeps AI entry', () => {
    expect(sales).toContain('@click="openCreateDialog"');
    expect(sales).toContain('@click="aiEntryVisible = true"');
    expect(sales).not.toContain('CreateModeSelector');
    expect(sales).not.toContain('BatchCreateDialog');
    expect(sales).not.toContain('QuickCreateDialog');
    expect(sales).not.toContain('BomExpansionDialog');
    expect(sales).not.toContain('选择录入方式');
  });

  it('opens the only supported normal purchase form directly and keeps AI entry', () => {
    expect(purchase).toContain('@click="openCreateDialog"');
    expect(purchase).toContain('@click="aiEntryVisible = true"');
    expect(purchase).not.toContain('CreateModeSelector');
    expect(purchase).not.toContain('BatchCreateDialog');
    expect(purchase).not.toContain('QuickCreateDialog');
    expect(purchase).not.toContain('BomExpansionDialog');
    expect(purchase).not.toContain('选择录入方式');
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
    // 旧断言钉的是 BOM 页里的 `?? 13`(而不是 `|| 13`, 后者会把显式 0% 吞成 13%)。
    // 画布这边用的是显式三分支映射, 结构上不可能吞 0 —— 断言跟着改成钉这个结构,
    // ⛔ 不许退化成「不断言」: 0% 税率被吞是真出过的问题。
    expect(packagingCell).toContain("taxRate === 'TAX_9' ? 9");
    expect(packagingCell).toContain("taxRate === 'TAX_13' ? 13 : 0");
    expect(packagingCell).not.toMatch(/taxRate[^\n]*\|\|\s*13/);
  });
});
