<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { usePermissionStore } from '@/store/modules/permission'
import { get } from '@/api/request'
import { ElMessage } from 'element-plus'

const permissionStore = usePermissionStore()
const canViewPrice = computed(() => permissionStore.canViewPrice)

const PRICE_FIELDS = ['unitPrice', 'taxRate', 'priceUnit']
function visibleEntries(obj: Record<string, unknown> | null): [string, unknown][] {
  if (!obj) return []
  const skipKeys = ['id', 'factoryId', 'recipeId', 'createdAt', 'updatedAt', 'deletedAt']
  return Object.entries(obj).filter(([k]) => {
    if (skipKeys.includes(k)) return false
    if (!canViewPrice.value && PRICE_FIELDS.includes(k)) return false
    return true
  })
}
function visibleDiffKeys(oldVal: Record<string, unknown> | null, newVal: Record<string, unknown> | null): string[] {
  return diffKeys(oldVal, newVal).filter(k => canViewPrice.value || !PRICE_FIELDS.includes(k))
}

interface ChangeLogEntry {
  id: string
  bomRecipeId: string
  bomRecipeItemId: number | null
  changeType: 'CREATE' | 'UPDATE' | 'DELETE'
  oldValue: Record<string, unknown> | null
  newValue: Record<string, unknown> | null
  changedBy: number
  changedByName: string
  changeReason: string | null
  createdAt: string
}

const props = defineProps<{
  factoryId: string
  recipeId: string
}>()

const visible = defineModel<boolean>('visible', { default: false })
const loading = ref(false)
const logs = ref<ChangeLogEntry[]>([])

async function fetchLogs() {
  if (!props.factoryId || !props.recipeId) return
  loading.value = true
  try {
    const res = await get(`/${props.factoryId}/bom/change-logs/${props.recipeId}`)
    if (res.success) {
      logs.value = res.data || []
    } else {
      ElMessage.error(res.message || '获取变更记录失败')
    }
  } catch (e: any) {
    if (!e?.actionHint) ElMessage.error('获取变更记录失败')
  } finally {
    loading.value = false
  }
}

watch(visible, (val) => {
  if (val) fetchLogs()
})

// Compute diff keys between old and new
function diffKeys(oldVal: Record<string, unknown> | null, newVal: Record<string, unknown> | null): string[] {
  if (!oldVal || !newVal) return []
  const allKeys = new Set([...Object.keys(oldVal), ...Object.keys(newVal)])
  return [...allKeys].filter(k => JSON.stringify(oldVal[k]) !== JSON.stringify(newVal[k]))
}

function formatValue(val: unknown): string {
  if (val === null || val === undefined) return '-'
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

function formatTime(ts: string): string {
  if (!ts) return '-'
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// Human-readable field name mapping
const fieldLabels: Record<string, string> = {
  materialName: '物料名称',
  standardQuantity: '标准用量',
  yieldRate: '出品率',
  unit: '单位',
  unitPrice: '单价',
  taxRate: '税率',
  sortOrder: '排序',
  notes: '备注',
  processName: '工序名称',
  processCategory: '工序类别',
  priceUnit: '计价单位',
  name: '名称',
  category: '类别',
  allocationRate: '分摊比例'
}

function fieldLabel(key: string): string {
  return fieldLabels[key] || key
}
</script>

<template>
  <el-drawer v-model="visible" title="BOM 变更记录" size="680px" :destroy-on-close="false">
    <div v-loading="loading">
      <el-empty v-if="!loading && logs.length === 0" description="暂无变更记录" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="log in logs"
          :key="log.id"
          :timestamp="formatTime(log.createdAt)"
          placement="top"
          :type="log.changeType === 'CREATE' ? 'success' : log.changeType === 'DELETE' ? 'danger' : 'warning'"
        >
          <el-card shadow="never" class="change-card">
            <div class="change-header">
              <el-tag
                :type="log.changeType === 'CREATE' ? 'success' : log.changeType === 'DELETE' ? 'danger' : 'warning'"
                size="small"
                disable-transitions
              >
                {{ log.changeType === 'CREATE' ? '新增' : log.changeType === 'DELETE' ? '删除' : '修改' }}
              </el-tag>
              <span class="change-user">{{ log.changedByName || '系统' }}</span>
              <span v-if="log.bomRecipeItemId" class="change-scope">物料项 #{{ log.bomRecipeItemId }}</span>
              <span v-else class="change-scope">BOM 级别</span>
            </div>
            <div v-if="log.changeReason" class="change-reason">
              原因: {{ log.changeReason }}
            </div>

            <!-- CREATE: show new values -->
            <div v-if="log.changeType === 'CREATE' && log.newValue" class="change-detail">
              <el-descriptions :column="2" size="small" border>
                <el-descriptions-item
                  v-for="[k, v] in visibleEntries(log.newValue)"
                  :key="k"
                  :label="fieldLabel(k)"
                >
                  {{ formatValue(v) }}
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <!-- DELETE: show old values -->
            <div v-if="log.changeType === 'DELETE' && log.oldValue" class="change-detail">
              <el-descriptions :column="2" size="small" border>
                <el-descriptions-item
                  v-for="[k, v] in visibleEntries(log.oldValue)"
                  :key="k"
                  :label="fieldLabel(k)"
                >
                  <span style="text-decoration: line-through; color: #f56c6c;">{{ formatValue(v) }}</span>
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <!-- UPDATE: show diff -->
            <div v-if="log.changeType === 'UPDATE' && log.oldValue && log.newValue" class="change-detail">
              <el-table :data="visibleDiffKeys(log.oldValue, log.newValue).map(k => ({ field: k, old: log.oldValue![k], new: log.newValue![k] }))" size="small" border stripe>
                <el-table-column prop="field" label="字段" width="120">
                  <template #default="{ row }">{{ fieldLabel(row.field) }}</template>
                </el-table-column>
                <el-table-column label="原值" min-width="120">
                  <template #default="{ row }">
                    <span style="color: #f56c6c;">{{ formatValue(row.old) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="新值" min-width="120">
                  <template #default="{ row }">
                    <span style="color: #67c23a;">{{ formatValue(row.new) }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<style scoped>
.change-card {
  margin-bottom: 0;
}
.change-card :deep(.el-card__body) {
  padding: 12px;
}
.change-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.change-user {
  font-size: 13px;
  color: #606266;
}
.change-scope {
  font-size: 12px;
  color: #909399;
}
.change-reason {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  font-style: italic;
}
.change-detail {
  margin-top: 8px;
}
</style>
