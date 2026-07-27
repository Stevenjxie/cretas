import MockAdapter from 'axios-mock-adapter';

import { qualityInspectorApi } from '../../../services/api/qualityInspectorApi';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

const FACTORY_ID = 'LIUSHANMEN';
const USER_ID = 1670;
const BASE = `/api/mobile/${FACTORY_ID}/notifications`;

describe('qualityInspectorApi notification contract', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = createApiMock();
    qualityInspectorApi.setFactoryId(FACTORY_ID);
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  it('loads only the current user notifications and normalizes backend fields', async () => {
    mock.onGet(BASE).reply(200, {
      success: true,
      message: 'ok',
      data: {
        content: [
          {
            id: 11,
            factoryId: FACTORY_ID,
            userId: USER_ID,
            type: 'WARNING',
            title: '待处理',
            content: '请检查批次',
            isRead: false,
            createdAt: '2026-07-27T10:00:00',
          },
        ],
        page: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      },
    });

    const result = await qualityInspectorApi.getNotifications({
      page: 1,
      size: 20,
      userId: USER_ID,
    });

    expect(mock.history.get[0]!.params).toEqual({
      page: 1,
      size: 20,
      isRead: undefined,
      userId: USER_ID,
    });
    expect(result.content[0]).toMatchObject({
      id: '11',
      type: 'urgent',
      read: false,
    });
  });

  it('marks one notification and all current-user notifications as read', async () => {
    mock.onPut(`${BASE}/11/read`).reply(200, {
      success: true,
      message: 'ok',
      data: { id: 11, isRead: true },
    });
    mock.onPut(`${BASE}/read-all`).reply(200, {
      success: true,
      message: 'ok',
      data: { updatedCount: 1 },
    });

    await qualityInspectorApi.markNotificationRead('11');
    await qualityInspectorApi.markAllNotificationsRead(USER_ID);

    expect(mock.history.put[0]!.url).toBe(`${BASE}/11/read`);
    expect(mock.history.put[1]!.url).toBe(`${BASE}/read-all`);
    expect(mock.history.put[1]!.params).toEqual({ userId: USER_ID });
  });

  it('loads the unread badge count for the current user', async () => {
    mock.onGet(`${BASE}/unread-count`).reply(200, {
      success: true,
      message: 'ok',
      data: { count: 5 },
    });

    await expect(qualityInspectorApi.getUnreadCount(USER_ID)).resolves.toBe(5);
    expect(mock.history.get[0]!.params).toEqual({ userId: USER_ID });
  });
});
