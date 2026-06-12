/**
 * useMyTodos — OA 我的待办数据 hook
 *
 * 封装:
 *   - 待办列表 fetch（按角色过滤，后端决定）
 *   - 加载状态 / 错误状态
 *   - refetch（审批后刷新列表）
 *   - 待办数量（tab 徽标用，可独立调用）
 *
 * @since 2026-06-12 (OA 待办 R1)
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import { myTodoApiClient, TodoItemDTO } from '../services/api/myTodoApiClient';

// ──────────────────────────────────────────────────────────────────────────────
// Hook: useMyTodos — 完整待办列表（供 MyTodoListScreen 使用）
// ──────────────────────────────────────────────────────────────────────────────

export interface UseMyTodosResult {
  todos: TodoItemDTO[];
  loading: boolean;
  refreshing: boolean;
  error: string | null;
  refetch: () => Promise<void>;
  /** 下拉刷新触发（为 FlatList onRefresh 准备） */
  onRefresh: () => Promise<void>;
}

export function useMyTodos(factoryId?: string): UseMyTodosResult {
  const [todos, setTodos] = useState<TodoItemDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 防止 unmount 后 setState
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const fetchTodos = useCallback(
    async (isRefreshing = false) => {
      try {
        if (isRefreshing) {
          setRefreshing(true);
        } else {
          setLoading(true);
        }
        setError(null);

        const resp = await myTodoApiClient.getMyTodos(factoryId);

        if (!mountedRef.current) return;

        if (resp.success) {
          setTodos(resp.data ?? []);
        } else {
          setError(resp.message ?? '获取待办失败');
        }
      } catch (err: unknown) {
        if (!mountedRef.current) return;
        const msg = err instanceof Error ? err.message : '网络错误，请稍后重试';
        setError(msg);
      } finally {
        if (mountedRef.current) {
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [factoryId],
  );

  // 首次加载
  useEffect(() => {
    void fetchTodos(false);
  }, [fetchTodos]);

  const refetch = useCallback(async () => {
    await fetchTodos(false);
  }, [fetchTodos]);

  const onRefresh = useCallback(async () => {
    await fetchTodos(true);
  }, [fetchTodos]);

  return { todos, loading, refreshing, error, refetch, onRefresh };
}

// ──────────────────────────────────────────────────────────────────────────────
// Hook: useMyTodoCount — 仅数量（供 tab 徽标独立 polling 使用）
// ──────────────────────────────────────────────────────────────────────────────

export interface UseMyTodoCountResult {
  count: number;
  /** 主动刷新数量（审批后调用） */
  refetchCount: () => Promise<void>;
}

/**
 * 轻量 count hook，供 MainNavigator tab badge 周期性刷新使用。
 * 如果 factoryId 不可用（未登录），返回 0。
 *
 * @param factoryId - 工厂 ID（可不传，从 store 自动取）
 * @param pollIntervalMs - 轮询间隔（毫秒），0=不轮询，默认 30000
 */
export function useMyTodoCount(
  factoryId?: string,
  pollIntervalMs = 30000,
): UseMyTodoCountResult {
  const [count, setCount] = useState(0);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const fetchCount = useCallback(async () => {
    try {
      const resp = await myTodoApiClient.getMyTodoCount(factoryId);
      if (!mountedRef.current) return;
      if (resp.success) {
        setCount(resp.data ?? 0);
      }
    } catch {
      // 网络错误不更新（保留旧值，不打扰用户）
    }
  }, [factoryId]);

  // 首次加载
  useEffect(() => {
    void fetchCount();
  }, [fetchCount]);

  // 可选轮询
  useEffect(() => {
    if (!pollIntervalMs) return undefined;
    const timer = setInterval(() => {
      void fetchCount();
    }, pollIntervalMs);
    return () => clearInterval(timer);
  }, [fetchCount, pollIntervalMs]);

  const refetchCount = useCallback(async () => {
    await fetchCount();
  }, [fetchCount]);

  return { count, refetchCount };
}
