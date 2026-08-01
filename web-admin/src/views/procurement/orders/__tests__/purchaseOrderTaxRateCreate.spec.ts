import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

describe('procurement order tax rate create entry', () => {
  it('exposes per-line taxRate input and validation in the normal create dialog', () => {
    expect(listSource).toContain('taxRate?: number | string | null');
    expect(listSource).toContain('v-model="item.taxRate"');
    expect(listSource).toContain('placeholder="请选择税率"');
    expect(listSource).toContain('0%');
    expect(listSource).toContain('9%');
    expect(listSource).toContain('13%');
    expect(listSource).toContain('validateTaxRate(item.taxRate)');
    expect(listSource).toContain('税率必须是 0-100 之间的数字');
    expect(listSource).toContain('请选择税率（免税请选择 0%）');
  });

  /**
   * 原用例还断言了 BOM 建单侧的两条 (`buildBomPurchaseOrderPayload(...)` /
   * `normalizeTaxRateForPayload(tpl.taxRate)`), 但 #1557 (2026-07-21
   * 「connect procurement supplier contracts and OA」) 把整个
   * `submitBomPurchaseOrder` 流程从本页删掉了, 且没有搬到别的页面 ——
   * 这两条从那天起断言的是**不存在的代码**, 一直红着没人看见。
   *
   * ✅ 2026-08-01 结案: `buildBomPurchaseOrderPayload` 连同它唯一的调用方一起**已删除**
   * (含 `normalizePurchaseType` 与两个 BomPurchaseOrder* 接口, 以及那条只服务于它的单测)。
   * 选删不选接回: 调用方 #1557 删掉后没搬去任何页面, 即这个功能本身已经不在了 ——
   * 留着一个零调用函数 + 它自己的单测, 只会让覆盖率看起来是真的。
   */
  it('sends taxRate on normal create payload items without fake defaults', () => {
    expect(listSource).toContain('taxRate: normalizeTaxRateForPayload(i.taxRate)');
    expect(listSource).not.toContain('taxRate: 13');
  });
});
