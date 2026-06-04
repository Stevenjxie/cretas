<!--
  菜品名称匹配 — POS 菜品名称解析回填 admin 裁决页 (#61 Phase 1).

  URL: /restaurant/admin/name-resolution
  Roles: factory_super_admin | platform_admin | permission_admin

  解锁 #57 成本卡片准确性: POS 导出菜名未匹配 cretas product_type → 财务 ETL 无法
  为该菜写 COGS 行 → 利润被高估。本页让 admin 一键确认未解析菜名的绑定，确认后
  自动重跑财务 ETL 更新成本卡片。

  Uses pythonFetch (snake→camel auto-convert). No `as any`.
  防呆 (fool-proof-design): Rule 1 (确认后明示自动重跑 ~30s)、Rule 2 (上下文带营收/出现次数)、
  低置信度 warning badge、确认即触发 ETL 不让用户事后困惑。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import {
  fetchUnresolved,
  fetchCoverageStats,
  confirmBinding,
  rejectBinding,
  skipBinding,
  runBackfill,
  fetchProductTypes,
  type UnresolvedItem,
  type CoverageStats,
  type ProductTypeOption,
} from '@/api/restaurant/name-resolution';

// ── State ───────────────────────────────────────────────────────────────
const loading = ref<boolean>(false);
const backfilling = ref<boolean>(false);
const items = ref<UnresolvedItem[]>([]);
const stats = ref<CoverageStats>({ matched: 0, total: 0, coveragePct: 0 });
const productTypes = ref<ProductTypeOption[]>([]);
// per-row selected product_type for confirm (keyed by posName; defaults to best candidate)
const selectedPt = ref<Record<string, string>>({});
const rowBusy = ref<Record<string, boolean>>({});
const multiSelected = ref<UnresolvedItem[]>([]);

const LOW_CONF_THRESHOLD = 0.7;

// ── Helpers ──────────────────────────────────────────────────────────────
function ptName(id: string | null | undefined): string {
  if (!id) return '';
  return productTypes.value.find((p) => p.id === id)?.name ?? id;
}

function confidencePct(c: number | null): string {
  return c === null || c === undefined ? '—' : `${(c * 100).toFixed(0)}%`;
}

function isLowConfidence(c: number | null): boolean {
  return c !== null && c !== undefined && c < LOW_CONF_THRESHOLD;
}

function showError(message: string): void {
  // 4位一体: error toast sticky + showClose + 原样显示后端 message
  ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

// ── Data loaders ───────────────────────────────────────────────────────
async function loadAll(): Promise<void> {
  loading.value = true;
  try {
    const [uRes, sRes, pts] = await Promise.all([
      fetchUnresolved(),
      fetchCoverageStats(),
      fetchProductTypes(),
    ]);
    items.value = uRes?.data?.items ?? [];
    stats.value = sRes?.data ?? { matched: 0, total: 0, coveragePct: 0 };
    productTypes.value = pts ?? [];
    // Default each row's confirm selection to its best candidate (one-click confirm).
    const next: Record<string, string> = {};
    for (const it of items.value) {
      if (it.bestCandidateId) next[it.posName] = it.bestCandidateId;
    }
    selectedPt.value = next;
  } catch (e) {
    showError(`加载未解析队列失败: ${(e as Error)?.message ?? e}`);
  } finally {
    loading.value = false;
  }
}

async function doBackfill(): Promise<void> {
  backfilling.value = true;
  try {
    const res = await runBackfill();
    const d = res?.data;
    ElMessage.success(
      `回填完成: 共 ${d?.totalPosNames ?? 0} 个菜名, 已匹配 ${d?.alreadyResolved ?? 0}, ` +
        `自动绑定 ${d?.resolvedAuto ?? 0}, 待裁决 ${d?.queued ?? 0}`,
    );
    await loadAll();
  } catch (e) {
    showError(`运行回填失败: ${(e as Error)?.message ?? e}`);
  } finally {
    backfilling.value = false;
  }
}

// ── Row actions ──────────────────────────────────────────────────────────
async function doConfirm(row: UnresolvedItem): Promise<void> {
  const ptId = selectedPt.value[row.posName];
  if (!ptId) {
    showError(`请先为「${row.displayName || row.posName}」选择要绑定的菜品`);
    return;
  }
  rowBusy.value[row.posName] = true;
  try {
    const res = await confirmBinding(row.posName, ptId);
    // Rule 1: 明示确认后自动重跑 ETL ~30s (来自后端 message)
    ElMessage.success(res?.message || '已绑定。后台正在重跑财务 ETL (约 30 秒) 更新成本卡片。');
    items.value = items.value.filter((i) => i.posName !== row.posName);
    void refreshStats();
  } catch (e) {
    showError(`确认绑定失败: ${(e as Error)?.message ?? e}`);
  } finally {
    rowBusy.value[row.posName] = false;
  }
}

async function doReject(row: UnresolvedItem): Promise<void> {
  rowBusy.value[row.posName] = true;
  try {
    await rejectBinding(row.posName);
    ElMessage.success(`已拒绝「${row.displayName || row.posName}」`);
    items.value = items.value.filter((i) => i.posName !== row.posName);
  } catch (e) {
    showError(`拒绝失败: ${(e as Error)?.message ?? e}`);
  } finally {
    rowBusy.value[row.posName] = false;
  }
}

async function doSkip(row: UnresolvedItem): Promise<void> {
  rowBusy.value[row.posName] = true;
  try {
    await skipBinding(row.posName);
    ElMessage.success(`已跳过「${row.displayName || row.posName}」`);
    items.value = items.value.filter((i) => i.posName !== row.posName);
  } catch (e) {
    showError(`跳过失败: ${(e as Error)?.message ?? e}`);
  } finally {
    rowBusy.value[row.posName] = false;
  }
}

async function bulkConfirm(): Promise<void> {
  const rows = multiSelected.value.filter((r) => selectedPt.value[r.posName]);
  if (rows.length === 0) {
    showError('请先勾选有候选菜品的行 (无候选的行需手动选择菜品后确认)');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将批量确认 ${rows.length} 个菜名绑定到其建议菜品, 确认后自动重跑财务 ETL (约 30 秒)。是否继续?`,
      '批量确认',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    );
  } catch {
    return; // user cancelled
  }
  let ok = 0;
  for (const r of rows) {
    try {
      await confirmBinding(r.posName, selectedPt.value[r.posName]);
      ok += 1;
    } catch {
      // continue; surface aggregate below
    }
  }
  ElMessage.success(`批量确认完成: ${ok}/${rows.length} 成功。后台正在重跑财务 ETL。`);
  await loadAll();
}

async function refreshStats(): Promise<void> {
  try {
    const sRes = await fetchCoverageStats();
    stats.value = sRes?.data ?? stats.value;
  } catch {
    // non-fatal
  }
}

function onSelectionChange(rows: UnresolvedItem[]): void {
  multiSelected.value = rows;
}

const pendingCount = computed(() => items.value.length);

onMounted(loadAll);
</script>

<template>
  <div class="name-resolution-page">
    <div class="page-header">
      <div>
        <h2 style="margin: 0">菜品名称匹配</h2>
        <p class="subtitle">
          POS 导出菜名未匹配到菜品时, 财务 ETL 无法计算该菜的食材成本 → 利润被高估。
          确认绑定后将自动重跑财务 ETL, 修正成本卡片。
        </p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button type="primary" :loading="backfilling" @click="doBackfill">
          运行回填 (重新扫描)
        </el-button>
      </div>
    </div>

    <!-- KPI 卡 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col :span="8">
        <el-card shadow="never">
          <div class="kpi-label">已匹配 / 总菜名</div>
          <div class="kpi-value">{{ stats.matched }} / {{ stats.total }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="kpi-label">匹配覆盖率</div>
          <div class="kpi-value" :class="{ warn: stats.coveragePct < 80 }">
            {{ stats.coveragePct }}%
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="kpi-label">待裁决</div>
          <div class="kpi-value" :class="{ warn: pendingCount > 0 }">{{ pendingCount }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="pendingCount > 0"
      type="warning"
      :closable="false"
      show-icon
      class="hint-alert"
      title="按营收风险敞口降序排列, 优先确认高营收菜名 — 它们对成本卡片准确性影响最大。"
    />

    <el-table
      :data="items"
      v-loading="loading"
      border
      stripe
      empty-text="暂无待裁决菜名 — 全部已匹配或已处理"
      style="width: 100%"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="POS 菜名" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.displayName || row.posName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="出现次数" prop="occurrenceCount" width="90" align="right" />
      <el-table-column label="营收风险敞口" width="130" align="right">
        <template #default="{ row }">
          ¥{{ Number(row.revenueAtRisk || 0).toLocaleString() }}
        </template>
      </el-table-column>
      <el-table-column label="系统建议置信度" width="120" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.bestConfidence === null" type="info" size="small">无建议</el-tag>
          <el-badge
            v-else
            :value="isLowConfidence(row.bestConfidence) ? '低' : ''"
            :type="isLowConfidence(row.bestConfidence) ? 'warning' : 'success'"
          >
            <span>{{ confidencePct(row.bestConfidence) }}</span>
          </el-badge>
        </template>
      </el-table-column>
      <el-table-column label="绑定到菜品" min-width="220">
        <template #default="{ row }">
          <el-select
            v-model="selectedPt[row.posName]"
            filterable
            placeholder="选择菜品"
            size="small"
            style="width: 100%"
          >
            <el-option
              v-for="p in productTypes"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right" align="center">
        <template #default="{ row }">
          <el-button
            type="primary"
            size="small"
            :loading="rowBusy[row.posName]"
            @click="doConfirm(row)"
          >
            确认
          </el-button>
          <el-button size="small" :loading="rowBusy[row.posName]" @click="doSkip(row)">
            跳过
          </el-button>
          <el-button
            type="danger"
            plain
            size="small"
            :loading="rowBusy[row.posName]"
            @click="doReject(row)"
          >
            拒绝
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="bulk-bar" v-if="pendingCount > 0">
      <el-button type="success" :disabled="multiSelected.length === 0" @click="bulkConfirm">
        批量确认选中 ({{ multiSelected.length }})
      </el-button>
      <span class="bulk-hint">勾选有建议菜品的行后批量确认; 确认后自动重跑财务 ETL。</span>
    </div>
  </div>
</template>

<style scoped>
.name-resolution-page {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.subtitle {
  color: #909399;
  font-size: 13px;
  margin: 6px 0 0;
  max-width: 720px;
}
.header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.kpi-row {
  margin-bottom: 12px;
}
.kpi-label {
  color: #909399;
  font-size: 13px;
}
.kpi-value {
  font-size: 26px;
  font-weight: 600;
  margin-top: 4px;
}
.kpi-value.warn {
  color: #e6a23c;
}
.hint-alert {
  margin-bottom: 12px;
}
.bulk-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.bulk-hint {
  color: #909399;
  font-size: 12px;
}
</style>
