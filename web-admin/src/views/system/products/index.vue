<script setup lang="ts">
import { ref, computed, onMounted, reactive, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import { Plus, Search, Refresh, Download, Upload, Picture, ChatDotRound, Setting, Rank, Delete as DeleteIcon } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PRODUCT_CONFIG } from '@/components/ai-entry/types';
import DynamicEntityForm from '@/components/DynamicEntityForm.vue';
import type { FieldConfig } from '@/config/entityFieldConfigs';
import {
  composeProductSpecificationFromNetContent,
  convertNetContent,
  displayProductSpecification,
  netContentDimension,
  parseNetContent,
  type NetContentUnit,
} from '@/utils/productSpecification';
import { displayUnit } from '@/utils/unitPricing';
import UnitSelect from '@/components/common/UnitSelect.vue';
import { showSingletonNotification } from '@/utils/singletonNotification';
import { isCurrentCategorySuggestion, reconcilePackagingSpecs } from './productDialogState';
import {
  filterProcessCatalog,
  pageProcessCatalog,
  type ProcessRelationFilter,
} from './processCatalogModel';

// T137: 转换数 placeholder 动态显示实际单位名 ("1 筐 = ? 盒 填 20")
const conversionPlaceholder = computed(() => {
  const l1 = (formData.level1Unit as string | undefined)?.trim();
  const l2 = formData.unit?.trim();
  if (l1 && l2) return `1 ${l1} = ? ${l2}（如 1筐=20盒 填 20）`;
  if (l1) return `1 ${l1} = ? 二级单位（如 1筐=20盒 填 20）`;
  return '先填一级单位，再填换算数（如 1筐=20盒 填 20）';
});

// T146: 标准克重 placeholder — 动态替换二级单位名 (如 "每盒多少克")
const gramsPerUnitPlaceholder = computed(() => '每基本单位数量');

// 产品扩展字段 — 添加新字段只需在此数组加一行
// T123 重组: gramsPerUnit + boxConversionCoefficient 移到 '规格信息' 组
//   (成品也需要标准克重设置,不应被'商务信息'隐藏逻辑屏蔽)
// T148: level1Unit + boxConversionCoefficient 移出 productExtendedFields,
//   改为内联行渲染「1 [一级单位▼] ＝ [换算数] [二级单位▼]」,始终显示
const productExtendedFields = computed<FieldConfig[]>(() => [
  // ---- 规格信息 (成品/原料均显示) ----
  // T148: level1Unit + boxConversionCoefficient 已移为内联行 (见模板中「装箱换算」el-form-item)
  // T146 Fix4: gramsPerUnit placeholder 动态替换二级单位名 (如 "每盒多少克")
  // T157 (2026-07-13) 双模式表单瘦身: 组重打 —— 克重=规格; 出成率/单锅产能=产能; 研发人工=成本。
  //   驱动模板里「规格 section (标准克重内联)」+「高级设置」下的 成本/产能/库存 分组渲染。
  { key: 'gramsPerUnit', label: '标准单位换算', type: 'decimal', group: '系统', precision: 3, suffix: '克', order: 3,
    placeholder: gramsPerUnitPlaceholder.value },
  // 六扇门 配料单 (配料员按锅配料): 单锅产能 = 1 锅产出数量 (同计划产量单位); 配料单据此算锅数 = ceil(计划量/单锅产能)
  { key: 'singlePotCapacity', label: '单锅产能', type: 'decimal', group: '产能', precision: 3, order: 6,
    placeholder: '1 锅产出数量(同计划产量单位), 配料单算锅数用; 留空则配料单不计每锅量' },
  // SP9-M1: 研发预估人工成本 (quotedLaborCost); 供人效双口径对比用; 成品才有意义, 原辅料留空即可
  { key: 'quotedLaborCostPerKg', label: '研发人工成本(元/kg)', type: 'decimal', group: '成本', precision: 4, order: 5,
    suffix: '元/kg', placeholder: '研发预估人工, 不填则双口径对比中报价列显示"-"' },
  // ---- 商务信息 (成品隐藏, 原辅料显示) ----
  { key: 'brand', label: '品牌', type: 'text', group: '商务信息', order: 1 },
  { key: 'taxIncludedUnitPrice', label: '含税单价', type: 'decimal', group: '商务信息', precision: 4, suffix: '元', order: 2 },
  // SP4-A8: 税率 select — 选定后服务端自动推导未税 unitPrice
  { key: 'taxRate', label: '出货税率', type: 'select', group: '商务信息', order: 3,
    placeholder: '选择后自动推导未税单价',
    options: [
      { label: '9% (农产品)', value: 'TAX_9' },
      { label: '13% (一般货物)', value: 'TAX_13' },
    ] },
  { key: 'settlementMethod', label: '结算方式', type: 'select', group: '商务信息', order: 4,
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
import { productAiGuard } from '@/utils/aiEntryGuards';

// 产品分类定义 (全量 — 仅用于标签渲染/历史数据兼容; 原料/包辅材/调味品 是遗留物料类, 物料应在"原料类型字典"管理)
const PRODUCT_CATEGORIES = [
  { value: 'FINISHED_PRODUCT', label: '成品' },
  { value: 'RAW_MATERIAL', label: '原料' },
  { value: 'PACKAGING', label: '包辅材' },
  { value: 'SEASONING', label: '调味品' },
  { value: 'CUSTOMER_MATERIAL', label: '客户自带原料加工' },
  { value: 'CONTRACT_MANUFACTURING', label: '纯代工' },
  { value: 'SEMI_FINISHED', label: '半成品' }
] as const;

// 产品管理只展示"产出类"(成品/纯代工/客供料). 原料/包辅材/调味品 属物料(投入), 归"原料类型字典"页管理,
// 不在产品里录 —— 产品是产品, 产品"用"原料(经 BOM 关联), 二者不混。
const PRODUCT_TABS = PRODUCT_CATEGORIES.filter(
  c => c.value === 'FINISHED_PRODUCT' || c.value === 'CONTRACT_MANUFACTURING'
    || c.value === 'CUSTOMER_MATERIAL' || c.value === 'SEMI_FINISHED'
);

type ProductCategory = typeof PRODUCT_CATEGORIES[number]['value'];

interface PackagingSpec {
  id?: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor?: number;
  defaultSpec: boolean;
  active: boolean;
  sortOrder: number;
  version?: number;
}

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
  quotedLaborCostPerKg?: number; // SP9-M1: 研发预估人工成本(元/kg), 人效双口径对比报价侧
  standardCost?: number | null; // SP5: standard unit cost for margin redline
  targetGrossMargin?: number | null; // SP5: 0-1 decimal from backend
  targetGrossMarginPercent?: number | null; // UI-only percent input
  level1Unit?: string;     // T123: 一级单位 (如 筐, 箱) 与 boxConversionCoefficient 联用
  packagingSpecs?: PackagingSpec[];
  baseProductName?: string; // T123: 产品基础名 (名称分离), RN 展示优先使用, 无则 fallback 到 name
  inventoryWarningThreshold?: number;
  minimumOrderQuantity?: number;
  brand?: string;
  settlementMethod?: string;
  taxIncludedUnitPrice?: number;
  /** SP4-A8: 税率 TAX_9 / TAX_13 / null=未配置 */
  taxRate?: 'TAX_9' | 'TAX_13' | null;
  [key: string]: unknown;
}

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canEditMarginRedline = computed(() => permissionStore.canWrite('finance'));

// 状态
const loading = ref(false);
const tableData = ref<ProductType[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');
const activeTab = ref<ProductCategory>('FINISHED_PRODUCT');

// 筛选: 单位 / 温区 (实时按当前大类下实际存在的值生成选项, 非固定枚举)
const filterUnit = ref('');
const filterTemperatureZone = ref('');
// 精简选项端点 (@Cacheable, 全量, 供筛选下拉动态取值用) — 与主列表分页查询独立, 避免重复拉重 DTO
interface ProductOptionSummary {
  id: string;
  name: string;
  unit?: string | null;
  temperatureZone?: string | null;
  productCategory?: string | null;
  isActive?: boolean | null;
}

const filterOptionsSource = ref<ProductOptionSummary[]>([]);

async function loadFilterOptionsSource() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content: ProductOptionSummary[] }>(
      `/${factoryId.value}/product-types/options`
    );
    if (res.success && res.data?.content) {
      filterOptionsSource.value = res.data.content;
    }
  } catch { /* silent — 筛选选项加载失败不影响主列表 */ }
}

const unitFilterOptions = computed(() => {
  const set = new Set(
    filterOptionsSource.value
      .filter(p => (p.productCategory || '') === activeTab.value)
      .map(p => p.unit)
      .filter((v): v is string => !!v)
  );
  return Array.from(set).sort();
});
const temperatureZoneFilterOptions = computed(() => {
  const set = new Set(
    filterOptionsSource.value
      .filter(p => (p.productCategory || '') === activeTab.value)
      .map(p => p.temperatureZone)
      .filter((v): v is string => !!v)
  );
  return Array.from(set).sort();
});

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

const NET_CONTENT_UNIT_OPTIONS: Array<{ value: NetContentUnit; label: string }> = [
  { value: 'g', label: 'g' },
  { value: 'kg', label: 'kg' },
  { value: 'ml', label: 'ml' },
  { value: 'L', label: 'L' },
];
const netContentAmount = ref<number | null>(null);
const netContentUnit = ref<NetContentUnit>('g');
let previousNetContentUnit: NetContentUnit = 'g';

function hydrateNetContent(gramsPerUnit?: number | null, specification?: string | null): void {
  const parsed = parseNetContent(specification, gramsPerUnit);
  netContentAmount.value = parsed.amount > 0 ? parsed.amount : null;
  netContentUnit.value = parsed.unit;
  previousNetContentUnit = parsed.unit;
}

function syncNetContentToCanonical(): void {
  const amount = Number(netContentAmount.value);
  if (!Number.isFinite(amount) || amount <= 0) {
    formData.gramsPerUnit = undefined;
  } else if (netContentDimension(netContentUnit.value) === 'MASS') {
    formData.gramsPerUnit = convertNetContent(amount, netContentUnit.value, 'g') ?? undefined;
  } else {
    // gramsPerUnit is a mass bridge. Volume net content remains in specification and must not masquerade as grams.
    formData.gramsPerUnit = undefined;
  }
  synchronizeSpecificationFields();
}

function handleNetContentAmountChange(): void {
  gramsPerUnitManuallyEdited.value = true;
  syncNetContentToCanonical();
}

function handleNetContentUnitChange(nextUnit: NetContentUnit): void {
  const amount = Number(netContentAmount.value);
  if (Number.isFinite(amount) && amount > 0) {
    const converted = convertNetContent(amount, previousNetContentUnit, nextUnit);
    if (converted == null) {
      netContentAmount.value = null;
      ElMessage.info('重量与容量不能自动互转，请重新填写净含量');
    } else {
      netContentAmount.value = Number(converted.toFixed(6));
    }
  }
  previousNetContentUnit = nextUnit;
  gramsPerUnitManuallyEdited.value = true;
  syncNetContentToCanonical();
}

const packagingSpecs = ref<PackagingSpec[]>([]);

function blankPackagingSpec(index = 0): PackagingSpec {
  return {
    name: index === 0 ? '默认箱规' : `箱规${index + 1}`,
    packageUnit: '箱',
    baseUnit: formData.unit?.trim() || '',
    conversionFactor: undefined,
    defaultSpec: index === 0,
    active: true,
    sortOrder: index,
  };
}

function addPackagingSpec() {
  packagingSpecs.value.push(blankPackagingSpec(packagingSpecs.value.length));
}

function removePackagingSpec(index: number) {
  if (index === 0) return;
  packagingSpecs.value.splice(index, 1);
  packagingSpecs.value.forEach((spec, idx) => {
    spec.defaultSpec = idx === 0;
    spec.sortOrder = idx;
    spec.name = idx === 0 ? '默认箱规' : `箱规${idx + 1}`;
  });
}

function packagingSpecText(spec: PackagingSpec): string {
  const factor = Number(spec.conversionFactor);
  if (!spec.packageUnit || !spec.baseUnit || !Number.isFinite(factor) || factor <= 0) return '';
  return `1 ${displayUnit(spec.packageUnit)} = ${factor} ${displayUnit(spec.baseUnit)}`;
}

// T123: baseProductName autocomplete 建议 (从已加载产品名/baseProductName 去重)
const baseProductNameSuggestions = ref<{ value: string }[]>([]);
function queryBaseProductName(query: string, cb: (suggestions: { value: string }[]) => void) {
  const existing = new Set<string>();
  for (const p of tableData.value.filter((item) => item.productCategory === formData.productCategory)) {
    const bpn = (p as ProductType).baseProductName;
    if (bpn) existing.add(bpn);
    if (p.name) existing.add(p.name);
  }
  const arr = Array.from(existing)
    .filter(s => !query || s.toLowerCase().includes(query.toLowerCase()))
    .map(v => ({ value: v }));
  cb(arr);
}

// T147 Fix1: 关联客户改「下拉」(el-select filterable + allow-create).
// v-model 绑 relatedCustomer(名称); change 时按名称回查 customerId 同步 entity link.
// - 选中已有客户名 → 设置对应 customerId (绑定)
// - allow-create 手输新客户名 → 找不到匹配 → 清空 customerId (新客户, 仅存名称, 兼容 T123 行为)
function handleCustomerChange(name: string | null) {
  if (!name) {
    formData.relatedCustomer = '';
    formData.customerId = '';
    return;
  }
  formData.relatedCustomer = name;
  const matched = customers.value.find(c => c.name === name);
  formData.customerId = matched ? matched.id : '';
  // Fix2: 客户变化触发编号预览刷新
  refreshCodePreview();
}

// T147 Fix2: 产品编号实时预览 (仅新增模式). 调用只读 preview-code 端点, 复用后端生成逻辑.
// codePreview 仅作占位/提示文案, 真正的 code 仍可被用户手输覆盖; 提交时若 code 为空后端会再生成 (与预览同逻辑).
const codePreview = ref('');           // 后端预览出的将生成编号 (如 CPDD0012)
const codePreviewLoading = ref(false);
// 用户是否手动改过编号 — 一旦手动覆盖, 预览不再回填 code 输入框
const codeManuallyEdited = ref(false);
let codePreviewTimer: ReturnType<typeof setTimeout> | null = null;

async function fetchCodePreview() {
  if (isEditing.value || !factoryId.value) return;
  if (!formData.productCategory) { codePreview.value = ''; return; }
  codePreviewLoading.value = true;
  try {
    const res = await get<{ code: string }>(`/${factoryId.value}/product-types/preview-code`, {
      params: {
        productCategory: formData.productCategory,
        customerId: formData.customerId || undefined,
        relatedCustomer: formData.relatedCustomer || undefined,
      },
    });
    if (res.success && res.data?.code) {
      codePreview.value = res.data.code;
      // 仅在用户未手动改过编号时回填 (用户手输优先)
      if (!codeManuallyEdited.value) formData.code = res.data.code;
    } else {
      // 禁止假数据: 预览失败不编造编号, 留空 + 由 placeholder/提示说明
      codePreview.value = '';
    }
  } catch {
    codePreview.value = '';
  } finally {
    codePreviewLoading.value = false;
  }
}

// debounce 包装, 供大类/客户变化时调用
function refreshCodePreview() {
  if (isEditing.value) return;
  if (codePreviewTimer) clearTimeout(codePreviewTimer);
  codePreviewTimer = setTimeout(fetchCodePreview, 300);
}

// 用户手动编辑编号 → 标记, 停止自动回填
function handleCodeInput() {
  codeManuallyEdited.value = true;
}

// 大类变化 (含 tab 默认值与下拉切换) → 刷新预览
function handleCategoryChange() {
  // T149: 用户手动改大类 → 标记, 智能填充不再覆盖大类
  categoryManuallyEdited.value = true;
  applyCategoryUnitContract();
  refreshCodePreview();
  handleNameInput();
}

const isSemiFinishedSku = computed(() => formData.productCategory === 'SEMI_FINISHED');

function applyCategoryUnitContract(): void {
  if (isSemiFinishedSku.value) {
    // 半成品可按实际 SKU 定义基本单位；仅重量型 SKU 在 Workflow/报工层统一折算为 kg。
    if (!formData.unit) formData.unit = 'kg';
    formData.gramsPerUnit = undefined;
    formData.wipToFgYield = undefined;
    formData.level1Unit = undefined;
    formData.boxConversionCoefficient = undefined;
    formData.specification = '';
    packagingSpecs.value = reconcilePackagingSpecs(formData.productCategory, packagingSpecs.value, () => blankPackagingSpec(0));
    return;
  }
  // 从半成品切回需要装箱的产品类型时，稳定恢复一条默认箱规。
  packagingSpecs.value = reconcilePackagingSpecs(formData.productCategory, packagingSpecs.value, () => blankPackagingSpec(0));
}

// ==================== T149: SKU 智能防呆填充 (名称→大类/单位/装箱 历史记忆) ====================
// 输产品名称后, 调用只读 /suggest 端点按历史 SKU 记忆推断默认值.
// T154 reactive cascade: 改/删名称后自动填字段随之更新/清空 (非首次填入才有效的旧行为).
// - 非手动编辑字段: 始终跟随当前匹配结果; 名称清空则清空这些字段.
// - 手动编辑字段: 保留用户输入, 不覆盖.
// - cascadeWriting 守卫: cascade 自己写字段时不触发手动编辑标志 (防循环).

// 手动编辑追踪标志 — 一旦用户手动改过, 智能填充不再覆盖该字段
const categoryManuallyEdited = ref(false);
const unitManuallyEdited = ref(false);
const level1UnitManuallyEdited = ref(false);
const boxCoefManuallyEdited = ref(false);
// T150: 扩展字段手动编辑追踪
const temperatureZoneManuallyEdited = ref(false);
const specificationManuallyEdited = ref(false);
const gramsPerUnitManuallyEdited = ref(false);
const wipToFgYieldManuallyEdited = ref(false);
// T153: 基础名称手动编辑追踪 — 一旦用户手输/选择, 自动推导不再覆盖
const baseProductNameManuallyEdited = ref(false);

// T154: cascade 正在写字段时置 true, 防止 handleExtendedFormUpdate 把 cascade 写入误标为手动编辑
let cascadeWriting = false;

// 智能填充命中提示 (展示匹配来源产品名)
const suggestHint = ref('');
let suggestTimer: ReturnType<typeof setTimeout> | null = null;

function markUnitEdited() { unitManuallyEdited.value = true; }
function markLevel1UnitEdited() { level1UnitManuallyEdited.value = true; }
function markBoxCoefEdited() { boxCoefManuallyEdited.value = true; }
function markTemperatureZoneEdited() { temperatureZoneManuallyEdited.value = true; }
function markSpecificationEdited() { specificationManuallyEdited.value = true; }
// T153: 用户手输/选择基础名称 → 标记, 自动推导不再覆盖
function markBaseProductNameEdited() { baseProductNameManuallyEdited.value = true; }

function resetSuggestFlags() {
  categoryManuallyEdited.value = false;
  unitManuallyEdited.value = false;
  level1UnitManuallyEdited.value = false;
  boxCoefManuallyEdited.value = false;
  temperatureZoneManuallyEdited.value = false;
  specificationManuallyEdited.value = false;
  gramsPerUnitManuallyEdited.value = false;
  wipToFgYieldManuallyEdited.value = false;
  baseProductNameManuallyEdited.value = false; // T153
  suggestHint.value = '';
}

// T154: 清空所有 cascade 管控字段 (名称被清空时调用; 手动编辑字段保留)
function clearCascadeFields() {
  cascadeWriting = true;
  try {
    // 产品大类由用户先选定。名称建议只在当前大类内匹配，绝不反写或清空大类。
    if (!unitManuallyEdited.value) formData.unit = '';
    if (!level1UnitManuallyEdited.value) formData.level1Unit = undefined;
    if (!boxCoefManuallyEdited.value) formData.boxConversionCoefficient = undefined;
    if (!temperatureZoneManuallyEdited.value) formData.temperatureZone = '';
    if (!gramsPerUnitManuallyEdited.value) formData.gramsPerUnit = undefined;
    if (!wipToFgYieldManuallyEdited.value) formData.wipToFgYield = undefined;
    if (!baseProductNameManuallyEdited.value) formData.baseProductName = '';
    // 规格: 若未手动编辑, 清空 (结构化字段都清了, 重新拼会得到空串)
    if (!specificationManuallyEdited.value) formData.specification = '';
    suggestHint.value = '';
  } finally {
    cascadeWriting = false;
  }
}

interface SuggestResult {
  productCategory?: string | null;
  unit?: string | null;
  level1Unit?: string | null;
  boxConversionCoefficient?: number | null;
  // T150: 扩展字段
  temperatureZone?: string | null;
  specification?: string | null;
  gramsPerUnit?: number | null;
  wipToFgYield?: number | null;
  baseProductName?: string | null; // T153
  matchedFrom?: string | null;
}

// T150: DynamicEntityForm 更新处理 — 透传 formData 并追踪 gramsPerUnit/wipToFgYield 手动编辑
// T154: cascadeWriting 守卫 — cascade 写字段时不触发手动编辑标志
function handleExtendedFormUpdate(event: Record<string, unknown>) {
  if (!cascadeWriting) {
    // 若用户改了 gramsPerUnit/wipToFgYield (从非空改 或 从 null/undefined 改为有值), 标记已手动编辑
    if (event.gramsPerUnit !== undefined && event.gramsPerUnit !== formData.gramsPerUnit) {
      gramsPerUnitManuallyEdited.value = true;
    }
    if (event.wipToFgYield !== undefined && event.wipToFgYield !== formData.wipToFgYield) {
      wipToFgYieldManuallyEdited.value = true;
    }
  }
  Object.assign(formData, event);
}

async function fetchSuggest() {
  // 仅新增模式; 编辑模式不自动改用户既有数据
  if (isEditing.value || !factoryId.value) return;
  const name = (formData.name || '').trim();
  // T154: 名称为空 → 清空所有 cascade 管控的非手动编辑字段
  if (!name) { clearCascadeFields(); return; }
  const requestedCategory = formData.productCategory;
  const requestedName = name;
  try {
    const res = await get<SuggestResult>(`/${factoryId.value}/product-types/suggest`, {
      params: { name, productCategory: formData.productCategory || undefined },
    });
    // 丢弃大类或名称变化前发出的旧响应；后端若返回跨类结果也不应用。
    if (!isCurrentCategorySuggestion(
      formData.name || '',
      formData.productCategory,
      requestedName,
      requestedCategory,
      res.data?.productCategory,
    )) {
      suggestHint.value = '';
      return;
    }
    // T154: cascade 开始写字段, 防 handleExtendedFormUpdate 误标手动编辑
    cascadeWriting = true;
    try {
      if (!res.success || !res.data) { suggestHint.value = ''; return; }
      const s = res.data;
      let filledAny = false;

      // T154 reactive: 对每个非手动编辑字段, 始终写入新匹配值 (或清空 null); 不再限制"仅当为空".
      // 大类是建议查询条件，不是建议结果。选定后只能由用户修改。
      // 单位 (二级): 未手动改过 → 写入或清空
      if (!unitManuallyEdited.value) {
        const newUnit = s.unit ?? '';
        if (formData.unit !== newUnit) { formData.unit = newUnit; filledAny = true; }
      }
      // 一级单位: 未手动改过 → 写入或清空
      if (!level1UnitManuallyEdited.value) {
        const newL1 = s.level1Unit ?? undefined;
        if (formData.level1Unit !== newL1) { formData.level1Unit = newL1; filledAny = true; }
      }
      // 装箱换算系数: 未手动改过 → 写入或清空
      if (!boxCoefManuallyEdited.value) {
        const newCoef = s.boxConversionCoefficient ?? undefined;
        if (formData.boxConversionCoefficient !== newCoef) { formData.boxConversionCoefficient = newCoef; filledAny = true; }
      }
      // 温区: 未手动改过 → 写入或清空
      if (!temperatureZoneManuallyEdited.value) {
        const newTz = s.temperatureZone ?? '';
        if (formData.temperatureZone !== newTz) { formData.temperatureZone = newTz; filledAny = true; }
      }
      // 规格 (从 /suggest 直接给的原始规格字符串): 未手动改过 → 写入; 无匹配则让结构化字段重拼
      if (!specificationManuallyEdited.value && s.specification) {
        if (formData.specification !== s.specification) { formData.specification = s.specification; filledAny = true; }
      }
      // 标准克重: 未手动改过 → 写入或清空
      if (!gramsPerUnitManuallyEdited.value) {
        const newGrams = s.gramsPerUnit ?? undefined;
        if (formData.gramsPerUnit !== newGrams) { formData.gramsPerUnit = newGrams; filledAny = true; }
        hydrateNetContent(newGrams, s.specification || formData.specification);
      }
      // 半成品出成率: 未手动改过 → 写入或清空
      if (!wipToFgYieldManuallyEdited.value) {
        const newYield = s.wipToFgYield ?? undefined;
        if (formData.wipToFgYield !== newYield) { formData.wipToFgYield = newYield; filledAny = true; }
      }
      // T153: 基础名称 (匹配产品带出的优先于本地推导): 未手动改过 → 写入或清空
      if (!baseProductNameManuallyEdited.value) {
        const newBpn = s.baseProductName ?? '';
        if (formData.baseProductName !== newBpn) { formData.baseProductName = newBpn; filledAny = true; }
      }

      // 命中提示 (仅名称匹配到历史产品时, matchedFrom 才有值)
      suggestHint.value = (filledAny && s.matchedFrom)
        ? `已按历史产品「${s.matchedFrom}」匹配，可修改`
        : '';
    } finally {
      cascadeWriting = false;
    }
  } catch {
    // 静默: 建议失败不影响录入, 不编造默认值
    cascadeWriting = false;
    suggestHint.value = '';
  }
}

// 名称输入 debounce 触发智能填充 (仅新增)
function handleNameInput() {
  if (isEditing.value) return;
  if (suggestTimer) clearTimeout(suggestTimer);
  suggestTimer = setTimeout(fetchSuggest, 400);
}

// 名称变化也用 watch 兜底 (AI 填充 / 程序化赋值场景)
watch(() => formData.name, () => {
  if (!dialogVisible.value || isEditing.value) return;
  handleNameInput();
});

// ==================== T153 Fix A: 规格 (specification) 由结构化字段自动拼 ====================
// 结构化字段是单一事实源, 规格只是展示文字, 从「标准克重 + 二级单位 + 装箱系数 + 一级单位」拼出.
// 格式: {克重}克/{基本单位} {装箱系数}{基本单位}/{包装单位} → 如 "80克/盒 20盒/框".
// 产品基本单位是唯一权威；包装行里残留的旧 baseUnit 不得污染展示规格。
function composeSpecification(): string {
  return composeProductSpecificationFromNetContent(
    netContentAmount.value,
    netContentUnit.value,
    formData.unit,
    packagingSpecs.value,
  );
}

function synchronizeSpecificationFields(): void {
  packagingSpecs.value.forEach((spec, index) => {
    spec.baseUnit = formData.unit?.trim() || '';
    spec.defaultSpec = index === 0;
    spec.sortOrder = index;
  });
  const first = packagingSpecs.value[0];
  formData.level1Unit = first?.packageUnit || undefined;
  formData.boxConversionCoefficient = first?.conversionFactor;
  const composed = composeSpecification();
  if (formData.specification !== composed) formData.specification = composed;
}

// 监听结构化字段 → 自动重拼规格；基本单位变化会同步所有装箱换算右侧单位。
watch(
  [() => formData.gramsPerUnit, () => formData.unit, packagingSpecs],
  () => {
    if (dialogVisible.value) synchronizeSpecificationFields();
  },
  { deep: true },
);

// ==================== T153 Fix B1: 基础名称从「产品名称 - 客户前缀 - 规格后缀」自动推导 ====================
// 去客户前缀 (名称以关联客户名开头时剥离) + 去尾部规格 (如 "120g"/"(...)" 括号规格).
// best-effort 非覆盖: 用户手动设置过 (baseProductNameManuallyEdited) 则不覆盖; 推导为空则留空 (不过度剥离).
function deriveBaseProductName(): string {
  let s = (formData.name || '').trim();
  if (!s) return '';
  // 1) 剥客户前缀 (名称以客户名开头)
  const cust = (formData.relatedCustomer || '').trim();
  if (cust && s.startsWith(cust)) {
    s = s.slice(cust.length).trim();
  }
  // 2) 剥尾部规格: 末尾「数字+单位」(如 "120g" "1.5kg" "200份")
  s = s.replace(/\s*\d+(\.\d+)?\s*(g|kg|克|ml|l|份|盒|袋|箱)\b\.?$/i, '').trim();
  // 3) 剥尾部括号规格 (如 "(去大骨)" "（200g）")
  s = s.replace(/[（(][^（()）]*[)）]\s*$/, '').trim();
  return s;
}

// 监听 产品名称 + 关联客户 → 自动推导基础名称 (仅新增, 非覆盖, 不过度剥离到空)
watch(
  () => [formData.name, formData.relatedCustomer],
  () => {
    if (!dialogVisible.value || isEditing.value) return;
    if (baseProductNameManuallyEdited.value) return;
    if (!formData.name || !formData.name.trim()) {
      // T154: 名称清空 → 基础名称也清空 (cascade 已设置过的)
      if (formData.baseProductName) formData.baseProductName = '';
      return;
    }
    const derived = deriveBaseProductName();
    // 不过度剥离: 推导为空 → 留空 (不写空串去覆盖已有值, 也不臆造)
    if (derived && formData.baseProductName !== derived) {
      formData.baseProductName = derived;
    }
  },
);

// P1-NEW-2: 产品大类=成品时隐藏"商务信息"组 (客户需求 1567-1572s: 成品不展示, 原辅料才展示)
const visibleExtendedFields = computed<FieldConfig[]>(() => {
  const all = productExtendedFields.value;
  const base = formData.productCategory === 'FINISHED_PRODUCT'
    ? all.filter(f => f.group !== '商务信息')
    : all;
  // SP4-A8: taxRate 与 taxIncludedUnitPrice 均属价格敏感信息, 无 canViewPrice 时隐藏
  return canViewPrice.value ? base : base.filter(f => f.key !== 'taxIncludedUnitPrice' && f.key !== 'taxRate');
});

// T157 (2026-07-13) 双模式表单瘦身: 按组分渲染 —— 规格 section(标准克重) / 高级设置内 成本·产能·库存。
// 多个 DynamicEntityForm 共享 :model-value=formData + @update=handleExtendedFormUpdate(Object.assign 合并) 安全。
const costExtendedFields = computed(() => visibleExtendedFields.value.filter(f => f.group === '成本' || f.group === '商务信息'));
const capacityExtendedFields = computed(() => visibleExtendedFields.value.filter(f => f.group === '产能'));
const inventoryExtendedFields = computed(() => visibleExtendedFields.value.filter(f => f.group === '库存采购'));

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

const exactNameDuplicate = computed(() => {
  const normalizedName = formData.name?.trim().toLocaleLowerCase();
  if (!normalizedName) return null;
  return filterOptionsSource.value.find((product) =>
    product.id !== formData.id
      && product.name.trim().toLocaleLowerCase() === normalizedName,
  ) ?? null;
});

onMounted(() => {
  loadData();
  loadCustomers();
  loadFilterOptionsSource(); // 筛选下拉动态取值源
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
        productCategory: activeTab.value,
        unit: filterUnit.value || undefined,
        temperatureZone: filterTemperatureZone.value || undefined
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
  // 大类切换后单位/温区候选集不同 (unitFilterOptions/temperatureZoneFilterOptions 按 activeTab 过滤),
  // 旧选中值可能不在新大类里 → 清空, 避免筛选出"看似选中但其实不存在于当前 tab"的死值。
  filterUnit.value = '';
  filterTemperatureZone.value = '';
  pagination.value.page = 1;
  loadData();
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  filterUnit.value = '';
  filterTemperatureZone.value = '';
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
  formData.level1Unit = undefined; // T148: 装箱换算内联行字段
  formData.boxConversionCoefficient = undefined; // T148: 装箱换算内联行字段
  formData.temperatureZone = '';
  formData.imageUrl = '';
  formData.notes = '';
  formData.standardCost = null;
  formData.targetGrossMargin = null;
  formData.targetGrossMarginPercent = null;
  netContentAmount.value = null;
  netContentUnit.value = 'g';
  previousNetContentUnit = 'g';
  packagingSpecs.value = [blankPackagingSpec(0)];
  applyCategoryUnitContract();
}

function handleAdd() {
  resetForm();
  dialogTitle.value = '新增 SKU';
  isEditing.value = false;
  // T147 Fix2: 重置编号预览状态, 大类已由 resetForm 默认为当前 tab (Fix3) → 触发预览
  codePreview.value = '';
  codeManuallyEdited.value = false;
  resetSuggestFlags(); // T149: 重置智能填充手动编辑标志
  dialogVisible.value = true;
  refreshCodePreview();
}

async function handleEdit(row: ProductType) {
  dialogTitle.value = '编辑产品';
  try {
    const [detailResponse, packagingResponse] = await Promise.all([
      get<ProductType>(`/${factoryId.value}/product-types/${row.id}`),
      get<PackagingSpec[]>(`/${factoryId.value}/product-types/${row.id}/packaging-specs`),
    ]);
    if (!detailResponse.success || !detailResponse.data
      || !packagingResponse.success || !Array.isArray(packagingResponse.data)) {
      throw new Error('incomplete SKU edit payload');
    }

    const product = detailResponse.data;
    resetForm();
    isEditing.value = true;
    Object.assign(formData, product);
    formData.productCategory = product.productCategory || activeTab.value;
    formData.gramsPerUnit = product.gramsPerUnit ?? undefined;
    hydrateNetContent(product.gramsPerUnit, product.specification);
    formData.standardCost = product.standardCost ?? null;
    formData.targetGrossMargin = product.targetGrossMargin ?? null;
    formData.targetGrossMarginPercent = product.targetGrossMargin != null
      ? Number((Number(product.targetGrossMargin) * 100).toFixed(2))
      : null;
    formData.level1Unit = product.level1Unit ?? undefined;
    formData.boxConversionCoefficient = product.boxConversionCoefficient ?? undefined;

    packagingSpecs.value = packagingResponse.data.length > 0
      ? packagingResponse.data.map((spec, index) => ({
          ...spec,
          name: spec.name || (index === 0 ? '默认箱规' : `箱规${index + 1}`),
          baseUnit: spec.baseUnit || product.unit,
          defaultSpec: index === 0,
          active: spec.active !== false,
          sortOrder: index,
        }))
      : [{
          ...blankPackagingSpec(0),
          packageUnit: product.level1Unit || '箱',
          baseUnit: product.unit,
          conversionFactor: product.boxConversionCoefficient,
        }];
  } catch {
    // 详情或多箱规加载失败时不能用分页行的残缺字段继续编辑，否则保存会覆盖权威数据。
    ElMessage.error('产品详情或包装规格加载失败，请重试后再编辑产品');
    isEditing.value = false;
    dialogVisible.value = false;
    return;
  }
  // T153: 编辑模式下, 已有规格视为「用户已设置」→ 改结构化字段不覆盖既有规格 (仅用户清空后才自动重拼).
  //       基础名称推导本就跳过编辑模式 (watch isEditing 守卫), 此处显式置标志保持一致.
  resetSuggestFlags();
  specificationManuallyEdited.value = false;
  baseProductNameManuallyEdited.value = true;
  // 打开旧数据时立即按结构化字段纠正规格，不能等用户再次触碰输入框。
  synchronizeSpecificationFields();
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

function nullableNumber(value: unknown): number | null {
  return value === '' || value == null ? null : Number(value);
}

async function handleSubmit() {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();
    applyCategoryUnitContract();
    // 提交前再次收敛，保证 API 永远收到规范文本和与基本单位一致的包装右侧单位。
    synchronizeSpecificationFields();
    const partiallyConfigured = packagingSpecs.value.some((spec) => {
      const hasAny = spec.conversionFactor != null && String(spec.conversionFactor) !== '';
      const complete = !!spec.packageUnit && !!formData.unit && Number(spec.conversionFactor) > 0;
      return hasAny && !complete;
    });
    if (partiallyConfigured) {
      ElMessage.warning('请完整填写每条包装规格的包装单位和换算数量');
      return;
    }
    const fractionalPackage = packagingSpecs.value.some((spec) => {
      const factor = Number(spec.conversionFactor);
      return Number.isFinite(factor) && factor > 0 && !Number.isInteger(factor);
    });
    if (fractionalPackage) {
      ElMessage.warning('装箱换算数量必须是正整数，例如 1箱=10袋');
      return;
    }
    submitting.value = true;

    const submittedPackagingSpecs = packagingSpecs.value
      .filter((spec) => spec.packageUnit && Number(spec.conversionFactor) > 0)
      .map((spec, index) => ({
        id: spec.id,
        name: index === 0 ? '默认箱规' : `箱规${index + 1}`,
        packageUnit: spec.packageUnit,
        baseUnit: formData.unit,
        conversionFactor: Number(spec.conversionFactor),
        defaultSpec: index === 0,
        active: true,
        sortOrder: index,
        version: spec.version,
      }));

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
      // T148: level1Unit + boxConversionCoefficient 已移出 productExtendedFields (改内联行渲染),
      //   但仍需随产品提交 — 显式追加 (后者覆盖前者同名 key 优先级: 上方 Object.fromEntries 无这两key 所以无冲突)
      level1Unit: formData.level1Unit ?? null,
      boxConversionCoefficient: formData.boxConversionCoefficient ?? null,
      packagingSpecs: submittedPackagingSpecs,
    };
    if (canEditMarginRedline.value) {
      const marginPercent = nullableNumber(formData.targetGrossMarginPercent);
      payload.standardCost = nullableNumber(formData.standardCost);
      payload.targetGrossMargin = marginPercent == null ? null : Number((marginPercent / 100).toFixed(4));
    }
    if (!isEditing.value) {
      payload.isActive = true;
    }

    const response = isEditing.value
      ? await put<ProductType>(`/${factoryId.value}/product-types/${formData.id}`, payload)
      : await post<ProductType>(`/${factoryId.value}/product-types`, payload);

    if (response.success) {
      ElMessage.success(isEditing.value ? '产品已保存，毛利红线配置已同步' : '产品已新增，毛利红线配置已同步');
      dialogVisible.value = false;
      await Promise.all([loadData(), loadFilterOptionsSource()]);
      if (!isEditing.value && response.data?.id) {
        // fire-and-forget：推荐请求不阻塞 UI，LLM 冷启动 2-5s 会在后台进行，弹框异步出现
        offerWorkProcessRecommendation(response.data);
        // 🔴 防呆 (production/warehouse walk #1): 新建成品后主动引导去配 BOM ——
        // BOM 页主产品下拉现已改远程搜索 + 支持 ?productTypeId= 直达自动选中
        // (见 production/bom/index.vue selectProductFromRoute)，点击通知直接跳过去，
        // 不用在几百条产品里再搜一次刚建好的这条。
        if (formData.productCategory === 'FINISHED_PRODUCT') {
          offerBomConfiguration(response.data);
        }
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

function offerBomConfiguration(product: ProductType) {
  showSingletonNotification({
    title: '配置 BOM',
    message: `新产品「${product.name}」已创建，点击"去配置"设置原辅料配方`,
    type: 'info',
    duration: 0,
    showClose: true,
    onClick: () => {
      router.push({
        path: '/production/bom',
        query: { productTypeId: product.id },
      });
    },
  });
}

async function offerWorkProcessRecommendation(product: ProductType) {
  if (!factoryId.value) return;
  try {
    const res = await getProductWorkProcessRecommendation(factoryId.value, product.id, 5);
    const recommendation = res.success ? res.data : null;
    const count = recommendation?.recommendations?.length || 0;
    const nestedProvenance = recommendation?.provenance;
    const explicitProvenance = Boolean(
      recommendation?.sourceProductTypeId
      && recommendation?.sourceProductName
      && recommendation?.sourceWorkflowId != null
      && recommendation?.sourceWorkflowVersion != null,
    );
    const complete = recommendation?.reasonCode === 'COMPLETE_PUBLISHED_WORKFLOW'
      || recommendation?.completeWorkflow === true
      || recommendation?.workflowComplete === true
      || (typeof nestedProvenance === 'object' && nestedProvenance !== null
        && (nestedProvenance.completeWorkflow === true || nestedProvenance.workflowComplete === true || nestedProvenance.complete === true));
    const fromHistory = recommendation?.source === 'PUBLISHED_WORKFLOW'
      || recommendation?.source === 'HISTORY'
      || recommendation?.source === 'HISTORY_WORKFLOW';
    if (!recommendation || count === 0 || (!explicitProvenance && !nestedProvenance) || !complete || !fromHistory) return;

    const sourceName = recommendation.sourceProductName
      || (typeof nestedProvenance === 'string'
        ? nestedProvenance
        : nestedProvenance?.sourceProductName || nestedProvenance?.workflowName || nestedProvenance?.sourceProductCode)
      || '历史完整 Workflow';

    // 非阻塞：用全局单例通知代替 ElMessageBox.confirm，
    // 用户可继续操作产品列表，通知自动展示在角落，点击"去查看"再跳转。
    showSingletonNotification({
      title: 'AI 工序推荐',
      message: `已找到来源「${sourceName}」的完整 ${count} 道工序，点击进入核对草稿`,
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
    // 推荐是非阻塞增强能力；失败时不弹持久 warning 干扰继续录入。
    console.error('工序推荐加载失败:', error);
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

interface SkuImportIssue {
  sheetName?: string;
  rowNumber?: number;
  field?: string;
  code?: string;
  message: string;
}
interface SkuImportPreviewRow {
  sheetName: string;
  rowNumber: number;
  skuCategory?: string;
  skuCode?: string;
  name?: string;
  unit?: string;
  specification?: string;
  imageUrl?: string;
  imageFileName?: string;
  matchedImageName?: string;
  status: 'VALID' | 'INVALID' | 'SKIPPED_EXAMPLE';
  errors?: SkuImportIssue[];
}
interface SkuImportPreview {
  previewToken: string;
  fileSha256: string;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  rows: SkuImportPreviewRow[];
  errors: SkuImportIssue[];
}
interface ImageMapping { skuCode: string; fileName?: string; url: string }

const importDialogVisible = ref(false);
const importExcelFile = ref<File | null>(null);
const importImageFiles = ref<File[]>([]);
const importImageUrlText = ref('');
const importPreview = ref<SkuImportPreview | null>(null);
const importPreviewing = ref(false);
const importConfirming = ref(false);
const importDownloading = ref(false);
const importImageUploading = ref(false);
const duplicateImageCodes = computed(() => {
  const counts = new Map<string, number>();
  importImageFiles.value.forEach((file) => {
    const code = file.name.replace(/\.[^.]+$/, '').trim();
    counts.set(code, (counts.get(code) || 0) + 1);
  });
  return new Set([...counts.entries()].filter(([, count]) => count > 1).map(([code]) => code));
});

function handleImport() {
  importExcelFile.value = null;
  importImageFiles.value = [];
  importImageUrlText.value = '';
  importPreview.value = null;
  importDialogVisible.value = true;
}

function handleImportExcelChange(file: { raw?: File }) {
  importExcelFile.value = file.raw || null;
  importPreview.value = null;
}

function handleImportImagesChange(_file: unknown, files: Array<{ raw?: File }>) {
  importImageFiles.value = files.map((item) => item.raw).filter((file): file is File => !!file);
  importPreview.value = null;
}

function validImportImage(file: File): boolean {
  return ['image/jpeg', 'image/png'].includes(file.type) && file.size <= 5 * 1024 * 1024;
}

function parseImageUrlMappings(): ImageMapping[] | null {
  const mappings: ImageMapping[] = [];
  const codes = new Set<string>();
  for (const [index, raw] of importImageUrlText.value.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (!line) continue;
    const comma = line.indexOf(',');
    const skuCode = (comma >= 0 ? line.slice(0, comma) : '').trim();
    const url = (comma >= 0 ? line.slice(comma + 1) : '').trim();
    let parsedUrl: URL;
    try {
      parsedUrl = new URL(url);
    } catch {
      ElMessage.warning(`图片 URL 第 ${index + 1} 行格式错误，请使用“SKU编号,https://...”`);
      return null;
    }
    if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
      ElMessage.warning(`图片 URL 第 ${index + 1} 行必须使用 http 或 https`);
      return null;
    }
    if (codes.has(skuCode)) {
      ElMessage.warning(`图片 URL 中 SKU 编号 ${skuCode} 重复`);
      return null;
    }
    codes.add(skuCode);
    mappings.push({ skuCode, url });
  }
  return mappings;
}

function importRowErrorText(row: SkuImportPreviewRow): string {
  return row.errors?.map((item) => item.message).join('；') || '—';
}

async function uploadImportImages(): Promise<ImageMapping[] | null> {
  if (duplicateImageCodes.value.size > 0) {
    ElMessage.warning(`存在重复图片编号：${[...duplicateImageCodes.value].join('、')}`);
    return null;
  }
  const invalid = importImageFiles.value.find((file) => !validImportImage(file));
  if (invalid) {
    ElMessage.warning(`图片 ${invalid.name} 不是 5MB 以内的 JPG/PNG`);
    return null;
  }
  importImageUploading.value = importImageFiles.value.length > 0;
  try {
    return await Promise.all(importImageFiles.value.map(async (file) => {
      const formData = new FormData();
      formData.append('file', file);
      const response = await post<{ url: string }>(`/${factoryId.value}/upload/product-image`, formData);
      if (!response.success || !response.data?.url) throw new Error(`${file.name} 上传失败`);
      return {
        skuCode: file.name.replace(/\.[^.]+$/, '').trim(),
        fileName: file.name,
        url: response.data.url,
      };
    }));
  } finally {
    importImageUploading.value = false;
  }
}

async function downloadSkuImportTemplate() {
  if (!factoryId.value || importDownloading.value) return;
  importDownloading.value = true;
  try {
    const response = await get<Blob>(`/${factoryId.value}/product-types/import/template`, { responseType: 'blob' });
    const blob = response instanceof Blob ? response : response.data;
    if (!(blob instanceof Blob)) throw new Error('模板文件响应无效');
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'SKU批量导入模板.xlsx';
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    handleCatchError(error, '下载 SKU 导入模板失败');
  } finally {
    importDownloading.value = false;
  }
}

async function previewSkuImport() {
  if (!factoryId.value || !importExcelFile.value) {
    ElMessage.warning('请先选择 Excel 文件');
    return;
  }
  if (!importExcelFile.value.name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('仅支持后端模板生成的 .xlsx 文件');
    return;
  }
  if (importExcelFile.value.size > 10 * 1024 * 1024) {
    ElMessage.warning('Excel 文件不能超过 10MB');
    return;
  }
  const urlMappings = parseImageUrlMappings();
  if (urlMappings == null) return;
  const fileCodes = new Set(importImageFiles.value.map((file) => file.name.replace(/\.[^.]+$/, '').trim()));
  const overlapping = urlMappings.find((mapping) => fileCodes.has(mapping.skuCode));
  if (overlapping) {
    ElMessage.warning(`SKU 编号 ${overlapping.skuCode} 同时配置了图片 URL 和本地图片，请保留一种来源`);
    return;
  }
  importPreviewing.value = true;
  try {
    const uploadedMappings = await uploadImportImages();
    if (uploadedMappings == null) return;
    const allMappings = [...urlMappings, ...uploadedMappings];
    const duplicate = allMappings.find((mapping, index) => (
      allMappings.findIndex((candidate) => candidate.skuCode === mapping.skuCode) !== index
    ));
    if (duplicate) {
      ElMessage.warning(`SKU 编号 ${duplicate.skuCode} 同时配置了多张图片，请保留一种来源`);
      return;
    }
    const formData = new FormData();
    formData.append('file', importExcelFile.value);
    if (allMappings.length) formData.append('imageMappings', JSON.stringify(allMappings));
    const response = await post<SkuImportPreview>(`/${factoryId.value}/product-types/import/preview`, formData);
    if (!response.success || !response.data) throw new Error(response.message || '导入预览失败');
    importPreview.value = response.data;
  } catch (error) {
    handleCatchError(error, 'SKU 导入预览失败');
  } finally {
    importPreviewing.value = false;
  }
}

async function confirmSkuImport() {
  if (!factoryId.value || !importPreview.value || importPreview.value.invalidRows > 0) return;
  importConfirming.value = true;
  try {
    const response = await post<{ totalRows: number; createdCount: number }>(
      `/${factoryId.value}/product-types/import/confirm`,
      { previewToken: importPreview.value.previewToken },
    );
    if (!response.success || !response.data) throw new Error(response.message || '确认导入失败');
    ElMessage.success(`导入完成：成功创建 ${response.data.createdCount} 个 SKU`);
    importDialogVisible.value = false;
    await loadData();
  } catch (error) {
    handleCatchError(error, '确认导入 SKU 失败');
  } finally {
    importConfirming.value = false;
  }
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
const processKeyword = ref('');
const processCategoryFilter = ref('');
const processRelationFilter = ref<ProcessRelationFilter>('ALL');
const processCatalogPage = ref(1);
const processCatalogPageSize = 30;

const linkedProcessIds = computed(() => new Set(linkedProcesses.value.map((item) => item.workProcessId)));
const linkedProcessById = computed(() => new Map(linkedProcesses.value.map((item) => [item.workProcessId, item])));
const processCategoryOptions = computed(() => Array.from(new Set(
  availableProcesses.value.map((item) => item.processCategory).filter(Boolean),
)).sort((a, b) => a.localeCompare(b, 'zh-CN')));
const filteredProcessCatalog = computed(() => filterProcessCatalog(
  availableProcesses.value,
  linkedProcessIds.value,
  {
    keyword: processKeyword.value,
    category: processCategoryFilter.value,
    relation: processRelationFilter.value,
  },
));
const pagedProcessCatalog = computed(() => pageProcessCatalog(
  filteredProcessCatalog.value,
  processCatalogPage.value,
  processCatalogPageSize,
));

function resetProcessCatalogFilters(): void {
  processKeyword.value = '';
  processCategoryFilter.value = '';
  processRelationFilter.value = 'ALL';
  processCatalogPage.value = 1;
}

watch(
  [processKeyword, processCategoryFilter, processRelationFilter],
  () => { processCatalogPage.value = 1; },
);

async function handleConfigProcesses(row: ProductType) {
  processDrawerProduct.value = row;
  resetProcessCatalogFilters();
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
  formData.customerId = '';     // AI 填充仅给客户名, 编号预览按名后备生成
  formData.id = '';
  formData.code = '';
  formData.imageUrl = '';
  formData.level1Unit = undefined;              // T149: AI 填充不带装箱, 留给智能填充
  formData.boxConversionCoefficient = undefined;
  dialogTitle.value = '新增产品 (AI 填充)';
  isEditing.value = false;
  // T147 Fix2/Fix3: 重置预览状态并触发 (productCategory 已由 AI 或 activeTab 默认)
  codePreview.value = '';
  codeManuallyEdited.value = false;
  // T149: AI 已给名称/大类/单位 → 视为已设置, 智能填充只补 AI 没给的空字段 (装箱等)
  resetSuggestFlags();
  if (params.productCategory) categoryManuallyEdited.value = true;
  if (params.unit) unitManuallyEdited.value = true;
  // T153: AI 已给规格 → 视为已设置, 自动拼不覆盖; AI 给的名称用于推导基础名称 (未给则留空)
  if (params.specification) specificationManuallyEdited.value = true;
  dialogVisible.value = true;
  refreshCodePreview();
  fetchSuggest(); // 立即按 AI 给的名称补充装箱等历史记忆
}

function handleProductAiGuardAction() {
  aiEntryVisible.value = false;
  router.push('/warehouse/material-types');
}

// ==================== AI 智能建产品 (飞轮衔接) ====================

interface AiProductPreviewBomRow {
  materialName: string;
  standardQuantity: number;
  yieldRate: number;
  materialCategory: string;
  unit: string;
}

interface AiProductPreviewSeasoningRow {
  name: string;
  section: string;
  dosagePerKgG: number;
}

interface AiProductPreviewData {
  status: string;
  productName: string;
  inheritFromProduct: string | null;
  suggestedProcessChain: string[];
  suggestedBom: AiProductPreviewBomRow[];
  suggestedSeasoning: AiProductPreviewSeasoningRow[];
  message: string;
  confirmationExpiresAt: string;
}

interface AiProductPreviewResponse extends AiProductPreviewData {
  confirmationToken: string;
}

const aiProductDialogVisible = ref(false);
const aiProductPreviewing = ref(false);
const aiProductCreating = ref(false);
const aiProductPreview = ref<AiProductPreviewData | null>(null);
const aiProductConfirmationToken = ref<string | null>(null);

const aiProductForm = reactive({
  productName: '',
  unit: '',
  specification: '',
  customerName: '',
  inheritFrom: '',
});

function resetAiProductDialog() {
  aiProductForm.productName = '';
  aiProductForm.unit = '';
  aiProductForm.specification = '';
  aiProductForm.customerName = '';
  aiProductForm.inheritFrom = '';
  aiProductPreview.value = null;
  aiProductConfirmationToken.value = null;
}

function buildAiProductBody(): Record<string, string> {
  const body: Record<string, string> = { productName: aiProductForm.productName.trim() };
  if (aiProductForm.unit.trim()) body.unit = aiProductForm.unit.trim();
  if (aiProductForm.specification.trim()) body.specification = aiProductForm.specification.trim();
  if (aiProductForm.customerName.trim()) body.customerName = aiProductForm.customerName.trim();
  if (aiProductForm.inheritFrom.trim()) body.inheritFrom = aiProductForm.inheritFrom.trim();
  return body;
}

async function handleAiProductPreview() {
  if (!aiProductForm.productName.trim()) {
    ElMessage.warning('请输入产品名称');
    return;
  }
  if (!factoryId.value) return;
  aiProductPreviewing.value = true;
  aiProductPreview.value = null;
  aiProductConfirmationToken.value = null;
  try {
    const res = await post<AiProductPreviewResponse>(
      `/${factoryId.value}/ai-product-create/preview`,
      buildAiProductBody()
    );
    if (res.success && res.data) {
      const data = res.data;
      if (!data.confirmationToken?.trim()) {
        ElMessage.error('预览未返回有效确认凭证，请重新预览');
        return;
      }
      aiProductConfirmationToken.value = data.confirmationToken;
      aiProductPreview.value = {
        status: data.status,
        productName: data.productName,
        inheritFromProduct: data.inheritFromProduct,
        suggestedProcessChain: data.suggestedProcessChain,
        suggestedBom: data.suggestedBom,
        suggestedSeasoning: data.suggestedSeasoning,
        message: data.message,
        confirmationExpiresAt: data.confirmationExpiresAt,
      };
    } else {
      ElMessage({ message: res.message || '预览失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    handleCatchError(e, '预览失败');
  } finally {
    aiProductPreviewing.value = false;
  }
}

async function handleAiProductCreate() {
  if (!factoryId.value || !aiProductPreview.value || !aiProductConfirmationToken.value) {
    ElMessage.warning('请先完成预览并取得有效确认凭证');
    return;
  }
  aiProductCreating.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/ai-product-create`,
      buildAiProductBody(),
      {
        headers: {
          'X-Cretas-Confirmation-Token': aiProductConfirmationToken.value,
        },
      },
    );
    if (res.success) {
      ElMessage.success((res.data as { message?: string })?.message || '产品已创建');
      aiProductDialogVisible.value = false;
      resetAiProductDialog();
      await loadData();
    } else {
      ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    handleCatchError(e, '创建失败');
  } finally {
    aiProductCreating.value = false;
  }
}

watch(
  [
    () => aiProductForm.productName,
    () => aiProductForm.unit,
    () => aiProductForm.specification,
    () => aiProductForm.customerName,
    () => aiProductForm.inheritFrom,
  ],
  () => {
    aiProductPreview.value = null;
    aiProductConfirmationToken.value = null;
  },
);

watch(aiProductDialogVisible, (visible) => {
  if (!visible) {
    aiProductPreview.value = null;
    aiProductConfirmationToken.value = null;
  }
});
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="SKU 管理"
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
            <span class="page-title">SKU 管理（成品 / 半成品）</span>
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
            <el-button v-if="canWrite" type="warning" :icon="ChatDotRound" @click="aiProductDialogVisible = true; resetAiProductDialog()">
              AI 智能建产品
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">
              新增 SKU
            </el-button>
          </div>
        </div>
      </template>

      <!-- 分类Tab切换 -->
      <div class="category-tabs">
        <el-tabs v-model="activeTab" @tab-change="handleTabChange">
          <el-tab-pane
            v-for="category in PRODUCT_TABS"
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
        <el-select
          v-model="filterUnit"
          placeholder="全部单位"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option v-for="u in unitFilterOptions" :key="u" :label="displayUnit(u)" :value="u" />
        </el-select>
        <el-select
          v-model="filterTemperatureZone"
          placeholder="全部温区"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option v-for="tz in temperatureZoneFilterOptions" :key="tz" :label="tz" :value="tz" />
        </el-select>
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
            {{ displayProductSpecification(row.specification) || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center">
          <template #default="{ row }">{{ displayUnit(row.unit) }}</template>
        </el-table-column>
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
      class="product-edit-dialog"
      :modal-class="'product-edit-modal'"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
        label-position="right"
      >
        <!-- T147 Fix2: 新增模式下实时预览将生成的编号并回填 (用户可手输覆盖); 编辑模式显示既有编号(禁用) -->
        <el-form-item label="产品编号" prop="code">
          <el-input
            v-model="formData.code"
            :placeholder="isEditing ? '' : (codePreviewLoading ? '生成中…' : '将根据大类+客户自动生成')"
            :disabled="isEditing"
            @input="handleCodeInput"
          />
          <div v-if="isEditing" class="form-tip">编辑模式下产品编号不可修改</div>
          <div v-else-if="codePreview" class="form-tip">
            将生成: <strong>{{ codePreview }}</strong>（CP + 客户首字母 + 序号）— 可手动覆盖，留空则保存时自动生成
          </div>
          <div v-else class="form-tip">选定「产品大类」与「关联客户」后将自动生成编号（如 CPDD0001）；也可手动输入</div>
        </el-form-item>
        <!-- 先定产品大类，再录名称；历史建议只在该大类内查询，不会反写大类。 -->
        <el-form-item label="产品大类" prop="productCategory">
          <el-select v-model="formData.productCategory" placeholder="请选择产品大类" style="width: 100%" @change="handleCategoryChange">
            <el-option
              v-for="category in PRODUCT_TABS"
              :key="category.value"
              :label="category.label"
              :value="category.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产品名称" prop="name">
          <el-input
            v-model="formData.name"
            placeholder="请输入产品名称"
            :disabled="!formData.productCategory"
            @input="handleNameInput"
          />
          <div class="form-tip">历史建议仅匹配当前产品大类，不会改变已选大类</div>
          <div v-if="suggestHint && !isEditing" class="form-tip" style="color:#67c23a;">
            ✨ {{ suggestHint }}
          </div>
          <el-alert
            v-if="exactNameDuplicate"
            class="exact-duplicate-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="`同厂已存在同名 SKU「${exactNameDuplicate.name}」（${exactNameDuplicate.productCategory || '未分类'}）`"
          />
        </el-form-item>
        <!-- ========== 规格 section (T157 双模式表单瘦身): 基本单位 + 标准克重 + 装箱换算 + 规格(只读自动) ========== -->
        <el-divider content-position="left">规格</el-divider>
        <el-alert
          class="spec-authority-tip"
          type="info"
          :closable="false"
          show-icon
          title="这里定义库存/销售规格；保存后系统会自动生成明确的单位换算，Workflow 可直接绑定。工序报工单位仍以 Workflow 端口为准。"
        />
        <el-form-item label="销售单位 / 净含量" prop="unit" class="sku-measure-form-item">
          <div class="sku-measure-row">
            <UnitSelect
              v-model="formData.unit"
              :factory-id="factoryId"
              placeholder="选择销售单位"
              class="sales-unit-select"
              @change="markUnitEdited"
            />
            <template v-if="!isSemiFinishedSku">
              <span class="measure-separator">每 {{ displayUnit(formData.unit) || '销售单位' }}</span>
              <el-input-number
                v-model="netContentAmount"
                :min="0.001"
                :precision="3"
                :controls="false"
                placeholder="净含量"
                class="net-content-input"
                @change="handleNetContentAmountChange"
              />
              <el-select
                v-model="netContentUnit"
                aria-label="净含量单位"
                class="net-content-unit-select"
                @change="handleNetContentUnitChange"
              >
                <el-option v-for="option in NET_CONTENT_UNIT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </template>
          </div>
          <div class="form-tip" v-if="isSemiFinishedSku">半成品只维护销售单位；重量型报工单位在 Workflow 中统一约束</div>
          <div class="form-tip" v-else>销售单位用于库存和销售；净含量仅支持 g/kg/ml/L，同维度自动等值换算，重量与容量不互转</div>
        </el-form-item>
        <!-- 同一 SKU 允许多条装箱换算；标准克重仍保持唯一。第一条是默认箱规。 -->
        <el-form-item v-if="!isSemiFinishedSku" label="装箱换算" label-width="120px">
          <div class="packaging-spec-list">
            <div class="packaging-rule-note">
              一个 SKU 只有一个基本单位和一份标准克重；下面每一条都只是不同外箱对基本单位的换算规则。
            </div>
            <div v-for="(spec, index) in packagingSpecs" :key="spec.id || index" class="packaging-spec-item">
              <div class="spec-conversion-row">
                <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
                <span v-else class="packaging-spec-index">规格 {{ index + 1 }}</span>
                <span class="spec-conversion-one">1</span>
                <UnitSelect v-model="spec.packageUnit" :factory-id="factoryId" placeholder="大包装单位"
                  class="spec-unit-select" @change="markLevel1UnitEdited" />
                <span class="spec-conversion-eq">＝</span>
                <el-input-number v-model="spec.conversionFactor" :min="1" :precision="0" :controls="false"
                  placeholder="换算数" class="spec-coef-input" @change="markBoxCoefEdited" />
                <el-input :model-value="displayUnit(formData.unit)" disabled placeholder="销售单位" class="spec-unit-select" />
                <el-button v-if="index > 0" link type="danger" @click="removePackagingSpec(index)">删除</el-button>
              </div>
              <div v-if="spec.packageUnit && formData.unit && spec.packageUnit === formData.unit" class="spec-same-warn">
                ⚠️ 大包装单位应与基本单位不同（大包装如「箱」，基本如「盒」）
              </div>
              <div v-else-if="packagingSpecText(spec)" class="spec-echo">= {{ packagingSpecText(spec) }}</div>
            </div>
            <el-button type="primary" plain @click="addPackagingSpec">添加多装箱包装规则</el-button>
          </div>
        </el-form-item>
        <!-- 规格 = 上面结构化字段自动拼 (只读, 不再手输文字+数字混排) -->
        <el-form-item v-if="!isSemiFinishedSku" label="规格">
          <el-input v-model="formData.specification" readonly placeholder="由上方「标准克重 + 装箱换算」自动生成" />
          <div class="form-tip">规格由上面结构化字段自动生成，无需手填（如「200g/袋 10袋/箱 2kg/箱」）</div>
        </el-form-item>

        <el-form-item label="备注" prop="notes">
          <el-input v-model="formData.notes" type="textarea" :rows="3" placeholder="请输入备注信息" />
        </el-form-item>

        <!-- ========== 高级设置 (T157): 非必要项收起, 内分 关联客户/温区/图片 + 成本 + 产能 + 库存 ========== -->
        <el-collapse class="product-advanced">
          <el-collapse-item name="advanced">
            <template #title>
              <span style="font-weight:600">高级设置（关联客户 / 温区 / 图片 / 成本 / 产能 / 库存 — 可选）</span>
            </template>

            <el-form-item label="关联客户" prop="relatedCustomer">
              <el-select v-model="formData.relatedCustomer" placeholder="选择客户（可输入新客户名）"
                filterable allow-create default-first-option clearable style="width: 100%" @change="handleCustomerChange">
                <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.name" />
              </el-select>
              <div v-if="formData.customerId" class="form-tip" style="color:#67c23a;">已绑定客户ID: {{ formData.customerId }}</div>
              <div v-else-if="formData.relatedCustomer" class="form-tip">新客户（仅记录名称，未绑定客户档案）</div>
            </el-form-item>
            <el-form-item label="基础名称">
              <el-autocomplete v-model="formData.baseProductName" :fetch-suggestions="queryBaseProductName"
                placeholder="如: 好食光卤猪蹄（RN优先显示，留空用产品名）" clearable style="width: 100%"
                @input="markBaseProductNameEdited" @select="markBaseProductNameEdited" />
              <div class="form-tip">仅含产品本身名称，不含客户/规格后缀；RN 展示优先用此字段</div>
            </el-form-item>
            <el-form-item label="温区" prop="temperatureZone">
              <el-select v-model="formData.temperatureZone" placeholder="请选择温区" clearable style="width: 100%" @change="markTemperatureZoneEdited">
                <el-option label="常温" value="常温" />
                <el-option label="冷藏" value="冷藏" />
                <el-option label="冷冻" value="冷冻" />
              </el-select>
            </el-form-item>
            <el-form-item label="产品图片" prop="imageUrl">
              <el-input v-model="formData.imageUrl" placeholder="请输入图片URL" />
              <div v-if="formData.imageUrl" class="image-preview">
                <el-image :src="formData.imageUrl" fit="contain" style="width: 100px; height: 100px; margin-top: 8px;">
                  <template #error><div class="image-error">图片加载失败</div></template>
                </el-image>
              </div>
            </el-form-item>

            <!-- 成本 -->
            <el-divider content-position="left">成本</el-divider>
            <template v-if="canEditMarginRedline">
              <el-alert class="margin-redline-tip" type="info" :closable="false" show-icon
                title="用于销售低价拦截：最低售价 = 标准成本 ÷ (1 - 目标毛利率)。留空则产品级配置不生效。" />
              <el-row :gutter="16">
                <el-col :xs="24" :sm="12">
                  <el-form-item label="标准成本">
                    <el-input-number v-model="formData.standardCost" :min="0" :precision="4" :controls="false"
                      placeholder="例如 12.5000，留空跳过产品红线" style="width: 100%" />
                    <div class="form-tip">单位成本，范围 ≥ 0；作为毛利红线成本基准。</div>
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12">
                  <el-form-item label="目标毛利率">
                    <el-input-number v-model="formData.targetGrossMarginPercent" :min="0" :max="99.99" :precision="2" :controls="false"
                      placeholder="例如 10 表示 10%" style="width: 100%" />
                    <div class="form-tip">百分比 0-99.99，提交按小数存储（10% 存 0.10）。</div>
                  </el-form-item>
                </el-col>
              </el-row>
            </template>
            <DynamicEntityForm v-if="costExtendedFields.length" :fields="costExtendedFields"
              :model-value="formData as TableRow" @update:model-value="handleExtendedFormUpdate" :columns="2" label-width="120px" />

            <!-- 产能 -->
            <template v-if="capacityExtendedFields.length">
              <el-divider content-position="left">产能</el-divider>
              <DynamicEntityForm :fields="capacityExtendedFields" :model-value="formData as TableRow"
                @update:model-value="handleExtendedFormUpdate" :columns="2" label-width="120px" />
            </template>

            <!-- 库存采购 -->
            <template v-if="inventoryExtendedFields.length">
              <el-divider content-position="left">库存采购</el-divider>
              <DynamicEntityForm :fields="inventoryExtendedFields" :model-value="formData as TableRow"
                @update:model-value="handleExtendedFormUpdate" :columns="2" label-width="120px" />
            </template>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      title="批量导入 SKU"
      width="920px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>使用后端提供的四工作表模板</template>
        <template #default>
          模板包含成品、半成品、客户自带原料加工、纯代工。先预览校验，确认后才会原子写入；示例行不会导入。
        </template>
      </el-alert>
      <div class="import-actions">
        <el-button :icon="Download" :loading="importDownloading" @click="downloadSkuImportTemplate">下载 Excel 模板</el-button>
        <el-upload
          accept=".xlsx"
          :auto-upload="false"
          :limit="1"
          :show-file-list="true"
          :on-change="handleImportExcelChange"
          :on-remove="() => { importExcelFile = null; importPreview = null; }"
        >
          <el-button :icon="Upload" type="primary">选择 Excel</el-button>
        </el-upload>
      </div>
      <el-divider content-position="left">SKU 图片（可选）</el-divider>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="图片 URL">
            <el-input
              v-model="importImageUrlText"
              type="textarea"
              :rows="5"
              placeholder="每行：SKU编号,https://example.com/image.jpg"
              @input="importPreview = null"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="批量图片匹配">
            <el-upload
              accept="image/jpeg,image/png"
              multiple
              :auto-upload="false"
              :show-file-list="true"
              :on-change="handleImportImagesChange"
              :on-remove="handleImportImagesChange"
            >
              <el-button :icon="Picture">选择 JPG / PNG</el-button>
            </el-upload>
            <div class="form-tip">图片文件名（不含扩展名）必须等于 SKU 编号；单张不超过 5MB。</div>
            <el-alert
              v-if="duplicateImageCodes.size"
              type="error"
              :closable="false"
              :title="`重复编号：${[...duplicateImageCodes].join('、')}`"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <div class="import-preview-action">
        <el-button
          type="primary"
          :loading="importPreviewing || importImageUploading"
          :disabled="!importExcelFile"
          @click="previewSkuImport"
        >上传并预览</el-button>
      </div>
      <template v-if="importPreview">
        <el-divider content-position="left">导入预览</el-divider>
        <div class="import-summary">
          <el-tag>总计 {{ importPreview.totalRows }}</el-tag>
          <el-tag type="success">有效 {{ importPreview.validRows }}</el-tag>
          <el-tag :type="importPreview.invalidRows ? 'danger' : 'info'">错误 {{ importPreview.invalidRows }}</el-tag>
        </div>
        <el-table :data="importPreview.rows" border stripe size="small" max-height="340">
          <el-table-column prop="sheetName" label="工作表" width="130" />
          <el-table-column prop="rowNumber" label="行" width="60" align="center" />
          <el-table-column prop="skuCode" label="SKU 编号" width="140" show-overflow-tooltip />
          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column prop="specification" label="生成规格" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.specification || '—' }}</template>
          </el-table-column>
          <el-table-column label="图片" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.matchedImageName || row.imageUrl || '未匹配' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'VALID' ? 'success' : row.status === 'INVALID' ? 'danger' : 'info'">
                {{ row.status === 'VALID' ? '可导入' : row.status === 'INVALID' ? '有错误' : '示例行跳过' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ importRowErrorText(row) }}</template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="importPreview.errors.length"
          type="error"
          :closable="false"
          style="margin-top: 10px"
          :title="`请先修正 ${importPreview.invalidRows} 行错误后重新预览`"
        />
      </template>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="importConfirming"
          :disabled="!importPreview || importPreview.invalidRows > 0 || importPreview.validRows === 0"
          @click="confirmSkuImport"
        >确认导入 {{ importPreview?.validRows || 0 }} 个 SKU</el-button>
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

    <!-- AI 智能建产品 (飞轮衔接) -->
    <el-dialog
      v-model="aiProductDialogVisible"
      title="AI 智能建产品 (飞轮衔接)"
      width="700px"
      :close-on-click-modal="false"
      @closed="resetAiProductDialog"
    >
      <el-form :model="aiProductForm" label-width="100px">
        <el-form-item label="产品名称" required>
          <el-input v-model="aiProductForm.productName" placeholder="请输入产品名称（必填）" clearable />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="aiProductForm.unit" placeholder="如 盒、份、kg（可选）" clearable />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="aiProductForm.specification" placeholder="如 120g/盒（可选）" clearable />
        </el-form-item>
        <el-form-item label="关联客户">
          <el-input v-model="aiProductForm.customerName" placeholder="客户名称（可选）" clearable />
        </el-form-item>
        <el-form-item label="参照产品">
          <el-input v-model="aiProductForm.inheritFrom" placeholder="参照产品名，飞轮按此继承工序链（可选）" clearable />
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 16px;">
        <el-button type="info" :loading="aiProductPreviewing" @click="handleAiProductPreview">
          预览飞轮方案
        </el-button>
      </div>

      <!-- 预览结果 -->
      <template v-if="aiProductPreview">
        <el-alert
          :title="aiProductPreview.message"
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
        />

        <div style="margin-bottom: 10px; display: flex; align-items: center; gap: 8px;">
          <span style="font-size: 13px; color: #606266; flex-shrink: 0;">最相似产品:</span>
          <el-tag v-if="aiProductPreview.inheritFromProduct" type="success">
            {{ aiProductPreview.inheritFromProduct }}
          </el-tag>
          <span v-else style="font-size: 13px; color: #909399;">飞轮未找到相似产品，工序需手动配</span>
        </div>

        <template v-if="aiProductPreview.suggestedProcessChain.length > 0">
          <div class="ai-section-title">将自动继承的工序链（大框架）</div>
          <div style="display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 14px;">
            <el-tag
              v-for="(proc, idx) in aiProductPreview.suggestedProcessChain"
              :key="idx"
              type="primary"
            >
              {{ idx + 1 }}. {{ proc }}
            </el-tag>
          </div>
        </template>

        <template v-if="aiProductPreview.suggestedBom.length > 0">
          <div class="ai-section-title">BOM 建议（细节请自行调）</div>
          <el-table
            :data="aiProductPreview.suggestedBom"
            size="small"
            border
            stripe
            style="width: 100%; margin-bottom: 14px;"
          >
            <el-table-column prop="materialName" label="原料名" min-width="120" show-overflow-tooltip />
            <el-table-column prop="materialCategory" label="分类" width="90" align="center" />
            <el-table-column prop="standardQuantity" label="标准用量" width="90" align="center" />
            <el-table-column label="出成率" width="80" align="center">
              <template #default="{ row }">
                {{ row.yieldRate != null ? (row.yieldRate * 100).toFixed(1) + '%' : '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="60" align="center" />
          </el-table>
        </template>

        <template v-if="aiProductPreview.suggestedSeasoning.length > 0">
          <div class="ai-section-title">调料配方建议</div>
          <el-table
            :data="aiProductPreview.suggestedSeasoning"
            size="small"
            border
            stripe
            style="width: 100%; margin-bottom: 14px;"
          >
            <el-table-column prop="name" label="调料名" min-width="120" show-overflow-tooltip />
            <el-table-column prop="section" label="工序段" width="110" />
            <el-table-column prop="dosagePerKgG" label="用量 (g/kg)" width="100" align="center" />
          </el-table>
        </template>
      </template>

      <template #footer>
        <el-button @click="aiProductDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="aiProductCreating"
          :disabled="!aiProductPreview || !aiProductConfirmationToken"
          @click="handleAiProductCreate"
        >
          确认建产品
        </el-button>
      </template>
    </el-dialog>

    <!-- AI 对话录入 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PRODUCT_CONFIG"
      :confirm-guard="productAiGuard"
      guard-action-label="前往原料类型字典"
      @fill-form="handleAiFill"
      @guard-action="handleProductAiGuardAction"
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
                  <span class="linked-unit">{{ displayUnit(item.unitOverride || item.defaultUnit) }}</span>
                  <span v-if="item.estimatedMinutesOverride || item.defaultEstimatedMinutes" class="linked-time">
                    {{ item.estimatedMinutesOverride || item.defaultEstimatedMinutes }}分钟
                  </span>
                </div>
              </div>
              <div class="linked-actions">
                <el-button :icon="Rank" link size="small" :disabled="idx === 0" @click="handleMoveProcess(idx, 'up')" title="上移" aria-label="上移工序" />
                <el-button :icon="Rank" link size="small" :disabled="idx === linkedProcesses.length - 1" @click="handleMoveProcess(idx, 'down')" title="下移" aria-label="下移工序" />
                <el-button :icon="DeleteIcon" link size="small" type="danger" title="移除" aria-label="移除工序" @click="handleRemoveProcess(item)" />
              </div>
            </div>
          </div>
        </div>

        <el-divider />

        <!-- 快捷工序目录：与完整 Workflow 编辑器并存，适合连续关联基础工序。 -->
        <div class="process-section">
          <div class="section-title">
            <span>工序目录</span>
            <el-tag size="small">结果 {{ filteredProcessCatalog.length }} / 总计 {{ availableProcesses.length }}</el-tag>
            <el-button link type="primary" @click="resetProcessCatalogFilters">清空筛选</el-button>
          </div>
          <div class="process-catalog-filters">
            <el-input
              v-model="processKeyword"
              clearable
              placeholder="搜索工序名称 / 编码 / 类别标签"
              aria-label="搜索快捷工序"
            />
            <el-select v-model="processCategoryFilter" clearable placeholder="全部类别" aria-label="工序类别筛选">
              <el-option v-for="category in processCategoryOptions" :key="category" :label="category" :value="category" />
            </el-select>
            <el-segmented
              v-model="processRelationFilter"
              :options="[
                { label: '全部', value: 'ALL' },
                { label: '已关联', value: 'LINKED' },
                { label: '未关联', value: 'UNLINKED' },
              ]"
              aria-label="工序关联状态筛选"
            />
          </div>
          <div v-if="availableProcesses.length === 0" class="process-empty">
            暂无可用工序，请先在工序字典维护启用工序
          </div>
          <div v-else-if="filteredProcessCatalog.length === 0" class="process-empty">
            没有符合当前条件的工序，请调整或清空筛选
          </div>
          <div v-else class="available-list">
            <div v-for="proc in pagedProcessCatalog" :key="proc.id" class="available-item" :class="{ 'is-linked': linkedProcessIds.has(proc.id) }">
              <div class="available-info">
                <span class="available-name">{{ proc.processName }}</span>
                <el-tag size="small" type="info">{{ proc.processCategory }}</el-tag>
                <span class="available-code">{{ proc.id }}</span>
              </div>
              <el-tag v-if="linkedProcessIds.has(proc.id)" type="success" size="small">
                已关联 · 第 {{ linkedProcessById.get(proc.id)?.processOrder }} 道
              </el-tag>
              <el-button
                v-else
                type="primary"
                size="small"
                :icon="Plus"
                :loading="addingProcessId === proc.id"
                @click="handleAddProcess(proc.id)"
              >
                添加
              </el-button>
            </div>
            <el-pagination
              v-if="filteredProcessCatalog.length > processCatalogPageSize"
              v-model:current-page="processCatalogPage"
              :page-size="processCatalogPageSize"
              :total="filteredProcessCatalog.length"
              layout="prev, pager, next"
              small
              class="process-catalog-pagination"
            />
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

.import-actions,
.import-preview-action,
.import-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}
.import-preview-action { justify-content: flex-end; margin-top: 12px; }
.import-summary { margin-bottom: 10px; }

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

.margin-redline-tip {
  margin-bottom: 16px;
}

/* T148: 装箱换算内联行 — 「1 [一级单位▼] ＝ [换算数] [二级单位▼]」始终显示 */
.spec-conversion-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}

.packaging-spec-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.packaging-rule-note {
  padding: 10px 12px;
  color: #7a8599;
  font-size: 12px;
  line-height: 1.6;
  background: #f4f6f9;
  border: 1px solid #edf2f7;
  border-radius: 8px;
}

.exact-duplicate-alert {
  width: 100%;
  margin-top: 8px;
}

.standard-weight-row {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.standard-weight-row :deep(.el-input-number) {
  width: 220px;
}

.standard-weight-unit {
  min-width: 54px;
  color: var(--el-text-color-primary, #303133);
  font-weight: 500;
}

.sku-measure-row {
  display: grid;
  grid-template-columns: minmax(150px, 1.25fr) auto minmax(110px, .75fr) 84px;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.sales-unit-select,
.net-content-input,
.net-content-unit-select {
  width: 100%;
}

.measure-separator {
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}

:global(.product-edit-modal .el-dialog__body) {
  max-height: calc(100vh - 190px);
  overflow-y: auto;
  overscroll-behavior: contain;
}

:global(.product-edit-modal .el-dialog__footer) {
  position: sticky;
  bottom: 0;
  z-index: 2;
  padding-top: 12px;
  background: var(--el-bg-color, #fff);
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.packaging-spec-item {
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafafa;
}

.packaging-spec-index {
  min-width: 48px;
  color: #606266;
  font-size: 12px;
}

.spec-conversion-one {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
  flex-shrink: 0;
}

.spec-conversion-eq {
  font-size: 16px;
  color: #606266;
  flex-shrink: 0;
  padding: 0 2px;
}

.spec-unit-select {
  width: 100px;
  flex-shrink: 0;
}

.spec-coef-input {
  width: 110px;
  flex-shrink: 0;
}

.spec-same-warn {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 4px;
  line-height: 1.4;
}

.spec-echo {
  font-size: 12px;
  color: #67c23a;
  margin-top: 4px;
  font-weight: 500;
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
  max-height: min(48vh, 620px);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
}

.process-catalog-filters {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) minmax(110px, .8fr) minmax(120px, .8fr);
  gap: 8px;
  margin-bottom: 10px;
}

.process-catalog-filters :deep(.el-segmented) {
  grid-column: 1 / -1;
  justify-self: start;
}

.available-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border: 1px solid #EBEEF5;
  border-radius: 6px;
  background: #fff;

  &.is-linked {
    border-color: var(--el-color-success-light-5, #b3e19d);
    background: var(--el-color-success-light-9, #f0f9eb);
  }

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

    .available-code {
      color: var(--el-text-color-secondary, #909399);
      font: 11px/1.2 ui-monospace, SFMono-Regular, Consolas, monospace;
    }
  }
}

.process-catalog-pagination {
  position: sticky;
  bottom: 0;
  justify-content: center;
  padding: 8px 0;
  background: var(--el-bg-color, #fff);
}

@media (max-width: 720px) {
  .sku-measure-row,
  .process-catalog-filters {
    grid-template-columns: 1fr;
  }
  .measure-separator { display: none; }
}

/* AI 智能建产品 dialog 内部标题 */
.ai-section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
</style>
