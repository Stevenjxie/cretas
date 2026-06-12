<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { bomYieldEstimateApi, bomRecipeApi } from '@/api/bom';
import type {
  YieldEstimateResponse,
  RecalculatePreviewRow,
  RecalculateApplyItem,
  RecalculateApplyStaleResponse,
  BomRecipeSummary,
  BomRecipeStatus,
  BomRecipeItemPayload,
  CreateBomRecipeRequest,
  UpdateBomRecipeRequest,
} from '@/api/bom';
import { isAxiosError } from 'axios';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete, Download, Refresh, MagicStick, InfoFilled } from '@element-plus/icons-vue';
import BomChangeLog from './BomChangeLog.vue'
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue'
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue'
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// =========================================================================
// BOM Recipe status panel (DRAFT → ACTIVE 激活)
// =========================================================================

/** BOM recipe status tag display config */
const recipeStatusTagType: Record<BomRecipeStatus, '' | 'success' | 'info' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  ACTIVE: 'success',
  ARCHIVED: '',
};
const recipeStatusLabel: Record<BomRecipeStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '已生效',
  ARCHIVED: '已归档',
};

/** All BOM recipes for the currently selected product (newest first) */
const bomRecipes = ref<BomRecipeSummary[]>([]);
const bomRecipesLoading = ref(false);
const activatingRecipeId = ref<string | null>(null);

async function loadBomRecipes() {
  if (!factoryId.value || !selectedProductTypeId.value) {
    bomRecipes.value = [];
    return;
  }
  bomRecipesLoading.value = true;
  try {
    const res = await bomRecipeApi.listRecipes(factoryId.value, { size: 200 });
    if (res.success && res.data) {
      // Filter to current product only; backend sorts by updatedAt desc
      const all: BomRecipeSummary[] = Array.isArray(res.data)
        ? (res.data as BomRecipeSummary[])
        : ((res.data as { content: BomRecipeSummary[] }).content ?? []);
      bomRecipes.value = all.filter(
        (r) => r.productTypeId === selectedProductTypeId.value,
      );
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      // Non-critical: BOM recipe list is supplemental info; don't block the main page
      console.warn('加载 BOM 配方列表失败', error);
    }
  } finally {
    bomRecipesLoading.value = false;
  }
}

async function handleActivateRecipe(recipe: BomRecipeSummary) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `激活后此 BOM（${recipe.productName} v${recipe.version}）成为产品当前生效配方，原有生效配方将自动归档。确认激活？`,
      '激活 BOM 配方',
      {
        confirmButtonText: '激活',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    // user cancelled
    return;
  }

  activatingRecipeId.value = recipe.id;
  try {
    const operatorId = authStore.user?.id ?? null;
    const res = await bomRecipeApi.activate(factoryId.value, recipe.id, operatorId);
    if (res.success) {
      ElMessage.success('已激活 — BOM 配方已设为生效状态');
      await loadBomRecipes();
      // Refresh BOM items and cost summary since active recipe may differ
      await loadBomItems();
      await loadCostSummary();
    } else {
      ElMessage({
        message: res.message || '激活失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || '激活 BOM 配方失败，请稍后重试';
      ElMessage({
        message: msg,
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } finally {
    activatingRecipeId.value = null;
  }
}

// State
const loading = ref(false);
const changeLogVisible = ref(false)
const selectedProductTypeId = ref<string>('');
const productTypes = ref<TableRow[]>([]);
const costSummary = ref<TableRow | null>(null);
const selectedProductName = computed(() => {
  const product = productTypes.value.find((item) => item.id === selectedProductTypeId.value);
  return String(product?.name || '');
});

interface RecipeHeaderForm {
  outputQuantityPerUnit: number | null;
  outputUnit: string;
  overallYieldRate: number | null;
  notes: string;
}

const recipeDialogVisible = ref(false);
const recipeDialogLoading = ref(false);
const isRecipeEdit = ref(false);
const editingRecipeId = ref<string | null>(null);
const recipeForm = ref<RecipeHeaderForm>({
  outputQuantityPerUnit: 1,
  outputUnit: '份',
  overallYieldRate: 100,
  notes: '',
});

// BOM Items (原辅料)
interface BomItemRow {
  id?: number | null;
  productTypeId?: string;
  materialTypeId?: string;
  materialName?: string;
  standardQuantity?: number;
  yieldRate?: number | null;
  unit?: string;
  unitPrice?: number;
  taxRate?: number;
  sortOrder?: number;
  notes?: string;
  isOptional?: boolean;
  substituteGroup?: string;
  // SP4-8: 按份数投料 + 半成品引用
  perPortion?: boolean;
  semiFinishedRefCode?: string;
  // SP12 #728: 组合装子产品/嵌套 BOM
  subProductTypeId?: string;
  [k: string]: unknown;
}
interface LaborCostRow {
  id?: number | null;
  productTypeId?: string;
  processName?: string;
  processCategory?: string;
  unitPrice?: number;
  priceUnit?: string;
  standardQuantity?: number;
  sortOrder?: number;
  notes?: string;
  [k: string]: unknown;
}
interface OverheadCostRow {
  id?: number | null;
  name?: string;
  category?: string;
  unitPrice?: number;
  priceUnit?: string;
  allocationRate?: number;
  sortOrder?: number;
  notes?: string;
  [k: string]: unknown;
}
const bomItems = ref<BomItemRow[]>([]);
const bomDialogVisible = ref(false);
const bomDialogLoading = ref(false);
const isBomEdit = ref(false);
// D3 (2026-05-10 客户会议): BOM 配方层默认用 g, 仓库 / 调拨层用 kg, 后台 1:1000 自动换算
const bomForm = ref({
  id: null as number | null,
  productTypeId: '',
  materialTypeId: '',
  materialName: '',
  materialCategory: 'RAW',
  standardQuantity: 0,
  // Phase A side-effect: 默认 null, 保存时 null = 出成率待评估 (后端用 standardQuantity 原样)
  yieldRate: null as number | null,
  unit: 'g',
  unitPrice: 0,
  taxRate: 13,
  sortOrder: 0,
  notes: '',
  isOptional: false as boolean,
  substituteGroup: '' as string,
  // SP4-8: 按份数投料 + 半成品引用
  perPortion: false as boolean,
  semiFinishedRefCode: '' as string,
  // SP12 #728: 组合装子产品/嵌套 BOM
  subProductTypeId: '' as string,
  // #759: 包材每产品单位用量 (仅 PACKAGING 有意义; null=未配置需手填 standardQuantity)
  packQtyPerProduct: null as number | null,
});

// SP8: 半成品产品类型列表 (用于 semiFinishedRefCode 下拉)
const semiFinishedTypes = ref<TableRow[]>([]);

// Phase B: 弹窗评估按钮状态
const estimateLoading = ref(false);
const estimateResult = ref<YieldEstimateResponse | null>(null);

// D2 (2026-05-10 客户会议): 实时计算实际原料用量 = 成品含量 / (出成率/100)
// 镜像后端 BomItem.getActualQuantity()
// GAP F7: 出成率为 null (待评估) 时返回 null, 不用 100% 兜底 (防止 sq/1.0=sq 误导为真实 100%)
const computedActualQuantity = computed<number | null>(() => {
  if (bomForm.value.yieldRate == null) return null;
  const sq = Number(bomForm.value.standardQuantity) || 0;
  const yr = Number(bomForm.value.yieldRate) / 100;
  if (yr <= 0 || sq <= 0) return 0;
  return Number((sq / yr).toFixed(4));
});

function normalizeRecipeMaterialCategory(value: unknown): 'RAW' | 'AUXILIARY' | 'PACKAGING' {
  const category = String(value || '').toUpperCase();
  if (category === 'PACKAGING' || category === '包材') return 'PACKAGING';
  if (category === 'AUXILIARY' || category === '辅料' || category === '调味料') return 'AUXILIARY';
  return 'RAW';
}

function buildRecipeItemPayloads(): BomRecipeItemPayload[] | null {
  if (bomItems.value.length === 0) {
    ElMessage.warning('请先添加至少 1 行原辅料，再创建配方头');
    return null;
  }

  const missingMaterialRows: string[] = [];
  const invalidQuantityRows: string[] = [];
  const items = bomItems.value.map((item, index) => {
    const materialTypeId = String(item.materialTypeId || '').trim();
    const materialName = String(item.materialName || `第 ${index + 1} 行`);
    const standardQuantity = Number(item.standardQuantity);
    if (!materialTypeId) missingMaterialRows.push(materialName);
    if (!(standardQuantity > 0)) invalidQuantityRows.push(materialName);

    const substituteGroup = String(item.substituteGroup || '').trim();
    const remark = String(item.notes || item.remark || '').trim();
    const unit = String(item.unit || 'g').trim();

    return {
      materialTypeId,
      standardQuantity,
      yieldRate: item.yieldRate == null ? undefined : Number(item.yieldRate),
      unit,
      unitPrice: item.unitPrice == null ? undefined : Number(item.unitPrice),
      taxRate: item.taxRate == null ? undefined : Number(item.taxRate),
      materialCategory: normalizeRecipeMaterialCategory(item.materialCategory || item.category),
      sortOrder: item.sortOrder == null ? index : Number(item.sortOrder),
      isOptional: Boolean(item.isOptional),
      substituteGroup: substituteGroup || undefined,
      remark: remark || undefined,
      perPortion: Boolean(item.perPortion),
      semiFinishedRefCode: item.semiFinishedRefCode || undefined,
      subProductTypeId: item.subProductTypeId || undefined,
    };
  });

  if (missingMaterialRows.length > 0) {
    ElMessage({
      message: `创建配方头前，请给这些行关联原料类型：${missingMaterialRows.join('、')}`,
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return null;
  }

  if (invalidQuantityRows.length > 0) {
    ElMessage({
      message: `创建配方头前，请确认这些行的成品含量大于 0：${invalidQuantityRows.join('、')}`,
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return null;
  }

  return items;
}

function resetRecipeForm(recipe?: BomRecipeSummary) {
  recipeForm.value = {
    outputQuantityPerUnit: recipe?.outputQuantityPerUnit != null
      ? Number(recipe.outputQuantityPerUnit)
      : 1,
    outputUnit: recipe?.outputUnit || '份',
    overallYieldRate: recipe?.overallYieldRate != null
      ? Number(recipe.overallYieldRate)
      : 100,
    notes: recipe?.notes || '',
  };
}

function handleAddRecipeHeader() {
  if (!selectedProductTypeId.value) {
    ElMessage.warning('请先选择产品，再创建配方头');
    return;
  }
  isRecipeEdit.value = false;
  editingRecipeId.value = null;
  const currentRecipe = bomRecipes.value.find((recipe) => recipe.isCurrent) || bomRecipes.value[0];
  resetRecipeForm(currentRecipe);
  recipeDialogVisible.value = true;
}

function handleEditRecipeHeader(recipe: BomRecipeSummary) {
  if (recipe.status !== 'DRAFT') {
    ElMessage.warning('只有草稿配方可以编辑配方头');
    return;
  }
  isRecipeEdit.value = true;
  editingRecipeId.value = recipe.id;
  resetRecipeForm(recipe);
  recipeDialogVisible.value = true;
}

async function submitRecipeHeaderForm() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  const outputQuantityPerUnit = Number(recipeForm.value.outputQuantityPerUnit);
  const outputUnit = recipeForm.value.outputUnit.trim();
  const overallYieldRate = Number(recipeForm.value.overallYieldRate);

  if (!(outputQuantityPerUnit > 0)) {
    ElMessage.warning('每单位产出量必须大于 0');
    return;
  }
  if (!outputUnit) {
    ElMessage.warning('请填写产出单位，例如 g、kg、份、盒');
    return;
  }
  if (!(overallYieldRate > 0 && overallYieldRate <= 100)) {
    ElMessage.warning('整体出成率必须在 0.01 到 100 之间');
    return;
  }

  recipeDialogLoading.value = true;
  try {
    let response;
    const basePayload = {
      productName: selectedProductName.value || null,
      outputQuantityPerUnit,
      outputUnit,
      overallYieldRate,
      notes: recipeForm.value.notes.trim() || null,
    };

    if (isRecipeEdit.value && editingRecipeId.value) {
      const payload: UpdateBomRecipeRequest = basePayload;
      response = await bomRecipeApi.update(factoryId.value, editingRecipeId.value, payload);
    } else {
      const items = buildRecipeItemPayloads();
      if (!items) return;
      const payload: CreateBomRecipeRequest = {
        ...basePayload,
        productTypeId: selectedProductTypeId.value,
        sourceType: 'MANUAL',
        items,
      };
      response = await bomRecipeApi.create(factoryId.value, payload);
    }

    if (response.success) {
      ElMessage.success(
        `${isRecipeEdit.value ? '配方头已更新' : '配方草稿已创建'}：每单位产出 ${outputQuantityPerUnit} ${outputUnit}，整体出成率 ${overallYieldRate}%`,
      );
      recipeDialogVisible.value = false;
      await loadBomRecipes();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || (isRecipeEdit.value ? '配方头更新失败' : '配方创建失败'),
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (isRecipeEdit.value ? '配方头更新失败，请稍后重试' : '配方创建失败，请稍后重试');
      ElMessage({
        message: msg,
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } finally {
    recipeDialogLoading.value = false;
  }
}

// Phase B: 弹窗评估按钮 handler
async function handleEstimate() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  estimateLoading.value = true;
  estimateResult.value = null;
  try {
    const res = await bomYieldEstimateApi.getEstimate(
      factoryId.value,
      selectedProductTypeId.value,
      bomForm.value.materialCategory || 'RAW',
    );
    if (res.success && res.data) {
      const data = res.data;
      estimateResult.value = data;

      // 自动回填建议值 (可被用户覆盖)
      if (data.suggestedStandardQuantity != null) {
        bomForm.value.standardQuantity = data.suggestedStandardQuantity;
      }
      if (data.suggestedYieldRate != null) {
        bomForm.value.yieldRate = data.suggestedYieldRate;
      } else {
        // 无出成率建议 → 清空, 显示 actionHint
        bomForm.value.yieldRate = null;
        if (data.actionHint) {
          ElMessage({
            message: data.actionHint,
            type: 'warning',
            duration: 0,
            showClose: true,
          });
        }
      }
    }
  } catch (error: unknown) {
    // error toast 由 request interceptor 统一处理
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      ElMessage({
        message: '获取出成率评估失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } finally {
    estimateLoading.value = false;
  }
}

// Labor Costs (人工费用)
const laborCosts = ref<LaborCostRow[]>([]);
const laborDialogVisible = ref(false);
const laborDialogLoading = ref(false);
const isLaborEdit = ref(false);
const laborForm = ref({
  id: null as number | null,
  productTypeId: '',
  processName: '',
  processCategory: '',
  unitPrice: 0,
  priceUnit: '元/kg',
  standardQuantity: 1,
  sortOrder: 0,
  notes: ''
});

// Overhead Costs (均摊费用)
const overheadCosts = ref<OverheadCostRow[]>([]);
const overheadDialogVisible = ref(false);
const overheadDialogLoading = ref(false);
const isOverheadEdit = ref(false);
const overheadForm = ref({
  id: null as number | null,
  name: '',
  category: '',
  unitPrice: 0,
  priceUnit: '元/kg',
  allocationRate: 1,
  sortOrder: 0,
  notes: ''
});

// Raw material types for dropdown
const materialTypes = ref<TableRow[]>([]);

// Per-serving cost calculation
const standardServingWeight = ref<number>(0.5); // kg per serving, user-adjustable

// Process categories for dropdown
const processCategories = ['通用工序', '分割工序', '包装工序', '质检工序', '冷藏工序'];

// Overhead categories for dropdown
const overheadCategories = ['房租', '水电', '燃气', '设备折旧', '后端毛利', '其他'];

// Phase C: 一键重算出成率状态
const recalcPreviewVisible = ref(false);
const recalcPreviewLoading = ref(false);
const recalcApplyLoading = ref(false);
const recalcPreviewRows = ref<RecalculatePreviewRow[]>([]);
// 用户勾选的行 (bomItemId)
const recalcSelectedIds = ref<number[]>([]);

/** 打开预览抽屉, 先 POST recalculate-preview */
async function handleOpenRecalcPreview() {
  if (!factoryId.value) return;
  recalcPreviewVisible.value = true;
  recalcPreviewLoading.value = true;
  recalcPreviewRows.value = [];
  recalcSelectedIds.value = [];
  try {
    const res = await bomYieldEstimateApi.recalculatePreview(factoryId.value);
    if (res.success && res.data) {
      recalcPreviewRows.value = res.data;
      // 默认勾选所有 UPDATABLE 行
      recalcSelectedIds.value = res.data
        .filter((r) => r.status === 'UPDATABLE')
        .map((r) => r.bomItemId);
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      ElMessage({
        message: '获取出成率重算预览失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
    recalcPreviewVisible.value = false;
  } finally {
    recalcPreviewLoading.value = false;
  }
}

/** 应用勾选行的建议出成率 */
async function handleApplyRecalc() {
  if (!factoryId.value) return;
  // GAP M10: 同时发送 expectedCurrentYieldRate (乐观锁), 后端检测是否 stale
  const items: RecalculateApplyItem[] = recalcPreviewRows.value
    .filter((r) => recalcSelectedIds.value.includes(r.bomItemId) && r.status === 'UPDATABLE' && r.suggestedYieldRate != null)
    .map((r) => ({
      bomItemId: r.bomItemId,
      yieldRate: r.suggestedYieldRate as number,
      expectedCurrentYieldRate: r.currentYieldRate, // null 表示预览时就是未填
    }));

  if (items.length === 0) {
    ElMessage.warning('没有可应用的行');
    return;
  }

  recalcApplyLoading.value = true;
  try {
    const res = await bomYieldEstimateApi.recalculateApply(factoryId.value, items);
    if (res.success && res.data) {
      ElMessage.success(`已更新 ${res.data.applied} 条 BOM 出成率`);
      recalcPreviewVisible.value = false;
      // 刷新当前产品的 BOM 明细
      await loadBomItems();
      await loadCostSummary();
    } else {
      ElMessage({
        message: res.message || '应用失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    // GAP M10: 409 乐观锁冲突 — 数据已变化, 提示重新预览
    if (isAxiosError(error) && error.response?.status === 409) {
      const body = error.response.data as RecalculateApplyStaleResponse;
      ElMessage({
        message: body?.message || '数据已变化，请重新评估',
        type: 'error',
        duration: 0,
        showClose: true,
      });
      // 关闭抽屉让用户重新打开预览 (Rule 5: dead-end 改导航)
      recalcPreviewVisible.value = false;
      return;
    }
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      ElMessage({
        message: '应用出成率更新失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } finally {
    recalcApplyLoading.value = false;
  }
}

onMounted(async () => {
  await loadProductTypes();
  await loadMaterialTypes();
  await loadOverheadCosts();
  await loadAllLaborCosts();
});

watch(selectedProductTypeId, async (newVal) => {
  if (newVal) {
    await loadBomItems();
    await loadLaborCosts();
    await loadCostSummary();
    await loadBomRecipes();
  } else {
    bomItems.value = [];
    laborCosts.value = [];
    costSummary.value = null;
    bomRecipes.value = [];
  }
});

// ========== Product Types ==========
async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/product-types/active`);
    if (response.success && response.data) {
      // Issue 7: Only show finished products in BOM dropdown
      const allProducts = response.data as TableRow[];
      productTypes.value = allProducts.filter(
        (p: TableRow) => p.productCategory === 'FINISHED_PRODUCT' || p.category === '成品' || !p.productCategory
      );
      // SP8: 半成品列表 (用于 semiFinishedRefCode 下拉)
      semiFinishedTypes.value = allProducts.filter(
        (p: TableRow) => p.productCategory === 'SEMI_FINISHED' || p.category === '半成品'
      );
      // Select first product if available
      if (productTypes.value.length > 0 && !selectedProductTypeId.value) {
        selectedProductTypeId.value = productTypes.value[0].id;
      }
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载产品类型失败');
  }
}

async function loadMaterialTypes() {
  if (!factoryId.value) return;
  try {
    // Issue 8: Fetch ALL active materials to stay in sync with material master
    const response = await get(`/${factoryId.value}/raw-material-types/active`);
    if (response.success && response.data) {
      materialTypes.value = Array.isArray(response.data) ? response.data : (response.data.content || []);
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载原料类型失败');
  }
}

// Phase A fix: onMaterialLink 不再覆盖 RAW 物料的 BOM 单位.
// BOM 配方层 RAW 成品含量永远是克 (g), 单位固定显示 "克 (g)" 后缀.
// 仅 PACKAGING 物料允许使用 material.unit (pcs/件 等).
// Ref: D3 comment L68 + F006_OPERATIONS_GUIDE §0.4
function onMaterialLink(materialTypeId: string) {
  if (!materialTypeId) return;
  const material = materialTypes.value.find((m: Record<string, unknown>) => m.id === materialTypeId);
  if (material) {
    if (material.name) bomForm.value.materialName = String(material.name);
    // Phase A: RAW / AUXILIARY → 固定 g, 不覆盖; 仅 PACKAGING 跟随物料单位
    if (bomForm.value.materialCategory === 'PACKAGING') {
      if (material.unit) bomForm.value.unit = String(material.unit);
    } else {
      // RAW / AUXILIARY 配方层始终用 g
      bomForm.value.unit = 'g';
    }
  }
}

// ========== BOM Items ==========
async function loadBomItems() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/bom/items/${selectedProductTypeId.value}`);
    if (response.success && response.data) {
      bomItems.value = response.data;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('Failed to load BOM data');
  } finally {
    loading.value = false;
  }
}

function handleAddBomItem() {
  isBomEdit.value = false;
  estimateResult.value = null;
  // D3: 新建 BOM 默认单位为 g (克), 后台调拨时自动换算为 kg (千克)
  // Phase A side-effect: yieldRate 默认 null (出成率待评估), 不是 100
  bomForm.value = {
    id: null,
    productTypeId: selectedProductTypeId.value,
    materialTypeId: '',
    materialName: '',
    materialCategory: activeCategoryTab.value,
    standardQuantity: 0,
    yieldRate: null,
    unit: 'g',
    unitPrice: 0,
    taxRate: 13,
    sortOrder: bomItems.value.length,
    notes: '',
    isOptional: false,
    substituteGroup: '',
    perPortion: false,
    semiFinishedRefCode: '',
    subProductTypeId: '',
    packQtyPerProduct: null,
  };
  bomDialogVisible.value = true;
}

function handleEditBomItem(row: TableRow) {
  isBomEdit.value = true;
  estimateResult.value = null;
  bomForm.value = {
    id: row.id,
    productTypeId: row.productTypeId,
    materialTypeId: row.materialTypeId,
    materialName: row.materialName,
    materialCategory: (row.materialCategory as string) || 'RAW',
    standardQuantity: row.standardQuantity || 0,
    // Phase A: 编辑时保留原值 (可为 null = 待评估)
    yieldRate: row.yieldRate != null ? (row.yieldRate as number) : null,
    unit: row.unit || 'g',
    unitPrice: row.unitPrice || 0,
    taxRate: row.taxRate || 13,
    sortOrder: row.sortOrder || 0,
    notes: row.notes || '',
    isOptional: Boolean(row.isOptional),
    substituteGroup: String(row.substituteGroup || ''),
    perPortion: (row.perPortion as boolean) ?? false,
    semiFinishedRefCode: String(row.semiFinishedRefCode || ''),
    subProductTypeId: String(row.subProductTypeId || ''),
    packQtyPerProduct: row.packQtyPerProduct != null ? Number(row.packQtyPerProduct) : null,
  };
  bomDialogVisible.value = true;
}

function buildLegacyBomItemPayload() {
  const payload = { ...bomForm.value };
  delete payload.id;
  delete payload.isOptional;
  delete payload.substituteGroup;
  return payload;
}

async function submitBomForm() {
  if (!bomForm.value.materialName) {
    ElMessage.warning('Please enter material name');
    return;
  }
  bomDialogLoading.value = true;
  try {
    let response;
    const bomItemPayload = buildLegacyBomItemPayload();
    if (isBomEdit.value && bomForm.value.id) {
      response = await put(`/${factoryId.value}/bom/items/${bomForm.value.id}`, bomItemPayload);
    } else {
      // BUG-4 fix (depth-e2e qa-v2.4, PR #370): strip phantom `id: null` from POST body.
      // handleAddBomItem 设 `id: null` 给 form 一致性, 但 POST 不应携带 id (Jackson 当前默默 drop,
      // 但在未来 FAIL_ON_UNKNOWN_PROPERTIES strict mode 会爆 400).
      response = await post(`/${factoryId.value}/bom/items`, bomItemPayload);
    }
    if (response.success) {
      ElMessage.success(isBomEdit.value ? 'Updated successfully' : 'Added successfully');
      bomDialogVisible.value = false;
      await loadBomItems();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Operation failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage({
      message: 'Operation failed',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    bomDialogLoading.value = false;
  }
}

async function handleDeleteBomItem(row: TableRow) {
  try {
    await ElMessageBox.confirm('Are you sure you want to delete this item?', 'Confirm', { type: 'warning' });
    const response = await del(`/${factoryId.value}/bom/items/${row.id}`);
    if (response.success) {
      ElMessage.success('Deleted successfully');
      await loadBomItems();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (error !== 'cancel' && !err?.actionHint) {
      ElMessage({
        message: 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  }
}

// ========== Labor Costs ==========
async function loadLaborCosts() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  try {
    const response = await get(`/${factoryId.value}/bom/labor`, {
      params: { productTypeId: selectedProductTypeId.value }
    });
    if (response.success && response.data) {
      laborCosts.value = response.data;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载人工费用失败');
  }
}

async function loadAllLaborCosts() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/bom/labor/all`);
    if (response.success && response.data) {
      // Store all labor costs for reference
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载人工费用汇总失败');
  }
}

function handleAddLaborCost() {
  isLaborEdit.value = false;
  laborForm.value = {
    id: null,
    productTypeId: selectedProductTypeId.value,
    processName: '',
    processCategory: '',
    unitPrice: 0,
    priceUnit: '元/kg',
    standardQuantity: 1,
    sortOrder: laborCosts.value.length,
    notes: ''
  };
  laborDialogVisible.value = true;
}

function handleEditLaborCost(row: TableRow) {
  isLaborEdit.value = true;
  laborForm.value = {
    id: row.id,
    productTypeId: row.productTypeId,
    processName: row.processName,
    processCategory: row.processCategory || '',
    unitPrice: row.unitPrice || 0,
    priceUnit: row.priceUnit || '元/kg',
    standardQuantity: row.standardQuantity || 1,
    sortOrder: row.sortOrder || 0,
    notes: row.notes || ''
  };
  laborDialogVisible.value = true;
}

async function submitLaborForm() {
  if (!laborForm.value.processName) {
    ElMessage.warning('Please enter process name');
    return;
  }
  laborDialogLoading.value = true;
  try {
    let response;
    if (isLaborEdit.value && laborForm.value.id) {
      response = await put(`/${factoryId.value}/bom/labor/${laborForm.value.id}`, laborForm.value);
    } else {
      response = await post(`/${factoryId.value}/bom/labor`, laborForm.value);
    }
    if (response.success) {
      ElMessage.success(isLaborEdit.value ? 'Updated successfully' : 'Added successfully');
      laborDialogVisible.value = false;
      await loadLaborCosts();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Operation failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage({
      message: 'Operation failed',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    laborDialogLoading.value = false;
  }
}

async function handleDeleteLaborCost(row: TableRow) {
  try {
    await ElMessageBox.confirm('Are you sure you want to delete this item?', 'Confirm', { type: 'warning' });
    const response = await del(`/${factoryId.value}/bom/labor/${row.id}`);
    if (response.success) {
      ElMessage.success('Deleted successfully');
      await loadLaborCosts();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (error !== 'cancel' && !err?.actionHint) {
      ElMessage({
        message: 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  }
}

// ========== Overhead Costs ==========
async function loadOverheadCosts() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/bom/overhead`);
    if (response.success && response.data) {
      overheadCosts.value = response.data;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载均摊费用失败');
  }
}

function handleAddOverheadCost() {
  isOverheadEdit.value = false;
  overheadForm.value = {
    id: null,
    name: '',
    category: '',
    unitPrice: 0,
    priceUnit: '元/kg',
    allocationRate: 1,
    sortOrder: overheadCosts.value.length,
    notes: ''
  };
  overheadDialogVisible.value = true;
}

function handleEditOverheadCost(row: TableRow) {
  isOverheadEdit.value = true;
  overheadForm.value = {
    id: row.id,
    name: row.name,
    category: row.category || '',
    unitPrice: row.unitPrice || 0,
    priceUnit: row.priceUnit || '元/kg',
    allocationRate: row.allocationRate || 1,
    sortOrder: row.sortOrder || 0,
    notes: row.notes || ''
  };
  overheadDialogVisible.value = true;
}

async function submitOverheadForm() {
  if (!overheadForm.value.name) {
    ElMessage.warning('Please enter cost name');
    return;
  }
  overheadDialogLoading.value = true;
  try {
    let response;
    if (isOverheadEdit.value && overheadForm.value.id) {
      response = await put(`/${factoryId.value}/bom/overhead/${overheadForm.value.id}`, overheadForm.value);
    } else {
      response = await post(`/${factoryId.value}/bom/overhead`, overheadForm.value);
    }
    if (response.success) {
      ElMessage.success(isOverheadEdit.value ? 'Updated successfully' : 'Added successfully');
      overheadDialogVisible.value = false;
      await loadOverheadCosts();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Operation failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage({
      message: 'Operation failed',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    overheadDialogLoading.value = false;
  }
}

async function handleDeleteOverheadCost(row: TableRow) {
  try {
    await ElMessageBox.confirm('Are you sure you want to delete this item?', 'Confirm', { type: 'warning' });
    const response = await del(`/${factoryId.value}/bom/overhead/${row.id}`);
    if (response.success) {
      ElMessage.success('Deleted successfully');
      await loadOverheadCosts();
      await loadCostSummary();
    } else {
      ElMessage({
        message: response.message || 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (error !== 'cancel' && !err?.actionHint) {
      ElMessage({
        message: 'Delete failed',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  }
}

// ========== Cost Summary ==========
async function loadCostSummary() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  try {
    const response = await get(`/${factoryId.value}/bom/cost-summary/${selectedProductTypeId.value}`);
    if (response.success && response.data) {
      costSummary.value = response.data;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载成本汇总失败');
  }
}

// ========== Computed ==========
const materialCostTotal = computed(() => {
  return bomItems.value.reduce((sum, item) => {
    const qty = item.standardQuantity || 0;
    const yieldRate = item.yieldRate != null ? (Number(item.yieldRate) || 100) / 100 : 1;
    const price = item.unitPrice || 0;
    return sum + (yieldRate > 0 ? (qty / yieldRate) * price : 0);
  }, 0);
});

const laborCostTotal = computed(() => {
  return laborCosts.value.reduce((sum, item) => {
    return sum + (item.unitPrice || 0) * (item.standardQuantity || 1);
  }, 0);
});

const overheadCostTotal = computed(() => {
  return overheadCosts.value.reduce((sum, item) => {
    return sum + (item.unitPrice || 0) * (item.allocationRate || 1);
  }, 0);
});

const totalCost = computed(() => {
  return materialCostTotal.value + laborCostTotal.value + overheadCostTotal.value;
});

// Issue 12: Group BOM items by material category
const groupedBomItems = computed(() => {
  const groups: { category: string; items: TableRow[] }[] = [];
  const categoryMap = new Map<string, TableRow[]>();
  const categoryOrder = ['原材料', '辅料', '包材', '调味料', '其他'];

  for (const item of bomItems.value) {
    // Try to get category from linked material or fall back
    const cat = String(item.materialCategory || item.category || '其他');
    if (!categoryMap.has(cat)) categoryMap.set(cat, []);
    categoryMap.get(cat)!.push(item);
  }

  // Sort by predefined order
  for (const cat of categoryOrder) {
    if (categoryMap.has(cat)) {
      groups.push({ category: cat, items: categoryMap.get(cat)! });
      categoryMap.delete(cat);
    }
  }
  // Any remaining categories
  for (const [cat, items] of categoryMap) {
    groups.push({ category: cat, items });
  }

  return groups;
});

const hasMultipleCategories = computed(() => groupedBomItems.value.length > 1);

// P0-14: Tab filtering by materialCategory (RAW/AUXILIARY/PACKAGING)
const activeCategoryTab = ref<'RAW' | 'AUXILIARY' | 'PACKAGING'>('RAW');
function matchCategory(row: TableRow, code: 'RAW' | 'AUXILIARY' | 'PACKAGING') {
  const c = String(row.materialCategory || row.category || '').toUpperCase();
  if (code === 'RAW') return c === 'RAW' || c === '原材料' || c === '' || c === '其他';
  if (code === 'AUXILIARY') return c === 'AUXILIARY' || c === '辅料' || c === '调味料';
  if (code === 'PACKAGING') return c === 'PACKAGING' || c === '包材';
  return false;
}
const rawItems = computed(() => bomItems.value.filter((i: TableRow) => matchCategory(i, 'RAW')));
const auxiliaryItems = computed(() => bomItems.value.filter((i: TableRow) => matchCategory(i, 'AUXILIARY')));
const packagingItems = computed(() => bomItems.value.filter((i: TableRow) => matchCategory(i, 'PACKAGING')));
const currentTabItems = computed(() => {
  if (activeCategoryTab.value === 'RAW') return rawItems.value;
  if (activeCategoryTab.value === 'AUXILIARY') return auxiliaryItems.value;
  return packagingItems.value;
});

// Issue 11: Cost per serving
const costPerServing = computed(() => {
  if (standardServingWeight.value <= 0) return 0;
  return totalCost.value * standardServingWeight.value;
});

// Phase C: 预览表中是否有可应用的 UPDATABLE 行
const recalcUpdatableCount = computed(() =>
  recalcPreviewRows.value.filter((r) => r.status === 'UPDATABLE').length
);
const recalcSelectedCount = computed(() =>
  recalcSelectedIds.value.length
);

// Phase C: el-table selection handler
function handleRecalcSelectionChange(rows: RecalculatePreviewRow[]) {
  recalcSelectedIds.value = rows.map((r) => r.bomItemId);
}

// ========== Export ==========
function exportToExcel(type: string) {
  let headers: string[];
  let rows: string[][];
  if (type === 'material') {
    if (bomItems.value.length === 0) { ElMessage.warning('暂无BOM数据可导出'); return; }
    headers = ['物料名称', '物料编号', '数量', '单位', '单价(元)', '小计(元)', '备注'];
    rows = bomItems.value.map((item: TableRow) => [
      item.materialName || '', item.materialCode || '', String(item.quantity ?? ''),
      item.unit || '', String(item.unitPrice ?? ''),
      String(((item.quantity || 0) * (item.unitPrice || 0)).toFixed(2)), item.notes || ''
    ]);
  } else if (type === 'labor') {
    if (laborCosts.value.length === 0) { ElMessage.warning('暂无人工成本数据'); return; }
    headers = ['工序名称', '工时(分钟)', '单价(元/时)', '费用(元)'];
    rows = laborCosts.value.map((item: TableRow) => [
      item.processName || '', String(item.duration ?? ''), String(item.unitPrice ?? ''),
      String(((item.duration || 0) / 60 * (item.unitPrice || 0)).toFixed(2))
    ]);
  } else {
    if (overheadCosts.value.length === 0) { ElMessage.warning('暂无制造费用数据'); return; }
    headers = ['费用名称', '金额(元)', '分摊率', '分摊金额(元)'];
    rows = overheadCosts.value.map((item: TableRow) => [
      item.name || '', String(item.unitPrice ?? ''), String(item.allocationRate ?? 1),
      String(((item.unitPrice || 0) * (item.allocationRate || 1)).toFixed(2))
    ]);
  }
  const csvContent = '﻿' + [headers, ...rows].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `BOM_${type}_${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  ElMessage.success('导出成功');
}

function refreshData() {
  loadBomItems();
  loadLaborCosts();
  loadOverheadCosts();
  loadCostSummary();
  loadBomRecipes();
}
</script>

<template>
  <CanvasAwareWrapper module-code="bom">
  <div class="bom-page">
    <!-- D4 Path B (2026-05-10 customer meeting, PR #309 A2=B): BomExpansionService 现已优先读 bom_items 表. -->
    <!-- BOM 编辑保存后立即对生产计划生效, 无需再手动同步到转换率配置. -->
    <!-- RPF (MaterialProductConversion) 仅作 fallback (老工厂数据无 BOM 配置时沿用). -->
    <!-- 详见 docs/architecture/2026-05-10-rpf-vs-bomitem-divergence.md §7 -->
    <el-alert
      type="success"
      :closable="false"
      show-icon
      style="margin-bottom: 12px;"
    >
      <template #title>
        BOM 已对接生产计划, 录入即生效
      </template>
      <template #default>
        本页录入的 BOM 配方 (含成品含量 + 出成率% + 单位) 保存后立即被生产计划自动展开使用,
        无需再同步「转换率配置」(RPF)。RPF 表保留作为老工厂数据的 fallback。
      </template>
    </el-alert>
    <ConceptDisambiguationAlert
      here-name="BOM 成本管理"
      here="一个成品需要哪些原料、各多少量、成本如何拆分（多对多结构 + 成本核算）"
      other-name="生产管理 → 转换率配置"
      other="单一原料 → 单一成品的「出成率」（如 1kg 冻猪蹄 → 600g 卤猪蹄，60%）"
      other-path="/production/conversions"
      consequence="复杂配方用 BOM，简单出成率用转换率"
    />
    <!-- Header -->
    <el-card class="header-card" shadow="never">
      <div class="header-content">
        <div class="header-left">
          <h2 class="page-title">BOM成本管理</h2>
          <el-select
            v-model="selectedProductTypeId"
            placeholder="选择产品"
            style="width: 280px; margin-left: 20px;"
            filterable
          >
            <el-option
              v-for="product in productTypes"
              :key="product.id"
              :label="product.name"
              :value="product.id"
            />
          </el-select>
          <el-button :icon="Refresh" style="margin-left: 12px;" @click="refreshData">刷新</el-button>
          <el-button style="margin-left: 12px;" @click="changeLogVisible = true" :disabled="!selectedProductTypeId">变更记录</el-button>
          <el-button
            v-if="canWrite"
            type="primary"
            :icon="Plus"
            style="margin-left: 12px;"
            :disabled="!selectedProductTypeId"
            @click="handleAddRecipeHeader"
          >创建配方</el-button>
          <!-- Phase C: 一键重算出成率按钮 (gated on production:read_write) -->
          <el-button
            v-if="canWrite"
            :icon="MagicStick"
            style="margin-left: 12px;"
            @click="handleOpenRecalcPreview"
          >一键重算出成率</el-button>
        </div>
        <div v-if="canViewPrice" class="header-right">
          <el-card class="cost-summary-card" shadow="never">
            <div class="cost-summary">
              <div class="cost-item">
                <span class="cost-label">原料成本:</span>
                <span class="cost-value">{{ materialCostTotal.toFixed(2) }}</span>
              </div>
              <div class="cost-item">
                <span class="cost-label">人工成本:</span>
                <span class="cost-value">{{ laborCostTotal.toFixed(2) }}</span>
              </div>
              <div class="cost-item">
                <span class="cost-label">均摊费用:</span>
                <span class="cost-value">{{ overheadCostTotal.toFixed(2) }}</span>
              </div>
              <div class="cost-item total">
                <span class="cost-label">总成本:</span>
                <span class="cost-value">{{ totalCost.toFixed(2) }} 元/kg</span>
              </div>
              <!-- Issue 11: Per-serving cost -->
              <div class="cost-item serving">
                <el-input-number
                  v-model="standardServingWeight"
                  :min="0.01" :max="100" :precision="2" :step="0.1"
                  size="small"
                  style="width: 90px;"
                />
                <span class="cost-label" style="margin-left: 4px;">kg/份</span>
                <span class="cost-value" style="margin-left: 8px;">{{ costPerServing.toFixed(2) }} 元/份</span>
              </div>
            </div>
          </el-card>
        </div>
      </div>
    </el-card>

    <!-- BOM Recipe Status Card — shows DRAFT/ACTIVE/ARCHIVED status + 激活 button -->
    <el-card
      v-if="selectedProductTypeId"
      class="recipe-status-card table-card"
      shadow="never"
    >
      <template #header>
        <div class="table-header">
          <span class="table-title">BOM 配方版本</span>
          <div class="table-actions">
            <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" @click="handleAddRecipeHeader">
              创建配方
            </el-button>
            <el-button size="small" :icon="Refresh" :loading="bomRecipesLoading" @click="loadBomRecipes">
              刷新
            </el-button>
          </div>
        </div>
      </template>
      <el-table
        v-loading="bomRecipesLoading"
        :data="bomRecipes"
        stripe
        border
        size="small"
        style="width: 100%"
        empty-text="该产品暂无 BOM 配方记录"
      >
        <el-table-column prop="recipeCode" label="配方编号" width="160" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="60" align="center">
          <template #default="{ row }">
            v{{ row.version }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="recipeStatusTagType[row.status as BomRecipeStatus]"
              size="small"
              disable-transitions
            >
              {{ recipeStatusLabel[row.status as BomRecipeStatus] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前生效" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isCurrent" type="success" size="small" disable-transitions>是</el-tag>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="激活时间" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.activatedAt">{{ row.activatedAt.substring(0, 10) }}</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="每单位产出" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.outputQuantityPerUnit != null">
              {{ Number(row.outputQuantityPerUnit).toFixed(4) }} {{ row.outputUnit || '' }}
            </span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="整体出成率" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.overallYieldRate != null">{{ Number(row.overallYieldRate).toFixed(2) }}%</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="总成本" width="100" align="right">
          <template #default="{ row }">
            <span v-if="canViewPrice && row.totalCost != null">
              {{ Number(row.totalCost).toFixed(2) }}
            </span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              type="primary"
              link
              size="small"
              @click="handleEditRecipeHeader(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              type="success"
              size="small"
              :loading="activatingRecipeId === row.id"
              @click="handleActivateRecipe(row)"
            >
              激活
            </el-button>
            <span v-else-if="row.status === 'ACTIVE'" class="recipe-active-label">已生效</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="recipe-status-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>仅 <strong>草稿 (DRAFT)</strong> 配方可激活；激活后同产品其他配方自动归档。</span>
      </div>
    </el-card>

    <!-- Main Content -->
    <div class="tables-container">
      <!-- BOM Items Table (原辅料需求明细表) -->
      <el-card class="table-card" shadow="never">
        <template #header>
          <div class="table-header">
            <span class="table-title">原辅料需求明细表</span>
            <div class="table-actions">
              <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" @click="handleAddBomItem">
                添加
              </el-button>
              <el-button size="small" :icon="Download" @click="exportToExcel('material')">导出</el-button>
            </div>
          </div>
        </template>
        <el-tabs v-model="activeCategoryTab" class="bom-category-tabs">
          <el-tab-pane name="RAW" :label="`原料 (${rawItems.length})`" />
          <el-tab-pane name="AUXILIARY" :label="`辅料 (${auxiliaryItems.length})`" />
          <el-tab-pane name="PACKAGING" :label="`包材 (${packagingItems.length})`" />
        </el-tabs>
        <el-table empty-text="暂无数据" :data="currentTabItems" v-loading="loading" stripe border size="small" style="width: 100%"
          :row-class-name="({ row }: { row: TableRow }) => row._isCategoryHeader ? 'category-header-row' : (row.yieldRate == null ? 'yield-pending-row' : '')">
          <!-- Issue 12: Show material category column -->
          <el-table-column prop="materialCategory" label="类型" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.materialCategory === '原材料' ? '' : row.materialCategory === '包材' ? 'warning' : 'info'" disable-transitions>
                {{ row.materialCategory || row.category || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="materialName" label="物料名称" min-width="120" show-overflow-tooltip />
          <el-table-column label="可选" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.isOptional" type="info" size="small" disable-transitions>可选</el-tag>
              <span v-else class="text-secondary">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="substituteGroup" label="替代组" width="100" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.substituteGroup">{{ row.substituteGroup }}</span>
              <span v-else class="text-secondary">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="standardQuantity" label="成品含量(g)" width="100" align="right">
            <template #default="{ row }">
              {{ (row.standardQuantity || 0).toFixed(4) }}
            </template>
          </el-table-column>
          <!-- Phase A: yieldRate null → 显示待评估 badge -->
          <!-- GAP F3: 出成率 >100 (保水/腌制等增重工序) 合法, 带 tooltip 说明 -->
          <el-table-column prop="yieldRate" label="出成率%" width="110" align="right">
            <template #default="{ row }">
              <el-tag v-if="row.yieldRate == null" type="warning" size="small" disable-transitions>待评估</el-tag>
              <template v-else>
                <el-tooltip
                  v-if="(row.yieldRate as number) > 100"
                  content="增重工序（如保水）出成率可超过100%"
                  placement="top"
                >
                  <span class="yield-over100">{{ (row.yieldRate as number).toFixed(2) }}%</span>
                </el-tooltip>
                <span v-else>{{ (row.yieldRate as number).toFixed(2) }}%</span>
              </template>
            </template>
          </el-table-column>
          <!-- Issue 13: Conversion rate inline -->
          <el-table-column label="转换率" width="80" align="right">
            <template #default="{ row }">
              {{ row.conversionRate ? row.conversionRate.toFixed(4) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="原料投量/份" width="100" align="right">
            <template #default="{ row }">
              {{ row.conversionRate
                ? ((row.standardQuantity || 0) / row.conversionRate).toFixed(4)
                : ((row.standardQuantity || 0) / ((row.yieldRate != null ? row.yieldRate : 100) / 100)).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" align="center" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价(含税)" width="90" align="right">
            <template #default="{ row }">
              {{ (row.unitPrice || 0).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="taxRate" label="税率%" width="70" align="right">
            <template #default="{ row }">
              {{ (row.taxRate || 0).toFixed(0) }}%
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="90" align="right">
            <template #default="{ row }">
              {{ (((row.standardQuantity || 0) / ((row.yieldRate != null ? row.yieldRate : 100) / 100)) * (row.unitPrice || 0)).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="primary" link size="small" :icon="Edit" @click="handleEditBomItem(row)" />
              <el-button v-if="canWrite" type="danger" link size="small" :icon="Delete" @click="handleDeleteBomItem(row)" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canViewPrice" class="table-footer">
          <span class="total-label">原料成本合计:</span>
          <span class="total-value">{{ materialCostTotal.toFixed(2) }} 元</span>
        </div>
      </el-card>

      <!-- Labor Cost Table (人工费用表) -->
      <el-card class="table-card" shadow="never">
        <template #header>
          <div class="table-header">
            <span class="table-title">人工费用表</span>
            <div class="table-actions">
              <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" @click="handleAddLaborCost">
                添加
              </el-button>
              <el-button size="small" :icon="Download" @click="exportToExcel('labor')">导出</el-button>
            </div>
          </div>
        </template>
        <el-table :data="laborCosts" stripe border size="small" style="width: 100%">
          <el-table-column prop="processName" label="工序名称" min-width="120" show-overflow-tooltip />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="工序单价" width="90" align="right">
            <template #default="{ row }">
              {{ (row.unitPrice || 0).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="priceUnit" label="工序单位" width="80" align="center" />
          <el-table-column prop="standardQuantity" label="操作量" width="80" align="right">
            <template #default="{ row }">
              {{ (row.standardQuantity || 1).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="费用小计" width="100" align="right">
            <template #default="{ row }">
              {{ ((row.unitPrice || 0) * (row.standardQuantity || 1)).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column prop="processCategory" label="工序大类" width="100" show-overflow-tooltip />
          <el-table-column label="操作" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="primary" link size="small" :icon="Edit" @click="handleEditLaborCost(row)" />
              <el-button v-if="canWrite" type="danger" link size="small" :icon="Delete" @click="handleDeleteLaborCost(row)" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canViewPrice" class="table-footer">
          <span class="total-label">人工费用合计:</span>
          <span class="total-value">{{ laborCostTotal.toFixed(4) }} 元</span>
        </div>
      </el-card>

      <!-- Overhead Cost Table (均摊费用表) -->
      <el-card class="table-card" shadow="never">
        <template #header>
          <div class="table-header">
            <span class="table-title">均摊费用表</span>
            <div class="table-actions">
              <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" @click="handleAddOverheadCost">
                添加
              </el-button>
              <el-button size="small" :icon="Download" @click="exportToExcel('overhead')">导出</el-button>
            </div>
          </div>
        </template>
        <el-table :data="overheadCosts" stripe border size="small" style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="90" align="right">
            <template #default="{ row }">
              {{ (row.unitPrice || 0).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="priceUnit" label="分摊单位" width="80" align="center" />
          <el-table-column prop="allocationRate" label="分摊量" width="80" align="right">
            <template #default="{ row }">
              {{ (row.allocationRate || 1).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="费用小计" width="100" align="right">
            <template #default="{ row }">
              {{ ((row.unitPrice || 0) * (row.allocationRate || 1)).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column prop="category" label="费用类别" width="100" show-overflow-tooltip />
          <el-table-column label="操作" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="primary" link size="small" :icon="Edit" @click="handleEditOverheadCost(row)" />
              <el-button v-if="canWrite" type="danger" link size="small" :icon="Delete" @click="handleDeleteOverheadCost(row)" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canViewPrice" class="table-footer">
          <span class="total-label">均摊费用合计:</span>
          <span class="total-value">{{ overheadCostTotal.toFixed(4) }} 元</span>
        </div>
      </el-card>
    </div>

    <!-- BOM Recipe Header Dialog -->
    <el-dialog
      v-model="recipeDialogVisible"
      :title="isRecipeEdit ? '编辑配方头' : '创建配方头'"
      width="560px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px;"
      >
        <template #title>
          这里维护 BomRecipe 配方头，不是原辅料行项目
        </template>
        <template #default>
          创建配方头会使用当前原辅料明细生成草稿配方；编辑配方头只修改每单位产出量、产出单位和整体出成率。
        </template>
      </el-alert>
      <el-form :model="recipeForm" label-width="130px">
        <el-form-item label="产品">
          <el-input :model-value="selectedProductName || selectedProductTypeId" disabled />
        </el-form-item>
        <el-form-item label="每单位产出量" required>
          <el-input-number
            v-model="recipeForm.outputQuantityPerUnit"
            :min="0.0001"
            :precision="4"
            :step="1"
            placeholder="例：1 或 200"
            style="width: 100%"
          />
          <div class="form-tip">BomRecipe.outputQuantityPerUnit，必须大于 0；例如 1 份、200 g、0.5 kg。</div>
        </el-form-item>
        <el-form-item label="产出单位" required>
          <el-input
            v-model="recipeForm.outputUnit"
            maxlength="20"
            show-word-limit
            placeholder="例：份 / g / kg / 盒"
          />
          <div class="form-tip">BomRecipe.outputUnit，用于嵌套 BOM 成本、营养标签和添加剂合规换算。</div>
        </el-form-item>
        <el-form-item label="整体出成率%" required>
          <el-input-number
            v-model="recipeForm.overallYieldRate"
            :min="0.01"
            :max="100"
            :precision="2"
            :step="1"
            placeholder="默认 100"
            style="width: 100%"
          />
          <div class="form-tip">BomRecipe.overallYieldRate，范围 0.01–100；行项目出成率仍在原辅料弹窗维护。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="recipeForm.notes"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可填写配方头说明，例如适用门店、口径或版本原因"
          />
        </el-form-item>
      </el-form>
      <div v-if="!isRecipeEdit" class="recipe-create-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>创建时会把当前原辅料明细作为 CreateBomRecipeRequest.items；请先确认每行已关联原料类型且成品含量大于 0。</span>
      </div>
      <template #footer>
        <el-button @click="recipeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="recipeDialogLoading" @click="submitRecipeHeaderForm">
          {{ isRecipeEdit ? '保存配方头' : '创建草稿配方' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- BOM Item Dialog -->
    <el-dialog v-model="bomDialogVisible" :title="isBomEdit ? '编辑原辅料' : '添加原辅料'" width="580px">
      <el-form :model="bomForm" label-width="110px">
        <el-form-item label="物料名称" required>
          <el-input v-model="bomForm.materialName" placeholder="请输入物料名称" />
        </el-form-item>
        <el-form-item label="物料类别" required>
          <el-select v-model="bomForm.materialCategory" style="width: 100%">
            <el-option label="原料" value="RAW" />
            <el-option label="辅料" value="AUXILIARY" />
            <el-option label="包材" value="PACKAGING" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联原料">
          <el-select v-model="bomForm.materialTypeId" placeholder="选择原料类型(可选)" clearable style="width: 100%" @change="onMaterialLink">
            <el-option
              v-for="item in materialTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <!-- Phase A: 成品含量 RAW/AUXILIARY 固定显示「克 (g)」后缀 -->
        <el-form-item label="成品含量" required>
          <div style="display: flex; align-items: center; gap: 8px; width: 100%;">
            <el-input-number
              v-model="bomForm.standardQuantity"
              :min="0"
              :precision="4"
              :step="0.01"
              style="flex: 1;"
            />
            <span
              v-if="bomForm.materialCategory !== 'PACKAGING'"
              class="unit-suffix"
            >克 (g)</span>
            <span v-else class="unit-suffix">{{ bomForm.unit }}</span>
          </div>
          <div class="form-tip">
            成品含量 = 每份成品中该物料的克数（来自产品标准克重）
          </div>
        </el-form-item>
        <!-- Phase B: 评估按钮 (RAW 类别时显示) -->
        <el-form-item
          v-if="bomForm.materialCategory === 'RAW'"
          label="出成率评估"
        >
          <div style="width: 100%;">
            <el-button
              :loading="estimateLoading"
              :disabled="!selectedProductTypeId"
              @click="handleEstimate"
            >
              评估建议出成率
            </el-button>
            <!-- 评估结果展示 -->
            <div v-if="estimateResult" class="estimate-result">
              <template v-if="estimateResult.reason === 'NO_GRAMS_PER_UNIT'">
                <el-alert
                  type="warning"
                  :closable="false"
                  show-icon
                  :title="estimateResult.actionHint || '请先在系统管理→产品维护填写标准克重'"
                  style="margin-top: 8px;"
                />
              </template>
              <template v-else-if="estimateResult.suggestedYieldRate != null">
                <div class="estimate-detail">
                  <el-tag type="success" size="small">建议出成率: {{ estimateResult.suggestedYieldRate.toFixed(2) }}%</el-tag>
                  <span v-if="estimateResult.source === 'BATCH_REPORTING'" class="estimate-source">
                    基于最近 {{ estimateResult.sampleCount }} 批报工
                    <template v-if="estimateResult.yieldMin != null && estimateResult.yieldMax != null">
                      (范围 {{ estimateResult.yieldMin.toFixed(1) }}%–{{ estimateResult.yieldMax.toFixed(1) }}%)
                    </template>
                  </span>
                  <span v-else-if="estimateResult.source === 'STANDARD_WEIGHT_ONLY'" class="estimate-source">
                    仅带入标准克重 (暂无批次报工数据)
                  </span>
                </div>
              </template>
              <template v-else-if="estimateResult.reason === 'INSUFFICIENT_SAMPLES'">
                <el-alert
                  type="info"
                  :closable="false"
                  show-icon
                  title="样本数据不足，暂无出成率建议"
                  style="margin-top: 8px;"
                />
              </template>
              <template v-else>
                <el-alert
                  type="info"
                  :closable="false"
                  show-icon
                  title="暂无出成率建议数据"
                  style="margin-top: 8px;"
                />
              </template>
            </div>
          </div>
        </el-form-item>
        <!-- 出成率输入 (在评估按钮之后, 成品含量之后) -->
        <!-- GAP F3: 无客户端 ≤100 校验, 增重工序 (保水/腌制) 出成率合法超 100% -->
        <el-form-item label="出成率%">
          <el-input-number
            v-model="bomForm.yieldRate"
            :min="0"
            :max="999"
            :precision="2"
            :step="1"
            :placeholder="bomForm.yieldRate == null ? '出成率待评估' : ''"
            style="width: 100%"
          />
          <div v-if="bomForm.yieldRate == null" class="form-tip form-tip--warning">
            出成率为空时保存后显示「待评估」，后端使用标准克重原样展开
          </div>
          <div v-else-if="bomForm.yieldRate > 100" class="form-tip form-tip--over100">
            增重工序（如保水、腌制）出成率可超过100%，属正常情况
          </div>
          <div v-else class="form-tip">输入百分比数值，如 61 表示 61%</div>
        </el-form-item>
        <!-- D2: 实时显示实际原料用量 (考虑出成率) -->
        <!-- GAP F7: yieldRate null → 显示「待评估」, 不显示误导性数字 -->
        <el-form-item label="实际原料用量">
          <div :class="computedActualQuantity == null ? 'bom-computed-quantity bom-computed-quantity--pending' : 'bom-computed-quantity'">
            <template v-if="computedActualQuantity == null">
              <el-tag type="warning" size="small" disable-transitions>待评估</el-tag>
              <span class="bom-computed-quantity__hint"> (出成率未填，暂无法计算)</span>
            </template>
            <template v-else>
              {{ computedActualQuantity.toFixed(4) }}
              <span v-if="bomForm.materialCategory !== 'PACKAGING'"> 克 (g)</span>
              <span v-else> {{ bomForm.unit }}</span>
            </template>
          </div>
          <div class="form-tip">
            = 成品含量 ÷ (出成率/100) | 示例: 200g 成品 × 58% 出成率 → 自动算原料 344.83g
          </div>
        </el-form-item>
        <!-- Phase A: 计量单位 (RAW/AUXILIARY 锁定 g, PACKAGING 可选) -->
        <el-form-item label="计量单位">
          <template v-if="bomForm.materialCategory !== 'PACKAGING'">
            <el-input value="克 (g)" disabled style="width: 100%;" />
            <div class="form-tip">D3: BOM 配方层 RAW/辅料 固定用 g，系统调拨时自动按 1:1000 换算为 kg</div>
          </template>
          <template v-else>
            <el-select v-model="bomForm.unit" placeholder="选择单位" style="width: 100%">
              <el-option label="克 (g)" value="g" />
              <el-option label="千克 (kg)" value="kg" />
              <el-option label="毫升 (mL)" value="mL" />
              <el-option label="升 (L)" value="L" />
              <el-option label="件 (pcs)" value="pcs" />
            </el-select>
            <div class="form-tip">包材单位按实际填写</div>
          </template>
        </el-form-item>
        <!-- #759: 包材每产品单位用量 (PACKAGING 专属, 配置后 BOM 标准用量可自动推算) -->
        <el-form-item
          v-if="bomForm.materialCategory === 'PACKAGING'"
          label="每产品用量"
        >
          <el-input-number
            v-model="bomForm.packQtyPerProduct"
            :min="0"
            :precision="6"
            :step="0.1"
            style="width: 100%"
            placeholder="每个成品单位需要该包材多少个"
          />
          <div class="form-tip">
            例：吸塑盒 1 个/成品填 1；外箱 20 盒/箱填 0.05。留空则手填成品含量
          </div>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="单价（含税）">
          <el-input-number v-model="bomForm.unitPrice" :min="0" :precision="4" :step="0.1" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="税率%">
          <el-input-number v-model="bomForm.taxRate" :min="0" :max="100" :precision="0" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="bomForm.notes" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可选料">
          <el-checkbox v-model="bomForm.isOptional">
            可选原辅料，不作为生产计划完整性硬要求
          </el-checkbox>
          <div class="form-tip">对应 BomRecipeItemDTO.isOptional，适用于装饰菜、可省略配料等。</div>
        </el-form-item>
        <el-form-item label="替代料分组">
          <el-input
            v-model="bomForm.substituteGroup"
            maxlength="50"
            show-word-limit
            placeholder="例：MEAT_BASE / SAUCE_ALT，同组物料可互相替代"
          />
          <div class="form-tip">对应 BomRecipeItemDTO.substituteGroup；相同分组表示互为替代料。</div>
        </el-form-item>
        <!-- SP4-8: 按份数投料 -->
        <el-form-item label="按份数投料">
          <el-tooltip
            content="勾选后按成品份数投料，不随出成率折算（适用于调味料、添加剂等固定添加量的物料）"
            placement="top"
          >
            <el-checkbox v-model="bomForm.perPortion">
              按成品份数投料，不随出成率折算
            </el-checkbox>
          </el-tooltip>
        </el-form-item>
        <!-- SP8: 组合装半成品引用 -->
        <el-form-item label="半成品引用">
          <el-select
            v-model="bomForm.semiFinishedRefCode"
            placeholder="组合装引用半成品（可选）"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="item in semiFinishedTypes"
              :key="item.id"
              :label="item.name"
              :value="item.code || item.id"
            />
          </el-select>
          <div class="form-tip">仅组合装产品需填写，引用半成品作为配方原料</div>
        </el-form-item>
        <!-- SP12 #728: 组合装子产品 / 先做后用嵌套 BOM -->
        <el-form-item label="嵌套子产品">
          <el-select
            v-model="bomForm.subProductTypeId"
            placeholder="嵌套子产品（触发递归 BOM 成本）"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="item in semiFinishedTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div class="form-tip">SP1 嵌套 BOM：非空时成本引用子产品 BOM 总成本，不用本行单价。配合"半成品引用"支持"先做后用"移动均价。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bomDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="bomDialogLoading" @click="submitBomForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- Labor Cost Dialog -->
    <el-dialog v-model="laborDialogVisible" :title="isLaborEdit ? '编辑人工费用' : '添加人工费用'" width="500px">
      <el-form :model="laborForm" label-width="100px">
        <el-form-item label="工序名称" required>
          <el-input v-model="laborForm.processName" placeholder="请输入工序名称" />
        </el-form-item>
        <el-form-item label="工序大类">
          <el-select v-model="laborForm.processCategory" placeholder="选择工序类别" clearable style="width: 100%">
            <el-option v-for="cat in processCategories" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="工序单价" required>
          <el-input-number v-model="laborForm.unitPrice" :min="0" :precision="4" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="工序单位">
          <el-input v-model="laborForm.priceUnit" placeholder="如: 元/kg" />
        </el-form-item>
        <el-form-item label="操作量">
          <el-input-number v-model="laborForm.standardQuantity" :min="0" :precision="2" :step="0.1" style="width: 100%" />
        </el-form-item>
        <!-- Issue 10: Real-time subtotal calculation -->
        <el-form-item v-if="canViewPrice" label="费用小计">
          <div class="labor-subtotal">
            {{ ((laborForm.unitPrice || 0) * (laborForm.standardQuantity || 1)).toFixed(4) }} 元
          </div>
          <div class="form-tip">= 工序单价 × 操作量</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="laborForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="laborDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="laborDialogLoading" @click="submitLaborForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- Overhead Cost Dialog -->
    <el-dialog v-model="overheadDialogVisible" :title="isOverheadEdit ? '编辑均摊费用' : '添加均摊费用'" width="500px">
      <el-form :model="overheadForm" label-width="100px">
        <el-form-item label="费用名称" required>
          <el-input v-model="overheadForm.name" placeholder="请输入费用名称" />
        </el-form-item>
        <el-form-item label="费用类别">
          <el-select v-model="overheadForm.category" placeholder="选择费用类别" clearable style="width: 100%">
            <el-option v-for="cat in overheadCategories" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="单价" required>
          <el-input-number v-model="overheadForm.unitPrice" :min="0" :precision="4" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="分摊单位">
          <el-input v-model="overheadForm.priceUnit" placeholder="如: 元/kg" />
        </el-form-item>
        <el-form-item label="分摊量">
          <el-input-number v-model="overheadForm.allocationRate" :min="0" :precision="2" :step="0.1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="overheadForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="overheadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="overheadDialogLoading" @click="submitOverheadForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- BOM Change Log Drawer (P1-9) -->
    <BomChangeLog v-model:visible="changeLogVisible" :factory-id="factoryId" :product-type-id="selectedProductTypeId" />

    <!-- Phase C: 一键重算出成率 预览抽屉 -->
    <el-drawer
      v-model="recalcPreviewVisible"
      title="一键重算 BOM 出成率 — 预览"
      size="780px"
      :destroy-on-close="true"
    >
      <div v-loading="recalcPreviewLoading" class="recalc-drawer">
        <el-empty
          v-if="!recalcPreviewLoading && recalcPreviewRows.length === 0"
          description="暂无可更新的 BOM 出成率数据"
        />
        <template v-else-if="!recalcPreviewLoading">
          <div class="recalc-hint">
            共 {{ recalcPreviewRows.length }} 条，{{ recalcUpdatableCount }} 条可更新，已勾选 {{ recalcSelectedCount }} 条。
            状态「数据不足」和「跳过」不可勾选。
          </div>
          <el-table
            :data="recalcPreviewRows"
            stripe
            border
            size="small"
            style="width: 100%;"
            @selection-change="handleRecalcSelectionChange"
          >
            <el-table-column type="selection" width="44" :selectable="(row: RecalculatePreviewRow) => row.status === 'UPDATABLE'" />
            <el-table-column prop="productName" label="产品" min-width="110" show-overflow-tooltip />
            <el-table-column prop="materialName" label="主原料" min-width="110" show-overflow-tooltip />
            <!-- GAP F3: >100 带 tooltip 说明 -->
            <el-table-column label="当前出成率%" width="110" align="right">
              <template #default="{ row }">
                <el-tag v-if="row.currentYieldRate == null" type="warning" size="small" disable-transitions>待评估</el-tag>
                <template v-else>
                  <el-tooltip
                    v-if="(row.currentYieldRate as number) > 100"
                    content="增重工序（如保水）出成率可超过100%"
                    placement="top"
                  >
                    <span class="yield-over100">{{ (row.currentYieldRate as number).toFixed(2) }}%</span>
                  </el-tooltip>
                  <span v-else>{{ (row.currentYieldRate as number).toFixed(2) }}%</span>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="建议出成率%" width="120" align="right">
              <template #default="{ row }">
                <template v-if="row.suggestedYieldRate != null">
                  <el-tooltip
                    v-if="(row.suggestedYieldRate as number) > 100"
                    content="增重工序（如保水）出成率可超过100%"
                    placement="top"
                  >
                    <span class="yield-suggested yield-over100">{{ (row.suggestedYieldRate as number).toFixed(2) }}%</span>
                  </el-tooltip>
                  <span v-else class="yield-suggested">{{ (row.suggestedYieldRate as number).toFixed(2) }}%</span>
                </template>
                <span v-else class="yield-na">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="sampleCount" label="样本数" width="70" align="right" />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag
                  :type="row.status === 'UPDATABLE' ? 'success' : row.status === 'INSUFFICIENT_SAMPLES' ? 'info' : 'warning'"
                  size="small"
                  disable-transitions
                >
                  {{ row.status === 'UPDATABLE' ? '可更新' : row.status === 'INSUFFICIENT_SAMPLES' ? '数据不足' : '跳过' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="recalcPreviewVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="recalcApplyLoading"
          :disabled="recalcSelectedCount === 0"
          @click="handleApplyRecalc"
        >
          确认应用 ({{ recalcSelectedCount }} 条)
        </el-button>
      </template>
    </el-drawer>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.bom-page {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: auto;
}

.header-card {
  flex-shrink: 0;

  :deep(.el-card__body) {
    padding: 16px 20px;
  }
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.header-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
}

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.cost-summary-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 12px 16px;
  }
}

.cost-summary {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.cost-item {
  display: flex;
  align-items: center;
  gap: 6px;

  .cost-label {
    font-size: 13px;
    color: rgba(255, 255, 255, 0.85);
  }

  .cost-value {
    font-size: 14px;
    font-weight: 600;
    color: #fff;
  }

  &.total {
    padding-left: 16px;
    border-left: 1px solid rgba(255, 255, 255, 0.3);

    .cost-label {
      font-size: 14px;
      color: #fff;
    }

    .cost-value {
      font-size: 18px;
      color: #ffd700;
    }
  }
}

.tables-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.table-card {
  flex-shrink: 0;

  :deep(.el-card__header) {
    padding: 12px 16px;
    background: #fafafa;
    border-bottom: 1px solid #ebeef5;
  }

  :deep(.el-card__body) {
    padding: 16px;
  }
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.table-actions {
  display: flex;
  gap: 8px;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid #ebeef5;

  .total-label {
    font-size: 14px;
    color: #606266;
    margin-right: 8px;
  }

  .total-value {
    font-size: 16px;
    font-weight: 600;
    color: #e6a23c;
  }
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;

  &--warning {
    color: #e6a23c;
  }
}

.labor-subtotal {
  font-size: 16px;
  font-weight: 600;
  color: #e6a23c;
  line-height: 32px;
}

/* D2: 实际原料用量实时计算显示 — 样式迁移到 GAP F7 block 下方 */

.cost-item.serving {
  display: flex;
  align-items: center;

  .cost-label {
    font-size: 12px;
    color: rgba(255, 255, 255, 0.75);
  }

  .cost-value {
    font-size: 15px;
    font-weight: 600;
    color: #90ee90;
  }
}

/* Phase A: 固定单位后缀 */
.unit-suffix {
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
  padding: 0 6px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #f5f7fa;
  line-height: 32px;
}

/* Phase A: 出成率待评估行 — 黄色高亮 */
:deep(.yield-pending-row) {
  background-color: #fdf6ec !important;

  td {
    background-color: #fdf6ec !important;
  }
}

/* Phase B: 评估结果区域 */
.estimate-result {
  margin-top: 8px;
  width: 100%;
}

.estimate-detail {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.estimate-source {
  font-size: 12px;
  color: #909399;
}

/* Phase C: 重算预览抽屉 */
.recalc-drawer {
  padding: 0 4px;
}

.recalc-hint {
  font-size: 13px;
  color: #606266;
  margin-bottom: 12px;
}

.yield-suggested {
  color: #67c23a;
  font-weight: 600;
}

.yield-na {
  color: #c0c4cc;
}

/* GAP F7: 待评估实际原料用量 */
.bom-computed-quantity {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 600;
  color: #67c23a;
  line-height: 32px;

  &--pending {
    color: #e6a23c;
    font-size: 14px;
    font-weight: normal;
  }

  &__hint {
    font-size: 12px;
    color: #909399;
    font-weight: normal;
  }
}

/* GAP F3: 出成率 >100 的显示 */
.yield-over100 {
  color: #409eff;
  cursor: help;
  border-bottom: 1px dashed #409eff;
}

/* GAP F3: 出成率输入 >100 提示 */
.form-tip--over100 {
  font-size: 12px;
  color: #409eff;
  margin-top: 4px;
}

/* BOM Recipe Status Card */
.recipe-status-card {
  flex-shrink: 0;
}

.recipe-status-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;

  .el-icon {
    color: #909399;
    flex-shrink: 0;
  }
}

.recipe-create-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 12px;
  margin-top: 4px;
  border-radius: 6px;
  background: #f4f8ff;
  color: #606266;
  font-size: 12px;
  line-height: 1.5;

  .el-icon {
    flex-shrink: 0;
    margin-top: 2px;
    color: #409eff;
  }
}

.recipe-active-label {
  font-size: 12px;
  color: #67c23a;
  font-weight: 500;
}

.text-secondary {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
