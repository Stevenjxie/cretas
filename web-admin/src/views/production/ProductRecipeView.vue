<script setup lang="ts">
/**
 * ProductRecipeView — 调料配方 tab (U6 + U7)
 *
 * U6: 从 BOM seasoning API 加载/保存调料配方（注射+熟制段），绑定到当前 BOM 配方。
 *     DRAFT BOM → 可编辑；ACTIVE/ARCHIVED → 只读 + 克隆提示。
 * U7: 若产品无 BOM（404）→ EmptyState + useCreateAndReturn 跳至原辅料配方 tab 新建 BOM。
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
} from '@/api/bom';
import { get } from '@/api/request';
import { isNotFoundError } from '@/api/notFound';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Delete, InfoFilled } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

// =========================================================================
// Auth + composables
// =========================================================================
const authStore = useAuthStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId as string);
const { canReach, goCreate } = useCreateAndReturn();

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
// Seasoning data state
// =========================================================================

/** null = not yet loaded; 'NO_BOM' = 404 from backend */
type LoadState = 'idle' | 'loading' | 'loaded' | 'NO_BOM' | 'error';
const loadState = ref<LoadState>('idle');

const current = ref<BomSeasoningResponse | null>(null);

/**
 * 克隆后锁定到新草稿的 recipeId — 因为克隆出的 DRAFT is_current=false,
 * 若仍按产品 getByProduct 会取回旧 ACTIVE, 用户编辑看似丢失 (audit R2 Issue 3).
 * pin 时按 recipeId 加载, 切换产品时清除. null = 按产品加载当前 BOM.
 */
const pinnedRecipeId = ref<string | null>(null);

// Form mirror — local editable copy
const formCookingPotBaseKg = ref<number | null>(null);
const formSubsequentPotRatio = ref<number | null>(0.3333);
const formInjectionRate = ref<number | null>(null);
const formItems = ref<BomSeasoningItem[]>([]);

const injectionRows = computed(() => formItems.value.filter((i) => i.section === 'INJECTION'));
const cookingRows = computed(() => formItems.value.filter((i) => i.section === 'COOKING'));

const isReadOnly = computed(
  () => current.value !== null && current.value.status !== 'DRAFT',
);

/** Apply loaded response to local form state */
function applyToForm(data: BomSeasoningResponse) {
  current.value = data;
  formCookingPotBaseKg.value = data.cookingPotBaseKg;
  formSubsequentPotRatio.value = data.subsequentPotRatio;
  formInjectionRate.value = data.injectionRate;
  // Deep-copy items so edits don't mutate the original
  formItems.value = data.seasoningItems.map((item) => ({ ...item }));
}

// =========================================================================
// Load seasoning for selected product
// =========================================================================
async function loadSeasoning() {
  if (!factoryId.value || !selectedProductTypeId.value) return;
  loadState.value = 'loading';
  current.value = null;
  formItems.value = [];
  try {
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
  }
});

onMounted(async () => {
  await loadProductTypes();
  if (selectedProductTypeId.value) await loadSeasoning();
});

// =========================================================================
// Ingredient helpers
// =========================================================================
function nextSeq(section: 'INJECTION' | 'COOKING'): number {
  const existing = formItems.value.filter((i) => i.section === section);
  return existing.length > 0
    ? Math.max(...existing.map((i) => i.seq)) + 1
    : 1;
}

function addIngredient(section: 'INJECTION' | 'COOKING') {
  if (isReadOnly.value) return;
  formItems.value.push({
    section,
    seq: nextSeq(section),
    name: '',
    dosagePerKgG: null,
    priceSource1: null,
    priceSource2: null,
    countInSeasoning: true,
    remark: '',
  });
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

  // fool-proof Rule 1: pre-validate before sending
  if (formItems.value.length === 0) {
    ElMessage.warning('请至少添加一条调料');
    return;
  }
  const blankNames = formItems.value.filter((i) => !i.name.trim());
  if (blankNames.length > 0) {
    ElMessage.warning('存在未填名称的调料行，请补充');
    return;
  }
  // fool-proof Rule 1: 后端 dosagePerKgG @NotNull, 提前拦住空值避免 400 (audit R2 Issue 2)
  const nullDosage = formItems.value.filter(
    (i) => i.dosagePerKgG === null || i.dosagePerKgG === undefined,
  );
  if (nullDosage.length > 0) {
    ElMessage.warning('存在未填「每 kg 用量」的调料行，请补充后再保存');
    return;
  }

  saving.value = true;
  try {
    // Re-assign seq to keep order clean
    let injSeq = 1;
    let cookSeq = 1;
    const items = formItems.value.map((item) => ({
      section: item.section,
      seq: item.section === 'INJECTION' ? injSeq++ : cookSeq++,
      name: item.name.trim(),
      dosagePerKgG: item.dosagePerKgG,
      priceSource1: item.priceSource1,
      priceSource2: item.priceSource2,
      countInSeasoning: item.countInSeasoning,
      remark: item.remark ?? null,
    }));

    const res = await bomSeasoningApi.save(factoryId.value, current.value.bomRecipeId, {
      cookingPotBaseKg: formCookingPotBaseKg.value,
      subsequentPotRatio: formSubsequentPotRatio.value,
      injectionRate: formInjectionRate.value,
      seasoningItems: items,
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
const canCreateBom = computed(() => canReach('production', { write: true }));

function goCreateBom() {
  if (!canCreateBom.value) return;
  // 显式构造 returnTo (tab=recipe) — 宿主 bom-unified 仅在 mount 时读 ?tab, 不把 tab 写回 URL,
  // 故 route.fullPath 不含 tab=recipe; 不显式传 reopen 会返回到默认 materials tab (audit R2 Issue 5).
  const productParam = selectedProductTypeId.value
    ? `&productTypeId=${selectedProductTypeId.value}`
    : '';
  const targetPath = `/production/bom-unified?tab=materials${productParam}`;
  const returnPath = `/production/bom-unified?tab=recipe${productParam}`;
  goCreate(targetPath, { reopen: returnPath });
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

    <!-- U7: No BOM → EmptyState (fool-proof Rule 5: dead-end → navigation) -->
    <el-empty
      v-else-if="loadState === 'NO_BOM'"
      description="该产品尚未建立 BOM 配方，调料配方挂在 BOM 下，请先创建 BOM"
      style="margin-top: 40px;"
    >
      <template #extra>
        <template v-if="canCreateBom">
          <el-button type="primary" @click="goCreateBom">
            前往创建 BOM 配方
          </el-button>
          <p style="margin-top: 8px; font-size: 12px; color: #909399;">
            创建完成后，点击顶部返回栏回到此页继续填写调料配方
          </p>
        </template>
        <template v-else>
          <el-alert
            :closable="false"
            type="warning"
            show-icon
            title="需要生产写入权限才能创建 BOM，请联系管理员开通"
          />
        </template>
      </template>
    </el-empty>

    <!-- Error state -->
    <el-empty
      v-else-if="loadState === 'error'"
      description="加载调料配方失败，请刷新重试"
      style="margin-top: 40px;"
    >
      <template #extra>
        <el-button @click="loadSeasoning">重试</el-button>
      </template>
    </el-empty>

    <!-- Idle: no product selected -->
    <el-empty
      v-else-if="loadState === 'idle'"
      description="请先选择一个产品"
      style="margin-top: 40px;"
    />

    <!-- Main editor (loaded) -->
    <template v-else-if="loadState === 'loaded' && current">
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

      <!-- Pot parameters -->
      <el-card shadow="never" style="margin-bottom: 16px;">
        <template #header>
          <span style="font-size: 14px; font-weight: 600;">锅序参数</span>
        </template>
        <el-form label-width="140px" size="default">
          <el-form-item label="注射率">
            <el-input-number
              v-model="formInjectionRate"
              :precision="4"
              :step="0.01"
              :disabled="isReadOnly"
              placeholder="如 0.0500"
            />
            <el-tooltip content="每 kg 原料注射量比例 (如 0.05 = 5%)" placement="top">
              <el-icon style="margin-left: 6px; color: #909399;"><InfoFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="每锅基准原料 kg">
            <el-input-number
              v-model="formCookingPotBaseKg"
              :precision="3"
              :min="0"
              :disabled="isReadOnly"
              placeholder="如 100"
            />
          </el-form-item>
          <el-form-item label="第二锅起比例">
            <el-input-number
              v-model="formSubsequentPotRatio"
              :precision="4"
              :min="0.0001"
              :max="1"
              :disabled="isReadOnly"
              placeholder="如 0.3333"
            />
            <el-tooltip content="第二锅及之后相比第一锅的调料用量比例 (老汤锅)" placement="top">
              <el-icon style="margin-left: 6px; color: #909399;"><InfoFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 注射配方 (无「计入调料」开关: 成本引擎对注射段恒计入,
           RecipeCostCalculator.perKg(INJECTION, applyCountInSeasoning=false), 故不暴露该字段) -->
      <el-card shadow="never" style="margin-bottom: 16px;">
        <template #header>
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <span style="font-size: 14px; font-weight: 600;">注射配方（INJECTION）</span>
            <el-button
              v-if="!isReadOnly"
              size="small"
              :icon="Plus"
              @click="addIngredient('INJECTION')"
            >添加注射料</el-button>
          </div>
        </template>
        <el-table :data="injectionRows" size="small" border>
          <el-table-column label="料名" min-width="120">
            <template #default="{ row }">
              <el-input v-if="!isReadOnly" v-model="row.name" size="small" />
              <span v-else>{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="每 kg 原料用量 g" width="150" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.dosagePerKgG"
                size="small"
                :precision="4"
                :min="0"
                style="width: 120px;"
              />
              <span v-else>{{ row.dosagePerKgG ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价1" width="120" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.priceSource1"
                size="small"
                :precision="4"
                :min="0"
                style="width: 100px;"
              />
              <span v-else>{{ row.priceSource1 ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价2" width="120" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.priceSource2"
                size="small"
                :precision="4"
                :min="0"
                style="width: 100px;"
              />
              <span v-else>{{ row.priceSource2 ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">
              <el-input v-if="!isReadOnly" v-model="row.remark" size="small" />
              <span v-else>{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="!isReadOnly" label="操作" width="70" align="center">
            <template #default="{ row }">
              <el-button link type="danger" :icon="Delete" @click="removeIngredient(row)" />
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="injectionRows.length === 0" description="暂无注射料" :image-size="60" />
      </el-card>

      <!-- 熟制配方 -->
      <el-card shadow="never" style="margin-bottom: 16px;">
        <template #header>
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <span style="font-size: 14px; font-weight: 600;">
              熟制配方（COOKING）
              <el-tooltip content="老汤取消「计入调料」开关，不计入调料成本" placement="top">
                <el-icon style="color: #909399; margin-left: 4px;"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
            <el-button
              v-if="!isReadOnly"
              size="small"
              :icon="Plus"
              @click="addIngredient('COOKING')"
            >添加熟制料</el-button>
          </div>
        </template>
        <el-table :data="cookingRows" size="small" border>
          <el-table-column label="料名" min-width="120">
            <template #default="{ row }">
              <el-input v-if="!isReadOnly" v-model="row.name" size="small" />
              <span v-else>{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="每 kg 原料用量 g" width="150" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.dosagePerKgG"
                size="small"
                :precision="4"
                :min="0"
                style="width: 120px;"
              />
              <span v-else>{{ row.dosagePerKgG ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价1" width="120" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.priceSource1"
                size="small"
                :precision="4"
                :min="0"
                style="width: 100px;"
              />
              <span v-else>{{ row.priceSource1 ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价2" width="120" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="!isReadOnly"
                v-model="row.priceSource2"
                size="small"
                :precision="4"
                :min="0"
                style="width: 100px;"
              />
              <span v-else>{{ row.priceSource2 ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="计入调料" width="90" align="center">
            <template #default="{ row }">
              <el-switch
                v-if="!isReadOnly"
                v-model="row.countInSeasoning"
                size="small"
              />
              <el-tag
                v-else
                :type="row.countInSeasoning ? 'success' : 'info'"
                size="small"
                disable-transitions
              >{{ row.countInSeasoning ? '是' : '否' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">
              <el-input v-if="!isReadOnly" v-model="row.remark" size="small" />
              <span v-else>{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="!isReadOnly" label="操作" width="70" align="center">
            <template #default="{ row }">
              <el-button link type="danger" :icon="Delete" @click="removeIngredient(row)" />
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="cookingRows.length === 0" description="暂无熟制料" :image-size="60" />
      </el-card>

      <!-- Footer actions -->
      <div style="text-align: right; margin-top: 8px;">
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
</style>
