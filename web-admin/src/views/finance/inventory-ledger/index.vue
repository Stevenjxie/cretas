<script setup lang="ts">
/**
 * SP11 F1/F2 — 进销存台账报表 + Excel 导出
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 1: 日期范围必填, 缺失禁用查询
 * - Rule 2: 表头显示期间 + 物料筛选上下文
 * - Rule 3: @PriceSensitive null 金额列显示"—", tooltip 说明需财务权限
 * - Rule 4: 导出防重按钮(loading 态 disabled)
 * - 4位一体: error toast sticky(duration:0, showClose=true), 后端真实 message
 *
 * Route: /finance/inventory-ledger
 * RBAC: inventory:read (后端控制), 金额字段由 PriceFieldResponseAdvice 自动脱敏
 *
 * Endpoints:
 *   GET /api/mobile/{factoryId}/inventory/ledger
 *     params: startDate, endDate, materialTypeId?
 *     returns: { data: { factoryId, startDate, endDate, lines: InventoryLedgerLineDTO[] } }
 *   GET /api/mobile/{factoryId}/inventory/ledger/export
 *     params: startDate, endDate, materialTypeId?, targetSystem?
 *     returns: xlsx attachment
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Download, Search } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import request from '@/api/request';

// ============================================================
// Types — mirror InventoryLedgerLineDTO (camelCase, backend truth)
// ============================================================
interface InventoryLedgerLineDTO {
  materialTypeId: string;
  materialCode: string | null;
  materialName: string | null;
  unit: string | null;
  // 数量 (全角色可见)
  openingQty: number | null;
  inboundQty: number | null;
  outboundProductionQty: number | null;
  outboundSalesQty: number | null;
  transferInQty: number | null;
  transferOutQty: number | null;
  adjustQty: number | null;
  closingQty: number | null;
  // 金额 (@PriceSensitive — 仓管角色后端返 null)
  openingAmount: number | null;
  inboundAmount: number | null;
  outboundAmount: number | null;
  adjustAmount: number | null;
  closingAmount: number | null;
  movingAvgUnitPrice: number | null;
}

interface InventoryLedgerDTO {
  factoryId: string;
  startDate: string;
  endDate: string;
  lines: InventoryLedgerLineDTO[];
}

type VoucherTargetSystem = 'KINGDEE' | 'YONYOU' | 'CUSTOM';

// ============================================================
// Stores
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ============================================================
// State
// ============================================================
const loading = ref(false);
const exportLoading = ref(false);
const tableData = ref<InventoryLedgerLineDTO[]>([]);
const ledgerPeriod = ref({ startDate: '', endDate: '' });

// Filters
const dateRange = ref<[string, string] | null>(null);
const materialTypeId = ref('');
const targetSystem = ref<VoucherTargetSystem>('KINGDEE');

// ============================================================
// Computed
// ============================================================
const canQuery = computed(() => {
  return !!dateRange.value && !!dateRange.value[0] && !!dateRange.value[1];
});

const periodLabel = computed(() => {
  if (ledgerPeriod.value.startDate && ledgerPeriod.value.endDate) {
    return `${ledgerPeriod.value.startDate} 至 ${ledgerPeriod.value.endDate}`;
  }
  return '';
});

// ============================================================
// Helpers
// ============================================================
function fmtQty(v: number | null): string {
  if (v === null || v === undefined) return '0.000000';
  return Number(v).toFixed(6);
}

function fmtAmt(v: number | null): string {
  // @PriceSensitive: null → "—" (后端已脱敏, 前端不重算)
  if (v === null || v === undefined) return '—';
  return Number(v).toFixed(2);
}

function fmtPrice(v: number | null): string {
  if (v === null || v === undefined) return '—';
  return Number(v).toFixed(4);
}

function amtTooltip(v: number | null): string {
  if (v === null || v === undefined) return '需要财务权限查看金额';
  return '';
}

// ============================================================
// Data load
// ============================================================
async function loadData() {
  if (!canQuery.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [startDate, endDate] = dateRange.value!;
    const params: Record<string, string> = { startDate, endDate };
    if (materialTypeId.value) params.materialTypeId = materialTypeId.value;

    const res = await get<InventoryLedgerDTO>(
      `/${factoryId.value}/inventory/ledger`,
      { params }
    );
    if (res.success && res.data) {
      tableData.value = res.data.lines ?? [];
      ledgerPeriod.value = {
        startDate: res.data.startDate,
        endDate: res.data.endDate,
      };
    }
  } catch {
    // 拦截器已 toast
  } finally {
    loading.value = false;
  }
}

// ============================================================
// Export
// ============================================================
async function handleExport() {
  if (!canQuery.value) {
    ElMessage({ message: '请先选择日期范围再导出', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  exportLoading.value = true;
  try {
    const [startDate, endDate] = dateRange.value!;
    const params: Record<string, string> = {
      startDate,
      endDate,
      targetSystem: targetSystem.value,
    };
    if (materialTypeId.value) params.materialTypeId = materialTypeId.value;

    const response = await request.get(
      `/${factoryId.value}/inventory/ledger/export`,
      { params, responseType: 'blob' }
    );

    // 触发浏览器下载
    const contentDisposition = response.headers['content-disposition'] ?? '';
    let filename = `inventory_ledger_${startDate}_${endDate}.xlsx`;
    const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
    if (match) {
      filename = decodeURIComponent(match[1].trim());
    }

    const url = URL.createObjectURL(new Blob([response.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    ElMessage({ message: '台账已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    exportLoading.value = false;
  }
}

function handleReset() {
  dateRange.value = null;
  materialTypeId.value = '';
  tableData.value = [];
  ledgerPeriod.value = { startDate: '', endDate: '' };
}

onMounted(() => {
  // 默认不自动查询，需用户选日期后手动触发（防呆 Rule 1）
});
</script>

<template>
  <div class="page-container">
    <!-- 页头 -->
    <div class="page-header">
      <div class="page-header__left">
        <h2 class="page-title">进销存台账</h2>
        <span v-if="periodLabel" class="period-badge">{{ periodLabel }}</span>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" class="filter-form">
        <el-form-item label="日期范围" required>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="物料代码/名称">
          <el-input
            v-model="materialTypeId"
            placeholder="物料类型 ID（可选）"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="导出格式">
          <el-select v-model="targetSystem" style="width: 120px">
            <el-option label="金蝶" value="KINGDEE" />
            <el-option label="用友" value="YONYOU" />
            <el-option label="通用" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :icon="Search"
            :loading="loading"
            :disabled="!canQuery"
            @click="loadData"
          >
            查询
          </el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button
            type="success"
            :icon="Download"
            :loading="exportLoading"
            :disabled="!canQuery"
            @click="handleExport"
          >
            导出 Excel
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 台账表格 -->
    <el-card shadow="never" class="table-card">
      <el-alert
        v-if="!canViewPrice && tableData.length > 0"
        title="金额列需要财务权限查看，当前仅显示数量"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <el-table
        v-loading="loading"
        :data="tableData"
        border
        stripe
        style="width: 100%"
        empty-text="请选择日期范围后点击查询"
        size="small"
      >
        <el-table-column label="物料代码" prop="materialCode" width="120" fixed="left" />
        <el-table-column label="物料名称" prop="materialName" min-width="140" fixed="left" show-overflow-tooltip />
        <el-table-column label="单位" prop="unit" width="60" />

        <!-- 期初 -->
        <el-table-column label="期初数量" width="110" align="right">
          <template #default="{ row }">{{ fmtQty(row.openingQty) }}</template>
        </el-table-column>
        <el-table-column label="期初金额" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.openingAmount" :content="amtTooltip(row.openingAmount)" placement="top">
              <span class="price-masked">{{ fmtAmt(row.openingAmount) }}</span>
            </el-tooltip>
            <span v-else>{{ fmtAmt(row.openingAmount) }}</span>
          </template>
        </el-table-column>

        <!-- 入库 -->
        <el-table-column label="入库数量" width="110" align="right">
          <template #default="{ row }">{{ fmtQty(row.inboundQty) }}</template>
        </el-table-column>
        <el-table-column label="入库金额" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.inboundAmount" :content="amtTooltip(row.inboundAmount)" placement="top">
              <span class="price-masked">{{ fmtAmt(row.inboundAmount) }}</span>
            </el-tooltip>
            <span v-else>{{ fmtAmt(row.inboundAmount) }}</span>
          </template>
        </el-table-column>

        <!-- 出库：生产领用 -->
        <el-table-column label="领用数量" width="110" align="right">
          <template #default="{ row }">{{ fmtQty(row.outboundProductionQty) }}</template>
        </el-table-column>

        <!-- 出库：销售出货 -->
        <el-table-column label="出货数量" width="110" align="right">
          <template #default="{ row }">{{ fmtQty(row.outboundSalesQty) }}</template>
        </el-table-column>

        <!-- 出库合计金额 -->
        <el-table-column label="出库金额" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.outboundAmount" :content="amtTooltip(row.outboundAmount)" placement="top">
              <span class="price-masked">{{ fmtAmt(row.outboundAmount) }}</span>
            </el-tooltip>
            <span v-else>{{ fmtAmt(row.outboundAmount) }}</span>
          </template>
        </el-table-column>

        <!-- 调拨 -->
        <el-table-column label="调入数量" width="100" align="right">
          <template #default="{ row }">{{ fmtQty(row.transferInQty) }}</template>
        </el-table-column>
        <el-table-column label="调出数量" width="100" align="right">
          <template #default="{ row }">{{ fmtQty(row.transferOutQty) }}</template>
        </el-table-column>

        <!-- 盘盈/损 -->
        <el-table-column label="盘点调整" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-danger': (row.adjustQty ?? 0) < 0 }">
              {{ fmtQty(row.adjustQty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="调整金额" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.adjustAmount" :content="amtTooltip(row.adjustAmount)" placement="top">
              <span class="price-masked">{{ fmtAmt(row.adjustAmount) }}</span>
            </el-tooltip>
            <span v-else :class="{ 'text-danger': (row.adjustAmount ?? 0) < 0 }">
              {{ fmtAmt(row.adjustAmount) }}
            </span>
          </template>
        </el-table-column>

        <!-- 期末 -->
        <el-table-column label="期末数量" width="110" align="right" fixed="right">
          <template #default="{ row }">
            <strong>{{ fmtQty(row.closingQty) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="期末金额" width="110" align="right" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.closingAmount" :content="amtTooltip(row.closingAmount)" placement="top">
              <strong class="price-masked">{{ fmtAmt(row.closingAmount) }}</strong>
            </el-tooltip>
            <strong v-else>{{ fmtAmt(row.closingAmount) }}</strong>
          </template>
        </el-table-column>

        <!-- 移动均价 -->
        <el-table-column label="移动均价" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="!row.movingAvgUnitPrice" content="需要财务权限查看单价" placement="top">
              <span class="price-masked">{{ fmtPrice(row.movingAvgUnitPrice) }}</span>
            </el-tooltip>
            <span v-else>{{ fmtPrice(row.movingAvgUnitPrice) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="tableData.length" class="table-footer">
        共 {{ tableData.length }} 条物料
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background-color: #F4F6F9;
  min-height: 100%;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header__left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #1B65A8;
  margin: 0;
}
.period-badge {
  font-size: 13px;
  color: #666;
  background: #e8f0fe;
  padding: 2px 10px;
  border-radius: 12px;
}
.filter-card {
  margin-bottom: 16px;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}
.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.table-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}
.price-masked {
  color: #aaa;
  cursor: help;
}
.text-danger {
  color: #f56c6c;
}
.table-footer {
  margin-top: 12px;
  font-size: 13px;
  color: #999;
  text-align: right;
}
</style>
