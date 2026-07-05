<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { getBatchWip, getOrderYieldSummary, getBatchWorkProcessTasks, type WipRowItem, type OrderYieldSummary, type WorkProcessTaskItem } from '@/api/processProduction';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Refresh, User } from '@element-plus/icons-vue';
import { formatDateTime } from '@/utils/dateFormat';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const batchId = computed(() => route.params.id as string);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canWrite = computed(() => permissionStore.canWrite('production'));

// Issue #760: detail 页消费 ?mode=edit query
// 编辑 button (PR #755) 跳转时带 ?mode=edit, 本页据此切换 readonly vs editable.
// 当前 detail 页主要展示 KPI / 描述 / 成本 / 时间线 — 无 inline 表单控件,
// 编辑入口通过顶部"编辑批次"按钮跳回 list dialog (router-back) 实现.
// 此变更:
//  1. isEditMode 反映 query 状态
//  2. 顶部 header 显示 mode tag (查看 / 编辑)
//  3. 仅 edit 模式显示"返回列表编辑"按钮 (跳 list.vue 触发 edit dialog)
const isEditMode = computed(() => route.query.mode === 'edit');

const loading = ref(false);
const batch = ref<TableRow | null>(null);
const timeline = ref<TableRow[]>([]);
const attachmentRefreshKey = ref(0);
// T4-D4 (issue #533): F006 customer wants 原料消耗记录 visible on batch detail.
// Backend endpoint /processing/material-consumptions/batch/{productionBatchId} (MaterialConsumptionController:151)
// returns the consumption rows for this batch's production plan.
const consumptions = ref<TableRow[]>([]);
const yieldData = ref<any | null>(null);
const batchCost = ref<any | null>(null);
// G6/G7 Wave 4: 半成品库存 (WIP) — 每道工序中间品存量 (产出/已领/余额/状态)
const wipRows = ref<WipRowItem[]>([]);

// T140: 工序任务明细
const workProcessTasks = ref<WorkProcessTaskItem[]>([]);
// T140: 工序详情抽屉
const taskDrawerVisible = ref(false);
const selectedTask = ref<WorkProcessTaskItem | null>(null);

// 单元 F (F006 REQ-21): 分订单出成率 — 本订单下全部批次聚合 (弹窗惰性加载)
const orderYieldVisible = ref(false);
const orderYieldLoading = ref(false);
const orderYield = ref<OrderYieldSummary | null>(null);
const orderYieldError = ref('');

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value || !batchId.value) return;

  loading.value = true;
  try {
    const [batchRes, timelineRes, yieldRes, wipRes, tasksRes] = await Promise.allSettled([
      get(`/${factoryId.value}/processing/batches/${batchId.value}`),
      get(`/${factoryId.value}/processing/batches/${batchId.value}/timeline`),
      get(`/${factoryId.value}/production/batches/${batchId.value}/yield`),
      getBatchWip(factoryId.value, batchId.value),
      getBatchWorkProcessTasks(factoryId.value, batchId.value)
    ]);

    if (batchRes.status === 'fulfilled' && batchRes.value.success) {
      batch.value = batchRes.value.data;
      // Once we know productionBatchId (= batchId), load consumption records.
      // Endpoint path uses productionBatchId param name; for batches, batchId === productionBatchId.
      await Promise.allSettled([loadConsumptions(), loadBatchCost()]);
    } else {
      ElMessage.error('加载批次详情失败');
    }

    if (timelineRes.status === 'fulfilled' && timelineRes.value.success) {
      timeline.value = timelineRes.value.data || [];
    }

    if (yieldRes.status === 'fulfilled' && yieldRes.value.success
        && yieldRes.value.data?.steps?.length > 0) {
      yieldData.value = yieldRes.value.data;
    } else {
      yieldData.value = null;
    }

    // G6/G7 Wave 4: WIP 库存行 (无则空数组 → WIP 区 v-if 隐藏, 诚实空态)
    if (wipRes.status === 'fulfilled' && wipRes.value.success && Array.isArray(wipRes.value.data)) {
      wipRows.value = wipRes.value.data;
    } else {
      wipRows.value = [];
    }

    // T140: 工序任务 (无则空数组 → 工序区显"暂未生成工序任务", 诚实空态)
    if (tasksRes.status === 'fulfilled' && tasksRes.value.success && Array.isArray(tasksRes.value.data)) {
      workProcessTasks.value = tasksRes.value.data;
    } else {
      workProcessTasks.value = [];
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadConsumptions() {
  if (!factoryId.value || !batchId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/material-consumptions/batch/${batchId.value}`);
    if (res.success && res.data) {
      consumptions.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
    }
  } catch {
    // Interceptor shows toast. Consumption block gracefully hides via v-if length check.
  }
}

async function loadBatchCost() {
  if (!factoryId.value || !batch.value?.batchNumber) {
    batchCost.value = null;
    return;
  }
  try {
    const batchNumber = encodeURIComponent(String(batch.value.batchNumber));
    const res = await get(`/${factoryId.value}/production/batches/${batchNumber}/cost-breakdown`);
    batchCost.value = res.success && res.data?.hasData !== false ? res.data : null;
  } catch {
    batchCost.value = null;
  }
}

function goBack() {
  router.push('/production/batches');
}

// Issue #760: 回 list 并提示打开 edit dialog (用 query 标识)
function goBackToListWithEdit() {
  router.push({
    path: '/production/batches',
    query: { editId: batchId.value },
  });
}

// 🔴 Bug2 修复 (fool-proof-design.md Rule 5 no-dead-end): 本页全程只读展示,
// 工时/工序数据的录入实际在「生产计划 → 逐道录入」抽屉 (plans/list.vue), 而不是这里。
// 之前用户走 批次→详情 路径完全找不到入口, 无任何提示 — 典型死胡同。
// 有 productionPlanId 时直接带 query 跳去生产计划页并自动打开对应计划的逐道录入抽屉
// (见 plans/list.vue::maybeOpenProcessEntryFromQuery); 没有关联计划时退化为跳转列表页
// + 提示手动查找, 而不是什么都不做。
function goToProcessEntry() {
  const planId = batch.value?.productionPlanId as string | undefined;
  if (planId) {
    router.push({ path: '/production/plans', query: { openProcessEntryPlan: String(planId) } });
  } else {
    ElMessage.info('该批次未关联生产计划，请前往「生产计划」页面手动查找并点击"逐道录入"');
    router.push('/production/plans');
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PLANNED: 'info',
    PENDING: 'info',
    IN_PROGRESS: 'warning',
    PAUSED: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PLANNED: '待生产',
    PENDING: '待生产',
    IN_PROGRESS: '生产中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}

function getQualityStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING_INSPECTION: '待检验',
    INSPECTING: '检验中',
    PASSED: '已通过',
    FAILED: '不合格',
    PARTIAL_PASS: '部分合格',
    REWORK_REQUIRED: '需返工'
  };
  return map[status?.toUpperCase()] || status || '-';
}

function getQualityStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING_INSPECTION: 'info',
    INSPECTING: 'warning',
    PASSED: 'success',
    FAILED: 'danger',
    PARTIAL_PASS: 'warning',
    REWORK_REQUIRED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function formatNum(val: unknown, suffix = '') {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : n.toLocaleString('zh-CN') + suffix;
}

function formatCost(val: unknown) {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// A.6 逐道成本: null/未计算 → "—" (em dash, 不是 0/¥0); 与出成率表其他空值 (出成率/结转/人数/工时) 一致.
// 后端 cost 为 null 表示无法计算 (未配工价 / 无原料单价), 不应误显 ¥0.
function formatCostDash(val: unknown) {
  if (val === null || val === undefined) return '—';
  const n = Number(val);
  return isNaN(n) ? '—' : '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatPercent(val: unknown) {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : n.toFixed(1) + '%';
}

// 适配单元5 (F006 传统报工): 数量类字段 null/undefined → "—" (em dash, 非 0).
// 用于损耗 / 留样 / 副产物数量等 — 后端 null 表示"未记录", 绝不能误显 0.
// 与 formatCostDash (带 ¥) 区分: 这个不带货币符号, 带可选单位后缀.
function fmtDash(val: unknown, suffix = '') {
  if (val === null || val === undefined) return '—';
  const n = Number(val);
  return isNaN(n) ? '—' : n.toLocaleString('zh-CN') + (suffix ? ' ' + suffix : '');
}

function formatDuration(minutes: number | null) {
  if (!minutes) return '-';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}小时${m > 0 ? m + '分钟' : ''}` : `${m}分钟`;
}

// 单元 F: 累计出成率 (0-1 小数) → 百分比文案; 跨单位不可比 (null) → "—"
function formatRateDash(rate: unknown) {
  if (rate === null || rate === undefined) return '—';
  const n = Number(rate);
  return isNaN(n) ? '—' : (n * 100).toFixed(1) + '%';
}

// 单元 F: 本批次是否挂在某生产计划 (有 productionPlanId 才能解析订单)
const hasOrderContext = computed(() => !!batch.value?.productionPlanId);

/**
 * 单元 F (F006 REQ-21 "以订单的模式呈现"): 打开"本订单整体出成率"弹窗。
 * 链路: batch.productionPlanId → GET 生产计划 (取 sourceOrderId) → GET 订单出成率聚合。
 * 惰性触发 (点击才查), 不影响详情页初始加载。无订单/无聚合 → 诚实空态文案。
 */
async function openOrderYield() {
  orderYieldVisible.value = true;
  if (orderYield.value || orderYieldLoading.value) return; // 已加载 / 加载中, 不重复请求
  orderYieldError.value = '';
  const planId = batch.value?.productionPlanId as string | undefined;
  if (!factoryId.value || !planId) {
    orderYieldError.value = '本批次未关联生产计划, 无法按订单聚合';
    return;
  }
  orderYieldLoading.value = true;
  try {
    // 1) 计划 → sourceOrderId
    const planRes = await get(`/${factoryId.value}/production-plans/${planId}`);
    const orderId = planRes.success ? (planRes.data?.sourceOrderId as string | undefined) : undefined;
    if (!orderId) {
      orderYieldError.value = '本批次所属计划未关联销售订单, 无法按订单聚合';
      return;
    }
    // 2) 订单 → 全部批次出成率聚合
    const res = await getOrderYieldSummary(factoryId.value, orderId);
    if (res.success && res.data) {
      orderYield.value = res.data;
    } else {
      orderYieldError.value = res.message || '加载订单出成率失败';
    }
  } catch (error) {
    // 拦截器已弹 sticky toast; 这里给弹窗内兜底文案
    orderYieldError.value = '加载订单出成率失败';
    console.error('加载订单出成率失败:', error);
  } finally {
    orderYieldLoading.value = false;
  }
}

// 单元3: 有 YIELD 数据时用末道产出回填"实际产量"
const hasYield = computed(() => !!yieldData.value?.steps?.length);
const displayActualQuantity = computed(() =>
  hasYield.value ? yieldData.value.lastStepOutput : batch.value?.actualQuantity);
const displayActualUnit = computed(() =>
  hasYield.value ? (yieldData.value.lastStepOutputUnit || '') : (batch.value?.unit || ''));
// audit YIELD-1: 跨单位 cumulative=null 显 —, 不能 *100 (null*100===0 会误显 0.0%)
// P0-2: 跨单位且无 cumulative → 标"跨单位不可比, 需配产品标准克重" (诚实, 不显 0/—)
const cumulativeDisplay = computed(() => {
  const yd = yieldData.value;
  const r = yd?.cumulativeYieldRate;
  if (r != null) return formatPercent(r * 100);
  const inU = yd?.firstStepInputUnit;
  const outU = yd?.lastStepOutputUnit;
  if (inU != null && outU != null && inU !== outU) {
    return '跨单位不可比, 需配产品标准克重';
  }
  return '—';
});

// G8 Wave 4 (C): 进行中标注 — 在制半成品未计入成品, 出成率偏低且会变 (cumulativeYieldRate 仍是 A 完工口径)
const yieldInProgress = computed(() => yieldData.value?.inProgress === true);
const wipInProgressText = computed(() => {
  const yd = yieldData.value;
  if (!yd?.inProgress) return '';
  const q = yd.wipInProgressQuantity;
  if (q == null || Number(q) <= 0) return '生产进行中, 出成率完工后才锁定';
  const u = yd.wipInProgressUnit || '';
  return `进行中: 含 ${formatNum(q)} ${u} 在制半成品未计入成品, 出成率完工后才锁定`;
});

// 适配单元5 (F006 传统报工 展示层): 逐道报工新增字段 — 证据照片 / 工时段 / 副产物 / 损耗 / 留样.
// 因主表已有 11 列, 再加 5 列会过宽 → 用 el-table type="expand" 展开行承载这些细节,
// 主行仅保留核心出成率数字 + 小缩略图 + 📷 计数, 由工厂管理者按需展开.
// 下列 helper 统一判空 (null/空数组 → false), 避免模板里散落判断.
function stepPhotos(row: Record<string, unknown>): string[] {
  const p = row?.photos;
  return Array.isArray(p) ? (p as string[]).filter((u) => !!u) : [];
}
// 单元3 (F006 三阶段): 投入照片 / 产出照片 分组 (后端 BatchYieldDTO.steps[].inputPhotos / outputPhotos).
// 各自独立 lightbox 画廊; 空数组/null → []. legacy 行两组都空时回退到合并的 photos (见 stepLegacyFallbackPhotos).
type EvidenceMediaKind = 'image' | 'video';
interface EvidenceMediaItem {
  url: string;
  kind: EvidenceMediaKind;
}

function isEvidenceVideoUrl(url: string): boolean {
  const clean = String(url || '').split(/[?#]/)[0]?.toLowerCase() || '';
  return /\.(mp4|mov|m4v|webm)$/.test(clean);
}

function evidenceMediaItems(urls: string[]): EvidenceMediaItem[] {
  return urls.map((url) => ({
    url,
    kind: isEvidenceVideoUrl(url) ? 'video' : 'image'
  }));
}

function evidenceImageUrls(urls: string[]): string[] {
  return urls.filter((url) => !isEvidenceVideoUrl(url));
}

function evidenceImageInitialIndex(urls: string[], url: string): number {
  const idx = evidenceImageUrls(urls).indexOf(url);
  return idx >= 0 ? idx : 0;
}

function firstEvidenceMedia(row: Record<string, unknown>): EvidenceMediaItem | null {
  return evidenceMediaItems(stepPhotos(row))[0] || null;
}

function stepInputPhotos(row: Record<string, unknown>): string[] {
  const p = row?.inputPhotos;
  return Array.isArray(p) ? (p as string[]).filter((u) => !!u) : [];
}
function stepOutputPhotos(row: Record<string, unknown>): string[] {
  const p = row?.outputPhotos;
  return Array.isArray(p) ? (p as string[]).filter((u) => !!u) : [];
}
// T161: per-photo annotation helpers — null-safe, backward-compat (old records have no annotations)
interface PhotoAnnotation { url?: string; label?: string | null; note?: string | null }
function stepInputPhotoAnnotations(row: Record<string, unknown>): (PhotoAnnotation | null)[] {
  const a = row?.inputPhotoAnnotations;
  return Array.isArray(a) ? (a as (PhotoAnnotation | null)[]) : [];
}
function stepOutputPhotoAnnotations(row: Record<string, unknown>): (PhotoAnnotation | null)[] {
  const a = row?.outputPhotoAnnotations;
  return Array.isArray(a) ? (a as (PhotoAnnotation | null)[]) : [];
}
// legacy 回退: 旧数据无 input/output 分组 (两组都空), 仍用合并 photos 兜底展示, 不丢历史证据.
function hasSplitPhotos(row: Record<string, unknown>): boolean {
  return stepInputPhotos(row).length > 0 || stepOutputPhotos(row).length > 0;
}
function stepLegacyFallbackPhotos(row: Record<string, unknown>): string[] {
  return hasSplitPhotos(row) ? [] : stepPhotos(row);
}
// 单元3 (F006 三阶段): 道阶段标记 — AWAITING_INPUT 待投料 / IN_PRODUCTION 生产中 / COMPLETED 已完工.
// null (legacy 无阶段字段) → 不显示徽标.
function phaseTag(phase: unknown): { text: string; type: 'info' | 'warning' | 'success' } | null {
  const map: Record<string, { text: string; type: 'info' | 'warning' | 'success' }> = {
    AWAITING_INPUT: { text: '待投料', type: 'info' },
    IN_PRODUCTION: { text: '生产中', type: 'warning' },
    COMPLETED: { text: '已完工', type: 'success' }
  };
  const key = typeof phase === 'string' ? phase.toUpperCase() : '';
  return map[key] || null;
}
function stepSegments(row: Record<string, unknown>): Array<Record<string, unknown>> {
  const s = row?.laborSegments;
  return Array.isArray(s) ? (s as Array<Record<string, unknown>>) : [];
}
function stepByproducts(row: Record<string, unknown>): Array<Record<string, unknown>> {
  const b = row?.byproducts;
  return Array.isArray(b) ? (b as Array<Record<string, unknown>>) : [];
}
// 某道是否有任意传统报工细节 (决定展开行是否有内容显示, 无则展示"本道无补充明细")
function hasTraditionalDetail(row: Record<string, unknown>): boolean {
  return (
    stepPhotos(row).length > 0 ||
    stepInputPhotos(row).length > 0 ||
    stepOutputPhotos(row).length > 0 ||
    stepSegments(row).length > 0 ||
    stepByproducts(row).length > 0 ||
    row?.processedQuantity != null ||
    row?.stageOutputQuantity != null ||
    row?.segmentWasteQuantity != null ||
    row?.wasteQuantity != null ||
    row?.sampleRetainQuantity != null
  );
}
// 工时段单行文案: "08:00-10:00 3人 焯水" (note 可缺省)
function segmentQtyText(seg: Record<string, unknown>, qtyKey: string, unitKey: string, label: string): string {
  const qty = seg?.[qtyKey];
  if (qty == null || String(qty).trim() === '') return '';
  const unit = (seg?.[unitKey] as string) || '';
  return `${label}${fmtDash(qty)}${unit}`;
}
function segmentText(seg: Record<string, unknown>): string {
  const st = (seg?.startTime as string) || '';
  const et = (seg?.endTime as string) || '';
  const time = st || et ? `${st || '?'}-${et || '?'}` : '';
  const hc = seg?.headcount != null ? `${seg.headcount}人` : '';
  const note = (seg?.note as string) || '';
  return [time, hc, note].filter((x) => !!x).join(' ') || '—';
}
// 副产物单条文案: "料头 24.2kg"
function byproductText(bp: Record<string, unknown>): string {
  const name = (bp?.name as string) || '副产物';
  const qty = bp?.quantity != null ? fmtDash(bp.quantity) : '—';
  const unit = (bp?.unit as string) || '';
  return `${name} ${qty}${unit ? unit : ''}`.trim();
}
// 批次级总损耗 / 总留样是否需在 KPI 汇总区显示 (任一非 null)
const hasBatchTraditionalSummary = computed(() => {
  const yd = yieldData.value;
  return !!yd && (yd.totalWaste != null || yd.totalSampleRetain != null);
});

// T161: 批次所有逐道报工证据照片/视频 — 跨步骤平铺 (用于总数徽章 + 报工证据相册卡).
// 覆盖三种来源: inputPhotos (三阶段投入) / outputPhotos (三阶段产出) / photos (legacy 合并).
// legacy 行 (input+output 都空) 只取 photos 避免重复计数.
interface GalleryItem {
  url: string;
  kind: EvidenceMediaKind;
  processOrder: number;
  processName: string;
  phase: 'input' | 'output' | 'legacy';
}

const evidenceGallery = computed((): GalleryItem[] => {
  const steps: Record<string, unknown>[] = yieldData.value?.steps ?? [];
  const items: GalleryItem[] = [];
  const seen = new Set<string>();

  for (const step of steps) {
    const order = (step.processOrder as number) ?? 0;
    const name = (step.processName as string) || ('第' + order + '道');

    const addUrls = (urls: string[], phase: GalleryItem['phase']) => {
      for (const url of urls) {
        if (url && !seen.has(url)) {
          seen.add(url);
          items.push({ url, kind: isEvidenceVideoUrl(url) ? 'video' : 'image', processOrder: order, processName: name, phase });
        }
      }
    };

    const inPhotos = stepInputPhotos(step);
    const outPhotos = stepOutputPhotos(step);
    if (inPhotos.length > 0 || outPhotos.length > 0) {
      // Split-phase data: add input then output
      addUrls(inPhotos, 'input');
      addUrls(outPhotos, 'output');
    } else {
      // Legacy data: use combined photos
      addUrls(stepPhotos(step), 'legacy');
    }
  }
  return items;
});

const totalEvidenceCount = computed(() => evidenceGallery.value.length);
const hasEvidenceGallery = computed(() => hasYield.value && totalEvidenceCount.value > 0);

// Gallery lightbox: image-only URLs in order (for el-image preview-src-list)
const galleryImageUrls = computed(() => evidenceGallery.value.filter(i => i.kind === 'image').map(i => i.url));

function galleryImageInitialIndex(url: string): number {
  const idx = galleryImageUrls.value.indexOf(url);
  return idx >= 0 ? idx : 0;
}

function phaseLabel(phase: GalleryItem['phase']): string {
  if (phase === 'input') return '投入';
  if (phase === 'output') return '产出';
  return '证据';
}

// G6/G7 Wave 4: WIP 区 — 仅有 WIP 行时显示
const hasWip = computed(() => wipRows.value.length > 0);
function getWipStatusText(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: '可领用',
    DEPLETED: '已领空',
    RETURNED: '已退回'
  };
  return map[status?.toUpperCase()] || status || '-';
}
function getWipStatusType(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: 'success',
    DEPLETED: 'info',
    RETURNED: 'warning'
  };
  return map[status?.toUpperCase()] || 'info';
}

// P0-2 review fix: 末道产出单位 (份/盒) ≠ 批次原计划单位 (kg) 时, 后端已把 efficiency/unitCost
// 置 null (跨单位无意义)。前端据 plannedUnit≠unit 显诚实提示, 而非裸 "-" (易误读为"无数据")。
// 镜像 cumulativeDisplay 跨单位做法。同单位批次 plannedUnit 为 null → 走原 formatPercent/formatCost。
const isCrossUnit = computed(() => {
  const pu = batch.value?.plannedUnit;
  const u = batch.value?.unit;
  return pu != null && pu !== '' && u != null && pu !== u;
});
const efficiencyDisplay = computed(() =>
  isCrossUnit.value ? '跨单位不可比' : formatPercent(batch.value?.efficiency));
const closedLoopCost = computed(() => batchCost.value?.hasData === true ? batchCost.value : null);
const displayMaterialCost = computed(() => closedLoopCost.value?.rawMaterialCost ?? batch.value?.materialCost);
const displayLaborCost = computed(() => closedLoopCost.value?.laborCost ?? batch.value?.laborCost);
const displayEquipmentCost = computed(() => closedLoopCost.value?.equipmentCost ?? batch.value?.equipmentCost);
const displayOtherCost = computed(() => closedLoopCost.value?.otherCost ?? batch.value?.otherCost);
const displayTotalCost = computed(() => closedLoopCost.value?.totalCost ?? batch.value?.totalCost);
const displayUnitCost = computed(() => closedLoopCost.value?.perBoxCost ?? batch.value?.unitCost);
const displayCostUnit = computed(() => batch.value?.unit || displayActualUnit.value || '');
const unitCostDisplay = computed(() => {
  if (closedLoopCost.value) return formatCost(displayUnitCost.value);
  return isCrossUnit.value ? '跨单位不可比' : formatCost(batch.value?.unitCost);
});

// T140: 工序任务 helpers
function getTaskStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待开工',
    IN_PROGRESS: '进行中',
    COMPLETED: '已完工',
    SKIPPED: '已跳过',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status || '-';
}

function getTaskStatusType(status: string): 'info' | 'primary' | 'success' | 'warning' | 'danger' {
  const map: Record<string, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
    PENDING: 'info',
    IN_PROGRESS: 'primary',
    COMPLETED: 'success',
    SKIPPED: 'warning',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function openTaskDrawer(task: WorkProcessTaskItem) {
  selectedTask.value = task;
  taskDrawerVisible.value = true;
}

function getTimelineIcon(type: string) {
  const map: Record<string, string> = {
    CREATED: 'primary',
    STARTED: 'primary',
    PAUSED: 'warning',
    RESUMED: 'primary',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[type?.toUpperCase()] || 'primary';
}

// ===== SP2: 整单撤回 =====
const reversalDialogVisible = ref(false);
const reversalSubmitting = ref(false);
const reversalError = ref<{ message: string; actionHint?: string } | null>(null);

const REVERSAL_REASONS = [
  '录入错误',
  '产品变更',
  '质量问题',
  '计划调整',
  '其他',
] as const;
const reversalReasonSelected = ref('录入错误');
const reversalReasonOther = ref('');

function openReversalDialog() {
  reversalError.value = null;
  reversalReasonSelected.value = '录入错误';
  reversalReasonOther.value = '';
  reversalDialogVisible.value = true;
}

async function submitReversal() {
  if (!factoryId.value || !batchId.value) return;

  const selected = reversalReasonSelected.value;
  const other = reversalReasonOther.value.trim();
  const reason = selected === '其他'
    ? (other || '其他')
    : (other ? `${selected}: ${other}` : selected);

  reversalSubmitting.value = true;
  reversalError.value = null;

  try {
    const res = await post(`/${factoryId.value}/processing/batches/${batchId.value}/reversal`, { reason });
    reversalDialogVisible.value = false;

    if (res.data && (res.data as { status?: string }).status === 'DONE') {
      ElMessage.success('整单已直接撤回（无报工数据），批次已取消');
    } else {
      ElMessage.success('撤回申请已提交，请等待主管审批');
    }
    // 刷新详情页数据
    loadData();
  } catch (e: unknown) {
    // 409: 守卫拦截 — 显示后端真实 message + next action (fool-proof Rule 5)
    const err = e as { status?: number; message?: string; actionHint?: string };
    if (err?.status === 409) {
      const msg = err.message || '无法提交撤回申请';
      reversalError.value = {
        message: msg,
        actionHint: err.actionHint || undefined,
      };
      // sticky toast (duration:0 已由 request.ts interceptor 处理)
    }
    // 其他错误由 interceptor 已 toast，无需重复
  } finally {
    reversalSubmitting.value = false;
  }
}

function goToReversalList() {
  router.push('/production/reversals');
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <!-- Empty state -->
    <el-card v-if="!loading && !batch" shadow="never">
      <el-empty description="批次数据不存在">
        <el-button @click="goBack">返回列表</el-button>
      </el-empty>
    </el-card>

    <template v-if="batch">
      <!-- Header -->
      <div class="detail-header">
        <div class="header-left">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <h2 class="batch-title">{{ batch.batchNumber }}</h2>
          <el-tag :type="getStatusType(batch.status)" size="large">
            {{ getStatusText(batch.status) }}
          </el-tag>
          <!-- Issue #760: 显式标记当前模式 -->
          <el-tag :type="isEditMode ? 'warning' : 'info'" size="large" effect="plain">
            {{ isEditMode ? '编辑模式' : '查看模式' }}
          </el-tag>
        </div>
        <div style="display:flex;gap:8px">
          <!-- Issue #760: edit mode 提供回 list 触发 edit dialog 的入口 -->
          <el-button
            v-if="isEditMode"
            type="primary"
            plain
            @click="goBackToListWithEdit"
          >在列表编辑</el-button>
          <!-- SP2: 整单撤回入口 — 仅对未取消批次显示 -->
          <el-button
            v-if="batch && batch.status !== 'CANCELLED' && canWrite"
            type="danger"
            plain
            @click="openReversalDialog"
          >撤回整单</el-button>
          <!-- Bug2 修复: 本页只读, 报工/工时录入入口在生产计划页, 头部加直达按钮 (次入口, 主提示见下方 banner) -->
          <el-button type="success" plain @click="goToProcessEntry">前往逐道录入</el-button>
          <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        </div>
      </div>

      <!-- Bug2 修复 (fool-proof-design.md Rule 5): 本页全程只读, 明确告知报工/工时录入的真实入口,
           避免用户走"批次→详情"这条路径却找不到任何写入功能 (死胡同) -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      >
        <template #title>
          本页仅供查看，录入报工 / 工时数据请前往「生产计划 → 逐道录入」
          <el-button type="primary" link @click="goToProcessEntry">立即前往 →</el-button>
        </template>
      </el-alert>

      <!-- KPI Cards -->
      <div class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">计划数量</div>
          <div class="kpi-value">{{ formatNum(batch.plannedQuantity) }}</div>
          <div class="kpi-unit">{{ batch.unit || '' }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">实际产量</div>
          <div class="kpi-value" :class="{ 'text-success': Number(displayActualQuantity) > 0 }">
            {{ formatNum(displayActualQuantity) }}
          </div>
          <div class="kpi-unit">{{ displayActualUnit }}</div>
        </div>
        <div v-if="hasYield" class="kpi-card">
          <div class="kpi-label">
            {{ yieldInProgress ? '累计出成率 (进行中)' : '累计出成率' }}
          </div>
          <div class="kpi-value">{{ cumulativeDisplay }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">良品率</div>
          <div class="kpi-value" :class="{
            'text-success': batch.yieldRate >= 95,
            'text-warning': batch.yieldRate >= 80 && batch.yieldRate < 95,
            'text-danger': batch.yieldRate > 0 && batch.yieldRate < 80
          }">
            {{ formatPercent(batch.yieldRate) }}
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">完成效率</div>
          <div class="kpi-value">{{ efficiencyDisplay }}</div>
        </div>
        <div v-if="canViewPrice" class="kpi-card">
          <div class="kpi-label">单位成本</div>
          <div class="kpi-value">{{ unitCostDisplay }}</div>
        </div>
      </div>

      <!-- Detail Sections -->
      <div class="detail-grid">
        <!-- Basic Info -->
        <el-card shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">基本信息</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="批次号">{{ batch.batchNumber }}</el-descriptions-item>
            <el-descriptions-item label="产品类型">{{ batch.productName || batch.productType || '-' }}</el-descriptions-item>
            <el-descriptions-item label="生产状态">
              <el-tag :type="getStatusType(batch.status)" size="small">
                {{ getStatusText(batch.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="质量状态">
              <el-tag v-if="batch.qualityStatus" :type="getQualityStatusType(batch.qualityStatus)" size="small">
                {{ getQualityStatusText(batch.qualityStatus) }}
              </el-tag>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">{{ batch.supervisorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="工人数">{{ batch.workerCount || '-' }} 人</el-descriptions-item>
            <el-descriptions-item label="生产线">{{ batch.equipmentName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="工作时长">{{ formatDuration(batch.workDurationMinutes) }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ formatDateTime(batch.startTime) }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ formatDateTime(batch.endTime) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(batch.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(batch.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="batch.notes" label="备注" :span="2">{{ batch.notes }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- Quantity & Quality -->
        <el-card shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">产量与质量</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="计划数量">{{ formatNum(batch.plannedQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="实际产量">{{ formatNum(batch.actualQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="良品数量">{{ formatNum(batch.goodQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="不良品数量">
              <span :class="{ 'text-danger': batch.defectQuantity > 0 }">
                {{ formatNum(batch.defectQuantity) }} {{ batch.unit }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="良品率">
              <span :class="{
                'text-success': batch.yieldRate >= 95,
                'text-warning': batch.yieldRate >= 80 && batch.yieldRate < 95,
                'text-danger': batch.yieldRate > 0 && batch.yieldRate < 80
              }">
                {{ formatPercent(batch.yieldRate) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="完成效率">{{ efficiencyDisplay }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- Cost Breakdown -->
        <el-card v-if="canViewPrice" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">成本明细</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="原料成本">{{ formatCost(displayMaterialCost) }}</el-descriptions-item>
            <el-descriptions-item label="人工成本">{{ formatCost(displayLaborCost) }}</el-descriptions-item>
            <el-descriptions-item v-if="closedLoopCost" label="辅料/调料">{{ formatCost(closedLoopCost.seasoningCost) }}</el-descriptions-item>
            <el-descriptions-item v-if="closedLoopCost" label="包材成本">{{ formatCost(closedLoopCost.packagingCost) }}</el-descriptions-item>
            <el-descriptions-item label="设备成本">{{ formatCost(displayEquipmentCost) }}</el-descriptions-item>
            <el-descriptions-item label="其他成本">{{ formatCost(displayOtherCost) }}</el-descriptions-item>
            <el-descriptions-item label="总成本">
              <span class="cost-total">{{ formatCost(displayTotalCost) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="单位成本">
              <template v-if="!closedLoopCost && isCrossUnit">跨单位不可比</template>
              <template v-else>{{ formatCost(displayUnitCost) }}/{{ displayCostUnit }}</template>
            </el-descriptions-item>
            <el-descriptions-item v-if="closedLoopCost" label="副产物抵扣">{{ formatCost(closedLoopCost.byproductCredit) }}</el-descriptions-item>
            <el-descriptions-item v-if="closedLoopCost" label="可售单位成本">{{ formatCost(closedLoopCost.sellablePerBoxCost) }}/{{ displayCostUnit }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 单元3: 出成率·逐道报工 (audit YIELD-1/5/6, FE-VUE-6) -->
        <el-card v-if="hasYield" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">出成率 · 逐道报工</span>
            <!-- G8 Wave 4 (C): 进行中标注 (展示层防呆) -->
            <el-tag v-if="yieldInProgress" type="warning" size="small" effect="plain" style="margin-left: 12px">
              进行中
            </el-tag>
            <!-- T161: 证据照片总数徽章 — 提示审核员有多少证据可查 (见下方「报工证据」相册卡) -->
            <el-tag
              v-if="totalEvidenceCount > 0"
              type="info"
              size="small"
              effect="plain"
              style="margin-left: 12px"
            >
              · 共 {{ totalEvidenceCount }} 张证据照片
            </el-tag>
            <!-- SP1 T6: 半成品库存流水入口 (有 WIP 行时才显示, 运营可从批次直接跳 SFI 明细) -->
            <el-button
              v-if="hasWip"
              type="warning"
              link
              size="small"
              style="float: right; margin-right: 8px"
              @click="router.push({ path: '/warehouse/wip-batches', query: { batchId: String(batchId) } })"
            >
              查看半成品库存流水
            </el-button>
            <!-- 单元 F (F006 REQ-21): 以订单的模式呈现 — 查看本订单下全部批次整体出成率 -->
            <el-button
              v-if="hasOrderContext"
              type="primary"
              link
              size="small"
              style="float: right"
              @click="openOrderYield"
            >
              查看本订单整体出成率
            </el-button>
          </template>
          <!-- G8 Wave 4 (C): 在制半成品提示条 — 数字偏低且会变, 完工后锁定 -->
          <el-alert
            v-if="yieldInProgress"
            :title="wipInProgressText"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table :data="yieldData.steps" border stripe size="small" style="width: 100%">
            <!-- 适配单元5 (F006 传统报工): 展开行承载证据照片/工时段/副产物/损耗/留样.
                 主表已 11 列, 这些细节放展开行避免横向溢出, 工厂管理者按需点开. -->
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="trad-detail">
                  <template v-if="hasTraditionalDetail(row)">
                    <!-- 单元3 (F006 三阶段): 证据照片拆分 投入照片 / 产出照片, 各自独立 lightbox 画廊.
                         空组 → "—". legacy 行 (两组都空) 回退到合并 photos, 旧数据仍可见. -->
                    <!-- T161: 投入照片 (含逐张标注) -->
                    <div class="trad-item">
                      <span class="trad-label">投入照片</span>
                      <template v-if="stepInputPhotos(row).length > 0">
                        <div
                          v-for="(media, i) in evidenceMediaItems(stepInputPhotos(row))"
                          :key="'in-' + i"
                          class="photo-annot-wrap"
                        >
                          <video
                            v-if="media.kind === 'video'"
                            :src="media.url"
                            class="trad-thumb trad-video"
                            controls
                            preload="metadata"
                          />
                          <el-image
                            v-else
                            :src="media.url"
                            fit="cover"
                            :preview-src-list="evidenceImageUrls(stepInputPhotos(row))"
                            :initial-index="evidenceImageInitialIndex(stepInputPhotos(row), media.url)"
                            class="trad-thumb"
                            preview-teleported
                          />
                          <!-- T161 annotation badge (label + note), only shown when present -->
                          <template v-if="stepInputPhotoAnnotations(row)[i]?.label || stepInputPhotoAnnotations(row)[i]?.note">
                            <div class="photo-annot-badge">
                              <el-tag
                                v-if="stepInputPhotoAnnotations(row)[i]?.label"
                                size="small"
                                type="warning"
                                class="annot-label-tag"
                              >{{ stepInputPhotoAnnotations(row)[i]?.label }}</el-tag>
                              <span
                                v-if="stepInputPhotoAnnotations(row)[i]?.note"
                                class="annot-note-text"
                              >{{ stepInputPhotoAnnotations(row)[i]?.note }}</span>
                            </div>
                          </template>
                        </div>
                      </template>
                      <span v-else class="trad-empty">—</span>
                    </div>
                    <!-- T161: 产出照片 (含逐张标注) -->
                    <div class="trad-item">
                      <span class="trad-label">产出照片</span>
                      <template v-if="stepOutputPhotos(row).length > 0">
                        <div
                          v-for="(media, i) in evidenceMediaItems(stepOutputPhotos(row))"
                          :key="'out-' + i"
                          class="photo-annot-wrap"
                        >
                          <video
                            v-if="media.kind === 'video'"
                            :src="media.url"
                            class="trad-thumb trad-video"
                            controls
                            preload="metadata"
                          />
                          <el-image
                            v-else
                            :src="media.url"
                            fit="cover"
                            :preview-src-list="evidenceImageUrls(stepOutputPhotos(row))"
                            :initial-index="evidenceImageInitialIndex(stepOutputPhotos(row), media.url)"
                            class="trad-thumb"
                            preview-teleported
                          />
                          <!-- T161 annotation badge (label + note), only shown when present -->
                          <template v-if="stepOutputPhotoAnnotations(row)[i]?.label || stepOutputPhotoAnnotations(row)[i]?.note">
                            <div class="photo-annot-badge">
                              <el-tag
                                v-if="stepOutputPhotoAnnotations(row)[i]?.label"
                                size="small"
                                type="success"
                                class="annot-label-tag"
                              >{{ stepOutputPhotoAnnotations(row)[i]?.label }}</el-tag>
                              <span
                                v-if="stepOutputPhotoAnnotations(row)[i]?.note"
                                class="annot-note-text"
                              >{{ stepOutputPhotoAnnotations(row)[i]?.note }}</span>
                            </div>
                          </template>
                        </div>
                      </template>
                      <span v-else class="trad-empty">—</span>
                    </div>
                    <!-- legacy 回退: 旧数据无 input/output 分组时, 用合并 photos 兜底 (新数据此组不显示) -->
                    <div v-if="stepLegacyFallbackPhotos(row).length > 0" class="trad-item">
                      <span class="trad-label">证据照片</span>
                      <template
                        v-for="(media, i) in evidenceMediaItems(stepLegacyFallbackPhotos(row))"
                        :key="'legacy-' + i"
                      >
                        <video
                          v-if="media.kind === 'video'"
                          :src="media.url"
                          class="trad-thumb trad-video"
                          controls
                          preload="metadata"
                        />
                        <el-image
                          v-else
                          :src="media.url"
                          fit="cover"
                          :preview-src-list="evidenceImageUrls(stepLegacyFallbackPhotos(row))"
                          :initial-index="evidenceImageInitialIndex(stepLegacyFallbackPhotos(row), media.url)"
                          class="trad-thumb"
                          preview-teleported
                        />
                      </template>
                    </div>
                    <!-- 工时段: 每段 起-止 N人 备注 -->
                    <div class="trad-item">
                      <span class="trad-label">工时段</span>
                      <template v-if="stepSegments(row).length > 0">
                        <span
                          v-for="(seg, i) in stepSegments(row)"
                          :key="i"
                          class="trad-chip"
                        >{{ segmentText(seg) }}</span>
                      </template>
                      <span v-else class="trad-empty">—</span>
                    </div>
                    <!-- 副产物: 名称 数量单位 列表 -->
                    <div class="trad-item">
                      <span class="trad-label">副产物</span>
                      <template v-if="stepByproducts(row).length > 0">
                        <span
                          v-for="(bp, i) in stepByproducts(row)"
                          :key="i"
                          class="trad-chip trad-chip-by"
                        >{{ byproductText(bp) }}</span>
                      </template>
                      <span v-else class="trad-empty">—</span>
                    </div>
                    <!-- 损耗 / 留样: 数量 null → "—" (非 0) -->
                    <div class="trad-item">
                      <span class="trad-label">损耗</span>
                      <span class="trad-value">{{ fmtDash(row.wasteQuantity, row.outputUnit || '') }}</span>
                      <span class="trad-label" style="margin-left: 24px">留样</span>
                      <span class="trad-value">{{ fmtDash(row.sampleRetainQuantity, row.outputUnit || '') }}</span>
                    </div>
                  </template>
                  <span v-else class="trad-empty">本道无补充明细 (证据照片 / 工时段 / 副产物 / 损耗 / 留样)</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="道" width="60" align="center">
              <template #default="{ row }">{{ row.processOrder }}</template>
            </el-table-column>
            <el-table-column label="工序" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.processName || ('第' + row.processOrder + '道') }}</span>
                <!-- 单元3 (F006 三阶段): 道阶段徽标 — 待投料/生产中/已完工. null (legacy) → 无徽标. -->
                <el-tag
                  v-if="phaseTag(row.phase)"
                  :type="phaseTag(row.phase)!.type"
                  size="small"
                  effect="plain"
                  style="margin-left: 6px"
                >{{ phaseTag(row.phase)!.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="投入" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalInput) }} {{ row.inputUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="产出" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalOutput) }} {{ row.outputUnit || '' }}</template>
            </el-table-column>
            <!-- SP1 双产出: outputKind=SEMI/BOTH 时展示半成品产出量 + semiCode; null/FINISHED → 隐藏 -->
            <el-table-column label="产出类型" width="90" align="center">
              <template #default="{ row }">
                <span v-if="!row.outputKind || row.outputKind === 'FINISHED'">—</span>
                <el-tag v-else-if="row.outputKind === 'SEMI'" type="warning" size="small">纯半成品</el-tag>
                <el-tag v-else-if="row.outputKind === 'BOTH'" type="success" size="small">双产出</el-tag>
                <span v-else>{{ row.outputKind }}</span>
              </template>
            </el-table-column>
            <el-table-column label="半成品产出" width="160" align="right">
              <template #default="{ row }">
                <template v-if="row.semiOutputQuantity != null">
                  <span>{{ formatNum(row.semiOutputQuantity) }} {{ row.semiOutputUnit || '' }}</span>
                  <div v-if="row.semiCode" class="semi-code-text">{{ row.semiCode }}</div>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="出成率" width="110" align="center">
              <template #default="{ row }">
                <span v-if="!row.unitComparable">—</span>
                <span v-else :class="{ 'text-danger': row.yieldAlert }" :title="row.yieldAlert || ''">
                  {{ formatPercent(row.yieldRate * 100) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="结转" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.carryover == null">—</span>
                <span v-else :class="{ 'text-warning': Number(row.carryover) > 0 }">{{ formatNum(row.carryover) }}</span>
              </template>
            </el-table-column>
            <!-- P1-3 (G4): 逐道人数/工时 (张权 "用了多少人 / 一个人一个小时"); null 显 "—" -->
            <el-table-column label="人数" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.totalWorkers == null">—</span>
                <span v-else>{{ row.totalWorkers }} 人</span>
              </template>
            </el-table-column>
            <el-table-column label="工时" width="100" align="right">
              <template #default="{ row }">
                <span v-if="row.totalWorkMinutes == null">—</span>
                <span v-else>{{ row.totalWorkMinutes }} 分钟</span>
              </template>
            </el-table-column>
            <!-- 适配单元5 (F006 传统报工): 证据列 — 首张缩略图 + 📷 张数; 点击展开行看全部细节.
                 无照片 → "—". 镜像 AttachmentList 的 el-image + preview-src-list. -->
            <el-table-column label="证据" width="92" align="center">
              <template #default="{ row }">
                <div v-if="stepPhotos(row).length > 0" class="evidence-cell">
                  <video
                    v-if="firstEvidenceMedia(row)?.kind === 'video'"
                    :src="firstEvidenceMedia(row)?.url"
                    class="evidence-thumb evidence-video"
                    controls
                    preload="metadata"
                  />
                  <el-image
                    v-else
                    :src="firstEvidenceMedia(row)?.url || ''"
                    fit="cover"
                    :preview-src-list="evidenceImageUrls(stepPhotos(row))"
                    :initial-index="evidenceImageInitialIndex(stepPhotos(row), firstEvidenceMedia(row)?.url || '')"
                    class="evidence-thumb"
                    preview-teleported
                  />
                  <span v-if="stepPhotos(row).length > 1" class="evidence-badge">📷{{ stepPhotos(row).length }}</span>
                </div>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="过程处理" width="120" align="right">
              <template #default="{ row }">{{ fmtDash(row.processedQuantity, row.processedUnit || '') }}</template>
            </el-table-column>
            <el-table-column label="阶段产出" width="120" align="right">
              <template #default="{ row }">{{ fmtDash(row.stageOutputQuantity, row.stageOutputUnit || '') }}</template>
            </el-table-column>
            <el-table-column label="过程损耗" width="120" align="right">
              <template #default="{ row }">{{ fmtDash(row.segmentWasteQuantity, row.segmentWasteUnit || '') }}</template>
            </el-table-column>
            <!-- A.6 逐道成本: 人工/材料/小计. null (未配工价 / 无原料单价) → "—" (非 ¥0). canViewPrice 门控. -->
            <el-table-column v-if="canViewPrice" label="人工成本" width="120" align="right">
              <template #default="{ row }">{{ formatCostDash(row.laborCost) }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" label="材料成本" width="120" align="right">
              <template #default="{ row }">{{ formatCostDash(row.materialCost) }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" label="小计" width="120" align="right">
              <template #default="{ row }">{{ formatCostDash(row.stepCost) }}</template>
            </el-table-column>
          </el-table>
          <div class="yield-summary">
            合计: {{ formatNum(yieldData.firstStepInput) }} {{ yieldData.firstStepInputUnit || '' }}
            → {{ formatNum(yieldData.lastStepOutput) }} {{ yieldData.lastStepOutputUnit || '' }}
            &nbsp;累计出成率 {{ cumulativeDisplay }}
            <!-- P1-3 (G4): 整批工时/人次 — 跨道相加是"人次"(同一人多道重复计), 诚实标注 -->
            <span v-if="yieldData.totalWorkMinutes != null">&nbsp;·&nbsp;总工时 {{ yieldData.totalWorkMinutes }} 分钟</span>
            <span v-if="yieldData.totalWorkers != null">&nbsp;·&nbsp;总人次 {{ yieldData.totalWorkers }}</span>
          </div>
          <!-- 适配单元5 (F006 传统报工): 批次级 总损耗 / 总留样 汇总. null (未记录) → "—" (非 0). -->
          <div v-if="hasBatchTraditionalSummary" class="yield-trad-summary">
            <span class="trad-summary-item">总损耗 {{ fmtDash(yieldData.totalWaste, yieldData.lastStepOutputUnit || '') }}</span>
            <span class="trad-summary-item">总留样 {{ fmtDash(yieldData.totalSampleRetain, yieldData.lastStepOutputUnit || '') }}</span>
          </div>
          <!-- A.6 整批逐道成本汇总: 总人工/总材料/总成本. null (无法计算) → "—" (非 ¥0). canViewPrice 门控. -->
          <div v-if="canViewPrice" class="yield-cost-summary">
            <span class="cost-item">总人工成本 {{ formatCostDash(yieldData.totalLaborCost) }}</span>
            <span class="cost-item">总材料成本 {{ formatCostDash(yieldData.totalMaterialCost) }}</span>
            <span class="cost-item cost-item-total">总成本 {{ formatCostDash(yieldData.totalCost) }}</span>
          </div>
        </el-card>

        <!-- T161: 报工证据相册卡 — 跨步骤平铺所有证据照片/视频, 无需逐行展开.
             只在有逐道报工数据 (hasYield) 且至少有 1 张证据 (hasEvidenceGallery) 时显示.
             总数 = 0 时整块隐藏, 诚实空态由各步骤展开行内承载. -->
        <el-card v-if="hasEvidenceGallery" shadow="never" class="detail-card gallery-card">
          <template #header>
            <span class="section-title">报工证据</span>
            <span class="section-meta">共 {{ totalEvidenceCount }} 张 · 按工序顺序排列</span>
          </template>
          <div class="gallery-grid">
            <div
              v-for="(item, idx) in evidenceGallery"
              :key="idx"
              class="gallery-item"
            >
              <!-- video -->
              <video
                v-if="item.kind === 'video'"
                :src="item.url"
                class="gallery-thumb gallery-thumb-video"
                controls
                preload="metadata"
              />
              <!-- image — opens lightbox for all images in the gallery -->
              <el-image
                v-else
                :src="item.url"
                fit="cover"
                :preview-src-list="galleryImageUrls"
                :initial-index="galleryImageInitialIndex(item.url)"
                class="gallery-thumb"
                preview-teleported
              />
              <!-- label: 道序 · 工序名 · 阶段 -->
              <div class="gallery-label">
                <span class="gallery-order">第{{ item.processOrder }}道</span>
                <span class="gallery-process-name">{{ item.processName }}</span>
                <el-tag
                  :type="item.phase === 'input' ? 'primary' : item.phase === 'output' ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                  class="gallery-phase-tag"
                >{{ phaseLabel(item.phase) }}</el-tag>
              </div>
            </div>
          </div>
        </el-card>

        <!-- G6/G7 Wave 4: 半成品库存 (WIP) — 每道工序中间品产出/已领/余额/状态.
             端点 YieldReportController GET /wip. 无 WIP → 整块隐藏 (诚实空态). -->
        <el-card v-if="hasWip" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">半成品库存 (WIP)</span>
            <span class="section-meta">共 {{ wipRows.length }} 道</span>
          </template>
          <el-table :data="wipRows" border stripe size="small" style="width: 100%">
            <el-table-column label="道" width="60" align="center">
              <template #default="{ row }">{{ row.processOrder ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="工序" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.processName || ('第' + (row.processOrder ?? '?') + '道') }}</template>
            </el-table-column>
            <el-table-column label="工序批次号" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.intermediateBatchNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="产出" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.producedQuantity) }} {{ row.unit || '' }}</template>
            </el-table-column>
            <el-table-column label="已领" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.consumedQuantity) }} {{ row.unit || '' }}</template>
            </el-table-column>
            <el-table-column label="余额" width="120" align="right">
              <template #default="{ row }">
                <span :class="{ 'text-warning': Number(row.availableQuantity) > 0 }">
                  {{ formatNum(row.availableQuantity) }} {{ row.unit || '' }}
                </span>
              </template>
            </el-table-column>
            <!-- 段1: 双出成率 — 对上工序 / 对原料 (单位不可比 → "—", 诚实留空) -->
            <el-table-column label="对上工序" width="110" align="right">
              <template #default="{ row }">
                {{ row.stepYieldRate != null ? (Number(row.stepYieldRate) * 100).toFixed(1) + '%' : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="对原料" width="110" align="right">
              <template #default="{ row }">
                {{ row.cumulativeYieldRate != null ? (Number(row.cumulativeYieldRate) * 100).toFixed(1) + '%' : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="getWipStatusType(row.status)" size="small">{{ getWipStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- T4-D4 (issue #533): F006 customer asked for raw_material consumption visibility on
             batch detail. Backend /processing/material-consumptions/batch/{id} (MaterialConsumption-
             Controller:151) returns enriched rows; this card renders them. Field names verified against
             enrichConsumptionWithMaps response Map (post-review fix for reviewer C1/I1/I2/I3/I4). -->
        <el-card v-if="consumptions.length > 0" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">原料消耗记录</span>
            <span class="section-meta">共 {{ consumptions.length }} 条</span>
          </template>
          <el-table :data="consumptions" border stripe size="small" style="width: 100%">
            <el-table-column prop="materialTypeName" label="原料" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.materialTypeName || row.materialTypeId || '-' }}</template>
            </el-table-column>
            <el-table-column prop="batchNumber" label="批次号" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.batchNumber || '-' }}</template>
            </el-table-column>
            <el-table-column prop="quantity" label="消耗数量" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.quantity) }}</template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="80" align="center">
              <template #default="{ row }">{{ row.unit || '-' }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
              <template #default="{ row }">{{ formatCost(row.unitPrice) }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="totalCost" label="小计" width="120" align="right">
              <template #default="{ row }">{{ formatCost(row.totalCost) }}</template>
            </el-table-column>
            <el-table-column prop="consumptionTime" label="消耗时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.consumedAt || row.consumptionTime || row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- T140: 工序明细 section (from WorkProcessTask list) -->
        <el-card shadow="never" class="detail-card process-tasks-card">
          <template #header>
            <div class="section-header-row">
              <span class="section-title">工序明细</span>
              <span v-if="workProcessTasks.length > 0" class="section-meta">共 {{ workProcessTasks.length }} 道</span>
            </div>
          </template>
          <!-- 诚实空态: 批次未 spawn 工序任务 (转为批次时还未点"生成工序任务") -->
          <el-empty
            v-if="workProcessTasks.length === 0"
            description="暂无工序任务 (批次生产启动后可生成)"
            :image-size="60"
          />
          <div v-else class="process-task-list">
            <div
              v-for="task in workProcessTasks"
              :key="task.id"
              class="process-task-row"
              @click="openTaskDrawer(task)"
            >
              <!-- 道序号 -->
              <div class="task-order">
                <span class="order-badge">{{ task.processOrder }}</span>
              </div>
              <!-- 工序名 + 状态 -->
              <div class="task-main">
                <span class="task-name">{{ task.processName || ('第' + task.processOrder + '道工序') }}</span>
                <el-tag
                  :type="getTaskStatusType(task.status)"
                  size="small"
                  class="task-status-tag"
                >
                  {{ getTaskStatusText(task.status) }}
                </el-tag>
              </div>
              <!-- 负责人 (T142: 显示真实姓名; fallback 到 #ID; 未分配时 "未分配") -->
              <div class="task-assignee">
                <span v-if="task.assignedTo != null" class="task-assignee-text">
                  <el-icon style="vertical-align: middle; margin-right: 2px"><User /></el-icon>
                  {{ task.assignedToName || '#' + task.assignedTo }}
                </span>
                <span v-else class="task-assignee-empty">未分配</span>
              </div>
              <!-- 实际产出 (已完工时显示) -->
              <div v-if="task.status === 'COMPLETED' && task.actualQuantity != null" class="task-output">
                产出 {{ formatNum(task.actualQuantity) }} {{ task.outputUnit || task.plannedUnit || '' }}
              </div>
              <!-- 箭头 -->
              <div class="task-arrow">›</div>
            </div>
          </div>
        </el-card>

        <!-- Timeline (T140 fixed: backend returns {time, event, status}; also handle {timestamp, title, action}) -->
        <el-card v-if="timeline.length > 0" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">生产时间线</span>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="(item, index) in timeline"
              :key="index"
              :type="getTimelineIcon(item.status || item.type || item.action)"
              :timestamp="formatDateTime(item.time || item.timestamp || item.createdAt)"
              placement="top"
            >
              <div class="timeline-content">
                <strong>{{ item.event || item.title || item.action || '-' }}</strong>
                <p v-if="item.description || item.notes">{{ item.description || item.notes }}</p>
                <p v-if="item.operatorName" class="timeline-operator">操作人: {{ item.operatorName }}</p>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
        <!-- Fallback: timeline empty (no events yet, batch just created without startTime/endTime) -->
        <el-card v-else shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">生产时间线</span>
          </template>
          <el-empty description="暂无时间线记录" :image-size="60" />
        </el-card>

        <!-- T161: 附件卡澄清 — 此处是通用附件 (上传的单据/质检报告), 非逐道报工证据.
             报工证据照片见上方「报工证据」相册卡 (来自手机端逐道报工). -->
        <el-card class="section-card" shadow="never" style="margin-top: 16px">
          <template #header>
            <span class="section-title">附件 <span class="attachment-card-note">(非报工证据)</span></span>
            <div class="attachment-card-hint">报工证据照片见上方「报工证据」相册 / 逐道展开</div>
          </template>
          <AttachmentList
            entity-type="PRODUCTION_BATCH"
            :entity-id="String(batchId)"
            :factory-id="factoryId"
            :refresh-key="attachmentRefreshKey"
          />
          <div style="margin-top: 12px">
            <AttachmentUploadButton
              entity-type="PRODUCTION_BATCH"
              :entity-id="String(batchId)"
              :factory-id="factoryId"
              business-tag="BATCH_EVIDENCE"
              @uploaded="attachmentRefreshKey++"
            />
          </div>
        </el-card>
      </div>
    </template>

    <!-- T140: 工序任务详情抽屉 (点击工序行弹出) -->
    <el-drawer
      v-model="taskDrawerVisible"
      :title="selectedTask ? (selectedTask.processName || ('第' + selectedTask.processOrder + '道工序')) + ' 详情' : '工序详情'"
      direction="rtl"
      size="420px"
    >
      <template v-if="selectedTask">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="道序号">第 {{ selectedTask.processOrder }} 道</el-descriptions-item>
          <el-descriptions-item label="工序名">{{ selectedTask.processName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工序类别">{{ selectedTask.processCategory || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getTaskStatusType(selectedTask.status)" size="small">
              {{ getTaskStatusText(selectedTask.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="负责人">
            <template v-if="selectedTask.assignedTo != null">
              {{ selectedTask.assignedToName || '#' + selectedTask.assignedTo }}
            </template>
            <span v-else>未分配</span>
          </el-descriptions-item>
          <el-descriptions-item label="计划数量">
            <template v-if="selectedTask.plannedQuantity != null">
              {{ formatNum(selectedTask.plannedQuantity) }} {{ selectedTask.plannedUnit || '' }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="实际产出">
            <template v-if="selectedTask.status === 'COMPLETED' && selectedTask.actualQuantity != null">
              {{ formatNum(selectedTask.actualQuantity) }} {{ selectedTask.outputUnit || selectedTask.plannedUnit || '' }}
            </template>
            <span v-else-if="selectedTask.status === 'COMPLETED'">—</span>
            <span v-else class="text-warning">待报工</span>
          </el-descriptions-item>
          <el-descriptions-item label="标准出成率">
            <template v-if="selectedTask.standardYieldMin != null || selectedTask.standardYieldMax != null">
              {{ selectedTask.standardYieldMin != null ? formatPercent(selectedTask.standardYieldMin * 100) : '—' }}
              ~
              {{ selectedTask.standardYieldMax != null ? formatPercent(selectedTask.standardYieldMax * 100) : '—' }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="计划开始">{{ formatDateTime(selectedTask.plannedStartAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="计划结束">{{ formatDateTime(selectedTask.plannedEndAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="实际开始">{{ formatDateTime(selectedTask.actualStartAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="实际结束">{{ formatDateTime(selectedTask.actualEndAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">
            <template v-if="selectedTask.actualMinutes != null">{{ formatDuration(selectedTask.actualMinutes) }}</template>
            <template v-else-if="selectedTask.estimatedMinutes != null">预计 {{ formatDuration(selectedTask.estimatedMinutes) }}</template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatDateTime(selectedTask.completedAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedTask.notes" label="备注" :span="1">
            {{ selectedTask.notes }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

    <!-- 单元 F (F006 REQ-21 "以订单的模式呈现…分订单分产品分工序"): 订单整体出成率弹窗 -->
    <el-dialog
      v-model="orderYieldVisible"
      title="本订单整体出成率"
      width="760px"
      append-to-body
    >
      <div v-loading="orderYieldLoading">
        <el-alert
          v-if="orderYieldError"
          :title="orderYieldError"
          type="info"
          :closable="false"
          show-icon
        />
        <template v-else-if="orderYield">
          <div class="order-yield-meta">
            订单号: {{ orderYield.orderId }} · 共 {{ orderYield.batchCount }} 个批次
          </div>
          <el-empty
            v-if="orderYield.batchCount === 0"
            description="该订单下暂无生产批次"
          />
          <template v-else>
            <el-table :data="orderYield.batches" border stripe size="small" style="width: 100%">
              <el-table-column label="批次号" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.batchNumber || ('#' + (row.batchId ?? '-')) }}</template>
              </el-table-column>
              <el-table-column label="首道投入" width="130" align="right">
                <template #default="{ row }">{{ formatNum(row.firstStepInput) }} {{ row.firstStepInputUnit || '' }}</template>
              </el-table-column>
              <el-table-column label="末道产出" width="130" align="right">
                <template #default="{ row }">{{ formatNum(row.lastStepOutput) }} {{ row.lastStepOutputUnit || '' }}</template>
              </el-table-column>
              <el-table-column label="累计出成率" width="120" align="center">
                <template #default="{ row }">
                  <span>{{ formatRateDash(row.cumulativeYieldRate) }}</span>
                  <el-tag v-if="row.inProgress" type="warning" size="small" effect="plain" style="margin-left: 6px">
                    进行中
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
            <!-- 订单整体合计行: 单位不可比时 totalFirstInput/overallYieldRate 后端返 null → 显 "—" -->
            <div class="order-yield-summary">
              <span>
                订单合计:
                {{ orderYield.totalFirstInput != null ? formatNum(orderYield.totalFirstInput) + ' ' + (orderYield.firstInputUnit || '') : '—' }}
                →
                {{ orderYield.totalLastOutput != null ? formatNum(orderYield.totalLastOutput) + ' ' + (orderYield.lastOutputUnit || '') : '—' }}
              </span>
              <span class="order-yield-rate">
                整体出成率 {{ formatRateDash(orderYield.overallYieldRate) }}
              </span>
            </div>
            <div v-if="orderYield.overallYieldRate == null" class="order-yield-note">
              各批次单位不一致, 无法跨单位合计 (整体出成率不可比)
            </div>
            <div v-if="canViewPrice" class="order-yield-cost">
              <span class="cost-item">总人工成本 {{ formatCostDash(orderYield.totalLaborCost) }}</span>
              <span class="cost-item">总材料成本 {{ formatCostDash(orderYield.totalMaterialCost) }}</span>
              <span class="cost-item cost-item-total">总成本 {{ formatCostDash(orderYield.totalCost) }}</span>
            </div>
          </template>
        </template>
      </div>
    </el-dialog>

    <!-- ===== SP2: 整单撤回 Dialog ===== -->
    <el-dialog
      v-model="reversalDialogVisible"
      title="申请撤回整单"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <!-- 批次上下文（fool-proof Rule 2） -->
      <el-alert
        :title="`撤回批次: ${batch?.batchNumber || '#' + batchId}`"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:16px"
      >
        <template #default>
          <div style="font-size:13px;color:#606266;line-height:1.6;margin-top:4px">
            产品: <strong>{{ batch?.productName || batch?.productType || '—' }}</strong><br>
            计划数量: <strong>{{ batch?.plannedQuantity != null ? batch.plannedQuantity + ' ' + (batch.unit || '') : '—' }}</strong><br>
            当前状态: <strong>{{ batch ? getStatusText(batch.status) : '—' }}</strong>
          </div>
        </template>
      </el-alert>

      <!-- 409 守卫拦截错误（fool-proof Rule 5: 显示后端 message + 跳转） -->
      <el-alert
        v-if="reversalError"
        :title="reversalError.message"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom:16px"
      >
        <template v-if="reversalError.actionHint" #default>
          <el-button
            type="danger"
            link
            style="margin-top:4px;font-size:12px"
            @click="goToReversalList"
          >查看撤回申请列表</el-button>
        </template>
      </el-alert>

      <!-- 撤回原因（fool-proof Rule 3: dropdown + 其他才显 textarea） -->
      <div style="margin-bottom:8px">
        <span style="font-size:13px;font-weight:500;color:#303133">撤回原因</span>
        <span style="color:#F56C6C;margin-left:2px">*</span>
      </div>
      <el-select
        v-model="reversalReasonSelected"
        placeholder="选择撤回原因"
        style="width:100%;margin-bottom:10px"
      >
        <el-option label="录入错误" value="录入错误" />
        <el-option label="产品变更" value="产品变更" />
        <el-option label="质量问题" value="质量问题" />
        <el-option label="计划调整" value="计划调整" />
        <el-option label="其他" value="其他" />
      </el-select>
      <el-input
        v-if="reversalReasonSelected === '其他'"
        v-model="reversalReasonOther"
        type="textarea"
        :rows="3"
        placeholder="请补充说明撤回原因"
        maxlength="300"
        show-word-limit
        style="margin-bottom:10px"
      />
      <el-input
        v-else
        v-model="reversalReasonOther"
        type="textarea"
        :rows="2"
        :placeholder="`可选: 补充说明（已选: ${reversalReasonSelected}）`"
        maxlength="200"
        show-word-limit
      />

      <el-alert
        title="提交后将通知主管审批。若该批次无报工数据，将直接撤回。下游已领用或成品已出库时会被系统拦截。"
        type="info"
        :closable="false"
        show-icon
        style="margin-top:12px"
      />

      <template #footer>
        <div style="display:flex;justify-content:flex-end;gap:8px">
          <el-button @click="reversalDialogVisible = false">取消</el-button>
          <el-button
            type="danger"
            :loading="reversalSubmitting"
            @click="submitReversal"
          >确认提交撤回申请</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  overflow-y: auto;
  padding: 16px 20px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .batch-title {
    font-size: 18px;
    font-weight: 600;
    margin: 0;
    color: var(--text-color-primary, #303133);
  }
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 16px 20px;
  text-align: center;

  .kpi-label {
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
    margin-bottom: 8px;
  }

  .kpi-value {
    font-size: 24px;
    font-weight: 700;
    color: var(--text-color-primary, #303133);
    line-height: 1.2;
  }

  .kpi-unit {
    font-size: 12px;
    color: var(--text-color-secondary, #909399);
    margin-top: 4px;
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-card {
  :deep(.el-card__header) {
    padding: 12px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-color-primary, #303133);
}

.section-meta {
  font-size: 13px;
  color: #909399;
  margin-left: 12px;
}

.cost-total {
  font-weight: 700;
  color: var(--el-color-primary);
  font-size: 15px;
}

.text-success { color: #67C23A; }
.text-warning { color: #E6A23C; }
.text-danger { color: #F56C6C; }

.yield-summary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  font-weight: 600;
  color: var(--text-color-primary, #303133);
}

.yield-cost-summary {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  font-size: 14px;
  color: var(--text-color-secondary, #606266);

  .cost-item {
    font-weight: 500;
  }

  .cost-item-total {
    font-weight: 700;
    color: var(--el-color-primary);
  }
}

/* 适配单元5 (F006 传统报工): 批次级总损耗/总留样汇总 */
.yield-trad-summary {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  font-size: 14px;
  color: var(--text-color-secondary, #606266);

  .trad-summary-item {
    font-weight: 500;
  }
}

/* 适配单元5: 主表证据列 — 缩略图 + 张数角标 */
.evidence-cell {
  position: relative;
  display: inline-block;
  line-height: 0;
}

.evidence-thumb {
  width: 40px;
  height: 40px;
  border-radius: 4px;
  cursor: pointer;
  object-fit: cover;
  background: #111827;
}

.evidence-video {
  display: block;
}

.evidence-badge {
  position: absolute;
  right: -6px;
  bottom: -4px;
  font-size: 10px;
  line-height: 1;
  padding: 1px 3px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
}

/* 适配单元5: 展开行 — 证据照片/工时段/副产物/损耗/留样 细节 */
.trad-detail {
  padding: 8px 16px 8px 48px;
  background: var(--el-fill-color-lighter, #fafafa);
}

.trad-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  padding: 4px 0;
  font-size: 13px;
}

.trad-label {
  font-weight: 600;
  color: var(--text-color-secondary, #606266);
  min-width: 56px;
}

.trad-value {
  color: var(--text-color-primary, #303133);
}

.trad-empty {
  color: var(--text-color-placeholder, #c0c4cc);
}

.trad-thumb {
  width: 56px;
  height: 56px;
  border-radius: 6px;
  cursor: pointer;
  object-fit: cover;
  background: #111827;
}

.trad-video {
  display: inline-block;
  vertical-align: middle;
}

/* T161: per-photo annotation badge wrapper */
.photo-annot-wrap {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  margin-right: 8px;
  margin-bottom: 6px;
  vertical-align: top;
}

.photo-annot-badge {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
  max-width: 72px;
}

.annot-label-tag {
  font-size: 10px;
  line-height: 1.2;
  padding: 1px 4px;
  height: auto;
}

.annot-note-text {
  font-size: 10px;
  color: #606266;
  word-break: break-all;
  line-height: 1.3;
}

.trad-chip {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--el-color-info-light-9, #f4f4f5);
  color: var(--text-color-primary, #303133);
  font-size: 12px;
}

.trad-chip-by {
  background: var(--el-color-success-light-9, #f0f9eb);
}

/* 单元 F: 订单出成率弹窗 */
.order-yield-meta {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-color-secondary, #606266);
}

.order-yield-summary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 20px;
  font-weight: 600;
  color: var(--text-color-primary, #303133);

  .order-yield-rate {
    color: var(--el-color-primary);
  }
}

.order-yield-note {
  margin-top: 6px;
  font-size: 13px;
  color: var(--el-color-warning, #e6a23c);
}

.order-yield-cost {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  font-size: 14px;
  color: var(--text-color-secondary, #606266);

  .cost-item {
    font-weight: 500;
  }

  .cost-item-total {
    font-weight: 700;
    color: var(--el-color-primary);
  }
}

.timeline-content {
  p {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
  }

  .timeline-operator {
    font-size: 12px;
    color: var(--text-color-placeholder, #C0C4CC);
  }
}

/* T140: 工序明细 section */
.process-tasks-card {
  :deep(.el-card__body) {
    padding: 0;
  }
}

.section-header-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.process-task-list {
  display: flex;
  flex-direction: column;
}

.process-task-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid var(--border-color-lighter, #ebeef5);

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: var(--el-fill-color-light, #f5f7fa);
  }
}

.task-order {
  flex-shrink: 0;
}

.order-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
  font-size: 13px;
  font-weight: 700;
}

.task-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-color-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-status-tag {
  flex-shrink: 0;
}

.task-assignee {
  flex-shrink: 0;
  font-size: 13px;
}

.task-assignee-text {
  color: var(--text-color-secondary, #606266);
}

.task-assignee-empty {
  color: var(--text-color-placeholder, #c0c4cc);
  font-size: 12px;
}

.task-output {
  flex-shrink: 0;
  font-size: 13px;
  color: #67C23A;
  font-weight: 500;
}

.task-arrow {
  flex-shrink: 0;
  font-size: 18px;
  color: var(--text-color-placeholder, #c0c4cc);
  line-height: 1;
}

@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* T161: 附件卡澄清 */
.attachment-card-note {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-color-placeholder, #c0c4cc);
  margin-left: 4px;
}

.attachment-card-hint {
  font-size: 12px;
  color: var(--text-color-placeholder, #c0c4cc);
  margin-top: 2px;
}

/* T161: 报工证据相册卡 */
.gallery-card {
  :deep(.el-card__header) {
    padding: 12px 20px 8px;
  }
}

.gallery-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 4px 0;
}

.gallery-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 100px;
}

.gallery-thumb {
  width: 100px;
  height: 100px;
  border-radius: 6px;
  cursor: pointer;
  object-fit: cover;
  background: #111827;
  display: block;
  border: 1px solid var(--border-color-lighter, #ebeef5);
}

.gallery-thumb-video {
  cursor: default;
}

.gallery-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  width: 100%;
}

.gallery-order {
  font-size: 11px;
  font-weight: 700;
  color: var(--el-color-primary, #409eff);
}

.gallery-process-name {
  font-size: 11px;
  color: var(--text-color-secondary, #606266);
  text-align: center;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gallery-phase-tag {
  font-size: 10px;
}

/* SP1 T6 双产出: semiCode 副标题 (在半成品产出量下方小字) */
.semi-code-text {
  font-size: 11px;
  color: var(--text-color-secondary, #606266);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 140px;
}
</style>
