import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const orderSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const suggestionSource = readFileSync(
  resolve(import.meta.dirname, '../../../../components/dialog/StartPurchaseDialog.vue'),
  'utf8',
);

describe('采购原料多包装选择契约', () => {
  it('供应商未维护专属规格时回退原料类型包装规格', () => {
    expect(orderSource).toContain('loadMaterialSpecs(relation.materialTypeId)');
    expect(orderSource).toContain('supplierSpecs.length > 0');
    expect(orderSource).toContain("id: `material:${row.id}`");
    expect(orderSource).toContain('materialPackagingSpecId: i.purchasePackagingSpecId?.startsWith');
  });

  it('开始采购弹窗读取全部动态包装规格并提交具体规格身份', () => {
    expect(suggestionSource).toContain('packagingSpecs?: Array');
    expect(suggestionSource).toContain('materialPackagingSpecId: packagingByMaterial.value');
    expect(suggestionSource).toContain('dynamic.join');
  });
});
