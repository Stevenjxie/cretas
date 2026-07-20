<script setup lang="ts">
import { ref, computed, nextTick, onMounted, reactive } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import type { FormInstance } from 'element-plus';
import DynamicEntityForm from '@/components/DynamicEntityForm.vue';
import type { FieldConfig } from '@/config/entityFieldConfigs';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import CreditPanel from './components/CreditPanel.vue';
import type { TableRow } from '@/types/api';
import {
  CUSTOMER_ADVANCED_FIELDS,
  CUSTOMER_TYPE_OPTIONS,
  countFilledCustomerAdvancedFields,
  customerTypeLabel,
  isCustomerAdvancedField,
  isSupportedCustomerPhone,
  normalizeCustomerPayload,
} from './customerFormModel';

// P1 #23 S-CREDIT-1 — 信用状态映射
type CreditStatusValue = 'NORMAL' | 'WARNING' | 'SUSPENDED';
const CREDIT_STATUS_OPTIONS: Array<{ value: CreditStatusValue; label: string; tagType: 'success' | 'warning' | 'danger' }> = [
  { value: 'NORMAL', label: '正常', tagType: 'success' },
  { value: 'WARNING', label: '预警', tagType: 'warning' },
  { value: 'SUSPENDED', label: '冻结', tagType: 'danger' },
];
function getCreditStatusOption(v: string | undefined | null) {
  return CREDIT_STATUS_OPTIONS.find(o => o.value === v);
}
// Sprint 4 W2 S-CRM-FULL-1 — 防呆 R3: dropdown options 来自 single source of truth
import {
  CUSTOMER_STATUS_OPTIONS,
  CUSTOMER_IMPORTANCE_OPTIONS,
  CUSTOMER_SOURCE_OPTIONS,
  INVOICE_TYPE_OPTIONS,
  getCustomerStatusOption,
  getCustomerImportanceOption,
  getCustomerSourceOption,
  getInvoiceTypeOption,
  type CustomerStatusValue,
  type CustomerImportanceValue,
  type CustomerSourceValue,
  type InvoiceTypeValue,
} from '@/constants/customerEnums';

// 客户扩展字段 — 添加新字段只需在此数组加一行
const customerExtendedFields: FieldConfig[] = [
  { key: 'taxNumber', label: '纳税人识别号', type: 'text', group: '开票信息', order: 1 },
  { key: 'billingAddress', label: '开票地址', type: 'text', group: '开票信息', order: 2 },
  { key: 'bankName', label: '开户行', type: 'text', group: '开票信息', order: 3 },
  { key: 'bankAccount', label: '银行账号', type: 'text', group: '开票信息', order: 4 },
  { key: 'creditLimit', label: '信用额度', type: 'decimal', group: '信用管理', precision: 2, suffix: '元', order: 5 },
  { key: 'paymentTerms', label: '结算模式', type: 'text', group: '信用管理', order: 6, placeholder: '如 月结30天' },
  // P1 #23 S-CREDIT-1: 信用账期天数 + 信用状态
  { key: 'creditPeriodDays', label: '信用账期', type: 'decimal', group: '信用管理', precision: 0, suffix: '天', order: 7, placeholder: '默认 30' },
];

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: Math.max(1, Number(route.query.page) || 1), size: Math.max(1, Number(route.query.size) || 10), total: 0 });
const searchKeyword = ref(String(route.query.keyword || ''));
// Sprint 4 W2 S-CRM-FULL-1 — list filters (R3 dropdown, 任一为空 = 不过滤)
const filterCustomerStatus = ref<CustomerStatusValue | ''>((route.query.customerStatus as CustomerStatusValue) || '');
const filterImportance = ref<CustomerImportanceValue | ''>((route.query.importance as CustomerImportanceValue) || '');
const filterSource = ref<CustomerSourceValue | ''>((route.query.source as CustomerSourceValue) || '');
const filterCustomerType = ref(String(route.query.customerType || ''));
const advancedFiltersVisible = ref(Boolean(filterCustomerStatus.value || filterImportance.value || filterSource.value));

onMounted(async () => {
  await loadData();
  const scrollY = Math.max(0, Number(route.query.scrollY) || 0);
  if (scrollY) {
    await nextTick();
    window.scrollTo({ top: scrollY });
  }
});

function listRouteQuery(includeScroll = false): Record<string, string> {
  const query: Record<string, string> = {};
  if (pagination.value.page !== 1) query.page = String(pagination.value.page);
  if (pagination.value.size !== 10) query.size = String(pagination.value.size);
  if (searchKeyword.value.trim()) query.keyword = searchKeyword.value.trim();
  if (filterCustomerType.value) query.customerType = filterCustomerType.value;
  if (filterCustomerStatus.value) query.customerStatus = filterCustomerStatus.value;
  if (filterImportance.value) query.importance = filterImportance.value;
  if (filterSource.value) query.source = filterSource.value;
  if (includeScroll && window.scrollY > 0) query.scrollY = String(Math.round(window.scrollY));
  return query;
}

async function syncListRoute(includeScroll = false): Promise<void> {
  await router.replace({ path: '/sales/customers', query: listRouteQuery(includeScroll) });
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/customers`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined,
        type: filterCustomerType.value || undefined,
        // Sprint 4 W2 S-CRM-FULL-1 — 3 个 CRM 过滤器 (空字符串 → undefined → 后端 IS NULL 分支)
        customerStatus: filterCustomerStatus.value || undefined,
        importance: filterImportance.value || undefined,
        source: filterSource.value || undefined,
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleSearch() {
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

async function handleRefresh() {
  searchKeyword.value = '';
  filterCustomerStatus.value = '';
  filterImportance.value = '';
  filterSource.value = '';
  filterCustomerType.value = '';
  advancedFiltersVisible.value = false;
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

async function handlePageChange(page: number) {
  pagination.value.page = page;
  await syncListRoute();
  await loadData();
}

async function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'add' | 'edit' | 'view'>('add');
const formRef = ref<FormInstance>();
const submitting = ref(false);
const advancedSections = ref<string[]>([]);

const defaultForm = {
  id: '',
  customerCode: '',  // S-CRM-FULL-1 R2: 显示 dialog context, read-only
  name: '',
  contactPerson: '',
  phone: '',
  shippingAddress: '',
  email: '',
  type: '',
  industry: '',
  notes: '',
  status: 'ACTIVE',  // T10: status field default
  // Sprint 4 W2 S-CRM-FULL-1 — 4 CRM 字段 (新增/编辑 dialog 内)
  customerStatus: '' as CustomerStatusValue | '',
  importance: '' as CustomerImportanceValue | '',
  source: '' as CustomerSourceValue | '',
  lastContactedAt: null as string | null,
  // Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认 (Option 3 三层链第 1 层)
  defaultTaxRate: null as number | null,
  defaultInvoiceType: '' as InvoiceTypeValue | '',
  // P1 #23 S-CREDIT-1 — 信用状态 (creditLimit/creditPeriodDays 走 extendedFields)
  creditStatus: '' as CreditStatusValue | '',
  version: null as number | null,  // optimistic lock — echoed on PUT, server returns 409 on mismatch
};
const formData = reactive({ ...defaultForm });

function requiredTextRule(label: string) {
  return {
    validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
      if (!String(value ?? '').trim()) callback(new Error(`请输入${label}`));
      else callback();
    },
    trigger: ['blur', 'change'],
  };
}

const formRules = {
  name: [requiredTextRule('客户名称')],
  contactPerson: [requiredTextRule('联系人')],
  phone: [
    requiredTextRule('联系电话'),
    {
      validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
        if (!String(value ?? '').trim() || isSupportedCustomerPhone(value)) callback();
        else callback(new Error('支持手机号、企业座机/分机或国际电话号码'));
      },
      trigger: ['blur', 'change'],
    },
  ],
  shippingAddress: [requiredTextRule('收货地址')],
  status: [{ required: true, message: '请选择客户状态', trigger: 'change' }],
  email: [{ type: 'email', message: '请输入有效邮箱地址', trigger: ['blur', 'change'] }],
};

const advancedFieldKeys = Array.from(new Set([
  ...CUSTOMER_ADVANCED_FIELDS,
  ...customerExtendedFields.map((field) => field.key),
]));
const advancedFilledCount = computed(() => countFilledCustomerAdvancedFields(formData as TableRow, advancedFieldKeys));

const dialogTitle = computed(() => {
  // S-CRM-FULL-1 防呆 R2: edit/view dialog title 必带客户身份信息 (name + code)
  if (dialogMode.value === 'add') return '新增客户';
  const ctx = formData.name
    ? ` — ${formData.name}${formData.customerCode ? ` (${formData.customerCode})` : ''}`
    : '';
  return (dialogMode.value === 'edit' ? '编辑客户' : '查看客户') + ctx;
});

const isViewMode = computed(() => dialogMode.value === 'view');

function handleAdd() {
  dialogMode.value = 'add';
  Object.assign(
    formData,
    defaultForm,
    Object.fromEntries(customerExtendedFields.map((field): [string, null] => [field.key, null])),
  );
  advancedSections.value = [];
  dialogVisible.value = true;
  void nextTick(() => formRef.value?.clearValidate());
}

async function handleView(row: TableRow) {
  // Bug #6 fix: 跳转到 21-tab 客户 360° 详情页, 而非打开 basic dialog
  await syncListRoute(true);
  const returnTarget = router.resolve({ path: '/sales/customers', query: listRouteQuery(true) }).fullPath;
  await router.push({
    name: 'SalesCustomerDetail',
    params: { id: row.id as string },
    query: { from: returnTarget },
  });
}

function handleEdit(row: TableRow) {
  dialogMode.value = 'edit';
  Object.assign(formData, {
    id: row.id,
    customerCode: (row.customerCode as string) || '',
    name: row.name || '',
    contactPerson: row.contactPerson || '',
    phone: row.phone || '',
    version: typeof row.version === 'number' ? row.version : null,
    shippingAddress: row.shippingAddress || row.address || '',
    email: row.email || '',
    type: row.type || '',
    industry: row.industry || '',
    notes: row.notes || '',
    status: (row.status as string) || 'ACTIVE',  // T10
    // Sprint 4 W2 S-CRM-FULL-1
    customerStatus: (row.customerStatus as CustomerStatusValue) || '',
    importance: (row.importance as CustomerImportanceValue) || '',
    source: (row.source as CustomerSourceValue) || '',
    lastContactedAt: (row.lastContactedAt as string) || null,
    // Sprint 4 W2 S-INVOICE-CLIENT-1
    defaultTaxRate: (row.defaultTaxRate as number | null) ?? null,
    defaultInvoiceType: (row.defaultInvoiceType as InvoiceTypeValue) || '',
    // P1 #23 S-CREDIT-1
    creditStatus: (row.creditStatus as CreditStatusValue) || '',
    ...Object.fromEntries(customerExtendedFields.map((field) => [field.key, row[field.key] ?? null] as const)),
  });
  advancedSections.value = [];
  dialogVisible.value = true;
  void nextTick(() => formRef.value?.clearValidate());
}

async function handleSubmit() {
  if (!formRef.value || submitting.value) return;
  try {
    await formRef.value.validate();
  } catch (fields) {
    const invalidFields = Object.keys((fields as Record<string, unknown>) || {});
    const advancedInvalid = invalidFields.find(isCustomerAdvancedField);
    if (advancedInvalid) {
      advancedSections.value = ['advanced'];
      await nextTick();
      formRef.value?.scrollToField(advancedInvalid);
    }
    return;
  }
  submitting.value = true;
  try {
    const payload: TableRow = normalizeCustomerPayload(
      formData as TableRow,
      customerExtendedFields.map((field) => field.key),
    );
    let res;
    if (dialogMode.value === 'add') {
      res = await post(`/${factoryId.value}/customers`, payload);
    } else {
      // Optimistic lock: include version snapshot so BE can detect concurrent edit.
      if (formData.version !== null && formData.version !== undefined) {
        payload.version = formData.version;
      }
      res = await put(`/${factoryId.value}/customers/${formData.id}`, payload);
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'add' ? '新增成功' : '编辑成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (error) {
    // R24 P2 follow-up: 409 with actionHint = business invariant (already rich-toasted by
    // interceptor). 409 without actionHint = vanilla optimistic-lock — show refresh dialog.
    const err = error as { status?: number; actionHint?: string | null };
    if (err?.status === 409 && !err.actionHint) {
      try {
        await ElMessageBox.confirm(
          '此客户数据已被其他用户修改。点击"确定"将刷新列表并放弃当前编辑。',
          '并发编辑冲突',
          { type: 'warning', confirmButtonText: '刷新列表', cancelButtonText: '取消' }
        );
        dialogVisible.value = false;
        loadData();
      } catch {
        // user cancelled — keep dialog open so they can copy their changes elsewhere
      }
    }
    // Interceptor (request.ts) already shows specific sticky toast for ApiError.
    // Firing a fallback here creates double-toast ("操作失败" BEFORE "客户名称已存在").
    console.error('提交失败:', error);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(`确定删除客户「${row.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    });
    const res = await del(`/${factoryId.value}/customers/${row.id}`);
    if (res.success) {
      ElMessage.success('删除成功');
      loadData();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (e !== 'cancel') console.error('Delete customer failed:', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="客户"
      here="向我们采购的下游买家（如「叮咚」「盒马」「永辉」），用于销售订单 / 应收账款"
      other-name="采购管理 → 供应商"
      other="向我们供货的上游卖家（如肉联厂、包材厂），用于采购订单 / 应付账款"
      other-path="/procurement/suppliers"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">客户管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">新增客户</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索客户名称/编号"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="filterCustomerType"
          placeholder="全部客户类型"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option v-for="opt in CUSTOMER_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button plain @click="advancedFiltersVisible = !advancedFiltersVisible">
          {{ advancedFiltersVisible ? '收起高级筛选' : '高级筛选' }}
        </el-button>
        <!-- CRM 字段降级为高级筛选，避免列表默认界面混淆客户类型与生命周期。 -->
        <el-select
          v-if="advancedFiltersVisible"
          v-model="filterCustomerStatus"
          placeholder="全部生命周期"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in CUSTOMER_STATUS_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-if="advancedFiltersVisible"
          v-model="filterImportance"
          placeholder="全部重要度"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in CUSTOMER_IMPORTANCE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-if="advancedFiltersVisible"
          v-model="filterSource"
          placeholder="全部来源"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in CUSTOMER_SOURCE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="customerCode" label="客户编号" width="140" />
        <el-table-column prop="name" label="客户名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="120" />
        <el-table-column prop="phone" label="联系电话" width="140" />
        <el-table-column label="收货地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.shippingAddress || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="(row.status === 'ACTIVE' || row.isActive === true) ? 'success' : 'info'" size="small">
              {{ (row.status === 'ACTIVE' || row.isActive === true) ? '合作中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客户类型" width="110" align="center">
          <template #default="{ row }">{{ customerTypeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="重要度" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getCustomerImportanceOption(row.importance as string)"
              :type="getCustomerImportanceOption(row.importance as string)?.tagType"
              size="small"
            >
              {{ getCustomerImportanceOption(row.importance as string)?.label }}
            </el-tag>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <span>{{ getCustomerSourceOption(row.source as string)?.label || '-' }}</span>
          </template>
        </el-table-column>
        <!-- P1 #23 S-CREDIT-1 — 信用状态列 -->
        <el-table-column label="信用" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getCreditStatusOption(row.creditStatus as string)"
              :type="getCreditStatusOption(row.creditStatus as string)?.tagType"
              size="small"
            >
              {{ getCreditStatusOption(row.creditStatus as string)?.label }}
            </el-tag>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="min(720px, calc(100vw - 24px))" destroy-on-close>
      <!-- P1 #23 S-CREDIT-1: view/edit 模式下 mount 信用面板 (在 context bar 之上) -->
      <CreditPanel
        v-if="dialogMode !== 'add' && formData.id"
        :customer-id="formData.id"
      />

      <!-- S-CRM-FULL-1 防呆 R2: edit/view 模式下 dialog 顶部 context bar (品名 + 编号 + 状态 + 重要度 + 最近接洽) -->
      <div v-if="dialogMode !== 'add' && formData.name" class="dialog-context-bar">
        <div class="ctx-row">
          <span class="ctx-label">客户:</span>
          <strong>{{ formData.name }}</strong>
          <span v-if="formData.customerCode" class="ctx-code">{{ formData.customerCode }}</span>
        </div>
        <div class="ctx-row ctx-tags">
          <el-tag
            v-if="getCustomerStatusOption(formData.customerStatus)"
            :type="getCustomerStatusOption(formData.customerStatus)?.tagType"
            size="small"
          >生命周期: {{ getCustomerStatusOption(formData.customerStatus)?.label }}</el-tag>
          <el-tag
            v-if="getCustomerImportanceOption(formData.importance)"
            :type="getCustomerImportanceOption(formData.importance)?.tagType"
            size="small"
          >重要度: {{ getCustomerImportanceOption(formData.importance)?.label }}</el-tag>
          <el-tag v-if="formData.source" size="small" type="info">来源: {{ getCustomerSourceOption(formData.source)?.label }}</el-tag>
          <span class="ctx-last-contact">
            最近接洽: {{ formData.lastContactedAt ? new Date(formData.lastContactedAt).toLocaleString('zh-CN') : '未记录' }}
          </span>
        </div>
      </div>

      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="90px" :disabled="isViewMode">
        <el-form-item label="客户名称" prop="name" required>
          <el-input v-model="formData.name" placeholder="请输入客户名称" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson" required>
          <el-input v-model="formData.contactPerson" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone" required>
          <el-input v-model="formData.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="收货地址" prop="shippingAddress" required>
          <el-input v-model="formData.shippingAddress" placeholder="请输入收货地址" type="textarea" :rows="2" />
        </el-form-item>
        <!-- T10 P2.2: status field editable in form (was view-only via list column badge) -->
        <el-form-item label="状态" prop="status" required>
          <el-select v-model="formData.status" :disabled="isViewMode" style="width: 100%">
            <el-option label="合作中" value="ACTIVE" />
            <el-option label="已停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户类型" prop="type">
          <el-select v-model="formData.type" placeholder="请选择客户类型" clearable style="width: 100%">
            <el-option v-for="opt in CUSTOMER_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="notes">
          <el-input v-model="formData.notes" placeholder="请输入备注" type="textarea" :rows="2" />
        </el-form-item>

        <el-collapse v-model="advancedSections" class="advanced-collapse">
          <el-collapse-item name="advanced">
            <template #title>
              <span class="advanced-title">高级资料</span>
              <el-tag v-if="advancedFilledCount" size="small" type="info">已填 {{ advancedFilledCount }} 项</el-tag>
              <span v-else class="advanced-empty">选填</span>
            </template>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="formData.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="所属行业" prop="industry">
          <el-input v-model="formData.industry" placeholder="请输入所属行业" />
        </el-form-item>

        <el-divider content-position="left">CRM 跟进</el-divider>
        <el-form-item label="生命周期" prop="customerStatus">
          <el-select v-model="formData.customerStatus" placeholder="请选择客户生命周期阶段" style="width: 100%">
            <el-option
              v-for="opt in CUSTOMER_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="重要程度" prop="importance">
          <el-select v-model="formData.importance" placeholder="请选择客户重要程度" style="width: 100%">
            <el-option
              v-for="opt in CUSTOMER_IMPORTANCE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户来源" prop="source">
          <el-select v-model="formData.source" placeholder="请选择客户来源渠道" clearable style="width: 100%">
            <el-option
              v-for="opt in CUSTOMER_SOURCE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最近接洽" prop="lastContactedAt">
          <el-date-picker
            v-model="formData.lastContactedAt"
            type="datetime"
            placeholder="选择最近接洽时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <!-- Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认 (Option 3 三层链第 1 层, R3 dropdown) -->
        <el-divider content-position="left">开票默认 (新建销售单自动 prefill)</el-divider>
        <el-form-item label="默认税率" prop="defaultTaxRate">
          <el-input-number
            v-model="formData.defaultTaxRate"
            :precision="2"
            :step="1"
            :min="0"
            :max="100"
            placeholder="未设 (开票时再选)"
            style="width: 100%"
          >
            <template #suffix>%</template>
          </el-input-number>
          <div class="form-hint">销售单/开票申请创建时, 此税率自动 prefill 到行项目 (用户仍可逐行调整)</div>
        </el-form-item>
        <el-form-item label="默认开票类型" prop="defaultInvoiceType">
          <el-select v-model="formData.defaultInvoiceType" placeholder="请选择默认开票类型" clearable style="width: 100%">
            <el-option
              v-for="opt in INVOICE_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            >
              <span style="float: left">{{ opt.label }}</span>
              <span style="float: right; color: var(--el-text-color-secondary); font-size: 12px">
                {{ opt.description }}
              </span>
            </el-option>
          </el-select>
        </el-form-item>

        <!-- P1 #23 S-CREDIT-1: 信用状态 dropdown (creditLimit / creditPeriodDays 走扩展字段) -->
        <el-divider content-position="left">信用控制 (创建销售单时按此状态预检)</el-divider>
        <el-form-item label="信用状态" prop="creditStatus">
          <el-select v-model="formData.creditStatus" placeholder="请选择信用状态" style="width: 100%">
            <el-option
              v-for="opt in CREDIT_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="form-hint">
            NORMAL=正常下单, WARNING=已用 ≥ 80% 自动预警, SUSPENDED=管理员手工冻结 (创建销售单将被阻塞)
          </div>
        </el-form-item>

        <!-- 扩展字段 (动态渲染) -->
        <el-divider content-position="left">扩展信息</el-divider>
        <DynamicEntityForm
          :fields="customerExtendedFields"
          :model-value="formData as TableRow"
          @update:model-value="Object.assign(formData, $event)"
          :readonly="isViewMode"
          :columns="2"
          label-width="110px"
        />
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ isViewMode ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!isViewMode" type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}

/* Sprint 4 W2 S-CRM-FULL-1 — 防呆 R2 dialog context bar */
.dialog-context-bar {
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 16px;
  font-size: 13px;
}

.ctx-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ctx-row + .ctx-row {
  margin-top: 8px;
}

.ctx-label {
  color: var(--text-color-secondary, #909399);
}

.ctx-code {
  color: var(--text-color-secondary, #909399);
  font-family: monospace;
}

.ctx-last-contact {
  color: var(--text-color-secondary, #909399);
  margin-left: auto;
}

.ctx-tags {
  gap: 6px;
}

.cell-empty {
  color: var(--text-color-disabled, #c0c4cc);
  font-size: 12px;
}

.advanced-collapse { margin-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.advanced-title { margin-right: 8px; font-weight: 600; color: var(--el-text-color-primary); }
.advanced-empty { color: var(--el-text-color-secondary); font-size: 12px; }
.advanced-collapse :deep(.el-collapse-item__content) { padding: 8px 4px 2px; }

/* Sprint 4 W2 S-INVOICE-CLIENT-1 — form hint under input */
.form-hint {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
  line-height: 1.4;
  margin-top: 4px;
}
</style>
