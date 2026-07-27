/**
 * 通知列表页面
 * Quality Inspector - Notifications Screen
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';

import { QI_COLORS, QINotification, NotificationType } from '../../types/qualityInspector';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { useAuthStore } from '../../store/authStore';

interface Notification {
  id: string;
  type: 'urgent' | 'info' | 'success' | 'warning';
  title: string;
  content: string;
  time: string;
  read: boolean;
}

type FilterType = 'all' | 'unread' | 'urgent';

const NOTIFICATION_ICONS: Record<Notification['type'], { icon: IoniconsName; color: string; bg: string }> = {
  urgent: { icon: 'warning', color: '#D32F2F', bg: '#FFEBEE' },
  info: { icon: 'information-circle', color: '#1976D2', bg: '#E3F2FD' },
  success: { icon: 'checkmark-circle', color: '#388E3C', bg: '#E8F5E9' },
  warning: { icon: 'alert-circle', color: '#F57C00', bg: '#FFF3E0' },
};

const mapNotificationType = (apiType: NotificationType): Notification['type'] => {
  switch (apiType) {
    case 'urgent':
      return 'urgent';
    case 'new_batch':
      return 'info';
    case 'review_result':
      return 'success';
    case 'system':
      return 'warning';
    default:
      return 'info';
  }
};

const formatRelativeTime = (dateStr: string): string => {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMinutes = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMinutes < 1) return '刚刚';
  if (diffMinutes < 60) return `${diffMinutes}分钟前`;
  if (diffHours < 24) return `${diffHours}小时前`;
  if (diffDays < 7) return `${diffDays}天前`;
  return date.toLocaleDateString();
};

const transformNotification = (apiNotification: QINotification): Notification => ({
  id: apiNotification.id,
  type: mapNotificationType(apiNotification.type),
  title: apiNotification.title,
  content: apiNotification.content,
  time: formatRelativeTime(apiNotification.createdAt),
  read: apiNotification.read,
});

export default function QINotificationsScreen() {
  const { t } = useTranslation('quality');
  const insets = useSafeAreaInsets();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;
  const userId = user?.id;

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [filter, setFilter] = useState<FilterType>('all');
  const [pendingReadIds, setPendingReadIds] = useState<Set<string>>(() => new Set());
  const [markingAll, setMarkingAll] = useState(false);
  const pendingReadIdsRef = useRef<Set<string>>(new Set());
  const markingAllRef = useRef(false);

  const loadNotifications = useCallback(async () => {
    try {
      setLoading(true);
      if (!userId) {
        throw new Error('登录信息不完整，请退出后重新登录');
      }
      const response = await qualityInspectorApi.getNotifications({
        page: 1,
        size: 20,
        userId,
      });

      if (response && response.content) {
        const transformedNotifications = response.content.map(transformNotification);
        setNotifications(transformedNotifications);
      } else {
        console.error('API returned empty data');
        setNotifications([]);
      }
    } catch (error) {
      console.error('Failed to load notifications:', error);
      if (isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          Alert.alert(t('common.error'), t('common.sessionExpired'));
        } else {
          Alert.alert(
            t('common.error'),
            error.response?.data?.message || t('notifications.loadFailed')
          );
        }
      } else if (error instanceof Error) {
        Alert.alert(t('common.error'), error.message);
      }
      setNotifications([]);
    } finally {
      setLoading(false);
    }
  }, [t, userId]);

  useEffect(() => {
    if (factoryId && userId) {
      qualityInspectorApi.setFactoryId(factoryId);
      void loadNotifications();
    }
  }, [factoryId, loadNotifications, userId]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadNotifications();
    setRefreshing(false);
  }, [loadNotifications]);

  const handleMarkAsRead = async (notification: Notification) => {
    if (notification.read || pendingReadIdsRef.current.has(notification.id)) {
      return;
    }

    pendingReadIdsRef.current.add(notification.id);
    setPendingReadIds((prev) => new Set(prev).add(notification.id));
    try {
      await qualityInspectorApi.markNotificationRead(notification.id);
      setNotifications((prev) =>
        prev.map((item) => (
          item.id === notification.id ? { ...item, read: true } : item
        )),
      );
    } catch {
      Alert.alert('未能标记已读', '网络或服务器暂时不可用，请检查网络后重试。');
    } finally {
      pendingReadIdsRef.current.delete(notification.id);
      setPendingReadIds((prev) => {
        const next = new Set(prev);
        next.delete(notification.id);
        return next;
      });
    }
  };

  const handleMarkAllAsRead = () => {
    Alert.alert(t('notifications.markAllRead'), t('notifications.confirmMarkAll'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('common.confirm'),
        onPress: async () => {
          if (!userId || markingAllRef.current) {
            return;
          }
          markingAllRef.current = true;
          setMarkingAll(true);
          try {
            await qualityInspectorApi.markAllNotificationsRead(userId);
            setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
          } catch {
            Alert.alert('未能全部标记已读', '网络或服务器暂时不可用，请检查网络后重试。');
          } finally {
            markingAllRef.current = false;
            setMarkingAll(false);
          }
        },
      },
    ]);
  };

  const handleDelete = (id: string) => {
    Alert.alert(t('notifications.deleteNotification'), t('notifications.confirmDelete'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('notifications.delete'),
        style: 'destructive',
        onPress: () => {
          setNotifications((prev) => prev.filter((n) => n.id !== id));
        },
      },
    ]);
  };

  const filteredNotifications = notifications.filter((n) => {
    if (filter === 'unread') return !n.read;
    if (filter === 'urgent') return n.type === 'urgent';
    return true;
  });

  const unreadCount = notifications.filter((n) => !n.read).length;

  const renderFilterTab = (type: FilterType, label: string, count?: number) => (
    <TouchableOpacity
      style={[styles.filterTab, filter === type && styles.filterTabActive]}
      onPress={() => setFilter(type)}
    >
      <Text style={[styles.filterText, filter === type && styles.filterTextActive]}>
        {label}
      </Text>
      {count !== undefined && count > 0 && (
        <View style={[styles.filterBadge, filter === type && styles.filterBadgeActive]}>
          <Text style={[styles.filterBadgeText, filter === type && styles.filterBadgeTextActive]}>
            {count}
          </Text>
        </View>
      )}
    </TouchableOpacity>
  );

  const renderNotificationItem = ({ item }: { item: Notification }) => {
    const iconConfig = NOTIFICATION_ICONS[item.type];

    return (
      <TouchableOpacity
        testID={`qi-notification-${item.id}`}
        style={[styles.notificationItem, !item.read && styles.notificationUnread]}
        onPress={() => void handleMarkAsRead(item)}
        onLongPress={() => handleDelete(item.id)}
        activeOpacity={0.7}
        disabled={pendingReadIds.has(item.id)}
        accessibilityState={{
          disabled: pendingReadIds.has(item.id),
          busy: pendingReadIds.has(item.id),
        }}
      >
        <View style={[styles.iconContainer, { backgroundColor: iconConfig.bg }]}>
          <Ionicons name={iconConfig.icon} size={22} color={iconConfig.color} />
        </View>
        <View style={styles.notificationContent}>
          <View style={styles.notificationHeader}>
            <Text style={[styles.notificationTitle, !item.read && styles.titleUnread]}>
              {item.title}
            </Text>
            {!item.read && (
              <View testID={`qi-notification-unread-${item.id}`} style={styles.unreadDot} />
            )}
          </View>
          <Text style={styles.notificationText} numberOfLines={2}>
            {item.content}
          </Text>
          <Text style={styles.notificationTime}>{item.time}</Text>
        </View>
      </TouchableOpacity>
    );
  };

  const renderEmpty = () => (
    <View style={styles.emptyContainer}>
      <Ionicons name="notifications-off-outline" size={64} color={QI_COLORS.disabled} />
      <Text style={styles.emptyTitle}>{t('notifications.noNotifications')}</Text>
      <Text style={styles.emptySubtitle}>
        {filter === 'unread' ? t('notifications.noUnread') : filter === 'urgent' ? t('notifications.noUrgent') : t('notifications.emptyList')}
      </Text>
    </View>
  );

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={QI_COLORS.primary} />
        <Text style={styles.loadingText}>{t('notifications.loading')}</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* 筛选栏 */}
      <View style={styles.filterBar}>
        <View style={styles.filterTabs}>
          {renderFilterTab('all', t('notifications.all'))}
          {renderFilterTab('unread', t('notifications.unread'), unreadCount)}
          {renderFilterTab('urgent', t('notifications.urgent'))}
        </View>
        {unreadCount > 0 && (
          <TouchableOpacity
            testID="qi-notifications-mark-all"
            style={[styles.markAllBtn, markingAll && styles.markAllBtnDisabled]}
            onPress={handleMarkAllAsRead}
            disabled={markingAll}
            accessibilityState={{ disabled: markingAll, busy: markingAll }}
          >
            <Text style={styles.markAllText}>
              {markingAll ? '处理中…' : t('notifications.markAllRead')}
            </Text>
          </TouchableOpacity>
        )}
      </View>

      {/* 通知列表 */}
      <FlatList
        data={filteredNotifications}
        keyExtractor={(item) => item.id}
        renderItem={renderNotificationItem}
        contentContainerStyle={[
          styles.listContent,
          { paddingBottom: insets.bottom + 20 },
        ]}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[QI_COLORS.primary]}
          />
        }
        ListEmptyComponent={renderEmpty}
        showsVerticalScrollIndicator={false}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.background,
  },
  loadingText: {
    marginTop: 12,
    color: QI_COLORS.textSecondary,
    fontSize: 14,
  },

  // 筛选栏
  filterBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: QI_COLORS.card,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  filterTabs: {
    flexDirection: 'row',
    gap: 8,
  },
  filterTab: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 44,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: QI_COLORS.background,
    gap: 4,
  },
  filterTabActive: {
    backgroundColor: QI_COLORS.primary,
  },
  filterText: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },
  filterTextActive: {
    color: '#fff',
    fontWeight: '500',
  },
  filterBadge: {
    minWidth: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: QI_COLORS.danger,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 5,
  },
  filterBadgeActive: {
    backgroundColor: 'rgba(255,255,255,0.3)',
  },
  filterBadgeText: {
    fontSize: 11,
    fontWeight: '600',
    color: '#fff',
  },
  filterBadgeTextActive: {
    color: '#fff',
  },
  markAllBtn: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  markAllBtnDisabled: { opacity: 0.55 },
  markAllText: {
    fontSize: 14,
    color: QI_COLORS.primary,
  },

  // 列表
  listContent: {
    padding: 16,
  },

  // 通知项
  notificationItem: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 14,
    marginBottom: 12,
  },
  notificationUnread: {
    borderLeftWidth: 3,
    borderLeftColor: QI_COLORS.primary,
  },
  iconContainer: {
    width: 44,
    height: 44,
    borderRadius: 22,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  notificationContent: {
    flex: 1,
  },
  notificationHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  notificationTitle: {
    fontSize: 15,
    color: QI_COLORS.text,
    flex: 1,
  },
  titleUnread: {
    fontWeight: '600',
  },
  unreadDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: QI_COLORS.primary,
    marginLeft: 8,
  },
  notificationText: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    lineHeight: 20,
    marginBottom: 6,
  },
  notificationTime: {
    fontSize: 12,
    color: QI_COLORS.disabled,
  },

  // 空状态
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginTop: 16,
    marginBottom: 8,
  },
  emptySubtitle: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },
});
