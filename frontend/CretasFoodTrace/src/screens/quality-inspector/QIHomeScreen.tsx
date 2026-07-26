/**
 * 质检工作台首页
 *
 * 现场质检员的首要任务是找到待人工审核，不是阅读管理报表。
 * 首页按「待我审核 → 发起拍检/记录 → 其他批次 → 今日摘要」排序。
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import {
  QI_COLORS,
  QIBatch,
  QualityInspectorStackParamList,
  QualityStatistics,
} from '../../types/qualityInspector';
import { LabelQcTaskSummary } from '../../types/labelQc';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;

export default function QIHomeScreen() {
  const { t } = useTranslation('quality');
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const { user } = useAuthStore();
  const { isScreenEnabled } = useFactoryFeatureStore();
  const factoryId = user?.factoryId;

  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statistics, setStatistics] = useState<QualityStatistics | null>(null);
  const [nextBatch, setNextBatch] = useState<QIBatch | null>(null);
  const [unreadCount, setUnreadCount] = useState(0);
  const [pendingReviewCount, setPendingReviewCount] = useState(0);
  const [nextReviewTask, setNextReviewTask] = useState<LabelQcTaskSummary | null>(null);

  const loadData = useCallback(async () => {
    if (!factoryId) {
      setError('登录信息缺少工厂，请重新登录');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    qualityInspectorApi.setFactoryId(factoryId);

    const results = await Promise.allSettled([
      qualityInspectorApi.getStatistics(),
      qualityInspectorApi.getPendingBatches({ page: 1, size: 1 }),
      qualityInspectorApi.getUnreadCount(),
      labelQcApi.listTasks(
        { statuses: ['NEEDS_REVIEW', 'ANALYSIS_FAILED'], page: 1, size: 1 },
        factoryId,
      ),
    ]);

    const [statsResult, batchesResult, unreadResult, reviewResult] = results;
    if (statsResult.status === 'fulfilled') setStatistics(statsResult.value);
    if (batchesResult.status === 'fulfilled') {
      setNextBatch(batchesResult.value.content[0] ?? null);
    }
    if (unreadResult.status === 'fulfilled') setUnreadCount(unreadResult.value);
    if (reviewResult.status === 'fulfilled') {
      setPendingReviewCount(reviewResult.value.totalElements);
      setNextReviewTask(reviewResult.value.content[0] ?? null);
    }
    if (results.every((result) => result.status === 'rejected')) {
      setError('首页信息加载失败，请检查网络后重试');
    }
    setLoading(false);
  }, [factoryId]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [loadData]);

  const openReviewQueue = () => {
    navigation.navigate('QIInspectTab', { screen: 'QILabelQcQueue' });
  };

  const openNewLabelQc = () => {
    navigation.navigate('QIInspectTab', { screen: 'QILabelQcCreate' });
  };

  const startOtherInspection = () => {
    if (nextBatch) {
      navigation.navigate('QIInspectTab', {
        screen: 'QIForm',
        params: { batchId: nextBatch.id, batchNumber: nextBatch.batchNumber },
      });
      return;
    }
    navigation.navigate('QIInspectTab', { screen: 'QIInspectList' });
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20 }]}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          colors={[QI_COLORS.primary]}
        />
      }
      testID="qi-home-screen"
    >
      <View style={styles.welcomeSection}>
        <Ionicons name="person-circle" size={46} color={QI_COLORS.primary} />
        <View style={styles.welcomeText}>
          <Text style={styles.greeting}>{t('home.welcomeBack')}</Text>
          <Text style={styles.userName}>
            {user?.fullName || user?.username || t('home.qualityInspector')}
          </Text>
        </View>
        <TouchableOpacity
          style={styles.notificationBtn}
          onPress={() => navigation.navigate('QINotifications')}
          accessibilityRole="button"
          accessibilityLabel={`通知${unreadCount > 0 ? `，${unreadCount} 条未读` : ''}`}
        >
          <Ionicons name="notifications-outline" size={24} color={QI_COLORS.text} />
          {unreadCount > 0 && (
            <View style={styles.badge}>
              <Text style={styles.badgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
            </View>
          )}
        </TouchableOpacity>
      </View>

      {error && (
        <View style={styles.errorCard}>
          <MaterialCommunityIcons name="cloud-off-outline" size={22} color={QI_COLORS.danger} />
          <Text style={styles.errorText}>{error}</Text>
          <TouchableOpacity onPress={() => void loadData()} style={styles.retryButton}>
            <Text style={styles.retryText}>重试</Text>
          </TouchableOpacity>
        </View>
      )}

      <TouchableOpacity
        style={[
          styles.reviewCard,
          pendingReviewCount === 0 && styles.reviewCardEmpty,
        ]}
        onPress={openReviewQueue}
        activeOpacity={0.84}
        accessibilityRole="button"
        accessibilityLabel={`待我审核 ${pendingReviewCount} 条`}
        testID="qi-home-pending-review"
      >
        <View style={styles.reviewHeader}>
          <View style={styles.reviewTitleRow}>
            <Ionicons
              name={pendingReviewCount > 0 ? 'alert-circle' : 'checkmark-circle'}
              size={22}
              color="#fff"
            />
            <Text style={styles.reviewTitle}>待我审核</Text>
          </View>
          <View style={styles.reviewCountBadge}>
            <Text style={styles.reviewCount}>{loading ? '—' : pendingReviewCount}</Text>
            <Text style={styles.reviewCountUnit}>条</Text>
          </View>
        </View>
        {nextReviewTask ? (
          <View style={styles.reviewTask}>
            <Text style={styles.reviewTaskName} numberOfLines={1}>
              {nextReviewTask.skuName}
            </Text>
            <Text style={styles.reviewTaskMeta}>
              {nextReviewTask.skuCode} · 批次 {nextReviewTask.batchNumber}
            </Text>
          </View>
        ) : (
          <View style={styles.reviewTask}>
            <Text style={styles.reviewTaskName}>当前没有待审核任务</Text>
            <Text style={styles.reviewTaskMeta}>新任务完成 AI 初筛后会自动出现</Text>
          </View>
        )}
        <View style={styles.reviewAction}>
          <Text style={styles.reviewActionText}>
            {pendingReviewCount > 0 ? '立即审核' : '查看任务'}
          </Text>
          <Ionicons name="arrow-forward" size={20} color="#fff" />
        </View>
      </TouchableOpacity>

      <View style={styles.primaryActions}>
        <TouchableOpacity
          style={styles.primaryAction}
          onPress={openNewLabelQc}
          accessibilityRole="button"
          testID="qi-home-new-label-qc"
        >
          <View style={[styles.primaryActionIcon, styles.photoActionIcon]}>
            <MaterialCommunityIcons name="camera-plus-outline" size={25} color="#B23E2C" />
          </View>
          <View style={styles.primaryActionCopy}>
            <Text style={styles.primaryActionTitle}>发起标签拍检</Text>
            <Text style={styles.primaryActionHint}>录入 SKU、批次并拍照</Text>
          </View>
          <Ionicons name="chevron-forward" size={20} color={QI_COLORS.textSecondary} />
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.primaryAction}
          onPress={() => navigation.navigate('QIRecordsTab', { screen: 'QIRecords' })}
          accessibilityRole="button"
        >
          <View style={[styles.primaryActionIcon, styles.recordActionIcon]}>
            <Ionicons name="document-text-outline" size={24} color="#1463A5" />
          </View>
          <View style={styles.primaryActionCopy}>
            <Text style={styles.primaryActionTitle}>查看质检记录</Text>
            <Text style={styles.primaryActionHint}>追踪已提交和已完成任务</Text>
          </View>
          <Ionicons name="chevron-forward" size={20} color={QI_COLORS.textSecondary} />
        </TouchableOpacity>
      </View>

      {isScreenEnabled('QualityInspection') && nextBatch && (
        <TouchableOpacity
          style={styles.otherBatchCard}
          onPress={startOtherInspection}
          accessibilityRole="button"
        >
          <View style={styles.otherBatchIcon}>
            <Ionicons name="clipboard-outline" size={23} color={QI_COLORS.primary} />
          </View>
          <View style={styles.otherBatchCopy}>
            <Text style={styles.otherBatchLabel}>其他待检批次</Text>
            <Text style={styles.otherBatchTitle} numberOfLines={1}>
              {nextBatch.productName} · {nextBatch.batchNumber}
            </Text>
          </View>
          <Text style={styles.otherBatchAction}>开始</Text>
        </TouchableOpacity>
      )}

      <View style={styles.todayCard}>
        <Text style={styles.sectionTitle}>今日摘要</Text>
        <View style={styles.todayMetrics}>
          <View style={styles.todayMetric}>
            <Text style={styles.todayValue}>{statistics?.today?.pending ?? '—'}</Text>
            <Text style={styles.todayLabel}>待检</Text>
          </View>
          <View style={styles.todayDivider} />
          <View style={styles.todayMetric}>
            <Text style={styles.todayValue}>{statistics?.today?.passed ?? '—'}</Text>
            <Text style={styles.todayLabel}>已通过</Text>
          </View>
          <View style={styles.todayDivider} />
          <View style={styles.todayMetric}>
            <Text style={[styles.todayValue, styles.failedValue]}>
              {statistics?.today?.failed ?? '—'}
            </Text>
            <Text style={styles.todayLabel}>未通过</Text>
          </View>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: QI_COLORS.background },
  content: { padding: 16 },
  welcomeSection: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
  },
  welcomeText: { flex: 1, marginLeft: 10 },
  greeting: { fontSize: 13, color: QI_COLORS.textSecondary },
  userName: { marginTop: 1, fontSize: 18, fontWeight: '700', color: QI_COLORS.text },
  notificationBtn: {
    position: 'relative',
    width: 46,
    height: 46,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 3,
    right: 2,
    minWidth: 18,
    height: 18,
    paddingHorizontal: 4,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 9,
    backgroundColor: QI_COLORS.danger,
  },
  badgeText: { color: '#fff', fontSize: 10, fontWeight: '700' },
  errorCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 12,
    padding: 11,
    borderRadius: 12,
    backgroundColor: '#FFF0F0',
  },
  errorText: { flex: 1, color: '#7D3030', fontSize: 12 },
  retryButton: { minHeight: 40, justifyContent: 'center', paddingHorizontal: 9 },
  retryText: { color: QI_COLORS.danger, fontWeight: '700' },
  reviewCard: {
    padding: 17,
    borderRadius: 18,
    backgroundColor: '#D57A08',
    shadowColor: '#7E4C0B',
    shadowOpacity: 0.16,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 5 },
    elevation: 3,
  },
  reviewCardEmpty: { backgroundColor: '#08795A' },
  reviewHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  reviewTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  reviewTitle: { color: '#fff', fontSize: 18, fontWeight: '800' },
  reviewCountBadge: {
    flexDirection: 'row',
    alignItems: 'baseline',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
    backgroundColor: 'rgba(255,255,255,0.18)',
  },
  reviewCount: { color: '#fff', fontSize: 21, fontWeight: '900' },
  reviewCountUnit: { marginLeft: 2, color: '#fff', fontSize: 12 },
  reviewTask: { marginTop: 17, marginBottom: 13 },
  reviewTaskName: { color: '#fff', fontSize: 17, fontWeight: '700' },
  reviewTaskMeta: { marginTop: 4, color: 'rgba(255,255,255,0.82)', fontSize: 12 },
  reviewAction: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 6,
    paddingTop: 11,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: 'rgba(255,255,255,0.34)',
  },
  reviewActionText: { color: '#fff', fontSize: 14, fontWeight: '800' },
  primaryActions: { gap: 10, marginTop: 13 },
  primaryAction: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 72,
    padding: 13,
    borderRadius: 15,
    backgroundColor: QI_COLORS.card,
  },
  primaryActionIcon: {
    width: 45,
    height: 45,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 13,
  },
  photoActionIcon: { backgroundColor: '#FFF0EC' },
  recordActionIcon: { backgroundColor: '#EAF3FF' },
  primaryActionCopy: { flex: 1, marginHorizontal: 12 },
  primaryActionTitle: { color: QI_COLORS.text, fontSize: 15, fontWeight: '700' },
  primaryActionHint: { marginTop: 3, color: QI_COLORS.textSecondary, fontSize: 12 },
  otherBatchCard: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 66,
    marginTop: 12,
    padding: 12,
    borderWidth: 1,
    borderColor: '#CFE9DF',
    borderRadius: 14,
    backgroundColor: '#F1FBF7',
  },
  otherBatchIcon: {
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 12,
    backgroundColor: '#DDF5EB',
  },
  otherBatchCopy: { flex: 1, marginHorizontal: 11 },
  otherBatchLabel: { color: QI_COLORS.textSecondary, fontSize: 11 },
  otherBatchTitle: { marginTop: 3, color: QI_COLORS.text, fontSize: 13, fontWeight: '700' },
  otherBatchAction: { color: QI_COLORS.primary, fontSize: 13, fontWeight: '800' },
  todayCard: {
    marginTop: 13,
    padding: 15,
    borderRadius: 15,
    backgroundColor: QI_COLORS.card,
  },
  sectionTitle: { color: QI_COLORS.text, fontSize: 14, fontWeight: '700' },
  todayMetrics: { flexDirection: 'row', alignItems: 'center', marginTop: 12 },
  todayMetric: { flex: 1, alignItems: 'center' },
  todayValue: { color: QI_COLORS.text, fontSize: 20, fontWeight: '800' },
  failedValue: { color: QI_COLORS.danger },
  todayLabel: { marginTop: 2, color: QI_COLORS.textSecondary, fontSize: 11 },
  todayDivider: { width: StyleSheet.hairlineWidth, height: 29, backgroundColor: QI_COLORS.border },
});
