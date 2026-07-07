import { beforeEach, describe, expect, it, vi } from 'vitest';

const executeIntentMock = vi.fn();
vi.mock('../intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
}));

import { askRestaurantQuestion } from '../restaurant-chat';

describe('restaurant chat adapter', () => {
  beforeEach(() => {
    executeIntentMock.mockReset();
  });

  it('uses owner-action session and scenario from resultData instead of the generic Java session', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: '\u5efa\u8bae\u4eca\u5929\u5148\u63a8\u9ad8\u6bdb\u5229\u53cc\u4eba\u5957\u9910\u3002',
      sessionId: 'java-intent-session',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'package',
        sessionId: 'owner-action-session',
        suggestedFollowups: [
          { label: '\u660e\u5929\u600e\u4e48\u505c', question: '\u660e\u5929\u600e\u4e48\u5224\u65ad\u8fd9\u4e2a\u5957\u9910\u8981\u4e0d\u8981\u505c\uff1f' },
        ],
      },
    });

    const response = await askRestaurantQuestion({
      query: '\u6839\u636e\u83dc\u54c1\u6bdb\u5229\u548c\u6210\u672c\uff0c\u5e2e\u6211\u7b97\u4e00\u4e2a\u9002\u5408\u4eca\u5929\u63a8\u7684\u5c0f\u5957\u9910',
      factoryId: 'DEMO_REST',
      userId: 'owner',
    });

    expect(response.sessionId).toBe('owner-action-session');
    expect(response.ownerActionScenario).toBe('package');
    expect(response.followUpChips).toEqual(['\u660e\u5929\u600e\u4e48\u5224\u65ad\u8fd9\u4e2a\u5957\u9910\u8981\u4e0d\u8981\u505c\uff1f']);
  });

  it('sends Java session separately from owner-action session on follow-up turns', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: '\u53ef\u4ee5\u7ee7\u7eed\u7528\u8fd9\u4e2a\u5957\u9910\u65b9\u5411\u505a\u590d\u76d8\u3002',
      sessionId: 'java-intent-session-2',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'package',
        sessionId: 'owner-action-session-2',
      },
    });

    await askRestaurantQuestion({
      query: '\u660e\u5929\u600e\u4e48\u5224\u65ad\u8fd9\u4e2a\u5957\u9910\u8981\u4e0d\u8981\u505c\uff1f',
      factoryId: 'DEMO_REST',
      userId: 'owner',
      sessionId: 'java-intent-session-1',
      ownerActionSessionId: 'owner-action-session-1',
      ownerActionScenario: 'package',
    });

    expect(executeIntentMock).toHaveBeenCalledWith('DEMO_REST', expect.any(String), expect.objectContaining({
      sessionId: 'java-intent-session-1',
      context: expect.objectContaining({
        ownerActionSessionId: 'owner-action-session-1',
        ownerActionScenario: 'package',
      }),
    }));
  });
});
