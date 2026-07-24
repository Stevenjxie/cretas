<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  bomRecipeApi,
  type BomFamilyOutputCosting,
  type BomFamilyOutputCostingItem,
} from '@/api/bom';

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  canWrite: boolean;
  canViewPrice: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
}>();

const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
const data = ref<BomFamilyOutputCosting | null>(null);
const outputs = ref<BomFamilyOutputCostingItem[]>([]);

const editable = computed(() => Boolean(
  props.canWrite && props.canViewPrice && data.value?.editable,
));
const hasByproduct = computed(() => outputs.value.some((row) => row.outputRole === 'BY_PRODUCT'));

const roleLabel: Record<BomFamilyOutputCostingItem['outputRole'], string> = {
  MAIN: '主产出',
  CO_PRODUCT: '联产品',
  BY_PRODUCT: '副产品',
};
const quantityFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 4,
});
const percentageFormatter = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

async function load() {
  if (!props.factoryId || !props.recipeId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await bomRecipeApi.getFamilyOutputCosting(props.factoryId, props.recipeId);
    if (!response.success || !response.data) {
      throw new Error(response.message || '产出成本配置响应为空');
    }
    data.value = response.data;
    outputs.value = response.data.outputs.map((row) => ({ ...row }));
  } catch (error: unknown) {
    loadError.value = (error as { message?: string }).message || '产出成本配置加载失败';
  } finally {
    loading.value = false;
  }
}

function close() {
  emit('update:modelValue', false);
}

async function save() {
  if (!editable.value || saving.value) return;
  const invalid = outputs.value.find((row) => (
    row.outputRole === 'BY_PRODUCT'
    && (row.byproductNrvUnitPrice == null || Number(row.byproductNrvUnitPrice) <= 0)
  ));
  if (invalid) {
    loadError.value = `请填写「${invalid.productName}」大于 0 的单位可变现净值`;
    return;
  }
  saving.value = true;
  loadError.value = '';
  try {
    const response = await bomRecipeApi.updateFamilyOutputCosting(
      props.factoryId,
      props.recipeId,
      {
        outputs: outputs.value.map((row) => ({
          recipeId: row.recipeId,
          byproductNrvUnitPrice: row.outputRole === 'BY_PRODUCT'
            ? row.byproductNrvUnitPrice
            : null,
        })),
      },
    );
    if (!response.success || !response.data) {
      throw new Error(response.message || '产出成本配置保存失败');
    }
    data.value = response.data;
    outputs.value = response.data.outputs.map((row) => ({ ...row }));
    ElMessage.success('产出成本配置已保存并重新计算');
    emit('saved');
    close();
  } catch (error: unknown) {
    loadError.value = (error as { message?: string }).message || '产出成本配置保存失败';
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) void load();
  },
  { immediate: true },
);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="产出成本配置"
    width="min(760px, calc(100vw - 32px))"
    destroy-on-close
    data-testid="family-output-costing-dialog"
    @close="close"
  >
    <div v-loading="loading" class="output-costing">
      <el-alert
        v-if="loadError"
        type="error"
        show-icon
        :closable="false"
        :title="loadError"
        class="output-costing__alert"
        data-testid="family-output-costing-error"
      />
      <el-alert
        v-if="hasByproduct"
        type="info"
        show-icon
        :closable="false"
        title="副产品按可变现净值计价"
        description="填写预计售价扣除后续加工和销售费用后的单位净值。系统只从实际通向该副产品的共享成本中抵扣，不会产生负成本。"
        class="output-costing__alert"
      />
      <el-table
        :data="outputs"
        border
        class="output-costing__table"
        empty-text="暂无产出配置"
        data-testid="family-output-costing-table"
      >
        <el-table-column label="产出" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.productName }}</strong>
            <div class="output-costing__secondary" translate="no">{{ row.productTypeId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.outputRole === 'BY_PRODUCT' ? 'warning' : row.outputRole === 'MAIN' ? 'success' : 'info'">
              {{ roleLabel[row.outputRole as BomFamilyOutputCostingItem['outputRole']] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="基准产出" width="130" align="right">
          <template #default="{ row }">
            {{ row.outputQuantity == null ? '—' : quantityFormatter.format(row.outputQuantity) }}
            {{ row.outputUnit || '' }}
          </template>
        </el-table-column>
        <el-table-column label="共享成本分摊" width="130" align="right">
          <template #default="{ row }">
            {{ percentageFormatter.format(row.costAllocationRatio ?? 0) }}%
          </template>
        </el-table-column>
        <el-table-column label="单位可变现净值（元）" min-width="190">
          <template #default="{ row }">
            <el-input-number
              v-if="row.outputRole === 'BY_PRODUCT'"
              v-model="row.byproductNrvUnitPrice"
              :disabled="!editable"
              :min="0.0001"
              :precision="4"
              :step="0.1"
              :name="`byproduct-nrv-${row.recipeId}`"
              controls-position="right"
              :aria-label="`${row.productName}单位可变现净值`"
              class="output-costing__number"
            />
            <span v-else class="output-costing__secondary">按工艺分摊</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="output-costing__cards" data-testid="family-output-costing-cards">
        <article
          v-for="row in outputs"
          :key="row.recipeId"
          class="output-costing__card"
        >
          <header class="output-costing__card-header">
            <div class="output-costing__identity">
              <strong>{{ row.productName }}</strong>
              <span class="output-costing__secondary" translate="no">{{ row.productTypeId }}</span>
            </div>
            <el-tag :type="row.outputRole === 'BY_PRODUCT' ? 'warning' : row.outputRole === 'MAIN' ? 'success' : 'info'">
              {{ roleLabel[row.outputRole] }}
            </el-tag>
          </header>
          <dl class="output-costing__facts">
            <div>
              <dt>基准产出</dt>
              <dd>
                {{ row.outputQuantity == null ? '—' : quantityFormatter.format(row.outputQuantity) }}
                {{ row.outputUnit || '' }}
              </dd>
            </div>
            <div>
              <dt>共享成本分摊</dt>
              <dd>{{ percentageFormatter.format(row.costAllocationRatio ?? 0) }}%</dd>
            </div>
          </dl>
          <div v-if="row.outputRole === 'BY_PRODUCT'" class="output-costing__mobile-field">
            <label :for="`mobile-byproduct-nrv-${row.recipeId}`">单位可变现净值（元）</label>
            <el-input-number
              :id="`mobile-byproduct-nrv-${row.recipeId}`"
              v-model="row.byproductNrvUnitPrice"
              :disabled="!editable"
              :min="0.0001"
              :precision="4"
              :step="0.1"
              :name="`mobile-byproduct-nrv-${row.recipeId}`"
              controls-position="right"
              :aria-label="`${row.productName}移动端单位可变现净值`"
              class="output-costing__number"
            />
          </div>
        </article>
      </div>
      <p v-if="data && !data.editable" class="output-costing__readonly">
        当前为已生效或历史版本。如需调整，请先克隆为新版本。
      </p>
    </div>
    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button
        v-if="editable"
        type="primary"
        :loading="saving"
        data-testid="save-family-output-costing"
        @click="save"
      >
        保存并重算
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.output-costing {
  min-height: 180px;
  overscroll-behavior: contain;
}
.output-costing__alert { margin-bottom: 14px; }
.output-costing__secondary,
.output-costing__readonly {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.output-costing__secondary {
  margin-top: 3px;
  overflow-wrap: anywhere;
}
.output-costing__readonly { margin: 12px 0 0; }
.output-costing__number { width: 100%; }
.output-costing__cards { display: none; }
.output-costing :deep(.el-table__cell.is-right) {
  font-variant-numeric: tabular-nums;
}
.output-costing__identity {
  min-width: 0;
}
.output-costing__identity .output-costing__secondary {
  display: block;
}
.output-costing__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0 0;
}
.output-costing__facts div {
  min-width: 0;
}
.output-costing__facts dt,
.output-costing__mobile-field label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.output-costing__facts dd {
  margin: 4px 0 0;
  font-variant-numeric: tabular-nums;
}
.output-costing__mobile-field {
  margin-top: 14px;
}
.output-costing__mobile-field label {
  display: block;
  margin-bottom: 6px;
}

@media (max-width: 640px) {
  .output-costing__table { display: none; }
  .output-costing__cards {
    display: grid;
    gap: 10px;
  }
  .output-costing__card {
    padding: 14px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    background: var(--el-fill-color-blank);
  }
  .output-costing__card-header {
    display: flex;
    gap: 12px;
    align-items: flex-start;
    justify-content: space-between;
  }
}
</style>
