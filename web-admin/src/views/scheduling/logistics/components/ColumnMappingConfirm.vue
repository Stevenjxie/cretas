<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ColumnMapping } from '@/api/logistics';

/**
 * 任意 Excel / 粘贴导入的「映射确认面板」。
 * 自动识别全中(autoConfident) → 折叠成一行一键确认；有必填缺失/歧义 → 展开逐列下拉核对，
 * 必填未覆盖时禁用「确认并预览」(防呆 Rule 1：先显示边界，不让错误进下一步)。
 */
const props = defineProps<{
  columns: ColumnMapping[];
  autoConfident: boolean;
  busy: boolean;
}>();

const emit = defineEmits<{
  (e: 'confirm', override: Record<number, string>, dirty: boolean): void;
}>();

const IGNORE = '__ignore__';
// 目标字段选项（value = 后端 LogisticsOrderImportRow 字段名）。
const FIELD_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'storeCode', label: '订单号' },
  { value: 'storeName', label: '门店名称' },
  { value: 'address', label: '配送地址' },
  { value: 'pieces', label: '件数' },
  { value: 'boxes', label: '箱数' },
  { value: 'weightKg', label: '重量kg' },
  { value: 'volumeCbm', label: '体积m³' },
  { value: 'areaCode', label: '区域' },
  { value: 'businessDate', label: '业务日期' },
  { value: 'windowStart', label: '配送开始时间' },
  { value: 'windowEnd', label: '配送结束时间' },
  { value: 'longitude', label: '经度' },
  { value: 'latitude', label: '纬度' },
  { value: IGNORE, label: '忽略此列' },
];
const FIELD_LABEL: Record<string, string> = {
  ...Object.fromEntries(FIELD_OPTIONS.map((o) => [o.value, o.label])),
  quantity: '件数或箱数',
};
const REQUIRED = ['storeName', 'address', 'weightKg', 'volumeCbm'];

interface LocalCol { index: number; header: string; sampleValue: string; field: string; ambiguous: boolean; }
const local = ref<LocalCol[]>([]);
const dirty = ref(false);
const expanded = ref(false);

function reset(): void {
  local.value = (props.columns ?? []).map((c) => ({
    index: c.index,
    header: c.header || `列${c.index + 1}`,
    sampleValue: c.sampleValue ?? '',
    field: c.mappedField || IGNORE,
    ambiguous: c.ambiguous,
  }));
  dirty.value = false;
  expanded.value = !props.autoConfident; // 全中 → 折叠一键确认；有缺失/歧义 → 展开逐列核对
}
watch(() => props.columns, reset, { immediate: true });

const mappedFields = computed(() => local.value.map((c) => c.field).filter((f) => f && f !== IGNORE));

const hasQuantity = computed(() => mappedFields.value.includes('pieces') || mappedFields.value.includes('boxes'));

const unmappedRequired = computed(() => {
  const miss = REQUIRED.filter((f) => !mappedFields.value.includes(f));
  if (!hasQuantity.value) miss.push('quantity');
  return miss;
});
const covered = computed(() => unmappedRequired.value.length === 0);

// 同一字段被指到多列（后端按最小列索引去重）——提示但不阻断。
const duplicateFields = computed(() => {
  const seen = new Set<string>();
  const dup = new Set<string>();
  for (const f of mappedFields.value) {
    if (seen.has(f)) dup.add(f);
    else seen.add(f);
  }
  return [...dup];
});

const recognizedCount = computed(() => mappedFields.value.length);

function fieldCovered(f: string): boolean {
  return mappedFields.value.includes(f);
}

function onFieldChange(): void {
  dirty.value = true;
}

function confirm(): void {
  const override: Record<number, string> = {};
  for (const c of local.value) override[c.index] = c.field; // 全量覆盖 = 所见即所得
  emit('confirm', override, dirty.value);
}
</script>

<template>
  <div class="mapping-confirm" data-testid="column-mapping-confirm">
    <div class="mc-head">
      <div class="mc-title">
        <strong v-if="autoConfident && !dirty">✅ 已自动识别 {{ recognizedCount }} 列，全部必填已匹配</strong>
        <strong v-else>请核对「你的表头 → 系统字段」的对应（可修正）</strong>
        <span class="mc-sub">从你的 Excel 表头自动匹配到系统字段，确认无误后生成预览。</span>
      </div>
      <el-button link type="primary" data-testid="mapping-toggle" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开核对' }}
      </el-button>
    </div>

    <!-- 必填覆盖状态条 -->
    <div class="mc-coverage">
      <span class="cov-label">必填字段：</span>
      <el-tag
        v-for="f in REQUIRED"
        :key="f"
        :type="fieldCovered(f) ? 'success' : 'danger'"
        effect="light"
        size="small"
      >{{ FIELD_LABEL[f] }} {{ fieldCovered(f) ? '✓' : '缺' }}</el-tag>
      <el-tag :type="hasQuantity ? 'success' : 'danger'" effect="light" size="small">
        件数/箱数 {{ hasQuantity ? '✓' : '缺' }}
      </el-tag>
    </div>

    <!-- 逐列映射（展开时） -->
    <el-table v-if="expanded" :data="local" size="small" border class="mc-table">
      <el-table-column label="源表头（你的 Excel）" min-width="170">
        <template #default="{ row }">
          <div class="src-col">
            <strong>{{ row.header }}</strong>
            <span v-if="row.sampleValue" class="sample">例：{{ row.sampleValue }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="" width="38" align="center">
        <template #default>→</template>
      </el-table-column>
      <el-table-column label="对应系统字段" min-width="170">
        <template #default="{ row }">
          <el-select
            v-model="row.field"
            size="small"
            class="field-select"
            :class="{ unmapped: row.field === IGNORE, ambiguous: row.ambiguous && row.field !== IGNORE }"
            @change="onFieldChange"
          >
            <el-option v-for="o in FIELD_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="!covered" class="mc-missing" data-testid="mapping-missing">
      ⚠️ 还需指定：{{ unmappedRequired.map((f) => FIELD_LABEL[f]).join('、') }} —— 请展开上表把对应列选成这些字段。
    </p>
    <p v-if="duplicateFields.length" class="mc-dup">
      注意：{{ duplicateFields.map((f) => FIELD_LABEL[f]).join('、') }} 被指到了多列，仅第一列生效。
    </p>

    <div class="mc-actions">
      <el-tooltip :disabled="covered" content="必填字段未全部匹配，请先展开上表指定" placement="top">
        <span>
          <button
            class="mc-confirm"
            type="button"
            :disabled="!covered || busy"
            data-testid="mapping-confirm-btn"
            @click="confirm"
          >{{ busy ? '生成预览中…' : '确认并预览' }}</button>
        </span>
      </el-tooltip>
    </div>
  </div>
</template>

<style scoped lang="scss">
.mapping-confirm { display: grid; gap: 12px; padding: 18px 20px; background: #f8fbff; border: 1px solid #cfe3fb; border-radius: 10px; }
.mc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.mc-title { display: grid; gap: 3px; }
.mc-title strong { color: #101828; font-size: 15px; }
.mc-sub { color: #667085; font-size: 12.5px; }
.mc-coverage { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.cov-label { color: #475467; font-size: 12.5px; font-weight: 650; }
.mc-table { width: 100%; }
.src-col { display: flex; flex-direction: column; line-height: 1.35; }
.src-col strong { color: #101828; font-size: 13px; }
.src-col .sample { color: #98a2b3; font-size: 11.5px; }
.field-select { width: 100%; }
.field-select.unmapped :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px #f0a848 inset; }
.field-select.ambiguous :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px #f0a848 inset; }
.mc-missing { margin: 0; color: #b42318; font-size: 13px; }
.mc-dup { margin: 0; color: #b54708; font-size: 12.5px; }
.mc-actions { display: flex; }
.mc-confirm { width: fit-content; padding: 9px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; }
.mc-confirm:disabled { background: #98a2b3; cursor: not-allowed; }
</style>
