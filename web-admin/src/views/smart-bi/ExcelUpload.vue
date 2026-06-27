<script setup lang="ts">
/**
 * SmartBI Excel 上传页面
 * 支持 Excel 文件上传、自动解析、AI分析和结果展示
 * 连接 Python SmartBI 服务获取真实分析结果
 */
import { ref, computed, reactive, nextTick, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import {
  uploadAndAnalyze,
  // Step 2D+2-5 (Apr 20 2026): async path now does full streaming persist
  // (smart_bi_dynamic_data + field_definitions) — wiring it in for files >50MB
  // so customers get 200K+ rows without the sync-path 65K cap and without
  // blocking the browser tab.
  uploadFileAsync,
  pollUploadStatus,
  confirmUploadAndPersist,
  detectTableRegions,
  type AnalysisResult,
  type AIInsightData,
  type KPIData,
  type ChartConfig,
  type TableRegion,
} from '@/api/smartbi';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadFile, UploadUserFile } from 'element-plus';
import {
  Upload,
  Document,
  Check,
  Close,
  Refresh,
  Download,
  ArrowRight,
  ChatDotRound,
  DataAnalysis,
  TrendCharts,
  CircleCheck,
  Warning
} from '@element-plus/icons-vue';
import { KPICard, AIInsightPanel } from '@/components/smartbi';
import { usePermissionStore } from '@/store/modules/permission';
import echarts from '@/utils/echarts';

const router = useRouter();
const rootRef = ref<HTMLDivElement>();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// 上传步骤
const currentStep = ref(0);
const steps = [
  { title: '上传文件', description: '选择 Excel 文件' },
  { title: '解析结果', description: '预览解析数据' },
  { title: '分析结果', description: 'AI 分析洞察' },
  { title: '保存确认', description: '保存并继续' }
];

// 分析结果
const analysisResult = ref<AnalysisResult | null>(null);
const kpiData = ref<KPIData[]>([]);
const chartConfigs = ref<ChartConfig[]>([]);
const chartInstances = new Map<string, echarts.ECharts>();

// 解析后的原始数据 (用于传递给 Chat)
const parsedData = ref<unknown[]>([]);
const parsedFields = ref<Array<{ original: string; standard: string }>>([]);
const parsedTableType = ref<string>('');
// Bug #43: uploadId from /upload-and-analyze so /upload/confirm can skip
// re-persistence and only update field definitions. Without this, the server
// would re-persist from trimmed 50-row previewData instead of the original file.
const currentUploadId = ref<number | null>(null);

// 上传状态
const uploading = ref(false);
const uploadProgress = ref(0);
// P0-6 (Apr 20): 上传阶段显示 — 'idle' | 'uploading' (字节 0-100%) | 'parsing' (后端解析, 无百分比) | 'done'
const uploadStage = ref<'idle' | 'uploading' | 'parsing' | 'done'>('idle');
const fileList = ref<UploadUserFile[]>([]);

// Bug #25b (2026-04-18): 多表堆叠 — 区域选择 dialog
const regionDialogVisible = ref(false);
const detectedRegions = ref<TableRegion[]>([]);
const pendingFile = ref<File | null>(null);
const selectedRegionIndex = ref<number | null>(null);
const regionDetecting = ref(false);

// 数据类型
interface DataType {
  value: string;
  label: string;
  description: string;
  requiredFields: string[];
}

const dataTypes: DataType[] = [
  {
    value: 'sales',
    label: '销售数据',
    description: '包含订单、客户、销售额等销售相关数据',
    requiredFields: ['日期', '客户名称', '产品名称', '数量', '金额']
  },
  {
    value: 'finance',
    label: '财务数据',
    description: '包含收入、成本、利润等财务相关数据',
    requiredFields: ['日期', '科目', '金额', '类型']
  },
  {
    value: 'inventory',
    label: '库存数据',
    description: '包含库存数量、进出库记录等仓储数据',
    requiredFields: ['日期', '产品名称', '数量', '操作类型']
  },
  {
    value: 'customer',
    label: '客户数据',
    description: '包含客户信息、联系方式等基础数据',
    requiredFields: ['客户名称', '联系人', '电话', '地址']
  },
  {
    value: 'product',
    label: '产品数据',
    description: '包含产品信息、价格、规格等基础数据',
    requiredFields: ['产品名称', '类别', '规格', '单价']
  }
];

const selectedDataType = ref<string>('');

// 字段映射
interface FieldMapping {
  sourceField: string;
  targetField: string;
  matched: boolean;
  sample: string;
}

const fieldMappings = ref<FieldMapping[]>([]);

// 解析结果
interface ParseResult {
  totalRows: number;
  validRows: number;
  errorRows: number;
  headers: string[];
  sampleData: Record<string, string>[];
  errors: string[];
}

const parseResult = ref<ParseResult>({
  totalRows: 0,
  validRows: 0,
  errorRows: 0,
  headers: [],
  sampleData: [],
  errors: []
});

// 导入结果
interface ImportResult {
  success: boolean;
  imported: number;
  failed: number;
  errors: string[];
}

const importResult = ref<ImportResult | null>(null);
// TODO: handleImport — 后端导入 API 尚未实现，已移除假实现 (Round 4.5)

// 目标字段选项
const targetFieldOptions = computed(() => {
  const selectedType = dataTypes.find(t => t.value === selectedDataType.value);
  if (!selectedType) return [];
  return selectedType.requiredFields.map(field => ({
    value: field,
    label: field
  }));
});

const visiblePreviewHeaders = computed(() =>
  parseResult.value.headers.filter((header) =>
    canViewPrice.value || getColumnTypeBadge(header).label !== '楼',
  ),
);

// ==================== 字段类型检测与确认 ====================

type FieldTypeName = '文本' | '数字' | '日期' | '百分比' | '金额';

interface FieldTypeConfirmation {
  fieldName: string;
  detectedType: FieldTypeName;
  confirmedType: FieldTypeName;
  sampleValues: string[];
  confirmed: boolean;
}

const fieldTypeOptions: FieldTypeName[] = ['文本', '数字', '日期', '百分比', '金额'];
const fieldTypeConfirmations = ref<FieldTypeConfirmation[]>([]);

/** Auto-detect field type from sample values */
function detectFieldType(values: string[]): FieldTypeName {
  const nonEmpty = values.filter(v => v !== null && v !== undefined && String(v).trim() !== '');
  if (nonEmpty.length === 0) return '文本';

  const stringValues = nonEmpty.map(v => String(v).trim());

  // Check percentage — contains %
  const percentCount = stringValues.filter(v => /[\d.]+\s*%/.test(v)).length;
  if (percentCount >= Math.ceil(stringValues.length * 0.6)) return '百分比';

  // Check currency — contains currency symbols or patterns like ¥123, $123, 123.00元
  const currencyCount = stringValues.filter(v =>
    /^[¥$€£][\s]?[\d,]+(\.\d+)?$/.test(v) ||
    /^[\d,]+(\.\d{2})\s*(元|万元|千元)$/.test(v)
  ).length;
  if (currencyCount >= Math.ceil(stringValues.length * 0.6)) return '金额';

  // Check date patterns — YYYY-MM-DD, YYYY/MM/DD, DD/MM/YYYY, etc.
  const dateCount = stringValues.filter(v =>
    /^\d{4}[-/]\d{1,2}[-/]\d{1,2}/.test(v) ||
    /^\d{1,2}[-/]\d{1,2}[-/]\d{4}/.test(v) ||
    /^\d{4}年\d{1,2}月\d{1,2}日/.test(v) ||
    /^\d{4}[-/]\d{1,2}$/.test(v) // YYYY-MM
  ).length;
  if (dateCount >= Math.ceil(stringValues.length * 0.6)) return '日期';

  // Check numeric — all values are numbers (allow commas as thousand separators)
  const numericCount = stringValues.filter(v => {
    const cleaned = v.replace(/,/g, '');
    return /^-?[\d]+(\.\d+)?$/.test(cleaned);
  }).length;
  if (numericCount >= Math.ceil(stringValues.length * 0.6)) return '数字';

  return '文本';
}

/** Build field type confirmations from parsed data */
function buildFieldTypeConfirmations() {
  const data = parseResult.value.sampleData;
  const headers = parseResult.value.headers;

  fieldTypeConfirmations.value = headers.map(header => {
    // Collect non-empty sample values from all preview data
    const allValues = data
      .map(row => row[header])
      .filter(v => v !== null && v !== undefined && String(v).trim() !== '')
      .map(v => String(v));
    const sampleValues = allValues.slice(0, 3);
    const detected = detectFieldType(allValues);

    return {
      fieldName: header,
      detectedType: detected,
      confirmedType: detected,
      sampleValues,
      confirmed: false
    };
  });
}

/** Confirm all fields at once */
function confirmAllFields() {
  fieldTypeConfirmations.value.forEach(f => {
    f.confirmed = true;
  });
  ElMessage.success('已确认全部字段类型');
}

/** Re-run auto-detection */
function redetectFieldTypes() {
  buildFieldTypeConfirmations();
  ElMessage.info('已重新检测字段类型');
}

/** Handle user changing a field type via dropdown */
function handleFieldTypeChange(index: number) {
  const field = fieldTypeConfirmations.value[index];
  if (field) {
    field.confirmed = true;
  }
}

/** Are all fields confirmed? */
const allFieldsConfirmed = computed(() => {
  if (fieldTypeConfirmations.value.length === 0) return false;
  return fieldTypeConfirmations.value.every(f => f.confirmed);
});

// ==================== End field type detection ====================

// 处理文件上传
async function handleUpload(file: UploadFile) {
  if (!file.raw) return;

  const validTypes = [
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-excel',
    'text/csv',
    'application/csv',
  ];
  // Also allow by extension (some browsers report different MIME for CSV)
  const ext = file.name?.toLowerCase().split('.').pop();
  const validExts = ['xlsx', 'xls', 'csv'];

  if (!validTypes.includes(file.raw.type) && !validExts.includes(ext || '')) {
    ElMessage.error('请上传 Excel 或 CSV 文件 (.xlsx, .xls, .csv)');
    return false;
  }

  if (file.raw.size > 500 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 500MB');
    return false;
  }

  fileList.value = [file];

  // Bug #25b: preview regions first; CSV skips the dialog (rarely stacked).
  if (ext !== 'csv') {
    regionDetecting.value = true;
    try {
      const regionResp = await detectTableRegions(file.raw);
      if (regionResp.success && regionResp.totalRegions >= 2) {
        detectedRegions.value = regionResp.regions;
        pendingFile.value = file.raw;
        selectedRegionIndex.value = 0;
        regionDialogVisible.value = true;
        return false; // wait for user to confirm region; continueUpload fires next
      }
    } catch (error) {
      console.warn('Region detection failed, falling back to full-file parse:', error);
    } finally {
      regionDetecting.value = false;
    }
  }

  await runUploadAndAnalyze(file.raw);
  return false;
}

async function runUploadAndAnalyze(rawFile: File, region?: TableRegion) {
  // Step 2-5 (Apr 20 2026): branch >50MB files to async streaming persist
  // path. Sync path is still capped at ~65K rows due to memory; async does
  // full 200K+ via chunked read + execute_values bulk insert. UX win: tab
  // can close mid-parse, polling reconnects.
  const ASYNC_THRESHOLD_MB = 50;
  if (rawFile.size > ASYNC_THRESHOLD_MB * 1024 * 1024) {
    return runUploadAsync(rawFile, region);
  }
  uploading.value = true;
  uploadProgress.value = 0;
  uploadStage.value = 'uploading';  // P0-6: 'uploading' (0-100 字节进度) → 'parsing' (后端解析, 无百分比)

  try {
    // P0-6 (Apr 20): 真上传进度 — axios onUploadProgress 报实际字节, 不再假 90%.
    // 263MB+ 文件能看到准确 0→100% 上传进度, 传完切换到 'parsing' 阶段.
    const uploadOptions = {
      ...(region ? { selectedRegionStart: region.startRow, selectedRegionEnd: region.endRow } : {}),
      onUploadProgress: (percent: number, _loaded: number, _total: number) => {
        uploadProgress.value = percent;
        if (percent >= 100) {
          uploadStage.value = 'parsing';  // 字节传完, 后端开始解析
        }
      }
    };
    const result = await uploadAndAnalyze(rawFile, uploadOptions);

    uploadProgress.value = 100;
    uploadStage.value = 'done';

    if (!result.success) {
      // Apr 28 2026: axios interceptor (request.ts:168 showRichError) already
      // emits the rich actionHint toast for any success=false response.
      // uploadAndAnalyze.catch then converts it to {success:false, error: msg}.
      // Re-firing here = duplicate toast (3ms apart, captured during qa-prompt
      // v2.4 deep E2E Phase 4). Skip to keep one toast per error event.
      uploading.value = false;
      return false;
    }

    // 保存解析结果 — show ALL preview rows, not just 5
    const pr = result.parseResult;
    parseResult.value = {
      totalRows: pr.row_count || 0,
      validRows: pr.row_count || 0,
      errorRows: 0,
      headers: pr.headers || [],
      sampleData: (pr.preview_data || []) as Record<string, string>[],
      errors: []
    };

    // 保存原始数据用于 Chat
    parsedData.value = pr.preview_data || [];
    parsedFields.value = pr.field_mappings || pr.headers?.map((h: string) => ({ original: h, standard: h })) || [];
    parsedTableType.value = pr.table_type || '';

    // 保存分析结果
    if (result.analysis) {
      analysisResult.value = result.analysis;
      kpiData.value = result.analysis.kpis || [];
    }

    // 保存图表配置
    chartConfigs.value = result.chartRecommendations || [];

    // Bug #43: remember uploadId so save step reuses pre-persisted rows.
    currentUploadId.value = result.uploadId ?? null;

    // Build field type confirmations from parsed data
    buildFieldTypeConfirmations();

    currentStep.value = 1;
    ElMessage.success('文件解析成功');

    // A-fix (Apr 17 2026): 后端 requiresConfirmation=true 时保留在步骤 2,
    // 让用户 review 字段映射后再进分析. 旧代码硬编码 analysis.success=true
    // 导致 confidence<阈值的文件也跳过确认,在步骤 3 显示"暂无分析结论".
    if (result.requiresConfirmation) {
      ElMessage.warning('字段映射信心较低,请在下方确认后再查看分析结果');
    } else if (result.analysis && result.analysis.success) {
      currentStep.value = 2;
      await nextTick();
      renderCharts();
    }
  } catch (error) {
    console.error('文件上传失败:', error);
    ElMessage.error('文件上传失败: ' + (error instanceof Error ? error.message : '未知错误'));
  } finally {
    // P0-6: no more fake progressInterval; axios onUploadProgress drives uploadProgress now.
    uploading.value = false;
    uploadStage.value = 'idle';
  }

  return false;
}

/**
 * Step 2-5 (Apr 20 2026): async streaming upload path for files >50MB.
 *
 * Calls Python /auto-parse-async directly (bypasses Java sync flow which
 * is memory-capped at ~65K rows). Returns uploadId immediately, then polls
 * /auto-parse-status every 3s until COMPLETED/FAILED.
 *
 * Memory bounded on server (chunked CSV read + execute_values bulk insert)
 * so 263MB × 200K × 231 fits. Customer gets full-fidelity analysis on large
 * POS detail exports.
 */
async function runUploadAsync(rawFile: File, region?: TableRegion) {
  uploading.value = true;
  uploadProgress.value = 0;
  uploadStage.value = 'uploading';

  // #840: no F001 fallback (multi-tenant leak). Empty string → backend 4xx → user sees error.
  const authStore = useAuthStore();
  const factoryId =
    (authStore.user as { factoryId?: string } | null)?.factoryId || '';

  try {
    // Phase A: transfer bytes to server (progress 0-100%)
    const uploadResult = await uploadFileAsync(rawFile, factoryId, {
      maxRows: 500000,
      ...(region
        ? {
            selectedRegionStart: region.startRow,
            selectedRegionEnd: region.endRow,
          }
        : {}),
      onUploadProgress: (percent: number) => {
        uploadProgress.value = percent;
        if (percent >= 100) {
          uploadStage.value = 'parsing';
        }
      },
    });

    if (!uploadResult.success || !uploadResult.uploadId) {
      // Apr 28 2026: axios interceptor already emitted toast for success=false.
      // Same pattern as runUploadAndAnalyze (sync path) — skip duplicate.
      return false;
    }

    ElMessage.info(
      `大文件异步上传: ${Math.round(rawFile.size / 1024 / 1024)}MB 已接收, 后台处理中 (可切换页面,稍后回来).`,
    );

    // Phase B: poll status until terminal state.
    const final = await pollUploadStatus(uploadResult.uploadId, {
      intervalMs: 3000,
      timeoutMs: 20 * 60 * 1000, // 20 min for very large files
      onProgress: (s) => {
        // Keep UI in 'parsing' stage; rows count grows as batches commit.
        uploadStage.value = 'parsing';
        if (typeof s.rowCount === 'number' && s.rowCount > 0) {
          // Re-purpose progress bar as a "rows-so-far" indicator.
          // We don't know totalRows yet, so keep bar at 100% during parsing.
        }
      },
    });

    uploadProgress.value = 100;
    uploadStage.value = 'done';

    if (final.status !== 'COMPLETED') {
      ElMessage.error(`解析失败: ${final.error || '未知错误'}`);
      return false;
    }

    // Phase C: build parseResult from status response.
    type AsyncStatusPayload = typeof final & {
      headers?: string[];
      previewData?: Record<string, unknown>[];
    };
    const payload = final as AsyncStatusPayload;
    const headers: string[] = payload.headers || [];
    const previewData: Record<string, unknown>[] = payload.previewData || [];
    const fmappings = (payload.fieldMappings as Array<{
      originalColumn: string;
      standardField: string;
    }> | null) || [];

    parseResult.value = {
      totalRows: final.rowCount || 0,
      validRows: final.rowCount || 0,
      errorRows: 0,
      headers,
      sampleData: previewData as Record<string, string>[],
      errors: [],
    };
    parsedData.value = previewData;
    parsedFields.value = fmappings.map((m) => ({
      original: m.originalColumn,
      standard: m.standardField,
    }));
    parsedTableType.value = final.detectedTableType || '';
    currentUploadId.value = final.uploadId;

    buildFieldTypeConfirmations();
    currentStep.value = 1;
    ElMessage.success(
      `大文件解析完成: ${final.rowCount} 行 × ${final.columnCount} 列 (流式持久化,已入库)`,
    );
  } catch (error) {
    console.error('异步上传失败:', error);
    ElMessage.error(
      '异步上传失败: ' + (error instanceof Error ? error.message : '未知错误'),
    );
  } finally {
    uploading.value = false;
    uploadStage.value = 'idle';
  }

  return false;
}

// Bug #25b: confirm region selection from dialog, then continue uploading.
// Bug #25b patch: distinguish user-confirm close from user-cancel close.
// el-dialog @close fires for BOTH paths; without this flag cancel logic would
// clear fileList after confirm, breaking the downstream save step.
const regionConfirmed = ref(false);

async function confirmRegionSelection() {
  if (selectedRegionIndex.value === null || !pendingFile.value) {
    ElMessage.warning('请选择一个数据区域');
    return;
  }
  const region = detectedRegions.value[selectedRegionIndex.value];
  regionConfirmed.value = true;
  regionDialogVisible.value = false;
  const fileToUpload = pendingFile.value;
  pendingFile.value = null;
  await runUploadAndAnalyze(fileToUpload, region);
}

function cancelRegionSelection() {
  regionDialogVisible.value = false;
  if (regionConfirmed.value) {
    regionConfirmed.value = false;
    return;  // confirm path already consumed state
  }
  pendingFile.value = null;
  detectedRegions.value = [];
  selectedRegionIndex.value = null;
  fileList.value = [];
}

// 渲染所有图表
function renderCharts() {
  chartConfigs.value.forEach((config, index) => {
    const chartId = `analysis-chart-${index}`;
    const chartDom = document.getElementById(chartId);
    if (!chartDom || !config.option) return;

    // 销毁旧图表
    const oldChart = chartInstances.get(chartId);
    if (oldChart) {
      oldChart.dispose();
    }

    const chart = echarts.init(chartDom, 'cretas');
    chartInstances.set(chartId, chart);
    chart.setOption(config.option as echarts.EChartsOption);
  });
}

// 跳转到 Chat 页面继续提问
function goToChat() {
  // 通过 query 参数传递上下文标识
  router.push({
    path: '/smart-bi/query',
    query: {
      hasContext: 'true',
      tableType: parsedTableType.value
    }
  });
}

// 保存分析结果 (使用现有 Java 端点)
async function handleSaveAnalysis() {
  if (!fileList.value[0]) return;

  try {
    // 使用现有的 /upload/confirm 端点持久化
    const saveResponse = await confirmUploadAndPersist({
      // Bug #43: pass uploadId so backend skips 50-row-trim re-persist and only
      // updates field definitions. Rows were already persisted during upload.
      uploadId: currentUploadId.value ?? undefined,
      parseResponse: {
        fileName: fileList.value[0].name || 'unknown.xlsx',
        sheetName: 'Sheet1',
        headers: parseResult.value.headers,
        rowCount: parseResult.value.totalRows,
        columnCount: parseResult.value.headers.length,
        previewData: parsedData.value,
        tableType: parsedTableType.value
      },
      confirmedMappings: Object.fromEntries(
        parsedFields.value.map(f => [f.original, f.standard])
      ),
      dataType: parsedTableType.value || 'general',
      saveRawData: true,
      generateChart: chartConfigs.value.length > 0
    });

    if (saveResponse && saveResponse.success === false) {
      ElMessage.error(saveResponse.message || '保存失败');
      return;
    }

    ElMessage.success('分析结果已保存');
    importResult.value = {
      success: true,
      imported: parseResult.value.validRows,
      failed: parseResult.value.errorRows,
      errors: parseResult.value.errors
    };
    currentStep.value = 3;
  } catch (error) {
    console.error('保存失败:', error);
    ElMessage.error('保存分析结果失败');
  }
}

// 窗口大小变化时调整图表
let resizeObserver: ResizeObserver | null = null;
function handleChartResize() {
  chartInstances.forEach(chart => chart.resize());
}
onMounted(() => {
  window.addEventListener('resize', handleChartResize);
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleChartResize);
    resizeObserver.observe(rootRef.value);
  }
});

// 清理图表实例
onUnmounted(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  window.removeEventListener('resize', handleChartResize);
  chartInstances.forEach(chart => chart.dispose());
  chartInstances.clear();
});

// 选择数据类型
function handleSelectDataType(type: string) {
  selectedDataType.value = type;

  // 自动匹配字段
  const selectedType = dataTypes.find(t => t.value === type);
  if (!selectedType) return;

  fieldMappings.value = parseResult.value.headers.map(header => {
    const matchedField = selectedType.requiredFields.find(
      field => field === header || header.includes(field) || field.includes(header)
    );
    return {
      sourceField: header,
      targetField: matchedField || '',
      matched: !!matchedField,
      sample: parseResult.value.sampleData[0]?.[header] || ''
    };
  });
}

// 进入下一步
function nextStep() {
  if (currentStep.value === 1 && !selectedDataType.value) {
    ElMessage.warning('请选择数据类型');
    return;
  }

  if (currentStep.value === 2) {
    const requiredFields = dataTypes.find(t => t.value === selectedDataType.value)?.requiredFields || [];
    const mappedFields = fieldMappings.value.filter(m => m.targetField).map(m => m.targetField);
    const missingFields = requiredFields.filter(f => !mappedFields.includes(f));

    if (missingFields.length > 0) {
      ElMessage.warning(`缺少必填字段映射: ${missingFields.join(', ')}`);
      return;
    }
  }

  currentStep.value++;
}

// 返回上一步
function prevStep() {
  if (currentStep.value > 0) {
    currentStep.value--;
  }
}

// TODO: handleImport — 后端导入 API 尚未实现，已移除假实现 (Round 4.5)

// 重新开始
function handleReset() {
  currentStep.value = 0;
  fileList.value = [];
  selectedDataType.value = '';
  fieldMappings.value = [];
  fieldTypeConfirmations.value = [];
  parseResult.value = {
    totalRows: 0,
    validRows: 0,
    errorRows: 0,
    headers: [],
    sampleData: [],
    errors: []
  };
  importResult.value = null;
}

// 下载模板
function handleDownloadTemplate() {
  if (!selectedDataType.value) {
    ElMessage.warning('请先选择数据类型');
    return;
  }
  ElMessage.info('模板下载功能开发中...');
}

/** Column type label mapping for preview table header badges */
function getColumnTypeBadge(header: string): { label: string; type: 'info' | 'success' | 'warning' | 'danger' | '' } {
  const conf = fieldTypeConfirmations.value.find(f => f.fieldName === header);
  if (!conf) return { label: '', type: 'info' };
  const typeMap: Record<FieldTypeName, { label: string; type: 'info' | 'success' | 'warning' | 'danger' | '' }> = {
    '文本': { label: 'Aa', type: 'info' },
    '数字': { label: '#', type: 'success' },
    '日期': { label: 'D', type: 'warning' },
    '百分比': { label: '%', type: '' },
    '金额': { label: '¥', type: 'danger' }
  };
  return typeMap[conf.confirmedType] || { label: '', type: 'info' };
}
</script>

<template>
  <div ref="rootRef" class="excel-upload-page">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/smart-bi' }">Smart BI</el-breadcrumb-item>
          <el-breadcrumb-item>数据导入</el-breadcrumb-item>
        </el-breadcrumb>
        <h1>Excel 数据导入</h1>
      </div>
      <div class="header-right">
        <el-button :icon="Download" @click="handleDownloadTemplate">下载模板</el-button>
        <el-button :icon="Refresh" @click="handleReset">重新开始</el-button>
      </div>
    </div>

    <!-- 步骤条 -->
    <el-card class="steps-card">
      <el-steps :active="currentStep" finish-status="success" align-center>
        <el-step
          v-for="(step, index) in steps"
          :key="index"
          :title="step.title"
          :description="step.description"
        />
      </el-steps>
    </el-card>

    <!-- 步骤内容 -->
    <el-card class="content-card">
      <!-- 步骤 1: 上传文件 -->
      <div v-show="currentStep === 0" class="step-content">
        <el-upload
          class="upload-area"
          drag
          action=""
          :auto-upload="false"
          :file-list="fileList"
          :on-change="handleUpload"
          :limit="1"
          accept=".xlsx,.xls,.csv"
        >
          <el-icon class="upload-icon" v-if="!uploading && !regionDetecting"><Upload /></el-icon>
          <el-progress
            v-else-if="uploading"
            type="circle"
            :percentage="uploadProgress"
            :width="80"
          />
          <el-icon v-else class="upload-icon is-loading"><Refresh /></el-icon>
          <div class="upload-text">
            <template v-if="!uploading && !regionDetecting">
              <p class="main-text">将文件拖到此处，或<em>点击上传</em></p>
              <p class="sub-text">支持 .xlsx、.xls 和 .csv 格式，文件大小不超过 500MB</p>
            </template>
            <template v-else-if="regionDetecting">
              <p class="main-text">正在检测数据区域...</p>
            </template>
            <template v-else>
              <!-- P0-6 (Apr 20): 真实阶段提示, 不再全程"正在解析" -->
              <p class="main-text" v-if="uploadStage === 'uploading'">
                正在上传文件 ({{ uploadProgress }}%)
              </p>
              <p class="main-text" v-else>正在解析文件...</p>
              <p class="sub-text" v-if="uploadStage === 'parsing'">
                上传完成, 后端正在解析大文件可能需要几分钟
              </p>
            </template>
          </div>
        </el-upload>

        <div class="upload-tips">
          <h4>上传说明</h4>
          <ul>
            <li>支持 Excel 2007+ 格式 (.xlsx) 和 Excel 97-2003 格式 (.xls)</li>
            <li>第一行应为列标题，从第二行开始为数据</li>
            <li>建议使用系统提供的模板上传数据</li>
            <li>日期格式推荐使用 YYYY-MM-DD (如 2026-01-15)</li>
          </ul>
        </div>
      </div>

      <!-- 步骤 2: 解析结果预览 -->
      <div v-show="currentStep === 1" class="step-content">
        <div class="parse-summary">
          <div class="summary-stats">
            <div class="stat-item">
              <div class="stat-value">{{ parseResult.totalRows }}</div>
              <div class="stat-label">总行数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ parseResult.headers.length }}</div>
              <div class="stat-label">列数</div>
            </div>
            <div class="stat-item" v-if="parsedTableType">
              <div class="stat-value type-badge">{{ parsedTableType }}</div>
              <div class="stat-label">识别类型</div>
            </div>
          </div>

          <div class="field-list">
            <h4>识别到的字段</h4>
            <div class="field-tags">
              <el-tag v-for="field in parseResult.headers" :key="field" type="info">
                {{ field }}
              </el-tag>
            </div>
          </div>

          <!-- 字段类型确认区域 -->
          <div v-if="fieldTypeConfirmations.length > 0" class="field-confirmation">
            <div class="field-confirmation-header">
              <h4>字段类型确认</h4>
              <div class="field-confirmation-actions">
                <el-button size="small" @click="redetectFieldTypes">
                  <el-icon><Refresh /></el-icon>
                  重新检测
                </el-button>
                <el-button size="small" type="primary" @click="confirmAllFields">
                  <el-icon><Check /></el-icon>
                  全部确认
                </el-button>
              </div>
            </div>
            <el-table :data="fieldTypeConfirmations" stripe border size="small" class="field-type-table">
              <el-table-column label="字段名" prop="fieldName" min-width="120">
                <template #default="{ row }">
                  <span class="field-name-cell">{{ row.fieldName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="检测类型" min-width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.detectedType === '数字' ? 'success' : row.detectedType === '日期' ? 'warning' : row.detectedType === '金额' ? 'danger' : 'info'">
                    {{ row.detectedType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="确认类型" min-width="140">
                <template #default="{ row, $index }">
                  <el-select
                    v-model="row.confirmedType"
                    size="small"
                    @change="handleFieldTypeChange($index)"
                    style="width: 100%"
                  >
                    <el-option
                      v-for="opt in fieldTypeOptions"
                      :key="opt"
                      :label="opt"
                      :value="opt"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="样本值" min-width="200">
                <template #default="{ row }">
                  <div class="sample-values-cell">
                    <el-tag
                      v-for="(val, i) in row.sampleValues"
                      :key="i"
                      size="small"
                      type="info"
                      effect="plain"
                      class="sample-tag"
                    >
                      {{ val }}
                    </el-tag>
                    <span v-if="row.sampleValues.length === 0" class="no-sample">--</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80" align="center">
                <template #default="{ row }">
                  <el-icon v-if="row.confirmed && row.confirmedType === row.detectedType" class="type-status-icon confirmed">
                    <CircleCheck />
                  </el-icon>
                  <el-icon v-else-if="row.confirmed && row.confirmedType !== row.detectedType" class="type-status-icon changed">
                    <Warning />
                  </el-icon>
                  <span v-else class="type-status-icon pending">--</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="field-confirmation-hint">
              <span v-if="allFieldsConfirmed" class="hint-success">
                <el-icon><CircleCheck /></el-icon>
                全部字段已确认
              </span>
              <span v-else class="hint-pending">
                请确认或调整各字段的数据类型，确认后可继续查看分析结果
              </span>
            </div>
          </div>

          <!-- 数据预览表格 -->
          <div class="preview-table">
            <h4>
              数据预览
              <el-tag size="small" type="info" class="row-count-badge">
                {{ parseResult.sampleData.length }} 行
              </el-tag>
            </h4>
            <el-table
              :data="parseResult.sampleData"
              stripe
              border
              size="small"
              max-height="400"
              class="enhanced-preview-table"
            >
              <el-table-column
                v-for="header in visiblePreviewHeaders"
                :key="header"
                :prop="header"
                min-width="120"
                sortable
              >
                <template #header>
                  <div class="column-header-cell">
                    <span class="column-header-text">{{ header }}</span>
                    <el-tag
                      v-if="getColumnTypeBadge(header).label"
                      size="small"
                      :type="getColumnTypeBadge(header).type"
                      effect="plain"
                      class="column-type-badge"
                    >
                      {{ getColumnTypeBadge(header).label }}
                    </el-tag>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="step-actions">
          <el-button @click="handleReset">重新上传</el-button>
          <el-button
            type="primary"
            :disabled="!allFieldsConfirmed"
            @click="currentStep = 2"
          >
            查看分析结果
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>

      <!-- 步骤 3: AI 分析结果 -->
      <div v-show="currentStep === 2" class="step-content analysis-result">
        <!-- KPI 卡片 -->
        <div v-if="kpiData.length > 0" class="kpi-grid">
          <KPICard
            v-for="kpi in kpiData"
            :key="kpi.key"
            :title="kpi.title"
            :value="kpi.value"
            :unit="kpi.unit"
            :trend="kpi.trend"
            :trend-value="kpi.trendValue"
            :status="kpi.status"
          />
        </div>

        <!-- AI 洞察面板 -->
        <div v-if="analysisResult?.insights" class="insight-section">
          <AIInsightPanel
            :insight="analysisResult.insights"
            title="AI 分析洞察"
            :collapsible="true"
            :default-expanded="true"
          />
        </div>

        <!-- 文本分析结果 -->
        <div v-if="analysisResult?.answer" class="answer-section">
          <h4>
            <el-icon><DataAnalysis /></el-icon>
            分析结论
          </h4>
          <div class="answer-content">{{ analysisResult.answer }}</div>
        </div>

        <!-- 推荐图表 -->
        <div v-if="chartConfigs.length > 0" class="charts-section">
          <h4>
            <el-icon><TrendCharts /></el-icon>
            数据可视化
          </h4>
          <div class="charts-grid">
            <div
              v-for="(chart, index) in chartConfigs"
              :key="index"
              class="chart-item"
            >
              <div class="chart-title">{{ chart.title || `图表 ${index + 1}` }}</div>
              <div :id="`analysis-chart-${index}`" class="chart-container"></div>
            </div>
          </div>
        </div>

        <!-- 无分析结果时的提示 -->
        <div v-if="!analysisResult?.success && !kpiData.length" class="no-analysis">
          <el-icon :size="48" color="#909399"><DataAnalysis /></el-icon>
          <p>暂无 AI 分析结果</p>
          <p class="sub-text">您可以继续提问获取更多洞察</p>
        </div>

        <div class="step-actions">
          <el-button @click="currentStep = 1">返回预览</el-button>
          <el-button @click="goToChat">
            <el-icon><ChatDotRound /></el-icon>
            继续提问
          </el-button>
          <el-button type="primary" @click="handleSaveAnalysis">
            保存分析结果
          </el-button>
        </div>
      </div>

      <!-- Bug #25b: 多表堆叠 - 区域选择 dialog -->
      <el-dialog
        v-model="regionDialogVisible"
        title="请选择表范围"
        width="680px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        @close="cancelRegionSelection"
      >
        <div class="region-dialog-hint">
          检测到 <strong>{{ detectedRegions.length }}</strong> 个独立数据区域，请选择要解析的表：
        </div>
        <el-radio-group v-model="selectedRegionIndex" style="width: 100%">
          <div v-for="region in detectedRegions" :key="region.index" class="region-card">
            <el-radio :value="region.index" :label="region.index" size="large">
              <strong>区域 {{ region.index + 1 }}</strong>
              <span class="region-meta">
                (第 {{ region.startRow + 1 }}–{{ region.endRow + 1 }} 行,
                {{ region.previewCols.length }} 列, 约 {{ region.sampleRows }} 行数据)
              </span>
            </el-radio>
            <div class="region-preview">
              <div class="region-cols">
                <el-tag v-for="col in region.previewCols.slice(0, 8)" :key="col" size="small" type="info">
                  {{ col }}
                </el-tag>
                <span v-if="region.previewCols.length > 8" class="region-cols-more">
                  … 共 {{ region.previewCols.length }} 列
                </span>
              </div>
              <el-table
                v-if="region.previewData.length > 0"
                :data="region.previewData.slice(0, 3).map((row) => Object.fromEntries(row.map((v, i) => [String(i), v])))"
                size="small"
                border
                class="region-preview-table"
              >
                <el-table-column
                  v-for="(col, idx) in region.previewCols.slice(0, 6)"
                  :key="idx"
                  :label="col"
                  :prop="String(idx)"
                  min-width="90"
                />
              </el-table>
            </div>
          </div>
        </el-radio-group>
        <template #footer>
          <el-button @click="cancelRegionSelection">取消</el-button>
          <el-button
            type="primary"
            :disabled="selectedRegionIndex === null"
            @click="confirmRegionSelection"
          >
            确定
          </el-button>
        </template>
      </el-dialog>

      <!-- 步骤 4: 保存确认 -->
      <div v-show="currentStep === 3" class="step-content">
        <div class="save-result">
          <el-result
            icon="success"
            title="分析结果已保存"
          >
            <template #sub-title>
              <p>
                已解析 {{ parseResult.totalRows }} 行数据，
                识别到 {{ parseResult.headers.length }} 个字段
              </p>
              <p v-if="parsedTableType" class="table-type-info">
                数据类型: {{ parsedTableType }}
              </p>
            </template>
            <template #extra>
              <el-button type="primary" @click="goToChat">
                <el-icon><ChatDotRound /></el-icon>
                继续提问分析
              </el-button>
              <el-button @click="handleReset">上传新文件</el-button>
              <el-button @click="$router.push('/smart-bi')">返回驾驶舱</el-button>
            </template>
          </el-result>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.excel-upload-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;

  .header-left {
    h1 {
      margin: 12px 0 0;
      font-size: 20px;
      font-weight: 600;
    }
  }

  .header-right {
    display: flex;
    gap: 12px;
  }
}

.steps-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.content-card {
  border-radius: 8px;
  min-height: 500px;
  transition: box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  &:hover {
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  }
}

.step-content {
  padding: 20px;
}

// 上传区域
.upload-area {
  width: 100%;

  :deep(.el-upload-dragger) {
    width: 100%;
    height: 200px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    transition: border-color 0.2s ease, background-color 0.2s ease;
    &:hover {
      border-color: var(--el-color-primary, #2D8B57);
      background-color: var(--el-color-primary-light-9, #ecf5ff);
    }
  }

  .upload-icon {
    font-size: 48px;
    color: var(--el-text-color-secondary, #909399);
    margin-bottom: 16px;
  }

  .upload-text {
    .main-text {
      font-size: 16px;
      color: var(--el-text-color-regular, #606266);
      margin: 0 0 8px;

      em {
        color: var(--color-primary);
        font-style: normal;
      }
    }

    .sub-text {
      font-size: 13px;
      color: var(--el-text-color-secondary, #909399);
      margin: 0;
    }
  }
}

.upload-tips {
  margin-top: 24px;
  padding: 16px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 8px;

  h4 {
    margin: 0 0 12px;
    font-size: 14px;
    color: var(--el-text-color-primary, #303133);
  }

  ul {
    margin: 0;
    padding-left: 20px;

    li {
      font-size: 13px;
      color: var(--el-text-color-regular, #606266);
      line-height: 2;
    }
  }
}

// 数据类型选择
.data-type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.data-type-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border: 2px solid var(--el-border-color-light, #ebeef5);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;

  &:hover {
    border-color: var(--color-primary);
  }

  &.selected {
    border-color: var(--color-primary);
    background: var(--el-color-primary-light-9, #ecf5ff);
  }

  > .el-icon {
    font-size: 32px;
    color: var(--color-primary);
  }

  .type-info {
    flex: 1;

    .type-label {
      font-size: 16px;
      font-weight: 500;
      color: var(--el-text-color-primary, #303133);
      margin-bottom: 4px;
    }

    .type-desc {
      font-size: 13px;
      color: var(--el-text-color-secondary, #909399);
    }
  }

  .check-icon {
    font-size: 24px;
    color: var(--el-color-success, #67C23A);
  }
}

.required-fields {
  padding: 16px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 8px;
  margin-bottom: 24px;

  h4 {
    margin: 0 0 12px;
    font-size: 14px;
    color: var(--el-text-color-primary, #303133);
  }

  .field-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
}

// 字段映射
.mapping-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  .mapping-info {
    font-size: 14px;
    color: var(--el-text-color-regular, #606266);

    .matched {
      color: var(--el-color-success, #67C23A);
      margin-left: 16px;
    }
  }
}

.sample-data {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}

// 解析结果摘要
.parse-summary {
  .summary-stats {
    display: flex;
    gap: 24px;
    margin-bottom: 24px;

    .stat-item {
      flex: 1;
      text-align: center;
      padding: 20px;
      background: var(--el-fill-color-light, #f5f7fa);
      border-radius: 8px;
      transition: transform 0.2s ease, box-shadow 0.2s ease;
      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
      }

      .stat-value {
        font-size: 28px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
        color: var(--color-primary);

        &.type-badge {
          font-size: 16px;
          padding: 4px 12px;
          background: var(--el-color-primary-light-9, #ecf5ff);
          border-radius: 4px;
        }
      }

      .stat-label {
        font-size: 14px;
        color: var(--el-text-color-secondary, #909399);
        margin-top: 8px;
      }
    }
  }

  .field-list {
    margin-bottom: 24px;

    h4 {
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }
  }

  .field-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .preview-table {
    h4 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }

    .row-count-badge {
      font-weight: 400;
    }
  }
}

// 字段类型确认
.field-confirmation {
  margin-bottom: 24px;
  padding: 16px;
  background: var(--el-fill-color-lighter, #fafafa);
  border: 1px solid var(--el-border-color-lighter, #f0f0f0);
  border-radius: 8px;

  .field-confirmation-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    h4 {
      margin: 0;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }

    .field-confirmation-actions {
      display: flex;
      gap: 8px;
    }
  }

  .field-type-table {
    margin-bottom: 12px;
  }

  .field-name-cell {
    font-weight: 500;
  }

  .sample-values-cell {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;

    .sample-tag {
      max-width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .no-sample {
      color: var(--el-text-color-placeholder, #c0c4cc);
      font-size: 13px;
    }
  }

  .type-status-icon {
    font-size: 18px;

    &.confirmed {
      color: var(--el-color-success, #67C23A);
    }

    &.changed {
      color: var(--el-color-warning, #E6A23C);
    }

    &.pending {
      color: var(--el-text-color-placeholder, #c0c4cc);
      font-size: 13px;
    }
  }

  .field-confirmation-hint {
    font-size: 13px;
    padding-top: 4px;

    .hint-success {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--el-color-success, #67C23A);
    }

    .hint-pending {
      color: var(--el-text-color-secondary, #909399);
    }
  }
}

// Enhanced preview table
.enhanced-preview-table {
  .column-header-cell {
    display: inline-flex;
    align-items: center;
    gap: 6px;

    .column-header-text {
      font-weight: 500;
    }

    .column-type-badge {
      font-size: 11px;
      padding: 0 4px;
      height: 18px;
      line-height: 16px;
      border-radius: 3px;
    }
  }
}

// 分析结果页面
.analysis-result {
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 16px;
    margin-bottom: 24px;
  }

  .insight-section {
    margin-bottom: 24px;
  }

  .answer-section {
    margin-bottom: 24px;
    padding: 16px;
    background: var(--el-fill-color-light, #f5f7fa);
    border-radius: 8px;

    h4 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }

    .answer-content {
      font-size: 14px;
      line-height: 1.8;
      color: var(--el-text-color-regular, #606266);
      white-space: pre-wrap;
    }
  }

  .charts-section {
    margin-bottom: 24px;

    h4 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 16px;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }

    .charts-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(400px, 100%), 1fr));
      gap: 16px;
    }

    .chart-item {
      background: var(--el-bg-color, #fff);
      border: 1px solid var(--el-border-color-light, #ebeef5);
      border-radius: 8px;
      padding: 16px;

      .chart-title {
        font-size: 14px;
        font-weight: 500;
        color: var(--el-text-color-primary, #303133);
        margin-bottom: 12px;
      }

      .chart-container {
        height: 280px;
      }
    }
  }

  .no-analysis {
    text-align: center;
    padding: 60px 20px;
    color: var(--el-text-color-secondary, #909399);

    p {
      margin: 16px 0 0;
      font-size: 16px;
    }

    .sub-text {
      font-size: 14px;
      margin-top: 8px;
    }
  }
}

// 保存结果
.save-result {
  padding: 40px 0;

  .table-type-info {
    margin-top: 8px;
    color: var(--color-primary);
    font-weight: 500;
  }
}

// 导入预览
.import-preview {
  .preview-stats {
    display: flex;
    gap: 24px;
    margin-bottom: 24px;

    .stat-item {
      flex: 1;
      text-align: center;
      padding: 20px;
      background: var(--el-fill-color-light, #f5f7fa);
      border-radius: 8px;
      transition: transform 0.2s ease, box-shadow 0.2s ease;
      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
      }

      .stat-value {
        font-size: 32px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
        color: var(--el-text-color-primary, #303133);
      }

      .stat-label {
        font-size: 14px;
        color: var(--el-text-color-secondary, #909399);
        margin-top: 8px;
      }

      &.success .stat-value {
        color: var(--el-color-success, #67C23A);
      }

      &.error .stat-value {
        color: var(--el-color-danger, #F56C6C);
      }
    }
  }

  .error-list {
    margin-bottom: 24px;
    padding: 16px;
    background: var(--el-color-danger-light-9, #fef0f0);
    border-radius: 8px;

    h4 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--el-color-danger, #F56C6C);
    }

    ul {
      margin: 0;
      padding-left: 20px;

      li {
        font-size: 13px;
        color: var(--el-text-color-regular, #606266);
        line-height: 1.8;
      }
    }
  }

  .preview-table {
    margin-bottom: 24px;

    h4 {
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--el-text-color-primary, #303133);
    }
  }
}

.import-result {
  padding: 40px 0;
}

// 步骤操作
.step-actions {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-light, #ebeef5);
}

// Bug #25b: region selection dialog
.region-dialog-hint {
  margin-bottom: 12px;
  font-size: 14px;
  color: var(--el-text-color-regular, #606266);
}

.region-card {
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 6px;

  .region-meta {
    margin-left: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
    font-weight: normal;
  }

  .region-preview {
    margin-top: 12px;
    margin-left: 24px;
  }

  .region-cols {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 8px;
  }

  .region-cols-more {
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
    align-self: center;
  }

  .region-preview-table {
    margin-top: 6px;
  }
}

@keyframes cretas-rotate-360 {
  to { transform: rotate(360deg); }
}
.upload-icon.is-loading {
  animation: cretas-rotate-360 1s linear infinite;
}

// 响应式
@media (max-width: 768px) {
  .data-type-grid {
    grid-template-columns: 1fr;
  }

  .preview-stats {
    flex-direction: column;
  }

  .field-confirmation-header {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start !important;
  }
}
</style>
