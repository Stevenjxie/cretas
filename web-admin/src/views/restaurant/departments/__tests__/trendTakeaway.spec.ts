import { describe, expect, it } from 'vitest';
import { trendTakeaway } from '../trendTakeaway';
import { DEPARTMENTS, DEPARTMENT_ORDER } from '../departmentConfig';

const MONEY = { unit: '元', money: true };

describe('趋势图的一句话结论', () => {
  it('空数据说没有数据, 不编趋势', () => {
    expect(trendTakeaway([], MONEY)).toContain('没有数据');
  });

  it('只有一天时明说不足以看趋势', () => {
    const s = trendTakeaway([{ date: '2026-07-30', value: 100 }], MONEY);
    expect(s).toContain('只有 2026-07-30 一天');
    expect(s).toContain('不足以看趋势');
  });

  it('全零不说成"没有发生" —— 可能只是还没产生记录', () => {
    const s = trendTakeaway(
      ['a', 'b', 'c'].map((_, i) => ({ date: `2026-07-0${i + 1}`, value: 0 })),
      MONEY,
    );
    expect(s).toContain('均为 0');
    expect(s).toContain('尚未产生记录');
  });

  it('报出合计 / 日均 / 峰值日期, 且金额带 ¥', () => {
    const s = trendTakeaway([
      { date: '2026-07-01', value: 100 },
      { date: '2026-07-02', value: 300 },
      { date: '2026-07-03', value: 200 },
    ], MONEY);
    expect(s).toContain('¥600');
    expect(s).toContain('¥200');          // 日均
    expect(s).toContain('峰值在 2026-07-02');
    expect(s).toContain('¥300');
  });

  it('非金额单位不加 ¥', () => {
    const s = trendTakeaway([
      { date: '2026-07-01', value: 2 },
      { date: '2026-07-02', value: 4 },
    ], { unit: '次' });
    expect(s).not.toContain('¥');
    expect(s).toContain('次');
  });

  it('方向用首尾三分之一的均值判定, 单点噪声不该翻转结论', () => {
    // 明显上升的一串，但最后一天恰好掉下来 —— 结论仍应是走高
    const rising = [10, 12, 14, 40, 44, 48, 90, 95, 8].map((v, i) => ({
      date: `2026-07-${String(i + 1).padStart(2, '0')}`,
      value: v,
    }));
    expect(trendTakeaway(rising, { unit: '次' })).toContain('整体走高');
  });

  it('平稳数据判为基本持平', () => {
    const flat = Array.from({ length: 9 }, (_, i) => ({
      date: `2026-07-${String(i + 1).padStart(2, '0')}`,
      value: 100 + (i % 2),
    }));
    expect(trendTakeaway(flat, { unit: '次' })).toContain('基本持平');
  });

  it('忽略 NaN / 非数值点而不是让整句变成 NaN', () => {
    const s = trendTakeaway([
      { date: '2026-07-01', value: 100 },
      { date: '2026-07-02', value: Number.NaN },
      { date: '2026-07-03', value: 200 },
    ], MONEY);
    expect(s).not.toContain('NaN');
    expect(s).toContain('¥300');
  });

  it('结论里不出现因果措辞 —— 数据里没有原因', () => {
    const s = trendTakeaway([
      { date: '2026-07-01', value: 100 },
      { date: '2026-07-02', value: 300 },
    ], MONEY);
    for (const word of ['因为', '由于', '导致', '说明是']) {
      expect(s, `结论不该做因果推断: ${word}`).not.toContain(word);
    }
  });
});

describe('部门趋势配置', () => {
  it('有数据源的部门都配了趋势图, 人事没有', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      if (cfg.source) {
        expect(cfg.trend, `${key} 有数据源却没有趋势图`).toBeTruthy();
      } else {
        expect(cfg.trend, `${key} 无数据源不该有趋势图`).toBeUndefined();
      }
    }
  });

  it('趋势图必须声明标题与单位 —— 无单位的图表不可解读', () => {
    for (const key of DEPARTMENT_ORDER) {
      const t = DEPARTMENTS[key].trend;
      if (!t) continue;
      expect(t.title.trim().length, `${key} 缺标题`).toBeGreaterThan(0);
      expect(t.unit.trim().length, `${key} 缺单位`).toBeGreaterThan(0);
    }
  });

  it('带 {days} 占位的端点必须是按窗口取数的那一类', () => {
    for (const key of DEPARTMENT_ORDER) {
      const t = DEPARTMENTS[key].trend;
      if (!t) continue;
      if (t.endpoint.includes('{days}')) {
        expect(t.shape, `${key}: {days} 只用于 ops-kpi 形状`).toBe('ops-kpi');
      }
    }
  });

  it('市场与财务的趋势不是同一条曲线', () => {
    // 两边都用金额, 若指向同一个端点就等于两个部门看同一张图
    expect(DEPARTMENTS.marketing.trend!.endpoint)
      .not.toBe(DEPARTMENTS.finance.trend!.endpoint);
  });
});
