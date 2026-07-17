import { ElNotification } from 'element-plus';
import type { NotificationHandle, NotificationOptions } from 'element-plus';

let activeNotification: NotificationHandle | null = null;

export function closeSingletonNotification(): void {
  activeNotification?.close();
  activeNotification = null;
}

export function showSingletonNotification(options: Partial<NotificationOptions>): NotificationHandle {
  closeSingletonNotification();
  const onClose = options.onClose;
  const handle = ElNotification({
    ...options,
    onClose: (vm) => {
      if (activeNotification === handle) activeNotification = null;
      onClose?.(vm);
    },
  });
  activeNotification = handle;
  return handle;
}

export function resetSingletonNotificationForTest(): void {
  activeNotification = null;
}
