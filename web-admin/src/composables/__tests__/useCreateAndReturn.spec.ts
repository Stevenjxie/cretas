import { describe, it, expect, vi, beforeEach } from 'vitest';

const mockCanAccess = vi.fn();
const mockCanWrite = vi.fn();
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: mockCanAccess, canWrite: mockCanWrite }),
}));

const mockPush = vi.fn();
let mockFullPath = '/production/plans';
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => ({ get fullPath() { return mockFullPath; } }),
}));

import { useCreateAndReturn } from '../useCreateAndReturn';

describe('useCreateAndReturn', () => {
  beforeEach(() => {
    mockPush.mockReset();
    mockCanAccess.mockReset();
    mockCanWrite.mockReset();
    mockFullPath = '/production/plans';
  });

  it('canReach: 默认查 canAccess', () => {
    mockCanAccess.mockReturnValue(true);
    const { canReach } = useCreateAndReturn();
    expect(canReach('sales')).toBe(true);
    expect(mockCanAccess).toHaveBeenCalledWith('sales');
  });

  it('canReach: write=true 查 canWrite', () => {
    mockCanWrite.mockReturnValue(false);
    const { canReach } = useCreateAndReturn();
    expect(canReach('sales', { write: true })).toBe(false);
    expect(mockCanWrite).toHaveBeenCalledWith('sales');
  });

  it('goCreate: 默认用当前 fullPath 作 _returnTo', () => {
    const { goCreate } = useCreateAndReturn();
    goCreate('/system/products');
    expect(mockPush).toHaveBeenCalledWith(
      '/system/products?_returnTo=' + encodeURIComponent('/production/plans'),
    );
  });

  it('goCreate: reopen 意图覆盖 _returnTo', () => {
    const { goCreate } = useCreateAndReturn();
    goCreate('/sales/orders/SO1?editItems=1', { reopen: '/production/plans?reopenPlan=1&planSO=SO1' });
    expect(mockPush).toHaveBeenCalledWith(
      '/sales/orders/SO1?editItems=1&_returnTo=' +
        encodeURIComponent('/production/plans?reopenPlan=1&planSO=SO1'),
    );
  });
});
