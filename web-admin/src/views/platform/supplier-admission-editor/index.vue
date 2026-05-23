<!--
  SupplierAdmissionEditor — Canvas Phase BCP3 (2026-05-22).

  P3 半-Canvas-ed: 包装 Supplier entity 的准入字段
  (admission_status / admission_reviewed_at / rating / rating_notes).

  Backend: /api/mobile/{factoryId}/canvas-supplier-admission
  防呆 (Rule 2+3):
    - Status 用 dropdown, 不允许自由文本
    - REJECTED / SUSPENDED 强制填理由 (notes 必填)
-->
<template>
  <div class="supplier-admission-editor">
    <div class="panel-header">
      <h3>供应商准入</h3>
      <div class="panel-actions">
        <el-tag size="small" type="info">factoryId: {{ factoryId }}</el-tag>
        <el-select v-model="filterStatus" placeholder="全部状态" clearable
                   style="width: 140px" @change="loadList">
          <el-option v-for="s in ADMISSION_STATUS_LIST" :key="s"
                     :label="ADMISSION_STATUS_LABELS[s]" :value="s" />
        </el-select>
        <el-button size="small" @click="loadList" :disabled="loading">刷新</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="suppliers" stripe style="width: 100%"
              empty-text="暂无供应商">
      <el-table-column prop="supplierCode" label="供应商编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="contactPerson" label="联系人" width="120" />
      <el-table-column prop="contactPhone" label="电话" width="140" />
      <el-table-column label="准入状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.admissionStatus)" size="small">
            {{ ADMISSION_STATUS_LABELS[row.admissionStatus as AdmissionStatus]
                || row.admissionStatus || '-' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
            {{ row.isActive ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="评级" width="80">
        <template #default="{ row }">
          <el-rate v-if="row.rating" :model-value="6 - (row.rating || 5)" disabled
                   :max="5" size="small" />
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="creditLevel" label="信用等级" width="100" />
      <el-table-column label="最近审核" width="160">
        <template #default="{ row }">
          <span v-if="row.admissionReviewedAt" class="muted-text">
            {{ row.admissionReviewedAt }}
          </span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="onReview(row)">审核准入</el-button>
          <el-button size="small" type="warning" link
                     :disabled="row.admissionStatus === 'SUSPENDED'"
                     @click="onSuspend(row)">暂停</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Review Dialog -->
    <el-dialog v-model="reviewDialogVisible" :title="`准入审核 — ${currentSupplier?.name}`"
               width="600px" :close-on-click-modal="false">
      <div v-if="currentSupplier" class="review-context">
        <p><strong>供应商编码:</strong> {{ currentSupplier.supplierCode }}</p>
        <p><strong>当前状态:</strong>
          <el-tag :type="statusTagType(currentSupplier.admissionStatus)" size="small">
            {{ ADMISSION_STATUS_LABELS[currentSupplier.admissionStatus as AdmissionStatus]
                || currentSupplier.admissionStatus || '-' }}
          </el-tag>
        </p>
        <p><strong>营业执照:</strong> {{ currentSupplier.businessLicense || '-' }}</p>
        <p><strong>资质证书:</strong> {{ currentSupplier.qualityCertificates || '-' }}</p>
      </div>
      <el-form :model="reviewForm" :rules="reviewRules" ref="reviewFormRef" label-width="120px">
        <el-form-item label="审核结果" prop="admissionStatus">
          <el-select v-model="reviewForm.admissionStatus" placeholder="选择审核结果"
                     style="width: 100%">
            <el-option v-for="s in ADMISSION_STATUS_LIST" :key="s"
                       :label="ADMISSION_STATUS_LABELS[s]" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核理由"
                      :prop="reviewForm.admissionStatus === 'REJECTED'
                          || reviewForm.admissionStatus === 'SUSPENDED'
                          ? 'notes' : ''"
                      :rules="reviewForm.admissionStatus === 'REJECTED'
                          || reviewForm.admissionStatus === 'SUSPENDED'
                          ? [{ required: true, message: '拒绝/暂停必须填写理由', trigger: 'blur' }] : []">
          <el-input v-model="reviewForm.notes" type="textarea" :rows="4"
                    maxlength="2000" show-word-limit
                    :placeholder="reviewForm.admissionStatus === 'REJECTED'
                        || reviewForm.admissionStatus === 'SUSPENDED'
                        ? '必填: 请说明拒绝/暂停理由' : '选填'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReview">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  canvasSupplierAdmissionApi,
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_LIST,
  type AdmissionStatus,
  type SupplierAdmissionView,
} from '@/api/canvasSupplierAdmission'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const suppliers = ref<SupplierAdmissionView[]>([])
const filterStatus = ref<AdmissionStatus | ''>('')

const reviewDialogVisible = ref(false)
const reviewFormRef = ref<FormInstance>()
const currentSupplier = ref<SupplierAdmissionView | null>(null)
const reviewForm = ref<{ admissionStatus: AdmissionStatus | ''; notes: string }>({
  admissionStatus: '',
  notes: '',
})

const reviewRules: FormRules = {
  admissionStatus: [{ required: true, message: '请选择审核结果', trigger: 'change' }],
}

function statusTagType(s?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (s) {
    case 'APPROVED': return 'success'
    case 'PENDING': return 'warning'
    case 'REJECTED': return 'danger'
    case 'SUSPENDED': return 'info'
    default: return 'info'
  }
}

async function loadList() {
  loading.value = true
  try {
    const resp = await canvasSupplierAdmissionApi.list(
      props.factoryId,
      filterStatus.value || undefined,
    )
    suppliers.value = resp.data || []
  } catch (e: any) {
    ElMessage({
      message: e?.response?.data?.message || '加载失败',
      type: 'error', duration: 0, showClose: true,
    })
  } finally {
    loading.value = false
  }
}

function onReview(row: SupplierAdmissionView) {
  currentSupplier.value = row
  reviewForm.value = {
    admissionStatus: (row.admissionStatus as AdmissionStatus) || 'PENDING',
    notes: '',
  }
  reviewDialogVisible.value = true
}

async function submitReview() {
  if (!reviewFormRef.value || !currentSupplier.value) return
  await reviewFormRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      await canvasSupplierAdmissionApi.review(
        props.factoryId,
        currentSupplier.value!.id,
        {
          admissionStatus: reviewForm.value.admissionStatus as AdmissionStatus,
          notes: reviewForm.value.notes || undefined,
        },
      )
      ElMessage.success('准入审核已提交')
      reviewDialogVisible.value = false
      await loadList()
    } catch (e: any) {
      ElMessage({
        message: e?.response?.data?.message || '审核失败',
        type: 'error', duration: 0, showClose: true,
      })
    } finally {
      saving.value = false
    }
  })
}

async function onSuspend(row: SupplierAdmissionView) {
  try {
    await ElMessageBox.confirm(
      `确认暂停供应商 "${row.name}" 的准入资格 ?\n供应商将被设为 isActive=false, 不能再供货.`,
      '暂停准入',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await canvasSupplierAdmissionApi.suspend(props.factoryId, row.id)
    ElMessage.success('已暂停准入')
    await loadList()
  } catch (e: any) {
    ElMessage({
      message: e?.response?.data?.message || '暂停失败',
      type: 'error', duration: 0, showClose: true,
    })
  }
}

onMounted(loadList)
</script>

<style scoped>
.supplier-admission-editor { padding: 16px; }
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.panel-header h3 { margin: 0; }
.panel-actions { display: flex; gap: 8px; align-items: center; }
.review-context {
  background: #f8f9fa; padding: 12px; border-radius: 4px;
  margin-bottom: 16px; font-size: 13px;
}
.review-context p { margin: 4px 0; }
.muted { color: #c0c4cc; }
.muted-text { color: #909399; font-size: 12px; }
</style>
