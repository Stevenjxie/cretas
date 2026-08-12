import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 2026-08-13 真机 E2E 收尾轮抓到的两个「物料行落在只认成品的从属数据上」。
 * 与 materialLineAllocation.source.spec.ts 是同一族 —— 那边守发货/箱规,
 * 这边守 AI 录入与批次分配汇总。
 */
const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

/** 剥掉注释再断言 —— 注释里会引用被修掉的旧写法, 不剥会自己命中自己。 */
function stripComments(src: string): string {
  return src.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\/[^\n]*/g, '');
}

/** 截取一个具名函数的函数体(到下一个顶层 `\n}` 为止)。 */
function functionBody(src: string, signature: string): string {
  const start = src.indexOf(signature);
  expect(start, `找不到 ${signature} —— 结构变了, 这条闸需要重写`).toBeGreaterThan(-1);
  const end = src.indexOf('\n}', start);
  expect(end).toBeGreaterThan(start);
  return src.slice(start, end);
}

/**
 * 🔴 AI 录入解析对了, 点「填入表单」却什么也没发生。
 *
 * <p>`handleAiFill` 拿商品名去 `products`(**只有成品**)里找, 物料一律 NOT_FOUND →
 * throw → return, `dialogVisible` 永远到不了。而抽屉是**先** emit 关闭自己、
 * **再** emit fill-form 的, 所以现场看到的是「面板关了, 弹窗没开」,
 * 那句 warning 被关闭动画盖掉了 —— 没有任何线索指向真正的原因。
 *
 * <p>判据: **下拉能选到的东西, AI 填表必须认得**。两套口径只要不同源就一定漂。
 */
describe('AI 录入 · 填入表单认得物料', () => {
  const body = functionBody(stripComments(listSource), 'function handleAiFill(');

  it('① 商品名解析走 sellableOptions(成品 + 物料), 不是只认成品的 products', () => {
    expect(body, 'resolveReferenceByName 必须查 sellableOptions')
      .toContain('resolveReferenceByName(prodName, sellableOptions.value)');
    expect(body, '不能再退回只认成品的 products.value')
      .not.toContain('resolveReferenceByName(prodName, products.value)');
  });

  it('② 与下拉同源 —— sellableOptions 本身就是 products + materialOptions', () => {
    expect(listSource).toContain('[...products.value, ...materialOptions.value]');
  });

  it('③ 填进表单的单位保住计件单位(只/个)', () => {
    expect(body).toContain('canonicalUnitCodeKeepingCount(item.unit');
  });
});

/**
 * 🔴 `canonicalUnitCode` 把 只/个 都并成 `pcs`(显示回「件」)。
 * PR #2554 只改了 `onProductSelect` 一处, 同形状的兄弟call site 全留在原地 ——
 * 典型的「半截迁移」。这条闸按**用途**分类, 而不是一刀切禁用:
 *
 *   · 把单位**写进表单模型** → 必须 KeepingCount(否则 只/个 被就地改写)
 *   · 拿单位**做比较/算箱规** → 就该用普通版把同义单位折叠掉
 *
 * 其中最危险的是 `handleEdit`: 用户只是点开已有订单看一眼再保存,
 * 库里存的「只」就被换成「件」—— 静默改写已落库的数据。
 */
describe('单位规范化 · 计件单位不能被并掉', () => {
  const src = stripComments(listSource);

  it('写入表单模型的四个位置全部用 KeepingCount', () => {
    const writeSites = [
      'row.unit = canonicalUnitCodeKeepingCount(material?.unit',          // onSuppliedMaterialSelect
      'unit: canonicalUnitCodeKeepingCount(material.unit',                // handleEdit 客供料
      "unit: canonicalUnitCodeKeepingCount(item.unit || '份')",            // handleEdit 订单明细
      'unit: canonicalUnitCodeKeepingCount(item.unit',                    // handleAiFill
    ];
    for (const site of writeSites) {
      expect(src, `${site} 应当用 KeepingCount 版本`).toContain(site);
    }
  });

  it('比较/箱规口径仍用普通版 —— 不许一刀切全替换掉', () => {
    expect(src, 'onProductSelect 之后的箱规比较依赖同义单位折叠')
      .toMatch(/const pu = canonicalUnitCode\(p\.unit/);
  });

  /** 数量断言: 只断言「存在」抓不到「漏了一处」, 与半截修复同一个教训。 */
  it('写入型调用点数量固定为 4 —— 新增一处就要显式决定用哪个版本', () => {
    const keeping = src.match(/canonicalUnitCodeKeepingCount\(/g) || [];
    expect(keeping.length, '4 处写入 + onProductSelect 那处 = 5').toBe(5);
  });
});

/**
 * 🔴 「已分配 0/0.7 kg」——整单都是物料时, 这个读数永远停在 0。
 *
 * <p>物料行由后端在「确认发货」时按 FIFO 自动扣减, **从不产生批次分配记录**,
 * 所以把它算进 planned 就必然凑不满。仓管看到的是「还欠一步」, 实际上无事可做 ——
 * 与分配对话框里那条「物料无需分配批次」自相矛盾。
 *
 * <p>另一半是单位: 原来固定取 `items[0].unit` 去标那个合计, 于是
 * 「2只 + 1个」被汇总成一句 **「3 只」** —— 把两种单位的数加起来, 再随便挑一个标上。
 */
describe('批次分配汇总 · 物料行与混单位', () => {
  const src = stripComments(detailSource);

  it('① 汇总前先确保 materialIdSet 就绪(不依赖 onMounted 的调用顺序)', () => {
    const body = functionBody(src, 'async function loadAllocationSummaries(');
    expect(body).toContain('if (!materialIdSet.value.size) await loadProductsForEdit();');
  });

  it('② 物料行不计入 planned —— 计进去就永远凑不满', () => {
    const body = functionBody(src, 'async function loadAllocationSummaries(');
    expect(body).toMatch(/if \(materialIdSet\.value\.has\(productTypeId\)\) \{[\s\S]{0,120}?continue;/);
  });

  it('③ 整单物料 → materialOnly, 且算作 complete(否则按钮永远催你去分配)', () => {
    const body = functionBody(src, 'async function loadAllocationSummaries(');
    expect(body).toContain('materialOnly: goodsLines === 0 && materialLines > 0');
    expect(body).toMatch(/complete: goodsLines === 0/);
  });

  it('④ 界面对整单物料给专属标识, 不再显示 已分配 0/N', () => {
    expect(detailSource).toContain('物料 · 自动扣减');
    expect(detailSource, 'materialOnly 分支必须排在「已分配 X/Y」之前')
      .toMatch(/materialOnly[\s\S]{0,600}已分配 \{\{/);
  });

  it('⑤ 单位只在成品行单位一致时才标 —— 混单位不许拿第一行的单位标合计', () => {
    const body = functionBody(src, 'async function loadAllocationSummaries(');
    expect(body).toContain("unit: units.size === 1 ? [...units][0] : ''");
    expect(stripComments(detailSource), '模板不能再回到 items[0].unit')
      .not.toMatch(/已分配[\s\S]{0,200}displayUnit\(\(row\.items \|\| \[\]\)\[0\]\?\.unit\)/);
  });
});
