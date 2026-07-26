import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('raw material purchase packaging conversion', () => {
  it('places the required conversion editor directly below the inventory unit', () => {
    const inventoryUnitIndex = source.indexOf('<el-form-item label="入库计量单位" required>');
    const packagingConversionIndex = source.indexOf(
      '<el-form-item v-if="form.unit" label="包装换算" required>',
    );
    const storageTypeIndex = source.indexOf(
      '<el-form-item v-if="!isPackagingMaterial" label="储存类型" required>',
    );

    expect(inventoryUnitIndex).toBeGreaterThan(-1);
    expect(packagingConversionIndex).toBeGreaterThan(inventoryUnitIndex);
    expect(storageTypeIndex).toBeGreaterThan(packagingConversionIndex);
    expect(source).not.toContain('采购与库存单位换算（可选）');
    expect(source).toContain('请至少填写一条采购包装换算');
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
    expect(source).toContain('if (!packagingRules.value.length)');
    expect(source).toContain("return ElMessage.warning('请至少填写一条采购包装换算规则')");
    expect(source).toContain('return !hasUnit || !hasFactor');
    expect(source).toContain("return ElMessage.warning('请完整填写每条包装规则的包装单位和换算数')");
    expect(source).not.toContain('.filter((rule) => rule.packageUnit?.trim()');
  });

  it('limits packaging-unit choices to the shared purchase quantity contract', () => {
    expect(source.match(/usage-scope="PURCHASE_QUANTITY"/g)?.length).toBeGreaterThanOrEqual(2);
  });
});
