<script setup lang="ts">
/**
 * G4 — 餐饮 AI 经营体检报告主视图。
 *
 * 首屏自动加载上月报告 (Rule 1: 不需用户主动触发)。红/黄/绿灯 severity 卡片墙,
 * 前 3 张 critical 默认展开, 余折叠。RxAction 时间框 tabs 在 DiagnosisCard 内。
 * 无 finance 数据 → POS-only 降级 alert + 上传按钮 (Rule 5 dead-end 改导航)。
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { isAxiosError } from 'axios';
import { useAuthStore } from '@/store/modules/auth';
import {
  fetchHealthCheckReport,
  type HealthCheckReport,
} from '@/api/smartbi/healthCheck';
import DiagnosisCard from './components/health/DiagnosisCard.vue';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

// sub_sector 有限选项 (Rule 3: 约束选择, 不自由输入)。
const SUB_SECTOR_OPTIONS = [
  { label: '通用行业', value: '' },
  { label: '火锅', value: '火锅' },
  { label: '快餐', value: '快餐' },
  { label: '西餐', value: '西餐' },
  { label: '日料', value: '日料' },
  { label: '烧烤', value: '烧烤' },
  { label: '奶茶', value: '奶茶' },
  { label: '咖啡', value: '咖啡' },
  { label: '中式海鲜', value: '中式海鲜' },
  { label: '牛肉面', value: '牛肉面' },
  { label: '鱼类餐饮', value: '鱼类餐饮' },
  { label: '餐饮连锁', value: '餐饮连锁' },
];

const month = ref('');          // "YYYY-MM"; 空 = 上月 (后端默认)
const subSector = ref('');       // 空 = 走后端 FACTORY_SUBSECTOR_MAP / 通用
const tableCount = ref<number | null>(null);
const loading = ref(false);
const report = ref<HealthCheckReport | null>(null);
const errorMsg = ref('');

// month picker 上限 = 本月 (不允许选未来)。
const maxMonth = (() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
})();

const diagnoses = computed(() => report.value?.diagnoses ?? []);
const summary = computed(() => report.value?.summary ?? null);
const meta = computed(() => report.value?.reportMeta ?? null);

// 是否缺成本侧数据 (food/labor 任一 skipped) → POS-only 降级提示。
const financeMissing = computed(() => {
  const cov = report.value?.summary?.coverage ?? {};
  return (cov['food_cost_ratio'] || '').startsWith('skipped')
    && (cov['labor_cost_ratio'] || '').startsWith('skipped');
});

const allHealthy = computed(
  () => !loading.value && report.value != null && diagnoses.value.length === 0,
);

async function generateReport() {
  if (!factoryId.value) {
    errorMsg.value = '缺少工厂上下文, 请重新登录';
    return;
  }
  loading.value = true;
  errorMsg.value = '';
  try {
    const res = await fetchHealthCheckReport(factoryId.value, {
      month: month.value || undefined,
      subSector: subSector.value || undefined,
      tableCount: tableCount.value ?? undefined,
    });
    if (!res.success || !res.data) {
      throw new Error(res.message || '体检报告生成失败');
    }
    report.value = res.data;
  } catch (e) {
    const msg = isAxiosError(e)
      ? (e.response?.data?.message ?? '请求失败')
      : (e instanceof Error ? e.message : String(e));
    errorMsg.value = msg;
    report.value = null;
    const { ElMessage } = await import('element-plus');
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function goUpload() {
  router.push('/smart-bi/upload');
}

onMounted(() => {
  // Rule 1: 首屏自动加载上月报告。
  generateReport();
});
</script>

<template>
  <div class="health-report" data-test="health-report">
    <!-- 头部 -->
    <div class="report-header">
      <div class="header-title">
        <el-icon><FirstAidKit /></el-icon>
        <span>AI 经营体检</span>
        <span v-if="meta" class="header-context" data-test="report-context">
          {{ factoryId }} · {{ meta.period }} · {{ meta.subSector }}
        </span>
      </div>
      <div class="header-controls">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          placeholder="上月"
          :disabled-date="(d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` > maxMonth"
          style="width: 130px"
          data-test="month-picker"
        />
        <el-select
          v-model="subSector"
          placeholder="行业子品类"
          style="width: 140px"
          data-test="subsector-select"
        >
          <el-option
            v-for="opt in SUB_SECTOR_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-input-number
          v-model="tableCount"
          :min="1"
          :max="500"
          placeholder="桌台数"
          controls-position="right"
          style="width: 120px"
          data-test="table-count"
        />
        <el-button type="primary" :loading="loading" data-test="generate-btn" @click="generateReport">
          生成报告
        </el-button>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state" v-loading="true" element-loading-text="正在生成体检报告...">
      <div style="height: 200px" />
    </div>

    <!-- 错误 -->
    <el-alert
      v-else-if="errorMsg"
      :title="errorMsg"
      type="error"
      :closable="false"
      show-icon
      data-test="error-alert"
    />

    <template v-else-if="report">
      <!-- 摘要徽章行 -->
      <div class="summary-row" data-test="summary-row">
        <div class="badge badge-critical">
          <span class="badge-count" data-test="critical-count">{{ summary?.criticalCount ?? 0 }}</span>
          <span class="badge-label">严重</span>
        </div>
        <div class="badge badge-warning">
          <span class="badge-count" data-test="warning-count">{{ summary?.warningCount ?? 0 }}</span>
          <span class="badge-label">预警</span>
        </div>
        <div class="badge badge-ok">
          <span class="badge-count">{{ summary?.checkedCount ?? 0 }}</span>
          <span class="badge-label">已检查</span>
        </div>
        <el-tooltip
          v-if="summary?.coverageNote"
          :content="summary.coverageNote"
          placement="bottom"
          data-test="coverage-tooltip"
        >
          <span class="coverage-hint">
            <el-icon><InfoFilled /></el-icon> 数据覆盖说明
          </span>
        </el-tooltip>
      </div>

      <!-- POS-only 降级提示 (Rule 5 dead-end 改导航) -->
      <el-alert
        v-if="financeMissing"
        type="warning"
        :closable="false"
        show-icon
        class="pos-only-alert"
        data-test="pos-only-alert"
      >
        <template #title>
          当前仅基于 POS 数据生成营收侧诊断 — 上传财务 Excel 可解锁食材/人力成本诊断
        </template>
        <el-button size="small" type="primary" plain data-test="upload-btn" @click="goUpload">
          上传财务 Excel
        </el-button>
      </el-alert>

      <!-- 全部健康 -->
      <el-result
        v-if="allHealthy"
        icon="success"
        title="本月经营体检通过"
        :sub-title="summary?.coverageNote || '已检查的指标均无异常'"
        data-test="all-healthy"
      />

      <!-- 诊断卡片墙 (前 3 critical 默认展开) -->
      <div v-else class="card-wall" data-test="card-wall">
        <DiagnosisCard
          v-for="(d, idx) in diagnoses"
          :key="d.metricKey"
          :diagnosis="d"
          :default-expanded="d.severity === 'critical' && idx < 3"
        />
      </div>

      <!-- 页脚 -->
      <div v-if="meta" class="report-footer" data-test="report-footer">
        快照时间: {{ meta.snapshotAt }} · 业态: {{ meta.subSector }} · 周期: {{ meta.period }}
        <span v-if="meta.cacheHit"> · (缓存)</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.health-report {
  padding: 16px;
}
.report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.header-context {
  font-size: 13px;
  font-weight: 400;
  color: #909399;
  margin-left: 8px;
}
.header-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.summary-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.badge {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 72px;
  padding: 8px 16px;
  border-radius: 8px;
}
.badge-count {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
}
.badge-label {
  font-size: 12px;
  margin-top: 4px;
}
.badge-critical { background: #fee2e2; color: #b91c1c; }
.badge-warning { background: #fef3c7; color: #92400e; }
.badge-ok { background: #ecfdf5; color: #047857; }
.coverage-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #909399;
  cursor: help;
}
.pos-only-alert {
  margin-bottom: 16px;
}
.pos-only-alert :deep(.el-button) {
  margin-top: 8px;
}
.card-wall {
  margin-top: 8px;
}
.report-footer {
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
}
</style>
