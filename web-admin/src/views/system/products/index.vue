<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import { Plus, Search, Refresh, Download, Upload, Picture, ChatDotRound, Setting, Rank, Delete as DeleteIcon } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PRODUCT_CONFIG } from '@/components/ai-entry/types';
import DynamicEntityForm from '@/components/DynamicEntityForm.vue';
import type { FieldConfig } from '@/config/entityFieldConfigs';

// T137: 计量单位字典 — 从 /system-config/units 加载, 供一级单位/二级单位下拉用
const unitDictOptions = ref<{ label: string; value: string }[]>([]);
// 静态兜底列表 (API 为空或加载失败时使用)
const UNIT_FALLBACK: { label: string; value: string }[] = [
  { label: '筐', value: '筐' },
  { label: '箱', value: '箱' },
  { label: '件', value: '件' },
  { label: '袋', value: '袋' },
  { label: '桶', value: '桶' },
  { label: '盒', value: '盒' },
  { label: '份', value: '份' },
  { label: '瓶', value: '瓶' },
  { label: 'kg', value: 'kg' },
  { label: '克', value: '克' },
];
// 合并字典 + 兜底 (去重)
const unitSelectOptions = computed<{ label: string; value: string }[]>(() => {
  const dict = unitDictOptions.value.length > 0 ? unitDictOptions.value : UNIT_FALLBACK;
  const seen = new Set(dict.map(o => o.value));
  // ensure fallback entries that aren't in dict are also available (兜底补充)
  const extra = UNIT_FALLBACK.filter(o => !seen.has(o.value));
  return [...dict, ...extra];
});

async function loadUnitDict() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ unitName: string; unitSymbol?: string; isActive?: boolean }[]>(
      `/${factoryId.value}/system-config/units`
    );
    if (res.success && Array.isArray(res.data) && res.data.length > 0) {
      unitDictOptions.value = res.data
        .filter(u => u.isActive !== false)
        .map(u => ({ label: u.unitName, value: u.unitName }));
    }
  } catch {
    // 加载失败 → 静默, 使用兜底列表
  }
}

// T137: 转换数 placeholder 动态显示实际单位名 ("1 筐 = ? 盒 填 10")
const conversionPlaceholder = computed(() => {
  const l1 = (formData.level1Unit as string | undefined)?.trim();
  const l2 = formData.unit?.trim();
  if (l1 && l2) return `1 ${l1} = ? ${l2}（如 1筐=10盒 填 10）`;
  if (l1) return `1 ${l1} = ? 二级单位（如 1筐=10盒 填 10）`;
  return '先填一级单位，再填换算数（如 1筐=10盒 填 10）';
});

// 产品扩展字段 — 添加新字段只需在此数组加一行
// T123 重组: gramsPerUnit + boxConversionCoefficient 移到 '规格信息' 组
//   (成品也需要标准克重设置,不应被'商务信息'隐藏逻辑屏蔽)
// T137: level1Unit 改为 dict-backed el-select (type:'select' + 动态 options)
const productExtendedFields = computed<FieldConfig[]>(() => [
  // ---- 规格信息 (成品/原料均显示) ----
  { key: 'level1Unit', label: '一级单位', type: 'select', group: '规格信息', order: 1,
    placeholder: '选择或输入一级单位（如 筐、箱）',
    allowCreate: true,
    options: unitSelectOptions.value },
  { key: 'boxConversionCoefficient', label: '一级→二级转换数', type: 'decimal', group: '规格信息', precision: 4, order: 2,
    span: 24,
    placeholder: conversionPlaceholder.value },
  { key: 'gramsPerUnit', label: '标准克重', type: 'decimal', group: '规格信息', precision: 2, suffix: '克', order: 3,
    placeholder: '克/份，末道报工折算用（留空=无标准）' },
  { key: 'wipToFgYield', label: '半成品出成率', type: 'decimal', group: '规格信息', precision: 4, order: 4,
    placeholder: '0~1，如 0.55=55%（留空按 1:1）' },
  // ---- 商务信息 (成品隐藏, 原辅料显示) ----
  { key: 'brand', label: '品牌', type: 'text', group: '商务信息', order: 1 },
  { key: 'taxIncludedUnitPrice', label: '含税单价', type: 'decimal', group: '商务信息', precision: 4, suffix: '元', order: 2 },
  { key: 'settlementMethod', label: '结算方式', type: 'select', group: '商务信息', order: 3,
    options: [
      { label: '月结', value: 'MONTHLY' },
      { label: '现结', value: 'CASH' },
      { label: '预付', value: 'PREPAID' },
      { label: '货到付款', value: 'COD' },
    ] },
  // ---- 库存采购 ----
  { key: 'inventoryWarningThreshold', label: '库存预警值', type: 'decimal', group: '库存采购', precision: 2, order: 6 },
  { key: 'minimumOrderQuantity', label: '起订量(MOQ)', type: 'decimal', group: '库存采购', precision: 2, order: 7 },
]);
import {
  getActiveWorkProcesses,
  getProductWorkProcesses,
  createProductWorkProcess,
  deleteProductWorkProcess,
  batchSortProductWorkProcesses,
  getProductWorkProcessRecommendation,
  type WorkProcessItem,
  type ProductWorkProcessItem,
} from '@/api/processProduction';
import type { TableRow } from '@/types/api';

// 产品分类定义
const PRODUCT_CATEGORIES = [
  { value: 'FINISHED_PRODUCT', label: '成品' },
  { value: 'RAW_MATERIAL', label: '原料' },
  { value: 'PACKAGING', label: '包辅材' },
  { value: 'SEASONING', label: '调味品' },
  { value: 'CUSTOMER_MATERIAL', label: '客户自带原料加工' }
] as const;

type ProductCategory = typeof PRODUCT_CATEGORIES[number]['value'];

// 产品类型接口
interface ProductType {
  id: string;
  code: string;
  name: string;
  category?: string;
  productCategory?: ProductCategory;
  unit: string;
  specification?: string;
  relatedCustomer?: string;
  customerId?: string;       // T123: 关联客户 ID (entity link)
  temperatureZone?: string;
  imageUrl?: string;
  notes?: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
  // 六扇门扩展字段
  boxConversionCoefficient?: number;
  gramsPerUnit?: number;   // P0-2: 标准克重(克/份), 报工末道份→kg折算用
  wipToFgYield?: number;   // T133: 半成品→成品出成率 (0~1), 备货看板 WIP 估算; null=按 1.0
  level1Unit?: string;     // T123: 一级单位 (如 筐, 箱) 与 boxConversionCoefficient 联用
  baseProductName?: string; // T123: 产品基础名 (名称分离), RN 展示优先使用, 无则 fallback 到 name
  inventoryWarningThreshold?: number;
  minimumOrderQuantity?: number;
  brand?: string;
  settlementMethod?: string;
  taxIncludedUnitPrice?: number;
  [key: string]: unknown;
}

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// 状态
const loading = ref(false);
const tableData = ref<ProductType[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');
const activeTab = ref<ProductCategory>('FINISHED_PRODUCT');

// SKU组装
const skuDialogVisible = ref(false);
const skuLoading = ref(false);
const skuForm = ref({
  templateId: '',
  customerId: '',
  recipeVersion: 'default',
});
const templateOptions = ref<TableRow[]>([]);
const customerOptions = ref<TableRow[]>([]);
const templateRecipes = ref<TableRow[]>([]);

async function loadSkuOptions() {
  try {
    const [templatesRes, customersRes] = await Promise.all([
      get(`/${factoryId.value}/product-types/templates`),
      get(`/${factoryId.value}/customers?size=200`),
    ]);
    templateOptions.value = templatesRes.success ? (templatesRes.data || []) : [];
    customerOptions.value = customersRes.success ? (customersRes.data?.content || customersRes.data || []) : [];
  } catch { /* silent */ }
}

async function loadTemplateRecipe(templateId: string) {
  try {
    const res = await get(`/${factoryId.value}/conversions?productTypeId=${templateId}`);
    templateRecipes.value = res.success ? (res.data?.content || res.data || []) : [];
  } catch { templateRecipes.value = []; }
}

async function handleAssembleSku() {
  if (!skuForm.value.templateId) { ElMessage.warning('请选择产品模板'); return; }
  skuLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/product-types/assemble-sku`, skuForm.value);
    if (res.success) {
      ElMessage.success(`SKU创建成功: ${res.data?.code || ''}`);
      skuDialogVisible.value = false;
      skuForm.value = { templateId: '', customerId: '', recipeVersion: 'default' };
      templateRecipes.value = [];
      loadData();
    } else {
      ElMessage.error(res.message || 'SKU创建失败');
    }
  } catch (e: unknown) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '操作失败');
  } finally {
    skuLoading.value = false;
  }
}

// 弹窗状态
const dialogVisible = ref(false);
const dialogTitle = ref('新增产品');
const isEditing = ref(false);
const formRef = ref();
const submitting = ref(false);

// 表单数据
const formData = reactive<Partial<ProductType>>({
  id: '',
  code: '',
  name: '',
  productCategory: 'FINISHED_PRODUCT',
  unit: '',
  specification: '',
  relatedCustomer: '',
  customerId: '',      // T123: 关联客户 ID (entity link)
  baseProductName: '', // T123: 产品基础名 (名称分离)
  temperatureZone: '',
  imageUrl: '',
  notes: ''
});

// T123: 两级单位实时转换提示 ("1筐=10盒" 等)
const unitConversionHint = computed(() => {
  const l1 = (formData.level1Unit as string | undefined)?.trim();
  const coef = formData.boxConversionCoefficient;
  const l2 = formData.unit?.trim();
  if (l1 && coef && l2) {
    const n = typeof coef === 'number' ? coef : parseFloat(String(coef));
    if (!isNaN(n) && n > 0) return `当前: 1 ${l1} = ${n} ${l2}`;
  }
  return '';
});

// T123: baseProductName autocomplete 建议 (从已加载产品名/baseProductName 去重)
const baseProductNameSuggestions = ref<{ value: string }[]>([]);
function queryBaseProductName(query: string, cb: (suggestions: { value: string }[]) => void) {
  const existing = new Set<string>();
  for (const p of tableData.value) {
    const bpn = (p as ProductType).baseProductName;
    if (bpn) existing.add(bpn);
    if (p.name) existing.add(p.name);
  }
  const arr = Array.from(existing)
    .filter(s => !query || s.toLowerCase().includes(query.toLowerCase()))
    .map(v => ({ value: v }));
  cb(arr);
}

// T123: 关联客户 autocomplete — 选中后同步 customerId + relatedCustomer
function handleCustomerSelect(item: { id: string; name: string } | string) {
  if (typeof item === 'string') {
    // allow-create: 用户手输客户名, 不清除已有 customerId (兼容旧数据)
    formData.relatedCustomer = item;
  } else {
    formData.relatedCustomer = item.name;
    formData.customerId = item.id;
  }
}

function customerSearchFn(query: string, cb: (items: { id: string; name: string; value: string }[]) => void) {
  const q = query.toLowerCase();
  const matched = customers.value
    .filter(c => !q || c.name.toLowerCase().includes(q))
    .map(c => ({ ...c, value: c.name }));
  cb(matched);
}

// P1-NEW-2: 产品大类=成品时隐藏"商务信息"组 (客户需求 1567-1572s: 成品不展示, 原辅料才展示)
const visibleExtendedFields = computed<FieldConfig[]>(() => {
  const all = productExtendedFields.value;
  const base = formData.productCategory === 'FINISHED_PRODUCT'
    ? all.filter(f => f.group !== '商务信息')
    : all;
  return canViewPrice.value ? base : base.filter(f => f.key !== 'taxIncludedUnitPrice');
});

// 客户下拉列表
const customers = ref<{ id: string; name: string }[]>([]);

async function loadCustomers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { size: 200 } });
    if (res.success) {
      customers.value = res.data?.content || res.data || [];
    }
  } catch { /* silent */ }
}

// 表单验证规则
const formRules = {
  code: [
    { max: 50, message: '产品编号不能超过50个字符', trigger: 'blur' }
  ],
  name: [
    { required: true, message: '请输入产品名称', trigger: 'blur' },
    { max: 100, message: '产品名称不能超过100个字符', trigger: 'blur' }
  ],
  unit: [
    { required: true, message: '请选择或输入单位', trigger: ['blur', 'change'] },
    { max: 20, message: '单位不能超过20个字符', trigger: ['blur', 'change'] }
  ],
  productCategory: [
    { required: true, message: '请选择产品大类', trigger: 'change' }
  ]
};

onMounted(() => {
  loadData();
  loadCustomers();
  loadUnitDict(); // T137: 计量单位字典 (供一级/二级单位下拉)
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get<{ content: ProductType[]; totalElements: number }>(`/${factoryId.value}/product-types`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined,
        productCategory: activeTab.value
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

function handleTabChange(tab: ProductCategory) {
  activeTab.value = tab;
  pagination.value.page = 1;
  loadData();
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function resetForm() {
  formData.id = '';
  formData.code = '';
  formData.name = '';
  formData.productCategory = activeTab.value;
  formData.unit = '';
  formData.specification = '';
  formData.relatedCustomer = '';
  formData.customerId = '';      // T123
  formData.baseProductName = ''; // T123
  formData.level1Unit = undefined; // T123 — part of extendedFields, cleared separately
  formData.temperatureZone = '';
  formData.imageUrl = '';
  formData.notes = '';
}

function handleAdd() {
  resetForm();
  dialogTitle.value = '新增产品';
  isEditing.value = false;
  dialogVisible.value = true;
}

function handleEdit(row: ProductType) {
  dialogTitle.value = '编辑产品';
  isEditing.value = true;
  formData.id = row.id;
  formData.code = row.code;
  formData.name = row.name;
  formData.productCategory = row.productCategory || activeTab.value;
  formData.unit = row.unit;
  formData.specification = row.specification || '';
  formData.relatedCustomer = row.relatedCustomer || '';
  formData.customerId = row.customerId || '';        // T123
  formData.baseProductName = row.baseProductName || ''; // T123
  formData.temperatureZone = row.temperatureZone || '';
  formData.imageUrl = row.imageUrl || '';
  formData.notes = row.notes || '';
  dialogVisible.value = true;
}

async function handleDelete(row: ProductType) {
  try {
    await ElMessageBox.confirm(
      `确定要删除产品 "${row.name}" 吗？`,
      '删除确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    );

    const response = await del(`/${factoryId.value}/product-types/${row.id}`);
    if (response.success) {
      ElMessage.success('删除成功');
      loadData();
    } else {
      ElMessage.error(response.message || '删除失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('删除失败:', error);
  }
}

async function handleSubmit() {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();
    submitting.value = true;

    const payload: TableRow = {
      code: formData.code,
      name: formData.name,
      productCategory: formData.productCategory,
      unit: formData.unit,
      specification: formData.specification,
      relatedCustomer: formData.relatedCustomer,
      customerId: formData.customerId || null,        // T123: 客户 ID entity link
      baseProductName: formData.baseProductName || null, // T123: 产品基础名
      temperatureZone: formData.temperatureZone,
      imageUrl: formData.imageUrl,
      notes: formData.notes,
      // 扩展字段 — 从 productExtendedFields 自动收集
      ...Object.fromEntries(
        productExtendedFields.value.map(f => [f.key, formData[f.key as keyof typeof formData] ?? null])
      ),
    };
    if (!isEditing.value) {
      payload.isActive = true;
    }

    const response = isEditing.value
      ? await put<ProductType>(`/${factoryId.value}/product-types/${formData.id}`, payload)
      : await post<ProductType>(`/${factoryId.value}/product-types`, payload);

    if (response.success) {
      ElMessage.success(isEditing.value ? '编辑成功' : '新增成功');
      dialogVisible.value = false;
      await loadData();
      if (!isEditing.value && response.data?.id) {
        // fire-and-forget：推荐请求不阻塞 UI，LLM 冷启动 2-5s 会在后台进行，弹框异步出现
        offerWorkProcessRecommendation(response.data);
      }
    } else {
      ElMessage.error(response.message || '提交失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('提交失败:', error);
  } finally {
    submitting.value = false;
  }
}

async function offerWorkProcessRecommendation(product: ProductType) {
  if (!factoryId.value) return;
  try {
    const res = await getProductWorkProcessRecommendation(factoryId.value, product.id, 5);
    const count = res.success && res.data ? res.data.recommendations.length : 0;
    if (!res.success || !res.data || count === 0) {
      ElMessage.warning(res.message || res.data?.message || '暂未生成推荐工序，请手动配置');
      return;
    }

    // 非阻塞：用 ElNotification 代替 ElMessageBox.confirm，
    // 用户可继续操作产品列表，通知自动展示在角落，点击"去查看"再跳转。
    ElNotification({
      title: 'AI 工序推荐',
      message: `AI 已推荐 ${count} 道工序，点击"去查看"核对草稿`,
      type: 'success',
      duration: 0,
      showClose: true,
      onClick: () => {
        router.push({
          path: '/system/product-processes',
          query: {
            productTypeId: product.id,
            recommend: '1',
          },
        });
      },
    });
  } catch (error) {
    handleCatchError(error, '推荐工序生成失败');
  }
}

function handleExport() {
  if (tableData.value.length === 0) {
    ElMessage.warning('暂无数据可导出');
    return;
  }
  const headers = ['产品编号', '产品名称', '分类', '单位', '规格', '关联客户', '状态', '备注'];
  const rows = tableData.value.map((row: ProductType) => [
    row.code || '',
    row.name || '',
    getCategoryLabel(row.productCategory || row.category),
    row.unit || '',
    row.specification || '',
    row.relatedCustomer || '',
    row.isActive ? '启用' : '停用',
    row.notes || ''
  ]);
  const csvContent = '\uFEFF' + [headers, ...rows].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `产品列表_${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  ElMessage.success('导出成功');
}

function handleImport() {
  ElMessage.info('请使用 Excel 上传功能批量导入产品');
}

function getCategoryLabel(value?: string) {
  const category = PRODUCT_CATEGORIES.find(c => c.value === value);
  return category?.label || value || '-';
}

// ==================== 工序配置抽屉 ====================
const processDrawerVisible = ref(false);
const processDrawerProduct = ref<ProductType | null>(null);
const availableProcesses = ref<WorkProcessItem[]>([]);
const linkedProcesses = ref<ProductWorkProcessItem[]>([]);
const processLoading = ref(false);
const addingProcessId = ref('');

async function handleConfigProcesses(row: ProductType) {
  processDrawerProduct.value = row;
  processDrawerVisible.value = true;
  processLoading.value = true;
  try {
    const [activeRes, linkedRes] = await Promise.all([
      getActiveWorkProcesses(factoryId.value!),
      getProductWorkProcesses(factoryId.value!, row.id),
    ]);
    availableProcesses.value = (activeRes.success ? (Array.isArray(activeRes.data) ? activeRes.data : []) : []) as WorkProcessItem[];
    linkedProcesses.value = (linkedRes.success ? (Array.isArray(linkedRes.data) ? linkedRes.data : []) : []) as ProductWorkProcessItem[];
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载工序配置失败');
  } finally {
    processLoading.value = false;
  }
}

const unlinkedProcesses = computed(() => {
  const linkedIds = new Set(linkedProcesses.value.map(lp => lp.workProcessId));
  return availableProcesses.value.filter(p => !linkedIds.has(p.id));
});

async function handleAddProcess(processId: string) {
  if (!processDrawerProduct.value) return;
  addingProcessId.value = processId;
  try {
    const nextOrder = linkedProcesses.value.length + 1;
    const res = await createProductWorkProcess(factoryId.value!, {
      productTypeId: processDrawerProduct.value.id,
      workProcessId: processId,
      processOrder: nextOrder,
    });
    if (res.success) {
      await refreshLinkedProcesses();
      ElMessage.success('已关联');
    } else {
      ElMessage.error(res.message || '关联失败');
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '关联失败');
  } finally {
    addingProcessId.value = '';
  }
}

async function handleRemoveProcess(item: ProductWorkProcessItem) {
  try {
    await ElMessageBox.confirm(`确定取消关联工序「${item.processName}」？`, '提示', { type: 'warning' });
    const res = await deleteProductWorkProcess(factoryId.value!, item.id);
    if (res.success) {
      await refreshLinkedProcesses();
      ElMessage.success('已取消关联');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    if (e !== 'cancel') console.error('[操作失败]', e);
  }
}

async function handleMoveProcess(index: number, direction: 'up' | 'down') {
  const list = [...linkedProcesses.value];
  const swapIdx = direction === 'up' ? index - 1 : index + 1;
  if (swapIdx < 0 || swapIdx >= list.length) return;
  [list[index], list[swapIdx]] = [list[swapIdx], list[index]];
  const sortItems = list.map((item, i) => ({ id: item.id, processOrder: i + 1 }));
  try {
    await batchSortProductWorkProcesses(factoryId.value!, sortItems);
    await refreshLinkedProcesses();
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '排序失败');
  }
}

async function refreshLinkedProcesses() {
  if (!processDrawerProduct.value) return;
  const res = await getProductWorkProcesses(factoryId.value!, processDrawerProduct.value.id);
  linkedProcesses.value = (res.success ? (Array.isArray(res.data) ? res.data : []) : []) as ProductWorkProcessItem[];
}

// ==================== AI Entry ====================
const aiEntryVisible = ref(false);

function handleAiFill(params: TableRow) {
  formData.name = String(params.name || '');
  formData.productCategory = (String(params.productCategory || activeTab.value)) as ProductCategory;
  formData.unit = String(params.unit || '');
  formData.specification = String(params.specification || '');
  formData.relatedCustomer = String(params.relatedCustomer || '');
  formData.notes = String(params.notes || '');
  formData.id = '';
  formData.code = '';
  formData.imageUrl = '';
  dialogTitle.value = '新增产品 (AI 填充)';
  isEditing.value = false;
  dialogVisible.value = true;
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="成品 / SKU"
      here="本厂自己生产的成品（如「叮咚好食光卤猪蹄 200g」）"
      other-name="仓储管理 → 原料 / 物料 (采购入库)"
      other="采购入库的原料 / 包材（如「冻猪蹄」「吸塑盒」）"
      other-path="/warehouse/materials"
      consequence="建错位置会导致采购订单的「原料」下拉看不到选项"
    />
    <el-card class="page-card" shadow="never">
      <!-- 页面标题和操作 -->
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">成品 / SKU 管理 (本厂生产)</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button :icon="Download" @click="handleExport">导出</el-button>
            <el-button :icon="Upload" @click="handleImport">导入</el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true">
              AI录入
            </el-button>
            <el-tooltip content="选择产品模板+客户+配方组装为定制SKU" placement="bottom">
              <el-button type="warning" :disabled="!canWrite" @click="skuDialogVisible = true">
                SKU组装
              </el-button>
            </el-tooltip>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">
              新增产品
            </el-button>
          </div>
        </div>
      </template>

      <!-- 分类Tab切换 -->
      <div class="category-tabs">
        <el-tabs v-model="activeTab" @tab-change="handleTabChange">
          <el-tab-pane
            v-for="category in PRODUCT_CATEGORIES"
            :key="category.value"
            :label="category.label"
            :name="category.value"
          />
        </el-tabs>
      </div>

      <!-- 搜索区域 -->
      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索产品名称/编号/客户"
          :prefix-icon="Search"
          clearable
          style="width: 300px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">
          搜索
        </el-button>
        <el-button :icon="Refresh" @click="handleRefresh">
          重置
        </el-button>
      </div>

      <!-- 数据表格 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="relatedCustomer" label="关联客户" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.relatedCustomer || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="code" label="产品编号" width="140" />
        <el-table-column prop="name" label="产品名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="specification" label="规格" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.specification || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column prop="productCategory" label="产品大类" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">
              {{ getCategoryLabel(row.productCategory || row.category) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="temperatureZone" label="温区" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.temperatureZone" size="small" :type="row.temperatureZone === '冷冻' ? 'primary' : row.temperatureZone === '冷藏' ? 'info' : 'warning'">
              {{ row.temperatureZone }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="imageUrl" label="图片" width="100" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.imageUrl"
              :src="row.imageUrl"
              :preview-src-list="[row.imageUrl]"
              fit="cover"
              style="width: 50px; height: 50px; border-radius: 4px;"
              preview-teleported
            >
              <template #error>
                <div class="image-error">
                  <el-icon><Picture /></el-icon>
                </div>
              </template>
            </el-image>
            <span v-else class="no-image">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.notes || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="warning" link size="small" @click="handleConfigProcesses(row)">
              <el-icon><Setting /></el-icon>工序
            </el-button>
            <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="600px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="产品编号" prop="code">
          <el-input v-model="formData.code" placeholder="留空自动生成，如: CPDD0001" :disabled="isEditing" />
          <div class="form-tip">留空则系统自动生成编号（如 CP + 客户首字母 + 序号: CPDD0001）</div>
        </el-form-item>
        <el-form-item label="产品名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入产品名称" />
        </el-form-item>
        <el-form-item label="产品大类" prop="productCategory">
          <el-select v-model="formData.productCategory" placeholder="请选择产品大类" style="width: 100%">
            <el-option
              v-for="category in PRODUCT_CATEGORIES"
              :key="category.value"
              :label="category.label"
              :value="category.value"
            />
          </el-select>
        </el-form-item>
        <!-- T137: 二级单位(unit) 改为字典下拉, filterable + allow-create 兼容手输 -->
        <el-form-item label="单位" prop="unit">
          <el-select
            v-model="formData.unit"
            placeholder="选择或输入二级单位（如 盒、份、kg）"
            filterable
            allow-create
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="opt in unitSelectOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="form-tip">二级单位是最小计量单位（如「盒」）；一级单位是大包装（如「筐=10盒」），在规格信息中配置</div>
        </el-form-item>
        <el-form-item label="规格" prop="specification">
          <el-input v-model="formData.specification" placeholder="请输入规格（如：310g*42袋/箱）" />
        </el-form-item>
        <!-- T123: 客户打通 — el-autocomplete 同时填 relatedCustomer(名称) + customerId(ID) -->
        <el-form-item label="关联客户" prop="relatedCustomer">
          <el-autocomplete
            v-model="formData.relatedCustomer"
            :fetch-suggestions="customerSearchFn"
            placeholder="搜索客户名称（同步设置客户ID）"
            clearable
            style="width: 100%"
            @select="handleCustomerSelect"
            @clear="() => { formData.customerId = ''; }"
          >
            <template #suffix>
              <span v-if="formData.customerId" style="font-size:11px; color:#67c23a; padding-right:4px;">
                已绑定
              </span>
            </template>
          </el-autocomplete>
          <div v-if="formData.customerId" class="form-tip">客户ID: {{ formData.customerId }}</div>
        </el-form-item>
        <!-- T123: 产品基础名 (名称分离) — 如"好食光卤猪蹄"，RN 优先显示此字段 -->
        <el-form-item label="基础名称">
          <el-autocomplete
            v-model="formData.baseProductName"
            :fetch-suggestions="queryBaseProductName"
            placeholder="如: 好食光卤猪蹄（RN优先显示，留空用产品名）"
            clearable
            style="width: 100%"
          />
          <div class="form-tip">仅含产品本身名称，不含客户/规格后缀；RN 展示优先用此字段，为空则回退到产品名称</div>
        </el-form-item>
        <el-form-item label="温区" prop="temperatureZone">
          <el-select v-model="formData.temperatureZone" placeholder="请选择温区" clearable style="width: 100%">
            <el-option label="常温" value="常温" />
            <el-option label="冷藏" value="冷藏" />
            <el-option label="冷冻" value="冷冻" />
          </el-select>
        </el-form-item>
        <el-form-item label="产品图片" prop="imageUrl">
          <el-input v-model="formData.imageUrl" placeholder="请输入图片URL" />
          <div v-if="formData.imageUrl" class="image-preview">
            <el-image
              :src="formData.imageUrl"
              fit="contain"
              style="width: 100px; height: 100px; margin-top: 8px;"
            >
              <template #error>
                <div class="image-error">图片加载失败</div>
              </template>
            </el-image>
          </div>
        </el-form-item>
        <el-form-item label="备注" prop="notes">
          <el-input
            v-model="formData.notes"
            type="textarea"
            :rows="3"
            placeholder="请输入备注信息"
          />
        </el-form-item>

        <!-- 扩展字段 (动态渲染，添加新字段只需修改 productExtendedFields 数组) -->
        <el-divider content-position="left">扩展信息</el-divider>
        <DynamicEntityForm
          :fields="visibleExtendedFields"
          :model-value="formData as TableRow"
          @update:model-value="Object.assign(formData, $event)"
          :columns="2"
          label-width="120px"
        />
        <!-- T123: 两级单位实时换算提示 — 当 level1Unit + boxConversionCoefficient + unit 均填时显示 -->
        <el-form-item v-if="unitConversionHint" label=" ">
          <el-tag type="success" effect="plain">
            {{ unitConversionHint }}
          </el-tag>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- SKU 组装对话框 -->
    <el-dialog
      v-model="skuDialogVisible"
      title="SKU组装 — 产品模板 + 客户 + 配方"
      width="600px"
      @open="loadSkuOptions"
    >
      <el-form :model="skuForm" label-width="100px">
        <el-form-item label="产品模板" required>
          <el-select
            v-model="skuForm.templateId"
            placeholder="选择基础产品"
            filterable
            style="width: 100%"
            @change="(val: string) => { if (val) loadTemplateRecipe(val); }"
          >
            <el-option
              v-for="t in templateOptions"
              :key="t.id"
              :label="`${t.name} (${t.unit})`"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select
            v-model="skuForm.customerId"
            placeholder="选择客户"
            filterable
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配方版本">
          <el-input v-model="skuForm.recipeVersion" placeholder="default" />
        </el-form-item>

        <el-form-item v-if="templateRecipes.length > 0" label="配方预览">
          <el-table :data="templateRecipes" size="small" border stripe style="width: 100%">
            <el-table-column prop="materialType.name" label="原料" width="120">
              <template #default="{ row }">
                {{ row.materialType?.name || row.materialTypeId }}
              </template>
            </el-table-column>
            <el-table-column label="转换率" width="80" align="center">
              <template #default="{ row }">{{ row.conversionRate }}</template>
            </el-table-column>
            <el-table-column label="损耗率" width="80" align="center">
              <template #default="{ row }">{{ row.wastageRate || 0 }}%</template>
            </el-table-column>
            <el-table-column label="标准用量" width="90" align="center">
              <template #default="{ row }">{{ row.standardUsage || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="skuDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="skuLoading" @click="handleAssembleSku">
          创建SKU
        </el-button>
      </template>
    </el-dialog>

    <!-- AI 对话录入 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PRODUCT_CONFIG"
      @fill-form="handleAiFill"
    />

    <!-- 工序配置抽屉 -->
    <el-drawer
      v-model="processDrawerVisible"
      :title="`工序配置 — ${processDrawerProduct?.name || ''}`"
      size="560px"
      direction="rtl"
    >
      <div v-loading="processLoading" class="process-config">
        <!-- 已关联工序（右侧） -->
        <div class="process-section">
          <div class="section-title">
            <span>已关联工序</span>
            <el-tag size="small" type="info">{{ linkedProcesses.length }} 个</el-tag>
          </div>
          <div v-if="linkedProcesses.length === 0" class="process-empty">
            暂无关联工序，请从下方可选列表中添加
          </div>
          <div v-else class="linked-list">
            <div v-for="(item, idx) in linkedProcesses" :key="item.id" class="linked-item">
              <div class="linked-order">{{ idx + 1 }}</div>
              <div class="linked-info">
                <div class="linked-name">{{ item.processName }}</div>
                <div class="linked-meta">
                  <el-tag size="small" type="info">{{ item.processCategory }}</el-tag>
                  <span class="linked-unit">{{ item.unitOverride || item.defaultUnit }}</span>
                  <span v-if="item.estimatedMinutesOverride || item.defaultEstimatedMinutes" class="linked-time">
                    {{ item.estimatedMinutesOverride || item.defaultEstimatedMinutes }}分钟
                  </span>
                </div>
              </div>
              <div class="linked-actions">
                <el-button :icon="Rank" link size="small" :disabled="idx === 0" @click="handleMoveProcess(idx, 'up')" title="上移" />
                <el-button :icon="Rank" link size="small" :disabled="idx === linkedProcesses.length - 1" @click="handleMoveProcess(idx, 'down')" title="下移" />
                <el-button :icon="DeleteIcon" link size="small" type="danger" @click="handleRemoveProcess(item)" />
              </div>
            </div>
          </div>
        </div>

        <el-divider />

        <!-- 可选工序（左侧） -->
        <div class="process-section">
          <div class="section-title">
            <span>可选工序</span>
            <el-tag size="small">{{ unlinkedProcesses.length }} 个</el-tag>
          </div>
          <div v-if="unlinkedProcesses.length === 0" class="process-empty">
            所有工序已关联，或暂无可用工序
          </div>
          <div v-else class="available-list">
            <div v-for="proc in unlinkedProcesses" :key="proc.id" class="available-item">
              <div class="available-info">
                <span class="available-name">{{ proc.processName }}</span>
                <el-tag size="small" type="info">{{ proc.processCategory }}</el-tag>
                <span class="available-unit">{{ proc.unit }}</span>
              </div>
              <el-button
                type="primary"
                size="small"
                :icon="Plus"
                :loading="addingProcessId === proc.id"
                @click="handleAddProcess(proc.id)"
              >
                添加
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
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

  .header-right {
    display: flex;
    gap: 8px;
  }
}

.category-tabs {
  margin-bottom: 16px;

  :deep(.el-tabs__nav-wrap::after) {
    display: none;
  }

  :deep(.el-tabs__item) {
    font-size: 14px;
    padding: 0 20px;
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

.image-error {
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f5f7fa;
  border-radius: 4px;
  color: #909399;
}

.no-image {
  color: #909399;
}

.image-preview {
  width: 100%;
  display: flex;
  justify-content: flex-start;

  .image-error {
    width: 100px;
    height: 100px;
    margin-top: 8px;
    font-size: 12px;
    border: 1px dashed #dcdfe6;
  }
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

.process-config {
  min-height: 200px;
}

.process-section {
  .section-title {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    font-weight: 600;
    font-size: 14px;
    color: #303133;
  }
}

.process-empty {
  text-align: center;
  color: #909399;
  padding: 24px 0;
  font-size: 13px;
}

.linked-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.linked-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #F5F7FA;
  border-radius: 8px;
  border: 1px solid #EBEEF5;

  .linked-order {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: #409EFF;
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    font-weight: 600;
    flex-shrink: 0;
  }

  .linked-info {
    flex: 1;

    .linked-name {
      font-weight: 500;
      font-size: 14px;
      color: #303133;
    }

    .linked-meta {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 4px;
      font-size: 12px;
      color: #909399;
    }
  }

  .linked-actions {
    display: flex;
    gap: 2px;
  }
}

.available-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.available-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border: 1px solid #EBEEF5;
  border-radius: 6px;
  background: #fff;

  .available-info {
    display: flex;
    align-items: center;
    gap: 8px;

    .available-name {
      font-size: 14px;
      color: #303133;
    }

    .available-unit {
      font-size: 12px;
      color: #909399;
    }
  }
}
</style>
