<script setup lang="ts">
/**
 * ConditionalFormatPanel - 条件格式化配置面板
 * 支持 threshold / gradient / iconSet / dataBar 规则配置
 * 带预设模板、实时预览、拖拽排序
 */
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormatRule, ThresholdCondition } from '@/services/smartbi/ConditionalFormattingService';
import { ConditionalFormattingService, getConditionalFormattingService } from '@/services/smartbi/ConditionalFormattingService';

// ==================== Props & Emits ====================

interface Props {
  tableId: string;
  availableFields?: string[];
  visible?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  availableFields: () => [],
  visible: true,
});

const emit = defineEmits<{
  change: [rules: FormatRule[]];
  close: [];
}>();

// ==================== State ====================

const service = getConditionalFormattingService();
const rules = ref<FormatRule[]>([...service.getRules(props.tableId)]);
const editingRuleId = ref<string | null>(null);
const dragOverIndex = ref<number | null>(null);
const draggingIndex = ref<number | null>(null);

// ==================== Editing State ====================

const editForm = ref<Partial<FormatRule>>({});
const showAddDialog = ref(false);

function newRuleBase(): Partial<FormatRule> {
  return {
    id: `rule-${Date.now()}`,
    field: props.availableFields[0] ?? '',
    type: 'threshold',
    conditions: [
      { operator: '>', value: 0, style: { backgroundColor: '#d4f7dc', color: '#1a7a3c' } },
    ],
  };
}

function startAdd() {
  editForm.value = newRuleBase();
  editingRuleId.value = null;
  showAddDialog.value = true;
}

function startEdit(rule: FormatRule) {
  editForm.value = JSON.parse(JSON.stringify(rule));
  editingRuleId.value = rule.id;
  showAddDialog.value = true;
}

function cancelEdit() {
  showAddDialog.value = false;
  editForm.value = {};
  editingRuleId.value = null;
}

function saveRule() {
  const r = editForm.value as FormatRule;
  if (!r.id || !r.field || !r.type) {
    ElMessage.warning('请填写完整规则');
    return;
  }
  service.addRule(props.tableId, r);
  rules.value = [...service.getRules(props.tableId)];
  emit('change', rules.value);
  showAddDialog.value = false;
  ElMessage.success('规则已保存');
}

async function removeRule(ruleId: string) {
  await ElMessageBox.confirm('确认删除该规则？', '提示', { type: 'warning' }).catch(() => { throw new Error('cancel'); });
  service.removeRule(props.tableId, ruleId);
  rules.value = [...service.getRules(props.tableId)];
  emit('change', rules.value);
  ElMessage.success('已删除');
}

// ==================== Presets ====================

const PRESETS = [
  { label: '达成率着色', rule: ConditionalFormattingService.presets.achievementRate },
  { label: '增长率着色', rule: ConditionalFormattingService.presets.growthRate },
  { label: '红绿灯', rule: ConditionalFormattingService.presets.trafficLight },
  { label: '方差着色', rule: ConditionalFormattingService.presets.variance },
];

function applyPreset(preset: typeof PRESETS[number]) {
  const r: FormatRule = {
    ...JSON.parse(JSON.stringify(preset.rule)),
    id: `preset-${Date.now()}`,
    field: props.availableFields[0] ?? preset.rule.field,
  };
  service.addRule(props.tableId, r);
  rules.value = [...service.getRules(props.tableId)];
  emit('change', rules.value);
  ElMessage.success(`已应用预设: ${preset.label}`);
}

// ==================== Condition Editor Helpers ====================

function addCondition() {
  if (!editForm.value.conditions) editForm.value.conditions = [];
  editForm.value.conditions.push({ operator: '>', value: 0, style: { backgroundColor: '#ffffff', color: '#303133' } });
}

function removeCondition(idx: number) {
  editForm.value.conditions?.splice(idx, 1);
}

// ==================== Drag to Reorder ====================

function onDragStart(idx: number) {
  draggingIndex.value = idx;
}

function onDragOver(idx: number) {
  dragOverIndex.value = idx;
}

function onDrop(idx: number) {
  if (draggingIndex.value === null || draggingIndex.value === idx) return;
  const arr = [...rules.value];
  const [moved] = arr.splice(draggingIndex.value, 1);
  arr.splice(idx, 0, moved);
  rules.value = arr;
  draggingIndex.value = null;
  dragOverIndex.value = null;
  emit('change', rules.value);
}

function onDragEnd() {
  draggingIndex.value = null;
  dragOverIndex.value = null;
}

// ==================== Preview ====================

/** Preview sample values for the preview strip */
const PREVIEW_VALUES = [20, 40, 60, 80, 100, 120];

function previewStyle(value: number): Record<string, string> {
  if (!editForm.value.id) return {};
  return service.evaluateCell(props.tableId, editForm.value.field ?? '', value) as Record<string, string>;
}

// ==================== Rule Type Labels ====================

const TYPE_LABELS: Record<string, string> = {
  threshold: '阈值着色',
  gradient: '渐变色阶',
  iconSet: '图标集',
  dataBar: '数据条',
};

const OPERATOR_LABELS: Record<string, string> = {
  '>': '大于',
  '<': '小于',
  '>=': '大于等于',
  '<=': '小于等于',
  '==': '等于',
  between: '介于',
};

function onTypeChange() {
  const type = editForm.value.type;
  if (type === 'threshold') {
    editForm.value.conditions = [{ operator: '>', value: 0, style: { backgroundColor: '#d4f7dc', color: '#1a7a3c' } }];
    delete editForm.value.gradient;
    delete editForm.value.iconSet;
    delete editForm.value.dataBar;
  } else if (type === 'gradient') {
    editForm.value.gradient = {
      min: { value: 0, color: '#f56c6c' },
      mid: { value: 50, color: '#ffffff' },
      max: { value: 100, color: '#36B37E' },
    };
    delete editForm.value.conditions;
  } else if (type === 'iconSet') {
    editForm.value.iconSet = { type: 'arrows', thresholds: [66, 33] };
    delete editForm.value.conditions;
  } else if (type === 'dataBar') {
    editForm.value.dataBar = { minValue: 0, maxValue: 100, positiveColor: '#2D8B57', negativeColor: '#f56c6c' };
    delete editForm.value.conditions;
  }
}

watch(() => props.tableId, () => {
  rules.value = [...service.getRules(props.tableId)];
});
</script>

<template>
  <div class="cfp">
    <!-- Panel Header -->
    <div class="cfp__header">
      <span class="cfp__header-title">条件格式</span>
      <el-button text @click="emit('close')">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>

    <!-- Presets -->
    <div class="cfp__presets">
      <div class="cfp__section-label">快速预设</div>
      <div class="cfp__preset-tags">
        <el-tag
          v-for="p in PRESETS"
          :key="p.label"
          type="info"
          effect="plain"
          class="cfp__preset-tag"
          @click="applyPreset(p)"
        >{{ p.label }}</el-tag>
      </div>
    </div>

    <!-- Rule List -->
    <div class="cfp__rules">
      <div class="cfp__section-label">
        当前规则
        <span class="cfp__rule-count">({{ rules.length }})</span>
      </div>

      <div v-if="rules.length === 0" class="cfp__empty">
        暂无规则，点击下方添加
      </div>

      <div
        v-for="(rule, idx) in rules"
        :key="rule.id"
        class="cfp__rule-item"
        :class="{ 'cfp__rule-item--dragover': dragOverIndex === idx }"
        draggable="true"
        @dragstart="onDragStart(idx)"
        @dragover.prevent="onDragOver(idx)"
        @drop="onDrop(idx)"
        @dragend="onDragEnd"
      >
        <el-icon class="cfp__drag-handle"><Rank /></el-icon>
        <div class="cfp__rule-info">
          <span class="cfp__rule-field">{{ rule.field }}</span>
          <span class="cfp__rule-type">{{ TYPE_LABELS[rule.type] }}</span>
        </div>
        <div class="cfp__rule-actions">
          <el-button text size="small" @click="startEdit(rule)">
            <el-icon><Edit /></el-icon>
          </el-button>
          <el-button text size="small" type="danger" @click="removeRule(rule.id)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <!-- Add Button -->
    <div class="cfp__footer">
      <el-button type="primary" size="small" @click="startAdd">
        <el-icon><Plus /></el-icon>
        添加规则
      </el-button>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="showAddDialog"
      :title="editingRuleId ? '编辑规则' : '新建规则'"
      width="520px"
      :close-on-click-modal="false"
      @close="cancelEdit"
    >
      <div class="cfp__dialog-body">
        <!-- Field + Type -->
        <div class="cfp__form-row">
          <div class="cfp__form-group">
            <label class="cfp__form-label">字段</label>
            <el-select v-model="editForm.field" placeholder="选择字段" style="width: 100%">
              <el-option
                v-for="f in availableFields"
                :key="f"
                :label="f"
                :value="f"
              />
            </el-select>
          </div>
          <div class="cfp__form-group">
            <label class="cfp__form-label">规则类型</label>
            <el-select v-model="editForm.type" style="width: 100%" @change="onTypeChange">
              <el-option label="阈值着色" value="threshold" />
              <el-option label="渐变色阶" value="gradient" />
              <el-option label="图标集" value="iconSet" />
              <el-option label="数据条" value="dataBar" />
            </el-select>
          </div>
        </div>

        <!-- Threshold Editor -->
        <template v-if="editForm.type === 'threshold'">
          <div class="cfp__conditions-header">
            <span>条件列表</span>
            <el-button text size="small" @click="addCondition">
              <el-icon><Plus /></el-icon> 添加条件
            </el-button>
          </div>
          <div
            v-for="(cond, idx) in editForm.conditions ?? []"
            :key="idx"
            class="cfp__condition-row"
          >
            <el-select v-model="cond.operator" style="width: 100px">
              <el-option
                v-for="(label, op) in OPERATOR_LABELS"
                :key="op"
                :label="label"
                :value="op"
              />
            </el-select>
            <template v-if="cond.operator === 'between'">
              <el-input-number
                v-model="(cond.value as [number,number])[0]"
                :controls="false"
                style="width: 72px"
                placeholder="最小"
              />
              <span class="cfp__between-sep">~</span>
              <el-input-number
                v-model="(cond.value as [number,number])[1]"
                :controls="false"
                style="width: 72px"
                placeholder="最大"
              />
            </template>
            <el-input-number
              v-else
              v-model="cond.value as number"
              :controls="false"
              style="width: 80px"
            />
            <label class="cfp__color-label">背景</label>
            <input type="color" v-model="cond.style.backgroundColor" class="cfp__color-input" />
            <label class="cfp__color-label">文字</label>
            <input type="color" v-model="cond.style.color" class="cfp__color-input" />
            <el-button text size="small" type="danger" @click="removeCondition(idx)">
              <el-icon><Minus /></el-icon>
            </el-button>
          </div>
        </template>

        <!-- Gradient Editor -->
        <template v-if="editForm.type === 'gradient' && editForm.gradient">
          <div class="cfp__gradient-editor">
            <div
              v-for="stop in ['min', 'mid', 'max'] as const"
              :key="stop"
              class="cfp__gradient-stop"
            >
              <template v-if="editForm.gradient![stop]">
                <label class="cfp__form-label">{{ stop === 'min' ? '最小值' : stop === 'mid' ? '中间值' : '最大值' }}</label>
                <el-input-number
                  v-model="editForm.gradient![stop]!.value"
                  :controls="false"
                  style="width: 80px"
                />
                <input type="color" v-model="editForm.gradient![stop]!.color" class="cfp__color-input" />
              </template>
            </div>
          </div>
        </template>

        <!-- IconSet Editor -->
        <template v-if="editForm.type === 'iconSet' && editForm.iconSet">
          <div class="cfp__form-row">
            <div class="cfp__form-group">
              <label class="cfp__form-label">图标风格</label>
              <el-select v-model="editForm.iconSet!.type" style="width: 100%">
                <el-option label="箭头 ↑→↓" value="arrows" />
 <el-option label="红绿灯 " value="traffic" />
                <el-option label="星级 ⭐" value="stars" />
 <el-option label="旗帜 ️" value="flags" />
              </el-select>
            </div>
            <div class="cfp__form-group">
              <label class="cfp__form-label">阈值 (高→低，逗号分隔)</label>
              <el-input
                :model-value="(editForm.iconSet!.thresholds ?? []).join(',')"
                @update:model-value="(v: string) => { editForm.iconSet!.thresholds = v.split(',').map(Number).filter(n => !isNaN(n)); }"
                placeholder="如: 66,33"
              />
            </div>
          </div>
        </template>

        <!-- DataBar Editor -->
        <template v-if="editForm.type === 'dataBar' && editForm.dataBar">
          <div class="cfp__form-row">
            <div class="cfp__form-group">
              <label class="cfp__form-label">最小值</label>
              <el-input-number v-model="editForm.dataBar!.minValue" :controls="false" style="width: 100%" />
            </div>
            <div class="cfp__form-group">
              <label class="cfp__form-label">最大值</label>
              <el-input-number v-model="editForm.dataBar!.maxValue" :controls="false" style="width: 100%" />
            </div>
          </div>
          <div class="cfp__form-row">
            <div class="cfp__form-group cfp__form-group--color">
              <label class="cfp__form-label">正值颜色</label>
              <input type="color" v-model="editForm.dataBar!.positiveColor" class="cfp__color-input cfp__color-input--large" />
            </div>
            <div class="cfp__form-group cfp__form-group--color">
              <label class="cfp__form-label">负值颜色</label>
              <input type="color" v-model="editForm.dataBar!.negativeColor" class="cfp__color-input cfp__color-input--large" />
            </div>
          </div>
        </template>

        <!-- Live Preview -->
        <div class="cfp__preview">
          <div class="cfp__preview-label">预览效果</div>
          <div class="cfp__preview-cells">
            <div
              v-for="val in PREVIEW_VALUES"
              :key="val"
              class="cfp__preview-cell"
              :style="previewStyle(val)"
            >
              {{ val }}
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="cancelEdit">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cfp {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  min-width: 240px;
  background: #fff;
  border-radius: 8px;
}

.cfp__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cfp__header-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.cfp__section-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
  font-weight: 500;
}

.cfp__rule-count {
  color: #606266;
}

.cfp__preset-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.cfp__preset-tag {
  cursor: pointer;
  transition: background 0.15s;
}

.cfp__preset-tag:hover {
  background: #ecf5ff;
  border-color: #2D8B57;
  color: #2D8B57;
}

.cfp__empty {
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
  padding: 12px 0;
}

.cfp__rule-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
  margin-bottom: 4px;
  cursor: grab;
  transition: border-color 0.15s;
}

.cfp__rule-item:hover {
  border-color: #c6d9f0;
}

.cfp__rule-item--dragover {
  border-color: #2D8B57;
  background: #ecf5ff;
}

.cfp__drag-handle {
  color: #c0c4cc;
  cursor: grab;
  flex-shrink: 0;
}

.cfp__rule-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cfp__rule-field {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cfp__rule-type {
  font-size: 11px;
  color: #909399;
}

.cfp__rule-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.cfp__footer {
  border-top: 1px solid #f0f0f0;
  padding-top: 10px;
  display: flex;
  justify-content: center;
}

/* Dialog body */
.cfp__dialog-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cfp__form-row {
  display: flex;
  gap: 12px;
}

.cfp__form-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cfp__form-group--color {
  align-items: flex-start;
}

.cfp__form-label {
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}

.cfp__conditions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}

.cfp__condition-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  background: #f9f9f9;
  border-radius: 6px;
  flex-wrap: wrap;
}

.cfp__between-sep {
  color: #909399;
  font-size: 12px;
}

.cfp__color-label {
  font-size: 11px;
  color: #909399;
  flex-shrink: 0;
}

.cfp__color-input {
  width: 28px;
  height: 24px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 1px;
  cursor: pointer;
  background: none;
}

.cfp__color-input--large {
  width: 40px;
  height: 32px;
}

.cfp__gradient-editor {
  display: flex;
  gap: 16px;
}

.cfp__gradient-stop {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
}

/* Preview */
.cfp__preview {
  border-top: 1px solid #f0f0f0;
  padding-top: 10px;
}

.cfp__preview-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.cfp__preview-cells {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.cfp__preview-cell {
  min-width: 40px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 500;
  border-radius: 4px;
  border: 1px solid #e8eaed;
  padding: 0 6px;
  transition: background 0.2s;
}
</style>
