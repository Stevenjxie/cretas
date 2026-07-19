<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  BomCopyCandidate,
  BomCopyInjectionConfigRule,
  BomCopySeasoningRule,
  CopyBomToProductRequest,
} from '@/api/bom';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  targetProductName: string;
  targetProductTypeId: string;
  candidates: BomCopyCandidate[];
  loading?: boolean;
  submitting?: boolean;
}>(), {
  loading: false,
  submitting: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  copy: [payload: CopyBomToProductRequest];
}>();

const selectedSourceRecipeId = ref('');
const selectedBomItemIds = ref<string[]>([]);
const selectedSeasoningItemIds = ref<string[]>([]);
const selectedInjectionConfigIds = ref<string[]>([]);

const activeCandidate = computed(() =>
  props.candidates.find((candidate) => candidate.sourceRecipeId === selectedSourceRecipeId.value)
    ?? props.candidates[0]
    ?? null,
);

const groupedSeasoning = computed(() => {
  const groups = new Map<string, { workProcessId: string; processName: string; items: BomCopySeasoningRule[] }>();
  for (const item of activeCandidate.value?.seasoningItems ?? []) {
    const processId = item.workProcessId;
    const processName = item.workProcessName
      || activeCandidate.value?.sharedProcesses.find((process) => process.workProcessId === processId)?.processName
      || processId;
    const group = groups.get(processId) ?? { workProcessId: processId, processName, items: [] };
    group.items.push(item);
    groups.set(processId, group);
  }
  return Array.from(groups.values());
});

const selectedRuleCount = computed(() =>
  selectedBomItemIds.value.length
  + selectedSeasoningItemIds.value.length
  + selectedInjectionConfigIds.value.length,
);

function resetSelections(candidate: BomCopyCandidate | null) {
  selectedBomItemIds.value = (candidate?.bomItems ?? []).map((item) => String(item.id));
  selectedSeasoningItemIds.value = (candidate?.seasoningItems ?? []).map((item) => String(item.id));
  selectedInjectionConfigIds.value = (candidate?.processInjectionConfigs ?? []).map((item) => String(item.id));
}

watch(
  () => [props.modelValue, props.candidates] as const,
  ([visible]) => {
    if (!visible || props.candidates.length === 0) return;
    const selectedStillExists = props.candidates.some(
      (candidate) => candidate.sourceRecipeId === selectedSourceRecipeId.value,
    );
    if (!selectedStillExists) selectedSourceRecipeId.value = props.candidates[0].sourceRecipeId;
    resetSelections(activeCandidate.value);
  },
  { immediate: true },
);

watch(selectedSourceRecipeId, () => resetSelections(activeCandidate.value));

function setAllBomItems(checked: boolean) {
  selectedBomItemIds.value = checked
    ? (activeCandidate.value?.bomItems ?? []).map((item) => String(item.id))
    : [];
}

function setAllSeasoning(checked: boolean) {
  selectedSeasoningItemIds.value = checked
    ? (activeCandidate.value?.seasoningItems ?? []).map((item) => String(item.id))
    : [];
}

function setProcessSeasoning(group: { items: BomCopySeasoningRule[] }, checked: boolean) {
  const groupIds = new Set(group.items.map((item) => String(item.id)));
  const remaining = selectedSeasoningItemIds.value.filter((id) => !groupIds.has(id));
  selectedSeasoningItemIds.value = checked
    ? [...remaining, ...groupIds]
    : remaining;
}

function isProcessSeasoningAllSelected(group: { items: BomCopySeasoningRule[] }) {
  return group.items.length > 0
    && group.items.every((item) => selectedSeasoningItemIds.value.includes(String(item.id)));
}

function setAllInjectionConfigs(checked: boolean) {
  selectedInjectionConfigIds.value = checked
    ? (activeCandidate.value?.processInjectionConfigs ?? []).map((item) => String(item.id))
    : [];
}

function originalId(id: string, rules: Array<{ id: number }>): number {
  return rules.find((rule) => String(rule.id) === id)?.id ?? Number(id);
}

function handleCopy() {
  const candidate = activeCandidate.value;
  if (!candidate || selectedRuleCount.value === 0) return;
  emit('copy', {
    targetProductTypeId: props.targetProductTypeId,
    sourceRecipeId: candidate.sourceRecipeId,
    recipeItemIds: selectedBomItemIds.value.map((id) => originalId(id, candidate.bomItems)),
    seasoningItemIds: selectedSeasoningItemIds.value.map((id) => originalId(id, candidate.seasoningItems)),
    processInjectionConfigIds: selectedInjectionConfigIds.value.map(
      (id) => originalId(id, candidate.processInjectionConfigs),
    ),
  });
}

function quantityText(value: number | null | undefined, unit: string | null | undefined) {
  if (value == null) return '仅关联物料';
  return `${value} ${unit || ''}`.trim();
}

function injectionConfigText(item: BomCopyInjectionConfigRule) {
  return `注射量 ${item.injectionAmountKg} kg`;
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="参考同源产品创建 BOM"
    width="820px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="copy-dialog">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          发现与“{{ targetProductName || targetProductTypeId }}”同源且共享工序的成品
        </template>
        <template #default>
          可逐条参考复制到新草稿；数量不会按规格自动缩放，复制后请核对并编辑。不会自动激活。
        </template>
      </el-alert>

      <div v-if="candidates.length > 1" class="source-picker">
        <span class="source-picker__label">参考来源</span>
        <el-select v-model="selectedSourceRecipeId" style="flex: 1">
          <el-option
            v-for="candidate in candidates"
            :key="candidate.sourceRecipeId"
            :value="candidate.sourceRecipeId"
            :label="`${candidate.sourceProductName} · 共享 ${candidate.sharedProcesses.length} 道工序`"
          />
        </el-select>
      </div>

      <template v-if="activeCandidate">
        <div class="source-summary">
          <div>
            <strong>{{ activeCandidate.sourceProductName }}</strong>
            <span v-if="activeCandidate.sourceRecipeCode" class="source-summary__muted">
              {{ activeCandidate.sourceRecipeCode }}<template v-if="activeCandidate.sourceRecipeVersion != null"> · v{{ activeCandidate.sourceRecipeVersion }}</template>
            </span>
          </div>
          <el-tag type="success" effect="plain">共享 {{ activeCandidate.sharedProcesses.length }} 道工序</el-tag>
        </div>
        <div v-if="activeCandidate.sharedProcesses.length" class="shared-processes">
          <span>共享工序：</span>
          <el-tag
            v-for="process in activeCandidate.sharedProcesses"
            :key="process.workProcessId"
            size="small"
            effect="plain"
          >{{ process.processName }}</el-tag>
        </div>

        <section class="rule-section">
          <div class="rule-section__header">
            <div>
              <strong>BOM 原料 / 辅料 / 包材</strong>
              <span class="rule-count">{{ activeCandidate.bomItems.length }} 条</span>
            </div>
            <el-checkbox
              :model-value="activeCandidate.bomItems.length > 0 && selectedBomItemIds.length === activeCandidate.bomItems.length"
              :disabled="activeCandidate.bomItems.length === 0"
              @change="setAllBomItems(Boolean($event))"
            >本组全选</el-checkbox>
          </div>
          <el-checkbox-group v-if="activeCandidate.bomItems.length" v-model="selectedBomItemIds" class="rule-list">
            <el-checkbox
              v-for="item in activeCandidate.bomItems"
              :key="item.id"
              :value="String(item.id)"
              :data-testid="`copy-bom-item-${item.id}`"
              class="rule-row"
            >
              <span class="rule-row__name">{{ item.materialName }}</span>
              <el-tag v-if="item.materialCategory" size="small" type="info" effect="plain">{{ item.materialCategory }}</el-tag>
              <span class="rule-row__value">{{ quantityText(item.standardQuantity, item.unit) }}</span>
            </el-checkbox>
          </el-checkbox-group>
          <el-empty v-else description="来源配方没有 BOM 明细" :image-size="44" />
        </section>

        <section class="rule-section">
          <div class="rule-section__header">
            <div>
              <strong>工序调味 / 辅料规则</strong>
              <span class="rule-count">{{ activeCandidate.seasoningItems.length }} 条</span>
            </div>
            <el-checkbox
              :model-value="activeCandidate.seasoningItems.length > 0 && selectedSeasoningItemIds.length === activeCandidate.seasoningItems.length"
              :disabled="activeCandidate.seasoningItems.length === 0"
              @change="setAllSeasoning(Boolean($event))"
            >本组全选</el-checkbox>
          </div>
          <div v-if="groupedSeasoning.length" class="process-groups">
            <div v-for="group in groupedSeasoning" :key="group.workProcessId" class="process-group">
              <div class="process-group__header">
                <span>{{ group.processName }}</span>
                <el-checkbox
                  :model-value="isProcessSeasoningAllSelected(group)"
                  @change="setProcessSeasoning(group, Boolean($event))"
                >该工序全选</el-checkbox>
              </div>
              <el-checkbox-group v-model="selectedSeasoningItemIds" class="rule-list">
                <el-checkbox
                  v-for="item in group.items"
                  :key="item.id"
                  :value="String(item.id)"
                  :data-testid="`copy-seasoning-item-${item.id}`"
                  class="rule-row"
                >
                  <span class="rule-row__name">{{ item.name }}</span>
                  <span class="rule-row__value">
                    {{ item.dosagePerKgG == null ? '未填用量' : `${item.dosagePerKgG} g/kg` }}
                  </span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
          <el-empty v-else description="共享工序没有可复制的调味/辅料规则" :image-size="44" />
        </section>

        <section class="rule-section">
          <div class="rule-section__header">
            <div>
              <strong>注射配置</strong>
              <span class="rule-count">{{ activeCandidate.processInjectionConfigs.length }} 条</span>
            </div>
            <el-checkbox
              :model-value="activeCandidate.processInjectionConfigs.length > 0 && selectedInjectionConfigIds.length === activeCandidate.processInjectionConfigs.length"
              :disabled="activeCandidate.processInjectionConfigs.length === 0"
              @change="setAllInjectionConfigs(Boolean($event))"
            >本组全选</el-checkbox>
          </div>
          <el-checkbox-group v-if="activeCandidate.processInjectionConfigs.length" v-model="selectedInjectionConfigIds" class="rule-list">
            <el-checkbox
              v-for="item in activeCandidate.processInjectionConfigs"
              :key="item.id"
              :value="String(item.id)"
              :data-testid="`copy-injection-config-${item.id}`"
              class="rule-row"
            >
              <span class="rule-row__name">{{ item.workProcessName || item.workProcessId }}</span>
              <span class="rule-row__value">{{ injectionConfigText(item) }}</span>
            </el-checkbox>
          </el-checkbox-group>
          <el-empty v-else description="共享工序没有注射配置" :image-size="44" />
        </section>
      </template>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!activeCandidate || selectedRuleCount === 0"
          data-testid="copy-selected-rules"
          @click="handleCopy"
        >复制所选 {{ selectedRuleCount }} 条规则为草稿</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.copy-dialog {
  min-height: 120px;
}

.source-picker,
.source-summary,
.shared-processes,
.rule-section__header,
.process-group__header,
.dialog-footer {
  display: flex;
  align-items: center;
}

.source-picker {
  gap: 12px;
  margin-top: 16px;

  &__label {
    color: var(--el-text-color-regular);
    white-space: nowrap;
  }
}

.source-summary {
  justify-content: space-between;
  margin-top: 16px;
  color: var(--el-text-color-primary);

  &__muted {
    margin-left: 8px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.shared-processes {
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.rule-section {
  margin-top: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;

  &__header {
    justify-content: space-between;
    min-height: 42px;
    padding: 0 12px;
    background: var(--el-fill-color-light);
  }
}

.rule-count {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.rule-list {
  display: flex;
  flex-direction: column;
  padding: 4px 12px;
}

.rule-row {
  width: 100%;
  min-height: 36px;
  margin-right: 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);

  &:last-child {
    border-bottom: 0;
  }

  :deep(.el-checkbox__label) {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    width: 100%;
  }

  &__name {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    color: var(--el-text-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__value {
    flex-shrink: 0;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }
}

.process-group + .process-group {
  border-top: 1px solid var(--el-border-color-lighter);
}

.process-group__header {
  justify-content: space-between;
  min-height: 34px;
  padding: 0 12px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  font-size: 13px;
}

.dialog-footer {
  justify-content: flex-end;
  gap: 8px;
}

:deep(.el-empty) {
  padding: 12px 0;
}
</style>
