/**
 * #56 价值可视化回馈回路 — ValueFeedbackStrip 纯格式化 helper (vitest 可测)。
 *
 * 禁降级: null 金额 → '暂无数据' (不填 ¥0)。区分预估 (橙) / 实测 (绿) / 暂无 (灰)。
 */
import type { ValueSummary } from '@/api/smartbi/restaurantValueApi';

export type ValueKind = 'estimate' | 'measured' | 'none';

export interface ValueChip {
  label: string;
  amount: number | null;
  kind: ValueKind;
  /** 单位后缀, 如 '/月' '/年'。 */
  suffix: string;
}

/** 颜色: 预估橙 #FF9800 / 实测绿 #4CAF50 / 暂无灰 #9E9E9E。 */
export const KIND_COLORS: Record<ValueKind, string> = {
  estimate: '#FF9800',
  measured: '#4CAF50',
  none: '#9E9E9E',
};

export const KIND_LABELS: Record<ValueKind, string> = {
  estimate: '预估',
  measured: '实测',
  none: '暂无',
};

/** 金额格式化: null → '暂无数据' (禁填 0); 否则千分位 ¥。 */
export function formatValueAmount(v: number | null | undefined): string {
  if (v == null) return '暂无数据';
  return '¥' + Math.round(v).toLocaleString('zh-CN');
}

function kindOf(amount: number | null | undefined, baseKind: 'estimate' | 'measured'): ValueKind {
  return amount == null ? 'none' : baseKind;
}

/**
 * 把快照按期间口径 (month | annual) 拆成展示 chips。
 *
 * month: 食材改善空间 / 折扣率改善空间 (预估) + 档口损溢超标 (实测)。
 * annual: 人工刚性节省 (预估·年化)。
 * 合计 chip 单独 (顶部大数字)。
 */
export function buildValueChips(summary: ValueSummary, period: 'month' | 'annual'): ValueChip[] {
  const chips: ValueChip[] = [];
  if (period === 'month') {
    const m = summary.month;
    chips.push({ label: '食材成本改善空间', amount: m.foodCostSavings, kind: kindOf(m.foodCostSavings, 'estimate'), suffix: '/月' });
    chips.push({ label: '折扣率改善空间', amount: m.discountSavings, kind: kindOf(m.discountSavings, 'estimate'), suffix: '/月' });
    chips.push({ label: '档口损溢超标', amount: m.shrinkageVariance, kind: kindOf(m.shrinkageVariance, 'measured'), suffix: '/月' });
  } else {
    const a = summary.annual;
    chips.push({ label: '人工刚性节省', amount: a.laborRigidity, kind: kindOf(a.laborRigidity, 'estimate'), suffix: '/年' });
  }
  return chips;
}

/** 顶部合计数字 (按期间口径)。 */
export function totalForPeriod(summary: ValueSummary, period: 'month' | 'annual'): number | null {
  return period === 'month' ? summary.month.total : summary.annual.total;
}

/** 顶部合计 kind: 任何一个分项是实测则混合, 全预估则预估; 全 null → none。 */
export function totalKind(summary: ValueSummary, period: 'month' | 'annual'): ValueKind {
  const total = totalForPeriod(summary, period);
  if (total == null) return 'none';
  // 月度含损溢 (实测) → 混合标"预估为主"; 年化恒预估。这里以预估为主基调。
  return 'estimate';
}
