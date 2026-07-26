import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const pageSource = readFileSync(
  resolve(process.cwd(), 'src/views/inventory/by-warehouse/index.vue'),
  'utf8',
);

describe('UX-INVENTORY-MATERIAL-CODE-COLUMN-20260726 分仓库存物料编码列', () => {
  it('在原料库存表显示独立物料编码列并为缺失值提供占位符', () => {
    expect(pageSource).toContain('materialCode?: string;');
    expect(pageSource).toContain(
      '<el-table-column prop="materialCode" label="物料编码" width="150" show-overflow-tooltip>',
    );
    expect(pageSource).toContain("{{ row.materialCode || '-' }}");
  });

  it('支持按物料编码搜索并在搜索提示中说明', () => {
    expect(pageSource).toContain("const code = (r.materialCode || '').toLowerCase();");
    expect(pageSource).toContain(
      'return name.includes(kw) || code.includes(kw) || bn.includes(kw);',
    );
    expect(pageSource).toContain('placeholder="批次号 / 物料编码 / 物料名称"');
  });
});
