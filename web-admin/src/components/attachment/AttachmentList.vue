<template>
  <div class="attachment-list">
    <el-skeleton v-if="loading" :rows="2" animated />
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-else-if="list.length === 0" :description="emptyText ?? '暂无附件'" :image-size="60" />
    <div v-else class="att-grid">
      <el-card
        v-for="att in list"
        :key="att.id"
        class="att-card"
        shadow="hover"
        body-style="padding: 8px"
      >
        <div class="att-row">
          <div class="att-thumb" @click="open(att)">
            <el-image
              v-if="att.fileCategory === 'PHOTO'"
              :src="att.thumbnailUrl ?? att.fileUrl"
              fit="cover"
              :preview-src-list="[att.fileUrl]"
              :initial-index="0"
              class="thumb-img"
            />
            <video
              v-else-if="att.fileCategory === 'VIDEO'"
              :src="att.fileUrl"
              class="thumb-video"
              controls
              @click.stop
            />
            <div v-else class="thumb-icon">{{ categoryIcon(att.fileCategory) }}</div>
          </div>
          <div class="att-meta">
            <div class="att-name" :title="att.fileName">{{ att.fileName }}</div>
            <div class="att-sub">
              {{ formatSize(att.fileSize) }} · {{ formatTime(att.uploadedAt) }}
            </div>
            <div v-if="att.description" class="att-desc">{{ att.description }}</div>
          </div>
          <el-button text :icon="Delete" @click="handleDelete(att)" aria-label="删除附件" />
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete } from '@element-plus/icons-vue';
import {
  listAttachments,
  deleteAttachment,
  type Attachment,
  type AttachmentEntityType,
  type AttachmentFileCategory,
} from '@/api/attachment';

interface Props {
  entityType: AttachmentEntityType;
  entityId: string;
  factoryId?: string;
  /** 改变此 key 触发重拉. */
  refreshKey?: number;
  emptyText?: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  deleted: [att: Attachment];
  /**
   * 当前附件条数。调用方要据此判断「有没有传凭证」时用。
   *
   * 🔴 2026-08-03: 收货面板的「确认收货入库」按钮此前<b>没有任何 disabled 绑定</b> ——
   * 一个附件都没有也能点, 点完还要过一次二次确认弹窗, 才由服务端返回
   * 「409 确认收货前必须上传供应商供货单或收货凭证」。这个组件当时只 emit deleted,
   * 调用方拿不到条数, 想禁用也无从判断。
   */
  countChange: [count: number];
}>();

const list = ref<Attachment[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

async function load(): Promise<void> {
  if (!props.entityId) return;
  loading.value = true;
  error.value = null;
  try {
    const r = await listAttachments(props.entityType, props.entityId, props.factoryId);
    list.value = r.data ?? [];
    emit('countChange', list.value.length);
  } catch (e: unknown) {
    // Issue #750 — 附件区显示"用户数据无效" multi-page bug.
    // 区分两类错误:
    //   (a) factoryId 解析失败 (auth/账号问题) → 友好显示空态, 不要红色 alert 吓客户
    //   (b) API 调用失败 (网络/权限/服务) → 红色 alert
    list.value = [];
    emit('countChange', 0);   // 加载失败时按「没有附件」处理 —— 宁可挡住也别放行没凭证的入库
    const msg = e instanceof Error ? e.message : '加载附件失败';
    const isAuthIssue = /未登录|登录态|绑定工厂|factoryId/i.test(msg);
    if (isAuthIssue) {
      error.value = null; // 走 el-empty 暂无附件
    } else {
      error.value = msg;
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.entityType, props.entityId, props.refreshKey],
  () => load(),
  { immediate: true },
);

function open(att: Attachment): void {
  if (att.fileCategory === 'PHOTO') return; // el-image preview 已处理
  window.open(att.fileUrl, '_blank');
}

async function handleDelete(att: Attachment): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除附件 "${att.fileName}" ?`, '确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
    await deleteAttachment(att.id, props.factoryId);
    list.value = list.value.filter((x) => x.id !== att.id);
    emit('countChange', list.value.length);
    emit('deleted', att);
    ElMessage.success('已删除');
  } catch (e: unknown) {
    if (e === 'cancel') return;
    const msg = e instanceof Error ? e.message : '删除失败';
    ElMessage.error(msg);
  }
}

function categoryIcon(c: AttachmentFileCategory): string {
  switch (c) {
    case 'VIDEO':
      return 'VIDEO';
    case 'DOCUMENT':
      return 'DOC';
    case 'VOUCHER':
      return 'VOUCHER';
    case 'SIGNATURE':
      return 'SIGN';
    case 'CONTRACT':
      return 'CONTRACT';
    default:
      return 'FILE';
  }
}

function formatSize(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch {
    return iso;
  }
}

defineExpose({ reload: load });
</script>

<style scoped>
.attachment-list {
  width: 100%;
}
.att-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.att-card {
  width: 100%;
}
.att-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.att-thumb {
  cursor: pointer;
  width: 56px;
  height: 56px;
  border-radius: 6px;
  overflow: hidden;
  background: #f4f4f5;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb-img {
  width: 56px;
  height: 56px;
  border-radius: 6px;
}
.thumb-video {
  width: 56px;
  height: 56px;
  border-radius: 6px;
  object-fit: cover;
  background: #000;
}
.thumb-icon {
  font-size: 11px;
  font-weight: 700;
  color: #606266;
  text-align: center;
  padding: 0 4px;
}
.att-meta {
  flex: 1;
  min-width: 0;
}
.att-name {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.att-sub,
.att-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.att-desc {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
