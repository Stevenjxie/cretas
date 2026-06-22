<script setup lang="ts">
/**
 * SP10: 中报价汇算卡
 * POST /api/mobile/{factoryId}/rd/quotations/{taskId}/calculate-mid-quote
 * 展示: 试制批次汇算结果 + 与预报价对比 + 超支预警
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('rd'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// taskId from route (task 与 mid-quote 同路由参数)
const taskId = computed(() => route.params.taskId as string);

const loading = ref(false);
const calculating = ref(false);
const midQuoteResult = ref<TableRow | null>(null);

// 汇算表单
const calcForm = ref({
  trialBatchId: '' as string | number,
  materialCostPerKg: null as number | null,
  laborCostPerKg: null as number | null,
  overheadCostPerKg: null as number | null,
  varianceThresholdPct: null as number | null,
});

// 试制批次列表 (isTrial=true)
const trialBatches = ref<TableRow[]>([]);

async function loadTrialBatches() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/batches`, { params: { isTrial: true, size: 100 } });
    if (res.success && res.data) {
      trialBatches.value = res.data.content || res.data || [];
    }
  } catch { /* optional */ }
}

async function handleCalculate() {
  if (!calcForm.value.trialBatchId) {
    ElMessage.warning('请选择试制批次');
    return;
  }
  if (calcForm.value.materialCostPerKg == null) {
    ElMessage.warning('请填写材料成本/kg（移动均价自动算待 R12 上线）');
    return;
  }
  calculating.value = true;
  try {
    const payload: Record<string, unknown> = {
      trialBatchId: String(calcForm.value.trialBatchId),
    };
    if (calcForm.value.materialCostPerKg != null) payload.materialCostPerKg = calcForm.value.materialCostPerKg;
    if (calcForm.value.laborCostPerKg != null) payload.laborCostPerKg = calcForm.value.laborCostPerKg;
    if (calcForm.value.overheadCostPerKg != null) payload.overheadCostPerKg = calcForm.value.overheadCostPerKg;
    if (calcForm.value.varianceThresholdPct != null) payload.varianceThresholdPct = calcForm.value.varianceThresholdPct;

    const res = await post(`/${factoryId.value}/rd/quotations/${taskId.value}/calculate-mid-quote`, payload);
    if (res.success !== false) {
      midQuoteResult.value = res.data as TableRow;
      ElMessage.success('中报价汇算完成');
      // 超支预警 sticky
      if (res.data?.varianceAlert) {
        ElMessage({
          message: `⚠️ 中试成本超出预报价 ${res.data.costVariancePct?.toFixed(1)}%，超出设定阈值`,
          type: 'warning',
          duration: 0,
          showClose: true,
        });
      }
    } else {
      ElMessage({ message: res.message || '汇算失败', type: 'error', duration: 0, showClose: true });
    }
  } catch { /* interceptor */ }
  finally { calculating.value = false; }
}

onMounted(loadTrialBatches);
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="display:flex;align-items:center;gap:12px">
            <el-button link @click="router.back()">← 返回</el-button>
            <span style="font-size:16px;font-weight:600">中报价汇算</span>
          </div>
          <el-button @click="router.push(`/rd/quotations/${taskId}/three-price`)" type="info" plain>
            查看三价对比
          </el-button>
        </div>
      </template>

      <!-- 无价格权限 -->
      <el-alert v-if="!canViewPrice" type="warning" :closable="false"
        title="您的角色无成本查看权限" style="margin-bottom:16px" />

      <!-- 汇算触发表单 -->
      <el-card v-if="canWrite" shadow="never" style="margin-bottom:16px;background:#f8faff;border:1px solid #d9e7ff">
        <template #header><span style="font-weight:600">触发中报价汇算</span></template>
        <el-form label-width="140px">
          <el-form-item label="选择试制批次" required>
            <el-select
              v-model="calcForm.trialBatchId"
              placeholder="请选择试制批次 (is_trial=true)"
              filterable
              style="width:320px"
            >
              <el-option
                v-for="b in trialBatches"
                :key="b.id"
                :label="`${b.batchNumber || b.id} · ${b.productName || '未知产品'}${b.status ? ' [' + b.status + ']' : ''}`"
                :value="b.id"
              />
            </el-select>
            <el-text type="info" style="margin-left:8px;font-size:12px">
              仅显示 is_trial=true 的批次；任务 {{ taskId }}
            </el-text>
          </el-form-item>
          <el-form-item label="材料成本/kg" required>
            <el-input-number v-model="calcForm.materialCostPerKg" :min="0" :precision="4" style="width:200px" />
            <el-text type="info" style="margin-left:8px;font-size:12px">必填（移动均价自动算待 R12 上线）</el-text>
          </el-form-item>
          <el-form-item label="人工成本/kg (手填)">
            <el-input-number v-model="calcForm.laborCostPerKg" :min="0" :precision="4" style="width:200px" />
          </el-form-item>
          <el-form-item label="间接成本/kg (手填)">
            <el-input-number v-model="calcForm.overheadCostPerKg" :min="0" :precision="4" style="width:200px" />
          </el-form-item>
          <el-form-item label="超支阈值 (%)">
            <el-input-number v-model="calcForm.varianceThresholdPct" :min="0" :max="100" :precision="1" style="width:160px" />
            <el-text type="info" style="margin-left:8px;font-size:12px">留空则用全局配置（默认10%）</el-text>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="calculating" @click="handleCalculate">开始汇算</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 汇算结果 -->
      <template v-if="midQuoteResult">
        <el-alert
          v-if="midQuoteResult.varianceAlert"
          type="error"
          :closable="false"
          style="margin-bottom:12px"
          :title="`⚠️ 中试成本超出预报价 ${midQuoteResult.costVariancePct?.toFixed ? midQuoteResult.costVariancePct.toFixed(1) : midQuoteResult.costVariancePct}%，仍可确认`"
        />
        <el-card shadow="never" style="border:2px solid #e6a23c">
          <template #header><span style="font-weight:600;color:#e6a23c">中报价汇算结果</span></template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="试制产出 kg">{{ midQuoteResult.trialOutputKg ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="实际出成率">
              {{ midQuoteResult.actualYieldRate != null ? `${(Number(midQuoteResult.actualYieldRate) * 100).toFixed(1)}%` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="midQuoteResult.status === 'CONFIRMED' ? 'success' : 'warning'" size="small">
                {{ ({ DRAFT: '草稿', CALCULATED: '已汇算', CONFIRMED: '已确认' } as Record<string, string>)[String(midQuoteResult.status)] || midQuoteResult.status }}
              </el-tag>
            </el-descriptions-item>
            <template v-if="canViewPrice">
              <el-descriptions-item label="材料成本/kg">
                {{ midQuoteResult.materialCostPerKg != null ? `¥${midQuoteResult.materialCostPerKg}/kg` : '部分缺失' }}
              </el-descriptions-item>
              <el-descriptions-item label="人工成本/kg">
                {{ midQuoteResult.laborCostPerKg != null ? `¥${midQuoteResult.laborCostPerKg}/kg` : '—（请补充报工）' }}
              </el-descriptions-item>
              <el-descriptions-item label="间接成本/kg">
                {{ midQuoteResult.overheadCostPerKg != null ? `¥${midQuoteResult.overheadCostPerKg}/kg` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="中试总成本/kg">
                <span style="font-size:16px;font-weight:700;color:#e6a23c">
                  {{ midQuoteResult.totalCostPerKg != null ? `¥${midQuoteResult.totalCostPerKg}/kg` : '—' }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="与预报价差异">
                <el-tag :type="midQuoteResult.varianceAlert ? 'danger' : 'success'" size="small">
                  {{ midQuoteResult.costVariancePct != null ? `${Number(midQuoteResult.costVariancePct).toFixed(1)}%` : '—' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="超支预警">
                <el-tag :type="midQuoteResult.varianceAlert ? 'danger' : 'success'" size="small">
                  {{ midQuoteResult.varianceAlert ? '⚠️ 已触发' : '✓ 正常' }}
                </el-tag>
              </el-descriptions-item>
            </template>
          </el-descriptions>
        </el-card>
      </template>

      <el-empty v-if="!midQuoteResult && !loading" description="尚未汇算，请选择试制批次后触发" :image-size="80" />
    </el-card>
  </div>
</template>
