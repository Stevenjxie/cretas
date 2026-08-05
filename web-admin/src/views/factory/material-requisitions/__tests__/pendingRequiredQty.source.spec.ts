import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/**
 * 客户 2026-08-03 (Sheet 第 45 行):「没有途径可以配置配方用量」。
 *
 * 后端 generateFromPlan 原本对「BOM 只登记配方资格、没有参考用量」的行抛 409, 提示去
 * BOM 填标准用量 —— 而 BOM 编辑器对 RAW/AUXILIARY 强制 standardQuantity=null、界面上
 * 根本没有那个输入框。现在后端改为留 requiredQty=null 照常出单, 前端必须把 null 和 0
 * 区分开: toNumber(null) 是 0, 一路走下去会印成「0」, 车间照单领 0 就是短料。
 *
 * 断言都落在「null 不会变成 0」这一条不变量上, 不是落在具体文案。
 */
describe('物料需求单「用量待定」行不能退化成 0 (Sheet 第 45 行)', () => {
  it('需求量展示统一走 requiredQtyLabel, 不再直接 qty(requiredQty) / prop 裸绑', () => {
    // 两处展示 (明细表 + 确认领料弹窗) 都必须换成 label
    expect(source.match(/requiredQtyLabel\(row\)/g)).toHaveLength(2);
    expect(source).not.toContain('{{ qty(row.requiredQty) }}');
    expect(source).not.toContain('<el-table-column prop="requiredQty"');
  });

  it('isPendingQty 把 null/undefined/空串 与 0 区分开 —— 0 是真的要 0, null 是还不知道', () => {
    expect(source).toContain('function isPendingQty');
    // 判据必须是「值是不是缺失」, 不能写成 !item.requiredQty (那样 0 也会被当成待定)
    expect(source).toMatch(/requiredQty === null[\s\S]{0,120}requiredQty === undefined/);
    expect(source).not.toContain('!item.requiredQty');
  });

  it('待定行不预填拣货数量 —— 预填 0 会让人一路点确认, 那味料一点没调过去', () => {
    expect(source).toContain('isPendingQty(item) ? null : toNumber(item.requiredQty)');
  });

  it('待定行留空不许提交, 且要指名是哪几味料', () => {
    expect(source).toContain('missingPending');
    expect(source).toContain('isPendingQty(row) && !(Number(row.pickedInput) > 0)');
    // 错误提示按 fool-proof「4 位一体」: sticky + 可关 + 指名 + 说清下一步
    expect(source).toMatch(/missingPending\.map\(\(row\) => row\.materialName\)/);
    expect(source).toMatch(/missingPending[\s\S]{0,400}duration: 0/);
  });
});
