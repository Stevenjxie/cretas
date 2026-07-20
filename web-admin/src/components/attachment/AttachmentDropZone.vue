<template>
  <div class="attachment-drop-zone-wrap">
    <div
      class="attachment-drop-zone"
      :class="{ 'is-dragging': dragging, 'is-disabled': disabled }"
      role="button"
      tabindex="0"
      :aria-disabled="disabled"
      aria-label="添加附件，可点击选择或拖拽多个文件"
      @click="openPicker"
      @keydown.enter.prevent="openPicker"
      @keydown.space.prevent="openPicker"
      @dragenter.prevent.stop="dragging = !disabled"
      @dragover.prevent.stop="dragging = !disabled"
      @dragleave.prevent.stop="dragging = false"
      @drop.prevent.stop="handleDrop"
    >
      <input
        ref="fileInput"
        class="attachment-drop-zone__input"
        type="file"
        multiple
        :accept="accept"
        :disabled="disabled"
        @change="handleInput"
      />
      <el-icon class="attachment-drop-zone__icon"><UploadFilled /></el-icon>
      <strong>{{ dragging ? '松开即可上传' : '拖拽附件到这里，或点击选择文件' }}</strong>
      <span>支持多文件；单个不超过 {{ formatAttachmentSize(maxSize) }}，最多 {{ maxFiles }} 个</span>
    </div>

    <el-alert
      v-for="error in validationErrors"
      :key="`${error.fileName}:${error.reason}`"
      type="error"
      :closable="false"
      show-icon
      :title="`${error.fileName}：${error.reason}`"
      class="attachment-drop-zone__error"
    />

    <div v-if="entries.length" class="attachment-drop-zone__queue">
      <div v-for="entry in entries" :key="entry.fingerprint" class="attachment-drop-zone__row">
        <div class="attachment-drop-zone__meta">
          <strong :title="entry.file.name">{{ entry.file.name }}</strong>
          <span>{{ formatAttachmentSize(entry.file.size) }} · {{ statusText(entry) }}</span>
          <el-progress
            v-if="entry.status === 'uploading'"
            :percentage="entry.progress"
            :stroke-width="6"
          />
          <span v-if="entry.error" class="attachment-drop-zone__failure">{{ entry.error }}</span>
        </div>
        <div class="attachment-drop-zone__actions" @click.stop>
          <el-button v-if="entry.status === 'error'" type="primary" link @click="retry(entry)">重试</el-button>
          <el-button
            v-if="entry.status !== 'uploading'"
            type="danger"
            link
            @click="remove(entry)"
          >删除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { UploadFilled } from '@element-plus/icons-vue';
import {
  deleteAttachment,
  uploadAndRegister,
  type Attachment,
  type AttachmentEntityType,
  type AttachmentFileCategory,
} from '@/api/attachment';
import {
  DEFAULT_ATTACHMENT_ACCEPT,
  attachmentFingerprint,
  formatAttachmentSize,
  validateAttachmentFiles,
  type AttachmentValidationError,
} from './attachmentUploadModel';

type UploadStatus = 'queued' | 'uploading' | 'success' | 'error';
interface UploadEntry {
  fingerprint: string;
  file: File;
  status: UploadStatus;
  progress: number;
  error?: string;
  attachment?: Attachment;
}

const props = withDefaults(defineProps<{
  entityType: AttachmentEntityType;
  entityId?: string;
  factoryId?: string;
  businessTag?: string;
  fileCategory?: AttachmentFileCategory;
  accept?: string;
  maxSize?: number;
  maxFiles?: number;
  autoUpload?: boolean;
  disabled?: boolean;
}>(), {
  entityId: '',
  accept: DEFAULT_ATTACHMENT_ACCEPT,
  maxSize: 50 * 1024 * 1024,
  maxFiles: 20,
  autoUpload: true,
  disabled: false,
});

const emit = defineEmits<{
  uploaded: [attachment: Attachment];
  deleted: [attachment: Attachment];
  queueChange: [summary: { total: number; pending: number; failed: number }];
}>();

const fileInput = ref<HTMLInputElement>();
const dragging = ref(false);
const entries = ref<UploadEntry[]>([]);
const validationErrors = ref<AttachmentValidationError[]>([]);
const hasPending = computed(() => entries.value.some((entry) => entry.status === 'queued' || entry.status === 'uploading'));
const hasErrors = computed(() => entries.value.some((entry) => entry.status === 'error'));

function openPicker(event?: Event): void {
  if (props.disabled) return;
  const target = event?.target as HTMLElement | null;
  if (target?.closest('button')) return;
  fileInput.value?.click();
}

function handleInput(event: Event): void {
  const input = event.target as HTMLInputElement;
  enqueue(Array.from(input.files ?? []));
  input.value = '';
}

function handleDrop(event: DragEvent): void {
  dragging.value = false;
  if (props.disabled) return;
  enqueue(Array.from(event.dataTransfer?.files ?? []));
}

function enqueue(files: File[]): void {
  validationErrors.value = [];
  const result = validateAttachmentFiles(
    files,
    new Set(entries.value.map((entry) => entry.fingerprint)),
    { accept: props.accept, maxSize: props.maxSize, maxFiles: props.maxFiles },
  );
  validationErrors.value = result.errors;
  entries.value.push(...result.accepted.map((file) => ({
    fingerprint: attachmentFingerprint(file), file, status: 'queued' as const, progress: 0,
  })));
  emitSummary();
  if (props.autoUpload && props.entityId && result.accepted.length) void uploadQueued();
}

async function uploadOne(entry: UploadEntry, entityId: string): Promise<boolean> {
  if (entry.status === 'uploading' || entry.status === 'success') return entry.status === 'success';
  entry.status = 'uploading';
  entry.progress = 0;
  entry.error = undefined;
  emitSummary();
  try {
    const attachment = await uploadAndRegister(
      entry.file,
      props.entityType,
      entityId,
      { businessTag: props.businessTag, fileCategory: props.fileCategory },
      props.factoryId,
      (progress) => { entry.progress = progress; },
    );
    entry.status = 'success';
    entry.progress = 100;
    entry.attachment = attachment;
    emit('uploaded', attachment);
    return true;
  } catch (error) {
    entry.status = 'error';
    entry.error = error instanceof Error ? error.message : '上传失败';
    return false;
  } finally {
    emitSummary();
  }
}

async function uploadQueued(entityIdOverride?: string): Promise<{ succeeded: number; failed: number }> {
  const entityId = entityIdOverride || props.entityId;
  if (!entityId) throw new Error('附件尚未绑定业务单据，请先保存采购订单');
  const targets = entries.value.filter((entry) => entry.status === 'queued' || entry.status === 'error');
  const results = await Promise.all(targets.map((entry) => uploadOne(entry, entityId)));
  return { succeeded: results.filter(Boolean).length, failed: results.filter((result) => !result).length };
}

async function retry(entry: UploadEntry): Promise<void> {
  const entityId = props.entityId;
  if (!entityId) return void ElMessage.warning('请先保存采购订单，再重试附件上传');
  await uploadOne(entry, entityId);
}

async function remove(entry: UploadEntry): Promise<void> {
  if (entry.attachment) {
    await deleteAttachment(entry.attachment.id, props.factoryId);
    emit('deleted', entry.attachment);
  }
  entries.value = entries.value.filter((candidate) => candidate.fingerprint !== entry.fingerprint);
  emitSummary();
}

function statusText(entry: UploadEntry): string {
  return { queued: '等待绑定单据', uploading: '上传中', success: '上传成功', error: '上传失败' }[entry.status];
}

function emitSummary(): void {
  emit('queueChange', {
    total: entries.value.length,
    pending: entries.value.filter((entry) => entry.status === 'queued' || entry.status === 'uploading').length,
    failed: entries.value.filter((entry) => entry.status === 'error').length,
  });
}

function clear(): void {
  entries.value = [];
  validationErrors.value = [];
  emitSummary();
}

defineExpose({ uploadQueued, clear, hasPending, hasErrors, entries });
</script>

<style scoped>
.attachment-drop-zone-wrap { width: 100%; }
.attachment-drop-zone {
  min-height: 118px; border: 1px dashed var(--el-border-color); border-radius: 8px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  color: var(--el-text-color-regular); cursor: pointer; background: var(--el-fill-color-lighter);
  transition: border-color .2s, background-color .2s;
}
.attachment-drop-zone:hover, .attachment-drop-zone:focus, .attachment-drop-zone.is-dragging {
  border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); outline: none;
}
.attachment-drop-zone.is-disabled { cursor: not-allowed; opacity: .6; }
.attachment-drop-zone__input { display: none; }
.attachment-drop-zone__icon { font-size: 28px; color: var(--el-color-primary); }
.attachment-drop-zone > span { font-size: 12px; color: var(--el-text-color-secondary); }
.attachment-drop-zone__error { margin-top: 8px; }
.attachment-drop-zone__queue { margin-top: 10px; display: grid; gap: 8px; }
.attachment-drop-zone__row { display: flex; gap: 12px; align-items: center; padding: 9px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.attachment-drop-zone__meta { flex: 1; min-width: 0; display: grid; gap: 3px; }
.attachment-drop-zone__meta strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-drop-zone__meta span { color: var(--el-text-color-secondary); font-size: 12px; }
.attachment-drop-zone__failure { color: var(--el-color-danger) !important; }
.attachment-drop-zone__actions { display: flex; }
</style>
