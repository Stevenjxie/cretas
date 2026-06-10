<script setup lang="ts">
/**
 * SP10: 三价对比看板
 * GET /api/mobile/{factoryId}/rd/quotations/{taskId}/three-price-comparison
 * 展示: 预报价 / 中报价 / 实际成本  + 超支百分比 badge
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, ArrowRight, Money } from '@element-plus/icons-vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const taskId = computed(() => route.params.taskId as string);

const loading = ref(false);

interface VarianceAlert {
  stage: string;
  pct: number | null;
  alert: boolean;
}

interface ThreePriceData {
  priceQuote: number | null;
  midQuote: number | null;
  actualCost: number | null;
  varianceAlerts: VarianceAlert[];
  thresholdPct: number;
  [key: string]: unknown;
}

const threePrice = ref<ThreePriceData | null>(null);

async function loadData() {
  if (!factoryId.value || !taskId.value) return;
  // Guard: if taskId looks like a keyword (not a UUID/numeric ID), redirect to list
  if (taskId.value === 'three-price' || taskId.value === 'new') {
    ElMessage({ message: '请从报价列表选择具体任务再查看三价对比', type: 'warning', duration: 4000, showClose: true });
    router.replace('/rd/samples');
    return;
  }
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/rd/quotations/${taskId.value}/three-price-comparison`);
    if (res.success && res.data) {
      threePrice.value = res.data as ThreePriceData;
    }
  } catch { /* interceptor */ }
  finally { loading.value = false; }
}

onMounted(loadData);

function formatPrice(val: number | null | undefined): string {
  if (val == null) return '—';
  return `¥${Number(val).toFixed(4)}/kg`;
}

function getVarianceAlert(stage: string): VarianceAlert | undefined {
  return threePrice.value?.varianceAlerts?.find((a) => a.stage === stage);
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="display:flex;align-items:center;gap:12px">
            <el-button link @click="router.back()">← 返回</el-button>
            <span style="font-size:16px;font-weight:600">三价对比看板</span>
          </div>
          <el-button @click="loadData" :icon="Refresh">刷新</el-button>
        </div>
      </template>

      <!-- 无价格权限 -->
      <el-alert v-if="!canViewPrice" type="warning" :closable="false"
        title="您的角色无成本查看权限，三价对比受脱敏限制" style="margin-bottom:16px" />

      <div v-if="threePrice" style="display:flex;gap:16px;flex-wrap:wrap">
        <!-- 预报价 -->
        <el-card style="flex:1;min-width:240px;border:2px solid #409eff" shadow="hover">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <el-icon style="color:#409eff;font-size:18px"><Money /></el-icon>
              <span style="font-weight:600;color:#409eff">预报价</span>
              <el-tag type="info" size="small">PRE</el-tag>
            </div>
          </template>
          <div style="text-align:center;padding:16px 0">
            <div style="font-size:28px;font-weight:700;color:#409eff">
              {{ formatPrice(threePrice.priceQuote) }}
            </div>
            <div style="color:#909399;font-size:12px;margin-top:8px">研发经验估算</div>
          </div>
        </el-card>

        <!-- 预→中 差异 -->
        <div style="display:flex;align-items:center;padding:0 8px">
          <div style="text-align:center">
            <div v-if="getVarianceAlert('PRE_VS_MID')">
              <el-tag
                :type="getVarianceAlert('PRE_VS_MID')?.alert ? 'danger' : 'success'"
                size="large"
              >
                {{ getVarianceAlert('PRE_VS_MID')?.pct != null ? `${getVarianceAlert('PRE_VS_MID')!.pct!.toFixed(1)}%` : '—' }}
              </el-tag>
              <div style="font-size:11px;color:#909399;margin-top:4px">预→中</div>
            </div>
            <el-icon style="font-size:24px;color:#c0c4cc"><ArrowRight /></el-icon>
          </div>
        </div>

        <!-- 中报价 -->
        <el-card style="flex:1;min-width:240px;border:2px solid #e6a23c" shadow="hover">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <el-icon style="color:#e6a23c;font-size:18px"><Money /></el-icon>
              <span style="font-weight:600;color:#e6a23c">中报价</span>
              <el-tag type="warning" size="small">MID</el-tag>
            </div>
          </template>
          <div style="text-align:center;padding:16px 0">
            <template v-if="threePrice.midQuote != null">
              <div style="font-size:28px;font-weight:700;color:#e6a23c">
                {{ formatPrice(threePrice.midQuote) }}
              </div>
              <div style="color:#909399;font-size:12px;margin-top:8px">试制批次实际汇算</div>
            </template>
            <template v-else>
              <el-tag type="info" size="large">等待中试</el-tag>
              <div style="color:#909399;font-size:12px;margin-top:8px">小试批次完成后汇算</div>
            </template>
            <!-- 超支预警 -->
            <el-alert
              v-if="getVarianceAlert('PRE_VS_MID')?.alert"
              type="error"
              :closable="false"
              style="margin-top:8px;text-align:left"
              :title="`⚠️ 中试成本超出预报价 ${getVarianceAlert('PRE_VS_MID')?.pct?.toFixed(1)}%，超出阈值 ${threePrice.thresholdPct}%`"
            />
          </div>
        </el-card>

        <!-- 中→实际 差异 -->
        <div style="display:flex;align-items:center;padding:0 8px">
          <div style="text-align:center">
            <div v-if="getVarianceAlert('MID_VS_ACTUAL')">
              <el-tag
                :type="getVarianceAlert('MID_VS_ACTUAL')?.alert ? 'danger' : 'success'"
                size="large"
              >
                {{ getVarianceAlert('MID_VS_ACTUAL')?.pct != null ? `${getVarianceAlert('MID_VS_ACTUAL')!.pct!.toFixed(1)}%` : '—' }}
              </el-tag>
              <div style="font-size:11px;color:#909399;margin-top:4px">中→实际</div>
            </div>
            <el-icon style="font-size:24px;color:#c0c4cc"><ArrowRight /></el-icon>
          </div>
        </div>

        <!-- 实际成本 -->
        <el-card style="flex:1;min-width:240px;border:2px solid #67c23a" shadow="hover">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <el-icon style="color:#67c23a;font-size:18px"><Money /></el-icon>
              <span style="font-weight:600;color:#67c23a">实际成本</span>
              <el-tag type="success" size="small">ACTUAL</el-tag>
            </div>
          </template>
          <div style="text-align:center;padding:16px 0">
            <template v-if="threePrice.actualCost != null">
              <div style="font-size:28px;font-weight:700;color:#67c23a">
                {{ formatPrice(threePrice.actualCost) }}
              </div>
              <div style="color:#909399;font-size:12px;margin-top:8px">出库结算后真实成本</div>
            </template>
            <template v-else>
              <el-tag type="info" size="large">等待出库结算</el-tag>
              <div style="color:#909399;font-size:12px;margin-top:8px">SP3 成本引擎计算</div>
            </template>
            <!-- 超支预警 -->
            <el-alert
              v-if="getVarianceAlert('MID_VS_ACTUAL')?.alert"
              type="error"
              :closable="false"
              style="margin-top:8px;text-align:left"
              :title="`⚠️ 实际成本超出中报价 ${getVarianceAlert('MID_VS_ACTUAL')?.pct?.toFixed(1)}%，超出阈值 ${threePrice.thresholdPct}%`"
            />
          </div>
        </el-card>
      </div>

      <el-empty v-if="!threePrice && !loading" description="暂无三价对比数据，请先提交预报价" :image-size="80" />

      <!-- 阈值说明 -->
      <div v-if="threePrice" style="margin-top:16px;color:#909399;font-size:12px">
        超支阈值：{{ threePrice.thresholdPct }}%（超出此阈值则触发红色预警）
      </div>
    </el-card>
  </div>
</template>
