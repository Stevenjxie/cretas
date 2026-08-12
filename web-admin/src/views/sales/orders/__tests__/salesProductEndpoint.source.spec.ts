import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 销售订单的商品下拉必须走 /product-types/sellable, 不能走 /product-types/active。
 *
 * 2026-08-12 六膳门张权真机反馈:「老问题 销售订单 选择不了原料」。
 * 追下去发现销售侧和生产侧共用了 /active, 而那条口径对销售来说**两个方向都是反的**:
 *
 *   /active(生产侧, 11 个前端调用点在用): 排除原料 ✗  保留半成品 ✗
 *   销售侧要的(Steve 拍板「出了半成品全开」): 要原料      不要半成品
 *
 * 生产侧的口径本身没错 —— 生产计划就是要生产半成品、不会去「生产」一包真空袋。
 * 所以正解是给销售一条自己的端点, **不动** /active(动它会波及生产计划/批次/工时/
 * 毛利红线/成本差异/餐饮)。
 *
 * 这条闸守两件事:
 *   ① 别有人图省事把销售改回 /active(那会让半成品重新出现在销售单里)
 *   ② 列表页与详情页必须用同一个端点 —— 列表能选到而详情编辑时选不到,
 *      是最难查的那种不一致
 */
const FILES = ['list.vue', 'detail.vue'] as const;

describe('sales order product dropdown endpoint', () => {
  for (const file of FILES) {
    const source = readFileSync(resolve(__dirname, '..', file), 'utf8');

    it(`${file} 用 /product-types/sellable 拉商品下拉`, () => {
      expect(source, `${file} 应当调用 sellable 端点`)
        .toContain('/product-types/sellable');
    });

    it(`${file} 不再用生产侧的 /product-types/active 拉商品下拉`, () => {
      expect(source, `${file} 不该再调用 /product-types/active —— 那条口径保留半成品、排除原料`)
        .not.toContain('/product-types/active');
    });
  }


  // 2026-08-12 第二半: 物料(原料/辅料/包材)也要出现在销售下拉里。
  // 张权要卖的东西在物料字典, 不在商品目录 —— 只切 /sellable 解决不了他的问题。
  // 发货时物料行从 material_batches 扣(SalesMaterialLineShipmentTest 守那一段)。
  for (const file of FILES) {
    const source = readFileSync(resolve(__dirname, '..', file), 'utf8');

    it(`${file} 把物料字典也并进商品下拉`, () => {
      expect(source, `${file} 应当同时拉 /raw-material-types/active`)
        .toContain('/raw-material-types/active');
    });
  }

  it('两个页面用的是同一个端点(不允许一个 sellable 一个 active)', () => {
    const endpoints = FILES.map((file) => {
      const source = readFileSync(resolve(__dirname, '..', file), 'utf8');
      return /\/product-types\/(sellable|active)/.exec(source)?.[1];
    });
    expect(new Set(endpoints).size, `实得: ${endpoints.join(' vs ')}`).toBe(1);
  });
});
