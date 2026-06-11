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
 * 2. 类别下拉 (主材/辅材/调味料/包材) 走 system_enums.MATERIAL_CATEGORY 字典
 * 3. 单位下拉 + 智能默认 (suggest-unit 按相似名称+类别取最近原料的 unit)
 * 4. 去掉单价 (按采购价浮动, 在采购订单里录)
 * 5. 包装层级: 一级 (kg, 必填=unit) + 二/三级 (10kg/箱, 12箱/柜)
 *
 * T159-A-form (2026-06-08): 防呆复刻 SKU 表单模式
 *   1. 编码预览 (create mode): GET .../raw-material-types/preview-code?category=
 *      → 实时展示预期编码 (如 YL006), category 变化时重新 fetch
 *   2. 全字段智能匹配 cascade: GET .../raw-material-types/suggest?name=&category=
 *      → 填 unit/category/storageType/shelfLifeDays/level1PerLevel2/level2Unit
 *      + *ManuallyEdited flags + cascadeWriting guard (exact SKU pattern)
 *      + clearCascadeFields when name cleared
 *      null 字段 → 不覆盖; 端点 404/error → graceful degrade to suggest-unit fallback
 *   3. 包装层级内联换算行 (SKU-style):
 *      「1 [二级单位] = [换算数] [一级单位]」+ live preview tag
 *      一级单位 = 只读 echo 主单位 (single source of truth)
 *   4. 单位下拉: filterable + allow-create (复用 /system-config/units)
 *   foldable #1: 批次列表单位列 → 修 materials/list.vue 显示 quantityUnit 而非 unit
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete as DeleteIcon, Search, Refresh, Lock, View } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
// T2-5b (issue #534): expose movingAvgPrice — gate by canViewPrice RBAC
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');
// P11: 物料大类筛选 (原料/辅料/包材). 空串 = 全部.
const filterKind = ref('');

// 字典选项 (从后端 system_enums + unit_of_measurements 拉, Canvas 字典管理可改)
interface DictItem { enumCode: string; enumLabel: string; sortOrder: number }
interface UnitItem { unitCode: string; unitName: string; unitSymbol?: string; sortOrder: number }
const categoryOptions = ref<DictItem[]>([]);
const storageTypeOptions = ref<DictItem[]>([]);
const unitOptions = ref<UnitItem[]>([]);

onMounted(async () => {
  await loadDictionaries();
  await loadData();
});

async function loadDictionaries() {
  if (!factoryId.value) return;
  try {
    const [catRes, storageRes, unitRes] = await Promise.all([
      get<DictItem[]>(`/${factoryId.value}/system-config/enums/MATERIAL_CATEGORY`),
      get<DictItem[]>(`/${factoryId.value}/system-config/enums/MATERIAL_STORAGE_TYPE`),
      get<UnitItem[]>(`/${factoryId.value}/system-config/units`),
    ]);
    categoryOptions.value = (catRes.data || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
    storageTypeOptions.value = (storageRes.data || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
    unitOptions.value = (unitRes.data || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
  } catch (e) {
    console.warn('字典加载失败, 用空选项', e);
  }
}

// 编辑老数据时, 若历史值不在字典中, 临时合入下拉避免值丢失显示
function mergeHistoricCategory(current?: string): { value: string; label: string }[] {
  const opts = categoryOptions.value.map((c) => ({ value: c.enumLabel, label: c.enumLabel }));
  if (current && current.trim() !== '' && !opts.find((o) => o.value === current)) {
    return [{ value: current, label: `${current} (历史)` }, ...opts];
  }
  return opts;
}
function mergeHistoricStorage(current?: string): { value: string; label: string }[] {
  const opts = storageTypeOptions.value.map((c) => ({ value: c.enumLabel, label: c.enumLabel }));
  if (current && current.trim() !== '' && !opts.find((o) => o.value === current)) {
    return [{ value: current, label: `${current} (历史)` }, ...opts];
  }
  return opts;
}

// T159-A: 单位选项统一用 /system-config/units + allow-create (复用 SKU unitSelectOptions 模式)
const unitSelectOptions = computed(() => {
  return unitOptions.value.map((u) => {
    const sym = u.unitSymbol || u.unitCode;
    return { value: sym, label: `${u.unitName} (${sym})` };
  });
});

function mergeHistoricUnit(current?: string): { value: string; label: string }[] {
  const opts = unitSelectOptions.value;
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
          keyword: searchKeyword.value || undefined,
          // P11: 可选大类过滤 (原料/辅料/包材)
          materialKind: filterKind.value || undefined,
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
  taxRate: '' as string,
  taxIncludedUnitPrice: null as number | null,
});
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
  if (price == null || !rate) return null;
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
  children?: SegmentNode[];
}
const segmentTree = ref<SegmentNode[]>([]);
const segmentL1 = ref(''); // L1 类型
const segmentL2 = ref(''); // L2 部位
const segmentL3 = ref(''); // L3 品类
const segmentLoading = ref(false);
const segmentCodePreview = ref(''); // SP8 生成的编码预览
const sp8PreviewLoading = ref(false);

async function loadSegmentTree() {
  if (!factoryId.value) return;
  segmentLoading.value = true;
  try {
    const res = await get<SegmentNode[]>(`/${factoryId.value}/material-segments/tree`);
    if (res.success && Array.isArray(res.data)) {
      segmentTree.value = res.data;
    } else {
      segmentTree.value = [];
    }
  } catch {
    segmentTree.value = [];
  } finally {
    segmentLoading.value = false;
  }
}

// L1 options = top-level nodes
const segmentL1Options = computed(() =>
  segmentTree.value.filter((n) => n.level === 1),
);
// L2 options = children of selected L1
const segmentL2Options = computed(() => {
  if (!segmentL1.value) return [];
  const l1Node = segmentTree.value.find((n) => n.segmentCode === segmentL1.value);
  return l1Node?.children?.filter((c) => c.level === 2) ?? [];
});
// L3 options = children of selected L2
const segmentL3Options = computed(() => {
  if (!segmentL2.value) return [];
  for (const l1 of segmentTree.value) {
    const l2Node = l1.children?.find((c) => c.segmentCode === segmentL2.value);
    if (l2Node) return l2Node.children?.filter((c) => c.level === 3) ?? [];
  }
  return [];
});

// When L1 changes, reset L2/L3
watch(segmentL1, () => {
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
});
watch(segmentL2, () => {
  segmentL3.value = '';
  segmentCodePreview.value = '';
});

async function generateSP8Code() {
  if (!segmentL1.value || !segmentL2.value || !segmentL3.value) {
    ElMessage.warning('请先选择 L1类型、L2部位、L3品类后再生成编码');
    return;
  }
  if (!factoryId.value) return;
  sp8PreviewLoading.value = true;
  try {
    const res = await get<{ code: string }>(
      `/${factoryId.value}/material-segments/generate-code`,
      { params: { l1: segmentL1.value, l2: segmentL2.value, l3: segmentL3.value } },
    );
    if (res.success && res.data?.code) {
      segmentCodePreview.value = res.data.code;
    } else {
      // fallback: show a placeholder hint
      segmentCodePreview.value = `${segmentL1.value}${segmentL2.value}${segmentL3.value}...`;
    }
  } catch {
    // API not yet available — graceful degrade
    segmentCodePreview.value = `${segmentL1.value}-${segmentL2.value}-${segmentL3.value}`;
  } finally {
    sp8PreviewLoading.value = false;
  }
}

// ==================== T159-A: Code Preview ====================
// Create mode only: when category changes → fetch preview code.
// Degrades gracefully if endpoint not yet deployed (404/error → silent).
const codePreview = ref('');
const codePreviewLoading = ref(false);

async function fetchCodePreview(category: string) {
  if (!factoryId.value || !category.trim()) { codePreview.value = ''; return; }
  codePreviewLoading.value = true;
  try {
    const res = await get<{ code: string }>(
      `/${factoryId.value}/raw-material-types/preview-code`,
      { params: { category } },
    );
    if (res.success && res.data?.code) {
      codePreview.value = res.data.code;
    } else {
      codePreview.value = '';
    }
  } catch {
    // endpoint not yet deployed — degrade gracefully, don't break the form
    codePreview.value = '';
  } finally {
    codePreviewLoading.value = false;
  }
}

// Watch category in create mode for code preview
watch(
  () => [form.value.category, dialogVisible.value, editingId.value] as const,
  ([cat, visible, eid]) => {
    if (!visible || eid) { codePreview.value = ''; return; }
    fetchCodePreview(String(cat || ''));
  },
);

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

// Watch each field — if cascadeWriting, skip flagging as manual (cascade write)
watch(() => form.value.unit, () => {
  if (!cascadeWriting.value) unitManuallyEdited.value = true;
});
watch(() => form.value.category, () => {
  if (!cascadeWriting.value) categoryManuallyEdited.value = true;
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
watch(
  () => [form.value.name, form.value.category, dialogVisible.value, editingId.value] as const,
  ([name, _cat, visible, eid]) => {
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
          category?: string | null;
          storageType?: string | null;
          shelfLifeDays?: number | null;
          level1PerLevel2?: number | null;
          level2Unit?: string | null;
        }>(`/${factoryId.value}/raw-material-types/suggest`, { params });

        if (!res.success) {
          // Fallback: old suggest-unit endpoint (original behavior)
          const unitRes = await get<string>(
            `/${factoryId.value}/raw-material-types/suggest-unit`,
            { params },
          );
          if (unitRes.success && unitRes.data && !unitManuallyEdited.value) {
            cascadeWriting.value = true;
            try { form.value.unit = unitRes.data; } finally { cascadeWriting.value = false; }
          }
          return;
        }

        const d = res.data;
        if (!d) return;

        // Apply cascade — only fields user hasn't manually edited, skip null values
        cascadeWriting.value = true;
        try {
          if (d.unit != null && !unitManuallyEdited.value) form.value.unit = d.unit;
          if (d.category != null && !categoryManuallyEdited.value) form.value.category = d.category;
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
    taxRate: '',
    taxIncludedUnitPrice: null,
  };
  resetPackaging();
  resetManuallyEditedFlags();
  codePreview.value = '';
  // SP8: reset cascade
  segmentL1.value = '';
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
  if (segmentTree.value.length === 0) loadSegmentTree();
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
    taxRate: String(row.taxRate || ''),
    taxIncludedUnitPrice: (row.taxIncludedUnitPrice as number | null) ?? null,
  };
  resetPackaging();
  resetManuallyEditedFlags();
  codePreview.value = '';
  // SP8: reset cascade (edit mode — code already exists, cascade is create-only)
  segmentL1.value = '';
  segmentL2.value = '';
  segmentL3.value = '';
  segmentCodePreview.value = '';
  if (segmentTree.value.length === 0) loadSegmentTree();
  // 加载现有包装层级
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
  } catch { /* 无配置时正常空 */ }
  dialogVisible.value = true;
}

// ==================== T159-A: Packaging inline live preview (SKU-style) ====================
// 「1 [二级单位] = [换算数] [一级单位]」
const packagingL2Preview = computed(() => {
  const qty = Number(packaging.value.level1PerLevel2);
  const l1 = form.value.unit || '主单位';
  const l2 = packaging.value.level2Unit;
  if (!l2 || !qty || qty <= 0) return '';
  return `1 ${l2} = ${qty} ${l1}`;
});

const packagingL3Preview = computed(() => {
  const qty = Number(packaging.value.level2PerLevel3);
  const l2 = packaging.value.level2Unit || '二级单位';
  const l3 = packaging.value.level3Unit;
  if (!l3 || !qty || qty <= 0) return '';
  return `1 ${l3} = ${qty} ${l2}`;
});

const submitting = ref(false);
async function handleSave() {
  if (!form.value.name) return ElMessage.warning('请填写原料名称');
  if (!form.value.category) return ElMessage.warning('请选择类别');
  if (!form.value.unit) return ElMessage.warning('请选择单位');
  if (!form.value.storageType) return ElMessage.warning('请选择储存类型');

  // 包装层级前端校验 (后端 service + DB CHECK 双重兜底)
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
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/raw-material-types/${editingId.value}`, form.value);
      if (!res.success) throw new Error(res.message || '更新失败');
      materialId = editingId.value;
      ElMessage.success('更新成功');
    } else {
      // 创建: 不传 code 让后端自动生成
      const { code, ...payload } = form.value;
      const res = await post<{ id: string }>(`/${factoryId.value}/raw-material-types`, payload);
      if (!res.success) throw new Error(res.message || '创建失败');
      materialId = res.data?.id || '';
      ElMessage.success('创建成功');
    }

    // 包装层级 upsert / delete
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
    if (e instanceof Error) ElMessage.error(e.message);
  } finally {
    submitting.value = false;
  }
}

// ==================== Issue #779: 反查供应商 ====================
// 客户要求 (May 7 part2 L222-240): "原料的话那个加一个对应的供应商, 加个字段, 对应是哪家供应商供的吗"
// 复用 backend GET /suppliers/by-material?materialType={name} (SupplierController:179 已有).
const suppliersDialogVisible = ref(false);
const suppliersForMaterial = ref<Array<{ id: string; name: string; contactPerson?: string; phone?: string }>>([]);
const suppliersLoading = ref(false);
const suppliersDialogMaterialName = ref('');

async function openSuppliersForMaterial(row: TableRow) {
  const materialName = String(row.name || '').trim();
  if (!materialName) {
    ElMessage.warning('原料名称为空, 无法查询关联供应商');
    return;
  }
  suppliersDialogMaterialName.value = materialName;
  suppliersForMaterial.value = [];
  suppliersDialogVisible.value = true;
  suppliersLoading.value = true;
  try {
    const res = await get<Array<{ id: string; name: string; contactPerson?: string; phone?: string }>>(
      `/${factoryId.value}/suppliers/by-material`,
      { params: { materialType: materialName } },
    );
    if (res.success && Array.isArray(res.data)) {
      suppliersForMaterial.value = res.data;
    }
  } catch {
    /* interceptor */
  } finally {
    suppliersLoading.value = false;
  }
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

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  searchKeyword.value = '';
  filterKind.value = '';
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
        <!-- P11: 物料大类快速筛选 -->
        <el-select
          v-model="filterKind"
          placeholder="全部大类"
          clearable
          style="width: 130px"
          @change="handleSearch"
        >
          <el-option label="原料" value="原料" />
          <el-option label="辅料" value="辅料" />
          <el-option label="包材" value="包材" />
        </el-select>
        <el-input
          v-model="searchKeyword"
          placeholder="搜索原料名称 / 编码"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" stripe>
        <el-table-column prop="code" label="原料编码" width="160" />
        <el-table-column prop="name" label="原料名称" min-width="180" />
        <el-table-column prop="category" label="类别" width="120" />
        <el-table-column prop="unit" label="单位" width="80" />
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

        <!-- 编码: 创建时显示实时预览, 编辑时只读锁定 -->
        <el-form-item v-if="editingId" label="原料编码">
          <el-input v-model="form.code" disabled :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item v-else label="原料编码">
          <!-- T159-A: code preview — re-fetches whenever category changes -->
          <div class="code-preview-row">
            <el-tag
              v-if="codePreview"
              type="info"
              class="code-preview-tag"
              :class="{ 'code-preview-loading': codePreviewLoading }"
            >
              预计编码: {{ codePreview }}
            </el-tag>
            <span v-else-if="form.category" class="code-preview-hint">
              {{ codePreviewLoading ? '生成中...' : '编码将在保存后自动生成' }}
            </span>
            <span v-else class="code-preview-hint">
              选择类别后可预览自动编码（如 YL006）
            </span>
          </div>
        </el-form-item>

        <el-form-item label="原料名称" required>
          <el-input v-model="form.name" placeholder="如 冻猪蹄 / 三文鱼 / 吸塑盒2014-3.5" />
          <div class="field-hint">输入名称后自动匹配历史同类原料，智能填充单位 / 类别 / 保质期 / 包装换算</div>
        </el-form-item>

        <el-form-item label="类别" required>
          <el-select v-model="form.category" placeholder="请选择类别" style="width: 100%" filterable>
            <el-option
              v-for="opt in mergeHistoricCategory(form.category)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <!-- T159-A: 单位 — filterable + allow-create, 复用 /system-config/units -->
        <el-form-item label="单位" required>
          <el-select
            v-model="form.unit"
            placeholder="请选择或输入单位"
            style="width: 100%"
            filterable
            allow-create
            default-first-option
          >
            <el-option
              v-for="opt in mergeHistoricUnit(form.unit)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div v-if="unitManuallyEdited && !editingId" class="field-hint field-hint--manual">
            已手动设置，自动填充将不再覆盖此字段
          </div>
        </el-form-item>

        <el-form-item label="储存类型" required>
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
          <el-form-item label="税率">
            <el-select v-model="form.taxRate" placeholder="未配置" clearable style="width: 100%">
              <el-option label="未配置" value="" />
              <el-option label="9% (农产品等)" value="TAX_9" />
              <el-option label="13% (标准税率)" value="TAX_13" />
            </el-select>
          </el-form-item>
          <el-form-item label="含税单价 (元)">
            <el-input-number
              v-model="form.taxIncludedUnitPrice"
              :min="0"
              :precision="4"
              :controls="false"
              placeholder="含税单价（可选）"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item v-if="form.taxIncludedUnitPrice != null && form.taxRate" label="未税单价 (元)">
            <el-input
              :model-value="preTaxUnitPrice != null ? preTaxUnitPrice.toFixed(4) : '—'"
              disabled
              style="width: 100%"
            />
            <div class="field-hint">= 含税单价 ÷ (1 + 税率)，自动计算</div>
          </el-form-item>
        </template>

        <!-- SP8: 16位编码级联 (创建模式下显示, 编辑模式只读) -->
        <!-- SP8 兜底 (Tier0 #15 minimal): 字典未配置时隐藏级联入口防 dead-end (fool-proof Rule 5).
             generate-code 端点 P1 上线; 当前 tree 为空时显示诚实空态而非空下拉组合. -->
        <el-divider v-if="!editingId">
          <span class="divider-title">16位编码级联（可选）</span>
        </el-divider>
        <template v-if="!editingId">
          <!-- 字典已配置: 展示完整级联 -->
          <template v-if="segmentL1Options.length > 0 || segmentLoading">
            <el-form-item label="L1 类型">
              <el-select
                v-model="segmentL1"
                placeholder="请选择类型分类"
                clearable
                filterable
                style="width: 100%"
                :loading="segmentLoading"
              >
                <el-option
                  v-for="opt in segmentL1Options"
                  :key="opt.segmentCode"
                  :label="`${opt.segmentCode} — ${opt.segmentLabel}`"
                  :value="opt.segmentCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="L2 部位">
              <el-select
                v-model="segmentL2"
                placeholder="请先选择 L1 类型"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL1"
              >
                <el-option
                  v-for="opt in segmentL2Options"
                  :key="opt.segmentCode"
                  :label="`${opt.segmentCode} — ${opt.segmentLabel}`"
                  :value="opt.segmentCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="L3 品类">
              <el-select
                v-model="segmentL3"
                placeholder="请先选择 L2 部位"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL2"
              >
                <el-option
                  v-for="opt in segmentL3Options"
                  :key="opt.segmentCode"
                  :label="`${opt.segmentCode} — ${opt.segmentLabel}`"
                  :value="opt.segmentCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="segmentL1 && segmentL2 && segmentL3" label="编码预览">
              <div class="code-preview-row">
                <el-tag v-if="segmentCodePreview" type="success" class="code-preview-tag">
                  {{ segmentCodePreview }}
                </el-tag>
                <el-button
                  size="small"
                  :loading="sp8PreviewLoading"
                  @click="generateSP8Code"
                >
                  生成预览
                </el-button>
              </div>
              <div class="field-hint">点击「生成预览」查看将生成的16位编码</div>
            </el-form-item>
          </template>
          <!-- 字典未配置: 诚实空态 + 跳转配置引导 (fool-proof Rule 5: dead-end 改导航) -->
          <el-form-item v-else label="">
            <el-alert
              title="16位编码字典尚未配置，暂不可用"
              type="info"
              :closable="false"
              show-icon
              style="width: 100%"
            >
              <template #default>
                <div style="font-size:12px;margin-top:4px;color:#606266">
                  留空将使用系统自动编码。16位分段编码计划 P1 阶段上线。
                </div>
              </template>
            </el-alert>
          </el-form-item>
        </template>

        <!-- ==================== T159-A: 包装层级 内联换算行 (SKU-style) ==================== -->
        <el-divider>
          <span class="divider-title">包装层级（可选）</span>
        </el-divider>

        <!-- 一级: 显示主单位 (read-only echo — single source of truth = 上方单位字段) -->
        <el-form-item label="一级 (主单位)">
          <div class="packaging-inline-row">
            <el-tag type="info" class="unit-tag">{{ form.unit || '请先填单位' }}</el-tag>
            <span class="packaging-equals-hint">← 同「单位」字段（主数据 canonical 单位，不可单独更改）</span>
          </div>
        </el-form-item>

        <!-- 二级换算: SKU-style inline row -->
        <!-- 布局: 1 [二级单位 select] = [换算数] [一级单位 tag] -->
        <el-form-item label="二级换算">
          <div class="packaging-conversion-row">
            <span class="conversion-label">1</span>
            <el-select
              v-model="packaging.level2Unit"
              placeholder="二级单位 (箱)"
              style="width: 155px"
              filterable
              allow-create
              default-first-option
              clearable
            >
              <el-option
                v-for="opt in mergeHistoricUnit(packaging.level2Unit)"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <span class="conversion-equals">=</span>
            <el-input-number
              v-model="packaging.level1PerLevel2"
              :min="0"
              :controls="false"
              placeholder="换算数"
              style="width: 100px"
            />
            <el-tag type="info" class="unit-tag-echo">{{ form.unit || '主单位' }}</el-tag>
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
            <el-select
              v-model="packaging.level3Unit"
              placeholder="三级单位 (柜)"
              style="width: 155px"
              :disabled="!packaging.level2Unit"
              filterable
              allow-create
              default-first-option
              clearable
            >
              <el-option
                v-for="opt in mergeHistoricUnit(packaging.level3Unit)"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <span class="conversion-equals">=</span>
            <el-input-number
              v-model="packaging.level2PerLevel3"
              :min="0"
              :controls="false"
              placeholder="换算数"
              style="width: 100px"
              :disabled="!packaging.level2Unit"
            />
            <el-tag type="info" class="unit-tag-echo">{{ packaging.level2Unit || '二级单位' }}</el-tag>
          </div>
          <!-- Live preview summary -->
          <div v-if="packagingL3Preview" class="packaging-preview">
            <el-tag size="small" type="success">{{ packagingL3Preview }}</el-tag>
          </div>
          <div v-if="!packaging.level2Unit" class="field-hint">
            请先配置二级单位才能配置三级
          </div>
        </el-form-item>

      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- Issue #779: 反查供应商对话框 -->
    <el-dialog
      v-model="suppliersDialogVisible"
      :title="`${suppliersDialogMaterialName} — 关联供应商`"
      width="640px"
      destroy-on-close
    >
      <el-table
        v-loading="suppliersLoading"
        :data="suppliersForMaterial"
        empty-text="该原料暂无关联供应商"
        stripe
        size="small"
      >
        <el-table-column prop="name" label="供应商名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="120">
          <template #default="{ row }">{{ row.contactPerson || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" width="140">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="suppliersDialogVisible = false">关闭</el-button>
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
.search-bar { display: flex; gap: 8px; margin-bottom: 16px; }
.divider-title { font-size: 14px; color: #606266; font-weight: 500; }

/* T159-A: Code preview row */
.code-preview-row {
  display: flex;
  align-items: center;
  min-height: 32px;
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
