<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { displayUnit } from '@/utils/unitPricing';
import {
  aggregateFinishedGoodsOptions,
  aggregateMaterialInventoryOptions,
  applySelectedOption,
  optionsForItemType,
  resetSelectedOption,
  TRANSFER_TYPE_OPTIONS,
  toTransferItemPayload,
  type FinishedGoodsInventoryBatch,
  type MaterialInventoryBatch,
  type TransferCreateRow,
  type TransferItemType,
  type TransferSelectableItem,
  type TransferType,
} from './transferCreate';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// U-FOOTER-1
const summaryRequest = computed<ListSummaryRequest>(() => ({ filterConditions: {} }));
const { summary: footerSummary, loading: footerLoading } = useListSummary('internalTransfer', summaryRequest);

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  REQUESTED: { text: '已申请', type: 'warning' },
  APPROVED: { text: '已批准', type: '' },
  REJECTED: { text: '已驳回', type: 'danger' },
  SHIPPED: { text: '已发运', type: 'warning' },
  RECEIVED: { text: '已签收', type: '' },
  CONFIRMED: { text: '已确认', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' },
  // #1214 缺口修复: 撤销小结连带冲销同厂调拨记录时置此状态 (物理货物需人工核实/退回, 见调拨单备注)。
  REVERSED: { text: '已冲销', type: 'danger' },
};

const typeMap: Record<string, string> = {
  HQ_TO_BRANCH: '总部→分部',
  BRANCH_TO_BRANCH: '分部→分部',
  BRANCH_TO_HQ: '分部→总部',
  WAREHOUSE_TO_WAREHOUSE: '仓库间调拨',
};

// PR #289 §B9 — manual transfer create dialog state
// T4-B4 (issue #532): backend ReferenceDataController.findMaterials now emits currentStock
// (PR adds it via MaterialBatchRepository.sumQuantityByMaterialType bulk query — issue #540).
// Treat as optional + accept string|number to tolerate BigDecimal-as-string JSON encoding.
interface FactoryNetworkEntry { factoryId: string; factoryName: string }

const createVisible = ref(false);
const submitting = ref(false);
const submittingTransferId = ref('');
const formRef = ref();
const today = () => new Date().toISOString().slice(0, 10);
const form = ref({
  transferType: 'BRANCH_TO_HQ' as TransferType,
  targetFactoryId: '',
  sourceWarehouseId: '',
  targetWarehouseId: '',
  transferDate: today(),
  expectedArrivalDate: '',
  remark: '',
  items: [] as TransferCreateRow[],
});
function validateWarehouseTransferSource(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) {
    callback(new Error('请选择调出仓库'));
    return;
  }
  callback();
}

function validateWarehouseTransferTarget(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) {
    callback(new Error('请选择调入仓库'));
    return;
  }
  if (form.value.transferType === 'WAREHOUSE_TO_WAREHOUSE'
      && form.value.sourceWarehouseId && value === form.value.sourceWarehouseId) {
    callback(new Error('调入仓库不能和调出仓库相同'));
    return;
  }
  callback();
}

const formRules = {
  sourceWarehouseId: [{ required: true, validator: validateWarehouseTransferSource, trigger: 'change' }],
  targetWarehouseId: [{ required: true, validator: validateWarehouseTransferTarget, trigger: 'change' }],
  transferType: [{ required: true, message: '请选择调拨类型', trigger: 'change' }],
  targetFactoryId: [{ required: true, message: '请选择调入方', trigger: 'change' }],
  transferDate: [{ required: true, message: '请选择调拨日期', trigger: 'change' }],
};
const sourceMaterialOptions = ref<TransferSelectableItem[]>([]);
const finishedGoodsOptions = ref<TransferSelectableItem[]>([]);
const sourceInventoryLoading = ref(false);
const sourceInventoryLoaded = ref(false);
const factoryNetworkOptions = ref<FactoryNetworkEntry[]>([]);
const factoryNetworkLoading = ref(false);
// F-FP-4: 仓库下拉 (参考 stocktakes/index.vue 写法)
interface WarehouseOption { id: string | number; name: string; code?: string; type?: string }
const sourceWarehouseOptions = ref<WarehouseOption[]>([]);
const targetWarehouseOptions = ref<WarehouseOption[]>([]);

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/transfers`, {
      params: { page: pagination.value.page, size: pagination.value.size },
    });
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

interface InventoryByWarehouseResponse {
  materials?: MaterialInventoryBatch[];
  products?: FinishedGoodsInventoryBatch[];
}

async function loadSourceInventoryOptions() {
  sourceMaterialOptions.value = [];
  finishedGoodsOptions.value = [];
  sourceInventoryLoaded.value = Boolean(form.value.sourceWarehouseId);
  if (!factoryId.value || !form.value.sourceWarehouseId) return;
  sourceInventoryLoading.value = true;
  try {
    const res = await get<InventoryByWarehouseResponse>(`/${factoryId.value}/inventory/by-warehouse`, {
      params: {
        targetFactoryId: factoryId.value,
        warehouseId: form.value.sourceWarehouseId,
      },
    });
    const inventory = res?.data;
    sourceMaterialOptions.value = aggregateMaterialInventoryOptions(inventory?.materials || []);
    finishedGoodsOptions.value = aggregateFinishedGoodsOptions(inventory?.products || []);
  } catch { /* interceptor */ }
  finally { sourceInventoryLoading.value = false; }
}

async function handleSourceWarehouseChange() {
  form.value.items.forEach(resetSelectedOption);
  await loadSourceInventoryOptions();
}

async function handleItemTypeChange(row: TransferCreateRow, itemType: TransferItemType) {
  row.itemType = itemType;
  resetSelectedOption(row);
  if (form.value.sourceWarehouseId && !sourceInventoryLoaded.value) await loadSourceInventoryOptions();
}

function selectableOptions(row: TransferCreateRow): TransferSelectableItem[] {
  return optionsForItemType(row.itemType, sourceMaterialOptions.value, finishedGoodsOptions.value);
}

// PR #309 C2 — load visible factory network for 调入方 dropdown
async function loadFactoryNetwork() {
  if (!factoryId.value) return;
  factoryNetworkLoading.value = true;
  try {
    const res = await get<FactoryNetworkEntry[]>(`/${factoryId.value}/factories/network`);
    const entries = (res?.data ?? []) as FactoryNetworkEntry[];
    factoryNetworkOptions.value = Array.isArray(entries) ? entries : [];
    // Default to own factory if only one entry visible (and form not yet filled)
    if (factoryNetworkOptions.value.length === 1 && !form.value.targetFactoryId) {
      form.value.targetFactoryId = factoryNetworkOptions.value[0].factoryId;
      await loadTargetWarehouses();
    }
  } catch {
    // Fallback: at minimum let user fill in manually (so we keep filterable allow-create on the select)
    factoryNetworkOptions.value = [];
  } finally {
    factoryNetworkLoading.value = false;
  }
}

// F-FP-4: 加载本厂仓库列表 (同 stocktakes/index.vue loadWarehouses)
async function loadWarehousesForFactory(targetFactoryId: string): Promise<WarehouseOption[]> {
  if (!factoryId.value || !targetFactoryId) return [];
  try {
    const ownFactory = targetFactoryId === factoryId.value;
    const url = ownFactory
      ? `/${factoryId.value}/factory/warehouses`
      : `/${factoryId.value}/inventory/warehouses`;
    const res = await get<WarehouseOption[]>(url, ownFactory ? undefined : {
      params: { targetFactoryId },
    });
    return Array.isArray(res.data) ? res.data : [];
  } catch {
    return [];
  }
}

async function loadSourceWarehouses() {
  sourceWarehouseOptions.value = factoryId.value
    ? await loadWarehousesForFactory(factoryId.value)
    : [];
}

async function loadTargetWarehouses() {
  targetWarehouseOptions.value = form.value.targetFactoryId
    ? await loadWarehousesForFactory(form.value.targetFactoryId)
    : [];
}

async function handleTargetFactoryChange() {
  form.value.targetWarehouseId = '';
  await loadTargetWarehouses();
}

async function handleTransferTypeChange(value: TransferType) {
  form.value.targetWarehouseId = '';
  if (value === 'WAREHOUSE_TO_WAREHOUSE') {
    form.value.targetFactoryId = factoryId.value || '';
  }
  await loadTargetWarehouses();
}

function openCreateDialog() {
  form.value = {
    transferType: 'BRANCH_TO_HQ',
    targetFactoryId: '',
    sourceWarehouseId: '',
    targetWarehouseId: '',
    transferDate: today(),
    expectedArrivalDate: '',
    remark: '',
    items: [],
  };
  sourceMaterialOptions.value = [];
  finishedGoodsOptions.value = [];
  sourceInventoryLoaded.value = false;
  loadFactoryNetwork();
  loadSourceWarehouses();
  createVisible.value = true;
}

function addItem() {
  form.value.items.push({
    itemType: 'RAW_MATERIAL',
    selectedItemId: '',
    materialTypeId: undefined,
    productTypeId: undefined,
    itemName: '',
    quantity: undefined,
    unit: '',
    unitPrice: undefined,
    remark: '',
  });
}

function removeItem(idx: number) {
  form.value.items.splice(idx, 1);
}

function handleMaterialChange(idx: number, selectedId: string) {
  const row = form.value.items[idx];
  const m = selectableOptions(row).find(o => o.id === selectedId);
  if (m) {
    applySelectedOption(row, m);
  }
}

function formatStock(v: unknown): string {
  if (v === null || v === undefined || v === '') return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 3 });
}

async function submitCreate() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  if (form.value.items.length === 0) {
    ElMessage.warning('请至少添加一行调拨物料');
    return;
  }
  for (const it of form.value.items) {
    if (!it.selectedItemId || (it.itemType === 'FINISHED_GOODS' ? !it.productTypeId : !it.materialTypeId)) {
      ElMessage.warning('请为每行选择与类型匹配的物料或成品'); return;
    }
    if (!it.quantity || it.quantity <= 0) { ElMessage.warning('每行数量必须大于 0'); return; }
    if (!it.unit) { ElMessage.warning('每行必须有单位'); return; }
    // F-FP-3 Rule1: 超过现有库存时阻止提交，边界前置
    const stock = (it as any)._currentStock;
    if (stock != null && stock !== '' && Number(it.quantity) > Number(stock)) {
      ElMessage({ message: `调拨数量 ${it.quantity} 超过现有库存 ${stock}（物料：${it.itemName || it.materialTypeId}），请调整数量`, type: 'error', duration: 0, showClose: true });
      return;
    }
  }
  submitting.value = true;
  try {
    const payload: Record<string, unknown> = {
      transferType: form.value.transferType,
      targetFactoryId: form.value.targetFactoryId.trim(),
      transferDate: form.value.transferDate,
      remark: form.value.remark || undefined,
      items: form.value.items.map(toTransferItemPayload),
    };
    payload.sourceWarehouseId = form.value.sourceWarehouseId.trim();
    payload.targetWarehouseId = form.value.targetWarehouseId.trim();
    if (form.value.expectedArrivalDate) payload.expectedArrivalDate = form.value.expectedArrivalDate;

    const res = await post(`/${factoryId.value}/transfers`, payload);
    if (res.success && res.data) {
      ElMessage.success('调拨单已创建 (DRAFT)，可在详情页继续走 申请→审批→发货→签收 流程');
      createVisible.value = false;
      loadData();
    }
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; message?: string; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error(err?.message || '创建调拨单失败');
    }
  } finally {
    submitting.value = false;
  }
}

function goDetail(id: string) { router.push(`/transfer/${id}`); }

async function submitForApproval(row: TableRow) {
  if (submittingTransferId.value || row.status !== 'DRAFT') return;
  try {
    await ElMessageBox.confirm(
      `提交调拨单「${row.transferNumber || row.id}」到统一 OA 审批？提交后请在个人 OA 查看进度。`,
      '提交 OA 审批',
      { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  submittingTransferId.value = String(row.id);
  try {
    const res = await post(`/${factoryId.value}/transfers/${row.id}/request`);
    if (!res.success) throw new Error(res.message || '提交 OA 审批失败');
    ElMessage.success('已提交统一 OA 审批');
    await loadData();
  } catch (error) {
    const message = error instanceof Error ? error.message : '提交 OA 审批失败';
    ElMessage.error(message);
  } finally {
    submittingTransferId.value = '';
  }
}
function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }

function isOutbound(row: TableRow) { return row.sourceFactoryId === factoryId.value; }
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="调拨单"
      here="把物料从一个工厂/仓库搬到另一个工厂/仓库（实际搬动物料、转移所有权）"
      other-name="仓储管理 → 盘点管理"
      other="清点仓库实际库存与系统数对比，发现差异（盘盈/盘亏，不搬动物料）"
      other-path="/warehouse/inventory"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ label('transfer') }}管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button
              v-if="canWrite"
              type="primary" :icon="Plus"
              @click="openCreateDialog"
            >手动新建调拨单</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="transferNumber" label="调拨编号" width="170" />
        <el-table-column label="方向" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="isOutbound(row) ? 'danger' : 'success'" size="small">
              {{ isOutbound(row) ? '调出' : '调入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120" align="center">
          <template #default="{ row }">{{ typeMap[row.transferType] || row.transferType }}</template>
        </el-table-column>
        <el-table-column label="调出方" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceFactory?.name || row.sourceFactoryId }}</template>
        </el-table-column>
        <el-table-column label="调入方" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.targetFactory?.name || row.targetFactoryId }}</template>
        </el-table-column>
        <el-table-column prop="transferDate" label="调拨日期" width="120" />
        <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="120" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="(statusMap[row.status]?.type) || 'info'" size="small">
              {{ statusMap[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              type="warning"
              link
              size="small"
              :loading="submittingTransferId === String(row.id)"
              @click="submitForApproval(row)"
            >提交审批</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前调拨${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]" :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>

    <!-- PR #289 §B9 — Manual create dialog -->
    <el-dialog
      v-model="createVisible"
      title="手动新建调拨单"
      width="960px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="info" show-icon :closable="false"
        style="margin-bottom:12px"
        title="使用场景"
        description="无生产计划时手动创建（领用 / 研发 / 互调 / 分部退总仓 等）。创建后为草稿，提交后进入统一 OA 审批，再由仓储执行后续调拨。"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="调拨类型" prop="transferType">
              <el-select v-model="form.transferType" style="width:100%" @change="handleTransferTypeChange">
                <el-option
                  v-for="option in TRANSFER_TYPE_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调出方" prop="sourceFactoryId">
              <el-input :value="factoryId" disabled placeholder="当前工厂 (自动填充)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="调入方" prop="targetFactoryId">
              <el-select
                v-model="form.targetFactoryId"
                :loading="factoryNetworkLoading"
                filterable
                default-first-option
                placeholder="请选择调入方"
                clearable
                style="width:100%"
                @change="handleTargetFactoryChange"
              >
                <el-option
                  v-for="opt in factoryNetworkOptions"
                  :key="opt.factoryId"
                  :label="`${opt.factoryName} (${opt.factoryId})`"
                  :value="opt.factoryId"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调拨日期" prop="transferDate">
              <el-date-picker
                v-model="form.transferDate" type="date" value-format="YYYY-MM-DD"
                style="width:100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- F-FP-4 Rule3: 仓库改 el-select，仓管员无需记 UUID，参考 stocktakes/index.vue -->
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="调出仓库" prop="sourceWarehouseId">
              <el-select
                v-model="form.sourceWarehouseId"
                placeholder="请选择调出仓库"
                clearable
                filterable
                style="width:100%"
                @change="handleSourceWarehouseChange"
              >
                <el-option
                  v-for="w in sourceWarehouseOptions"
                  :key="String(w.id)"
                  :label="`${w.name}${w.code ? ` (${w.code})` : ''}`"
                  :value="String(w.id)"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调入仓库" prop="targetWarehouseId">
              <el-select
                v-model="form.targetWarehouseId"
                placeholder="请选择调入仓库"
                clearable
                filterable
                :disabled="!form.targetFactoryId"
                style="width:100%"
              >
                <el-option
                  v-for="w in targetWarehouseOptions"
                  :key="String(w.id)"
                  :label="`${w.name}${w.code ? ` (${w.code})` : ''}`"
                  :value="String(w.id)"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="预计到货">
              <el-date-picker
                v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD"
                style="width:100%" clearable
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注 / 原因">
          <el-input
            v-model="form.remark" type="textarea" :rows="2"
            placeholder="如: 领用 / 研发样品 / 余料退总仓 等"
            maxlength="5000" show-word-limit
          />
        </el-form-item>

        <el-divider content-position="left">调拨物料</el-divider>
        <UpstreamMissingHint v-if="sourceInventoryLoaded && sourceMaterialOptions.length === 0 && finishedGoodsOptions.length === 0" description="所选调出仓库暂无可调拨物料或成品库存" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库管理员核对库存" @action="goCreate('/warehouse/material-types')" />
        <el-button size="small" :icon="Plus" @click="addItem" style="margin-bottom:8px">添加物料</el-button>
        <el-table :data="form.items" border empty-text="点击「添加物料」开始">
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-select
                v-model="row.itemType"
                size="small"
                style="width:100%"
                @change="(value: TransferItemType) => handleItemTypeChange(row, value)"
              >
                <el-option label="原料/食材" value="RAW_MATERIAL" />
                <el-option label="成品/菜品" value="FINISHED_GOODS" />
                <el-option label="包材" value="PACKAGING_MATERIAL" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="物料" min-width="220">
            <template #default="{ row, $index }">
              <el-select
                v-model="row.selectedItemId"
                :placeholder="!form.sourceWarehouseId ? '请先选择调出仓库' : '选择物料/成品'"
                :loading="sourceInventoryLoading"
                :disabled="!form.sourceWarehouseId"
                filterable size="small" style="width:100%"
                @change="(val: string) => handleMaterialChange($index, val)"
              >
                <el-option
                  v-for="m in selectableOptions(row)" :key="m.id"
                  :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''} · 可用 ${formatStock(m.currentStock)} ${displayUnit(m.unit)}`"
                  :value="m.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量 / 单位" width="190">
            <!-- F-FP-3 Rule1: :max = 现有库存，超量时橙色提示边框，超量禁提交 -->
            <template #default="{ row }">
              <div class="quantity-unit-cell">
                <el-input-number
                  v-model="row.quantity" :min="0.01" :precision="3"
                  :max="row._currentStock != null && row._currentStock !== '' ? Number(row._currentStock) : undefined"
                  :controls="false" size="small" placeholder="数量"
                  :disabled="!row.selectedItemId"
                  :class="{ 'over-stock': row._currentStock != null && row._currentStock !== '' && Number(row.quantity) > Number(row._currentStock) }"
                />
                <span class="unit-chip">{{ row.selectedItemId ? displayUnit(row.unit) : '单位' }}</span>
              </div>
            </template>
          </el-table-column>
          <!-- T4-B4 (issue #532): F006 customer asked for 现有库存 inline next to 调拨数量 so user
               knows the upper bound before submitting. Backend ReferenceDataController.findMaterials
               populates currentStock (issue #540 fix). Red when 调拨数量 exceeds available; green otherwise. -->
          <el-table-column label="现有库存" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row._currentStock != null && row._currentStock !== ''"
                    :style="{ color: Number(row._currentStock) < Number(row.quantity || 0) ? '#f56c6c' : '#67c23a' }">
                {{ formatStock(row._currentStock) }} {{ displayUnit(row.unit) }}
              </span>
              <span v-else style="color: #c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="120">
            <template #default="{ row }">
              <el-input-number
                v-model="row.unitPrice" :min="0" :precision="2"
                :controls="false" size="small" style="width:100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="行备注" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.remark" size="small" placeholder="(可选)" maxlength="5000" />
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
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
  .header-right { display: flex; gap: 8px; }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
/* F-FP-3: 超库存时橙色边框提示 */
:deep(.over-stock .el-input__wrapper) { box-shadow: 0 0 0 1px #e6a23c inset !important; }
.quantity-unit-cell { display: flex; align-items: center; gap: 8px;
  :deep(.el-input-number) { flex: 1; min-width: 0; }
}
.unit-chip { flex: 0 0 36px; text-align: center; color: #606266; font-weight: 600; }
</style>
