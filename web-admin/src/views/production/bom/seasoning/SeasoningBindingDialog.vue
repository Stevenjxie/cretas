<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElNotification } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { bomSeasoningApi, type SeasoningBindingView, type SeasoningProcessView } from '@/api/bom';
import { get } from '@/api/request';
import { findDuplicateBinding } from './seasoningModel';

export interface SeasoningMaterialOption {
  id: string;
  name: string;
  code?: string | null;
  unit?: string | null;
  movingAvgPrice?: number | null;
}

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  process: SeasoningProcessView | null;
  binding: SeasoningBindingView | null;
  materials: SeasoningMaterialOption[];
  revision: number;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
  conflict: [];
}>();
const router = useRouter();
const route = useRoute();

const form = reactive({
  materialTypeId: '',
  dosagePerKgG: null as number | null,
  potEnabled: false,
  subsequentPercent: 50 as number | null,
  countInSeasoning: true,
  remark: '',
});
const saving = reactive({ value: false });
const dosageUnit = ref<'g' | 'kg'>('g');
const selectedMaterial = computed(() => props.materials.find((item) => item.id === form.materialTypeId));
const refreshedMovingAvgPrice = ref<number | null | undefined>(undefined);
const effectiveMovingAvgPrice = computed(() => (
  refreshedMovingAvgPrice.value === undefined
    ? selectedMaterial.value?.movingAvgPrice
    : refreshedMovingAvgPrice.value
));
const missingMovingAvgPrice = computed(() => (
  selectedMaterial.value != null
  && (effectiveMovingAvgPrice.value == null || Number(effectiveMovingAvgPrice.value) <= 0)
));
const dosageDisplayValue = computed<number | null>({
  get: () => form.dosagePerKgG == null
    ? null
    : dosageUnit.value === 'kg' ? form.dosagePerKgG / 1000 : form.dosagePerKgG,
  set: (value) => {
    form.dosagePerKgG = value == null ? null : value * (dosageUnit.value === 'kg' ? 1000 : 1);
  },
});

watch(() => [props.modelValue, props.binding] as const, () => {
  if (!props.modelValue) return;
  form.materialTypeId = props.binding?.materialTypeId || '';
  form.dosagePerKgG = props.binding?.dosagePerKgG ?? null;
  dosageUnit.value = (props.binding?.dosagePerKgG ?? 0) >= 1000 ? 'kg' : 'g';
  form.potEnabled = props.binding?.subsequentPotRatio != null;
  form.subsequentPercent = props.binding?.subsequentPotRatio == null ? 50 : props.binding.subsequentPotRatio * 100;
  form.countInSeasoning = props.binding?.countInSeasoning ?? true;
  form.remark = props.binding?.remark || '';
}, { immediate: true });
watch(() => form.materialTypeId, () => { refreshedMovingAvgPrice.value = undefined; });

function isRevisionConflict(error: unknown): boolean {
  const candidate = error as { response?: { status?: number }; status?: number; code?: string };
  return candidate.response?.status === 409 || candidate.status === 409 || candidate.code === 'SEASONING_REVISION_CONFLICT';
}

async function submit() {
  if (!props.process) return;
  if (!form.materialTypeId) return ElMessage.warning('请选择调料');
  if (form.dosagePerKgG == null || form.dosagePerKgG <= 0) return ElMessage.warning('投入量必须大于 0');
  if (form.potEnabled && (form.subsequentPercent == null || form.subsequentPercent < 0 || form.subsequentPercent > 100)) {
    return ElMessage.warning('后续锅比例必须在 0% 到 100% 之间');
  }
  const duplicate = findDuplicateBinding(props.process, form.materialTypeId, props.binding?.id);
  if (duplicate) return ElMessage.warning('该调料已在本工序配置');
  if (missingMovingAvgPrice.value) {
    ElNotification({
      title: '该调料尚无移动平均价',
      message: '表单内容已保留。请点击下方“去配置价格”在新标签页补充价格，完成后回到本页即可继续保存。',
      type: 'warning', duration: 0, showClose: true,
    });
    return;
  }

  saving.value = true;
  try {
    const payload = {
      materialTypeId: form.materialTypeId,
      dosagePerKgG: form.dosagePerKgG,
      subsequentPotRatio: form.potEnabled ? Number(form.subsequentPercent) / 100 : null,
      countInSeasoning: form.countInSeasoning,
      remark: form.remark.trim() || null,
      expectedRevision: props.revision,
    };
    if (props.binding) {
      await bomSeasoningApi.updateBinding(props.factoryId, props.recipeId, props.binding.id, payload);
    } else {
      await bomSeasoningApi.createBinding(props.factoryId, props.recipeId, props.process.workProcessId, payload);
    }
    emit('update:modelValue', false);
    emit('saved');
  } catch (error: unknown) {
    if (isRevisionConflict(error)) {
      ElMessage.warning('配方已被其他人修改，请重新加载后再操作');
      emit('conflict');
    } else {
      ElMessage.error((error as { message?: string }).message || '保存调料失败');
    }
  } finally {
    saving.value = false;
  }
}

function goConfigureMaterialPrice() {
  const keyword = selectedMaterial.value?.name || '';
  const target = router.resolve({
    path: '/warehouse/material-types',
    query: { _returnTo: route.fullPath, ...(keyword ? { keyword } : {}) },
  }).href;
  window.open(target, '_blank', 'noopener');
  window.addEventListener('focus', refreshSelectedMaterialPrice, { once: true });
}

async function refreshSelectedMaterialPrice() {
  if (!form.materialTypeId) return;
  try {
    const response = await get<SeasoningMaterialOption[]>(`/${props.factoryId}/raw-material-types/active`);
    const material = response.success && Array.isArray(response.data)
      ? response.data.find((item) => item.id === form.materialTypeId)
      : undefined;
    refreshedMovingAvgPrice.value = material?.movingAvgPrice ?? null;
    if (!missingMovingAvgPrice.value) ElMessage.success('移动平均价已更新，可继续保存');
  } catch {
    ElMessage.warning('价格读取失败，请稍后点击“重新读取价格”');
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="binding ? '编辑工序调料' : '添加工序调料'" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="145px" data-testid="seasoning-binding-dialog">
      <el-form-item label="投入工序">
        <el-input data-testid="locked-process-context" :model-value="process?.processName" disabled />
        <div class="form-tip">工序由入口锁定，保存只影响本工序。</div>
      </el-form-item>
      <el-form-item label="调料" required>
        <el-select v-model="form.materialTypeId" filterable placeholder="从辅料/调料档案选择" style="width: 100%">
          <el-option v-for="material in materials" :key="material.id" :label="material.name" :value="material.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="投入数量" required>
        <div class="dosage-sentence" data-testid="seasoning-dosage-sentence">
          <span>每生产 1 kg 本工序半成品，需要投入</span>
          <el-input-number v-model="dosageDisplayValue" :min="0" :precision="4" :controls="false" />
          <el-select v-model="dosageUnit" style="width: 84px">
            <el-option label="g" value="g" />
            <el-option label="kg" value="kg" />
          </el-select>
        </div>
        <div class="form-tip">保存时系统统一换算为 g/kg，显示可自由切换 g 或 kg。</div>
      </el-form-item>
      <el-form-item label="按锅序计算"><el-switch v-model="form.potEnabled" /></el-form-item>
      <template v-if="form.potEnabled">
        <el-form-item label="第一锅"><el-input model-value="100%" disabled /></el-form-item>
        <el-form-item label="后续锅占第一锅">
          <el-input-number v-model="form.subsequentPercent" :min="0" :max="100" :precision="2" /><span class="suffix">%</span>
        </el-form-item>
      </template>
      <el-form-item label="自动单价">
        <el-input :model-value="missingMovingAvgPrice ? '保存前需先配置移动平均价' : `¥${effectiveMovingAvgPrice}`" disabled />
      </el-form-item>
      <el-alert
        v-if="missingMovingAvgPrice"
        type="warning"
        :closable="false"
        show-icon
        title="该调料缺少移动平均价，暂不能保存"
      >
        <template #default>
          <span>当前表单会保留。</span>
          <el-button link type="primary" data-testid="configure-seasoning-price" @click="goConfigureMaterialPrice">去配置价格</el-button>
          <el-button link type="primary" data-testid="refresh-seasoning-price" @click="refreshSelectedMaterialPrice">重新读取价格</el-button>
        </template>
      </el-alert>
      <el-form-item label="成本核算"><el-switch v-model="form.countInSeasoning" active-text="计入" inactive-text="不计入" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving.value" @click="submit">保存到本工序</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-tip { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.suffix { margin-left: 6px; }
.dosage-sentence { display: flex; align-items: center; gap: 8px; width: 100%; flex-wrap: wrap; }
.dosage-sentence :deep(.el-input-number) { width: 130px; }
</style>
