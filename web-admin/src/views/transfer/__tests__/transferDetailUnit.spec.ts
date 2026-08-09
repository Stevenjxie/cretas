import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { displayUnit } from '@/utils/unitPricing';

const detailSource = readFileSync(
  resolve(process.cwd(), 'src/views/transfer/detail.vue'),
  'utf8',
);

describe('M08 调拨详情单位显示契约', () => {
  it.each([
    ['box', '盒'],
    ['case', '箱'],
    ['slice', '片'],
    ['g', 'g'],
    ['kg', 'kg'],
  ])('只转换用户可见单位 %s -> %s', (canonical, expected) => {
    expect(displayUnit(canonical)).toBe(expected);
  });

  it('详情、操作确认和差异处理统一经过 displayUnit', () => {
    expect(detailSource).toContain("import { displayUnit } from '@/utils/unitPricing';");
    expect(detailSource).toContain('${displayUnit(it.unit)}');
    expect(detailSource).toContain('{{ displayUnit(row.unit) }}');
    expect(detailSource).toContain('{{ displayUnit(decidingDiff.unit) }}');
    expect(detailSource).not.toContain('prop="unit" label="单位"');
    expect(detailSource).not.toContain('{{ row.shippedQuantity }} {{ row.unit }}');
    expect(detailSource).not.toContain('{{ row.receivedQuantity }} {{ row.unit }}');
    expect(detailSource).not.toContain('{{ decidingDiff.shippedQuantity }} {{ decidingDiff.unit }}');
    expect(detailSource).not.toContain('{{ decidingDiff.receivedQuantity }} {{ decidingDiff.unit }}');
  });

  /**
   * 客户 2026-08-09 反馈: 调拨明细里「调拨数量 1000 / 现有库存 1,000 / 已收数量 0」全是裸数字,
   * 单位孤零零挂在三列之外的「单位」列 —— 要横跨半个表格才知道是 kg 还是箱。
   * 新建表单一直是「300 kg」这样自带单位的 (list.vue), 详情页反而不是, 两处口径不一致。
   */
  it('调拨明细的每个数量都自带单位, 不再有只用来配单位的独立列', () => {
    const table = detailSource.slice(
      detailSource.indexOf('<h3 style="margin: 20px 0 12px">调拨明细</h3>'),
      detailSource.indexOf('<!-- B1: SHIP 前批次选择'),
    );
    expect(table.length).toBeGreaterThan(200); // 锚点没找到就不是在断言真东西

    // 三个数量列各自带单位后缀 (用正则容忍缩进/换行, 断言的是"紧跟其后", 不是排版)
    const unitSuffix = String.raw`\s*<span class="unit-suffix">\{\{ displayUnit\(row\.unit\) \}\}</span>`;
    expect(table).toMatch(new RegExp(String.raw`<span v-else>\{\{ row\.quantity \}\}</span>` + unitSuffix));
    expect(table).toMatch(new RegExp(String.raw`\{\{ formatStock\(row\.currentStock\) \}\}\s*</span>` + unitSuffix));
    expect(table).toMatch(new RegExp(String.raw`\{\{ row\.receivedQuantity \|\| 0 \}\}` + unitSuffix));

    // 那个只承载单位的独立列已删除 —— 留着就是同一个信息在一行里出现四次
    expect(table).not.toContain('label="单位"');
    expect(detailSource).toContain('.unit-suffix {');
  });
});
