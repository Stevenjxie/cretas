/**
 * AI 飞轮运营台 API — 卡5 (web-admin) / 卡5b (Python 后端) 共用契约。
 *
 * Backend (卡5b, 未就绪时见下方 MOCK 层): Python /api/smartbi/flywheel/*
 *   GET  /overview?domain=&days=
 *   GET  /candidates?domain=&status=
 *   POST /candidates/approve   { id, domain, note? }
 *   POST /candidates/reject    { id, domain, reason }
 *   POST /candidates/seed-import  { domain, queries: string[] }  (扩展点, 契约卡未列出但页面 2 "manual_seed
 *        批量导入" 需要; 卡5b 落地后如端点不同, 只需改本文件 seedImportCandidates 一处)
 *   GET  /misses?domain=&status=
 *   POST /misses/status        { id, domain, status }  (扩展点, 页面 3 "标注处理状态" 需要, 契约卡未列出)
 *   GET  /quality?domain=&days=
 *   POST /dataset/export       { domain, start_date?, end_date?, contract_pass?, served?, feedback? }
 *
 * ============ MOCK 切换说明 ============
 * `FORCE_MOCK` 常量: true = 无论后端是否就绪, 一律用本文件内 mock 生成器(用于本地/演示/UI 冻结验收)。
 * 默认 FORCE_MOCK=false: 每个函数先尝试真实请求, 若失败(网络错误/404/500, 卡5b 尚未部署时的正常状态)
 * 才回退 mock 并 console.warn 提示。**卡5b 后端上线后不需要改代码** — 请求会自然转为成功。
 * 若要在后端已上线后仍强制看 mock (例如演示脚本), 把 FORCE_MOCK 改 true 即可, 或未来可换成
 * `import.meta.env.VITE_FLYWHEEL_MOCK === 'true'` 环境变量开关。
 */
import { adminGet, adminPost } from '../request';
import { PYTHON_SMARTBI_URL } from './common';

const BASE = `${PYTHON_SMARTBI_URL}/api/smartbi/flywheel`;

const FORCE_MOCK = false;

// ==================== Types ====================

export type FlywheelDomain = 'restaurant' | 'factory';

export interface FlywheelWindowMetrics {
  qa_count: number;
  llm_calls: number;
  cache_hit_rate: number; // 0-1
  promotion_hit_rate: number; // 0-1
  token_estimate: number;
  contract_fail_rate: number; // 0-1
  clarify_rate: number; // 0-1
  thumbs_up: number;
  thumbs_down: number;
}

export interface TierDistributionRow {
  tier: string; // T1 / T2 / T3
  label: string;
  count: number;
  pct: number; // 0-100
}

export interface FlywheelOverview {
  domain: string;
  generated_at: string;
  today: FlywheelWindowMetrics;
  d7: FlywheelWindowMetrics;
  d30: FlywheelWindowMetrics;
  tier_distribution: TierDistributionRow[];
}

export type CandidateStatus = 'pending' | 'approved' | 'rejected';
export type CandidateSource = 'auto' | 'manual_seed';

export interface FlywheelCandidate {
  id: string;
  domain: string;
  query_text: string;
  frequency: number;
  confidence: number; // 0-1
  contract_pass_rate: number; // 0-1
  plan_json: Record<string, unknown>;
  sample_answer: string;
  status: CandidateStatus;
  source: CandidateSource;
  created_at: string;
  reviewed_at?: string | null;
  reviewed_by?: string | null;
  reject_reason?: string | null;
}

export interface FlywheelMiss {
  id: string;
  domain: string;
  query_text: string;
  template_code: string;
  count: number;
  first_seen: string;
  last_seen: string;
  status: 'open' | 'triaged' | 'resolved' | 'wontfix';
}

export interface ContractFailureRow {
  id: string;
  ts: string;
  domain: string;
  query_text: string;
  contract_name: string;
  error_detail: string;
}

export interface NegativeFeedbackPair {
  id: string;
  ts: string;
  domain: string;
  query_text: string;
  answer_text: string;
  feedback: 'up' | 'down';
  note?: string | null;
}

export interface BatteryTrendRow {
  date: string;
  total: number;
  passed: number;
  failed: number;
  pass_rate: number; // 0-100
}

export interface FlywheelQuality {
  domain: string;
  contract_failures: ContractFailureRow[];
  negative_feedback: NegativeFeedbackPair[];
  battery_trend: BatteryTrendRow[];
}

export interface DatasetExportParams {
  domain: string;
  start_date?: string;
  end_date?: string;
  contract_pass?: boolean;
  served?: boolean;
  feedback?: 'up' | 'down' | 'any';
}

export interface DatasetExportResult {
  count: number;
  jsonl: string;
}

// ==================== Mock generators ====================

function seededRandom(seed: number) {
  let s = seed;
  return () => {
    s = (s * 9301 + 49297) % 233280;
    return s / 233280;
  };
}
const rnd = seededRandom(42);

function mockWindowMetrics(scale: number): FlywheelWindowMetrics {
  const qa = Math.round(scale * (80 + rnd() * 40));
  return {
    qa_count: qa,
    llm_calls: Math.round(qa * (0.35 + rnd() * 0.1)),
    cache_hit_rate: 0.55 + rnd() * 0.15,
    promotion_hit_rate: 0.22 + rnd() * 0.1,
    token_estimate: Math.round(qa * (180 + rnd() * 60)),
    contract_fail_rate: 0.01 + rnd() * 0.02,
    clarify_rate: 0.04 + rnd() * 0.03,
    thumbs_up: Math.round(qa * (0.28 + rnd() * 0.08)),
    thumbs_down: Math.round(qa * (0.02 + rnd() * 0.02)),
  };
}

export function buildMockOverview(domain: string): FlywheelOverview {
  return {
    domain,
    generated_at: new Date().toISOString(),
    today: mockWindowMetrics(1),
    d7: mockWindowMetrics(7),
    d30: mockWindowMetrics(30),
    tier_distribution: [
      { tier: 'T1', label: 'T1 关键词直答', count: 412, pct: 46 },
      { tier: 'T2', label: 'T2 向量晋升', count: 268, pct: 30 },
      { tier: 'T3', label: 'T3 LLM 兜底', count: 214, pct: 24 },
    ],
  };
}

const SAMPLE_QUERIES = [
  '这个月万达店营业额多少', '上周哪个门店卖得最好', '毛利率最低的三个菜品',
  '本月和上月比营收涨了多少', '哪个菜的复购率最高', '昨天的客单价是多少',
  '这周有没有异常波动的门店', '牛肉类目本月成本涨了多少', '新店开业后销售趋势怎样',
  '周末和工作日营业额差多少', '哪几个菜最近卖得少了', '本季度净利润率',
];

export function buildMockCandidates(domain: string): FlywheelCandidate[] {
  return SAMPLE_QUERIES.map((q, i) => ({
    id: `cand-${domain}-${i + 1}`,
    domain,
    query_text: q,
    frequency: Math.round(8 + rnd() * 60),
    confidence: Math.round((0.6 + rnd() * 0.38) * 100) / 100,
    contract_pass_rate: Math.round((0.7 + rnd() * 0.28) * 100) / 100,
    plan_json: {
      intent: 'sales_summary',
      metric: ['revenue', 'gross_margin'][i % 2],
      time_range: { type: 'relative', value: 'last_7d' },
      focus_entity: i % 3 === 0 ? { type: 'store', name: '万达店' } : null,
      group_by: i % 2 === 0 ? ['store'] : ['dish'],
    },
    sample_answer: `根据最近数据: ${q.includes('营业额') || q.includes('营收') ? '本期营业额约 ¥128,400, 环比 +6.2%。' : '已生成对应分析结果, 详见图表。'}`,
    status: (i < 8 ? 'pending' : i < 10 ? 'approved' : 'rejected') as CandidateStatus,
    source: i % 5 === 4 ? 'manual_seed' : 'auto',
    created_at: new Date(Date.now() - i * 86400000).toISOString(),
    reviewed_at: i >= 8 ? new Date(Date.now() - (i - 8) * 3600000).toISOString() : null,
    reviewed_by: i >= 8 ? 'platform_admin' : null,
    reject_reason: i >= 10 ? '问法覆盖率不足, 建议再观察一周' : null,
  }));
}

export function buildMockMisses(domain: string): FlywheelMiss[] {
  const misses = [
    '隔壁老王家最近怎么样', '帮我订明天的食材', '这个月天气对客流影响多大',
    '竞对门店价格怎么样', '能不能自动生成月报发我邮箱', '客如云的库存和我们对得上吗',
  ];
  return misses.map((q, i) => ({
    id: `miss-${domain}-${i + 1}`,
    domain,
    query_text: q,
    template_code: 'RESTAURANT_OPS_MISS',
    count: Math.round(2 + rnd() * 20),
    first_seen: new Date(Date.now() - (20 - i) * 86400000).toISOString(),
    last_seen: new Date(Date.now() - i * 3600000).toISOString(),
    status: (['open', 'open', 'triaged', 'open', 'resolved', 'wontfix'] as const)[i % 6],
  }));
}

export function buildMockQuality(domain: string): FlywheelQuality {
  const contractFailures: ContractFailureRow[] = Array.from({ length: 6 }).map((_, i) => ({
    id: `cf-${domain}-${i + 1}`,
    ts: new Date(Date.now() - i * 5400000).toISOString(),
    domain,
    query_text: SAMPLE_QUERIES[i % SAMPLE_QUERIES.length],
    contract_name: ['NoFabricatedNumber', 'DateRangeRequired', 'MetricWhitelist'][i % 3],
    error_detail: '计划 JSON 缺少 time_range 字段, 契约要求所有 sales_summary 意图必须显式区间',
  }));
  const negativeFeedback: NegativeFeedbackPair[] = Array.from({ length: 5 }).map((_, i) => ({
    id: `nf-${domain}-${i + 1}`,
    ts: new Date(Date.now() - i * 7200000).toISOString(),
    domain,
    query_text: SAMPLE_QUERIES[(i + 3) % SAMPLE_QUERIES.length],
    answer_text: '本期营收约 ¥98,200, 环比下降 3.1%。',
    feedback: 'down',
    note: i % 2 === 0 ? '门店范围理解错了' : null,
  }));
  const batteryTrend: BatteryTrendRow[] = Array.from({ length: 14 }).map((_, i) => {
    const total = 52;
    const passed = Math.round(total * (0.82 + rnd() * 0.15));
    return {
      date: new Date(Date.now() - (13 - i) * 86400000).toISOString().slice(0, 10),
      total,
      passed,
      failed: total - passed,
      pass_rate: Math.round((passed / total) * 1000) / 10,
    };
  });
  return { domain, contract_failures: contractFailures, negative_feedback: negativeFeedback, battery_trend: batteryTrend };
}

export function buildMockDatasetExport(params: DatasetExportParams): DatasetExportResult {
  const rows = SAMPLE_QUERIES.slice(0, 8).map((q, i) => ({
    query: q,
    domain: params.domain,
    plan: { intent: 'sales_summary', metric: 'revenue' },
    served: true,
    contract_pass: i % 5 !== 4,
    feedback: i % 4 === 0 ? 'up' : i % 4 === 1 ? 'down' : null,
  }));
  return { count: rows.length, jsonl: rows.map((r) => JSON.stringify(r)).join('\n') };
}

// ==================== API (real-first, mock fallback) ====================

async function withMockFallback<T>(real: () => Promise<T>, mock: () => T, label: string): Promise<T> {
  if (FORCE_MOCK) return mock();
  try {
    return await real();
  } catch (e) {
    // 卡5b 后端未就绪期间的预期路径 — 不视为错误, 静默降级到 mock 供 UI 联调/演示。
    console.warn(`[ai-flywheel] ${label} 真实接口不可用, 回退 mock:`, e instanceof Error ? e.message : e);
    return mock();
  }
}

// _silent: true — 卡5b 后端未就绪期间 404/500 是预期路径 (withMockFallback 会静默降级),
// 不应该弹全局 sticky error toast 吓用户; 见 src/api/request.ts 的 _silent 拦截器约定。
const SILENT = { _silent: true };

export const flywheelApi = {
  overview: (domain: string, days: number) =>
    withMockFallback(
      async () =>
        (await adminGet<FlywheelOverview>(`${BASE}/overview?domain=${encodeURIComponent(domain)}&days=${days}`, SILENT))
          .data as FlywheelOverview,
      () => buildMockOverview(domain),
      'overview',
    ),

  candidates: (domain: string, status?: CandidateStatus) =>
    withMockFallback(
      async () =>
        (
          await adminGet<FlywheelCandidate[]>(
            `${BASE}/candidates?domain=${encodeURIComponent(domain)}${status ? '&status=' + status : ''}`,
            SILENT,
          )
        ).data as FlywheelCandidate[],
      () => buildMockCandidates(domain),
      'candidates',
    ),

  approveCandidate: (id: string, domain: string, note?: string) =>
    withMockFallback(
      async () => (await adminPost<{ id: string }>(`${BASE}/candidates/approve`, { id, domain, note }, SILENT)).data,
      () => ({ id }),
      'candidates/approve',
    ),

  rejectCandidate: (id: string, domain: string, reason: string) =>
    withMockFallback(
      async () => (await adminPost<{ id: string }>(`${BASE}/candidates/reject`, { id, domain, reason }, SILENT)).data,
      () => ({ id }),
      'candidates/reject',
    ),

  seedImportCandidates: (domain: string, queries: string[]) =>
    withMockFallback(
      async () =>
        (await adminPost<{ imported: number }>(`${BASE}/candidates/seed-import`, { domain, queries }, SILENT))
          .data as { imported: number },
      () => ({ imported: queries.length }),
      'candidates/seed-import',
    ),

  misses: (domain: string, status?: string) =>
    withMockFallback(
      async () =>
        (
          await adminGet<FlywheelMiss[]>(
            `${BASE}/misses?domain=${encodeURIComponent(domain)}${status ? '&status=' + status : ''}`,
            SILENT,
          )
        ).data as FlywheelMiss[],
      () => buildMockMisses(domain),
      'misses',
    ),

  updateMissStatus: (id: string, domain: string, status: FlywheelMiss['status']) =>
    withMockFallback(
      async () => (await adminPost<{ id: string }>(`${BASE}/misses/status`, { id, domain, status }, SILENT)).data,
      () => ({ id }),
      'misses/status',
    ),

  quality: (domain: string, days: number) =>
    withMockFallback(
      async () =>
        (await adminGet<FlywheelQuality>(`${BASE}/quality?domain=${encodeURIComponent(domain)}&days=${days}`, SILENT))
          .data as FlywheelQuality,
      () => buildMockQuality(domain),
      'quality',
    ),

  exportDataset: (params: DatasetExportParams) =>
    withMockFallback(
      async () => (await adminPost<DatasetExportResult>(`${BASE}/dataset/export`, params, SILENT)).data as DatasetExportResult,
      () => buildMockDatasetExport(params),
      'dataset/export',
    ),
};
