import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'), 'utf8',
);

/**
 * 盘点页「副产价值确认」区的承载点。
 *
 * 展示语义(null vs 0)已由 byproductCredit.spec.ts 用真单测钉住; 这里盯的是接线 ——
 * 「加了显示的一半, 没加承载它的另一半」是本仓最高频 bug 形状。
 */
describe('盘点页: 副产价值确认区', () => {
  it('区块存在, 且没有副产批次时整块不显示 (不给空表格)', () => {
    expect(source).toContain('data-testid="byproduct-credit-section"');
    expect(source).toMatch(/v-if="byproductCredits\.length > 0"/);
  });

  it('打开录入弹窗时会去加载副产批次 —— 少了这一步整块永远是空的', () => {
    expect(source).toContain('loadByproductCredits(String(row.id))');
    expect(source).toContain('/byproduct-credits`');
  });

  it('确认单价走后端写端点, 不在前端落库', () => {
    expect(source).toContain('/confirm-price`');
  });

  /**
   * 🔴 前端只做格式化, 不算钱。金额由后端 ByproductCreditService 算 ——
   * 前端再乘一遍就是本仓第六处「同一件事多套实现」, 且两边漂开时不报错。
   */
  it('抵扣额与单价一律走 byproductCredit.ts 的格式化函数', () => {
    expect(source).toContain("from './byproductCredit'");
    expect(source).toContain('formatCredit(row.credit)');
    expect(source).toContain('formatCreditUnitPrice(row.unitPrice)');
    expect(source).toContain('creditStatus(row.unitPrice, row.priceConfirmedAt)');
  });

  it('前端不自己做 数量×单价 的乘法', () => {
    const multiplications = source.match(/(stocktakeQuantity|reportedQuantity)\s*\*\s*\w*[Uu]nitPrice/g) ?? [];
    expect(multiplications, `前端不许重算抵扣额: ${multiplications.join(', ')}`).toHaveLength(0);
  });

  /** 未确认时输入框留空, 不拿 0 占位 —— 0 是「确认这批不值钱」这个真实结论。 */
  it('未确认的单价不预填 0', () => {
    expect(source).toContain('draftUnitPrice: row.unitPrice == null ? null : Number(row.unitPrice)');
    expect(source).toContain('请先填写单价；确认为 0 请显式填 0');
  });

  /** 单位一律经展示映射, 不裸露英文码 (与 unitDisplayContract 同一条规矩)。 */
  it('副产行的单位经 countDisplayUnit', () => {
    const section = source.slice(source.indexOf('byproduct-credit-section'));
    // (?![A-Za-z]) 不能省: 不加会把 row.unitPrice 也当成单位插值 —— 那是**价格**,
    // 走 formatCreditUnitPrice 而不是单位映射。同一个前缀陷阱本项目已踩过两次。
    const unitInterpolations = section.match(/\{\{[^}]*row\.unit(?![A-Za-z])[^}]*\}\}/g) ?? [];
    expect(unitInterpolations.length).toBeGreaterThan(0); // 阳性对照: 确实扫到了东西
    for (const chunk of unitInterpolations) {
      expect(chunk, `裸露单位插值: ${chunk}`).toMatch(/countDisplayUnit|displayUnit/);
    }
  });

  /** 物料档案被删时如实说, 不拿 batchId 冒充名称。 */
  it('物料名缺失时显示占位而非 ID', () => {
    expect(source).toContain("row.materialName || '（物料档案已删除）'");
  });
});
