import { beforeEach, describe, expect, it, vi } from 'vitest';

const notification = vi.hoisted(() => vi.fn());
vi.mock('element-plus', () => ({ ElNotification: notification }));

import { resetSingletonNotificationForTest, showSingletonNotification } from '../singletonNotification';

describe('singleton notification', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetSingletonNotificationForTest();
  });

  it('closes the previous persistent notification before showing the next one', () => {
    const first = { close: vi.fn() };
    const second = { close: vi.fn() };
    notification.mockReturnValueOnce(first).mockReturnValueOnce(second);

    showSingletonNotification({ title: 'first', message: 'one', duration: 0 });
    showSingletonNotification({ title: 'second', message: 'two', duration: 0 });

    expect(first.close).toHaveBeenCalledTimes(1);
    expect(notification).toHaveBeenCalledTimes(2);
  });
});
