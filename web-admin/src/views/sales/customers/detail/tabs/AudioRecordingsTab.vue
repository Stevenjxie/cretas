<!--
  Sprint 6 W2-C-2 (2026-05-20): 通话记录 + 录音 tab (replaces Sprint 5 F-1 placeholder).

  Background: HJ Round 11 §F.2 — Customer 详情 "谈话录音" tab. F006 卤制品销售场景:
  电话沟通后上传录音 + Whisper 自动转写关键信息.

  Backend: CallRecordController @ /api/mobile/{factoryId}/customer/{customerId}/call-record
  - POST multipart/form-data (audio + metadata) → OSS upload → Whisper async transcribe
  - GET list/single → frontend polls for transcript status

  防呆 (per fool-proof-design.md):
    - R1 (max display): 文件大小 50 MB 上限 → 选择后立即显 "已选 X MB / 50 MB"
    - R2 (context): dialog header 含 客户名 (规格代码) + "已有 N 条通话"
    - R3 (dropdown): callType 必为 INCOMING/OUTGOING/MISSED (el-select 非自由文本)
    - R4 (idempotent): 后端 5min dedup, 409 actionHint → 跳转高亮已有记录
    - R5 (dead-end): 空状态显 "去新增第一条通话记录" button

  4 位一体 error toast:
    - duration: 0 + showClose for errors (sticky)
    - 展示后端 message 原文 (e.response.data.message)
-->
<template>
  <div class="call-records-tab">
    <div class="toolbar">
      <span class="title">
        通话记录 + 录音
        <el-tag v-if="customer" type="info" size="small" class="ctx">
          {{ customer.name }}{{ customer.customerCode ? ` (${customer.customerCode})` : '' }}
        </el-tag>
      </span>
      <div class="actions">
        <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增通话记录</el-button>
      </div>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />

    <!-- 防呆 R5: 空状态附 next action button -->
    <el-empty v-else-if="state === 'empty'" description="暂无通话记录" :image-size="80">
      <el-button type="primary" @click="openCreateDialog">去新增第一条通话记录</el-button>
    </el-empty>

    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-timeline class="call-timeline">
        <el-timeline-item
          v-for="row in records"
          :key="row.id"
          :timestamp="formatDate(row.callTime)"
          placement="top"
          :type="timelineColor(row.callType)"
        >
          <el-card shadow="hover" class="call-card" :class="cardClass(row.callType)">
            <template #header>
              <div class="card-header">
                <el-tag size="small" :type="typeTagType(row.callType)">
                  {{ typeLabel(row.callType) }}
                </el-tag>
                <span class="duration">
                  时长: {{ formatDuration(row.durationSec) }}
                </span>
                <span class="recorder">{{ row.createdByName || `用户 #${row.createdBy}` }}</span>
                <span v-if="row.contactName" class="contact-person">
                  对端: {{ row.contactName }}{{ row.contactPhone ? ` (${row.contactPhone})` : '' }}
                </span>
                <div class="card-actions">
                  <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
                  <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
                </div>
              </div>
            </template>

            <!-- 录音播放 (HTML5 audio) -->
            <div v-if="row.audioOssUrl" class="audio-block">
              <audio :src="row.audioOssUrl" controls preload="none" class="audio-player">
                您的浏览器不支持音频播放
              </audio>
              <div class="audio-meta">
                <span v-if="row.audioFilename" class="audio-filename">{{ row.audioFilename }}</span>
                <span v-if="row.audioSizeBytes" class="audio-size">
                  ({{ formatFileSize(row.audioSizeBytes) }})
                </span>
              </div>
            </div>
            <div v-else-if="row.callType === 'MISSED'" class="missed-note">
              <el-tag size="small" type="warning" effect="plain">漏接 - 无录音</el-tag>
            </div>

            <!-- Whisper 转写文本 -->
            <div v-if="row.transcriptText" class="transcript-block">
              <div class="transcript-header">
                <el-tag size="small" type="success" effect="plain">转写文本</el-tag>
                <span class="transcript-status-tip">(Whisper 自动转写)</span>
              </div>
              <div class="transcript-text">{{ row.transcriptText }}</div>
            </div>
            <div v-else-if="row.transcriptStatus === 'PENDING'" class="transcript-pending">
              <el-tag size="small" type="info" effect="plain">
                <el-icon class="is-loading"><Loading /></el-icon>
                转写处理中...
              </el-tag>
              <el-button link type="primary" size="small" @click="refreshSingle(row)">刷新查看</el-button>
            </div>
            <div v-else-if="row.transcriptStatus === 'FAILED'" class="transcript-failed">
              <el-tag size="small" type="danger" effect="plain">转写失败</el-tag>
              <span class="transcript-hint">(请人工补录关键内容到「备注」字段)</span>
            </div>

            <!-- 备注 -->
            <div v-if="row.remark" class="remark">
              <el-tag size="small" type="info" effect="plain">备注</el-tag>
              <span>{{ row.remark }}</span>
            </div>
          </el-card>
        </el-timeline-item>
      </el-timeline>

      <el-pagination
        class="pagination"
        background
        layout="prev, pager, next, total"
        :total="totalElements"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="onPageChange"
      />
    </template>

    <!-- 新增 / 编辑 dialog (防呆 R2: header 含 客户名 + 已有 N 条) -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="640px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        v-if="!editingId"
        type="info"
        :closable="false"
        show-icon
        title="此客户当前已有通话记录"
        :description="`已有 ${totalElements} 条历史记录. 5 分钟内重复 callTime 会被拒收 (防呆 R4).`"
        style="margin-bottom: 16px"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <!-- 防呆 R3 dropdown — 3 options enforced, no free text -->
        <el-form-item label="通话类型" prop="callType">
          <el-select v-model="form.callType" placeholder="请选择通话类型" style="width: 100%" :disabled="!!editingId">
            <el-option
              v-for="t in CALL_TYPES"
              :key="t.value"
              :value="t.value"
              :label="t.label"
            >
              <span>{{ t.label }}</span>
              <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">
                ({{ t.value }})
              </span>
            </el-option>
          </el-select>
          <div class="hint">
            <span v-if="form.callType === 'INCOMING'">客户主动呼入</span>
            <span v-else-if="form.callType === 'OUTGOING'">业务员主动呼出</span>
            <span v-else-if="form.callType === 'MISSED'">漏接 (无录音, 时长 0)</span>
          </div>
        </el-form-item>

        <el-form-item label="通话时间" prop="callTime">
          <el-date-picker
            v-model="form.callTime"
            type="datetime"
            placeholder="默认当前时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="时长 (秒)" prop="durationSec">
          <el-input-number
            v-model="form.durationSec"
            :min="0"
            :max="36000"
            placeholder="选填, 漏接默认 0"
            :disabled="form.callType === 'MISSED'"
            style="width: 100%"
          />
        </el-form-item>

        <!-- 录音文件上传 (callType=MISSED 时禁用) -->
        <el-form-item label="录音文件" v-if="!editingId">
          <div class="upload-block">
            <el-upload
              :auto-upload="false"
              :on-change="handleFileChange"
              :on-remove="handleFileRemove"
              :limit="1"
              :file-list="uploadFileList"
              :disabled="form.callType === 'MISSED'"
              accept="audio/mpeg,audio/wav,audio/ogg,audio/webm,audio/mp4,audio/x-m4a,.mp3,.wav,.ogg,.webm,.m4a"
            >
              <el-button
                :icon="Upload"
                :disabled="form.callType === 'MISSED'"
                type="primary"
                plain
              >
                选择录音文件
              </el-button>
              <template #tip>
                <!-- 防呆 R1: max display 实时 -->
                <div class="upload-tip">
                  <span v-if="selectedFile">
                    已选 {{ formatFileSize(selectedFile.size) }} /
                    <span :class="{ 'size-over': overSize }">50 MB</span>
                    <el-tag v-if="overSize" type="danger" size="small" style="margin-left: 8px">
                      超过限制
                    </el-tag>
                  </span>
                  <span v-else>
                    支持格式: mp3 / wav / m4a / ogg / webm. 单文件最大 50 MB.
                  </span>
                </div>
              </template>
            </el-upload>
          </div>
        </el-form-item>

        <el-form-item label="对端联系人" prop="contactName">
          <el-input v-model="form.contactName" placeholder="对方姓名 (可选)" maxlength="100" />
        </el-form-item>

        <el-form-item label="对端电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="对方电话 (可选)" maxlength="32" />
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            placeholder="通话要点 / 客户反馈 / 下次跟进事项 (可选)"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          :disabled="overSize"
          @click="submitForm"
        >
          {{ editingId ? '保存修改' : '提交' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, Plus, Upload, Loading } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadFile, type UploadUserFile } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { storeToRefs } from 'pinia';
import {
  listCallRecords,
  createCallRecord,
  updateCallRecord,
  deleteCallRecord,
  getCallRecord,
  CALL_TYPES,
  callTypeLabel as typeLabel,
  callTypeTagType as typeTagType,
  formatFileSize,
  formatDuration,
  MAX_AUDIO_FILE_SIZE_BYTES,
  type CallRecord,
  type CallType,
  type UpdateCallRecordRequest,
} from '@/api/callRecord';
import type { Customer } from '@/api/customer';

const props = defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const router = useRouter();
const authStore = useAuthStore();
const { factoryId, user } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const records = ref<CallRecord[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const submitLoading = ref(false);
const formRef = ref<FormInstance | null>(null);

const selectedFile = ref<File | null>(null);
const uploadFileList = ref<UploadUserFile[]>([]);
const overSize = computed(() => {
  return !!selectedFile.value && selectedFile.value.size > MAX_AUDIO_FILE_SIZE_BYTES;
});

interface CallForm {
  callType: CallType;
  callTime: string;
  durationSec: number | null;
  contactName: string;
  contactPhone: string;
  remark: string;
}

const form = ref<CallForm>({
  callType: 'OUTGOING',
  callTime: '',
  durationSec: null,
  contactName: '',
  contactPhone: '',
  remark: '',
});

const rules: FormRules = {
  callType: [{ required: true, message: '请选择通话类型', trigger: 'change' }],
};

// 防呆 R2 context: header 含 客户名 + 编号 + 已有 N 条
const dialogTitle = computed(() => {
  const action = editingId.value ? '编辑通话记录' : '新增通话记录';
  const name = props.customer?.name || '';
  const code = props.customer?.customerCode || '';
  if (name && code) {
    return `${action} — ${name} (${code})`;
  }
  return name ? `${action} — ${name}` : action;
});

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function timelineColor(t: CallType): 'success' | 'primary' | 'warning' {
  if (t === 'INCOMING') return 'success';
  if (t === 'OUTGOING') return 'primary';
  return 'warning';
}

function cardClass(t: CallType): string {
  if (t === 'INCOMING') return 'call-card--incoming';
  if (t === 'OUTGOING') return 'call-card--outgoing';
  return 'call-card--missed';
}

async function fetchList() {
  if (!factoryId.value || !props.customerId) return;
  state.value = 'loading';
  try {
    const page = await listCallRecords(factoryId.value, props.customerId, {
      page: currentPage.value - 1,
      size: pageSize,
    });
    records.value = page.content;
    totalElements.value = page.totalElements;
    state.value = records.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    handleError(e);
  }
}

// Single-row refresh — used by "刷新查看" on PENDING transcript rows.
async function refreshSingle(row: CallRecord) {
  if (!factoryId.value) return;
  try {
    const fresh = await getCallRecord(factoryId.value, props.customerId, row.id);
    const idx = records.value.findIndex((r) => r.id === row.id);
    if (idx >= 0) {
      records.value[idx] = fresh;
    }
  } catch (e) {
    const backendMsg = (e as { response?: { data?: { message?: string } }; message?: string })
      ?.response?.data?.message
      || (e as Error)?.message
      || '刷新失败';
    ElMessage({ message: backendMsg, type: 'error', duration: 0, showClose: true });
  }
}

function handleError(e: unknown) {
  const err = e as { response?: { status?: number; data?: { message?: string } }; code?: number; message?: string };
  const status = err?.response?.status || err?.code;
  const backendMsg = err?.response?.data?.message || err?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的通话记录';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/AudioRecordingsTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function openCreateDialog() {
  editingId.value = null;
  form.value = {
    callType: 'OUTGOING',
    callTime: '',
    durationSec: null,
    contactName: '',
    contactPhone: '',
    remark: '',
  };
  selectedFile.value = null;
  uploadFileList.value = [];
  dialogVisible.value = true;
}

function openEditDialog(row: CallRecord) {
  editingId.value = row.id;
  form.value = {
    callType: row.callType,
    callTime: row.callTime || '',
    durationSec: row.durationSec ?? null,
    contactName: row.contactName || '',
    contactPhone: row.contactPhone || '',
    remark: row.remark || '',
  };
  selectedFile.value = null;
  uploadFileList.value = [];
  dialogVisible.value = true;
}

function handleFileChange(uploadFile: UploadFile, fileList: UploadUserFile[]) {
  uploadFileList.value = fileList;
  const f = uploadFile.raw;
  if (!f) {
    selectedFile.value = null;
    return;
  }
  selectedFile.value = f;
  // 防呆 R1: warn 但不强阻塞 (submit button :disabled 才阻塞)
  if (f.size > MAX_AUDIO_FILE_SIZE_BYTES) {
    ElMessage({
      message: `文件大小 ${(f.size / 1024 / 1024).toFixed(2)} MB 超过 50 MB 上限`,
      type: 'warning',
      duration: 3000,
    });
  }
}

function handleFileRemove() {
  selectedFile.value = null;
  uploadFileList.value = [];
}

async function submitForm() {
  if (!formRef.value || !factoryId.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      if (editingId.value) {
        const body: UpdateCallRecordRequest = {
          contactName: form.value.contactName || undefined,
          contactPhone: form.value.contactPhone || undefined,
          remark: form.value.remark || undefined,
          durationSec: form.value.durationSec ?? undefined,
        };
        await updateCallRecord(factoryId.value, props.customerId, editingId.value, body);
        ElMessage.success('已更新');
      } else {
        // 拿当前登录 user 作为 createdBy
        const u = user.value;
        if (!u || !u.id) {
          throw new Error('当前未登录或用户信息缺失');
        }
        await createCallRecord(factoryId.value, props.customerId, {
          callType: form.value.callType,
          callTime: form.value.callTime || undefined,
          durationSec: form.value.durationSec ?? undefined,
          contactName: form.value.contactName || undefined,
          contactPhone: form.value.contactPhone || undefined,
          remark: form.value.remark || undefined,
          createdBy: u.id,
          createdByName: u.fullName || u.username,
          audio: selectedFile.value,
        });
        ElMessage.success(
          selectedFile.value
            ? '通话记录已创建, Whisper 转写进行中 (~10-30s)'
            : '通话记录已创建',
        );
      }
      dialogVisible.value = false;
      await fetchList();
    } catch (e) {
      // 防呆 R4: 后端 409 with actionHint → confirm + navigate
      const err = e as {
        response?: { status?: number; data?: { message?: string; actionHint?: string } };
        code?: number;
        message?: string;
      };
      const status = err?.response?.status || err?.code;
      const backendMsg = err?.response?.data?.message || err?.message || '操作失败';
      const actionHint = err?.response?.data?.actionHint;
      if (status === 409 && actionHint) {
        try {
          await ElMessageBox.confirm(
            `${backendMsg} 是否跳转查看?`,
            '操作冲突',
            { type: 'warning', confirmButtonText: '查看', cancelButtonText: '取消' },
          );
          router.push(actionHint);
        } catch {
          // user cancelled
        }
      } else {
        ElMessage({
          message: backendMsg,
          type: 'error',
          duration: 0,
          showClose: true,
        });
      }
      console.error('[CustomerDetail/AudioRecordingsTab] submit failed:', e);
    } finally {
      submitLoading.value = false;
    }
  });
}

async function confirmDelete(row: CallRecord) {
  // 防呆 R2 context: confirm 含 客户名 + 通话时间 + 类型
  const callTimeFmt = formatDate(row.callTime);
  try {
    await ElMessageBox.confirm(
      `确定删除「${props.customer?.name || '客户'}」的通话记录?\n\n` +
        `时间: ${callTimeFmt}\n类型: ${typeLabel(row.callType)}`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    await deleteCallRecord(factoryId.value, props.customerId, row.id);
    ElMessage.success('已删除');
    await fetchList();
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } }; message?: string };
    const backendMsg = err?.response?.data?.message || err?.message || '删除失败';
    ElMessage({ message: backendMsg, type: 'error', duration: 0, showClose: true });
  }
}

onMounted(fetchList);
</script>

<style scoped>
.call-records-tab {
  padding: 8px 0;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar .title {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ctx {
  margin-left: 8px;
}
.call-timeline {
  padding-left: 12px;
}
.call-card {
  margin-bottom: 4px;
  border-left: 3px solid var(--el-border-color);
}
.call-card--incoming {
  border-left-color: var(--el-color-success);
}
.call-card--outgoing {
  border-left-color: var(--el-color-primary);
}
.call-card--missed {
  border-left-color: var(--el-color-warning);
}
.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.duration {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.recorder {
  font-size: 13px;
  color: var(--el-text-color-regular);
  font-weight: 500;
}
.contact-person {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-actions {
  margin-left: auto;
  display: flex;
  gap: 4px;
}
.audio-block {
  margin: 8px 0;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.audio-player {
  width: 100%;
  max-width: 480px;
}
.audio-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.audio-filename {
  font-family: monospace;
}
.audio-size {
  margin-left: 8px;
}
.missed-note {
  margin: 8px 0;
}
.transcript-block {
  margin-top: 12px;
  padding: 8px;
  background: var(--el-color-success-light-9);
  border-left: 3px solid var(--el-color-success);
  border-radius: 4px;
}
.transcript-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.transcript-status-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.transcript-text {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-primary);
}
.transcript-pending {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.transcript-failed {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.transcript-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.remark {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.upload-block {
  display: flex;
  flex-direction: column;
}
.upload-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.size-over {
  color: var(--el-color-danger);
  font-weight: bold;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
  display: flex;
}
.error-panel {
  padding: 24px 0;
}
</style>
