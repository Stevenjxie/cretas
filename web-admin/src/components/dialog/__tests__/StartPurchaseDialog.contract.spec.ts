import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/components/dialog/StartPurchaseDialog.vue'), 'utf8');

describe('StartPurchaseDialog purchase-order contract', () => {
  it('keeps supplier optional while exposing the existing supplier selector', () => {
    expect(source).toContain('supplierId: supplierId.value || null');
    expect(source).toContain('供应商（选填）');
    expect(source).toContain("`/${props.factoryId}/suppliers`");
  });

  it('submits canonical quantity and price units plus supported packaging fields', () => {
    expect(source).toContain('quantityUnit: it.quantityUnit');
    expect(source).toContain('priceUnit: it.priceUnit');
    expect(source).toContain('specification: it.specification || null');
    expect(source).toContain('boxQuantity: it.boxQuantity');
    expect(source).toContain('material-packaging/by-material');
  });

  it('does not invent an unsupported packagingSpecId contract', () => {
    expect(source).not.toContain('packagingSpecId');
  });

  it('does not let a unit switch silently change the physical purchase quantity', () => {
    expect(source).toContain('function quantityUnitToLevel1Factor');
    expect(source).toContain('不能直接切换单位');
    expect(source).toContain('clearSuggestionAmount(item)');
    expect(source).not.toContain('allow-create');
  });

  it('invalidates the numeric price when its pricing unit changes', () => {
    expect(source).toContain('function onPriceUnitChange');
    expect(source).toContain('item.unitPrice = null');
    expect(source).toContain('@change="onPriceUnitChange(row)"');
    expect(source).toContain('切换计价单位后需重新输入对应单价');
  });
});
