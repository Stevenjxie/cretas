// @ts-nocheck
/**
 * useAnomalyDetection hook 单元测试
 * 测试异常检测：历史均值加载、偏差警告、debounce
 */

import { renderHook, act } from '@testing-library/react-native';

// 退役第 3 步：这个 hook 已改指向 process-work-reporting。
// ⛔ 这里的 mock 路径必须跟着换 —— 桩挂在旧模块上的话，真实调用打的是没桩的
//    processTaskApiClient，长相是「API 没被调用」而不是「改错了」。
const mockGetHistoricalAverage = jest.fn();
jest.mock('../../../services/api/processTaskApiClient', () => ({
  processTaskApiClient: {
    getHistoricalAverage: (...args: any[]) => mockGetHistoricalAverage(...args),
  },
}));

import { useAnomalyDetection } from '../../../hooks/useAnomalyDetection';

beforeEach(() => {
  jest.clearAllMocks();
  jest.useFakeTimers();
  mockGetHistoricalAverage.mockReset();
});

afterEach(() => {
  jest.useRealTimers();
});

const FACTORY_ID = 'F001';

const mockStats = {
  avgOutput: 100,
  stddevOutput: 10,
  avgDefect: 5,
  sampleCount: 20,
};

describe('useAnomalyDetection', () => {
  // ========== Initial State ==========
  it('initial state: stats is null, warnings is empty, loading is false', () => {
    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));
    expect(result.current.stats).toBeNull();
    expect(result.current.warnings).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  // ========== onProcessCategoryChange ==========
  it('onProcessCategoryChange calls API after 500ms debounce', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    // Before debounce fires, API should not be called
    expect(mockGetHistoricalAverage).not.toHaveBeenCalled();

    // Advance past debounce
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    expect(mockGetHistoricalAverage).toHaveBeenCalledWith('切割', 30, FACTORY_ID);
  });

  it('onProcessCategoryChange debounce resets on rapid calls', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切');
    });
    act(() => {
      jest.advanceTimersByTime(200);
    });
    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    // Should only call once with the last value
    expect(mockGetHistoricalAverage).toHaveBeenCalledTimes(1);
    expect(mockGetHistoricalAverage).toHaveBeenCalledWith('切割', 30, FACTORY_ID);
  });

  it('onProcessCategoryChange with empty factoryId does not call API', async () => {
    const { result } = renderHook(() => useAnomalyDetection(undefined));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    expect(mockGetHistoricalAverage).not.toHaveBeenCalled();
  });

  it('onProcessCategoryChange with empty processCategory clears stats', async () => {
    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('  ');
    });

    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    expect(result.current.stats).toBeNull();
    expect(result.current.warnings).toEqual([]);
  });

  it('sets stats from successful API response', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    expect(result.current.stats).toEqual(mockStats);
  });

  it('sets stats to null when API returns unsuccessful', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: false, data: null });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    expect(result.current.stats).toBeNull();
  });

  // ========== checkAnomaly ==========
  it('checkAnomaly with no stats produces no warnings', () => {
    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.checkAnomaly('100', '5');
    });

    expect(result.current.warnings).toEqual([]);
  });

  it('checkAnomaly with sampleCount < 5 produces no warnings', async () => {
    mockGetHistoricalAverage.mockResolvedValue({
      success: true,
      data: { ...mockStats, sampleCount: 3 },
    });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    act(() => {
      result.current.checkAnomaly('200', '50');
    });

    expect(result.current.warnings).toEqual([]);
  });

  it('checkAnomaly warns when output is above upper bound (avg + 2*stddev)', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    // upperBound = 100 + 2*10 = 120, so 150 is above
    act(() => {
      result.current.checkAnomaly('150', '3');
    });

    expect(result.current.warnings.length).toBeGreaterThanOrEqual(1);
    const outputWarning = result.current.warnings.find(w => w.field === 'output');
    expect(outputWarning).toBeDefined();
    expect(outputWarning.severity).toBe('warning');
    expect(outputWarning.message).toContain('远高于');
  });

  it('checkAnomaly warns when output is below lower bound (avg - 2*stddev)', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    // lowerBound = 100 - 2*10 = 80, so 50 is below
    act(() => {
      result.current.checkAnomaly('50', '3');
    });

    expect(result.current.warnings.length).toBeGreaterThanOrEqual(1);
    const outputWarning = result.current.warnings.find(w => w.field === 'output');
    expect(outputWarning).toBeDefined();
    expect(outputWarning.message).toContain('远低于');
  });

  it('checkAnomaly warns when defect is greater than 3x average', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    // avgDefect = 5, so defect > 15 triggers warning
    act(() => {
      result.current.checkAnomaly('100', '20');
    });

    const defectWarning = result.current.warnings.find(w => w.field === 'defect');
    expect(defectWarning).toBeDefined();
    expect(defectWarning.severity).toBe('warning');
    expect(defectWarning.message).toContain('远高于');
  });

  it('checkAnomaly produces no warnings when values are normal', async () => {
    mockGetHistoricalAverage.mockResolvedValue({ success: true, data: mockStats });

    const { result } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });
    await act(async () => {
      jest.advanceTimersByTime(500);
    });

    // 100 is exactly avg, 3 is below 3*5=15
    act(() => {
      result.current.checkAnomaly('100', '3');
    });

    expect(result.current.warnings).toEqual([]);
  });

  // ========== Cleanup ==========
  it('cleanup clears debounce timeout on unmount', () => {
    const clearTimeoutSpy = jest.spyOn(global, 'clearTimeout');
    const { result, unmount } = renderHook(() => useAnomalyDetection(FACTORY_ID));

    act(() => {
      result.current.onProcessCategoryChange('切割');
    });

    unmount();

    expect(clearTimeoutSpy).toHaveBeenCalled();
    clearTimeoutSpy.mockRestore();
  });
});
