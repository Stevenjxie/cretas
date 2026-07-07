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
      message: '建议今天先推高毛利双人套餐。',
      sessionId: 'java-intent-session',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'package',
        sessionId: 'owner-action-session',
        suggestedFollowups: [
          { label: '明天怎么停', question: '明天怎么判断这个套餐要不要停？' },
        ],
      },
    });

    const response = await askRestaurantQuestion({
      query: '根据菜品毛利和成本，帮我算一个适合今天推的小套餐',
      factoryId: 'DEMO_REST',
      userId: 'owner',
    });

    expect(response.sessionId).toBe('owner-action-session');
    expect(response.ownerActionScenario).toBe('package');
    expect(response.followUpChips).toEqual(['明天怎么判断这个套餐要不要停？']);
  });
});
