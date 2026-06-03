/**
 * WS2 #7 — 餐饮营收分析段规则洞察 (2026-06-02).
 *
 * RULES-FIRST (0 LLM token): 从已 fetch 的 gold finance-summary (门店营收排行) +
 * channel-breakdown (渠道占比) 算出一句话洞察 — 营收最高/末位门店差距 + 堂食占比。
 * 数据不足返回 null → 组件不渲染洞察条 (诚实, 不编)。
 *
 * 跟 fool-proof-design / "禁止降级处理 (不返回假数据)" 一致: 没有可算的内容就
 * 返回 null, 而不是给一句空泛的话。
 */
import { formatNumber } from '@/utils/format-number';

export interface RevenueStore {
  storeId: number;
  storeName: string;
  revenue: number;
  billCount?: number;
}

export interface RevenueChannel {
  channelId: number;
  channelName: string;
  amount: number;
  sharePct: number;
}

/** 堂食渠道名匹配 (大众点评/POS 渠道名通常含"堂食"/"门店"/"到店")。 */
const DINE_IN_RE = /堂食|店内|到店|门店|内堂/;

/**
 * 用已 fetch 的门店营收 + 渠道数据生成一句话洞察。
 *
 * @returns 洞察句子, 或 null (数据不足时不渲染)。
 */
export function buildRevenueInsight(
  stores: RevenueStore[] | null | undefined,
  channels: RevenueChannel[] | null | undefined,
): string | null {
  const parts: string[] = [];

  // 1) 门店营收差距: 需要 ≥2 家店且最高店有正营收, 否则无差距可讲。
  const valid = (stores ?? []).filter(
    (s) => s && typeof s.revenue === 'number' && s.revenue > 0 && s.storeName,
  );
  if (valid.length >= 2) {
    const sorted = [...valid].sort((a, b) => b.revenue - a.revenue);
    const top = sorted[0];
    const bottom = sorted[sorted.length - 1];
    if (bottom.revenue > 0) {
      const ratio = top.revenue / bottom.revenue;
      // 差距≥1.1 倍才值得说 (近似相等不提"X 倍", 避免"1.0 倍"废话)。
      if (ratio >= 1.1) {
        parts.push(
          `营收最高 ${top.storeName} ¥${formatNumber(top.revenue)}, ` +
            `是末位 ${bottom.storeName} 的 ${ratio.toFixed(1)} 倍`,
        );
      } else {
        parts.push(`营收最高 ${top.storeName} ¥${formatNumber(top.revenue)}, 各店较均衡`);
      }
    }
  } else if (valid.length === 1) {
    // 仅一家店: 只报它的营收, 不讲差距。
    parts.push(`${valid[0].storeName} 营收 ¥${formatNumber(valid[0].revenue)}`);
  }

  // 2) 堂食占比: 找名字含"堂食"的渠道; 找不到则不讲渠道。
  const validChan = (channels ?? []).filter(
    (c) => c && typeof c.sharePct === 'number' && c.channelName,
  );
  const dineIn = validChan.find((c) => DINE_IN_RE.test(c.channelName));
  if (dineIn && dineIn.sharePct > 0) {
    parts.push(`堂食占 ${dineIn.sharePct.toFixed(1)}%`);
  }

  if (parts.length === 0) return null;
  return parts.join('; ') + '。';
}
