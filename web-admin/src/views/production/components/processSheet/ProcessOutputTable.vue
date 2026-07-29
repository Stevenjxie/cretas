<script setup lang="ts">
/**
 * 产出明细 —— 紧凑表格布局。
 *
 * 客户实测反馈: 每条产出各占一整块(头部 + 4 列字段 + 副产区 + 重量提示), 同一个产出品名在一张卡里
 * 出现三次(作业时间、产出明细、扣料结果), 「视觉上很散」。这里把作业时间并进产出行, 副产与成本分摊
 * 收进按需展开区, 一条产出一行。
 *
 * 无障碍: 表头只是视觉对齐用 (aria-hidden), 每个控件自带 aria-label —— 否则表格化之后
 * 屏幕阅读器会念到一串没有名字的输入框。
 *
 * 副产区用 v-show 而不是 v-if: 它是「按需填写」不是「按需存在」, 折叠状态下依然要能被
 * 表单校验和既有测试找到 (per ProcessDataTable.portReporting.spec.ts)。
 */
import { ref } from 'vue';
import { ArrowDown, ArrowRight, Warning } from '@element-plus/icons-vue';
import { outputGridTemplate, type MultiOutputLine, type OutputLineView } from './processSheetOutputs';

const props = defineProps<{
  views: OutputLineView[];
  /** 产出维度无法统一时才要人工填分摊比例。 */
  showCostAllocation: boolean;
  /** 标题右侧的说明文字; 卡片/表格两种视图措辞不同。 */
  hint: string;
}>();

const emit = defineEmits<{
  (e: 'toggle-select', portId: string, selected: boolean): void;
  (e: 'open-spec', line: MultiOutputLine): void;
}>();

/** 展开了副产/成本分摊的端口 id。默认全收起 —— 绝大多数报工不填这两项。 */
const expanded = ref<Set<string>>(new Set());

function detailOpen(portId: string): boolean {
  return expanded.value.has(portId);
}

function toggleDetail(portId: string): void {
  const next = new Set(expanded.value);
  if (next.has(portId)) next.delete(portId); else next.add(portId);
  expanded.value = next;
}

function withSelector(): boolean {
  return props.views.some((view) => view.selectorVisible);
}

function gridStyle(): Record<string, string> {
  return { gridTemplateColumns: outputGridTemplate(withSelector()) };
}
</script>

<template>
  <div class="sp-io">
    <div class="sp-io-title">
      <span><b>③</b> 产出明细 — {{ views.length }} 项</span>
      <span>{{ hint }}</span>
    </div>

    <div class="sp-io-table">
      <div class="sp-io-head" :style="gridStyle()" aria-hidden="true">
        <span v-if="withSelector()">选用</span>
        <span>产出品</span>
        <span><i class="sp-required">*</i>产出数量</span>
        <span>出成率</span>
        <span>开始时间</span>
        <span>结束时间</span>
        <span>人数</span>
        <span>总工时</span>
        <span></span>
      </div>

      <div
        v-for="(view, vi) in views"
        :key="view.line.workflowPortId || vi"
        data-testid="workflow-output-line"
        class="sp-io-line"
        :class="{ 'sp-port-unselected': !view.line.selected }"
      >
        <div class="sp-io-row" :style="gridStyle()">
          <span v-if="withSelector()" class="sp-io-cell">
            <el-checkbox
              v-if="view.selectorVisible"
              :model-value="view.line.selected"
              :disabled="view.selectorDisabled"
              data-testid="port-selected"
              :aria-label="`选用 ${view.line.materialName}`"
              @change="(selected: boolean) => emit('toggle-select', view.line.workflowPortId, selected)"
            />
          </span>

          <span class="sp-io-cell sp-io-product">
            <strong>{{ view.line.materialName }}</strong>
            <el-tag size="small" :type="view.line.finished ? 'success' : 'warning'">
              {{ view.line.finished ? '成品' : '半成品' }}
            </el-tag>
            <span v-if="view.line.batchNumber" class="sp-batch-num">{{ view.line.batchNumber }}</span>
          </span>

          <span class="sp-io-cell" data-testid="output-quantity">
            <span class="sp-inline-input" role="group" aria-label="产出数量与单位">
              <el-input-number
                v-model="view.line.quantity"
                :min="0"
                :precision="view.quantityPrecision"
                controls-position="right"
                size="small"
                :aria-label="`${view.line.materialName} 产出数量`"
              />
              <span data-testid="output-unit-readonly" class="sp-fixed-unit">{{ view.unitLabel }}</span>
            </span>
          </span>

          <span class="sp-io-cell sp-io-num">{{ view.yieldText }}</span>

          <!--
            作业时间与人数原本是独立一节, 每条产出在那里再出现一次。并进产出行后品名只出现一次,
            但 data-testid 保留 —— 它标记的是「这条产出的执行事实」, 与摆在哪一节无关。
          -->
          <span class="sp-io-cell sp-io-exec" data-testid="workflow-execution-line">
            <span class="sp-io-exec-field" data-testid="output-start-time">
              <el-time-picker
                v-model="view.line.startTime"
                value-format="HH:mm"
                format="HH:mm"
                placeholder="开始时间…"
                size="small"
                :aria-label="`${view.line.materialName} 开始时间`"
              />
            </span>
            <span class="sp-io-exec-field" data-testid="output-end-time">
              <el-time-picker
                v-model="view.line.endTime"
                value-format="HH:mm"
                format="HH:mm"
                placeholder="结束时间…"
                size="small"
                :aria-label="`${view.line.materialName} 结束时间`"
              />
            </span>
            <span class="sp-io-exec-field" data-testid="output-worker-count">
              <el-input-number
                v-model="view.line.workerCount"
                :min="1"
                :precision="0"
                controls-position="right"
                size="small"
                :aria-label="`${view.line.materialName} 人数`"
              />
            </span>
            <span class="sp-io-exec-field sp-io-num sp-readonly">{{ view.totalHoursText }} h</span>
          </span>

          <span class="sp-io-cell sp-io-more">
            <el-button
              link
              size="small"
              :aria-expanded="detailOpen(view.line.workflowPortId)"
              :title="`副产与成本分摊 — ${view.line.materialName}`"
              @click="toggleDetail(view.line.workflowPortId)"
            >
              <el-icon><component :is="detailOpen(view.line.workflowPortId) ? ArrowDown : ArrowRight" /></el-icon>
              副产
            </el-button>
          </span>
        </div>

        <!--
          算不出来时说清楚为什么, 并给一个就地补规格的入口。
          只显示「—」会让人以为是系统坏了, 而实际是缺一个可以当场填的数。
        -->
        <div v-if="view.blocker" class="sp-yield-blocked">
          <el-icon><Warning /></el-icon>
          <span>{{ view.blocker }}</span>
          <el-button link type="primary" size="small" @click="emit('open-spec', view.line)">去设置</el-button>
        </div>

        <div v-show="detailOpen(view.line.workflowPortId)" class="sp-io-detail">
          <div class="sp-io-detail-title">按需填写：副产与成本分摊</div>
          <div class="sp-io-detail-fields">
            <label data-testid="byproduct-quantity">副产数量
              <span class="sp-inline-input" role="group" aria-label="副产数量与单位">
                <el-input-number
                  v-model="view.line.byproductQuantity"
                  :min="0"
                  :precision="6"
                  controls-position="right"
                  size="small"
                />
                <span data-testid="byproduct-unit-readonly" class="sp-fixed-unit">{{ view.byproductUnitLabel }}</span>
              </span>
            </label>
            <label data-testid="byproduct-unit-price">副产回收单价
              <el-input-number
                v-model="view.line.byproductUnitPrice"
                :min="0"
                :precision="4"
                controls-position="right"
                size="small"
              />
            </label>
            <label v-if="showCostAllocation" data-testid="cost-allocation-ratio">成本分摊比例(%)
              <el-input-number
                v-model="view.line.costAllocationRatio"
                :min="0"
                :max="100"
                :precision="4"
                controls-position="right"
                size="small"
              />
            </label>
            <span v-if="view.weightHint" class="sp-io-weight-hint">{{ view.weightHint }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sp-io { display: flex; flex-direction: column; gap: 8px; }

.sp-io-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}
.sp-io-title b { color: #1677ff; }
.sp-io-title span:last-child {
  color: #909399;
  font-weight: 400;
  font-size: 11px;
}

.sp-io-table {
  border: 1px solid #e4e9f2;
  border-radius: 6px;
  overflow-x: auto;
  background: #fff;
}

.sp-io-head,
.sp-io-row {
  display: grid;
  align-items: center;
  gap: 0 10px;
  min-width: 940px;
}

.sp-io-head {
  padding: 7px 10px;
  border-bottom: 1px solid #e4e9f2;
  background: #f5f7fa;
  color: #606266;
  font-size: 11px;
  font-weight: 600;
}

.sp-io-line { border-top: 1px solid #eef1f6; }
.sp-io-line:first-of-type { border-top: 0; }
.sp-port-unselected { opacity: 0.58; }

.sp-io-row { padding: 7px 10px; }

.sp-io-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: #303133;
}
.sp-io-num { justify-content: flex-end; font-variant-numeric: tabular-nums; }

.sp-io-product { flex-wrap: wrap; }
.sp-io-product strong { font-size: 13px; }
.sp-batch-num { color: #909399; font-size: 11px; }

/*
  作业时间的控件横跨开始/结束/人数/总工时四列。这里显式重复这四个列宽而不是用 subgrid ——
  subgrid 的浏览器下限比这个后台要支持的高, 而这四列本来就是定宽, 显式写死对齐结果完全相同。
  列宽必须与 outputGridTemplate() 的后四列保持一致。
*/
.sp-io-exec {
  grid-column: span 4;
  display: grid;
  grid-template-columns: 120px 120px 92px 86px;
  gap: 0 10px;
}
.sp-io-exec-field { display: inline-flex; align-items: center; min-width: 0; }

.sp-io-more { justify-content: flex-end; }

.sp-required { margin-right: 2px; color: var(--el-color-danger); font-style: normal; font-weight: 700; }

.sp-yield-blocked {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px 7px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning-dark-2);
}
.sp-yield-blocked .el-icon { flex: none; }

.sp-io-detail {
  padding: 8px 10px 10px;
  border-top: 1px dashed #e9edf3;
  background: #fbfcfe;
}
.sp-io-detail-title { margin-bottom: 6px; color: #909399; font-size: 11px; }
.sp-io-detail-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px 18px;
}
.sp-io-detail-fields > label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #909399;
  font-size: 11px;
  font-weight: 600;
}
.sp-io-weight-hint { color: #909399; font-size: 11px; }

.sp-inline-input { display: inline-flex; align-items: center; gap: 4px; }
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
.sp-readonly { color: #303133; font-size: 12px; }

/* 窄屏下让表格自己横向滚动, 不把整页撑宽 */
@media (max-width: 1366px) {
  .sp-io-table { max-width: 100%; }
}
</style>
