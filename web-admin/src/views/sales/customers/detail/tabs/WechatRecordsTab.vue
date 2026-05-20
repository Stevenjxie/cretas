<!--
  Sprint 6 W2-C-1: 微信记录 tab.

  Background: HJ Round 11 §F.2 finding — Customer 详情 "微信记录" tab.
  F006 业务员场景: 跟客户微信聊单进度, 因微信 OAuth 第三方限制, 改用手工补录.

  防呆 (per fool-proof-design.md):
    - R2 (context): dialog header 含 客户名 + 已有 N 条
    - R3 (dropdown): direction 必为 INBOUND / OUTBOUND / INTERNAL (el-select 非自由文本)
    - R4 (idempotent): 后端 5min dedup, 409 actionHint → 跳转高亮已有记录
    - R5 (dead-end): 空状态显 "去新增第一条微信记录" button

  4 位一体 error toast:
    - duration: 0 + showClose for errors (sticky)
    - 展示后端 message 原文 (e.response.data.message)
-->
<template>
  <div class="wechat-records-tab">
    <div class="toolbar">
      <span class="title">微信记录 (手工补录)</span>
      <div class="actions">
        <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增微信记录</el-button>
      </div>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />

    <!-- 防呆 R5: 空状态附 next action button -->
    <el-empty v-else-if="state === 'empty'" description="暂无微信记录" :image-size="80">
      <el-button type="primary" @click="openCreateDialog">去新增第一条微信记录</el-button>
    </el-empty>

    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <!-- Timeline 时间线展示 (符合聊天记录视觉直觉) -->
      <el-timeline class="wechat-timeline">
        <el-timeline-item
          v-for="row in records"
          :key="row.id"
          :timestamp="formatDate(row.recordTime)"
          placement="top"
          :type="timelineColor(row.direction)"
        >
          <el-card shadow="hover" class="wechat-card" :class="cardClass(row.direction)">
            <template #header>
              <div class="card-header">
                <el-tag size="small" :type="dirTagType(row.direction)">
                  {{ dirLabel(row.direction) }}
                </el-tag>
                <span class="recorder">{{ row.createdByName || `用户 #${row.createdBy}` }}</span>
                <span v-if="row.contactPerson" class="contact-person">
                  联系人: {{ row.contactPerson }}
                </span>
                <div class="card-actions">
                  <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
                  <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
                </div>
              </div>
            </template>
            <div class="message-content">{{ row.messageContent }}</div>
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

    <!-- 防呆 R2: dialog header 含 客户名 + 已有 N 条 -->
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
        title="此客户当前已有微信记录"
        :description="`已有 ${totalElements} 条历史记录. 5 分钟内重复内容会被拒收 (防呆 R4).`"
        style="margin-bottom: 16px"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <!-- 防呆 R3 dropdown — 3 options enforced, no free text -->
        <el-form-item label="方向" prop="direction">
          <el-select v-model="form.direction" placeholder="请选择消息方向" style="width: 100%">
            <el-option
              v-for="d in WECHAT_DIRECTIONS"
              :key="d.value"
              :value="d.value"
              :label="d.label"
            >
              <span>{{ d.label }}</span>
              <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">
                ({{ d.value }})
              </span>
            </el-option>
          </el-select>
          <div class="hint">
            <span v-if="form.direction === 'INBOUND'">客户主动发消息给业务员</span>
            <span v-else-if="form.direction === 'OUTBOUND'">业务员主动联系客户</span>
            <span v-else-if="form.direction === 'INTERNAL'">业务员自记工作笔记 (不发给客户)</span>
          </div>
        </el-form-item>

        <el-form-item label="消息内容" prop="messageContent">
          <el-input
            v-model="form.messageContent"
            type="textarea"
            :rows="5"
            placeholder="请输入或粘贴微信聊天内容 (建议: 简短记录关键事项)"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="对方联系人 (可选)" />
        </el-form-item>

        <el-form-item label="记录时间" prop="recordTime">
          <el-date-picker
            v-model="form.recordTime"
            type="datetime"
            placeholder="默认当前时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">
          {{ editingId ? '保存修改' : '提交' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { storeToRefs } from 'pinia';
import {
  listWechatRecords,
  createWechatRecord,
  updateWechatRecord,
  deleteWechatRecord,
  WECHAT_DIRECTIONS,
  wechatDirectionLabel as dirLabel,
  wechatDirectionTagType as dirTagType,
  type WechatRecord,
  type WechatDirection,
  type CreateWechatRecordRequest,
  type UpdateWechatRecordRequest,
} from '@/api/wechatRecord';
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
const records = ref<WechatRecord[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const submitLoading = ref(false);
const formRef = ref<FormInstance | null>(null);

interface WechatForm {
  direction: WechatDirection;
  messageContent: string;
  contactPerson: string;
  recordTime: string;
  remark: string;
}

const form = ref<WechatForm>({
  direction: 'OUTBOUND',
  messageContent: '',
  contactPerson: '',
  recordTime: '',
  remark: '',
});

const rules: FormRules = {
  direction: [{ required: true, message: '请选择消息方向', trigger: 'change' }],
  messageContent: [
    { required: true, message: '请输入消息内容', trigger: 'blur' },
    { min: 1, max: 2000, message: '消息内容长度须在 1-2000 字之间', trigger: 'blur' },
  ],
};

// 防呆 R2 context: header 含 客户名 + 已有 N 条
const dialogTitle = computed(() => {
  const action = editingId.value ? '编辑微信记录' : '新增微信记录';
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

function timelineColor(d: WechatDirection): 'success' | 'primary' | 'info' {
  if (d === 'INBOUND') return 'success';
  if (d === 'OUTBOUND') return 'primary';
  return 'info';
}

function cardClass(d: WechatDirection): string {
  if (d === 'INBOUND') return 'wechat-card--inbound';
  if (d === 'OUTBOUND') return 'wechat-card--outbound';
  return 'wechat-card--internal';
}

async function fetchList() {
  if (!factoryId.value || !props.customerId) return;
  state.value = 'loading';
  try {
    const page = await listWechatRecords(factoryId.value, props.customerId, {
      page: currentPage.value - 1,
      size: pageSize,
    });
    records.value = page.content;
    totalElements.value = page.totalElements;
    state.value = records.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    handleError(e);
  }
}

function handleError(e: any) {
  const status = e?.response?.status || e?.code;
  const backendMsg = e?.response?.data?.message || e?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的微信记录';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    // 4 位一体: sticky + 后端 message 原文 + showClose
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/WechatRecordsTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function openCreateDialog() {
  editingId.value = null;
  form.value = {
    direction: 'OUTBOUND',
    messageContent: '',
    contactPerson: '',
    recordTime: '',
    remark: '',
  };
  dialogVisible.value = true;
}

function openEditDialog(row: WechatRecord) {
  editingId.value = row.id;
  form.value = {
    direction: row.direction,
    messageContent: row.messageContent,
    contactPerson: row.contactPerson || '',
    recordTime: row.recordTime || '',
    remark: row.remark || '',
  };
  dialogVisible.value = true;
}

async function submitForm() {
  if (!formRef.value || !factoryId.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      if (editingId.value) {
        const body: UpdateWechatRecordRequest = {
          direction: form.value.direction,
          messageContent: form.value.messageContent,
          contactPerson: form.value.contactPerson || undefined,
          remark: form.value.remark || undefined,
        };
        await updateWechatRecord(factoryId.value, props.customerId, editingId.value, body);
        ElMessage.success('已更新');
      } else {
        // 拿当前登录 user 作为 createdBy
        const u = user.value;
        if (!u || !u.id) {
          throw new Error('当前未登录或用户信息缺失');
        }
        const body: CreateWechatRecordRequest = {
          direction: form.value.direction,
          messageContent: form.value.messageContent,
          createdBy: u.id,
          createdByName: u.fullName || u.username,
          recordTime: form.value.recordTime || undefined,
          contactPerson: form.value.contactPerson || undefined,
          remark: form.value.remark || undefined,
        };
        await createWechatRecord(factoryId.value, props.customerId, body);
        ElMessage.success('微信记录已创建');
      }
      dialogVisible.value = false;
      await fetchList();
    } catch (e: any) {
      // 防呆 R4: 后端 409 with actionHint → confirm + navigate
      const status = e?.response?.status || e?.code;
      const backendMsg = e?.response?.data?.message || e?.message || '操作失败';
      const actionHint = e?.response?.data?.actionHint;
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
        // 4 位一体
        ElMessage({
          message: backendMsg,
          type: 'error',
          duration: 0,
          showClose: true,
        });
      }
      console.error('[CustomerDetail/WechatRecordsTab] submit failed:', e);
    } finally {
      submitLoading.value = false;
    }
  });
}

async function confirmDelete(row: WechatRecord) {
  // 防呆 R2 context: confirm 含 客户名 + 内容片段
  const preview = (row.messageContent || '').substring(0, 30);
  try {
    await ElMessageBox.confirm(
      `确定删除「${props.customer?.name || '客户'}」的微信记录?\n\n内容: ${preview}${row.messageContent.length > 30 ? '...' : ''}`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    await deleteWechatRecord(factoryId.value, props.customerId, row.id);
    ElMessage.success('已删除');
    await fetchList();
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message || '删除失败';
    ElMessage({ message: backendMsg, type: 'error', duration: 0, showClose: true });
  }
}

onMounted(fetchList);
</script>

<style scoped>
.wechat-records-tab {
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
.wechat-timeline {
  padding-left: 12px;
}
.wechat-card {
  margin-bottom: 4px;
  border-left: 3px solid var(--el-border-color);
}
.wechat-card--inbound {
  border-left-color: var(--el-color-success);
}
.wechat-card--outbound {
  border-left-color: var(--el-color-primary);
}
.wechat-card--internal {
  border-left-color: var(--el-color-info);
}
.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
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
.message-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: var(--el-text-color-primary);
  font-size: 14px;
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
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
  display: flex;
}
.error-panel {
  padding: 24px 0;
}
</style>
