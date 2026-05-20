<script setup lang="ts">
// Sprint 7 wave 2 T5 — 我的业绩看板.
// HJ Round 13 §15 业绩 6 项: 月/季/年 target 实际/gap/完成率/排名.
// 防呆:
//   R1 max — set target ¥10M 上限 (input :max + 提示文字)
//   R2 context — header 含 ownerId + period + year/periodNum
//   R3 dropdown — period <el-select>
import { ref, computed, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import {
  setTarget,
  getProgress,
  type SalesTargetProgress,
  type TargetPeriod,
} from '@/api/salesTarget';
import { useAuthStore } from '@/store/modules/auth';

const auth = useAuthStore();
const ownerId = computed<number | null>(() => {
  const u = auth.user;
  return u && typeof u.id === 'number' ? u.id : null;
});
const ownerName = computed<string>(() => auth.user?.username || '我');

const period = ref<TargetPeriod>('MONTH');
const year = ref<number>(new Date().getFullYear());
const periodNum = ref<number>(new Date().getMonth() + 1);

const progress = ref<SalesTargetProgress | null>(null);
const loading = ref(false);

const targetAmountInput = ref<number>(0);
const TARGET_MAX = 10000000;

const periodNumChoices = computed<{ value: number; label: string }[]>(() => {
  if (period.value === 'MONTH') {
    return Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 月` }));
  }
  if (period.value === 'QUARTER') {
    return [1, 2, 3, 4].map((q) => ({ value: q, label: `Q${q}` }));
  }
  return [{ value: 1, label: '全年' }];
});

watch(period, () => {
  // 重置 periodNum 到该 period 下合法范围首项
  if (period.value === 'MONTH') periodNum.value = new Date().getMonth() + 1;
  else if (period.value === 'QUARTER') periodNum.value = 1;
  else periodNum.value = 1;
});

async function load(): Promise<void> {
  if (ownerId.value === null) {
    ElMessage.warning('未取得当前用户身份, 请重新登录');
    return;
  }
  loading.value = true;
  try {
    progress.value = await getProgress(
      ownerId.value,
      period.value,
      year.value,
      periodNum.value,
    );
    targetAmountInput.value = progress.value.target || 0;
  } catch (e) {
    handleCatchError(e, '加载业绩进度失败');
  } finally {
    loading.value = false;
  }
}

async function saveTarget(): Promise<void> {
  if (ownerId.value === null) return;
  if (targetAmountInput.value <= 0) {
    ElMessage.warning('目标金额必须大于 0');
    return;
  }
  if (targetAmountInput.value > TARGET_MAX) {
    ElMessage.warning(`目标金额不能超过 ¥${TARGET_MAX.toLocaleString()} (¥10M 上限, 防误输)`);
    return;
  }
  try {
    await setTarget({
      ownerId: ownerId.value,
      period: period.value,
      year: year.value,
      periodNum: periodNum.value,
      targetAmount: targetAmountInput.value,
      createdBy: ownerId.value,
    });
    ElMessage.success('目标已保存');
    await load();
  } catch (e) {
    handleCatchError(e, '保存目标失败');
  }
}

onMounted(load);
</script>

<template>
  <div class="my-sales-target">
    <div class="header">
      <h2>我的业绩 — {{ ownerName }} ({{ year }}-{{ progress?.periodDisplay || '' }} {{ periodNum }})</h2>
      <p class="subtitle">月/季/年 销售目标 / 实际完成 / 完成率 / 排名 (HJ Round 13 §15 业绩 6 项)</p>
    </div>

    <div class="filter-bar">
      <el-form inline>
        <el-form-item label="周期">
          <el-select v-model="period" style="width: 120px">
            <el-option label="月度" value="MONTH" />
            <el-option label="季度" value="QUARTER" />
            <el-option label="年度" value="YEAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="年份">
          <el-input-number v-model="year" :min="2024" :max="2099" style="width: 120px" />
        </el-form-item>
        <el-form-item :label="period === 'MONTH' ? '月份' : period === 'QUARTER' ? '季度' : '全年'">
          <el-select v-model="periodNum" style="width: 120px" :disabled="period === 'YEAR'">
            <el-option
              v-for="opt in periodNumChoices"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-loading="loading" v-if="progress" class="kpi-grid">
      <el-card class="kpi">
        <div class="label">目标</div>
        <div class="value primary">¥ {{ Number(progress.target).toLocaleString() }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">实际</div>
        <div class="value success">¥ {{ Number(progress.actual).toLocaleString() }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">差额 (Gap)</div>
        <div class="value" :class="Number(progress.gap) <= 0 ? 'success' : 'warning'">
          ¥ {{ Number(progress.gap).toLocaleString() }}
        </div>
      </el-card>
      <el-card class="kpi">
        <div class="label">完成率</div>
        <div class="value" :class="Number(progress.completionRate) >= 100 ? 'success' : Number(progress.completionRate) >= 70 ? 'primary' : 'warning'">
          {{ progress.completionRate }}%
        </div>
      </el-card>
      <el-card class="kpi">
        <div class="label">销售排名</div>
        <div class="value">
          <template v-if="progress.rank">第 {{ progress.rank }} 名</template>
          <template v-else>未上榜</template>
        </div>
      </el-card>
    </div>

    <el-card class="set-target">
      <template #header>
        <strong>设置/更新本周期目标</strong>
        <span style="color: #999; font-size: 12px; margin-left: 8px">
          目标上限 ¥{{ TARGET_MAX.toLocaleString() }}, 防误输 (R1 防呆)
        </span>
      </template>
      <el-form inline @submit.prevent="saveTarget">
        <el-form-item label="目标金额">
          <el-input-number
            v-model="targetAmountInput"
            :min="0"
            :max="TARGET_MAX"
            :step="10000"
            controls-position="right"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveTarget">保存目标</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.my-sales-target {
  padding: 16px;
  .header h2 { margin: 0; }
  .subtitle { color: #909399; margin: 4px 0 16px; font-size: 13px; }
  .filter-bar { margin-bottom: 16px; }
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 16px;
    margin-bottom: 16px;
    .kpi {
      .label { color: #909399; font-size: 13px; }
      .value {
        font-size: 22px;
        font-weight: 600;
        margin-top: 8px;
        &.primary { color: #409eff; }
        &.success { color: #67c23a; }
        &.warning { color: #e6a23c; }
      }
    }
  }
  .set-target { margin-top: 16px; }
}
</style>
