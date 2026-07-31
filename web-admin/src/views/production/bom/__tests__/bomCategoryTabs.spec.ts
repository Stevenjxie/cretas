import { describe, expect, it } from 'vitest';
import {
  BOM_CATEGORY_TABS,
  bomTabAddButtonLabel,
  bomTabBigCategories,
  bomTabItemLabel,
  isBomCategoryTab,
  matchBomCategory,
  normalizeRecipeMaterialCategory,
  type BomCategoryTab,
} from '../bomCategoryTabs';

/**
 * BOM 配方内容的四个页签。第四类「副产」是**产出声明**, 与前三类(投入)语义相反。
 *
 * 这些函数原先散在 index.vue (153KB) 里, 加第四类时被提出来 —— 不是为了好看, 是因为
 * 「加了新的一类, 但承载它的某一处没跟上」是本仓最高频的 bug 形状 (2026-07-31 一天撞四次)。
 * 散在巨型 SFC 里的 if/else 链只能靠源码正则去断言, 而正则断言不了「漏了分支时会怎样」。
 */
describe('BOM 配方内容页签', () => {
  it('四个页签, 副产排在最后', () => {
    expect(BOM_CATEGORY_TABS).toEqual(['RAW', 'AUXILIARY', 'PACKAGING', 'BYPRODUCT']);
  });

  it('isBomCategoryTab 认不出的一律 false (不猜)', () => {
    expect(isBomCategoryTab('BYPRODUCT')).toBe(true);
    expect(isBomCategoryTab('RAW')).toBe(true);
    expect(isBomCategoryTab('byproduct')).toBe(false);
    expect(isBomCategoryTab('')).toBe(false);
    expect(isBomCategoryTab(null)).toBe(false);
    expect(isBomCategoryTab(undefined)).toBe(false);
  });

  describe('normalizeRecipeMaterialCategory', () => {
    it('英文码与中文写法都要认出副产', () => {
      expect(normalizeRecipeMaterialCategory('BYPRODUCT')).toBe('BYPRODUCT');
      expect(normalizeRecipeMaterialCategory('byproduct')).toBe('BYPRODUCT');
      expect(normalizeRecipeMaterialCategory('副产')).toBe('BYPRODUCT');
    });

    it('既有三类的口径不能被第四类挤掉', () => {
      expect(normalizeRecipeMaterialCategory('PACKAGING')).toBe('PACKAGING');
      expect(normalizeRecipeMaterialCategory('包材')).toBe('PACKAGING');
      expect(normalizeRecipeMaterialCategory('AUXILIARY')).toBe('AUXILIARY');
      expect(normalizeRecipeMaterialCategory('辅料')).toBe('AUXILIARY');
      expect(normalizeRecipeMaterialCategory('调味料')).toBe('AUXILIARY');
      expect(normalizeRecipeMaterialCategory('RAW')).toBe('RAW');
    });

    it('未识别的兜底落 RAW —— 与既有行为一致, 物料不因筛选彻底消失', () => {
      expect(normalizeRecipeMaterialCategory('')).toBe('RAW');
      expect(normalizeRecipeMaterialCategory(null)).toBe('RAW');
      expect(normalizeRecipeMaterialCategory('不认识的类别')).toBe('RAW');
    });
  });

  describe('matchBomCategory —— 每个页签只能捞到属于自己的行', () => {
    const raw = { materialCategory: 'RAW' };
    const aux = { materialCategory: 'AUXILIARY' };
    const pkg = { materialCategory: 'PACKAGING' };
    const byp = { materialCategory: 'BYPRODUCT' };
    const legacyBlank = { materialCategory: '' };

    it('副产行只落副产页签', () => {
      expect(matchBomCategory(byp, 'BYPRODUCT')).toBe(true);
      expect(matchBomCategory(byp, 'RAW')).toBe(false);
      expect(matchBomCategory(byp, 'AUXILIARY')).toBe(false);
      expect(matchBomCategory(byp, 'PACKAGING')).toBe(false);
    });

    /**
     * 🔴 这条是本次最该有的回归网。原来的 currentTabItems 写法是
     *     if RAW → rawItems; if AUXILIARY → auxiliaryItems; return packagingItems
     * 也就是「除前两类之外一律当包材」。加第四个页签时若沿用这个兜底,
     * 副产页签会**若无其事地列出全部包材**, 不报错、不空白, 人眼很难发现。
     */
    it('前三类的行一条都不许漏进副产页签', () => {
      expect(matchBomCategory(raw, 'BYPRODUCT')).toBe(false);
      expect(matchBomCategory(aux, 'BYPRODUCT')).toBe(false);
      expect(matchBomCategory(pkg, 'BYPRODUCT')).toBe(false);
      expect(matchBomCategory(legacyBlank, 'BYPRODUCT')).toBe(false);
    });

    it('既有三类的归属不变 (含历史空类别仍算原料)', () => {
      expect(matchBomCategory(raw, 'RAW')).toBe(true);
      expect(matchBomCategory(legacyBlank, 'RAW')).toBe(true);
      expect(matchBomCategory({ materialCategory: '其他' }, 'RAW')).toBe(true);
      expect(matchBomCategory({ materialCategory: '调味料' }, 'AUXILIARY')).toBe(true);
      expect(matchBomCategory({ materialCategory: '包材' }, 'PACKAGING')).toBe(true);
    });

    it('中文「副产」写法也认 —— 与后端英文码并存时不至于两边对不上', () => {
      expect(matchBomCategory({ materialCategory: '副产' }, 'BYPRODUCT')).toBe(true);
    });

    it('每一行有且只有一个归属页签 (不重不漏)', () => {
      for (const row of [raw, aux, pkg, byp, legacyBlank]) {
        const hits = BOM_CATEGORY_TABS.filter((tab) => matchBomCategory(row, tab));
        expect(hits, `${row.materialCategory || '(空)'} 的归属页签`).toHaveLength(1);
      }
    });
  });

  describe('bomTabBigCategories —— 物料下拉按页签过滤', () => {
    it('副产页签只放行原料字典里显式打标 副产 的 SKU', () => {
      expect(bomTabBigCategories('BYPRODUCT')).toEqual(['副产']);
    });

    it('既有三档的放行集合不变', () => {
      expect(bomTabBigCategories('RAW')).toEqual(['原料']);
      expect(bomTabBigCategories('AUXILIARY')).toEqual(['辅料', '调料']);
      expect(bomTabBigCategories('PACKAGING')).toEqual(['包材']);
    });

    it('四个页签都必须有放行集合 —— 漏一个就是「选了永远筛出 0 条」', () => {
      for (const tab of BOM_CATEGORY_TABS) {
        expect(bomTabBigCategories(tab as BomCategoryTab).length,
          `${tab} 没有放行的大类`).toBeGreaterThan(0);
      }
    });
  });

  describe('文案', () => {
    it('按钮文案随页签切换', () => {
      expect(bomTabAddButtonLabel('BYPRODUCT')).toBe('添加副产');
      expect(bomTabAddButtonLabel('PACKAGING')).toBe('添加包材');
      expect(bomTabAddButtonLabel('RAW')).toBe('添加原料');
    });

    it('弹窗里的物料称呼随页签切换', () => {
      expect(bomTabItemLabel('BYPRODUCT')).toBe('副产');
      expect(bomTabItemLabel('PACKAGING')).toBe('包材');
      expect(bomTabItemLabel('AUXILIARY')).toBe('工序辅料');
      expect(bomTabItemLabel('RAW')).toBe('原料');
    });

    it('四个页签的文案都不能落空 —— 空文案会显示成「添加」/「请选择」', () => {
      for (const tab of BOM_CATEGORY_TABS) {
        expect(bomTabAddButtonLabel(tab as BomCategoryTab)).not.toBe('');
        expect(bomTabItemLabel(tab as BomCategoryTab)).not.toBe('');
      }
    });
  });
});
