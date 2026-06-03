<script setup lang="ts">
/**
 * G2 餐饮目标拆分 + 达成率预警 — TargetHierarchyEditor
 *
 * Rule 1: 实时显示月均预览; data_missing 不显 0%
 * Rule 2: 标题带 factory / year / kpiKind 上下文
 * Rule 3: 调整原因 el-select; 选"其他"才显 textarea
 * Rule 4: POST 幂等 upsert; saving=true 防双击
 * Rule 5: 保存成功后自动跳 /analytics/kpi
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  upsertTarget,
  type TargetUpsertRequest,
} from '@/api/smartbi/restaurant-targets';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId ?? '');

// ── State ─────────────────────────────────────────────────────────────────────
const selectedYear = ref(new Date().getFullYear());
const kpiKind = ref<'revenue' | 'bill_count'>('revenue');
const yearTargetValue = ref<number | null>(null);
const monthlyTargets = ref<Record<string, number | null>>(
  Object.fromEntries(
    Array.from({ length: 12 }, (_, i) => [
      `${new Date().getFullYear()}-${String(i + 1).padStart(2, '0')}`,
      null,
    ]),
  ),
);
const selectedReason = ref<string>('');
const reasonDetail = ref<string>('');
const saving = ref(false);
const loading = ref(false);

const REASON_OPTIONS = ['季节性', '促销活动', '市场变化', '节假日', '其他'];

// Rule 1: 月均提示
const monthlyAvgHint = computed(() => {
  if (yearTargetValue.value && yearTargetValue.value > 0) {
    return Math.round(yearTargetValue.value / 12).toLocaleString();
  }
  return null;
});

// Rule 3: 仅"其他"显 textarea
const showReasonDetail = computed(() => selectedReason.value === '其他');

// Rule 2: 页面标题上下文
const pageTitle = computed(() => {
  const kpiLabel = kpiKind.value === 'revenue' ? '营业额' : '单量';
  return `设置目标 — ${factoryId.value} / ${selectedYear.value} / ${kpiLabel}`;
});

// ── Save single target entry ──────────────────────────────────────────────────
async function saveSingleTarget(level: string, periodKey: string, targetValue: number) {
  if (!factoryId.value) return;
  const reason = selectedReason.value === '其他' ? reasonDetail.value : selectedReason.value;
  const req: TargetUpsertRequest = {
    kpiKind: kpiKind.value,
    level,
    periodKey,
    targetValue,
    storeId: null,
    reason: reason || null,
  };
  await upsertTarget(req);
}

async function saveYearTarget() {
  if (!yearTargetValue.value || yearTargetValue.value <= 0) {
    ElMessage({ message: '目标值必须大于 0', type: 'error', duration: 0, showClose: true });
    return;
  }
  saving.value = true;
  try {
    await saveSingleTarget('year', String(selectedYear.value), yearTargetValue.value);
    ElMessage({ message: `年度目标已保存（${selectedYear.value}）`, type: 'success' });
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function saveAllMonthly() {
  const entries = Object.entries(monthlyTargets.value).filter(([, v]) => v && v > 0);
  if (entries.length === 0) {
    ElMessage({ message: '请至少填写一个月度目标', type: 'warning', duration: 3000 });
    return;
  }
  saving.value = true;
  try {
    for (const [periodKey, value] of entries) {
      await saveSingleTarget('month', periodKey, value as number);
    }
    ElMessage({ message: `已保存 ${entries.length} 个月度目标`, type: 'success' });
    // Rule 5: 保存成功跳 KPI 看板
    setTimeout(() => router.push('/analytics/kpi'), 1200);
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function applyYearlyAverage() {
  if (!yearTargetValue.value || yearTargetValue.value <= 0) {
    ElMessage({ message: '请先填写年度目标', type: 'warning', duration: 3000 });
    return;
  }
  await ElMessageBox.confirm(
    `将用年度目标 ¥${yearTargetValue.value.toLocaleString()} 的 1/12 填充所有月度格（¥${monthlyAvgHint.value}/月），是否继续？`,
    '月度均分确认',
    { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
  );
  const avg = Math.round(yearTargetValue.value / 12);
  const year = selectedYear.value;
  for (let m = 1; m <= 12; m++) {
    const key = `${year}-${String(m).padStart(2, '0')}`;
    monthlyTargets.value[key] = avg;
  }
}

onMounted(() => {
  loading.value = false;
  // Future: load existing targets via hierarchy_rollup endpoint
});

defineExpose({ saving, yearTargetValue, selectedReason, monthlyTargets });
</script>

<template>
  <div class="target-hierarchy-editor" v-loading="loading">
    <!-- Rule 2: context in header -->
    <div class="page-header">
      <h2>{{ pageTitle }}</h2>
    </div>

    <el-card class="section-card">
      <template #header>
        <div class="card-header">年度目标设置</div>
      </template>

      <div class="year-row">
        <el-date-picker
          v-model="selectedYear"
          type="year"
          format="YYYY"
          value-format="YYYY"
          placeholder="选择年份"
          style="width: 140px; margin-right: 16px;"
        />
        <el-tabs v-model="kpiKind" style="margin-bottom: 0;">
          <el-tab-pane label="营业额" name="revenue" />
          <el-tab-pane label="单量" name="bill_count" />
        </el-tabs>
      </div>

      <!-- Rule 1: 年度输入 + 月均提示 -->
      <div class="year-target-row">
        <label class="target-label">年度目标（{{ kpiKind === 'revenue' ? '元' : '单' }}）</label>
        <el-input-number
          v-model="yearTargetValue"
          class="year-target-input"
          :min="1"
          :step="10000"
          style="width: 200px;"
          placeholder="输入年度目标"
        />
        <span v-if="monthlyAvgHint" class="avg-hint">
          月均 ≈ ¥{{ monthlyAvgHint }}
        </span>
      </div>

      <div class="action-row">
        <el-button type="primary" :loading="saving" :disabled="saving" @click="saveYearTarget">
          保存年度目标
        </el-button>
        <el-button :disabled="!yearTargetValue" @click="applyYearlyAverage">
          按年度均分到月度
        </el-button>
      </div>
    </el-card>

    <!-- Monthly targets grid -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">月度目标（{{ selectedYear }}）</div>
      </template>

      <el-row :gutter="12">
        <el-col :span="4" v-for="m in 12" :key="m" class="month-col">
          <div class="month-label">{{ m }}月</div>
          <el-input-number
            v-model="monthlyTargets[`${selectedYear}-${String(m).padStart(2, '0')}`]"
            :min="1"
            size="small"
            style="width: 100%;"
            :placeholder="`${m}月目标`"
          />
        </el-col>
      </el-row>

      <!-- Rule 3: 原因 dropdown -->
      <div class="reason-row" style="margin-top: 16px;">
        <label class="target-label">调整原因</label>
        <el-select
          v-model="selectedReason"
          class="reason-select"
          placeholder="选择原因（可选）"
          style="width: 180px;"
        >
          <el-option v-for="opt in REASON_OPTIONS" :key="opt" :label="opt" :value="opt" />
        </el-select>
        <el-input
          v-if="showReasonDetail"
          v-model="reasonDetail"
          class="reason-detail-input"
          placeholder="请补充原因"
          style="width: 240px; margin-left: 8px;"
        />
      </div>

      <div class="action-row">
        <!-- Rule 4: saving=true → disabled -->
        <el-button
          type="primary"
          :loading="saving"
          :disabled="saving"
          class="save-btn"
          @click="saveAllMonthly"
        >
          保存所有月度目标
        </el-button>
        <el-button @click="router.push('/analytics/kpi')">返回 KPI 看板</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.target-hierarchy-editor { padding: 20px; }
.page-header { margin-bottom: 20px; }
.page-header h2 { font-size: 18px; font-weight: 600; color: #303133; }
.section-card { margin-bottom: 16px; }
.card-header { font-weight: 600; }
.year-row { display: flex; align-items: center; margin-bottom: 16px; }
.year-target-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.target-label { font-size: 14px; color: #606266; min-width: 120px; }
.avg-hint { color: #909399; font-size: 13px; }
.action-row { margin-top: 16px; display: flex; gap: 8px; }
.month-col { margin-bottom: 12px; }
.month-label { font-size: 13px; color: #606266; margin-bottom: 4px; }
.reason-row { display: flex; align-items: center; gap: 8px; }
</style>
