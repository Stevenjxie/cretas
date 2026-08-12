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

/**
 * 🔴 2026-08-12 真机 E2E 抓到的阻断缺陷。
 *
 * 选中物料的那一刻, 订单行拿【物料的 id】去查【成品的箱规接口】:
 *   GET /product-types/RMT_1781657771241/packaging-specs → 404
 * → 弹「包装规格加载失败, 请重试后再创建订单」, 而 packagingLoadError 正是创建按钮的
 *   拦截条件 —— 于是【选了物料就建不了单】, 整个功能形同没做。
 *
 * ⚠️ 判据教训: 之前的闸只验到「选项出现在下拉里」, 没有一条走到「选中之后会发生什么」。
 * 「能选」≠「能下单」—— 与「量了引用面不是执行路径」是同一个毛病。
 */
describe('物料行不查成品箱规', () => {
  const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

  it('loadPackagingSpecs 对物料行提前返回, 不发成品箱规请求', () => {
    expect(listSource).toMatch(/if \(materialIdSet\.value\.has\(String\(productId\)\)\) \{[\s\S]{0,200}?return;/);
  });

  it('提前返回必须排在 packaging-specs 请求之前(否则请求照发)', () => {
    const guardAt = listSource.indexOf('materialIdSet.value.has(String(productId))');
    // ⚠️ 不能用 indexOf('/packaging-specs') —— 它会命中守卫上方【注释里】那一行,
    //    测出来永远是「守卫在请求之后」的假红。要锚在真正发请求的那句模板串上。
    const requestAt = listSource.indexOf('${productId}/packaging-specs');
    expect(guardAt).toBeGreaterThan(-1);
    expect(requestAt).toBeGreaterThan(-1);
    expect(guardAt, '守卫必须在请求之前').toBeLessThan(requestAt);
  });

  it('物料 id 集合与下拉同源, 不另起第二份判定', () => {
    expect(listSource).toContain('new Set(materialOptions.value.map((m) => String(m.id)))');
  });
});
