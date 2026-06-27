<script setup lang="ts">
/**
 * QHJ 收入管理报表 — 青花椒 / R_*_REAL restaurant chains.
 *
 * Spec: docs/qa-specs/2026-05-12-qhj-revenue-report-design.md §9 (Phase I)
 * Plan: docs/superpowers/plans/2026-05-12-qhj-revenue-report.md Task I2
 *
 * Single SFC matching project's mega-component norm (FinanceAnalysis 2949 LOC,
 * RestaurantV2Dashboard 2320 LOC). Block 1/2/3/4 preview tables + xlsx download
 * + audit log tab.
 *
 * Visible only to RESTAURANT-type factories (hideForFactoryTypes:['FACTORY']
 * in router meta + sidebar).
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElAlert } from 'element-plus';
import type { UploadUserFile } from 'element-plus';
import {
  uploadPosFiles,
  prepare,
  generateAndDownload,
  listStores,
  getAuditLog,
  type RevenueReportParams,
  type StoreEntry,
  type UploadResultItem,
  type AuditLogEntry,
} from '@/api/smartbi/revenue-report';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { useAuthStore } from '@/store/modules/auth';
import SmartBIUploader from '@/components/smartbi/SmartBIUploader.vue';

const authStore = useAuthStore();
const router = useRouter();
const activeTab = ref<'generate' | 'audit'>('generate');
const tenantLabel = computed(() => authStore.factoryId || '餐饮门店');

// ─── Stores list (loaded once at mount) ───────────────────────────────
const stores = ref<StoreEntry[]>([]);
const storesLoading = ref(false);

// ─── Filter form ──────────────────────────────────────────────────────
const dateRange = ref<[string, string] | null>(null);
const selectedStoreNames = ref<string[]>([]);
const selectedMealPeriods = ref<string[]>([]);

// localStorage key per spec §9 audit X — restore filters on tab switch
const filterStorageKey = computed(() =>
  `revenue-report-filters-${authStore.factoryId ?? 'anon'}`,
);

// ─── Upload state ─────────────────────────────────────────────────────
const uploadFiles = ref<UploadUserFile[]>([]);
const uploading = ref(false);
const uploadResults = ref<UploadResultItem[]>([]);
const uploadPurposeDialogVisible = ref(false);
const uploadPurpose = ref<'pos' | 'supplier-delivery' | 'finance-excel' | 'review-data'>('pos');

const uploadPurposeOptions = [
  {
    value: 'pos',
    title: 'POS 销售/收入流水',
    desc: '二维火详细日报表、营业概况、堂食外卖占比、商品销售明细等。',
    action: '继续上传并生成收入报表',
  },
  {
    value: 'supplier-delivery',
    title: '供应商送货/采购入库单',
    desc: '供应商、送货日期、食材、规格、数量、单价等入库数据。',
    action: '前往供应商进货录入',
  },
  {
    value: 'finance-excel',
    title: '财务费用/银行流水',
    desc: '费用、收入、成本、银行流水、损益类 Excel。',
    action: '前往通用 Excel 上传',
  },
  {
    value: 'review-data',
    title: '顾客评价/平台口碑',
    desc: '大众点评、美团评价、评分、评论文本等口碑数据。',
    action: '前往数据完整度查看缺口',
  },
] as const;

const selectedUploadPurpose = computed(() =>
  uploadPurposeOptions.find((item) => item.value === uploadPurpose.value) ?? uploadPurposeOptions[0],
);

// ─── Generation state ─────────────────────────────────────────────────
const generating = ref(false);
const oneClickLoading = ref(false);
const elapsedSec = ref(0);
let elapsedTimer: number | null = null;
const lastDownloadInfo = ref<{
  filename: string;
  cacheHit: boolean;
  storeCount: number;
  goldMaterializedAt: string;
  isStale: boolean;
} | null>(null);

// ─── Preview (block previews after /prepare) ──────────────────────────
const previewSummary = ref<{
  store_count: number;
  date_range: string;
  gold_materialized_at: string;
  file_size_bytes: number;
  cache_hit: boolean;
  is_stale: boolean;
} | null>(null);

interface PreviewBlockRow { [key: string]: unknown }
const previewBlocks = ref<{
  block1_yoy: PreviewBlockRow[];
  block2_mom: PreviewBlockRow[];
  block3_meal_split: PreviewBlockRow[];
  meta?: { yoy_available?: boolean; yoy_note?: string | null };
} | null>(null);

// Format helpers for preview cells.
function fmtAmount(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function fmtRatio(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return (n * 100).toFixed(2) + '%';
}

// ─── Audit log tab ────────────────────────────────────────────────────
const auditRows = ref<AuditLogEntry[]>([]);
const auditLoading = ref(false);

// ─── Date shortcuts ───────────────────────────────────────────────────
const dateShortcuts = [
  {
    text: '上周',
    value: () => {
      const today = new Date();
      const dayOfWeek = today.getDay() || 7; // 1..7 (Mon..Sun, treat Sun as 7)
      const lastSun = new Date(today);
      lastSun.setDate(today.getDate() - dayOfWeek);
      const lastMon = new Date(lastSun);
      lastMon.setDate(lastSun.getDate() - 6);
      return [lastMon, lastSun] as [Date, Date];
    },
  },
  {
    text: '本月',
    value: () => {
      const today = new Date();
      const start = new Date(today.getFullYear(), today.getMonth(), 1);
      return [start, today] as [Date, Date];
    },
  },
  {
    text: '上月',
    value: () => {
      const today = new Date();
      const start = new Date(today.getFullYear(), today.getMonth() - 1, 1);
      const end = new Date(today.getFullYear(), today.getMonth(), 0);
      return [start, end] as [Date, Date];
    },
  },
  {
    text: '近 30 天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setDate(end.getDate() - 30);
      return [start, end] as [Date, Date];
    },
  },
];

// ─── Lifecycle ────────────────────────────────────────────────────────
onMounted(async () => {
  await loadStores();
  loadFiltersFromStorage();
  await loadAuditLog();
});

watch([dateRange, selectedStoreNames, selectedMealPeriods], () => {
  saveFiltersToStorage();
});

async function loadStores() {
  storesLoading.value = true;
  try {
    stores.value = await listStores(true);
  } catch (e: any) {
    showStickyError(`门店列表加载失败: ${e?.message || e}。请确认当前账号有收入报表权限，或刷新后重试。`);
  } finally {
    storesLoading.value = false;
  }
}

function loadFiltersFromStorage() {
  try {
    const raw = localStorage.getItem(filterStorageKey.value);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (parsed.dateRange) dateRange.value = parsed.dateRange;
    if (parsed.selectedStoreNames) selectedStoreNames.value = parsed.selectedStoreNames;
    if (parsed.selectedMealPeriods) selectedMealPeriods.value = parsed.selectedMealPeriods;
  } catch {
    // Ignore malformed cache.
  }
}

function saveFiltersToStorage() {
  try {
    localStorage.setItem(
      filterStorageKey.value,
      JSON.stringify({
        dateRange: dateRange.value,
        selectedStoreNames: selectedStoreNames.value,
        selectedMealPeriods: selectedMealPeriods.value,
      }),
    );
  } catch {
    // Ignore quota errors.
  }
}

// ─── Upload handler ───────────────────────────────────────────────────
async function handleFilesChange(files: UploadUserFile[]) {
  uploadFiles.value = files;
  uploadPurpose.value = 'pos';
}

async function handleUpload() {
  if (uploadFiles.value.length === 0) {
    ElMessage.warning('请先选择文件');
    return;
  }
  uploadPurposeDialogVisible.value = true;
}

async function confirmUploadPurpose() {
  if (uploadPurpose.value === 'supplier-delivery') {
    uploadPurposeDialogVisible.value = false;
    await router.push('/restaurant/supplier-delivery');
    return;
  }
  if (uploadPurpose.value === 'finance-excel') {
    uploadPurposeDialogVisible.value = false;
    await router.push('/smart-bi/upload');
    return;
  }
  if (uploadPurpose.value === 'review-data') {
    uploadPurposeDialogVisible.value = false;
    await router.push('/restaurant/data-completeness');
    return;
  }
  uploadPurposeDialogVisible.value = false;
  await uploadPosReports();
}

async function uploadPosReports() {
  if (uploading.value) return;
  const raws = uploadFiles.value
    .map((f) => f.raw)
    .filter((r): r is NonNullable<UploadUserFile['raw']> => !!r);
  if (raws.length === 0) {
    ElMessage.warning('请先选择文件');
    return;
  }
  uploading.value = true;
  uploadResults.value = [];
  try {
    const res = await uploadPosFiles(raws);
    uploadResults.value = res.files;
    const ok = res.files.filter((f) => f.status === 'ok').length;
    const dup = res.files.filter((f) => f.status === 'duplicate').length;
    const unk = res.files.filter((f) => f.status === 'unknown').length;
    let msg = `上传完成: ${ok} 成功`;
    if (dup) msg += ` / ${dup} 重复（已跳过）`;
    if (unk) msg += ` / ${unk} 无法识别`;
    if (unk > 0) showStickyWarning(`${msg}。请确认文件名包含“详细日报表 / 营业概况报表 / 堂食外卖占比表 / 商品销售明细表”，或改选正确的数据类型入口。`);
    else ElMessage.success(msg);
  } catch (e: any) {
    showStickyError(`上传失败: ${e?.response?.data?.message || e?.message || e}。请检查文件类型、账号权限后重试。`);
  } finally {
    uploading.value = false;
  }
}

// ─── Generate handler ─────────────────────────────────────────────────
function buildParams(): RevenueReportParams | null {
  if (!dateRange.value) {
    ElMessage.warning('请选择日期范围');
    return null;
  }
  const [from, to] = dateRange.value;
  return {
    store_names: selectedStoreNames.value,
    date_from: from,
    date_to: to,
    meal_periods: selectedMealPeriods.value,
  };
}

function startElapsedTimer() {
  elapsedSec.value = 0;
  if (elapsedTimer !== null) clearInterval(elapsedTimer);
  elapsedTimer = window.setInterval(() => {
    elapsedSec.value++;
  }, 1000) as unknown as number;
}

function stopElapsedTimer() {
  if (elapsedTimer !== null) {
    clearInterval(elapsedTimer);
    elapsedTimer = null;
  }
}

async function handlePreview() {
  if (generating.value) return;
  const params = buildParams();
  if (!params) return;
  generating.value = true;
  startElapsedTimer();
  try {
    const res = await prepare(params);
    previewSummary.value = res.summary;
    previewBlocks.value = res.preview
      ? {
          block1_yoy: res.preview.block1_yoy as PreviewBlockRow[],
          block2_mom: res.preview.block2_mom as PreviewBlockRow[],
          block3_meal_split: res.preview.block3_meal_split as PreviewBlockRow[],
          meta: res.preview.meta,
        }
      : null;
    ElMessage.success(
      `数据已生成${res.summary.cache_hit ? '（缓存命中）' : ''}，` +
        `点 "下载 Excel" 获取文件`,
    );
  } catch (e: any) {
    showStickyError(`预览失败: ${e?.response?.data?.message || e?.message || e}。请确认日期范围内已有 POS 数据。`);
  } finally {
    generating.value = false;
    stopElapsedTimer();
  }
}

// Filenames built from the resolved date range. Firefox mangles non-ASCII
// download names, so fall back to an ASCII filename there.
function buildFilename(dateFrom: string, dateTo: string): string {
  const isFirefox = navigator.userAgent.toLowerCase().includes('firefox');
  const safeRange = `${dateFrom}_${dateTo}`;
  return isFirefox
    ? `revenue_report_${safeRange}.xlsx`
    : `收入管理报表_${safeRange}.xlsx`;
}

// Shared blob → browser-download. Chrome requires the anchor to be IN THE DOM
// before .click() for the `download` attribute to be respected; without
// appendChild Chrome falls back to the Blob URL's UUID as the filename.
function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(
    new Blob([blob], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
  );
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function handleDownload() {
  if (generating.value) return;
  const params = buildParams();
  if (!params) return;
  generating.value = true;
  startElapsedTimer();
  try {
    const result = await generateAndDownload(params);
    const filename = buildFilename(params.date_from, params.date_to);
    triggerDownload(result.blob, filename);

    lastDownloadInfo.value = {
      filename,
      cacheHit: result.cacheHit,
      storeCount: result.storeCount,
      goldMaterializedAt: result.goldMaterializedAt,
      isStale: result.isStale,
    };
    ElMessage.success(`下载完成${result.cacheHit ? '（缓存命中）' : ''}`);
    // Refresh audit log so the new generation appears immediately.
    await loadAuditLog();
  } catch (e: any) {
    showStickyError(`下载失败: ${e?.response?.data?.message || e?.message || e}。请先预览确认数据范围，或稍后重试。`);
  } finally {
    generating.value = false;
    stopElapsedTimer();
  }
}

// ─── One-click default-header report (#13) ───────────────────────────────
// Generates with full-history range (from getGoldDataRange) + all stores
// (empty array) + all meal periods (empty array). No filter pre-fill needed —
// the default-header layout is already baked into the backend renderer
// (qhj_revenue_v1.py), so we only supply the all-history date window.
async function handleOneClickDefault() {
  if (oneClickLoading.value || generating.value) return;
  const factoryId = authStore.factoryId;
  if (!factoryId) {
    showStickyError('无法获取工厂信息，请重新登录后再生成报表。');
    return;
  }
  oneClickLoading.value = true;
  startElapsedTimer();
  try {
    const dr = await getGoldDataRange(factoryId);
    if (!dr.minDate || !dr.maxDate) {
      showStickyError('暂无可用数据：尚未上传任何 POS 报表，请先在上方选择 POS 文件上传。');
      return;
    }
    const params: RevenueReportParams = {
      date_from: dr.minDate,
      date_to: dr.maxDate,
      store_names: [], // 空 = 全部门店
      meal_periods: [], // 空 = 全班次
    };
    const result = await generateAndDownload(params);
    const filename = buildFilename(params.date_from, params.date_to);
    triggerDownload(result.blob, filename);

    lastDownloadInfo.value = {
      filename,
      cacheHit: result.cacheHit,
      storeCount: result.storeCount,
      goldMaterializedAt: result.goldMaterializedAt,
      isStale: result.isStale,
    };
    ElMessage.success(
      `默认报表已生成（全部历史 ${dr.minDate} ~ ${dr.maxDate}）` +
        `${result.cacheHit ? '（缓存命中）' : ''}`,
    );
    await loadAuditLog();
  } catch (e: any) {
    showStickyError(`一键生成失败: ${e?.response?.data?.message || e?.message || e}。请确认已有 POS 数据并稍后重试。`);
  } finally {
    oneClickLoading.value = false;
    stopElapsedTimer();
  }
}

// ─── Audit log ────────────────────────────────────────────────────────
async function loadAuditLog() {
  auditLoading.value = true;
  try {
    auditRows.value = await getAuditLog(20);
  } catch (e: any) {
    showStickyError(`历史记录加载失败: ${e?.message || e}。请刷新页面后重试。`);
  } finally {
    auditLoading.value = false;
  }
}

function fmtFileSize(bytes: number | null | undefined) {
  if (!bytes) return '—';
  return `${(bytes / 1024).toFixed(1)} KB`;
}

function fmtDuration(ms: number | null | undefined) {
  if (!ms) return '—';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function showStickyError(message: string) {
  const messenger = ElMessage as unknown as {
    (options: { message: string; type: 'error' | 'warning'; duration: number; showClose: boolean }): void;
    error?: (message: string) => void;
  };
  messenger({ message, type: 'error', duration: 0, showClose: true });
}

function showStickyWarning(message: string) {
  const messenger = ElMessage as unknown as {
    (options: { message: string; type: 'error' | 'warning'; duration: number; showClose: boolean }): void;
    warning?: (message: string) => void;
  };
  messenger({ message, type: 'warning', duration: 0, showClose: true });
}
</script>

<template>
  <div class="revenue-report-page">
      <h1 class="page-title">收入管理报表</h1>
      <p class="page-subtitle">
      {{ tenantLabel }} — 同比 / 环比 / 堂食外卖占比 / 客单人数分析
      </p>

    <!-- Stale-data warning banner (spec §11.4) -->
    <el-alert
      v-if="previewSummary?.is_stale || lastDownloadInfo?.isStale"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      数据延迟：最新截至 {{ previewSummary?.gold_materialized_at || lastDownloadInfo?.goldMaterializedAt }}，
      可能不含最近一次上传的数据。
    </el-alert>

    <el-tabs v-model="activeTab" type="border-card">
      <el-tab-pane name="generate" label="上传 & 生成">
        <!-- ─── Upload block ──────────────────────────────────── -->
        <section class="card">
          <h3 class="card-title">上传二维火 POS 文件</h3>
          <p class="card-help">
            支持
            <code>详细日报表</code> /
            <code>营业概况报表</code> /
            <code>堂食外卖占比表</code> /
            <code>商品销售明细表</code>
            等。zip / xlsx / xls / csv 均可，文件名识别后自动派发到对应 Silver 写入路径。
          </p>
          <SmartBIUploader
            :canUpload="true"
            :historyLoading="false"
            :uploading="uploading"
            :maxCount="0"
            accept=".zip,.xlsx,.xls,.csv"
            @files-change="handleFilesChange"
            @upload="handleUpload"
          />
          <el-button
            v-if="uploadFiles.length > 0 && !uploading"
            type="primary"
            size="large"
            :loading="uploading"
            @click="handleUpload"
            style="margin-top: 16px"
          >
            开始上传 {{ uploadFiles.length }} 个文件
          </el-button>

          <el-dialog
            v-model="uploadPurposeDialogVisible"
            title="确认这批文件是什么业务数据"
            width="680px"
            :close-on-click-modal="false"
            :close-on-press-escape="false"
            destroy-on-close
          >
            <p class="purpose-dialog-hint">
              系统会按用途把文件送到对应模块。POS 销售流水会写入收入报表；采购、财务、评价类文件不会在这里强行导入。
            </p>
            <el-radio-group v-model="uploadPurpose" class="purpose-options">
              <el-radio
                v-for="item in uploadPurposeOptions"
                :key="item.value"
                :value="item.value"
                class="purpose-option"
              >
                <div class="purpose-option-title">{{ item.title }}</div>
                <div class="purpose-option-desc">{{ item.desc }}</div>
              </el-radio>
            </el-radio-group>
            <template #footer>
              <el-button @click="uploadPurposeDialogVisible = false">取消</el-button>
              <el-button type="primary" @click="confirmUploadPurpose">
                {{ selectedUploadPurpose.action }}
              </el-button>
            </template>
          </el-dialog>

          <!-- Upload results table -->
          <el-table
            v-if="uploadResults.length > 0"
            :data="uploadResults"
            style="margin-top: 16px"
            stripe
          >
            <el-table-column prop="filename" label="文件名" />
            <el-table-column prop="status" label="状态" width="120">
              <template #default="{ row }">
                <el-tag
                  :type="
                    row.status === 'ok'
                      ? 'success'
                      : row.status === 'duplicate'
                        ? 'warning'
                        : 'danger'
                  "
                >
                  {{
                    row.status === 'ok'
                      ? '成功'
                      : row.status === 'duplicate'
                        ? '重复'
                        : '无法识别'
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="识别类型" width="200">
              <template #default="{ row }">
                {{ row.report_types?.join(', ') || '—' }}
              </template>
            </el-table-column>
            <el-table-column label="详情">
              <template #default="{ row }">
                <span v-if="row.status === 'duplicate' && row.existing_upload_id">
                  已存在 upload_id={{ row.existing_upload_id }}
                </span>
                <span v-else-if="row.preview_headers">
                  Preview: {{ row.preview_headers?.slice(0, 1).join(' / ') }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ─── Generate block ─────────────────────────────────── -->
        <section class="card" style="margin-top: 24px">
          <h3 class="card-title">生成报表</h3>

          <!-- ─── One-click default-header report (#13) ─────────── -->
          <div class="oneclick-block">
            <el-button
              type="primary"
              size="large"
              :loading="oneClickLoading"
              :disabled="oneClickLoading || generating"
              @click="handleOneClickDefault"
            >
              一键生成默认收入管理报表 (全部历史)
            </el-button>
            <div class="oneclick-hint">
              默认表头 · 全部门店 · 全部班次 · 全部历史区间，无需选择，直接下载 Excel
            </div>
            <span v-if="oneClickLoading" class="elapsed">已等待 {{ elapsedSec }} 秒...</span>
          </div>

          <el-divider content-position="left">或自定义生成</el-divider>

          <el-form label-width="100px" label-position="left">
            <el-form-item label="日期范围" required>
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                :shortcuts="dateShortcuts"
                style="width: 320px"
              />
            </el-form-item>

            <el-form-item label="门店">
              <el-select
                v-model="selectedStoreNames"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :loading="storesLoading"
                placeholder="不选 = 全部门店"
                style="width: 480px"
              >
                <el-option
                  v-for="s in stores"
                  :key="s.store_id"
                  :label="s.name"
                  :value="s.name"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="班次">
              <el-checkbox-group v-model="selectedMealPeriods">
                <el-checkbox label="午市" />
                <el-checkbox label="晚市" />
              </el-checkbox-group>
              <span class="hint">未勾选 = 全班次</span>
            </el-form-item>

            <el-form-item>
              <el-button
                type="default"
                :loading="generating"
                :disabled="generating"
                @click="handlePreview"
              >
                预览数据
              </el-button>
              <el-button
                type="primary"
                :loading="generating"
                :disabled="generating"
                @click="handleDownload"
              >
                下载 Excel
              </el-button>
              <span v-if="generating" class="elapsed">已等待 {{ elapsedSec }} 秒...</span>
            </el-form-item>
          </el-form>

          <!-- Preview summary card -->
          <div v-if="previewSummary" class="summary-card">
            <div class="summary-row">
              <span class="summary-label">门店数：</span>
              <strong>{{ previewSummary.store_count }}</strong>
            </div>
            <div class="summary-row">
              <span class="summary-label">日期范围：</span>
              <strong>{{ previewSummary.date_range }}</strong>
            </div>
            <div class="summary-row">
              <span class="summary-label">数据新鲜度：</span>
              <strong>{{ previewSummary.gold_materialized_at }}</strong>
            </div>
            <div class="summary-row">
              <span class="summary-label">文件大小：</span>
              <strong>{{ fmtFileSize(previewSummary.file_size_bytes) }}</strong>
            </div>
            <div class="summary-row">
              <span class="summary-label">缓存命中：</span>
              <strong>{{ previewSummary.cache_hit ? '是' : '否' }}</strong>
            </div>
          </div>

          <!-- ─── 3 preview tables (first 10 rows per block) ─── -->
          <div v-if="previewBlocks" class="preview-blocks" style="margin-top:24px">
            <!-- 表 1: 可比同比 -->
            <div class="preview-block">
              <h4 class="preview-title">表 1: 可比同比 (前 10 行)</h4>
              <div v-if="previewBlocks.meta?.yoy_note" class="preview-note">
                ℹ️ {{ previewBlocks.meta.yoy_note }}
              </div>
              <el-table :data="previewBlocks.block1_yoy" stripe size="small" border max-height="350">
                <el-table-column prop="store_name" label="门店名称" min-width="180" />
                <el-table-column label="汇总实际收入" align="center">
                  <el-table-column prop="total" label="本期" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.total) }}</template>
                  </el-table-column>
                  <el-table-column prop="prev_total" label="去年同期" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.prev_total) }}</template>
                  </el-table-column>
                  <el-table-column prop="total_ratio" label="同比率" align="right" min-width="90">
                    <template #default="{ row }">{{ fmtRatio(row.total_ratio) }}</template>
                  </el-table-column>
                </el-table-column>
                <el-table-column label="堂食" align="center">
                  <el-table-column prop="dine_in" label="本期" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.dine_in) }}</template>
                  </el-table-column>
                  <el-table-column prop="dine_in_ratio" label="同比率" align="right" min-width="90">
                    <template #default="{ row }">{{ fmtRatio(row.dine_in_ratio) }}</template>
                  </el-table-column>
                </el-table-column>
                <el-table-column label="外卖" align="center">
                  <el-table-column prop="takeout" label="本期" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.takeout) }}</template>
                  </el-table-column>
                  <el-table-column prop="takeout_ratio" label="同比率" align="right" min-width="90">
                    <template #default="{ row }">{{ fmtRatio(row.takeout_ratio) }}</template>
                  </el-table-column>
                </el-table-column>
              </el-table>
            </div>

            <!-- 表 2: 环比 -->
            <div class="preview-block" style="margin-top:20px">
              <h4 class="preview-title">表 2: 环比 (前 10 行)</h4>
              <el-table :data="previewBlocks.block2_mom" stripe size="small" border max-height="350">
                <el-table-column prop="store_name" label="门店名称" min-width="180" />
                <el-table-column label="汇总实际收入" align="center">
                  <el-table-column prop="total" label="本期" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.total) }}</template>
                  </el-table-column>
                  <el-table-column prop="prev_total" label="环比" align="right" min-width="100">
                    <template #default="{ row }">{{ fmtAmount(row.prev_total) }}</template>
                  </el-table-column>
                  <el-table-column prop="total_ratio" label="环比率" align="right" min-width="90">
                    <template #default="{ row }">{{ fmtRatio(row.total_ratio) }}</template>
                  </el-table-column>
                </el-table-column>
                <el-table-column prop="dine_in_ratio" label="堂食环比率" align="right" min-width="100">
                  <template #default="{ row }">{{ fmtRatio(row.dine_in_ratio) }}</template>
                </el-table-column>
                <el-table-column prop="takeout_ratio" label="外卖环比率" align="right" min-width="100">
                  <template #default="{ row }">{{ fmtRatio(row.takeout_ratio) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <!-- 表 3: 堂食外卖占比 -->
            <div class="preview-block" style="margin-top:20px">
              <h4 class="preview-title">表 3: 堂食外卖占比 (前 10 行)</h4>
              <el-table :data="previewBlocks.block3_meal_split" stripe size="small" border max-height="350">
                <el-table-column prop="store_name" label="门店名称" min-width="180" />
                <el-table-column prop="dine_in_revenue" label="实际收入堂食" align="right" min-width="120">
                  <template #default="{ row }">{{ fmtAmount(row.dine_in_revenue) }}</template>
                </el-table-column>
                <el-table-column prop="takeout_revenue" label="实际收入外卖" align="right" min-width="120">
                  <template #default="{ row }">{{ fmtAmount(row.takeout_revenue) }}</template>
                </el-table-column>
                <el-table-column prop="revenue_ratio" label="收入比例" align="right" min-width="100">
                  <template #default="{ row }">{{ fmtRatio(row.revenue_ratio) }}</template>
                </el-table-column>
                <el-table-column prop="dine_in_bills" label="客单量堂食" align="right" min-width="100" />
                <el-table-column prop="takeout_bills" label="客单量外卖" align="right" min-width="100" />
                <el-table-column prop="bill_ratio" label="客单比例" align="right" min-width="100">
                  <template #default="{ row }">{{ fmtRatio(row.bill_ratio) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <div style="margin-top:12px;color:#86909c;font-size:13px">
 仅显示前 10 行；点 "下载 Excel" 获取完整报表 (含 客单人数分析 4 表全部数据)
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane name="audit" label="历史记录">
        <section class="card">
          <h3 class="card-title">最近 20 次生成</h3>
          <el-table :data="auditRows" v-loading="auditLoading" stripe>
            <el-table-column prop="generated_at" label="时间" width="180" />
            <el-table-column prop="generated_by" label="用户" width="180" />
            <el-table-column label="参数">
              <template #default="{ row }">
                <code>{{ JSON.stringify(row.params_snapshot) }}</code>
              </template>
            </el-table-column>
            <el-table-column label="文件大小" width="100">
              <template #default="{ row }">{{ fmtFileSize(row.file_size_bytes) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">{{ fmtDuration(row.duration_ms) }}</template>
            </el-table-column>
            <el-table-column label="缓存" width="80">
              <template #default="{ row }">
                <el-tag :type="row.cache_hit ? 'success' : 'info'" size="small">
                  {{ row.cache_hit ? '命中' : '未命中' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ok' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'ok' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.revenue-report-page {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-title {
  font-size: 24px;
  margin: 0 0 4px;
}

.page-subtitle {
  color: #86909c;
  margin: 0 0 24px;
  font-size: 14px;
}

.card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 12px;
}

.card-help {
  color: #86909c;
  font-size: 13px;
  margin: 0 0 16px;
}

.card-help code {
  background: #f4f4f5;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}

.purpose-dialog-hint {
  margin: 0 0 16px;
  color: #606266;
  line-height: 1.6;
}

.purpose-options {
  display: grid;
  gap: 10px;
  width: 100%;
}

.purpose-option {
  width: 100%;
  min-height: 72px;
  margin-right: 0;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  align-items: flex-start;
  white-space: normal;
}

.purpose-option :deep(.el-radio__label) {
  white-space: normal;
}

.purpose-option-title {
  color: #303133;
  font-weight: 600;
  line-height: 1.4;
}

.purpose-option-desc {
  margin-top: 4px;
  color: #86909c;
  font-size: 13px;
  line-height: 1.5;
}

.hint {
  margin-left: 12px;
  color: #86909c;
  font-size: 12px;
}

.oneclick-block {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 8px;
  padding: 20px 24px;
  margin-bottom: 8px;
}

.oneclick-hint {
  margin-top: 10px;
  color: #606266;
  font-size: 13px;
}

.elapsed {
  margin-left: 16px;
  color: #86909c;
  font-size: 12px;
}

.summary-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 16px 20px;
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px 24px;
}

.summary-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.summary-label {
  color: #86909c;
  font-size: 13px;
}
</style>
