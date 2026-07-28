import { describe, expect, it, vi } from 'vitest';
import { createBomWorkspaceLoadCoordinator } from '../bomWorkspaceLoadCoordinator';

describe('bomWorkspaceLoadCoordinator', () => {
  it('reuses one in-flight request for the same product resource', async () => {
    const coordinator = createBomWorkspaceLoadCoordinator();
    let resolveRequest: ((value: string) => void) | undefined;
    const request = vi.fn(() => new Promise<string>((resolve) => {
      resolveRequest = resolve;
    }));

    const first = coordinator.singleFlight('F006:P1:readiness:R1', request);
    const second = coordinator.singleFlight('F006:P1:readiness:R1', request);

    expect(first).toBe(second);
    expect(request).toHaveBeenCalledTimes(1);
    resolveRequest?.('ready');
    await expect(first).resolves.toBe('ready');
  });

  it('rejects stale product generations while keeping the latest generation current', () => {
    const coordinator = createBomWorkspaceLoadCoordinator();
    const first = coordinator.beginProductLoad('F006:P1');
    const second = coordinator.beginProductLoad('F006:P2');

    expect(coordinator.isCurrent(first)).toBe(false);
    expect(coordinator.isCurrent(second)).toBe(true);
  });

  it('deduplicates one error code within a product navigation and resets for another product', () => {
    const coordinator = createBomWorkspaceLoadCoordinator();
    coordinator.beginProductLoad('F006:P1');
    const error = { code: 'WORKFLOW_DRAFT_AMBIGUOUS', message: '找到多个 Workflow 草稿' };

    expect(coordinator.shouldNotifyOnce('F006:P1', error, '加载失败')).toBe(true);
    expect(coordinator.shouldNotifyOnce('F006:P1', error, '加载失败')).toBe(false);

    coordinator.beginProductLoad('F006:P2');
    expect(coordinator.shouldNotifyOnce('F006:P2', error, '加载失败')).toBe(true);
  });
});
