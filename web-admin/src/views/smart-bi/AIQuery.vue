<script setup lang="ts">
/**
 * SmartBI AI 问答页面
 * 支持自然语言查询、快捷问题、对话历史和图表展示
 * 连接 Python SmartBI 服务获取真实分析结果
 */
import { ref, computed, onMounted, onUnmounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useChartResize } from '@/composables/useChartResize';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { chatAnalysis, chatAnalysisStream, getUploadHistory, deduplicateUploads, nl2sql, logFeedback, type AnalysisResult, type AIInsightData, type ChartConfig, type UploadHistoryItem, type NL2SQLResponse } from '@/api/smartbi';
import { executeIntent, fetchCachedXlsx, submitIntentFeedback } from '@/api/smartbi/intent-chat';
import { listFactoryTemplates, type FactoryTemplate } from '@/api/smartbi/materialized';
import { getTemplateTitle } from './composables/useTemplateMap';
import { pickDefaultDataSource } from './composables/useDataSourceSelect';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ChatDotRound,
  ChatRound,
  Promotion,
  Refresh,
  Delete,
  Download,
  User,
  Cpu,
  TrendCharts,
  Loading,
  Histogram,
  Location,
  Money,
  Coin,
  DataLine,
  PieChart,
  Warning,
  Sort,
  SetUp,
  DataAnalysis,
  Flag,
  Search,
  MagicStick,
  QuestionFilled
} from '@element-plus/icons-vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import echarts from '@/utils/echarts';
import { processEChartsOptions } from '@/utils/echarts-fmt';
import { AIInsightPanel } from '@/components/smartbi';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import MaterializedAnalysisPanel from '@/components/smart-bi/MaterializedAnalysisPanel.vue';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta } from './components/chartInsight';

// Render markdown content safely
function renderMarkdown(text: string): string {
  if (!text) return '';
  try {
    return DOMPurify.sanitize(marked(text) as string);
  } catch {
    return text;
  }
}

const route = useRoute();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// 输入框
const inputQuery = ref('');
const inputRef = ref<HTMLInputElement | null>(null);

// 对话历史
interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  chart?: {
    type: 'line' | 'bar' | 'pie';
    data: Record<string, unknown>;
  };
  chartConfig?: ChartConfig;
  // P2 综合分析 (2026-06-02): multi-chart answers (synthesis returns several
  // charts — 评价 pie / 门店 bar / VIP bar / 时段 line). Rendered as a stack of
  // chart containers below the answer. Single-chart tools keep using chartConfig.
  charts?: ChartConfig[];
  insights?: AIInsightData;
  table?: {
    columns: string[];
    data: Record<string, unknown>[];
  };
  sqlResult?: NL2SQLResponse;
  loading?: boolean;
  streaming?: boolean;
  // Fix 3 (Apr 23 2026): source tags so the UI can render a "深入分析"
  // CTA after pre-computed template answers. LLM fallback answers already
  // contain analysis, so no CTA for those.
  source?: string;
  templateCode?: string;
  // Phase 1 (Apr 23 2026): log id of the fallback log row (LLM answers only)
  logId?: number | null;
  // Java Tool-Skill answers do not use Python fallback logs. Keep enough context
  // to submit feedback to /ai-intents/feedback and feed the intent learning loop.
  feedbackTarget?: {
    source: 'java-intent';
    userInput: string;
    intentCode: string;
    sessionId?: string;
  };
  feedbackValue?: 1 | -1 | 0;  // local optimistic state
  feedbackPending?: boolean;  // Phase 1.5 Task 1 nit: prevent double-click POSTs
  // P2 guardrail (Apr 24 2026): backend flags numeric hallucination in
  // LLM output (e.g. "合计 3.4 亿" on a 36M dataset).
  warning?: string | null;
  // D1 (Apr 26 2026): set when backend response contains 25s timeout marker.
  // FE shows a "重试" button so user doesn't need to retype.
  truncated?: boolean;
  // Original query for retry button to re-send.
  origQuery?: string;
  // AI Chat Unification (2026-05-13): Tool produced a downloadable file.
  // Bubble shows a prominent blue "下载 Excel" button — user must click.
  downloadAttachment?: {
    cacheKey: string;
    filename: string;       // e.g. "收入管理报表_2025-03-01_2025-03-31.xlsx"
    factoryId: string;
    downloading?: boolean;  // local UI state during fetch
  };
  // P1 conversational-depth (2026-06-02): gold-tool answers carry these.
  // suggestedFollowups: clickable down-drill chips ({label shown, question sent}).
  // glossary: term → 通俗定义 for the expandable 字段说明 block + local meta-Q answers.
  // chartGuide: one-liner explaining how to read the chart.
  suggestedFollowups?: Array<{ label: string; question: string; ownerActionScenario?: string }>;
  glossary?: Record<string, string>;
  chartGuide?: string;
  // UI local state for the expandable 字段说明/怎么看图 block.
  depthExpanded?: boolean;
}

// ==================== ChartInsightProvider: runtime type→meta mapping ====================

const EXOTIC_CHART_TYPES_AIQUERY = new Set(['scatter', 'waterfall', 'radar', 'heatmap', 'gauge', 'funnel', 'sankey']);

/**
 * Build ChartWithMeta from a ChatMessage's chartConfig (single-chart case).
 * message.chartConfig.type: 'line'|'bar'|'pie'
 * message.chartConfig.option: ECharts option with xAxis.data and series[].data
 * Context: AI chat may serve factory or restaurant tenants — use 'factory' as safe domain default.
 */
function chartOptionTitle(opt: Record<string, unknown>, fallback = ''): string {
  const title = opt['title'];
  if (title && typeof title === 'object') {
    return String((title as Record<string, unknown>)['text'] ?? fallback);
  }
  return fallback;
}

function extractChartLabels(opt: Record<string, unknown>, chartType: string): string[] {
  const xAxis = opt['xAxis'];
  if (xAxis && typeof xAxis === 'object' && Array.isArray((xAxis as Record<string, unknown>)['data'])) {
    return ((xAxis as Record<string, unknown>)['data'] as unknown[]).map((item) => String(item));
  }
  const yAxis = opt['yAxis'];
  if (yAxis && typeof yAxis === 'object' && Array.isArray((yAxis as Record<string, unknown>)['data'])) {
    return ((yAxis as Record<string, unknown>)['data'] as unknown[]).map((item) => String(item));
  }
  if (chartType === 'pie' && Array.isArray(opt['series'])) {
    const series = opt['series'] as Array<Record<string, unknown>>;
    const data = Array.isArray(series[0]?.data) ? series[0].data as unknown[] : [];
    return data.map((item, index) => {
      if (item && typeof item === 'object' && 'name' in item) {
        return String((item as { name?: unknown }).name ?? `项目${index + 1}`);
      }
      return `项目${index + 1}`;
    });
  }
  return [];
}

function inferAiChartMeta(
  chartType: string,
  title: string,
  content = '',
): NonNullable<ChartWithMeta['meta']> {
  const text = `${title} ${content}`;
  const isReview = /评价|口碑|差评|星级|服务分|环境分|好评|低星|点评|美团/.test(text);
  const isRevenue = /收入|营收|营业额|销售额|堂食|外卖|客单/.test(text);
  const xDim = chartType === 'line'
    ? 'time'
    : /平台|点评|美团/.test(text)
      ? 'channel'
      : /门店|店/.test(text)
        ? 'store'
        : /菜品|口味|标签|好评词|差评词/.test(text)
          ? 'product'
          : 'category';
  const yMetric = /占比|率/.test(text)
    ? 'pct'
    : isReview
      ? 'count'
      : isRevenue
        ? 'revenue'
        : 'quantity';
  return {
    xDim,
    yMetric,
    aggregation: /平均|均值/.test(text) ? 'avg' : 'sum',
    domain: isReview || isRevenue ? 'restaurant' : 'factory',
  };
}

function normalizeSeriesData(
  opt: Record<string, unknown>,
  chartType: string,
): Array<{ name?: string; type?: string; data?: unknown[] }> {
  if (!Array.isArray(opt['series'])) return [];
  return (opt['series'] as Array<Record<string, unknown>>).map(s => ({
    name: typeof s['name'] === 'string' ? s['name'] : undefined,
    type: typeof s['type'] === 'string' ? s['type'] : chartType,
    data: Array.isArray(s['data']) ? s['data'] : [],
  }));
}

function messageChartWithMeta(msg: ChatMessage): ChartWithMeta | null {
  const cfg = msg.chartConfig;
  if (!cfg || !cfg.option) return null;
  const ct = (cfg.type ?? '').toLowerCase();
  if (EXOTIC_CHART_TYPES_AIQUERY.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  const opt = cfg.option as Record<string, unknown>;
  const title = chartOptionTitle(opt, cfg.title ?? '');
  const xData = extractChartLabels(opt, ct);
  const seriesData = normalizeSeriesData(opt, ct);
  const meta = inferAiChartMeta(ct, title, msg.content);

  return {
    chartType: ct,
    title,
    meta,
    config: { xAxis: { data: xData }, series: seriesData },
  };
}

/**
 * Build ChartWithMeta for a single chart in a multi-chart message (P2 charts[] array).
 * Same logic as messageChartWithMeta but operates on ChartConfig directly.
 */
function multiChartWithMeta(cfg: ChartConfig): ChartWithMeta | null {
  if (!cfg || !cfg.option) return null;
  const ct = (cfg.type ?? '').toLowerCase();
  if (EXOTIC_CHART_TYPES_AIQUERY.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  const opt = cfg.option;
  const title = chartOptionTitle(opt, cfg.title ?? '');
  const xData = extractChartLabels(opt, ct);
  const seriesData = normalizeSeriesData(opt, ct);
  const meta = inferAiChartMeta(ct, title);

  return {
    chartType: ct,
    title,
    meta,
    config: { xAxis: { data: xData }, series: seriesData },
  };
}

// 当前分析上下文 (用于连续对话)
const currentData = ref<unknown[]>([]);
const currentFields = ref<Array<{ original: string; standard: string }>>([]);
const currentTableType = ref<string>('');

// 数据源：自动加载最新上传作为分析上下文
const dataSources = ref<UploadHistoryItem[]>([]);
const selectedUploadId = ref<number | null>(null);
const dataSourceLabel = computed(() => {
  if (!selectedUploadId.value) return '';
  const item = dataSources.value.find(d => d.id === selectedUploadId.value);
  return item ? `数据源：${item.fileName || item.originalFileName || `上传#${item.id}`}` : '';
});

const chatHistory = ref<ChatMessage[]>([]);
const chatContainerRef = ref<HTMLDivElement | null>(null);

// v2 conversation memory (Apr 26 2026): server-side session id persisted in
// sessionStorage so a follow-up question on the same tab inherits the parent
// answer summary. Survives page reload (sessionStorage), clears on tab close
// or when user clicks "新会话". Key is namespaced per factory so switching
// tenants does not leak.
const CHAT_SESSION_KEY = computed(() => `smartbi.chatSessionId.${factoryId.value || 'anon'}`);
function getOrCreateChatSessionId(): string {
  try {
    const key = CHAT_SESSION_KEY.value;
    let id = sessionStorage.getItem(key);
    if (!id) {
      // Use crypto.randomUUID when available; fallback to RFC4122 v4 polyfill.
      id = (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
        ? crypto.randomUUID()
        : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
            const r = (Math.random() * 16) | 0;
            return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
          });
      sessionStorage.setItem(key, id);
    }
    return id;
  } catch {
    // sessionStorage may be blocked (private mode) — still return a per-call
    // UUID so the request goes through, just without continuity.
    return (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}
function resetChatSession(): void {
  try {
    sessionStorage.removeItem(CHAT_SESSION_KEY.value);
  } catch {
    // ignore — sessionStorage blocked
  }
}

// P1-B (2026-06-03): 餐饮租户专属快捷问题目录 — 按 老板 高频维度分组展示.
// 仅当 authStore.factoryType === 'RESTAURANT' 时生效; 工厂租户不受影响.
// 覆盖 gold 后端已支持的维度: 营收/菜品/门店/评价/渠道/客单价.
// 不含"商品分类占比"/"峰值时段"(gold 后端无此数据/无执行器, 会 fall-through).
interface RestaurantQuickGroup {
  label: string;
  questions: string[];
}
const RESTAURANT_QUICK_GROUPS: RestaurantQuickGroup[] = [
  {
    label: '老板今天要做什么',
    questions: [
      '这个星期营收比上周怎么提高？给我今天能做的动作',
      '二人桌不够、周末翻台慢，今天桌型和排队怎么调？',
      '根据菜品毛利和成本，帮我算一个适合今天推的小套餐',
      '今晚怎么排班？哪些时段要加人，哪些时段可以少排？',
      '厨房出餐慢和差评变多，今天先改哪三个动作？',
      '商圈活动和天气会影响今天客流吗？要怎么备货和推品？',
      '客流画像显示路过人多但进店少，今天先改哪个入口？',
    ],
  },
  {
    label: '经营总览',
    questions: [
      '总营业额', '今年营收', '本季度营收', '营收趋势',
    ],
  },
  {
    label: '菜品',
    questions: [
      '哪个菜卖得最好', '慢销菜品', '外卖点什么多', '高频好评词',
    ],
  },
  {
    label: '门店',
    questions: [
      '哪家店业绩最好', '门店销售对比', '哪家店差评多',
    ],
  },
  {
    label: '顾客与评价',
    questions: [
      '客户评价怎么样', 'VIP客户分析', '有哪些差评', '平台口碑如何',
    ],
  },
  {
    label: '渠道与促销',
    questions: [
      '外卖占比', '优惠券使用', '客单价', '周末周中对比',
    ],
  },
];

// P1-B: 时间变体追问 map — 前端静态补充. 后端 gold 工具已对时间参数化,
// 点击 chip 只是预填+提交一个新问题, 走现有 intent 路由.
// key = 问题模式 (substring match), value = 追问 chip 列表.
// 注意: 追问 chip 应避免"怎么样"结尾(命中 GENERAL_QUESTION 高阈值会被拒绝).
const RESTAURANT_TIME_FOLLOWUPS: Array<{
  patterns: string[];
  followups: Array<{ label: string; question: string }>;
}> = [
  {
    patterns: ['营收趋势', '收入趋势', '月度趋势'],
    followups: [
      { label: '本季度', question: '本季度营收趋势' },
      { label: '今年', question: '今年营收趋势' },
      { label: '近30天', question: '近30天营收趋势' },
      { label: '今年 vs 去年', question: '今年与去年营收对比' },
    ],
  },
  {
    patterns: ['总营业额', '营业额多少', '今年营收', '本季度营收'],
    followups: [
      { label: '上个月', question: '上个月总营业额' },
      { label: '本季度', question: '本季度总营业额' },
      { label: '今年', question: '今年总营业额' },
      { label: '去年同期', question: '去年同期总营业额' },
    ],
  },
  {
    patterns: ['哪个菜卖得最好', '畅销', '热销', '爆款'],
    followups: [
      { label: '上个月', question: '上个月哪个菜卖得最好' },
      { label: '本季度', question: '本季度哪个菜卖得最好' },
      { label: '今年', question: '今年哪个菜卖得最好' },
      { label: '近30天', question: '近30天畅销菜品' },
    ],
  },
  {
    patterns: ['慢销', '滞销'],
    followups: [
      { label: '上个月', question: '上个月慢销菜品' },
      { label: '本季度', question: '本季度慢销菜品' },
      { label: '近60天', question: '近60天慢销菜品' },
    ],
  },
  {
    patterns: ['哪家店业绩最好', '门店业绩', '门店销售', '门店对比'],
    followups: [
      { label: '上个月', question: '上个月哪家店业绩最好' },
      { label: '本季度', question: '本季度门店业绩对比' },
      { label: '今年', question: '今年各门店营收排名' },
      { label: '近30天', question: '近30天门店销售对比' },
    ],
  },
  {
    patterns: ['客户评价', '评价情况', '口碑', '平台口碑'],
    followups: [
      { label: 'VIP 评价', question: 'VIP客户评价情况' },
      { label: '差评门店', question: '哪家店差评最多' },
      { label: '高频好评词', question: '高频好评词有哪些' },
      { label: '回复率', question: '平台评价回复率' },
    ],
  },
  {
    patterns: ['VIP', 'vip', '会员'],
    followups: [
      { label: '本季度', question: '本季度VIP客户分析' },
      { label: '今年', question: '今年VIP客户分析' },
      { label: 'VIP评价', question: 'VIP客户评价情况' },
    ],
  },
  {
    patterns: ['客单价'],
    followups: [
      { label: '上个月', question: '上个月客单价' },
      { label: '本季度', question: '本季度客单价' },
      { label: '门店对比', question: '各门店客单价对比' },
    ],
  },
  {
    patterns: ['外卖占比', '外卖'],
    followups: [
      { label: '上个月', question: '上个月外卖占比' },
      { label: '本季度', question: '本季度外卖占比' },
      { label: '堂食外卖', question: '堂食外卖对比' },
    ],
  },
  {
    patterns: ['周末周中', '周末'],
    followups: [
      { label: '上个月', question: '上个月周末与工作日营收对比' },
      { label: '本季度', question: '本季度周末与工作日对比' },
    ],
  },
  {
    patterns: ['优惠券', '促销'],
    followups: [
      { label: '上个月', question: '上个月优惠券使用情况' },
      { label: '本季度', question: '本季度优惠券使用情况' },
      { label: '本月', question: '本月优惠券使用情况' },
    ],
  },
];

// Apr 27 2026 (F6): caps-aware 快捷问题 — chip 内容根据当前数据集 domain
// 动态切换. 之前固定 8 个 sales-focused chips 在 reviews/finance/member 数据集
// 上点了会触发 capability_short_circuit 拒绝, UX 误导.
//
// Heuristic: 用 file_name + sheet_name 关键词推断 domain. 错了无功能损失,
// 只是少一个相关 chip — fallback 到通用销售 chip 组.
const QUICK_QUESTIONS_BY_DOMAIN: Record<string, string[]> = {
  // 评价/口碑数据
  review: [
    '客户评价情况', '差评最多的门店', '哪些菜品差评多',
    'VIP 评价情况', '投诉最集中的问题', '哪个城市评价最低',
    '服务分排名', '环境分对比'
  ],
  // 会员/储值/会员卡数据
  member: [
    '会员卡数据有什么发现', 'VIP 占比', '充值最多的会员',
    '会员等级分布', '余额最高的客户', '会员消费频次',
    '储值卡使用率', '会员流失率'
  ],
  // 财务/利润/收入报表
  finance: [
    '总营业额', '哪家店利润最高', '成本结构',
    '毛利率排名', '门店营收对比', '同比增长',
    '费用占比', '收入趋势'
  ],
  // 库存/进销存
  inventory: [
    '库存周转情况', '滞销库存', '采购金额排名',
    '损耗率', '进货 Top 10', '库存预警'
  ],
  // 默认 — 餐饮销售场景. Apr 28 2026 (UX audit): trimmed chips that often
  // hit capability_short_circuit on minimal sales datasets (e.g. xmx_real
  // doesn't have staff/coupon/payment cols). Kept ones that work on any
  // sales dataset with 商品/门店/销售金额 columns.
  default: [
    '畅销品 Top 5', '哪家店业绩最好', '慢销菜品',
    '周末周中对比', '总营业额', '门店销售对比',
    '商品分类占比', '客单价分析'
  ],
};

// Some uploads have filenames that arrive as GBK bytes mis-decoded as
// Latin-1 (e.g. "评价下载" → "ÆÀ¼ÛÏÂÔØ"). The Excel parser reads .xls/.csv
// names from Windows-style file systems where Chinese is GBK; the byte
// sequence is then JSON-serialized as if Latin-1. To recover, re-encode
// each char's code-point as a GBK byte and decode as GBK.
//
// Try BOTH GBK and UTF-8 since some filenames may legitimately be UTF-8
// mis-decoded (different upload paths). Use GBK first since prod uploads
// (qhj_*) all match GBK pattern.
function recoverUtf8(s: string): string {
  if (!s) return '';
  // Already-Chinese: untouched.
  if (/[一-鿿]/.test(s)) return s;
  try {
    const bytes = new Uint8Array([...s].map(c => c.charCodeAt(0)));
    if (bytes.some(b => b > 0xFF)) return s;
    // Try GBK first (most common on prod uploads).
    try {
      const gbk = new TextDecoder('gbk', { fatal: true }).decode(bytes);
      if (/[一-鿿]/.test(gbk)) return gbk;
    } catch { /* not GBK */ }
    // Fallback UTF-8.
    try {
      const utf8 = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      if (/[一-鿿]/.test(utf8)) return utf8;
    } catch { /* not UTF-8 either */ }
    return s;
  } catch {
    return s;
  }
}

function inferDomainFromFilename(name: string): string {
  if (!name) return 'default';
  const n = (recoverUtf8(name) + ' ' + name).toLowerCase();
  // Order matters: review checks first since "会员" + "评价" overlap less
  if (/评价|评论|review|rating|comment/.test(n)) return 'review';
  if (/会员|储值|membership|vip|member|card|huiyuan|会员卡|卡详情/.test(n)) return 'member';
  if (/收入管理|利润|损益|资产负债|财务|finance|profit|revenue|income|p[\W_]?l/.test(n)) return 'finance';
  if (/库存|进销存|采购|inventory|stock|purchase|kucun/.test(n)) return 'inventory';
  return 'default';
}

// P1-B: For RESTAURANT tenants, the quick-question section is replaced by the
// grouped catalog (rendered separately in the template). This flat list is used
// as a fallback for non-restaurant tenants and the autocomplete list.
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
const ownerActionSessionId = ref('');
const pendingOwnerActionScenario = ref('');

function isRestaurantOwnerActionQuery(query: string): boolean {
  if (!isRestaurantTenant.value) return false;
  return /老板|今天.*动作|具体动作|二人桌|四人桌|桌型|翻台|排队|小套餐|套餐|排班|员工|厨房|出餐|差评|毛利|成本|商圈|客流|画像|进店|转化|曝光|核销|活动|天气|备货|推品/.test(query);
}

function ownerActionFollowups(items?: string[], scenario?: string): Array<{ label: string; question: string; ownerActionScenario?: string }> {
  return (items ?? []).slice(0, 4).map((question) => ({
    label: question.length > 18 ? `${question.slice(0, 18)}...` : question,
    question,
    ownerActionScenario: scenario,
  }));
}

const quickQuestions = computed<string[]>(() => {
  // Restaurant tenants get the grouped catalog (rendered as grouped chips).
  // Return all questions flattened so autocomplete still covers them.
  if (isRestaurantTenant.value) {
    return RESTAURANT_QUICK_GROUPS.flatMap((g) => g.questions);
  }
  const item = dataSources.value.find(d => d.id === selectedUploadId.value);
  const fname = item?.fileName || item?.originalFileName || '';
  const sname = (item as any)?.sheetName || '';
  const domain = inferDomainFromFilename(fname + ' ' + sname);
  return QUICK_QUESTIONS_BY_DOMAIN[domain] || QUICK_QUESTIONS_BY_DOMAIN.default;
});

// 自动补全候选 — 覆盖 35 个模板的高频 sample_queries(177 中精选)
// 命中这里的任何 query → Python RAG 秒回(sim=1.0 via template embedding)
const autocompleteSuggestions = [
  // 菜品
  '畅销品 Top 5', '畅销品排行', '热销菜品', '爆款菜品有哪些',
  '慢销菜品', '滞销菜品', '哪些菜卖不出去', '销量垫底',
  '哪个菜品类别卖得多', '品类销量排名',
  // 门店
  '哪家店业绩最好', '哪家店业绩最差', '业绩冠军是哪家店',
  '门店业绩排名', '哪家店客单价最高', '门店营收对比',
  // 员工
  '员工里谁最厉害', '最厉害的员工', '谁是销售冠军',
  '服务员业绩排名', '员工绩效排名',
  // 时间
  '峰值月份', '营收最高的月份', '月度趋势', '哪个月营业额最高',
  '周末周中对比', '周末生意好还是平日', '礼拜几卖得最好',
  // 异常
  '最近销售异常吗', '营收暴跌月份', '异常月识别',
  // 渠道/付款
  '外卖占比多少', '堂食外卖对比', '付款方式占比',
  '移动支付占比', '美团订单',
  // 套餐/优惠
  '套餐使用率', '优惠券使用情况', '折扣率',
  // 桌位
  '包厢客人点什么菜', '桌位类型对比',
  // 反结账
  '反结账情况', '反结账多吗',
  // 会员
  '会员消费情况', '储值卡使用'
];

// ── 措施③ 推荐机制 "猜你想问" ───────────────────────────────────────────
// 列出该工厂已物化的模板, 渲染成可点 chip. 点击 → 把输入框设为模板中文标题
// 并自动提交 → 命中物化缓存 (0 LLM token + ~230ms), 比开放 NL 链快且便宜.
// 这是把 "开放 NL" 转成 "受限选择" 的核心杠杆.
const recommendTemplates = ref<FactoryTemplate[]>([]);
const recommendLoading = ref(false);

// 优先展示 is_recommended (Top 3), 再按物化次数补满, 最多 6 个 chip.
const recommendChips = computed<FactoryTemplate[]>(() => {
  const all = recommendTemplates.value;
  const recommended = all.filter((t) => t.isRecommended);
  const rest = all.filter((t) => !t.isRecommended);
  return [...recommended, ...rest].slice(0, 6);
});

// chip 显示用 FE 短标题 (useTemplateMap) override 后端 registry 长标题; 缺失回退后端 title.
function recommendChipLabel(t: FactoryTemplate): string {
  const feTitle = getTemplateTitle(t.code);
  // getTemplateTitle 未命中时返回 code 本身 → 退回后端 title.
  return feTitle === t.code ? (t.title || t.code) : feTitle;
}

async function loadRecommendTemplates() {
  if (!factoryId.value) return;
  recommendLoading.value = true;
  try {
    const res = await listFactoryTemplates();
    recommendTemplates.value = res.success && Array.isArray(res.templates) ? res.templates : [];
  } catch (e) {
    // 推荐区是增强项, 失败不打扰用户 — 只是少一个引导. 不返回假数据.
    console.warn('[ai-query] 加载推荐模板失败:', e);
    recommendTemplates.value = [];
  } finally {
    recommendLoading.value = false;
  }
}

// 点击推荐 chip → 用模板标题填入并自动提交 (走现有 chatAnalysisStream 命中物化缓存).
function handleRecommendChip(t: FactoryTemplate) {
  if (isTyping.value) return;
  inputQuery.value = recommendChipLabel(t);
  handleSendMessage();
}

// 分析模板系统
interface QueryTemplate {
  id: string;
  category: string;
  icon: string;
  label: string;
  description: string;
  query: string;
  params?: { name: string; label: string; type: 'text' | 'select'; options?: string[] }[];
}

const templateCategories = [
  { key: 'sales', label: '销售分析', icon: 'TrendCharts', color: '#2D8B57' },
  { key: 'finance', label: '财务分析', icon: 'Money', color: '#67C23A' },
  { key: 'cost', label: '成本分析', icon: 'PieChart', color: '#E6A23C' },
  { key: 'comparison', label: '对比分析', icon: 'DataAnalysis', color: '#F56C6C' },
];

const queryTemplates: QueryTemplate[] = [
  // 销售分析
  { id: 't1', category: 'sales', icon: 'TrendCharts', label: '销售趋势分析', description: '按月度/季度展示销售额变化趋势', query: '分析销售额的月度变化趋势，标注增长和下降的关键月份' },
  { id: 't2', category: 'sales', icon: 'Histogram', label: '产品销售排名', description: '各产品/品类的销售额排名对比', query: '按产品或品类统计销售额排名，找出TOP5和末位产品' },
  { id: 't3', category: 'sales', icon: 'Location', label: '区域销售对比', description: '不同区域的销售业绩对比', query: '对比各区域的销售业绩，分析区域差异的原因' },

  // 财务分析
  { id: 't4', category: 'finance', icon: 'Money', label: '毛利率分析', description: '各产品/业务线的毛利率对比', query: '计算并对比各产品线的毛利率，识别高利润和低利润业务' },
  { id: 't5', category: 'finance', icon: 'Coin', label: '费用结构分析', description: '各项费用的占比和趋势', query: '分析各项费用（管理费用、销售费用、财务费用）的占比和变化趋势' },
  { id: 't6', category: 'finance', icon: 'DataLine', label: '收入利润对比', description: '收入与利润的变化关系', query: '对比分析收入和利润的变化趋势，计算利润率变动' },

  // 成本分析
  { id: 't7', category: 'cost', icon: 'PieChart', label: '成本构成分析', description: '各项成本的占比分布', query: '分析成本构成，找出占比最大的成本项目和优化空间' },
  { id: 't8', category: 'cost', icon: 'Warning', label: '异常值检测', description: '检测数据中的异常波动', query: '检测数据中的异常值和突变点，分析可能的原因' },

  // 对比分析
  { id: 't9', category: 'comparison', icon: 'Sort', label: '同比环比分析', description: '与去年同期/上期的对比', query: '进行同比和环比分析，识别增长和下降趋势' },
  { id: 't10', category: 'comparison', icon: 'SetUp', label: '预算达成分析', description: '实际值与预算目标的对比', query: '对比实际业绩与预算目标，计算达成率和差异' },
  { id: 't11', category: 'comparison', icon: 'DataAnalysis', label: '综合经营分析', description: '多维度经营指标综合分析', query: '综合分析收入、成本、利润、费用等关键经营指标，给出经营建议' },
  { id: 't12', category: 'comparison', icon: 'Flag', label: '行业对标分析', description: '与行业平均水平对比', query: '将关键指标与食品加工行业平均水平对比，评估竞争力和改进方向' },
];

const selectedCategory = ref('');

const filteredTemplates = computed(() => {
  if (!selectedCategory.value) return queryTemplates;
  return queryTemplates.filter(t => t.category === selectedCategory.value);
});

const useTemplate = (tpl: QueryTemplate) => {
  inputQuery.value = tpl.query;
  // Auto-send the template query
  handleSendMessage();
};

// Fix 3 (Apr 23 2026): deep-analysis CTA handlers. Both just feed a
// follow-up query into the regular chat pipeline; because Fix 2 passes
// the last 6 turns as context, the LLM understands "that" / "这个" / etc.
// and gives grounded analysis on top of the template's numbers.
function triggerDeepAnalysis(templateMsg: ChatMessage) {
  const tplHint = templateMsg.templateCode
    ? `（基于刚才的「${templateMsg.templateCode}」结果）`
    : '';
  inputQuery.value = `请结合上面这些数字，分析原因和影响${tplHint}，给出可执行的业务判断`;
  handleSendMessage();
}

function triggerImprovementSuggestions(templateMsg: ChatMessage) {
  const tplHint = templateMsg.templateCode
    ? `针对「${templateMsg.templateCode}」暴露的问题`
    : '针对上面这些数字';
  inputQuery.value = `${tplHint}，给我3-5条可落地的改进建议，说明预期效果和优先级`;
  handleSendMessage();
}

// 相关追问 — 每个模板 3 条交叉维度问题,引导用户深入分析
const RELATED_FOLLOWUPS: Record<string, string[]> = {
  store_performance: ['哪家店客单价最高', '员工里谁最厉害', '峰值月份'],
  staff_performance: ['哪家店业绩最好', '畅销品 Top 5', '反结账情况'],
  dish_sales_top_n: ['慢销菜品', '哪个菜品类别卖得多', '套餐使用率'],
  dish_slow_movers: ['畅销品 Top 5', '优惠券使用情况', '哪家店业绩最差'],
  dish_category_breakdown: ['畅销品 Top 5', '套餐使用率', '包厢客人点什么菜'],
  channel_analysis: ['付款方式占比', '外卖占比多少', '周末周中对比'],
  monthly_trend: ['峰值月份', '最近销售异常吗', '周末周中对比'],
  monthly_anomaly: ['月度趋势', '哪家店业绩最差', '慢销菜品'],
  payment_method_mix: ['优惠券使用情况', '外卖占比多少', '储值卡使用'],
  promotion_impact: ['付款方式占比', '套餐使用率', '反结账情况'],
  weekday_weekend_pattern: ['哪家店业绩最好', '时段销售分布', '优惠券使用情况'],
  combo_usage_rate: ['畅销品 Top 5', '哪个菜品类别卖得多', '哪家店业绩最好'],
  reverse_checkout_stats: ['哪家店业绩最差', '客户评价情况', '员工里谁最厉害'],
  member_consumption: ['储值卡使用', '付款方式占比', '优惠券使用情况'],
  dish_by_table_type: ['畅销品 Top 5', '哪家店业绩最好', '套餐使用率'],
};

function relatedFollowups(templateCode?: string): string[] {
  if (!templateCode) return [];
  return RELATED_FOLLOWUPS[templateCode] || [];
}

function triggerRelatedFollowup(query: string, ownerActionScenario?: string) {
  inputQuery.value = query;
  pendingOwnerActionScenario.value = ownerActionScenario || '';
  handleSendMessage();
}

// P1-B (2026-06-03): time-variant follow-up chips for restaurant tenants.
// Looks up the last user question against RESTAURANT_TIME_FOLLOWUPS patterns.
// Returns an empty array for non-restaurant tenants — the chips never appear.
function timeFollowups(lastQuestion?: string): Array<{ label: string; question: string }> {
  if (!isRestaurantTenant.value || !lastQuestion) return [];
  const q = lastQuestion.trim();
  for (const entry of RESTAURANT_TIME_FOLLOWUPS) {
    if (entry.patterns.some((p) => q.includes(p))) {
      return entry.followups;
    }
  }
  return [];
}

async function sendFeedback(msg: ChatMessage, value: 1 | -1) {
  if (!msg.logId && !msg.feedbackTarget) return;
  if (msg.feedbackPending) return;  // in-flight, ignore rapid double-click
  if (msg.feedbackValue === value) return;  // already this value, no-op
  const prevValue = msg.feedbackValue;
  msg.feedbackPending = true;
  msg.feedbackValue = value;  // optimistic
  let comment: string | undefined;
  if (value === -1) {
    const result = await ElMessageBox.prompt('说一下哪里不准确? (可选)', '反馈', {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputValidator: () => true,
    }).catch((): null => null);
    if (result === null) {
      msg.feedbackValue = prevValue;
      msg.feedbackPending = false;
      return;
    }
    comment = (result as { value?: string }).value || undefined;
  }
  let ok = false;
  if (msg.logId) {
    ok = await logFeedback(msg.logId, value, comment);
  } else if (msg.feedbackTarget && factoryId.value) {
    ok = await submitIntentFeedback(factoryId.value, {
      userInput: msg.feedbackTarget.userInput,
      matchedIntentCode: msg.feedbackTarget.intentCode,
      isCorrect: value === 1,
      correctIntentCode: value === 1 ? msg.feedbackTarget.intentCode : null,
      sessionId: msg.feedbackTarget.sessionId,
      userFeedback: comment,
    });
  }
  if (!ok) {
    msg.feedbackValue = prevValue;
    ElMessage.warning('反馈提交失败, 请稍后重试');
  } else {
 ElMessage.success(value === 1 ? '感谢反馈 ' : '已记录, 我们会改进');
  }
  msg.feedbackPending = false;
}

// NL2SQL 模式
const nl2sqlMode = ref(false);

// 加载状态
const isTyping = ref(false);

// SSE 降级状态
const sseWarningVisible = ref(false);
const sseRetryVisible = ref(false);
let sseWarningTimer: ReturnType<typeof setTimeout> | null = null;
let sseRetryTimer: ReturnType<typeof setTimeout> | null = null;
let pendingRetryQuery = '';

function clearSseDegradationTimers() {
  if (sseWarningTimer) { clearTimeout(sseWarningTimer); sseWarningTimer = null; }
  if (sseRetryTimer) { clearTimeout(sseRetryTimer); sseRetryTimer = null; }
  sseWarningVisible.value = false;
  sseRetryVisible.value = false;
}

function handleSseRetry() {
  if (!pendingRetryQuery) return;
  clearSseDegradationTimers();
  inputQuery.value = pendingRetryQuery;
  pendingRetryQuery = '';
  handleSendMessage();
}

// D1 (Apr 26 2026): re-send the same query when answer was truncated by 25s
// soft timeout. Uses the original query stored on the message so user doesn't
// need to retype.
function handleTruncatedRetry(message: ChatMessage) {
  if (!message.origQuery) return;
  inputQuery.value = message.origQuery;
  handleSendMessage();
}

// 图表实例缓存
const chartInstances: Map<string, echarts.ECharts> = new Map();

// Container ref for ResizeObserver-based chart resize
const pageRef = ref<HTMLElement>();

onMounted(async () => {
  // 加载可用数据源列表，去重 + 智能默认选择
  try {
    const res = await getUploadHistory({ status: 'COMPLETED' });
    if (res.success && Array.isArray(res.data) && res.data.length > 0) {
      const deduped = deduplicateUploads(res.data);
      dataSources.value = deduped;

      // #8 (2026-06-02): 默认数据源选择. 之前的逻辑会在最新非评价上传超过 1 小时时
      // "回退"到最近的评价文件 (评价下载...xlsx) → 对青花椒等餐饮租户, AI 问答默认选中
      // 评价文件 (只有评分/口碑, 无营收/时间序列), 销售/营收/趋势问题就跑错数据集.
      //
      // 修复: pickDefaultDataSource 永远优先非评价、完整的上传 (POS/销售/财务数据),
      // 仅在完全没有非评价上传时才回退评价文件; 同名文件优先完整文件 (避开 _part1/(1)
      // 等分卷). 详见 composables/useDataSourceSelect.ts.
      const chosen = pickDefaultDataSource(deduped);
      if (chosen) {
        selectedUploadId.value = chosen.id;
      }
    }
  } catch (e) {
    console.warn('加载上传列表失败:', e);
  }

  // 添加欢迎消息
  if (chatHistory.value.length === 0) {
    const sourceHint = dataSourceLabel.value ? `\n\n当前${dataSourceLabel.value}` : '\n\n提示：暂无上传数据，建议先在"数据分析"页面上传 Excel 文件。';
    chatHistory.value.push({
      id: 'welcome',
      role: 'assistant',
      content: `您好！我是 SmartBI 智能助手，可以帮您分析销售、财务、库存等数据。您可以选择下方的分析模板快速开始，或直接输入问题。${sourceHint}`,
      timestamp: new Date()
    });
  }

  // R47 BUG-18 fix: 之前两段 logic (一段立即 send + 一段 nextTick+300ms)
  // 都监听 route.query.q, 导致快捷问答 button 双发. 保留 nextTick 那段 (等
  // data-source auto-select), 删掉立即 send 那段.
  // 同时支持 Apr 24 Phase 5 餐饮日常页 "AI 分析" 按钮 ?q= 跳转.
  const qFromRoute = typeof route.query.q === 'string' ? route.query.q : null;
  if (qFromRoute) {
    submitRouteQuery(qFromRoute, /* waitForDataSource */ true);
  }

  // 措施③: 加载该工厂已物化模板, 填充 "猜你想问" 推荐区 (非阻塞, 失败静默).
  loadRecommendTemplates();
});

// WS2 #6 (2026-06-02): 经营驾驶舱快捷问答 chip 跳本页时 AIQuery 已挂载 (同路由
// name 仅 query 变), Vue 复用组件 → onMounted 不再触发 → 之前点 chip "卡住不跳/
// 不出答案". 这里 watch route.query.q, 已挂载情况下也读 q 并自动提交.
//
// onMounted 与本 watch 都会读初始 q: watch 默认 (immediate:false) 不在挂载时
// 触发, 所以首次进入只由 onMounted 提交, 不会双发; 之后 q 变化才由 watch 接管.
function submitRouteQuery(q: string, waitForDataSource = false): void {
  const text = String(q).trim();
  if (!text) return;
  inputQuery.value = text;
  if (waitForDataSource) {
    // Wait one tick so the data-source auto-select (onMounted) finishes, then send.
    nextTick(() => {
      setTimeout(() => handleSendMessage(), 300);
    });
  } else {
    // Already mounted: data source已就绪, 立即提交.
    handleSendMessage();
  }
}

watch(
  () => route.query.q,
  (q) => {
    if (typeof q === 'string' && q.trim()) {
      submitRouteQuery(q, /* waitForDataSource */ false);
    }
  },
);

// Cleanup on unmount
onUnmounted(() => {
  if (activeStreamController) {
    activeStreamController.abort();
    activeStreamController = null;
  }
  if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
  if (chunkFlushTimer) clearTimeout(chunkFlushTimer);
  if (typewriterTimer) clearTimeout(typewriterTimer);
  clearSseDegradationTimers();
  chartInstances.forEach(chart => chart.dispose());
  chartInstances.clear();
});

// ResizeObserver-based chart resize (also handles sidebar toggle)
useChartResize(pageRef, () => {
  chartInstances.forEach(chart => chart.resize());
});

// Active stream controller (to cancel on new message or cleanup)
let activeStreamController: AbortController | null = null;

// Cancel flag for chart retry timers after unmount
let isComponentAlive = true;
onBeforeUnmount(() => { isComponentAlive = false; });

// ── AI Chat Unification (2026-05-13) ──────────────────────────────────
// AIQuery.vue now primarily routes user queries to Java AIIntentService
// (337 Tools + Skill orchestration). Python chatAnalysisStream falls
// through only when Java can't help AND the user has uploaded data.
const javaIntentSessionId = ref<string | undefined>(undefined);

/** User clicks the blue "下载 Excel" button on a bot bubble. */
async function handleAttachmentDownload(message: ChatMessage) {
  const att = message.downloadAttachment;
  if (!att || att.downloading) return;
  att.downloading = true;
  try {
    const blob = await fetchCachedXlsx(att.factoryId, att.cacheKey);
    const url = URL.createObjectURL(
      new Blob([blob], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = att.filename;
    document.body.appendChild(a);  // Chrome needs anchor in DOM for `download`
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    ElMessage.success('下载完成');
  } catch (e) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const err = e as any;
    ElMessage.error(`下载失败: ${err?.message || err}`);
  } finally {
    att.downloading = false;
  }
}

/**
 * Primary route: Java AIIntentService.
 * Returns 'handled' when chat bubble is filled; 'fall-through' when caller
 * should continue to Python chatAnalysisStream (only when user has upload).
 */
async function tryJavaIntentChat(
  query: string,
  assistantId: string,
): Promise<'handled' | 'fall-through'> {
  const idx = () => chatHistory.value.findIndex((m) => m.id === assistantId);
  const factoryId = authStore.factoryId;
  if (!factoryId) return 'fall-through';
  try {
    const ownerActionQuery = isRestaurantOwnerActionQuery(query);
    const res = await executeIntent(factoryId, query, {
      sessionId: javaIntentSessionId.value,
      context: ownerActionQuery ? {
        ownerActionSessionId: ownerActionSessionId.value || undefined,
        ownerActionScenario: pendingOwnerActionScenario.value || undefined,
        storeName: '青花椒上海示范店',
        subSector: '中餐/川味酸菜鱼',
        period: 'this_week',
      } : undefined,
    });

    const i = idx();
    if (i === -1) return 'handled';
    const msg = chatHistory.value[i];

    if (res.status === 'SUCCESS') {
      // Two response shapes:
      //   FRESH (cache miss): res.message = clean text, res.resultData.data
      //     = {download_url, summary, ...} ← Tool's structured result
      //   CACHED:              res.message = "(缓存结果) " + JSON.stringify(...)
      //     resultData is null on cache hit; parse JSON out of message.
      let displayMessage = res.message || res.formattedText || '已为您处理。';
      let downloadUrl: string | undefined;
      let summary: Record<string, unknown> | undefined;

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      let preview: any = null;

      const toolData = res.resultData?.data;
      // Java is now the single frontend entry. It may internally route boss-facing
      // action questions to Python owner-action sections and return their payload
      // directly as resultData.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const resultDataAny = res.resultData as any;
      const isOwnerActionResponse = resultDataAny?.source === 'restaurant_owner_action';
      if (isOwnerActionResponse) {
        ownerActionSessionId.value = String(resultDataAny.sessionId || ownerActionSessionId.value || '');
        msg.source = 'restaurant_owner_action';
        msg.templateCode = `owner_action_${resultDataAny.scenario || 'general'}`;
        msg.logId = resultDataAny.log_id ?? resultDataAny.logId ?? null;
      } else {
        javaIntentSessionId.value = res.sessionId ?? undefined;
      }
      if (toolData?.download_url) {
        downloadUrl = toolData.download_url;
        summary = toolData.summary as Record<string, unknown> | undefined;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        preview = (toolData as any).preview;
      }
      // Gold-backed restaurant tools (经营驾驶舱 销售问答) emit a chartConfig with a
      // full ECharts `option` so the answer renders a visualization (bar/pie/line),
      // not just text. These tools return their map DIRECTLY as resultData (no
      // `.data` wrapper), so chartConfig is on res.resultData itself; report-style
      // tools (buildSimpleResult) put it under resultData.data. Check both.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _toolChart = (res.resultData as any)?.chartConfig ?? (toolData as any)?.chartConfig;
      if (_toolChart?.option) {
        msg.chartConfig = _toolChart as ChartConfig;
      }
      // P2 综合分析: tools may return MULTIPLE charts under resultData.charts.
      // Two shapes: (a) gold-style {type,title,option:{…ECharts}} and (b)
      // synthesis/restaurant_ops-style {chartType,title,xAxis:{data},series}
      // with ECharts fields INLINE (no .option). Normalize (b) → renderable
      // .option here so renderChartFromConfig (+normalizeChartLayout) can draw it.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _toolCharts = (res.resultData as any)?.charts ?? (toolData as any)?.charts;
      if (Array.isArray(_toolCharts) && _toolCharts.length) {
        const built: ChartConfig[] = [];
        for (const c of _toolCharts as any[]) {
          if (!c) continue;
          if (c.option && typeof c.option === 'object') {
            built.push(c as ChartConfig);
            continue;
          }
          if (!c.series && !c.data) continue;
          const ct = c.chartType || c.type || 'bar';
          const opt: Record<string, unknown> = { title: { text: c.title, left: 'center' } };
          if (ct === 'pie') {
            const pd = (Array.isArray(c.series) && c.series[0]?.data) || c.data || [];
            opt.tooltip = { trigger: 'item', formatter: '{b}: {c} ({d}%)' };
            opt.legend = { top: 'bottom' };
            opt.series = [{ type: 'pie', radius: '60%', data: pd }];
          } else {
            opt.tooltip = { trigger: 'axis' };
            opt.xAxis = { type: 'category', data: (c.xAxis && c.xAxis.data) || c.xAxis || [] };
            opt.yAxis = c.yAxis || { type: 'value' };
            opt.series = (c.series || []).map((s: any) => ({
              name: s.name, type: s.type || ct, data: s.data,
            }));
          }
          built.push({ type: ct, title: c.title, option: opt } as ChartConfig);
        }
        if (built.length) msg.charts = built;
      }
      // P1 (2026-06-02): conversational-depth fields. Gold tools return their
      // map DIRECTLY as resultData (no .data wrapper) — same as chartConfig.
      // Report-style tools (buildSimpleResult) put them under resultData.data.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _rd = res.resultData as any;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _td = toolData as any;
      const _followups = _rd?.suggestedFollowups ?? _td?.suggestedFollowups;
      if (Array.isArray(_followups) && _followups.length > 0) {
        msg.suggestedFollowups = _followups
          .filter((f: unknown) => f && typeof (f as { question?: unknown }).question === 'string')
          .map((f: { label?: string; question: string }) => ({
            label: String(f.label ?? f.question),
            question: String(f.question),
          }));
      }
      const _glossary = _rd?.glossary ?? _td?.glossary;
      if (_glossary && typeof _glossary === 'object' && !Array.isArray(_glossary)) {
        msg.glossary = _glossary as Record<string, string>;
      }
      const _chartGuide = _rd?.chartGuide ?? _td?.chartGuide;
      if (typeof _chartGuide === 'string' && _chartGuide.trim()) {
        msg.chartGuide = _chartGuide;
      }
      if (isOwnerActionResponse && Array.isArray(resultDataAny.followUpSuggestions)) {
        msg.suggestedFollowups = ownerActionFollowups(resultDataAny.followUpSuggestions, resultDataAny.scenario);
      }
      if (isOwnerActionResponse && Array.isArray(resultDataAny.suggestedFollowups)) {
        msg.suggestedFollowups = resultDataAny.suggestedFollowups
          .filter((f: unknown) => f && typeof (f as { question?: unknown }).question === 'string')
          .map((f: { label?: string; question: string; ownerActionScenario?: string }) => ({
            label: String(f.label ?? f.question),
            question: String(f.question),
            ownerActionScenario: f.ownerActionScenario,
          }));
      }
      if (res.intentCode && !isOwnerActionResponse) {
        msg.feedbackTarget = {
          source: 'java-intent',
          userInput: query,
          intentCode: res.intentCode,
          sessionId: res.sessionId ?? javaIntentSessionId.value,
        };
      }
      if (!downloadUrl) {
        const rawMsg = res.message || '';
        const jsonStart = rawMsg.indexOf('{');
        if (jsonStart !== -1 && rawMsg.trim().endsWith('}')) {
          try {
            const parsed = JSON.parse(rawMsg.substring(jsonStart));
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const inner: any = parsed.data?.data ?? parsed.data ?? parsed;
            if (typeof inner?.download_url === 'string') downloadUrl = inner.download_url;
            if (inner?.summary && typeof inner.summary === 'object') summary = inner.summary;
            if (inner?.preview && typeof inner.preview === 'object') preview = inner.preview;
            if (inner?.chartConfig?.option) msg.chartConfig = inner.chartConfig as ChartConfig;
            // P1 (2026-06-02): cached map carries depth fields too — capture from inner.
            if (Array.isArray(inner?.suggestedFollowups)) {
              msg.suggestedFollowups = inner.suggestedFollowups
                .filter((f: { question?: unknown }) => f && typeof f.question === 'string')
                .map((f: { label?: string; question: string }) => ({
                  label: String(f.label ?? f.question), question: String(f.question),
                }));
            }
            if (inner?.glossary && typeof inner.glossary === 'object') msg.glossary = inner.glossary;
            if (typeof inner?.chartGuide === 'string') msg.chartGuide = inner.chartGuide;
            const cleanMsg = parsed.data?.message ?? parsed.message;
            if (typeof cleanMsg === 'string' && cleanMsg.trim()) displayMessage = cleanMsg;
          } catch {
            // Not JSON or malformed — keep raw message
          }
        }
      }

      // Build inline chart from preview data — stacked bar per store
      // (堂食 + 外卖 = 汇总), shown directly in the chat bubble.
      if (preview?.block1_yoy && Array.isArray(preview.block1_yoy)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const rows = preview.block1_yoy as Array<any>;
        const storeNames = rows.map((r) => String(r.store_name || `store_${r.store_id}`));
        const dineIn = rows.map((r) => Number(r.dine_in) || 0);
        const takeout = rows.map((r) => Number(r.takeout) || 0);
        msg.chartConfig = {
          type: 'bar' as const,
          title: `${summary?.date_range || ''} 收入分布`,
          option: {
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
            legend: { data: ['堂食', '外卖'], top: 25 },
            grid: { left: '3%', right: '4%', bottom: '8%', containLabel: true, top: 60 },
            xAxis: {
              type: 'category',
              data: storeNames,
              axisLabel: { rotate: 20, fontSize: 11, interval: 0 },
            },
            yAxis: {
              type: 'value',
              name: '元',
              axisLabel: {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                formatter: (v: any) => (v >= 10000 ? (v / 10000).toFixed(1) + 'w' : String(v)),
              },
            },
            series: [
              {
                name: '堂食',
                type: 'bar',
                stack: 'revenue',
                data: dineIn,
                itemStyle: { color: '#5470c6' },
              },
              {
                name: '外卖',
                type: 'bar',
                stack: 'revenue',
                data: takeout,
                itemStyle: { color: '#91cc75' },
              },
            ],
          } as Record<string, unknown>,
        };
      }

      if (typeof downloadUrl === 'string' && downloadUrl.length > 0) {
        const cacheKeyMatch = downloadUrl.match(/\/download\/(.+)$/);
        if (cacheKeyMatch) {
          const dateRange = String(summary?.date_range || 'report')
            .replace(' - ', '_').replace(/\s+/g, '');
          msg.downloadAttachment = {
            cacheKey: cacheKeyMatch[1],
            filename: `收入管理报表_${dateRange}.xlsx`,
            factoryId,
          };
        }
      }
      msg.content = displayMessage;
      msg.loading = false;
      // After Vue renders the chart container (v-if message.chartConfig),
      // mount the ECharts instance.
      if (msg.chartConfig) {
        const cfg = msg.chartConfig;
        nextTick(() => renderChartFromConfig(assistantId, cfg));
      }
      // P2: render the multi-chart stack (each into its own container id).
      if (msg.charts && msg.charts.length) {
        const cs = msg.charts;
        nextTick(() => cs.forEach((c, i) => renderChartFromConfig(`${assistantId}__${i}`, c)));
      }
      return 'handled';
    }

    if (res.status === 'NEED_MORE_INFO' || res.status === 'CONVERSATION_CONTINUE') {
      msg.content = res.message || res.formattedText || '请补充信息后继续。';
      msg.loading = false;
      return 'handled';
    }

    if (
      res.status === 'WRITE_CONFIRM_REQUIRED' ||
      res.status === 'TOOL_DISABLED' ||
      res.status === 'PERMISSION_DENIED' ||
      res.status === 'ERROR'
    ) {
      msg.content = res.message || res.formattedText || '该意图已识别，但暂时无法执行。';
      msg.loading = false;
      return 'handled';
    }

    // Java didn't recognize intent — fall to Python if uploaded data present.
    const hasDataSource =
      currentData.value.length > 0 ||
      Boolean(selectedUploadId.value);
    if (!hasDataSource) {
      msg.content = res.message || '抱歉，没听明白。试试："本月收入管理报表" / "哪家店亏损"';
      msg.loading = false;
      return 'handled';
    }
    return 'fall-through';
  } catch (e) {
    console.warn('[ai-chat] Java intent failed, fall through to Python:', e);
    return 'fall-through';
  }
}

// P1 (2026-06-02): local meta-question resolver. Answers "这个字段什么意思" /
// "这张图说明什么" from the LAST assistant answer's glossary/chartGuide with
// ZERO LLM/API calls. Falls through (returns false) when it can't answer
// locally, so the normal pipeline runs instead.
const META_FIELD_PATTERNS = [/(.+?)\s*(是什么意思|什么意思|是啥|是什么|怎么算|怎么理解|指的是什么)/];
const META_CHART_PATTERNS = [/(这张图|这个图|图表|图)(说明|表示|代表|怎么看|看什么|什么意思)/];

function lastAssistantAnswer(): ChatMessage | undefined {
  for (let i = chatHistory.value.length - 1; i >= 0; i--) {
    const m = chatHistory.value[i];
    if (m.role === 'assistant' && !m.loading && !m.streaming) return m;
  }
  return undefined;
}

function tryLocalMetaAnswer(query: string): boolean {
  const prev = lastAssistantAnswer();
  if (!prev) return false;
  const q = query.trim();

  // (a) "这张图说明什么 / 怎么看这张图" → chartGuide
  if (META_CHART_PATTERNS.some((re) => re.test(q)) && prev.chartGuide) {
    pushLocalAssistant(`关于上一张图：${prev.chartGuide}`);
    return true;
  }

  // (b) "X 什么意思" → glossary[X] (substring match, longest term wins)
  if (prev.glossary && Object.keys(prev.glossary).length > 0) {
    for (const pat of META_FIELD_PATTERNS) {
      const m = q.match(pat);
      if (m && m[1]) {
        const asked = m[1].trim();
        const terms = Object.keys(prev.glossary).sort((a, b) => b.length - a.length);
        const hit = terms.find((t) => asked.includes(t) || t.includes(asked));
        if (hit) {
          pushLocalAssistant(`「${hit}」：${prev.glossary[hit]}`);
          return true;
        }
      }
    }
    // (c) bare term lookup: query itself IS a glossary term (e.g. user types "差评率")
    const direct = Object.keys(prev.glossary).find((t) => t === q);
    if (direct) {
      pushLocalAssistant(`「${direct}」：${prev.glossary[direct]}`);
      return true;
    }
  }
  return false;
}

// Render a synthetic assistant bubble without any backend call.
function pushLocalAssistant(content: string) {
  // Echo the user's question first (mirror handleSendMessage's user-echo).
  chatHistory.value.push({
    id: `u-${Date.now()}`, role: 'user', content: inputQuery.value.trim(), timestamp: new Date(),
  });
  chatHistory.value.push({
    id: `a-${Date.now()}`, role: 'assistant', content, timestamp: new Date(),
  });
  inputQuery.value = '';
  nextTick(() => scrollToBottom());
}

// 发送消息
async function handleSendMessage() {
  const query = inputQuery.value.trim();
  if (!query) return;
  // P1: try local glossary/chartGuide answer first (0 LLM).
  if (tryLocalMetaAnswer(query)) return;

  // Route to NL2SQL handler if in SQL mode
  if (nl2sqlMode.value) {
    return handleNL2SQLQuery(query);
  }

  // Cancel any in-flight stream
  if (activeStreamController) {
    activeStreamController.abort();
    activeStreamController = null;
  }

  // 添加用户消息
  const userMessage: ChatMessage = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: query,
    timestamp: new Date()
  };
  chatHistory.value.push(userMessage);

  // 清空输入
  inputQuery.value = '';

  // 添加助手加载消息
  const assistantId = `assistant-${Date.now()}`;
  const loadingMessage: ChatMessage = {
    id: assistantId,
    role: 'assistant',
    content: '',
    timestamp: new Date(),
    loading: true
  };
  chatHistory.value.push(loadingMessage);

  // 滚动到底部 (force: user just sent message)
  scrollToBottom(true);

  isTyping.value = true;

  // ── AI Chat Unification primary route: try Java AIIntentService first.
  // Falls through to Python chatAnalysisStream only when Java didn't help
  // AND user has a data source loaded.
  const intentResult = await tryJavaIntentChat(query, assistantId);
  if (intentResult === 'handled') {
    pendingOwnerActionScenario.value = '';
    isTyping.value = false;
    scrollToBottom();
    return;
  }
  pendingOwnerActionScenario.value = '';

  // Fix 2 (Apr 23 2026): pass last 3 Q+A pairs as conversation history so
  // backend LLM can resolve pronominal/temporal references ("这个月"/"它"/
  // "那家") from previous turns. Exclude the welcome message, loading
  // placeholder, and the current user message just pushed above.
  const historyForContext = chatHistory.value
    .filter((m) => m.id !== 'welcome' && m.id !== assistantId && !m.loading && m.content.trim())
    .slice(-7, -1) // last 6 before current user msg (the 7th from end)
    .map((m) => ({
      role: m.role as 'user' | 'assistant',
      content: m.content,
    }));

  const requestParams = {
    query,
    data: currentData.value.length > 0 ? currentData.value : undefined,
    fields: currentFields.value.length > 0 ? currentFields.value : undefined,
    table_type: currentTableType.value || undefined,
    uploadId: selectedUploadId.value ? String(selectedUploadId.value) : undefined,
    history: historyForContext.length > 0 ? historyForContext : undefined,
    sessionId: getOrCreateChatSessionId(),
  };

  // Helper to find the assistant message
  const getMessageIndex = () => chatHistory.value.findIndex(m => m.id === assistantId);

  // SSE degradation watchdog: 30s warning + 60s retry button (backend status 心跳会 reset)
  // 200K 行冷缓存聚合实测 35s — 30s 有 brief "网络不稳定" 警告是可接受的,60s 才 retry 兜底
  let firstChunkReceived = false;
  pendingRetryQuery = query;

  const startSseWatchdog = () => {
    clearSseDegradationTimers();
    // 30s: 显示 inline 警告 (每次 backend status 心跳都会 reset 此 timer)
    sseWarningTimer = setTimeout(() => {
      if (!firstChunkReceived) {
        sseWarningVisible.value = true;
        const idx = getMessageIndex();
        if (idx !== -1) {
          const msg = chatHistory.value[idx];
          msg.content = '网络连接不稳定，正在尝试重新连接...';
          msg.loading = true;
        }
        scrollToBottom();
      }
    }, 30000);
    // 60s: 显示 retry 按钮 (200K 行首次冷缓存 + LLM TTFT 最坏情况;缓存命中只需 12-15s)
    sseRetryTimer = setTimeout(() => {
      if (!firstChunkReceived) {
        sseRetryVisible.value = true;
        const idx = getMessageIndex();
        if (idx !== -1) {
          const msg = chatHistory.value[idx];
          msg.content = '连接超时，服务响应较慢。';
          msg.loading = false;
        }
        scrollToBottom();
      }
    }, 60000);
  };
  const clearSseWatchdog = () => {
    clearSseDegradationTimers();
    pendingRetryQuery = '';
  };

  // Try streaming first, fall back to non-streaming
  activeStreamController = chatAnalysisStream(requestParams, {
    onStatus(status: string) {
      const idx = getMessageIndex();
      if (idx !== -1) {
        const msg = chatHistory.value[idx];
        msg.content = status;
        msg.loading = true;
      }
      scrollToBottom();
      // 每次 status 都 reset watchdog — 连续心跳 = 后端还活着,只要 30s 内有新 status 或第一个 chunk 就不超时
      if (!firstChunkReceived) startSseWatchdog();
    },

    onChunk(text: string) {
      // First chunk arrived — cancel watchdog and clear degradation UI
      if (!firstChunkReceived) {
        firstChunkReceived = true;
        clearSseWatchdog();
        sseWarningVisible.value = false;
        sseRetryVisible.value = false;
      }
      // Buffer chunks for 16ms before flushing to reduce Vue reactivity triggers
      chunkTargetId = assistantId;
      chunkTargetIdx = -1; // invalidate cache so resolveTargetIdx re-lookups
      chunkBuffer += text;
      if (!chunkFlushTimer) {
        chunkFlushTimer = setTimeout(flushChunkBuffer, 16);
      }
    },

    async onCharts(charts: ChartConfig[]) {
      const idx = getMessageIndex();
      if (idx !== -1 && charts.length > 0) {
        chatHistory.value[idx].chartConfig = charts[0];
        await nextTick();
        renderChartFromConfig(assistantId, charts[0]);
      }
    },

    async onDone(result: AnalysisResult) {
      clearSseWatchdog();
      // Flush any remaining buffered chunks + typewriter queue
      if (chunkFlushTimer) { clearTimeout(chunkFlushTimer); chunkFlushTimer = null; }
      flushChunkBuffer();
      flushTypewriterImmediate();

      const idx = getMessageIndex();
      if (idx !== -1) {
        const msg = chatHistory.value[idx];
        // If still loading (no chunks arrived), content is just status text — prefer result.answer
        const finalContent = msg.loading
          ? (result.answer || '分析完成')
          : (msg.content || result.answer || '分析完成');

        // Build chart data for compat
        let chartData: ChatMessage['chart'] | undefined;
        if (result.charts && result.charts.length > 0) {
          const firstChart = result.charts[0];
          chartData = {
            type: firstChart.type as 'line' | 'bar' | 'pie',
            data: firstChart.option as Record<string, unknown>
          };
        }

        // Direct mutation instead of object spread
        msg.content = finalContent;
        msg.chart = chartData;
        msg.chartConfig = msg.chartConfig || result.charts?.[0];
        msg.insights = result.insights;
        msg.table = result.table as ChatMessage['table'];
        msg.source = result.source;
        msg.templateCode = result.template_code;
        msg.logId = result.log_id ?? null;
        msg.warning = (result as { warning?: string | null }).warning ?? null;
        // D1 (Apr 26 2026): detect 25s soft-timeout truncation marker
        // (chat.py emits one of "*分析超过 25 秒已截断*" or
        // "*本次 AI 思考超时, 已显示 24 小时内对相同问题的历史回答*")
        msg.truncated = /分析超过 25 秒已截断|本次 AI 思考超时.*历史回答|AI 思考超时\(>25 秒\)/.test(finalContent);
        msg.origQuery = query;
        msg.loading = false;
        msg.streaming = false;

        // Render chart if not already rendered via onCharts
        if (!msg.chartConfig && result.charts && result.charts.length > 0) {
          await nextTick();
          renderChartFromConfig(assistantId, result.charts[0]);
        }
      }

      isTyping.value = false;
      activeStreamController = null;
      scrollToBottom(true);
    },

    async onError(error: string) {
      clearSseWatchdog();
      // Clear chunk buffer + typewriter on error
      if (chunkFlushTimer) { clearTimeout(chunkFlushTimer); chunkFlushTimer = null; }
      chunkBuffer = '';
      if (typewriterTimer) { clearTimeout(typewriterTimer); typewriterTimer = null; }
      typewriterQueue = '';
      console.error('AI 流式查询失败:', error);

      // Convert to user-friendly message
      let friendlyMessage = '抱歉，查询过程中发生错误，请稍后重试。';
      if (error.includes('422') || error.includes('Unprocessable')) {
        friendlyMessage = '请先选择一个数据源（上传 Excel 或选择已有数据），再进行 AI 问答';
      } else if (error.includes('500') || error.includes('Internal Server')) {
        friendlyMessage = '分析服务暂时不可用，请稍后重试';
      } else if (error.includes('timeout') || error.includes('503') || error.includes('504')) {
        friendlyMessage = 'AI 分析服务暂时不可用，请稍后重试。';
      } else if (error.includes('fetch') || error.includes('network') || error.includes('ERR_CONNECTION')) {
        friendlyMessage = '请求失败，请检查网络连接';
      } else if (error && error.length < 100) {
        friendlyMessage = error;
      }

      // Fall back to non-streaming
      try {
        const response = await chatAnalysis(requestParams);
        const idx = getMessageIndex();
        if (idx !== -1) {
          if (response.success) {
            let chartData: ChatMessage['chart'] | undefined;
            if (response.charts && response.charts.length > 0) {
              const firstChart = response.charts[0];
              chartData = {
                type: firstChart.type as 'line' | 'bar' | 'pie',
                data: firstChart.option as Record<string, unknown>
              };
            }
            chatHistory.value[idx] = {
              id: assistantId,
              role: 'assistant',
              content: response.answer || '分析完成',
              timestamp: new Date(),
              chart: chartData,
              chartConfig: response.charts?.[0],
              insights: response.insights,
              table: response.table as ChatMessage['table'],
              loading: false
            };
            await nextTick();
            if (response.charts && response.charts.length > 0) {
              renderChartFromConfig(assistantId, response.charts[0]);
            }
          } else {
            chatHistory.value[idx] = {
              id: assistantId,
              role: 'assistant',
              content: friendlyMessage,
              timestamp: new Date(),
              loading: false
            };
          }
        }
      } catch {
        const idx = getMessageIndex();
        if (idx !== -1) {
          chatHistory.value[idx] = {
            id: assistantId,
            role: 'assistant',
            content: friendlyMessage,
            timestamp: new Date(),
            loading: false
          };
        }
      }

      isTyping.value = false;
      activeStreamController = null;
      scrollToBottom(true);
    },
  });
}

// NL2SQL 查询处理
async function handleNL2SQLQuery(query: string) {
  if (!selectedUploadId.value) {
    ElMessage.warning('请先选择数据源');
    return;
  }

  // Add user message
  chatHistory.value.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content: query,
    timestamp: new Date()
  });
  inputQuery.value = '';

  const assistantId = `assistant-${Date.now()}`;
  chatHistory.value.push({
    id: assistantId,
    role: 'assistant',
    content: '',
    timestamp: new Date(),
    loading: true
  });
  scrollToBottom(true);
  isTyping.value = true;

  try {
    const res = await nl2sql({
      query,
      uploadId: selectedUploadId.value,
      factoryId: factoryId.value || '',
      execute: true,
      limit: 200,
    });

    const idx = chatHistory.value.findIndex(m => m.id === assistantId);
    if (idx === -1) return;

    if (res.success && res.sql) {
      // Build table from results
      let table: ChatMessage['table'] | undefined;
      if (res.result && res.result.length > 0) {
        const columns = Object.keys(res.result[0]);
        table = { columns, data: res.result };
      }

      chatHistory.value[idx] = {
        id: assistantId,
        role: 'assistant',
        content: res.explanation || 'SQL 查询已执行',
        timestamp: new Date(),
        sqlResult: res,
        table,
        loading: false,
      };
    } else {
      chatHistory.value[idx] = {
        id: assistantId,
        role: 'assistant',
        content: res.message || '查询失败',
        timestamp: new Date(),
        sqlResult: res.sql ? res : undefined,
        loading: false,
      };
    }
  } catch (error) {
    const idx = chatHistory.value.findIndex(m => m.id === assistantId);
    if (idx !== -1) {
      chatHistory.value[idx] = {
        id: assistantId,
        role: 'assistant',
        content: '查询失败，请稍后重试',
        timestamp: new Date(),
        loading: false,
      };
    }
  }

  isTyping.value = false;
  scrollToBottom(true);
}

/**
 * 设置分析上下文 (用于连续对话)
 * 可从 Excel 上传页面传入数据
 */
function setAnalysisContext(data: unknown[], fields: Array<{ original: string; standard: string }>, tableType?: string) {
  currentData.value = data;
  currentFields.value = fields;
  currentTableType.value = tableType || '';
}

// Format data source label with sheet name and row count
function formatDataSourceLabel(ds: UploadHistoryItem): string {
  const name = ds.fileName || `上传#${ds.id}`;
  const parts = [name];
  if (ds.sheetName) parts.push(ds.sheetName);
  if (ds.rowCount) parts.push(`${ds.rowCount}行`);
  return parts.join(' · ');
}

// 暴露给父组件调用。inputQuery / handleSendMessage / chatHistory 额外暴露供
// 单元测试驱动消息管线 (P1 AIQuery.depth.spec.ts)，生产代码不依赖。
defineExpose({ setAnalysisContext, inputQuery, handleSendMessage, chatHistory });

// 渲染图表 (从 ChartConfig)
// Defensive chart-layout normalizer (Jun 2026): some Python templates emit
// ECharts options that leave no room for the title (it overlaps the plot) and
// rotate many long Chinese category labels too little (labels overlap each
// other). Applied to EVERY rendered option so both Python templates and gold
// tools render clean inside the fixed-height chart container — no re-
// materialization needed. Mutates `opt` in place.
function normalizeChartLayout(opt: Record<string, unknown>): void {
  if (!opt || typeof opt !== 'object') return;
  const titleObj = opt.title as { text?: string } | undefined;
  const hasTitle = !!(titleObj && typeof titleObj === 'object' && titleObj.text);
  const grid = (typeof opt.grid === 'object' && opt.grid && !Array.isArray(opt.grid)
    ? opt.grid : {}) as Record<string, unknown>;
  grid.containLabel = true;
  if (grid.left == null) grid.left = '4%';
  if (grid.right == null) grid.right = '5%';
  // Leave vertical room for the title so it never overlaps the plot.
  if (grid.top == null) grid.top = hasTitle ? 62 : 28;
  // A value-axis `name` (e.g. "星级分") renders at the top-left and collides
  // with a centered title that already states the measure ("…(按 星级分)").
  // Drop the redundant axis name when a title is present.
  if (hasTitle) {
    const ya = (Array.isArray(opt.yAxis) ? opt.yAxis[0] : opt.yAxis) as
      Record<string, unknown> | undefined;
    if (ya && typeof ya === 'object' && ya.name) ya.name = '';
  }
  // Shrink crowded value labels on many-bar charts so they stop piling up.
  const xcat = (Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis) as
    Record<string, unknown> | undefined;
  const nCat = xcat && Array.isArray(xcat.data) ? xcat.data.length : 0;
  if (nCat > 6 && Array.isArray(opt.series)) {
    for (const s of opt.series as Array<Record<string, unknown>>) {
      const lbl = s && typeof s.label === 'object' ? (s.label as Record<string, unknown>) : null;
      if (lbl && lbl.show && lbl.fontSize == null) lbl.fontSize = 9;
    }
  }

  // Long / many category labels on a horizontal (xAxis) category axis overlap
  // when barely rotated. Rotate more + show every label + shrink font + reserve
  // bottom room. Horizontal-bar charts (categories on yAxis) are untouched.
  const xa = (Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis) as
    Record<string, unknown> | undefined;
  if (xa && xa.type === 'category' && Array.isArray(xa.data)) {
    const cats = xa.data as unknown[];
    const longest = cats.reduce((m: number, c) => Math.max(m, String(c ?? '').length), 0);
    if (cats.length > 4 || longest > 5) {
      const al = (typeof xa.axisLabel === 'object' && xa.axisLabel
        ? xa.axisLabel : {}) as Record<string, unknown>;
      if (al.rotate == null || (typeof al.rotate === 'number' && al.rotate < 35)) {
        al.rotate = (cats.length > 6 || longest > 7) ? 40 : 30;
      }
      al.interval = 0;
      if (al.fontSize == null) al.fontSize = 10;
      al.hideOverlap = true;
      if (al.width == null) { al.width = 84; al.overflow = 'truncate'; }
      xa.axisLabel = al;
      if (grid.bottom == null || grid.bottom === '3%') grid.bottom = 76;
    }
  }
  opt.grid = grid;
}

function renderChartFromConfig(messageId: string, chartConfig: ChartConfig) {
  if (!chartConfig) return;

  // Try to get chart container — may need a small delay for Vue to render the v-if container
  const tryRender = (attempt = 0) => {
    if (!isComponentAlive) return;
    const chartDom = document.getElementById(`chart-${messageId}`);
    if (!chartDom) {
      if (attempt < 5) {
        setTimeout(() => tryRender(attempt + 1), 300);
      }
      return;
    }

    // 销毁旧图表
    const oldChart = chartInstances.get(messageId);
    if (oldChart) {
      oldChart.dispose();
    }

    const chart = echarts.init(chartDom, 'cretas');
    chartInstances.set(messageId, chart);

    // Use option directly if available (proper ECharts config from Python).
    // Bug #20 fix (Apr 17 2026): Python emits __FMT__/__ANIM__ sentinel strings
    // in place of callbacks. Resolve them via processEChartsOptions, otherwise
    // ECharts throws "TypeError: f is not a function" when trying to call the
    // sentinel string as a formatter.
    if (chartConfig.option && typeof chartConfig.option === 'object') {
      const resolvedOption = processEChartsOptions(chartConfig.option as Record<string, unknown>);
      normalizeChartLayout(resolvedOption as Record<string, unknown>);
      chart.setOption(resolvedOption as echarts.EChartsOption);
      return;
    }

    // Fallback: chartConfig has raw data but no option — skip rendering
    console.warn('[AIQuery] chartConfig missing option, cannot render:', chartConfig);
  };

  tryRender();
}

// 渲染图表 (兼容旧格式)
function renderChart(messageId: string, chartConfig: ChatMessage['chart']) {
  if (!chartConfig) return;

  const chartDom = document.getElementById(`chart-${messageId}`);
  if (!chartDom) return;

  // 销毁旧图表
  const oldChart = chartInstances.get(messageId);
  if (oldChart) {
    oldChart.dispose();
  }

  const chart = echarts.init(chartDom, 'cretas');
  chartInstances.set(messageId, chart);

  let option: echarts.EChartsOption;

  const xAxisData = chartConfig.data.xAxis as unknown[] | undefined;
  const seriesArr = (chartConfig.data.series as Array<Record<string, unknown>> | undefined) || [];

  if (chartConfig.type === 'line') {
    option = {
      tooltip: { trigger: 'axis', confine: true },
      legend: { bottom: 0 },
      grid: { left: '3%', right: '4%', bottom: '15%', top: '10%', containLabel: true },
      xAxis: { type: 'category', data: xAxisData as (string | number)[] },
      yAxis: { type: 'value' },
      series: seriesArr.map((s) => ({
        name: s.name as string,
        type: 'line',
        smooth: true,
        data: s.data as number[],
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(45, 139, 87, 0.3)' },
            { offset: 1, color: 'rgba(45, 139, 87, 0.05)' }
          ])
        }
      }))
    };
  } else if (chartConfig.type === 'bar') {
    option = {
      tooltip: { trigger: 'axis', confine: true },
      legend: { bottom: 0 },
      grid: { left: '3%', right: '4%', bottom: '15%', top: '10%', containLabel: true },
      xAxis: { type: 'category', data: xAxisData as (string | number)[] },
      yAxis: { type: 'value' },
      series: seriesArr.map((s, i) => ({
        name: s.name as string,
        type: 'bar',
        data: s.data as number[],
        itemStyle: {
          color: i === 0 ? '#2D8B57' : '#67C23A',
          borderRadius: [4, 4, 0, 0]
        }
      }))
    };
  } else if (chartConfig.type === 'pie') {
    option = {
      tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c}万 ({d}%)' },
      legend: { orient: 'vertical', right: '10%', top: 'center' },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['40%', '50%'],
        data: seriesArr.map((s, i) => ({
          name: s.name as string,
          value: s.value as number,
          itemStyle: {
            color: ['#2D8B57', '#67C23A', '#E6A23C', '#F56C6C', '#909399'][i % 5]
          }
        })),
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }]
    };
  } else {
    console.warn(`Unsupported chart type: ${chartConfig.type}`);
    return;
  }

  chart.setOption(option);
}

// 滚动到底部 — rAF 节流 + 用户上翻暂停
let scrollRafId: number | null = null;
let userIsScrollingUp = false;

function onChatScroll() {
  if (!chatContainerRef.value) return;
  const el = chatContainerRef.value;
  const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
  userIsScrollingUp = !isAtBottom;
}

function scrollToBottom(force = false) {
  if (!force && userIsScrollingUp) return;
  if (scrollRafId !== null) return;
  scrollRafId = requestAnimationFrame(() => {
    if (chatContainerRef.value) {
      chatContainerRef.value.scrollTop = chatContainerRef.value.scrollHeight;
    }
    scrollRafId = null;
  });
}

// chunk 缓冲 — 16ms 内累积后批量更新
let chunkBuffer = '';
let chunkFlushTimer: ReturnType<typeof setTimeout> | null = null;
let chunkTargetId = '';
// P2 PERF fix: Cache message index to avoid O(n) findIndex on every chunk/typewriter tick
let chunkTargetIdx = -1;

// Typewriter effect: drip characters from pending queue
let typewriterQueue = '';
let typewriterTimer: ReturnType<typeof setTimeout> | null = null;
const TYPEWRITER_DELAY = 20; // ms per character drip

/** Resolve cached index, re-lookup only if stale */
function resolveTargetIdx(): number {
  if (chunkTargetIdx >= 0 && chunkTargetIdx < chatHistory.value.length
      && chatHistory.value[chunkTargetIdx].id === chunkTargetId) {
    return chunkTargetIdx;
  }
  chunkTargetIdx = chatHistory.value.findIndex(m => m.id === chunkTargetId);
  return chunkTargetIdx;
}

function drainTypewriter() {
  if (!typewriterQueue || !chunkTargetId) {
    typewriterTimer = null;
    return;
  }
  const idx = resolveTargetIdx();
  if (idx === -1) {
    typewriterQueue = '';
    typewriterTimer = null;
    return;
  }
  // Drip 1-3 characters at a time for natural feel
  const chars = Math.min(typewriterQueue.length, typewriterQueue.length > 20 ? 3 : 1);
  const msg = chatHistory.value[idx];
  msg.content += typewriterQueue.slice(0, chars);
  msg.streaming = true;
  typewriterQueue = typewriterQueue.slice(chars);
  scrollToBottom();

  if (typewriterQueue.length > 0) {
    typewriterTimer = setTimeout(drainTypewriter, TYPEWRITER_DELAY);
  } else {
    typewriterTimer = null;
  }
}

function flushChunkBuffer() {
  if (!chunkBuffer || !chunkTargetId) return;
  const idx = resolveTargetIdx();
  if (idx !== -1) {
    const msg = chatHistory.value[idx];
    // If message was loading (status text), clear it before appending
    if (msg.loading) {
      msg.content = '';
      msg.loading = false;
    }
    msg.streaming = true;
    // Feed chunk into typewriter queue
    typewriterQueue += chunkBuffer;
    if (!typewriterTimer) {
      drainTypewriter();
    }
  }
  chunkBuffer = '';
  chunkFlushTimer = null;
}

/** Immediately flush any remaining typewriter chars (used on stream end) */
function flushTypewriterImmediate() {
  if (typewriterTimer) { clearTimeout(typewriterTimer); typewriterTimer = null; }
  if (!typewriterQueue || !chunkTargetId) return;
  const idx = resolveTargetIdx();
  if (idx !== -1) {
    chatHistory.value[idx].content += typewriterQueue;
  }
  typewriterQueue = '';
}

// 处理快捷问题
function handleQuickQuestion(question: string) {
  inputQuery.value = question;
  handleSendMessage();
}

// 自动补全 — 从 autocompleteSuggestions 按 substring 过滤
function fetchAutocomplete(
  queryString: string,
  cb: (suggestions: { value: string }[]) => void,
) {
  const q = (queryString || '').trim().toLowerCase();
  const list = autocompleteSuggestions
    .filter((s) => !q || s.toLowerCase().includes(q))
    .slice(0, 15)
    .map((value) => ({ value }));
  cb(list);
}

// 选中建议后自动发送
function handleSuggestionSelect(item: { value: string }) {
  inputQuery.value = item.value;
  handleSendMessage();
}

// 清空对话
function handleClearHistory() {
  // 销毁所有图表
  chartInstances.forEach(chart => chart.dispose());
  chartInstances.clear();

  // v2 conversation memory: 清空对话同时重置服务端 session_id, 让下一句问从零
  // 上下文开始(否则上轮 parent_answer_summary 还会注入到下一个 LLM prompt).
  resetChatSession();

  chatHistory.value = [{
    id: 'welcome',
    role: 'assistant',
    content: '您好！我是 SmartBI 智能助手，可以帮您分析销售、财务、库存等数据。您可以选择下方的分析模板快速开始，或直接输入问题。',
    timestamp: new Date()
  }];
}

// D3 (Apr 26 2026): 新话题 — 仅重置服务端 session_id, 保留对话记录可见.
// 适用场景: 用户想换话题但希望前文还能滚动查看.
// 与"清空对话"的区别: 后者销毁图表+清屏+重置 session.
function handleNewTopic() {
  resetChatSession();
  // 加一条系统消息说明 (用 assistant 风格但内容是状态提示).
  chatHistory.value.push({
    id: `topic-reset-${Date.now()}`,
    role: 'assistant',
 content: ' 已开新话题，下一句提问不再引用前文上下文（前文记录保留可见）',
    timestamp: new Date()
  });
  scrollToBottom(true);
}

// 格式化时间
function formatTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

// 处理键盘事件
function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    handleSendMessage();
  }
}
</script>

<template>
  <div ref="pageRef" class="ai-query-page">
    <div class="page-header">
      <div class="header-left">
        <h1>
          <el-icon><ChatDotRound /></el-icon>
          AI 智能问答
        </h1>
      </div>
      <div class="header-right">
        <el-select
          v-if="dataSources.length > 0"
          v-model="selectedUploadId"
          placeholder="选择数据源"
          size="small"
          style="width: 280px; margin-right: 8px"
        >
          <el-option
            v-for="ds in dataSources"
            :key="ds.id"
            :label="formatDataSourceLabel(ds)"
            :value="ds.id"
          >
            <el-tooltip :content="formatDataSourceLabel(ds)" placement="left" :show-after="500">
              <span style="display:block;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ formatDataSourceLabel(ds) }}</span>
            </el-tooltip>
          </el-option>
        </el-select>
        <el-tooltip content="SQL 直查：直接将问题转为 SQL 查询数据" placement="bottom">
          <el-switch
            v-model="nl2sqlMode"
            active-text="SQL 直查"
            inactive-text="AI 分析"
            style="margin-right: 12px"
            :active-action-icon="Search"
            :inactive-action-icon="Cpu"
          />
        </el-tooltip>
        <el-button :icon="Delete" @click="handleClearHistory">清空对话</el-button>
        <el-tooltip content="开新会话: 下一句问题不引用前文上下文,但当前对话记录保留可见" placement="bottom">
          <el-button :icon="ChatRound" @click="handleNewTopic">新话题</el-button>
        </el-tooltip>
      </div>
    </div>

    <div class="chat-container">
      <!-- 物化分析面板：仅在选择了数据源时展示，位于对话历史区上方 -->
      <MaterializedAnalysisPanel
        v-if="selectedUploadId"
        :upload-id="selectedUploadId"
      />

      <!-- 对话历史区 -->
      <div class="chat-history" ref="chatContainerRef" @scroll="onChatScroll">
        <div
          v-for="(message, msgIdx) in chatHistory"
          :key="message.id"
          class="chat-message"
          :class="message.role"
        >
          <div class="message-avatar">
            <el-icon v-if="message.role === 'user'"><User /></el-icon>
            <el-icon v-else><Cpu /></el-icon>
          </div>
          <div class="message-content">
            <div class="message-header">
              <span class="role-name">{{ message.role === 'user' ? '我' : 'AI 助手' }}</span>
              <span class="message-time">{{ formatTime(message.timestamp) }}</span>
            </div>
            <div class="message-body">
              <div v-if="message.loading" class="loading-indicator">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>{{ message.content || '正在思考...' }}</span>
                <span v-if="sseWarningVisible && !sseRetryVisible" class="sse-warning-hint">
                  <el-icon><Warning /></el-icon> 网络连接不稳定
                </span>
              </div>
              <!-- SSE retry button shown after 15s timeout -->
              <div v-if="sseRetryVisible && !message.loading && !message.streaming && message.content === '连接超时，服务响应较慢。'" class="sse-retry-area">
                <span class="sse-retry-text">{{ message.content }}</span>
                <el-button type="primary" size="small" :icon="Refresh" @click="handleSseRetry">重新提问</el-button>
              </div>
              <template v-else>
                <div v-if="message.role === 'assistant' && message.streaming" class="message-text streaming-text">{{ message.content }}</div>
                <div v-else-if="message.role === 'assistant'" class="message-text markdown-body" v-html="renderMarkdown(message.content)"></div>
                <div v-else class="message-text">{{ message.content }}</div>

                <!-- AI Chat Unification (2026-05-13): user-clicked xlsx download -->
                <div v-if="message.role === 'assistant' && message.downloadAttachment" class="download-attachment-bar">
                  <el-button
                    type="primary"
                    :icon="Download"
                    :loading="message.downloadAttachment.downloading"
                    @click="handleAttachmentDownload(message)"
                  >下载 Excel</el-button>
                </div>

                <!-- D1 (Apr 26 2026): retry button when answer was truncated by 25s soft timeout -->
                <div v-if="message.role === 'assistant' && !message.streaming && message.truncated" class="truncated-retry-bar">
                  <el-button
                    type="primary"
                    size="small"
                    :icon="Refresh"
                    plain
                    @click="handleTruncatedRetry(message)"
                  >完整重试 (基于 25 秒截断,可换种问法)</el-button>
                </div>

                <!-- P2 guardrail: warn when backend detected numeric hallucination -->
                <el-alert
                  v-if="message.role === 'assistant' && !message.streaming && message.warning"
                  :title="message.warning"
                  type="warning"
                  :closable="false"
                  show-icon
                  class="message-warning"
                />


                <!-- AI 洞察面板 (only show if insights has actual content) -->
                <div v-if="message.insights && ((message.insights.positive?.items?.length ?? 0) > 0 || (message.insights.negative?.items?.length ?? 0) > 0 || (message.insights.suggestions?.items?.length ?? 0) > 0)" class="message-insights">
                  <AIInsightPanel
                    :insight="message.insights"
                    title="AI 分析洞察"
                    :collapsible="true"
                    :default-expanded="true"
                  />
                </div>

                <!-- 图表展示 (only show if chart has proper ECharts option) -->
                <div v-if="(message.chartConfig && message.chartConfig.option) || message.chart" class="message-chart">
                  <div :id="`chart-${message.id}`" class="chart-container"></div>
                </div>
                <!-- Chart Insight for single-chart message (bar/line/pie wired; exotic→null→renders nothing) -->
                <ChartInsightProvider
                  v-if="message.chartConfig && message.chartConfig.option && message.source !== 'restaurant_owner_action'"
                  :chart="messageChartWithMeta(message)"
                  :perms="{ canViewFinance: false }"
                  :factory-id="factoryId ?? ''"
                  depth="detailed"
                />
                <!-- P2 综合分析: multi-chart stack (评价/门店/VIP/时段 …) -->
                <div v-if="message.charts && message.charts.length" class="message-chart">
                  <div
                    v-for="(c, ci) in message.charts"
                    :key="ci"
                    :id="`chart-${message.id}__${ci}`"
                    class="chart-container"
                  ></div>
                </div>
                <!-- Chart Insight for P2 multi-chart: each ChartInsightProvider has its own setup() → v-for safe -->
                <div
                  v-if="message.source === 'restaurant_owner_action' && message.chartGuide"
                  class="owner-chart-guide"
                >
                  <strong>怎么看这几张图：</strong>{{ message.chartGuide }}
                </div>
                <template v-if="message.source !== 'restaurant_owner_action' && message.charts && message.charts.length">
                  <ChartInsightProvider
                    v-for="(c, ci) in message.charts"
                    :key="`insight-${message.id}__${ci}`"
                    :chart="multiChartWithMeta(c)"
                    :perms="{ canViewFinance: false }"
                    :factory-id="factoryId ?? ''"
                    depth="detailed"
                  />
                </template>

                <!-- P1 (2026-06-02): expandable 字段说明 / 怎么看这张图 (zero extra call). -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && message.source !== 'restaurant_owner_action' && ((message.glossary && Object.keys(message.glossary).length > 0) || message.chartGuide)"
                  class="message-depth-block"
                >
                  <el-link
                    type="info"
                    underline="never"
                    class="depth-toggle"
                    @click="message.depthExpanded = !message.depthExpanded"
                  >
                    <el-icon><QuestionFilled /></el-icon>
                    {{ message.depthExpanded ? '收起说明' : '字段说明 / 怎么看这张图' }}
                  </el-link>
                  <div v-show="message.depthExpanded" class="depth-content">
                    <div v-if="message.chartGuide" class="depth-chart-guide">
                      <strong>怎么看这张图：</strong>{{ message.chartGuide }}
                    </div>
                    <dl v-if="message.glossary && Object.keys(message.glossary).length > 0" class="depth-glossary">
                      <template v-for="(def, term) in message.glossary" :key="term">
                        <dt>{{ term }}</dt>
                        <dd>{{ def }}</dd>
                      </template>
                    </dl>
                  </div>
                </div>

                <!-- NL2SQL 结果面板 -->
                <div v-if="message.sqlResult" class="sql-result-panel">
                  <div class="sql-meta">
                    <el-tag size="small" :type="message.sqlResult.success ? 'success' : 'danger'">
                      {{ message.sqlResult.intent || 'QUERY' }}
                    </el-tag>
                    <span v-if="message.sqlResult.confidence" class="sql-confidence">
                      置信度 {{ (message.sqlResult.confidence * 100).toFixed(0) }}%
                    </span>
                    <span v-if="message.sqlResult.executionTimeMs" class="sql-time">
                      {{ message.sqlResult.executionTimeMs }}ms
                    </span>
                    <span v-if="message.sqlResult.rowCount != null" class="sql-rows">
                      {{ message.sqlResult.rowCount }} 行
                    </span>
                  </div>
                  <div v-if="message.sqlResult.sql" class="sql-code">
                    <pre><code>{{ message.sqlResult.sql }}</code></pre>
                  </div>
                  <div v-if="message.sqlResult.warnings?.length" class="sql-warnings">
                    <el-tag v-for="(w, i) in message.sqlResult.warnings" :key="i" size="small" type="warning" style="margin: 2px">
                      {{ w }}
                    </el-tag>
                  </div>
                </div>

                <!-- 表格展示 -->
                <div v-if="message.table" class="message-table">
                  <el-table :data="message.table.data" stripe border size="small" max-height="400">
                    <el-table-column
                      v-for="col in message.table.columns"
                      :key="col"
                      :label="col"
                      :prop="col"
                      min-width="100"
                    />
                  </el-table>
                </div>

                <!-- Fix 3 (Apr 23 2026): deep-analysis CTA after template hits.
                     Only shown for assistant messages that were served from the
                     materialized cache — i.e. templated answer with structured
                     numbers but no LLM reasoning. LLM fallback answers already
                     contain analysis, so no CTA for those. -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && message.source === 'materialized_cache'"
                  class="message-deep-analysis"
                >
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="ChatDotRound"
                    @click="triggerDeepAnalysis(message)"
                  >
                    深入分析 / 为什么这样
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    @click="triggerImprovementSuggestions(message)"
                  >
                    给出改进建议
                  </el-button>
                </div>

                <!-- Related follow-ups: 3 cross-dim template-hit queries per code -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && message.source === 'materialized_cache' && relatedFollowups(message.templateCode).length > 0"
                  class="message-related-followups"
                >
                  <span class="related-label">相关追问:</span>
                  <el-button
                    v-for="(q, i) in relatedFollowups(message.templateCode)"
                    :key="i"
                    size="small"
                    type="info"
                    plain
                    round
                    @click="triggerRelatedFollowup(q)"
                  >
                    {{ q }}
                  </el-button>
                </div>

                <!-- P1 (2026-06-02): gold-tool dynamic follow-up chips.
                     Independent of materialized_cache source — gold answers
                     carry suggestedFollowups directly. -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && (message.suggestedFollowups?.length ?? 0) > 0"
                  class="message-related-followups"
                >
                  <span class="related-label">继续追问:</span>
                  <el-button
                    v-for="(f, i) in message.suggestedFollowups"
                    :key="i"
                    size="small"
                    type="info"
                    plain
                    round
                    @click="triggerRelatedFollowup(f.question, f.ownerActionScenario)"
                  >
                    {{ f.label }}
                  </el-button>
                </div>

                <!-- P1-B (2026-06-03): time-variant follow-up chips for restaurant tenants.
                     Only shown when the backend suggestedFollowups are absent (to avoid
                     crowding the UI when the gold tool already returned rich follow-ups).
                     Looks at the preceding user message to decide which time variants to show. -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && !(message.suggestedFollowups?.length) && timeFollowups(msgIdx > 0 ? chatHistory[msgIdx - 1]?.content : '').length > 0"
                  class="message-related-followups"
                >
                  <span class="related-label">换个时间:</span>
                  <el-button
                    v-for="(f, i) in timeFollowups(msgIdx > 0 ? chatHistory[msgIdx - 1]?.content : '')"
                    :key="i"
                    size="small"
                    type="success"
                    plain
                    round
                    @click="triggerRelatedFollowup(f.question)"
                  >
                    {{ f.label }}
                  </el-button>
                </div>

                <!-- Feedback for both LLM and template answers (Apr 24 2026 extended).
                     Template hits get a logId via _log_template_hit_safe in chat.py. -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && (message.logId || message.feedbackTarget)"
                  class="message-feedback"
                >
                  <span class="feedback-label">这个回答有用吗?</span>
                  <el-button
                    size="small"
                    :type="message.feedbackValue === 1 ? 'success' : 'default'"
                    plain
                    @click="sendFeedback(message, 1)"
 > 有用</el-button>
                  <el-button
                    size="small"
                    :type="message.feedbackValue === -1 ? 'danger' : 'default'"
                    plain
                    @click="sendFeedback(message, -1)"
 > 不准确</el-button>
                </div>
              </template>
            </div>
          </div>
        </div>

        <!-- Template section - shown when only welcome message exists (no real conversation) -->
        <div v-if="chatHistory.length <= 1" class="template-section">
          <h3 class="template-title">
            <el-icon><Cpu /></el-icon> 选择分析模板
          </h3>

          <!-- Category tabs -->
          <div class="template-categories">
            <el-button
              v-for="cat in templateCategories"
              :key="cat.key"
              :type="selectedCategory === cat.key ? 'primary' : 'default'"
              size="small"
              round
              @click="selectedCategory = selectedCategory === cat.key ? '' : cat.key"
            >
              {{ cat.label }}
            </el-button>
            <el-button
              v-if="selectedCategory"
              size="small"
              text
              @click="selectedCategory = ''"
            >
              全部
            </el-button>
          </div>

          <!-- Template cards grid -->
          <div class="template-grid">
            <div
              v-for="tpl in filteredTemplates"
              :key="tpl.id"
              class="template-card"
              @click="useTemplate(tpl)"
            >
              <div class="template-card-header">
                <el-icon :size="20"><component :is="tpl.icon" /></el-icon>
                <span class="template-label">{{ tpl.label }}</span>
              </div>
              <p class="template-desc">{{ tpl.description }}</p>
            </div>
          </div>
        </div>
      </div>

      <!-- 快捷问题 (hide when conversation has started to save space) -->
      <!-- P1-B: RESTAURANT 租户展示分组目录; 其他租户展示扁平 chip 列表 (原有逻辑) -->
      <div v-if="chatHistory.length <= 2 && isRestaurantTenant" class="quick-questions quick-questions--grouped">
        <span class="label">老板问什么:</span>
        <div class="questions-grouped">
          <div
            v-for="group in RESTAURANT_QUICK_GROUPS"
            :key="group.label"
            class="questions-group"
          >
            <span class="group-label">{{ group.label }}</span>
            <el-button
              v-for="(q, qi) in group.questions"
              :key="qi"
              size="small"
              round
              @click="handleQuickQuestion(q)"
            >
              {{ q }}
            </el-button>
          </div>
        </div>
      </div>
      <div v-else-if="chatHistory.length <= 2" class="quick-questions">
        <span class="label">快捷问题:</span>
        <div class="questions-list">
          <el-button
            v-for="(q, index) in quickQuestions"
            :key="index"
            size="small"
            round
            @click="handleQuickQuestion(q)"
          >
            {{ q }}
          </el-button>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <el-autocomplete
          v-model="inputQuery"
          ref="inputRef"
          class="query-autocomplete"
          :fetch-suggestions="fetchAutocomplete"
          :placeholder="nl2sqlMode ? '输入数据查询，例如：各产品的销售额汇总' : '输入您的问题（下拉有 40+ 模板秒回问题可选）'"
          :disabled="isTyping"
          :trigger-on-focus="true"
          popper-class="query-autocomplete-popper"
          value-key="value"
          clearable
          @keydown="handleKeydown"
          @select="handleSuggestionSelect"
        >
          <template #default="{ item }">
            <div class="suggestion-item">
              <span class="suggestion-text">{{ item.value }}</span>
 <span class="suggestion-tag"> 秒回</span>
            </div>
          </template>
        </el-autocomplete>
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="isTyping"
          :disabled="!inputQuery.trim()"
          @click="handleSendMessage"
        >
          发送
        </el-button>
      </div>

      <!-- 措施③ "猜你想问" 推荐区: 输入框下方. 列出该工厂已物化的模板, 点 chip
           直接命中物化缓存 (0 token, ~230ms). 仅在有已物化模板时展示. -->
      <div v-if="recommendChips.length > 0" class="recommend-area">
        <span class="recommend-label">
          <el-icon><MagicStick /></el-icon> 猜你想问:
        </span>
        <div class="recommend-chips">
          <el-button
            v-for="chip in recommendChips"
            :key="chip.code"
            class="recommend-chip"
            size="small"
            round
            :type="chip.isRecommended ? 'primary' : 'default'"
            :plain="!chip.isRecommended"
            :disabled="isTyping"
            @click="handleRecommendChip(chip)"
          >
            {{ recommendChipLabel(chip) }}
            <el-tag
              v-if="chip.materializationCount > 0"
              class="recommend-chip-badge"
              size="small"
              :type="chip.isRecommended ? 'success' : 'info'"
              effect="plain"
              round
            >已物化 {{ chip.materializationCount }}</el-tag>
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.ai-query-page {
  padding: 20px;
  height: calc(100vh - var(--header-height, 64px) - 80px);
  display: flex;
  flex-direction: column;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  flex-shrink: 0;
  flex-wrap: wrap;  // E1 Apr 17 2026: mobile 窄屏 header-left + header-right 换行, 避免标题被挤竖排
  gap: 8px;

  .header-left {
    flex-shrink: 0;
    min-width: 0;  // allow flex child to shrink below content size

    h1 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 12px 0 0;
      font-size: 20px;
      font-weight: 600;
      white-space: nowrap;  // 禁止 "AI 智能问答" 4 字竖排换行

      .el-icon {
        color: var(--color-primary);
      }
    }
  }
}

.chat-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color, #fff);
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

// 对话历史
.chat-history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}

.chat-message {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;

  &.user {
    flex-direction: row-reverse;

    .message-content {
      align-items: flex-end;
    }

    .message-header {
      flex-direction: row-reverse;
    }

    .message-body {
      background: var(--color-primary);
      color: #fff;
      border-radius: 12px 0 12px 12px;
    }

    .message-text {
      color: #fff;
    }
  }

  &.assistant {
    .message-body {
      background: var(--el-fill-color-light, #f5f7fa);
      border-radius: 0 12px 12px 12px;
    }
  }

  .message-avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    background: var(--color-primary);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;

    .el-icon {
      font-size: 20px;
      color: #fff;
    }
  }

  &.assistant .message-avatar {
    background: var(--el-color-success, #67C23A);
  }

  .message-content {
    display: flex;
    flex-direction: column;
    max-width: 70%;
  }

  .message-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;

    .role-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--el-text-color-primary, #303133);
    }

    .message-time {
      font-size: 12px;
      color: var(--el-text-color-secondary, #909399);
    }
  }

  .message-body {
    padding: 12px 16px;
  }

  .message-text {
    font-size: 14px;
    line-height: 1.8;
    color: var(--el-text-color-primary, #303133);
    white-space: pre-wrap;

    // Markdown body styles for assistant messages
    &.markdown-body {
      white-space: normal;

      :deep(p) { margin: 0.4em 0; }
      :deep(h1), :deep(h2), :deep(h3) { margin: 0.6em 0 0.3em; font-weight: 600; }
      :deep(h3) { font-size: 15px; }
      :deep(ul), :deep(ol) { padding-left: 1.5em; margin: 0.3em 0; }
      :deep(li) { margin: 0.15em 0; }
      :deep(strong) { font-weight: 600; }
      :deep(code) { background: rgba(0,0,0,0.06); padding: 2px 4px; border-radius: 3px; font-size: 13px; }
      :deep(pre) { background: rgba(0,0,0,0.04); padding: 8px 12px; border-radius: 6px; overflow-x: auto; }
      :deep(blockquote) { border-left: 3px solid var(--color-primary); padding-left: 12px; margin: 0.4em 0; color: var(--el-text-color-regular, #606266); }
      :deep(table) { border-collapse: collapse; margin: 0.5em 0; }
      :deep(th), :deep(td) { border: 1px solid var(--el-border-color, #dcdfe6); padding: 4px 8px; font-size: 13px; }
      :deep(th) { background: var(--el-fill-color-light, #f5f7fa); }
    }

    // Streaming plain text — pre-wrap preserves newlines, no markdown overhead
    // Blinking cursor gives "AI is typing" perception (like Power BI Copilot)
    &.streaming-text {
      white-space: pre-wrap;

      &::after {
        content: '▍';
        display: inline;
        animation: cursor-blink 0.6s steps(2) infinite;
        color: var(--el-color-primary, #2D8B57);
        font-weight: 300;
        margin-left: 1px;
      }
    }
  }

  @keyframes cursor-blink {
    0% { opacity: 1; }
    50% { opacity: 0; }
    100% { opacity: 1; }
  }

  .loading-indicator {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--el-text-color-secondary, #909399);

    .el-icon {
      font-size: 16px;
    }

    .sse-warning-hint {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      margin-left: 8px;
      color: var(--el-color-warning, #E6A23C);
      font-size: 12px;
      animation: fade-in 0.3s ease-in;
    }
  }

  .sse-retry-area {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 4px 0;

    .sse-retry-text {
      color: var(--el-text-color-secondary, #909399);
      font-size: 14px;
    }
  }

  @keyframes fade-in {
    from { opacity: 0; }
    to { opacity: 1; }
  }

  .message-insights {
    margin-top: 16px;
    max-width: 500px;
  }

  .message-warning {
    margin-top: 12px;
    max-width: 640px;
    font-size: 13px;
  }

  .message-chart {
    margin-top: 16px;

    .chart-container {
      height: 250px;
      width: 100%;
      min-width: 300px;
    }
  }

  /* P1 (2026-06-02): expandable 字段说明 / 怎么看这张图 block. */
  .owner-chart-guide {
    margin-top: 10px;
    padding: 10px 12px;
    border-left: 3px solid #2d8b57;
    background: #f4fbf7;
    color: #303133;
    font-size: 13px;
    line-height: 1.65;
  }

  .message-depth-block {
    margin-top: 8px;
  }
  .depth-toggle {
    font-size: 12px;
  }
  .depth-content {
    margin-top: 6px;
    padding: 8px 12px;
    background: #f5f7fa;
    border-radius: 6px;
    font-size: 13px;
    line-height: 1.6;
  }
  .depth-chart-guide {
    margin-bottom: 6px;
    color: #303133;
  }
  .depth-glossary dt {
    font-weight: 600;
    color: #303133;
    margin-top: 4px;
  }
  .depth-glossary dd {
    margin: 0 0 4px 0;
    color: #606266;
  }

  .message-table {
    margin-top: 16px;
    max-width: 500px;
  }

  // Fix 3 (Apr 23 2026): deep-analysis CTA after template answers
  .message-deep-analysis {
    display: flex;
    gap: 8px;
    margin-top: 12px;
    flex-wrap: wrap;
  }

  .message-related-followups {
    display: flex;
    gap: 6px;
    margin-top: 10px;
    flex-wrap: wrap;
    align-items: center;

    .related-label {
      font-size: 12px;
      color: var(--el-text-color-secondary, #909399);
      margin-right: 4px;
    }
  }

  // Phase 1 (Apr 23 2026): feedback for LLM answers
  .message-feedback {
    display: flex;
    gap: 8px;
    margin-top: 12px;
    align-items: center;

    .feedback-label {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
}

// 快捷问题
.quick-questions {
  padding: 12px 20px;
  border-top: 1px solid var(--el-border-color-light, #ebeef5);
  background: var(--el-fill-color-lighter, #fafafa);

  .label {
    font-size: 13px;
    color: var(--el-text-color-secondary, #909399);
    margin-right: 12px;
  }

  .questions-list {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  // P1-B: grouped layout for restaurant tenants
  &--grouped {
    padding-bottom: 16px;

    .label {
      display: block;
      margin-bottom: 10px;
      font-size: 13px;
      font-weight: 500;
      color: var(--el-text-color-secondary, #909399);
    }

    .questions-grouped {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .questions-group {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;

      .group-label {
        font-size: 12px;
        color: var(--el-text-color-placeholder, #c0c4cc);
        font-weight: 600;
        min-width: 52px;
        text-align: right;
        padding-right: 4px;
        flex-shrink: 0;
      }
    }
  }
}

// 输入区域
.input-area {
  display: flex;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: var(--el-bg-color, #fff);

  .query-autocomplete {
    flex: 1;

    :deep(.el-input__wrapper) {
      border-radius: 8px;
    }
  }

  :deep(.el-textarea) {
    flex: 1;

    .el-textarea__inner {
      resize: none;
      border-radius: 8px;
    }
  }

  .el-button {
    align-self: flex-end;
    height: 40px;
    padding: 0 24px;
  }
}

// 措施③ "猜你想问" 推荐区
.recommend-area {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 20px 14px;
  background: var(--el-bg-color, #fff);

  .recommend-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary, #909399);
    white-space: nowrap;
    padding-top: 4px;
  }

  .recommend-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .recommend-chip {
    display: inline-flex;
    align-items: center;

    .recommend-chip-badge {
      margin-left: 6px;
      transform: scale(0.85);
    }
  }
}

// 分析模板
.template-section {
  padding: 20px;
}

.template-title {
  margin: 0 0 16px;
  color: #303133;
  font-size: 18px;
  display: flex;
  align-items: center;
  gap: 6px;

  .el-icon {
    color: var(--color-primary);
  }
}

.template-categories {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.template-card {
  padding: 16px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    border-color: var(--el-color-primary, #2D8B57);
    box-shadow: 0 4px 12px rgba(45, 139, 87, 0.15);
    transform: translateY(-2px);
  }
}

.template-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--el-text-color-primary, #303133);
  font-weight: 600;
}

.template-label {
  font-size: 14px;
}

.template-desc {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.5;
}

// NL2SQL 结果面板
.sql-result-panel {
  margin-top: 8px;

  .sql-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
    flex-wrap: wrap;

    .sql-confidence,
    .sql-time,
    .sql-rows {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .sql-code {
    background: #1e1e2e;
    border-radius: 8px;
    padding: 12px;
    overflow-x: auto;
    margin-bottom: 6px;

    pre {
      margin: 0;
    }

    code {
      color: #cdd6f4;
      font-family: 'Fira Code', 'Menlo', monospace;
      font-size: 12px;
      white-space: pre-wrap;
      word-break: break-all;
    }
  }

  .sql-warnings {
    margin-top: 4px;
  }
}

// 响应式
@media (max-width: 768px) {
  .ai-query-page {
    padding: 12px;
    height: calc(100vh - var(--header-height, 56px) - 24px);
  }

  // E1 Apr 17 2026: 窄屏 header 改纵向排 (标题在上, 数据源在下)
  // 避免 "AI 智能问答" 4 字被容器挤到竖排 (用户截图 bug)
  .page-header {
    flex-direction: column;
    align-items: stretch;

    .header-left h1 {
      font-size: 18px;
      margin: 0;
    }

    .header-right {
      width: 100%;
    }
  }

  .chat-message {
    .message-content {
      max-width: 90%;
    }
  }

  .quick-questions {
    .questions-list {
      max-height: 120px;
      overflow-y: auto;
    }
  }

  .template-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style lang="scss">
.query-autocomplete-popper {
  .suggestion-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    line-height: 1.4;
    padding: 2px 0;
  }
  .suggestion-text {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .suggestion-tag {
    font-size: 11px;
    color: #67c23a;
    margin-left: 12px;
    flex-shrink: 0;
  }
}
</style>
