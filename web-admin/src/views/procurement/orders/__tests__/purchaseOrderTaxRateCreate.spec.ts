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
   * ⚠️ 副作用: `@/utils/orderPayloadBuilders` 的 `buildBomPurchaseOrderPayload`
   * 现在没有任何生产代码调用, 只剩它自己的单测在给它「覆盖率」。要么接回去,
   * 要么删掉 —— 别让一个没人调用的函数看起来是被测过的。
   */
  it('sends taxRate on normal create payload items without fake defaults', () => {
    expect(listSource).toContain('taxRate: normalizeTaxRateForPayload(i.taxRate)');
    expect(listSource).not.toContain('taxRate: 13');
  });
});
