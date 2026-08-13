import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * `BomSeasoningIntegration.source.spec.ts` 的继承者。
 *
 * ## 为什么原文件没了
 * 2026-08-07 阶段 5(方案 B「画布即 BOM」)删掉了 `views/production/bom/index.vue` 与
 * `views/production/bom-unified/index.vue`。原 spec 十几条断言里绝大多数钉的就是这两个
 * 文件的模板与函数体 —— 载体删了, 那些断言没有主语, 只能一起走。
 *
 * ## 为什么不能整个删掉
 * 原 spec 是**混装**的: 它同时钉了三组「跟 BOM 页无关、载体现在还活着」的契约。
 * 跟着页面一起删 = 静默丢闸, 而且丢得毫无痕迹 —— 下次有人改物料档案的计税字段,
 * 不会有任何东西变红。所以这三组原样搬过来, 断言内容一个字不改。
 *
 * 已在别处覆盖、这里不重复的: 画布不再跳 BOM 页那一组, 见
 * `legacyBomEntryRetired.source.spec.ts` 与 `bomMenuRetired.source.spec.ts`。
 */
const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');

const materialTypeSource = readSource('src/views/warehouse/material-types/list.vue');
const routerSource = readSource('src/router/index.ts');
const bomCostDtoSource = readSource(
  '../backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomCostSummaryDTO.java',
);

describe('BOM 页删除后仍然成立的契约(载体不是那个页面)', () => {
  /**
   * 原属 'uses category-specific material pickers and material-master pricing' —— 该条前半段
   * 钉 BOM 页的物料选择器(已随页面走), 后半段钉的是**物料档案表单**, 与 BOM 页无关:
   * 计税方式必填、应税才出税率、免税/含税参考价的措辞要跟着 taxTreatment 变、
   * 参考价填了就必须 > 0(未知价格要留空, 不许垫 0 —— 垫 0 会让成本算出假答案)。
   */
  it('物料档案的计税与参考价契约(原 BOM spec 后半段)', () => {
    expect(materialTypeSource).toContain('<el-form-item label="计税方式" required>');
    expect(materialTypeSource).toContain(
      "v-if=\"form.taxTreatment === 'TAXABLE'\" label=\"采购税率\" required",
    );
    expect(materialTypeSource).toContain(
      "form.taxTreatment === 'EXEMPT' ? `免税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）` : `含税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`",
    );
    // 2026-08-13: 文案按类别分了岔 —— 包材的参考价在后端是**必填**
    // (create/update 两条路径都有 `if (packaging) validateRequiredPricing`),
    // 界面原本一律说「选填；未知价格请留空」, 照做就存不进去(24/25 个包材因此改不动)。
    // 本用例守的意图是「填了就必须 > 0」, 那条对非包材仍然逐字成立;
    // 包材必填由 packagingPriceRequired.source.spec.ts 守。
    expect(materialTypeSource).toContain(
      "'采购参考价如填写，必须大于 0；未知价格请留空'",
    );
    expect(materialTypeSource, '包材分支不能把这条正数校验也一起丢掉')
      .toContain("'采购参考价必须大于 0'");
  });

  /**
   * 原属 'keeps the canonical BOM auxiliary entry without restoring the removed product recipe route'。
   * 前半段钉 bom-unified 的 tab(已随页面走), 后半段是一条**反向**契约:
   * 「产品配方」路由早先已被移除, 不许有人再把它加回来 —— 这跟 BOM 页在不在没关系,
   * 反而在页面删干净之后更需要, 否则很容易被当成"缺个 BOM 入口"而复活。
   */
  it('已移除的产品配方路由不许复活', () => {
    expect(routerSource).not.toContain("path: 'product-recipes'");
    expect(routerSource).not.toContain("name: 'ProductRecipes'");
    expect(routerSource).not.toContain("import('@/views/production/ProductRecipeView.vue')");
  });

  /**
   * 原属 'labels cost bases explicitly and formats numbers without meaningless trailing zeros'。
   * 前端那半段随页面走了; 这半段钉的是**后端 DTO 的字段仍然在**。
   * BOM canvas phase 1 把人工/制费移出 BOM(现在恒为 null), 但字段没删 —— 结算侧还在读。
   * 「值恒为 null」和「字段不存在」是两回事, 后者会让结算侧反序列化少一块。
   */
  it('BomCostSummaryDTO 仍带人工/制费字段(值恒 null, 但字段不许删)', () => {
    expect(bomCostDtoSource).toContain('private BigDecimal materialCostTotal;');
    expect(bomCostDtoSource).toContain('private BigDecimal laborCostTotal;');
    expect(bomCostDtoSource).toContain('private BigDecimal overheadCostTotal;');
    expect(bomCostDtoSource).toContain('private String costUnit;');
  });
});
