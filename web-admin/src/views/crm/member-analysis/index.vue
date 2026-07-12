<template>
  <!--
    CRM P0 「会员与营销」— 会员分析 (2026-07-11)
    数据源: gold.ts getMemberRfm (RFM 客群分层 + 三维散点 + 生命周期, all-time)
            + getMemberProfile (等级/性别/生日/充值 画像, 从 经营驾驶舱 RestaurantGoldGrid 迁移过来)。
    业态门控: 路由 meta module='analytics' + roles + hideForFactoryTypes=['FACTORY']
    (见 router/modules/crm.ts), 仅餐饮/demo 租户可达。
    两次请求彻底隔离 (各自 loading/error) — 一个 500 不连累另一个 (fool-proof-design 跨规则铁律 + 历史教训)。
  -->
  <div class="cma-page" data-test="crm-member-analysis">
    <header class="cma-header">
      <div class="cma-title">
        <el-icon><User /></el-icon>
        <span>会员分析</span>
        <span class="cma-subtitle">{{ factoryId }} · RFM 客群分层 · 会员画像</span>
      </div>
      <el-button size="small" type="primary" plain :loading="rfmLoading || profileLoading" @click="loadAll">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <!-- 总览条 (数字 GSAP count-up: 首次加载一次性从 0 数到目标值, 刷新时瞬时更新不重播) -->
    <div class="cma-overview">
      <div class="cma-kpi">
        <span class="cma-kpi-val">{{ memberCountDisplay }}</span>
        <span class="cma-kpi-label">会员总数</span>
      </div>
      <div class="cma-kpi">
        <span class="cma-kpi-val cma-kpi-active">{{ activeCountDisplay }}</span>
        <span class="cma-kpi-label">活跃</span>
      </div>
      <div class="cma-kpi">
        <span class="cma-kpi-val cma-kpi-dormant">{{ dormantCountDisplay }}</span>
        <span class="cma-kpi-label">沉睡</span>
      </div>
      <div class="cma-kpi">
        <span class="cma-kpi-val cma-kpi-churned">{{ churnedCountDisplay }}</span>
        <span class="cma-kpi-label">流失</span>
      </div>
      <div class="cma-kpi">
        <span class="cma-kpi-val">{{ avgSpendIntervalDisplay }}</span>
        <span class="cma-kpi-label">平均消费间隔</span>
      </div>
    </div>

    <div class="cma-layout">
      <div class="cma-main">
        <!-- RFM 客群分层 (hero) -->
        <el-card v-reveal="0" class="cma-card cma-hero" shadow="never">
          <template #header>
            <div class="cma-card-header">
              <span class="cma-card-header-left"><el-icon><TrendCharts /></el-icon><span>RFM 客群分层</span></span>
              <el-button size="small" text @click="focusChart('rfm-tier')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="rfmLoading" :rows="6" animated />
          <div v-else-if="rfmError" class="cma-inline-error">加载 RFM 数据失败: {{ rfmError }}</div>
          <template v-else-if="rfmDataAvailable && rfmTierDistribution.length">
            <!-- 规则洞察 (0 LLM, 从真实数字算出; 数据不足不渲染, 不编) -->
            <div v-if="rfmInsight" class="cma-insight">
              <el-icon class="cma-insight-icon"><MagicStick /></el-icon>
              <span>{{ rfmInsight }}</span>
            </div>
            <div ref="rfmBarChartRef" class="cma-chart" style="height: 360px"></div>
            <ChartInsights
              :bullets="rfmTierBullets"
              chart-key="rfm-tier"
              chart-title="RFM 客群分层"
              :data-summary="rfmTierSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty v-else :description="rfmNote || '暂无会员消费数据'" :image-size="60" />
        </el-card>

        <!-- RFM 三维散点 (hero) -->
        <el-card v-reveal="1" class="cma-card cma-hero" shadow="never">
          <template #header>
            <div class="cma-card-header">
              <span class="cma-card-header-left"><el-icon><Grid /></el-icon><span>RFM 三维分布 (R × F × M)</span></span>
              <el-button size="small" text @click="focusChart('rfm-scatter')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="rfmLoading" :rows="6" animated />
          <div v-else-if="rfmError" class="cma-inline-error">加载 RFM 散点数据失败: {{ rfmError }}</div>
          <template v-else-if="rfmDataAvailable && rfmScatter.length">
            <div ref="rfmScatterChartRef" class="cma-chart" style="height: 380px"></div>
            <div v-if="rfmScatterSuppressedCount > 0" class="cma-est-note">
              <el-icon><InfoFilled /></el-icon>
              <span>另有 {{ rfmScatterSuppressedCount }} 位会员因群体过小(&lt;5)已隐去(隐私保护)</span>
            </div>
            <ChartInsights
              :bullets="scatterBullets"
              chart-key="rfm-scatter"
              chart-title="RFM 三维分布"
              :data-summary="scatterSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty v-else description="暂无 RFM 散点数据" :image-size="60" />
        </el-card>

        <!-- RFM 诚实披露 (非完整 RFM 说明) -->
        <div v-if="!rfmError && rfmDataAvailable && rfmCaveat" class="cma-caveat">{{ rfmCaveat }}</div>

        <!-- 会员生命周期 -->
        <el-card v-reveal="2" class="cma-card" shadow="never">
          <template #header>
            <div class="cma-card-header">
              <span class="cma-card-header-left"><el-icon><PieChart /></el-icon><span>会员生命周期</span></span>
              <el-button size="small" text @click="focusChart('lifecycle')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="rfmLoading" :rows="4" animated />
          <div v-else-if="rfmError" class="cma-inline-error">加载生命周期数据失败: {{ rfmError }}</div>
          <template v-else-if="rfmDataAvailable && lifecycleDistribution.length">
            <div class="cma-lifecycle-grid">
              <div ref="lifecycleChartRef" class="cma-chart" style="height: 260px"></div>
              <el-table :data="lifecycleDistribution" size="small" stripe class="cma-lifecycle-table">
                <el-table-column prop="lifecycleStage" label="阶段" />
                <el-table-column label="人数" align="right">
                  <template #default="{ row }">{{ row.memberCount.toLocaleString() }}</template>
                </el-table-column>
                <el-table-column label="储值余额" align="right">
                  <template #default="{ row }">{{ formatMoney(row.totalBalance) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div class="cma-caveat-inline">此为当前快照 (非趋势), 阶段划分基于最近消费时间距今长短。</div>
            <ChartInsights
              :bullets="lifecycleBulletsComputed"
              chart-key="lifecycle"
              chart-title="会员生命周期"
              :data-summary="lifecycleSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty v-else :description="rfmNote || '暂无生命周期数据'" :image-size="60" />
        </el-card>

        <!-- 会员画像 (从 经营驾驶舱 迁移过来 — 等级/性别/生日/充值) -->
        <el-card v-reveal="3" class="cma-card" shadow="never">
          <template #header>
            <div class="cma-card-header">
              <span class="cma-card-header-left"><el-icon><User /></el-icon><span>会员画像</span></span>
              <el-button size="small" text @click="focusChart('profile')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="profileLoading" :rows="4" animated />
          <div v-else-if="profileError" class="cma-inline-error">会员画像加载失败: {{ profileError }}</div>
          <template v-else-if="profileDataAvailable">
            <div class="cma-profile-kpi">
              <div class="cma-kpi">
                <span class="cma-kpi-val">{{ profileMemberCount.toLocaleString() }}</span>
                <span class="cma-kpi-label">会员数</span>
              </div>
              <div class="cma-kpi">
                <span class="cma-kpi-val">{{ formatMoney(profileTotalBalance) }}</span>
                <span class="cma-kpi-label">储值总额</span>
              </div>
            </div>

            <div class="cma-member-detail-grid">
              <!-- 等级分布 (k-anon: 人数<5 的等级合并入「其他」) -->
              <div class="cma-member-block">
                <div class="cma-member-block-title">等级分布</div>
                <div v-if="memberTierDistribution.length" class="cma-rank-list">
                  <div v-for="t in memberTierDistribution" :key="t.tier" class="cma-rank-item">
                    <span class="cma-rank-name" :title="t.tier">{{ t.tier }}</span>
                    <span class="cma-rank-bar-wrap">
                      <span class="cma-rank-bar" :style="{ width: tierPct(t.memberCount) + '%' }"></span>
                    </span>
                    <span class="cma-rank-val">{{ t.memberCount }}人</span>
                    <span class="cma-rank-pct">{{ formatMoney(t.totalBalance) }}</span>
                  </div>
                </div>
                <el-empty v-else description="暂无等级数据" :image-size="40" />
              </div>

              <!-- 性别分布 -->
              <div class="cma-member-block">
                <div class="cma-member-block-title">性别分布 <span class="cma-member-block-hint">(性别画像)</span></div>
                <div v-if="memberGenderDistribution.length" class="cma-rank-list">
                  <div v-for="g in memberGenderDistribution" :key="g.gender" class="cma-rank-item">
                    <span class="cma-rank-name" :title="g.gender">{{ g.gender }}</span>
                    <span class="cma-rank-bar-wrap">
                      <span class="cma-rank-bar cma-chan-bar" :style="{ width: genderPct(g.memberCount) + '%' }"></span>
                    </span>
                    <span class="cma-rank-val">{{ g.memberCount }}人</span>
                  </div>
                </div>
                <el-empty v-else description="暂无性别数据" :image-size="40" />
              </div>

              <!-- 生日月份分布 (生日营销 hook) -->
              <div class="cma-member-block">
                <div class="cma-member-block-title">
                  生日月份分布 <span class="cma-member-block-hint">(生日营销参考)</span>
                </div>
                <div v-if="memberBirthCoveragePct !== null" class="cma-member-coverage">
                  生日覆盖率 {{ memberBirthCoveragePct.toFixed(0) }}%（{{ memberBirthUnknownCount.toLocaleString() }} 名会员未填生日）
                </div>
                <div v-if="memberBirthMonthDistribution.length" class="cma-birth-bars">
                  <div v-for="m in memberBirthMonthDistribution" :key="m.birthMonth" class="cma-birth-bar-col">
                    <div class="cma-birth-bar-wrap">
                      <div class="cma-birth-bar" :style="{ height: birthMonthPct(m.memberCount) + '%' }" :title="`${m.birthMonth}月: ${m.memberCount}人`"></div>
                    </div>
                    <span class="cma-birth-bar-label">{{ m.birthMonth }}月</span>
                  </div>
                </div>
                <el-empty v-else description="暂无生日数据" :image-size="40" />
              </div>

              <!-- 充值趋势 -->
              <div class="cma-member-block">
                <div class="cma-member-block-title">充值趋势 (本金/赠送)</div>
                <div v-if="memberRechargeTrend.length && memberRechargeStoreCount >= 1" class="cma-member-coverage">
                  仅 {{ memberRechargeStoreCount }} 家门店有充值记录（部分门店未开通储值）
                </div>
                <el-table v-if="memberRechargeTrend.length" :data="memberRechargeTrend" size="small" stripe>
                  <el-table-column prop="month" label="月份" width="90" />
                  <el-table-column label="本金" align="right">
                    <template #default="{ row }">{{ formatMoney(row.principal) }}</template>
                  </el-table-column>
                  <el-table-column label="赠送" align="right">
                    <template #default="{ row }">{{ formatMoney(row.bonus) }}</template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="暂无充值趋势数据" :image-size="40" />
              </div>
            </div>
            <!-- 禁降级: 本维度不是完整 RFM (画像层), 明确提示 + k-anon 说明 -->
            <div class="cma-caveat-inline">{{ profileCaveat }}</div>
            <ChartInsights
              :bullets="profileBulletsComputed"
              chart-key="profile"
              chart-title="会员画像"
              :data-summary="profileSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty v-else :description="profileNote || '未上传会员数据'" :image-size="60" />
        </el-card>
      </div>

      <div class="cma-side">
        <AnalysisChatPanel ref="analysisChatPanelRef" :contexts="analysisContexts" page-title="会员分析" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  Refresh,
  User,
  TrendCharts,
  PieChart,
  Grid,
  MagicStick,
  InfoFilled,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import echarts from '@/utils/echarts';
import type { ECharts as EChartsInstance } from 'echarts/core';
import { getMemberRfm, getMemberProfile, type MemberRfm, type MemberProfile } from '@/api/smartbi/gold';
import { formatNumber } from '@/utils/format-number';
import ChartInsights from '@/views/smart-bi/components/analysis/ChartInsights.vue';
import AnalysisChatPanel from '@/views/smart-bi/components/analysis/AnalysisChatPanel.vue';
import { useCountUp } from '@/composables/useCountUp';
import { vReveal } from '@/composables/useReveal';
import {
  memberRfmTierBullets,
  memberRfmTierSummary,
  lifecycleBullets,
  lifecycleSummary,
  rfmScatterBullets,
  rfmScatterSummary,
  memberProfileBullets,
  memberProfileSummary,
  type AnalysisChartContext,
} from '@/views/smart-bi/components/analysis/analysisBullets';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

// ============================================================
// English RFM tier → 中文业务标签 (客户能看懂的说法, 不是技术术语)
// ============================================================
const TIER_LABELS: Record<string, string> = {
  Champions: '重要价值客户',
  Loyal: '重要保持客户',
  Potential: '重要发展客户',
  New: '新客户',
  'At Risk': '重要挽留客户',
  Hibernating: '一般维持客户',
  Lost: '流失客户',
};
// 与 RestaurantV2Dashboard.vue 的 segColors 保持一致 (同一套 RFM 语义, 视觉统一)
const TIER_COLORS: Record<string, string> = {
  Champions: '#67c23a',
  Loyal: '#409eff',
  Potential: '#36b37e',
  New: '#4C9AFF',
  'At Risk': '#e6a23c',
  Hibernating: '#909399',
  Lost: '#f56c6c',
};
function tierLabel(tier: string): string {
  return TIER_LABELS[tier] || tier;
}
function tierColor(tier: string): string {
  return TIER_COLORS[tier] || '#6B778C';
}

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return '¥' + formatNumber(v);
}

// ============================================================
// RFM (getMemberRfm) — isolated fetch/loading/error, all-time snapshot
// ============================================================
const rfmLoading = ref(false);
const rfmError = ref('');
const rfmDataAvailable = ref(false);
const memberCount = ref(0);
const rfmNote = ref<string | null>(null);
const rfmCaveat = ref('');
const rfmTierDistribution = ref<MemberRfm['rfmTierDistribution']>([]);
const lifecycleDistribution = ref<MemberRfm['lifecycleDistribution']>([]);
const rfmScatter = ref<MemberRfm['rfmScatter']>([]);
const rfmScatterSuppressedCount = ref(0);

const activeCount = computed<number | null>(() => {
  const row = lifecycleDistribution.value.find((l) => l.lifecycleStage === '活跃');
  return row ? row.memberCount : null;
});
const dormantCount = computed<number | null>(() => {
  const row = lifecycleDistribution.value.find((l) => l.lifecycleStage === '沉睡');
  return row ? row.memberCount : null;
});
const churnedCount = computed<number | null>(() => {
  const row = lifecycleDistribution.value.find((l) => l.lifecycleStage === '流失');
  return row ? row.memberCount : null;
});

// 平均消费间隔: 按人数加权跨 tier 平均 (后端只给逐 tier 均值, 前端算总体口径)。
// 只用非 null 且 memberCount>0 的行, 全部缺失时诚实显示 "—"。
const avgSpendIntervalOverall = computed<number | null>(() => {
  const rows = rfmTierDistribution.value.filter(
    (t) => t.avgSpendInterval != null && t.memberCount > 0,
  );
  if (!rows.length) return null;
  const totalMembers = rows.reduce((s, t) => s + t.memberCount, 0);
  if (totalMembers <= 0) return null;
  const weighted = rows.reduce((s, t) => s + (t.avgSpendInterval as number) * t.memberCount, 0);
  return weighted / totalMembers;
});
const avgSpendIntervalLabel = computed(() => {
  const v = avgSpendIntervalOverall.value;
  return v == null ? '—' : `${v.toFixed(0)}天`;
});

// ============================================================
// 总览条 KPI count-up (GSAP, once on first RFM load — not on 刷新 refetch)
// Gated on rfmLoading (not memberCount's raw value) because memberCount/等
// default to ref(0) pre-fetch — watching "first non-null" alone would treat
// that placeholder 0 as "data arrived" and burn the once-only animation.
// ============================================================
const { display: memberCountDisplay } = useCountUp(memberCount, {
  loading: rfmLoading,
  formatter: (v) => Math.round(v).toLocaleString(),
});
const { display: activeCountDisplay } = useCountUp(activeCount, {
  loading: rfmLoading,
  formatter: (v) => Math.round(v).toLocaleString(),
});
const { display: dormantCountDisplay } = useCountUp(dormantCount, {
  loading: rfmLoading,
  formatter: (v) => Math.round(v).toLocaleString(),
});
const { display: churnedCountDisplay } = useCountUp(churnedCount, {
  loading: rfmLoading,
  formatter: (v) => Math.round(v).toLocaleString(),
});
const { display: avgSpendIntervalDisplay } = useCountUp(avgSpendIntervalOverall, {
  loading: rfmLoading,
  formatter: (v) => `${Math.round(v)}天`,
});

// 规则洞察 (0 LLM) — 从真实 RFM 数字算一句话; 数据不足返 null (不编)。
// 优先高亮"重要挽留客户" (At Risk): 高消费 + 已进入挽留窗口 = 唤回优先级最高。
const rfmInsight = computed<string | null>(() => {
  const rows = rfmTierDistribution.value;
  if (!rows.length) return null;
  const atRisk = rows.find((t) => t.rfmTier === 'At Risk');
  if (atRisk && atRisk.memberCount > 0) {
    const spend = atRisk.totalCumSpend;
    return spend != null
      ? `重要挽留客户共 ${atRisk.memberCount} 人，历史累计消费 ¥${formatNumber(spend)} — 消费能力可观但已进入挽留窗口，建议优先精准营销唤回`
      : `重要挽留客户共 ${atRisk.memberCount} 人 — 已进入挽留窗口，建议优先精准营销唤回`;
  }
  // fallback: 高亮价值最高的客群 (按 totalCumSpend 排序, 忽略 null)
  const byValue = rows
    .filter((t) => t.totalCumSpend != null && t.memberCount > 0)
    .sort((a, b) => (b.totalCumSpend as number) - (a.totalCumSpend as number));
  if (byValue.length) {
    const top = byValue[0];
    return `${tierLabel(top.rfmTier)}共 ${top.memberCount} 人，贡献历史累计消费 ¥${formatNumber(top.totalCumSpend)}，是当前最具价值的客群`;
  }
  return null;
});

async function loadRfm() {
  if (!factoryId.value) return;
  rfmLoading.value = true;
  rfmError.value = '';
  try {
    const res = await getMemberRfm({ factoryId: factoryId.value });
    rfmDataAvailable.value = res.dataAvailable ?? false;
    memberCount.value = res.memberCount ?? 0;
    rfmNote.value = res.note ?? null;
    rfmCaveat.value = res.caveat ?? '';
    rfmTierDistribution.value = res.rfmTierDistribution ?? [];
    lifecycleDistribution.value = res.lifecycleDistribution ?? [];
    rfmScatter.value = res.rfmScatter ?? [];
    rfmScatterSuppressedCount.value = res.rfmScatterSuppressedCount ?? 0;
  } catch (e) {
    // 禁降级假数据: 失败显式报错, 不伪造
    rfmError.value = e instanceof Error ? e.message : 'RFM 数据加载失败';
    rfmDataAvailable.value = false;
    rfmTierDistribution.value = [];
    lifecycleDistribution.value = [];
    rfmScatter.value = [];
  } finally {
    rfmLoading.value = false;
  }
}

// ============================================================
// 会员画像 (getMemberProfile) — 从经营驾驶舱迁移, 独立加载, 与 RFM 请求彻底隔离
// (member 500 不连累 RFM 卡, 反之亦然 — 历史教训: 一个 Promise.all 500 会拖垮整页)
// ============================================================
const profileLoading = ref(false);
const profileError = ref('');
const profileDataAvailable = ref(false);
const profileMemberCount = ref(0);
const profileTotalBalance = ref<number | null>(null);
const profileNote = ref<string | null>(null);
const profileCaveat = ref('');
const memberTierDistribution = ref<MemberProfile['tierDistribution']>([]);
const memberGenderDistribution = ref<MemberProfile['genderDistribution']>([]);
const memberBirthMonthDistribution = ref<MemberProfile['birthMonthDistribution']>([]);
const memberBirthUnknownCount = ref(0);
const memberBirthCoveragePct = ref<number | null>(null);
const memberRechargeTrend = ref<MemberProfile['rechargeTrend']>([]);
const memberRechargeStoreCount = ref(0);

const maxTierMemberCount = computed(() =>
  memberTierDistribution.value.reduce((m, t) => Math.max(m, t.memberCount), 0),
);
function tierPct(count: number): number {
  return maxTierMemberCount.value > 0 ? (count / maxTierMemberCount.value) * 100 : 0;
}

const maxGenderMemberCount = computed(() =>
  memberGenderDistribution.value.reduce((m, g) => Math.max(m, g.memberCount), 0),
);
function genderPct(count: number): number {
  return maxGenderMemberCount.value > 0 ? (count / maxGenderMemberCount.value) * 100 : 0;
}

const maxBirthMonthCount = computed(() =>
  memberBirthMonthDistribution.value.reduce((m, b) => Math.max(m, b.memberCount), 0),
);
function birthMonthPct(count: number): number {
  return maxBirthMonthCount.value > 0 ? Math.max((count / maxBirthMonthCount.value) * 100, 4) : 4;
}

async function loadProfile() {
  if (!factoryId.value) return;
  profileLoading.value = true;
  profileError.value = '';
  try {
    // Dates omitted = 全部历史 (tier/gender/birthMonth 本就是 all-time snapshot,
    // rechargeTrend 也取全部历史以匹配 RFM 的 all-time 口径)。
    const res = await getMemberProfile({ factoryId: factoryId.value });
    profileDataAvailable.value = res.dataAvailable ?? false;
    profileMemberCount.value = res.memberCount ?? 0;
    profileTotalBalance.value = res.totalBalance ?? null;
    profileNote.value = res.note ?? null;
    profileCaveat.value = res.caveat ?? '';
    memberTierDistribution.value = res.tierDistribution ?? [];
    memberGenderDistribution.value = res.genderDistribution ?? [];
    memberBirthMonthDistribution.value = res.birthMonthDistribution ?? [];
    memberBirthUnknownCount.value = res.birthMonthUnknownCount ?? 0;
    memberBirthCoveragePct.value = res.birthMonthCoveragePct ?? null;
    memberRechargeTrend.value = res.rechargeTrend ?? [];
    memberRechargeStoreCount.value = res.rechargeStoreCount ?? 0;
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : '会员画像加载失败';
    profileDataAvailable.value = false;
    memberTierDistribution.value = [];
    memberGenderDistribution.value = [];
    memberBirthMonthDistribution.value = [];
    memberRechargeTrend.value = [];
  } finally {
    profileLoading.value = false;
  }
}

function loadAll() {
  // 两个请求各自 try/catch, 互不阻塞 (Promise.allSettled 语义 — 不用 Promise.all,
  // 避免一个 reject 影响另一个的 UI 更新时机)。
  void loadRfm();
  void loadProfile();
}

// ============================================================
// 混合式图表分析(bullets + AI 解读 + 整页 AI 分析面板) — 2026-07-12
// 每张图: 确定性 bullets (analysisBullets.ts, 0 LLM) + ChartInsights.vue
// 承载的按需 AI 解读。页面级 AnalysisChatPanel 消费同一批 dataSummary 作为
// 聚焦上下文 (grounding trick) — 全部真实数字来自上面已加载的 refs, 不编造。
// ============================================================
const rfmTierBullets = computed(() => memberRfmTierBullets(rfmTierDistribution.value));
const rfmTierSummaryStr = computed(() => memberRfmTierSummary(rfmTierDistribution.value));

const scatterBullets = computed(() => rfmScatterBullets(rfmScatter.value, rfmScatterSuppressedCount.value));
const scatterSummaryStr = computed(() => rfmScatterSummary(rfmScatter.value, rfmScatterSuppressedCount.value));

const lifecycleBulletsComputed = computed(() => lifecycleBullets(lifecycleDistribution.value));
const lifecycleSummaryStr = computed(() => lifecycleSummary(lifecycleDistribution.value));

const profileBulletsInput = computed(() => ({
  memberCount: profileMemberCount.value,
  totalBalance: profileTotalBalance.value,
  tierDistribution: memberTierDistribution.value,
  genderDistribution: memberGenderDistribution.value,
  birthMonthDistribution: memberBirthMonthDistribution.value,
  birthMonthCoveragePct: memberBirthCoveragePct.value,
  birthMonthUnknownCount: memberBirthUnknownCount.value,
  rechargeTrend: memberRechargeTrend.value,
  rechargeStoreCount: memberRechargeStoreCount.value,
}));
const profileBulletsComputed = computed(() => memberProfileBullets(profileBulletsInput.value));
const profileSummaryStr = computed(() => memberProfileSummary(profileBulletsInput.value));

const analysisContexts = computed<AnalysisChartContext[]>(() => [
  { key: 'rfm-tier', title: 'RFM 客群分层', dataSummary: rfmTierSummaryStr.value },
  { key: 'rfm-scatter', title: 'RFM 三维分布', dataSummary: scatterSummaryStr.value },
  { key: 'lifecycle', title: '会员生命周期', dataSummary: lifecycleSummaryStr.value },
  { key: 'profile', title: '会员画像', dataSummary: profileSummaryStr.value },
]);

const analysisChatPanelRef = ref<InstanceType<typeof AnalysisChatPanel> | null>(null);
function focusChart(key: string) {
  analysisChatPanelRef.value?.setFocus(key);
}

// ============================================================
// ECharts — RFM 客群分层横向柱状图 / RFM 三维散点 / 生命周期环形图
// ============================================================
const rfmBarChartRef = ref<HTMLDivElement | null>(null);
const rfmScatterChartRef = ref<HTMLDivElement | null>(null);
const lifecycleChartRef = ref<HTMLDivElement | null>(null);

let rfmBarChart: EChartsInstance | null = null;
let rfmScatterChart: EChartsInstance | null = null;
let lifecycleChart: EChartsInstance | null = null;

function disposeChart(instance: EChartsInstance | null): null {
  if (instance && !instance.isDisposed?.()) instance.dispose();
  return null;
}

function handleChartsResize() {
  rfmBarChart?.resize();
  rfmScatterChart?.resize();
  lifecycleChart?.resize();
}

function renderRfmBarChart() {
  if (!rfmBarChartRef.value || !rfmTierDistribution.value.length) return;
  rfmBarChart = disposeChart(rfmBarChart);
  rfmBarChart = echarts.init(rfmBarChartRef.value, 'cretas');

  // 按人数降序排列 (客户最关心的排序: 群体规模从大到小)
  const rows = [...rfmTierDistribution.value].sort((a, b) => b.memberCount - a.memberCount);
  const labels = rows.map((r) => tierLabel(r.rfmTier));
  const counts = rows.map((r) => r.memberCount);
  const spends = rows.map((r) => r.totalCumSpend);
  const colors = rows.map((r) => tierColor(r.rfmTier));

  rfmBarChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: any) => {
        const p = Array.isArray(params) ? params[0] : params;
        const idx = p.dataIndex;
        const spend = spends[idx];
        const spendStr = spend != null ? `¥${Number(spend).toLocaleString()}` : '—';
        return `<strong>${labels[idx]}</strong><br/>人数: ${counts[idx]}<br/>累计消费: ${spendStr}`;
      },
    },
    grid: { left: 120, right: 30, top: 10, bottom: 20, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: labels, inverse: true },
    series: [
      {
        type: 'bar',
        data: counts.map((c, i) => ({ value: c, itemStyle: { color: colors[i] } })),
        barMaxWidth: 28,
        label: { show: true, position: 'right', formatter: '{c}人' },
      },
    ],
  });
}

function renderRfmScatterChart() {
  if (!rfmScatterChartRef.value || !rfmScatter.value.length) return;
  rfmScatterChart = disposeChart(rfmScatterChart);
  rfmScatterChart = echarts.init(rfmScatterChartRef.value, 'cretas');

  const rows = rfmScatter.value;
  const maxCount = Math.max(...rows.map((r) => r.memberCount), 1);
  const data = rows.map((r) => [r.rScore, r.fScore, r.memberCount, r.mScore, r.avgCumSpend]);

  rfmScatterChart.setOption({
    tooltip: {
      formatter: (p: any) => {
        const [r, f, count, m, avgSpend] = p.data;
        const spendStr = avgSpend != null ? `¥${Number(avgSpend).toLocaleString()}` : '—';
        return `R分: ${r} · F分: ${f} · M分: ${m}<br/>人数: ${count}<br/>均消费: ${spendStr}`;
      },
    },
    grid: { left: 50, right: 30, top: 20, bottom: 40, containLabel: true },
    xAxis: {
      type: 'value',
      name: 'R 分 (最近消费)',
      min: 0.5,
      max: 5.5,
      interval: 1,
      nameLocation: 'middle',
      nameGap: 28,
    },
    yAxis: {
      type: 'value',
      name: 'F 分 (消费频次)',
      min: 0.5,
      max: 5.5,
      interval: 1,
      nameLocation: 'middle',
      nameGap: 32,
    },
    visualMap: {
      dimension: 3,
      min: 1,
      max: 5,
      calculable: true,
      orient: 'vertical',
      right: 0,
      top: 'center',
      text: ['M分高', 'M分低'],
      inRange: { color: ['#f0f9eb', '#b3e19d', '#67c23a', '#e6a23c', '#f56c6c'] },
    },
    series: [
      {
        type: 'scatter',
        data,
        symbolSize: (val: number[]) => 8 + (val[2] / maxCount) * 40,
        itemStyle: { opacity: 0.75 },
      },
    ],
  });
}

function renderLifecycleChart() {
  if (!lifecycleChartRef.value || !lifecycleDistribution.value.length) return;
  lifecycleChart = disposeChart(lifecycleChart);
  lifecycleChart = echarts.init(lifecycleChartRef.value, 'cretas');

  const LIFECYCLE_COLORS: Record<string, string> = {
    活跃: '#67c23a',
    沉睡: '#e6a23c',
    流失: '#909399',
  };
  const data = lifecycleDistribution.value.map((row) => ({
    name: row.lifecycleStage,
    value: row.memberCount,
    itemStyle: { color: LIFECYCLE_COLORS[row.lifecycleStage] },
  }));

  lifecycleChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)',
    },
    legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}: {c}', fontSize: 11 },
        data,
      },
    ],
  });
}

watch(rfmTierDistribution, () => nextTick(renderRfmBarChart));
watch(rfmScatter, () => nextTick(renderRfmScatterChart));
watch(lifecycleDistribution, () => nextTick(renderLifecycleChart));

onMounted(() => {
  loadAll();
  window.addEventListener('resize', handleChartsResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleChartsResize);
  rfmBarChart = disposeChart(rfmBarChart);
  rfmScatterChart = disposeChart(rfmScatterChart);
  lifecycleChart = disposeChart(lifecycleChart);
});
</script>

<style scoped>
.cma-page { padding: 16px; }
.cma-header { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.cma-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 600; color: #303133; }
.cma-subtitle { font-size: 12px; font-weight: 400; color: #909399; margin-left: 4px; }

.cma-overview {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
@media (max-width: 1200px) { .cma-overview { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) { .cma-overview { grid-template-columns: repeat(2, 1fr); } }
.cma-kpi {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 8px;
  background: #f7f8fa;
  border-radius: 8px;
}
.cma-kpi-val { font-size: 24px; font-weight: 700; color: #303133; }
.cma-kpi-label { font-size: 12px; color: #909399; }
.cma-kpi-active { color: #67c23a; }
.cma-kpi-dormant { color: #e6a23c; }
.cma-kpi-churned { color: #909399; }

/* 主列(图表卡片) + 侧列(AI 分析面板) 响应式布局 */
.cma-layout { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
@media (max-width: 1280px) { .cma-layout { grid-template-columns: 1fr; } }
.cma-main { min-width: 0; }
.cma-side { position: sticky; top: 16px; }
@media (max-width: 1280px) { .cma-side { position: static; } }

.cma-card { margin-bottom: 16px; }
.cma-hero { min-height: 120px; }
.cma-card-header { display: flex; align-items: center; justify-content: space-between; gap: 6px; font-weight: 600; }
.cma-card-header-left { display: flex; align-items: center; gap: 6px; }
.cma-chart { width: 100%; }
.cma-inline-error { color: #f56c6c; font-size: 13px; padding: 8px 0; }

.cma-insight {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: #1f6f54;
  background: #f0f9f4;
  border: 1px solid #d4ecdd;
  border-radius: 6px;
}
.cma-insight-icon { color: #2d8b57; flex-shrink: 0; }

.cma-est-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 10px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
  background: #f4f4f5;
  border-radius: 4px;
}
.cma-caveat { margin: -4px 0 16px; font-size: 12px; color: #909399; line-height: 1.5; }
.cma-caveat-inline { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.5; }

.cma-lifecycle-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: center; }
@media (max-width: 992px) { .cma-lifecycle-grid { grid-template-columns: 1fr; } }
.cma-lifecycle-table { align-self: center; }

.cma-profile-kpi { display: flex; gap: 24px; margin-bottom: 16px; }
.cma-profile-kpi .cma-kpi { min-width: 120px; }

/* 会员画像明细 — 3-column detail grid (原 RestaurantGoldGrid rgg-* 迁移) */
.cma-member-detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }
@media (max-width: 1200px) { .cma-member-detail-grid { grid-template-columns: 1fr; } }
.cma-member-block { display: flex; flex-direction: column; gap: 8px; }
.cma-member-block-title { font-size: 13px; font-weight: 600; color: #303133; }
.cma-member-block-hint { font-size: 11px; font-weight: 400; color: #909399; }
.cma-member-coverage { font-size: 11px; color: #e6a23c; margin: -2px 0 2px; }

.cma-rank-list { display: flex; flex-direction: column; gap: 10px; }
.cma-rank-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.cma-rank-name { width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.cma-rank-bar-wrap { flex: 1; height: 8px; background: #f5f7fa; border-radius: 4px; overflow: hidden; }
.cma-rank-bar { display: block; height: 100%; background: linear-gradient(90deg, #5470c6, #91cc75); border-radius: 4px; }
.cma-chan-bar { background: linear-gradient(90deg, #fac858, #ee6666); }
.cma-rank-val { width: 60px; text-align: right; font-weight: 600; color: #303133; flex-shrink: 0; }
.cma-rank-pct { width: 96px; text-align: right; color: #606266; flex-shrink: 0; }

.cma-birth-bars { display: flex; align-items: flex-end; gap: 4px; height: 100px; }
.cma-birth-bar-col { display: flex; flex-direction: column; align-items: center; flex: 1; height: 100%; }
.cma-birth-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.cma-birth-bar { width: 100%; min-height: 4px; background: linear-gradient(180deg, #91cc75, #5470c6); border-radius: 3px 3px 0 0; }
.cma-birth-bar-label { font-size: 10px; color: #909399; margin-top: 4px; }
</style>
