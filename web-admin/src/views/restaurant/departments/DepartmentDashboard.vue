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
import { DEPARTMENTS, pickPath, type DeptKey } from './departmentConfig';

const props = defineProps<{ dept: DeptKey }>();

const router = useRouter();
const permission = usePermissionStore();

const config = computed(() => DEPARTMENTS[props.dept]);
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

function endpointFor(days: number): string | null {
  const factoryId = getFactoryId();
  if (config.value.source === 'ops-summary') {
    return `/api/smartbi/gold/restaurant-ops/summary?factory_id=${factoryId}&days=${days}`;
  }
  if (config.value.source === 'kpi-summary') {
    return `/api/smartbi/gold/kpi-summary?factory_id=${factoryId}`;
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

watch(() => [props.dept, windowDays.value], load, { immediate: true });

function formatKpi(kpi: { path: string; money?: boolean; percent?: boolean; rate01?: boolean }) {
  if (kpi.money && !canViewPrice.value) return '—';
  const raw = pickPath(payload.value, kpi.path);
  if (raw === undefined || raw === null) return '—';
  const num = Number(raw);
  if (Number.isNaN(num)) return String(raw);
  if (kpi.percent) return `${(kpi.rate01 ? num * 100 : num).toFixed(1)}%`;
  if (kpi.money) return `¥${num.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`;
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

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

function ask(question: string) {
  // 复用现有 AI 问答入口, 把问题带过去
  router.push({ path: '/smart-bi/query', query: { q: question } });
}
</script>

<template>
  <div class="dept-page">
    <!-- ① 头部 -->
    <div class="dept-header">
      <h2 class="dept-title">
        <span class="dept-dot" :style="{ background: config.accent }"></span>
        {{ config.title }}
      </h2>
      <div class="header-right">
        <el-select
          v-if="config.source"
          v-model="windowDays"
          size="default"
          style="width: 130px"
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
    </div>

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
      />
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
          <div v-for="kpi in config.kpis" :key="kpi.label" class="stat-item">
            <span class="stat-label">
              {{ kpi.label }}
              <el-tooltip v-if="kpi.hint" :content="kpi.hint" placement="top">
                <span class="stat-hint">ⓘ</span>
              </el-tooltip>
            </span>
            <span class="stat-value" :class="{ masked: kpi.money && !canViewPrice }">
              {{ formatKpi(kpi) }}
            </span>
          </div>
        </div>
      </el-card>

      <!-- ④ 排行明细 -->
      <el-card v-if="config.ranking" shadow="never" class="dept-card">
        <div class="card-header">
          <span class="card-title">{{ config.ranking.title }}</span>
        </div>
        <el-table :data="rankingRows" size="small" v-loading="loading">
          <el-table-column prop="name" :label="'食材'" min-width="140" />
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

    <div class="dept-bottom">
      <!-- ⑤ AI 入口 -->
      <el-card shadow="never" class="dept-card">
        <div class="card-header"><span class="card-title">问 AI</span></div>
        <div class="q-list">
          <el-button
            v-for="q in config.questions"
            :key="q"
            class="q-chip"
            size="small"
            @click="ask(q)"
          >{{ q }}</el-button>
        </div>
      </el-card>

      <!-- ⑥ 功能入口 -->
      <el-card v-if="config.entries.length" shadow="never" class="dept-card">
        <div class="card-header"><span class="card-title">功能入口</span></div>
        <div class="entry-list">
          <el-button
            v-for="entry in config.entries"
            :key="entry.path"
            size="small"
            @click="goto(entry.path)"
          >{{ entry.title }}</el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dept-page { padding: 20px; display: flex; flex-direction: column; gap: 16px; }

.dept-header {
  display: flex; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 8px;
}
.dept-title {
  font-size: 18px; font-weight: 600; display: flex; align-items: center; gap: 8px; margin: 0;
}
.dept-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.header-right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.dept-card { border-radius: 10px; }
.dept-alert { border-radius: 8px; }

.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.stat-item {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 4px;
}
.stat-label { font-size: 12px; color: var(--el-text-color-secondary); }
.stat-hint { cursor: help; margin-left: 2px; }
.stat-value {
  font-size: 22px; font-weight: 600;
  font-variant-numeric: tabular-nums; letter-spacing: -0.01em;
  &.masked { color: var(--el-text-color-secondary); font-weight: 500; }
}

.card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.card-title { font-size: 15px; font-weight: 600; }

.dept-bottom {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}
.q-list, .entry-list { display: flex; flex-wrap: wrap; gap: 8px; }
.q-chip { border-radius: 16px; }

.empty-state {
  display: flex; flex-direction: column; align-items: center;
  gap: 10px; padding: 32px 24px; text-align: center;
}
.empty-title { font-size: 16px; font-weight: 600; }
.empty-detail { font-size: 13px; color: var(--el-text-color-secondary); max-width: 44ch; }
.empty-todos {
  text-align: left; font-size: 13px; color: var(--el-text-color-primary);
  margin: 0; padding-left: 20px; display: flex; flex-direction: column; gap: 6px;
}

@media (max-width: 768px) {
  .dept-page { padding: 12px; }
  .stat-item { padding: 8px 12px; }
  .stat-value { font-size: 18px; }
}
</style>
