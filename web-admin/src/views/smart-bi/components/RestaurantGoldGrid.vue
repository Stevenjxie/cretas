<template>
  <!--
    餐饮经营驾驶舱 — Gold 营收分析区 (May 29 2026)
    替代通用 schema-driven 模板卡 (对青花椒会误选"星级分" → 累计 SUM bug + 老板不关心)。
    展示老板每天最在乎的 Gold 全量真营收: 门店营收排行 + 堂食外卖渠道占比。
    数据源 gold.ts (getFinanceSummary / getChannelBreakdown), 全量 agg_daily, 不依赖上传。
    业态门控: 仅 RESTAURANT 渲染 (父组件 v-if), 制造业仍用 TemplateGrid。
  -->
  <section class="rgg-section">
    <header class="rgg-header">
      <span class="rgg-title-text">餐饮营收分析</span>
      <span class="rgg-subtitle">基于全量经营数据 · {{ dateLabel }}</span>
      <el-button v-if="!loading" size="small" type="primary" plain @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <div v-if="loadError" class="rgg-error">
      加载营收分析失败: {{ loadError }}
    </div>

    <!--
      WS2 #7: 规则洞察条 (0 LLM). 仅在有数据可算时渲染 (revenueInsight 非空);
      数据不足或加载中不显示 (诚实, 不编).
    -->
    <div v-if="!loadError && !loading && revenueInsight" class="rgg-insight">
      <el-icon class="rgg-insight-icon"><MagicStick /></el-icon>
      <span>{{ revenueInsight }}</span>
    </div>

    <div v-if="!loadError" class="rgg-grid">
      <!-- 门店营收排行 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><Sell /></el-icon><span>门店营收排行</span></div>
        </template>
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="topStores.length" class="rgg-rank-list">
          <div v-for="(s, i) in topStores" :key="s.storeId" class="rgg-rank-item">
            <span class="rgg-rank-no" :class="'rgg-rank-' + Math.min(i, 3)">{{ i + 1 }}</span>
            <span class="rgg-rank-name" :title="s.storeName">{{ s.storeName }}</span>
            <span class="rgg-rank-bar-wrap">
              <span class="rgg-rank-bar" :style="{ width: storePct(s.revenue) + '%' }"></span>
            </span>
            <span class="rgg-rank-val">{{ formatMoney(s.revenue) }}</span>
          </div>
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight
            :insight="storeInsight"
            :loading="storeInsightLoading"
            depth="detailed"
          />
        </div>
        <el-empty v-else description="暂无门店营收数据" :image-size="60" />
      </el-card>

      <!-- 堂食 / 外卖 / 渠道占比 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><PieChart /></el-icon><span>支付渠道占比</span></div>
        </template>
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="channels.length" class="rgg-rank-list">
          <div v-for="(c, i) in channels" :key="c.channelId" class="rgg-rank-item">
            <span class="rgg-rank-name rgg-chan-name" :title="c.channelName">{{ c.channelName }}</span>
            <span class="rgg-rank-bar-wrap">
              <span class="rgg-rank-bar rgg-chan-bar" :style="{ width: Math.min(c.sharePct, 100) + '%' }"></span>
            </span>
            <span class="rgg-rank-pct">{{ c.sharePct.toFixed(1) }}%</span>
            <span class="rgg-rank-val">{{ formatMoney(c.amount) }}</span>
          </div>
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight
            :insight="channelInsight"
            :loading="channelInsightLoading"
            depth="detailed"
          />
        </div>
        <el-empty v-else description="暂无渠道数据" :image-size="60" />
      </el-card>

      <!-- 堂食 / 外卖占比 (order_type mix, 独立于上面的支付渠道) -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><KnifeFork /></el-icon><span>堂食外卖占比</span></div>
        </template>
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="orderTypes.length" class="rgg-rank-list">
          <div v-for="ot in orderTypes" :key="ot.orderType" class="rgg-rank-item">
            <span class="rgg-rank-name rgg-chan-name" :title="ot.orderType">{{ ot.orderType }}</span>
            <span class="rgg-rank-bar-wrap">
              <span class="rgg-rank-bar rgg-dine-bar" :style="{ width: Math.min(ot.revenuePct ?? 0, 100) + '%' }"></span>
            </span>
            <span class="rgg-rank-pct">{{ formatPct(ot.revenuePct) }}</span>
            <span class="rgg-rank-val">{{ formatMoney(ot.revenue) }}</span>
            <span class="rgg-rank-bills">{{ ot.billCount }}单</span>
          </div>
          <!-- 禁降级: 金额由后端全店平均客单价估算时诚实标注, 不假装精确 -->
          <div v-if="orderTypeEstimated" class="rgg-est-note">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ orderTypeEstimationNote || '金额为估算值，仅用于结构参考' }}</span>
          </div>
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight
            :insight="orderTypeInsight"
            :loading="orderTypeInsightLoading"
            depth="detailed"
          />
        </div>
        <el-empty v-else description="暂无堂食/外卖数据" :image-size="60" />
      </el-card>

      <!-- 撤单率 (新增维度, greenfield) — KPI tile. 独立加载 (voidLoading/voidError),
           void 500 不连累其它 3 卡. voidRate 为 null 时: 未上传→"未上传撤单数据",
           样本不足→显示后端 note; 绝不伪造 0.00%。 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><WarningFilled /></el-icon><span>撤单率</span></div>
        </template>
        <el-skeleton v-if="voidLoading" :rows="3" animated />
        <div v-else-if="voidError" class="rgg-void-inline-error">撤单数据加载失败: {{ voidError }}</div>
        <div v-else-if="voidRate !== null" class="rgg-void-kpi">
          <div class="rgg-void-rate">
            <span class="rgg-void-rate-val">{{ formatPct(voidRate) }}</span>
            <span class="rgg-void-rate-label">撤单率</span>
          </div>
          <div class="rgg-void-subs">
            <div class="rgg-void-sub"><span class="rgg-void-sub-label">撤单数</span><span class="rgg-void-sub-val">{{ voidCount }}</span></div>
            <div class="rgg-void-sub"><span class="rgg-void-sub-label">开单总数</span><span class="rgg-void-sub-val">{{ voidBillCount.toLocaleString() }}</span></div>
          </div>
        </div>
        <!-- 未上传撤单数据 或 样本不足 — 诚实 empty-state, 不显示 0.00% -->
        <el-empty
          v-else
          :description="voidDataAvailable ? (voidNote || '订单样本不足，暂不计算撤单率') : '未上传撤单数据'"
          :image-size="60"
        />
      </el-card>

      <!-- 会员储值 + 画像 (新增维度, greenfield) — KPI tile. 独立加载
           (memberLoading/memberError), member 500 不连累其它卡。⚠️ 本维度不是
           完整 RFM (数据源无逐笔消费记录, 无法算复购间隔/频次) — 明细卡里
           显式提示。 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><User /></el-icon><span>会员储值 + 画像</span></div>
        </template>
        <el-skeleton v-if="memberLoading" :rows="3" animated />
        <div v-else-if="memberError" class="rgg-void-inline-error">会员数据加载失败: {{ memberError }}</div>
        <div v-else-if="memberDataAvailable" class="rgg-void-kpi">
          <div class="rgg-void-rate">
            <span class="rgg-void-rate-val">{{ memberCount.toLocaleString() }}</span>
            <span class="rgg-void-rate-label">会员数</span>
          </div>
          <div class="rgg-void-subs">
            <div class="rgg-void-sub"><span class="rgg-void-sub-label">储值总额</span><span class="rgg-void-sub-val">{{ formatMoney(memberTotalBalance) }}</span></div>
          </div>
        </div>
        <!-- 未上传会员数据 — 诚实 empty-state -->
        <el-empty v-else :description="memberNote || '未上传会员数据'" :image-size="60" />
      </el-card>
    </div>

    <!-- 撤单稽核 (操作人 × 门店, 按每百单撤单率排序) — full-width row below the grid.
         与 KPI 共用同一次 getVoidRate() 请求. 按 staff_id 分组(非同名合并) +
         每百单撤单率(非撤单总数)排序, 避免冤枉授权量大的收银主管/店长。 -->
    <el-card v-if="!voidError && voidBreakdown.length" class="rgg-audit-card" shadow="never">
      <template #header>
        <div class="rgg-card-header"><el-icon><Search /></el-icon><span>撤单稽核 · 操作人 Top {{ voidBreakdown.length }} (按每百单撤单率)</span></div>
      </template>
      <el-skeleton v-if="voidLoading" :rows="4" animated />
      <template v-else>
        <el-table :data="voidBreakdown" size="small" stripe>
          <el-table-column prop="staffName" label="操作人" width="140" />
          <el-table-column prop="storeName" label="门店" min-width="140" show-overflow-tooltip />
          <el-table-column label="主要原因" width="130">
            <template #default="{ row }">
              <span v-if="row.topReason">{{ row.topReason }}</span>
              <span v-else class="rgg-void-unlabeled">未标注</span>
            </template>
          </el-table-column>
          <el-table-column prop="voidCount" label="撤单次数" width="90" align="right" />
          <el-table-column label="每百单撤单" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.voidsPer100Bills !== null">{{ formatPct(row.voidsPer100Bills) }}</span>
              <span v-else class="rgg-void-unlabeled" title="该操作人无经手订单记录，无法计算率">—</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="rgg-void-caveat">{{ voidCaveat }}</div>
      </template>
    </el-card>

    <!-- 区域坪效 (新增维度, greenfield) — 独立加载 (zoneLoading/zoneError),
         zone 500 不连累其它卡。数据为营收/数量代理指标 (源数据无场地面积字段,
         无法算真实元/平米坪效), caveat 诚实转述, 不伪造。 -->
    <el-card v-if="!loadError" class="rgg-zone-card" shadow="never">
      <template #header>
        <div class="rgg-card-header"><el-icon><Grid /></el-icon><span>区域坪效</span></div>
      </template>
      <el-skeleton v-if="zoneLoading" :rows="4" animated />
      <div v-else-if="zoneError" class="rgg-void-inline-error">区域数据加载失败: {{ zoneError }}</div>
      <div v-else-if="zones.length" class="rgg-rank-list">
        <div v-for="z in zones" :key="z.zoneName" class="rgg-rank-item">
          <span class="rgg-rank-name rgg-chan-name" :title="z.zoneName">{{ z.zoneName }}</span>
          <span class="rgg-rank-bar-wrap">
            <span class="rgg-rank-bar rgg-zone-bar" :style="{ width: Math.min(z.revenuePct ?? 0, 100) + '%' }"></span>
          </span>
          <span class="rgg-rank-pct">{{ formatPct(z.revenuePct) }}</span>
          <span class="rgg-rank-val">{{ formatMoney(z.revenue) }}</span>
          <span class="rgg-rank-bills">{{ z.itemQty.toLocaleString() }}件</span>
        </div>
        <div class="rgg-est-note">
          <el-icon><InfoFilled /></el-icon>
          <span>{{ zoneCaveat }}</span>
        </div>
      </div>
      <!-- 未上传区域销售数据 — 诚实 empty-state -->
      <el-empty
        v-else
        :description="zoneDataAvailable ? '暂无区域销售数据' : (zoneNote || '未上传区域销售数据')"
        :image-size="60"
      />
    </el-card>

    <!-- 会员画像明细 (等级分布 + 生日月份分布 + 充值趋势) — full-width row.
         与 KPI 共用同一次 getMemberProfile() 请求。 -->
    <el-card v-if="!memberError && memberDataAvailable" class="rgg-audit-card" shadow="never">
      <template #header>
        <div class="rgg-card-header"><el-icon><User /></el-icon><span>会员画像明细</span></div>
      </template>
      <el-skeleton v-if="memberLoading" :rows="4" animated />
      <template v-else>
        <div class="rgg-member-detail-grid">
          <!-- 等级分布 (k-anon: 人数<5 的等级合并入「其他」) -->
          <div class="rgg-member-block">
            <div class="rgg-member-block-title">等级分布</div>
            <div v-if="memberTierDistribution.length" class="rgg-rank-list">
              <div v-for="t in memberTierDistribution" :key="t.tier" class="rgg-rank-item">
                <span class="rgg-rank-name rgg-chan-name" :title="t.tier">{{ t.tier }}</span>
                <span class="rgg-rank-bar-wrap">
                  <span class="rgg-rank-bar" :style="{ width: tierPct(t.memberCount) + '%' }"></span>
                </span>
                <span class="rgg-rank-val">{{ t.memberCount }}人</span>
                <span class="rgg-rank-pct">{{ formatMoney(t.totalBalance) }}</span>
              </div>
            </div>
            <el-empty v-else description="暂无等级数据" :image-size="40" />
          </div>

          <!-- 性别分布 (性别画像, k-anon) -->
          <div class="rgg-member-block">
            <div class="rgg-member-block-title">性别分布 <span class="rgg-member-block-hint">(性别画像)</span></div>
            <div v-if="memberGenderDistribution.length" class="rgg-rank-list">
              <div v-for="g in memberGenderDistribution" :key="g.gender" class="rgg-rank-item">
                <span class="rgg-rank-name rgg-chan-name" :title="g.gender">{{ g.gender }}</span>
                <span class="rgg-rank-bar-wrap">
                  <span class="rgg-rank-bar rgg-chan-bar" :style="{ width: genderPct(g.memberCount) + '%' }"></span>
                </span>
                <span class="rgg-rank-val">{{ g.memberCount }}人</span>
              </div>
            </div>
            <el-empty v-else description="暂无性别数据" :image-size="40" />
          </div>

          <!-- 生日月份分布 (生日营销 hook) -->
          <div class="rgg-member-block">
            <div class="rgg-member-block-title">
              生日月份分布 <span class="rgg-member-block-hint">(生日营销参考)</span>
            </div>
            <!-- F4 honesty: 生日覆盖率 — histogram 只含有生日的会员, 不能被读成完整。 -->
            <div v-if="memberBirthCoveragePct !== null" class="rgg-member-coverage">
              生日覆盖率 {{ memberBirthCoveragePct.toFixed(0) }}%（{{ memberBirthUnknownCount.toLocaleString() }} 名会员未填生日）
            </div>
            <div v-if="memberBirthMonthDistribution.length" class="rgg-birth-bars">
              <div v-for="m in memberBirthMonthDistribution" :key="m.birthMonth" class="rgg-birth-bar-col">
                <div class="rgg-birth-bar-wrap">
                  <div class="rgg-birth-bar" :style="{ height: birthMonthPct(m.memberCount) + '%' }" :title="`${m.birthMonth}月: ${m.memberCount}人`"></div>
                </div>
                <span class="rgg-birth-bar-label">{{ m.birthMonth }}月</span>
              </div>
            </div>
            <el-empty v-else description="暂无生日数据" :image-size="40" />
          </div>

          <!-- 充值趋势 -->
          <div class="rgg-member-block">
            <div class="rgg-member-block-title">充值趋势 (本金/赠送)</div>
            <!-- F4 honesty: 演示数据源只有 1 家门店有充值记录, 明确披露。 -->
            <div v-if="memberRechargeTrend.length && memberRechargeStoreCount >= 1" class="rgg-member-coverage">
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
        <!-- 禁降级: 本维度不是完整 RFM, 明确提示不是复购/流失预测 + k-anon 说明 -->
        <div class="rgg-void-caveat">{{ memberCaveat }}</div>
      </template>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Refresh, Sell, PieChart, KnifeFork, MagicStick, InfoFilled, WarningFilled, Search, Grid, User } from '@element-plus/icons-vue';
import { getFinanceSummary, getChannelBreakdown, getOrderTypeMix, getVoidRate, getZoneEfficiency, getMemberProfile } from '@/api/smartbi/gold';
import { formatNumber } from '@/utils/format-number';
import { buildRevenueInsight } from './revenueInsight';
import type { ChartWithMeta } from './chartInsight';
import { usePermissionStore } from '@/store/modules/permission';
import ChartInsight from './ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const permissionStore = usePermissionStore();

const props = defineProps<{
  factoryId: string;
  /** 日期区间 [start, end] (父组件按 Gold 数据真实区间设置) */
  dateRange: [string, string] | null;
}>();

const loading = ref(false);
const loadError = ref('');
const topStores = ref<Array<{ storeId: number; storeName: string; revenue: number; billCount: number }>>([]);
const channels = ref<Array<{ channelId: number; channelName: string; amount: number; billCount: number; sharePct: number }>>([]);
// revenue/revenuePct nullable: RBAC price-strip nulls any "*revenue*"-named
// leaf for roles outside PRICE_VIEW_ROLES (matches "revenue_pct" too — see
// gold.ts OrderTypeMix comment). billCount is never money-stripped.
const orderTypes = ref<Array<{ orderType: string; revenue: number | null; billCount: number; revenuePct: number | null }>>([]);
// 禁降级: 后端在 order_type 明细金额缺失时会按全店平均客单价估算并显式标注,
// 前端必须诚实转述这个降级说明, 不能悄悄当精确值展示 (fool-proof-design 跨规则铁律)。
const orderTypeEstimated = ref(false);
const orderTypeEstimationNote = ref<string | null>(null);

// 撤单率 / 撤单稽核 (新增维度, greenfield). void_count/void_rate/bill_count 皆非金额,
// 不含 "revenue" 子串 → RBAC price-strip 不置空; voidRate 为 null 时(未上传/样本不足)
// 显示诚实 empty-state 而非伪造 0%。独立于上方 3 卡加载 (MUST-FIX 2): void 500 只
// 降级本区域, 不连累 finance/channel/order-type 卡。
const voidLoading = ref(false);
const voidError = ref('');
const voidRate = ref<number | null>(null);
const voidCount = ref(0);
const voidBillCount = ref(0);
const voidDataAvailable = ref(false);
const voidNote = ref<string | null>(null);
const voidBreakdown = ref<Array<{
  staffName: string;
  storeName: string;
  voidCount: number;
  billsHandled: number;
  voidsPer100Bills: number | null;
  topReason: string | null;
}>>([]);
const voidCaveat = ref('');

// 区域坪效 (新增维度, greenfield). revenue/revenuePct 皆含 "revenue" 子串 —
// RBAC price-strip 对无价格权限角色置 null (与 orderTypes.revenue 同规则),
// 前端必须诚实处理 null (formatMoney/formatPct 已 null-safe)。独立于上方 3+1
// 卡加载: zone 500 只降级本卡, 不连累 finance/channel/order-type/void。
const zoneLoading = ref(false);
const zoneError = ref('');
const zoneDataAvailable = ref(false);
const zoneNote = ref<string | null>(null);
const zones = ref<Array<{
  zoneName: string;
  revenue: number | null;
  itemQty: number;
  revenuePct: number | null;
}>>([]);
const zoneCaveat = ref('');

// 会员储值 + 画像 (新增维度, greenfield). ⚠️ 不是完整 RFM — 数据源(卡详情/
// 卡充值报表)无逐笔消费记录, 无法算复购间隔(Recency)/消费频次(Frequency),
// 仅覆盖储值余额、等级/性别/生日月份分布、充值趋势。后端对人数<5 的分组做
// k-匿名合并。独立于其它卡加载: member 500 只降级本区域, 不连累其它卡。
const memberLoading = ref(false);
const memberError = ref('');
const memberDataAvailable = ref(false);
const memberCount = ref(0);
const memberTotalBalance = ref<number | null>(null);
const memberNote = ref<string | null>(null);
const memberTierDistribution = ref<Array<{ tier: string; memberCount: number; totalBalance: number | null }>>([]);
const memberGenderDistribution = ref<Array<{ gender: string; memberCount: number }>>([]);
const memberBirthMonthDistribution = ref<Array<{ birthMonth: number; memberCount: number }>>([]);
const memberBirthUnknownCount = ref(0);
const memberBirthCoveragePct = ref<number | null>(null);
const memberRechargeTrend = ref<Array<{ month: string; principal: number | null; bonus: number | null }>>([]);
const memberRechargeStoreCount = ref(0);
const memberCaveat = ref('');

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

const dateLabel = computed(() =>
  props.dateRange ? `${props.dateRange[0]} 至 ${props.dateRange[1]}` : '全部数据',
);

// WS2 #7: 规则洞察 (0 LLM) — 从已 fetch 的门店营收 + 渠道占比算一句话; 数据不足返 null。
// UNTOUCHED — leave the top combined insight bar as-is.
const revenueInsight = computed<string | null>(() =>
  buildRevenueInsight(topStores.value, channels.value),
);

const maxStoreRevenue = computed(() =>
  topStores.value.reduce((m, s) => Math.max(m, s.revenue), 0),
);
function storePct(rev: number): number {
  return maxStoreRevenue.value > 0 ? (rev / maxStoreRevenue.value) * 100 : 0;
}

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return '¥' + formatNumber(v);
}

// 禁降级: revenuePct 对无价格权限角色会被后端置 null (见 gold.ts 注释) — 诚实显示
// "—" 而不是让 v.toFixed 抛错或伪造 0%。
function formatPct(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(1) + '%';
}

// ============================================================
// U6: per-card insights via useChartInsight composable
// Explicit meta provided (store/channel) — no deriveChartMeta needed.
// autoTier2=true: Tier1 fires instantly (0 token); null → Tier2 auto.
// ============================================================

/**
 * Reactive source getter for the store ranking card.
 * Returns null when data has not loaded yet (< 2 stores → Tier1 will return null;
 * composable will attempt Tier2 once Tier1-null is observed).
 */
const storeSource = (): { chart: ChartWithMeta } | null => {
  if (topStores.value.length < 1) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: topStores.value.map((s) => s.storeName) },
        series: [{ type: 'bar', data: topStores.value.map((s) => s.revenue) }],
      },
    },
  };
};

/**
 * Reactive source getter for the channel breakdown card.
 * Returns null when data has not loaded yet.
 */
const channelSource = (): { chart: ChartWithMeta } | null => {
  if (channels.value.length < 1) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: channels.value.map((c) => c.channelName) },
        series: [{ type: 'bar', data: channels.value.map((c) => c.amount) }],
      },
    },
  };
};

const permsGetter = () => ({ canViewFinance: permissionStore.canWrite('finance') });

const { insight: storeInsight, loading: storeInsightLoading } = useChartInsight(
  storeSource,
  permsGetter,
  { factoryId: () => props.factoryId, autoTier2: true },
);

const { insight: channelInsight, loading: channelInsightLoading } = useChartInsight(
  channelSource,
  permsGetter,
  { factoryId: () => props.factoryId, autoTier2: true },
);

/**
 * Reactive source getter for the 堂食/外卖 order-type mix card.
 * Returns null when data has not loaded yet.
 */
const orderTypeSourceGetter = (): { chart: ChartWithMeta } | null => {
  if (orderTypes.value.length < 1) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: orderTypes.value.map((o) => o.orderType) },
        series: [{ type: 'bar', data: orderTypes.value.map((o) => o.revenue ?? 0) }],
      },
    },
  };
};

const { insight: orderTypeInsight, loading: orderTypeInsightLoading } = useChartInsight(
  orderTypeSourceGetter,
  permsGetter,
  { factoryId: () => props.factoryId, autoTier2: true },
);

async function load() {
  if (!props.factoryId || !props.dateRange) return;
  loading.value = true;
  loadError.value = '';
  const [startDate, endDate] = props.dateRange;
  const capturedFactoryId = props.factoryId;
  try {
    // 撤单率 is a NEW dimension whose Gold table (agg_daily_void) may not exist
    // yet if web-admin deploys before the Python migration runs → /void-rate 500s.
    // It MUST NOT be in this Promise.all — a void 500 would reject the whole batch
    // and blank the finance/channel/order-type cards too. Loaded separately below
    // so a void failure degrades ONLY the void tile/table.
    const [fin, chan, mix] = await Promise.all([
      getFinanceSummary({ factoryId: capturedFactoryId, startDate, endDate, topNStores: 10 }),
      getChannelBreakdown({ factoryId: capturedFactoryId, startDate, endDate }),
      getOrderTypeMix({ factoryId: capturedFactoryId, startDate, endDate }),
    ]);
    topStores.value = fin.topStores ?? [];
    channels.value = chan.channels ?? [];
    orderTypes.value = mix.orderTypes ?? [];
    orderTypeEstimated.value = mix.revenueEstimated ?? false;
    orderTypeEstimationNote.value = mix.estimationNote ?? null;
    // Insights are computed reactively by useChartInsight watchers —
    // updating topStores/channels/orderTypes above triggers them automatically.
  } catch (e) {
    // 禁降级假数据: 失败显式报错, 不伪造
    loadError.value = e instanceof Error ? e.message : '请求失败';
  } finally {
    loading.value = false;
  }

  // 撤单率/撤单稽核 — isolated so a 500 (e.g. migration not yet applied) degrades
  // ONLY the void surface to an honest error state, leaving the 3 cards above intact.
  voidLoading.value = true;
  voidError.value = '';
  try {
    const voids = await getVoidRate({ factoryId: capturedFactoryId, startDate, endDate, topN: 8 });
    voidRate.value = voids.voidRate ?? null;
    voidCount.value = voids.voidCount ?? 0;
    voidBillCount.value = voids.billCount ?? 0;
    voidDataAvailable.value = voids.dataAvailable ?? false;
    voidNote.value = voids.note ?? null;
    voidBreakdown.value = voids.breakdown ?? [];
    voidCaveat.value = voids.caveat ?? '';
  } catch (e) {
    voidError.value = e instanceof Error ? e.message : '撤单数据加载失败';
    voidRate.value = null;
    voidDataAvailable.value = false;
    voidBreakdown.value = [];
  } finally {
    voidLoading.value = false;
  }

  // 区域坪效 — isolated so a 500 (e.g. migration not yet applied) degrades
  // ONLY this card, leaving every other card intact (MUST-FIX 2 pattern,
  // same as the void section above).
  zoneLoading.value = true;
  zoneError.value = '';
  try {
    const zoneResult = await getZoneEfficiency({ factoryId: capturedFactoryId, startDate, endDate, topN: 8 });
    zoneDataAvailable.value = zoneResult.dataAvailable ?? false;
    zoneNote.value = zoneResult.note ?? null;
    zones.value = zoneResult.zones ?? [];
    zoneCaveat.value = zoneResult.caveat ?? '';
  } catch (e) {
    zoneError.value = e instanceof Error ? e.message : '区域数据加载失败';
    zoneDataAvailable.value = false;
    zones.value = [];
  } finally {
    zoneLoading.value = false;
  }

  // 会员储值 + 画像 — isolated so a 500 (e.g. migration not yet applied)
  // degrades ONLY the member surface, leaving every other card intact.
  memberLoading.value = true;
  memberError.value = '';
  try {
    const members = await getMemberProfile({ factoryId: capturedFactoryId, startDate, endDate });
    memberDataAvailable.value = members.dataAvailable ?? false;
    memberCount.value = members.memberCount ?? 0;
    memberTotalBalance.value = members.totalBalance ?? null;
    memberNote.value = members.note ?? null;
    memberTierDistribution.value = members.tierDistribution ?? [];
    memberGenderDistribution.value = members.genderDistribution ?? [];
    memberBirthMonthDistribution.value = members.birthMonthDistribution ?? [];
    memberBirthUnknownCount.value = members.birthMonthUnknownCount ?? 0;
    memberBirthCoveragePct.value = members.birthMonthCoveragePct ?? null;
    memberRechargeTrend.value = members.rechargeTrend ?? [];
    memberRechargeStoreCount.value = members.rechargeStoreCount ?? 0;
    memberCaveat.value = members.caveat ?? '';
  } catch (e) {
    memberError.value = e instanceof Error ? e.message : '会员数据加载失败';
    memberDataAvailable.value = false;
    memberTierDistribution.value = [];
    memberGenderDistribution.value = [];
    memberBirthMonthDistribution.value = [];
    memberRechargeTrend.value = [];
  } finally {
    memberLoading.value = false;
  }
}

// 父组件先探测 Gold 区间再设 dateRange, 故 watch 立即触发首次加载。
watch(
  () => [props.factoryId, props.dateRange?.[0], props.dateRange?.[1]],
  () => { void load(); },
  { immediate: true },
);
</script>

<style scoped>
.rgg-section { margin-top: 16px; }
.rgg-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.rgg-title-text { font-size: 16px; font-weight: 600; }
.rgg-subtitle { font-size: 12px; color: #909399; flex: 1; }
.rgg-error { color: #f56c6c; padding: 12px; background: #fef0f0; border-radius: 6px; }
.rgg-insight {
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
.rgg-insight-icon { color: #2d8b57; flex-shrink: 0; }
.rgg-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
@media (max-width: 1400px) { .rgg-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 992px) { .rgg-grid { grid-template-columns: 1fr; } }
.rgg-card-header { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.rgg-rank-list { display: flex; flex-direction: column; gap: 10px; }
.rgg-rank-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.rgg-rank-no { width: 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 4px; font-size: 12px; background: #f0f2f5; color: #606266; flex-shrink: 0; }
.rgg-rank-0 { background: #ffe7ba; color: #d48806; }
.rgg-rank-1 { background: #f0f5ff; color: #2f54eb; }
.rgg-rank-2 { background: #f6ffed; color: #389e0d; }
.rgg-rank-name { width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.rgg-chan-name { width: 90px; }
.rgg-rank-bar-wrap { flex: 1; height: 8px; background: #f5f7fa; border-radius: 4px; overflow: hidden; }
.rgg-rank-bar { display: block; height: 100%; background: linear-gradient(90deg, #5470c6, #91cc75); border-radius: 4px; }
.rgg-chan-bar { background: linear-gradient(90deg, #fac858, #ee6666); }
.rgg-dine-bar { background: linear-gradient(90deg, #73c0de, #3ba272); }
.rgg-zone-bar { background: linear-gradient(90deg, #9a60b4, #ea7ccc); }
.rgg-rank-pct { width: 48px; text-align: right; color: #606266; flex-shrink: 0; }
.rgg-rank-val { width: 96px; text-align: right; font-weight: 600; color: #303133; flex-shrink: 0; }
.rgg-rank-bills { width: 56px; text-align: right; color: #909399; font-size: 12px; flex-shrink: 0; }
/* 撤单率 KPI tile */
.rgg-void-kpi { display: flex; flex-direction: column; gap: 14px; }
.rgg-void-rate { display: flex; align-items: baseline; gap: 8px; }
.rgg-void-rate-val { font-size: 30px; font-weight: 700; color: #e6a23c; }
.rgg-void-rate-label { font-size: 13px; color: #909399; }
.rgg-void-subs { display: flex; gap: 24px; }
.rgg-void-sub { display: flex; flex-direction: column; gap: 2px; }
.rgg-void-sub-label { font-size: 12px; color: #909399; }
.rgg-void-sub-val { font-size: 16px; font-weight: 600; color: #303133; }
.rgg-void-inline-error { color: #f56c6c; font-size: 13px; padding: 8px 0; }
/* 撤单稽核 full-width table */
.rgg-audit-card { margin-top: 16px; }
.rgg-zone-card { margin-top: 16px; }
.rgg-void-unlabeled { color: #909399; }
.rgg-void-caveat { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.5; }
.rgg-est-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 2px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
  background: #f4f4f5;
  border-radius: 4px;
}
/* 会员画像明细 — 3-column detail grid */
.rgg-member-detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }
@media (max-width: 1200px) { .rgg-member-detail-grid { grid-template-columns: 1fr; } }
.rgg-member-block { display: flex; flex-direction: column; gap: 8px; }
.rgg-member-block-title { font-size: 13px; font-weight: 600; color: #303133; }
.rgg-member-block-hint { font-size: 11px; font-weight: 400; color: #909399; }
/* F4 honesty disclosures — 生日覆盖率 / 充值门店覆盖 */
.rgg-member-coverage { font-size: 11px; color: #e6a23c; margin: -2px 0 2px; }
/* 生日月份分布 — mini vertical bar chart (12 months) */
.rgg-birth-bars { display: flex; align-items: flex-end; gap: 4px; height: 100px; }
.rgg-birth-bar-col { display: flex; flex-direction: column; align-items: center; flex: 1; height: 100%; }
.rgg-birth-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.rgg-birth-bar { width: 100%; min-height: 4px; background: linear-gradient(180deg, #91cc75, #5470c6); border-radius: 3px 3px 0 0; }
.rgg-birth-bar-label { font-size: 10px; color: #909399; margin-top: 4px; }
</style>
