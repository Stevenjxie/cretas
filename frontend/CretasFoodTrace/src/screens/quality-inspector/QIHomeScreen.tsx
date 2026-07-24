/**
 * 质检工作台首页
 * Quality Inspector Home Screen
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';
import { TouchableRipple } from 'react-native-paper';

import { QI_COLORS, QualityInspectorStackParamList, QIBatch, QualityStatistics } from '../../types/qualityInspector';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
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

  // 初始化 API
  useEffect(() => {
    if (factoryId) {
      qualityInspectorApi.setFactoryId(factoryId);
      loadData();
    }
  }, [factoryId]);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);

      // 并行加载数据
      const [statsResult, batchesResult, unreadResult] = await Promise.allSettled([
        qualityInspectorApi.getStatistics(),
        qualityInspectorApi.getPendingBatches({ page: 1, size: 1 }),
        qualityInspectorApi.getUnreadCount(),
      ]);

      if (statsResult.status === 'fulfilled') {
        setStatistics(statsResult.value);
      }

      if (batchesResult.status === 'fulfilled' && batchesResult.value.content.length > 0) {
        setNextBatch(batchesResult.value.content[0] ?? null);
      }

      if (unreadResult.status === 'fulfilled') {
        setUnreadCount(unreadResult.value);
      }
    } catch (err) {
      console.error('加载数据失败:', err);
      setError('加载失败');
    } finally {
      setLoading(false);
    }
  };

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, []);

  const handleStartInspection = () => {
    if (nextBatch) {
      navigation.navigate('QIInspectTab', {
        screen: 'QIForm',
        params: {
          batchId: nextBatch.id,
          batchNumber: nextBatch.batchNumber,
        },
      });
    } else {
      navigation.navigate('QIInspectTab', {
        screen: 'QIInspectList',
      });
    }
  };

  const handleNotifications = () => {
    navigation.navigate('QINotifications');
  };

  // 计算合格率
  const passRate = statistics?.today?.total
    ? Math.round((statistics.today.passed / statistics.today.total) * 100 * 10) / 10
    : 0;

  // 错误状态
  if (error && !statistics) {
    return (
      <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: QI_COLORS.background }}>
        <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
        <Text style={{ color: '#606266', marginTop: 12, fontSize: 14 }}>{t('analysis.loadFailed')}</Text>
        <TouchableOpacity
          style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#EF4444', borderRadius: 6 }}
          onPress={() => loadData()}
        >
          <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>{t('common:retry')}</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20 }]}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[QI_COLORS.primary]} />
      }
    >
      {/* 欢迎区域 */}
      <View style={styles.welcomeSection}>
        <View style={styles.avatarContainer}>
          <Ionicons name="person-circle" size={48} color={QI_COLORS.primary} />
        </View>
        <View style={styles.welcomeText}>
          <Text style={styles.greeting}>{t('home.welcomeBack')}</Text>
          <Text style={styles.userName}>{user?.fullName || user?.username || t('home.qualityInspector')}</Text>
        </View>
        <TouchableOpacity style={styles.notificationBtn} onPress={handleNotifications}>
          <Ionicons name="notifications-outline" size={24} color={QI_COLORS.text} />
          {unreadCount > 0 && (
            <View style={styles.badge}>
              <Text style={styles.badgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
            </View>
          )}
        </TouchableOpacity>
      </View>

      {/* 下一个待检批次 */}
      <TouchableOpacity style={styles.nextBatchCard} onPress={handleStartInspection} activeOpacity={0.8}>
        <View style={styles.nextBatchHeader}>
          <Ionicons name="flash" size={20} color="#fff" />
          <Text style={styles.nextBatchTitle}>{t('home.nextPendingBatch')}</Text>
        </View>
        {nextBatch ? (
          <View style={styles.nextBatchContent}>
            <Text style={styles.batchNumber}>{nextBatch.batchNumber}</Text>
            <Text style={styles.productName}>{nextBatch.productName}</Text>
            <View style={styles.batchInfo}>
              <Text style={styles.batchInfoText}>
                {t('home.quantity')}: {nextBatch.quantity} {nextBatch.unit}
              </Text>
              {nextBatch.sourceProcess && (
                <Text style={styles.batchInfoText}>{t('home.source')}: {nextBatch.sourceProcess}</Text>
              )}
            </View>
          </View>
        ) : (
          <View style={styles.nextBatchContent}>
            <Text style={styles.noBatchText}>{t('home.noPendingBatch')}</Text>
            <Text style={styles.noBatchSubText}>{t('home.clickToViewRecords')}</Text>
          </View>
        )}
        <View style={styles.nextBatchAction}>
          <Text style={styles.startText}>{nextBatch ? t('home.startInspection') : t('home.viewList')}</Text>
          <Ionicons name="arrow-forward" size={20} color="#fff" />
        </View>
      </TouchableOpacity>

      {/* 统计卡片 */}
      <View style={styles.statsGrid}>
        <View style={[styles.statCard, { backgroundColor: '#FFF3E0' }]}>
          <Text style={[styles.statValue, { color: '#E65100' }]}>
            {statistics?.today?.pending ?? '-'}
          </Text>
          <Text style={styles.statLabel}>{t('home.pending')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#E3F2FD' }]}>
          <Text style={[styles.statValue, { color: '#1565C0' }]}>
            {statistics?.today?.inspecting ?? '-'}
          </Text>
          <Text style={styles.statLabel}>{t('home.inProgress')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#E8F5E9' }]}>
          <Text style={[styles.statValue, { color: '#2E7D32' }]}>
            {statistics?.today?.passed ?? '-'}
          </Text>
          <Text style={styles.statLabel}>{t('home.passed')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#FFEBEE' }]}>
          <Text style={[styles.statValue, { color: '#C62828' }]}>
            {statistics?.today?.failed ?? '-'}
          </Text>
          <Text style={styles.statLabel}>{t('home.failed')}</Text>
        </View>
      </View>

      {/* 合格率 & 效率 */}
      <View style={styles.metricsRow}>
        {/* 合格率 */}
        <View style={styles.metricCard}>
          <Text style={styles.metricTitle}>{t('home.todayPassRate')}</Text>
          <View style={styles.rateCircle}>
            <Text style={styles.rateValue}>{passRate}</Text>
            <Text style={styles.rateUnit}>%</Text>
          </View>
          <Text style={styles.metricSubText}>
            {t('home.batchesPassed', { passed: statistics?.today?.passed ?? 0, total: statistics?.today?.total ?? 0 })}
          </Text>
        </View>

        {/* 效率 */}
        <View style={styles.metricCard}>
          <Text style={styles.metricTitle}>{t('home.avgInspectionTime')}</Text>
          <View style={styles.timeDisplay}>
            <Ionicons name="time-outline" size={24} color={QI_COLORS.secondary} />
            <Text style={styles.timeValue}>
              {statistics?.today?.avgInspectionMinutes ?? '-'}
            </Text>
            <Text style={styles.timeUnit}>{t('home.minutesPerBatch')}</Text>
          </View>
          <Text style={styles.metricSubText}>
            {t('home.inspectedCount', { count: statistics?.today?.total ?? 0 })}
          </Text>
        </View>
      </View>

      {/* 快捷操作 */}
      <View style={styles.quickActions}>
        <Text style={styles.sectionTitle}>{t('home.quickActions')}</Text>
        <View style={styles.actionGrid}>
          {isScreenEnabled('QualityInspection') && (
          <TouchableRipple
            style={styles.actionItem}
            onPress={() => navigation.navigate('QIInspectTab', { screen: 'QILabelQcCreate' })}
            borderless
            accessibilityRole="button"
            accessibilityLabel="包装标签拍检"
          >
            <View style={styles.actionTouchableContent}>
              <View style={[styles.actionIcon, { backgroundColor: '#FFF1EE' }]}>
                <MaterialCommunityIcons name="label-multiple-outline" size={24} color="#C84A32" />
              </View>
              <Text style={styles.actionText}>标签拍检</Text>
            </View>
          </TouchableRipple>
          )}

          {isScreenEnabled('QualityInspection') && (
          <TouchableOpacity
            style={styles.actionItem}
            onPress={() => navigation.navigate('QIInspectTab', { screen: 'QIScan' })}
          >
            <View style={[styles.actionIcon, { backgroundColor: '#E8F5E9' }]}>
              <Ionicons name="scan" size={24} color={QI_COLORS.primary} />
            </View>
            <Text style={styles.actionText}>{t('home.scanInspection')}</Text>
          </TouchableOpacity>
          )}

          {isScreenEnabled('QualityInspection') && (
          <TouchableOpacity
            style={styles.actionItem}
            onPress={() => navigation.navigate('QIInspectTab', { screen: 'QIVoice', params: { batchId: '', batchNumber: '' } })}
          >
            <View style={[styles.actionIcon, { backgroundColor: '#E3F2FD' }]}>
              <Ionicons name="mic" size={24} color={QI_COLORS.secondary} />
            </View>
            <Text style={styles.actionText}>{t('home.voiceInspection')}</Text>
          </TouchableOpacity>
          )}

          <TouchableOpacity
            style={styles.actionItem}
            onPress={() => navigation.navigate('QIRecordsTab', { screen: 'QIRecords' })}
          >
            <View style={[styles.actionIcon, { backgroundColor: '#FFF3E0' }]}>
              <Ionicons name="document-text" size={24} color="#E65100" />
            </View>
            <Text style={styles.actionText}>{t('home.inspectionRecords')}</Text>
          </TouchableOpacity>

          {isScreenEnabled('QualityAnalysis') && (
          <TouchableOpacity
            style={styles.actionItem}
            onPress={() => navigation.navigate('QIAnalysisTab', { screen: 'QIAnalysis' })}
          >
            <View style={[styles.actionIcon, { backgroundColor: '#F3E5F5' }]}>
              <Ionicons name="bar-chart" size={24} color="#7B1FA2" />
            </View>
            <Text style={styles.actionText}>{t('home.dataAnalysis')}</Text>
          </TouchableOpacity>
          )}
        </View>
      </View>

      {/* 异常提醒 */}
      {statistics?.today?.failed != null && statistics.today.failed > 0 && (
        <View style={styles.alertSection}>
          <View style={styles.alertHeader}>
            <Ionicons name="warning" size={20} color={QI_COLORS.danger} />
            <Text style={styles.alertTitle}>{t('home.exceptionAlert')}</Text>
          </View>
          <Text style={styles.alertText}>
            {t('home.batchesFailedToday', { count: statistics.today.failed })}
          </Text>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  content: {
    padding: 16,
  },

  // 欢迎区域
  welcomeSection: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 20,
  },
  avatarContainer: {
    width: 48,
    height: 48,
  },
  welcomeText: {
    flex: 1,
    marginLeft: 12,
  },
  greeting: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },
  userName: {
    fontSize: 18,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  notificationBtn: {
    width: 44,
    height: 44,
    justifyContent: 'center',
    alignItems: 'center',
    position: 'relative',
  },
  badge: {
    position: 'absolute',
    top: 4,
    right: 4,
    backgroundColor: QI_COLORS.danger,
    borderRadius: 10,
    minWidth: 18,
    height: 18,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 4,
  },
  badgeText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '600',
  },

  // 下一个待检批次
  nextBatchCard: {
    backgroundColor: QI_COLORS.primary,
    borderRadius: 16,
    padding: 16,
    marginBottom: 20,
  },
  nextBatchHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  nextBatchTitle: {
    color: 'rgba(255,255,255,0.9)',
    fontSize: 14,
    marginLeft: 8,
  },
  nextBatchContent: {
    marginBottom: 12,
  },
  batchNumber: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 4,
  },
  productName: {
    color: 'rgba(255,255,255,0.9)',
    fontSize: 16,
    marginBottom: 8,
  },
  batchInfo: {
    flexDirection: 'row',
    gap: 16,
  },
  batchInfoText: {
    color: 'rgba(255,255,255,0.8)',
    fontSize: 13,
  },
  noBatchText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 4,
  },
  noBatchSubText: {
    color: 'rgba(255,255,255,0.8)',
    fontSize: 14,
  },
  nextBatchAction: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.2)',
  },
  startText: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
    marginRight: 8,
  },

  // 统计卡片
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginBottom: 20,
  },
  statCard: {
    width: '47%',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 28,
    fontWeight: '700',
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
  },

  // 指标卡片
  metricsRow: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 20,
  },
  metricCard: {
    flex: 1,
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
  },
  metricTitle: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginBottom: 12,
  },
  rateCircle: {
    flexDirection: 'row',
    alignItems: 'baseline',
    marginBottom: 8,
  },
  rateValue: {
    fontSize: 32,
    fontWeight: '700',
    color: QI_COLORS.success,
  },
  rateUnit: {
    fontSize: 16,
    color: QI_COLORS.success,
    marginLeft: 2,
  },
  metricSubText: {
    fontSize: 12,
    color: QI_COLORS.textSecondary,
  },
  timeDisplay: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  timeValue: {
    fontSize: 28,
    fontWeight: '700',
    color: QI_COLORS.secondary,
    marginLeft: 8,
  },
  timeUnit: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginLeft: 4,
  },

  // 快捷操作
  quickActions: {
    marginBottom: 20,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginBottom: 12,
  },
  actionGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  actionItem: {
    width: '22%',
    alignItems: 'center',
    minHeight: 72,
    borderRadius: 12,
    overflow: 'hidden',
  },
  actionTouchableContent: {
    minHeight: 72,
    width: '100%',
    alignItems: 'center',
  },
  actionIcon: {
    width: 48,
    height: 48,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  actionText: {
    fontSize: 12,
    color: QI_COLORS.text,
    textAlign: 'center',
  },

  // 异常提醒
  alertSection: {
    backgroundColor: '#FFF8E1',
    borderRadius: 12,
    padding: 16,
    borderLeftWidth: 4,
    borderLeftColor: QI_COLORS.warning,
  },
  alertHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  alertTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: QI_COLORS.danger,
    marginLeft: 8,
  },
  alertText: {
    fontSize: 13,
    color: QI_COLORS.text,
    lineHeight: 20,
  },
});
