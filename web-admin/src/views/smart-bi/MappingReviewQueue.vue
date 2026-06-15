<script setup lang="ts">
/**
 * Phase 0: 字段映射复核队列
 *
 * Columns the controlled-vocab mapper could not map confidently end up here
 * (PENDING). An operator picks the correct canonical field from a dropdown
 * and confirms → backend writes a deterministic pin so the same column on
 * the same upload template always maps identically in the future.
 *
 * UX防呆规则 (fool-proof-design.md):
 * - Rule 2: 每行显示 column_name + detected_standard(系统猜) + confidence + upload_id/template_key
 * - Rule 3: confirmed_standard 用 el-select + el-option-group(按域分组, 禁自由文本)
 * - Rule 4: 批量确认幂等(成功行从列表消失)
 * - Rule 5: 队列空时友好空状态
 * - 4位一体错误: 后端 message 原样透传, error sticky (request.ts 已实现 duration:0)
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Check, InfoFilled } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ==================== 类型定义 ====================

interface PendingItem {
  id: number;
  upload_id: number;
  template_key: string;
  column_name: string;
  detected_standard: string | null;
  detected_confidence: number | null;
  detected_method: string | null;
  created_at: string;
  // local UI state
  _confirmed_standard?: string;
  _selected?: boolean;
}

interface CanonicalFieldEntry {
  standard_name: string;
  description: string;
  category: string;
}

interface CanonicalFieldsData {
  finance: CanonicalFieldEntry[];
  production: CanonicalFieldEntry[];
  sales: CanonicalFieldEntry[];
  purchase: CanonicalFieldEntry[];
  inventory: CanonicalFieldEntry[];
  [key: string]: CanonicalFieldEntry[];
}

// ==================== 状态 ====================

const loadingItems = ref(false);
const loadingFields = ref(false);
const confirming = ref(false);

const pendingItems = ref<PendingItem[]>([]);
const canonicalFields = ref<CanonicalFieldsData | null>(null);

// 选中的行 ids (批量确认)
const selectedIds = ref<Set<number>>(new Set());

// ==================== 计算 ====================

// 按 upload_id 分组
const groupedByUpload = computed(() => {
  const groups: Record<number, PendingItem[]> = {};
  for (const item of pendingItems.value) {
    if (!groups[item.upload_id]) groups[item.upload_id] = [];
    groups[item.upload_id].push(item);
  }
  return groups;
});

const uploadIds = computed(() => Object.keys(groupedByUpload.value).map(Number));

// 选中项中已分配了 confirmed_standard 的
const confirmableSelected = computed(() =>
  pendingItems.value.filter(
    (it) => selectedIds.value.has(it.id) && it._confirmed_standard
  )
);

// el-select options: 按域分组
const canonicalOptionGroups = computed(() => {
  if (!canonicalFields.value) return [];
  const DOMAIN_LABELS: Record<string, string> = {
    finance: '财务',
    production: '生产',
    sales: '销售',
    purchase: '采购',
    inventory: '库存',
  };
  return Object.entries(canonicalFields.value).map(([domain, entries]) => ({
    label: DOMAIN_LABELS[domain] ?? domain,
    options: entries,
  }));
});

// ==================== 数据加载 ====================

async function fetchPending() {
  if (!factoryId.value) return;
  loadingItems.value = true;
  try {
    const res = await get<PendingItem[]>(
      `/${factoryId.value}/smart-bi/mapping-review/pending?limit=200`
    );
    // interceptor unwraps { success, data, message } → data (or throws on HTTP err)
    // For business success=false the interceptor may not throw, handle both shapes:
    const rows: PendingItem[] = Array.isArray(res) ? res : (res as any)?.data ?? [];
    pendingItems.value = rows.map((r) => ({
      ...r,
      _confirmed_standard: r.detected_standard ?? undefined,
      _selected: false,
    }));
    selectedIds.value.clear();
  } catch (err: unknown) {
    // HTTP errors: interceptor already showed sticky toast; nothing more needed.
    // Business-level errors surface as thrown ApiError with message.
    const msg = (err as any)?.message ?? '加载待复核队列失败';
    ElMessage.error(msg);
  } finally {
    loadingItems.value = false;
  }
}

async function fetchCanonicalFields() {
  if (!factoryId.value) return;
  loadingFields.value = true;
  try {
    const res = await get<CanonicalFieldsData>(
      `/${factoryId.value}/smart-bi/mapping-review/canonical-fields`
    );
    canonicalFields.value = Array.isArray(res) ? null : (res as any)?.data ?? res as any;
  } catch (err: unknown) {
    const msg = (err as any)?.message ?? '加载字段字典失败';
    ElMessage.error(msg);
  } finally {
    loadingFields.value = false;
  }
}

onMounted(async () => {
  await Promise.all([fetchPending(), fetchCanonicalFields()]);
});

// ==================== 行选择 ====================

function toggleRow(item: PendingItem) {
  if (selectedIds.value.has(item.id)) {
    selectedIds.value.delete(item.id);
  } else {
    selectedIds.value.add(item.id);
  }
  // trigger reactivity
  selectedIds.value = new Set(selectedIds.value);
}

function toggleAll(uploadId: number) {
  const group = groupedByUpload.value[uploadId] ?? [];
  const allSelected = group.every((it) => selectedIds.value.has(it.id));
  if (allSelected) {
    group.forEach((it) => selectedIds.value.delete(it.id));
  } else {
    group.forEach((it) => selectedIds.value.add(it.id));
  }
  selectedIds.value = new Set(selectedIds.value);
}

// ==================== 确认操作 ====================

async function confirmSelected() {
  const toConfirm = confirmableSelected.value;
  if (toConfirm.length === 0) {
    ElMessage.warning('请先选择行并选择确认字段');
    return;
  }

  // Group by upload_id (POST is per-upload)
  const byUpload: Record<number, typeof toConfirm> = {};
  for (const it of toConfirm) {
    if (!byUpload[it.upload_id]) byUpload[it.upload_id] = [];
    byUpload[it.upload_id].push(it);
  }

  await ElMessageBox.confirm(
    `确认将 ${toConfirm.length} 个列映射提交？确认后将写入 pin，同模板同列永久采用该映射。`,
    '确认字段映射',
    { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
  );

  confirming.value = true;
  let totalConfirmed = 0;
  const errors: string[] = [];

  for (const [uploadIdStr, items] of Object.entries(byUpload)) {
    const uploadId = Number(uploadIdStr);
    try {
      const res = await post(
        `/${factoryId.value}/smart-bi/mapping-review/${uploadId}/confirm`,
        {
          items: items.map((it) => ({
            column_name: it.column_name,
            confirmed_standard: it._confirmed_standard,
            should_pin: true,
          })),
        }
      );
      const confirmed: number = (res as any)?.data?.confirmed ?? (res as any)?.confirmed ?? 0;
      totalConfirmed += confirmed;
    } catch (err: unknown) {
      const msg = (err as any)?.message ?? `上传 ${uploadId} 确认失败`;
      errors.push(msg);
      // interceptor already showed sticky error toast; accumulate for summary
    }
  }

  confirming.value = false;

  if (totalConfirmed > 0) {
    ElMessage.success(`已确认 ${totalConfirmed} 个字段映射并写入 pin`);
    // Remove confirmed rows from local list (idempotency: they're CONFIRMED now)
    const confirmedColumnsByUpload: Record<number, Set<string>> = {};
    for (const it of toConfirm) {
      if (!confirmedColumnsByUpload[it.upload_id]) confirmedColumnsByUpload[it.upload_id] = new Set();
      confirmedColumnsByUpload[it.upload_id].add(it.column_name);
    }
    pendingItems.value = pendingItems.value.filter(
      (it) => !(confirmedColumnsByUpload[it.upload_id]?.has(it.column_name))
    );
    selectedIds.value.clear();
  }

  if (errors.length > 0 && totalConfirmed === 0) {
    // All failed — errors already shown by interceptor sticky toasts
    ElMessage({
      message: `${errors.length} 个上传批次确认失败，请查看上方错误提示`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

// ==================== 辅助 ====================

function confidenceLabel(conf: number | null): string {
  if (conf === null) return '—';
  return `${(conf * 100).toFixed(0)}%`;
}

function confidenceType(conf: number | null): 'success' | 'warning' | 'danger' | 'info' {
  if (conf === null) return 'info';
  if (conf >= 0.8) return 'success';
  if (conf >= 0.5) return 'warning';
  return 'danger';
}
</script>

<template>
  <div class="mapping-review-queue">
    <!-- 标题栏 -->
    <div class="page-header">
      <div class="header-left">
        <h2>字段映射复核</h2>
        <span class="subtitle">
          Excel 上传时系统无法确定映射的列，在此由人工确认 canonical 字段并写入确定性 pin
        </span>
      </div>
      <div class="header-actions">
        <el-button
          :icon="Refresh"
          :loading="loadingItems"
          @click="fetchPending"
        >
          刷新
        </el-button>
        <el-button
          type="primary"
          :icon="Check"
          :loading="confirming"
          :disabled="confirmableSelected.length === 0"
          @click="confirmSelected"
        >
          确认选中 ({{ confirmableSelected.length }})
        </el-button>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loadingItems" class="loading-wrapper">
      <el-skeleton :rows="4" animated />
    </div>

    <!-- 空状态 (Rule 5) -->
    <el-empty
      v-else-if="pendingItems.length === 0"
      description="✅ 暂无待复核列映射"
      :image-size="120"
    >
      <template #description>
        <p class="empty-desc">✅ 暂无待复核列映射</p>
        <p class="empty-sub">所有上传列均已成功映射到受控字典，或尚未上传 Excel 文件</p>
      </template>
    </el-empty>

    <!-- 按 upload 分组展示 -->
    <div v-else class="groups-wrapper">
      <el-card
        v-for="uploadId in uploadIds"
        :key="uploadId"
        class="upload-group"
        shadow="never"
      >
        <!-- 分组标题: Rule 2 — 上下文 -->
        <template #header>
          <div class="group-header">
            <div class="group-title">
              <el-tag type="info" size="small">Upload {{ uploadId }}</el-tag>
              <span class="template-key">
                模板: {{ groupedByUpload[uploadId]?.[0]?.template_key ?? '—' }}
              </span>
              <span class="row-count">
                {{ groupedByUpload[uploadId]?.length ?? 0 }} 列待复核
              </span>
            </div>
            <el-button
              size="small"
              text
              @click="toggleAll(uploadId)"
            >
              全选/取消
            </el-button>
          </div>
        </template>

        <!-- 表格 -->
        <el-table
          :data="groupedByUpload[uploadId]"
          size="small"
          row-key="id"
        >
          <!-- 多选列 -->
          <el-table-column width="48">
            <template #default="{ row }">
              <el-checkbox
                :model-value="selectedIds.has(row.id)"
                @change="toggleRow(row)"
              />
            </template>
          </el-table-column>

          <!-- Rule 2: 上下文 — 原始列名 -->
          <el-table-column prop="column_name" label="原始列名" min-width="140">
            <template #default="{ row }">
              <span class="column-name">{{ row.column_name }}</span>
            </template>
          </el-table-column>

          <!-- Rule 2: 系统检测到的猜测 -->
          <el-table-column label="系统猜测" min-width="150">
            <template #default="{ row }">
              <div class="detection-info">
                <el-tag
                  v-if="row.detected_standard"
                  size="small"
                  type="info"
                >
                  {{ row.detected_standard }}
                </el-tag>
                <span v-else class="no-detection">未检测到</span>
                <el-tag
                  size="small"
                  :type="confidenceType(row.detected_confidence)"
                  class="conf-tag"
                >
                  {{ confidenceLabel(row.detected_confidence) }}
                </el-tag>
              </div>
            </template>
          </el-table-column>

          <!-- Rule 2: 检测方式 -->
          <el-table-column prop="detected_method" label="方式" width="90">
            <template #default="{ row }">
              <span class="method-label">{{ row.detected_method ?? '—' }}</span>
            </template>
          </el-table-column>

          <!-- Rule 3: 人工确认字段 — el-select + el-option-group, 禁自由文本 -->
          <el-table-column label="确认 canonical 字段" min-width="220">
            <template #default="{ row }">
              <el-select
                v-model="row._confirmed_standard"
                placeholder="选择字段..."
                filterable
                clearable
                size="small"
                style="width: 100%"
                :loading="loadingFields"
              >
                <el-option-group
                  v-for="group in canonicalOptionGroups"
                  :key="group.label"
                  :label="group.label"
                >
                  <el-option
                    v-for="field in group.options"
                    :key="field.standard_name"
                    :label="field.standard_name"
                    :value="field.standard_name"
                  >
                    <div class="field-option">
                      <span class="field-name">{{ field.standard_name }}</span>
                      <span class="field-desc">{{ field.description }}</span>
                      <el-tag size="small" type="info" class="field-cat">{{ field.category }}</el-tag>
                    </div>
                  </el-option>
                </el-option-group>
              </el-select>
            </template>
          </el-table-column>

          <!-- 上传时间 -->
          <el-table-column label="进队时间" width="130">
            <template #default="{ row }">
              <span class="time-label">
                {{ row.created_at ? new Date(row.created_at).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 底部操作栏 (当有选中且有字段时浮现) -->
    <div v-if="confirmableSelected.length > 0" class="sticky-action-bar">
      <el-icon :size="16"><InfoFilled /></el-icon>
      已选 {{ selectedIds.size }} 行，其中 {{ confirmableSelected.length }} 行已选择 canonical 字段
      <el-button
        type="primary"
        size="small"
        :loading="confirming"
        style="margin-left: 12px"
        @click="confirmSelected"
      >
        确认并写入 pin
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.mapping-review-queue {
  padding: 20px;
  min-height: 400px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 16px;
}

.header-left h2 {
  margin: 0 0 4px 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.subtitle {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.loading-wrapper {
  padding: 20px;
}

.empty-desc {
  font-size: 16px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  margin: 0;
}

.empty-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.groups-wrapper {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 80px; /* space for sticky bar */
}

.upload-group {
  border: 1px solid var(--el-border-color-light);
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.template-key {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.row-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.column-name {
  font-weight: 500;
  font-family: var(--el-font-family-mono, monospace);
}

.detection-info {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.no-detection {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.conf-tag {
  font-size: 11px;
}

.method-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.field-option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.field-name {
  font-weight: 500;
  flex-shrink: 0;
}

.field-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-cat {
  flex-shrink: 0;
  font-size: 10px;
}

.time-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sticky-action-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-light);
  padding: 10px 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  z-index: 100;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.06);
}
</style>
