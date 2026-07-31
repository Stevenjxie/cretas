import { describe, expect, it } from 'vitest';
import { creditStatus, formatCredit, formatCreditUnitPrice } from '../byproductCredit';

/**
 * 副产抵扣的**展示**语义。
 *
 * 🔴 全部只做格式化, 不算钱 —— 金额由后端 ByproductCreditService.creditOf 算好返回
 * (Task 3)。本仓 2026-07-31 一天连修五处「同一件事多套实现」, 前端再算一遍就是第六处,
 * 而且两边一旦漂开, 用户看到的抵扣额和成本表里扣掉的会对不上。
 *
 * 🔴 核心是 null 与 0 必须分得开: null = 还没人确认过, 0 = 有人确认了「这批不值钱」。
 * 把 null 显示成 0.00 就是禁降级里说的臆造默认值 —— 它会让「漏确认」看起来像「已确认为 0」。
 */
describe('副产抵扣展示', () => {
  describe('formatCredit', () => {
    it('未确认显示「未抵扣」, 不显示 0', () => {
      expect(formatCredit(null)).toBe('未抵扣');
      expect(formatCredit(undefined)).toBe('未抵扣');
    });

    it('确认为 0 显示 0.00 —— 与「未抵扣」是两回事', () => {
      expect(formatCredit(0)).toBe('0.00');
    });

    it('正常金额两位小数', () => {
      expect(formatCredit(12)).toBe('12.00');
      expect(formatCredit(12.345)).toBe('12.35');
    });

    /** 禁降级: 拿不准的输入不许静默变成 0, 否则等于凭空造出一笔抵扣。 */
    it('非有限数不臆造 0, 一律按未抵扣处理', () => {
      expect(formatCredit(Number.NaN)).toBe('未抵扣');
      expect(formatCredit(Number.POSITIVE_INFINITY)).toBe('未抵扣');
    });
  });

  describe('creditStatus', () => {
    it('有价且有确认时间才算已确认', () => {
      expect(creditStatus(4, '2026-07-31T10:00:00')).toBe('CONFIRMED');
    });

    it('都没有 = 待确认', () => {
      expect(creditStatus(null, null)).toBe('PENDING');
    });

    /**
     * 🔴 有价但没有确认时间 —— 那是 BOM/SKU 带过来的**参考价**, 还没有人拍板。
     * 判成 CONFIRMED 会让「系统猜的价」冒充「人确认的价」, 直接影响抵扣后成本。
     */
    it('有价但没确认时间 = 参考价, 不算确认', () => {
      expect(creditStatus(4, null)).toBe('PENDING');
    });

    /** 确认为 0 是真实的确认结果, 不能因为 0 是 falsy 就被当成没填。 */
    it('确认为 0 仍是已确认', () => {
      expect(creditStatus(0, '2026-07-31T10:00:00')).toBe('CONFIRMED');
    });

    it('有确认时间但没有价 = 数据不完整, 保守判待确认', () => {
      expect(creditStatus(null, '2026-07-31T10:00:00')).toBe('PENDING');
    });
  });

  describe('formatCreditUnitPrice', () => {
    it('未确认单价如实说未确认, 不显示 0.00', () => {
      expect(formatCreditUnitPrice(null)).toBe('未确认');
    });

    it('确认为 0 显示 0.0000 (单价保留 4 位, 与本仓单价口径一致)', () => {
      expect(formatCreditUnitPrice(0)).toBe('0.0000');
      expect(formatCreditUnitPrice(8)).toBe('8.0000');
    });
  });
});
