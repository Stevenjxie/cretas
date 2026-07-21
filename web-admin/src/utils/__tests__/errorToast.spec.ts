import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/types/api';

const { errorToast } = vi.hoisted(() => ({
  errorToast: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: {
    error: errorToast,
  },
}));

import { handleCatchError } from '@/utils/errorToast';

describe('handleCatchError', () => {
  beforeEach(() => {
    errorToast.mockReset();
  });

  it('does not duplicate a business error already displayed by the interceptor', () => {
    handleCatchError(
      new ApiError('当前物料分类编码不可用', 'MATERIAL_BUSINESS_CODE_PREFIX_CONFLICT', 409),
      '保存失败',
    );

    expect(errorToast).not.toHaveBeenCalled();
  });

  it('shows exactly one fallback for a transport error without status', () => {
    handleCatchError(new Error('Network Error'), '保存失败，请稍后重试');

    expect(errorToast).toHaveBeenCalledTimes(1);
    expect(errorToast).toHaveBeenCalledWith('保存失败，请稍后重试');
  });
});
