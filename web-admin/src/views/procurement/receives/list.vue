<script setup lang="ts">
/**
 * 采购入库 — 收货管理
 *
 * 六扇门 V1 §3.3 (14-requirements-matrix #9): 采购到货扫码/手动录入 + 自动更新库存.
 * Apr 26 audit: backend POST /purchase/receives + GET /receives + /receives/{id}/confirm
 * 都已 R26 P2 verified, 但缺 FE 入口. 本页补齐手动录入路径 (扫码模式 V2 backlog).
 *
 * Backend endpoints (PurchaseController):
 *   GET  /api/mobile/{factoryId}/purchase/receives?page=1&size=20
 *   POST /api/mobile/{factoryId}/purchase/receives                  (create DRAFT)
 *   GET  /api/mobile/{factoryId}/purchase/receives/{receiveId}      (detail)
 *   POST /api/mobile/{factoryId}/purchase/receives/{receiveId}/confirm  (DRAFT → CONFIRMED, 生成物料批次)
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { listManufacturers, type ManufacturerRegistry } from '@/api/manufacturer';
// 单元 G (F006 R-B3): 跨页累计 + 分次收货时序明细 (后端权威值, 替代 page-local 聚合)
import {
  getCumulativeReceived,
  getOrderReceiveSequence,
  type CumulativeReceived,
  type ReceiveSequenceEntry,
} from '@/api/purchaseReceive';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Check, Document, ChatDotRound, List } from '@element-plus/icons-vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { RowActionMenu } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { WH_INBOUND_CONFIG } from '@/components/ai-entry/types';
// Issue #794: 采购入库拍照附件 UI (六扇门 May 7 part 2 L177-180)
// "拍照也可以留个单谱吧... 留个附件类似一个拍照然后一个附件吗也可以的呀"
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';

interface ReceiveRow {
  id: string;
  receiveNumber: string;
  purchaseOrderId?: string;
  purchaseOrderNumber?: string;
  supplierId: string;
  supplierName?: string;
  receiveDate: string;
  status: 'DRAFT' | 'CONFIRMED' | 'CANCELLED';
  warehouseId?: string;
  remark?: string;
  itemCount?: number;
  items?: ReceiveItem[];
  createdAt: string;
  createdByName?: string;
}

interface ReceiveItem {
  id?: string;
  materialTypeId: string;
  materialName: string;
  receivedQuantity: number;
  unit: string;
  unitPrice?: number;
  qcResult?: string;
  factoryNumber?: string;
  originPlace?: string;
  remark?: string;
}

interface SupplierOption { id: string; name: string }
interface MaterialTypeOption { id: string; name: string; code: string; unit: string; unitPrice?: number }
interface PurchaseOrderOption { id: string; orderNumber: string; supplierName?: string; supplierId?: string; status?: string }

const authStore = useAuthStore();
const router = useRouter();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: ReceiveRow) {
  return computeRowActions(
    'whInbound',
    { status: String(row.status || 'PENDING'), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
function handleRowActionClick(actionId: string, row: ReceiveRow) {
  switch (actionId) {
    case 'view-detail': handleDetail(row); break;
    case 'submit': handleConfirm(row); break;
    // 入库单复用 material-requisition 模板 (warehouse:read 准入)
    case 'print-pdf': void safePrint('material-requisition', factoryId.value, String(row.id), { fileName: `入库单_${row.receiveNumber || row.id}` }); break;
    default: ElMessage.info(`Action: ${actionId}`);
  }
}
// AI 智能创建入库单 (Day 9, Issue #780.2)
const aiEntryVisible = ref(false);
function openAiForRow(_row: ReceiveRow) {
  // Row-context AI is future scope; current Day 9 opens drawer for CREATE flow
  aiEntryVisible.value = true;
}
function openAiCreate() {
  aiEntryVisible.value = true;
}
function handleAiFill(params: Record<string, unknown>) {
  // Pre-fill the create dialog with AI-collected data, then open it for user confirmation
  const supplierName = String(params.supplierName || '');
  const matchedSupplier = supplierOptions.value.find(
    (s) => String(s.name || '').includes(supplierName) || supplierName.includes(String(s.name || ''))
  );
  const poNumber = String(params.purchaseOrderNumber || '');
  const matchedPo = poNumber
    ? purchaseOrderOptions.value.find(
        (p) => String(p.orderNumber || '').toUpperCase() === poNumber.toUpperCase()
      )
    : undefined;

  form.value = {
    purchaseOrderId: matchedPo ? String(matchedPo.id) : '',
    supplierId: matchedSupplier ? String(matchedSupplier.id) : (matchedPo?.supplierId || ''),
    receiveDate: String(params.receiveDate || new Date().toISOString().slice(0, 10)),
    warehouseId: '',
    remark: String(params.remark || ''),
    items: [],
  };

  if (Array.isArray(params.items) && params.items.length > 0) {
    form.value.items = (params.items as Array<Record<string, unknown>>).map((it) => {
      const matName = String(it.materialName || '');
      const matched = materialOptions.value.find(
        (m) => String(m.name || '').includes(matName) || matName.includes(String(m.name || ''))
      );
      return {
        materialTypeId: matched ? String(matched.id) : '',
        materialName: matched ? matched.name : matName,
        receivedQuantity: Number(it.receivedQuantity || 0),
        unit: String(it.unit || matched?.unit || 'kg'),
        unitPrice: matched?.unitPrice,
        qcResult: '',
        factoryNumber: '',
        originPlace: '',
        remark: '',
      };
    });
  }

  // Open create dialog (loadOptions handled by handleCreate, but we want pre-fill not reset)
  if (supplierOptions.value.length === 0) loadOptions();
  createVisible.value = true;
}

const loading = ref(false);
const tableData = ref<ReceiveRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

// Detail dialog
const detailVisible = ref(false);
const detailData = ref<ReceiveRow | null>(null);

// Issue #794: 附件刷新 key — 上传后递增触发 AttachmentList 重拉
const attachmentRefreshKey = ref(0);

// Create dialog
const createVisible = ref(false);
const submitting = ref(false);
const formRef = ref();
const form = ref({
  purchaseOrderId: '',
  supplierId: '',
  receiveDate: new Date().toISOString().slice(0, 10),
  warehouseId: '',
  remark: '',
  items: [] as ReceiveItem[],
});
const formRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  receiveDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }],
};

const supplierOptions = ref<SupplierOption[]>([]);
const materialOptions = ref<MaterialTypeOption[]>([]);
const purchaseOrderOptions = ref<PurchaseOrderOption[]>([]);
const manufacturerOptions = ref<ManufacturerRegistry[]>([]);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<{ content: ReceiveRow[]; totalElements: number }>(
      `/${factoryId.value}/purchase/receives`,
      { params: { page: pagination.value.page, size: pagination.value.size } }
    );
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch { /* interceptor toasts */ }
  finally { loading.value = false; }
}

async function loadOptions() {
  if (!factoryId.value) return;
  try {
    const [sup, mat, po, manufacturers] = await Promise.all([
      get<{ content: SupplierOption[] } | SupplierOption[]>(
        `/${factoryId.value}/reference-data/suppliers?usage=active`
      ),
      get<{ content: MaterialTypeOption[] } | MaterialTypeOption[]>(
        `/${factoryId.value}/reference-data/materials?usage=active`
      ),
      get<{ content: PurchaseOrderOption[] } | PurchaseOrderOption[]>(
        `/${factoryId.value}/reference-data/purchase-orders?usage=receivable`
      ),
      listManufacturers(factoryId.value, true),
    ]);
    const ext = <T,>(r: any): T[] => (r?.data?.content || r?.data?.list || r?.data || []) as T[];
    supplierOptions.value = ext<SupplierOption>(sup);
    materialOptions.value = ext<MaterialTypeOption>(mat);
    // 后端 /reference-data/purchase-orders 返回 { id, poNumber, supplierId }
    // (ReferenceDataController: displayField=poNumber per V13), 不含 orderNumber/supplierName。
    // normalize 到本组件契约 (orderNumber + supplierName), 否则下拉 label 显示 "undefined ·"
    // 且 matchedPo 按 orderNumber 匹配永远失败 → 选 PO 自动带物料/供应商全部失效。
    purchaseOrderOptions.value = ext<PurchaseOrderOption & { poNumber?: string }>(po).map((p) => ({
      ...p,
      orderNumber: p.orderNumber || p.poNumber || '',
      supplierName: p.supplierName || supplierOptions.value.find((s) => s.id === p.supplierId)?.name || '',
    }));
    manufacturerOptions.value = ext<ManufacturerRegistry>(manufacturers);
  } catch { /* interceptor */ }
}

function handleCreate() {
  form.value = {
    purchaseOrderId: '',
    supplierId: '',
    receiveDate: new Date().toISOString().slice(0, 10),
    warehouseId: '',
    remark: '',
    items: [],
  };
  loadOptions();
  createVisible.value = true;
  // Auto-populate supplier when PO selected (single-source-of-truth FE convenience)
}

function handlePoChange(poId: string) {
  if (!poId) return;
  const po = purchaseOrderOptions.value.find(p => p.id === poId);
  if (po?.supplierId) form.value.supplierId = po.supplierId;
}

function addItem() {
  form.value.items.push({
    materialTypeId: '',
    materialName: '',
    receivedQuantity: 0,
    unit: 'kg',
    unitPrice: undefined,
    qcResult: '',
    factoryNumber: '',
    originPlace: '',
    remark: '',
  });
}

function removeItem(idx: number) {
  form.value.items.splice(idx, 1);
}

function handleMaterialChange(idx: number, materialId: string) {
  const m = materialOptions.value.find(o => o.id === materialId);
  if (m) {
    form.value.items[idx].materialName = m.name;
    if (m.unit) form.value.items[idx].unit = m.unit;
    if (m.unitPrice && !form.value.items[idx].unitPrice) form.value.items[idx].unitPrice = m.unitPrice;
  }
}

function handleManufacturerChange(idx: number, code: string) {
  const manufacturer = manufacturerOptions.value.find((item) => item.code === code);
  if (manufacturer?.originPlace) {
    form.value.items[idx].originPlace = manufacturer.originPlace;
  }
}

async function handleSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  if (form.value.items.length === 0) {
    ElMessage.warning('请至少添加一行入库物料');
    return;
  }
  const noMatIdx = form.value.items.findIndex(i => !i.materialTypeId);
  if (noMatIdx >= 0) {
    ElMessage.warning(`第 ${noMatIdx + 1} 行请选择物料类型`);
    return;
  }
  const badQtyIdx = form.value.items.findIndex(i => !(Number(i.receivedQuantity) > 0));
  if (badQtyIdx >= 0) {
    ElMessage.warning(`第 ${badQtyIdx + 1} 行到货数量必须大于 0`);
    return;
  }
  const noUnitIdx = form.value.items.findIndex(i => !i.unit);
  if (noUnitIdx >= 0) {
    ElMessage.warning(`第 ${noUnitIdx + 1} 行请填写单位`);
    return;
  }
  submitting.value = true;
  try {
    const payload = { ...form.value };
    if (!payload.purchaseOrderId) delete (payload as Record<string, unknown>).purchaseOrderId;
    const res = await post<ReceiveRow>(`/${factoryId.value}/purchase/receives`, payload);
    if (res.success && res.data) {
      ElMessage.success('入库单已创建,DRAFT 状态。点击「确认入库」生成物料批次');
      createVisible.value = false;
      loadData();
    }
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('创建入库单失败');
    }
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: ReceiveRow) {
  if (row.status !== 'DRAFT') return;
  try {
    await ElMessageBox.confirm(
      `确认入库单 ${row.receiveNumber}？\n确认后将生成物料批次,无法撤销。`,
      '确认入库',
      { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '取消' }
    );
    await post(`/${factoryId.value}/purchase/receives/${row.id}/confirm`);
    ElMessage.success('已确认入库,物料批次已创建');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('确认入库失败');
    }
    if (err?.status === 409) loadData();
  }
}

async function handleDetail(row: ReceiveRow) {
  try {
    const res = await get<ReceiveRow>(`/${factoryId.value}/purchase/receives/${row.id}`);
    if (res.success && res.data) {
      detailData.value = res.data;
      detailVisible.value = true;
    }
  } catch { /* interceptor */ }
}

function statusTag(status: string): { type: string; label: string } {
  switch (status) {
    case 'DRAFT': return { type: 'info', label: '草稿' };
    case 'CONFIRMED': return { type: 'success', label: '已确认' };
    case 'CANCELLED': return { type: 'danger', label: '已取消' };
    default: return { type: '', label: status };
  }
}

/**
 * 汇总收货数量 (六扇门 May 7 transcript MINOR gap close):
 * 客户想直接在列表看到这次收了多少。
 * 多 item 同单位 → 求和; 多单位 → 显式分组显示 (e.g. "120 kg + 30 L")。
 */
function totalReceivedQuantity(row: ReceiveRow): string {
  const items = row.items || [];
  if (items.length === 0) return '-';
  // group by unit, accumulate quantity
  const byUnit = new Map<string, number>();
  for (const it of items) {
    const qty = Number(it.receivedQuantity) || 0;
    const unit = (it.unit || '').trim() || '-';
    byUnit.set(unit, (byUnit.get(unit) || 0) + qty);
  }
  // format: strip trailing zeros, max 3 decimals
  const fmt = (n: number) => {
    const s = n.toFixed(3);
    return s.replace(/\.?0+$/, '');
  };
  return Array.from(byUnit.entries())
    .map(([unit, qty]) => `${fmt(qty)} ${unit}`)
    .join(' + ');
}

/**
 * Issue #775: F006 客户要求列表显示分次收货进度.
 * "本次收货 vs 累计已收 / 下单数" — 让用户直接看出"第一次 100 / 第二次 200 / 共 300"形态.
 *
 * 实现策略: 仅聚合**当前页已加载的 rows** 同一 PO 的 items 累加, 不发额外 HTTP.
 * 跨页/历史收货请进入采购单详情查看完整进度.
 *
 * 返回 {cumulative, ordered, percent}; ordered 在当前页 rows 中找不到时返回 null.
 */
interface CumulativeStats {
  cumulative: number;
  ordered: number | null;
  byUnit: Map<string, number>;
  unit: string;
}
function cumulativeForRow(row: ReceiveRow): CumulativeStats | null {
  if (!row.purchaseOrderId) return null;
  const poId = row.purchaseOrderId;
  // 同 PO 全部 rows (当前页内, 含本行)
  const sameOrderRows = tableData.value.filter(r => r.purchaseOrderId === poId);
  let cumulative = 0;
  let ordered = 0;
  let hasOrdered = false;
  const byUnit = new Map<string, number>();
  let firstUnit = '';
  for (const r of sameOrderRows) {
    for (const it of r.items || []) {
      const qty = Number(it.receivedQuantity) || 0;
      cumulative += qty;
      const u = (it.unit || '').trim();
      if (u) {
        byUnit.set(u, (byUnit.get(u) || 0) + qty);
        if (!firstUnit) firstUnit = u;
      }
      // PO 下单数 (orderedQuantity) — 若 backend 在 receive item 上回传 PO 计划量
      const planned = Number((it as Record<string, unknown>).orderedQuantity ?? (it as Record<string, unknown>).plannedQuantity ?? 0);
      if (planned > 0) {
        ordered += planned;
        hasOrdered = true;
      }
    }
  }
  return {
    cumulative,
    ordered: hasOrdered ? ordered : null,
    byUnit,
    unit: firstUnit,
  };
}

function cumulativeDisplay(row: ReceiveRow): string {
  const stats = cumulativeForRow(row);
  if (!stats) return '-';
  const fmt = (n: number) => {
    const s = n.toFixed(3);
    return s.replace(/\.?0+$/, '');
  };
  // 单位混合 → 列出全部
  if (stats.byUnit.size > 1) {
    return Array.from(stats.byUnit.entries())
      .map(([u, q]) => `${fmt(q)} ${u}`)
      .join(' + ');
  }
  const cumStr = `${fmt(stats.cumulative)} ${stats.unit || ''}`.trim();
  if (stats.ordered != null && stats.ordered > 0) {
    return `${cumStr} / 计划 ${fmt(stats.ordered)}`;
  }
  return cumStr;
}

function cumulativeProgress(row: ReceiveRow): number | null {
  const stats = cumulativeForRow(row);
  if (!stats || stats.ordered == null || stats.ordered <= 0) return null;
  return Math.min(100, Math.round((stats.cumulative / stats.ordered) * 100));
}

/**
 * 单元 G (F006 R-B3): 收货明细对话框 — 客户张权 "第一次收了多少第二次收了多少更直观".
 *
 * page-local cumulativeForRow() 只聚合当前页 rows, 跨页不准. 本对话框打开时拉两个后端权威 endpoint:
 *   - getCumulativeReceived: 跨页累计 vs 计划 (按 PO item receivedQuantity 累计, 准确)
 *   - getOrderReceiveSequence: 分次收货时序 (第N次 — 日期 — 数量)
 */
const seqDialogVisible = ref(false);
const seqLoading = ref(false);
const seqCumulative = ref<CumulativeReceived | null>(null);
const seqEntries = ref<ReceiveSequenceEntry[]>([]);
const seqOrderNumber = ref('');

const fmtQty = (n: number | null | undefined): string => {
  if (n == null) return '—';
  const s = Number(n).toFixed(3);
  return s.replace(/\.?0+$/, '');
};

async function openSequenceDialog(row: ReceiveRow) {
  if (!row.purchaseOrderId) {
    ElMessage.info('无单入库, 无累计收货明细');
    return;
  }
  const orderId = row.purchaseOrderId;
  seqOrderNumber.value = row.purchaseOrderNumber || orderId;
  seqCumulative.value = null;
  seqEntries.value = [];
  seqDialogVisible.value = true;
  seqLoading.value = true;
  try {
    const [cumRes, seqRes] = await Promise.all([
      getCumulativeReceived(factoryId.value, orderId),
      getOrderReceiveSequence(factoryId.value, orderId),
    ]);
    if (cumRes.success && cumRes.data) seqCumulative.value = cumRes.data;
    if (seqRes.success && Array.isArray(seqRes.data)) seqEntries.value = seqRes.data;
  } catch { /* interceptor toasts */ }
  finally { seqLoading.value = false; }
}

function formatDate(s: string): string {
  if (!s) return '';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function handleSizeChange(val: number) { pagination.value.size = val; pagination.value.page = 1; loadData(); }
function handlePageChange(val: number) { pagination.value.page = val; loadData(); }

onMounted(() => { loadData(); loadOptions(); });
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <ConceptDisambiguationAlert
      here-name="采购入库"
      here="把供应商发来的物料正式收货登记，更新原料库存（采购订单的执行环节）"
      other-name="采购管理 → 采购订单"
      other="向供应商下的订单（金额、付款条款）。入库是采购订单的物流接收环节"
      other-path="/procurement/orders"
    />
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:16px;font-weight:600">采购入库管理</span>
          <div style="display:flex;gap:8px">
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="openAiCreate">AI 入库</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建入库单</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info" show-icon :closable="false"
        style="margin-bottom:12px"
        title="入库流程"
        description="新建入库单 → DRAFT 状态 → 点「确认入库」生成物料批次,自动更新库存。可关联采购订单或无单入库。"
      />

      <el-table :data="tableData" empty-text="暂无入库单" stripe border style="width:100%">
        <el-table-column prop="receiveNumber" label="入库单号" width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type as any" size="small">{{ statusTag(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采购订单" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.purchaseOrderNumber || row.purchaseOrderId || '— (无单入库)' }}</template>
        </el-table-column>
        <el-table-column label="供应商" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column prop="receiveDate" label="入库日期" width="110" />
        <el-table-column prop="itemCount" label="物料行数" width="90" align="center">
          <template #default="{ row }">{{ row.itemCount ?? row.items?.length ?? '-' }}</template>
        </el-table-column>
        <!-- Issue #775: 本次 vs 累计/计划 — 分次收货进度可见. -->
        <el-table-column label="本次收货" min-width="120" align="right" show-overflow-tooltip>
          <template #default="{ row }">{{ totalReceivedQuantity(row) }}</template>
        </el-table-column>
        <el-table-column label="累计已收 / 计划" min-width="170" align="right" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ cumulativeDisplay(row) }}</span>
            <el-progress
              v-if="cumulativeProgress(row) != null"
              :percentage="cumulativeProgress(row) as number"
              :show-text="false"
              :stroke-width="4"
              :status="cumulativeProgress(row) === 100 ? 'success' : ''"
              style="margin-top:4px"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" label="创建人" width="110" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Document" @click="handleDetail(row)">详情</el-button>
            <!-- 单元 G (F006 R-B3): 跨页准确累计 + 分次收货时序 (仅关联采购单的行可看) -->
            <el-button
              v-if="row.purchaseOrderId"
              size="small" :icon="List"
              @click="openSequenceDialog(row)"
            >收货明细</el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              type="success" size="small" :icon="Check"
              @click="handleConfirm(row)"
            >确认入库</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top:16px;justify-content:flex-end"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- Create dialog -->
    <el-dialog v-model="createVisible" title="新建入库单" width="900px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="采购订单" prop="purchaseOrderId">
          <el-select
            v-model="form.purchaseOrderId" placeholder="(可选) 关联已批准的采购订单"
            clearable filterable style="width:100%"
            @change="handlePoChange"
          >
            <el-option
              v-for="po in purchaseOrderOptions" :key="po.id"
              :label="`${po.orderNumber} · ${po.supplierName || ''}`"
              :value="po.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" prop="supplierId">
          <el-select v-model="form.supplierId" placeholder="选择供应商" filterable style="width:100%">
            <el-option v-for="s in supplierOptions" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库日期" prop="receiveDate">
          <el-date-picker v-model="form.receiveDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="入库备注(可选)" />
        </el-form-item>

        <el-divider content-position="left">入库物料</el-divider>
        <div class="line-toolbar">
          <el-button size="small" :icon="Plus" @click="addItem">添加物料</el-button>
          <el-button size="small" @click="router.push('/warehouse/manufacturers')">厂商登记表</el-button>
        </div>
        <el-table :data="form.items" border>
          <el-table-column label="物料类型" min-width="200">
            <template #header><span class="req-star">*</span>物料类型</template>
            <template #default="{ row, $index }">
              <el-select
                v-model="row.materialTypeId" placeholder="选择物料"
                filterable size="small" style="width:100%"
                @change="(val: string) => handleMaterialChange($index, val)"
              >
                <el-option v-for="m in materialOptions" :key="m.id" :label="`${m.name} (${m.code})`" :value="m.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="到货数量" width="120">
            <template #header><span class="req-star">*</span>到货数量</template>
            <template #default="{ row }">
              <el-input-number v-model="row.receivedQuantity" :min="0.001" :precision="3" :controls="false" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="80">
            <template #header><span class="req-star">*</span>单位</template>
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" />
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="质检结果" width="140">
            <template #default="{ row }">
              <el-select v-model="row.qcResult" size="small" placeholder="(可选)" clearable>
                <el-option label="合格" value="PASS" />
                <el-option label="不合格" value="FAIL" />
                <el-option label="待复检" value="RECHECK" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="厂号" width="190">
            <template #default="{ row, $index }">
              <el-select
                v-model="row.factoryNumber"
                placeholder="选择或输入厂号(选填)"
                filterable
                clearable
                allow-create
                default-first-option
                size="small"
                style="width:100%"
                @change="(val: string) => handleManufacturerChange($index, val)"
              >
                <el-option
                  v-for="manufacturer in manufacturerOptions"
                  :key="manufacturer.id"
                  :label="`${manufacturer.code} · ${manufacturer.name}`"
                  :value="manufacturer.code"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="产地" width="150">
            <template #default="{ row }">
              <el-input v-model="row.originPlace" size="small" placeholder="产地(选填)" maxlength="200" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ $index }">
              <el-button type="danger" link size="small" @click="removeItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">创建</el-button>
      </template>
    </el-dialog>

    <!-- Detail dialog -->
    <el-dialog v-model="detailVisible" :title="`入库单详情 — ${detailData?.receiveNumber || ''}`" width="800px">
      <el-descriptions v-if="detailData" :column="2" border>
        <el-descriptions-item label="入库单号">{{ detailData.receiveNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detailData.status).type as any" size="small">{{ statusTag(detailData.status).label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="采购订单">{{ detailData.purchaseOrderNumber || detailData.purchaseOrderId || '— (无单)' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ detailData.supplierName || detailData.supplierId }}</el-descriptions-item>
        <el-descriptions-item label="入库日期">{{ detailData.receiveDate }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ detailData.createdByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detailData.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">入库物料</el-divider>
      <el-table v-if="detailData?.items?.length" :data="detailData.items" border size="small">
        <el-table-column prop="materialName" label="物料名称" min-width="160" />
        <el-table-column prop="receivedQuantity" label="到货数量" width="110" align="right" />
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column v-if="canViewPrice" label="单价" width="100" align="right">
          <!--
            RBAC defense-in-depth (PR #415 Option B 2026-05-12):
            backend PriceFieldResponseAdvice strips unitPrice → null for roles
            lacking procurement:price:view (warehouse_mgr / inspector / operator).
            v-if guard avoids rendering "0" or empty when stripped.
          -->
          <template #default="{ row }">
            <span v-if="row.unitPrice != null">{{ row.unitPrice }}</span>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="qcResult" label="质检" width="100" align="center" />
        <el-table-column prop="factoryNumber" label="厂号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.factoryNumber || '-' }}</template>
        </el-table-column>
        <el-table-column prop="originPlace" label="产地" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.originPlace || '-' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      </el-table>
      <el-empty v-else description="无物料明细" />

      <!--
        Issue #794: 拍照附件 (六扇门 May 7 part 2 L177-180).
        客户原话: "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
        Reuses C-ATT-1 通用附件组件 (entityType=PURCHASE_RECEIPT, Attachment.java:115 enum 已支持).
      -->
      <template v-if="detailData?.id">
        <el-divider content-position="left">收货照片 / 附件</el-divider>
        <AttachmentList
          entity-type="PURCHASE_RECEIPT"
          :entity-id="String(detailData.id)"
          :factory-id="factoryId"
          :refresh-key="attachmentRefreshKey"
          empty-text="暂无收货照片"
        />
        <div v-if="canWrite" style="margin-top: 12px">
          <AttachmentUploadButton
            entity-type="PURCHASE_RECEIPT"
            :entity-id="String(detailData.id)"
            :factory-id="factoryId"
            business-tag="RECEIVE_PHOTO"
            file-category="PHOTO"
            accept="image/*"
            button-label="拍照 / 上传照片"
            @uploaded="attachmentRefreshKey++"
          />
        </div>
      </template>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="canWrite && detailData?.status === 'DRAFT'"
          type="success" :icon="Check"
          @click="() => detailData && handleConfirm(detailData)"
        >确认入库</el-button>
      </template>
    </el-dialog>

    <!-- 单元 G (F006 R-B3): 收货明细对话框 — 跨页准确累计 + 分次收货时序 -->
    <el-dialog
      v-model="seqDialogVisible"
      :title="`收货明细 — ${seqOrderNumber}`"
      width="720px"
    >
      <div v-loading="seqLoading">
        <!-- 跨页准确累计 vs 计划 (后端 cumulative-received endpoint) -->
        <el-descriptions v-if="seqCumulative" :column="2" border size="small">
          <el-descriptions-item label="采购单号">{{ seqCumulative.orderNumber || '—' }}</el-descriptions-item>
          <el-descriptions-item label="计划总量">{{ fmtQty(seqCumulative.plannedTotal) }}</el-descriptions-item>
          <el-descriptions-item label="累计已收 (跨页准确)">
            <strong>{{ fmtQty(seqCumulative.cumulativeReceived) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item label="待收">
            {{ fmtQty((seqCumulative.plannedTotal ?? 0) - (seqCumulative.cumulativeReceived ?? 0)) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">分次收货时序</el-divider>
        <el-table
          v-if="seqEntries.length"
          :data="seqEntries" border size="small" style="width:100%"
        >
          <el-table-column label="第几次" width="80" align="center">
            <template #default="{ row }">第 {{ row.seq }} 次</template>
          </el-table-column>
          <el-table-column prop="receiveNumber" label="收货单号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="receiveDate" label="日期" width="120" />
          <el-table-column label="数量" min-width="140" align="right">
            <template #default="{ row }">
              <span>{{ fmtQty(row.totalQuantity) }}</span>
              <span v-if="row.items && row.items[0]?.unit" style="margin-left:4px;color:#909399">
                {{ row.items[0].unit }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="createdByName" label="收货人" width="100" show-overflow-tooltip>
            <template #default="{ row }">{{ row.createdByName || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!seqLoading" description="暂无收货记录" :image-size="80" />
      </div>
      <template #footer>
        <el-button @click="seqDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- AI 入库录入抽屉 (Day 9, Issue #780.2) -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="WH_INBOUND_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
</template>

<style scoped>
.page-wrapper {
  padding: 16px;
}

.line-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.req-star { color: #f56c6c; margin-right: 2px; }
</style>
