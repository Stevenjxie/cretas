<template>
  <el-dialog
    :model-value="modelValue"
    title="供应商进货录入"
    width="560px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <!-- Rule 1: 本月录入限制信息 header -->
    <el-alert
      v-if="limits"
      type="info"
      :closable="false"
      style="margin-bottom: 16px"
      :title="`本月已录 ${limits.totalCount} 张 (确认 ${limits.confirmedCount} / 草稿 ${limits.draftCount})`"
    />

    <!-- Rule 2: 上下文 — 供应商 + 日期 必填 -->
    <el-form label-width="90px">
      <el-form-item label="供应商" required>
        <el-select
          v-model="supplierId"
          filterable
          clearable
          placeholder="选择供应商"
          style="width: 100%"
        >
          <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <!-- Rule 5: 供应商列表为空 → 跳供应商管理 -->
        <div v-if="suppliers.length === 0" class="empty-hint">
          还未添加供应商，
          <el-link type="primary" underline="hover" @click="goSupplierMgmt">前往供应商管理</el-link>
        </div>
      </el-form-item>
      <el-form-item label="送货日期" required>
        <el-date-picker
          v-model="deliveryDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>

    <!-- 上传区 -->
    <el-upload
      ref="uploadRef"
      drag
      action=""
      :auto-upload="false"
      :limit="1"
      accept="image/jpeg,image/png,image/webp"
      :on-change="onFileChange"
      :on-exceed="onExceed"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">拖拽送货单照片到此处，或<em>点击上传</em></div>
      <template #tip>
        <div class="el-upload__tip">支持 JPG / PNG，建议正对单据、光线充足、文字清晰</div>
      </template>
    </el-upload>

    <!-- Rule 5: 低置信提示 (上次解析后) -->
    <el-alert
      v-if="lastLowConfidence"
      type="warning"
      :closable="true"
      show-icon
      style="margin-top: 12px"
      title="上次识别置信度较低，建议重拍：确保单据平整、光线充足、文字清晰"
    />

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="info" plain :disabled="!canSubmit" @click="goManual">
        改为手动录入
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submit"
      >
        识别并录入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type UploadFile, type UploadInstance } from 'element-plus';
import { get } from '@/api/request';
import {
  ocrParseNote,
  getNoteLimits,
  type SupplierDeliveryNoteDto,
  type NoteLimits,
} from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';
import { isAxiosError } from 'axios';

const props = defineProps<{ modelValue: boolean; factoryId: string }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'parsed', note: SupplierDeliveryNoteDto): void;
}>();

const router = useRouter();
const uploadRef = ref<UploadInstance>();
const supplierId = ref<string>('');
const deliveryDate = ref<string>(new Date().toISOString().slice(0, 10));
const selectedFile = ref<File | null>(null);
const submitting = ref(false);
const limits = ref<NoteLimits | null>(null);
const suppliers = ref<Array<{ id: string; name: string }>>([]);
const lastLowConfidence = ref(false);

const canSubmit = computed(() => !!selectedFile.value && !!deliveryDate.value);

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      selectedFile.value = null;
      lastLowConfidence.value = false;
      uploadRef.value?.clearFiles();
      loadLimits();
      loadSuppliers();
    }
  },
);

async function loadLimits() {
  try {
    const month = deliveryDate.value.slice(0, 7) || new Date().toISOString().slice(0, 7);
    const resp = await getNoteLimits(props.factoryId, month);
    if (resp.success) limits.value = resp.data;
  } catch {
    // 限制信息加载失败不阻断上传
    limits.value = null;
  }
}

async function loadSuppliers() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(
      `/${props.factoryId}/suppliers/active`,
    );
    if (resp.success && Array.isArray(resp.data)) suppliers.value = resp.data;
  } catch {
    suppliers.value = [];
  }
}

function onFileChange(file: UploadFile) {
  selectedFile.value = (file.raw as File) || null;
}

function onExceed(files: File[]) {
  uploadRef.value?.clearFiles();
  selectedFile.value = files[0] || null;
  uploadRef.value?.handleStart(files[0] as any);
}

async function submit() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择照片');
    return;
  }
  submitting.value = true;
  try {
    const fd = new FormData();
    fd.append('photo', selectedFile.value);
    fd.append('deliveryDate', deliveryDate.value);
    if (supplierId.value) fd.append('supplierId', supplierId.value);
    const resp = await ocrParseNote(props.factoryId, fd);
    if (resp.success && resp.data) {
      // Rule 5: 置信度低时记录, 进详情后仍提示
      lastLowConfidence.value = !!resp.data.lowConfidenceWarning;
      emit('parsed', resp.data);
    }
  } catch (e) {
    // Rule 4: 409 重复照片 → 跳已有草稿
    if (isAxiosError(e) && e.response?.status === 409) {
      const data = e.response.data as { hintTarget?: string; message?: string };
      const existingId = data?.hintTarget;
      try {
        await ElMessageBox.confirm(
          data?.message || '该照片已上传过，是否前往查看已有草稿？',
          '重复照片',
          { confirmButtonText: '前往查看', cancelButtonText: '取消', type: 'warning' },
        );
        if (existingId) {
          emit('update:modelValue', false);
          router.push({ name: 'SupplierDeliveryNoteDetail', params: { id: existingId } });
        }
      } catch {
        // 用户取消
      }
    } else {
      handleCatchError(e, 'OCR 识别失败');
    }
  } finally {
    submitting.value = false;
  }
}

function goManual() {
  emit('update:modelValue', false);
  router.push({
    name: 'SupplierDeliveryNoteDetail',
    params: { id: 'new' },
    query: {
      mode: 'manual',
      supplierId: supplierId.value || '',
      deliveryDate: deliveryDate.value,
    },
  });
}

function goSupplierMgmt() {
  emit('update:modelValue', false);
  router.push('/restaurant/suppliers');
}
</script>

<style scoped lang="scss">
.empty-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
