<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { vReveal } from '@/composables/useReveal';
import { listStoreMaster } from '@/api/logistics';
import { useAuthStore } from '@/store/modules/auth';
import type { LogisticsDeliveryOrder, ManualOrderRow, OrderBatch, PreviewResult } from '@/api/logistics';
import ColumnMappingConfirm from './ColumnMappingConfirm.vue';

const props = defineProps<{
  preview: PreviewResult | null;
  batch: OrderBatch | null;
  orders: LogisticsDeliveryOrder[];
  uploading: boolean;
  committing: boolean;
  error: string | null;
}>();

const emit = defineEmits<{
  (event: 'download-template'): void;
  (event: 'upload-file', file: File, columnMapping?: Record<number, string>): void;
  (event: 'preview-paste', payload: { rawText: string; businessDate: string | null; columnMapping?: Record<number, string> }): void;
  (event: 'commit'): void;
  (event: 'submit-manual', payload: { businessDate: string | null; rows: ManualOrderRow[] }): void;
  (event: 'clear-batch'): void;
}>();

type EntryMode = 'file' | 'paste' | 'manual';
const mode = ref<EntryMode>('file');
// 用户在映射面板改了映射并点确认 → 带覆盖重新预览，回来后要载入表格（而非再弹面板）。
const awaitingLoad = ref(false);

// ==================== 文件导入 ====================

const fileInput = ref<HTMLInputElement | null>(null);
const selectedFileName = ref('');
const lastFile = ref<File | null>(null); // 保留最近上传的文件, 供映射确认后带覆盖重新预览

const canCommit = computed(() => Boolean(props.preview) && props.preview!.validRows > 0 && !props.committing);
const rowErrorPreview = computed(() => (props.preview?.rowErrors ?? []).slice(0, 20));

function handleFileChange(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  selectedFileName.value = file.name;
  lastFile.value = file;
  awaitingLoad.value = false;
  emit('upload-file', file); // 首次不带覆盖映射 → 后端自动识别
}

function resetFileInput(): void {
  selectedFileName.value = '';
  if (fileInput.value) fileInput.value.value = '';
}

// ==================== 粘贴导入（从 Excel 连表头复制） ====================

const pasteText = ref('');
const pasteDate = ref<string>('');
const lastPaste = ref<{ rawText: string; businessDate: string | null } | null>(null);

const pasteRowCount = computed(() => pasteText.value.split(/\r?\n/).filter((l) => l.trim()).length);

function submitPaste(): void {
  const text = pasteText.value;
  if (!text.trim()) { ElMessage.warning('请先从 Excel 连表头复制并粘贴到框里'); return; }
  const businessDate = pasteDate.value || null;
  lastPaste.value = { rawText: text, businessDate };
  awaitingLoad.value = false;
  emit('preview-paste', { rawText: text, businessDate }); // 首次不带覆盖映射
}

// ==================== 映射确认（文件 + 粘贴共用） ====================

// 有列映射结果 + 处于文件/粘贴模式 + 不在等待重新预览载入时 → 显示映射确认面板（不自动进表格）。
const showMappingPanel = computed(() =>
  Boolean(props.preview?.columnMapping) && (mode.value === 'file' || mode.value === 'paste') && !awaitingLoad.value,
);

function onMappingConfirm(override: Record<number, string>, dirty: boolean): void {
  if (!dirty) {
    loadPreviewIntoTable(); // 未改映射 → 直接用现有预览载入可编辑表格
    return;
  }
  awaitingLoad.value = true; // 改了映射 → 带覆盖重新预览
  if (mode.value === 'paste' && lastPaste.value) {
    emit('preview-paste', { ...lastPaste.value, columnMapping: override });
  } else if (lastFile.value) {
    emit('upload-file', lastFile.value, override);
  }
}

// ==================== 手动录入 ====================
// 防呆: 门店名称 + 配送地址必填; 件数/箱数/重量/体积至少填一项帮助配载(不强制).
// 提交前逐行本地校验 → 高亮缺失; 后端再次校验并逐行返回错误(与文件导入同一套).

// 字段与 Excel 导入模板一一对应（业务日期在表格上方单选，经纬度由地理编码补全不手填）。
interface ManualRowForm {
  storeCode: string; // 订单号（Excel 模板同名列）——留空后端自动生成
  storeName: string;
  address: string;
  pieces: string;
  boxes: string;
  weightKg: string;
  volumeCbm: string;
  areaCode: string; // 区域
  windowStart: string;
  windowEnd: string;
  longitude: string; // 经度（文件导入带入时保留，避免重复地理编码；表格不展示，随提交透传）
  latitude: string; // 纬度
}

/** 后端必填字段(除自动生成的订单号/系统给的业务日期/二选一的数量外) —— 防呆: 缺任一项高亮 + 禁用提交。 */
const REQUIRED_FIELDS: Array<keyof ManualRowForm> = ['storeName', 'address', 'weightKg', 'volumeCbm'];
const FIELD_LABELS: Record<string, string> = {
  storeName: '门店名称', address: '配送地址', weightKg: '重量kg', volumeCbm: '体积m³',
};

/** 数量 = 件数 / 箱数 二选一（门店下单数量通常就是"多少箱货"）——两个都空才算缺。 */
function qtyMissing(r: ManualRowForm): boolean {
  return !r.pieces.trim() && !r.boxes.trim();
}

// 门店名自动匹配 —— 输入即从门店主数据(记忆)里推测门店，选中后自动带出地址/区域(可再改)，
// 坐标由后端按门店名从主数据复用，不再重复地理编码 (客户"录一次"核心诉求)。
interface StoreSuggestion { storeName: string; address: string | null; areaCode: string | null; }
const authStore = useAuthStore();

async function queryStoreSuggestions(query: string, cb: (results: StoreSuggestion[]) => void): Promise<void> {
  const factoryId = authStore.factoryId;
  const kw = (query || '').trim();
  if (!factoryId || kw.length < 1) { cb([]); return; }
  try {
    const res = await listStoreMaster(factoryId, { page: 0, size: 8, keyword: kw });
    cb((res.data?.content ?? []).map((s) => ({ storeName: s.storeName, address: s.address, areaCode: s.areaCode })));
  } catch {
    cb([]); // 记忆库不可用时静默不打断录入
  }
}

/** 选中历史门店 → 自动带出地址/区域(覆盖当前值, 仍可手工再改)。坐标由后端按名复用。 */
function onStorePicked(row: ManualRowForm, item: StoreSuggestion): void {
  if (item.address) row.address = item.address;
  if (item.areaCode) row.areaCode = item.areaCode;
}

function todayStr(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function emptyRow(): ManualRowForm {
  return { storeCode: '', storeName: '', address: '', pieces: '', boxes: '', weightKg: '', volumeCbm: '', areaCode: '', windowStart: '', windowEnd: '', longitude: '', latitude: '' };
}

const manualDate = ref<string>(todayStr());

// ==================== 文件导入 → 载入可编辑表格 ====================
// 上传 Excel/CSV 后端解析出的每一行（含错误行）→ 灌进上面的可编辑表格，用户直接改完再提交。
// 让文件导入和手动录入用同一张表：文件只是"批量填表"，导入后可继续手动修改内容（客户诉求）。
const fromFileImport = ref(false);
const importedFileName = ref('');

function numToStr(v: number | null | undefined): string {
  return v == null ? '' : String(v);
}

/** 把后端 preview 的解析行（含无效行）映射为可编辑表格行。 */
function loadPreviewIntoTable(): void {
  const p = props.preview;
  if (!p || !p.rows?.length) return;
  manualRows.value = p.rows.map((r) => ({
    storeCode: r.storeCode ?? '',
    storeName: r.storeName ?? '',
    address: r.address ?? '',
    pieces: numToStr(r.pieces),
    boxes: numToStr(r.boxes),
    weightKg: numToStr(r.weightKg),
    volumeCbm: numToStr(r.volumeCbm),
    areaCode: r.areaCode ?? '',
    windowStart: r.windowStart ?? '',
    windowEnd: r.windowEnd ?? '',
    longitude: numToStr(r.longitude),
    latitude: numToStr(r.latitude),
  }));
  if (p.businessDate) manualDate.value = p.businessDate;
  importedFileName.value = p.sourceFilename || selectedFileName.value;
  fromFileImport.value = true;
  mode.value = 'manual'; // 切到可编辑表格
}

// 预览到达：只在「用户已在映射面板点确认 → 带覆盖重新预览返回」时载入可编辑表格。
// 首次识别到达不自动进表格 —— 先弹映射确认面板给用户核对（见 showMappingPanel）。
watch(
  () => props.preview,
  (p) => {
    if (p && awaitingLoad.value) { awaitingLoad.value = false; loadPreviewIntoTable(); }
  },
);
const manualRows = ref<ManualRowForm[]>([emptyRow(), emptyRow()]);

function addManualRow(): void {
  manualRows.value = [...manualRows.value, emptyRow()];
}

function removeManualRow(index: number): void {
  manualRows.value = manualRows.value.filter((_, i) => i !== index);
  if (!manualRows.value.length) manualRows.value = [emptyRow()];
}

/** 一行是否"开始填了"(任一字段非空) —— 用于判定该行是否要提交/校验。 */
function rowTouched(r: ManualRowForm): boolean {
  return Boolean(r.storeCode.trim() || r.storeName.trim() || r.address.trim() || r.pieces.trim()
    || r.boxes.trim() || r.weightKg.trim() || r.volumeCbm.trim() || r.areaCode.trim()
    || r.windowStart.trim() || r.windowEnd.trim());
}

/** 已填行里，缺少任一必填字段（或件数/箱数都空）的行号(1-based) —— 防呆预校验。 */
const invalidRowNumbers = computed(() => {
  const bad: number[] = [];
  manualRows.value.forEach((r, i) => {
    if (rowTouched(r) && (REQUIRED_FIELDS.some((f) => !r[f].trim()) || qtyMissing(r))) bad.push(i + 1);
  });
  return bad;
});

/** 第一处缺失字段的中文名(用于 tooltip 提示具体缺什么)。 */
const firstMissingHint = computed(() => {
  for (let i = 0; i < manualRows.value.length; i++) {
    const r = manualRows.value[i];
    if (!rowTouched(r)) continue;
    const miss = REQUIRED_FIELDS.find((f) => !r[f].trim());
    if (miss) return `第 ${i + 1} 行缺少「${FIELD_LABELS[miss]}」`;
    if (qtyMissing(r)) return `第 ${i + 1} 行「件数、箱数」至少填一项`;
  }
  return '';
});

const filledRowCount = computed(() => manualRows.value.filter(rowTouched).length);
const canSubmitManual = computed(() => filledRowCount.value > 0 && invalidRowNumbers.value.length === 0 && !props.committing);

function isRowMissing(r: ManualRowForm, field: keyof ManualRowForm): boolean {
  return rowTouched(r) && !r[field].trim();
}

function submitManual(): void {
  if (invalidRowNumbers.value.length) {
    ElMessage.warning(firstMissingHint.value || `第 ${invalidRowNumbers.value.join('、')} 行有必填项未填`);
    return;
  }
  const rows: ManualOrderRow[] = manualRows.value.filter(rowTouched).map((r) => ({
    // 订单号(storeCode)留空 → 后端防呆自动生成; 箱数留空 → 后端默认 0。字段与 Excel 模板一致。
    storeCode: r.storeCode.trim() || null,
    storeName: r.storeName.trim(),
    address: r.address.trim(),
    pieces: r.pieces.trim() || null,
    boxes: r.boxes.trim() || null,
    weightKg: r.weightKg.trim() || null,
    volumeCbm: r.volumeCbm.trim() || null,
    areaCode: r.areaCode.trim() || null,
    windowStart: r.windowStart.trim() || null,
    windowEnd: r.windowEnd.trim() || null,
    // 文件导入带入的坐标透传，避免重复地理编码（经纬度须同时有效才透传）。
    longitude: r.longitude.trim() || null,
    latitude: r.latitude.trim() || null,
  }));
  if (!rows.length) {
    ElMessage.warning('请至少录入一行订单');
    return;
  }
  emit('submit-manual', { businessDate: manualDate.value || null, rows });
}

function switchMode(next: EntryMode): void {
  mode.value = next;
  resetFileInput();
  awaitingLoad.value = false;
  if (next === 'file') fromFileImport.value = false; // 手动切回文件模式 → 清掉"来自文件"标记
}

// 手动录入成功(批次创建, batchId 变化)后清空表格, 防止误重复提交造成重复批次(防呆 Rule 4)。
watch(() => props.batch?.id, (id, prev) => {
  if (id && id !== prev && mode.value === 'manual') {
    manualRows.value = [emptyRow(), emptyRow()];
    fromFileImport.value = false;
    importedFileName.value = '';
  }
});
</script>

<template>
  <section data-testid="import-step" class="step-panel">
    <div class="panel-heading">
      <div>
        <p>第一步</p>
        <h2>录入配送订单</h2>
        <span>上传当天订单文件，或直接在表格里填写；系统校验后写入，空白时地图与线路保持空白，不使用示例数据。</span>
      </div>
      <el-button plain @click="emit('download-template')">下载导入模板</el-button>
    </div>

    <el-radio-group :model-value="mode" class="mode-toggle" @update:model-value="switchMode">
      <el-radio-button value="file">📄 文件导入</el-radio-button>
      <el-radio-button value="paste">📋 粘贴导入</el-radio-button>
      <el-radio-button value="manual">✏️ 手动录入</el-radio-button>
    </el-radio-group>

    <div v-if="batch" v-reveal="0" class="batch-card" data-testid="import-batch-summary">
      <div class="batch-card-head">
        <strong>✓ 已录入批次 {{ batch.batchNumber }}</strong>
        <el-button link type="danger" size="small" data-testid="clear-batch" @click="emit('clear-batch')">✕ 清除，重新导入</el-button>
      </div>
      <span>{{ batch.validRows }} / {{ batch.totalRows }} 行有效，业务日期 {{ batch.businessDate }}。点下方「下一步」生成排线。</span>
    </div>

    <!-- 已导入订单明细表（打开批次即列出，供核对；文件 / 手动 / 测试导入统一在此可见） -->
    <div v-if="orders.length" v-reveal="0" class="imported-detail" data-testid="imported-detail">
      <div class="detail-head">
        <strong>已导入 {{ orders.length }} 单</strong>
        <span>明细如下，核对无误后点「下一步」生成排线</span>
      </div>
      <el-table :data="orders" size="small" border max-height="360" class="detail-table">
        <el-table-column type="index" label="#" width="46" />
        <el-table-column prop="storeCode" label="订单号" width="120" show-overflow-tooltip />
        <el-table-column prop="storeName" label="门店" min-width="140" show-overflow-tooltip />
        <el-table-column prop="areaCode" label="区域" width="88" />
        <el-table-column prop="pieces" label="件数" width="66" align="right" />
        <el-table-column prop="boxes" label="箱数" width="66" align="right" />
        <el-table-column prop="weightKg" label="重量kg" width="92" align="right" />
        <el-table-column prop="volumeCbm" label="体积m³" width="92" align="right" />
        <el-table-column label="送达时间窗" min-width="128">
          <template #default="{ row }">{{ row.windowStart || '—' }} – {{ row.windowEnd || '—' }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ============ 文件导入 ============ -->
    <template v-if="mode === 'file'">
      <label class="file-input">选择订单文件（CSV / Excel）
        <input
          ref="fileInput"
          data-testid="csv-input"
          type="file"
          accept=".csv,text/csv,.xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          :disabled="uploading"
          @change="handleFileChange"
        >
      </label>
      <p class="file-hint">支持任意表头的 Excel/CSV —— 系统会自动识别列（如「客户」「数量」也认得），上传后核对一下即可。</p>
      <p v-if="selectedFileName" class="selected-file">已选择：{{ selectedFileName }}</p>
      <p v-if="uploading" data-testid="import-uploading" class="import-status">正在解析文件…</p>

      <button
        v-if="!showMappingPanel"
        data-testid="commit-import"
        class="primary-button"
        type="button"
        :disabled="!canCommit"
        @click="emit('commit'); resetFileInput()"
      >
        {{ committing ? '正在提交…' : '提交导入' }}
      </button>
    </template>

    <!-- ============ 粘贴导入（从 Excel 连表头复制） ============ -->
    <template v-else-if="mode === 'paste'">
      <div class="manual-toolbar">
        <span class="field-label">业务日期</span>
        <el-date-picker
          v-model="pasteDate"
          type="date"
          value-format="YYYY-MM-DD"
          clearable
          placeholder="选填，粘贴无日期列时用"
          style="width: 200px"
        />
        <span class="manual-hint">从 Excel 选中「含表头」的区域，Ctrl+C 复制，粘贴到下方框里 —— 系统自动识别各列。</span>
      </div>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="8"
        resize="vertical"
        data-testid="paste-textarea"
        class="paste-textarea"
        placeholder="从 Excel 连同表头一起复制，粘贴到这里，例如：&#10;门店名称	配送地址	箱数	重量kg	体积m³&#10;大润发苏州店	苏州工业园区xx路1号	10	50	1.5"
      />
      <div class="paste-actions">
        <span v-if="pasteRowCount" class="filled-count">检测到 {{ pasteRowCount }} 行（含表头）</span>
        <button
          v-if="!showMappingPanel"
          class="primary-button"
          type="button"
          data-testid="paste-preview-btn"
          :disabled="!pasteText.trim() || uploading"
          @click="submitPaste"
        >
          {{ uploading ? '识别中…' : '识别并预览' }}
        </button>
      </div>
    </template>

    <!-- ============ 手动录入 ============ -->
    <template v-else>
      <el-alert
        v-if="fromFileImport"
        type="success"
        :closable="false"
        show-icon
        class="from-file-banner"
        :title="`已从「${importedFileName || '文件'}」读入 ${manualRows.length} 行，可在下表直接修改后提交`"
        description="标红的必填项请补齐；也可用门店名自动匹配历史门店。改完点「提交录入」写入。"
      />
      <div class="manual-toolbar">
        <span class="field-label">业务日期</span>
        <el-date-picker
          v-model="manualDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择日期"
          style="width: 150px"
        />
        <span class="manual-hint">门店名称、配送地址、重量、体积必填；件数 / 箱数至少填一项（门店下单数量，通常填箱数）；订单号留空自动生成。</span>
      </div>

      <el-table :data="manualRows" size="small" border class="manual-table">
        <el-table-column type="index" label="#" width="42" />
        <el-table-column label="订单号" width="128">
          <template #default="{ row }"><el-input v-model="row.storeCode" placeholder="留空自动生成" /></template>
        </el-table-column>
        <el-table-column label="门店名称 *" min-width="170">
          <template #default="{ row }">
            <el-autocomplete
              v-model="row.storeName"
              :fetch-suggestions="queryStoreSuggestions"
              placeholder="输入门店名，自动匹配历史门店"
              :trigger-on-focus="false"
              value-key="storeName"
              class="store-name-input"
              :class="{ 'missing': isRowMissing(row, 'storeName') }"
              @select="(item: StoreSuggestion) => onStorePicked(row, item)"
            >
              <template #default="{ item }">
                <div class="store-sug"><strong>{{ item.storeName }}</strong><span v-if="item.address">{{ item.address }}</span></div>
              </template>
            </el-autocomplete>
          </template>
        </el-table-column>
        <el-table-column label="配送地址 *" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.address" placeholder="如：苏州工业园区xx路1号" :class="{ 'missing': isRowMissing(row, 'address') }" />
          </template>
        </el-table-column>
        <el-table-column label="件数" width="82">
          <template #default="{ row }"><el-input v-model="row.pieces" placeholder="件数" :class="{ 'missing': rowTouched(row) && qtyMissing(row) }" /></template>
        </el-table-column>
        <el-table-column label="箱数(数量) *" width="98">
          <template #default="{ row }"><el-input v-model="row.boxes" placeholder="下单箱数" :class="{ 'missing': rowTouched(row) && qtyMissing(row) }" /></template>
        </el-table-column>
        <el-table-column label="重量kg *" width="90">
          <template #default="{ row }"><el-input v-model="row.weightKg" placeholder="80" :class="{ 'missing': isRowMissing(row, 'weightKg') }" /></template>
        </el-table-column>
        <el-table-column label="体积m³ *" width="90">
          <template #default="{ row }"><el-input v-model="row.volumeCbm" placeholder="1.5" :class="{ 'missing': isRowMissing(row, 'volumeCbm') }" /></template>
        </el-table-column>
        <el-table-column label="区域" width="110">
          <template #default="{ row }"><el-input v-model="row.areaCode" placeholder="如：园区" /></template>
        </el-table-column>
        <el-table-column label="送达时间窗" min-width="170">
          <template #default="{ row }">
            <div class="win-cell">
              <el-input v-model="row.windowStart" placeholder="08:00" style="width: 74px" />
              <span>–</span>
              <el-input v-model="row.windowEnd" placeholder="10:00" style="width: 74px" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeManualRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="manual-actions">
        <el-button plain @click="addManualRow">+ 添加一行</el-button>
        <span v-if="filledRowCount" class="filled-count">已填 {{ filledRowCount }} 行</span>
        <el-tooltip
          :disabled="canSubmitManual || props.committing"
          :content="invalidRowNumbers.length ? (firstMissingHint || `第 ${invalidRowNumbers.join('、')} 行有必填项未填`) : '请至少录入一行订单'"
          placement="top"
        >
          <span>
            <button
              data-testid="submit-manual"
              class="primary-button"
              type="button"
              :disabled="!canSubmitManual"
              @click="submitManual"
            >
              {{ committing ? '正在录入…' : '提交录入' }}
            </button>
          </span>
        </el-tooltip>
      </div>
    </template>

    <!-- ============ 映射确认面板（文件 + 粘贴，识别后先核对列→字段）============ -->
    <ColumnMappingConfirm
      v-if="showMappingPanel"
      v-reveal="0"
      :columns="preview!.columnMapping!.columns"
      :auto-confident="preview!.columnMapping!.autoConfident"
      :busy="uploading"
      @confirm="onMappingConfirm"
    />

    <!-- ============ 校验结果(文件+手动共用) ============ -->
    <div v-if="preview && !showMappingPanel" v-reveal="0" data-testid="import-preview" class="validation-card">
      <strong>{{ preview.validRows }} / {{ preview.totalRows }} 行校验通过</strong>
      <span v-if="preview.errorRows > 0">{{ preview.errorRows }} 行存在字段错误，仅写入有效行——请修正后重试。</span>
      <span v-else>全部行校验通过。</span>
      <ul v-if="rowErrorPreview.length" data-testid="import-row-errors" class="row-error-list">
        <li v-for="(rowError, index) in rowErrorPreview" :key="`${rowError.rowNumber}-${rowError.column}-${index}`">
          第 {{ rowError.rowNumber }} 行 · {{ rowError.column }}：{{ rowError.message }}
        </li>
      </ul>
      <p v-if="preview.rowErrors.length > rowErrorPreview.length" class="row-error-more">
        还有 {{ preview.rowErrors.length - rowErrorPreview.length }} 条错误未显示。
      </p>
    </div>

    <p v-if="error" data-testid="import-error" class="import-error">{{ error }}</p>
    <p v-if="!preview && !batch && !uploading && (mode === 'file' || mode === 'paste')" class="empty-hint">
      尚未导入任何订单——下方地图与线路将保持空白，不会使用示例数据。
    </p>
  </section>
</template>

<style scoped lang="scss">
.step-panel { display: grid; gap: 18px; min-height: 340px; padding: 28px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; }
.panel-heading { display: flex; justify-content: space-between; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } span { color: #667085; line-height: 1.6; }
.mode-toggle { width: fit-content; }
.batch-card { display: grid; gap: 4px; padding: 16px 20px; background: #ecfdf3; border-radius: 10px; } .batch-card strong { color: #027a48; font-size: 15px; }
.batch-card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.validation-card { display: grid; gap: 6px; padding: 20px; background: #f0f7ff; border-radius: 10px; } strong { color: #101828; font-size: 18px; }
.row-error-list { display: grid; gap: 4px; margin: 6px 0 0; padding-left: 20px; color: #b42318; font-size: 13px; }
.row-error-more { margin: 0; color: #b42318; font-size: 13px; }
.primary-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; }
.primary-button:disabled { background: #98a2b3; cursor: not-allowed; }
.file-input { display: grid; gap: 8px; width: fit-content; color: #344054; font-size: 14px; font-weight: 650; }
.file-hint { margin: 0; color: #98a2b3; font-size: 12.5px; }
.selected-file, .import-status, .empty-hint { margin: 0; color: #475467; font-size: 14px; }
/* 粘贴导入 */
.paste-textarea :deep(textarea) { font-family: ui-monospace, 'SF Mono', Consolas, monospace; font-size: 13px; line-height: 1.5; }
.paste-actions { display: flex; align-items: center; gap: 14px; }
.import-error { margin: 0; padding: 10px 12px; color: #b42318; background: #fef3f2; border-radius: 8px; font-size: 14px; }
/* 手动录入 */
.from-file-banner { margin-bottom: 4px; }
.manual-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.field-label { color: #344054; font-size: 13px; font-weight: 650; }
.manual-hint { color: #98a2b3; font-size: 12.5px; }
.manual-table { width: 100%; }
.batch-card, .validation-card, .manual-table :deep(input), .win-cell { font-variant-numeric: tabular-nums; }
.store-name-input { width: 100%; }
.store-sug { display: flex; flex-direction: column; line-height: 1.35; padding: 2px 0; }
.store-sug strong { color: #101828; font-size: 13px; }
.store-sug span { color: #98a2b3; font-size: 12px; }
.manual-table :deep(.missing .el-input__wrapper) { box-shadow: 0 0 0 1px #f04438 inset; }
.win-cell { display: flex; align-items: center; gap: 6px; }
.manual-actions { display: flex; align-items: center; gap: 14px; }
.filled-count { color: #475467; font-size: 13px; }
/* 已导入订单明细表 */
.imported-detail { display: grid; gap: 8px; }
.detail-head { display: flex; align-items: baseline; gap: 10px; }
.detail-head strong { color: #027a48; font-size: 15px; } .detail-head span { color: #667085; font-size: 13px; }
.detail-table { width: 100%; } .detail-table :deep(.cell) { font-variant-numeric: tabular-nums; }
@media (max-width: 720px) { .step-panel { padding: 18px; } .panel-heading { flex-direction: column; } }
</style>
