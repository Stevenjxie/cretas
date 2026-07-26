import { beforeEach, describe, expect, it, vi } from 'vitest';

const pythonFetchMock = vi.fn();
const executeIntentMock = vi.fn();
vi.mock('../common', () => ({
  pythonFetch: (...args: unknown[]) => pythonFetchMock(...args),
  PYTHON_LLM_TIMEOUT_MS: 300000,
  PYTHON_SMARTBI_URL: '/smartbi-api',
  getPythonAuthHeaders: () => ({ 'Content-Type': 'application/json', Authorization: 'Bearer test-token' }),
}));
vi.mock('../intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
}));

import {
  askRestaurantIntent,
  askRestaurantSynthesis,
  askRestaurantSynthesisStream,
} from '../restaurant-synthesis';

describe('askRestaurantIntent', () => {
  beforeEach(() => {
    executeIntentMock.mockReset();
  });

  it('uses READ mode and keeps page context separate from the raw question', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'NEED_MORE_INFO',
      message: '你想看哪个时间范围？',
      formattedText: '',
      resultData: {
        source: 'restaurant_ops_gold',
        charts: [],
        alerts: [],
        suggestedFollowups: [
          { label: '本月', question: '本月哪家店业绩最好' },
          { label: '最近30天', question: '最近30天哪家店业绩最好' },
        ],
      },
    });

    const result = await askRestaurantIntent(
      'DEMO_REST',
      '哪家店业绩最好',
      'session-1',
      { pageContext: '页面撤单率 0.55%', dimensionHints: ['void-audit'] },
    );

    expect(executeIntentMock).toHaveBeenCalledWith(
      'DEMO_REST',
      '哪家店业绩最好',
      {
        sessionId: 'session-1',
        mode: 'READ',
        context: {
          pageContext: '页面撤单率 0.55%',
          dimensionHints: ['void-audit'],
        },
      },
    );
    expect(result).toMatchObject({
      success: true,
      answer: '你想看哪个时间范围？',
      source: 'restaurant_ops_gold',
      followUpActions: [
        { label: '本月', question: '本月哪家店业绩最好' },
        { label: '最近30天', question: '最近30天哪家店业绩最好' },
      ],
    });
  });

  it('normalizes string clarification questions as clickable actions', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'NEED_MORE_INFO',
      message: '请选择门店范围',
      clarificationQuestions: ['全部门店', '指定门店'],
      resultData: {},
    });

    const result = await askRestaurantIntent('DEMO_REST', '本月菜品销量排行');

    expect(result.followUpActions).toEqual([
      { label: '全部门店', question: '全部门店' },
      { label: '指定门店', question: '指定门店' },
    ]);
  });

  it('normalizes charts returned by the Java-orchestrated comprehensive path', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      message: '综合经营分析',
      resultData: {
        source: 'deterministic_fallback',
        charts: [{
          chartType: 'bar',
          title: '门店营收',
          xAxis: { data: ['A店'] },
          series: [{ type: 'bar', data: [100] }],
        }],
      },
    });

    const result = await askRestaurantIntent('DEMO_REST', '综合分析最近30天经营情况');

    expect(result.success).toBe(true);
    expect(result.charts).toEqual([{
      type: 'bar',
      title: '门店营收',
      option: {
        xAxis: { data: ['A店'] },
        series: [{ type: 'bar', data: [100] }],
        yAxis: { type: 'value' },
      },
    }]);
  });

  it('returns an honest failure when the unified orchestrator fails', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'ERROR',
      message: '餐饮意图服务不可用',
      resultData: null,
    });

    const result = await askRestaurantIntent('DEMO_REST', '哪家店业绩最好');

    expect(result.success).toBe(false);
    expect(result.error).toContain('不可用');
    expect(result.answer).toBe('餐饮意图服务不可用');
  });
});

describe('askRestaurantSynthesis', () => {
  beforeEach(() => {
    pythonFetchMock.mockReset();
  });

  it('calls the comprehensive-synthesis endpoint with question + session_id', async () => {
    pythonFetchMock.mockResolvedValue({ answer: 'x', charts: [], alerts: [] });

    await askRestaurantSynthesis('这两个月成本率咋样', 'sess-1');

    expect(pythonFetchMock).toHaveBeenCalledWith(
      '/api/smartbi/synthesis/comprehensive',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ question: '这两个月成本率咋样', session_id: 'sess-1' }),
      }),
    );
  });

  it('sends page context separately so routing receives the raw user question', async () => {
    pythonFetchMock.mockResolvedValue({ answer: 'x', charts: [], alerts: [] });

    await askRestaurantSynthesis('这个数据说明什么', 'sess-2', {
      pageContext: '【页面焦点】财务概览\n【页面摘要未提供】毛利率；未提供不等于0',
      dimensionHints: ['finance'],
    });

    const request = pythonFetchMock.mock.calls[0][1] as { body: string };
    expect(JSON.parse(request.body)).toEqual({
      question: '这个数据说明什么',
      session_id: 'sess-2',
      page_context: '【页面焦点】财务概览\n【页面摘要未提供】毛利率；未提供不等于0',
      dimension_hints: ['finance'],
    });
  });

  it('normalizes a flat {chartType,title,xAxis,series} chart into {type,title,option}', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: '本店上月营收上升。',
      charts: [
        {
          chartType: 'bar',
          title: '门店营收排行',
          xAxis: { data: ['A店', 'B店'] },
          series: [{ name: '营收', type: 'bar', data: [1, 2] }],
        },
      ],
      alerts: [],
      source: 'llm',
    });

    const result = await askRestaurantSynthesis('哪家店营收最高');

    expect(result.success).toBe(true);
    expect(result.charts).toHaveLength(1);
    expect(result.charts[0]).toEqual({
      type: 'bar',
      title: '门店营收排行',
      option: {
        xAxis: { data: ['A店', 'B店'] },
        series: [{ name: '营收', type: 'bar', data: [1, 2] }],
        // needsValueAxis: bar series + xAxis present + no yAxis → auto value axis.
        yAxis: { type: 'value' },
      },
    });
  });

  it('preserves grounded markLine reference values from synthesis charts', async () => {
    const markLine = {
      silent: true,
      data: [{ yAxis: 30, name: '上期实绩' }],
    };
    pythonFetchMock.mockResolvedValue({
      answer: '毛利率较上期提升。',
      charts: [{
        chartType: 'bar',
        title: '加权毛利率环比',
        xAxis: { data: ['上一等长周期', '当前周期'] },
        series: [{ name: '加权毛利率', type: 'bar', data: [30, 34], markLine }],
      }],
      alerts: [],
    });

    const result = await askRestaurantSynthesis('毛利率环比如何');

    expect((result.charts[0].option.series as Array<Record<string, unknown>>)[0].markLine).toEqual(markLine);
  });

  it('passes through 反回扣 alerts unmodified when title+detail are present', async () => {
    const alert = {
      type: 'cost_ratio_rising',
      level: 'medium',
      title: '领料成本率环比上升',
      detail: '领料成本率环比+0.6个百分点（当前32.0%），疑似用料增加/漏损/回扣，建议核查物料用量与供应商。',
    };
    pythonFetchMock.mockResolvedValue({ answer: 'x', charts: [], alerts: [alert] });

    const result = await askRestaurantSynthesis('成本率是不是涨了');

    expect(result.alerts).toEqual([alert]);
  });

  it('drops a chart with no renderable option (defensive, never crashes the panel)', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: 'x',
      charts: [{ chartType: 'bar', title: '空图' }],
      alerts: [],
    });

    const result = await askRestaurantSynthesis('q');

    expect(result.charts).toEqual([]);
  });

  it('drops an alert missing title or detail rather than rendering it half-formed', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: 'x',
      charts: [],
      alerts: [{ type: 'supplier_price_anomaly', level: 'high' }],
    });

    const result = await askRestaurantSynthesis('q');

    expect(result.alerts).toEqual([]);
  });

  it('returns an honest failure result on backend/network error, never throws', async () => {
    pythonFetchMock.mockRejectedValue(new Error('Python service error: 503 Service Unavailable'));

    const result = await askRestaurantSynthesis('q');

    expect(result.success).toBe(false);
    expect(result.answer).toBe('');
    expect(result.charts).toEqual([]);
    expect(result.alerts).toEqual([]);
    expect(result.error).toContain('503');
  });
});

// ─── askRestaurantSynthesisStream (SSE, 2026-07-24 诊断抽屉真流式) ───────────

function sseResponse(frames: string[], status = 200): Response {
  const encoder = new TextEncoder();
  const chunks = frames.map((frame) => encoder.encode(frame));
  let index = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    body: {
      getReader: () => ({
        read: async () => {
          if (index < chunks.length) {
            return { done: false, value: chunks[index++] };
          }
          return { done: true, value: undefined };
        },
        releaseLock: () => undefined,
      }),
    },
  } as unknown as Response;
}

describe('askRestaurantSynthesisStream', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it('POSTs the stream endpoint with pythonFetch conventions and emits status/chunk/charts/done', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([
      'event: status\ndata: 正在读取经营数据…\n\n',
      'event: chunk\ndata: **结论：赚钱**\n\n',
      'event: charts\ndata: [{"chartType":"bar","title":"t","xAxis":{"data":["A"]},"series":[{"type":"bar","data":[1]}]}]\n\n',
      'event: done\ndata: {"answer":"**结论：赚钱**","charts":[],"alerts":[],"source":"synthesis"}\n\n',
    ]));
    vi.stubGlobal('fetch', fetchMock);

    const events: Array<[string, unknown]> = [];
    await askRestaurantSynthesisStream('最近赚钱吗', 'sess-9', {
      onStatus: (text) => events.push(['status', text]),
      onChunk: (text) => events.push(['chunk', text]),
      onCharts: (charts) => events.push(['charts', charts]),
      onDone: (result) => events.push(['done', result]),
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/smartbi-api/api/smartbi/synthesis/comprehensive-stream',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
        body: JSON.stringify({ question: '最近赚钱吗', session_id: 'sess-9' }),
      }),
    );
    expect(events[0]).toEqual(['status', '正在读取经营数据…']);
    expect(events[1]).toEqual(['chunk', '**结论：赚钱**']);
    expect(events[2][0]).toBe('charts');
    expect((events[2][1] as unknown[])).toHaveLength(1);
    const done = events[3][1] as { success: boolean; answer: string; source?: string };
    expect(events[3][0]).toBe('done');
    expect(done.success).toBe(true);
    expect(done.answer).toBe('**结论：赚钱**');
    expect(done.source).toBe('synthesis');
  });

  it('reassembles frames split across network reads', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([
      'event: chunk\ndata: 前半',
      '段\n\nevent: done\ndata: {"answer":"前半段","charts":[],"alerts":[]}\n\n',
    ]));
    vi.stubGlobal('fetch', fetchMock);

    const chunks: string[] = [];
    await askRestaurantSynthesisStream('q', undefined, {
      onChunk: (text) => chunks.push(text),
    });

    expect(chunks).toEqual(['前半段']);
  });

  it('propagates a backend error event as a thrown error after onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: error\ndata: 分析引擎过载\n\n',
    ])));

    const onError = vi.fn();
    await expect(
      askRestaurantSynthesisStream('q', 'sess', { onError }),
    ).rejects.toThrow('分析引擎过载');
    expect(onError).toHaveBeenCalledWith('分析引擎过载');
  });

  it('throws on HTTP failure so the caller can fall back to the non-stream endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([], 502)));

    await expect(
      askRestaurantSynthesisStream('q', 'sess', {}),
    ).rejects.toThrow('502');
  });

  it('throws when the stream ends without a done event (incomplete answer must not pass silently)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: chunk\ndata: 只有一半\n\n',
    ])));

    await expect(
      askRestaurantSynthesisStream('q', 'sess', {}),
    ).rejects.toThrow('未完整结束');
  });
});
