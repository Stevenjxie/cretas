<script setup lang="ts">
/**
 * 动态实体表单 — 根据 FieldConfig[] 元数据自动渲染 el-form
 *
 * 用法:
 *   <DynamicEntityForm :fields="productTypeFields" v-model="formData" :readonly="false" />
 *
 * 添加新字段只需在 entityFieldConfigs.ts 的数组中增加一行配置。
 */
import { computed } from 'vue'
import type { FieldConfig, EntityFieldGroup } from '@/config/entityFieldConfigs'
import { groupFields } from '@/config/entityFieldConfigs'
import { formatAmount } from '@/utils/tableFormatters'

const props = withDefaults(defineProps<{
  fields: FieldConfig[]
  modelValue: Record<string, unknown>
  readonly?: boolean
  labelWidth?: string
  columns?: number
}>(), {
  readonly: false,
  labelWidth: '130px',
  columns: 2,
})

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const groups = computed<EntityFieldGroup[]>(() => groupFields(props.fields))

function updateField(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function getFieldValue(key: string): unknown {
  return props.modelValue?.[key] ?? null
}

function getSpan(field: FieldConfig): number {
  return field.span ?? (24 / props.columns)
}

function isFieldReadonly(field: FieldConfig): boolean {
  return props.readonly || !!field.readonly
}

function displayValue(field: FieldConfig): string {
  const val = getFieldValue(field.key)
  if (val == null || val === '') return '-'
  if (field.type === 'boolean') return val ? '是' : '否'
  if (field.type === 'select' && field.options) {
    const opt = field.options.find(o => o.value === String(val))
    return opt ? opt.label : String(val)
  }
  if (field.type === 'decimal' || field.type === 'number') {
    const formatted = field.type === 'decimal' ? formatAmount(val as number) : String(val)
    return field.suffix ? `${formatted} ${field.suffix}` : formatted
  }
  return String(val)
}
</script>

<template>
  <div class="dynamic-entity-form">
    <template v-for="group in groups" :key="group.name">
      <div class="form-group">
        <div class="form-group-title">{{ group.label }}</div>
        <el-row :gutter="16">
          <el-col v-for="field in group.fields" :key="field.key" :span="getSpan(field)">
            <!-- 只读模式: el-descriptions 风格 -->
            <div v-if="isFieldReadonly(field)" class="readonly-field">
              <span class="readonly-label">{{ field.label }}</span>
              <span class="readonly-value">{{ displayValue(field) }}</span>
            </div>

            <!-- 编辑模式 -->
            <el-form-item v-else :label="field.label" :required="field.required" :label-width="labelWidth">
              <!-- text -->
              <el-input
                v-if="field.type === 'text'"
                :model-value="getFieldValue(field.key) as string"
                :placeholder="field.placeholder || `请输入${field.label}`"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- textarea -->
              <el-input
                v-else-if="field.type === 'textarea'"
                type="textarea"
                :rows="3"
                :model-value="getFieldValue(field.key) as string"
                :placeholder="field.placeholder || `请输入${field.label}`"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- number -->
              <el-input-number
                v-else-if="field.type === 'number'"
                :model-value="getFieldValue(field.key) as number"
                :placeholder="field.placeholder"
                controls-position="right"
                style="width: 100%"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- decimal -->
              <el-input
                v-else-if="field.type === 'decimal'"
                :model-value="getFieldValue(field.key) as string"
                :placeholder="field.placeholder || `请输入${field.label}`"
                @update:model-value="updateField(field.key, $event)"
              >
                <template #suffix v-if="field.suffix">{{ field.suffix }}</template>
              </el-input>

              <!-- select -->
              <el-select
                v-else-if="field.type === 'select'"
                :model-value="getFieldValue(field.key) as string"
                :placeholder="field.placeholder || `请选择${field.label}`"
                clearable
                filterable
                :allow-create="field.allowCreate"
                style="width: 100%"
                @update:model-value="updateField(field.key, $event)"
              >
                <el-option
                  v-for="opt in field.options"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>

              <!-- date -->
              <el-date-picker
                v-else-if="field.type === 'date'"
                :model-value="getFieldValue(field.key) as string"
                type="date"
                value-format="YYYY-MM-DD"
                :placeholder="field.placeholder || `选择${field.label}`"
                style="width: 100%"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- datetime -->
              <el-date-picker
                v-else-if="field.type === 'datetime'"
                :model-value="getFieldValue(field.key) as string"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :placeholder="field.placeholder || `选择${field.label}`"
                style="width: 100%"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- boolean -->
              <el-switch
                v-else-if="field.type === 'boolean'"
                :model-value="!!getFieldValue(field.key)"
                @update:model-value="updateField(field.key, $event)"
              />

              <!-- image (简单URL展示，后续可扩展为上传组件) -->
              <el-input
                v-else-if="field.type === 'image'"
                :model-value="getFieldValue(field.key) as string"
                placeholder="图片URL"
                @update:model-value="updateField(field.key, $event)"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.dynamic-entity-form {
  .form-group {
    margin-bottom: 20px;
    .form-group-title {
      font-size: 14px;
      font-weight: 600;
      color: #303133;
      margin-bottom: 12px;
      padding-bottom: 8px;
      border-bottom: 1px solid #ebeef5;
    }
  }
  .readonly-field {
    display: flex;
    padding: 8px 0;
    font-size: 14px;
    .readonly-label {
      color: #909399;
      width: 130px;
      flex-shrink: 0;
      text-align: right;
      padding-right: 12px;
      &::after { content: ':'; }
    }
    .readonly-value { color: #303133; flex: 1; }
  }
}
</style>
