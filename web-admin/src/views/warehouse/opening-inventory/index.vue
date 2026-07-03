<script setup lang="ts">
/**
 * 期初建账 (Opening Inventory Onboarding)
 *
 * 客户场景 (六膳门): 建账/上线时需要录入 ~215 条物料的现有库存数量 + 来源单价,
 * 建立库存台账并计入财务(借1403原材料/贷4001实收资本), 不产生采购应付。
 * 客户之前误用「采购入库」录入期初库存 → 产生了幽灵应付账款 (phantom AP)。
 *
 * Steve 明确要求: 这个页面必须在「仓储管理」模块下 (仓管员建账操作), 不是财务模块 —
 * 记账凭证是这个动作的会计副作用, 不是它的主流程。
 *
 * 两种录入方式:
 *   1. 手动录入 — 少量/零星补录, 逐行添加
 *   2. Excel 批量导入 — 主路径 (215 条物料), 下载模板 → 填写 → 导入 → 预览校正 → 确认
 *
 * @since 2026-07-03
 */
import { computed, onMounted, reactive, ref } from 'vue';
import * as XLSX from 'xlsx';
import { ElMessage } from 'element-plus';
import { Plus, Delete, Download, Upload, InfoFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { formatAmount } from '@/utils/tableFormatters';
import {
  createOpeningInventory,
  listActiveMaterialTypes,
  type MaterialTypeOption,
  type OpeningInventoryItem,
  type OpeningInventoryResult,
} from '@/api/materialBatchOpening';
import { listWarehouses, type FactoryWarehouse } from '@/api/factoryWarehouse';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

// =========================================================================
// 主数据: 物料类型字典 + 仓库列表
// =========================================================================
const materialTypes = ref<MaterialTypeOption[]>([]);
const warehouses = ref<FactoryWarehouse[]>([]);
const masterDataLoading = ref(false);

/** 默认仓: 优先「原料仓」(type=RAW), 否则第一个仓库。 */
const defaultWarehouseId = computed(() => {
  if (warehouses.value.length === 0) return '';
  const raw = warehouses.value.find((w) => w.type === 'RAW');
  return (raw || warehouses.value[0]).id;
});

async function loadMasterData() {
  if (!factoryId.value) return;
  masterDataLoading.value = true;
  try {
    const [materialRes, warehouseRes] = await Promise.all([
      listActiveMaterialTypes(factoryId.value),
      listWarehouses(factoryId.value),
    ]);
    if (materialRes.success && Array.isArray(materialRes.data)) {
      materialTypes.value = materialRes.data;
    }
    if (warehouseRes.success && Array.isArray(warehouseRes.data)) {
      warehouses.value = warehouseRes.data;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      ElMessage({ message: '加载物料/仓库主数据失败, 请刷新重试', type: 'error', duration: 0, showClose: true });
    }
  } finally {
    masterDataLoading.value = false;
  }
}

function findMaterialByNameOrCode(raw: string): MaterialTypeOption | undefined {
  const key = raw.trim();
  if (!key) return undefined;
  return (
    materialTypes.value.find((m) => m.name === key) ||
    materialTypes.value.find((m) => m.code === key) ||
    materialTypes.value.find((m) => m.name.includes(key) || key.includes(m.name))
  );
}

function findWarehouseByName(raw: string): FactoryWarehouse | undefined {
  const key = raw.trim();
  if (!key) return undefined;
  return warehouses.value.find((w) => w.name === key) || warehouses.value.find((w) => w.name.includes(key));
}

// =========================================================================
// 提交结果 / 幂等提交守卫
// =========================================================================
const submitting = ref(false);
const lastResult = ref<OpeningInventoryResult | null>(null);

async function submitItems(items: OpeningInventoryItem[], remark: string): Promise<boolean> {
  if (!factoryId.value) return false;
  if (submitting.value) return false; // fool-proof Rule 4: 幂等防重复提交
  if (items.length === 0) {
    ElMessage.warning('没有可提交的行');
    return false;
  }
  const invalidRows = items.filter((i) => !i.materialTypeId || !i.warehouseId || !(i.quantity > 0));
  if (invalidRows.length > 0) {
    ElMessage({
      message: `有 ${invalidRows.length} 行未完整填写(物料/仓库/数量必填, 数量须大于 0), 请修正后再提交`,
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return false;
  }

  submitting.value = true;
  try {
    const res = await createOpeningInventory(factoryId.value, { items, remark: remark || undefined });
    if (res.success && res.data) {
      lastResult.value = res.data;
      const { createdBatches, voucherId, skippedNoPriceCount } = res.data;
      const parts = [`已建 ${createdBatches} 条期初库存`];
      parts.push(voucherId ? `记账凭证 ${voucherId} (借1403原材料/贷4001实收资本)` : '本次无金额记账 (全部行无单价)');
      if (skippedNoPriceCount > 0) {
        parts.push(`${skippedNoPriceCount} 条无单价未计入记账金额`);
      }
      ElMessage({ message: parts.join(', '), type: 'success', duration: 0, showClose: true });
      return true;
    }
    ElMessage({ message: res.message || '期初建账提交失败', type: 'error', duration: 0, showClose: true });
    return false;
  } catch (error: unknown) {
    const err = error as { actionHint?: string; response?: { data?: { message?: string } } };
    if (!err?.actionHint) {
      const msg = err?.response?.data?.message || '期初建账提交失败, 请稍后重试';
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
    return false;
  } finally {
    submitting.value = false;
  }
}

// =========================================================================
// Tab 1: 手动录入
// =========================================================================
interface ManualRow {
  _key: number;
  materialTypeId: string;
  warehouseId: string;
  quantity: number | null;
  unitPrice: number | null;
  batchNumber: string;
  productionDate: string;
  expiryDate: string;
}

let manualKeySeq = 0;
function newManualRow(): ManualRow {
  return {
    _key: ++manualKeySeq,
    materialTypeId: '',
    warehouseId: defaultWarehouseId.value,
    quantity: null,
    unitPrice: null,
    batchNumber: '',
    productionDate: '',
    expiryDate: '',
  };
}

const manualRows = reactive<ManualRow[]>([newManualRow()]);

function addManualRow() {
  manualRows.push(newManualRow());
}
function removeManualRow(key: number) {
  const idx = manualRows.findIndex((r) => r._key === key);
  if (idx >= 0) manualRows.splice(idx, 1);
  if (manualRows.length === 0) manualRows.push(newManualRow());
}

function materialName(materialTypeId: string): string {
  return materialTypes.value.find((m) => m.id === materialTypeId)?.name || '';
}

const manualTotalValue = computed(() =>
  manualRows.reduce((sum, r) => {
    if (r.unitPrice == null || r.quantity == null) return sum;
    return sum + r.quantity * r.unitPrice;
  }, 0),
);
const manualNoPriceCount = computed(
  () => manualRows.filter((r) => r.materialTypeId && r.quantity != null && r.unitPrice == null).length,
);

const manualRemark = ref('');

async function submitManual() {
  const items: OpeningInventoryItem[] = manualRows
    .filter((r) => r.materialTypeId || r.warehouseId || r.quantity != null)
    .map((r) => ({
      materialTypeId: r.materialTypeId,
      warehouseId: r.warehouseId,
      quantity: Number(r.quantity),
      unitPrice: r.unitPrice == null ? null : Number(r.unitPrice),
      batchNumber: r.batchNumber || undefined,
      productionDate: r.productionDate || undefined,
      expiryDate: r.expiryDate || undefined,
    }));
  const ok = await submitItems(items, manualRemark.value);
  if (ok) {
    manualRows.splice(0, manualRows.length, newManualRow());
    manualRemark.value = '';
  }
}

// =========================================================================
// Tab 2: Excel 批量导入 (主路径, 215 条物料)
// =========================================================================
function downloadOpeningTemplate() {
  const ws = XLSX.utils.aoa_to_sheet([
    ['物料名称/编码', '数量', '单价', '仓库', '批次号(可选)', '生产日期(可选,YYYY-MM-DD)', '到期日期(可选,YYYY-MM-DD)'],
    ['示例: 猪蹄', 100, 18.5, '原料仓', '', '', ''],
    ['示例: 食盐', 50, 3.2, '原料仓', '', '', ''],
  ]);
  ws['!cols'] = [{ wch: 20 }, { wch: 10 }, { wch: 10 }, { wch: 14 }, { wch: 16 }, { wch: 22 }, { wch: 22 }];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, '期初建账模板');
  XLSX.writeFile(wb, '期初建账导入模板.xlsx');
}

interface ImportPreviewRow {
  _key: number;
  rawMaterialName: string;
  materialTypeId: string;
  warehouseId: string;
  quantity: number | null;
  unitPrice: number | null;
  batchNumber: string;
  productionDate: string;
  expiryDate: string;
}

let previewKeySeq = 0;
const importPreviewVisible = ref(false);
const importPreviewRows = ref<ImportPreviewRow[]>([]);
const importFileInputRef = ref<HTMLInputElement | null>(null);
const importRemark = ref('');

function unmatchedMaterialCount() {
  return importPreviewRows.value.filter((r) => !r.materialTypeId).length;
}
function unmatchedWarehouseCount() {
  return importPreviewRows.value.filter((r) => !r.warehouseId).length;
}
function invalidQuantityCount() {
  return importPreviewRows.value.filter((r) => !(r.quantity != null && r.quantity > 0)).length;
}
const importNoPriceCount = computed(
  () => importPreviewRows.value.filter((r) => r.materialTypeId && r.quantity != null && r.unitPrice == null).length,
);
const importTotalValue = computed(() =>
  importPreviewRows.value.reduce((sum, r) => {
    if (r.unitPrice == null || r.quantity == null) return sum;
    return sum + r.quantity * r.unitPrice;
  }, 0),
);
const importReadyToConfirm = computed(
  () =>
    importPreviewRows.value.length > 0 &&
    unmatchedMaterialCount() === 0 &&
    unmatchedWarehouseCount() === 0 &&
    invalidQuantityCount() === 0,
);

function handleImportClick() {
  if (materialTypes.value.length === 0 || warehouses.value.length === 0) {
    ElMessage.warning('物料/仓库主数据尚未加载完成, 请稍候重试');
    return;
  }
  importFileInputRef.value?.click();
}

function handleFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  input.value = ''; // 允许重复选择同一文件

  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const raw = e.target?.result;
      const wb = XLSX.read(raw, { type: 'binary' });
      const ws = wb.Sheets[wb.SheetNames[0]];
      const jsonRows = XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: '' });

      if (jsonRows.length === 0) {
        ElMessage.warning('Excel 文件没有数据行, 请检查格式');
        return;
      }

      const parsed: ImportPreviewRow[] = jsonRows
        .map((r) => {
          const rawMaterialName = String(r['物料名称/编码'] ?? r['物料名称'] ?? r['物料编码'] ?? '').trim();
          const matched = findMaterialByNameOrCode(rawMaterialName);
          const rawWarehouseName = String(r['仓库'] ?? '').trim();
          // 未填仓库列 → 落默认仓 (原料仓); 填了但没匹配上 → 留空标「待修正」, 不静默瞎猜
          const resolvedWarehouseId = rawWarehouseName
            ? findWarehouseByName(rawWarehouseName)?.id || ''
            : defaultWarehouseId.value;
          const qtyRaw = r['数量'];
          const quantity = qtyRaw === '' || qtyRaw == null ? null : Number(qtyRaw);
          const priceRaw = r['单价'];
          const unitPrice = priceRaw === '' || priceRaw == null ? null : Number(priceRaw);
          return {
            _key: ++previewKeySeq,
            rawMaterialName,
            materialTypeId: matched?.id || '',
            warehouseId: resolvedWarehouseId,
            quantity: quantity != null && !Number.isNaN(quantity) ? quantity : null,
            unitPrice: unitPrice != null && !Number.isNaN(unitPrice) ? unitPrice : null,
            batchNumber: String(r['批次号(可选)'] ?? r['批次号'] ?? '').trim(),
            productionDate: String(r['生产日期(可选,YYYY-MM-DD)'] ?? r['生产日期'] ?? '').trim(),
            expiryDate: String(r['到期日期(可选,YYYY-MM-DD)'] ?? r['到期日期'] ?? '').trim(),
          };
        })
        .filter((r) => r.rawMaterialName.length > 0);

      if (parsed.length === 0) {
        ElMessage.warning('未解析到有效行 (物料名称/编码必填)');
        return;
      }

      importPreviewRows.value = parsed;
      importPreviewVisible.value = true;
    } catch {
      ElMessage({ message: '解析 Excel 文件失败, 请检查文件格式', type: 'error', duration: 0, showClose: true });
    }
  };
  reader.readAsBinaryString(file);
}

async function confirmImport() {
  const items: OpeningInventoryItem[] = importPreviewRows.value.map((r) => ({
    materialTypeId: r.materialTypeId,
    warehouseId: r.warehouseId,
    quantity: Number(r.quantity),
    unitPrice: r.unitPrice == null ? null : Number(r.unitPrice),
    batchNumber: r.batchNumber || undefined,
    productionDate: r.productionDate || undefined,
    expiryDate: r.expiryDate || undefined,
  }));
  const ok = await submitItems(items, importRemark.value);
  if (ok) {
    importPreviewVisible.value = false;
    importPreviewRows.value = [];
    importRemark.value = '';
  }
}

onMounted(loadMasterData);
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title">期初建账</div>
            <div class="page-subtitle">录入建账/上线时的现有库存, 建立库存台账并计入财务</div>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon :icon="InfoFilled" class="info-banner">
        <template #title>
          期初建账做什么: 录入开业/上线时<b>已经存在</b>的库存数量与来源单价 —— 建立库存数量,
          并按 <b>借 1403 原材料 / 贷 4001 实收资本</b> 记一笔期初凭证, <b>不会产生采购应付款</b>。
          这与「采购入库」不同: 采购入库是"向供应商买货, 应付账款增加"; 期初建账是"承认已有的家底"。
          如果用「采购入库」录入期初库存, 会凭空产生一笔从未打算支付的应付账款 (幽灵应付)。
        </template>
      </el-alert>

      <el-tabs type="border-card" class="mode-tabs">
        <!-- ============ Tab 1: 手动录入 ============ -->
        <el-tab-pane label="手动录入">
          <div class="tab-toolbar">
            <el-button :icon="Plus" @click="addManualRow" :disabled="!canWrite">添加一行</el-button>
            <div class="running-total">
              期初存货总额 (Σ数量×单价) = <span class="total-value">{{ formatAmount(manualTotalValue) }}</span>
              <span v-if="manualNoPriceCount > 0" class="warn-text">
                （另有 {{ manualNoPriceCount }} 行无单价, 只建数量不计入此金额）
              </span>
            </div>
          </div>

          <el-table :data="manualRows" v-loading="masterDataLoading" border stripe empty-text="暂无行, 点「添加一行」开始录入">
            <el-table-column label="#" width="50">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="物料" min-width="200">
              <template #default="{ row }">
                <el-select
                  v-model="row.materialTypeId"
                  filterable
                  placeholder="搜索并选择物料"
                  style="width: 100%"
                  :disabled="!canWrite"
                >
                  <el-option v-for="m in materialTypes" :key="m.id" :label="m.name" :value="m.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="仓库" width="160">
              <template #default="{ row }">
                <el-select v-model="row.warehouseId" placeholder="选择仓库" style="width: 100%" :disabled="!canWrite">
                  <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="0" :controls="false" style="width: 100%" :disabled="!canWrite" />
              </template>
            </el-table-column>
            <el-table-column label="单价 (元, 可空)" width="150">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.unitPrice"
                  :min="0"
                  :precision="2"
                  :controls="false"
                  placeholder="无单价则留空"
                  style="width: 100%"
                  :disabled="!canWrite"
                />
              </template>
            </el-table-column>
            <el-table-column label="批次号 (可选, 空则自动生成)" width="180">
              <template #default="{ row }">
                <el-input v-model="row.batchNumber" placeholder="留空自动生成" :disabled="!canWrite" />
              </template>
            </el-table-column>
            <el-table-column label="生产日期 (可选)" width="160">
              <template #default="{ row }">
                <el-date-picker
                  v-model="row.productionDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                  :disabled="!canWrite"
                />
              </template>
            </el-table-column>
            <el-table-column label="到期日期 (可选)" width="160">
              <template #default="{ row }">
                <el-date-picker
                  v-model="row.expiryDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                  :disabled="!canWrite"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" :icon="Delete" :disabled="!canWrite" @click="removeManualRow(row._key)" />
              </template>
            </el-table-column>
          </el-table>

          <el-form label-width="80px" class="remark-form">
            <el-form-item label="备注">
              <el-input v-model="manualRemark" placeholder="可选, 记入凭证摘要" :disabled="!canWrite" />
            </el-form-item>
          </el-form>

          <div class="submit-row">
            <el-button type="primary" :loading="submitting" :disabled="!canWrite" @click="submitManual">
              提交期初建账
            </el-button>
          </div>
        </el-tab-pane>

        <!-- ============ Tab 2: Excel 批量导入 ============ -->
        <el-tab-pane label="Excel 批量导入 (推荐, 适合批量物料)">
          <el-alert type="warning" :closable="false" show-icon class="import-hint">
            <template #title>
              215 条物料建议用此方式: 下载模板 → 按格式填写「物料名称/编码、数量、单价、仓库」→ 导入 →
              系统会先展示预览表让你核对哪些物料匹配上了、哪些还需要手动选, 确认无误后再一次性提交。
            </template>
          </el-alert>

          <div class="tab-toolbar">
            <el-button :icon="Download" @click="downloadOpeningTemplate">下载模板</el-button>
            <el-button :icon="Upload" type="primary" :disabled="!canWrite" @click="handleImportClick">导入 Excel</el-button>
            <input
              ref="importFileInputRef"
              type="file"
              accept=".xlsx,.xls"
              style="display: none"
              @change="handleFileSelected"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- Excel 导入预览对话框 -->
    <el-dialog v-model="importPreviewVisible" title="期初建账 — 导入预览" width="90%" top="4vh" destroy-on-close>
      <el-alert type="info" :closable="false" class="preview-summary">
        <template #title>
          共解析 {{ importPreviewRows.length }} 行；
          <span v-if="unmatchedMaterialCount() > 0" class="warn-text">{{ unmatchedMaterialCount() }} 行未匹配到物料 (需手动选择)；</span>
          <span v-if="unmatchedWarehouseCount() > 0" class="warn-text">{{ unmatchedWarehouseCount() }} 行未匹配到仓库 (需手动选择)；</span>
          <span v-if="invalidQuantityCount() > 0" class="warn-text">{{ invalidQuantityCount() }} 行数量缺失或非法；</span>
          <span v-if="importNoPriceCount > 0" class="warn-text">{{ importNoPriceCount }} 行无单价 (只建数量, 不计入金额)；</span>
          期初存货总额 (Σ数量×单价) = <b>{{ formatAmount(importTotalValue) }}</b>
        </template>
      </el-alert>

      <el-table :data="importPreviewRows" border stripe max-height="480" size="small">
        <el-table-column label="#" width="50">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="Excel 原文" prop="rawMaterialName" width="160" show-overflow-tooltip />
        <el-table-column label="匹配物料" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.materialTypeId" filterable placeholder="未匹配, 请手动选择" style="width: 100%">
              <el-option v-for="m in materialTypes" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="仓库" width="150">
          <template #default="{ row }">
            <el-select v-model="row.warehouseId" placeholder="未匹配, 请手动选择" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :controls="false" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="单价 (可空)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="批次号" prop="batchNumber" width="140" show-overflow-tooltip />
        <el-table-column label="生产日期" prop="productionDate" width="110" />
        <el-table-column label="到期日期" prop="expiryDate" width="110" />
        <el-table-column label="状态" width="110" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="!row.materialTypeId || !row.warehouseId || !(row.quantity > 0)" type="danger" size="small">待修正</el-tag>
            <el-tag v-else-if="row.unitPrice == null" type="warning" size="small">无单价</el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-form label-width="80px" class="remark-form">
        <el-form-item label="备注">
          <el-input v-model="importRemark" placeholder="可选, 记入凭证摘要" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="importPreviewVisible = false">取消</el-button>
        <el-tooltip v-if="!importReadyToConfirm" content="请先修正上表中标「待修正」的行 (物料/仓库/数量必须完整)" placement="top">
          <span><el-button type="primary" disabled>确认导入</el-button></span>
        </el-tooltip>
        <el-button v-else type="primary" :loading="submitting" @click="confirmImport">确认导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrapper {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.page-subtitle {
  margin-top: 4px;
  color: #606266;
  font-size: 13px;
}
.info-banner {
  margin-bottom: 16px;
}
.mode-tabs {
  margin-top: 4px;
}
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.running-total {
  font-size: 14px;
  color: #303133;
}
.total-value {
  font-weight: 700;
  font-size: 16px;
  color: #e6a23c;
}
.warn-text {
  color: #e6a23c;
  margin-left: 6px;
  font-size: 12px;
}
.remark-form {
  margin-top: 12px;
  max-width: 480px;
}
.submit-row {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.import-hint {
  margin-bottom: 12px;
}
.preview-summary {
  margin-bottom: 12px;
}
</style>
