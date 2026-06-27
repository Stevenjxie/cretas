<script setup lang="ts">
/**
 * PendingQueue.vue — 未完成生产计划队列 (Clerk View)
 *
 * 显示所有 PENDING 和 IN_PROGRESS 状态的生产计划，按计划日期升序（最紧迫的排最前）。
 * 设计原则：防呆 (fool-proof) — 文员只需看"还差什么"，不需要理解状态机。
 *
 * 数据来源：直接调用现有分页端点两次（PENDING + IN_PROGRESS），前端合并排序。
 * 后端无需任何修改。
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, Search, WarningFilled } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ==================== Data ====================
const loading = ref(false);
const planRows = ref<TableRow[]>([]);
const lastRefreshedAt = ref<string>('');

// ==================== Filter ====================
const keyword = ref('');

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return planRows.value;
  return planRows.value.filter((row) => {
    return (
      String(row.planNumber || '').toLowerCase().includes(kw) ||
      String(row.productTypeName || row.productName || '').toLowerCase().includes(kw) ||
      String(row.sourceCustomerName || '').toLowerCase().includes(kw)
    );
  });
});

// ==================== Load ====================
async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // Fetch PENDING and IN_PROGRESS in parallel using the existing list endpoint
    // Each call uses sortBy=plannedDate ASC so results come pre-sorted per group
    const [pendingRes, inProgressRes] = await Promise.all([
      get(`/${factoryId.value}/production-plans`, {
        params: {
          page: 1,
          size: 200,
          status: 'PENDING',
          sortBy: 'plannedDate',
          sortDirection: 'ASC',
        },
      }),
      get(`/${factoryId.value}/production-plans`, {
        params: {
          page: 1,
          size: 200,
          status: 'IN_PROGRESS',
          sortBy: 'plannedDate',
          sortDirection: 'ASC',
        },
      }),
    ]);

    const pendingList: TableRow[] =
      pendingRes.success && pendingRes.data
        ? pendingRes.data.content || []
        : [];
    const inProgressList: TableRow[] =
      inProgressRes.success && inProgressRes.data
        ? inProgressRes.data.content || []
        : [];

    // Merge then sort by plannedDate ascending (nulls last)
    const merged = [...pendingList, ...inProgressList];
    merged.sort((a, b) => {
      const da = String(a.plannedDate || '');
      const db = String(b.plannedDate || '');
      if (!da && !db) return 0;
      if (!da) return 1; // null dates go last
      if (!db) return -1;
      return da < db ? -1 : da > db ? 1 : 0;
    });

    planRows.value = merged;
    lastRefreshedAt.value = new Date().toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch (e) {
    console.error('[未完成队列] 加载失败', e);
    ElMessage({ message: '加载未完成计划失败', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

onMounted(() => loadData());

// ==================== Status helpers ====================
function getStatusType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, '' | 'success' | 'warning' | 'info' | 'danger'> = {
    PENDING: 'info',
    PLANNED: 'info',
    PREPARED: 'info',
    IN_PROGRESS: 'warning',
  };
  return map[String(status).toUpperCase()] ?? 'info';
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待执行',
    PLANNED: '待执行',
    PREPARED: '草稿',
    IN_PROGRESS: '进行中',
  };
  return map[String(status).toUpperCase()] ?? status;
}

// ==================== Urgency / Shortage ====================

/**
 * 返回日期紧迫性级别:
 *  'overdue'  — 计划日期已过期
 *  'urgent'   — 今日或明日到期
 *  'soon'     — 3天内到期
 *  'normal'   — 4天以上
 */
function getDateUrgency(
  plannedDate: unknown
): 'overdue' | 'urgent' | 'soon' | 'normal' | 'unknown' {
  if (!plannedDate) return 'unknown';
  const target = new Date(String(plannedDate));
  if (isNaN(target.getTime())) return 'unknown';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  target.setHours(0, 0, 0, 0);
  const diffDays = Math.floor(
    (target.getTime() - today.getTime()) / (1000 * 60 * 60 * 24)
  );
  if (diffDays < 0) return 'overdue';
  if (diffDays <= 1) return 'urgent';
  if (diffDays <= 3) return 'soon';
  return 'normal';
}

function getDateUrgencyTag(row: TableRow): {
  text: string;
  type: '' | 'success' | 'warning' | 'info' | 'danger';
  tooltip: string;
} {
  const urgency = getDateUrgency(row.plannedDate);
  switch (urgency) {
    case 'overdue':
      return {
        text: '已超期',
        type: 'danger',
        tooltip: `计划日 ${row.plannedDate} 已过期，请尽快处理`,
      };
    case 'urgent':
      return {
        text: '今明到期',
        type: 'danger',
        tooltip: `计划日 ${row.plannedDate} 极度紧迫`,
      };
    case 'soon':
      return {
        text: '3天内',
        type: 'warning',
        tooltip: `计划日 ${row.plannedDate} 即将到期`,
      };
    case 'normal':
      return { text: '', type: 'info', tooltip: '' };
    default:
      return { text: '', type: 'info', tooltip: '' };
  }
}

/**
 * 是否有开工阻断风险 (N1 后端已放行警告，但前端仍提示文员关注)
 * 使用 isUrgent 字段（CR < 1）作为代理指标; 真实缺料需看 transfer 记录
 */
function hasMaterialRisk(row: TableRow): boolean {
  // isUrgent: CR < 1 = 期望完工时间 < 工期，通常伴随资源压力
  return row.isUrgent === true;
}

// ==================== Counts ====================
const pendingCount = computed(
  () => planRows.value.filter((r) => String(r.status).toUpperCase() === 'PENDING').length
);
const inProgressCount = computed(
  () =>
    planRows.value.filter((r) => String(r.status).toUpperCase() === 'IN_PROGRESS').length
);
const overdueCount = computed(
  () =>
    planRows.value.filter((r) => getDateUrgency(r.plannedDate) === 'overdue').length
);

// ==================== Navigation ====================
function goToPlanList() {
  router.push('/production/plans');
}
</script>

<template>
  <div class="pending-queue-page">
    <!-- Header -->
    <div class="queue-header">
      <div class="queue-header-left">
        <h2 class="queue-title">未完成计划队列</h2>
        <span class="queue-subtitle">按计划日期升序 — 最紧迫排最前</span>
      </div>
      <div class="queue-header-right">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="info" link @click="goToPlanList">→ 返回完整计划列表</el-button>
      </div>
    </div>

    <!-- Summary badges -->
    <div class="summary-row">
      <el-tag type="info" size="large" effect="plain">
        待执行 <strong>{{ pendingCount }}</strong>
      </el-tag>
      <el-tag type="warning" size="large" effect="plain">
        进行中 <strong>{{ inProgressCount }}</strong>
      </el-tag>
      <el-tag v-if="overdueCount > 0" type="danger" size="large" effect="dark">
        ⚠ 已超期 {{ overdueCount }}
      </el-tag>
      <span v-if="lastRefreshedAt" class="refresh-time">上次刷新 {{ lastRefreshedAt }}</span>
    </div>

    <!-- Search filter -->
    <div class="filter-row">
      <el-input
        v-model="keyword"
        placeholder="按计划编号 / 产品名称 / 客户筛选"
        :prefix-icon="Search"
        clearable
        style="width: 320px"
      />
      <span class="filter-count">
        共 <strong>{{ filteredRows.length }}</strong> 条未完成计划
      </span>
    </div>

    <!-- Table -->
    <el-table
      :data="filteredRows"
      v-loading="loading"
      empty-text="暂无未完成计划"
      stripe
      border
      style="width: 100%"
      :row-class-name="
        ({ row }: { row: { plannedDate?: string } }) => {
          const u = getDateUrgency(row.plannedDate);
          if (u === 'overdue') return 'row-overdue';
          if (u === 'urgent') return 'row-urgent';
          return '';
        }
      "
    >
      <!-- 计划编号 -->
      <el-table-column prop="planNumber" label="计划编号" width="160" fixed="left" />

      <!-- 产品 -->
      <el-table-column label="产品" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.productTypeName || row.productName || row.productTypeId || '-' }}
        </template>
      </el-table-column>

      <!-- 客户 -->
      <el-table-column
        prop="sourceCustomerName"
        label="客户"
        min-width="120"
        show-overflow-tooltip
      />

      <!-- 计划数量 -->
      <el-table-column
        prop="plannedQuantity"
        label="计划量"
        width="100"
        align="right"
      />

      <!-- 计划日期 + 紧迫度 -->
      <el-table-column label="计划日期" width="160" align="center">
        <template #default="{ row }">
          <div class="date-cell">
            <span>{{ row.plannedDate || '-' }}</span>
            <el-tooltip
              v-if="getDateUrgencyTag(row).text"
              :content="getDateUrgencyTag(row).tooltip"
              placement="top"
            >
              <el-tag
                :type="getDateUrgencyTag(row).type"
                size="small"
                style="margin-left: 4px"
              >{{ getDateUrgencyTag(row).text }}</el-tag>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>

      <!-- 状态 -->
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)" size="small">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>

      <!-- 报工模式 -->
      <el-table-column label="报工模式" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.skipProcessReporting === false"
            type="warning"
            size="small"
            title="逐道工序报工"
          >逐道</el-tag>
          <el-tag v-else type="info" size="small" title="两点报工（领料+产出）">
            免工序
          </el-tag>
        </template>
      </el-table-column>

      <!-- 物料风险 -->
      <el-table-column label="物料" width="90" align="center">
        <template #default="{ row }">
          <el-tooltip
            v-if="hasMaterialRisk(row)"
            content="CR值 < 1：排程紧张，建议确认原料到位"
            placement="top"
          >
            <el-tag type="danger" size="small" effect="plain">
              <el-icon><WarningFilled /></el-icon>
              排程紧
            </el-tag>
          </el-tooltip>
          <span v-else class="material-ok">✓</span>
        </template>
      </el-table-column>

      <!-- 指派主管 -->
      <el-table-column
        prop="assignedSupervisorName"
        label="主管"
        width="90"
        show-overflow-tooltip
      />

      <!-- 来源 -->
      <el-table-column label="来源" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="String(row.planNumber || '').startsWith('SEC-')"
            type="warning"
            size="small"
          >二次加工</el-tag>
          <el-tag
            v-else-if="row.sourceType === 'CUSTOMER_ORDER'"
            type="primary"
            size="small"
          >销售单</el-tag>
          <el-tag v-else-if="row.sourceType === 'AI_FORECAST'" type="success" size="small">
            AI预测
          </el-tag>
          <el-tag v-else size="small">手动</el-tag>
        </template>
      </el-table-column>

      <!-- 操作 -->
      <el-table-column label="操作" width="100" fixed="right" align="center">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="router.push(`/production/plans?highlight=${row.id}`)"
          >
            查看计划
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Empty hint -->
    <div v-if="!loading && filteredRows.length === 0" class="empty-hint">
      <el-empty description="暂无未完成计划" :image-size="80">
        <el-button type="primary" @click="goToPlanList">去查看完整计划列表</el-button>
      </el-empty>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.pending-queue-page {
  padding: 16px 20px;
  min-height: 100%;
  background: var(--el-bg-color-page, #f2f3f5);
}

.queue-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;

  .queue-header-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.queue-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--el-text-color-primary, #303133);
}

.queue-subtitle {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 2px;
}

.summary-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.refresh-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder, #c0c4cc);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-count {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}

// Row overdue / urgent highlighting
:deep(.row-overdue) {
  background-color: rgba(245, 108, 108, 0.08) !important;
}

:deep(.row-urgent) {
  background-color: rgba(230, 162, 60, 0.08) !important;
}

.date-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px;
}

.material-ok {
  color: var(--el-color-success, #67c23a);
  font-size: 14px;
}

.empty-hint {
  margin-top: 32px;
  display: flex;
  justify-content: center;
}
</style>
