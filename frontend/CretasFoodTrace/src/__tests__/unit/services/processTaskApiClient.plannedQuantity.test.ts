/**
 * 守「后端 planned_quantity 为 NULL 时, 不许在映射层兜底成 0」。
 *
 * ⚠️ 为什么这条不能只靠屏幕测试:
 * ProcessTaskListScreen 的用例把 `plannedQuantity: null` 【直接喂给屏幕】,
 * 整条 `toProcessTaskItem` 映射根本没被执行。也就是说, 只要有人把
 * `?? 0` 加回映射层, 那边的屏幕测试照样全绿 —— 真正会出事的那一行没有人守。
 * ⇒ 这条断言必须跑在【真的 client】上, 只桩掉 HTTP 那一层。
 */
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn() },
}));

jest.mock('../../../utils/factoryIdHelper', () => ({
  requireFactoryId: jest.fn(() => 'F001'),
}));

const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>;

const rawTask = (plannedQuantity: number | null) => ({
  id: 77,
  factoryId: 'F001',
  productTypeId: 'PT-009',
  productTypeName: '卤猪蹄',
  workProcessId: 'WP-009',
  processName: '卤制',
  processCategory: '热加工',
  plannedUnit: 'kg',
  productionBatchId: 10759,
  plannedQuantity,
  actualQuantity: 0,
  status: 'PENDING',
});

describe('processTaskApiClient — planned_quantity 为 NULL 不兜底成 0', () => {
  beforeEach(() => jest.clearAllMocks());

  it('NULL 计划量原样传成 null, ⛔ 不是 0', async () => {
    mockedApiClient.get.mockResolvedValueOnce({
      success: true,
      data: { content: [rawTask(null)] },
    } as never);

    const res = await processTaskApiClient.getActiveTasks('F001');
    const tasks = res.data as Array<{ plannedQuantity: number | null }>;

    expect(tasks).toHaveLength(1);
    const first = tasks[0]!;
    // 判据写成【恒等于 null】而不是 falsy —— `toBeFalsy()` 对 0 也成立,
    // 那样这条断言在缺陷回归时不会红(0 和 null 都能过)。
    expect(first.plannedQuantity).toBeNull();
    expect(first.plannedQuantity).not.toBe(0);
  });

  it('阳性对照: 有计划量时原样传数字', async () => {
    mockedApiClient.get.mockResolvedValueOnce({
      success: true,
      data: { content: [rawTask(200)] },
    } as never);

    const res = await processTaskApiClient.getActiveTasks('F001');
    const tasks = res.data as Array<{ plannedQuantity: number | null }>;

    expect(tasks[0]!.plannedQuantity).toBe(200);
  });
});
