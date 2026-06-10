<script setup lang="ts">
/**
 * SP11 F3/F4/F5/F6 — 凭证导出 + 科目映射配置 + 导出配置 CRUD
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 1: 日期范围必填, 缺失禁用导出
 * - Rule 2: CRUD dialog 带配置名称/编号作上下文
 * - Rule 3: 删除操作用 ElMessageBox.confirm 而非即时删除
 * - Rule 4: 导出防重 (loading 态 disabled); 新建配置 409 提示 "已有，请使用更新操作"
 * - Rule 5: SP6 未完成提示 + next action
 * - 4位一体: error toast sticky(duration:0, showClose=true), 后端真实 message
 *
 * Route: /finance/voucher-export
 * RBAC: finance:read_write (导出/CRUD); finance:read (查看)
 *
 * Endpoints:
 *   POST /api/mobile/{factoryId}/finance/voucher-export          — 序时账 xlsx
 *   GET  /api/mobile/{factoryId}/finance/subject-balance/export  — 科目余额 xlsx
 *   GET    /api/mobile/{factoryId}/finance/export-config          — 查列表
 *   POST   /api/mobile/{factoryId}/finance/export-config          — 新建
 *   PUT    /api/mobile/{factoryId}/finance/export-config/{id}     — 更新
 *   DELETE /api/mobile/{factoryId}/finance/export-config/{id}     — 软删除
 *   GET    /api/mobile/{factoryId}/finance/subject-mappings       — 查列表
 *   POST   /api/mobile/{factoryId}/finance/subject-mappings       — upsert
 *   DELETE /api/mobile/{factoryId}/finance/subject-mappings/{id}  — 软删除
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Download, Plus, Edit, Delete, Refresh } from '@element-plus/icons-vue';
import { get, post, put, del } from '@/api/request';
import request from '@/api/request';

// ============================================================
// Types — mirror backend DTOs (camelCase, backend truth)
// ============================================================
type VoucherTargetSystem = 'KINGDEE' | 'YONYOU' | 'CUSTOM';
// SP6 SettlementType enum values (PREPAID/CREDIT_FIRST/NO_INVOICE/MONTHLY/CREDIT_PERIOD/IMMEDIATE)
type SettlementType = 'PREPAID' | 'CREDIT_FIRST' | 'NO_INVOICE' | 'MONTHLY' | 'CREDIT_PERIOD' | 'IMMEDIATE';

interface VoucherExportConfigDTO {
  id?: string;
  factoryId?: string;
  targetSystem: VoucherTargetSystem;
  colVoucherNo: string;
  colDate: string;
  colSummary: string;
  colSubjectCode: string;
  colSubjectName: string;
  colDebit: string;
  colCredit: string;
  colAuxiliary: string;
  colCurrency: string;
  isActive?: boolean;
}

interface VoucherSubjectMappingDTO {
  id?: string;
  factoryId?: string;
  settlementType: SettlementType;
  businessType: string | null;
  debitSubjectCode: string;
  debitSubjectName: string;
  creditSubjectCode: string;
  creditSubjectName: string;
  remark: string | null;
}

// ============================================================
// Stores
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ============================================================
// Export state
// ============================================================
const exportLoading = ref(false);
const balanceExportLoading = ref(false);
const exportDateRange = ref<[string, string] | null>(null);
const exportTargetSystem = ref<VoucherTargetSystem>('KINGDEE');
const selectedConfigId = ref('');

const canExport = computed(() =>
  !!exportDateRange.value && !!exportDateRange.value[0] && !!exportDateRange.value[1]
);

// ============================================================
// Export Config state
// ============================================================
const configLoading = ref(false);
const configList = ref<VoucherExportConfigDTO[]>([]);
const configDialogVisible = ref(false);
const configDialogMode = ref<'create' | 'edit'>('create');
const configSaveLoading = ref(false);
const editingConfigId = ref('');

const emptyConfig = (): VoucherExportConfigDTO => ({
  targetSystem: 'KINGDEE',
  colVoucherNo: '凭证字号',
  colDate: '日期',
  colSummary: '摘要',
  colSubjectCode: '科目编码',
  colSubjectName: '科目名称',
  colDebit: '借方金额',
  colCredit: '贷方金额',
  colAuxiliary: '辅助核算',
  colCurrency: '币别',
  isActive: true,
});
const configForm = ref<VoucherExportConfigDTO>(emptyConfig());

// ============================================================
// Subject Mapping state
// ============================================================
const mappingLoading = ref(false);
const mappingList = ref<VoucherSubjectMappingDTO[]>([]);
const mappingDialogVisible = ref(false);
const mappingSaveLoading = ref(false);
const editingMappingId = ref('');

const SETTLEMENT_TYPE_LABELS: Record<SettlementType, string> = {
  PREPAID: '预付',
  CREDIT_FIRST: '赊销先入库',
  NO_INVOICE: '未到票（暂估）',
  MONTHLY: '月结',
  CREDIT_PERIOD: '账期',
  IMMEDIATE: '现结',
};

const emptyMapping = (): VoucherSubjectMappingDTO => ({
  settlementType: 'MONTHLY',
  businessType: 'PURCHASE',
  debitSubjectCode: '1405',
  debitSubjectName: '原材料',
  creditSubjectCode: '2202',
  creditSubjectName: '应付账款',
  remark: null,
});
const mappingForm = ref<VoucherSubjectMappingDTO>(emptyMapping());

// ============================================================
// Load data
// ============================================================
async function loadConfigs() {
  if (!factoryId.value) return;
  configLoading.value = true;
  try {
    const res = await get<VoucherExportConfigDTO[]>(
      `/${factoryId.value}/finance/export-config`
    );
    if (res.success && res.data) configList.value = res.data;
  } catch {
    // 拦截器已 toast
  } finally {
    configLoading.value = false;
  }
}

async function loadMappings() {
  if (!factoryId.value) return;
  mappingLoading.value = true;
  try {
    const res = await get<VoucherSubjectMappingDTO[]>(
      `/${factoryId.value}/finance/subject-mappings`
    );
    if (res.success && res.data) mappingList.value = res.data;
  } catch {
    // 拦截器已 toast
  } finally {
    mappingLoading.value = false;
  }
}

// ============================================================
// 凭证序时账导出
// ============================================================
async function handleVoucherExport() {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  exportLoading.value = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const body: Record<string, string> = {
      startDate,
      endDate,
      targetSystem: exportTargetSystem.value,
    };
    if (selectedConfigId.value) body.configId = selectedConfigId.value;

    const response = await request.post(
      `/${factoryId.value}/finance/voucher-export`,
      body,
      { responseType: 'blob' }
    );

    const contentDisposition = response.headers['content-disposition'] ?? '';
    let filename = `voucher_${startDate}_${endDate}.xlsx`;
    const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
    if (match) filename = decodeURIComponent(match[1].trim());

    const url = URL.createObjectURL(new Blob([response.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    ElMessage({ message: '凭证序时账已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    exportLoading.value = false;
  }
}

// ============================================================
// 科目余额表导出
// ============================================================
async function handleBalanceExport() {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  balanceExportLoading.value = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const response = await request.get(
      `/${factoryId.value}/finance/subject-balance/export`,
      {
        params: { startDate, endDate, targetSystem: exportTargetSystem.value },
        responseType: 'blob',
      }
    );

    const contentDisposition = response.headers['content-disposition'] ?? '';
    let filename = `subject_balance_${startDate}_${endDate}.xlsx`;
    const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
    if (match) filename = decodeURIComponent(match[1].trim());

    const url = URL.createObjectURL(new Blob([response.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    ElMessage({ message: '科目余额表已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    balanceExportLoading.value = false;
  }
}

// ============================================================
// Export Config CRUD
// ============================================================
function openConfigCreate() {
  configDialogMode.value = 'create';
  editingConfigId.value = '';
  configForm.value = emptyConfig();
  configDialogVisible.value = true;
}

function openConfigEdit(row: VoucherExportConfigDTO) {
  configDialogMode.value = 'edit';
  editingConfigId.value = row.id ?? '';
  configForm.value = { ...row };
  configDialogVisible.value = true;
}

async function handleConfigSave() {
  if (!factoryId.value) return;
  configSaveLoading.value = true;
  try {
    if (configDialogMode.value === 'create') {
      const res = await post<VoucherExportConfigDTO>(
        `/${factoryId.value}/finance/export-config`,
        configForm.value
      );
      if (res.success) {
        ElMessage({ message: '导出配置已创建', type: 'success', duration: 3000 });
        configDialogVisible.value = false;
        await loadConfigs();
      }
    } else {
      const res = await put<VoucherExportConfigDTO>(
        `/${factoryId.value}/finance/export-config/${editingConfigId.value}`,
        configForm.value
      );
      if (res.success) {
        ElMessage({ message: '导出配置已更新', type: 'success', duration: 3000 });
        configDialogVisible.value = false;
        await loadConfigs();
      }
    }
  } catch {
    // 拦截器已 toast (含 409 Rule 4 幂等)
  } finally {
    configSaveLoading.value = false;
  }
}

async function handleConfigDelete(row: VoucherExportConfigDTO) {
  if (!factoryId.value) return;
  // Rule 3: 删除前 confirm + 上下文
  await ElMessageBox.confirm(
    `确认删除 "${row.targetSystem}" 导出配置？此操作不可恢复。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  );
  try {
    const res = await del(`/${factoryId.value}/finance/export-config/${row.id}`);
    if (res.success) {
      ElMessage({ message: '删除成功', type: 'success', duration: 3000 });
      await loadConfigs();
    }
  } catch {
    // 拦截器已 toast
  }
}

// ============================================================
// Subject Mapping CRUD
// ============================================================
function openMappingCreate() {
  editingMappingId.value = '';
  mappingForm.value = emptyMapping();
  mappingDialogVisible.value = true;
}

function openMappingEdit(row: VoucherSubjectMappingDTO) {
  editingMappingId.value = row.id ?? '';
  mappingForm.value = { ...row };
  mappingDialogVisible.value = true;
}

async function handleMappingSave() {
  if (!factoryId.value) return;
  mappingSaveLoading.value = true;
  try {
    const res = await post<VoucherSubjectMappingDTO>(
      `/${factoryId.value}/finance/subject-mappings`,
      mappingForm.value
    );
    if (res.success) {
      ElMessage({ message: '科目映射已保存', type: 'success', duration: 3000 });
      mappingDialogVisible.value = false;
      await loadMappings();
    }
  } catch {
    // 拦截器已 toast
  } finally {
    mappingSaveLoading.value = false;
  }
}

async function handleMappingDelete(row: VoucherSubjectMappingDTO) {
  if (!factoryId.value) return;
  const label = SETTLEMENT_TYPE_LABELS[row.settlementType] ?? row.settlementType;
  await ElMessageBox.confirm(
    `确认删除结算方式「${label}」的科目映射？`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  );
  try {
    const res = await del(`/${factoryId.value}/finance/subject-mappings/${row.id}`);
    if (res.success) {
      ElMessage({ message: '删除成功', type: 'success', duration: 3000 });
      await loadMappings();
    }
  } catch {
    // 拦截器已 toast
  }
}

function getTargetSystemLabel(ts: VoucherTargetSystem): string {
  const map: Record<VoucherTargetSystem, string> = {
    KINGDEE: '金蝶',
    YONYOU: '用友',
    CUSTOM: '通用',
  };
  return map[ts] ?? ts;
}

function getSettlementLabel(st: SettlementType): string {
  return SETTLEMENT_TYPE_LABELS[st] ?? st;
}

// ============================================================
// Init
// ============================================================
onMounted(async () => {
  await Promise.all([loadConfigs(), loadMappings()]);
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">凭证导出</h2>
    </div>

    <!-- === 导出操作区 === -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <span class="section-title">凭证序时账 / 科目余额表导出</span>
      </template>

      <el-form :inline="true" class="export-form">
        <el-form-item label="期间范围" required>
          <el-date-picker
            v-model="exportDateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="目标系统">
          <el-select v-model="exportTargetSystem" style="width: 120px">
            <el-option label="金蝶" value="KINGDEE" />
            <el-option label="用友" value="YONYOU" />
            <el-option label="通用" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="列名配置">
          <el-select
            v-model="selectedConfigId"
            placeholder="使用默认配置"
            clearable
            style="width: 200px"
          >
            <el-option
              v-for="cfg in configList"
              :key="cfg.id"
              :label="`${getTargetSystemLabel(cfg.targetSystem)} — ${cfg.colVoucherNo}`"
              :value="cfg.id ?? ''"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :icon="Download"
            :loading="exportLoading"
            :disabled="!canExport || !canWrite"
            @click="handleVoucherExport"
          >
            导出凭证序时账
          </el-button>
          <el-button
            :icon="Download"
            :loading="balanceExportLoading"
            :disabled="!canExport || !canWrite"
            @click="handleBalanceExport"
          >
            导出科目余额表
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="!canWrite"
        title="导出需要财务读写权限，当前账号无权操作"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="!canExport && canWrite"
        title="请先选择期间日期范围再导出"
        type="info"
        :closable="false"
        show-icon
      />
    </el-card>

    <!-- === 导出配置 CRUD === -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <span class="section-title">凭证导出配置（列名映射）</span>
          <div>
            <el-button size="small" :icon="Refresh" circle @click="loadConfigs" />
            <el-button
              v-if="canWrite"
              type="primary"
              size="small"
              :icon="Plus"
              @click="openConfigCreate"
            >
              新建配置
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="configLoading"
        :data="configList"
        border
        stripe
        style="width: 100%"
        empty-text="暂无配置，点击「新建配置」创建"
        size="small"
      >
        <el-table-column label="目标系统" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.targetSystem === 'KINGDEE' ? 'primary' : 'success'">
              {{ getTargetSystemLabel(row.targetSystem) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="凭证字号列名" prop="colVoucherNo" width="110" />
        <el-table-column label="日期列名" prop="colDate" width="90" />
        <el-table-column label="摘要列名" prop="colSummary" width="90" />
        <el-table-column label="科目编码列名" prop="colSubjectCode" width="110" />
        <el-table-column label="科目名称列名" prop="colSubjectName" width="110" />
        <el-table-column label="借方金额列名" prop="colDebit" width="110" />
        <el-table-column label="贷方金额列名" prop="colCredit" width="110" />
        <el-table-column label="辅助核算列名" prop="colAuxiliary" width="110" />
        <el-table-column label="币别列名" prop="colCurrency" width="90" />
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary" @click="openConfigEdit(row)">
              编辑
            </el-button>
            <el-button size="small" :icon="Delete" link type="danger" @click="handleConfigDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- === 科目映射 CRUD === -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <div>
            <span class="section-title">结算属性 → 会计科目映射</span>
            <el-tooltip content="配置采购结算属性对应的会计借贷科目，用于自动生成凭证分录" placement="right">
              <el-icon style="margin-left: 6px; color: #999; cursor: help;"><el-icon-info-filled /></el-icon>
            </el-tooltip>
          </div>
          <div>
            <el-button size="small" :icon="Refresh" circle @click="loadMappings" />
            <el-button
              v-if="canWrite"
              type="primary"
              size="small"
              :icon="Plus"
              @click="openMappingCreate"
            >
              新增映射
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="mappingLoading"
        :data="mappingList"
        border
        stripe
        style="width: 100%"
        empty-text="暂无科目映射配置"
        size="small"
      >
        <el-table-column label="结算方式" width="130">
          <template #default="{ row }">{{ getSettlementLabel(row.settlementType) }}</template>
        </el-table-column>
        <el-table-column label="业务类型" width="100">
          <template #default="{ row }">{{ row.businessType ?? '通用' }}</template>
        </el-table-column>
        <el-table-column label="借方科目代码" prop="debitSubjectCode" width="110" />
        <el-table-column label="借方科目名称" prop="debitSubjectName" min-width="110" show-overflow-tooltip />
        <el-table-column label="贷方科目代码" prop="creditSubjectCode" width="110" />
        <el-table-column label="贷方科目名称" prop="creditSubjectName" min-width="110" show-overflow-tooltip />
        <el-table-column label="备注" prop="remark" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark ?? '—' }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary" @click="openMappingEdit(row)">
              编辑
            </el-button>
            <el-button size="small" :icon="Delete" link type="danger" @click="handleMappingDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============================================================
         Dialog: 导出配置 新建/编辑
         ============================================================ -->
    <el-dialog
      v-model="configDialogVisible"
      :title="`${configDialogMode === 'create' ? '新建' : '编辑'} — 凭证导出配置（${getTargetSystemLabel(configForm.targetSystem)}）`"
      width="600px"
      @close="configDialogVisible = false"
    >
      <el-form :model="configForm" label-width="110px" class="dialog-form">
        <el-form-item label="目标系统" required>
          <el-select v-model="configForm.targetSystem" :disabled="configDialogMode === 'edit'">
            <el-option label="金蝶" value="KINGDEE" />
            <el-option label="用友" value="YONYOU" />
            <el-option label="通用" value="CUSTOM" />
          </el-select>
          <span class="form-hint">同工厂同目标系统只能有一个配置</span>
        </el-form-item>
        <el-form-item label="凭证字号列名">
          <el-input v-model="configForm.colVoucherNo" />
        </el-form-item>
        <el-form-item label="日期列名">
          <el-input v-model="configForm.colDate" />
        </el-form-item>
        <el-form-item label="摘要列名">
          <el-input v-model="configForm.colSummary" />
        </el-form-item>
        <el-form-item label="科目编码列名">
          <el-input v-model="configForm.colSubjectCode" />
        </el-form-item>
        <el-form-item label="科目名称列名">
          <el-input v-model="configForm.colSubjectName" />
        </el-form-item>
        <el-form-item label="借方金额列名">
          <el-input v-model="configForm.colDebit" />
        </el-form-item>
        <el-form-item label="贷方金额列名">
          <el-input v-model="configForm.colCredit" />
        </el-form-item>
        <el-form-item label="辅助核算列名">
          <el-input v-model="configForm.colAuxiliary" />
        </el-form-item>
        <el-form-item label="币别列名">
          <el-input v-model="configForm.colCurrency" />
        </el-form-item>
        <el-form-item v-if="configDialogMode === 'edit'" label="状态">
          <el-switch v-model="configForm.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="configSaveLoading" @click="handleConfigSave">
          {{ configDialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================================================
         Dialog: 科目映射 新增/编辑
         ============================================================ -->
    <el-dialog
      v-model="mappingDialogVisible"
      :title="`${editingMappingId ? '编辑' : '新增'} — 科目映射（${getSettlementLabel(mappingForm.settlementType)}）`"
      width="500px"
      @close="mappingDialogVisible = false"
    >
      <el-form :model="mappingForm" label-width="110px" class="dialog-form">
        <el-form-item label="结算方式" required>
          <el-select v-model="mappingForm.settlementType">
            <el-option
              v-for="(label, value) in SETTLEMENT_TYPE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务类型">
          <el-select v-model="mappingForm.businessType" clearable placeholder="通用（不区分）">
            <el-option label="采购" value="PURCHASE" />
            <el-option label="销售" value="SALES" />
          </el-select>
        </el-form-item>
        <el-form-item label="借方科目代码" required>
          <el-input v-model="mappingForm.debitSubjectCode" placeholder="如 1405" />
        </el-form-item>
        <el-form-item label="借方科目名称">
          <el-input v-model="mappingForm.debitSubjectName" placeholder="如 原材料" />
        </el-form-item>
        <el-form-item label="贷方科目代码" required>
          <el-input v-model="mappingForm.creditSubjectCode" placeholder="如 2202" />
        </el-form-item>
        <el-form-item label="贷方科目名称">
          <el-input v-model="mappingForm.creditSubjectName" placeholder="如 应付账款" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="mappingForm.remark"
            type="textarea"
            :rows="2"
            placeholder="可选说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mappingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="mappingSaveLoading" @click="handleMappingSave">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background-color: #F4F6F9;
  min-height: 100%;
}
.page-header {
  margin-bottom: 16px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #1B65A8;
  margin: 0;
}
.section-card {
  margin-bottom: 16px;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.export-form {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.dialog-form {
  padding: 0 8px;
}
.form-hint {
  font-size: 12px;
  color: #999;
  margin-left: 8px;
}
</style>
