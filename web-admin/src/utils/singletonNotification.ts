import { ElNotification } from 'element-plus';
import type { NotificationHandle, NotificationParams } from 'element-plus';

let activeNotification: NotificationHandle | null = null;

export function closeSingletonNotification(): void {
  activeNotification?.close();
  activeNotification = null;
}

export function showSingletonNotification(options: NotificationParams): NotificationHandle {
  closeSingletonNotification();
  const onClose = options.onClose;
  const handle = ElNotification({
    ...options,
    onClose: () => {
      if (activeNotification === handle) activeNotification = null;
      onClose?.();
    },
  });
  activeNotification = handle;
  return handle;
}

export function resetSingletonNotificationForTest(): void {
  activeNotification = null;
}
