<script setup lang="ts">
/**
 * ProductRecipeView — 调料配方 tab (U6 + U7 + 按工序重构)
 *
 * U6: 从 BOM seasoning API 加载/保存调料配方，绑定到当前 BOM 配方。
 *     DRAFT BOM → 可编辑；ACTIVE/ARCHIVED → 只读 + 克隆提示。
 * U7: 若产品无 BOM（404）→ EmptyState + useCreateAndReturn 跳至原辅料配方 tab 新建 BOM。
 *
 * 按工序重构 (2026-07): 调料配方不再是"注射段/熟制段"两个扁平大块，而是按产品的
 * 工序链 (ProductWorkProcess) 逐工序展示 —— 每道工序按其 processCategory 渲染对应表单:
 *   '熟制' → 第二锅起比例 + 卤料明细表 (COOKING 段, 老汤「计入调料」开关)
 *   '注射' → 绝对注射量(kg) + 注射内容明细表 (INJECTION 段, 恒计入调料无开关)
 *   其他(普通) → 仅明细表 (可留空)
 * 若产品尚未配置工序链 → EmptyState 引导去配置工序 (fool-proof Rule 5)。
 *
 * 不再使用 /product-recipes 端点（api/productRecipe.ts）。
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import {
  bomSeasoningApi,
  type BomSeasoningResponse,
  type BomSeasoningItem,
  type ProcessSeasoningParam,
} from '@/api/bom';
import { getProductWorkProcesses, type ProductWorkProcessItem } from '@/api/processProduction';
import { get } from '@/api/request';
import { isNotFoundError } from '@/api/notFound';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Delete, InfoFilled } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
import {
  applySeasoningMaterial,
  filterSeasoningMaterials,
  isPotSequencingEnabled,
  percentToRatio,
  ratioToPercent,
  validatePotRatio,
  validateSeasoningRows,
  type SeasoningMaterialOption,
} from './seasoning/seasoningForm';

// =========================================================================
// Auth + composables
// =========================================================================
const authStore = useAuthStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId as string);
const { canReach, goCreate } = useCreateAndReturn();

// fool-proof Rule 5: 两处 EmptyState (无 BOM / 无工序链) 都跳到"生产"模块的写入页, 同一权限域
const canWriteProduction = computed(() => canReach('production', { write: true }));

// =========================================================================
// Product selector
// =========================================================================
const productTypes = ref<TableRow[]>([]);
const selectedProductTypeId = ref<string>('');
const productTypesLoading = ref(false);

async function loadProductTypes() {
  if (!factoryId.value) return;
  productTypesLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) {
      const all = res.data as TableRow[];
      // Mirror bom/index.vue: only FINISHED_PRODUCT (+ legacy entries without category)
      productTypes.value = all.filter(
        (p) =>
          p.productCategory === 'FINISHED_PRODUCT' ||
          p.category === '成品' ||
          !p.productCategory,
      );
      // Pre-select first product, or the one carried in query string
      const queryPid = route.query.productTypeId as string | undefined;
      if (queryPid && productTypes.value.some((p) => p.id === queryPid)) {
        selectedProductTypeId.value = queryPid;
      } else if (productTypes.value.length > 0 && !selectedProductTypeId.value) {
        selectedProductTypeId.value = productTypes.value[0].id;
      }
    }
  } catch {
    ElMessage({ message: '加载产品列表失败', type: 'error', duration: 0, showClose: true });
  } finally {
    productTypesLoading.value = false;
  }
}

// =========================================================================
// 工序链 (ProductWorkProcess) — 调料配方分组锚点
// =========================================================================
const workProcesses = ref<ProductWorkProcessItem[]>([]);
const selectedWorkProcessId = ref('');

/** 按 processOrder 排序展示 */
const sortedWorkProcesses = computed(() =>
  workProcesses.value.slice().sort((a, b) => a.processOrder - b.processOrder),
);

const currentWorkProcess = computed(() =>
  sortedWorkProcesses.value.find((wp) => wp.workProcessId === selectedWorkProcessId.value) ?? null,
);

function categoryOf(wp: ProductWorkProcessItem): '熟制' | '注射' | '普通' {
  if (wp.processCategory === '熟制') return '熟制';
  if (wp.processCategory === '注射') return '注射';
  return '普通';
}

/** 该 category 的调料段默认归属 — 熟制/普通 → COOKING, 注射 → INJECTION */
function sectionOf(wp: ProductWorkProcessItem): 'INJECTION' | 'COOKING' {
  return categoryOf(wp) === '注射' ? 'INJECTION' : 'COOKING';
}

// =========================================================================
// Seasoning data state
// =========================================================================

/** null = not yet loaded; NO_BOM = 该产品尚未建 BOM; NO_PROCESS = 该产品尚未配置工序链 */
type LoadState = 'idle' | 'loading' | 'loaded' | 'NO_BOM' | 'NO_PROCESS' | 'error';
const loadState = ref<LoadState>('idle');

const current = ref<BomSeasoningResponse | null>(null);

/**
 * 克隆后锁定到新草稿的 recipeId — 因为克隆出的 DRAFT is_current=false,
 * 若仍按产品 getByProduct 会取回旧 ACTIVE, 用户编辑看似丢失 (audit R2 Issue 3).
 * pin 时按 recipeId 加载, 切换产品时清除. null = 按产品加载当前 BOM.
 */
const pinnedRecipeId = ref<string | null>(null);

// Form mirror — local editable copy
// 旧 header 级字段 (cookingPotBaseKg/subsequentPotRatio/injectionRate): 新 UI 不再编辑,
// 仅保留已加载的值原样透传保存 (向后兼容, 见 bom.ts 类型注释)。
const formCookingPotBaseKg = ref<number | null>(null);
const formSubsequentPotRatio = ref<number | null>(null);
const formInjectionRate = ref<number | null>(null);
const formItems = ref<BomSeasoningItem[]>([]);

interface ProcessParamForm {
  subsequentPotRatio: number | null;
  injectionAmountKg: number | null;
  notes: string | null;
}

/** 按 workProcessId 索引的每工序调料参数（熟制=锅序比例, 注射=绝对注射量） */
const processParamsMap = ref<Record<string, ProcessParamForm>>({});

function defaultParamFor(category: '熟制' | '注射' | '普通'): ProcessParamForm {
  return {
    subsequentPotRatio: null,
    injectionAmountKg: null,
    notes: null,
  };
}

/** workProcesses 加载完成后, 用已加载的 processParams (若有) 初始化每工序表单值 */
function initProcessParams(loadedParams: ProcessSeasoningParam[]) {
  const map: Record<string, ProcessParamForm> = {};
  for (const wp of workProcesses.value) {
    const category = categoryOf(wp);
    const existing = loadedParams.find((p) => p.workProcessId === wp.workProcessId);
    map[wp.workProcessId] = existing
      ? {
          subsequentPotRatio: existing.subsequentPotRatio,
          injectionAmountKg: existing.injectionAmountKg,
          notes: existing.notes ?? null,
        }
      : defaultParamFor(category);
  }
  processParamsMap.value = map;
}

const isReadOnly = computed(
  () => current.value !== null && current.value.status !== 'DRAFT',
);

const seasoningMaterials = ref<SeasoningMaterialOption[]>([]);
const materialsLoading = ref(false);

async function loadSeasoningMaterials() {
  if (!factoryId.value) return;
  materialsLoading.value = true;
  try {
    const res = await get<SeasoningMaterialOption[]>(`/${factoryId.value}/raw-material-types/active`);
    const data = res.success && res.data ? res.data : [];
    seasoningMaterials.value = filterSeasoningMaterials(Array.isArray(data) ? data : []);
  } catch {
    ElMessage({ message: '加载辅料档案失败，请刷新重试', type: 'error', duration: 0, showClose: true });
  } finally {
    materialsLoading.value = false;
  }
}

/** Apply loaded response to local form state */
function applyToForm(data: BomSeasoningResponse) {
  current.value = data;
  formCookingPotBaseKg.value = data.cookingPotBaseKg;
  formSubsequentPotRatio.value = data.subsequentPotRatio;
  formInjectionRate.value = data.injectionRate;
  // Deep-copy items so edits don't mutate the original
  formItems.value = data.seasoningItems.map((item) => ({ ...item }));
  initProcessParams(data.processParams || []);
}

// =========================================================================
// Load 工序链 + 调料配方 for selected product
// =========================================================================
async function loadSeasoning() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  loadState.value = 'loading';
  current.value = null;
  formItems.value = [];
  workProcesses.value = [];
  processParamsMap.value = {};
  try {
    // 先取工序链 —— 决定按工序分组的锚点 + NO_PROCESS 判定, 与 BOM 是否存在无关
    const wpRes = await getProductWorkProcesses(factoryId.value, selectedProductTypeId.value);
    workProcesses.value = wpRes.success && wpRes.data ? wpRes.data : [];

    if (workProcesses.value.length === 0) {
      // fool-proof Rule 5: 无工序链 → EmptyState 引导去配置, 不继续加载 BOM
      loadState.value = 'NO_PROCESS';
      return;
    }
    if (!workProcesses.value.some((wp) => wp.workProcessId === selectedWorkProcessId.value)) {
      selectedWorkProcessId.value = sortedWorkProcesses.value[0]?.workProcessId ?? '';
    }

    // pin 时按 recipeId 取 (克隆出的非当前草稿); 否则按产品取当前 BOM.
    const res = pinnedRecipeId.value
      ? await bomSeasoningApi.getById(factoryId.value, pinnedRecipeId.value)
      : await bomSeasoningApi.getByProduct(factoryId.value, selectedProductTypeId.value);
    if (res.success && res.data) {
      applyToForm(res.data);
      loadState.value = 'loaded';
    } else {
      // success=false but no throw — treat as error
      loadState.value = 'error';
    }
  } catch (err: unknown) {
    // 后端 getSeasoningByProduct 返 ApiResponse.error(404,...) = HTTP 200 + body code 404 (数字),
    // 需识别 body-code (而非仅 HTTP status) 否则 NO_BOM 永落 error 态 → U7 引导失效 (audit R4).
    // 判定逻辑抽到 isNotFoundError (有单测覆盖各种 code 形态).
    if (isNotFoundError(err)) {
      // U7: product has no BOM — show EmptyState, suppress the generic 404 toast
      // (request.ts already showed it; we need to override the UX instead of adding a second toast)
      loadState.value = 'NO_BOM';
    } else {
      loadState.value = 'error';
    }
  }
}

watch(selectedProductTypeId, () => {
  pinnedRecipeId.value = null; // 切换产品 → 回到按产品取当前 BOM (放弃上一克隆草稿的 pin)
  if (selectedProductTypeId.value) loadSeasoning();
  else {
    loadState.value = 'idle';
    current.value = null;
    formItems.value = [];
    workProcesses.value = [];
    selectedWorkProcessId.value = '';
  }
});

onMounted(async () => {
  await Promise.all([loadProductTypes(), loadSeasoningMaterials()]);
  if (selectedProductTypeId.value) await loadSeasoning();
});

// =========================================================================
// Ingredient helpers — grouped by workProcessId
// =========================================================================
function itemsForProcess(workProcessId: string): BomSeasoningItem[] {
  // 只按 workProcessId 分组 (不再叠加按 section 过滤) —— workProcessId 是唯一分组锚点键,
  // 避免万一 section 与当前工序 category 不一致时数据被误藏 (数据完整性优先于展示整洁)。
  return formItems.value.filter((i) => i.workProcessId === workProcessId);
}

/** 未能匹配到任何现存工序的调料行 (工序被删除/历史脏数据等边界情况) — 兜底展示避免保存时静默丢弃 */
const orphanItems = computed(() => {
  const validIds = new Set(workProcesses.value.map((wp) => wp.workProcessId));
  return formItems.value.filter((i) => !i.workProcessId || !validIds.has(i.workProcessId));
});

/** 未配置任何调料的工序数 — fool-proof 软提示 (非阻塞) */
const unconfiguredProcessCount = computed(
  () => workProcesses.value.filter((wp) => itemsForProcess(wp.workProcessId).length === 0).length,
);

const currentItems = computed(() =>
  selectedWorkProcessId.value ? itemsForProcess(selectedWorkProcessId.value) : [],
);

const currentProcessParam = computed(() =>
  selectedWorkProcessId.value ? processParamsMap.value[selectedWorkProcessId.value] : undefined,
);

const currentPotEnabled = computed({
  get: () => isPotSequencingEnabled(currentProcessParam.value?.subsequentPotRatio),
  set: (enabled: boolean) => {
    if (!currentProcessParam.value) return;
    currentProcessParam.value.subsequentPotRatio = enabled
      ? (currentProcessParam.value.subsequentPotRatio ?? 0.5)
      : null;
  },
});

const currentPotPercent = computed({
  get: () => ratioToPercent(currentProcessParam.value?.subsequentPotRatio),
  set: (percent: number | null) => {
    if (currentProcessParam.value) {
      currentProcessParam.value.subsequentPotRatio = percentToRatio(percent);
    }
  },
});

function nextSeq(workProcessId: string): number {
  const existing = itemsForProcess(workProcessId);
  return existing.length > 0 ? Math.max(...existing.map((i) => i.seq)) + 1 : 1;
}

function addIngredient(wp: ProductWorkProcessItem) {
  if (isReadOnly.value) return;
  const workProcessId = wp.workProcessId;
  formItems.value.push({
    workProcessId,
    materialTypeId: null,
    section: sectionOf(wp),
    seq: nextSeq(workProcessId),
    name: '',
    dosagePerKgG: null,
    priceSource1: null,
    priceSource2: null,
    countInSeasoning: true,
    remark: '',
  });
}

function onMaterialChange(item: BomSeasoningItem) {
  const material = seasoningMaterials.value.find((option) => option.id === item.materialTypeId);
  if (material) Object.assign(item, applySeasoningMaterial(material));
}

function displayUnit(item: BomSeasoningItem): string {
  return item.unit || seasoningMaterials.value.find((option) => option.id === item.materialTypeId)?.unit || 'g';
}

function removeIngredient(item: BomSeasoningItem) {
  if (isReadOnly.value) return;
  formItems.value = formItems.value.filter((x) => x !== item);
}

// =========================================================================
// Save
// =========================================================================
const saving = ref(false);

async function save() {
  if (!factoryId.value || !current.value) return;
  if (isReadOnly.value) {
    ElMessage.warning('当前 BOM 不是草稿状态，无法保存');
    return;
  }

  const rowErrors = validateSeasoningRows(formItems.value);
  if (rowErrors.length > 0) {
    ElMessage.warning(rowErrors[0]);
    return;
  }
  for (const wp of workProcesses.value) {
    const ratio = processParamsMap.value[wp.workProcessId]?.subsequentPotRatio;
    const ratioError = validatePotRatio(isPotSequencingEnabled(ratio), ratioToPercent(ratio));
    if (ratioError) {
      ElMessage.warning(`${wp.processName}：${ratioError}`);
      return;
    }
  }

  saving.value = true;
  try {
    // Re-assign seq per (workProcessId, section) group — 每个工序表格自己的序号从 1 开始,
    // 而不是全局递增 (旧逻辑是按 section 全局计数, 按工序分组展示后会看着"缺号")。
    const seqCounters: Record<string, number> = {};
    const items = formItems.value.map((item) => {
      const key = `${item.workProcessId}::${item.section}`;
      const seq = (seqCounters[key] = (seqCounters[key] ?? 0) + 1);
      return {
        workProcessId: item.workProcessId,
        materialTypeId: item.materialTypeId as string,
        section: item.section,
        seq,
        name: item.name.trim(),
        dosagePerKgG: item.dosagePerKgG,
        priceSource1: item.priceSource1,
        priceSource2: item.priceSource2,
        countInSeasoning: item.countInSeasoning,
        remark: item.remark ?? null,
      };
    });

    // 每道工序都可显式启用锅序；注射工序同时保留绝对注射量语义。
    const processParams: ProcessSeasoningParam[] = workProcesses.value
      .map((wp) => {
        const category = categoryOf(wp);
        const p = processParamsMap.value[wp.workProcessId] ?? defaultParamFor(category);
        return {
          workProcessId: wp.workProcessId,
          subsequentPotRatio: p.subsequentPotRatio,
          injectionAmountKg: category === '注射' ? p.injectionAmountKg : null,
          notes: p.notes ?? null,
        };
      })
      // 不为“完全未配置”的工序创建空参数行；否则后端会把空行误认成该工序已启用调料核算。
      .filter((param) =>
        param.subsequentPotRatio != null ||
        param.injectionAmountKg != null ||
        (param.notes != null && param.notes.trim() !== ''),
      );

    const res = await bomSeasoningApi.save(factoryId.value, current.value.bomRecipeId, {
      // legacy header 字段: 新 UI 不提供编辑入口, 原样透传已加载值 (不清空, 向后兼容)
      cookingPotBaseKg: formCookingPotBaseKg.value,
      subsequentPotRatio: formSubsequentPotRatio.value,
      injectionRate: formInjectionRate.value,
      seasoningItems: items,
      processParams,
    });
    if (res.success && res.data) {
      applyToForm(res.data);
      ElMessage.success('调料配方已保存');
    } else {
      ElMessage({
        message: res.message || '保存失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (err: unknown) {
    const msg =
      (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
      (err as { message?: string })?.message ||
      '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

// =========================================================================
// Clone (ACTIVE/ARCHIVED → new DRAFT)
// =========================================================================
const cloning = ref(false);

async function handleClone() {
  if (!factoryId.value || !current.value) return;
  const productName = current.value.productName || '此产品';
  const currentStatus = current.value.status === 'ACTIVE' ? '生效版本' : '已归档版本';
  try {
    await ElMessageBox.confirm(
      // fool-proof Rule 2: show product name + current status in context
      `当前 BOM 为「${productName}」的${currentStatus}，不可直接编辑。\n克隆后将创建新 DRAFT 版本供修改，原版本不受影响。\n\n确认克隆为新版本？`,
      '克隆 BOM 为新草稿',
      {
        confirmButtonText: '克隆为新版本',
        cancelButtonText: '取消',
        type: 'info',
      },
    );
  } catch {
    return; // user cancelled
  }

  cloning.value = true;
  try {
    const res = await bomSeasoningApi.clone(factoryId.value, current.value.bomRecipeId);
    if (res.success && res.data) {
      ElMessage.success('已克隆为新草稿，正在加载...');
      // pin 到新草稿 (is_current=false) → 后续刷新按 recipeId 取, 不会回退到旧 ACTIVE.
      pinnedRecipeId.value = res.data.id;
      // Load the new DRAFT's seasoning directly by recipeId
      // (workProcesses 不受克隆影响, 复用已加载的 workProcesses.value 分组)
      const newRes = await bomSeasoningApi.getById(factoryId.value, res.data.id);
      if (newRes.success && newRes.data) {
        applyToForm(newRes.data);
        loadState.value = 'loaded';
      }
    } else {
      ElMessage({
        message: res.message || '克隆失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (err: unknown) {
    // ApiError 把后端 message 放 err.message (非 err.response.data.message) — 补 err.message
    // 兜底, 否则 DRAFT 守卫等业务拒绝被吞成通用文案 (audit R4 fool-proof a/b).
    const msg =
      (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
      (err as { message?: string })?.message ||
      '克隆失败，请稍后重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    cloning.value = false;
  }
}

// =========================================================================
// Activate cloned DRAFT → ACTIVE (使编辑后的新版本生效为当前配方)
// =========================================================================
const activating = ref(false);

/** pin 的草稿(克隆产物) 需激活才生效; 非 pin 的当前 BOM 草稿本就是 is_current, 无需此动作. */
const canActivate = computed(
  () => pinnedRecipeId.value !== null && current.value?.status === 'DRAFT',
);

async function handleActivate() {
  if (!factoryId.value || !current.value) return;
  try {
    await ElMessageBox.confirm(
      // fool-proof Rule 2: 上下文 — 品名 + 此操作含义
      `将「${current.value.productName || '此产品'}」的新草稿激活为当前生效配方。\n` +
        `激活后, 之后的生产报工调料成本将按此版本计算, 原版本归档。\n\n确认激活？`,
      '激活为当前版本',
      { confirmButtonText: '激活', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  activating.value = true;
  try {
    const res = await bomSeasoningApi.activate(factoryId.value, current.value.bomRecipeId);
    if (res.success) {
      ElMessage.success('已激活为当前生效配方');
      pinnedRecipeId.value = null; // 解除 pin → 回到按产品取 (现在它就是当前 BOM)
      await loadSeasoning();
    } else {
      ElMessage({ message: res.message || '激活失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (err: unknown) {
    const msg =
      (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
      (err as { message?: string })?.message ||
      '激活失败，请稍后重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    activating.value = false;
  }
}

// =========================================================================
// U7: EmptyState — no BOM → guide user to create BOM first
// =========================================================================
function goCreateBom() {
  if (!canWriteProduction.value) return;
  // 显式构造 returnTo (tab=recipe) — 宿主 bom-unified 仅在 mount 时读 ?tab, 不把 tab 写回 URL,
  // 故 route.fullPath 不含 tab=recipe; 不显式传 reopen 会返回到默认 materials tab (audit R2 Issue 5).
  const productParam = selectedProductTypeId.value
    ? `&productTypeId=${selectedProductTypeId.value}`
    : '';
  // 路由实际路径是 /production/bom (bom-unified 组件挂在此 path); 用 bom-unified 会 404 (headed 验证抓到)。
  const targetPath = `/production/bom?tab=materials${productParam}`;
  const returnPath = `/production/bom?tab=recipe${productParam}`;
  goCreate(targetPath, { reopen: returnPath });
}

// =========================================================================
// 按工序重构: EmptyState — 无工序链 → 引导去配置产品-工序 (fool-proof Rule 5)
// =========================================================================
function goConfigureProcess() {
  if (!canWriteProduction.value) return;
  const productParam = selectedProductTypeId.value
    ? `?productTypeId=${selectedProductTypeId.value}`
    : '';
  // goCreate 默认 reopen=当前 fullPath, 配置完工序返回本 tab 会自动重新加载工序链
  goCreate(`/system/product-processes${productParam}`);
}
</script>

<template>
  <div class="recipe-view">
    <!-- Product selector (mirrors materials tab UX) -->
    <div class="recipe-view__header">
      <el-select
        v-model="selectedProductTypeId"
        placeholder="选择产品"
        style="width: 280px;"
        filterable
        :loading="productTypesLoading"
      >
        <el-option
          v-for="product in productTypes"
          :key="product.id"
          :label="product.name"
          :value="product.id"
        />
      </el-select>
      <el-button
        :icon="Refresh"
        style="margin-left: 8px;"
        :loading="loadState === 'loading'"
        @click="loadSeasoning"
        :disabled="!selectedProductTypeId"
      >刷新</el-button>
    </div>

    <!-- Loading skeleton -->
    <div v-if="loadState === 'loading'" v-loading="true" style="min-height: 200px;" />

    <!-- 按工序重构: 无工序链 → EmptyState (fool-proof Rule 5: dead-end → navigation) -->
    <el-empty
      v-else-if="loadState === 'NO_PROCESS'"
      description="该产品尚未配置工序链，请先去配置工序"
      style="margin-top: 40px;"
    >
      <template v-if="canWriteProduction">
        <el-button type="primary" @click="goConfigureProcess">
          前往配置工序
        </el-button>
        <p style="margin-top: 8px; font-size: 12px; color: #909399;">
          配置完成后，点击顶部返回栏回到此页继续填写调料配方
        </p>
      </template>
      <el-alert
        v-else
        :closable="false"
        type="warning"
        show-icon
        title="需要生产写入权限才能配置工序，请联系管理员开通"
        style="max-width: 420px;"
      />
    </el-empty>

    <!-- U7: No BOM → EmptyState (fool-proof Rule 5: dead-end → navigation) -->
    <el-empty
      v-else-if="loadState === 'NO_BOM'"
      description="该产品尚未建立 BOM 配方，调料配方挂在 BOM 下，请先创建 BOM"
      style="margin-top: 40px;"
    >
      <!-- el-empty 用 default slot 放动作 (无 #extra slot — headed 验证抓到) -->
      <template v-if="canWriteProduction">
        <el-button type="primary" @click="goCreateBom">
          前往创建 BOM 配方
        </el-button>
        <p style="margin-top: 8px; font-size: 12px; color: #909399;">
          创建完成后，点击顶部返回栏回到此页继续填写调料配方
        </p>
      </template>
      <el-alert
        v-else
        :closable="false"
        type="warning"
        show-icon
        title="需要生产写入权限才能创建 BOM，请联系管理员开通"
        style="max-width: 420px;"
      />
    </el-empty>

    <!-- Error state -->
    <el-empty
      v-else-if="loadState === 'error'"
      description="加载调料配方失败，请刷新重试"
      style="margin-top: 40px;"
    >
      <el-button @click="loadSeasoning">重试</el-button>
    </el-empty>

    <!-- Idle: no product selected -->
    <el-empty
      v-else-if="loadState === 'idle'"
      description="请先选择一个产品"
      style="margin-top: 40px;"
    />

    <!-- Main editor (loaded) -->
    <template v-else-if="loadState === 'loaded' && current">
      <!-- 产品上下文 (fool-proof Rule 2): 始终显示正在编辑哪个产品 + BOM 状态, 防克隆后改错版本 -->
      <div style="display:flex; align-items:center; gap:8px; margin:8px 0 4px;">
        <span style="font-size:15px; font-weight:600;">{{ current.productName }}</span>
        <el-tag
          :type="current.status === 'DRAFT' ? 'warning' : current.status === 'ACTIVE' ? 'success' : 'info'"
          size="small"
          disable-transitions
        >{{ current.status === 'DRAFT' ? '草稿(可编辑)' : current.status === 'ACTIVE' ? '生效版本' : '已归档' }}</el-tag>
        <span style="font-size:12px; color:#909399;">调料配方（按工序）</span>
      </div>

      <!-- fool-proof 软提示 (非阻塞): 部分工序未配置调料 -->
      <el-alert
        v-if="unconfiguredProcessCount > 0"
        type="info"
        show-icon
        :closable="false"
        :title="`检测到 ${unconfiguredProcessCount} 道工序未配置调料，需要的工序请配置，不需要的留空即可`"
        style="margin: 4px 0 12px;"
      />

      <!-- U6 DRAFT gate: read-only banner when BOM is ACTIVE/ARCHIVED (fool-proof Rule 2 + 5) -->
      <el-alert
        v-if="isReadOnly"
        type="warning"
        show-icon
        :closable="false"
        style="margin: 12px 0;"
      >
        <template #title>
          <span>
            当前 BOM「{{ current.productName }}」为
            <el-tag
              :type="current.status === 'ACTIVE' ? 'success' : 'info'"
              size="small"
              disable-transitions
            >{{ current.status === 'ACTIVE' ? '生效版本(ACTIVE)' : '已归档(ARCHIVED)' }}</el-tag>
            ，修改调料需克隆为新版本
          </span>
        </template>
        <template #default>
          <el-button
            type="primary"
            size="small"
            :loading="cloning"
            style="margin-top: 6px;"
            @click="handleClone"
          >
            克隆为新版本以修改
          </el-button>
        </template>
      </el-alert>

      <!-- 克隆草稿提示 (fool-proof Rule 2): 编辑中的是非当前草稿, 保存后需激活 -->
      <el-alert
        v-if="pinnedRecipeId && !isReadOnly"
        type="info"
        show-icon
        :closable="false"
        title="您正在编辑新克隆的草稿（尚未生效）。保存后点「激活此版本」使其成为当前生效配方，否则生产报工仍按原版本计算成本。"
        style="margin: 12px 0;"
      />

      <el-alert
        v-if="orphanItems.length > 0"
        type="error"
        :closable="false"
        show-icon
        title="存在已删除工序下的历史调料。请删除这些记录后再保存，避免数据错配。"
        class="recipe-view__orphan-alert"
      >
        <template #default>
          <div v-for="row in orphanItems" :key="row.id || `${row.workProcessId}-${row.seq}`" class="orphan-row">
            <span>{{ row.name || '未命名调料' }}</span>
            <el-tag v-if="!row.materialTypeId" type="danger" size="small">历史数据，需重新选择物料</el-tag>
            <el-button v-if="!isReadOnly" link type="danger" :icon="Delete" @click="removeIngredient(row)">删除</el-button>
          </div>
        </template>
      </el-alert>

      <div class="seasoning-workspace">
        <aside data-testid="seasoning-process-nav" class="process-nav">
          <div class="process-nav__title">工序导航</div>
          <button
            v-for="process in sortedWorkProcesses"
            :key="process.workProcessId"
            type="button"
            class="process-nav__item"
            :class="{ 'is-active': selectedWorkProcessId === process.workProcessId }"
            @click="selectedWorkProcessId = process.workProcessId"
          >
            <span class="process-nav__order">{{ process.processOrder }}</span>
            <span class="process-nav__content">
              <strong>{{ process.processName }}</strong>
              <small>{{ itemsForProcess(process.workProcessId).length }} 种调料</small>
            </span>
            <el-tag
              v-if="isPotSequencingEnabled(processParamsMap[process.workProcessId]?.subsequentPotRatio)"
              type="warning"
              size="small"
            >锅序</el-tag>
          </button>
        </aside>

        <section v-if="currentWorkProcess" data-testid="seasoning-current-process" class="process-editor">
          <header class="process-editor__header">
            <div>
              <div class="process-editor__eyebrow">当前工序</div>
              <h3>{{ currentWorkProcess.processOrder }}. {{ currentWorkProcess.processName }}</h3>
              <p>每条用量均按每投入 1 kg 本工序半成品计算。</p>
            </div>
            <el-button
              v-if="!isReadOnly"
              type="primary"
              :icon="Plus"
              @click="addIngredient(currentWorkProcess)"
            >添加调料</el-button>
          </header>

          <div class="process-settings">
            <div class="setting-row">
              <div>
                <strong>锅序调料</strong>
                <p>开启后，报工时填写锅数；第一锅 100%，之后每锅按固定比例。</p>
              </div>
              <el-switch v-model="currentPotEnabled" :disabled="isReadOnly" />
            </div>
            <div v-if="currentPotEnabled" class="setting-row setting-row--nested">
              <span>后续锅占第一锅</span>
              <el-input-number
                v-model="currentPotPercent"
                :min="0"
                :max="100"
                :precision="2"
                :disabled="isReadOnly"
              />
              <span>%</span>
            </div>
            <div v-if="categoryOf(currentWorkProcess) === '注射' && currentProcessParam" class="setting-row setting-row--nested">
              <span>绝对注射量</span>
              <el-input-number
                v-model="currentProcessParam.injectionAmountKg"
                :min="0"
                :precision="3"
                :disabled="isReadOnly"
              />
              <span>kg</span>
              <el-tooltip content="保留注射工序现有语义：该值是本工序一次注射的绝对用量。" placement="top">
                <el-icon><InfoFilled /></el-icon>
              </el-tooltip>
            </div>
          </div>

          <el-table :data="currentItems" size="small" border class="seasoning-table">
            <el-table-column label="辅料 / 调料" min-width="230">
              <template #default="{ row }">
                <el-select
                  v-if="!isReadOnly"
                  v-model="row.materialTypeId"
                  filterable
                  :loading="materialsLoading"
                  placeholder="从辅料档案选择"
                  style="width: 100%;"
                  @change="onMaterialChange(row)"
                >
                  <el-option
                    v-for="material in seasoningMaterials"
                    :key="material.id"
                    :label="material.name"
                    :value="material.id"
                  >
                    <span>{{ material.name }}</span>
                    <span class="material-option-meta">{{ material.unit || 'g' }} · {{ material.movingAvgPrice == null ? '保存时自动带入' : `¥${material.movingAvgPrice}` }}</span>
                  </el-option>
                </el-select>
                <span v-else>{{ row.name }}</span>
                <el-tag v-if="!row.materialTypeId" type="danger" size="small" class="legacy-tag">历史数据，需重新选择物料</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="每 1 kg 本工序投入用量" width="210" align="right">
              <template #default="{ row }">
                <el-input-number
                  v-if="!isReadOnly"
                  v-model="row.dosagePerKgG"
                  :controls="false"
                  :precision="2"
                  :min="0"
                  style="width: 130px;"
                />
                <span v-else>{{ row.dosagePerKgG ?? '—' }}</span>
                <span class="unit-label">g</span>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="80" align="center">
              <template #default="{ row }">{{ displayUnit(row) }}</template>
            </el-table-column>
            <el-table-column label="移动均价" width="130" align="right">
              <template #default="{ row }">
                <span v-if="row.priceSource1 != null">¥{{ Number(row.priceSource1).toFixed(4) }}</span>
                <el-tag v-else type="info" size="small">保存时自动带入</el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="categoryOf(currentWorkProcess) !== '注射'" label="计入调料" width="90" align="center">
              <template #default="{ row }">
                <el-switch v-if="!isReadOnly" v-model="row.countInSeasoning" size="small" />
                <span v-else>{{ row.countInSeasoning ? '是' : '否' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">
                <el-input v-if="!isReadOnly" v-model="row.remark" size="small" placeholder="选填" />
                <span v-else>{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="!isReadOnly" label="操作" width="64" align="center">
              <template #default="{ row }">
                <el-button link type="danger" :icon="Delete" @click="removeIngredient(row)" />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="currentItems.length === 0" description="本工序暂未配置调料" :image-size="56">
            <el-button v-if="!isReadOnly" type="primary" plain :icon="Plus" @click="addIngredient(currentWorkProcess)">添加第一种调料</el-button>
          </el-empty>
        </section>
      </div>

      <!-- Footer actions (sticky: 长表单滚动时保存/激活常驻底部) -->
      <div class="recipe-view__footer">
        <el-button
          v-if="isReadOnly"
          type="primary"
          :loading="cloning"
          @click="handleClone"
        >克隆为新版本以修改</el-button>
        <template v-else>
          <el-button
            :loading="saving"
            @click="save"
          >保存调料配方</el-button>
          <!-- 克隆草稿: 保存后需激活才生效 (audit R2 Issue 3 + usage-logic) -->
          <el-button
            v-if="canActivate"
            type="primary"
            :loading="activating"
            @click="handleActivate"
          >激活此版本</el-button>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.recipe-view {
  padding: 16px 0;
}

.recipe-view__header {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.recipe-view__orphan-alert {
  margin: 12px 0;
}

.orphan-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.seasoning-workspace {
  display: grid;
  grid-template-columns: minmax(190px, 230px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  margin-top: 12px;
}

.process-nav,
.process-editor {
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  background: var(--el-bg-color, #fff);
}

.process-nav {
  position: sticky;
  top: 12px;
  overflow: hidden;
}

.process-nav__title {
  padding: 11px 12px;
  color: var(--el-text-color-secondary, #606266);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .04em;
  background: var(--el-fill-color-light, #f5f7fa);
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.process-nav__item {
  width: 100%;
  min-height: 58px;
  padding: 9px 10px;
  border: 0;
  border-bottom: 1px solid var(--el-border-color-extra-light, #f2f3f5);
  background: transparent;
  display: flex;
  align-items: center;
  gap: 9px;
  text-align: left;
  color: var(--el-text-color-primary, #303133);
  cursor: pointer;
  transition: background-color .15s ease, box-shadow .15s ease;
}

.process-nav__item:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}

.process-nav__item.is-active {
  background: var(--el-color-primary-light-9, #ecf5ff);
  box-shadow: inset 3px 0 0 var(--el-color-primary, #409eff);
}

.process-nav__order {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, #ecf5ff);
  font-size: 12px;
  font-weight: 700;
}

.process-nav__content {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.process-nav__content strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.process-nav__content small {
  color: var(--el-text-color-secondary, #909399);
  font-size: 11px;
}

.process-editor {
  min-width: 0;
  padding: 14px;
}

.process-editor__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.process-editor__header h3 {
  margin: 2px 0 3px;
  font-size: 17px;
}

.process-editor__header p,
.setting-row p {
  margin: 0;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.process-editor__eyebrow {
  color: var(--el-color-primary, #409eff);
  font-size: 11px;
  font-weight: 600;
}

.process-settings {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light, #fafafa);
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.setting-row--nested {
  justify-content: flex-start;
  margin-top: 9px;
  padding-top: 9px;
  border-top: 1px dashed var(--el-border-color, #dcdfe6);
}

.material-option-meta {
  float: right;
  margin-left: 18px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.legacy-tag {
  margin-top: 5px;
}

.unit-label {
  margin-left: 5px;
  color: var(--el-text-color-secondary, #909399);
}

.seasoning-table :deep(.el-table__cell) {
  padding-top: 7px;
  padding-bottom: 7px;
}

@media (max-width: 960px) {
  .seasoning-workspace {
    grid-template-columns: 1fr;
  }

  .process-nav {
    position: static;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .process-nav__title {
    grid-column: 1 / -1;
  }
}

/* sticky 底栏: 长配方表单滚动时, 保存/激活/克隆 常驻底部可见 */
.recipe-view__footer {
  position: sticky;
  bottom: 0;
  z-index: 5;
  text-align: right;
  margin-top: 8px;
  padding: 10px 12px;
  background: var(--el-bg-color, #fff);
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
}
</style>
