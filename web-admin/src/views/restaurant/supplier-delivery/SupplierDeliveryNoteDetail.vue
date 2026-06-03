<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never" v-loading="loading">
      <template #header>
        <div class="card-header">
          <!-- Rule 2: context header — 供应商 / 单号 / 金额 -->
          <div class="header-left">
            <el-button :icon="ArrowLeft" link @click="goBack">返回</el-button>
            <span class="page-title">
              {{ isManual ? '手动录入送货单' : '送货单详情' }}
              <template v-if="note">— {{ note.supplierName || '未指定供应商' }}</template>
            </span>
            <el-tag v-if="note" size="small" :type="statusTagType(note.status)">
              {{ statusText(note.status) }}
            </el-tag>
          </div>
        </div>
      </template>

      <!-- Rule 5: 低置信橙色提示 + 重拍 -->
      <el-alert
        v-if="note && note.lowConfidenceWarning"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
      >
        <template #title>
          识别置信度较低 ({{ Math.round((note.ocrConfidence || 0) * 100) }}%)，建议核对行项或重拍：确保单据平整、光线充足、文字清晰
        </template>
        <el-button size="small" type="warning" plain style="margin-top: 8px" @click="goBack">
          返回重新上传
        </el-button>
      </el-alert>

      <!-- OCR 错误提示 -->
      <el-alert
        v-if="note && note.ocrErrorMessage"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        :title="note.ocrErrorMessage"
      />

      <!-- 头部信息 (Rule 2 context) -->
      <el-form :inline="false" label-width="90px" class="head-form">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="供应商">
              <el-select v-model="form.supplierId" filterable clearable :disabled="!editable"
                placeholder="选择供应商" style="width: 100%" @change="onSupplierChange">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="送货日期">
              <el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD"
                :disabled="!editable" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="送货单号">
              <el-input v-model="form.noteNumber" :disabled="!editable" placeholder="送货单号" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 行项表 (Rule 3: qty×price 联动) -->
      <el-table :data="form.lines" border style="width: 100%">
        <el-table-column label="食材名称" min-width="160">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.ingredientName" placeholder="食材名称" />
            <span v-else>{{ row.ingredientName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对应原料" min-width="160">
          <template #default="{ row }">
            <el-select v-if="editable" v-model="row.rawMaterialTypeId" filterable clearable
              placeholder="(自动匹配)" style="width: 100%">
              <el-option v-for="m in materialTypes" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <span v-else>{{ materialNameMap[row.rawMaterialTypeId] || row.rawMaterialTypeId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.quantity" :precision="4" :step="0.5" :min="0"
              size="small" controls-position="right" style="width: 100px" @change="recalcLine(row)" />
            <span v-else>{{ row.quantity ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.unit" size="small" style="width: 60px" />
            <span v-else>{{ row.unit ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.unitPrice" :precision="4" :step="0.5" :min="0"
              size="small" controls-position="right" style="width: 100px" @change="recalcLine(row)" />
            <span v-else>{{ row.unitPrice ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span :class="{ 'auto-calc': editable && row.quantity != null && row.unitPrice != null }">
              {{ row.lineAmount != null ? '¥' + row.lineAmount : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="editable" label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeLine($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-button v-if="editable" link type="primary" :icon="Plus" style="margin-top: 8px" @click="addLine">
        添加行项
      </el-button>

      <div class="total-row">
        合计金额：<strong>¥{{ totalAmount }}</strong>
      </div>

      <!-- 操作按钮 -->
      <div class="action-bar" v-if="editable">
        <el-button @click="goBack">取消</el-button>
        <el-button v-if="!isManual" :loading="saving" @click="saveLines">保存行项</el-button>
        <el-button v-if="isManual" type="primary" :loading="saving" @click="saveManual">保存录入</el-button>
        <el-button v-if="!isManual" type="danger" plain @click="openReject">拒绝</el-button>
        <el-button v-if="!isManual" type="primary" :loading="confirming" @click="confirm">
          确认并写入进价
        </el-button>
      </div>
    </el-card>

    <!-- Rule 3: 拒绝原因 dropdown + OTHER 才显 textarea -->
    <el-dialog v-model="rejectVisible" title="拒绝送货单" width="440px">
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-select v-model="rejectForm.code" placeholder="选择原因" style="width: 100%">
            <el-option label="图片模糊" value="IMAGE_BLUR" />
            <el-option label="光线不足" value="LOW_LIGHT" />
            <el-option label="单据不对" value="WRONG_DOCUMENT" />
            <el-option label="供应商不存在" value="SUPPLIER_NOT_FOUND" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectForm.code === 'OTHER'" label="补充说明" required>
          <el-input v-model="rejectForm.note" type="textarea" :rows="2" placeholder="请说明具体原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" :disabled="!canReject" @click="doReject">
          确认拒绝
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useFactoryId } from '@/composables/useFactoryId';
import { get } from '@/api/request';
import {
  getNoteDetail, confirmNote, rejectNote, updateNoteLines, createManualNote,
  type SupplierDeliveryNoteDto, type SupplierDeliveryNoteLineDto,
} from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';

const route = useRoute();
const router = useRouter();
const factoryId = useFactoryId();

const noteId = computed(() => route.params.id as string);
const isManual = computed(() => noteId.value === 'new' || route.query.mode === 'manual');

const loading = ref(false);
const saving = ref(false);
const confirming = ref(false);
const rejecting = ref(false);
const note = ref<SupplierDeliveryNoteDto | null>(null);
const suppliers = ref<Array<{ id: string; name: string }>>([]);
const materialTypes = ref<Array<{ id: string; name: string }>>([]);

const form = reactive<{
  supplierId: string;
  deliveryDate: string;
  noteNumber: string;
  lines: SupplierDeliveryNoteLineDto[];
}>({ supplierId: '', deliveryDate: new Date().toISOString().slice(0, 10), noteNumber: '', lines: [] });

const rejectVisible = ref(false);
const rejectForm = reactive({ code: '', note: '' });

const editable = computed(() => isManual.value || note.value?.status === 'DRAFT');
const canReject = computed(() => !!rejectForm.code && (rejectForm.code !== 'OTHER' || !!rejectForm.note));

const materialNameMap = computed<Record<string, string>>(() => {
  const m: Record<string, string> = {};
  materialTypes.value.forEach((x) => (m[x.id] = x.name));
  return m;
});

const totalAmount = computed(() =>
  form.lines.reduce((sum, l) => sum + (Number(l.lineAmount) || 0), 0).toFixed(2),
);

function statusText(s?: string): string {
  return { DRAFT: '草稿', CONFIRMED: '已确认', REJECTED: '已拒绝' }[s || ''] || s || '';
}
function statusTagType(s?: string): string {
  return { DRAFT: 'info', CONFIRMED: 'success', REJECTED: 'danger' }[s || ''] || 'info';
}

/** Rule 3 数字联动: 数量 × 单价 → 金额自动计算。 */
function recalcLine(row: SupplierDeliveryNoteLineDto) {
  if (row.quantity != null && row.unitPrice != null) {
    row.lineAmount = Number((Number(row.quantity) * Number(row.unitPrice)).toFixed(2));
  }
}

function addLine() {
  form.lines.push({ ingredientName: '', quantity: null, unit: '', unitPrice: null, lineAmount: null });
}
function removeLine(idx: number) {
  form.lines.splice(idx, 1);
}

function onSupplierChange(id: string) {
  const s = suppliers.value.find((x) => x.id === id);
  if (s) note.value && (note.value.supplierName = s.name);
}

async function loadSuppliers() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(`/${factoryId.value}/suppliers/active`);
    if (resp.success && Array.isArray(resp.data)) suppliers.value = resp.data;
  } catch { suppliers.value = []; }
}

async function loadMaterials() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(`/${factoryId.value}/raw-material-types/active`);
    if (resp.success && Array.isArray(resp.data)) materialTypes.value = resp.data;
  } catch { materialTypes.value = []; }
}

async function loadDetail() {
  if (isManual.value) {
    // 手动录入预填 query
    form.supplierId = (route.query.supplierId as string) || '';
    form.deliveryDate = (route.query.deliveryDate as string) || new Date().toISOString().slice(0, 10);
    if (form.lines.length === 0) addLine();
    return;
  }
  loading.value = true;
  try {
    const resp = await getNoteDetail(factoryId.value, noteId.value);
    if (resp.success && resp.data) {
      note.value = resp.data;
      form.supplierId = resp.data.supplierId || '';
      form.deliveryDate = resp.data.deliveryDate;
      form.noteNumber = resp.data.noteNumber || '';
      form.lines = (resp.data.lines || []).map((l) => ({ ...l }));
    }
  } catch (e) {
    handleCatchError(e, '加载送货单详情失败');
  } finally {
    loading.value = false;
  }
}

async function saveLines() {
  saving.value = true;
  try {
    const resp = await updateNoteLines(factoryId.value, noteId.value, form.lines);
    if (resp.success) {
      ElMessage.success('行项已保存');
      note.value = resp.data;
    }
  } catch (e) {
    handleCatchError(e, '保存行项失败');
  } finally {
    saving.value = false;
  }
}

async function saveManual() {
  if (form.lines.length === 0 || form.lines.every((l) => !l.ingredientName)) {
    ElMessage.warning('请至少填写一行食材');
    return;
  }
  saving.value = true;
  try {
    const s = suppliers.value.find((x) => x.id === form.supplierId);
    const resp = await createManualNote(factoryId.value, {
      supplierId: form.supplierId || undefined,
      supplierName: s?.name,
      deliveryDate: form.deliveryDate,
      noteNumber: form.noteNumber || undefined,
      lines: form.lines,
    });
    if (resp.success && resp.data) {
      ElMessage.success('录入成功');
      router.replace({ name: 'SupplierDeliveryNoteDetail', params: { id: resp.data.id } });
      note.value = resp.data;
    }
  } catch (e) {
    handleCatchError(e, '录入失败');
  } finally {
    saving.value = false;
  }
}

async function confirm() {
  confirming.value = true;
  try {
    const resp = await confirmNote(factoryId.value, noteId.value);
    if (resp.success) {
      ElMessage.success('已确认，进价已写入');
      note.value = resp.data;
    }
  } catch (e) {
    handleCatchError(e, '确认失败');
  } finally {
    confirming.value = false;
  }
}

function openReject() {
  rejectForm.code = '';
  rejectForm.note = '';
  rejectVisible.value = true;
}

async function doReject() {
  rejecting.value = true;
  try {
    const resp = await rejectNote(factoryId.value, noteId.value, {
      rejectReasonCode: rejectForm.code,
      rejectReasonNote: rejectForm.note || undefined,
    });
    if (resp.success) {
      ElMessage.success('已拒绝');
      rejectVisible.value = false;
      note.value = resp.data;
    }
  } catch (e) {
    handleCatchError(e, '拒绝失败');
  } finally {
    rejecting.value = false;
  }
}

function goBack() {
  router.push({ name: 'SupplierDeliveryNoteList' });
}

onMounted(async () => {
  await Promise.all([loadSuppliers(), loadMaterials()]);
  await loadDetail();
});
</script>

<style scoped lang="scss">
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.head-form {
  margin-bottom: 8px;
}
.total-row {
  text-align: right;
  margin-top: 12px;
  font-size: 15px;
}
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
.auto-calc {
  color: var(--el-color-primary);
}
</style>
