<script setup lang="ts">
import { ref, computed, onMounted, reactive, watch } from 'vue';
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
// 静态兜底列表 (API 为空或加载失败时使用) — common-first 顺序, 含 框 (六扇门规格用)
const UNIT_FALLBACK: { label: string; value: string }[] = [
  { label: '盒', value: '盒' },
  { label: '份', value: '份' },
  { label: '筐', value: '筐' },
  { label: '框', value: '框' },
  { label: '箱', value: '箱' },
  { label: '件', value: '件' },
  { label: '袋', value: '袋' },
  { label: '桶', value: '桶' },
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

// T137: 转换数 placeholder 动态显示实际单位名 ("1 筐 = ? 盒 填 20")
const conversionPlaceholder = computed(() => {
  const l1 = (formData.level1Unit as string | undefined)?.trim();
  const l2 = formData.unit?.trim();
  if (l1 && l2) return `1 ${l1} = ? ${l2}（如 1筐=20盒 填 20）`;
  if (l1) return `1 ${l1} = ? 二级单位（如 1筐=20盒 填 20）`;
  return '先填一级单位，再填换算数（如 1筐=20盒 填 20）';
});

// T146: 标准克重 placeholder — 动态替换二级单位名 (如 "每盒多少克")
const gramsPerUnitPlaceholder = computed(() => {
  const l2 = formData.unit?.trim();
  if (l2) return `每${l2}多少克（如 1${l2}=120克 填 120）`;
  return '每二级单位多少克（如 1盒=120克 填 120），末道报工折算用';
});

// 产品扩展字段 — 添加新字段只需在此数组加一行
// T123 重组: gramsPerUnit + boxConversionCoefficient 移到 '规格信息' 组
//   (成品也需要标准克重设置,不应被'商务信息'隐藏逻辑屏蔽)
// T148: level1Unit + boxConversionCoefficient 移出 productExtendedFields,
//   改为内联行渲染「1 [一级单位▼] ＝ [换算数] [二级单位▼]」,始终显示
const productExtendedFields = computed<FieldConfig[]>(() => [
  // ---- 规格信息 (成品/原料均显示) ----
  // T148: level1Unit + boxConversionCoefficient 已移为内联行 (见模板中「装箱换算」el-form-item)
  // T146 Fix4: gramsPerUnit placeholder 动态替换二级单位名 (如 "每盒多少克")
  { key: 'gramsPerUnit', label: '标准克重', type: 'decimal', group: '规格信息', precision: 2, suffix: '克', order: 3,
    placeholder: gramsPerUnitPlaceholder.value },
  { key: 'wipToFgYield', label: '半成品出成率', type: 'decimal', group: '规格信息', precision: 4, order: 4,
    placeholder: '0~1，如 0.55=55%（留空按 1:1）' },
  // 六扇门 配料单 (配料员按锅配料): 单锅产能 = 1 锅产出数量 (同计划产量单位); 配料单据此算锅数 = ceil(计划量/单锅产能)
  { key: 'singlePotCapacity', label: '单锅产能', type: 'decimal', group: '规格信息', precision: 3, order: 6,
    placeholder: '1 锅产出数量(同计划产量单位), 配料单算锅数用; 留空则配料单不计每锅量' },
  // SP9-M1: 研发预估人工成本 (quotedLaborCost); 供人效双口径对比用; 成品才有意义, 原辅料留空即可
  { key: 'quotedLaborCostPerKg', label: '研发人工成本(元/kg)', type: 'decimal', group: '规格信息', precision: 4, order: 5,
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
  quotedLaborCostPerKg?: number; // SP9-M1: 研发预估人工成本(元/kg), 人效双口径对比报价侧
  standardCost?: number | null; // SP5: standard unit cost for margin redline
  targetGrossMargin?: number | null; // SP5: 0-1 decimal from backend
  targetGrossMarginPercent?: number | null; // UI-only percent input
  level1Unit?: string;     // T123: 一级单位 (如 筐, 箱) 与 boxConversionCoefficient 联用
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

// T147 Fix4: 规格信息组的「关系摘要」— 把 二级单位(=顶部单位, 只读回显) / 标准克重 / 一级单位+换算
// 三者摆在一起, 让 "1 筐 = 20 盒, 每盒 120 克" 的关系一目了然.
// 二级单位 不另建可编辑绑定, 只 read-only echo 顶部「单位」字段 (单一事实源).
const specRelationSummary = computed<{
  l2: string; l1: string; coef: string; grams: string;
  conversionText: string; hasConversion: boolean; warn: boolean;
}>(() => {
  const l2 = formData.unit?.trim() || '';
  const l1 = (formData.level1Unit as string | undefined)?.trim() || '';
  const coefRaw = formData.boxConversionCoefficient;
  const coefNum = typeof coefRaw === 'number' ? coefRaw : parseFloat(String(coefRaw ?? ''));
  const grams = formData.gramsPerUnit != null && String(formData.gramsPerUnit) !== ''
    ? String(formData.gramsPerUnit) : '';
  const warn = !!(l1 && l2 && l1 === l2); // T146 守卫: 一级=二级 是废话
  let conversionText = '';
  let hasConversion = false;
  if (!warn && l1 && l2 && !isNaN(coefNum) && coefNum > 0) {
    conversionText = `1 ${l1} = ${coefNum} ${l2}`;
    hasConversion = true;
  }
  return {
    l2, l1,
    coef: !isNaN(coefNum) && coefNum > 0 ? String(coefNum) : '',
    grams,
    conversionText, hasConversion, warn,
  };
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
  refreshCodePreview();
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
    if (!categoryManuallyEdited.value) {
      // 大类重置为当前 tab 默认值 (大类是必填, 不能置 null)
      formData.productCategory = activeTab.value;
    }
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
  try {
    const res = await get<SuggestResult>(`/${factoryId.value}/product-types/suggest`, {
      params: { name, productCategory: formData.productCategory || undefined },
    });
    // T154: cascade 开始写字段, 防 handleExtendedFormUpdate 误标手动编辑
    cascadeWriting = true;
    try {
      if (!res.success || !res.data) { suggestHint.value = ''; return; }
      const s = res.data;
      let filledAny = false;

      // T154 reactive: 对每个非手动编辑字段, 始终写入新匹配值 (或清空 null); 不再限制"仅当为空".
      // 大类: 未手动改过 → 跟随匹配结果; 匹配无结果时保持 tab 默认 (大类是必填)
      if (!categoryManuallyEdited.value) {
        const newCat = (s.productCategory as ProductCategory | null) ?? null;
        if (newCat && formData.productCategory !== newCat) {
          formData.productCategory = newCat;
          refreshCodePreview(); // 大类变化需刷新编号预览
          filledAny = true;
        }
      }
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
// 格式: {克重}g/{二级单位} {装箱系数}{二级单位}/{一级单位} → 如 "80g/盒 20盒/框".
// 只拼存在的部分: 仅克重→"80g/盒"; 仅装箱→"20盒/框"; 都有→两段空格相连; 都无→空串.
// 非覆盖: 用户手动改过规格 (specificationManuallyEdited) 后停止自动拼, 用户始终可覆盖.
function composeSpecification(): string {
  const grams = formData.gramsPerUnit;
  const l2 = formData.unit?.trim() || '';
  const coefRaw = formData.boxConversionCoefficient;
  const coefNum = typeof coefRaw === 'number' ? coefRaw : parseFloat(String(coefRaw ?? ''));
  const l1 = (formData.level1Unit as string | undefined)?.trim() || '';

  const parts: string[] = [];
  // 克重段: 需要 克重 + 二级单位 (如 "80g/盒")
  const gramsNum = grams != null && String(grams) !== '' ? Number(grams) : NaN;
  if (!isNaN(gramsNum) && gramsNum > 0 && l2) {
    parts.push(`${gramsNum}g/${l2}`);
  }
  // 装箱段: 需要 装箱系数 + 二级单位 + 一级单位 (如 "20盒/框")
  if (!isNaN(coefNum) && coefNum > 0 && l2 && l1 && l1 !== l2) {
    parts.push(`${coefNum}${l2}/${l1}`);
  }
  return parts.join(' ');
}

// 监听结构化字段 → 自动重拼规格 (仅新增, 非覆盖)
watch(
  () => [formData.gramsPerUnit, formData.unit, formData.boxConversionCoefficient, formData.level1Unit],
  () => {
    if (!dialogVisible.value) return;
    // 用户手动改过规格 → 停止自动拼 (尊重用户输入)
    if (specificationManuallyEdited.value) return;
    const composed = composeSpecification();
    // T154: cascade 清空结构化字段后 composed 为空串 → 也清掉规格 (不保留旧自动拼结果)
    if (formData.specification !== composed) {
      formData.specification = composed;
    }
  },
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
  formData.level1Unit = undefined; // T148: 装箱换算内联行字段
  formData.boxConversionCoefficient = undefined; // T148: 装箱换算内联行字段
  formData.temperatureZone = '';
  formData.imageUrl = '';
  formData.notes = '';
  formData.standardCost = null;
  formData.targetGrossMargin = null;
  formData.targetGrossMarginPercent = null;
}

function handleAdd() {
  resetForm();
  dialogTitle.value = '新增产品';
  isEditing.value = false;
  // T147 Fix2: 重置编号预览状态, 大类已由 resetForm 默认为当前 tab (Fix3) → 触发预览
  codePreview.value = '';
  codeManuallyEdited.value = false;
  resetSuggestFlags(); // T149: 重置智能填充手动编辑标志
  dialogVisible.value = true;
  refreshCodePreview();
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
  formData.standardCost = row.standardCost ?? null;
  formData.targetGrossMargin = row.targetGrossMargin ?? null;
  formData.targetGrossMarginPercent = row.targetGrossMargin != null
    ? Number((Number(row.targetGrossMargin) * 100).toFixed(2))
    : null;
  // T148: 装箱换算内联行字段 — 编辑时从 row 回填
  formData.level1Unit = row.level1Unit ?? undefined;
  formData.boxConversionCoefficient = row.boxConversionCoefficient ?? undefined;
  // T153: 编辑模式下, 已有规格视为「用户已设置」→ 改结构化字段不覆盖既有规格 (仅用户清空后才自动重拼).
  //       基础名称推导本就跳过编辑模式 (watch isEditing 守卫), 此处显式置标志保持一致.
  resetSuggestFlags();
  specificationManuallyEdited.value = !!(row.specification && row.specification.trim());
  baseProductNameManuallyEdited.value = true;
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
      // T148: level1Unit + boxConversionCoefficient 已移出 productExtendedFields (改内联行渲染),
      //   但仍需随产品提交 — 显式追加 (后者覆盖前者同名 key 优先级: 上方 Object.fromEntries 无这两key 所以无冲突)
      level1Unit: formData.level1Unit ?? null,
      boxConversionCoefficient: formData.boxConversionCoefficient ?? null,
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
}

const aiProductDialogVisible = ref(false);
const aiProductPreviewing = ref(false);
const aiProductCreating = ref(false);
const aiProductPreview = ref<AiProductPreviewData | null>(null);

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
  try {
    const res = await post<AiProductPreviewData>(
      `/${factoryId.value}/ai-product-create/preview`,
      buildAiProductBody()
    );
    if (res.success && res.data) {
      aiProductPreview.value = res.data;
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
  if (!factoryId.value) return;
  aiProductCreating.value = true;
  try {
    const res = await post(`/${factoryId.value}/ai-product-create`, buildAiProductBody());
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
            <el-button v-if="canWrite" type="warning" :icon="ChatDotRound" @click="aiProductDialogVisible = true; resetAiProductDialog()">
              AI 智能建产品
            </el-button>
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
        <el-form-item label="产品名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入产品名称" @input="handleNameInput" />
          <!-- T149: 智能填充命中提示 (按历史产品匹配出大类/单位/装箱) -->
          <div v-if="suggestHint && !isEditing" class="form-tip" style="color:#67c23a;">
            ✨ {{ suggestHint }}
          </div>
        </el-form-item>
        <!-- T147 Fix3: 新增时默认=当前 tab 的大类 (resetForm/handleAdd 已置), 变化时刷新编号预览 (Fix2) -->
        <el-form-item label="产品大类" prop="productCategory">
          <el-select v-model="formData.productCategory" placeholder="请选择产品大类" style="width: 100%" @change="handleCategoryChange">
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
            @change="markUnitEdited"
          >
            <el-option
              v-for="opt in unitSelectOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="form-tip">二级单位 = 最小计量（如「盒」120克/盒）；大包装一级单位（如「筐」1筐=20盒）在下方「规格信息」中配置</div>
        </el-form-item>
        <el-form-item label="规格" prop="specification">
          <el-input v-model="formData.specification" placeholder="请输入规格（如：310g*42袋/箱）"
            @input="markSpecificationEdited" />
        </el-form-item>
        <!-- T147 Fix1: 关联客户改「下拉」(el-select filterable + allow-create) — 既是客户下拉, 也可手输新客户名.
             选中已有客户同步 customerId(entity link); 手输新名清空 customerId (保留 T123 双绑行为) -->
        <el-form-item label="关联客户" prop="relatedCustomer">
          <el-select
            v-model="formData.relatedCustomer"
            placeholder="选择客户（可输入新客户名）"
            filterable
            allow-create
            default-first-option
            clearable
            style="width: 100%"
            @change="handleCustomerChange"
          >
            <el-option
              v-for="c in customers"
              :key="c.id"
              :label="c.name"
              :value="c.name"
            />
          </el-select>
          <div v-if="formData.customerId" class="form-tip" style="color:#67c23a;">已绑定客户ID: {{ formData.customerId }}</div>
          <div v-else-if="formData.relatedCustomer" class="form-tip">新客户（仅记录名称，未绑定客户档案）</div>
        </el-form-item>
        <!-- T123: 产品基础名 (名称分离) — 如"好食光卤猪蹄"，RN 优先显示此字段 -->
        <el-form-item label="基础名称">
          <el-autocomplete
            v-model="formData.baseProductName"
            :fetch-suggestions="queryBaseProductName"
            placeholder="如: 好食光卤猪蹄（RN优先显示，留空用产品名）"
            clearable
            style="width: 100%"
            @input="markBaseProductNameEdited"
            @select="markBaseProductNameEdited"
          />
          <div class="form-tip">仅含产品本身名称，不含客户/规格后缀；RN 展示优先用此字段，为空则回退到产品名称</div>
        </el-form-item>
        <el-form-item label="温区" prop="temperatureZone">
          <el-select v-model="formData.temperatureZone" placeholder="请选择温区" clearable style="width: 100%"
            @change="markTemperatureZoneEdited">
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

        <!-- T148: 规格信息 — 装箱换算内联行「1 [一级单位▼] ＝ [换算数] [二级单位▼]」始终显示
             二级单位下拉 v-model 绑 formData.unit (同顶部「单位」字段, 单一事实源, 双向同步)
             一级单位 → formData.level1Unit; 换算数 → formData.boxConversionCoefficient -->
        <el-form-item label="装箱换算" label-width="120px">
          <div class="spec-conversion-row">
            <span class="spec-conversion-one">1</span>
            <el-select
              v-model="formData.level1Unit"
              placeholder="一级单位"
              filterable
              allow-create
              clearable
              class="spec-unit-select"
              @change="markLevel1UnitEdited"
            >
              <el-option
                v-for="opt in unitSelectOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <span class="spec-conversion-eq">＝</span>
            <el-input-number
              v-model="formData.boxConversionCoefficient"
              :min="0"
              :precision="4"
              :controls="false"
              placeholder="换算数"
              class="spec-coef-input"
              @change="markBoxCoefEdited"
            />
            <el-select
              v-model="formData.unit"
              placeholder="二级单位"
              filterable
              allow-create
              clearable
              class="spec-unit-select"
              @change="markUnitEdited"
            >
              <el-option
                v-for="opt in unitSelectOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </div>
          <!-- T146 守卫: 一级=二级 警告 -->
          <div v-if="specRelationSummary.warn" class="spec-same-warn">
            ⚠️ 一级单位应与二级单位不同（一级是大包装如「筐」，二级是最小单位如「盒」）
          </div>
          <!-- 换算完整时给出确认回显 -->
          <div v-else-if="specRelationSummary.hasConversion" class="spec-echo">
            = {{ specRelationSummary.conversionText }}
          </div>
          <div class="form-tip">如 1 筐 = 20 盒；二级单位与上方「单位」字段同步</div>
        </el-form-item>

        <template v-if="canEditMarginRedline">
          <el-divider content-position="left">毛利红线</el-divider>
          <el-alert
            class="margin-redline-tip"
            type="info"
            :closable="false"
            show-icon
            title="用于销售低价拦截：最低售价 = 标准成本 ÷ (1 - 目标毛利率)。留空则产品级配置不生效。"
          />
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12">
              <el-form-item label="标准成本">
                <el-input-number
                  v-model="formData.standardCost"
                  :min="0"
                  :precision="4"
                  :controls="false"
                  placeholder="例如 12.5000，留空跳过产品红线"
                  style="width: 100%"
                />
                <div class="form-tip">单位成本，范围 ≥ 0；保存后会作为毛利红线成本基准。</div>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item label="目标毛利率">
                <el-input-number
                  v-model="formData.targetGrossMarginPercent"
                  :min="0"
                  :max="99.99"
                  :precision="2"
                  :controls="false"
                  placeholder="例如 10 表示 10%"
                  style="width: 100%"
                />
                <div class="form-tip">请输入百分比 0-99.99，提交后按小数存储，例如 10% 存为 0.10。</div>
              </el-form-item>
            </el-col>
          </el-row>
        </template>

        <!-- T150: @update 改用 handleExtendedFormUpdate 以追踪 gramsPerUnit/wipToFgYield 手动编辑标志 -->
        <DynamicEntityForm
          :fields="visibleExtendedFields"
          :model-value="formData as TableRow"
          @update:model-value="handleExtendedFormUpdate"
          :columns="2"
          label-width="120px"
        />
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
          :disabled="!aiProductPreview"
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

/* AI 智能建产品 dialog 内部标题 */
.ai-section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
</style>
