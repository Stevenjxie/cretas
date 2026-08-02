/**
 * HR 首页仪表板
 *
 * 功能:
 * - 今日出勤/迟到/待激活白名单/本月入职统计卡片
 * - 快捷操作入口
 * - 异常告警列表
 *
 * 对应原型: /docs/prd/prototype/hr-admin/index.html
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
  Alert,
} from 'react-native';
import { Text, Card, Surface, ActivityIndicator, IconButton } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { hrApiClient } from '../../../services/api/hrApiClient';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { canAccessWhitelist } from '../../../utils/permissionHelper';
import {
  HR_THEME,
  HR_QUICK_ACTIONS,
  ANOMALY_TYPE_CONFIG,
  type HRDashboardStats,
  type AttendanceAnomaly,
  type HRHomeStackParamList,
} from '../../../types/hrNavigation';

type NavigationProp = NativeStackNavigationProp<HRHomeStackParamList>;

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

interface StatCardProps {
  title: string;
  value: number | string;
  icon: MCIconName;
  color: string;
  subtext?: string;
  onPress?: () => void;
}

const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  icon,
  color,
  subtext,
  onPress,
}) => (
  <TouchableOpacity
    onPress={onPress}
    style={styles.statCardWrapper}
    activeOpacity={onPress ? 0.7 : 1}
  >
    <Surface style={styles.statCard} elevation={2}>
      <View style={[styles.statIconContainer, { backgroundColor: color + '20' }]}>
        <MaterialCommunityIcons name={icon} size={24} color={color} />
      </View>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statTitle}>{title}</Text>
      {subtext && <Text style={styles.statSubtext}>{subtext}</Text>}
    </Surface>
  </TouchableOpacity>
);

export default function HRHomeScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { user } = useAuthStore();
  const { isScreenEnabled, getEnabledQuickActions } = useFactoryFeatureStore();
  const { t } = useTranslation('hr');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stats, setStats] = useState<HRDashboardStats | null>(null);
  const [anomalies, setAnomalies] = useState<AttendanceAnomaly[]>([]);

  const loadData = useCallback(async () => {
    try {
      setError(null);
      const [dashboardData, anomalyData] = await Promise.all([
        hrApiClient.getDashboardData(),
        hrApiClient.getTodayAnomalies(),
      ]);

      // API returns data directly, not wrapped in {success, data}
      if (dashboardData) {
        setStats(dashboardData);
      }
      if (anomalyData) {
        // Map API response (id: number) to UI type (id: string)
        const mappedAnomalies: AttendanceAnomaly[] = anomalyData.slice(0, 5).map(a => ({
          ...a,
          id: String(a.id),
          // Ensure resolvedBy is number if present
          resolvedBy: a.resolvedBy != null ? Number(a.resolvedBy) : undefined,
        }));
        setAnomalies(mappedAnomalies);
      }
    } catch (err) {
      console.error(t('messages.loadFailed'), err);
      setError('加载失败');
      Alert.alert(t('messages.error'), t('messages.retryLater'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  const handleQuickAction = (route: string) => {
    navigation.dispatch(CommonActions.navigate(route));
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={HR_THEME.primary} />
        <Text style={styles.loadingText}>{t('home.loading')}</Text>
      </View>
    );
  }

  // 错误状态
  if (error && !stats) {
    return (
      <View style={styles.loadingContainer}>
        <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
        <Text style={[styles.loadingText, { color: '#606266', marginTop: 12 }]}>加载失败，请检查网络</Text>
        <TouchableOpacity
          style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#8B5CF6', borderRadius: 6 }}
          onPress={() => loadData()}
        >
          <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>重试</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <View style={styles.header}>
        <View style={styles.headerGradient}>
          <View style={styles.headerContent}>
            <View>
              <Text style={styles.headerTitle}>{t('home.title')}</Text>
              <Text style={styles.headerSubtitle}>
                {user?.username || t('profile.role')}
              </Text>
            </View>
            <IconButton
              icon="bell-outline"
              iconColor="#fff"
              size={24}
              onPress={() => Alert.alert(t('messages.tip'), t('home.notifications.noNew', { defaultValue: '暂无新通知' }))}
            />
          </View>
        </View>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 统计卡片 */}
        <View style={styles.statsGrid}>
          <StatCard
            title={t('home.stats.onSite')}
            value={stats?.todayOnSite ?? 0}
            icon="account-check"
            color={HR_THEME.success}
            subtext={`${t('home.stats.totalStaff')} ${stats?.totalStaff ?? 0}`}
          />
          {isScreenEnabled('AttendanceManagement') && (
          <StatCard
            title={t('home.stats.lateToday')}
            value={stats?.lateCount ?? 0}
            icon="clock-alert"
            color={HR_THEME.warning}
            onPress={() => navigation.dispatch(CommonActions.navigate('AttendanceAnomaly'))}
          />
          )}
          {/* APP-RBAC-003: 无准入角色时不展示 — 否则统计接口 403 会被降级成一个可信的假 0 */}
          {isScreenEnabled('WhitelistManagement') && canAccessWhitelist(user) && (
          <StatCard
            title={t('home.stats.pendingWhitelist')}
            value={stats?.whitelistPending ?? '—'}
            icon="shield-check"
            color={HR_THEME.primary}
            onPress={() => navigation.dispatch(CommonActions.navigate('WhitelistList'))}
          />
          )}
          {isScreenEnabled('NewHireTracking') && (
          <StatCard
            title={t('home.stats.thisMonthNewHires')}
            value={stats?.thisMonthNewHires ?? 0}
            icon="account-plus"
            color={HR_THEME.accent}
            subtext={stats?.lastMonthNewHires !== undefined
              ? `${t('home.stats.lastMonth')} ${stats.lastMonthNewHires}`
              : undefined
            }
            onPress={() => navigation.navigate('NewHires')}
          />
          )}
        </View>

        {/* 出勤率 */}
        <Card style={styles.attendanceCard}>
          <Card.Content>
            <View style={styles.attendanceHeader}>
              <Text style={styles.sectionTitle}>{t('home.attendance.title')}</Text>
              <Text style={styles.attendanceRate}>
                {Number(stats?.attendanceRate ?? 0).toFixed(1)}%
              </Text>
            </View>
            <View style={styles.progressBar}>
              <View
                style={[
                  styles.progressFill,
                  { width: `${stats?.attendanceRate ?? 0}%` },
                ]}
              />
            </View>
          </Card.Content>
        </Card>

        {/* 快捷操作 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('home.quickActions.title')}</Text>
          <View style={styles.quickActions}>
            {HR_QUICK_ACTIONS.filter((action) => {
              const QUICK_ACTION_GUARD: Record<string, string> = {
                'new-hires': 'NewHireTracking',
              };
              if (QUICK_ACTION_GUARD[action.id] && !isScreenEnabled(QUICK_ACTION_GUARD[action.id]!)) {
                return false;
              }
              const enabledQA = getEnabledQuickActions();
              if (enabledQA.length > 0 && !enabledQA.includes(action.id)) {
                return false;
              }
              return true;
            }).map((action) => (
              <TouchableOpacity
                key={action.id}
                style={styles.quickActionItem}
                onPress={() => handleQuickAction(action.route)}
              >
                <View
                  style={[
                    styles.quickActionIcon,
                    { backgroundColor: action.color + '20' },
                  ]}
                >
                  <MaterialCommunityIcons
                    name={action.icon as MCIconName}
                    size={24}
                    color={action.color}
                  />
                </View>
                <Text style={styles.quickActionText}>{action.title}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* 考勤异常 */}
        {isScreenEnabled('AttendanceManagement') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>{t('home.anomaly.title')}</Text>
            <TouchableOpacity
              onPress={() => navigation.dispatch(CommonActions.navigate('AttendanceAnomaly'))}
            >
              <Text style={styles.viewAll}>{t('home.anomaly.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {anomalies.length === 0 ? (
            <Card style={styles.emptyCard}>
              <Card.Content style={styles.emptyContent}>
                <MaterialCommunityIcons
                  name="check-circle-outline"
                  size={48}
                  color={HR_THEME.success}
                />
                <Text style={styles.emptyText}>{t('home.anomaly.noAnomalies')}</Text>
              </Card.Content>
            </Card>
          ) : (
            anomalies.map((anomaly) => {
              const config = ANOMALY_TYPE_CONFIG[anomaly.anomalyType];
              return (
                <Card key={anomaly.id} style={styles.anomalyCard}>
                  <Card.Content style={styles.anomalyContent}>
                    <View
                      style={[
                        styles.anomalyIcon,
                        { backgroundColor: config.color + '20' },
                      ]}
                    >
                      <MaterialCommunityIcons
                        name={config.icon as MCIconName}
                        size={20}
                        color={config.color}
                      />
                    </View>
                    <View style={styles.anomalyInfo}>
                      <Text style={styles.anomalyName}>{anomaly.userName}</Text>
                      <Text style={styles.anomalyType}>{config.label}</Text>
                    </View>
                    <Text style={styles.anomalyTime}>
                      {anomaly.anomalyTime?.split(' ')[1] || anomaly.anomalyTime}
                    </Text>
                  </Card.Content>
                </Card>
              );
            })
          )}
        </View>
        )}

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: HR_THEME.background,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: HR_THEME.background,
  },
  loadingText: {
    marginTop: 12,
    color: HR_THEME.textSecondary,
  },
  header: {
    backgroundColor: HR_THEME.primary,
  },
  headerGradient: {
    backgroundColor: HR_THEME.primary,
    paddingBottom: 16,
  },
  headerContent: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 8,
  },
  headerTitle: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#fff',
  },
  headerSubtitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.8)',
    marginTop: 4,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginHorizontal: -6,
    marginBottom: 16,
  },
  statCardWrapper: {
    width: '50%',
    padding: 6,
  },
  statCard: {
    padding: 16,
    borderRadius: 12,
    backgroundColor: HR_THEME.cardBackground,
    alignItems: 'center',
  },
  statIconContainer: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  statValue: {
    fontSize: 28,
    fontWeight: 'bold',
    color: HR_THEME.textPrimary,
  },
  statTitle: {
    fontSize: 14,
    color: HR_THEME.textSecondary,
    marginTop: 4,
  },
  statSubtext: {
    fontSize: 12,
    color: HR_THEME.textMuted,
    marginTop: 2,
  },
  attendanceCard: {
    borderRadius: 12,
    marginBottom: 16,
    backgroundColor: HR_THEME.cardBackground,
  },
  attendanceHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  attendanceRate: {
    fontSize: 24,
    fontWeight: 'bold',
    color: HR_THEME.primary,
  },
  progressBar: {
    height: 8,
    borderRadius: 4,
    backgroundColor: HR_THEME.border,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 4,
    backgroundColor: HR_THEME.primary,
  },
  section: {
    marginBottom: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
  },
  viewAll: {
    fontSize: 14,
    color: HR_THEME.primary,
  },
  quickActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginHorizontal: -8,
    marginTop: 8,
  },
  quickActionItem: {
    width: '33.33%',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 8,
  },
  quickActionIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  quickActionText: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
    textAlign: 'center',
  },
  emptyCard: {
    borderRadius: 12,
    backgroundColor: HR_THEME.cardBackground,
  },
  emptyContent: {
    alignItems: 'center',
    paddingVertical: 24,
  },
  emptyText: {
    fontSize: 14,
    color: HR_THEME.textSecondary,
    marginTop: 12,
  },
  anomalyCard: {
    borderRadius: 12,
    marginBottom: 8,
    backgroundColor: HR_THEME.cardBackground,
  },
  anomalyContent: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  anomalyIcon: {
    width: 40,
    height: 40,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  anomalyInfo: {
    flex: 1,
  },
  anomalyName: {
    fontSize: 14,
    fontWeight: '500',
    color: HR_THEME.textPrimary,
  },
  anomalyType: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
    marginTop: 2,
  },
  anomalyTime: {
    fontSize: 12,
    color: HR_THEME.textMuted,
  },
  bottomSpacer: {
    height: 80,
  },
});
