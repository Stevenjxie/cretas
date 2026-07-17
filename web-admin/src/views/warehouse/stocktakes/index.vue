<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import request, { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, View, Edit, Check, Close, Upload, Download, Delete } from '@element-plus/icons-vue';
import * as XLSX from 'xlsx';
import type { TableRow } from '@/types/api';
import { warehouseTypeBadge } from '@/utils/warehouse';
import { BIG_CATEGORY_OPTIONS, filterOptionsByBigCategory, type BigCategory } from '@/utils/materialCategory';
import { buildStocktakeItemUpdates } from './stocktakeItemUpdates';

// Safe helpers — Vue template compiler does not support optional chaining (?.)
// in attribute bindings; delegate to these plain functions instead.
function wBadgeColor(type: unknown): string | undefined {
  return warehouseTypeBadge(String(type || ''))?.color;
}
function wBadgeLabel(type: unknown): string | undefined {
  return warehouseTypeBadge(String(type || ''))?.label;
}

// ────────────────────────────────────────────────────────────────────────────
// Auth / permissions
// ────────────────────────────────────────────────────────────────────────────
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

// ────────────────────────────────────────────────────────────────────────────
// List state
// ────────────────────────────────────────────────────────────────────────────
const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const statusFilter = ref('');

// Status display mapping
const statusMap: Record<string, { label: string; type: string }> = {
  INITIATED: { label: '已发起', type: 'info' },
  COUNTING: { label: '盘点中', type: 'warning' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已审批', type: 'success' },
  APPLIED: { label: '已应用', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
};

// ────────────────────────────────────────────────────────────────────────────
// Warehouses for dropdown
// ────────────────────────────────────────────────────────────────────────────
const warehouses = ref<TableRow[]>([]);

async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/factory/warehouses`);
    if (res.success && Array.isArray(res.data)) warehouses.value = res.data;
  } catch { /* silent */ }
}

function warehouseName(id: string) {
  const w = warehouses.value.find((x) => String(x.id) === String(id));
  return w ? String(w.name || id) : id;
}

// ────────────────────────────────────────────────────────────────────────────
// Load list
// ────────────────────────────────────────────────────────────────────────────
async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: Math.max(0, pagination.value.page - 1), // el-pagination is 1-based; backend expects 0-based
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/stocktakes`, { params });
    if (res.success && res.data) {
      const d = res.data;
      tableData.value = d.content || d || [];
      pagination.value.total = d.totalElements ?? tableData.value.length;
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Monthly prompt: check if this month already has a task
// ────────────────────────────────────────────────────────────────────────────
const currentMonth = computed(() => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
});

const hasThisMonthTask = computed(() => {
  return tableData.value.some((row) => String(row.periodMonth || '').startsWith(currentMonth.value));
});

// ────────────────────────────────────────────────────────────────────────────
// Initiate dialog
// ────────────────────────────────────────────────────────────────────────────
const initiateDialogVisible = ref(false);
const initiateLoading = ref(false);
const initiateForm = reactive({
  warehouseId: '',
  periodMonth: currentMonth.value,
  notes: '',
  // 临时/专项盘点 (fool-proof Rule 5: 月底约束不再是 dead-end — 月底前也有出口)
  adHoc: false,
  adHocReasonSelect: '',
  adHocReasonOther: '',
});

// fool-proof Rule 3: 自由文本改约束下拉 (标准原因 + 其他才显文本框)
const adHocReasonOptions = [
  { value: 'THEFT_SUSPECTED', label: '疑似失窃' },
  { value: 'DAMAGE', label: '货物损坏' },
  { value: 'CUSTOMER_AUDIT', label: '客户抽查' },
  { value: 'SPECIAL_REVIEW', label: '专项复核' },
  { value: 'OTHER', label: '其他' },
];

function resolveAdHocReason(): string {
  if (!initiateForm.adHoc) return '';
  if (initiateForm.adHocReasonSelect === 'OTHER') return initiateForm.adHocReasonOther.trim();
  return adHocReasonOptions.find((o) => o.value === initiateForm.adHocReasonSelect)?.label || '';
}

const adHocReasonValid = computed(() => initiateForm.adHoc && !!resolveAdHocReason());

// ────────────────────────────────────────────────────────────────────────────
// 月底约束展示态 (fool-proof Rule 1: 边界必须在填表前展示, 不能填完点提交才报错)
// ────────────────────────────────────────────────────────────────────────────
interface InitiateConstraint {
  monthEndThreshold: number;
  canInitiateToday: boolean;
  today: string;
  nextAllowedDate: string;
}
const initiateConstraint = ref<InitiateConstraint | null>(null);
const initiateConstraintLoading = ref(false);

async function loadInitiateConstraint() {
  if (!factoryId.value) return;
  initiateConstraintLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/stocktakes/initiate-constraint`);
    if (res.success && res.data) initiateConstraint.value = res.data;
  } catch { /* 展示态加载失败不阻塞发起 — 后端 initiate() 仍会真实校验并报错 */ }
  finally { initiateConstraintLoading.value = false; }
}

function openInitiateDialog() {
  initiateForm.warehouseId = '';
  initiateForm.periodMonth = currentMonth.value;
  initiateForm.notes = '';
  initiateForm.adHoc = false;
  initiateForm.adHocReasonSelect = '';
  initiateForm.adHocReasonOther = '';
  initiateDialogVisible.value = true;
  void loadInitiateConstraint();
}

async function submitInitiate() {
  if (!initiateForm.warehouseId) {
    ElMessage({ message: '请选择仓库', type: 'warning', duration: 3000 });
    return;
  }
  if (!initiateForm.periodMonth) {
    ElMessage({ message: '请选择盘点月份', type: 'warning', duration: 3000 });
    return;
  }
  // 防呆 Rule 1 + Rule 5: 月底约束已在弹窗打开时展示, 此处兜底拦截 (防用户在约束加载完成前抢点提交)。
  // Rule 5 (无 dead-end): 常规盘点被约束挡住时, 提供【临时/专项盘点】出口 — 勾选 + 填写标准原因即可绕过。
  if (initiateConstraint.value && !initiateConstraint.value.canInitiateToday && !adHocReasonValid.value) {
    ElMessage({
      message: initiateForm.adHoc
        ? '请选择临时盘点原因（或填写补充说明）后再发起'
        : `本月盘点只能在每月${initiateConstraint.value.monthEndThreshold}日后发起，下次可发起日期: ${initiateConstraint.value.nextAllowedDate}；如需临时/专项清点（疑似失窃/货物损坏等），勾选下方【临时盘点】并填写原因。`,
      type: 'warning', duration: 0, showClose: true,
    });
    return;
  }
  initiateLoading.value = true;
  try {
    const adHocReason = resolveAdHocReason();
    const res = await post(`/${factoryId.value}/stocktakes`, {
      warehouseId: initiateForm.warehouseId,
      periodMonth: initiateForm.periodMonth,
      notes: initiateForm.notes,
      adHoc: initiateForm.adHoc,
      adHocReason,
    });
    if (res.success) {
      ElMessage({ message: '盘点任务已发起', type: 'success', duration: 3000 });
      initiateDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '发起失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '发起失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    initiateLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Bulk import dialog (导出模板 → 填实盘 → 导入比对 → 确认校正)
// ────────────────────────────────────────────────────────────────────────────
interface BulkMatchedLine {
  materialBatchId: string | null; batchNumber: string; materialName: string; unit: string;
  systemQty: number | null; actualQty: number | null; differenceQty: number | null; differenceType: string | null;
  materialTypeId?: string | null; unitPrice?: number | null; willCreate?: boolean;
}
interface BulkRowError { rowNumber: number; batchNumber: string; materialName: string; reason: string; }

// 盘点差异预览 — 镜像后端 StocktakeDiffPreviewDTO (backend/.../dto/factory/StocktakeDiffPreviewDTO.java)
interface DiffPreviewLine {
  itemId: string;
  materialBatchId: string | null;
  batchNumber: string | null;
  materialName: string | null;
  systemQty: number | null;
  actualQty: number | null;
  differenceQty: number | null;
  differenceType: string | null;
}
interface DiffPreviewResponse {
  stocktakeId?: string;
  stocktakeNo?: string;
  periodMonth?: string;
  diffLines: DiffPreviewLine[];
  surplusCount: number;
  shortageCount: number;
  matchCount: number;
}
interface BulkPreview {
  stocktakeId?: string | null; stocktakeNo?: string | null;
  warehouseId: string; warehouseName: string; periodMonth?: string;
  totalRows: number; matchedCount: number; surplusCount: number; shortageCount: number;
  matchCount: number; skippedCount: number; willCreateCount?: number; errorCount: number;
  matchedLines: BulkMatchedLine[]; errors: BulkRowError[];
}

const bulkDialogVisible = ref(false);
const bulkLoading = ref(false);
const bulkFile = ref<File | null>(null);
const bulkPreview = ref<BulkPreview | null>(null);
const bulkForm = reactive({
  warehouseId: '',
  periodMonth: currentMonth.value,
  openingMode: false,
});

function openBulkDialog() {
  bulkForm.warehouseId = '';
  bulkForm.periodMonth = currentMonth.value;
  bulkForm.openingMode = false;
  bulkFile.value = null;
  bulkPreview.value = null;
  openingEntryMode.value = 'ROWS';
  resetOpeningRows();
  bulkDialogVisible.value = true;
}

// 期初建账勾选框可以在两个入口摸到（顶层「期初建账/期初入库」按钮 或 「批量导入盘点」弹窗里勾选），
// 两处都要能用「逐条录入」— 用户勾上时才去按需加载物料下拉数据（避免每次开弹窗都请求一次）。
function onOpeningModeChange(checked: boolean) {
  bulkPreview.value = null;
  if (checked && materialOptions.value.length === 0) {
    void loadMaterialOptions();
  }
}

// fool-proof Rule 5 (无 dead-end): 期初建账此前只能靠"批量导入盘点"弹窗里一个不起眼的
// 勾选框才能摸到 (bulkForm.openingMode) — 新仓管员根本发现不了入口。加一个独立的、
// 有清楚文字的顶层按钮, 直接复用现成的批量导入 dialog + 预置勾选, 不重新实现导入流程。
const bulkDialogTitle = computed(() =>
  bulkForm.openingMode
    ? '期初建账 / 期初入库（新增期初物料 + 校正已有批次）'
    : '批量导入盘点（导出模板 → 填实盘 → 导入比对 → 确认校正）'
);

function openOpeningEntry() {
  openBulkDialog();
  bulkForm.openingMode = true;
  openingEntryMode.value = 'ROWS';
  resetOpeningRows();
  if (materialOptions.value.length === 0) void loadMaterialOptions();
}

// ────────────────────────────────────────────────────────────────────────────
// 期初建账 — 逐条录入 (IN-APP row-by-row entry, 不需要 Excel)
//
// 客户反馈: 仓管/工厂主文化素质参差不齐, Excel 导出→填→再导入 对他们太难。加一个"添加一行"式
// 的表格录入 —— 选物料 + 填数量(+单价可选), 逐条加。提交时不新建后端逻辑, 而是在浏览器内生成
// 一份跟"导出模板"列结构完全一致的虚拟 Excel (复用 xlsx 库, 参考 production/bom/index.vue 的
// 用法), 再喂给已经跑通的 doBulkPreview / doBulkConfirm（跟用户手填 Excel 上传走的是同一段
// 后端代码：建壳 create-if-missing → initiate 快照 → apply 盘盈 → 借1403/贷4001 凭证）。
// 这样零后端改动、零新流程分叉 —— 只是把"怎么产生这份 Excel"从"用户自己在 Excel 里操作"
// 换成"页面表格帮用户生成"。
// ────────────────────────────────────────────────────────────────────────────
type OpeningEntryMode = 'ROWS' | 'EXCEL';
const openingEntryMode = ref<OpeningEntryMode>('ROWS');

interface OpeningMaterialOption {
  id: string;
  name: string;
  code: string;
  unit: string;
  category?: string;
}
const materialOptions = ref<OpeningMaterialOption[]>([]);
const materialOptionsLoading = ref(false);

async function loadMaterialOptions() {
  if (!factoryId.value) return;
  materialOptionsLoading.value = true;
  try {
    const res = await get<OpeningMaterialOption[] | { content: OpeningMaterialOption[] }>(
      `/${factoryId.value}/raw-material-types/active`
    );
    if (res.success && res.data) {
      const data = res.data;
      materialOptions.value = Array.isArray(data) ? data : (data.content || []);
    }
  } catch {
    // 静默失败: 逐条录入下拉物料为空时, 用户仍可切到「Excel 批量导入」tab
  } finally {
    materialOptionsLoading.value = false;
  }
}

interface OpeningRow {
  materialTypeId: string;
  materialName: string;
  quantity: number | null;
  unitPrice: number | null;
  unit: string;
  _bigCategory: BigCategory | '';
}

function newOpeningRow(): OpeningRow {
  return { materialTypeId: '', materialName: '', quantity: null, unitPrice: null, unit: 'kg', _bigCategory: '原料' };
}

const openingRows = ref<OpeningRow[]>([newOpeningRow()]);

function resetOpeningRows() {
  openingRows.value = [newOpeningRow()];
}

function addOpeningRow() {
  openingRows.value.push(newOpeningRow());
}

function removeOpeningRow(idx: number) {
  openingRows.value.splice(idx, 1);
  if (openingRows.value.length === 0) addOpeningRow(); // 防呆: 表格至少留一行, 不留空白死状态
}

function materialsForOpeningRow(row: OpeningRow): OpeningMaterialOption[] {
  return filterOptionsByBigCategory(materialOptions.value, row._bigCategory);
}

function handleOpeningMaterialChange(idx: number, materialId: string) {
  const row = openingRows.value[idx];
  const m = materialOptions.value.find((o) => o.id === materialId);
  if (row && m) {
    row.materialName = m.name;
    if (m.unit) row.unit = m.unit;
  }
}

// fool-proof Rule 1: 提交前先在页面上校验清楚, 不要点了「提交」才报"服务器错误"
function validateOpeningRows(): string | null {
  if (!bulkForm.warehouseId) return '请先选择仓库';
  if (!bulkForm.periodMonth) return '请填写月份';
  const filled = openingRows.value.filter((r) => r.materialTypeId && r.quantity != null && Number(r.quantity) > 0);
  if (filled.length === 0) return '请至少填写一行：选择物料 + 填写数量（大于 0）';
  return null;
}

// 把逐条录入的行, 在浏览器里打包成一份跟「下载模板」列结构完全一致的虚拟 Excel 文件,
// 复用既有的 doBulkPreview / doBulkConfirm（同一段后端代码, 见上方大注释）。
// 列顺序必须跟 StocktakeImportRowDTO 的 @ExcelProperty index 完全对应（后端按位置读, 不按表头文字）：
// 0 物料名称 / 1 批次号*(留空=新物料) / 2 仓库 / 3 账面数量(忽略) / 4 单位 / 5 实盘数量 / 6 单价(可空)
function buildOpeningRowsFile(): File | null {
  const err = validateOpeningRows();
  if (err) {
    ElMessage({ message: err, type: 'warning', duration: 3000 });
    return null;
  }
  const wname = warehouseName(bulkForm.warehouseId);
  const header = ['物料名称', '批次号*', '仓库', '账面数量', '单位', '实盘数量', '单价(期初建账新建填写,可空)'];
  const dataRows = openingRows.value
    .filter((r) => r.materialTypeId && r.quantity != null && Number(r.quantity) > 0)
    .map((r) => [
      r.materialName,
      '', // 批次号留空 → 后端按"新物料"从 0 盘盈建账
      wname,
      '',
      r.unit || 'kg',
      Number(r.quantity),
      r.unitPrice != null ? Number(r.unitPrice) : '',
    ]);
  const ws = XLSX.utils.aoa_to_sheet([header, ...dataRows]);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, '库存盘点');
  const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
  return new File([wbout], `期初建账-逐条录入-${bulkForm.periodMonth}.xlsx`, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
}

async function doOpeningRowsPreview() {
  const file = buildOpeningRowsFile();
  if (!file) return;
  bulkFile.value = file;
  await doBulkPreview();
}

async function doOpeningRowsConfirm() {
  const file = buildOpeningRowsFile();
  if (!file) return;
  bulkFile.value = file;
  await doBulkConfirm();
}

// 弹窗底部「预览比对」/「确认导入」按钮统一入口 — 期初建账+逐条录入 时走行数据打包,
// 其余情况(常规批量导入 / 期初建账+Excel tab) 走既有的 Excel 上传路径。
const isOpeningRowsMode = computed(() => bulkForm.openingMode && openingEntryMode.value === 'ROWS');

async function runBulkPreview() {
  if (isOpeningRowsMode.value) await doOpeningRowsPreview();
  else await doBulkPreview();
}

async function runBulkConfirm() {
  if (isOpeningRowsMode.value) await doOpeningRowsConfirm();
  else await doBulkConfirm();
}

// 下载当前库存填报模板（blob）
async function downloadBulkTemplate() {
  if (!bulkForm.warehouseId) {
    ElMessage({ message: '请先选择仓库', type: 'warning', duration: 3000 });
    return;
  }
  try {
    // _keepResponse: true 必须 — 否则响应拦截器把 blob 解成 {success,data,message} 信封,
    // new Blob([信封对象]) 会 String() 成 "[object Object]" → 下载出损坏文件 (2026-07-03 修).
    const resp = await request.get<Blob>(`/${factoryId.value}/stocktakes/bulk-import/template`, {
      params: { warehouseId: bulkForm.warehouseId },
      responseType: 'blob',
      _keepResponse: true,
    });
    const blob = new Blob([resp.data as unknown as BlobPart], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const wname = warehouseName(bulkForm.warehouseId);
    a.download = `库存盘点模板-${wname}-${bulkForm.periodMonth}.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    ElMessage({ message: `模板下载失败：${String((e as Error).message || '')}`, type: 'error', duration: 0, showClose: true });
  }
}

// el-upload manual: 记录选中文件，不自动上传
function onBulkFileChange(uploadFile: { raw?: File }) {
  bulkFile.value = uploadFile.raw || null;
  bulkPreview.value = null; // 换文件后清空旧预览
}

function buildBulkFormData(): FormData | null {
  if (!bulkForm.warehouseId) {
    ElMessage({ message: '请选择仓库', type: 'warning', duration: 3000 });
    return null;
  }
  if (!bulkFile.value) {
    ElMessage({ message: '请选择填好的 Excel 文件', type: 'warning', duration: 3000 });
    return null;
  }
  const fd = new FormData();
  fd.append('file', bulkFile.value);
  return fd;
}

// 预览：read-only 比对
async function doBulkPreview() {
  const fd = buildBulkFormData();
  if (!fd) return;
  bulkLoading.value = true;
  try {
    const previewParams = new URLSearchParams({
      warehouseId: bulkForm.warehouseId,
      openingMode: String(bulkForm.openingMode),
    });
    const res = await post(
      `/${factoryId.value}/stocktakes/bulk-import/preview?${previewParams.toString()}`,
      fd,
    );
    if (res.success) {
      bulkPreview.value = res.data as unknown as BulkPreview;
    } else {
      ElMessage({ message: res.message || '比对失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '比对失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    bulkLoading.value = false;
  }
}

// 确认：创建盘点 + 回填实盘
async function doBulkConfirm() {
  const fd = buildBulkFormData();
  if (!fd) return;
  if (!bulkForm.periodMonth) {
    ElMessage({ message: '请选择盘点月份', type: 'warning', duration: 3000 });
    return;
  }
  const okText = bulkForm.openingMode
    ? '确认将按【期初建账】创建盘点（生效后过账期初权益凭证，不计营业外收入）'
    : '确认创建盘点任务并回填实盘数量？';
  try {
    await ElMessageBox.confirm(okText, '确认导入', { type: 'warning' });
  } catch { return; }
  bulkLoading.value = true;
  try {
    const params = new URLSearchParams({
      warehouseId: bulkForm.warehouseId,
      periodMonth: bulkForm.periodMonth,
      openingMode: String(bulkForm.openingMode),
    });
    const res = await post(`/${factoryId.value}/stocktakes/bulk-import/confirm?${params.toString()}`, fd);
    if (res.success) {
      ElMessage({ message: res.message || '盘点任务已创建', type: 'success', duration: 5000, showClose: true });
      bulkDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '导入失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '导入失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    bulkLoading.value = false;
  }
}

function diffTypeTag(t: string | null): string {
  if (t === 'SURPLUS') return 'success';
  if (t === 'SHORTAGE') return 'danger';
  if (t === 'MATCH') return 'info';
  return '';
}
function diffTypeLabel(t: string | null): string {
  if (t === 'SURPLUS') return '盘盈';
  if (t === 'SHORTAGE') return '盘亏';
  if (t === 'MATCH') return '一致';
  return '未盘点';
}

// ────────────────────────────────────────────────────────────────────────────
// Detail / counting dialog
// ────────────────────────────────────────────────────────────────────────────
const detailDialogVisible = ref(false);
const detailData = ref<TableRow | null>(null);
const detailLoading = ref(false);
const diffPreview = ref<DiffPreviewLine[]>([]);
const diffLoading = ref(false);

async function openDetail(row: TableRow) {
  detailData.value = row;
  detailDialogVisible.value = true;
  diffPreview.value = [];
  // Load diff-preview if approved/applied
  if (row.status === 'APPROVED' || row.status === 'APPLIED') {
    await loadDiffPreview(String(row.id));
  }
}

async function loadDiffPreview(stocktakeId: string) {
  diffLoading.value = true;
  try {
    // ⚠️ Bug fix (2026-07): 后端 DTO 字段是 `diffLines`（StocktakeDiffPreviewDTO.diffLines）,
    // 之前误读 `res.data.items`（不存在的字段）→ 差异预览表格一直渲染"暂无数据"。
    const res = await get<DiffPreviewResponse>(`/${factoryId.value}/stocktakes/${stocktakeId}/diff-preview`);
    if (res.success && res.data) {
      diffPreview.value = Array.isArray(res.data.diffLines) ? res.data.diffLines : [];
    }
  } catch { /* silent */ } finally {
    diffLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Count entry dialog (录入实际数量)
// ────────────────────────────────────────────────────────────────────────────
const countDialogVisible = ref(false);
const countLoading = ref(false);
const countItems = ref<Array<{ id: string; batchId: string; batchNo: string; materialName: string; systemQty: number; actualQty: number | null; notes: string }>>([]);
const countStocktakeId = ref('');
const countStocktakeNo = ref('');

async function openCountDialog(row: TableRow) {
  countStocktakeId.value = String(row.id);
  countStocktakeNo.value = String(row.stocktakeNo || row.id);
  countLoading.value = true;
  countDialogVisible.value = true;
  try {
    const res = await get(`/${factoryId.value}/stocktakes/${row.id}`);
    if (res.success && res.data) {
      const items = res.data.items || [];
      countItems.value = items.map((item: TableRow) => ({
        id: String(item.id),
        batchId: String(item.materialBatchId || ''),
        batchNo: String(item.batchNumber || item.materialBatchId || ''),
        materialName: String(item.materialName || item.rawMaterialTypeId || ''),
        systemQty: Number(item.systemQty ?? 0),
        actualQty: item.actualQty != null ? Number(item.actualQty) : null,
        notes: String(item.notes || ''),
      }));
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    countLoading.value = false;
  }
}

async function saveCountItems() {
  countLoading.value = true;
  try {
    const updates = buildStocktakeItemUpdates(countItems.value);
    if (updates.length === 0) {
      ElMessage({ message: '请至少录入一条实盘数量', type: 'warning', duration: 3000 });
      return;
    }
    // Backend PUT /items accepts a raw DTO array. Uncounted rows must be omitted
    // because actualQty is required for every DTO that is submitted.
    const res = await put(`/${factoryId.value}/stocktakes/${countStocktakeId.value}/items`, updates);
    if (res.success) {
      ElMessage({ message: '盘点数量已保存（暂存，批准后生效）', type: 'success', duration: 3000 });
      countDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '保存失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '保存失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    countLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Submit for approval
// ────────────────────────────────────────────────────────────────────────────
const submitLoading = ref(false);

async function submitForApproval(row: TableRow) {
  await ElMessageBox.confirm(
    `确认提交盘点任务 ${row.stocktakeNo || row.id} 审批？\n仓库：${warehouseName(String(row.warehouseId))} · 月份：${row.periodMonth}`,
    '提交审批',
    { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'warning' }
  ).catch(() => { throw new Error('cancel'); });

  submitLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/stocktakes/${row.id}/submit`, {});
    if (res.success) {
      ElMessage({ message: '已提交审批', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    if (String((e as Error).message) !== 'cancel') {
      ElMessage({ message: String((e as Error).message || '提交失败'), type: 'error', duration: 0, showClose: true });
    }
  } finally {
    submitLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Approve dialog (财务审批)
// ────────────────────────────────────────────────────────────────────────────
const approveDialogVisible = ref(false);
const approveLoading = ref(false);
const approveRow = ref<TableRow | null>(null);
const approveNote = ref('');

function openApproveDialog(row: TableRow) {
  approveRow.value = row;
  approveNote.value = '';
  approveDialogVisible.value = true;
}

async function submitApprove() {
  if (!approveRow.value) return;
  approveLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/stocktakes/${approveRow.value.id}/approve`, { notes: approveNote.value });
    if (res.success) {
      ElMessage({ message: '审批通过', type: 'success', duration: 3000 });
      approveDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '审批失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    approveLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Reject dialog
// ────────────────────────────────────────────────────────────────────────────
const rejectDialogVisible = ref(false);
const rejectLoading = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectReasonSelect = ref('');
const rejectReasonOther = ref('');

const rejectReasonOptions = [
  { value: 'DATA_ERROR', label: '数据填写有误' },
  { value: 'INCOMPLETE', label: '盘点不完整' },
  { value: 'WRONG_PERIOD', label: '月份不符' },
  { value: 'WAREHOUSE_MISMATCH', label: '仓库信息有误' },
  { value: 'OTHER', label: '其他' },
];

function openRejectDialog(row: TableRow) {
  rejectRow.value = row;
  rejectReasonSelect.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectRow.value) return;
  const reason = rejectReasonSelect.value === 'OTHER'
    ? rejectReasonOther.value.trim()
    : rejectReasonOptions.find((o) => o.value === rejectReasonSelect.value)?.label || '';
  if (!reason) {
    ElMessage({ message: '请选择驳回原因', type: 'warning', duration: 3000 });
    return;
  }
  rejectLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/stocktakes/${rejectRow.value.id}/reject`, { reason });
    if (res.success) {
      ElMessage({ message: '已驳回', type: 'success', duration: 3000 });
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '驳回失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    rejectLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Apply diff to inventory (财务审批后)
// ────────────────────────────────────────────────────────────────────────────
const applyLoading = ref(false);

async function applyDiff(row: TableRow) {
  await ElMessageBox.confirm(
    `确认将盘点 ${row.stocktakeNo || row.id} 的差异应用到库存？\n仓库：${warehouseName(String(row.warehouseId))} · 月份：${row.periodMonth}\n⚠️ 此操作会调整实际库存数量，不可撤销。`,
    '应用盘点差异',
    { confirmButtonText: '确认应用', cancelButtonText: '取消', type: 'warning' }
  ).catch(() => { throw new Error('cancel'); });

  applyLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/stocktakes/${row.id}/apply`, {});
    if (res.success) {
      ElMessage({ message: '盘点差异已应用到库存', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '应用失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    if (String((e as Error).message) !== 'cancel') {
      ElMessage({ message: String((e as Error).message || '应用失败'), type: 'error', duration: 0, showClose: true });
    }
  } finally {
    applyLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Init
// ────────────────────────────────────────────────────────────────────────────
onMounted(async () => {
  await loadWarehouses();
  await loadData();
  // Dashboard onboarding 引导「期初建账」深链 (?openEntry=1) — 直接弹出期初建账对话框,
  // 不用新用户自己再摸索"批量导入盘点"里那个不起眼的勾选框 (fool-proof Rule 5)。
  if (route.query.openEntry === '1' && canWrite.value) {
    openOpeningEntry();
  }
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>盘点任务管理</h2>
      <div class="header-actions">
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 160px; margin-right: 12px"
          @change="handleFilterChange"
        >
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" :icon="Upload" @click="openBulkDialog">
          批量导入盘点
        </el-button>
        <el-button v-if="canWrite" type="success" :icon="Plus" @click="openOpeningEntry">
          期初建账/期初入库
        </el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openInitiateDialog">
          发起盘点
        </el-button>
      </div>
    </div>

    <!-- 月度提示 -->
    <el-alert
      v-if="!hasThisMonthTask && !loading"
      :title="`本月 (${currentMonth}) 尚未创建盘点任务，建议每月发起一次盘点。`"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <!-- 暂存提示 -->
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="盘点数量录入后暂存，批准后才正式生效调整库存。"
      style="margin-bottom: 16px"
    />

    <el-card>
      <el-table v-loading="loading" :data="tableData" row-key="id" stripe>
        <el-table-column label="盘点单号" min-width="200">
          <template #default="{ row }">
            <span>{{ row.stocktakeNo }}</span>
            <el-tag v-if="row.importMode === 'OPENING'" type="warning" size="small" effect="dark" style="margin-left: 6px">期初建账</el-tag>
            <el-tag v-else-if="row.importMode === 'NORMAL'" type="info" size="small" style="margin-left: 6px">批量导入</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="仓库" min-width="120">
          <template #default="{ row }">{{ warehouseName(String(row.warehouseId)) }}</template>
        </el-table-column>
        <el-table-column label="盘点月份" prop="periodMonth" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="(statusMap[String(row.status)]?.type as 'info' | 'warning' | 'success' | 'danger') || 'info'" size="small">
              {{ statusMap[String(row.status)]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起人" prop="initiatedBy" width="110" />
        <el-table-column label="发起时间" prop="initiatedAt" min-width="160">
          <template #default="{ row }">
            {{ row.initiatedAt ? String(row.initiatedAt).replace('T', ' ').slice(0, 16) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="审批人" prop="approvedBy" width="110">
          <template #default="{ row }">{{ row.approvedBy || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="View" @click="openDetail(row)">查看</el-button>
            <!-- 录入数量: INITIATED or COUNTING -->
            <el-button
              v-if="canWrite && (row.status === 'INITIATED' || row.status === 'COUNTING')"
              size="small"
              type="primary"
              :icon="Edit"
              @click="openCountDialog(row)"
            >录入</el-button>
            <!-- 提交审批: INITIATED or COUNTING -->
            <el-button
              v-if="canWrite && (row.status === 'INITIATED' || row.status === 'COUNTING')"
              size="small"
              type="warning"
              :loading="submitLoading"
              @click="submitForApproval(row)"
            >提交审批</el-button>
            <!-- 审批: PENDING_APPROVAL -->
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="success"
              :icon="Check"
              @click="openApproveDialog(row)"
            >审批</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="danger"
              :icon="Close"
              @click="openRejectDialog(row)"
            >驳回</el-button>
            <!-- 应用差异: APPROVED -->
            <el-button
              v-if="canWrite && row.status === 'APPROVED'"
              size="small"
              type="danger"
              :loading="applyLoading"
              @click="applyDiff(row)"
            >应用差异</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- ──────────────── Initiate Dialog ──────────────── -->
    <el-dialog v-model="initiateDialogVisible" title="发起盘点任务" width="480px">
      <!-- 防呆 Rule 1: 月底约束在弹窗打开时立即展示, 不等填完表单点提交才报错 -->
      <!-- 防呆 Rule 5 (无 dead-end): 约束挡住时给出临时盘点出口, 不是纯粹拒绝 -->
      <el-alert
        v-if="initiateConstraint && !initiateConstraint.canInitiateToday"
        type="warning"
        show-icon
        :closable="false"
        :title="`本月常规盘点只能在每月${initiateConstraint.monthEndThreshold}日后发起（下次可发起日期: ${initiateConstraint.nextAllowedDate}）`"
        style="margin-bottom: 8px"
      >
        <template #default>
          如需临时/专项清点（疑似失窃、货物损坏等），勾选下方【临时盘点】并填写原因即可立即发起。
        </template>
      </el-alert>
      <el-alert
        v-else-if="initiateConstraint && initiateConstraint.canInitiateToday"
        type="success"
        show-icon
        :closable="false"
        title="今天可以发起本月盘点"
        style="margin-bottom: 16px"
      />
      <el-form label-width="100px">
        <el-form-item label="盘点仓库" required>
          <el-select v-model="initiateForm.warehouseId" placeholder="选择仓库" style="width: 100%">
            <el-option v-for="w in warehouses" :key="String(w.id)" :label="String(w.name)" :value="String(w.id)">
              <span>{{ w.name }}</span>
              <el-tag
                v-if="wBadgeLabel(w.type)"
                size="small"
                style="margin-left: 8px; font-size: 11px"
                :color="wBadgeColor(w.type)"
                effect="plain"
              >{{ wBadgeLabel(w.type) }}</el-tag>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="盘点月份" required>
          <el-input v-model="initiateForm.periodMonth" placeholder="YYYY-MM" style="width: 160px" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px">格式: 2026-06</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="initiateForm.notes" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <!-- 临时/专项盘点出口: 只在月底约束挡住时展示, 平时常规发起不打扰 -->
        <el-form-item v-if="initiateConstraint && !initiateConstraint.canInitiateToday" label="临时盘点">
          <el-checkbox v-model="initiateForm.adHoc">
            本次为临时盘点（如疑似失窃/损坏，需专项清点）
          </el-checkbox>
        </el-form-item>
        <el-form-item v-if="initiateForm.adHoc" label="临时盘点原因" required>
          <el-select v-model="initiateForm.adHocReasonSelect" placeholder="选择原因" style="width: 100%">
            <el-option v-for="opt in adHocReasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="initiateForm.adHoc && initiateForm.adHocReasonSelect === 'OTHER'" label="补充说明" required>
          <el-input v-model="initiateForm.adHocReasonOther" type="textarea" :rows="2" placeholder="请填写具体原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="initiateDialogVisible = false">取消</el-button>
        <el-tooltip
          :disabled="!initiateConstraint || initiateConstraint.canInitiateToday || adHocReasonValid"
          :content="initiateConstraint ? `本月常规盘点只能在每月${initiateConstraint.monthEndThreshold}日后发起（下次可发起日期: ${initiateConstraint.nextAllowedDate}）；或勾选【临时盘点】并填写原因立即发起` : ''"
        >
          <el-button
            type="primary"
            :loading="initiateLoading"
            :disabled="!!initiateConstraint && !initiateConstraint.canInitiateToday && !adHocReasonValid"
            @click="submitInitiate"
          >确认发起</el-button>
        </el-tooltip>
      </template>
    </el-dialog>

    <!-- 批量导入盘点 -->
    <el-dialog v-model="bulkDialogVisible" :title="bulkDialogTitle" width="900px">
      <el-form label-width="110px">
        <el-form-item label="盘点仓库" required>
          <el-select v-model="bulkForm.warehouseId" placeholder="选择仓库" style="width: 320px" @change="bulkPreview = null">
            <el-option v-for="w in warehouses" :key="String(w.id)" :label="String(w.name)" :value="String(w.id)">
              <span>{{ w.name }}</span>
              <el-tag
                v-if="wBadgeLabel(w.type)"
                size="small"
                style="margin-left: 8px; font-size: 11px"
                :color="wBadgeColor(w.type)"
                effect="plain"
              >{{ wBadgeLabel(w.type) }}</el-tag>
            </el-option>
          </el-select>
          <el-button :icon="Download" style="margin-left: 12px" @click="downloadBulkTemplate">
            下载当前库存模板
          </el-button>
        </el-form-item>
        <el-form-item label="盘点月份" required>
          <el-input v-model="bulkForm.periodMonth" placeholder="YYYY-MM" style="width: 160px" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px">格式: 2026-06</span>
        </el-form-item>
        <el-form-item label="期初建账">
          <el-checkbox v-model="bulkForm.openingMode" @change="onOpeningModeChange">
            本次为期初建账（第一次给系统录入库存）
          </el-checkbox>
        </el-form-item>
        <!-- fool-proof Rule 3: 说人话在前, 会计术语收进可展开的次要说明里 (不是主文案) -->
        <div v-if="bulkForm.openingMode" style="margin: 0 0 12px 110px; max-width: 760px; color: #606266; font-size: 13px; line-height: 1.7">
          期初建账 = 告诉系统你工厂<b>现在仓库里已经有多少</b>原料/调料，系统从这个数量开始记账。
          只要填 <b>物料 + 数量</b> 就行（单价可以不填，不填就只记数量、不算钱），<b>不会产生要付的钱（应付款）</b>——
          跟「采购入库」不一样，这只是把你已经有的库存"登记"进系统。
          <el-popover placement="right" width="380" trigger="click">
            <template #reference>
              <el-link type="primary" :underline="false" style="font-size: 12px; margin-left: 4px">会计科目说明 ▸</el-link>
            </template>
            <div style="font-size: 12px; color: #606266; line-height: 1.7">
              系统内部会自动记一笔期初凭证：<b>借 1403 原材料 / 贷 4001 实收资本</b>。
              不计入营业外收入（不会虚增本期利润），也不产生采购应付款。
              诚实-null：没填单价的物料只建数量，不计入凭证金额。
            </div>
          </el-popover>
        </div>

        <!-- 期初建账: 两种录入方式 — 逐条录入(推荐, 不用 Excel) / Excel 批量导入(一次导入很多条) -->
        <template v-if="bulkForm.openingMode">
          <el-tabs v-model="openingEntryMode" style="margin: 0 0 4px 110px">
            <el-tab-pane label="逐条录入（推荐，不用 Excel）" name="ROWS">
              <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px">
                一条一条填：选物料、填数量，点「添加一行」可以继续填下一个。填完点下面「预览比对」核对，再「确认导入」。
              </el-alert>
              <el-table :data="openingRows" size="small" border style="margin-bottom: 10px">
                <el-table-column label="大类" width="100">
                  <template #default="{ row }">
                    <el-select v-model="row._bigCategory" size="small" style="width: 100%">
                      <el-option v-for="opt in BIG_CATEGORY_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="物料" min-width="200">
                  <template #header><span style="color: #f56c6c">*</span> 物料</template>
                  <template #default="{ row, $index }">
                    <el-select
                      v-model="row.materialTypeId"
                      placeholder="选择物料"
                      filterable
                      size="small"
                      style="width: 100%"
                      :loading="materialOptionsLoading"
                      @change="(val: string) => handleOpeningMaterialChange($index, val)"
                    >
                      <el-option
                        v-for="m in materialsForOpeningRow(row)"
                        :key="m.id"
                        :label="`${m.name} (${m.code})`"
                        :value="m.id"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="数量" width="150">
                  <template #header><span style="color: #f56c6c">*</span> 数量</template>
                  <template #default="{ row }">
                    <el-input-number v-model="row.quantity" :min="0" :precision="2" size="small" style="width: 130px" placeholder="期初数量" />
                  </template>
                </el-table-column>
                <el-table-column label="单位" width="90">
                  <template #default="{ row }">
                    <el-input v-model="row.unit" size="small" placeholder="kg" />
                  </template>
                </el-table-column>
                <el-table-column label="单价（可不填）" width="150">
                  <template #default="{ row }">
                    <el-input-number v-model="row.unitPrice" :min="0" :precision="2" size="small" style="width: 130px" placeholder="不填=不算钱" />
                  </template>
                </el-table-column>
                <el-table-column label="" width="60" fixed="right">
                  <template #default="{ $index }">
                    <el-button size="small" text type="danger" :icon="Delete" @click="removeOpeningRow($index)" />
                  </template>
                </el-table-column>
              </el-table>
              <el-button :icon="Plus" @click="addOpeningRow">添加一行</el-button>
            </el-tab-pane>
            <el-tab-pane label="Excel 批量导入（一次导入很多条）" name="EXCEL">
              <el-form-item label="填好的表格" label-width="90px">
                <el-upload
                  :auto-upload="false"
                  :limit="1"
                  accept=".xlsx,.xls"
                  :on-change="onBulkFileChange"
                  :on-exceed="() => ElMessage({ message: '一次只能上传一个文件', type: 'warning' })"
                >
                  <el-button :icon="Upload">选择文件</el-button>
                  <template #tip>
                    <span style="color: #909399; font-size: 12px">仅 .xlsx/.xls，请使用上方「下载当前库存模板」的文件填写「实盘数量」列</span>
                  </template>
                </el-upload>
              </el-form-item>
            </el-tab-pane>
          </el-tabs>
        </template>

        <!-- 常规批量导入盘点 (非期初建账): 只有 Excel 一种方式, 保持原样 -->
        <el-form-item v-if="!bulkForm.openingMode" label="填好的表格" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".xlsx,.xls"
            :on-change="onBulkFileChange"
            :on-exceed="() => ElMessage({ message: '一次只能上传一个文件', type: 'warning' })"
          >
            <el-button :icon="Upload">选择文件</el-button>
            <template #tip>
              <span style="color: #909399; font-size: 12px">仅 .xlsx/.xls，请使用上方导出的模板填写「实盘数量」列</span>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>

      <!-- 比对报告 -->
      <div v-if="bulkPreview">
        <el-divider content-position="left">
          比对报告（仓库：{{ bulkPreview.warehouseName }}）
        </el-divider>
        <div style="margin-bottom: 10px; display: flex; gap: 16px; flex-wrap: wrap; font-size: 13px">
          <span>数据行：<b>{{ bulkPreview.totalRows }}</b></span>
          <span>匹配：<b>{{ bulkPreview.matchedCount }}</b></span>
          <el-tag type="success" size="small">盘盈 {{ bulkPreview.surplusCount }}</el-tag>
          <el-tag type="danger" size="small">盘亏 {{ bulkPreview.shortageCount }}</el-tag>
          <el-tag type="info" size="small">一致 {{ bulkPreview.matchCount }}</el-tag>
          <el-tag size="small">未盘点 {{ bulkPreview.skippedCount }}</el-tag>
          <el-tag v-if="bulkForm.openingMode && (bulkPreview.willCreateCount || 0) > 0" type="warning" size="small" effect="dark">
            将新建 {{ bulkPreview.willCreateCount }}
          </el-tag>
          <el-tag v-if="bulkPreview.errorCount > 0" type="warning" size="small">失败 {{ bulkPreview.errorCount }}</el-tag>
        </div>

        <!-- 匹配失败（诚实上报，不静默丢弃）-->
        <el-alert
          v-if="bulkPreview.errorCount > 0"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 10px"
          :title="`有 ${bulkPreview.errorCount} 行未能匹配，请核对后修正（其余匹配行仍可确认导入）`"
        />
        <el-table v-if="bulkPreview.errorCount > 0" :data="bulkPreview.errors" size="small" max-height="160" border style="margin-bottom: 12px">
          <el-table-column label="行号" prop="rowNumber" width="70" />
          <el-table-column label="批次号" prop="batchNumber" width="180" />
          <el-table-column label="物料名称" prop="materialName" width="140" />
          <el-table-column label="失败原因" prop="reason" min-width="240" />
        </el-table>

        <el-table :data="bulkPreview.matchedLines" size="small" max-height="300" border>
          <el-table-column label="物料名称" prop="materialName" min-width="130" />
          <el-table-column label="批次号" min-width="170">
            <template #default="{ row }">
              <span v-if="row.willCreate" style="color: #909399">（新建，自动生成）</span>
              <span v-else>{{ row.batchNumber }}</span>
            </template>
          </el-table-column>
          <el-table-column label="账面数量" prop="systemQty" width="100" align="right" />
          <el-table-column label="实盘数量" width="100" align="right">
            <template #default="{ row }">{{ row.actualQty ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="差异" width="100" align="right">
            <template #default="{ row }">{{ row.differenceQty ?? '—' }}</template>
          </el-table-column>
          <el-table-column v-if="bulkForm.openingMode" label="单价" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.willCreate">{{ row.unitPrice != null ? row.unitPrice : '无单价' }}</span>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
          <el-table-column label="单位" prop="unit" width="70" />
          <el-table-column label="盈亏" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.willCreate" type="warning" size="small" effect="dark">将新建</el-tag>
              <el-tag v-else :type="diffTypeTag(row.differenceType)" size="small">{{ diffTypeLabel(row.differenceType) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="bulkDialogVisible = false">取消</el-button>
        <el-button :loading="bulkLoading" @click="runBulkPreview">预览比对</el-button>
        <el-button
          type="primary"
          :loading="bulkLoading"
          :disabled="!bulkPreview || bulkPreview.matchedCount === 0"
          @click="runBulkConfirm"
        >
          确认导入（创建盘点）
        </el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Detail Dialog ──────────────── -->
    <el-dialog v-model="detailDialogVisible" :title="`盘点详情 — ${detailData?.stocktakeNo || ''}`" width="700px">
      <template v-if="detailData">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="盘点单号">{{ detailData.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="(statusMap[String(detailData.status)]?.type as 'info' | 'warning' | 'success' | 'danger') || 'info'" size="small">
              {{ statusMap[String(detailData.status)]?.label || detailData.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(String(detailData.warehouseId)) }}</el-descriptions-item>
          <el-descriptions-item label="盘点月份">{{ detailData.periodMonth }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detailData.initiatedBy }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ detailData.initiatedAt ? String(detailData.initiatedAt).replace('T', ' ').slice(0, 16) : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detailData.approvedBy" label="审批人">{{ detailData.approvedBy }}</el-descriptions-item>
          <el-descriptions-item v-if="detailData.rejectReason" label="驳回原因" :span="2">
            <el-text type="danger">{{ detailData.rejectReason }}</el-text>
          </el-descriptions-item>
          <el-descriptions-item v-if="detailData.notes" label="备注" :span="2">{{ detailData.notes }}</el-descriptions-item>
        </el-descriptions>

        <!-- 差异预览 (APPROVED / APPLIED) -->
        <div v-if="detailData.status === 'APPROVED' || detailData.status === 'APPLIED'" style="margin-top: 20px">
          <div style="font-weight: 600; margin-bottom: 8px; color: #1B65A8">
            盘点差异预览
            <el-text v-if="detailData.status === 'APPLIED'" type="success" style="font-size: 12px; margin-left: 8px">（已应用，差异已过账）</el-text>
            <el-text v-else type="warning" style="font-size: 12px; margin-left: 8px">（已审批，批准后生效）</el-text>
          </div>
          <el-table v-loading="diffLoading" :data="diffPreview" size="small" border>
            <el-table-column label="批次/物料" prop="materialName" min-width="130" />
            <el-table-column label="系统库存" prop="systemQty" width="100" align="right" />
            <el-table-column label="实盘数量" prop="actualQty" width="100" align="right">
              <template #default="{ row }">{{ row.actualQty ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="差异" prop="differenceQty" width="90" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.differenceQty) < 0 ? '#f56c6c' : Number(row.differenceQty) > 0 ? '#67c23a' : '' }">
                  {{ row.differenceQty != null ? (Number(row.differenceQty) > 0 ? '+' : '') + row.differenceQty : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="类型" prop="differenceType" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.differenceType === 'SURPLUS'" type="success" size="small">盘盈</el-tag>
                <el-tag v-else-if="row.differenceType === 'SHORTAGE'" type="danger" size="small">盘亏</el-tag>
                <el-tag v-else-if="row.differenceType === 'BALANCED'" type="info" size="small">平衡</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Count Entry Dialog ──────────────── -->
    <el-dialog v-model="countDialogVisible" :title="`录入盘点数量 — ${countStocktakeNo}`" width="780px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="录入后为暂存状态，提交审批并批准后才正式调整库存。"
        style="margin-bottom: 16px"
      />
      <el-table v-loading="countLoading" :data="countItems" size="small" border>
        <el-table-column label="批次号" prop="batchNo" min-width="120" />
        <el-table-column label="物料名称" prop="materialName" min-width="130" />
        <el-table-column label="系统库存" prop="systemQty" width="100" align="right" />
        <el-table-column label="实盘数量" width="130">
          <template #default="{ row }">
            <el-input-number
              v-model="row.actualQty"
              :min="0"
              :precision="2"
              size="small"
              style="width: 110px"
              placeholder="实盘数量"
            />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="80" align="right">
          <template #default="{ row }">
            <span v-if="row.actualQty != null">
              <span :style="{ color: row.actualQty - row.systemQty < 0 ? '#f56c6c' : row.actualQty - row.systemQty > 0 ? '#67c23a' : '' }">
                {{ row.actualQty - row.systemQty > 0 ? '+' : '' }}{{ (row.actualQty - row.systemQty).toFixed(3) }}
              </span>
            </span>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.notes" size="small" placeholder="可选备注" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="countDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="countLoading" @click="saveCountItems">保存暂存</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Approve Dialog ──────────────── -->
    <el-dialog v-model="approveDialogVisible" title="审批盘点任务" width="480px">
      <template v-if="approveRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="盘点单号">{{ approveRow.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(String(approveRow.warehouseId)) }}</el-descriptions-item>
          <el-descriptions-item label="盘点月份">{{ approveRow.periodMonth }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ approveRow.initiatedBy }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="80px">
          <el-form-item label="审批备注">
            <el-input v-model="approveNote" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
        <el-alert type="warning" show-icon :closable="false" title="审批后差异数据锁定，可进一步「应用差异」更新实际库存。" />
      </template>
      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="approveLoading" @click="submitApprove">确认审批通过</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Reject Dialog ──────────────── -->
    <el-dialog v-model="rejectDialogVisible" title="驳回盘点任务" width="480px">
      <template v-if="rejectRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="盘点单号">{{ rejectRow.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(String(rejectRow.warehouseId)) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="100px">
          <el-form-item label="驳回原因" required>
            <el-select v-model="rejectReasonSelect" placeholder="选择驳回原因" style="width: 100%">
              <el-option v-for="opt in rejectReasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="rejectReasonSelect === 'OTHER'" label="补充说明" required>
            <el-input v-model="rejectReasonOther" type="textarea" :rows="2" placeholder="请填写具体原因" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectLoading" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.el-card {
  border-radius: 10px;
}
</style>
