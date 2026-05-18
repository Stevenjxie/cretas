<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 21 文件附件 by customer.
  Backend: GET /api/mobile/{factoryId}/attachments?entityType=CUSTOMER&entityId= (existing).
  支持下载, 上传暂留 Phase 后续接.
-->
<template>
  <div class="attachments-tab">
    <div class="toolbar">
      <span class="title">文件附件</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />
    <el-empty v-else-if="state === 'empty'" description="该客户暂无附件" :image-size="80">
      <p class="hint">附件上传请在客户编辑页或相关业务单据上传后自动归档.</p>
    </el-empty>
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="attachments" border stripe size="small">
        <el-table-column label="文件名" prop="fileName" min-width="220" show-overflow-tooltip />
        <el-table-column label="大小" prop="fileSize" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="类型" prop="contentType" width="160" show-overflow-tooltip />
        <el-table-column label="上传时间" prop="createdAt" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

interface Attachment {
  id: string;
  fileName?: string;
  fileSize?: number;
  contentType?: string;
  createdAt?: string;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const attachments = ref<Attachment[]>([]);

function formatSize(b?: number): string {
  if (b == null) return '—';
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<Attachment[] | { content: Attachment[] }>(
      `/${factoryId.value}/attachments`,
      { params: { entityType: 'CUSTOMER', entityId: props.customerId } },
    );
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    // backend returns List directly (see AttachmentController @GetMapping line 88)
    attachments.value = Array.isArray(res.data) ? res.data : (res.data as any).content || [];
    state.value = attachments.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的附件';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/AttachmentsTab] fetch failed:', e);
  }
}

function download(row: Attachment) {
  if (!factoryId.value) return;
  const url = `/api/mobile/${factoryId.value}/attachments/${row.id}/download`;
  window.open(url, '_blank');
}

onMounted(fetchList);
</script>

<style scoped>
.attachments-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 8px; }
.error-panel { padding: 24px 0; }
</style>
