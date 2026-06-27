<!--
  FactorySchedulingEditor — Canvas P3 batch 2 (2026-05-22).

  包装 FactorySchedulingConfig entity (per-factory 唯一). 30+ 字段分 8 组折叠面板:
    1. 基础配置
    2. 调度权重 (LinUCB / 公平性 / 技能维护 / 重复)
    3. 时间参数
    4. 临时工策略
    5. SKU 复杂度
    6. 自适应学习
    7. 异常检测
    8. APS 排产策略权重

  Backend: /api/mobile/{factoryId}/canvas-factory-scheduling
-->
<template>
  <div class="factory-scheduling-editor">
    <!-- Header -->
    <el-card shadow="never" class="header-card">
      <div class="header-row">
        <div class="header-left">
          <h3 class="hub-title">排班配置</h3>
          <el-tag v-if="currentConfig" type="success" size="small">已配置</el-tag>
          <el-tag v-else type="info" size="small">未配置</el-tag>
          <el-tag v-if="currentConfig?.version !== undefined" type="info" size="small">
            v{{ currentConfig.version }}
          </el-tag>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" @click="loadConfig">刷新</el-button>
          <el-button
            v-if="!currentConfig"
            type="primary"
            :icon="Plus"
            @click="createDefault"
          >
            创建默认配置
          </el-button>
          <el-button
            v-else
            type="primary"
            :icon="Check"
            :disabled="!dirty"
            @click="saveChanges"
          >
            保存修改
          </el-button>
          <el-button
            v-if="currentConfig"
            type="danger"
            :icon="Delete"
            @click="deleteConfig"
          >
            删除
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Empty state -->
    <el-empty
      v-if="!currentConfig && !loading"
      description="此工厂尚未配置排班参数 — 点'创建默认配置'使用系统默认值"
    >
      <el-button type="primary" :icon="Plus" @click="createDefault">
        创建默认配置
      </el-button>
    </el-empty>

    <!-- Editor -->
    <el-scrollbar v-if="currentConfig" class="editor-scrollbar">
      <el-collapse v-model="activeGroups">
        <el-collapse-item
          v-for="(group, groupKey) in FIELD_GROUPS"
          :key="groupKey"
          :name="groupKey"
        >
          <template #title>
            <span class="group-title">{{ group.title }}</span>
          </template>
          <el-form label-width="200px" label-position="right" size="small">
            <el-form-item
              v-for="field in group.fields"
              :key="field"
              :label="FIELD_LABELS[field as keyof typeof FIELD_LABELS] || field"
            >
              <!-- Boolean fields → switch -->
              <el-switch
                v-if="isBooleanField(field)"
                :model-value="getValue(field) as boolean"
                @update:model-value="(v: boolean) => setValue(field, v)"
              />
              <!-- Weight fields → slider 0-1 -->
              <div v-else-if="isWeightField(field as keyof FactorySchedulingConfig)" class="weight-input">
                <el-slider
                  :model-value="getValue(field) as number"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :show-tooltip="true"
                  @update:model-value="(v: number | number[]) => setValue(field, v as number)"
                />
                <el-input-number
                  :model-value="getValue(field) as number"
                  :min="0"
                  :max="1"
                  :step="0.01"
                  :precision="2"
                  style="width: 130px"
                  @update:model-value="(v: number | undefined) => setValue(field, v)"
                />
              </div>
              <!-- Number fields -->
              <el-input-number
                v-else
                :model-value="getValue(field) as number"
                :min="0"
                :step="1"
                style="width: 180px"
                @update:model-value="(v: number | undefined) => setValue(field, v)"
              />
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Delete, Plus, Refresh } from '@element-plus/icons-vue'
import {
  factorySchedulingApi,
  FIELD_GROUPS,
  FIELD_LABELS,
  isWeightField,
  type FactorySchedulingConfig,
} from '@/api/canvasFactoryScheduling'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const currentConfig = ref<FactorySchedulingConfig | null>(null)
const editedFields = reactive<Partial<FactorySchedulingConfig>>({})
const loading = ref(false)
const activeGroups = ref<string[]>(['basic', 'weights'])

const dirty = computed(() => Object.keys(editedFields).length > 0)

const BOOLEAN_FIELDS = new Set<string>([
  'enabled',
  'diversityEnabled',
  'lowComplexityForTraining',
  'adaptiveLearningEnabled',
  'anomalyDetectionEnabled',
])

function isBooleanField(field: string): boolean {
  return BOOLEAN_FIELDS.has(field)
}

function getValue(field: string): unknown {
  if (field in editedFields) {
    return (editedFields as Record<string, unknown>)[field]
  }
  return currentConfig.value
    ? (currentConfig.value as Record<string, unknown>)[field]
    : null
}

function setValue(field: string, value: unknown): void {
  ;(editedFields as Record<string, unknown>)[field] = value
}

async function loadConfig() {
  loading.value = true
  try {
    const resp = await factorySchedulingApi.list(props.factoryId)
    const list = (resp.data ?? []) as FactorySchedulingConfig[]
    currentConfig.value = list.length > 0 ? list[0] : null
    Object.keys(editedFields).forEach((k) => {
      delete (editedFields as Record<string, unknown>)[k]
    })
  } catch (err) {
    console.error('loadConfig failed', err)
  } finally {
    loading.value = false
  }
}

async function createDefault() {
  try {
    const resp = await factorySchedulingApi.create(props.factoryId, {})
    currentConfig.value = resp.data as FactorySchedulingConfig
    Object.keys(editedFields).forEach((k) => {
      delete (editedFields as Record<string, unknown>)[k]
    })
    ElMessage.success('默认排班配置已创建')
  } catch (err) {
    console.error('createDefault failed', err)
  }
}

async function saveChanges() {
  if (!currentConfig.value) return
  try {
    const body: Partial<FactorySchedulingConfig> = {
      ...editedFields,
      version: currentConfig.value.version, // AUD-4 P1 optimistic lock
    }
    const resp = await factorySchedulingApi.update(
      props.factoryId,
      currentConfig.value.id,
      body,
    )
    currentConfig.value = resp.data as FactorySchedulingConfig
    Object.keys(editedFields).forEach((k) => {
      delete (editedFields as Record<string, unknown>)[k]
    })
    ElMessage.success('排班配置已保存')
  } catch (err) {
    console.error('saveChanges failed', err)
  }
}

async function deleteConfig() {
  if (!currentConfig.value) return
  try {
    await ElMessageBox.confirm(
      '确定删除此工厂的排班配置? 删除后系统将使用全局默认参数.',
      '删除排班配置',
      {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
      },
    )
    await factorySchedulingApi.delete(props.factoryId, currentConfig.value.id)
    currentConfig.value = null
    Object.keys(editedFields).forEach((k) => {
      delete (editedFields as Record<string, unknown>)[k]
    })
    ElMessage.success('排班配置已删除')
  } catch (err: unknown) {
    if (err !== 'cancel') {
      console.error('deleteConfig failed', err)
    }
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.factory-scheduling-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  height: 100%;
}

.header-card {
  flex: 0 0 auto;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hub-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.editor-scrollbar {
  flex: 1 1 auto;
  min-height: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
}

.group-title {
  font-weight: 500;
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.weight-input {
  display: flex;
  gap: 16px;
  align-items: center;
  width: 100%;
  max-width: 480px;
}

.weight-input :deep(.el-slider) {
  flex: 1 1 auto;
}
</style>
