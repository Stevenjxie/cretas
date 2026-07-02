<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
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
};

const typeMap: Record<string, string> = {
  HQ_TO_BRANCH: '总部→分部',
  BRANCH_TO_BRANCH: '分部→分部',
  BRANCH_TO_HQ: '分部→总部',
  WAREHOUSE_TO_WAREHOUSE: '仓库间调拨',
};

// PR #289 §B9 — manual transfer create dialog state
interface ManualTransferItem {
  itemType: 'RAW_MATERIAL' | 'FINISHED_GOODS' | 'PACKAGING_MATERIAL';
  materialTypeId: string;
  itemName: string;
  quantity: number;
  unit: string;
  unitPrice?: number;
  remark?: string;
}
// T4-B4 (issue #532): backend ReferenceDataController.findMaterials now emits currentStock
// (PR adds it via MaterialBatchRepository.sumQuantityByMaterialType bulk query — issue #540).
// Treat as optional + accept string|number to tolerate BigDecimal-as-string JSON encoding.
interface MaterialTypeOption { id: string; name: string; code: string; unit: string; currentStock?: number | string | null }
interface FactoryNetworkEntry { factoryId: string; factoryName: string }

const createVisible = ref(false);
const submitting = ref(false);
const formRef = ref();
const today = () => new Date().toISOString().slice(0, 10);
const form = ref({
  transferType: 'BRANCH_TO_HQ' as 'HQ_TO_BRANCH' | 'BRANCH_TO_BRANCH' | 'BRANCH_TO_HQ',
  targetFactoryId: '',
  sourceWarehouseId: '',
  targetWarehouseId: '',
  transferDate: today(),
  expectedArrivalDate: '',
  remark: '',
  items: [] as ManualTransferItem[],
});
const formRules = {
  transferType: [{ required: true, message: '请选择调拨类型', trigger: 'change' }],
  targetFactoryId: [{ required: true, message: '请选择调入方', trigger: 'change' }],
  transferDate: [{ required: true, message: '请选择调拨日期', trigger: 'change' }],
};
const materialOptions = ref<MaterialTypeOption[]>([]);
const factoryNetworkOptions = ref<FactoryNetworkEntry[]>([]);
const factoryNetworkLoading = ref(false);
// F-FP-4: 仓库下拉 (参考 stocktakes/index.vue 写法)
interface WarehouseOption { id: string | number; name: string; type?: string }
const warehouseOptions = ref<WarehouseOption[]>([]);

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

async function loadMaterialOptions() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content: MaterialTypeOption[] } | MaterialTypeOption[]>(
      `/${factoryId.value}/reference-data/materials`,
      { params: { page: 1, size: 200 } }
    );
    const ext = <T,>(r: any): T[] => (r?.data?.content || r?.data?.list || r?.data || []) as T[];
    materialOptions.value = ext<MaterialTypeOption>(res);
  } catch { /* interceptor */ }
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
    }
  } catch {
    // Fallback: at minimum let user fill in manually (so we keep filterable allow-create on the select)
    factoryNetworkOptions.value = [];
  } finally {
    factoryNetworkLoading.value = false;
  }
}

// F-FP-4: 加载本厂仓库列表 (同 stocktakes/index.vue loadWarehouses)
async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get<WarehouseOption[]>(`/${factoryId.value}/factory/warehouses`);
    warehouseOptions.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* 失败时仓库下拉为空，用户仍可不填 (字段可选) */ }
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
  loadMaterialOptions();
  loadFactoryNetwork();
  loadWarehouses();
  createVisible.value = true;
}

function addItem() {
  form.value.items.push({
    itemType: 'RAW_MATERIAL',
    materialTypeId: '',
    itemName: '',
    quantity: 0,
    unit: 'kg',
    unitPrice: undefined,
    remark: '',
  });
}

function removeItem(idx: number) {
  form.value.items.splice(idx, 1);
}

function handleMaterialChange(idx: number, materialId: string) {
  const m = materialOptions.value.find(o => o.id === materialId);
  if (m) {
    form.value.items[idx].itemName = m.name;
    if (m.unit) form.value.items[idx].unit = m.unit;
    // T4-B4 (issue #532): project currentStock onto the row so dialog 现有库存 column renders.
    // Underscore prefix marks frontend-only (not submitted to backend on create).
    (form.value.items[idx] as any)._currentStock = m.currentStock ?? null;
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
    if (!it.materialTypeId) { ElMessage.warning('请为每行选择物料'); return; }
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
      items: form.value.items.map(it => ({
        itemType: it.itemType,
        materialTypeId: it.materialTypeId,
        itemName: it.itemName,
        quantity: it.quantity,
        unit: it.unit,
        unitPrice: it.unitPrice,
        remark: it.remark || undefined,
      })),
    };
    if (form.value.sourceWarehouseId) payload.sourceWarehouseId = form.value.sourceWarehouseId.trim();
    if (form.value.targetWarehouseId) payload.targetWarehouseId = form.value.targetWarehouseId.trim();
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
        <el-table-column label="操作" width="100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
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
        description="无生产计划时手动创建（领用 / 研发 / 互调 / 分部退总仓 等）。创建后为 DRAFT 状态,需走 申请→审批→发货→签收 流程。"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="调拨类型" prop="transferType">
              <el-select v-model="form.transferType" style="width:100%">
                <el-option label="总部→分部 (HQ_TO_BRANCH)" value="HQ_TO_BRANCH" />
                <el-option label="分部→分部 (BRANCH_TO_BRANCH)" value="BRANCH_TO_BRANCH" />
                <el-option label="分部→总部 (BRANCH_TO_HQ)" value="BRANCH_TO_HQ" />
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
                allow-create
                default-first-option
                placeholder="选择目标工厂/门店 (无可选项时可输入 ID)"
                clearable
                style="width:100%"
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
                placeholder="(可选) 选择调出仓库"
                clearable
                filterable
                style="width:100%"
              >
                <el-option
                  v-for="w in warehouseOptions"
                  :key="String(w.id)"
                  :label="String(w.name)"
                  :value="String(w.id)"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调入仓库" prop="targetWarehouseId">
              <el-select
                v-model="form.targetWarehouseId"
                placeholder="(可选) 选择调入仓库"
                clearable
                filterable
                style="width:100%"
              >
                <el-option
                  v-for="w in warehouseOptions"
                  :key="String(w.id)"
                  :label="String(w.name)"
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
        <UpstreamMissingHint v-if="materialOptions.length === 0" description="本工厂暂无物料，无法调拨" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        <el-button size="small" :icon="Plus" @click="addItem" style="margin-bottom:8px">添加物料</el-button>
        <el-table :data="form.items" border empty-text="点击「添加物料」开始">
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-select v-model="row.itemType" size="small" style="width:100%">
                <el-option label="原料/食材" value="RAW_MATERIAL" />
                <el-option label="成品/菜品" value="FINISHED_GOODS" />
                <el-option label="包材" value="PACKAGING_MATERIAL" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="物料" min-width="220">
            <template #default="{ row, $index }">
              <el-select
                v-model="row.materialTypeId" placeholder="选择物料"
                filterable size="small" style="width:100%"
                @change="(val: string) => handleMaterialChange($index, val)"
              >
                <el-option
                  v-for="m in materialOptions" :key="m.id"
                  :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                  :value="m.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <!-- F-FP-3 Rule1: :max = 现有库存，超量时橙色提示边框，超量禁提交 -->
            <template #default="{ row }">
              <el-input-number
                v-model="row.quantity" :min="0.001" :precision="3"
                :max="row._currentStock != null && row._currentStock !== '' ? Number(row._currentStock) : undefined"
                :controls="false" size="small" style="width:100%"
                :class="{ 'over-stock': row._currentStock != null && row._currentStock !== '' && Number(row.quantity) > Number(row._currentStock) }"
              />
            </template>
          </el-table-column>
          <!-- T4-B4 (issue #532): F006 customer asked for 现有库存 inline next to 调拨数量 so user
               knows the upper bound before submitting. Backend ReferenceDataController.findMaterials
               populates currentStock (issue #540 fix). Red when 调拨数量 exceeds available; green otherwise. -->
          <el-table-column label="现有库存" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row._currentStock != null && row._currentStock !== ''"
                    :style="{ color: Number(row._currentStock) < Number(row.quantity || 0) ? '#f56c6c' : '#67c23a' }">
                {{ formatStock(row._currentStock) }}
              </span>
              <span v-else style="color: #c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="90">
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" maxlength="20" />
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
</style>
