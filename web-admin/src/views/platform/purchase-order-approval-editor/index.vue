<!--
  PurchaseOrderApprovalEditor — Canvas P3 batch 2 (2026-05-22).

  包装 PurchaseOrderApprovalRule entity (per-factory 多条规则, 按 createdAt desc 取最新 enabled).
  典型用例: factory_admin / 财务主管 调整采购单转财务审核的阈值.

  字段:
    - ruleName (规则名称, 唯一)
    - priceVarianceThreshold (价格偏差阈值 % 0-100)
    - amountThreshold (总金额阈值, null = 不参与)
    - enabled (启用/禁用)

  Backend: /api/mobile/{factoryId}/canvas-purchase-order-approval
-->
<template>
  <div class="purchase-order-approval-editor">
    <!-- Header -->
    <el-card shadow="never" class="header-card">
      <div class="header-row">
        <div class="header-left">
          <h3 class="hub-title">采购审批规则</h3>
          <el-tag type="info" size="small">{{ rules.length }} 条规则</el-tag>
          <el-tag type="success" size="small">{{ enabledCount }} 启用</el-tag>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" @click="loadRules">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="onCreateClick">
            新建规则
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Rules table -->
    <el-card shadow="never">
      <el-empty v-if="rules.length === 0 && !loading" description="暂无规则 - 点'新建规则'添加" />
      <el-table v-else :data="rules" border stripe size="small">
        <el-table-column prop="ruleName" label="规则名称" min-width="200" />
        <el-table-column label="价格偏差阈值" width="160">
          <template #default="{ row }">
            {{ row.priceVarianceThreshold }} %
          </template>
        </el-table-column>
        <el-table-column label="总金额阈值" width="180">
          <template #default="{ row }">
            <span v-if="row.amountThreshold !== null && row.amountThreshold !== undefined">
              ¥ {{ formatAmount(row.amountThreshold) }}
            </span>
            <el-text v-else type="info" size="small">未设置</el-text>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.enabled" type="success" size="small">启用</el-tag>
            <el-tag v-else type="info" size="small">禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70">
          <template #default="{ row }">
            <el-text size="small" type="info">v{{ row.version ?? 0 }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            <el-text size="small">{{ formatDate(row.createdAt) }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="onEdit(row)">
              编辑
            </el-button>
            <el-button size="small" type="danger" link @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Create / Edit dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingRule?.id ? `编辑规则 — ${editingRule?.ruleName ?? ''}` : '新建采购审批规则'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        v-if="editingRule"
        ref="formRef"
        :model="editingRule"
        :rules="formRules"
        label-width="140px"
        size="small"
      >
        <el-form-item label="规则名称" prop="ruleName">
          <el-input
            v-model="editingRule.ruleName"
            placeholder="如: 默认审核规则 / 大额单审核"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="价格偏差阈值" prop="priceVarianceThreshold">
          <el-input-number
            v-model="editingRule.priceVarianceThreshold"
            :min="0"
            :max="100"
            :step="0.5"
            :precision="2"
            style="width: 180px"
          />
          <el-text type="info" size="small" style="margin-left: 8px">
            %（当前价 vs BOM/移动均偏差绝对值超过此值 → priceAlert）
          </el-text>
        </el-form-item>
        <el-form-item label="总金额阈值">
          <el-input-number
            v-model="editingRule.amountThreshold"
            :min="0"
            :step="10000"
            :precision="2"
            placeholder="留空表示不参与判断"
            style="width: 220px"
          />
          <el-text type="info" size="small" style="margin-left: 8px">
            元（订单总额超过此值 → 触发财务审核）
          </el-text>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editingRule.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  purchaseOrderApprovalApi,
  type PurchaseOrderApprovalRule,
} from '@/api/canvasPurchaseOrderApproval'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const rules = ref<PurchaseOrderApprovalRule[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingRule = ref<PurchaseOrderApprovalRule | null>(null)
const formRef = ref<FormInstance>()

const enabledCount = computed(() => rules.value.filter((r) => r.enabled).length)

const formRules: FormRules = {
  ruleName: [
    { required: true, message: '请输入规则名称', trigger: 'blur' },
    { max: 100, message: '规则名称最多 100 字符', trigger: 'blur' },
  ],
  priceVarianceThreshold: [
    { required: true, message: '请输入价格偏差阈值', trigger: 'blur' },
  ],
}

async function loadRules() {
  loading.value = true
  try {
    const resp = await purchaseOrderApprovalApi.list(props.factoryId)
    rules.value = (resp.data ?? []) as PurchaseOrderApprovalRule[]
  } catch (err) {
    console.error('loadRules failed', err)
  } finally {
    loading.value = false
  }
}

function onCreateClick() {
  editingRule.value = {
    id: '',
    factoryId: props.factoryId,
    ruleName: '',
    priceVarianceThreshold: 10.0,
    amountThreshold: 100000,
    enabled: true,
  }
  dialogVisible.value = true
}

function onEdit(row: PurchaseOrderApprovalRule) {
  editingRule.value = { ...row }
  dialogVisible.value = true
}

async function onSave() {
  if (!editingRule.value) return
  await formRef.value?.validate().catch((): null => null)
  try {
    if (editingRule.value.id) {
      // Update
      const body: Partial<PurchaseOrderApprovalRule> = {
        ruleName: editingRule.value.ruleName,
        priceVarianceThreshold: editingRule.value.priceVarianceThreshold,
        amountThreshold: editingRule.value.amountThreshold,
        enabled: editingRule.value.enabled,
        version: editingRule.value.version, // AUD-4 P1 optimistic lock
      }
      await purchaseOrderApprovalApi.update(
        props.factoryId,
        editingRule.value.id,
        body,
      )
      ElMessage.success('规则已更新')
    } else {
      // Create
      const body: Partial<PurchaseOrderApprovalRule> = {
        ruleName: editingRule.value.ruleName,
        priceVarianceThreshold: editingRule.value.priceVarianceThreshold,
        amountThreshold: editingRule.value.amountThreshold,
        enabled: editingRule.value.enabled,
      }
      await purchaseOrderApprovalApi.create(props.factoryId, body)
      ElMessage.success('规则已创建')
    }
    dialogVisible.value = false
    editingRule.value = null
    await loadRules()
  } catch (err) {
    console.error('onSave failed', err)
  }
}

async function onDelete(row: PurchaseOrderApprovalRule) {
  try {
    await ElMessageBox.confirm(
      `确定删除规则 "${row.ruleName}"?`,
      '删除采购审批规则',
      {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
      },
    )
    await purchaseOrderApprovalApi.delete(props.factoryId, row.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (err: unknown) {
    if (err !== 'cancel') {
      console.error('onDelete failed', err)
    }
  }
}

function formatAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '-'
  const num = typeof v === 'string' ? parseFloat(v) : v
  if (isNaN(num)) return '-'
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDate(v: string | undefined): string {
  if (!v) return '-'
  try {
    return new Date(v).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return v
  }
}

onMounted(() => {
  loadRules()
})
</script>

<style scoped>
.purchase-order-approval-editor {
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
</style>
