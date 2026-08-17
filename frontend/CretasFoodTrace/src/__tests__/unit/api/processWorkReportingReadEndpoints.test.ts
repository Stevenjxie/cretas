/**
 * legacy 报工栈退役第 3 步：只读方法改指向 `process-work-reporting`。
 *
 * 这里钉的是**打到哪个 URL**，不是「方法存在」——
 * 改指向这类改动最容易的错法是「函数改了名、路径没改」，
 * 那种错在类型检查和方法名断言下都是绿的。
 *
 * 底账：docs/decisions/2026-08-17-legacy报工栈退役.md
 */
import fs from 'fs';
import path from 'path';
import MockAdapter from 'axios-mock-adapter';
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

const FACTORY = 'F006';
const NEW_BASE = `/api/mobile/${FACTORY}/process-work-reporting`;
const LEGACY_BASE = `/api/mobile/${FACTORY}/work-reporting`;

describe('process-work-reporting 只读端点', () => {
  let mock: MockAdapter;

  /** 取第一条 GET；⛔ 不用 `history.get[0]!` 兜底 —— 一次都没发出去时要当场炸，不是读到 undefined。 */
  const firstGet = () => {
    const calls = mock.history.get;
    expect(calls).toHaveLength(1);
    const call = calls[0];
    if (!call) throw new Error('没有发出任何 GET —— 后面的断言无论绿不绿都没有意义');
    return call;
  };

  beforeEach(() => {
    mock = createApiMock();
    // catch-all: 任何 GET 都 200，这样断言的是「打去了哪」而不是「有没有配桩」。
    mock.onGet(/.*/).reply(200, { success: true, code: 200, message: 'ok', data: null });
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  it('🔴 getWorkReports 打新端点 /process-work-reporting/reports, ⛔ 不是 legacy /work-reporting/reports', async () => {
    await processTaskApiClient.getWorkReports({ page: 1, size: 50 }, FACTORY);

    expect(firstGet().url).toBe(`${NEW_BASE}/reports`);
    expect(firstGet().url).not.toContain(LEGACY_BASE);
    expect(firstGet().params).toEqual({ page: 1, size: 50 });
  });

  it('getWorkReports 透传筛选参数 —— 四个查询分支靠它们区分, 吞掉就变成「没传参」那一支', async () => {
    await processTaskApiClient.getWorkReports(
      { type: 'PROGRESS', startDate: '2026-08-01', endDate: '2026-08-17', page: 2, size: 20 },
      FACTORY,
    );

    expect(firstGet().params).toEqual({
      type: 'PROGRESS',
      startDate: '2026-08-01',
      endDate: '2026-08-17',
      page: 2,
      size: 20,
    });
  });

  it('🔴 getWorkReportSummary 打 /process-work-reporting/summary', async () => {
    await processTaskApiClient.getWorkReportSummary(FACTORY);

    expect(firstGet().url).toBe(`${NEW_BASE}/summary`);
    expect(firstGet().url).not.toContain(LEGACY_BASE);
  });

  it('🔴 getHistoricalAverage 打 /process-work-reporting/reports/historical-average, 且默认 days=30', async () => {
    await processTaskApiClient.getHistoricalAverage('卤制', undefined, FACTORY);

    expect(firstGet().url).toBe(`${NEW_BASE}/reports/historical-average`);
    expect(firstGet().params).toEqual({ processCategory: '卤制', days: 30 });
  });

  it('getHistoricalAverage 的 days 可覆盖 —— ⛔ 默认值不许把入参吃掉', async () => {
    await processTaskApiClient.getHistoricalAverage('拆骨', 7, FACTORY);

    expect(firstGet().params).toEqual({ processCategory: '拆骨', days: 7 });
  });
});

/**
 * ⚠️ 代理判据，标出来：下面两条量的是**源码里出现了哪个调用**，不是运行时真的调了它。
 *
 * 为什么用代理：`useDashboardData` / `MyWorkReportsScreen` 分别拖着
 * i18n + 三个 API client 和整套 react-native-paper/navigation，
 * 而 jest.config 的 `testPathIgnorePatterns` 本来就把 `__tests__/integration/screens/`
 * 整个排除在外 —— 为这两处现搭渲染环境的成本远超它能守住的东西。
 *
 * 它守得住的那一小块是真的：调用点被改回 legacy client 时这两条会红
 * （而「文件还 import 着 legacy client」由 `legacyWorkReportingRatchet` 那道闸另外守）。
 * ⛔ 它守不住的：调用点被删掉之后又在别处以别的写法调回来。
 */
describe('改指向的两个消费方（代理判据：源码扫描）', () => {
  const SRC = path.join(__dirname, '../../..');

  const read = (rel: string) => fs.readFileSync(path.join(SRC, rel), 'utf8');

  it('useDashboardData 用 processTaskApiClient.getWorkReportSummary', () => {
    const src = read('screens/factory-admin/home/hooks/useDashboardData.ts');
    expect(src).toContain('processTaskApiClient.getWorkReportSummary(');
    expect(src).not.toContain('workReportingApiClient');
  });

  it('MyWorkReportsScreen 用 processTaskApiClient.getWorkReports', () => {
    const src = read('screens/processing/MyWorkReportsScreen.tsx');
    expect(src).toContain('processTaskApiClient.getWorkReports(');
    expect(src).not.toContain('workReportingApiClient');
  });
});
