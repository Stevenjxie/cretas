<!--
  餐饮 ETL 状态管理页 — Task 1.5 (Restaurant Phase A-1).

  URL: /restaurant/admin/etl-status
  Roles: factory_super_admin | platform_admin | permission_admin

  Displays per-factory ETL job status across all registered restaurant
  factories. Supports manual trigger with polling until job completes.
  Failure logs shown in an el-dialog per factory row.

  Uses pythonFetch (W0.4 finding 6 — snake→camel auto-convert).
  No `as any` — all types defined in @/api/restaurant/etl-admin.
-->
<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import {
  fetchAllEtlStatus,
  fetchEtlStatus,
  triggerEtl,
  type AllStatusFactory,
  type EtlFailure,
  type EtlStatusResponse,
} from '@/api/restaurant/etl-admin';
import { useAuthStore } from '@/store/modules/auth';

const auth = useAuthStore();

// ── State ─────────────────────────────────────────────────────────────

const loading = ref<boolean>(false);
const factories = ref<AllStatusFactory[]>([]);

// Per-factory status details (keyed by factoryId)
const statusMap = ref<Record<string, EtlStatusResponse>>({});

// Per-factory loading/trigger state
const triggeringMap = ref<Record<string, boolean>>({});

// Failure log dialog
const failureDialogVisible = ref<boolean>(false);
const failureDialogFactory = ref<string>('');
const failureDialogRows = ref<EtlFailure[]>([]);

// ── Helpers ────────────────────────────────────────────────────────────

function statusLabel(status: EtlStatusResponse['lastStatus'] | undefined): string {
  switch (status) {
 case 'success': return ' 成功';
 case 'failed': return ' 失败';
    case 'running': return '⏳ 运行中';
    case 'queued':  return '⏳ 排队中';
    case 'never_ran': return '— 未运行';
    default:        return '— 未知';
  }
}

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      hour12: false,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function isActiveSyncing(factoryId: string): boolean {
  const s = statusMap.value[factoryId]?.lastStatus;
  return s === 'running' || s === 'queued';
}

// ── Data loading ───────────────────────────────────────────────────────

async function loadAll(): Promise<void> {
  loading.value = true;
  try {
    // /all-status is platform_admin only — factory_super_admin / permission_admin
    // get 403 from this endpoint. Fall back to single-factory status using the
    // user's own factory_id from the auth store. This way factory admins can
    // still trigger ETL + see status for their own factory.
    let resp: { factories: Array<{ factoryId: string; factoryName?: string | null; lastSuccessRun: string | null }> };
    try {
      resp = await fetchAllEtlStatus();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      const isForbidden = /403|Forbidden/i.test(msg);
      const myFactory = auth.factoryId;
      if (isForbidden && myFactory) {
        // Single-factory fallback for factory_super_admin / permission_admin
        resp = { factories: [{ factoryId: myFactory, factoryName: myFactory, lastSuccessRun: null }] };
      } else {
        throw err;
      }
    }
    factories.value = resp.factories.map((f) => ({
      ...f,
      factoryName: f.factoryName || f.factoryId,
    }));
    // Load detailed status for each factory in parallel
    await Promise.allSettled(
      resp.factories.map(async (f) => {
        try {
          const detail = await fetchEtlStatus(f.factoryId);
          statusMap.value = { ...statusMap.value, [f.factoryId]: detail };
        } catch {
          // Detail fetch failure is non-fatal — summary row still shows
        }
      })
    );
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`加载 ETL 状态失败: ${msg}`);
  } finally {
    loading.value = false;
  }
}

async function refreshRow(factoryId: string): Promise<void> {
  triggeringMap.value = { ...triggeringMap.value, [factoryId]: true };
  try {
    const detail = await fetchEtlStatus(factoryId);
    statusMap.value = { ...statusMap.value, [factoryId]: detail };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`刷新失败: ${msg}`);
  } finally {
    triggeringMap.value = { ...triggeringMap.value, [factoryId]: false };
  }
}

// ── Trigger + poll ─────────────────────────────────────────────────────

async function handleTrigger(factoryId: string): Promise<void> {
  if (isActiveSyncing(factoryId)) return;

  triggeringMap.value = { ...triggeringMap.value, [factoryId]: true };
  try {
    await triggerEtl(factoryId);
    ElMessage.success(`ETL 已触发 (${factoryId})，正在轮询状态…`);
    // Immediately set optimistic status so button disables
    if (statusMap.value[factoryId]) {
      statusMap.value = {
        ...statusMap.value,
        [factoryId]: { ...statusMap.value[factoryId], lastStatus: 'queued' },
      };
    }
    pollUntilDone(factoryId);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`触发 ETL 失败 (${factoryId}): ${msg}`);
    triggeringMap.value = { ...triggeringMap.value, [factoryId]: false };
  }
}

// Track active polling intervals so onBeforeUnmount can clean them up.
// Without this, navigating away during a poll continues firing fetch
// requests against a destroyed component.
const activeIntervals = new Set<ReturnType<typeof setInterval>>();

function pollUntilDone(factoryId: string): void {
  const intervalId = setInterval(async () => {
    try {
      const detail = await fetchEtlStatus(factoryId);
      statusMap.value = { ...statusMap.value, [factoryId]: detail };
      if (detail.lastStatus !== 'running' && detail.lastStatus !== 'queued') {
        clearInterval(intervalId);
        activeIntervals.delete(intervalId);
        triggeringMap.value = { ...triggeringMap.value, [factoryId]: false };
        if (detail.lastStatus === 'success') {
          ElMessage.success(`${factoryId} ETL 同步完成`);
        } else if (detail.lastStatus === 'failed') {
          ElMessage.error(`${factoryId} ETL 同步失败，请查看失败日志`);
        }
      }
    } catch {
      clearInterval(intervalId);
      activeIntervals.delete(intervalId);
      triggeringMap.value = { ...triggeringMap.value, [factoryId]: false };
    }
  }, 5000);
  activeIntervals.add(intervalId);
}

// ── Failure log dialog ─────────────────────────────────────────────────

function openFailureLog(factoryId: string): void {
  const detail = statusMap.value[factoryId];
  failureDialogFactory.value = factoryId;
  failureDialogRows.value = detail?.recentFailures ?? [];
  failureDialogVisible.value = true;
}

// ── Lifecycle ──────────────────────────────────────────────────────────

onMounted(loadAll);

onBeforeUnmount(() => {
  activeIntervals.forEach(clearInterval);
  activeIntervals.clear();
});
</script>

<template>
  <div class="etl-status-page">
    <!-- Page header -->
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">餐饮 ETL 状态</span>
          <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新全部</el-button>
        </div>
      </template>

      <!-- Loading skeleton -->
      <el-skeleton v-if="loading && factories.length === 0" :rows="4" animated />

      <!-- Empty state -->
      <el-empty
        v-else-if="!loading && factories.length === 0"
        description="暂无餐饮工厂数据，请确认 all-status 接口返回正确"
      />

      <!-- Main table -->
      <el-table
        v-else
        :data="factories"
        stripe
        size="default"
        class="status-table"
        v-loading="loading"
      >
        <!-- Factory ID -->
        <el-table-column label="工厂 ID" prop="factoryId" width="130" />

        <!-- Factory name -->
        <el-table-column label="工厂名称" min-width="150">
          <template #default="{ row }">
            {{ statusMap[row.factoryId]?.factoryName ?? row.factoryName ?? '—' }}
          </template>
        </el-table-column>

        <!-- Last success run -->
        <el-table-column label="最近成功时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(statusMap[row.factoryId]?.lastSuccessRun ?? row.lastSuccessRun) }}
          </template>
        </el-table-column>

        <!-- Status with emoji -->
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <span class="status-text">{{ statusLabel(statusMap[row.factoryId]?.lastStatus) }}</span>
          </template>
        </el-table-column>

        <!-- Row counts summary -->
        <el-table-column label="数据行数" min-width="200">
          <template #default="{ row }">
            <template v-if="statusMap[row.factoryId]?.rowCounts">
              <span
                v-for="(count, table) in statusMap[row.factoryId].rowCounts"
                :key="table"
                class="row-count-chip"
              >
                {{ table }}: {{ count }}
              </span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>

        <!-- Actions -->
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <!-- Refresh single row -->
            <el-button
              size="small"
              :loading="triggeringMap[row.factoryId] && isActiveSyncing(row.factoryId)"
              @click="refreshRow(row.factoryId)"
            >
              刷新
            </el-button>

            <!-- Manual trigger -->
            <el-button
              size="small"
              type="primary"
              :disabled="isActiveSyncing(row.factoryId)"
              :loading="!isActiveSyncing(row.factoryId) && !!triggeringMap[row.factoryId]"
              @click="handleTrigger(row.factoryId)"
            >
              立即同步
            </el-button>

            <!-- Failure log — only visible when there are recent failures -->
            <el-button
              v-if="(statusMap[row.factoryId]?.recentFailures?.length ?? 0) > 0"
              size="small"
              type="danger"
              plain
              @click="openFailureLog(row.factoryId)"
            >
              失败日志
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Failure log dialog -->
    <el-dialog
      v-model="failureDialogVisible"
      :title="`失败日志 — ${failureDialogFactory}`"
      width="720px"
      destroy-on-close
    >
      <el-table :data="failureDialogRows" stripe size="small">
        <el-table-column label="时间" prop="runAt" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.runAt) }}</template>
        </el-table-column>
        <el-table-column label="重试次数" prop="attempt" width="90" align="center" />
        <el-table-column label="错误类型" prop="errorClass" min-width="160" />
        <el-table-column label="错误信息" prop="errorMessage" min-width="240" />
        <el-table-column label="状态" prop="status" min-width="120" />
        <el-table-column label="耗时(ms)" prop="durationMs" width="100" />
      </el-table>
      <template #footer>
        <el-button @click="failureDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.etl-status-page {
  padding: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.status-table {
  width: 100%;
}

.status-text {
  font-size: 13px;
}

.row-count-chip {
  display: inline-block;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 1px 6px;
  margin: 2px 4px 2px 0;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
</style>
