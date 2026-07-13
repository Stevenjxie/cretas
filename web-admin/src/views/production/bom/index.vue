<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { bomYieldEstimateApi, bomRecipeApi, batchImportBomItems } from '@/api/bom';
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
  BomImportRow,
} from '@/api/bom';
import { isAxiosError } from 'axios';
import * as XLSX from 'xlsx';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete, Download, Refresh, MagicStick, InfoFilled } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import BomChangeLog from './BomChangeLog.vue'
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue'
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue'
import type { TableRow } from '@/types/api';
// 客户张权反馈 (2026-07-02): "辅料 添加剂全混在一起了" — 「添加原辅料」对话框的「关联原料」
// 下拉需按上方「物料类别」筛选, 归类逻辑复用 procurement/receives/list.vue 同款共享工具。
import { bigCategoryOf, type BigCategory } from '@/utils/materialCategory';

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
// :key 强制 el-select 重新挂载 —— element-plus 的 currentLabel 是渲染时按当前 options
// 匹配一次后缓存的, 异步 (route 带 productTypeId 跳转) 补写 options + modelValue 不会
// 触发它重新求值, 导致选中态生效但下拉框标签显示空白 (同款坑见 ReferenceSelector.vue)。
const bomProductSelectKey = ref(0);
const productTypes = ref<TableRow[]>([]);
const costSummary = ref<TableRow | null>(null);
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
  const materialUnit = normalizeUnitValue(material.unit);
  if (category === 'PACKAGING') return materialUnit || 'pcs';
  if (isCountingUnit(materialUnit)) return materialUnit;
  return 'g';
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
const bomUnitIsWeight = computed(() => isWeightUnit(bomForm.value.unit));
const bomUnitIsVolume = computed(() => isVolumeUnit(bomForm.value.unit));
const bomUnitIsCounting = computed(() => isCountingUnit(bomForm.value.unit));

function bomQuantityHelpText(): string {
  if (bomUnitIsCounting.value) {
    return `成品用量 = 每份成品需要该物料多少${bomForm.value.unit || '个'}。例：半只鸡填 0.5 只，400 袋自动核算 200 只`;
  }
  if (bomUnitIsVolume.value) {
    return `成品用量 = 每份成品需要该物料多少${bomForm.value.unit || 'mL'}`;
  }
  return '成品含量 = 每份成品中该物料的克数（来自产品标准克重）';
}

function actualQuantityHelpText(): string {
  if (bomUnitIsCounting.value) {
    return '= 成品用量；计数型原料不按出成率折算重量';
  }
  if (bomUnitIsVolume.value) {
    return '= 成品用量 ÷ (出成率/100)';
  }
  return '= 成品含量 ÷ (出成率/100) | 示例: 200g 成品 × 58% 出成率 → 自动算原料 344.83g';
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

// D2 (2026-05-10 客户会议): 实时计算实际原料用量 = 成品含量 / (出成率/100)
// 镜像后端 BomItem.getActualQuantity()
// GAP F7: 出成率为 null (待评估) 时返回 null, 不用 100% 兜底 (防止 sq/1.0=sq 误导为真实 100%)
const computedActualQuantity = computed<number | null>(() => {
  const sq = Number(bomForm.value.standardQuantity) || 0;
  if (bomUnitIsCounting.value) return Number(sq.toFixed(4));
  if (bomForm.value.yieldRate == null) return null;
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
  // Phase 1: 产出规格从 SKU 只读带入，不再读手填字段
  const outputUnit = skuOutputUnit.value;
  const gramsPerUnit = skuGramsPerUnit.value;
  const overallYieldRate = Number(recipeForm.value.overallYieldRate);

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
  } else {
    selectedProductMeta.value = null;
    bomItems.value = [];
    laborCosts.value = [];
    costSummary.value = null;
    bomRecipes.value = [];
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

// 默认仍保持重量原料用 g；若原料主单位是计数单位 (只/个/件/pcs)，BOM 也用计数单位。
// 这类原料不能被强行解释成克，否则结单时会出现 g ↔ 只 的不可换算预警。
// Ref: D3 comment L68 + F006_OPERATIONS_GUIDE §0.4
function onMaterialLink(materialTypeId: string) {
  if (!materialTypeId) return;
  const material = materialTypes.value.find((m: Record<string, unknown>) => m.id === materialTypeId);
  if (material) {
    if (material.name) bomForm.value.materialName = String(material.name);
    bomForm.value.unit = recipeUnitForMaterial(material, bomForm.value.materialCategory);
  }
}

function onBomCategoryChange(category: string) {
  const material = materialTypes.value.find((m: Record<string, unknown>) => m.id === bomForm.value.materialTypeId);
  if (material) {
    bomForm.value.unit = recipeUnitForMaterial(material, category);
    return;
  }
  bomForm.value.unit = category === 'PACKAGING' ? 'pcs' : 'g';
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
  // fool-proof Rule 1: 字段级校验, 不静默丢给后端报晦涩 400.
  if (!bomForm.value.materialCategory) {
    ElMessage.warning('请选择物料类别');
    return;
  }
  // Phase 1: 物料名称已改为从「关联原料」自动带入，校验改为要求选中关联原料
  if (!bomForm.value.materialTypeId) {
    ElMessage.warning('请选择关联原料（物料名称将自动带入）');
    return;
  }
  if (bomForm.value.standardQuantity == null || Number(bomForm.value.standardQuantity) <= 0) {
    ElMessage.warning('成品用量必须大于 0');
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

// ========== Excel 导入 ==========
interface ParsedImportRow {
  materialName: string;
  materialCategory: string;
  standardQuantity: number;
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
    ['物料名', '物料类别(RAW/AUXILIARY/PACKAGING)', '成品含量', '出成率%', '单位'],
    ['示例: 猪蹄', 'RAW', 200, 61, 'g'],
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
          const standardQuantity = Number(r['成品含量'] ?? 0);
          const yieldRateRaw = r['出成率%'] ?? r['出成率'];
          const yieldRate =
            yieldRateRaw === '' || yieldRateRaw == null
              ? null
              : Number(yieldRateRaw);
          const unit = String(r['单位'] ?? 'g').trim() || 'g';
          return { materialName, materialCategory, standardQuantity, yieldRate, unit };
        })
        .filter((r) => r.materialName.length > 0 && r.standardQuantity > 0);

      if (parsed.length === 0) {
        ElMessage.warning('未解析到有效行（物料名必填，成品含量必须大于 0）');
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
          <el-button
            v-if="canWrite"
            type="warning"
            style="margin-left: 12px;"
            :disabled="!selectedProductTypeId"
            @click="handleOpenAdjustDialog"
          >对话微调</el-button>
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
              <el-button
                v-if="canWrite"
                size="small"
                :disabled="!selectedProductTypeId"
                @click="handleImportClick"
              >Excel 导入</el-button>
              <el-button size="small" @click="downloadBomTemplate">下载模板</el-button>
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
          <el-table-column prop="standardQuantity" label="成品用量" width="120" align="right">
            <template #default="{ row }">
              {{ (row.standardQuantity || 0).toFixed(4) }} {{ row.unit || '' }}
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
          <el-table-column label="原料投量/份" width="120" align="right">
            <template #default="{ row }">
              {{ row.conversionRate
                ? ((row.standardQuantity || 0) / row.conversionRate).toFixed(4)
                : ((row.standardQuantity || 0) / ((row.yieldRate != null ? row.yieldRate : 100) / 100)).toFixed(4) }}
              {{ row.unit || '' }}
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
          这里维护配方头（产出规格 + 整体出成率），原辅料明细在下方表格维护
        </template>
        <template #default>
          创建配方头会使用当前原辅料明细生成草稿配方；编辑配方头只修改每单位产出量、产出单位和整体出成率。
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
          <div class="form-tip">整体出成率，范围 0.01–100；单行出成率在原辅料弹窗里维护</div>
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
        <span>创建时会把上方原辅料明细一起存为草稿配方；请先确认每行已关联原料且成品用量大于 0</span>
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
        <el-form-item label="物料类别" required>
          <el-select v-model="bomForm.materialCategory" style="width: 100%" @change="onBomCategoryChange">
            <el-option label="原料" value="RAW" />
            <el-option label="辅料" value="AUXILIARY" />
            <el-option label="包材" value="PACKAGING" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联原料">
          <el-select
            v-model="bomForm.materialTypeId"
            placeholder="输入名称筛选，或按上方物料类别自动筛选"
            filterable
            clearable
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
          <div class="form-tip">已按当前「物料类别」筛选，切换类别后可选项会跟着变</div>
        </el-form-item>
        <el-form-item label="成品用量" required>
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
          <div class="form-tip">
            {{ bomQuantityHelpText() }}
          </div>
        </el-form-item>
        <el-form-item label="计量单位">
          <el-select v-model="bomForm.unit" placeholder="选择单位" style="width: 100%">
            <el-option label="克 (g)" value="g" />
            <el-option label="千克 (kg)" value="kg" />
            <el-option label="毫升 (mL)" value="mL" />
            <el-option label="升 (L)" value="L" />
            <el-option label="只" value="只" />
            <el-option label="个" value="个" />
            <el-option label="件 (pcs)" value="pcs" />
            <el-option
              v-if="selectedMaterialUnit() && !['g','kg','mL','L','只','个','pcs'].includes(selectedMaterialUnit())"
              :label="selectedMaterialUnit()"
              :value="selectedMaterialUnit()"
            />
          </el-select>
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
            重量型原料建议使用 g，系统调拨/库存校验会自动按 g ↔ kg 换算。
          </div>
        </el-form-item>
        <!-- Phase B: 评估按钮 (RAW 类别时显示) -->
        <el-form-item
          v-if="bomForm.materialCategory === 'RAW' && bomUnitIsWeight"
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
                  :title="estimateResult.actionHint || '请先在生产管理→成品 / SKU填写标准克重'"
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
        <!-- 出成率输入 (仅原料; 辅料/包材无出成率折算) -->
        <!-- GAP F3: 无客户端 ≤100 校验, 增重工序 (保水/腌制) 出成率合法超 100% -->
        <el-form-item v-if="bomForm.materialCategory === 'RAW'" label="出成率%">
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
        <!-- D2: 实时显示实际原料用量 (仅原料, 考虑出成率) -->
        <!-- GAP F7: yieldRate null → 显示「待评估」, 不显示误导性数字 -->
        <el-form-item v-if="bomForm.materialCategory === 'RAW'" label="实际原料用量">
          <div :class="computedActualQuantity == null ? 'bom-computed-quantity bom-computed-quantity--pending' : 'bom-computed-quantity'">
            <template v-if="computedActualQuantity == null">
              <el-tag type="warning" size="small" disable-transitions>待评估</el-tag>
              <span class="bom-computed-quantity__hint"> (出成率未填，暂无法计算)</span>
            </template>
            <template v-else>
              {{ computedActualQuantity.toFixed(4) }}
              <span> {{ bomForm.unit }}</span>
            </template>
          </div>
          <div class="form-tip">
            {{ actualQuantityHelpText() }}
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
        <el-form-item v-if="canViewPrice" label="单价（含税）">
          <el-input-number v-model="bomForm.unitPrice" :min="0" :precision="4" :step="0.1" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="税率%">
          <el-input-number v-model="bomForm.taxRate" :min="0" :max="100" :precision="0" :step="1" style="width: 100%" />
        </el-form-item>
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
        <el-table-column prop="standardQuantity" label="成品含量" width="100" align="right" />
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
