/**
 * AI 飞轮运营台 API — 卡5 (web-admin) / 卡5b (Python 后端) 共用契约。
 *
 * Backend (卡5b): Python /api/smartbi/flywheel/*
 *   GET  /overview?domain=&days=
 *   GET  /candidates?domain=&status=
 *   POST /candidates/approve   { id, domain, note? }             — ⚠️ ai_promoted_routes 表未就绪时返回
 *        503 (明确失败, 非假成功), 见 flywheelApi.approveCandidate 调用方必须正确展示该错误
 *   POST /candidates/reject    { id, domain, reason }
 *   POST /candidates/seed-import  { domain, queries: string[] }  (⚠️ 扩展点 — 卡5b 契约卡 6 端点未列出此项,
 *        页面 2 "manual_seed 批量导入" 需要, 待卡5b 补齐前真实模式下会 404, 按正常失败展示)
 *   GET  /misses?domain=&status=
 *   POST /misses/status        { id, domain, status }  (⚠️ 扩展点 — 同上, 待卡5b 补齐前真实模式下会 404)
 *   GET  /quality?domain=&days=
 *   POST /dataset/export       { domain, start_date?, end_date?, contract_pass?, served?, feedback? }
 *
 * ============ MOCK 切换说明 (2026-07-28 阻断项修复) ============
 * 核心原则 (CLAUDE.md #1 「禁止降级处理」): 真实请求失败绝不允许静默回落 mock —
 * 这是飞轮运营台, 晋升审核是人审闸门, 假数据可能被人工"通过"写进生产 ai_promoted_routes。
 *
 * `FORCE_MOCK` 常量 + `FLYWHEEL_MOCK_ACTIVE` 导出常量 = `import.meta.env.DEV && FORCE_MOCK`:
 * - 只有「显式开关 FORCE_MOCK=true」且「本地 dev 构建 (import.meta.env.DEV)」同时成立才会用 mock。
 * - `import.meta.env.DEV` 在 `vite build` (生产构建) 下被 Vite 静态替换为 `false`, 整个
 *   `FLYWHEEL_MOCK_ACTIVE` 表达式常量折叠为 `false`, 所有 mock 分支被打包器判定为死代码 —
 *   **生产构建下即使有人忘记把 FORCE_MOCK 改回 false, mock 也不会生效**, 这是双重保险而非唯一防线。
 * - 生产构建里任何真实请求失败(网络错误/404/500/503)就是失败, 直接 reject 给调用方 (页面) 处理,
 *   不会被本文件吞掉或替换成假数据。
 * - GET 类 mock: 返回 mock 生成器数据, 页面需用 `FLYWHEEL_MOCK_ACTIVE` 显示醒目 MOCK 标识 (见
 *   FlywheelHeader.vue), 防止有人把 mock 截图当真实数据汇报。
 * - 写/导出类 mock (approve/reject/seed-import/misses-status/dataset-export): **不伪装成功**,
 *   直接 throw 明确错误, 提示"MOCK 模式下不执行真实写入", 调用方按正常失败路径展示。
 */
import { adminGet, adminPost } from '../request';
import { PYTHON_SMARTBI_URL } from './common';

const BASE = `${PYTHON_SMARTBI_URL}/api/smartbi/flywheel`;

const FORCE_MOCK = false;

/** mock 生效的唯一判定 — 页面用它渲染 MOCK 标识; 生产构建下恒为 false (Vite 常量折叠 + 死代码消除)。 */
export const FLYWHEEL_MOCK_ACTIVE = import.meta.env.DEV && FORCE_MOCK;

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

function buildMockOverview(domain: string): FlywheelOverview {
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

function buildMockCandidates(domain: string): FlywheelCandidate[] {
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

function buildMockMisses(domain: string): FlywheelMiss[] {
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

function buildMockQuality(domain: string): FlywheelQuality {
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

function buildMockDatasetExport(params: DatasetExportParams): DatasetExportResult {
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

// ==================== API (real-only; mock 只在 FLYWHEEL_MOCK_ACTIVE 下生效, 见文件头注释) ====================

// _silent: true — 页面自己按 AlertDashboard.vue 既定约定处理错误态 (本地 error ref + el-alert
// 常驻横幅 + 自建 sticky ElMessage(duration:0, showClose:true)), 不需要拦截器再弹一次通用 toast。
// ⚠️ 这不是"静默降级"的 _silent (那是本文件之前的阻断项) — 请求失败仍然 reject, 调用方 (页面
// load()/操作函数的 catch) 必须显式渲染错误, 不允许吞掉或换成假数据。
const SILENT = { _silent: true };

function mockWriteBlocked(action: string): never {
  throw new Error(`MOCK 模式下不执行真实写入 (${action}) — 请连接真实后端 (卡5b) 后再操作`);
}

export const flywheelApi = {
  overview: async (domain: string, days: number): Promise<FlywheelOverview> => {
    if (FLYWHEEL_MOCK_ACTIVE) return buildMockOverview(domain);
    return (
      await adminGet<FlywheelOverview>(`${BASE}/overview?domain=${encodeURIComponent(domain)}&days=${days}`, SILENT)
    ).data as FlywheelOverview;
  },

  candidates: async (domain: string, status?: CandidateStatus): Promise<FlywheelCandidate[]> => {
    if (FLYWHEEL_MOCK_ACTIVE) return buildMockCandidates(domain);
    return (
      await adminGet<FlywheelCandidate[]>(
        `${BASE}/candidates?domain=${encodeURIComponent(domain)}${status ? '&status=' + status : ''}`,
        SILENT,
      )
    ).data as FlywheelCandidate[];
  },

  approveCandidate: async (id: string, domain: string, note?: string) => {
    if (FLYWHEEL_MOCK_ACTIVE) mockWriteBlocked('晋升候选通过');
    // ⚠️ 卡5b: ai_promoted_routes 表未就绪时返回 503 (明确失败) — 走正常 reject 路径,
    // 由调用方 (Candidates.vue handleApprove) 的 catch 展示, 不当作成功处理。
    return (await adminPost<{ id: string }>(`${BASE}/candidates/approve`, { id, domain, note }, SILENT)).data;
  },

  rejectCandidate: async (id: string, domain: string, reason: string) => {
    if (FLYWHEEL_MOCK_ACTIVE) mockWriteBlocked('晋升候选否决');
    return (await adminPost<{ id: string }>(`${BASE}/candidates/reject`, { id, domain, reason }, SILENT)).data;
  },

  // 扩展点 (卡5b 6 端点契约未列出, 待补齐前真实模式下预期 404 — 按正常失败展示, 不是假成功)
  seedImportCandidates: async (domain: string, queries: string[]) => {
    if (FLYWHEEL_MOCK_ACTIVE) mockWriteBlocked('manual_seed 批量导入');
    return (
      await adminPost<{ imported: number }>(`${BASE}/candidates/seed-import`, { domain, queries }, SILENT)
    ).data as { imported: number };
  },

  misses: async (domain: string, status?: string): Promise<FlywheelMiss[]> => {
    if (FLYWHEEL_MOCK_ACTIVE) return buildMockMisses(domain);
    return (
      await adminGet<FlywheelMiss[]>(
        `${BASE}/misses?domain=${encodeURIComponent(domain)}${status ? '&status=' + status : ''}`,
        SILENT,
      )
    ).data as FlywheelMiss[];
  },

  // 扩展点 (同上, 待卡5b 补齐前真实模式下预期 404)
  updateMissStatus: async (id: string, domain: string, status: FlywheelMiss['status']) => {
    if (FLYWHEEL_MOCK_ACTIVE) mockWriteBlocked('Miss 处理状态更新');
    return (await adminPost<{ id: string }>(`${BASE}/misses/status`, { id, domain, status }, SILENT)).data;
  },

  quality: async (domain: string, days: number): Promise<FlywheelQuality> => {
    if (FLYWHEEL_MOCK_ACTIVE) return buildMockQuality(domain);
    return (
      await adminGet<FlywheelQuality>(`${BASE}/quality?domain=${encodeURIComponent(domain)}&days=${days}`, SILENT)
    ).data as FlywheelQuality;
  },

  exportDataset: async (params: DatasetExportParams): Promise<DatasetExportResult> => {
    if (FLYWHEEL_MOCK_ACTIVE) mockWriteBlocked('蒸馏数据集导出');
    return (await adminPost<DatasetExportResult>(`${BASE}/dataset/export`, params, SILENT)).data as DatasetExportResult;
  },
};
