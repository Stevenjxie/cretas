<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { showSingletonNotification } from '@/utils/singletonNotification';
import { useRoute, useRouter } from 'vue-router';
import { bomSeasoningApi, type BomItemSubstituteView, type SeasoningBindingView, type SeasoningProcessView } from '@/api/bom';
import { get } from '@/api/request';
import { convertUnit } from '@/api/unitContract';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';
import { findDuplicateBinding } from './seasoningModel';

export interface SeasoningMaterialOption {
  id: string;
  name: string;
  code?: string | null;
  unit?: string | null;
  movingAvgPrice?: number | null;
  /** 未税采购参考价；物料档案保存含税价时由后端按税率同步换算。 */
  unitPrice?: number | null;
}

const props = withDefaults(defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  process: SeasoningProcessView | null;
  binding: SeasoningBindingView | null;
  materials: SeasoningMaterialOption[];
  substituteRelations?: BomItemSubstituteView[];
  revision: number;
}>(), {
  substituteRelations: () => [],
});

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
  substituteMaterialTypeIds: [] as string[],
  substituteFactors: {} as Record<string, number | null>,
});
const saving = reactive({ value: false });
const dosageUnit = ref('kg');
const dosageUnitFactorsToG = ref<Record<string, number>>({ g: 1, kg: 1000 });
const materialUnitLoading = ref(false);
const selectedMaterial = computed(() => props.materials.find((item) => item.id === form.materialTypeId));
const selectedMaterialUnit = computed(() => selectedMaterial.value?.unit?.trim() || '');
const positivePrice = (value?: number | null): value is number => (
  value != null && Number.isFinite(Number(value)) && Number(value) > 0
);
const businessUnitLabel = (unit: string): string => {
  const code = canonicalUnitCode(unit);
  return ({ kg: '千克', g: '克', L: '升', mL: '毫升' } as Record<string, string>)[code]
    || displayUnit(code);
};
const standardBasisQuantity = computed(() => (
  props.process?.standardBasisQuantity ?? props.process?.basisQuantity ?? null
));
const standardBasisUnit = computed(() => (
  props.process?.standardBasisUnit ?? props.process?.basisUnit ?? null
));
const basisLabel = computed(() => {
  if (standardBasisQuantity.value == null || !standardBasisUnit.value) return '未解析';
  const quantity = Number(standardBasisQuantity.value).toFixed(4).replace(/\.?0+$/, '');
  return `${quantity} ${canonicalUnitCode(standardBasisUnit.value)}`;
});
const basisObjectLabel = computed(() => (
  props.process?.standardBasisMaterialKind === 'SEMI_FINISHED'
    ? '本工序半成品'
    : props.process?.standardBasisMaterialKind === 'FINISHED_GOOD'
      ? '本工序成品'
      : '本工序产出'
));
const dosageUnitOptions = computed(() => {
  const options = ['kg', 'g'];
  const materialUnit = selectedMaterialUnit.value;
  if (materialUnit && dosageUnitFactorsToG.value[materialUnit] && !options.includes(materialUnit)) {
    options.unshift(materialUnit);
  }
  return options;
});
const refreshedPrices = ref<Pick<SeasoningMaterialOption, 'movingAvgPrice' | 'unitPrice'> | undefined>();
const effectivePrice = computed(() => {
  const movingAvgPrice = refreshedPrices.value === undefined
    ? selectedMaterial.value?.movingAvgPrice
    : refreshedPrices.value.movingAvgPrice;
  if (positivePrice(movingAvgPrice)) {
    return { value: Number(movingAvgPrice), source: '移动平均库存成本' as const };
  }
  const purchaseReferencePrice = refreshedPrices.value === undefined
    ? selectedMaterial.value?.unitPrice
    : refreshedPrices.value.unitPrice;
  if (positivePrice(purchaseReferencePrice)) {
    return { value: Number(purchaseReferencePrice), source: '未税采购参考价' as const };
  }
  return null;
});
const missingEffectivePrice = computed(() => selectedMaterial.value != null && effectivePrice.value == null);
const automaticPriceLabel = computed(() => {
  if (!effectivePrice.value) return '保存前需先配置有效价格';
  const unit = selectedMaterialUnit.value ? businessUnitLabel(selectedMaterialUnit.value) : '单位';
  return `¥${effectivePrice.value.value.toFixed(4)} / ${unit}`;
});
const dosageDisplayValue = computed<number | null>({
  get: () => form.dosagePerKgG == null
    ? null
    : form.dosagePerKgG / (dosageUnitFactorsToG.value[dosageUnit.value] || 1),
  set: (value) => {
    form.dosagePerKgG = value == null
      ? null
      : value * (dosageUnitFactorsToG.value[dosageUnit.value] || 1);
  },
});

watch(() => [props.modelValue, props.binding] as const, () => {
  if (!props.modelValue) return;
  form.materialTypeId = props.binding?.materialTypeId || '';
  form.dosagePerKgG = props.binding?.dosagePerKgG ?? 1000;
  dosageUnit.value = props.binding && props.binding.dosagePerKgG < 1000 ? 'g' : 'kg';
  form.potEnabled = props.binding?.subsequentPotRatio != null;
  form.subsequentPercent = props.binding?.subsequentPotRatio == null ? 50 : props.binding.subsequentPotRatio * 100;
  form.countInSeasoning = props.binding?.countInSeasoning ?? true;
  form.remark = props.binding?.remark || '';
  form.substituteMaterialTypeIds = props.substituteRelations.map((relation) => relation.substituteMaterialTypeId);
  form.substituteFactors = Object.fromEntries(props.substituteRelations.map((relation) => [
    relation.substituteMaterialTypeId,
    relation.conversionFactor == null ? null : Number(relation.conversionFactor),
  ]));
}, { immediate: true });
watch(() => form.materialTypeId, () => {
  refreshedPrices.value = undefined;
  void loadSelectedMaterialUnit();
});

async function loadSelectedMaterialUnit(): Promise<void> {
  const unit = selectedMaterialUnit.value;
  if (!['g', 'kg', unit].includes(dosageUnit.value)) dosageUnit.value = 'kg';
  if (!unit || dosageUnitFactorsToG.value[unit]) return;
  materialUnitLoading.value = true;
  try {
    const response = await convertUnit(props.factoryId, {
      quantity: 1,
      fromUnit: unit,
      toUnit: 'g',
      scene: 'PRODUCTION',
      scale: 6,
      roundingMode: 'HALF_UP',
    });
    const factor = response.success ? Number(response.data?.quantity) : Number.NaN;
    if (Number.isFinite(factor) && factor > 0) {
      dosageUnitFactorsToG.value = { ...dosageUnitFactorsToG.value, [unit]: factor };
    } else {
      ElMessage.warning(`调料单位“${unit}”无法换算为重量，投入量暂按 kg/g 填写`);
    }
  } catch {
    ElMessage.warning(`调料单位“${unit}”换算读取失败，投入量暂按 kg/g 填写`);
  } finally {
    materialUnitLoading.value = false;
  }
}

function isRevisionConflict(error: unknown): boolean {
  const candidate = error as { response?: { status?: number }; status?: number; code?: string };
  return candidate.response?.status === 409 || candidate.status === 409 || candidate.code === 'SEASONING_REVISION_CONFLICT';
}

async function submit() {
  if (!props.process) return;
  if (props.process.standardUsageSupported !== true) {
    return ElMessage.warning('该工序的投入基准单位尚未形成可换算契约，暂不能保存标准辅料用量');
  }
  if (!form.materialTypeId) return ElMessage.warning('请选择调料');
  if (form.dosagePerKgG == null || form.dosagePerKgG <= 0) return ElMessage.warning('投入量必须大于 0');
  if (form.potEnabled && (form.subsequentPercent == null || form.subsequentPercent < 0 || form.subsequentPercent > 100)) {
    return ElMessage.warning('后续锅比例必须在 0% 到 100% 之间');
  }
  const duplicate = findDuplicateBinding(props.process, form.materialTypeId, props.binding?.id);
  if (duplicate) return ElMessage.warning('该调料已在本工序配置');
  if (missingEffectivePrice.value) {
    showSingletonNotification({
      title: '该调料尚无有效成本价格',
      message: '表单内容已保留。请维护移动平均库存成本或未税采购参考价，完成后回到本页重新读取即可继续保存。',
      type: 'warning', duration: 0, showClose: true,
    });
    return;
  }
  const missingFactor = form.substituteMaterialTypeIds.find((materialTypeId) => {
    const candidate = props.materials.find((item) => item.id === materialTypeId);
    return canonicalUnitCode(candidate?.unit) !== canonicalUnitCode(selectedMaterial.value?.unit)
      && (!form.substituteFactors[materialTypeId] || Number(form.substituteFactors[materialTypeId]) <= 0);
  });
  if (missingFactor) return ElMessage.warning('不同单位的替代辅料必须填写明确的等价换算系数');

  saving.value = true;
  try {
    const payload = {
      workflowProcessNodeId: props.process.workflowProcessNodeId,
      materialTypeId: form.materialTypeId,
      dosagePerKgG: form.dosagePerKgG,
      subsequentPotRatio: form.potEnabled ? Number(form.subsequentPercent) / 100 : null,
      countInSeasoning: form.countInSeasoning,
      remark: form.remark.trim() || null,
      substitutes: form.substituteMaterialTypeIds.map((materialTypeId) => ({
        materialTypeId,
        conversionFactor: canonicalUnitCode(props.materials.find((item) => item.id === materialTypeId)?.unit)
          === canonicalUnitCode(selectedMaterial.value?.unit)
          ? null
          : form.substituteFactors[materialTypeId] ?? null,
      })),
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
    refreshedPrices.value = {
      movingAvgPrice: material?.movingAvgPrice ?? null,
      unitPrice: material?.unitPrice ?? null,
    };
    if (!missingEffectivePrice.value && effectivePrice.value) {
      ElMessage.success(`已读取${effectivePrice.value.source}，可继续保存`);
    }
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
        <el-select v-model="form.materialTypeId" filterable placeholder="从辅料/调料档案选择…" style="width: 100%">
          <el-option v-for="material in materials" :key="material.id" :label="material.name" :value="material.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="替代辅料（可选）">
        <el-select
          v-model="form.substituteMaterialTypeIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择可替代本辅料的物料…"
          style="width: 100%"
          data-testid="seasoning-substitute-select"
        >
          <el-option
            v-for="material in materials.filter((item) => item.id !== form.materialTypeId)"
            :key="material.id"
            :label="`${material.name}${material.code ? `（${material.code}）` : ''}`"
            :value="material.id"
          />
        </el-select>
        <div class="form-tip">替代辅料仅适用于当前 Workflow 工序；不会作为额外需求或重复计入成本。</div>
        <div v-if="form.substituteMaterialTypeIds.length" class="substitute-factors">
          <div v-for="materialTypeId in form.substituteMaterialTypeIds" :key="materialTypeId" class="substitute-factor-row">
            <span>{{ materials.find((item) => item.id === materialTypeId)?.name }}</span>
            <el-input-number
              v-model="form.substituteFactors[materialTypeId]"
              :data-testid="`seasoning-substitute-factor-${materialTypeId}`"
              :min="0.000001"
              :precision="6"
              :controls="false"
              :placeholder="canonicalUnitCode(materials.find((item) => item.id === materialTypeId)?.unit) === canonicalUnitCode(selectedMaterial?.unit) ? '同单位默认1:1' : '填写等价系数'"
            />
          </div>
        </div>
      </el-form-item>
      <el-form-item label="投入数量" required>
        <div class="dosage-contract" data-testid="seasoning-dosage-sentence">
          <div class="dosage-contract__basis">
            <span class="dosage-contract__eyebrow">生产基准</span>
            <strong>每生产 {{ basisLabel }} {{ basisObjectLabel }}</strong>
          </div>
          <span class="dosage-contract__arrow">需要投入</span>
          <div class="dosage-contract__input">
            <el-input-number v-model="dosageDisplayValue" :min="0" :precision="4" :controls="false" />
            <el-select
              v-model="dosageUnit"
              :loading="materialUnitLoading"
              style="width: 96px"
              data-testid="seasoning-dosage-unit"
            >
              <el-option v-for="unit in dosageUnitOptions" :key="unit" :label="businessUnitLabel(unit)" :value="unit" />
            </el-select>
          </div>
        </div>
        <div v-if="process?.standardUsageSupported === true" class="dosage-preview">
          保存口径：{{ Number(form.dosagePerKgG || 0).toFixed(4).replace(/\.?0+$/, '') }} g/kg；
          生产基准来自已固定 Workflow 修订的产出端口。
        </div>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="无法解析本工序产出单位，当前不能保存"
          description="请回到 Workflow，为该工序绑定带合法重量单位的半成品或成品产出后重新保存修订。"
        />
      </el-form-item>
      <el-form-item label="按锅序计算"><el-switch v-model="form.potEnabled" /></el-form-item>
      <template v-if="form.potEnabled">
        <el-form-item label="第一锅"><el-input model-value="100%" disabled /></el-form-item>
        <el-form-item label="后续锅占第一锅">
          <el-input-number v-model="form.subsequentPercent" :min="0" :max="100" :precision="2" /><span class="suffix">%</span>
        </el-form-item>
      </template>
      <el-form-item label="自动单价">
        <div class="automatic-price" data-testid="seasoning-automatic-price">
          <el-input :model-value="automaticPriceLabel" disabled />
          <el-tag v-if="effectivePrice" :type="effectivePrice.source === '移动平均库存成本' ? 'success' : 'warning'">
            {{ effectivePrice.source }}
          </el-tag>
        </div>
      </el-form-item>
      <el-alert
        v-if="missingEffectivePrice"
        type="warning"
        :closable="false"
        show-icon
        title="该调料缺少有效移动平均库存成本或未税采购参考价，暂不能保存"
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
.dosage-contract { display: grid; grid-template-columns: minmax(180px, 1fr) auto minmax(190px, 1fr); align-items: center; gap: 10px; width: 100%; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-light); }
.dosage-contract__basis { display: grid; gap: 3px; }
.dosage-contract__eyebrow { color: var(--el-text-color-secondary); font-size: 12px; }
.dosage-contract__arrow { color: var(--el-text-color-secondary); font-size: 12px; white-space: nowrap; }
.dosage-contract__input { display: flex; gap: 8px; }
.dosage-contract__input :deep(.el-input-number) { width: 130px; }
.dosage-preview { width: 100%; margin-top: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.automatic-price { display: flex; align-items: center; gap: 8px; width: 100%; }
.automatic-price :deep(.el-input) { flex: 1; }
.substitute-factors { display: grid; gap: 6px; width: 100%; margin-top: 8px; }
.substitute-factor-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.substitute-factor-row span { overflow: hidden; color: var(--el-text-color-regular); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.substitute-factor-row :deep(.el-input-number) { width: 150px; }
@media (max-width: 680px) {
  .dosage-contract { grid-template-columns: 1fr; }
  .dosage-contract__arrow { justify-self: start; }
}
</style>
