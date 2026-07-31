import { describe, expect, it } from 'vitest';
import { bigCategoryOf, BIG_CATEGORY_OPTIONS, filterOptionsByBigCategory } from '../materialCategory';
import { isByproductMaterial, splitByproductMaterials } from '../byproductMaterial';

/**
 * 副产 = 物料上的**标记**，与 category 正交。
 *
 * <p>🔴 这条契约取代了 2026-07-31 上午的 `category='副产'` 做法。走前端验收时它被两件事推翻：</p>
 * 1. **建不出来** —— 原料字典「类别」下拉取的是物料分段字典的 L1 类族(prod 只有 原料/包材/辅料)，
 *    没有「副产」，所以副产 SKU 一个都建不出来，BOM 第四类的下拉永远空。
 * 2. 🔴 **堵死设计自己的目标** —— 副产放原料字典是为了「以后能当原料被别的 workflow 投入」，
 *    但 BOM 原料页签放行 ['原料']，category='副产' 的 SKU 在那里永远选不到。
 *
 * 用标记则同一个 SKU 既是副产(排除采购/进第四类)，又仍是原料(能被投料选到)。
 */
describe('副产标记与 category 正交', () => {
  const feiyou = { id: 'MT-1', name: '肥油', category: '原料', isByproduct: true };
  const zhurou = { id: 'MT-2', name: '冻猪蹄', category: '原料', isByproduct: false };
  const box = { id: 'MT-3', name: '成品盒', category: '包材', isByproduct: false };

  describe('isByproductMaterial', () => {
    it('只认标记, 不看 category', () => {
      expect(isByproductMaterial(feiyou)).toBe(true);
      expect(isByproductMaterial(zhurou)).toBe(false);
    });

    it('认不出的一律 false (不猜)', () => {
      expect(isByproductMaterial(null)).toBe(false);
      expect(isByproductMaterial(undefined)).toBe(false);
      expect(isByproductMaterial({})).toBe(false);
      expect(isByproductMaterial({ isByproduct: null })).toBe(false);
    });

    /**
     * 后端 JSON 布尔可能以字符串到达(历史接口有过)。'true' 认，'false' 不认 ——
     * 但**不接受**任意真值(比如 1/'yes')，宁可漏认也不能把普通原料误判成副产：
     * 误判会把一个真的采购原料从采购下拉里藏掉。
     */
    it('字符串布尔按字面认, 其余一律 false', () => {
      expect(isByproductMaterial({ isByproduct: 'true' })).toBe(true);
      expect(isByproductMaterial({ isByproduct: 'false' })).toBe(false);
      expect(isByproductMaterial({ isByproduct: 1 })).toBe(false);
      expect(isByproductMaterial({ isByproduct: 'yes' })).toBe(false);
    });
  });

  /**
   * 🔴 本次改造的核心收益：副产的材质分类不再被「副产」这个桶吃掉。
   * 肥油 category='原料' → 它在原料桶里 → BOM 原料页签能选到它 → 能当投入。
   */
  it('副产 SKU 仍按材质归类, 因此能被当原料投入', () => {
    expect(bigCategoryOf(feiyou.category)).toBe('原料');
    expect(filterOptionsByBigCategory([feiyou, zhurou, box], '原料'))
      .toEqual([feiyou, zhurou]);
  });

  /** 「副产」不再是一个 BigCategory —— 它是标记, 不该出现在大类选择器里。 */
  it('大类选择器不再有「副产」这一档', () => {
    expect(BIG_CATEGORY_OPTIONS.map((o) => o.value)).not.toContain('副产');
    expect(bigCategoryOf('副产')).not.toBe('副产');
  });

  describe('splitByproductMaterials —— 采购侧要把副产分出去', () => {
    it('副产与可采购物料分开', () => {
      const { byproducts, purchasable } = splitByproductMaterials([feiyou, zhurou, box]);
      expect(byproducts).toEqual([feiyou]);
      expect(purchasable).toEqual([zhurou, box]);
    });

    it('空输入返回两个空数组而不是 null', () => {
      expect(splitByproductMaterials([])).toEqual({ byproducts: [], purchasable: [] });
      expect(splitByproductMaterials(null)).toEqual({ byproducts: [], purchasable: [] });
    });

    it('不重不漏: 每个物料恰好落在一边', () => {
      const all = [feiyou, zhurou, box];
      const { byproducts, purchasable } = splitByproductMaterials(all);
      expect(byproducts.length + purchasable.length).toBe(all.length);
      for (const m of all) {
        const inBoth = byproducts.includes(m) && purchasable.includes(m);
        expect(inBoth, `${m.name} 同时落在两边`).toBe(false);
      }
    });
  });
});
