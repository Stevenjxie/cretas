import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// 与同目录既有 *.source.spec.ts 一致用 process.cwd() 定位 (vitest 的 root 就是 web-admin/)
const source = readFileSync(resolve(process.cwd(), 'src/views/production/bom/index.vue'), 'utf8');

/**
 * 「配方内容」第四类「副产」在 index.vue 里的**承载点**。
 *
 * 纯逻辑(归属/放行/文案)已经在 bomCategoryTabs.spec.ts 用真单测钉住了; 这里只盯模板侧
 * ——「加了显示的一半, 没加承载它的另一半」是本仓最高频 bug 形状 (2026-07-31 一天撞四次:
 * 加了 <td> 没加 <th>、grid 还是 3 列、加了大类选项但 bigCategoryOf 没分支)。
 */
describe('BOM 配方内容: 副产第四类的承载点', () => {
  it('页签本身存在, 且标成可选', () => {
    expect(source).toMatch(/<el-tab-pane\s+name="BYPRODUCT"/);
    expect(source).toContain('副产（${byproductItems.length}）· 可选');
  });

  it('页签有对应的数据集合 —— 少了它页签会空着或串到别的类', () => {
    expect(source).toContain('const byproductItems = computed(');
    expect(source).toContain("matchBomCategory(i, 'BYPRODUCT')");
  });

  /**
   * 🔴 原写法是「除 RAW/AUXILIARY 之外一律 packagingItems」。直接加第四个页签而不补分支,
   * 副产页签会**若无其事地列出全部包材** —— 不报错、不空白, 只有人眼盯着才看得出来。
   */
  it('currentTabItems 显式给了副产分支, 没有沿用兜底', () => {
    const branch = source.match(/const currentTabItems = computed\(\(\) => \{[\s\S]*?\n\}\);/);
    expect(branch, '找不到 currentTabItems').not.toBeNull();
    expect(branch![0]).toContain("activeCategoryTab.value === 'BYPRODUCT'");
    expect(branch![0]).toContain('byproductItems.value');
  });

  it('按钮文案随页签切换 (走唯一入口, 不在模板里再写一份 ternary)', () => {
    expect(source).toContain('bomTabAddButtonLabel(activeCategoryTab)');
  });

  it('生效条件面板写明副产可选', () => {
    expect(source).toContain('副产 可选');
  });

  it('副产行有「预计产出」列, 且未填时如实说未填 (禁降级, 不显示 0)', () => {
    expect(source).toMatch(/v-if="activeCategoryTab === 'BYPRODUCT'"[\s\S]{0,80}label="预计产出"/);
    expect(source).toContain('未填预计产出');
  });

  /**
   * 副产不进成本池 (后端 recomputeFamilyCosts 显式跳过)。前端若还显示「自动单价 / 小计 /
   * 原料成本合计」, 会让人以为它计入了成本 —— 那正是这个设计要避免的误读。
   */
  it('副产页签不展示单价/小计/成本合计', () => {
    expect(source).toContain("canViewPrice && activeCategoryTab !== 'BYPRODUCT'");
    expect(source).toContain(
      "canViewPrice && activeCategoryTab !== 'AUXILIARY' && activeCategoryTab !== 'BYPRODUCT'",
    );
  });

  it('副产没有替代物料 (列与表单项都不给)', () => {
    expect(source).toMatch(/v-if="activeCategoryTab !== 'BYPRODUCT'"[\s\S]{0,60}label="替代物料"/);
    expect(source).toMatch(/v-if="bomForm\.materialCategory !== 'BYPRODUCT'"[\s\S]{0,40}label="替代物料（可选）"/);
  });

  it('副产走预计产出量而不是包材用量, 且报错文案分得开', () => {
    expect(source).toContain('请填写大于 0 的预计产出量');
    expect(source).toContain('请填写大于 0 的每份成品包材用量');
  });

  /**
   * 与 utils/__tests__/unitDisplayContract.spec.ts 同一条规矩: 单位一律经展示映射,
   * 不得把 kg / box / pcs 这种英文码直接示人 (2026-07-31 PR #2080 修的就是这个)。
   */
  it('副产相关的单位插值都经过 displayUnit', () => {
    // (?![A-Za-z]) 是必要的: 不加会把 row.unitPrice 也当成单位插值扫进来 —— 那是**价格**,
    // 走 formatPriceUnit 而不是 displayUnit, 误判会逼着人去"修"一个本来就对的地方。
    const byproductUnitInterpolations = source.match(/\{\{[^}]*row\.unit(?![A-Za-z])[^}]*\}\}/g) ?? [];
    expect(byproductUnitInterpolations.length).toBeGreaterThan(0); // 阳性对照: 确实扫到了东西
    for (const chunk of byproductUnitInterpolations) {
      expect(chunk, `裸露单位插值: ${chunk}`).toMatch(/displayUnit|displayProcessUnit/);
    }
  });
});
