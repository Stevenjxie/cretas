import { getErrorMsg, isNetworkError } from '../../../utils/errorHandler';

describe('errorHandler API message extraction', () => {
  it('prefers backend business message and next-action hint over Axios status text', () => {
    const error = {
      message: 'Request failed with status code 409',
      response: {
        status: 409,
        data: {
          message: '生产完成必须先核对结单',
          actionHint: '请先打开“核对结单”录入实际数据',
        },
      },
    };

    expect(getErrorMsg(error)).toBe('生产完成必须先核对结单\n请先打开“核对结单”录入实际数据');
  });

  it('keeps ordinary Error messages as fallback', () => {
    expect(getErrorMsg(new Error('本地校验失败'))).toBe('本地校验失败');
  });

  it('distinguishes network errors from backend business responses', () => {
    expect(isNetworkError({ code: 'ERR_NETWORK', request: {} })).toBe(true);
    expect(isNetworkError({ code: 'ECONNABORTED', request: {} })).toBe(true);
    expect(isNetworkError({ request: {}, response: { status: 400 } })).toBe(false);
  });
});
