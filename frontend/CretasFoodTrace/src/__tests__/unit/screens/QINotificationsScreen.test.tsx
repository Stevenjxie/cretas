import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(
    __dirname,
    '../../../screens/quality-inspector/QINotificationsScreen.tsx',
  ),
  'utf8',
);

describe('QINotificationsScreen read synchronization contract', () => {
  it('loads notifications for the current signed-in user', () => {
    expect(source).toContain('const userId = user?.id');
    expect(source).toContain('userId,');
    expect(source).toContain('qualityInspectorApi.getNotifications({');
  });

  it('waits for the server before clearing a single unread marker', () => {
    const serverWrite = source.indexOf(
      'await qualityInspectorApi.markNotificationRead(notification.id)',
    );
    const localWrite = source.indexOf(
      'item.id === notification.id ? { ...item, read: true } : item',
    );

    expect(serverWrite).toBeGreaterThan(0);
    expect(localWrite).toBeGreaterThan(serverWrite);
    expect(source).toContain("Alert.alert('未能标记已读'");
    expect(source).toContain('pendingReadIdsRef.current.has(notification.id)');
  });

  it('marks all notifications with the current user id and preserves failure state', () => {
    const serverWrite = source.indexOf(
      'await qualityInspectorApi.markAllNotificationsRead(userId)',
    );
    const localWrite = source.indexOf(
      'setNotifications((prev) => prev.map((n) => ({ ...n, read: true })))',
    );

    expect(serverWrite).toBeGreaterThan(0);
    expect(localWrite).toBeGreaterThan(serverWrite);
    expect(source).toContain("Alert.alert('未能全部标记已读'");
    expect(source).toContain('markingAllRef.current');
  });
});
