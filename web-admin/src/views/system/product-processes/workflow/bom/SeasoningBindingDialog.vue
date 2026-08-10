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
  /**
   * 未税采购参考价；物料档案保存含税价时由后端按税率同步换算。
   *
   * ⚠️ 键名必须是 materialReferencePrice —— 后端 RawMaterialTypeDTO 把 entity.unitPrice
   * 装进这个字段(RawMaterialTypeServiceImpl:803)，字段上的 @JsonAlias("unitPrice")
   * 只作用于反序列化，响应 JSON 里没有 unitPrice 这个键。读错键会让本条判据恒为假。
   */
  materialReferencePrice?: number | null;
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
const dosageUnit = ref('g');
const dosageUnitFactorsToG = ref<Record<string, number>>({ g: 1, kg: 1000 });
const materialUnitLoading = ref(false);
const materialUnitError = ref('');
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
// ⛔ 这里曾经有 standardBasisQuantity / standardBasisUnit / basisLabel / basisObjectLabel 四个
// computed, 用 process.standardBasis*(后端按**产出单位**解析出来的基准)拼出「每生产 1 box 本工序成品」。
// 它与真实算式不符(算式分母是投料 kg, 见模板里那段注释与真机实测的 8.3 倍偏差), 已整体删除 ——
// 只删展示、不删后端字段。留着它们的话, 下一个人很容易再把这个错误的基准接回界面上。
// 要恢复「按产出计量」的语义, 必须先改 ProcessSheetServiceImpl 的算式并处理存量配置, 那是产品决策。
const refreshedPrices = ref<Pick<SeasoningMaterialOption, 'movingAvgPrice' | 'materialReferencePrice'> | undefined>();
const effectivePrice = computed(() => {
  const movingAvgPrice = refreshedPrices.value === undefined
    ? selectedMaterial.value?.movingAvgPrice
    : refreshedPrices.value.movingAvgPrice;
  if (positivePrice(movingAvgPrice)) {
    return { value: Number(movingAvgPrice), source: '移动平均库存成本' as const };
  }
  const purchaseReferencePrice = refreshedPrices.value === undefined
    ? selectedMaterial.value?.materialReferencePrice
    : refreshedPrices.value.materialReferencePrice;
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
  dosageUnit.value = canonicalUnitCode(
    props.materials.find((item) => item.id === (props.binding?.materialTypeId || ''))?.unit,
  ) || 'g';
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
  const unit = canonicalUnitCode(selectedMaterialUnit.value);
  dosageUnit.value = unit || 'g';
  materialUnitError.value = '';
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
      materialUnitError.value = `辅料档案单位“${businessUnitLabel(unit)}”缺少成本换算关系，当前条目暂不能保存。`;
    }
  } catch {
    materialUnitError.value = `辅料档案单位“${businessUnitLabel(unit)}”的换算关系读取失败，当前条目暂不能保存。`;
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
  if (materialUnitError.value) return ElMessage.warning('请先完善辅料档案的单位换算关系');
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
      materialReferencePrice: material?.materialReferencePrice ?? null,
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
          <!--
            🔴 2026-08-08 真机实测改正: 这里原本写「每生产 {basisLabel} {basisObjectLabel}」,
            即把用量说成**按产出**计。而后端 ProcessSheetServiceImpl 算需求量用的是

                quantityKg = effectiveRawKg × dosagePerKgG / 1000
                effectiveRawKg = req.getInputQuantity()(本工序**投料**量)/ potRawKgs

            —— 分母是**投入原料的公斤数**, 与产出无关。同一方法里包材才是按 reportedOutput 缩放的,
            两者本就不同源。实测(F006 投 100kg / 出 12 盒, 配 25 g): 系统要 2.5kg,
            而按「每盒 25 g」的读法只需 0.3kg —— 差 8.3 倍。

            ⛔ 不能反过来改算式: LIUSHANMEN(真客户)有 8 条在用的辅料配置, 改分母会直接改动
            他们的实际扣料。所以这里只把话说对, 计算一行没动。
            (遗留问题: 后端 standardBasis 仍按**产出单位**解析并据此 gate standardUsageSupported,
             那套口径与真实算式不一致, 属产品决策, 未动 —— 见交接。)
          -->
          <div class="dosage-contract__basis">
            <span class="dosage-contract__eyebrow">投入量基准</span>
            <strong>本工序每投入 1 kg</strong>
          </div>
          <span class="dosage-contract__arrow">需要投入</span>
          <div class="dosage-contract__input">
            <el-input-number
              v-model="dosageDisplayValue"
              :disabled="materialUnitLoading || Boolean(materialUnitError)"
              :min="0"
              :precision="4"
              :controls="false"
            />
            <span class="dosage-contract__unit" data-testid="seasoning-dosage-unit">
              {{ materialUnitLoading ? '读取中…' : businessUnitLabel(dosageUnit) }}
            </span>
          </div>
        </div>
        <div v-if="process?.standardUsageSupported === true" class="dosage-preview">
          辅料单位由物料档案固定带入；BOM 不提供单位切换。
        </div>
        <el-alert
          v-if="materialUnitError"
          type="warning"
          :closable="false"
          show-icon
          :title="materialUnitError"
          description="请先在物料档案维护该单位的权威成本换算关系；系统不会猜测或按 0 成本保存。"
        />
        <el-alert
          v-else-if="process?.standardUsageSupported !== true"
          type="warning"
          :closable="false"
          show-icon
          title="本工序缺少可用的产出基准，当前不能保存"
          description="请回到 Workflow，为该工序绑定明确的半成品或成品产出后重新保存修订。"
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
      <!--
        🔴 2026-08-09: 标签原为「成本核算」, 只说了一半。这个开关(countInSeasoning)同时管两件事:
          · RecipeCostCalculator —— 关掉后这条不计入调料成本;
          · ProcessSheetServiceImpl —— 关掉后这条**根本不产生投料需求, 报工时一克都不扣**。
        用户看到「成本核算」会以为只是不算钱, 实际库存也不动 —— 真机上我就是照这个读法去关它,
        想绕过库存不足, 结果等于把这条辅料从生产里整个抹掉。
        实体注释写着本意: 「老汤/高汤 = false (熟制段不计入调料)」—— 反复使用的底料不按批扣,
        这是对的; 错的是界面没把「不扣料」这一半说出来。
      -->
      <el-form-item label="计入调料">
        <div>
          <el-switch v-model="form.countInSeasoning" active-text="计入" inactive-text="不计入" />
          <div class="seasoning-count-hint">
            不计入 = 既不算调料成本，<strong>也不会按它扣料</strong>；用于老汤/高汤这类反复使用的底料。
          </div>
        </div>
      </el-form-item>
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
.dosage-contract__input { display: flex; align-items: center; gap: 8px; }
.dosage-contract__input :deep(.el-input-number) { width: 130px; }
.dosage-contract__unit { min-width: 52px; color: var(--el-text-color-regular); font-weight: 600; }
.seasoning-count-hint { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
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
