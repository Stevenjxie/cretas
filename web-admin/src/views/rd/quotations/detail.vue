<script setup lang="ts">
/**
 * SP10: 报价任务详情/表单
 * - laborPerKg 输入 (元/kg 成品口径, 经验填写)
 * - BOM 材料成本自动带入展示 (bomMaterialCost)
 * - 预成本价实时展示 = bomMaterialCost + laborPerKg
 * - 提交报价 dialog 含 fool-proof context (样品名+编号)
 * - 跳转至三价对比 + 中报价汇算入口
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('rd'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const taskId = computed(() => route.params.taskId as string);

const loading = ref(false);
const task = ref<TableRow | null>(null);

// 预报价提交表单
const submitDialogVisible = ref(false);
const submitting = ref(false);
const submitForm = ref({
  materialCost: '' as string | number,
  laborCost: '' as string | number,
  overheadCost: '' as string | number,
  suggestedPrice: '' as string | number,
  laborPerKg: '' as string | number,
  costHighReason: '',
});

// 预成本价 = bomMaterialCost + laborPerKg (展示用, 不发送)
const estimatedCostPerKg = computed(() => {
  const mat = parseFloat(String(task.value?.bomMaterialCost || 0));
  const lab = parseFloat(String(submitForm.value.laborPerKg || 0));
  if (isNaN(mat) || isNaN(lab)) return null;
  return (mat + lab).toFixed(4);
});

const quoteStageMap: Record<string, { text: string; type: string }> = {
  PRE: { text: '预报价', type: 'info' },
  MID_PENDING: { text: '等待中试', type: 'warning' },
  MID: { text: '中报价已汇算', type: 'success' },
  FINAL: { text: '最终确认', type: 'success' },
};
const statusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待报价', type: 'warning' },
  IN_PROGRESS: { text: '报价中', type: '' },
  QUOTED: { text: '已报价', type: 'success' },
  CONFIRMED: { text: '已确认', type: 'success' },
};

async function loadTask() {
  if (!factoryId.value || !taskId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/rd/quotations`, { params: { size: 200 } });
    if (res.success && res.data) {
      const list: TableRow[] = res.data.content || res.data || [];
      task.value = list.find((t) => String(t.id) === taskId.value) || null;
      if (task.value) {
        // Pre-fill form with existing values
        submitForm.value.materialCost = task.value.materialCost ?? '';
        submitForm.value.laborCost = task.value.laborCost ?? '';
        submitForm.value.overheadCost = task.value.overheadCost ?? '';
        submitForm.value.suggestedPrice = task.value.suggestedPrice ?? '';
        submitForm.value.laborPerKg = task.value.laborPerKg ?? '';
      }
    }
  } catch { /* interceptor handles */ }
  finally { loading.value = false; }
}

onMounted(loadTask);

function openSubmitDialog() {
  if (!task.value) return;
  submitDialogVisible.value = true;
}

async function handleSubmitQuotation() {
  const { suggestedPrice } = submitForm.value;
  if (!suggestedPrice) {
    ElMessage.warning('请填写建议售价');
    return;
  }
  submitting.value = true;
  try {
    const { materialCost, laborCost, overheadCost } = submitForm.value;
    const payload: Record<string, unknown> = {
      suggestedPrice: Number(suggestedPrice),
    };
    // 总额字段从任务数据带入（只读展示），有值则一并回传
    if (materialCost !== '' && materialCost != null) payload.materialCost = Number(materialCost);
    if (laborCost !== '' && laborCost != null) payload.laborCost = Number(laborCost);
    if (overheadCost !== '' && overheadCost != null) payload.overheadCost = Number(overheadCost);
    if (submitForm.value.laborPerKg) {
      payload.laborPerKg = Number(submitForm.value.laborPerKg);
    }
    if (submitForm.value.costHighReason) {
      payload.costHighReason = submitForm.value.costHighReason;
    }
    const res = await post(`/${factoryId.value}/rd/quotations/${taskId.value}/submit`, payload);
    if (res.success !== false) {
      ElMessage.success('预报价已提交');
      submitDialogVisible.value = false;
      await loadTask();
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch { /* interceptor */ }
  finally { submitting.value = false; }
}

function goThreePrice() {
  router.push(`/rd/quotations/${taskId.value}/three-price`);
}

function goMidQuote() {
  router.push(`/rd/mid-quotes/${taskId.value}`);
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="display:flex;align-items:center;gap:12px">
            <el-button link @click="router.back()">← 返回</el-button>
            <span style="font-size:16px;font-weight:600">报价任务详情</span>
            <el-tag v-if="task?.quoteStage" :type="quoteStageMap[task.quoteStage]?.type || 'info'" size="small">
              {{ quoteStageMap[task?.quoteStage]?.text || task.quoteStage }}
            </el-tag>
            <el-tag v-if="task?.status" :type="statusMap[task.status]?.type || 'info'" size="small">
              {{ statusMap[task?.status]?.text || task.status }}
            </el-tag>
          </div>
          <div style="display:flex;gap:8px" v-if="task && canWrite">
            <el-button @click="goThreePrice" type="info" plain>三价对比</el-button>
            <el-button @click="goMidQuote" type="warning" plain>中报价汇算</el-button>
            <el-button
              v-if="['PENDING','IN_PROGRESS'].includes(String(task?.status))"
              type="primary"
              @click="openSubmitDialog"
            >提交预报价</el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!task && !loading" description="报价任务未找到" />

      <template v-if="task">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="任务编号">{{ task.taskNumber || task.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[task.status]?.type || 'info'" size="small">
              {{ statusMap[task.status]?.text || task.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="报价阶段">
            <el-tag :type="quoteStageMap[task.quoteStage]?.type || 'info'" size="small">
              {{ quoteStageMap[task.quoteStage]?.text || task.quoteStage || 'PRE' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="样品名称">{{ task.sampleName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="样品编号">{{ task.sampleCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ task.createdAt ? String(task.createdAt).slice(0, 10) : '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 成本信息 (PriceSensitive: canViewPrice) -->
        <el-card shadow="never" style="margin-top:16px" v-if="canViewPrice">
          <template #header>
            <span style="font-weight:600">成本与报价信息</span>
          </template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="BOM 材料成本 (自动)">
              <span v-if="task.bomMaterialCost != null">¥{{ task.bomMaterialCost }}/kg</span>
              <el-text v-else type="info">BOM 尚未完成，材料成本待填</el-text>
            </el-descriptions-item>
            <el-descriptions-item label="人工成本 元/kg (SP10)">
              <span v-if="task.laborPerKg != null">¥{{ task.laborPerKg }}/kg</span>
              <el-text v-else type="warning">—（待填写）</el-text>
            </el-descriptions-item>
            <el-descriptions-item label="预估成本价/kg">
              <span v-if="task.bomMaterialCost != null && task.laborPerKg != null" style="color:#e6a23c;font-weight:600">
                ¥{{ (parseFloat(String(task.bomMaterialCost)) + parseFloat(String(task.laborPerKg))).toFixed(4) }}/kg
              </span>
              <el-text v-else type="info">—（需 BOM 成本 + 人工）</el-text>
            </el-descriptions-item>
            <el-descriptions-item label="材料成本（总）">
              {{ task.materialCost != null ? `¥${task.materialCost}` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="人工成本（总）">
              {{ task.laborCost != null ? `¥${task.laborCost}` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="间接成本（总）">
              {{ task.overheadCost != null ? `¥${task.overheadCost}` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="建议售价">
              {{ task.suggestedPrice != null ? `¥${task.suggestedPrice}` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="最终报价">
              {{ task.finalPrice != null ? `¥${task.finalPrice}` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="毛利率">
              {{ task.profitMargin != null ? `${task.profitMargin}%` : '—' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 无价格权限时 -->
        <el-alert v-if="!canViewPrice" type="info" :closable="false" style="margin-top:16px" title="您的角色无成本查看权限，成本字段已隐藏" />

        <!-- BOM 未完成提示 -->
        <el-alert
          v-if="task.bomMaterialCost == null && canViewPrice"
          type="warning"
          :closable="false"
          style="margin-top:12px"
          title="BOM 尚未完成，材料成本暂无法自动带入"
        >
          <template #default>
            BOM 尚未完成，材料成本由您手填。
            <el-button type="primary" link size="small" @click="router.push('/production/bom')">前往 BOM 配置</el-button>
          </template>
        </el-alert>
      </template>
    </el-card>

    <!-- 提交预报价 Dialog (Fool-Proof Rule 1+2+3+4) -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="`提交预报价 — ${task?.sampleName || ''} ${task?.sampleCode ? '(' + task.sampleCode + ')' : ''}`"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <!-- Rule 1: 预先显示边界 (BOM 成本) -->
      <el-alert
        v-if="task?.bomMaterialCost != null"
        type="success"
        :closable="false"
        style="margin-bottom:16px"
        :title="`本产品 BOM 材料成本: ¥${task.bomMaterialCost}/kg（已自动带入）`"
      />
      <el-alert
        v-else
        type="warning"
        :closable="false"
        style="margin-bottom:16px"
        title="BOM 尚未完成，材料成本请手填"
      />

      <el-form label-width="120px">
        <!-- SP10 核心新字段: laborPerKg -->
        <el-form-item label="人工 元/kg成品" required>
          <el-input-number
            v-model="submitForm.laborPerKg"
            :min="0"
            :precision="4"
            style="width:200px"
            placeholder="经验值填写"
          />
          <el-text type="info" style="margin-left:8px;font-size:12px">元/kg 成品（R&D 经验估算）</el-text>
        </el-form-item>

        <!-- 实时显示预估总成本 -->
        <el-form-item label="预估成本/kg">
          <el-text v-if="estimatedCostPerKg" style="color:#e6a23c;font-weight:600">
            ¥{{ estimatedCostPerKg }}/kg（BOM 材料 + 人工）
          </el-text>
          <el-text v-else type="info">填写人工成本后自动计算</el-text>
        </el-form-item>

        <!-- 总额三项：从任务数据带入只读展示，不可手动录入（批量尺寸换算引擎待 R13 上线） -->
        <el-form-item label="材料成本（总）">
          <el-text v-if="submitForm.materialCost !== '' && submitForm.materialCost != null" style="font-weight:600">
            ¥{{ submitForm.materialCost }}
          </el-text>
          <el-text v-else type="info">—（待 R13 批量换算引擎）</el-text>
          <el-text type="info" style="margin-left:8px;font-size:12px">元（总金额，只读）</el-text>
        </el-form-item>
        <el-form-item label="人工成本（总）">
          <el-text v-if="submitForm.laborCost !== '' && submitForm.laborCost != null" style="font-weight:600">
            ¥{{ submitForm.laborCost }}
          </el-text>
          <el-text v-else type="info">—（待 R13 批量换算引擎）</el-text>
        </el-form-item>
        <el-form-item label="间接成本（总）">
          <el-text v-if="submitForm.overheadCost !== '' && submitForm.overheadCost != null" style="font-weight:600">
            ¥{{ submitForm.overheadCost }}
          </el-text>
          <el-text v-else type="info">—（待 R13 批量换算引擎）</el-text>
        </el-form-item>
        <el-form-item label="建议售价" required>
          <el-input-number v-model="submitForm.suggestedPrice" :min="0" :precision="2" style="width:200px" />
        </el-form-item>
        <!-- Rule 3: 成本偏高原因 dropdown -->
        <el-form-item label="成本说明">
          <el-select v-model="submitForm.costHighReason" clearable placeholder="成本偏高原因（可选）" style="width:100%">
            <el-option label="原料价格波动" value="原料价格波动" />
            <el-option label="季节性供应紧张" value="季节性供应紧张" />
            <el-option label="新工艺学习期" value="新工艺学习期" />
            <el-option label="小批量试制成本高" value="小批量试制成本高" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitQuotation">提交预报价</el-button>
      </template>
    </el-dialog>
  </div>
</template>
