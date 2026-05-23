<!--
  数据质量 tab — 餐饮 Phase B-1 outlier filter (Task 9).

  在数据完整度页内嵌的二级 tab — 显示 IQR 异常信号 (待复核 + 已确认).
  Reviewer R2 关键: 必须 render baselineSource='global' 灰色 "全网基线" badge,
  让 admin 看清楚 fallback 是用本工厂数据还是全网数据.

  URL: /restaurant/data-completeness (tab 2 "数据质量")
  Visible: RESTAURANT factory only (parent page 已 gate)

  API:
   - GET    /api/restaurant/outliers              (fetchOutliers)
   - POST   /api/restaurant/outliers/dismiss      (dismissOutlier)
   - DELETE /api/restaurant/outliers/dismiss/{id} (undismissOutlier)

  No `as any` — types imported from @/api/restaurant/outliers.
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import {
  fetchOutliers,
  dismissOutlier,
  undismissOutlier,
  type OutliersResponse,
  type OutlierItem,
  type DismissedItem,
} from '@/api/restaurant/outliers';

// ── Auth ───────────────────────────────────────────────────────────────
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ── State ──────────────────────────────────────────────────────────────
const loading = ref<boolean>(false);
const data = ref<OutliersResponse | null>(null);
const errorMsg = ref<string>('');
const showDismissed = ref<boolean>(false);

// ── Helpers ────────────────────────────────────────────────────────────
// el-table scoped slot 把 row 暴露为 unknown — 用 typed identity 函数把它窄到
// 我们自己控制的 OutlierItem / DismissedItem (避免 template 内散落 `as`).
function asOutlier(row: unknown): OutlierItem {
  return row as OutlierItem;
}

function asDismissed(row: unknown): DismissedItem {
  return row as DismissedItem;
}

function severityType(sev: string): 'danger' | 'warning' {
  return sev === 'high' ? 'danger' : 'warning';
}

function formatRange(item: OutlierItem): string {
  return `¥${item.q1.toLocaleString()} - ¥${item.q3.toLocaleString()}`;
}

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
      hour12: false,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

// ── Data loading ───────────────────────────────────────────────────────
async function load(): Promise<void> {
  errorMsg.value = '';
  const fid = factoryId.value;
  if (!fid) {
    errorMsg.value = '当前用户无 factoryId — 请重新登录';
    return;
  }
  loading.value = true;
  try {
    data.value = await fetchOutliers(fid);
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    errorMsg.value = msg || '数据加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

async function handleDismiss(item: OutlierItem): Promise<void> {
  const fid = factoryId.value;
  if (!fid) {
    ElMessage.error('当前用户无 factoryId');
    return;
  }
  try {
    await dismissOutlier({
      factoryId: fid,
      anomalyDate: item.anomalyDate,
      kpiKind: item.kpiKind,
      snapshotValue: item.value,
      snapshotQ1: item.q1,
      snapshotQ3: item.q3,
      snapshotBaselineSource: item.baselineSource,
    });
    ElMessage.success('已标记为非异常');
    await load();
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '操作失败';
    ElMessage.error(msg);
  }
}

async function handleUndismiss(item: DismissedItem): Promise<void> {
  try {
    await undismissOutlier(item.id);
    ElMessage.success('已恢复待复核');
    await load();
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '操作失败';
    ElMessage.error(msg);
  }
}

onMounted(load);
</script>

<template>
  <div class="data-quality-tab">
    <!-- Loading skeleton -->
    <el-skeleton v-if="loading" :rows="5" animated />

    <!-- Error state -->
    <el-alert
      v-else-if="errorMsg"
      :title="errorMsg"
      type="error"
      show-icon
      :closable="false"
      class="error-alert"
    />

    <!-- Main content -->
    <template v-else-if="data">
      <!-- 总览 summary card -->
      <el-card class="summary-card" shadow="never">
        <div class="summary-inner">
          <span class="summary-text">
            本月 {{ data.outliers.length + data.dismissed.length }} 个 cost 信号异常
          </span>
          <el-tag type="warning" size="small">
            待复核 {{ data.summary.totalAnomalies }}
          </el-tag>
          <el-tag type="info" size="small">
            已确认 {{ data.summary.dismissedThisMonth }}
          </el-tag>
          <el-tag
            v-if="data.summary.insufficientKpis.length"
            type="info"
            size="small"
            effect="plain"
          >
            {{ data.summary.insufficientKpis.length }} 个信号样本不足
          </el-tag>
          <div class="summary-spacer" />
          <el-button :loading="loading" size="small" @click="load">刷新</el-button>
        </div>
      </el-card>

      <!-- 异常表格 -->
      <el-table
        v-if="data.outliers.length"
        :data="data.outliers"
        class="outlier-table"
        stripe
      >
        <el-table-column prop="anomalyDate" label="日期" width="120" />
        <el-table-column prop="kpiLabel" label="KPI" width="140" />
        <el-table-column label="实际值" width="140">
          <template #default="scope">
            ¥{{ asOutlier(scope.row).value.toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="正常范围" min-width="220">
          <template #default="scope">
            <span class="range-text">{{ formatRange(asOutlier(scope.row)) }}</span>
            <!-- R2: baselineSource 透明标记 (admin awareness) -->
            <el-tag
              v-if="asOutlier(scope.row).baselineSource === 'global'"
              type="info"
              size="small"
              effect="plain"
              class="baseline-badge"
            >
              全网基线
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="偏离" width="140">
          <template #default="scope">
            <el-tag :type="severityType(asOutlier(scope.row).severity)" size="small">
              {{ asOutlier(scope.row).direction === 'above' ? '高' : '低' }}
              {{ asOutlier(scope.row).deviationX.toFixed(1) }}×
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button size="small" @click="handleDismiss(asOutlier(scope.row))">
 非异常
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-else description="本周期内无异常信号" />

      <!-- 已确认折叠区 -->
      <div v-if="data.dismissed.length" class="dismissed-section">
        <el-button link @click="showDismissed = !showDismissed">
          {{ showDismissed ? '收起' : '展开' }}已确认 {{ data.dismissed.length }} 项
        </el-button>
        <el-table
          v-show="showDismissed"
          :data="data.dismissed"
          size="small"
          class="dismissed-table"
        >
          <el-table-column prop="anomalyDate" label="日期" width="120" />
          <el-table-column prop="kpiLabel" label="KPI" width="140" />
          <el-table-column label="当时值" width="140">
            <template #default="scope">
              ¥{{ asDismissed(scope.row).snapshotValue.toLocaleString() }}
            </template>
          </el-table-column>
          <el-table-column prop="dismissedBy" label="确认人" width="140" />
          <el-table-column label="确认时间" width="180">
            <template #default="scope">
              {{ formatDateTime(asDismissed(scope.row).dismissedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="scope">
              <el-button size="small" @click="handleUndismiss(asDismissed(scope.row))">
                ↺ 恢复
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.data-quality-tab {
  padding: 12px 0;
}

.error-alert {
  margin-bottom: 16px;
}

.summary-card {
  margin-bottom: 16px;
}

.summary-inner {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.summary-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
  margin-right: 8px;
}

.summary-spacer {
  flex: 1;
}

.outlier-table {
  margin-bottom: 16px;
}

.range-text {
  margin-right: 6px;
}

.baseline-badge {
  margin-left: 4px;
}

.dismissed-section {
  margin-top: 12px;
}

.dismissed-table {
  margin-top: 8px;
}
</style>
