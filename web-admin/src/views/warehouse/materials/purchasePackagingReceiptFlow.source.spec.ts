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

  /**
   * 客户台账(六膳门「原辅材进出库明细」)按这几列记来料并据此追溯:
   * 来料日期 | 料号 | 原料名称 | 合同号 | 批次号 | 厂号 | 件数(件/箱) | 初期重量KG
   * 收货弹窗此前一个都没有 —— 客户原话「收货里面要能填写这些信息」。
   */
  it('🔴 收货行必须能填客户台账要的可追溯字段', () => {
    expect(source).toContain('label="合同号"');
    expect(source).toContain('label="供应商批次号"');
    expect(source).toContain('label="厂号"');
    expect(source).toContain('label="件数"');
    // 厂号沿用「选厂商登记 → 自动带产地」, 不新造自由文本口径
    expect(source).toContain('onFactoryNumberChange(row)');
    expect(source).toContain('listManufacturers');
  });

  it('可追溯字段要随收货单一起提交, 且空串不落库', () => {
    expect(source).toContain('contractNumber: blankToUndefined(item.contractNumber)');
    expect(source).toContain('supplierBatchNumber: blankToUndefined(item.supplierBatchNumber)');
    expect(source).toContain('factoryNumber: blankToUndefined(item.factoryNumber)');
    expect(source).toContain('boxCount:');
  });

  /**
   * 客供料与采购来料没有理由只有一边能记 —— 客户台账不区分来料是买的还是客户送的。
   * 2026-08-06 第一版只给采购收货加了字段, 客供料那半漏了。
   */
  it('客供料收货也要有同一套可追溯字段', () => {
    expect(source).toContain('customerForm.contractNumber');
    expect(source).toContain('customerForm.factoryNumber');
    expect(source).toContain('customerForm.boxCount');
    expect(source).toContain('onCustomerFactoryNumberChange');
  });
});
