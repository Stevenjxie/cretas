<script setup lang="ts">
/**
 * SmartBI PeriodSelector - Flexible Period Selection Component
 * Features: Multiple selection modes (month, quarter, year, ranges), quick shortcuts, YoY comparison
 * Supports: Single month, single quarter, single year, month range, quarter range, custom range
 */
import { ref, computed, watch, onMounted } from 'vue';
import { Calendar, Clock } from '@element-plus/icons-vue';

// Types
export type PeriodType = 'month' | 'quarter' | 'year' | 'month_range' | 'quarter_range' | 'custom';

export interface PeriodSelection {
  type: PeriodType;
  year: number;
  value: string | [string, string];
  compareEnabled: boolean;
  compareValue?: string | [string, string];
  /**
   * 「全部历史」标记 (financial PBI 看板用)。设为 true 时表示不按月份/年份过滤,
   * 消费方应映射为后端 period_type='year' (normalizer 对 'year' 不做任何 month 过滤,
   * 即返回全部上传数据)。type 仍是 'year' 以兼容所有现有 switch 分支。
   */
  allHistory?: boolean;
}

interface Props {
  modelValue?: PeriodSelection;
  showQuickSelect?: boolean;
  showCustomTab?: boolean;
  defaultType?: PeriodType;
  minYear?: number;
  maxYear?: number;
  disabled?: boolean;
  /** 显示「全部历史」快捷选项 (默认 false — 只有 PBI 看板等需要时开启)。 */
  showAllHistory?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: undefined,
  showQuickSelect: true,
  showCustomTab: true,
  defaultType: 'month',
  minYear: 2020,
  maxYear: () => new Date().getFullYear() + 1,
  disabled: false,
  showAllHistory: false
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: PeriodSelection): void;
  (e: 'change', value: PeriodSelection): void;
}>();

// State
const activeTab = ref<'quick' | 'custom'>('quick');
const selectedYear = ref(new Date().getFullYear());
const selectedType = ref<PeriodType>(props.defaultType);
const selectedMonth = ref(new Date().getMonth() + 1);
const selectedQuarter = ref(Math.ceil((new Date().getMonth() + 1) / 3));
const monthRangeStart = ref(1);
const monthRangeEnd = ref(new Date().getMonth() + 1);
const quarterRangeStart = ref(1);
const quarterRangeEnd = ref(Math.ceil((new Date().getMonth() + 1) / 3));
const customStartYear = ref(new Date().getFullYear());
const customStartMonth = ref(1);
const customEndYear = ref(new Date().getFullYear());
const customEndMonth = ref(new Date().getMonth() + 1);
const compareEnabled = ref(false);
// 「全部历史」模式: type 仍是 'year' (兼容所有分支), 但消费方据此走"不过滤全部数据"路径。
const allHistoryMode = ref(false);

// Constants
const months = [
  { value: 1, label: '1月' },
  { value: 2, label: '2月' },
  { value: 3, label: '3月' },
  { value: 4, label: '4月' },
  { value: 5, label: '5月' },
  { value: 6, label: '6月' },
  { value: 7, label: '7月' },
  { value: 8, label: '8月' },
  { value: 9, label: '9月' },
  { value: 10, label: '10月' },
  { value: 11, label: '11月' },
  { value: 12, label: '12月' }
];

const quarters = [
  { value: 1, label: 'Q1' },
  { value: 2, label: 'Q2' },
  { value: 3, label: 'Q3' },
  { value: 4, label: 'Q4' }
];

// Computed
const yearOptions = computed(() => {
  const years: { value: number; label: string }[] = [];
  for (let y = props.maxYear; y >= props.minYear; y--) {
    years.push({ value: y, label: `${y}年` });
  }
  return years;
});

const monthEndOptions = computed(() => {
  return months.filter(m => m.value >= monthRangeStart.value);
});

const quarterEndOptions = computed(() => {
  return quarters.filter(q => q.value >= quarterRangeStart.value);
});

const customEndMonthOptions = computed(() => {
  if (customEndYear.value === customStartYear.value) {
    return months.filter(m => m.value >= customStartMonth.value);
  }
  return months;
});

// Build current selection
const currentSelection = computed<PeriodSelection>(() => {
  let value: string | [string, string];
  let compareValue: string | [string, string] | undefined;

  switch (selectedType.value) {
    case 'month':
      value = `${selectedYear.value}-${String(selectedMonth.value).padStart(2, '0')}`;
      if (compareEnabled.value) {
        compareValue = `${selectedYear.value - 1}-${String(selectedMonth.value).padStart(2, '0')}`;
      }
      break;

    case 'quarter':
      value = `${selectedYear.value}-Q${selectedQuarter.value}`;
      if (compareEnabled.value) {
        compareValue = `${selectedYear.value - 1}-Q${selectedQuarter.value}`;
      }
      break;

    case 'year':
      value = `${selectedYear.value}`;
      if (compareEnabled.value) {
        compareValue = `${selectedYear.value - 1}`;
      }
      break;

    case 'month_range':
      value = [
        `${selectedYear.value}-${String(monthRangeStart.value).padStart(2, '0')}`,
        `${selectedYear.value}-${String(monthRangeEnd.value).padStart(2, '0')}`
      ];
      if (compareEnabled.value) {
        compareValue = [
          `${selectedYear.value - 1}-${String(monthRangeStart.value).padStart(2, '0')}`,
          `${selectedYear.value - 1}-${String(monthRangeEnd.value).padStart(2, '0')}`
        ];
      }
      break;

    case 'quarter_range':
      value = [
        `${selectedYear.value}-Q${quarterRangeStart.value}`,
        `${selectedYear.value}-Q${quarterRangeEnd.value}`
      ];
      if (compareEnabled.value) {
        compareValue = [
          `${selectedYear.value - 1}-Q${quarterRangeStart.value}`,
          `${selectedYear.value - 1}-Q${quarterRangeEnd.value}`
        ];
      }
      break;

    case 'custom':
      value = [
        `${customStartYear.value}-${String(customStartMonth.value).padStart(2, '0')}`,
        `${customEndYear.value}-${String(customEndMonth.value).padStart(2, '0')}`
      ];
      if (compareEnabled.value) {
        // 计算跨年范围的同期对比
        const yearDiff = customEndYear.value - customStartYear.value;
        compareValue = [
          `${customStartYear.value - 1}-${String(customStartMonth.value).padStart(2, '0')}`,
          `${customEndYear.value - 1 - yearDiff + yearDiff}-${String(customEndMonth.value).padStart(2, '0')}`
        ];
      }
      break;

    default:
      value = `${selectedYear.value}-${String(selectedMonth.value).padStart(2, '0')}`;
  }

  return {
    type: selectedType.value,
    year: selectedYear.value,
    value,
    compareEnabled: compareEnabled.value,
    compareValue,
    allHistory: allHistoryMode.value || undefined
  };
});

// Display text for current selection
const selectionDisplayText = computed(() => {
  const sel = currentSelection.value;
  if (allHistoryMode.value) return '全部历史';

  switch (sel.type) {
    case 'month':
      return `${selectedYear.value}年${selectedMonth.value}月`;
    case 'quarter':
      return `${selectedYear.value}年Q${selectedQuarter.value}`;
    case 'year':
      return `${selectedYear.value}年`;
    case 'month_range':
      return `${selectedYear.value}年${monthRangeStart.value}-${monthRangeEnd.value}月`;
    case 'quarter_range':
      return `${selectedYear.value}年Q${quarterRangeStart.value}-Q${quarterRangeEnd.value}`;
    case 'custom':
      return `${customStartYear.value}年${customStartMonth.value}月 至 ${customEndYear.value}年${customEndMonth.value}月`;
    default:
      return '';
  }
});

// Compare display text
const compareDisplayText = computed(() => {
  if (!compareEnabled.value) return '';

  const sel = currentSelection.value;

  switch (sel.type) {
    case 'month':
      return `同期: ${selectedYear.value - 1}年${selectedMonth.value}月`;
    case 'quarter':
      return `同期: ${selectedYear.value - 1}年Q${selectedQuarter.value}`;
    case 'year':
      return `同期: ${selectedYear.value - 1}年`;
    case 'month_range':
      return `同期: ${selectedYear.value - 1}年${monthRangeStart.value}-${monthRangeEnd.value}月`;
    case 'quarter_range':
      return `同期: ${selectedYear.value - 1}年Q${quarterRangeStart.value}-Q${quarterRangeEnd.value}`;
    case 'custom':
      return `同期: ${customStartYear.value - 1}年${customStartMonth.value}月 至 ${customEndYear.value - 1}年${customEndMonth.value}月`;
    default:
      return '';
  }
});

// Quick selection handlers
function selectThisMonth() {
  const now = new Date();
  allHistoryMode.value = false;
  selectedYear.value = now.getFullYear();
  selectedMonth.value = now.getMonth() + 1;
  selectedType.value = 'month';
  emitChange();
}

function selectLastMonth() {
  const now = new Date();
  let year = now.getFullYear();
  let month = now.getMonth(); // 0-based, so this is last month

  if (month === 0) {
    year--;
    month = 12;
  }

  allHistoryMode.value = false;
  selectedYear.value = year;
  selectedMonth.value = month;
  selectedType.value = 'month';
  emitChange();
}

function selectThisQuarter() {
  const now = new Date();
  allHistoryMode.value = false;
  selectedYear.value = now.getFullYear();
  selectedQuarter.value = Math.ceil((now.getMonth() + 1) / 3);
  selectedType.value = 'quarter';
  emitChange();
}

function selectLastQuarter() {
  const now = new Date();
  let year = now.getFullYear();
  let quarter = Math.ceil((now.getMonth() + 1) / 3) - 1;

  if (quarter === 0) {
    year--;
    quarter = 4;
  }

  allHistoryMode.value = false;
  selectedYear.value = year;
  selectedQuarter.value = quarter;
  selectedType.value = 'quarter';
  emitChange();
}

function selectThisYear() {
  allHistoryMode.value = false;
  selectedYear.value = new Date().getFullYear();
  selectedType.value = 'year';
  emitChange();
}

function selectLastYear() {
  allHistoryMode.value = false;
  selectedYear.value = new Date().getFullYear() - 1;
  selectedType.value = 'year';
  emitChange();
}

/**
 * 「全部历史」: type 设为 'year' (兼容所有分支), 同时打开 allHistoryMode。
 * 消费方 (PBI 看板) 据 allHistory 标记走"全部上传数据, 不按月/年过滤"路径。
 */
function selectAllHistory() {
  allHistoryMode.value = true;
  selectedType.value = 'year';
  emitChange();
}

/** 用户手动改类型/月份/季度等 → 退出「全部历史」模式 (年份 select 同样)。 */
function clearAllHistory() {
  allHistoryMode.value = false;
}

// Emit change event
function emitChange() {
  const selection = currentSelection.value;
  emit('update:modelValue', selection);
  emit('change', selection);
}

// Watch for type changes to auto-adjust values
watch(selectedType, (newType) => {
  // Reset range values when switching types
  if (newType === 'month_range') {
    monthRangeEnd.value = Math.max(monthRangeStart.value, monthRangeEnd.value);
  } else if (newType === 'quarter_range') {
    quarterRangeEnd.value = Math.max(quarterRangeStart.value, quarterRangeEnd.value);
  } else if (newType === 'custom') {
    activeTab.value = 'custom';
  }
  emitChange();
});

// Watch month range start to adjust end if needed
watch(monthRangeStart, (newStart) => {
  if (monthRangeEnd.value < newStart) {
    monthRangeEnd.value = newStart;
  }
  emitChange();
});

// Watch quarter range start to adjust end if needed
watch(quarterRangeStart, (newStart) => {
  if (quarterRangeEnd.value < newStart) {
    quarterRangeEnd.value = newStart;
  }
  emitChange();
});

// Watch custom range start to adjust end if needed
watch([customStartYear, customStartMonth], ([newYear, newMonth]) => {
  if (customEndYear.value < newYear ||
      (customEndYear.value === newYear && customEndMonth.value < newMonth)) {
    customEndYear.value = newYear;
    customEndMonth.value = newMonth;
  }
  emitChange();
});

// Watch all other selectors
watch([selectedYear, selectedMonth, selectedQuarter, monthRangeEnd, quarterRangeEnd, customEndYear, customEndMonth, compareEnabled], () => {
  emitChange();
});

// Initialize from modelValue
function initFromModelValue() {
  if (!props.modelValue) return;

  const mv = props.modelValue;
  selectedType.value = mv.type;
  selectedYear.value = mv.year;
  compareEnabled.value = mv.compareEnabled;
  allHistoryMode.value = mv.allHistory === true;

  if (typeof mv.value === 'string') {
    if (mv.type === 'month') {
      const [y, m] = mv.value.split('-');
      selectedYear.value = parseInt(y);
      selectedMonth.value = parseInt(m);
    } else if (mv.type === 'quarter') {
      const [y, q] = mv.value.split('-Q');
      selectedYear.value = parseInt(y);
      selectedQuarter.value = parseInt(q);
    } else if (mv.type === 'year') {
      selectedYear.value = parseInt(mv.value);
    }
  } else if (Array.isArray(mv.value)) {
    if (mv.type === 'month_range') {
      const [start, end] = mv.value;
      const [, m1] = start.split('-');
      const [, m2] = end.split('-');
      monthRangeStart.value = parseInt(m1);
      monthRangeEnd.value = parseInt(m2);
    } else if (mv.type === 'quarter_range') {
      const [start, end] = mv.value;
      const [, q1] = start.split('-Q');
      const [, q2] = end.split('-Q');
      quarterRangeStart.value = parseInt(q1);
      quarterRangeEnd.value = parseInt(q2);
    } else if (mv.type === 'custom') {
      const [start, end] = mv.value;
      const [y1, m1] = start.split('-');
      const [y2, m2] = end.split('-');
      customStartYear.value = parseInt(y1);
      customStartMonth.value = parseInt(m1);
      customEndYear.value = parseInt(y2);
      customEndMonth.value = parseInt(m2);
    }
  }
}

// Lifecycle
onMounted(() => {
  initFromModelValue();
  emitChange();
});

// Watch modelValue changes
watch(() => props.modelValue, initFromModelValue, { deep: true });

// Expose for parent access
defineExpose({
  getCurrentSelection: () => currentSelection.value,
  reset: () => {
    selectThisMonth();
    compareEnabled.value = false;
  }
});
</script>

<template>
  <div class="period-selector" :class="{ 'is-disabled': disabled }">
    <!-- Tab Header -->
    <div v-if="showQuickSelect || showCustomTab" class="selector-tabs">
      <el-radio-group v-model="activeTab" size="small" :disabled="disabled">
        <el-radio-button v-if="showQuickSelect" value="quick">
          <el-icon class="tab-icon"><Clock /></el-icon>
          快捷选择
        </el-radio-button>
        <el-radio-button v-if="showCustomTab" value="custom">
          <el-icon class="tab-icon"><Calendar /></el-icon>
          自定义
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- Quick Selection -->
    <div v-if="activeTab === 'quick' && showQuickSelect" class="quick-selection">
      <div class="quick-buttons">
        <el-button
          v-if="showAllHistory"
          size="small"
          :type="allHistoryMode ? 'primary' : 'default'"
          :disabled="disabled"
          @click="selectAllHistory"
        >全部历史</el-button>
        <el-button size="small" :disabled="disabled" @click="selectThisMonth">本月</el-button>
        <el-button size="small" :disabled="disabled" @click="selectLastMonth">上月</el-button>
        <el-button size="small" :disabled="disabled" @click="selectThisQuarter">本季</el-button>
        <el-button size="small" :disabled="disabled" @click="selectLastQuarter">上季</el-button>
        <el-button size="small" :disabled="disabled" @click="selectThisYear">本年</el-button>
        <el-button size="small" :disabled="disabled" @click="selectLastYear">上年</el-button>
      </div>
    </div>

    <!-- Custom Selection -->
    <div class="custom-selection">
      <!-- Year Selector -->
      <div class="selector-row">
        <span class="row-label">年份:</span>
        <el-select
          v-model="selectedYear"
          :disabled="disabled || selectedType === 'custom'"
          size="small"
          style="width: 120px"
          @change="clearAllHistory"
        >
          <el-option
            v-for="year in yearOptions"
            :key="year.value"
            :label="year.label"
            :value="year.value"
          />
        </el-select>
      </div>

      <!-- Type Selector -->
      <div class="selector-row">
        <span class="row-label">类型:</span>
        <el-radio-group v-model="selectedType" size="small" :disabled="disabled" @change="clearAllHistory">
          <el-radio-button value="month">单月</el-radio-button>
          <el-radio-button value="quarter">单季</el-radio-button>
          <el-radio-button value="year">单年</el-radio-button>
          <el-radio-button value="month_range">月份范围</el-radio-button>
          <el-radio-button value="quarter_range">季度范围</el-radio-button>
          <el-radio-button v-if="showCustomTab" value="custom">自定义范围</el-radio-button>
        </el-radio-group>
      </div>

      <!-- Value Selectors based on type -->
      <div class="selector-row">
        <span class="row-label">选择:</span>

        <!-- Single Month -->
        <template v-if="selectedType === 'month'">
          <el-select
            v-model="selectedMonth"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="m in months"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
        </template>

        <!-- Single Quarter -->
        <template v-else-if="selectedType === 'quarter'">
          <el-select
            v-model="selectedQuarter"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="q in quarters"
              :key="q.value"
              :label="q.label"
              :value="q.value"
            />
          </el-select>
        </template>

        <!-- Single Year - no additional selector needed -->
        <template v-else-if="selectedType === 'year'">
          <span class="display-value">{{ selectedYear }}年</span>
        </template>

        <!-- Month Range -->
        <template v-else-if="selectedType === 'month_range'">
          <el-select
            v-model="monthRangeStart"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="m in months"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
          <span class="range-separator">至</span>
          <el-select
            v-model="monthRangeEnd"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="m in monthEndOptions"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
        </template>

        <!-- Quarter Range -->
        <template v-else-if="selectedType === 'quarter_range'">
          <el-select
            v-model="quarterRangeStart"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="q in quarters"
              :key="q.value"
              :label="q.label"
              :value="q.value"
            />
          </el-select>
          <span class="range-separator">至</span>
          <el-select
            v-model="quarterRangeEnd"
            :disabled="disabled"
            size="small"
            style="width: 100px"
          >
            <el-option
              v-for="q in quarterEndOptions"
              :key="q.value"
              :label="q.label"
              :value="q.value"
            />
          </el-select>
        </template>

        <!-- Custom Range (Cross-year) -->
        <template v-else-if="selectedType === 'custom'">
          <div class="custom-range">
            <div class="custom-range-item">
              <el-select
                v-model="customStartYear"
                :disabled="disabled"
                size="small"
                style="width: 100px"
              >
                <el-option
                  v-for="year in yearOptions"
                  :key="year.value"
                  :label="year.label"
                  :value="year.value"
                />
              </el-select>
              <el-select
                v-model="customStartMonth"
                :disabled="disabled"
                size="small"
                style="width: 90px"
              >
                <el-option
                  v-for="m in months"
                  :key="m.value"
                  :label="m.label"
                  :value="m.value"
                />
              </el-select>
            </div>
            <span class="range-separator">至</span>
            <div class="custom-range-item">
              <el-select
                v-model="customEndYear"
                :disabled="disabled"
                size="small"
                style="width: 100px"
              >
                <el-option
                  v-for="year in yearOptions"
                  :key="year.value"
                  :label="year.label"
                  :value="year.value"
                />
              </el-select>
              <el-select
                v-model="customEndMonth"
                :disabled="disabled"
                size="small"
                style="width: 90px"
              >
                <el-option
                  v-for="m in customEndMonthOptions"
                  :key="m.value"
                  :label="m.label"
                  :value="m.value"
                />
              </el-select>
            </div>
          </div>
        </template>
      </div>

      <!-- Compare Toggle -->
      <div class="selector-row compare-row">
        <el-checkbox v-model="compareEnabled" :disabled="disabled">
          启用同期对比（自动计算去年同期）
        </el-checkbox>
      </div>
    </div>

    <!-- Selection Summary -->
    <div class="selection-summary">
      <div class="summary-main">
        <el-icon><Calendar /></el-icon>
        <span>{{ selectionDisplayText }}</span>
      </div>
      <div v-if="compareEnabled && compareDisplayText" class="summary-compare">
        <el-tag size="small" type="info">{{ compareDisplayText }}</el-tag>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.period-selector {
  background: #ffffff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;

  &.is-disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.selector-tabs {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;

  .tab-icon {
    margin-right: 4px;
    vertical-align: middle;
  }

  :deep(.el-radio-button__inner) {
    display: flex;
    align-items: center;
  }
}

.quick-selection {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px dashed #ebeef5;

  .quick-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;

    .el-button {
      min-width: 60px;
    }
  }
}

.custom-selection {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.selector-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;

  .row-label {
    min-width: 50px;
    font-size: 14px;
    color: #606266;
    font-weight: 500;
  }

  .display-value {
    font-size: 14px;
    color: #303133;
    padding: 4px 8px;
    background: #f5f7fa;
    border-radius: 4px;
  }

  .range-separator {
    color: #909399;
    font-size: 14px;
    margin: 0 4px;
  }

  &.compare-row {
    margin-top: 8px;
    padding-top: 12px;
    border-top: 1px dashed #ebeef5;

    :deep(.el-checkbox__label) {
      font-size: 13px;
      color: #606266;
    }
  }
}

.custom-range {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;

  .custom-range-item {
    display: flex;
    gap: 8px;
  }
}

.selection-summary {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  gap: 8px;

  .summary-main {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;
    color: #303133;

    .el-icon {
      color: #409eff;
    }
  }

  .summary-compare {
    margin-left: 24px;
  }
}

// Responsive adjustments
@media (max-width: 768px) {
  .period-selector {
    padding: 12px;
  }

  .selector-row {
    flex-direction: column;
    align-items: flex-start;

    .row-label {
      margin-bottom: 4px;
    }

    :deep(.el-radio-group) {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
  }

  .custom-range {
    flex-direction: column;
    align-items: flex-start;

    .range-separator {
      margin: 4px 0;
    }
  }

  .quick-buttons {
    justify-content: flex-start;
  }
}
</style>
