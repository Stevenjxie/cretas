import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('raw material purchase packaging conversion', () => {
  it('shows the conversion editor for raw materials as well as packaging materials', () => {
    expect(source).toContain('采购与库存单位换算（可选）');
    expect(source).toContain('采购 8 箱时，系统按快照折算为 80 kg 入库');
    expect(source).not.toContain('<template v-if="isPackagingMaterial">\n          <el-divider>\n            <span class="divider-title">包装层级');
  });

  it('uses the same dynamic direct-to-base rule model as SKU creation', () => {
    expect(source).toContain('v-for="(rule, index) in packagingRules"');
    expect(source).toContain('添加多包装换算规则');
    expect(source).toContain('每一条都是不同采购包装对基本单位的换算规则');
    expect(source).toContain('packagingSpecs: submittedPackagingRules');
    expect(source).not.toContain('label="二级换算"');
    expect(source).not.toContain('label="三级换算"');
  });

  it('limits packaging-unit choices to the shared purchase quantity contract', () => {
    expect(source.match(/usage-scope="PURCHASE_QUANTITY"/g)?.length).toBeGreaterThanOrEqual(2);
  });
});
