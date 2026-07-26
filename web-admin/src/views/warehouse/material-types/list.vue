<script setup lang="ts">
/**
 * 原料类型字典管理 (raw_material_types)
 *
 * 张权 Apr 29 2026 反馈: "没有 新建的入口哦 这个入口是入库登记".
 * 仓储 → 原料 / 物料 (采购入库) 页面只有"入库登记"(material_batches CRUD),
 * 但缺一个"原料类型字典"管理页, 用户无法在 UI 里创建新原料类型 (冻猪蹄/吸塑盒).
 * 后端 /raw-material-types 全 CRUD 早齐, 只缺前端页面 — 本页补上.
 *
 * May 7 2026 用户需求 (PR #114/#116/#120 后端落地):
 * 1. 编码自动生成 (创建时不传 code, 后端生成)
 * 2. 类别下拉与列表大类统一读取 16 位物料编码字典的 L1 类族
 * 3. 单位下拉 + 智能默认 (suggest-unit 按相似名称+类别取最近原料的 unit)
 * 4. 去掉单价 (按采购价浮动, 在采购订单里录)
 * 5. 包装层级: 一级 (kg, 必填=unit) + 二/三级 (10kg/箱, 12箱/柜)
 *
 * T159-A-form (2026-06-08): 防呆复刻 SKU 表单模式
 *   1. 编码预览 (create mode): GET .../raw-material-types/preview-code?category=&segmentCode=
 *      → 完整 L1-L3 选定后，实时展示与保存同源的业务编码及16位兼容分类编码
 *   2. 全字段智能匹配 cascade: GET .../raw-material-types/suggest?name=&category=
 *      → 填 unit/category/storageType/shelfLifeDays/level1PerLevel2/level2Unit
 *      + *ManuallyEdited flags + cascadeWriting guard (exact SKU pattern)
 *      + clearCascadeFields when name cleared
 *      null 字段 → 不覆盖; 端点 404/error → graceful degrade to suggest-unit fallback
 *   3. 包装层级内联换算行 (SKU-style):
 *      「1 [二级单位] = [换算数] [一级单位]」+ live preview tag
 *      一级单位 = 只读 echo 主单位 (single source of truth)
 *   4. 单位下拉统一使用 UnitSelect，支持搜索、查重和现场创建
 *   foldable #1: 批次列表单位列 → 修 materials/list.vue 显示 quantityUnit 而非 unit
 */
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete as DeleteIcon, Search, Refresh, Lock, View } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { bigCategoryOf } from '@/utils/materialCategory';
import { displayUnit } from '@/utils/unitPricing';
import { handleCatchError } from '@/utils/errorToast';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import UnitSelect from '@/components/common/UnitSelect.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import type { TableRow } from '@/types/api';
import {
  listActiveSuppliers,
  listMaterialSuppliers,
  createSupplierMaterial,
  updateSupplierMaterial,
  deleteSupplierMaterial,
  type SupplierMaterialPayload,
  type SupplierMaterialRelation,
  type SupplierRecord,
} from '@/api/supplierManagement';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canManageSupplierRelations = computed(() => permissionStore.canWrite('procurement'));
const canManageClassification = computed(() => permissionStore.canWrite('system'));
// T2-5b (issue #534): expose movingAvgPrice — gate by canViewPrice RBAC
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
// 客户张权反馈 (2026-07-02): "搜不出来 filter 不了" — 搜索框绑了 searchKeyword 但后端
// GET /{factoryId}/raw-material-types 列表接口 (list.vue 实际调用的接口) 从不接收 keyword 参数
// (只有独立的 /raw-material-types/search 子路由支持 keyword) — 输入框敲字对结果毫无影响,
// 关键字和 L1-L3 前缀统一下推后端分页，避免只取前 2000 条后在客户端假筛选。
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');
// 16位编码前缀筛选：L1/L2/L3 分别对应编码前 3/6/10 位。
const filterSegmentL1 = ref('');
const filterSegmentL2 = ref('');
const filterSegmentL3 = ref('');
const selectedSegmentPrefix = computed(() =>
  filterSegmentL3.value || filterSegmentL2.value || filterSegmentL1.value,
);

// 储存类型读取系统字典；单位由 UnitSelect 统一加载；类别读取下方 16 位编码 L1 类族。
interface DictItem { enumCode: string; enumLabel: string; sortOrder: number }
const storageTypeOptions = ref<DictItem[]>([]);

onMounted(async () => {
  const keyword = route.query.keyword;
  if (typeof keyword === 'string' && keyword.trim()) {
    searchKeyword.value = keyword.trim();
  }
  await Promise.all([loadDictionaries(), loadSegmentTree()]);
  await loadData();
});

async function loadDictionaries() {
  if (!factoryId.value) return;
  try {
    const storageRes = await get<DictItem[]>(`/${factoryId.value}/system-config/enums/MATERIAL_STORAGE_TYPE`);
    storageTypeOptions.value = (storageRes.data || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
  } catch (e) {
    console.warn('字典加载失败, 用空选项', e);
  }
}

function mergeHistoricStorage(current?: string): { value: string; label: string }[] {
  const opts = storageTypeOptions.value.map((c) => ({ value: c.enumLabel, label: c.enumLabel }));
  if (current && current.trim() !== '' && !opts.find((o) => o.value === current)) {
    return [{ value: current, label: `${current} (历史)` }, ...opts];
  }
  return opts;
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<{ content: TableRow[]; totalElements: number }>(
      `/${factoryId.value}/raw-material-types`,
      {
        params: {
          page: pagination.value.page,
          size: pagination.value.size,
          codePrefix: selectedSegmentPrefix.value || undefined,
          keyword: searchKeyword.value.trim() || undefined,
        },
      },
    );
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

// ==================== Create / Edit Dialog ====================
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  code: '', // 仅编辑模式显示, 创建时不传 (后端生成)
  name: '',
  category: '',
  unit: 'kg',
  storageType: '',
  shelfLifeDays: null as number | null,
  notes: '',
  // SP4: 税率 + 含税单价
  taxTreatment: 'TAXABLE' as 'TAXABLE' | 'EXEMPT',
  taxRate: 'TAX_13' as string,
  taxExemptionReason: '',
  taxIncludedUnitPrice: null as number | null,
  // P8: 包材关联固定客户 (选填, 非 PACKAGING 类留空)
  associatedCustomerId: null as string | null,
  // #759: 包材每产品单位用量 (选填, 仅 PACKAGING 有意义)
  packQtyPerProduct: null as number | null,
});

function isPackagingCategory(category: string | null | undefined): boolean {
  return bigCategoryOf(String(category || '')) === '包材';
}

const isPackagingMaterial = computed(() => isPackagingCategory(form.value.category));

// P8: 客户列表 (用于 associatedCustomerId 下拉)
// 客户张权反馈 (2026-07-02): "关联固定客户" 下拉显示"没有数据"。根因排查 (headed + curl F006 对比):
// 之前调的 GET /{factoryId}/customers 是 CRM 客户列表页用的接口, 服务端加了
// @DataScope("created_by") (Sprint 6 W2-B RBAC 第2维数据权限) — SELF/SELF_AND_BELOW/DEPT_AND_BELOW
// 数据域的角色只能看到"自己创建过的客户", 仓库管理员角色几乎从没建过客户记录, 所以这个接口对他们
// 合法返回 0 条 (不是 bug, 是 CRM 列表页故意的数据域隔离), 但拿来给"关联固定客户"这种引用型下拉用
// 就是错的接口 — 后端另有专门为下拉场景准备的 GET /{factoryId}/customers/active
// (CustomerController.getActiveCustomers, 无 DataScope 限制, 文档原话"用于下拉选择等场景"),
// 跟本文件"关联原料"下拉复用的 /raw-material-types/active 是同一套设计模式。改调这个接口即修复
// (curl 对比: F006 /customers 对 factory_super_admin 返 17 条能"凑巧"验证通过, 但换 DataScope=SELF
// 角色会是 0 条; /customers/active 两种角色都稳定返回全部 17 条)。
interface CustomerItem { id: string; name: string }
const customerOptions = ref<CustomerItem[]>([]);
const customerOptionsLoaded = ref(false);
async function loadCustomers() {
  if (!factoryId.value || customerOptions.value.length > 0) return;
  try {
    const res = await get<CustomerItem[] | { content?: CustomerItem[] }>(
      `/${factoryId.value}/customers/active`,
    );
    const data = res.data;
    customerOptions.value = Array.isArray(data) ? data : (data as { content?: CustomerItem[] })?.content ?? [];
  } catch {
    customerOptions.value = [];
  } finally {
    customerOptionsLoaded.value = true;
  }
}
const packaging = ref({
  level1PerLevel2: '' as number | string,
  level2Unit: '',
  level2PerLevel3: '' as number | string,
  level3Unit: '',
});
const dialogTitle = computed(() => (editingId.value ? '编辑原料类型' : '新建原料类型'));

// SP4: 含税单价 → 未税联动
const preTaxUnitPrice = computed(() => {
  const price = form.value.taxIncludedUnitPrice;
  const rate = form.value.taxRate;
  if (price == null) return null;
  if (form.value.taxTreatment === 'EXEMPT') return Number(price);
  if (!rate) return null;
  const rateNum = rate === 'TAX_9' ? 0.09 : rate === 'TAX_13' ? 0.13 : null;
  if (rateNum == null) return null;
  return Math.round((price / (1 + rateNum)) * 100) / 100;
});

// SP8: 16位编码级联下拉 (MaterialCodeSegmentController)
interface SegmentNode {
  id: string;
  segmentCode: string;
  segmentLabel: string;
  level: number;
  parentCode: string | null;
  isActive: boolean;
  children?: SegmentNode[];
}

function formatSegmentOptionLabel(option: { segmentCode: string; segmentLabel?: string; label?: string }): string {
  const label = String(option.segmentLabel || option.label || '').trim();
  return `${label || '未命名分类'}（分类码 ${option.segmentCode}）`;
}

function materialDisplayCode(row: TableRow): string {
  return String(row.displayCode || row.businessCode || row.code || '-').trim() || '-';
}

function materialHasBusinessCode(row: TableRow): boolean {
  return Boolean(String(row.businessCode || '').trim());
}
const segmentTree = ref<SegmentNode[]>([]);
const segmentL1 = ref(''); // L1 大类
const segmentL2 = ref(''); // L2 中类
const segmentL3 = ref(''); // L3 小类（原料类型复用）
const segmentLoading = ref(false);
const segmentCodePreview = ref(''); // SP8 生成的编码预览
const businessCodePreview = ref('');
const businessCodePrefixSource = ref('');
const codeContractHint = ref('');
const codeContractReady = ref(false);
const editingBusinessCode = ref('');
const editingDisplayCode = ref('');
const sp8PreviewLoading = ref(false);
const QUICK_CREATE_L3 = '__quick_create_l3__';
const createL3DialogVisible = ref(false);
const createL3Submitting = ref(false);
const createL3Form = ref({ label: '' });
const l3MatchHint = ref('');
const l3ManuallyEdited = ref(false);
const unitSuggestionHint = ref('');
interface TaxonomyCandidate {
  l1Code: string;
  l1Label: string;
  l2Code: string;
  l2Label: string;
  l3Code: string;
  l3Label: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  reason: string;
}
const taxonomyCandidates = ref<TaxonomyCandidate[]>([]);
let segmentTreeRequestVersion = 0;

async function loadSegmentTree() {
  if (!factoryId.value) return;
  const requestFactoryId = factoryId.value;
  const requestVersion = ++segmentTreeRequestVersion;
  segmentLoading.value = true;
  try {
    const res = await get<SegmentNode[]>(`/${requestFactoryId}/material-segments/tree`);
    if (requestVersion !== segmentTreeRequestVersion || requestFactoryId !== factoryId.value) return;
    if (res.success && Array.isArray(res.data)) {
      segmentTree.value = res.data;
    } else {
      segmentTree.value = [];
    }
  } catch {
    if (requestVersion === segmentTreeRequestVersion) segmentTree.value = [];
  } finally {
    if (requestVersion === segmentTreeRequestVersion) segmentLoading.value = false;
  }
}

// L1 options = top-level nodes
const segmentL1Options = computed(() =>
  segmentTree.value.filter((n) => n.level === 1 && n.isActive),
);
// 新建类别与列表大类共用此组选项，避免旧 MATERIAL_CATEGORY 枚举与编码类族漂移。
const materialFamilyOptions = computed(() =>
  segmentL1Options.value.map((node) => ({
    value: node.segmentLabel,
    label: node.segmentLabel,
    segmentCode: node.segmentCode,
  })),
);
const filterSegmentL2Options = computed(() => {
  if (!filterSegmentL1.value) return [];
  const l1Node = segmentTree.value.find((node) => node.segmentCode === filterSegmentL1.value);
  return l1Node?.children?.filter((node) => node.level === 2 && node.isActive) ?? [];
});
const filterSegmentL3Options = computed(() => {
  if (!filterSegmentL2.value) return [];
  for (const l1Node of segmentTree.value) {
    const l2Node = l1Node.children?.find((node) => node.segmentCode === filterSegmentL2.value);
    if (l2Node) return l2Node.children?.filter((node) => node.level === 3 && node.isActive) ?? [];
  }
  return [];
});

watch(filterSegmentL1, () => {
  filterSegmentL2.value = '';
  filterSegmentL3.value = '';
  pagination.value.page = 1;
  loadData();
});
watch(filterSegmentL2, () => {
  filterSegmentL3.value = '';
  pagination.value.page = 1;
  loadData();
});
watch(filterSegmentL3, () => {
  pagination.value.page = 1;
  loadData();
});

function resolveMaterialFamily(category: string | null | undefined): string | null {
  const raw = String(category || '').trim();
  if (!raw) return null;
  const exact = materialFamilyOptions.value.find((option) => option.value === raw);
  if (exact) return exact.value;

  // 历史枚举归并到当前三类 L1：主材→原料，辅材/添加剂/调味料→辅料，PACKAGING→包材。
  const bucket = bigCategoryOf(raw);
  const canonical = bucket === '调料' ? '辅料' : bucket;
  return materialFamilyOptions.value.find((option) => option.value === canonical)?.value ?? null;
}

function isMaterialFamily(category: string | null | undefined): boolean {
  return materialFamilyOptions.value.some((option) => option.value === category);
}

function syncMaterialFamilyFromCategory(category: string | null | undefined) {
  if (editingId.value) return;
  const family = resolveMaterialFamily(category);
  if (!family) {
    if (!String(category || '').trim()) segmentL1.value = '';
    return;
  }
  const option = materialFamilyOptions.value.find((item) => item.value === family);
  if (option && segmentL1.value !== option.segmentCode) segmentL1.value = option.segmentCode;
}

// The tree is fetched asynchronously. Re-run the category → L1 synchronization
// after its options arrive instead of leaving L2 disabled with a stale placeholder.
watch(materialFamilyOptions, () => {
  if (dialogVisible.value && !editingId.value) syncMaterialFamilyFromCategory(form.value.category);
});

function syncMaterialFamilyFromSegment(segmentCode: string) {
  if (editingId.value || !segmentCode) return;
  const option = materialFamilyOptions.value.find((item) => item.segmentCode === segmentCode);
  if (!option || form.value.category === option.value) return;
  cascadeWriting.value = true;
  try {
    form.value.category = option.value;
  } finally {
    cascadeWriting.value = false;
  }
}
// L2 options = children of selected L1
const segmentL2Options = computed(() => {
  if (!segmentL1.value) return [];
  const l1Node = segmentTree.value.find((n) => n.segmentCode === segmentL1.value);
  return l1Node?.children?.filter((c) => c.level === 2 && c.isActive) ?? [];
});
// L3 options = children of selected L2
const segmentL3Options = computed(() => {
  if (!segmentL2.value) return [];
  for (const l1 of segmentTree.value) {
    const l2Node = l1.children?.find((c) => c.segmentCode === segmentL2.value);
    if (l2Node) return l2Node.children?.filter((c) => c.level === 3 && c.isActive) ?? [];
  }
  return [];
});

// When L1 changes, reset L2/L3
watch(segmentL1, (segmentCode) => {
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
  resetCodeContractPreview();
  syncMaterialFamilyFromSegment(segmentCode);
});
watch(segmentL2, () => {
  segmentL3.value = '';
  segmentCodePreview.value = '';
  resetCodeContractPreview();
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
});

watch(segmentL3, (value) => {
  resetCodeContractPreview();
  if (dialogVisible.value && !editingId.value && /^\d{10}$/.test(value)) {
    void generateSP8Code(false);
  }
});

function nextL3Suffix(): string {
  const maxSuffix = segmentL3Options.value.reduce((max, node) => {
    const suffix = Number(node.segmentCode.slice(-4));
    return Number.isInteger(suffix) ? Math.max(max, suffix) : max;
  }, 0);
  return String(maxSuffix + 1).padStart(4, '0');
}
const nextL3Code = computed(() => (
  segmentL2.value ? `${segmentL2.value}${nextL3Suffix()}` : ''
));

function handleL3Change(value: string): void {
  if (value === QUICK_CREATE_L3) {
    segmentL3.value = '';
    // L3 is shared taxonomy, not the current purchasable MaterialType.
    // Never copy the material name into a new shared classification.
    createL3Form.value = { label: '' };
    createL3DialogVisible.value = true;
    return;
  }
  l3ManuallyEdited.value = true;
  l3MatchHint.value = '';
  taxonomyCandidates.value = [];
}

async function applyTaxonomyCandidate(candidate: TaxonomyCandidate, manual: boolean): Promise<void> {
  cascadeWriting.value = true;
  try {
    segmentL1.value = candidate.l1Code;
    await nextTick();
    segmentL2.value = candidate.l2Code;
    await nextTick();
    segmentL3.value = candidate.l3Code;
    l3ManuallyEdited.value = manual;
    taxonomyCandidates.value = [];
    l3MatchHint.value = `${manual ? '已确认分类' : '已根据名称智能匹配'}：${candidate.l1Label} > ${candidate.l2Label} > ${candidate.l3Label}；${candidate.reason}`;
  } finally {
    cascadeWriting.value = false;
  }
}

function rematchTaxonomy(): void {
  l3ManuallyEdited.value = false;
  segmentL3.value = '';
  l3MatchHint.value = '已允许重新匹配，请继续编辑名称或稍候系统重新推荐';
  suggestionRefreshToken.value += 1;
}

async function handleCreateL3(): Promise<void> {
  if (!canManageClassification.value) {
    ElMessage.error('没有共享分类维护权限，请联系系统管理员新增 L3 分类');
    createL3DialogVisible.value = false;
    return;
  }
  const label = createL3Form.value.label.trim();
  if (!segmentL2.value) { ElMessage.warning('请先选择 L2 中类'); return; }
  if (!label) { ElMessage.warning('请输入新品类名称'); return; }
  if (!factoryId.value) return;

  const normalizedLabel = label.toLocaleLowerCase();
  const existing = segmentL3Options.value.find(
    (node) => node.segmentLabel.trim().toLocaleLowerCase() === normalizedLabel,
  );
  if (existing) {
    segmentL3.value = existing.segmentCode;
    l3ManuallyEdited.value = true;
    createL3DialogVisible.value = false;
    ElMessage.success(`“${existing.segmentLabel}”已存在，已直接选中`);
    return;
  }

  createL3Submitting.value = true;
  try {
    const segmentCode = nextL3Code.value;
    const response = await post<SegmentNode>(`/${factoryId.value}/material-segments`, {
      level: 3,
      segmentCode,
      segmentLabel: label,
      parentCode: segmentL2.value,
      sortOrder: segmentL3Options.value.length,
      isActive: true,
    });
    if (!response.success || !response.data) {
      throw new Error(response.message || '创建 L3 小类失败');
    }
    await loadSegmentTree();
    segmentL3.value = response.data.segmentCode;
    l3ManuallyEdited.value = true;
    createL3DialogVisible.value = false;
    ElMessage.success(`已创建新品类「${response.data.segmentLabel}」`);
  } catch (error) {
    handleCatchError(error, '创建 L3 小类失败');
  } finally {
    createL3Submitting.value = false;
  }
}

let l3MatchTimer: ReturnType<typeof setTimeout> | undefined;
let l3MatchRequestVersion = 0;
watch(
  () => [form.value.name, segmentL1.value, segmentL2.value, dialogVisible.value, editingId.value] as const,
  ([name, _l1, l2, visible, currentEditingId]) => {
    const requestVersion = ++l3MatchRequestVersion;
    if (l3MatchTimer) clearTimeout(l3MatchTimer);
    l3MatchHint.value = '';
    const normalizedName = String(name || '').trim();
    if (!visible || currentEditingId || !l2 || normalizedName.length < 2 || l3ManuallyEdited.value) return;
    l3MatchTimer = setTimeout(async () => {
      try {
        const response = await get<{ content: TableRow[] }>(`/${factoryId.value}/raw-material-types`, {
          params: { page: 1, size: 20, codePrefix: l2, keyword: normalizedName },
        });
        if (requestVersion !== l3MatchRequestVersion) return;
        const rows = response.success && Array.isArray(response.data?.content) ? response.data.content : [];
        const query = normalizedName.toLocaleLowerCase();
        // Only an exact same-factory historic identity may be auto-selected.
        // Fuzzy results are exposed by the explainable candidate UI instead.
        const matched = rows.find((row) => String(row.name || '').trim().toLocaleLowerCase() === query);
        const matchedL3 = String(matched?.code || '').slice(0, 10);
        const option = segmentL3Options.value.find((node) => node.segmentCode === matchedL3);
        if (option && !l3ManuallyEdited.value) {
          segmentL3.value = option.segmentCode;
          l3MatchHint.value = `已按历史物料「${String(matched?.name || '')}」匹配 ${option.segmentLabel}`;
        }
      } catch {
        // 历史匹配失败不影响手工选择，也不虚构 L3。
      }
    }, 400);
  },
);

interface MaterialCodePreview {
  code: string;
  businessCode: string;
  businessCodePrefix: string;
  businessCodePrefixSource: 'CONFIGURED' | 'SYSTEM_STABLE';
  businessCodePrefixSourceSegment: string;
  classificationSegmentCode: string;
  selectable: boolean;
  guidance: string;
}

let codeContractRequestVersion = 0;

function resetCodeContractPreview(): void {
  segmentCodePreview.value = '';
  businessCodePreview.value = '';
  businessCodePrefixSource.value = '';
  codeContractHint.value = '';
  codeContractReady.value = false;
}

async function generateSP8Code(notifyError = true): Promise<boolean> {
  if (!segmentL1.value || !segmentL2.value || !segmentL3.value) {
    if (notifyError) ElMessage.warning('请先选择 L1大类、L2中类、L3小类后再生成编码');
    return false;
  }
  if (!factoryId.value) return false;
  const requestVersion = ++codeContractRequestVersion;
  const requestedSegment = segmentL3.value;
  sp8PreviewLoading.value = true;
  try {
    const res = await get<MaterialCodePreview>(
      `/${factoryId.value}/raw-material-types/preview-code`,
      {
        params: { category: form.value.category, segmentCode: segmentL3.value },
        _silent: !notifyError,
      },
    );
    if (requestVersion !== codeContractRequestVersion || requestedSegment !== segmentL3.value) {
      return false;
    }
    if (res.success && res.data?.code && res.data.businessCode && res.data.selectable) {
      segmentCodePreview.value = res.data.code;
      businessCodePreview.value = res.data.businessCode;
      businessCodePrefixSource.value = res.data.businessCodePrefixSource;
      codeContractHint.value = res.data.guidance;
      codeContractReady.value = true;
      return true;
    } else {
      resetCodeContractPreview();
      codeContractHint.value = res.message || '当前分类的编码契约不可用';
      if (notifyError) ElMessage.error(codeContractHint.value);
      return false;
    }
  } catch (error) {
    if (requestVersion !== codeContractRequestVersion || requestedSegment !== segmentL3.value) {
      return false;
    }
    resetCodeContractPreview();
    codeContractHint.value = '编码契约校验暂不可用，请稍后重试';
    if (notifyError) handleCatchError(error, codeContractHint.value);
    return false;
  } finally {
    if (requestVersion === codeContractRequestVersion) {
      sp8PreviewLoading.value = false;
    }
  }
}

// ==================== T159-A: *ManuallyEdited flags + cascadeWriting guard ====================
// Exact mirror of SKU's pattern:
//   - cascadeWriting=true while cascade writes → watchers skip marking as manual
//   - User direct edits → *ManuallyEdited=true → cascade will NOT overwrite that field
const cascadeWriting = ref(false);
const unitManuallyEdited = ref(false);
const categoryManuallyEdited = ref(false);
const storageTypeManuallyEdited = ref(false);
const shelfLifeManuallyEdited = ref(false);
const packagingManuallyEdited = ref(false); // covers level1PerLevel2 + level2Unit together

// Unit manual state is driven by UnitSelect's user-only change event. Watching
// v-model cannot distinguish form initialization from a real user action.
function handleUnitUserChange(value: string): void {
  if (cascadeWriting.value) return;
  unitManuallyEdited.value = true;
  unitSuggestionHint.value = '';
  form.value.unit = value;
}
watch(() => form.value.category, (category) => {
  if (!cascadeWriting.value) categoryManuallyEdited.value = true;
  syncMaterialFamilyFromCategory(category);
  if (!isPackagingCategory(category)) {
    form.value.associatedCustomerId = null;
    form.value.packQtyPerProduct = null;
  }
});
watch(() => form.value.storageType, () => {
  if (!cascadeWriting.value) storageTypeManuallyEdited.value = true;
});
watch(() => form.value.shelfLifeDays, () => {
  if (!cascadeWriting.value) shelfLifeManuallyEdited.value = true;
});
watch(() => packaging.value.level1PerLevel2, () => {
  if (!cascadeWriting.value) packagingManuallyEdited.value = true;
});
watch(() => packaging.value.level2Unit, () => {
  if (!cascadeWriting.value) packagingManuallyEdited.value = true;
});

function resetManuallyEditedFlags() {
  unitManuallyEdited.value = false;
  categoryManuallyEdited.value = false;
  storageTypeManuallyEdited.value = false;
  shelfLifeManuallyEdited.value = false;
  packagingManuallyEdited.value = false;
  unitSuggestionHint.value = '';
  taxonomyCandidates.value = [];
}

// clearCascadeFields: when name is cleared entirely, reset cascade-filled fields
// ONLY for fields the user hasn't manually edited
function clearCascadeFields() {
  cascadeWriting.value = true;
  try {
    if (!unitManuallyEdited.value) form.value.unit = 'kg';
    if (!categoryManuallyEdited.value) form.value.category = '';
    if (!storageTypeManuallyEdited.value) form.value.storageType = storageTypeOptions.value[0]?.enumLabel || '';
    if (!shelfLifeManuallyEdited.value) form.value.shelfLifeDays = null;
    if (!packagingManuallyEdited.value) {
      packaging.value.level1PerLevel2 = '';
      packaging.value.level2Unit = '';
    }
  } finally {
    cascadeWriting.value = false;
  }
}

// ==================== T159-A: Full smart-match cascade ====================
// Debounced watch: name (or name+category) change → call suggest endpoint → fill fields.
// null fields from suggest → leave as-is (don't overwrite with null).
// Endpoint 404/error → fallback to suggest-unit only (backward compat).
let suggestTimer: number | undefined;
let suggestRequestVersion = 0;
const suggestionRefreshToken = ref(0);
watch(
  () => [form.value.name, form.value.category, dialogVisible.value, editingId.value, suggestionRefreshToken.value] as const,
  ([name, _cat, visible, eid]) => {
    const requestVersion = ++suggestRequestVersion;
    if (!visible || eid) return; // edit mode: no cascade
    if (suggestTimer) clearTimeout(suggestTimer);
    const trimmedName = String(name || '').trim();

    // Name cleared → reset cascade fields (graceful)
    if (trimmedName.length === 0) {
      clearCascadeFields();
      return;
    }
    if (trimmedName.length < 2) return;

    suggestTimer = window.setTimeout(async () => {
      try {
        const params: Record<string, string> = { name: trimmedName };
        const cat = String(form.value.category || '').trim();
        if (cat) params.category = cat;

        // Try full suggest endpoint (T159-B-codegen provides this)
        const res = await get<{
          unit?: string | null;
          matchedMaterialName?: string | null;
          unitConfidence?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
          category?: string | null;
          storageType?: string | null;
          shelfLifeDays?: number | null;
          level1PerLevel2?: number | null;
          level2Unit?: string | null;
          segmentL1Code?: string | null;
          segmentL2Code?: string | null;
          segmentL3Code?: string | null;
          classificationConfidence?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
          classificationReason?: string | null;
          classificationCandidates?: TaxonomyCandidate[] | null;
        }>(`/${factoryId.value}/raw-material-types/suggest`, { params });
        if (requestVersion !== suggestRequestVersion) return;

        if (!res.success) {
          // Fallback: old suggest-unit endpoint (original behavior)
          const unitRes = await get<string>(
            `/${factoryId.value}/raw-material-types/suggest-unit`,
            { params },
          );
          if (unitRes.success && unitRes.data && !unitManuallyEdited.value) {
            cascadeWriting.value = true;
            try {
              form.value.unit = unitRes.data;
              unitSuggestionHint.value = `已根据同工厂相似原料智能匹配为「${displayUnit(unitRes.data)}」，可手动修改`;
            } finally { cascadeWriting.value = false; }
          }
          return;
        }

        const d = res.data;
        if (!d) return;

        const candidates = (d.classificationCandidates || []).slice(0, 3);
        if (l3ManuallyEdited.value) {
          taxonomyCandidates.value = [];
          l3MatchHint.value = '名称已变化，但分类已手动修改；如需采用新名称建议，请点击“重新匹配分类”';
        } else if (d.classificationConfidence === 'HIGH' && candidates[0]) {
          await applyTaxonomyCandidate(candidates[0], false);
        } else if (d.classificationConfidence === 'MEDIUM') {
          taxonomyCandidates.value = candidates;
          l3MatchHint.value = '找到可能的现有分类，请确认后再保存；系统不会自动创建分类';
        } else {
          taxonomyCandidates.value = [];
        }

        // Apply cascade — only fields user hasn't manually edited, skip null values
        cascadeWriting.value = true;
        try {
          if (d.unit != null && !unitManuallyEdited.value && d.unitConfidence !== 'LOW') {
            form.value.unit = d.unit;
            const source = d.matchedMaterialName ? `，参考相似原料「${d.matchedMaterialName}」` : '';
            unitSuggestionHint.value = `已根据同工厂相似原料智能匹配为「${displayUnit(d.unit)}」${source}，可手动修改`;
          }
          if (d.category != null && !categoryManuallyEdited.value) {
            const family = resolveMaterialFamily(d.category);
            if (family) form.value.category = family;
          }
          if (d.storageType != null && !storageTypeManuallyEdited.value) form.value.storageType = d.storageType;
          if (d.shelfLifeDays != null && !shelfLifeManuallyEdited.value) form.value.shelfLifeDays = d.shelfLifeDays;
          if (!packagingManuallyEdited.value) {
            if (d.level1PerLevel2 != null) packaging.value.level1PerLevel2 = d.level1PerLevel2;
            if (d.level2Unit != null) packaging.value.level2Unit = d.level2Unit;
          }
        } finally {
          cascadeWriting.value = false;
        }
      } catch {
        // silent — endpoint may not be deployed yet
      }
    }, 400);
  },
);

function resetPackaging() {
  packaging.value = { level1PerLevel2: '', level2Unit: '', level2PerLevel3: '', level3Unit: '' };
}

function openCreate() {
  editingId.value = null;
  form.value = {
    code: '',
    name: '',
    category: '',
    unit: 'kg',
    storageType: storageTypeOptions.value[0]?.enumLabel || '',
    shelfLifeDays: null,
    notes: '',
    taxTreatment: 'TAXABLE',
    taxRate: 'TAX_13',
    taxExemptionReason: '',
    taxIncludedUnitPrice: null,
    associatedCustomerId: null,
    packQtyPerProduct: null,
  };
  resetPackaging();
  resetManuallyEditedFlags();
  // SP8: reset cascade
  segmentL1.value = '';
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
  resetCodeContractPreview();
  editingBusinessCode.value = '';
  editingDisplayCode.value = '';
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
  if (segmentTree.value.length === 0) loadSegmentTree();
  loadCustomers();
  dialogVisible.value = true;
}

async function openEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  form.value = {
    code: String(row.code || ''),
    name: String(row.name || ''),
    category: String(row.category || ''),
    unit: String(row.unit || 'kg'),
    storageType: String(row.storageType || ''),
    shelfLifeDays: row.shelfLifeDays as number | null ?? null,
    notes: String(row.notes || ''),
    taxTreatment: row.taxTreatment === 'EXEMPT' ? 'EXEMPT' : 'TAXABLE',
    taxRate: String(row.taxRate || ''),
    taxExemptionReason: String(row.taxExemptionReason || ''),
    taxIncludedUnitPrice: (row.taxIncludedUnitPrice as number | null) ?? null,
    associatedCustomerId: (row.associatedCustomerId as string | null) ?? null,
    packQtyPerProduct: row.packQtyPerProduct != null ? Number(row.packQtyPerProduct) : null,
  };
  editingBusinessCode.value = String(row.businessCode || '').trim();
  editingDisplayCode.value = materialDisplayCode(row);
  loadCustomers();
  resetPackaging();
  resetManuallyEditedFlags();
  // SP8: reset cascade (edit mode — code already exists, cascade is create-only)
  segmentL1.value = '';
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
  resetCodeContractPreview();
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
  if (segmentTree.value.length === 0) loadSegmentTree();
  // 原料、辅料和包材共用计量/包装层级；库存基本单位始终来自 form.unit。
  try {
    const res = await get<{ level1PerLevel2: number | null; level2Unit: string | null; level2PerLevel3: number | null; level3Unit: string | null }>(
      `/${factoryId.value}/material-packaging/by-material/${editingId.value}`,
    );
    if (res.success && res.data) {
      packaging.value = {
        level1PerLevel2: res.data.level1PerLevel2 ?? '',
        level2Unit: res.data.level2Unit || '',
        level2PerLevel3: res.data.level2PerLevel3 ?? '',
        level3Unit: res.data.level3Unit || '',
      };
    }
  } catch { /* 未配置换算时正常空 */ }
  dialogVisible.value = true;
}

// ==================== T159-A: Packaging inline live preview (SKU-style) ====================
// 「1 [二级单位] = [换算数] [一级单位]」
const packagingL2Preview = computed(() => {
  const qty = Number(packaging.value.level1PerLevel2);
  const l1 = form.value.unit ? displayUnit(form.value.unit) : '主单位';
  const l2 = packaging.value.level2Unit;
  if (!l2 || !qty || qty <= 0) return '';
  return `1 ${displayUnit(l2)} = ${qty} ${l1}`;
});

const packagingL3Preview = computed(() => {
  const qty = Number(packaging.value.level2PerLevel3);
  const l2 = packaging.value.level2Unit ? displayUnit(packaging.value.level2Unit) : '二级单位';
  const l3 = packaging.value.level3Unit;
  if (!l3 || !qty || qty <= 0) return '';
  return `1 ${displayUnit(l3)} = ${qty} ${l2}`;
});

const submitting = ref(false);
const editingNeedsSegmentRepair = computed(() =>
  Boolean(editingId.value) && !/^\d{16}$/.test(String(form.value.code || '')),
);
const showSegmentEditor = computed(() => !editingId.value || editingNeedsSegmentRepair.value);
async function handleSave() {
  if (!form.value.name) return ElMessage.warning('请填写原料名称');
  if (!form.value.category) return ElMessage.warning('请选择类别');
  if (!form.value.unit) return ElMessage.warning('请选择单位');
  if (!isPackagingMaterial.value && !form.value.storageType) return ElMessage.warning('请选择储存类型');
  if (canViewPrice.value) {
    if (form.value.taxTreatment === 'TAXABLE' && !form.value.taxRate) return ElMessage.warning('请选择税率');
    if (form.value.taxTreatment === 'EXEMPT' && !form.value.taxExemptionReason.trim()) {
      return ElMessage.warning('免税物料必须填写免税依据');
    }
    if (form.value.taxIncludedUnitPrice != null && Number(form.value.taxIncludedUnitPrice) <= 0) {
      return ElMessage.warning('采购参考价如填写，必须大于 0；未知价格请留空');
    }
  }
  if (showSegmentEditor.value && (!segmentL1.value || !segmentL2.value || !segmentL3.value)) {
    return ElMessage.error('每个原料类型都必须选择 L1大类、L2中类、L3小类后保存');
  }
  if (!editingId.value && !(await generateSP8Code(true))) {
    return;
  }

  // 所有物料均可维护包装换算；库存基本单位仍以 form.unit 为唯一真值。
  const hasL2Unit = !!packaging.value.level2Unit?.trim();
  const hasL2Qty = packaging.value.level1PerLevel2 !== '' && Number(packaging.value.level1PerLevel2) > 0;
  const hasL3Unit = !!packaging.value.level3Unit?.trim();
  const hasL3Qty = packaging.value.level2PerLevel3 !== '' && Number(packaging.value.level2PerLevel3) > 0;
  if (hasL2Unit !== hasL2Qty) return ElMessage.warning('二级单位和换算数量必须同时填写或同时清空');
  if (hasL3Unit !== hasL3Qty) return ElMessage.warning('三级单位和换算数量必须同时填写或同时清空');
  if (hasL3Unit && !hasL2Unit) return ElMessage.warning('必须先配置二级单位才能配置三级');

  submitting.value = true;
  try {
    let materialId: string;
    const materialPayload: Record<string, unknown> = { ...form.value };
    delete materialPayload.code;
    if (!canViewPrice.value) {
      delete materialPayload.taxTreatment;
      delete materialPayload.taxRate;
      delete materialPayload.taxExemptionReason;
      delete materialPayload.taxIncludedUnitPrice;
    }
    if (isPackagingMaterial.value) {
      delete materialPayload.storageType;
    } else {
      delete materialPayload.associatedCustomerId;
      delete materialPayload.packQtyPerProduct;
    }
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/raw-material-types/${editingId.value}`, {
        ...materialPayload,
        segmentCode: editingNeedsSegmentRepair.value ? segmentL3.value : undefined,
      });
      if (!res.success) throw new Error(res.message || '更新失败');
      materialId = editingId.value;
      ElMessage.success('更新成功');
    } else {
      // 创建: 不传 code 让后端自动生成
      const payload = {
        ...materialPayload,
        segmentCode: segmentL3.value || undefined,
      };
      const res = await post<{ id: string }>(`/${factoryId.value}/raw-material-types`, payload);
      if (!res.success) throw new Error(res.message || '创建失败');
      materialId = res.data?.id || '';
      ElMessage.success('创建成功');
    }

    // 计量/包装层级 upsert / delete
    if (hasL2Unit || hasL3Unit) {
      await put(`/${factoryId.value}/material-packaging/by-material/${materialId}`, {
        level1Unit: form.value.unit,
        level1PerLevel2: hasL2Unit ? Number(packaging.value.level1PerLevel2) : null,
        level2Unit: hasL2Unit ? packaging.value.level2Unit.trim() : null,
        level2PerLevel3: hasL3Unit ? Number(packaging.value.level2PerLevel3) : null,
        level3Unit: hasL3Unit ? packaging.value.level3Unit.trim() : null,
      });
    } else if (editingId.value) {
      // 编辑模式下用户清空了二三级 → 删除现有配置
      try { await del(`/${factoryId.value}/material-packaging/by-material/${materialId}`); }
      catch { /* 不存在也 OK */ }
    }

    dialogVisible.value = false;
    loadData();
  } catch (e) {
    console.error(e);
    handleCatchError(e, '原料类型保存失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

// ==================== SupplierMaterial 双向关系 ====================
// 原料侧与供应商详情共用同一 SupplierMaterial 关系实体/API；采购历史不是关系配置真值。
const suppliersDialogVisible = ref(false);
const suppliersForMaterial = ref<SupplierMaterialRelation[]>([]);
const suppliersLoading = ref(false);
const suppliersDialogMaterialName = ref('');
const suppliersDialogMaterial = ref<{ id: string; name: string; code?: string; unit?: string } | null>(null);
const activeSupplierOptions = ref<SupplierRecord[]>([]);
const relationDialogVisible = ref(false);
const relationEditing = ref<SupplierMaterialRelation | null>(null);
const relationSaving = ref(false);
const relationForm = reactive({
  supplierId: '', supplierMaterialCode: '', purchaseUnit: '', defaultPurchasePrice: null as number | null,
  currency: 'CNY', minOrderQuantity: null as number | null, leadTimeDays: null as number | null,
  preferred: false, active: true,
});

const availableSupplierOptions = computed(() => {
  const linked = new Set(suppliersForMaterial.value.map((row) => String(row.supplierId || '')));
  return activeSupplierOptions.value.filter((supplier) => !linked.has(String(supplier.id)));
});

async function reloadMaterialSuppliers(): Promise<void> {
  const material = suppliersDialogMaterial.value;
  if (!material) return;
  suppliersForMaterial.value = await listMaterialSuppliers(factoryId.value, material.id);
}

async function openSuppliersForMaterial(row: TableRow) {
  const materialId = String(row.id || '').trim();
  const materialName = String(row.name || '').trim();
  if (!materialId || !materialName) {
    ElMessage.warning('物料身份不完整，无法查询关联供应商');
    return;
  }
  suppliersDialogMaterialName.value = materialName;
  suppliersDialogMaterial.value = {
    id: materialId,
    name: materialName,
    code: String(row.businessCode || row.displayCode || row.code || ''),
    unit: String(row.unit || ''),
  };
  suppliersForMaterial.value = [];
  suppliersDialogVisible.value = true;
  suppliersLoading.value = true;
  try {
    await reloadMaterialSuppliers();
  } catch {
    /* interceptor */
  } finally {
    suppliersLoading.value = false;
  }
}

async function ensureActiveSupplierOptions(): Promise<void> {
  activeSupplierOptions.value = await listActiveSuppliers(factoryId.value);
}

async function openMaterialRelationCreate(): Promise<void> {
  if (!canManageSupplierRelations.value) return;
  await ensureActiveSupplierOptions();
  relationEditing.value = null;
  Object.assign(relationForm, {
    supplierId: '', supplierMaterialCode: '', purchaseUnit: suppliersDialogMaterial.value?.unit || '',
    defaultPurchasePrice: null, currency: 'CNY', minOrderQuantity: null, leadTimeDays: null,
    preferred: false, active: true,
  });
  relationDialogVisible.value = true;
}

async function openMaterialRelationEdit(row: SupplierMaterialRelation): Promise<void> {
  if (!canManageSupplierRelations.value) return;
  await ensureActiveSupplierOptions();
  relationEditing.value = row;
  Object.assign(relationForm, {
    supplierId: String(row.supplierId || ''),
    supplierMaterialCode: row.supplierMaterialCode || '',
    purchaseUnit: row.purchaseUnit || row.baseUnit || suppliersDialogMaterial.value?.unit || '',
    defaultPurchasePrice: row.defaultPurchasePrice ?? null,
    currency: row.currency || 'CNY',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
    preferred: Boolean(row.preferred),
    active: row.active !== false,
  });
  relationDialogVisible.value = true;
}

async function saveMaterialRelation(): Promise<void> {
  const material = suppliersDialogMaterial.value;
  if (!material) return;
  if (!relationForm.supplierId) return void ElMessage.warning('请选择供应商');
  if (!relationForm.purchaseUnit) return void ElMessage.warning('请选择采购单位');
  if (!relationForm.currency.trim()) return void ElMessage.warning('请输入币种');
  const payload: SupplierMaterialPayload = {
    materialTypeId: material.id,
    supplierMaterialCode: relationForm.supplierMaterialCode.trim() || undefined,
    purchaseUnit: relationForm.purchaseUnit,
    defaultPurchasePrice: relationForm.defaultPurchasePrice,
    currency: relationForm.currency.trim().toUpperCase(),
    minOrderQuantity: relationForm.minOrderQuantity,
    leadTimeDays: relationForm.leadTimeDays,
    preferred: relationForm.preferred,
    active: relationForm.active,
    version: relationEditing.value?.version ?? undefined,
  };
  relationSaving.value = true;
  try {
    if (relationEditing.value) {
      await updateSupplierMaterial(factoryId.value, relationForm.supplierId, relationEditing.value.id, payload);
    } else {
      await createSupplierMaterial(factoryId.value, relationForm.supplierId, payload);
    }
    await reloadMaterialSuppliers();
    relationDialogVisible.value = false;
    ElMessage.success('供应商—物料关系已保存');
  } finally {
    relationSaving.value = false;
  }
}

async function deactivateMaterialRelation(row: SupplierMaterialRelation): Promise<void> {
  const supplierId = String(row.supplierId || '');
  if (!supplierId) return void ElMessage.warning('供应商身份缺失，无法停用关系');
  await ElMessageBox.confirm(
    `确定停用「${row.supplierName || supplierId}」与当前物料的供应关系吗？历史采购记录不会被删除。`,
    '停用供应关系',
    { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
  );
  await deleteSupplierMaterial(factoryId.value, supplierId, row.id, row.version);
  await reloadMaterialSuppliers();
  ElMessage.success('供应关系已停用');
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除原料类型「${row.name}」? 该原料关联的批次仍保留, 但无法新建新批次.`,
      '删除确认',
      { type: 'warning' },
    );
    const res = await del(`/${factoryId.value}/raw-material-types/${row.id}`);
    if (res.success) {
      ElMessage.success('删除成功');
      loadData();
    }
  } catch { /* user cancelled or interceptor toasted */ }
}

// 关键字过滤是纯客户端 (filteredData computed 已实时响应 searchKeyword), 敲字/清空即生效,
// 不需要等 Enter/点搜索按钮才刷新 — 这里只需把页码归 1 (换关键字后从第一页开始看结果)。
watch(searchKeyword, () => {
  pagination.value.page = 1;
});

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  searchKeyword.value = '';
  filterSegmentL1.value = '';
  filterSegmentL2.value = '';
  filterSegmentL3.value = '';
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
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="原料类型字典"
      here="原料的「分类抽象」（如「冻猪蹄」「吸塑盒2014-3.5」），定义后才能在采购订单 / 入库登记 / BOM 里被选择"
      other-name="仓储管理 → 原料 / 物料 (采购入库)"
      other="原料的「具体批次」，记录某次入库的数量、价格、保质期"
      other-path="/warehouse/materials"
      consequence="先在这里建原料类型, 再去入库登记里给它建批次"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">原料类型字典</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
              新建原料类型
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <!-- 分类层级筛选：分类名称为主，稳定分类码仅作为次级识别信息。 -->
        <el-select
          v-model="filterSegmentL1"
          placeholder="全部 L1 大类"
          clearable
          style="width: 170px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in materialFamilyOptions"
            :key="opt.segmentCode"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.segmentCode"
          />
        </el-select>
        <el-select
          v-model="filterSegmentL2"
          placeholder="全部 L2 中类"
          clearable
          filterable
          :disabled="!filterSegmentL1"
          style="width: 190px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in filterSegmentL2Options"
            :key="opt.segmentCode"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.segmentCode"
          />
        </el-select>
        <el-select
          v-model="filterSegmentL3"
          placeholder="全部 L3 小类"
          clearable
          filterable
          :disabled="!filterSegmentL2"
          style="width: 220px"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in filterSegmentL3Options"
            :key="opt.segmentCode"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.segmentCode"
          />
        </el-select>
        <el-input
          v-model="searchKeyword"
          placeholder="搜索原料名称 / 业务编码 / 历史编码"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" stripe>
        <el-table-column label="业务编码" min-width="180">
          <template #default="{ row }">
            <div class="material-code-cell">
              <span class="material-code-value">{{ materialDisplayCode(row) }}</span>
              <el-tag v-if="!materialHasBusinessCode(row)" size="small" type="info">历史编码</el-tag>
              <el-tooltip
                v-else-if="row.code && String(row.code) !== materialDisplayCode(row)"
                :content="`历史兼容编码：${row.code}`"
                placement="top"
              >
                <el-tag size="small" type="info" effect="plain">兼容码</el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="原料名称" min-width="180" />
        <el-table-column prop="category" label="类别" width="120" />
        <el-table-column prop="unit" label="单位" width="80">
          <template #default="{ row }">{{ displayUnit(row.unit) }}</template>
        </el-table-column>
        <el-table-column prop="storageType" label="储存类型" width="100" />
        <el-table-column prop="shelfLifeDays" label="保质期 (天)" width="120">
          <template #default="{ row }">{{ row.shelfLifeDays ?? '-' }}</template>
        </el-table-column>
        <!-- T2-5b (issue #534): F006 客户反馈 — expose 移动均价 (RawMaterialTypeDTO.movingAvgPrice)
             gated by canViewPrice RBAC (per PR #443/#467 price-field policy) -->
        <el-table-column v-if="canViewPrice" prop="movingAvgPrice" label="移动均价" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.movingAvgPrice != null">{{ formatAmount(row.movingAvgPrice) }}</span>
            <span v-else style="color: #c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <!-- Issue #779: 反查供应商入口 -->
            <el-button link type="primary" :icon="View" @click="openSuppliersForMaterial(row)">供应商</el-button>
            <el-button v-if="canWrite" link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" link type="danger" :icon="DeleteIcon" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <!-- ==================== T159-A: Create / Edit Dialog ==================== -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="660px" destroy-on-close>
      <el-form :model="form" label-width="120px">

        <!-- 业务编码为用户主视图；旧16位编码仅保留兼容和分类修复用途。 -->
        <el-form-item v-if="editingId" label="业务编码">
          <el-input :model-value="editingDisplayCode" disabled :prefix-icon="Lock" />
          <div v-if="editingBusinessCode" class="field-hint">
            业务编码不可修改；历史兼容编码可在列表“兼容码”提示中查看。
          </div>
          <div v-else class="field-hint field-hint--legacy">
            该历史记录尚未分配业务编码，当前回退显示原16位编码。
          </div>
        </el-form-item>
        <el-form-item v-else label="业务编码">
          <span class="code-preview-hint">选择完整 L1-L3 分类后，系统将在下方生成并优先展示短业务编码。</span>
        </el-form-item>

        <el-form-item label="原料名称" required>
          <el-input v-model="form.name" placeholder="如 冻猪蹄 / 三文鱼 / 吸塑盒2014-3.5" />
          <div class="field-hint">输入名称后自动匹配历史同类原料，智能填充单位 / 类别 / 保质期 / 包装换算</div>
        </el-form-item>

        <el-form-item label="类别" required>
          <el-select v-model="form.category" placeholder="请选择类别" style="width: 100%" filterable>
            <el-option
              v-if="editingId && form.category && !isMaterialFamily(form.category)"
              :label="`${form.category} (历史)`"
              :value="form.category"
              disabled
            />
            <el-option
              v-for="opt in materialFamilyOptions"
              :key="opt.segmentCode"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="field-hint">与 16 位物料编码字典的 L1 类族保持一致</div>
        </el-form-item>

        <!-- 单位统一入口：搜索不到时可现场创建，重复时直接选择已有单位 -->
        <el-form-item label="入库计量单位" required>
          <UnitSelect
            v-model="form.unit"
            :factory-id="factoryId"
            usage-scope="INVENTORY_QUANTITY"
            :show-symbol="false"
            placeholder="请选择或搜索入库计量单位"
            :clearable="false"
            @change="handleUnitUserChange"
          />
          <div class="field-hint">新建默认 kg（公斤），可按实际入库计量单位修改</div>
          <div v-if="unitSuggestionHint && !unitManuallyEdited && !editingId" class="field-hint field-hint--matched">
            {{ unitSuggestionHint }}
          </div>
          <div v-if="unitManuallyEdited && !editingId" class="field-hint field-hint--manual">
            已手动设置，自动填充将不再覆盖此字段
          </div>
        </el-form-item>

        <el-form-item v-if="!isPackagingMaterial" label="储存类型" required>
          <el-select v-model="form.storageType" placeholder="请选择储存类型" style="width: 100%">
            <el-option
              v-for="opt in mergeHistoricStorage(form.storageType)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="保质期 (天)">
          <el-input-number v-model="form.shelfLifeDays" :min="0" style="width: 100%" />
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
        </el-form-item>

        <!-- SP4: 税率 + 含税/未税单价 (canViewPrice 门控) -->
        <template v-if="canViewPrice">
          <el-form-item label="计税方式" required>
            <el-radio-group v-model="form.taxTreatment">
              <el-radio value="TAXABLE">正常计税</el-radio>
              <el-radio value="EXEMPT">免税</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.taxTreatment === 'TAXABLE'" label="采购税率" required>
            <el-select v-model="form.taxRate" placeholder="请选择税率" style="width: 100%">
              <el-option label="9% (农产品等)" value="TAX_9" />
              <el-option label="13% (标准税率)" value="TAX_13" />
            </el-select>
          </el-form-item>
          <el-form-item v-else label="免税依据" required>
            <el-input v-model="form.taxExemptionReason" placeholder="填写政策、票据或业务依据" maxlength="255" />
          </el-form-item>
          <el-form-item :label="form.taxTreatment === 'EXEMPT' ? `免税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）` : `含税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`">
            <el-input-number
              v-model="form.taxIncludedUnitPrice"
              :min="0.0001"
              :precision="4"
              :controls="false"
              placeholder="选填；未知价格请留空"
              style="width: 100%"
            />
            <div class="field-hint">价格按库存主单位维护，并可作为供应关系未单独报价时的采购参考；空值不会被当作 0 元。</div>
          </el-form-item>
          <el-form-item v-if="form.taxIncludedUnitPrice != null && (form.taxRate || form.taxTreatment === 'EXEMPT')" :label="`未税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`">
            <el-input
              :model-value="preTaxUnitPrice != null ? preTaxUnitPrice.toFixed(4) : '—'"
              disabled
              style="width: 100%"
            />
            <div class="field-hint">{{ form.taxTreatment === 'EXEMPT' ? '免税时未税价等于采购参考价' : '= 含税采购参考价 ÷ (1 + 税率)，自动计算' }}</div>
          </el-form-item>
        </template>

        <!-- 包材专属字段只在包材建档时显示和提交。 -->
        <template v-if="isPackagingMaterial">
          <el-divider>
            <span class="divider-title">包材专属字段（选填）</span>
          </el-divider>
          <el-form-item label="关联固定客户">
          <el-select
            v-model="form.associatedCustomerId"
            placeholder="该包材专供某客户（可选）"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
            <!-- fool-proof-design Rule 5: 拉不到客户不能只显空白"无数据", 给明确下一步 -->
            <template #empty>
              <div style="padding: 8px 12px">
                <UpstreamMissingHint
                  v-if="customerOptionsLoaded"
                  description="本工厂暂无客户"
                  target-module="sales"
                  action-text="去添加客户"
                  contact-text="请联系销售或管理员先添加客户"
                  @action="goCreate('/sales/customers')"
                />
                <span v-else style="color: #909399; font-size: 12px">加载中…</span>
              </div>
            </template>
          </el-select>
          <div class="field-hint">选填 — 如吸塑盒专供某客户，留空表示通用包材</div>
          </el-form-item>
        <!-- #759: 每成品单位用量 (仅 PACKAGING 类型显示，后端自动推 BOM standardQuantity) -->
          <el-form-item label="每产品用量">
          <el-input-number
            v-model="form.packQtyPerProduct"
            :min="0"
            :precision="6"
            :step="0.1"
            style="width: 100%"
            placeholder="每个成品单位需要该包材多少个"
          />
          <div class="field-hint">
            例：吸塑盒 1 个/成品填 1；外箱 20 盒/箱填 0.05（=1/20）。留空则 BOM 行需手填用量
          </div>
          </el-form-item>
        </template>

        <!-- 物料分类 + 双码契约：业务编码主显示，16位编码仅用于历史兼容。 -->
        <!-- SP8 兜底 (Tier0 #15 minimal): 字典未配置时隐藏级联入口防 dead-end (fool-proof Rule 5).
             generate-code 端点 P1 上线; 当前 tree 为空时显示诚实空态而非空下拉组合. -->
        <el-divider v-if="showSegmentEditor">
          <span class="divider-title">物料分类与业务编码（必填）</span>
        </el-divider>
        <template v-if="showSegmentEditor">
          <!-- 字典已配置: 展示完整级联 -->
          <template v-if="segmentL1Options.length > 0 || segmentLoading">
            <el-form-item label="L1 大类" required>
              <el-select
                v-model="segmentL1"
                placeholder="请选择物料大类"
                clearable
                filterable
                style="width: 100%"
                :loading="segmentLoading"
              >
                <el-option
                  v-for="opt in segmentL1Options"
                  :key="opt.segmentCode"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.segmentCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="L2 中类" required>
              <el-select
                v-model="segmentL2"
                :placeholder="segmentL1 ? '请选择 L2 中类' : '请先选择 L1 大类'"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL1"
              >
                <el-option
                  v-for="opt in segmentL2Options"
                  :key="opt.segmentCode"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.segmentCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="L3 小类" required>
              <el-select
                v-model="segmentL3"
                placeholder="请先选择 L2 中类"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL2"
                @change="handleL3Change"
              >
                <el-option
                  v-if="canManageClassification"
                  :key="QUICK_CREATE_L3"
                  label="＋ 新建共享 L3 分类"
                  :value="QUICK_CREATE_L3"
                />
                <el-option
                  v-for="opt in segmentL3Options"
                  :key="opt.segmentCode"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.segmentCode"
                />
              </el-select>
              <div class="field-hint">L3 是多个具体原料共用的稳定分类（如“鱼类原料”）；当前原料名称、规格、单位等仍维护在原料类型中。</div>
              <div v-if="!canManageClassification" class="field-hint">没有共享分类维护权限；如无合适分类，请联系系统管理员在“物料编码分类”中新增。</div>
              <div v-if="l3MatchHint" class="field-hint field-hint--matched">{{ l3MatchHint }}</div>
              <div v-if="taxonomyCandidates.length" class="taxonomy-candidates" aria-label="分类建议">
                <el-button
                  v-for="candidate in taxonomyCandidates"
                  :key="candidate.l3Code"
                  size="small"
                  plain
                  @click="applyTaxonomyCandidate(candidate, true)"
                >
                  {{ candidate.l1Label }} &gt; {{ candidate.l2Label }} &gt; {{ candidate.l3Label }}
                </el-button>
              </div>
              <el-button
                v-if="l3ManuallyEdited && l3MatchHint.includes('名称已变化')"
                link
                type="primary"
                @click="rematchTaxonomy"
              >重新匹配分类</el-button>
            </el-form-item>
            <el-form-item v-if="segmentL1 && segmentL2 && segmentL3" label="编码预览">
              <div class="code-preview-stack">
                <div class="code-preview-row">
                  <span class="code-preview-label">业务编码</span>
                  <el-tag v-if="businessCodePreview" type="success" class="code-preview-tag">
                    {{ businessCodePreview }}
                  </el-tag>
                  <span v-else class="field-hint">正在校验编码契约…</span>
                </div>
                <div class="code-preview-row">
                  <span class="code-preview-label">历史兼容编码（16位）</span>
                  <el-tag v-if="segmentCodePreview" type="info" class="code-preview-tag">
                    {{ segmentCodePreview }}
                  </el-tag>
                </div>
                <el-button
                  size="small"
                  :loading="sp8PreviewLoading"
                  @click="generateSP8Code(true)"
                >
                  刷新预览
                </el-button>
              </div>
              <div v-if="codeContractHint" class="field-hint" :class="{ 'field-hint--matched': codeContractReady }">
                {{ codeContractHint }}
                <template v-if="businessCodePrefixSource === 'SYSTEM_STABLE'">；不会按分类名称猜测或覆盖历史前缀</template>
              </div>
            </el-form-item>
          </template>
          <!-- 字典未配置: 诚实空态 + 跳转配置引导 (fool-proof Rule 5: dead-end 改导航) -->
          <el-form-item v-else label="">
            <el-alert
              title="物料分类编码字典尚未配置，暂不可用"
              type="info"
              :closable="false"
              show-icon
              style="width: 100%"
            >
              <template #default>
                <div style="font-size:12px;margin-top:4px;color:#606266">
                   请先配置完整的 L1-L3 物料编码字典；配置完成前不能新建原料类型。
                </div>
              </template>
            </el-alert>
          </el-form-item>
        </template>

        <!-- ==================== T159-A: 计量/包装层级 内联换算行 (SKU-style) ==================== -->
        <template v-if="form.unit">
          <el-divider>
            <span class="divider-title">采购与库存单位换算（可选）</span>
          </el-divider>
          <el-alert
            title="库存始终按上方基本单位记账；采购可按箱、袋、桶等包装单位下单"
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 14px"
          >
            <template #default>例如：基本单位选 kg，再配置 1 箱 = 10 kg。采购 8 箱时，系统按快照折算为 80 kg 入库。</template>
          </el-alert>

        <!-- 一级: 显示主单位 (read-only echo — single source of truth = 上方单位字段) -->
          <el-form-item label="一级 (主单位)">
          <div class="packaging-inline-row">
            <el-tag type="info" class="unit-tag">{{ form.unit ? displayUnit(form.unit) : '请先填单位' }}</el-tag>
            <span class="packaging-equals-hint">← 同「单位」字段（主数据 canonical 单位，不可单独更改）</span>
          </div>
          </el-form-item>

        <!-- 二级换算: SKU-style inline row -->
        <!-- 布局: 1 [二级单位 select] = [换算数] [一级单位 tag] -->
          <el-form-item label="二级换算">
          <div class="packaging-conversion-row">
            <span class="conversion-label">1</span>
            <UnitSelect
              v-model="packaging.level2Unit"
              :factory-id="factoryId"
              usage-scope="PURCHASE_QUANTITY"
              placeholder="二级单位（如箱）"
              style="width: 155px"
            />
            <span class="conversion-equals">=</span>
            <el-input-number
              v-model="packaging.level1PerLevel2"
              :min="0"
              :controls="false"
              placeholder="换算数"
              style="width: 100px"
            />
            <el-tag type="info" class="unit-tag-echo">{{ form.unit ? displayUnit(form.unit) : '主单位' }}</el-tag>
          </div>
          <!-- Live preview summary (SKU-style) -->
          <div v-if="packagingL2Preview" class="packaging-preview">
            <el-tag size="small" type="success">{{ packagingL2Preview }}</el-tag>
          </div>
          <div
            v-else-if="(packaging.level2Unit || packaging.level1PerLevel2)"
            class="packaging-preview packaging-preview--warn"
          >
            请同时填写二级单位和换算数量
          </div>
          </el-form-item>

        <!-- 三级换算 -->
          <el-form-item label="三级换算">
          <div class="packaging-conversion-row">
            <span class="conversion-label">1</span>
            <UnitSelect
              v-model="packaging.level3Unit"
              :factory-id="factoryId"
              usage-scope="PURCHASE_QUANTITY"
              placeholder="三级单位（如柜）"
              style="width: 155px"
              :disabled="!packaging.level2Unit"
            />
            <span class="conversion-equals">=</span>
            <el-input-number
              v-model="packaging.level2PerLevel3"
              :min="0"
              :controls="false"
              placeholder="换算数"
              style="width: 100px"
              :disabled="!packaging.level2Unit"
            />
            <el-tag type="info" class="unit-tag-echo">{{ packaging.level2Unit ? displayUnit(packaging.level2Unit) : '二级单位' }}</el-tag>
          </div>
          <!-- Live preview summary -->
          <div v-if="packagingL3Preview" class="packaging-preview">
            <el-tag size="small" type="success">{{ packagingL3Preview }}</el-tag>
          </div>
          <div v-if="!packaging.level2Unit" class="field-hint">
            请先配置二级单位才能配置三级
          </div>
          </el-form-item>
        </template>

      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="createL3DialogVisible"
      title="新建共享 L3 分类"
      width="460px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert
        title="这里创建的是可被多个原料复用的共享分类，不是当前具体原料"
        description="例如 L3 可填写“鱼类原料”或“鸡胸肉”；具体规格、供应商形态和包装信息请填写在原料类型中。系统不会复制当前原料名称。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <el-form label-width="110px">
        <el-form-item label="所属 L2 中类">
          <el-input :model-value="segmentL2" disabled />
        </el-form-item>
        <el-form-item label="系统编码">
          <el-input :model-value="nextL3Code" disabled />
          <div class="field-hint">系统已检查当前 L2 下的编码并自动生成，无需手工填写。</div>
        </el-form-item>
        <el-form-item label="小类名称" required>
          <el-input v-model="createL3Form.label" maxlength="100" placeholder="例如：鱼类原料 / 鸡胸肉 / 箱类包材" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createL3DialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createL3Submitting" @click="handleCreateL3">创建并选中</el-button>
      </template>
    </el-dialog>

    <!-- SupplierMaterial 双向关系：原料侧维护入口 -->
    <el-dialog
      v-model="suppliersDialogVisible"
      :title="`${suppliersDialogMaterialName} — 供应商关系`"
      width="980px"
      destroy-on-close
    >
      <div class="supplier-relation-toolbar">
        <span>一个物料可关联多个供应商；关系价格与采购单位将直接用于采购订单。</span>
        <el-button
          v-if="canManageSupplierRelations"
          type="primary"
          :icon="Plus"
          @click="openMaterialRelationCreate"
        >关联供应商</el-button>
      </div>
      <el-table
        v-loading="suppliersLoading"
        :data="suppliersForMaterial"
        empty-text="该物料暂无供应商关系"
        stripe
        size="small"
      >
        <el-table-column prop="supplierName" label="供应商" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column prop="supplierMaterialCode" label="供应商料号" width="130">
          <template #default="{ row }">{{ row.supplierMaterialCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="采购单位" width="100">
          <template #default="{ row }">{{ displayUnit(row.purchaseUnit || row.baseUnit) }}</template>
        </el-table-column>
        <el-table-column label="默认采购价" width="155" align="right">
          <template #default="{ row }">
            {{ row.defaultPurchasePrice == null ? '未配置' : `${row.currency || 'CNY'} ${Number(row.defaultPurchasePrice).toFixed(2)}/${displayUnit(row.purchaseUnit || row.baseUnit)}` }}
          </template>
        </el-table-column>
        <el-table-column label="最小起订量" width="120" align="right">
          <template #default="{ row }">{{ row.minOrderQuantity ?? '-' }} {{ displayUnit(row.purchaseUnit || row.baseUnit) }}</template>
        </el-table-column>
        <el-table-column label="交期" width="90">
          <template #default="{ row }">{{ row.leadTimeDays == null ? '-' : `${row.leadTimeDays}天` }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active === false ? 'info' : 'success'" size="small">
              {{ row.active === false ? '已停用' : row.preferred ? '首选' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canManageSupplierRelations" label="操作" width="125" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openMaterialRelationEdit(row)">编辑</el-button>
            <el-button v-if="row.active !== false" type="warning" link @click="deactivateMaterialRelation(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="suppliersDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="relationDialogVisible"
      :title="relationEditing ? '编辑供应商—物料关系' : '关联供应商'"
      width="620px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-width="145px">
        <el-form-item label="供应商" required>
          <el-select v-model="relationForm.supplierId" filterable :disabled="Boolean(relationEditing)" style="width: 100%" placeholder="请选择当前工厂的合作中供应商">
            <el-option
              v-if="relationEditing"
              :label="relationEditing.supplierName || relationEditing.supplierId"
              :value="relationEditing.supplierId"
            />
            <el-option
              v-for="supplier in availableSupplierOptions"
              :key="supplier.id"
              :label="`${supplier.name}${supplier.supplierCode || supplier.code ? ` (${supplier.supplierCode || supplier.code})` : ''}`"
              :value="supplier.id"
            />
          </el-select>
          <div class="field-hint">已关联的供应商不会再次出现，后端唯一约束同时阻止重复关系。</div>
        </el-form-item>
        <el-form-item label="物料">
          <el-input :model-value="`${suppliersDialogMaterial?.name || '-'} (${suppliersDialogMaterial?.code || suppliersDialogMaterial?.id || '-'})`" disabled />
        </el-form-item>
        <el-form-item label="供应商料号"><el-input v-model="relationForm.supplierMaterialCode" maxlength="100" /></el-form-item>
        <el-form-item label="采购单位" required>
          <UnitSelect
            v-model="relationForm.purchaseUnit"
            :factory-id="factoryId"
            usage-scope="PURCHASE_QUANTITY"
            placeholder="请选择采购单位"
          />
          <div class="field-hint">采购订单沿用此单位；页面显示中文，API 保存标准单位代码。</div>
        </el-form-item>
        <el-form-item :label="`默认采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`">
          <el-input-number v-model="relationForm.defaultPurchasePrice" :min="0" :precision="4" controls-position="right" style="width: 100%" />
          <div class="field-hint">价格与采购单位绑定；未配置时采购单明确提示，不会静默变成 0 元。</div>
        </el-form-item>
        <el-form-item label="币种" required><el-input v-model="relationForm.currency" maxlength="3" /></el-form-item>
        <el-form-item label="最小起订量"><el-input-number v-model="relationForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 100%" /></el-form-item>
        <el-form-item label="交期（天）"><el-input-number v-model="relationForm.leadTimeDays" :min="0" :precision="0" style="width: 100%" /></el-form-item>
        <el-form-item label="首选供应商"><el-switch v-model="relationForm.preferred" /></el-form-item>
        <el-form-item label="关系启用"><el-switch v-model="relationForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="relationSaving" @click="saveMaterialRelation">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrapper { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-left { display: flex; align-items: baseline; gap: 12px; }
.page-title { font-size: 18px; font-weight: 600; }
.data-count { font-size: 13px; color: #909399; }
.search-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.supplier-relation-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; color: #606266; font-size: 13px; }
.divider-title { font-size: 14px; color: #606266; font-weight: 500; }

/* T159-A: Code preview row */
.code-preview-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}
.code-preview-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.code-preview-label {
  min-width: 132px;
  color: #606266;
  font-size: 12px;
}
.code-preview-tag {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
}
.code-preview-tag.code-preview-loading {
  opacity: 0.6;
}
.code-preview-hint {
  font-size: 12px;
  color: #909399;
}

.material-code-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.material-code-value {
  color: #303133;
  font-weight: 600;
  letter-spacing: 0.4px;
  overflow-wrap: anywhere;
}

/* T159-A: Field hints */
.field-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}
.field-hint--manual {
  color: #e6a23c;
}

.field-hint--legacy {
  color: #e6a23c;
}

.field-hint--matched {
  color: #67c23a;
}

/* T159-A: Packaging inline row (SKU-style) */
.packaging-inline-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.packaging-equals-hint {
  font-size: 12px;
  color: #909399;
}
.unit-tag {
  font-size: 13px;
  padding: 0 10px;
  height: 28px;
  line-height: 26px;
}
.unit-tag-echo {
  font-size: 13px;
  padding: 0 10px;
  height: 28px;
  line-height: 26px;
  flex-shrink: 0;
}
.packaging-conversion-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.conversion-label {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  min-width: 10px;
}
.conversion-equals {
  font-size: 16px;
  color: #606266;
  padding: 0 2px;
}
.packaging-preview {
  margin-top: 6px;
  font-size: 12px;
}
.packaging-preview--warn {
  color: #e6a23c;
  font-size: 12px;
}
</style>
