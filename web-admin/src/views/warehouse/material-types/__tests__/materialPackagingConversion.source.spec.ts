import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('raw material purchase packaging conversion', () => {
  it('places the conversion editor directly below the inventory unit', () => {
    const inventoryUnitIndex = source.indexOf('<el-form-item label="入库计量单位" required>');
    const packagingConversionIndex = source.indexOf(
      '<el-form-item v-if="form.unit" label="包装换算">',
    );
    const storageTypeIndex = source.indexOf(
      '<el-form-item v-if="!isPackagingMaterial" label="储存类型" required>',
    );

    expect(inventoryUnitIndex).toBeGreaterThan(-1);
    expect(packagingConversionIndex).toBeGreaterThan(inventoryUnitIndex);
    expect(storageTypeIndex).toBeGreaterThan(packagingConversionIndex);
    expect(source).not.toContain('采购与库存单位换算（可选）');
  });

  it('uses the same dynamic direct-to-base rule model as SKU creation', () => {
    expect(source).toContain('v-for="(rule, index) in packagingRules"');
    expect(source).toContain('添加多包装换算规则');
    expect(source).toContain('1 箱 = 10 kg');
    expect(source).toContain('packagingSpecs: submittedPackagingRules');
    expect(source).not.toContain('label="二级换算"');
    expect(source).not.toContain('label="三级换算"');
  });

  it('fails closed until every visible conversion rule is complete', () => {
    expect(source).toContain('return !hasUnit || !hasFactor');
    expect(source).toContain("return ElMessage.warning('请完整填写每条包装规则的包装单位和换算数')");
    expect(source).not.toContain('.filter((rule) => rule.packageUnit?.trim()');
  });

  /**
   * 🔴 2026-08-06 客户事故: 「至少一条」+「包装单位不能与基本单位相同」+「第一条删不掉」
   * 三条合起来, 让「抄码牛肉、没有固定重量一箱」在系统里没有任何合法表达方式。
   * 六膳门于是编了个假的 `1 斤 = 1 kg`(物理上 1 斤 = 0.5kg), 收货界面从此把 kg 显示成「斤」。
   *
   * 后端本来就接受空列表, 收货侧也会回落到「采购单规格」——「不配包装换算」一直是支持的,
   * 只有这个表单不让填。
   */
  it('🔴 包装换算是可选的: 不能再强制至少一条, 第一条也必须能删', () => {
    expect(source).not.toContain('请至少填写一条采购包装换算');
    expect(source).not.toContain('if (!packagingRules.value.length)');
    // 删除按钮不得再对第一条隐藏
    expect(source).not.toContain('if (index === 0) return;');
    expect(source).not.toContain('v-if="index > 0"');
    // 空态要自己解释清楚「留空会怎样」
    expect(source).toContain('packaging-rule-empty');
    expect(source).toContain('直接记账，不做包装折算');
  });

  it('🔴 智能填充不得填出一条必被拒绝的规则(包装单位==基本单位)', () => {
    expect(source).toContain('suggestedSameAsBase');
    expect(source).toContain('sameUnit(d.level2Unit, form.value.unit)');
  });

  it('同一性判断用 sameUnit, 不是字面比较(kg 与 公斤 是同一个单位)', () => {
    expect(source).toContain('sameUnit(rule.packageUnit, form.value.unit)');
    expect(source).not.toContain("rule.packageUnit.trim().toLowerCase() === form.value.unit.trim().toLowerCase()");
  });

  it('limits packaging-unit choices to the shared purchase quantity contract', () => {
    expect(source.match(/usage-scope="PURCHASE_QUANTITY"/g)?.length).toBeGreaterThanOrEqual(2);
  });
});
