import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const pricingSource = readFileSync(
  resolve(__dirname, '..', '..', '..', '..', 'utils', 'unitPricing.ts'), 'utf8');

/**
 * 客户 2026-07-30 (Sheet 第 38 行):「在成品SKU中设定完换算规格后, 生成采购订单的时候
 * 将不再自动填入供应商中设定好的采购单价。」
 *
 * 成因: 配了采购规格后计价单位变成规格的包装单位 (箱), 而供应关系价是按库存基本单位
 * (kg) 配的 —— applyRelationPrice 单位不等就把价清空成 MISSING。
 *
 * 换算由**后端**用该规格行自己声明的 conversionFactor 完成 (见
 * SupplierMaterialPurchaseSpecServiceImpl#withDerivedPrice)。这里守的是前端那一半:
 * 只搬后端给的数, 不自己乘系数 —— 本仓的既有口径是「金额只接受三种权威口径」。
 */
describe('采购规格无自报价时用后端换算价 (Sheet 第 38 行)', () => {
  it('规格自报价仍然优先, 其次才是后端换算价, 最后才回落到关系价', () => {
    const branchOrder = listSource.indexOf('hasConfiguredPrice(spec?.quotedPrice)');
    const derivedBranch = listSource.indexOf('hasConfiguredPrice(spec?.derivedPrice)');
    const fallback = listSource.indexOf('applyRelationPrice(item, relation, unit)');
    expect(branchOrder).toBeGreaterThan(-1);
    expect(derivedBranch).toBeGreaterThan(branchOrder);
    expect(fallback).toBeGreaterThan(derivedBranch);
  });

  it('用后端给的换算价时, 计价单位也跟着后端走 —— 不能沿用旧单位标签', () => {
    expect(listSource).toContain('item.unitPrice = Number(spec?.derivedPrice)');
    expect(listSource).toContain("canonicalUnitCode(spec?.derivedPriceUnit || unit)");
  });

  it('换算过的价必须在界面上说清来源, 不能伪装成用户自己配的那个数', () => {
    expect(listSource).toContain("'SUPPLIER_RELATION_CONVERTED'");
    expect(listSource).toContain('SUPPLIER_RELATION_CONVERTED: ');
    expect(listSource).toMatch(/SUPPLIER_RELATION_CONVERTED: '[^']*换算[^']*'/);
  });

  it('前端仍然不做单位数学 —— 没有把 factor 乘进价格的代码', () => {
    // 换算只允许发生在后端; 前端出现 price * factor 就是把口径重新分叉了
    expect(listSource).not.toMatch(/unitPrice\s*=\s*[^;]*\*\s*[^;]*factor/i);
    expect(listSource).not.toMatch(/derivedPrice\s*\*\s*/);
    // 既有的三种权威口径注释仍在, 说明这条口径没被顺手改掉
    expect(pricingSource).toContain('金额只接受三种权威口径');
  });
});
