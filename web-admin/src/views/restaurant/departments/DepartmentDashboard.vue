<script setup lang="ts">
/**
 * 餐饮部门驾驶舱 —— 四个部门共用这一个组件。
 *
 * 骨架：① 头部(部门 · 期间) → ② KPI 带 → ④ 排行明细 → ⑤ AI 入口 → ⑥ 建议+功能入口。
 * 差异全在 `departmentConfig.ts` 里，这里只负责取数与渲染。
 *
 * ⚠️ ③ 图表区本版**未做** —— 仓里没有通用图表封装，自己写一套等于加一片没人眼
 *    确认过的界面。留到视觉走查之后再补，现在不放占位框充数。
 *
 * 金额脱敏沿用现有 role-kpi 的范式：无价格权限时金额显示「—」，比率与计数照常。
 */
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { pythonFetch, getFactoryId } from '@/api/smartbi/common';
import { usePermissionStore } from '@/store/modules/permission';
import { DEPARTMENTS, DEPARTMENT_ORDER, pickPath, type DeptKey } from './departmentConfig';
import DeptTrendChart from './DeptTrendChart.vue';
import type { TrendPoint } from './trendTakeaway';
import { getRestaurantRoleExperience } from '@/views/restaurant/restaurantRoleExperience';

const props = defineProps<{ dept: DeptKey }>();

const router = useRouter();
const permission = usePermissionStore();

const config = computed(() => DEPARTMENTS[props.dept]);
const roleExperience = computed(() => getRestaurantRoleExperience(permission.currentRole));
const accessibleDepartments = computed(() => DEPARTMENT_ORDER.filter(
  (key) => permission.canAccess(DEPARTMENTS[key].module),
));
const loading = ref(false);
const payload = ref<Record<string, unknown> | null>(null);
const loadError = ref('');
const windowDays = ref(30);

const WINDOW_OPTIONS = [
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
  { label: '最近 90 天', value: 90 },
];

const canViewPrice = computed(() => permission.canViewPrice);
const dataSourceLabel = computed(() => config.value.source === 'kpi-summary'
  ? 'Gold 经营事实'
  : config.value.source === 'ops-summary'
    ? '后厨业务事实'
    : '等待人效配置');
const periodSummary = computed(() => {
  if (!config.value.source) return '尚未形成可计算区间';
  const { start, end } = windowRange(windowDays.value);
  return `${start} 至 ${end}`;
});

/**
 * 把「最近 N 天」化成具体起止日期。
 *
 * 🔴 gold_reads 的端点(kpi-summary / daily-trend)**不接 days**, 只接
 * start_date / end_date, 而且**省略即全部历史**。第一版我没传, 结果页头写着
 * 「最近 30 天」而图表画的是 576 天全量 —— 期间选择器做出了页面兑现不了的承诺,
 * 且不报错。这正是同一天在 AI resolver 侧修过三次的那一类缺陷。
 */
function windowRange(days: number): { start: string; end: string } {
  const end = new Date();
  const start = new Date(end.getTime() - (days - 1) * 86400000);
  const iso = (d: Date) => d.toISOString().slice(0, 10);
  return { start: iso(start), end: iso(end) };
}

function endpointFor(days: number): string | null {
  const factoryId = getFactoryId();
  if (config.value.source === 'ops-summary') {
    // 这个 router 收 days(滚动窗口), 与页头口径一致
    return `/api/smartbi/restaurant-ops/summary?factory_id=${factoryId}&days=${days}`;
  }
  if (config.value.source === 'kpi-summary') {
    const { start, end } = windowRange(days);
    return `/api/smartbi/gold/kpi-summary?factory_id=${factoryId}`
      + `&start_date=${start}&end_date=${end}`;
  }
  return null;
}

async function load() {
  const url = endpointFor(windowDays.value);
  if (!url) {
    payload.value = null;
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const res = await pythonFetch(url, { timeoutMs: 60000 }) as
      { success?: boolean; message?: string; data?: Record<string, unknown> };
    if (res && res.success === false) {
      // 不静默吞掉 —— 明确显示错误, 不拿空数据冒充"没有数据"
      loadError.value = res.message || '加载失败';
      payload.value = null;
      return;
    }
    payload.value = (res?.data ?? res) as Record<string, unknown>;
  } catch (e) {
    console.error('[dept-dashboard] load failed:', e);
    loadError.value = '加载失败，请稍后重试';
    payload.value = null;
  } finally {
    loading.value = false;
  }
}

// ── ③ 趋势 ────────────────────────────────────────────────────────
const trendPoints = ref<TrendPoint[]>([]);
const trendLoading = ref(false);

/** 无价格权限时不取金额曲线 —— 画一条全 0 的线比不画更误导。 */
const trendMasked = computed(() =>
  Boolean(config.value.trend?.money) && !canViewPrice.value);

async function loadTrend() {
  const t = config.value.trend;
  trendPoints.value = [];
  if (!t || trendMasked.value) return;
  trendLoading.value = true;
  try {
    const factoryId = getFactoryId();
    let url = t.endpoint.replace('{days}', String(windowDays.value))
      + (t.endpoint.includes('?') ? '&' : '?') + `factory_id=${factoryId}`;
    if (t.shape === 'revenue-points') {
      // 同上: 这一类端点省略日期 = 全部历史, 必须显式传
      const { start, end } = windowRange(windowDays.value);
      url += `&start_date=${start}&end_date=${end}`;
    }
    const res = await pythonFetch(url, { timeoutMs: 60000 }) as Record<string, unknown>;
    if (t.shape === 'ops-kpi') {
      const rows = (res?.data ?? []) as { date: string; value: number }[];
      trendPoints.value = rows.map((r) => ({ date: String(r.date), value: Number(r.value ?? 0) }));
    } else {
      const rows = (res?.points ?? []) as { date: string; revenue: number }[];
      trendPoints.value = rows.map((r) => ({ date: String(r.date), value: Number(r.revenue ?? 0) }));
    }
  } catch (e) {
    // 趋势取不到不该拖垮整页 —— KPI 与排行仍然有用。图表区自己显示"没有数据"。
    console.error('[dept-dashboard] trend load failed:', e);
    trendPoints.value = [];
  } finally {
    trendLoading.value = false;
  }
}

watch(() => [props.dept, windowDays.value], () => {
  load();
  loadTrend();
}, { immediate: true });

/** 依据不成立（如「可算毛利的菜品数」为 0）时，该 KPI 无从计算。 */
function kpiUnavailable(kpi: { basisPath?: string }): boolean {
  if (!kpi.basisPath) return false;
  const basis = pickPath(payload.value, kpi.basisPath);
  return !basis;
}

function formatKpi(kpi: {
  path: string; money?: boolean; percent?: boolean; rate01?: boolean; basisPath?: string;
}) {
  // 算不出来就显示「—」, 不能让后端的 0 变成「毛利率 0.0%」那种假的精确
  if (kpiUnavailable(kpi)) return '—';
  if (kpi.money && !canViewPrice.value) return '—';
  const raw = pickPath(payload.value, kpi.path);
  if (raw === undefined || raw === null) return '—';
  const num = Number(raw);
  if (Number.isNaN(num)) return String(raw);
  if (kpi.percent) return `${(kpi.rate01 ? num * 100 : num).toFixed(1)}%`;
  if (kpi.money) return `¥${num.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`;
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/** 因依据缺失而无法计算的 KPI 的解释文案（去重后展示在 KPI 带下方）。 */
const basisNotes = computed(() => {
  const notes = new Set<string>();
  for (const kpi of config.value.kpis) {
    if (kpi.basisHint && kpiUnavailable(kpi)) notes.add(kpi.basisHint);
  }
  return [...notes];
});

const rankingRows = computed(() => {
  const r = config.value.ranking;
  if (!r) return [];
  const rows = pickPath(payload.value, r.path);
  return Array.isArray(rows) ? rows as Record<string, unknown>[] : [];
});

/** ETL 尚未为该租户跑过时后端会带这个标志 —— 说清楚, 别让空数字看着像"就是 0"。 */
const etlPending = computed(() => Boolean(payload.value?.etl_pending));

function goto(path: string) {
  router.push(path);
}

function gotoDepartment(key: DeptKey) {
  router.push(`/restaurant/${key}`);
}

function ask(question: string) {
  // 复用现有 AI 问答入口, 把问题带过去
  router.push({ path: '/smart-bi/query', query: { q: question } });
}
</script>

<template>
  <main class="dept-page" :aria-labelledby="`dept-title-${dept}`">
    <header class="dept-header">
      <div class="dept-heading">
        <div class="dept-eyebrow">餐饮运营 · {{ roleExperience.roleLabel }}</div>
        <h1 :id="`dept-title-${dept}`" class="dept-title">
          <span class="dept-dot" :style="{ background: config.accent }" aria-hidden="true"></span>
          {{ config.title }}驾驶舱
        </h1>
        <p>{{ config.description }}</p>
      </div>
      <div class="header-right">
        <el-select
          v-if="config.source"
          v-model="windowDays"
          size="default"
          style="width: 130px"
          aria-label="选择分析时间范围"
        >
          <el-option
            v-for="opt in WINDOW_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-tag v-if="!canViewPrice" size="small" type="info" effect="plain">
          无价格权限，金额显示为「—」
        </el-tag>
      </div>
    </header>

    <nav class="department-switcher" aria-label="餐饮部门">
      <button
        v-for="key in accessibleDepartments"
        :key="key"
        type="button"
        class="department-switcher__item"
        :class="{ 'is-active': key === dept }"
        :aria-current="key === dept ? 'page' : undefined"
        @click="gotoDepartment(key)"
      >
        <span class="department-switcher__dot" :style="{ background: DEPARTMENTS[key].accent }" aria-hidden="true"></span>
        {{ DEPARTMENTS[key].title }}
      </button>
    </nav>

    <section class="context-strip" aria-label="数据口径与职责">
      <div class="context-strip__item">
        <span>数据来源</span>
        <strong>{{ dataSourceLabel }}</strong>
      </div>
      <div class="context-strip__item">
        <span>统计区间</span>
        <strong>{{ periodSummary }}</strong>
      </div>
      <div class="context-strip__responsibilities">
        <span>本部门负责</span>
        <div>
          <span v-for="item in config.responsibilities" :key="item">{{ item }}</span>
        </div>
      </div>
    </section>

    <!-- 数据源缺失（人事）：空态，不显示 0 也不给假图表 -->
    <el-card v-if="!config.source && config.emptyState" shadow="never" class="dept-card">
      <div class="empty-state">
        <div class="empty-title">{{ config.emptyState.title }}</div>
        <div class="empty-detail">{{ config.emptyState.detail }}</div>
        <ul class="empty-todos">
          <li v-for="todo in config.emptyState.todos" :key="todo">{{ todo }}</li>
        </ul>
        <el-button type="primary" @click="goto(config.emptyState.actionPath)">
          {{ config.emptyState.actionLabel }}
        </el-button>
      </div>
    </el-card>

    <template v-else>
      <!-- 加载失败：明确显示，不静默降级 -->
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        show-icon
        :closable="false"
        class="dept-alert"
      >
        <template #default>
          <el-button link type="primary" @click="load">重新读取</el-button>
        </template>
      </el-alert>
      <el-alert
        v-else-if="etlPending"
        title="该租户的聚合尚未生成，下面的数字会偏少或为空 —— 不是真实为 0"
        type="warning"
        show-icon
        :closable="false"
        class="dept-alert"
      />

      <!-- ② KPI 带 -->
      <el-card shadow="never" class="dept-card" v-loading="loading">
        <div class="stat-row">
          <div
            v-for="(kpi, index) in config.kpis"
            :key="kpi.label"
            class="stat-item"
            :class="{ 'stat-item--primary': index === 0 }"
          >
            <span class="stat-label">
              {{ kpi.label }}
              <el-tooltip v-if="kpi.hint" :content="kpi.hint" placement="top">
                <span class="stat-hint">ⓘ</span>
              </el-tooltip>
            </span>
            <span
              class="stat-value"
              :class="{ masked: (kpi.money && !canViewPrice) || kpiUnavailable(kpi) }"
            >
              {{ formatKpi(kpi) }}
            </span>
          </div>
        </div>
        <div v-if="basisNotes.length" class="basis-notes">
          <div v-for="note in basisNotes" :key="note">{{ note }}</div>
        </div>
      </el-card>

      <!-- ③ 趋势 -->
      <el-card v-if="config.trend" shadow="never" class="dept-card">
        <DeptTrendChart
          :title="config.trend.title"
          :unit="config.trend.unit"
          :money="config.trend.money"
          :points="trendPoints"
          :loading="trendLoading"
          :masked="trendMasked"
          :color="config.accent"
        />
      </el-card>

      <!-- ④ 排行明细 -->
      <el-card v-if="config.ranking" shadow="never" class="dept-card">
        <div class="card-header">
          <span class="card-title">{{ config.ranking.title }}</span>
        </div>
        <el-table :data="rankingRows" size="small" v-loading="loading">
          <el-table-column :prop="config.ranking.nameKey" label="食材" min-width="140" />
          <el-table-column
            v-if="config.ranking.categoryKey"
            :prop="config.ranking.categoryKey"
            label="类别"
            width="110"
          />
          <el-table-column :label="config.ranking.valueLabel" width="150" align="right">
            <template #default="{ row }">
              <span v-if="config.ranking!.valueMoney && !canViewPrice">—</span>
              <span v-else>
                {{ config.ranking!.valueMoney ? '¥' : '' }}{{
                  Number(row[config.ranking!.valueKey] ?? 0)
                    .toLocaleString('zh-CN', { maximumFractionDigits: 2 })
                }}
              </span>
            </template>
          </el-table-column>
          <template #empty>该窗口没有记录</template>
        </el-table>
      </el-card>
    </template>

    <section class="decision-grid">
      <div class="ai-command">
        <div class="ai-command__heading">
          <div>
            <span class="section-kicker">LLM ANALYSIS</span>
            <h2>让 AI 继续诊断</h2>
          </div>
          <span>真实数据负责数值，大模型负责解释与建议</span>
        </div>
        <div class="q-list">
          <button
            v-for="q in config.questions"
            :key="q"
            type="button"
            class="q-chip"
            @click="ask(q)"
          >
            <span>{{ q }}</span>
            <span aria-hidden="true">→</span>
          </button>
        </div>
      </div>

      <aside class="handoff-card">
        <span class="section-kicker">HANDOFF</span>
        <h2>执行与交接</h2>
        <p>{{ config.handoff }}</p>
        <div v-if="config.entries.length" class="entry-list">
          <button
            v-for="entry in config.entries"
            :key="entry.path"
            type="button"
            @click="goto(entry.path)"
          >
            <span>{{ entry.title }}</span>
            <span aria-hidden="true">→</span>
          </button>
        </div>
      </aside>
    </section>
  </main>
</template>

<style lang="scss" scoped>
.dept-page { display: flex; flex-direction: column; gap: 16px; padding: 24px; color: var(--color-text-primary, #1a2332); }
.dept-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 4px 0 2px; }
.dept-heading { min-width: 0; }
.dept-eyebrow,
.section-kicker { color: var(--color-primary, #1b65a8); font-size: 10px; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; }
.dept-title { display: flex; align-items: center; gap: 9px; margin: 6px 0; font-size: 27px; font-weight: 680; letter-spacing: -0.025em; }
.dept-heading p { max-width: 60ch; margin: 0; color: var(--el-text-color-regular); font-size: 13px; line-height: 1.6; }
.dept-dot { width: 8px; height: 8px; flex: none; border-radius: 50%; }
.header-right { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

.department-switcher { display: flex; gap: 4px; width: fit-content; padding: 4px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.department-switcher__item { display: inline-flex; align-items: center; gap: 7px; padding: 8px 12px; color: var(--el-text-color-regular); font-size: 13px; background: transparent; border: 0; border-radius: 7px; cursor: pointer; transition: color 160ms ease, background-color 160ms ease, box-shadow 160ms ease; }
.department-switcher__item:hover { color: var(--el-text-color-primary); }
.department-switcher__item.is-active { color: var(--el-text-color-primary); font-weight: 600; background: var(--el-bg-color); box-shadow: 0 2px 7px rgba(12, 25, 41, 0.08); }
.department-switcher__item:focus-visible,
.q-chip:focus-visible,
.entry-list button:focus-visible { outline: 2px solid var(--color-primary, #1b65a8); outline-offset: 1px; }
.department-switcher__dot { width: 6px; height: 6px; border-radius: 50%; }

.context-strip { display: grid; grid-template-columns: minmax(170px, auto) minmax(220px, auto) minmax(0, 1fr); gap: 0; overflow: hidden; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.context-strip__item,
.context-strip__responsibilities { display: flex; flex-direction: column; gap: 4px; min-width: 0; padding: 13px 16px; border-right: 1px solid var(--el-border-color-lighter); }
.context-strip__responsibilities { border-right: 0; }
.context-strip__item > span,
.context-strip__responsibilities > span { color: var(--el-text-color-secondary); font-size: 11px; }
.context-strip__item strong { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; }
.context-strip__responsibilities > div { display: flex; flex-wrap: wrap; gap: 6px; }
.context-strip__responsibilities > div span { padding: 3px 7px; color: var(--el-text-color-regular); font-size: 11px; background: var(--el-fill-color-light); border-radius: 999px; }

.dept-card { border-radius: 10px; }
.dept-card :deep(.el-card__body) { padding: 18px 20px; }
.dept-alert { border-radius: 8px; }
.stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 0; }
.stat-item { display: flex; flex-direction: column; gap: 5px; min-width: 0; padding: 8px 18px; border-right: 1px solid var(--el-border-color-lighter); }
.stat-item:first-child { padding-left: 0; }
.stat-item:last-child { border-right: 0; }
.stat-item--primary .stat-value { color: var(--color-primary, #1b65a8); font-size: 25px; }
.stat-label { color: var(--el-text-color-secondary); font-size: 12px; }
.stat-hint { margin-left: 2px; cursor: help; }
.stat-value { overflow: hidden; font-size: 21px; font-weight: 650; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; text-overflow: ellipsis; white-space: nowrap; }
.stat-value.masked { color: var(--el-text-color-secondary); font-weight: 500; }
.basis-notes { display: flex; flex-direction: column; gap: 4px; margin-top: 14px; padding-top: 12px; color: var(--el-text-color-secondary); font-size: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.card-title { font-size: 14px; font-weight: 650; }

.decision-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr); gap: 16px; }
.ai-command,
.handoff-card { padding: 20px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.ai-command { position: relative; overflow: hidden; }
.ai-command::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 3px; background: var(--color-primary, #1b65a8); }
.ai-command__heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.ai-command h2,
.handoff-card h2 { margin: 3px 0 0; font-size: 16px; }
.ai-command__heading > span { color: var(--el-text-color-secondary); font-size: 11px; }
.q-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.q-chip,
.entry-list button { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; padding: 10px 12px; text-align: left; color: var(--el-text-color-primary); background: var(--el-fill-color-light); border: 1px solid transparent; border-radius: 8px; cursor: pointer; transition: color 160ms ease, border-color 160ms ease, background-color 160ms ease; }
.q-chip:hover { color: var(--color-primary, #1b65a8); background: var(--el-bg-color); border-color: var(--el-color-primary-light-5); }
.q-chip span:first-child { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.handoff-card > p { margin: 10px 0 14px; color: var(--el-text-color-regular); font-size: 12px; line-height: 1.65; }
.entry-list { display: flex; flex-direction: column; gap: 4px; }
.entry-list button { padding: 8px 2px; background: transparent; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); border-radius: 0; }
.entry-list button:last-child { border-bottom: 0; }
.entry-list button:hover { color: var(--color-primary, #1b65a8); }
.entry-list button span:first-child { font-size: 12px; }

.empty-state { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; padding: 20px 8px; text-align: left; }
.empty-title { font-size: 17px; font-weight: 650; }
.empty-detail { max-width: 52ch; color: var(--el-text-color-secondary); font-size: 13px; }
.empty-todos { display: flex; flex-direction: column; gap: 6px; margin: 0; padding-left: 20px; color: var(--el-text-color-primary); font-size: 13px; text-align: left; }

@media (max-width: 1024px) {
  .context-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .context-strip__item:nth-child(2) { border-right: 0; }
  .context-strip__responsibilities { grid-column: 1 / -1; border-top: 1px solid var(--el-border-color-lighter); }
  .decision-grid { grid-template-columns: 1fr; }
  .q-list { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .dept-page { gap: 12px; padding: 12px; }
  .dept-header { align-items: stretch; flex-direction: column; }
  .dept-title { font-size: 23px; }
  .department-switcher { width: 100%; overflow-x: auto; }
  .department-switcher__item { flex: 1 0 auto; justify-content: center; }
  .context-strip { grid-template-columns: 1fr; }
  .context-strip__item,
  .context-strip__responsibilities { grid-column: auto; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .context-strip__responsibilities { border-bottom: 0; }
  .stat-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stat-item { padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
  .stat-item:nth-child(2n) { border-right: 0; }
  .stat-item:first-child { padding-left: 12px; }
  .stat-value,
  .stat-item--primary .stat-value { font-size: 19px; }
  .ai-command__heading { align-items: flex-start; flex-direction: column; }
}

@media (prefers-reduced-motion: reduce) {
  .department-switcher__item,
  .q-chip,
  .entry-list button { transition: none; }
}
</style>
