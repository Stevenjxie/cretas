import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 物料行(原料/辅料/包材)不参与【成品】批次分配。
 *
 * <h2>2026-08-12 实测缺口</h2>
 * 销售订单可以卖物料之后, 仓管在「分配批次」对话框里点开物料行, 会落到成品的空态上:
 *
 *   「该产品当前全厂无可发货成品库存(已含全部可售仓库…), 请先完成生产入库」
 *
 * 那句话是**错的** —— 货就在物料仓里, 92 个批次(LIUSHANMEN 实测)。它把仓管指向
 * 「去生产」这个完全错误的下一步。后端已经豁免了发货前的硬闸(不豁免会把物料发货全拦死),
 * 所以跳过这个对话框直接确认发货是能走通的 —— 但没人会知道该跳过。
 *
 * <p>这正是防呆规则里最典型的坏法: **能走, 但提示把人指向了错误的方向**。
 *
 * <h2>三处必须一致</h2>
 * 少任何一处, 物料行都会以不同形态卡住:
 *   ① 不查成品批次 —— 查了必然空, 然后落到那条空态
 *   ② 提交校验跳过 —— 否则永远「分配合计 0 ≠ 发货量」, 而且同一张子发运单里
 *      只要有一行物料就会把**成品行也一起卡死**
 *   ③ 界面给物料专属说明 —— 而不是复用成品空态
 */
const source = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('物料行不参与成品批次分配', () => {
  it('① 物料行不去查成品批次(查了必然空, 然后误导仓管)', () => {
    expect(source).toContain('const isMaterial = materialIdSet.value.has(String(productTypeId));');
    expect(source, '成品批次查询必须被 !isMaterial 挡住')
      .toContain('if (!isMaterial && productTypeId && deliveredQuantity > 0)');
  });

  it('② 提交校验跳过物料行(否则一行物料卡死整张子发运单)', () => {
    expect(source).toMatch(/if \(item\.isMaterial\) \{\s*\n\s*continue;/);
  });

  it('③ 界面给物料专属说明, 不复用成品空态', () => {
    expect(source).toContain('物料无需分配批次');
    expect(source, '物料分支必须排在成品表格/空态之前')
      .toMatch(/v-if="item\.isMaterial"[\s\S]{0,400}v-else-if="item\.allocations\.length > 0"/);
  });

  it('物料集合在开对话框前就绪 —— 空集合会让所有行都被当成成品', () => {
    expect(source).toMatch(/if \(!materialIdSet\.value\.size\) \{\s*\n\s*await loadProductsForEdit\(\);/);
  });

  it('materialIdSet 由物料字典接口填充(与下拉同源, 不另起一份判定)', () => {
    expect(source).toContain('materialIdSet.value = new Set(mats.map((m: any) => String(m.id)));');
  });
});
