<script setup lang="ts">
/**
 * 半成品/成品/在制 投入来源 —— 紧凑行式布局 (「选用 + 名称 + 数量 + 来源批次」)。
 *
 * 客户实测反馈: 每条来源是一条 flex 行, 品名/下拉/数量/单位/余量/两个按钮横着排, 字段没有表头,
 * 多条并排时看不出哪一列是什么, 「视觉上很散」。原料投入端口那一块已经改成带表头的行式表,
 * 这里对齐同一套观感。
 *
 * 这个块原本在 ProcessDataTable.vue 里写了三遍 (卡片 1 + 表格模式熟制/气调各 1)。三份必然漂移,
 * 抽成子组件后只剩一份实现。
 *
 * 无障碍: 表头只是视觉对齐用 (aria-hidden), 每个控件自带 aria-label —— 否则表格化之后
 * 屏幕阅读器会念到一串没有名字的输入框。
 *
 * 防呆:
 *  - Rule 1 预先显示边界: 已选批次的余量就摆在这一行, 不让操作员填完点「正式报工」才被告知超领。
 *  - Rule 2 上下文必带身份: 每个控件的 aria-label / title 都带物料名。
 *  - 数量**不自动带出**: 投入不一定等于上一道产出 (损耗/只用一部分/补料), 以实填为准
 *    (Steve 2026-07-30 拍板); 自动的只有「唯一候选批次自动选中」那一步。
 */
import { Delete, Plus } from '@element-plus/icons-vue';
import { inputSourceGridTemplate, type InputSourceLineView } from './processSheetInputs';

const props = defineProps<{
  views: InputSourceLineView[];
  /** 标题右侧的说明文字; 卡片/表格两种视图措辞不同。 */
  hint: string;
  /** 上游库存仍在拉取时下拉显加载态, 不显「暂无」。 */
  loading: boolean;
  /** legacy (无 workflow 上游端口) 才允许手工加来源批。 */
  showAddSource: boolean;
  addSourceLabel: string;
  batchPlaceholder: string;
}>();

const emit = defineEmits<{
  (e: 'toggle-select', index: number, selected: boolean): void;
  (e: 'select-batch', index: number, key: string): void;
  (e: 'remove', index: number): void;
  (e: 'add-same-material', index: number): void;
  (e: 'add-source'): void;
  (e: 'filter-batches', query: string): void;
  (e: 'batch-picker-visible', visible: boolean): void;
}>();

function withSelector(): boolean {
  return props.views.some((view) => view.selectorVisible);
}

function gridStyle(): Record<string, string> {
  return { gridTemplateColumns: inputSourceGridTemplate(withSelector()) };
}
</script>

<template>
  <div class="sp-src">
    <div class="sp-src-title">
      <span><b>①</b> 投入来源 — {{ views.length }} 项</span>
      <span>{{ hint }}</span>
      <el-button
        v-if="showAddSource"
        size="small"
        :icon="Plus"
        :aria-label="addSourceLabel"
        @click="emit('add-source')"
      >{{ addSourceLabel }}</el-button>
    </div>

    <div class="sp-src-table">
      <div class="sp-src-head" :style="gridStyle()" aria-hidden="true">
        <span v-if="withSelector()">选用</span>
        <span>投入物料</span>
        <span><i class="sp-required">*</i>投入数量</span>
        <span>来源批次</span>
        <span></span>
      </div>

      <div
        v-for="view in views"
        :key="`${view.source.workflowPortId || 'legacy'}-${view.index}`"
        data-testid="upstream-source-line"
        class="sp-src-line"
        :class="{ 'sp-port-unselected': !view.source.selected }"
      >
        <div class="sp-src-row" :style="gridStyle()">
          <span v-if="withSelector()" class="sp-src-cell">
            <el-checkbox
              v-if="view.selectorVisible"
              :model-value="view.source.selected"
              :disabled="view.selectorDisabled"
              data-testid="port-selected"
              :aria-label="`选用 ${view.materialName}`"
              @change="(selected: boolean) => emit('toggle-select', view.index, selected)"
            />
          </span>

          <span class="sp-src-cell sp-src-product">
            <strong data-testid="input-port-name">{{ view.materialName }}</strong>
          </span>

          <span class="sp-src-cell" data-testid="input-source-quantity">
            <span class="sp-inline-input" role="group" aria-label="投入数量与单位">
              <el-input-number
                v-model="view.source.feedQuantityKg"
                :min="0"
                :precision="2"
                :placeholder="view.quantityPlaceholder"
                controls-position="right"
                size="small"
                :aria-label="`${view.materialName} 投入数量`"
              />
              <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ view.unitLabel }}</span>
            </span>
          </span>

          <span class="sp-src-cell sp-src-batch">
            <!--
              唯一候选批次: 直接显示批次, 不给下拉。父组件已把它写进 source.sourceBatchNumber,
              这里只是把「已经定下来的事实」显示出来 —— 再摆一个只有一个选项的下拉就是白点一次。
            -->
            <span
              v-if="view.soleBatchLabel"
              data-testid="upstream-batch-fixed"
              class="sp-fixed-batch"
              :title="view.soleBatchLabel"
            >{{ view.soleBatchLabel }}</span>
            <el-select
              v-else
              :model-value="view.selectKey"
              :placeholder="batchPlaceholder"
              filterable
              clearable
              :filter-method="(query: string) => emit('filter-batches', query)"
              :loading="loading"
              :aria-label="`${view.materialName} 来源批次`"
              data-testid="upstream-batch-select"
              size="small"
              class="sp-src-select"
              @change="(key: string) => emit('select-batch', view.index, key)"
              @visible-change="(visible: boolean) => emit('batch-picker-visible', visible)"
            >
              <el-option-group
                v-for="group in view.optionGroups"
                :key="group.label"
                :label="group.label"
              >
                <el-option
                  v-for="option in group.options"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                  :disabled="option.disabled"
                />
              </el-option-group>
            </el-select>
            <span v-if="view.remainingText" class="sp-src-remaining">{{ view.remainingText }}</span>
          </span>

          <span class="sp-src-cell sp-src-actions">
            <el-button
              v-if="view.canClear"
              link
              type="danger"
              :icon="Delete"
              :title="`清除 ${view.materialName} 的来源批次`"
              :aria-label="`清除 ${view.materialName} 的来源批次`"
              @click="emit('remove', view.index)"
            />
            <el-button
              v-if="view.canAddSameMaterial"
              link
              type="primary"
              :icon="Plus"
              :title="`为 ${view.materialName} 再加一个来源批次`"
              :aria-label="`为 ${view.materialName} 再加一个来源批次`"
              @click="emit('add-same-material', view.index)"
            />
          </span>
        </div>
      </div>

      <div v-if="views.length === 0" class="sp-src-empty">
        暂无来源批，点击「{{ addSourceLabel }}」添加
      </div>
    </div>
  </div>
</template>

<style scoped>
.sp-src { display: flex; flex-direction: column; gap: 8px; }

.sp-src-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}
.sp-src-title b { color: #1677ff; }
.sp-src-title span:nth-child(2) {
  color: #909399;
  font-weight: 400;
  font-size: 11px;
}

.sp-src-table {
  border: 1px solid #e4e9f2;
  border-radius: 6px;
  overflow-x: auto;
  background: #fff;
}

.sp-src-head,
.sp-src-row {
  display: grid;
  align-items: center;
  gap: 0 10px;
  min-width: 760px;
}

.sp-src-head {
  padding: 7px 10px;
  border-bottom: 1px solid #e4e9f2;
  background: #f5f7fa;
  color: #606266;
  font-size: 11px;
  font-weight: 600;
}

.sp-src-line { border-top: 1px solid #eef1f6; }
.sp-src-line:first-of-type { border-top: 0; }
.sp-port-unselected { opacity: 0.58; }

.sp-src-row { padding: 7px 10px; }

.sp-src-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: #303133;
}
.sp-src-product strong { font-size: 13px; }
.sp-src-batch { flex-wrap: wrap; }
.sp-src-select { min-width: 0; flex: 1 1 200px; }
.sp-src-remaining { color: var(--el-text-color-secondary); font-size: 11px; white-space: nowrap; }
.sp-fixed-batch {
  overflow: hidden;
  font-weight: 600;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sp-src-actions { justify-content: flex-end; }

.sp-src-empty { padding: 8px 10px; color: #909399; font-size: 12px; }

.sp-required { margin-right: 2px; color: var(--el-color-danger); font-style: normal; font-weight: 700; }

.sp-inline-input { display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.sp-inline-input :deep(.el-input-number) { flex: 1; min-width: 0; }
.sp-fixed-unit {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 4px;
  background: #f5f7fa;
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
}

/* 窄屏下让表格自己横向滚动, 不把整页撑宽 */
@media (max-width: 1366px) {
  .sp-src-table { max-width: 100%; }
}
</style>
