type ErrorLike = {
  code?: string | number | null;
  errorCode?: string | number | null;
  message?: string | null;
  response?: {
    data?: {
      code?: string | number | null;
      errorCode?: string | number | null;
      message?: string | null;
    };
  };
};

export interface ProductLoadToken {
  generation: number;
  productKey: string;
}

/**
 * BOM 页面级加载协调器：
 * - 同一请求 key 只保留一个在途 Promise；
 * - 产品切换后，旧响应不能再写回新产品；
 * - 同一产品导航内，同一 errorCode 只提示一次。
 *
 * 状态限定在组件实例内，不会把另一个页面、产品或稍后的真实失败全局吞掉。
 */
export function createBomWorkspaceLoadCoordinator() {
  let generation = 0;
  let navigationProductKey = '';
  const inFlight = new Map<string, Promise<unknown>>();
  const notifiedErrors = new Set<string>();

  function beginProductLoad(productKey: string): ProductLoadToken {
    generation += 1;
    if (navigationProductKey !== productKey) {
      navigationProductKey = productKey;
      notifiedErrors.clear();
    }
    return { generation, productKey };
  }

  function isCurrent(token: ProductLoadToken): boolean {
    return token.generation === generation && token.productKey === navigationProductKey;
  }

  function invalidate(): void {
    generation += 1;
  }

  function singleFlight<T>(key: string, task: () => Promise<T>): Promise<T> {
    const current = inFlight.get(key) as Promise<T> | undefined;
    if (current) return current;

    const request = task();
    inFlight.set(key, request);
    void request.finally(() => {
      if (inFlight.get(key) === request) inFlight.delete(key);
    }).catch((): void => {});
    return request;
  }

  function errorFingerprint(error: unknown, fallbackMessage: string): string {
    const value = (error || {}) as ErrorLike;
    const response = value.response?.data;
    return String(
      value.code
      ?? value.errorCode
      ?? response?.errorCode
      ?? response?.code
      ?? value.message
      ?? response?.message
      ?? fallbackMessage,
    );
  }

  function shouldNotifyOnce(
    productKey: string,
    error: unknown,
    fallbackMessage: string,
  ): boolean {
    if (navigationProductKey !== productKey) return false;
    const key = `${productKey}::${errorFingerprint(error, fallbackMessage)}`;
    if (notifiedErrors.has(key)) return false;
    notifiedErrors.add(key);
    return true;
  }

  return {
    beginProductLoad,
    isCurrent,
    invalidate,
    singleFlight,
    shouldNotifyOnce,
  };
}
