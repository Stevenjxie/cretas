<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { bomYieldEstimateApi, bomRecipeApi, bomSeasoningApi, batchImportBomItems } from '@/api/bom';
import type {
  YieldEstimateResponse,
  BomRecipeSummary,
  BomRecipeStatus,
  BomRecipeItemPayload,
  CreateBomRecipeRequest,
  UpdateBomRecipeRequest,
  BomImportRow,
  BomCopyCandidate,
  CopyBomToProductRequest,
} from '@/api/bom';
import * as XLSX from 'xlsx';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete, Download, Refresh, InfoFilled } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import BomChangeLog from './BomChangeLog.vue'
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue'
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue'
import BomAuxiliaryWorkspace from './seasoning/BomAuxiliaryWorkspace.vue'
import BomCopySuggestionDialog from './BomCopySuggestionDialog.vue'
import type { TableRow } from '@/types/api';
// 客户张权反馈 (2026-07-02): "辅料 添加剂全混在一起了" — 「添加原辅料」对话框的「关联原料」
// 下拉需按上方「物料类别」筛选, 归类逻辑复用 procurement/receives/list.vue 同款共享工具。
import { bigCategoryOf, type BigCategory } from '@/utils/materialCategory';
import {
  canonicalUnitCode,
  displayUnit,
  formatPriceUnit,
  pricingAmountPreview,
} from '@/utils/unitPricing';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const route = useRoute();
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
  ARCHIVED: '历史 / 未生效',
};
const MAX_RECIPE_VERSIONS = 10;

/** All BOM recipes for the currently selected product (newest first) */
const bomRecipes = ref<BomRecipeSummary[]>([]);
const bomRecipesLoading = ref(false);
const activatingRecipeId = ref<string | null>(null);
const deletingRecipeId = ref<string | null>(null);
const selectedRecipeId = ref<string>('');
const historicalYield = ref<YieldEstimateResponse | null>(null);
const historicalYieldLoadFailed = ref(false);
const selectedRecipe = computed(() =>
  bomRecipes.value.find((recipe) => recipe.id === selectedRecipeId.value) ?? null,
);
const recipeVersionLimitReached = computed(() => bomRecipes.value.length >= MAX_RECIPE_VERSIONS);

function formatFriendlyNumber(value: unknown, maxDecimals = 4): string {
  const number = Number(value);
  if (!Number.isFinite(number)) return '—';
  return number.toFixed(maxDecimals).replace(/\.?0+$/, '');
}

async function loadBomRecipes(preferredRecipeId?: string) {
  if (!factoryId.value || !selectedProductTypeId.value) {
    bomRecipes.value = [];
    selectedRecipeId.value = '';
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
      const preferred = preferredRecipeId
        ? bomRecipes.value.find((recipe) => recipe.id === preferredRecipeId)
        : undefined;
      const draft = bomRecipes.value.find((recipe) => recipe.status === 'DRAFT');
      const currentActive = bomRecipes.value.find(
        (recipe) => recipe.status === 'ACTIVE' && recipe.isCurrent,
      );
      selectedRecipeId.value = (preferred || draft || currentActive || bomRecipes.value[0])?.id || '';
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

async function handleCloneRecipe(recipe: BomRecipeSummary) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `将 ${recipe.productName} v${recipe.version} 复制为新草稿后编辑，原版本和历史记录不受影响。`,
      '克隆 BOM 版本',
      { confirmButtonText: '克隆为草稿', cancelButtonText: '取消', type: 'info' },
    );
  } catch {
    return;
  }
  try {
    const response = await bomRecipeApi.clone(factoryId.value, recipe.id);
    if (!response.success || !response.data) throw new Error(response.message || '克隆失败');
    await loadBomRecipes(response.data.id);
    ElMessage.success('已复制为新草稿，可编辑配方和工序辅料');
  } catch (error: unknown) {
    ElMessage.error((error as { message?: string }).message || '克隆 BOM 版本失败');
  }
}

async function handleCloneSelectedRecipe() {
  if (selectedRecipe.value) await handleCloneRecipe(selectedRecipe.value);
}

async function handleDeleteRecipe(recipe: BomRecipeSummary) {
  if (!factoryId.value || recipe.status === 'ACTIVE') return;
  try {
    await ElMessageBox.confirm(
      `确认删除${recipe.status === 'DRAFT' ? '草稿' : '历史版本'} ${recipe.productName} v${recipe.version}？删除后不可恢复。`,
      '删除 BOM 版本',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  deletingRecipeId.value = recipe.id;
  try {
    const response = await bomRecipeApi.removeDraft(factoryId.value, recipe.id);
    if (!response.success) throw new Error(response.message || '删除失败');
    ElMessage.success('BOM 版本已删除');
    await loadBomRecipes();
  } catch (error: unknown) {
    ElMessage.error((error as { message?: string }).message || '删除 BOM 版本失败');
  } finally {
    deletingRecipeId.value = null;
  }
}

async function loadHistoricalYield() {
  historicalYieldLoadFailed.value = false;
  if (!factoryId.value || !selectedProductTypeId.value) {
    historicalYield.value = null;
    return;
  }
  try {
    const response = await bomYieldEstimateApi.getEstimate(factoryId.value, selectedProductTypeId.value, 'RAW');
    historicalYield.value = response.success && response.data ? response.data : null;
  } catch {
    historicalYield.value = null;
    historicalYieldLoadFailed.value = true;
  }
}

async function handleSeasoningWorkspaceChanged() {
  await Promise.all([loadBomRecipes(selectedRecipeId.value), loadCostSummary()]);
}

async function handleActivateRecipe(recipe: BomRecipeSummary) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `激活后 ${recipe.productName} v${recipe.version} 将成为唯一生效 BOM。仅之后新建的生产计划采用此版本；已有生产计划快照和已激活 Workflow 不受影响。确认激活？`,
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
const bomCopyDialogVisible = ref(false);
const bomCopyCandidates = ref<BomCopyCandidate[]>([]);
const bomCopyCandidatesLoading = ref(false);
const bomCopySubmitting = ref(false);
const automaticallyPromptedProductIds = new Set<string>();
// :key 强制 el-select 重新挂载 —— element-plus 的 currentLabel 是渲染时按当前 options
// 匹配一次后缓存的, 异步 (route 带 productTypeId 跳转) 补写 options + modelValue 不会
// 触发它重新求值, 导致选中态生效但下拉框标签显示空白 (同款坑见 ReferenceSelector.vue)。
const bomProductSelectKey = ref(0);
const productTypes = ref<TableRow[]>([]);
interface BomCostSummaryView {
  materialCostTotal?: number | null;
  laborCostTotal?: number | null;
  overheadCostTotal?: number | null;
  totalCost?: number | null;
  costUnit?: string | null;
}
const costSummary = ref<BomCostSummaryView | null>(null);
const selectedProductName = computed(() => {
  const product = productTypes.value.find((item) => item.id === selectedProductTypeId.value);
  return String(product?.name || '');
});

// Phase 1: 配方头产出规格改为从 SKU (ProductType) 只读带入，不再让用户手填。
// 产出单位 ← SKU.unit（份/盒/…）；每单位产出量 ← SKU.gramsPerUnit（标准克重，克）。
const selectedProductMeta = ref<Record<string, unknown> | null>(null);
const skuOutputUnit = computed(() => {
  const u = selectedProductMeta.value?.unit;
  return u ? String(u) : '份';
});
const skuGramsPerUnit = computed<number | null>(() => {
  const g = selectedProductMeta.value?.gramsPerUnit;
  if (g == null || g === '') return null;
  const n = Number(g);
  return Number.isFinite(n) && n > 0 ? n : null;
});

// Phase 1: 添加原辅料「高级选项」折叠状态（默认收起）
const showAdvancedBomFields = ref<string[]>([]);

interface RecipeHeaderForm {
  outputQuantityPerUnit: number | null;
  outputUnit: string;
  notes: string;
}

const recipeDialogVisible = ref(false);
const recipeDialogLoading = ref(false);
const isRecipeEdit = ref(false);
const editingRecipeId = ref<string | null>(null);
const recipeForm = ref<RecipeHeaderForm>({
  outputQuantityPerUnit: 1,
  outputUnit: '份',
  notes: '',
});

// BOM Items (原辅料)
interface BomItemRow {
  id?: number | null;
  productTypeId?: string;
  materialTypeId?: string;
  materialName?: string;
  standardQuantity?: number | null;
  yieldRate?: number | null;
  unit?: string;
  quantityUnit?: string;
  unitPrice?: number;
  priceUnit?: string;
  lineAmount?: number;
  convertedPricingQuantity?: number;
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
const bomForm = ref({
  id: null as number | null,
  productTypeId: '',
  materialTypeId: '',
  materialName: '',
  materialCategory: 'RAW',
  standardQuantity: null as number | null,
  // Phase A side-effect: 默认 null, 保存时 null = 出成率待评估 (后端用 standardQuantity 原样)
  yieldRate: null as number | null,
  unit: '',
  quantityUnit: '',
  unitPrice: 0,
  priceUnit: '',
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

const COUNTING_UNIT_ALIASES = new Set(['个', '只', '件', 'pcs', 'pc', 'pce', 'piece', 'pieces']);
const WEIGHT_UNIT_ALIASES = new Set(['g', '克', 'kg', '千克', '公斤', '斤']);
const VOLUME_UNIT_ALIASES = new Set(['ml', 'mL', '毫升', 'l', 'L', '升']);

function normalizeUnitValue(unit: unknown): string {
  return String(unit || '').trim();
}

function isCountingUnit(unit: unknown): boolean {
  return COUNTING_UNIT_ALIASES.has(normalizeUnitValue(unit));
}

function isWeightUnit(unit: unknown): boolean {
  return WEIGHT_UNIT_ALIASES.has(normalizeUnitValue(unit));
}

function isVolumeUnit(unit: unknown): boolean {
  return VOLUME_UNIT_ALIASES.has(normalizeUnitValue(unit));
}

function selectedMaterialRecord(): Record<string, unknown> | undefined {
  return materialTypes.value.find((m: Record<string, unknown>) => m.id === bomForm.value.materialTypeId);
}

function selectedMaterialUnit(): string {
  const material = selectedMaterialRecord();
  return normalizeUnitValue(material?.unit);
}

function recipeUnitForMaterial(material: Record<string, unknown>, category: string): string {
  const materialUnit = canonicalUnitCode(material.quantityUnit || material.unit);
  if (category === 'PACKAGING') return materialUnit || 'pcs';
  return materialUnit;
}

function bomUnitLabel(unit?: unknown): string {
  const u = normalizeUnitValue(unit);
  if (u === 'g') return '克 (g)';
  if (u === 'kg') return '千克 (kg)';
  if (u === 'mL') return '毫升 (mL)';
  if (u === 'L') return '升 (L)';
  if (u === 'pcs') return '件 (pcs)';
  return u || '单位';
}

const bomFormUnitLabel = computed(() => bomUnitLabel(bomForm.value.unit));
const bomUnitIsCounting = computed(() => isCountingUnit(bomForm.value.unit));
const bomUnitOptions = computed(() => {
  const materialUnit = canonicalUnitCode(selectedMaterialUnit());
  if (isWeightUnit(materialUnit)) return ['kg', 'g'];
  if (isVolumeUnit(materialUnit)) return ['L', 'mL'];
  return materialUnit ? [materialUnit] : [];
});

function bomLineAmountPreview(row: BomItemRow) {
  const yieldRate = row.yieldRate != null ? Number(row.yieldRate) : 100;
  const pricingQuantity = Number(row.standardQuantity || 0) / (yieldRate / 100 || 1);
  return pricingAmountPreview({
    ...row,
    quantity: pricingQuantity,
    quantityUnit: row.quantityUnit || row.unit,
  });
}

function knownUnitFamily(unit: unknown): 'weight' | 'volume' | 'count' | null {
  if (isWeightUnit(unit)) return 'weight';
  if (isVolumeUnit(unit)) return 'volume';
  if (isCountingUnit(unit)) return 'count';
  return null;
}

function unitsAreLocallyCompatible(bomUnit: unknown, materialUnit: unknown): boolean {
  const normalizedBomUnit = normalizeUnitValue(bomUnit);
  const normalizedMaterialUnit = normalizeUnitValue(materialUnit);
  if (!normalizedBomUnit || !normalizedMaterialUnit) return true;
  if (normalizedBomUnit === normalizedMaterialUnit) return true;
  const bomFamily = knownUnitFamily(normalizedBomUnit);
  const materialFamily = knownUnitFamily(normalizedMaterialUnit);
  if (!bomFamily || !materialFamily) return true;
  return bomFamily === materialFamily;
}

function bomUnitCompatibilityWarning(): string {
  const material = selectedMaterialRecord();
  const materialUnit = normalizeUnitValue(material?.unit);
  const bomUnit = normalizeUnitValue(bomForm.value.unit);
  if (!material || unitsAreLocallyCompatible(bomUnit, materialUnit)) return '';
  const materialName = normalizeUnitValue(material.name) || bomForm.value.materialName || '当前原料';
  return `原料「${materialName}」主单位是 ${materialUnit}，当前 BOM 单位是 ${bomUnit}，两者不是同一计量维度。请先核对单位配置，或把 BOM 单位改成同一口径。`;
}

function goMaterialUnitConfigFromBom() {
  const material = selectedMaterialRecord();
  const keyword = normalizeUnitValue(material?.name) || bomForm.value.materialName || normalizeUnitValue(material?.id);
  router.push({
    path: '/warehouse/material-types',
    query: {
      _returnTo: route.fullPath,
      ...(keyword ? { keyword } : {}),
    },
  });
}

async function confirmBomUnitCompatibility(): Promise<boolean> {
  const warning = bomUnitCompatibilityWarning();
  if (!warning) return true;
  try {
    await ElMessageBox.confirm(
      warning,
      'BOM 单位需要核对',
      {
        confirmButtonText: '去核对单位配置',
        cancelButtonText: '继续编辑',
        distinguishCancelAndClose: true,
        type: 'warning',
      },
    );
    goMaterialUnitConfigFromBom();
  } catch {
    // stay in dialog
  }
  return false;
}

function normalizeRecipeMaterialCategory(value: unknown): 'RAW' | 'AUXILIARY' | 'PACKAGING' {
  const category = String(value || '').toUpperCase();
  if (category === 'PACKAGING' || category === '包材') return 'PACKAGING';
  if (category === 'AUXILIARY' || category === '辅料' || category === '调味料') return 'AUXILIARY';
  return 'RAW';
}

// 客户张权反馈 (2026-07-02): 「添加原辅料」对话框的「物料类别」只有 RAW/AUXILIARY/PACKAGING 三档
// (对应 原料/辅料/包材), 没有独立的"调料"档。AUXILIARY 沿用 normalizeRecipeMaterialCategory 的口径
// (把"调味料"也算进 AUXILIARY), 所以映射到物料主数据大类时, 辅料档同时放行"辅料"+"调料"两个
// bigCategoryOf 桶 (二者本来就是"非原料非包材的配方成分", 不细分不会让物料消失于筛选结果)。
const MATERIAL_CATEGORY_TO_BIG_CATEGORIES: Record<'RAW' | 'AUXILIARY' | 'PACKAGING', BigCategory[]> = {
  RAW: ['原料'],
  AUXILIARY: ['辅料', '调料'],
  PACKAGING: ['包材'],
};

// 「关联原料」下拉按当前选中的「物料类别」筛选 materialTypes, 避免几十项混杂 (客户反馈的
// 吸塑盒/乳酸链球菌素/玉米淀粉/透明气调膜 混在一起的问题)。未识别类别("其他"桶)的物料
// 不因未选中的类别而永久消失 — 只在其对应类别被选中时才不出现，这里按设计保守处理:
// "其他" 桶物料只在没有更精确归类时才会出现，为了不"藏"数据 (fool-proof-design Rule 5 宁缺勿藏)，
// 三个类别里找不到归属的物料仍归入 RAW 档展示 (与 normalizeRecipeMaterialCategory 默认落 RAW 一致)。
const filteredMaterialTypesForBomForm = computed<TableRow[]>(() => {
  const matCat = normalizeRecipeMaterialCategory(bomForm.value.materialCategory);
  const allowed = new Set<BigCategory>(MATERIAL_CATEGORY_TO_BIG_CATEGORIES[matCat]);
  return materialTypes.value.filter((m) => {
    const big = bigCategoryOf(m.category as string | undefined);
    // "其他"桶只在 RAW 档下兜底展示 (未归类物料默认按原料处理, 不因筛选彻底消失于任一档).
    if (big === '其他') return matCat === 'RAW';
    return allowed.has(big);
  });
});

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
    const materialCategory = normalizeRecipeMaterialCategory(item.materialCategory || item.category);
    const standardQuantity = item.standardQuantity == null ? null : Number(item.standardQuantity);
    if (!materialTypeId) missingMaterialRows.push(materialName);
    if (materialCategory !== 'RAW' && !(standardQuantity != null && standardQuantity > 0)) {
      invalidQuantityRows.push(materialName);
    }

    const substituteGroup = String(item.substituteGroup || '').trim();
    const remark = String(item.notes || item.remark || '').trim();
    const unit = String(item.unit || 'g').trim();

    return {
      materialTypeId,
      standardQuantity,
      yieldRate: item.yieldRate == null ? undefined : Number(item.yieldRate),
      unit,
      materialCategory,
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
      message: `创建配方头前，这些行缺少系统计划基准量：${invalidQuantityRows.join('、')}`,
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
    notes: recipe?.notes || '',
  };
}

function openBlankRecipeHeader() {
  if (!selectedProductTypeId.value) {
    ElMessage.warning('请先选择产品，再创建配方头');
    return;
  }
  if (recipeVersionLimitReached.value) {
    ElMessage.warning('该 SKU 已达到 10 个 BOM 版本，请先删除无用草稿或历史版本');
    return;
  }
  isRecipeEdit.value = false;
  editingRecipeId.value = null;
  const currentRecipe = bomRecipes.value.find((recipe) => recipe.isCurrent) || bomRecipes.value[0];
  resetRecipeForm(currentRecipe);
  recipeDialogVisible.value = true;
}

function bomCopyErrorMessage(error: unknown, fallback: string) {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (error as { message?: string })?.message
    || fallback;
}

async function openBomCopySuggestions(manual: boolean): Promise<boolean> {
  const targetProductTypeId = selectedProductTypeId.value;
  if (!factoryId.value || !targetProductTypeId || recipeVersionLimitReached.value) return false;
  if (!manual && automaticallyPromptedProductIds.has(targetProductTypeId)) return false;
  if (!manual) automaticallyPromptedProductIds.add(targetProductTypeId);

  bomCopyCandidatesLoading.value = true;
  try {
    const response = await bomRecipeApi.getCopyCandidates(factoryId.value, targetProductTypeId);
    if (selectedProductTypeId.value !== targetProductTypeId) return false;
    if (!response.success) throw new Error(response.message || '同源配方候选加载失败');
    bomCopyCandidates.value = response.data ?? [];
    if (bomCopyCandidates.value.length > 0) {
      bomCopyDialogVisible.value = true;
      return true;
    }
    if (manual) openBlankRecipeHeader();
    return false;
  } catch (error: unknown) {
    if (selectedProductTypeId.value !== targetProductTypeId) return false;
    ElMessage({
      message: bomCopyErrorMessage(error, '未能加载同源配方候选，可继续空白创建'),
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    if (manual) openBlankRecipeHeader();
    return false;
  } finally {
    bomCopyCandidatesLoading.value = false;
  }
}

async function handleAddRecipeHeader() {
  if (!selectedProductTypeId.value) {
    ElMessage.warning('请先选择产品，再创建配方头');
    return;
  }
  if (recipeVersionLimitReached.value) {
    ElMessage.warning('该 SKU 已达到 10 个 BOM 版本，请先删除无用草稿或历史版本');
    return;
  }
  await openBomCopySuggestions(true);
}

function handleBlankCreateFromCopyDialog() {
  bomCopyDialogVisible.value = false;
  openBlankRecipeHeader();
}

async function handleCopyRulesToProduct(payload: CopyBomToProductRequest) {
  if (!factoryId.value || payload.targetProductTypeId !== selectedProductTypeId.value) return;
  bomCopySubmitting.value = true;
  try {
    const response = await bomRecipeApi.copyToProduct(factoryId.value, payload);
    if (!response.success || !response.data) throw new Error(response.message || '复制配方规则失败');
    bomCopyDialogVisible.value = false;
    await Promise.all([
      loadBomRecipes(response.data.id),
      loadBomItems(),
      loadCostSummary(),
    ]);
    ElMessage.success('所选规则已复制为新草稿，请核对数量后继续编辑');
  } catch (error: unknown) {
    ElMessage({
      message: bomCopyErrorMessage(error, '复制配方规则失败'),
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    bomCopySubmitting.value = false;
  }
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
  // Phase 1: 产出规格从 SKU 只读带入，不再读手填字段
  const outputUnit = skuOutputUnit.value;
  const gramsPerUnit = skuGramsPerUnit.value;

  // 防呆 (Rule 5): SKU 未填标准克重 → 无法确定每单位产出量，引导去补，不静默传 0
  if (gramsPerUnit == null) {
    try {
      await ElMessageBox.confirm(
        '该产品尚未在 SKU 里填写标准克重，无法生成配方头。是否现在去补录？',
        '缺少标准克重',
        { confirmButtonText: '去 SKU 补克重', cancelButtonText: '稍后', type: 'warning' },
      );
      goFillSkuWeight();
    } catch {
      // 留在弹窗
    }
    return;
  }
  const outputQuantityPerUnit = gramsPerUnit;
  recipeDialogLoading.value = true;
  try {
    let response;
    const basePayload = {
      productName: selectedProductName.value || null,
      outputQuantityPerUnit,
      outputUnit,
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
        `${isRecipeEdit.value ? '配方头已更新' : '配方草稿已创建'}：每单位产出 ${outputQuantityPerUnit} ${outputUnit}；历史出成率由系统自动统计`,
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

// Process categories for dropdown
const processCategories = ['通用工序', '分割工序', '包装工序', '质检工序', '冷藏工序'];

// Overhead categories for dropdown
const overheadCategories = ['房租', '水电', '燃气', '设备折旧', '后端毛利', '其他'];

onMounted(async () => {
  syncCategoryFromRoute();
  await loadProductTypes(); // 只用来抽取「半成品」列表 (semiFinishedRefCode 下拉)
  await fetchProductTypeOptions(''); // 主产品下拉默认展示前 N 个成品
  // 防呆 #1236 系列: 从「产品新建」页跳过来带 ?productTypeId= 时直接定位到该产品,
  // 不需要用户在几百条里翻找刚建的 SKU (Rule 1 预先显示边界 / Rule 5 导航直达)。
  const routeProductTypeId = route.query.productTypeId;
  if (typeof routeProductTypeId === 'string' && routeProductTypeId) {
    await selectProductFromRoute(routeProductTypeId);
  } else if (productTypes.value.length > 0 && !selectedProductTypeId.value) {
    selectedProductTypeId.value = productTypes.value[0].id;
  }
  await loadMaterialTypes();
  await loadOverheadCosts();
  await loadAllLaborCosts();
});

watch(selectedProductTypeId, async (newVal) => {
  if (newVal) {
    await loadSelectedProductMeta(newVal);
    await loadBomItems();
    await loadLaborCosts();
    await loadCostSummary();
    await loadBomRecipes();
    await loadHistoricalYield();
    if (bomRecipes.value.length === 0 && canWrite.value) {
      await openBomCopySuggestions(false);
    }
  } else {
    selectedProductMeta.value = null;
    bomItems.value = [];
    laborCosts.value = [];
    costSummary.value = null;
    bomRecipes.value = [];
    selectedRecipeId.value = '';
    bomCopyCandidates.value = [];
    bomCopyDialogVisible.value = false;
    historicalYield.value = null;
    historicalYieldLoadFailed.value = false;
  }
});

// ========== Product Types ==========
// 🔴 防呆 #1 (production/warehouse walk): 主产品下拉原来一次性拉全部 active 产品
// (some 工厂 300+ 条) 塞进 filterable el-select — 刚建的新 SKU 渲染慢/沉在长列表里
// 找不到, 客户配 BOM 时卡住。改为: loadProductTypes() 只负责取「半成品」列表 (给
// semiFinishedRefCode 下拉用, 半成品数量通常不大), 主产品下拉改走
// fetchProductTypeOptions() 远程搜索 (见下), 默认只拉一页 + 按关键词服务端过滤。
async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/product-types/active`);
    if (response.success && response.data) {
      const allProducts = response.data as TableRow[];
      // SP8: 半成品列表 (用于 semiFinishedRefCode 下拉)
      semiFinishedTypes.value = allProducts.filter(
        (p: TableRow) => p.productCategory === 'SEMI_FINISHED' || p.category === '半成品'
      );
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('加载产品类型失败');
  }
}

// 主产品下拉 remote search 状态
const productSearchLoading = ref(false);
let productSearchDebounceTimer: ReturnType<typeof setTimeout> | null = null;

/** 按关键词向后端搜索成品 (分页, 默认 50 条), 取代一次性拉全量。 */
async function fetchProductTypeOptions(keyword: string) {
  if (!factoryId.value) return;
  productSearchLoading.value = true;
  try {
    const response = await get<{ content: TableRow[]; totalElements?: number }>(
      `/${factoryId.value}/product-types`,
      {
        params: {
          productCategory: 'FINISHED_PRODUCT',
          keyword: keyword || undefined,
          page: 1,
          size: 50,
        },
      }
    );
    if (response.success && response.data) {
      const content = response.data.content || [];
      // 保留当前已选产品可见: 换关键词搜索后, 若已选产品不在这一页结果里,
      // el-select 会因为找不到匹配 option 而显示不出标签 (看起来像选择丢失)。
      const selected = productTypes.value.find((p) => p.id === selectedProductTypeId.value);
      if (selected && selectedProductTypeId.value && !content.some((p) => p.id === selected.id)) {
        content.unshift(selected);
      }
      productTypes.value = content;
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.error('搜索产品失败');
  } finally {
    productSearchLoading.value = false;
  }
}

/**
 * Phase 1 fix: remote + filterable el-select 选中后会保留已选项 label 作为残留搜索词，
 * 重新聚焦时只剩已选那一个可见（Element Plus remote-select 经典坑，误以为"只能选一个产品"）。
 * 打开下拉时用空关键词重拉首页候选，让用户先看到完整成品列表，再输入名称远程搜索其余。
 */
function onProductSelectVisibleChange(visible: boolean) {
  if (visible) {
    fetchProductTypeOptions('');
  }
}

/** el-select remote-method — 防抖 300ms 再请求，避免每敲一个字就打后端。 */
function handleProductTypeRemoteSearch(query: string) {
  if (productSearchDebounceTimer) clearTimeout(productSearchDebounceTimer);
  productSearchDebounceTimer = setTimeout(() => {
    fetchProductTypeOptions(query.trim());
  }, 300);
}

/**
 * 从「产品新建」页带 ?productTypeId= 跳转过来时, 按 id 单独取回该产品并自动选中
 * (即使它不在默认前 50 条搜索结果里), 让"建 SKU → 配 BOM"一步到位。
 */
async function selectProductFromRoute(productTypeId: string) {
  if (!factoryId.value || !productTypeId) return;
  try {
    const response = await get<TableRow>(`/${factoryId.value}/product-types/${productTypeId}`);
    if (response.success && response.data) {
      const product = response.data;
      if (!productTypes.value.some((p) => p.id === product.id)) {
        productTypes.value = [product, ...productTypes.value];
      }
      selectedProductTypeId.value = String(product.id);
      bomProductSelectKey.value += 1; // 强制重渲染, 让下拉标签显示新选中的产品名
    } else {
      ElMessage.warning('未找到指定产品，请手动搜索选择');
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) ElMessage.warning('未找到指定产品，请手动搜索选择');
  }
}

/**
 * Phase 1: 取当前选中成品(SKU)的详情，供配方头只读带入产出单位/标准克重。
 * 详情端点稳定含 gramsPerUnit / unit / specification（列表投影可能不含）。
 */
async function loadSelectedProductMeta(productTypeId: string) {
  if (!factoryId.value || !productTypeId) {
    selectedProductMeta.value = null;
    return;
  }
  try {
    const response = await get<Record<string, unknown>>(`/${factoryId.value}/product-types/${productTypeId}`);
    selectedProductMeta.value = response.success && response.data ? response.data : null;
  } catch {
    selectedProductMeta.value = null;
  }
}

/**
 * Phase 1 防呆 (Rule 5 dead-end→导航): SKU 未填标准克重时，引导去产品维护补录。
 */
function goFillSkuWeight() {
  router.push({
    path: '/system/products',
    query: {
      _returnTo: route.fullPath,
      ...(selectedProductName.value ? { keyword: selectedProductName.value } : {}),
    },
  });
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

function onMaterialLink(materialTypeId: string) {
  if (!materialTypeId) return;
  const material = materialTypes.value.find((m: Record<string, unknown>) => m.id === materialTypeId);
  if (material) {
    if (material.name) bomForm.value.materialName = String(material.name);
    const quantityUnit = recipeUnitForMaterial(material, bomForm.value.materialCategory);
    bomForm.value.unit = quantityUnit;
    bomForm.value.quantityUnit = quantityUnit;
    const autoPrice = Number(material.movingAvgPrice ?? material.unitPrice ?? 0);
    bomForm.value.unitPrice = Number.isFinite(autoPrice) ? autoPrice : 0;
    bomForm.value.priceUnit = canonicalUnitCode(material.priceUnit || material.unit);
    const taxRate = String(material.taxRate || '');
    bomForm.value.taxRate = taxRate === 'TAX_9' ? 9 : taxRate === 'TAX_13' ? 13 : 0;
    if (bomForm.value.materialCategory === 'PACKAGING' && material.packQtyPerProduct != null) {
      bomForm.value.standardQuantity = Number(material.packQtyPerProduct);
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
  // Phase A side-effect: yieldRate 默认 null (出成率待评估), 不是 100
  bomForm.value = {
    id: null,
    productTypeId: selectedProductTypeId.value,
    materialTypeId: '',
    materialName: '',
    materialCategory: activeCategoryTab.value,
    standardQuantity: null,
    yieldRate: null,
    unit: '',
    quantityUnit: '',
    unitPrice: 0,
    priceUnit: '',
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
  bomForm.value = {
    id: row.id,
    productTypeId: row.productTypeId,
    materialTypeId: row.materialTypeId,
    materialName: row.materialName,
    materialCategory: (row.materialCategory as string) || 'RAW',
    standardQuantity: row.standardQuantity || 0,
    // Phase A: 编辑时保留原值 (可为 null = 待评估)
    yieldRate: row.yieldRate != null ? (row.yieldRate as number) : null,
    unit: String(row.quantityUnit || row.unit || ''),
    quantityUnit: String(row.quantityUnit || row.unit || ''),
    unitPrice: row.unitPrice || 0,
    priceUnit: String(row.priceUnit || row.unit || ''),
    taxRate: row.taxRate ?? 13,
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
  const quantityUnit = canonicalUnitCode(bomForm.value.quantityUnit || bomForm.value.unit);
  const payload = {
    ...bomForm.value,
    unit: quantityUnit,
    quantityUnit,
    priceUnit: canonicalUnitCode(bomForm.value.priceUnit),
  };
  delete payload.id;
  delete payload.isOptional;
  delete payload.substituteGroup;
  return payload;
}

async function submitBomForm() {
  // 类别由当前分区锁定，不让用户在弹窗里重复选择。
  bomForm.value.materialCategory = activeCategoryTab.value;
  // Phase 1: 物料名称已改为从「关联原料」自动带入，校验改为要求选中关联原料
  if (!bomForm.value.materialTypeId) {
    ElMessage.warning(`请选择${activeCategoryTab.value === 'PACKAGING' ? '包材' : '原料'}（名称将自动带入）`);
    return;
  }
  if (bomForm.value.materialCategory === 'RAW') {
    // 原料只建立物料关联；实际投料由操作员填写并以仓储出库记录为真值。
    bomForm.value.standardQuantity = null;
    bomForm.value.yieldRate = null;
  } else if (bomForm.value.standardQuantity == null || Number(bomForm.value.standardQuantity) <= 0) {
    ElMessage.warning('请填写每个成品需要的包材数量');
    return;
  }
  // Phase 1: 辅料/包材无出成率折算，固定 100 满足后端 yield_rate NOT NULL；原料保留 null=待评估
  if (bomForm.value.materialCategory !== 'RAW') {
    bomForm.value.yieldRate = 100;
  }
  if (!(await confirmBomUnitCompatibility())) return;
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
  // fool-proof Rule 1: 字段级校验, 不静默丢给后端报晦涩 400.
  if (!laborForm.value.processName) {
    ElMessage.warning('请输入工序名称');
    return;
  }
  if (canViewPrice.value && laborForm.value.unitPrice == null) {
    ElMessage.warning('请填写工序单价');
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
  // fool-proof Rule 1: 字段级校验, 不静默丢给后端报晦涩 400.
  if (!overheadForm.value.name) {
    ElMessage.warning('请输入费用名称');
    return;
  }
  if (canViewPrice.value && overheadForm.value.unitPrice == null) {
    ElMessage.warning('请填写单价');
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
    const response = await get<BomCostSummaryView>(`/${factoryId.value}/bom/cost-summary/${selectedProductTypeId.value}`);
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
function summaryNumber(value: unknown, fallback: number): number {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}
const costDisplayUnit = computed(() => {
  return `元/${skuOutputUnit.value}`;
});
const estimatedMaterialCost = computed(() => summaryNumber(costSummary.value?.materialCostTotal, materialCostTotal.value));
const estimatedLaborCost = computed(() => summaryNumber(costSummary.value?.laborCostTotal, laborCostTotal.value));
const estimatedOverheadCost = computed(() => summaryNumber(costSummary.value?.overheadCostTotal, overheadCostTotal.value));
const costPerSkuUnit = computed(() => summaryNumber(costSummary.value?.totalCost, totalCost.value));
const costPerKg = computed<number | null>(() => {
  if (costDisplayUnit.value === '元/kg' || skuGramsPerUnit.value == null) return null;
  const value = costPerSkuUnit.value / (skuGramsPerUnit.value / 1000);
  return Number.isFinite(value) ? value : null;
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
function syncCategoryFromRoute() {
  const category = String(route.query.category || '').toUpperCase();
  if (category === 'RAW' || category === 'AUXILIARY' || category === 'PACKAGING') {
    activeCategoryTab.value = category;
  }
}
watch(() => route.query.category, syncCategoryFromRoute);
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
  loadHistoricalYield();
}

// ========== Excel 导入 ==========
interface ParsedImportRow {
  materialName: string;
  materialCategory: string;
  standardQuantity: number | null;
  yieldRate: number | null;
  unit: string;
  _ok?: boolean;
  _error?: string;
}

const importFileInputRef = ref<HTMLInputElement | null>(null);
const importDialogVisible = ref(false);
const importSubmitting = ref(false);
const importPreviewRows = ref<ParsedImportRow[]>([]);

function downloadBomTemplate() {
  const ws = XLSX.utils.aoa_to_sheet([
    ['物料名', '物料类别(RAW/AUXILIARY/PACKAGING)', '成品含量（RAW可空）', '出成率%', '单位'],
    ['示例: 猪蹄', 'RAW', '', 61, 'g'],
    ['示例: 辅料A', 'AUXILIARY', 5, 95, 'g'],
    ['示例: 包装袋', 'PACKAGING', 1, '', 'pcs'],
  ]);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'BOM模板');
  XLSX.writeFile(wb, 'BOM导入模板.xlsx');
}

function handleImportClick() {
  if (!selectedProductTypeId.value) {
    ElMessage.warning('请先选择产品');
    return;
  }
  importPreviewRows.value = [];
  importFileInputRef.value?.click();
}

function handleFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  // Reset so the same file can be re-selected
  input.value = '';

  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const raw = e.target?.result;
      const wb = XLSX.read(raw, { type: 'binary' });
      const ws = wb.Sheets[wb.SheetNames[0]];
      const jsonRows = XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: '' });

      if (jsonRows.length === 0) {
        ElMessage.warning('Excel 文件没有数据行，请检查格式');
        return;
      }

      const parsed: ParsedImportRow[] = jsonRows
        .map((r) => {
          const materialName = String(
            r['物料名'] ?? r['物料名称'] ?? '',
          ).trim();
          const categoryRaw = String(
            r['物料类别(RAW/AUXILIARY/PACKAGING)'] ?? r['物料类别'] ?? 'RAW',
          ).trim().toUpperCase();
          const materialCategory =
            categoryRaw === 'AUXILIARY' || categoryRaw === '辅料' ? 'AUXILIARY'
            : categoryRaw === 'PACKAGING' || categoryRaw === '包材' ? 'PACKAGING'
            : 'RAW';
          const quantityCell = r['成品含量（RAW可空）'] ?? r['成品含量'];
          const standardQuantity = materialCategory === 'RAW'
            ? null
            : Number(quantityCell ?? 0);
          const yieldRateRaw = r['出成率%'] ?? r['出成率'];
          const yieldRate =
            yieldRateRaw === '' || yieldRateRaw == null
              ? null
              : Number(yieldRateRaw);
          const unit = String(r['单位'] ?? 'g').trim() || 'g';
          return { materialName, materialCategory, standardQuantity, yieldRate, unit };
        })
        .filter((r) => r.materialName.length > 0
          && (r.materialCategory === 'RAW'
            || (r.standardQuantity != null && r.standardQuantity > 0)));

      if (parsed.length === 0) {
        ElMessage.warning('未解析到有效行（物料名必填；辅料/包材成品含量必须大于 0）');
        return;
      }

      importPreviewRows.value = parsed;
      importDialogVisible.value = true;
    } catch {
      ElMessage({
        message: '解析 Excel 文件失败，请检查文件格式',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  };
  reader.readAsBinaryString(file);
}

async function submitImport() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  importSubmitting.value = true;
  try {
    const items: BomImportRow[] = importPreviewRows.value.map((r) => ({
      materialName: r.materialName,
      materialCategory: r.materialCategory,
      standardQuantity: r.standardQuantity,
      yieldRate: r.yieldRate,
      unit: r.unit,
    }));
    const res = await batchImportBomItems(factoryId.value, selectedProductTypeId.value, items);
    if (res.success && res.data) {
      const { inserted, failed, rows } = res.data;
      if (failed > 0) {
        // Reset result flags then apply per-row errors
        importPreviewRows.value.forEach((r) => {
          r._ok = undefined;
          r._error = undefined;
        });
        rows.forEach((result, idx) => {
          const target = importPreviewRows.value[idx];
          if (target) {
            target._ok = result.ok;
            target._error = result.error;
          }
        });
        ElMessage({
          message: `${failed} 行校验失败，整批未导入，请修正后重试`,
          type: 'warning',
          duration: 0,
          showClose: true,
        });
      } else {
        ElMessage.success(`成功导入 ${inserted} 行`);
        importDialogVisible.value = false;
        await loadBomItems();
        await loadCostSummary();
      }
    } else {
      ElMessage({
        message: res.message || '导入失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      const msg =
        (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || '导入失败，请稍后重试';
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    importSubmitting.value = false;
  }
}

// =========================================================================
// 对话微调 (Conversational BOM Tweak)
// =========================================================================

interface AdjustPreviewRow {
  materialName: string;
  standardQuantity: number;
  yieldRate: number | null;
  unitPrice: number | null;
  materialCategory: string;
  unit: string;
  _changed: boolean;
  [k: string]: unknown;
}

interface AdjustSeasoningRow {
  name: string;
  section: string;
  dosagePerKgG: number | null;
  priceSource1: number | null;
  _changed: boolean;
  [k: string]: unknown;
}

interface AdjustPreviewResult {
  status: string;
  message: string;
  change?: {
    material: string;
    field: string;
    oldValue: unknown;
    newValue: unknown;
  };
  bomTable?: AdjustPreviewRow[];
  seasoningTable?: AdjustSeasoningRow[];
}

const adjustDialogVisible = ref(false);
const adjustInstruction = ref('');
const adjustPreviewLoading = ref(false);
const adjustConfirmLoading = ref(false);
const adjustPreviewResult = ref<AdjustPreviewResult | null>(null);

const adjustConfirmEnabled = computed(
  () => adjustPreviewResult.value?.status === 'PREVIEW',
);

function handleOpenAdjustDialog() {
  if (!selectedProductTypeId.value) {
    ElMessage.warning('请先选择产品');
    return;
  }
  adjustInstruction.value = '';
  adjustPreviewResult.value = null;
  adjustDialogVisible.value = true;
}

async function handleAdjustPreview() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  const instruction = adjustInstruction.value.trim();
  if (!instruction) {
    ElMessage.warning('请输入微调指令');
    return;
  }
  adjustPreviewLoading.value = true;
  adjustPreviewResult.value = null;
  try {
    const res = await post(`/${factoryId.value}/bom/adjust/preview`, {
      productTypeId: selectedProductTypeId.value,
      instruction,
    });
    if (res.success && res.data) {
      const data = res.data as AdjustPreviewResult;
      if (data.status !== 'PREVIEW') {
        ElMessage.warning(data.message || '预览失败，请检查指令');
      } else {
        adjustPreviewResult.value = data;
      }
    } else {
      ElMessage.warning(res.message || '预览失败，请检查指令');
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      const msg =
        (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || '预览失败，请稍后重试';
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    adjustPreviewLoading.value = false;
  }
}

async function handleAdjustConfirm() {
  if (!factoryId.value || !selectedProductTypeId.value || !adjustConfirmEnabled.value) return;
  adjustConfirmLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/bom/adjust`, {
      productTypeId: selectedProductTypeId.value,
      instruction: adjustInstruction.value.trim(),
    });
    if (res.success) {
      ElMessage.success(
        (res.data as { message?: string } | null)?.message
          || res.message
          || '微调已应用',
      );
      adjustDialogVisible.value = false;
      adjustInstruction.value = '';
      adjustPreviewResult.value = null;
      await loadBomItems();
      await loadCostSummary();
    } else {
      ElMessage({ message: res.message || '微调失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error: unknown) {
    const err = error as { actionHint?: string };
    if (!err?.actionHint) {
      const msg =
        (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || '微调失败，请稍后重试';
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    adjustConfirmLoading.value = false;
  }
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
        原料、包材和各工序辅料先保存到配方版本；激活后仅供之后新建的生产计划使用，
        已有生产计划快照与已激活 Workflow 不受影响。物料价格从档案带入，历史出成率由正式报工自动统计。
      </template>
    </el-alert>
    <ConceptDisambiguationAlert
      here-name="BOM 成本管理"
      here="一个成品关联哪些原料、包材和工序辅料，以及系统如何汇总成本"
      other-name="生产管理 → 转换率配置"
      other="旧工厂遗留的单一原料到单一成品转换关系"
      other-path="/production/conversions"
      consequence="新配方统一在 BOM 与 Workflow 中维护；实际出成率只从正式报工历史计算"
    />
    <!-- Header -->
    <el-card class="header-card" shadow="never">
      <div class="header-content">
        <div class="header-left">
          <h2 class="page-title">BOM成本管理</h2>
          <el-select
            :key="bomProductSelectKey"
            v-model="selectedProductTypeId"
            placeholder="输入产品名称搜索并选择"
            style="width: 280px; margin-left: 20px;"
            filterable
            remote
            :remote-method="handleProductTypeRemoteSearch"
            :loading="productSearchLoading"
            @visible-change="onProductSelectVisibleChange"
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
            :loading="bomCopyCandidatesLoading"
            :disabled="!selectedProductTypeId || recipeVersionLimitReached"
            @click="handleAddRecipeHeader"
          >创建配方</el-button>
        </div>
        <div v-if="canViewPrice" class="header-right">
          <el-card class="cost-summary-card" shadow="never">
            <div class="cost-summary">
              <div class="cost-item">
                <span class="cost-label">原料成本:</span>
                <span class="cost-value">{{ formatFriendlyNumber(estimatedMaterialCost, 2) }} {{ costDisplayUnit }}</span>
              </div>
              <div class="cost-item">
                <span class="cost-label">人工成本:</span>
                <span class="cost-value">{{ formatFriendlyNumber(estimatedLaborCost, 2) }} {{ costDisplayUnit }}</span>
              </div>
              <div class="cost-item">
                <span class="cost-label">均摊费用:</span>
                <span class="cost-value">{{ formatFriendlyNumber(estimatedOverheadCost, 2) }} {{ costDisplayUnit }}</span>
              </div>
              <div class="cost-item total">
                <span class="cost-label">总成本:</span>
                <span class="cost-value">{{ formatFriendlyNumber(costPerSkuUnit, 2) }} {{ costDisplayUnit }}</span>
                <span v-if="costPerKg != null" class="cost-secondary">
                  {{ formatFriendlyNumber(costPerKg, 2) }} 元/kg
                </span>
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
            <el-tag size="small" :type="recipeVersionLimitReached ? 'warning' : 'info'">
              已用 {{ bomRecipes.length }}/{{ MAX_RECIPE_VERSIONS }} 个版本
            </el-tag>
            <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" :loading="bomCopyCandidatesLoading" :disabled="recipeVersionLimitReached" @click="handleAddRecipeHeader">
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
              {{ formatFriendlyNumber(row.outputQuantityPerUnit) }} {{ row.outputUnit || '' }}
            </span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="系统历史出成率" width="150" align="right">
          <template #default>
            <el-tooltip
              v-if="historicalYield?.suggestedYieldRate != null"
              :content="`同工厂、同 SKU 的 ${historicalYield.sampleCount} 个正式批次自动统计，不可手改`"
              placement="top"
            >
              <span>{{ historicalYield.suggestedYieldRate.toFixed(2) }}%</span>
            </el-tooltip>
            <span v-else-if="historicalYieldLoadFailed" class="text-danger">统计加载失败</span>
            <span v-else class="text-secondary">待积累（{{ historicalYield?.sampleCount ?? 0 }}/3 批）</span>
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.notes || '—' }}</template>
        </el-table-column>
        <el-table-column label="总成本" width="100" align="right">
          <template #default="{ row }">
            <span v-if="canViewPrice && row.totalCost != null">
              <div>{{ formatFriendlyNumber(row.totalCost, 2) }} 元/{{ row.outputUnit || skuOutputUnit }}</div>
              <div v-if="skuGramsPerUnit != null" class="cost-secondary">
                {{ formatFriendlyNumber(Number(row.totalCost) / (skuGramsPerUnit / 1000), 2) }} 元/kg
              </div>
            </span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right" align="center">
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
              v-else-if="canWrite"
              type="primary"
              link
              size="small"
              @click="handleCloneRecipe(row)"
              :disabled="recipeVersionLimitReached"
            >
              复制编辑
            </el-button>
            <el-button
              v-if="canWrite && row.status !== 'ACTIVE'"
              type="danger"
              link
              size="small"
              :loading="deletingRecipeId === row.id"
              @click="handleDeleteRecipe(row)"
            >
              删除
            </el-button>
            <el-button
              v-if="canWrite && !row.isCurrent"
              type="success"
              size="small"
              :loading="activatingRecipeId === row.id"
              @click="handleActivateRecipe(row)"
            >
              激活
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="recipe-status-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>草稿和历史正式版本都可激活；同一 SKU 始终只有一个生效版本。生效版本不可删除，达到 10 个版本后请先删除无用草稿或历史版本。</span>
      </div>
    </el-card>

    <!-- Main Content -->
    <div class="tables-container">
      <!-- BOM Items Table (原辅料需求明细表) -->
      <el-card class="table-card" shadow="never">
        <template #header>
          <div class="table-header">
            <span class="table-title">{{ activeCategoryTab === 'AUXILIARY' ? '工序辅料明细' : activeCategoryTab === 'PACKAGING' ? '包材需求明细' : '原料需求明细' }}</span>
            <div v-if="activeCategoryTab !== 'AUXILIARY'" class="table-actions">
              <el-button v-if="canWrite" type="primary" size="small" :icon="Plus" @click="handleAddBomItem">
                {{ activeCategoryTab === 'PACKAGING' ? '添加包材' : '添加原料' }}
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

        <div v-if="activeCategoryTab === 'AUXILIARY'" data-testid="bom-auxiliary-integration" class="auxiliary-integration">
          <el-alert
            v-if="auxiliaryItems.length > 0"
            type="warning"
            show-icon
            :closable="false"
            :title="`存在 ${auxiliaryItems.length} 条历史普通辅料，待绑定工序 / 可能重复计成本`"
            class="legacy-auxiliary-alert"
          >
            <template #default>
              <span>系统不会自动转换或删除历史数据。迁移接口尚未开放，请先保留原记录并逐项核对。</span>
              <el-button size="small" disabled>批量绑定工序（暂不可用）</el-button>
            </template>
          </el-alert>

          <div v-if="bomRecipes.length > 0" class="seasoning-version-context">
            <span>配置版本</span>
            <el-select v-model="selectedRecipeId" style="width: 320px;" placeholder="选择 BOM 版本">
              <el-option
                v-for="recipe in bomRecipes"
                :key="recipe.id"
                :label="`${recipe.productName} v${recipe.version} · ${recipeStatusLabel[recipe.status]}`"
                :value="recipe.id"
              />
            </el-select>
            <el-tag v-if="selectedRecipe" :type="recipeStatusTagType[selectedRecipe.status]">
              {{ recipeStatusLabel[selectedRecipe.status] }}
            </el-tag>
          </div>

          <BomAuxiliaryWorkspace
            v-if="selectedRecipe && factoryId"
            :key="selectedRecipe.id"
            :factory-id="factoryId"
            :product-type-id="selectedProductTypeId"
            :recipe-id="selectedRecipe.id"
            :recipe-status="selectedRecipe.status"
            :can-write="canWrite"
            @request-clone="handleCloneSelectedRecipe"
            @changed="handleSeasoningWorkspaceChanged"
          />
          <el-empty
            v-else
            description="请先为当前 SKU 创建 BOM 配方，再配置工序调料"
            :image-size="64"
          >
            <el-button v-if="canWrite && selectedProductTypeId" type="primary" :loading="bomCopyCandidatesLoading" @click="handleAddRecipeHeader">创建配方</el-button>
          </el-empty>
        </div>

        <el-table v-else empty-text="暂无数据" :data="currentTabItems" v-loading="loading" stripe border size="small" style="width: 100%"
          :row-class-name="({ row }: { row: TableRow }) => row._isCategoryHeader ? 'category-header-row' : (row.yieldRate == null ? 'yield-pending-row' : '')">
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
          <el-table-column v-if="activeCategoryTab === 'PACKAGING'" prop="standardQuantity" label="每成品用量" width="120" align="right">
            <template #default="{ row }">
              {{ formatFriendlyNumber(row.standardQuantity) }} {{ row.unit || '' }}
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="计量单位" width="90" align="center" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="自动单价" width="100" align="right">
            <template #default="{ row }">
              {{ formatFriendlyNumber(row.unitPrice, 4) }} {{ formatPriceUnit(row.priceUnit) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="150" align="right">
            <template #default="{ row }">
              <span v-if="bomLineAmountPreview(row).amount != null">
                {{ formatFriendlyNumber(bomLineAmountPreview(row).amount, 4) }} 元
              </span>
              <span v-else class="text-secondary">{{ bomLineAmountPreview(row).message }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="primary" link size="small" :icon="Edit" @click="handleEditBomItem(row)" />
              <el-button v-if="canWrite" type="danger" link size="small" :icon="Delete" @click="handleDeleteBomItem(row)" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canViewPrice && activeCategoryTab !== 'AUXILIARY'" class="table-footer">
          <span class="total-label">原料成本合计:</span>
          <span class="total-value">{{ formatFriendlyNumber(estimatedMaterialCost, 2) }} {{ costDisplayUnit }}</span>
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

    <BomCopySuggestionDialog
      v-model="bomCopyDialogVisible"
      :target-product-name="selectedProductName"
      :target-product-type-id="selectedProductTypeId"
      :candidates="bomCopyCandidates"
      :loading="bomCopyCandidatesLoading"
      :submitting="bomCopySubmitting"
      @blank-create="handleBlankCreateFromCopyDialog"
      @copy="handleCopyRulesToProduct"
    />

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
          这里维护配方版本说明，产出规格由 SKU 自动带入
        </template>
        <template #default>
          整体出成率由系统根据同工厂、同 SKU 的全部正式批次自动统计，只读且不需要人工预估。
        </template>
      </el-alert>
      <el-form :model="recipeForm" label-width="130px">
        <el-form-item label="产品">
          <el-input :model-value="selectedProductName || selectedProductTypeId" disabled />
        </el-form-item>
        <el-form-item label="产出单位">
          <el-input :model-value="skuOutputUnit" disabled />
          <div class="form-tip">从 SKU 单位带入，如需修改请到产品(SKU)维护</div>
        </el-form-item>
        <el-form-item label="每单位产出量">
          <template v-if="skuGramsPerUnit != null">
            <el-input :model-value="`${skuGramsPerUnit} 克 / ${skuOutputUnit}`" disabled />
            <div class="form-tip">从 SKU 标准克重带入（1 {{ skuOutputUnit }} = {{ skuGramsPerUnit }} 克）</div>
          </template>
          <template v-else>
            <el-alert
              type="warning"
              :closable="false"
              show-icon
              title="该产品尚未在 SKU 里填写标准克重，无法确定每单位产出量"
              style="margin-bottom: 6px;"
            />
            <el-button link type="primary" @click="goFillSkuWeight">去 SKU 补标准克重</el-button>
          </template>
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
        <span>创建时会把上方明细一起存为草稿配方；原料只需关联物料，包材还需填写每成品基本单位用量。</span>
      </div>
      <template #footer>
        <el-button @click="recipeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="recipeDialogLoading" @click="submitRecipeHeaderForm">
          {{ isRecipeEdit ? '保存配方头' : '创建草稿配方' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- BOM Item Dialog -->
    <el-dialog
      v-model="bomDialogVisible"
      :title="`${isBomEdit ? '编辑' : '添加'}${bomForm.materialCategory === 'PACKAGING' ? '包材' : '原料'}`"
      width="580px"
    >
      <el-form :model="bomForm" label-width="110px">
        <el-form-item :label="bomForm.materialCategory === 'PACKAGING' ? '选择包材' : '选择原料'" required>
          <el-select
            v-model="bomForm.materialTypeId"
            :placeholder="bomForm.materialCategory === 'PACKAGING' ? '请选择包材' : '请选择原料'"
            filterable
            style="width: 100%"
            @change="onMaterialLink"
          >
            <el-option
              v-for="item in filteredMaterialTypesForBomForm"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div class="form-tip">列表已按当前页签筛选，所选物料为必填。</div>
        </el-form-item>
        <el-form-item v-if="bomForm.materialCategory === 'PACKAGING'" label="每成品用量" required>
          <div style="display: flex; align-items: center; gap: 8px; width: 100%;">
            <el-input-number
              v-model="bomForm.standardQuantity"
              :min="0"
              :precision="4"
              :step="0.01"
              style="flex: 1;"
            />
            <span class="unit-suffix">{{ bomFormUnitLabel }}</span>
          </div>
          <div class="form-tip">每 1 个成品基本单位需要的包材数量。</div>
        </el-form-item>
        <el-form-item label="计量单位">
          <el-select
            v-model="bomForm.unit"
            :disabled="bomUnitOptions.length <= 1"
            placeholder="选择物料后自动带入"
            style="width: 100%"
            @change="bomForm.quantityUnit = canonicalUnitCode(bomForm.unit)"
          >
            <el-option v-for="unit in bomUnitOptions" :key="unit" :label="displayUnit(unit)" :value="unit" />
          </el-select>
          <div class="form-tip">默认使用物料主数据单位；同量纲单位可显式选择，提交时同时保存 quantityUnit。</div>
          <div v-if="bomUnitCompatibilityWarning()" class="form-tip form-tip--warning">
            <span>{{ bomUnitCompatibilityWarning() }}</span>
            <el-button link type="warning" size="small" @click="goMaterialUnitConfigFromBom">
              去核对单位配置
            </el-button>
          </div>
          <div v-else-if="bomUnitIsCounting" class="form-tip">
            计数型原料按成品件数核算；例如 0.5 只/袋，400 袋会核算 200 只。
          </div>
          <div v-else class="form-tip">
            单位从物料字典自动带入；如不正确，请先修改物料档案。
          </div>
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
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="`参考单价从物料档案带入（${formatPriceUnit(bomForm.priceUnit)}）；数量单位不同且无权威换算时不预估金额。`"
          style="margin-bottom: 12px;"
        />
        <el-form-item label="备注">
          <el-input v-model="bomForm.notes" type="textarea" :rows="2" />
        </el-form-item>
        <!-- Phase 1: 高级字段默认收起，功能不删 -->
        <el-collapse v-model="showAdvancedBomFields" class="bom-advanced-collapse">
          <el-collapse-item name="adv" title="高级选项（可选料 / 替代料 / 按份数投料 / 半成品引用 / 嵌套子产品）">
        <el-form-item label="可选料">
          <el-checkbox v-model="bomForm.isOptional">
            可选原辅料，不作为生产计划完整性硬要求
          </el-checkbox>
          <div class="form-tip">适用于装饰菜、可省略配料等</div>
        </el-form-item>
        <el-form-item label="替代料分组">
          <el-input
            v-model="bomForm.substituteGroup"
            maxlength="50"
            show-word-limit
            placeholder="例：MEAT_BASE / SAUCE_ALT，同组物料可互相替代"
          />
          <div class="form-tip">相同分组的物料可互相替代</div>
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
            filterable
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
            filterable
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
          </el-collapse-item>
        </el-collapse>
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

    <!-- Hidden file input for Excel import -->
    <input
      ref="importFileInputRef"
      type="file"
      accept=".xlsx,.xls"
      style="display: none"
      @change="handleFileSelected"
    />

    <!-- Excel 导入预览对话框 -->
    <el-dialog
      v-model="importDialogVisible"
      title="Excel 导入 BOM 原辅料 — 预览"
      width="800px"
      :destroy-on-close="true"
    >
      <div class="import-hint">
        共解析 <strong>{{ importPreviewRows.length }}</strong> 行，请确认数据后点击「确认导入」。
        整批校验通过才入库；任一行失败则整批不入库，错误行标红提示。
      </div>
      <el-table
        :data="importPreviewRows"
        stripe
        border
        size="small"
        style="width: 100%"
        :row-class-name="({ row }: { row: ParsedImportRow }) => row._ok === false ? 'import-error-row' : ''"
      >
        <el-table-column type="index" label="行" width="50" align="center" />
        <el-table-column prop="materialName" label="物料名" min-width="130" show-overflow-tooltip />
        <el-table-column prop="materialCategory" label="物料类别" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.materialCategory === 'RAW' ? '' : row.materialCategory === 'PACKAGING' ? 'warning' : 'info'"
              size="small"
              disable-transitions
            >{{ row.materialCategory }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成品含量" width="100" align="right">
          <template #default="{ row }">
            {{ row.standardQuantity == null ? '系统统计' : row.standardQuantity }}
          </template>
        </el-table-column>
        <el-table-column label="出成率%" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.yieldRate != null">{{ row.yieldRate }}</span>
            <el-tag v-else type="warning" size="small" disable-transitions>待评估</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="70" align="center" />
        <el-table-column label="校验结果" width="160" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row._ok === false"
              type="danger"
              size="small"
              disable-transitions
              style="white-space: normal; height: auto; line-height: 1.4"
            >{{ row._error || '校验失败' }}</el-tag>
            <el-tag v-else-if="row._ok === true" type="success" size="small" disable-transitions>成功</el-tag>
            <span v-else class="text-secondary">待提交</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importSubmitting" @click="submitImport">
          确认导入
        </el-button>
      </template>
    </el-dialog>

    <!-- BOM Change Log Drawer (P1-9) -->
    <BomChangeLog v-model:visible="changeLogVisible" :factory-id="factoryId" :product-type-id="selectedProductTypeId" />

    <!-- 对话微调 Dialog -->
    <el-dialog
      v-model="adjustDialogVisible"
      title="对话微调 BOM"
      width="700px"
      :destroy-on-close="true"
    >
      <div style="margin-bottom: 12px;">
        <div style="font-size: 13px; color: #606266; margin-bottom: 8px;">
          当前产品：<strong>{{ selectedProductName }}</strong>
        </div>
        <el-input
          v-model="adjustInstruction"
          type="textarea"
          :rows="3"
          placeholder="例: 把冷冻猪舌用量改成120 / 猪舌损耗改成90% / 冷冻猪舌单价改成12"
          maxlength="500"
          show-word-limit
          :disabled="adjustPreviewLoading || adjustConfirmLoading"
        />
        <div style="margin-top: 10px; display: flex; justify-content: flex-end;">
          <el-button
            type="primary"
            :loading="adjustPreviewLoading"
            :disabled="!adjustInstruction.trim() || adjustConfirmLoading"
            @click="handleAdjustPreview"
          >预览</el-button>
        </div>
      </div>

      <!-- Preview result -->
      <div v-if="adjustPreviewResult">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
        >
          <template #title>{{ adjustPreviewResult.message }}</template>
        </el-alert>
        <el-table
          v-if="adjustPreviewResult.bomTable && adjustPreviewResult.bomTable.length > 0"
          :data="adjustPreviewResult.bomTable"
          stripe
          border
          size="small"
          style="width: 100%"
          :row-class-name="({ row }: { row: AdjustPreviewRow }) => row._changed ? 'adjust-changed-row' : ''"
        >
          <el-table-column prop="materialName" label="物料名称" min-width="130" show-overflow-tooltip />
          <el-table-column prop="materialCategory" label="类型" width="80" align="center" />
          <el-table-column label="成品含量" width="100" align="right">
            <template #default="{ row }">
              {{ Number(row.standardQuantity).toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column label="出成率%" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.yieldRate != null">{{ Number(row.yieldRate).toFixed(2) }}%</span>
              <el-tag v-else type="warning" size="small" disable-transitions>待评估</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.unitPrice != null">{{ Number(row.unitPrice).toFixed(2) }}</span>
              <span v-else class="text-secondary">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" align="center" />
        </el-table>
        <el-table
          v-if="adjustPreviewResult.seasoningTable && adjustPreviewResult.seasoningTable.length > 0"
          :data="adjustPreviewResult.seasoningTable"
          stripe
          border
          size="small"
          style="width: 100%"
          :row-class-name="({ row }: { row: AdjustSeasoningRow }) => row._changed ? 'adjust-changed-row' : ''"
        >
          <el-table-column prop="name" label="调料名称" min-width="130" show-overflow-tooltip />
          <el-table-column prop="section" label="段" width="90" align="center" />
          <el-table-column label="每kg用量(g)" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.dosagePerKgG != null">{{ Number(row.dosagePerKgG).toFixed(2) }}</span>
              <span v-else class="text-secondary">—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.priceSource1 != null">{{ Number(row.priceSource1).toFixed(2) }}</span>
              <span v-else class="text-secondary">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="adjustDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="adjustConfirmLoading"
          :disabled="!adjustConfirmEnabled"
          @click="handleAdjustConfirm"
        >确认微调</el-button>
      </template>
    </el-dialog>
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

.cost-secondary {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.cost-summary .cost-secondary { color: rgba(255, 255, 255, 0.82); }

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

.auxiliary-integration {
  padding-top: 4px;
}

.legacy-auxiliary-alert {
  margin-bottom: 12px;
}

.legacy-auxiliary-alert :deep(.el-alert__content) {
  width: 100%;
}

.legacy-auxiliary-alert :deep(.el-alert__description) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.seasoning-version-context {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 9px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-regular);
  font-size: 13px;
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

/* Excel 导入预览对话框 */
.import-hint {
  font-size: 13px;
  color: #606266;
  margin-bottom: 12px;
  line-height: 1.6;
}

:deep(.import-error-row) {
  background-color: #fef0f0 !important;

  td {
    background-color: #fef0f0 !important;
  }
}

/* 对话微调: 已变更行高亮 (green tint) */
:deep(.adjust-changed-row) {
  background-color: #f0f9eb !important;
  font-weight: 600;

  td {
    background-color: #f0f9eb !important;
  }
}
</style>
