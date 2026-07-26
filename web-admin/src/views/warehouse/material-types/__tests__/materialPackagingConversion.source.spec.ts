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

  it('loads, validates and persists hierarchy without a packaging-category gate', () => {
    expect(source).toContain('原料、辅料和包材共用计量/包装层级');
    expect(source).toContain('if (hasL2Unit !== hasL2Qty)');
    expect(source).toContain('if (hasL2Unit || hasL3Unit)');
    expect(source).not.toContain('if (isPackagingMaterial.value && hasL2Unit !== hasL2Qty)');
    expect(source).not.toContain('if (isPackagingMaterial.value && (hasL2Unit || hasL3Unit))');
  });

  it('limits packaging-unit choices to the shared purchase quantity contract', () => {
    expect(source.match(/usage-scope="PURCHASE_QUANTITY"/g)?.length).toBeGreaterThanOrEqual(3);
  });
});
