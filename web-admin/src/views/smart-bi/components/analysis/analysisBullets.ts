/**
 * analysisBullets.ts — deterministic (0-LLM) bullet-point facts for analytics
 * pages, plus compact "data summary" strings used to ground the AI 解读/chat
 * (see AnalysisChatPanel.vue's embed mechanism).
 *
 * Design (per fool-proof-design.md 诚实不伪造 + api-response-handling.md):
 * - Pure functions, no fetch, no LLM — trivially unit-testable.
 * - Null-safe: missing/insufficient real data → fewer bullets (or []), NEVER
 *   a fabricated fact. Callers render nothing when the array is empty.
 * - `*Bullets()` = human-readable sentences for on-page display.
 * - `*Summary()` = compact "N人/¥X" strings embedded into the AI query so the
 *   dedicated chart-interpretation synthesis call reasons over the EXACT
 *   numbers the user sees. Free-form questions are routed separately through
 *   the unified Java intent orchestrator.
 *
 * This file is page-agnostic infrastructure (see splitMarkdownBullets, used
 * by the shared ChartInsights.vue). The `member*` builders are the first
 * consumer (会员分析 page) — other analytics pages add their own named
 * builders here as they wire up the hybrid bullet-point analysis pattern.
 */

import type { MemberRfm, MemberProfile, VoidRate, ZoneEfficiency } from '@/api/smartbi/gold';
import { formatNumber } from '@/utils/format-number';

/**
 * One chart's context, fed into AnalysisChatPanel.vue's props.contexts and
 * ChartInsights.vue's props.dataSummary/chartTitle. `dataSummary` is a
 * *Summary() output (or hand-built equivalent) — a compact real-number
 * string with NO invented figures.
 */
export interface AnalysisChartContext {
  key: string;
  title: string;
  dataSummary: string;
  /** Exact period/source/semantic scope of this display context. */
  dataScope?: string;
  /** Metrics absent from this display summary (absence must never be treated as zero). */
  unavailableMetrics?: string[];
}

// ============================================================
// Shared helpers
// ============================================================

function fmtMoney(v: number | null | undefined): string | null {
  if (v == null) return null;
  return `¥${formatNumber(v)}`;
}

/** English RFM tier code → 中文业务标签 (mirrors member-analysis/index.vue TIER_LABELS). */
const TIER_LABELS: Record<string, string> = {
  Champions: '重要价值客户',
  Loyal: '重要保持客户',
  Potential: '重要发展客户',
  New: '新客户',
  'At Risk': '重要挽留客户',
  Hibernating: '一般维持客户',
  Lost: '流失客户',
};
function tierLabel(tier: string): string {
  return TIER_LABELS[tier] || tier;
}

// ============================================================
// splitMarkdownBullets — generic markdown → bullet[] parser
// ============================================================

/**
 * Split an LLM markdown answer into individual bullet strings.
 *
 * Recognizes `- `, `* `, `• ` and `1. ` / `1、` list markers (one per line).
 * If no bullet markers are found, the whole (trimmed) text is returned as a
 * single-element array — we never fabricate a split that isn't there.
 * Empty/whitespace-only input → [].
 */
export function splitMarkdownBullets(text: string | null | undefined): string[] {
  if (!text) return [];
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);

  const bulletLines = lines
    .filter((l) => /^[-*•]\s+/.test(l) || /^\d+[.、]\s*/.test(l))
    .map((l) => l.replace(/^[-*•]\s+/, '').replace(/^\d+[.、]\s*/, '').trim())
    .filter(Boolean);

  if (bulletLines.length) return bulletLines;

  const trimmed = text.trim();
  return trimmed ? [trimmed] : [];
}

// ============================================================
// 会员 RFM 客群分层 (rfm_tier_distribution)
// ============================================================

type RfmTierRow = MemberRfm['rfmTierDistribution'][number];

export function memberRfmTierBullets(rows: RfmTierRow[] | null | undefined): string[] {
  const valid = (rows ?? []).filter((r) => r.memberCount > 0);
  if (!valid.length) return [];

  const bullets: string[] = [];
  const total = valid.reduce((s, r) => s + r.memberCount, 0);

  // 1. 人数最多的客群 + 占比
  const byCount = [...valid].sort((a, b) => b.memberCount - a.memberCount);
  const top = byCount[0];
  if (total > 0) {
    const pct = (top.memberCount / total) * 100;
    bullets.push(`${tierLabel(top.rfmTier)}人数最多，共 ${top.memberCount} 人，占全部会员 ${pct.toFixed(1)}%`);
  }

  // 2. 重要挽留客户 (At Risk) — 高消费但已进入挽留窗口，唤回优先级最高
  const atRisk = valid.find((r) => r.rfmTier === 'At Risk');
  if (atRisk) {
    const spend = fmtMoney(atRisk.totalCumSpend);
    bullets.push(
      spend
        ? `重要挽留客户共 ${atRisk.memberCount} 人，历史累计消费 ${spend}，建议优先精准营销唤回`
        : `重要挽留客户共 ${atRisk.memberCount} 人，建议优先精准营销唤回`,
    );
  }

  // 3. 历史消费能力最高的客群
  const bySpend = valid
    .filter((r) => r.totalCumSpend != null)
    .sort((a, b) => (b.totalCumSpend as number) - (a.totalCumSpend as number));
  if (bySpend.length && bySpend[0].rfmTier !== atRisk?.rfmTier) {
    const richest = bySpend[0];
    bullets.push(`${tierLabel(richest.rfmTier)}历史累计消费最高，达 ${fmtMoney(richest.totalCumSpend)}`);
  }

  return bullets;
}

export function memberRfmTierSummary(rows: RfmTierRow[] | null | undefined): string {
  const valid = (rows ?? []).filter((r) => r.memberCount > 0).sort((a, b) => b.memberCount - a.memberCount);
  if (!valid.length) return '暂无数据';
  return valid
    .map((r) => {
      const spend = r.totalCumSpend != null ? `(历史消费${fmtMoney(r.totalCumSpend)})` : '';
      return `${tierLabel(r.rfmTier)}${r.memberCount}人${spend}`;
    })
    .join(' / ');
}

// ============================================================
// 会员生命周期 (lifecycle_distribution)
// ============================================================

type LifecycleRow = MemberRfm['lifecycleDistribution'][number];

export function lifecycleBullets(rows: LifecycleRow[] | null | undefined): string[] {
  const valid = (rows ?? []).filter((r) => r.memberCount > 0);
  if (!valid.length) return [];
  const total = valid.reduce((s, r) => s + r.memberCount, 0);
  const bullets: string[] = [];

  for (const stage of ['活跃', '沉睡', '流失']) {
    const row = valid.find((r) => r.lifecycleStage === stage);
    if (row && total > 0) {
      const pct = (row.memberCount / total) * 100;
      bullets.push(`${stage}会员 ${row.memberCount} 人，占 ${pct.toFixed(1)}%`);
    }
  }

  const dormant = valid.find((r) => r.lifecycleStage === '沉睡');
  const churned = valid.find((r) => r.lifecycleStage === '流失');
  const atRiskCount = (dormant?.memberCount ?? 0) + (churned?.memberCount ?? 0);
  if (total > 0 && atRiskCount > 0) {
    const pct = (atRiskCount / total) * 100;
    bullets.push(`沉睡+流失会员合计 ${atRiskCount} 人，占 ${pct.toFixed(1)}%，是唤回运营的主要目标群体`);
  }

  return bullets;
}

export function lifecycleSummary(rows: LifecycleRow[] | null | undefined): string {
  const valid = (rows ?? []).filter((r) => r.memberCount > 0);
  if (!valid.length) return '暂无数据';
  return valid
    .map((r) => {
      const bal = r.totalBalance != null ? `(储值${fmtMoney(r.totalBalance)})` : '';
      return `${r.lifecycleStage}${r.memberCount}人${bal}`;
    })
    .join(' / ');
}

// ============================================================
// RFM 三维散点 (rfm_scatter)
// ============================================================

type ScatterRow = MemberRfm['rfmScatter'][number];

export function rfmScatterBullets(
  scatter: ScatterRow[] | null | undefined,
  suppressedCount: number,
): string[] {
  const valid = (scatter ?? []).filter((r) => r.memberCount > 0);
  if (!valid.length) return [];
  const bullets: string[] = [];
  const total = valid.reduce((s, r) => s + r.memberCount, 0);

  const byCount = [...valid].sort((a, b) => b.memberCount - a.memberCount);
  const top = byCount[0];
  if (total > 0) {
    const pct = (top.memberCount / total) * 100;
    bullets.push(
      `R${top.rScore}F${top.fScore}M${top.mScore} 分段人数最多，共 ${top.memberCount} 人（占已展示会员 ${pct.toFixed(1)}%）`,
    );
  }

  const bySpend = valid
    .filter((r) => r.avgCumSpend != null)
    .sort((a, b) => (b.avgCumSpend as number) - (a.avgCumSpend as number));
  if (bySpend.length) {
    const richest = bySpend[0];
    bullets.push(
      `R${richest.rScore}F${richest.fScore}M${richest.mScore} 分段人均消费最高，达 ${fmtMoney(richest.avgCumSpend)}`,
    );
  }

  // R 高 (近期活跃) 但 F 低 (频次不足) — 提频潜力群体
  const highRLowF = valid.filter((r) => r.rScore >= 4 && r.fScore <= 2);
  const highRLowFCount = highRLowF.reduce((s, r) => s + r.memberCount, 0);
  if (highRLowFCount > 0) {
    bullets.push(`近期消费活跃但频次偏低（R高F低）的会员共 ${highRLowFCount} 人，具备提频潜力`);
  }

  if (suppressedCount > 0) {
    bullets.push(`另有 ${suppressedCount} 位会员因群体过小(<5)已隐去（隐私保护）`);
  }

  return bullets;
}

export function rfmScatterSummary(
  scatter: ScatterRow[] | null | undefined,
  suppressedCount: number,
): string {
  const valid = (scatter ?? []).filter((r) => r.memberCount > 0).sort((a, b) => b.memberCount - a.memberCount);
  if (!valid.length) return '暂无数据';
  const top = valid
    .slice(0, 6)
    .map((r) => {
      const spend = r.avgCumSpend != null ? `均消费${fmtMoney(r.avgCumSpend)}` : '';
      return `R${r.rScore}F${r.fScore}M${r.mScore}:${r.memberCount}人${spend ? `(${spend})` : ''}`;
    })
    .join(' / ');
  const suppressedNote = suppressedCount > 0 ? `；另有${suppressedCount}人因群体过小已隐去` : '';
  return top + suppressedNote;
}

// ============================================================
// 会员画像 (tier / gender / birth-month / recharge)
// ============================================================

export interface MemberProfileBulletsInput {
  memberCount: number;
  totalBalance: number | null;
  tierDistribution: MemberProfile['tierDistribution'];
  genderDistribution: MemberProfile['genderDistribution'];
  birthMonthDistribution: MemberProfile['birthMonthDistribution'];
  birthMonthCoveragePct: number | null;
  birthMonthUnknownCount: number;
  rechargeTrend: MemberProfile['rechargeTrend'];
  rechargeStoreCount: number;
}

export function memberProfileBullets(profile: MemberProfileBulletsInput): string[] {
  const bullets: string[] = [];

  if (profile.memberCount > 0) {
    const balance = fmtMoney(profile.totalBalance);
    bullets.push(
      balance
        ? `会员总数 ${profile.memberCount} 人，储值总额 ${balance}`
        : `会员总数 ${profile.memberCount} 人`,
    );
  }

  const tiers = (profile.tierDistribution ?? []).filter((t) => t.memberCount > 0);
  if (tiers.length) {
    const total = tiers.reduce((s, t) => s + t.memberCount, 0);
    const top = [...tiers].sort((a, b) => b.memberCount - a.memberCount)[0];
    if (total > 0) {
      const pct = (top.memberCount / total) * 100;
      bullets.push(`${top.tier} 等级人数最多，共 ${top.memberCount} 人，占 ${pct.toFixed(1)}%`);
    }
  }

  const genders = (profile.genderDistribution ?? []).filter((g) => g.memberCount > 0);
  if (genders.length) {
    const total = genders.reduce((s, g) => s + g.memberCount, 0);
    const top = [...genders].sort((a, b) => b.memberCount - a.memberCount)[0];
    if (total > 0) {
      const pct = (top.memberCount / total) * 100;
      bullets.push(`${top.gender}会员占比最高，${pct.toFixed(1)}%（共 ${top.memberCount} 人）`);
    }
  }

  if (profile.birthMonthCoveragePct != null) {
    bullets.push(
      `生日覆盖率 ${profile.birthMonthCoveragePct.toFixed(0)}%，${profile.birthMonthUnknownCount.toLocaleString()} 名会员未填生日`,
    );
  }
  const births = (profile.birthMonthDistribution ?? []).filter((b) => b.memberCount > 0);
  if (births.length) {
    const top = [...births].sort((a, b) => b.memberCount - a.memberCount)[0];
    bullets.push(`${top.birthMonth} 月生日会员最多，共 ${top.memberCount} 人，适合生日营销活动`);
  }

  const recharges = (profile.rechargeTrend ?? []).filter((r) => r.principal != null || r.bonus != null);
  if (recharges.length) {
    const latest = recharges[recharges.length - 1];
    const principal = fmtMoney(latest.principal);
    const bonus = fmtMoney(latest.bonus);
    if (principal || bonus) {
      const storeNote = profile.rechargeStoreCount > 0 ? `（${profile.rechargeStoreCount} 家门店有记录）` : '';
      bullets.push(`${latest.month} 充值本金 ${principal ?? '—'}，赠送 ${bonus ?? '—'}${storeNote}`);
    }
  }

  return bullets;
}

export function memberProfileSummary(profile: MemberProfileBulletsInput): string {
  const parts: string[] = [];

  if (profile.memberCount > 0) {
    const balance = fmtMoney(profile.totalBalance);
    parts.push(`会员总数${profile.memberCount}人${balance ? `，储值总额${balance}` : ''}`);
  }

  const tiers = (profile.tierDistribution ?? [])
    .filter((t) => t.memberCount > 0)
    .sort((a, b) => b.memberCount - a.memberCount);
  if (tiers.length) {
    parts.push('等级: ' + tiers.map((t) => `${t.tier}${t.memberCount}人`).join('/'));
  }

  const genders = (profile.genderDistribution ?? [])
    .filter((g) => g.memberCount > 0)
    .sort((a, b) => b.memberCount - a.memberCount);
  if (genders.length) {
    parts.push('性别: ' + genders.map((g) => `${g.gender}${g.memberCount}人`).join('/'));
  }

  if (profile.birthMonthCoveragePct != null) {
    parts.push(`生日覆盖率${profile.birthMonthCoveragePct.toFixed(0)}%`);
  }

  const recharges = (profile.rechargeTrend ?? []).filter((r) => r.principal != null || r.bonus != null);
  if (recharges.length) {
    const latest = recharges[recharges.length - 1];
    parts.push(`最近充值(${latest.month})本金${fmtMoney(latest.principal) ?? '—'}/赠送${fmtMoney(latest.bonus) ?? '—'}`);
  }

  return parts.length ? parts.join('；') : '暂无数据';
}

// ============================================================
// 撤单稽核 (void-rate + breakdown) — 运营分析 page (2026-07-12)
// ============================================================

export interface VoidRateBulletsInput {
  voidRate: number | null;
  voidCount: number;
  billCount: number;
  breakdown: VoidRate['breakdown'];
}

export function voidRateBullets(input: VoidRateBulletsInput): string[] {
  const bullets: string[] = [];

  if (input.voidRate != null) {
    bullets.push(
      `撤单率 ${input.voidRate.toFixed(2)}%，共 ${input.voidCount} 单撤单（开单总数 ${input.billCount.toLocaleString()}）`,
    );
  }

  const rated = (input.breakdown ?? []).filter((b) => b.voidsPer100Bills != null && b.voidCount > 0);
  if (rated.length) {
    const top = [...rated].sort((a, b) => (b.voidsPer100Bills as number) - (a.voidsPer100Bills as number))[0];
    const reasonNote = top.topReason ? `，主要原因: ${top.topReason}` : '';
    bullets.push(
      `${top.staffName}（${top.storeName}）每百单撤单率最高，达 ${(top.voidsPer100Bills as number).toFixed(2)}${reasonNote}`,
    );
  }

  // 按操作人「主要撤单原因」加权 voidCount 汇总的近似值 (breakdown 只给逐人主要原因,
  // 非逐笔撤单明细) — 找出最常见原因, 诚实标注为近似, 不假装逐单精确统计。
  const reasoned = (input.breakdown ?? []).filter((b) => b.topReason && b.voidCount > 0);
  if (reasoned.length) {
    const counts = new Map<string, number>();
    for (const row of reasoned) {
      const reason = row.topReason as string;
      counts.set(reason, (counts.get(reason) ?? 0) + row.voidCount);
    }
    const [topReason, topReasonCount] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];
    bullets.push(`最常见撤单原因为「${topReason}」，涉及约 ${topReasonCount} 单（按操作人主要原因汇总的近似值）`);
  }

  return bullets;
}

export function voidRateSummary(input: VoidRateBulletsInput): string {
  if (input.voidRate == null) return '暂无数据';
  const parts = [`撤单率${input.voidRate.toFixed(2)}%(${input.voidCount}/${input.billCount}单)`];

  const rated = (input.breakdown ?? [])
    .filter((b) => b.voidsPer100Bills != null)
    .sort((a, b) => (b.voidsPer100Bills as number) - (a.voidsPer100Bills as number))
    .slice(0, 6);
  if (rated.length) {
    parts.push(
      '操作人: ' +
        rated
          .map(
            (b) =>
              `${b.staffName}(${b.storeName})每百单${(b.voidsPer100Bills as number).toFixed(2)}${b.topReason ? `/${b.topReason}` : ''}`,
          )
          .join(' / '),
    );
  }

  return parts.join('；');
}

// ============================================================
// 区域坪效 (zone revenue/item-qty proxy) — 运营分析 page (2026-07-12)
// ============================================================

export interface ZoneEfficiencyBulletsInput {
  totalRevenue: number | null;
  totalItemQty: number;
  zones: ZoneEfficiency['zones'];
}

export function zoneEfficiencyBullets(input: ZoneEfficiencyBulletsInput): string[] {
  const valid = (input.zones ?? []).filter((z) => z.itemQty > 0 || (z.revenue != null && z.revenue > 0));
  if (!valid.length) return [];
  const bullets: string[] = [];

  // 营收最高区域 (revenue 对无价格权限角色被 RBAC 置 null — 全体一致置空, 不会部分置空)
  const byRevenue = valid.filter((z) => z.revenue != null).sort((a, b) => (b.revenue as number) - (a.revenue as number));
  if (byRevenue.length) {
    const top = byRevenue[0];
    const pct = top.revenuePct != null ? `，占比 ${top.revenuePct.toFixed(1)}%` : '';
    bullets.push(`${top.zoneName}营收最高，达 ${fmtMoney(top.revenue)}${pct}`);
  }

  // 销售数量最高区域 (itemQty 从不 RBAC-脱敏) — 若与营收最高区域不同才单独提及
  const byQty = [...valid].sort((a, b) => b.itemQty - a.itemQty);
  const topQty = byQty[0];
  if (topQty && (!byRevenue.length || topQty.zoneName !== byRevenue[0].zoneName)) {
    bullets.push(`${topQty.zoneName}销售数量最高，共 ${topQty.itemQty.toLocaleString()} 件`);
  }

  if (valid.length > 1) {
    bullets.push(
      input.totalRevenue != null
        ? `共 ${valid.length} 个区域/渠道有销售记录，合计营收 ${fmtMoney(input.totalRevenue)}`
        : `共 ${valid.length} 个区域/渠道有销售记录`,
    );
  }

  return bullets;
}

export function zoneEfficiencySummary(input: ZoneEfficiencyBulletsInput): string {
  const valid = (input.zones ?? []).filter((z) => z.itemQty > 0 || (z.revenue != null && z.revenue > 0));
  if (!valid.length) return '暂无数据';

  const sorted = [...valid].sort((a, b) => {
    if (a.revenue != null && b.revenue != null) return b.revenue - a.revenue;
    return b.itemQty - a.itemQty;
  });

  return sorted
    .slice(0, 8)
    .map((z) => {
      const rev = fmtMoney(z.revenue);
      const pct = z.revenuePct != null ? `(${z.revenuePct.toFixed(1)}%)` : '';
      return `${z.zoneName}:${rev ?? '—'}${pct}/${z.itemQty}件`;
    })
    .join(' / ');
}

// ============================================================
// 平台分析 (原 大众点评口碑) — review-summary/review-platform/
// review-good-tags/review-store-ranking/review-trend — platform.vue (2026-07-12)
// ============================================================

export interface PlatformOverviewBulletsInput {
  totalReviews: number;
  storeCount: number;
  cityCount: number;
  lowStarCount: number;
  scoreCards: Array<{ name: string; value: number | null }>;
  goodTags: Array<{ tag: string; count: number }>;
}

export function platformOverviewBullets(input: PlatformOverviewBulletsInput): string[] {
  if (input.totalReviews <= 0) return [];
  const bullets: string[] = [];

  bullets.push(
    `平台点评合计 ${input.totalReviews.toLocaleString()} 条，覆盖 ${input.storeCount} 家门店、${input.cityCount} 个城市`,
  );

  const scored = (input.scoreCards ?? []).filter((c) => c.value != null);
  if (scored.length) {
    const top = [...scored].sort((a, b) => (b.value as number) - (a.value as number))[0];
    bullets.push(`${top.name}分最高，达 ${(top.value as number).toFixed(2)} 分`);
  }

  if (input.lowStarCount > 0) {
    bullets.push(`低星(≤3分)评价共 ${input.lowStarCount.toLocaleString()} 条，建议优先归因处理`);
  }

  const tags = (input.goodTags ?? []).filter((t) => t.count > 0);
  if (tags.length) {
    const top = [...tags].sort((a, b) => b.count - a.count)[0];
    bullets.push(`好评标签中「${top.tag}」提及最多，共 ${top.count.toLocaleString()} 次`);
  }

  return bullets;
}

export function platformOverviewSummary(input: PlatformOverviewBulletsInput): string {
  if (input.totalReviews <= 0) return '暂无数据';
  const parts = [`合计${input.totalReviews}条/${input.storeCount}店/${input.cityCount}城`];

  const scored = (input.scoreCards ?? []).filter((c) => c.value != null);
  if (scored.length) {
    parts.push(scored.map((c) => `${c.name}${(c.value as number).toFixed(2)}`).join('/'));
  }
  if (input.lowStarCount > 0) parts.push(`低星${input.lowStarCount}条`);

  const tags = (input.goodTags ?? [])
    .filter((t) => t.count > 0)
    .sort((a, b) => b.count - a.count)
    .slice(0, 5);
  if (tags.length) parts.push('好评标签: ' + tags.map((t) => `${t.tag}(${t.count})`).join('/'));

  return parts.join('；');
}

export interface PlatformCompareRow {
  platform: string;
  reviewCount: number;
  avgStar: number | null;
}

export function platformCompareBullets(platforms: PlatformCompareRow[] | null | undefined): string[] {
  const valid = (platforms ?? []).filter((p) => p.reviewCount > 0);
  if (!valid.length) return [];
  const bullets: string[] = [];

  const byCount = [...valid].sort((a, b) => b.reviewCount - a.reviewCount);
  const top = byCount[0];
  bullets.push(`${top.platform}评价量最多，共 ${top.reviewCount.toLocaleString()} 条`);

  const scored = valid.filter((p) => p.avgStar != null);
  if (scored.length >= 2) {
    const byStar = [...scored].sort((a, b) => (b.avgStar as number) - (a.avgStar as number));
    const best = byStar[0];
    const worst = byStar[byStar.length - 1];
    if (best.platform !== worst.platform) {
      bullets.push(
        `${best.platform}平均星级最高(${(best.avgStar as number).toFixed(2)}分)，${worst.platform}最低(${(worst.avgStar as number).toFixed(2)}分)`,
      );
    }
  }

  return bullets;
}

export function platformCompareSummary(platforms: PlatformCompareRow[] | null | undefined): string {
  const valid = (platforms ?? []).filter((p) => p.reviewCount > 0).sort((a, b) => b.reviewCount - a.reviewCount);
  if (!valid.length) return '暂无数据';
  return valid
    .map((p) => `${p.platform}:${p.reviewCount}条${p.avgStar != null ? `(${p.avgStar.toFixed(2)}分)` : ''}`)
    .join(' / ');
}

export interface StoreRankingRow {
  store: string;
  reviewCount: number;
  avgStar: number | null;
  lowStarCount: number;
}

export function storeRankingBullets(stores: StoreRankingRow[] | null | undefined): string[] {
  const valid = (stores ?? []).filter((s) => s.reviewCount > 0);
  if (!valid.length) return [];
  const bullets: string[] = [];

  const scored = valid.filter((s) => s.avgStar != null);
  if (scored.length) {
    const byStar = [...scored].sort((a, b) => (b.avgStar as number) - (a.avgStar as number));
    const best = byStar[0];
    bullets.push(`${best.store}口碑最高，均 ${(best.avgStar as number).toFixed(2)} 星`);
    if (byStar.length > 1) {
      const weakest = byStar[byStar.length - 1];
      if (weakest.store !== best.store) {
        bullets.push(`${weakest.store}口碑暂时最弱，均 ${(weakest.avgStar as number).toFixed(2)} 星，是补课对象`);
      }
    }
  }

  const lowStarRanked = valid.filter((s) => s.lowStarCount > 0).sort((a, b) => b.lowStarCount - a.lowStarCount);
  if (lowStarRanked.length) {
    const top = lowStarRanked[0];
    bullets.push(`${top.store}低星评价最多，共 ${top.lowStarCount.toLocaleString()} 条`);
  }

  return bullets;
}

export function storeRankingSummary(stores: StoreRankingRow[] | null | undefined): string {
  const valid = (stores ?? []).filter((s) => s.reviewCount > 0).sort((a, b) => (b.avgStar ?? 0) - (a.avgStar ?? 0));
  if (!valid.length) return '暂无数据';
  return valid
    .slice(0, 8)
    .map((s) => `${s.store}:${s.avgStar != null ? `${s.avgStar.toFixed(2)}星` : '—'}(${s.reviewCount}条/低星${s.lowStarCount})`)
    .join(' / ');
}

export interface ReviewTrendRow {
  month: string;
  reviewCount: number;
  avgStar: number | null;
}

export function reviewTrendBullets(trend: ReviewTrendRow[] | null | undefined): string[] {
  const valid = (trend ?? []).filter((t) => t.reviewCount > 0 || t.avgStar != null);
  if (!valid.length) return [];
  const bullets: string[] = [];

  if (valid.length >= 2) {
    const first = valid[0];
    const last = valid[valid.length - 1];
    if (first.avgStar != null && last.avgStar != null) {
      const delta = last.avgStar - first.avgStar;
      bullets.push(
        `评分自 ${first.month} 到 ${last.month} ${delta >= 0 ? '提升' : '下降'} ${Math.abs(delta).toFixed(2)} 分`,
      );
    }
  }

  const byCount = [...valid].sort((a, b) => b.reviewCount - a.reviewCount);
  const peak = byCount[0];
  if (peak.reviewCount > 0) {
    bullets.push(`${peak.month}评价量最高，达 ${peak.reviewCount.toLocaleString()} 条`);
  }

  return bullets;
}

export function reviewTrendSummary(trend: ReviewTrendRow[] | null | undefined): string {
  const valid = (trend ?? []).filter((t) => t.reviewCount > 0 || t.avgStar != null);
  if (!valid.length) return '暂无数据';
  return valid
    .map((t) => `${t.month}:${t.reviewCount}条${t.avgStar != null ? `(${t.avgStar.toFixed(2)}星)` : ''}`)
    .join(' / ');
}

// ============================================================
// 经营分析 Hub (BusinessAnalysisHub.vue) — 财务/销售/趋势/KPI 全历史概览
// (2026-07-12)
//
// Hub 独立请求 gold.ts 现成的、后端已按角色 RBAC 脱敏的 summary 端点
// (getFinanceSummary/getTopProducts/getTrendBundle/getKpiSummary), 不深入
// FinancialDashboardPBI/SalesAnalysis/TrendsView/KpiView 各自数千行的内部
// 状态、日期选择器和 tier/capability gate 逻辑 (侵入式改造那 4 个大文件
// 风险高, 见 BusinessAnalysisHub.vue 顶部说明)。这里只做全历史粗粒度概览,
// 供 Hub 侧 AI 面板 grounding, 与各 tab 内可自定义时间范围的图表相互独立。
// ============================================================

export interface FinanceOverviewBulletsInput {
  totalRevenue: number | null;
  billCount: number;
  avgBillValue: number | null;
  storeCount: number;
  topStores: Array<{ storeName: string; revenue: number; billCount: number }>;
}

export function financeOverviewBullets(input: FinanceOverviewBulletsInput): string[] {
  if (input.billCount <= 0 || input.totalRevenue == null) return [];
  const bullets: string[] = [];

  bullets.push(
    `全部历史营收合计 ${fmtMoney(input.totalRevenue)}，共 ${input.billCount.toLocaleString()} 单，覆盖 ${input.storeCount} 家门店`,
  );
  if (input.avgBillValue != null) {
    bullets.push(`平均客单价 ${fmtMoney(input.avgBillValue)}`);
  }

  const stores = (input.topStores ?? []).filter((s) => s.revenue > 0);
  if (stores.length) {
    const top = [...stores].sort((a, b) => b.revenue - a.revenue)[0];
    bullets.push(`${top.storeName}营收最高，达 ${fmtMoney(top.revenue)}（${top.billCount.toLocaleString()} 单）`);
  }

  return bullets;
}

export function financeOverviewSummary(input: FinanceOverviewBulletsInput): string {
  if (input.billCount <= 0 || input.totalRevenue == null) return '暂无数据';
  const parts = [`全部历史营收${fmtMoney(input.totalRevenue)}(${input.billCount}单/${input.storeCount}店)`];
  if (input.avgBillValue != null) parts.push(`客单价${fmtMoney(input.avgBillValue)}`);

  const stores = (input.topStores ?? [])
    .filter((s) => s.revenue > 0)
    .sort((a, b) => b.revenue - a.revenue)
    .slice(0, 5);
  if (stores.length) parts.push('门店: ' + stores.map((s) => `${s.storeName}${fmtMoney(s.revenue)}`).join('/'));

  return parts.join('；');
}

export interface SalesOverviewBulletsInput {
  topProducts: Array<{ productName: string; qtySold: number; revenue: number; billCount: number }>;
}

export function salesOverviewBullets(input: SalesOverviewBulletsInput): string[] {
  const valid = (input.topProducts ?? []).filter((p) => p.qtySold > 0 || p.revenue > 0);
  if (!valid.length) return [];
  const bullets: string[] = [];

  const byQty = [...valid].sort((a, b) => b.qtySold - a.qtySold);
  const topQty = byQty[0];
  bullets.push(`${topQty.productName}销量最高，共 ${topQty.qtySold.toLocaleString()} 份`);

  const byRevenue = [...valid].sort((a, b) => b.revenue - a.revenue);
  const topRevenue = byRevenue[0];
  if (topRevenue.productName !== topQty.productName) {
    bullets.push(`${topRevenue.productName}销售额最高，达 ${fmtMoney(topRevenue.revenue) ?? '—'}`);
  }

  return bullets;
}

export function salesOverviewSummary(input: SalesOverviewBulletsInput): string {
  const valid = (input.topProducts ?? [])
    .filter((p) => p.qtySold > 0 || p.revenue > 0)
    .sort((a, b) => b.qtySold - a.qtySold);
  if (!valid.length) return '暂无数据';
  return valid.slice(0, 8).map((p) => `${p.productName}:${p.qtySold}份/${fmtMoney(p.revenue) ?? '—'}`).join(' / ');
}

export interface TrendOverviewBulletsInput {
  dailyTrend: Array<{ date: string; revenue: number | null; billCount: number }>;
  monthlyTrend: Array<{ month: string; revenue: number | null }>;
  weekdayWeekend: { weekdayAvg: number | null; weekendAvg: number | null; weekdayDays: number; weekendDays: number };
}

export function trendOverviewBullets(input: TrendOverviewBulletsInput): string[] {
  const bullets: string[] = [];

  const monthly = (input.monthlyTrend ?? []).filter((m) => m.revenue != null);
  if (monthly.length >= 2) {
    const first = monthly[0];
    const last = monthly[monthly.length - 1];
    const delta = (last.revenue as number) - (first.revenue as number);
    bullets.push(
      `月度营收自 ${first.month} 到 ${last.month} ${delta >= 0 ? '增长' : '下降'} ${fmtMoney(Math.abs(delta)) ?? '—'}`,
    );
  }

  const ww = input.weekdayWeekend;
  if (ww && ww.weekdayAvg != null && ww.weekendAvg != null) {
    const weekendHigher = ww.weekendAvg > ww.weekdayAvg;
    bullets.push(
      `工作日日均营收 ${fmtMoney(ww.weekdayAvg)}，周末日均营收 ${fmtMoney(ww.weekendAvg)}${weekendHigher ? '，周末明显更高' : ''}`,
    );
  }

  const daily = (input.dailyTrend ?? []).filter((d) => d.revenue != null);
  if (daily.length) {
    const peak = [...daily].sort((a, b) => (b.revenue as number) - (a.revenue as number))[0];
    bullets.push(`单日营收峰值出现在 ${peak.date}，达 ${fmtMoney(peak.revenue)}`);
  }

  return bullets;
}

export function trendOverviewSummary(input: TrendOverviewBulletsInput): string {
  const monthly = (input.monthlyTrend ?? []).filter((m) => m.revenue != null);
  if (!monthly.length) return '暂无数据';
  const parts = [monthly.slice(-6).map((m) => `${m.month}:${fmtMoney(m.revenue)}`).join(' / ')];

  const ww = input.weekdayWeekend;
  if (ww && ww.weekdayAvg != null && ww.weekendAvg != null) {
    parts.push(`工作日均${fmtMoney(ww.weekdayAvg)}/周末均${fmtMoney(ww.weekendAvg)}`);
  }

  return parts.join('；');
}

export interface KpiOverviewBulletsInput {
  revenue: number;
  billCount: number;
  itemCount: number;
  customerCount: number;
  storeCount: number;
  avgBillValue: number | null;
  itemsPerBill: number | null;
  avgPerCapita: number | null;
}

export function kpiOverviewBullets(input: KpiOverviewBulletsInput): string[] {
  if (input.billCount <= 0) return [];
  const bullets: string[] = [];

  bullets.push(
    `全部历史营收 ${fmtMoney(input.revenue) ?? '—'}，共 ${input.billCount.toLocaleString()} 单，覆盖 ${input.storeCount} 家门店`,
  );
  if (input.avgBillValue != null) bullets.push(`平均客单价 ${fmtMoney(input.avgBillValue)}`);
  if (input.avgPerCapita != null) bullets.push(`人均消费 ${fmtMoney(input.avgPerCapita)}`);
  if (input.itemsPerBill != null) bullets.push(`每单平均 ${input.itemsPerBill.toFixed(1)} 件商品`);

  return bullets;
}

export function kpiOverviewSummary(input: KpiOverviewBulletsInput): string {
  if (input.billCount <= 0) return '暂无数据';
  const parts = [
    `营收${fmtMoney(input.revenue) ?? '—'}(${input.billCount}单/${input.storeCount}店/${input.customerCount}客)`,
  ];
  if (input.avgBillValue != null) parts.push(`客单价${fmtMoney(input.avgBillValue)}`);
  if (input.avgPerCapita != null) parts.push(`人均${fmtMoney(input.avgPerCapita)}`);

  return parts.join('；');
}
