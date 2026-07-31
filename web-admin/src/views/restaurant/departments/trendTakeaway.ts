/**
 * 从趋势数据算出那句「一句话结论」。
 *
 * 为什么要有这个：只给一条曲线等于把解读成本推给读者。但结论必须**从数据算出来**，
 * 不能是写死的话术 —— 写死的那种在数据变了之后会变成假话，而且不报错。
 *
 * 刻意只说**可从数据直接读出**的事实（峰值、区间、方向），不做因果推断
 * （「因为天气」「因为促销」那类），数据里没有这些。
 */

export interface TrendPoint {
  date: string;
  value: number;
}

interface Options {
  unit: string;
  money?: boolean;
}

function fmt(value: number, money?: boolean): string {
  const n = value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
  return money ? `¥${n}` : n;
}

/**
 * 方向判定用**首尾各三分之一的均值**比较，而不是第一个点比最后一个点 ——
 * 单点噪声会让「昨天恰好低」变成「整体下降」。
 */
function direction(values: number[]): 'up' | 'down' | 'flat' {
  if (values.length < 4) return 'flat';
  const span = Math.max(1, Math.floor(values.length / 3));
  const avg = (arr: number[]) => arr.reduce((a, b) => a + b, 0) / (arr.length || 1);
  const head = avg(values.slice(0, span));
  const tail = avg(values.slice(-span));
  if (head === 0) return tail > 0 ? 'up' : 'flat';
  const delta = (tail - head) / Math.abs(head);
  if (delta > 0.1) return 'up';
  if (delta < -0.1) return 'down';
  return 'flat';
}

export function trendTakeaway(points: TrendPoint[], opts: Options): string {
  const valid = (points || []).filter(
    (p) => p && typeof p.value === 'number' && Number.isFinite(p.value),
  );

  if (valid.length === 0) return '该窗口没有数据。';
  if (valid.length === 1) {
    return `窗口内只有 ${valid[0].date} 一天有数据（${fmt(valid[0].value, opts.money)} ${opts.unit}），不足以看趋势。`;
  }

  const values = valid.map((p) => p.value);
  const total = values.reduce((a, b) => a + b, 0);

  if (total === 0) {
    return `窗口内 ${valid.length} 天均为 0 —— 可能是尚未产生记录，而非真的没有发生。`;
  }

  const peak = valid.reduce((a, b) => (b.value > a.value ? b : a));
  const avg = total / values.length;
  const dir = direction(values);
  const dirText = dir === 'up' ? '整体走高' : dir === 'down' ? '整体走低' : '基本持平';

  return `${valid.length} 天合计 ${fmt(total, opts.money)} ${opts.unit}，`
    + `日均 ${fmt(avg, opts.money)}，${dirText}；`
    + `峰值在 ${peak.date}（${fmt(peak.value, opts.money)}）。`;
}
