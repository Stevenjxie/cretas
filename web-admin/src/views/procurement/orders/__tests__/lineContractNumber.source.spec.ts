import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const orderSource = readFileSync(
  resolve(process.cwd(), 'src/views/procurement/orders/list.vue'),
  'utf8',
);
const receivingSource = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue'),
  'utf8',
);

/**
 * 🔴 客户(张权 2026-08-06 微信)：「这个下采购单的时候可以填写」，指的是他 Excel 台账里
 * 的合同号那一列。
 *
 * `purchase_orders.contract_number`（单头框架合同号）早就有，但**放不下**客户的实际单据：
 *   PO-20260806-0001
 *     行1 牛外脊西冷MB2+谷饲100天  995.75 kg  合同号 SAN-16572
 *     行2 牛外脊西冷MB2+谷饲100天 1010.65 kg  合同号 SAN-16562
 * 同一张单两行两个合同号，单头填哪个都是错的 → 必须行级。
 *
 * 这组断言锁的是**行级存在 + 能带到收货**，不是某种写法。
 */
describe('采购单行级合同号', () => {
  it('新建行时带 contractNumber 字段，且类型上认这个字段', () => {
    expect(orderSource).toContain('contractNumber?: string;');
    // newPurchaseItem 的返回对象里必须有它，否则新增行拿不到 v-model 目标
    const newItemBlock = orderSource.slice(
      orderSource.indexOf('function newPurchaseItem'),
      orderSource.indexOf('function loadPurchaseSpecs'),
    );
    expect(newItemBlock).toContain("contractNumber: ''");
  });

  it('行编辑区有合同号输入框，且绑到行上而不是单头', () => {
    expect(orderSource).toContain('v-model="item.contractNumber"');
    // 单头那个是框架合同号，保留，两者不能互相顶替
    expect(orderSource).toContain('v-model="form.contractNumber"');
  });

  it('编辑已有订单时把行级合同号读回来', () => {
    expect(orderSource).toContain("contractNumber: String(item.contractNumber || '')");
  });

  it('收货界面从待收任务的行预填合同号 —— 仓管不用照纸质合同重敲', () => {
    expect(receivingSource).toContain("contractNumber: String(item.contractNumber || '')");
    // 预填不能把其它可追溯字段也一起「猜」出来：它们只能现场填
    expect(receivingSource).toContain("supplierBatchNumber: ''");
    expect(receivingSource).toContain("factoryNumber: ''");
  });
});
