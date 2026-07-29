import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { Text, Card, Button, Appbar, ActivityIndicator, Dialog, Portal, IconButton } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ProcessingStackParamList } from '../../types/navigation';
import { useAuthStore } from '../../store/authStore';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';
import { dashboardAPI } from '../../services/api/dashboardApiClient';
import { processingApiClient, ProcessingBatch } from '../../services/api/processingApiClient';
import { handleError } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { useTranslation } from 'react-i18next';

// 创建ProcessingDashboard专用logger
const dashboardLogger = logger.createContextLogger('ProcessingDashboard');

type ProcessingDashboardNavigationProp = NativeStackNavigationProp<
  ProcessingStackParamList,
  'ProcessingDashboard'
>;

interface ErrorState {
  message: string;
  canRetry: boolean;
}


interface DashboardOverviewData {
  inProgressBatches?: number;
  activeBatches?: number;
  todayBatches?: number;
  totalBatches?: number;
  completedBatches?: number;
  qualityInspections?: number;
  onDutyWorkers?: number;
  totalWorkers?: number;
  monthlyOutput?: number;
  monthlyYieldRate?: number;
  lowStockMaterials?: number;
  summary?: DashboardOverviewData;
}

/**
 * 生产仪表板 - 生产模块入口页
 */
export default function ProcessingDashboard() {
  const navigation = useNavigation<ProcessingDashboardNavigationProp>();
  const { user } = useAuthStore();
  const { isScreenEnabled, isProcessMode } = useFactoryFeatureStore();
  const { t } = useTranslation('processing');

  // 状态管理
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ErrorState | null>(null);
  const [costAnalysisDialogVisible, setCostAnalysisDialogVisible] = useState(false);
  const [recentBatches, setRecentBatches] = useState<ProcessingBatch[]>([]);
  const [dashboardData, setDashboardData] = useState({
    inProgressBatches: 0,
    totalBatches: 0,
    completedBatches: 0,
    pendingInspection: 0,
    onDutyWorkers: 0,
    totalWorkers: 0,
  });

  // 权限控制 - 简化逻辑
  const userType = user?.userType || 'factory';
  const isPlatformAdmin = userType === 'platform';
  const canOperate = !isPlatformAdmin;  // 只要不是平台管理员就能操作

  // 权限检查日志
  dashboardLogger.debug('权限检查', {
    userType,
    isPlatformAdmin,
    canOperate,
    roleCode: user?.roleCode,
  });

  // 加载仪表板数据
  useEffect(() => {
    loadDashboardData();
  }, []);

  const loadDashboardData = async () => {
    try {
      setLoading(true);
      setError(null);
      dashboardLogger.debug('开始加载仪表板数据');

      // 使用 Dashboard API 获取今日概览数据
      const overviewRes = await dashboardAPI.getDashboardOverview('today');

      dashboardLogger.debug('仪表板数据响应', { success: overviewRes.success });

      // 提取数据 - 后端返回格式是 { success: true, data: {...}, message: "..." }
      const overview = overviewRes.data as DashboardOverviewData | undefined;
      dashboardLogger.debug('解析后数据', { overview });

      // 支持两种格式：
      // 1. 有 summary 包装: { summary: { activeBatches, ... } }
      // 2. 扁平结构: { inProgressBatches, todayBatches, ... }
      const data = overview?.summary || overview;

      if (data) {
        const newDashboardData = {
          // 兼容两种字段名格式
          inProgressBatches: data.inProgressBatches ?? data.activeBatches ?? 0,
          totalBatches: data.todayBatches ?? data.totalBatches ?? 0,
          completedBatches: data.completedBatches ?? 0,
          pendingInspection: data.qualityInspections ?? 0,
          onDutyWorkers: data.onDutyWorkers ?? 0,
          totalWorkers: data.totalWorkers ?? 0,
          // 额外字段（后端新返回的）
          monthlyOutput: data.monthlyOutput ?? 0,
          monthlyYieldRate: data.monthlyYieldRate ?? 0,
          lowStockMaterials: data.lowStockMaterials ?? 0,
        };

        dashboardLogger.info('统计结果', newDashboardData);
        setDashboardData(newDashboardData);
        setError(null);
      } else {
        dashboardLogger.warn('仪表板数据格式错误', { response: overviewRes });
        setError({
          message: t('dashboard.messages.dataFormatError'),
          canRetry: true,
        });
      }
    } catch (error) {
      dashboardLogger.error('加载仪表板数据失败', error);
      handleError(error, {
        showAlert: false,
        logError: true,
      });
      setError({
        message: error instanceof Error ? error.message : t('dashboard.messages.loadFailed'),
        canRetry: true,
      });
    } finally {
      setLoading(false);
    }
  };

  // 加载最近批次数据（用于成本对比）
  const loadRecentBatches = async () => {
    try {
      dashboardLogger.debug('开始加载最近批次数据');
      const result = await processingApiClient.getBatches({ size: 5 });

      // 解析响应，支持多种格式
      let batches: ProcessingBatch[] = [];
      if (result.data?.content) {
        batches = result.data.content;
      } else if (Array.isArray(result.data)) {
        batches = result.data;
      }

      dashboardLogger.info('最近批次加载成功', { batchCount: batches.length });
      setRecentBatches(batches.slice(0, 5));
    } catch (error) {
      dashboardLogger.error('加载最近批次失败', error);
      // 不显示错误，批次加载失败不影响其他功能
    }
  };

  // 同时加载仪表板数据和批次数据
  useEffect(() => {
    loadRecentBatches();
  }, []);

  // 处理成本对比按钮点击
  const handleCostComparisonPress = () => {
    if (recentBatches.length < 2) {
      Alert.alert(t('dashboard.messages.needTwoBatches'));
      return;
    }
    navigation.navigate('CostComparison', {
      batchIds: recentBatches.slice(0, 3).map(b => String(b.id))
    });
  };

  return (
    <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.Content title={t('dashboard.title')} />
        <Appbar.Action icon="refresh" onPress={loadDashboardData} />
      </Appbar.Header>

      <ScrollView contentContainerStyle={styles.content}>
        {/* 今日概览 */}
        <Card style={styles.card} mode="elevated">
          <Card.Title title={t('dashboard.todayOverview')} />
          <Card.Content>
            {loading ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="large" />
              </View>
            ) : error ? (
              <View style={styles.errorContainer}>
                <IconButton icon="alert-circle-outline" size={48} iconColor="#F44336" />
                <Text variant="bodyMedium" style={styles.errorText}>
                  {error.message}
                </Text>
                {error.canRetry && (
                  <Button
                    mode="outlined"
                    icon="refresh"
                    onPress={loadDashboardData}
                    style={styles.retryButton}
                  >
                    {t('common.retry')}
                  </Button>
                )}
              </View>
            ) : (
              <View style={styles.statsGrid}>
                <View style={styles.statItem}>
                  <Text variant="headlineSmall" style={styles.statValue}>
                    {dashboardData.inProgressBatches} / {dashboardData.totalBatches}
                  </Text>
                  <Text variant="bodySmall" style={styles.statLabel}>{t('dashboard.stats.inProgressBatches')}</Text>
                </View>
                <View style={styles.statItem}>
                  <Text variant="headlineSmall" style={styles.statValue}>
                    {dashboardData.completedBatches} / {dashboardData.totalBatches}
                  </Text>
                  <Text variant="bodySmall" style={styles.statLabel}>{t('dashboard.stats.completedBatches')}</Text>
                </View>
                <View style={styles.statItem}>
                  <Text variant="headlineSmall" style={styles.statValue}>
                    {dashboardData.pendingInspection}
                  </Text>
                  <Text variant="bodySmall" style={styles.statLabel}>{t('dashboard.stats.pendingInspection')}</Text>
                </View>
                <View style={styles.statItem}>
                  <Text variant="headlineSmall" style={styles.statValue}>
                    {dashboardData.onDutyWorkers} / {dashboardData.totalWorkers}
                  </Text>
                  <Text variant="bodySmall" style={styles.statLabel}>{t('dashboard.stats.onDutyWorkers')}</Text>
                </View>
              </View>
            )}
          </Card.Content>
        </Card>

        {/* 快捷操作 */}
        <Card style={styles.card} mode="elevated">
          <Card.Title title={t('dashboard.quickActions.title')} />
          <Card.Content>
            {/* 工厂用户操作按钮 */}
            {canOperate && (
              <View style={styles.actionsGrid}>
                <Button
                  mode="contained"
                  icon="truck-delivery"
                  onPress={() => navigation.navigate('CreateBatch')}
                  style={styles.actionButton}
                  buttonColor="#1976D2"
                >
                  {t('dashboard.quickActions.materialInbound')}
                </Button>
                <Button
                  mode="contained"
                  icon="file-document-edit"
                  onPress={() => navigation.navigate('ProductionPlanManagement')}
                  style={styles.actionButton}
                  buttonColor="#388E3C"
                >
                  {t('dashboard.quickActions.createProductionPlan')}
                </Button>
                <Button
                  mode="contained"
                  icon="chart-line"
                  onPress={() => navigation.navigate('DynamicReport', { reportType: 'PROGRESS' })}
                  style={styles.actionButton}
                  buttonColor="#2563EB"
                >
                  产量上报
                </Button>
                <Button
                  mode="contained"
                  icon="clock-outline"
                  onPress={() => navigation.navigate('DynamicReport', { reportType: 'HOURS' })}
                  style={styles.actionButton}
                  buttonColor="#DC2626"
                >
                  工时上报
                </Button>
                <Button
                  mode="contained"
                  icon="nfc-variant"
                  onPress={() => navigation.navigate('NfcCheckin')}
                  style={styles.actionButton}
                  buttonColor="#7C3AED"
                >
                  扫码签到
                </Button>
                <Button
                  mode="contained"
                  icon="account-group"
                  onPress={() => navigation.navigate('TeamBatchReport')}
                  style={styles.actionButton}
                  buttonColor="#EA580C"
                >
                  {t('dashboard.quickActions.teamReport', '班组报工')}
                </Button>
                {isProcessMode() && (
                  <Button
                    mode="contained"
                    icon="format-list-checks"
                    onPress={() => navigation.navigate('ProcessTaskList', {})}
                    style={styles.actionButton}
                    buttonColor="#0891B2"
                    testID="dashboard-process-task-btn"
                  >
                    工序任务
                  </Button>
                )}
              </View>
            )}

            {/* 平台管理员提示 */}
            {isPlatformAdmin && (
              <View style={styles.platformAdminNotice}>
                <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 8 }}>
                  <IconButton icon="eye" iconColor="#E65100" size={20} style={{ margin: 0, padding: 0, height: 20, width: 20, marginRight: 8 }} />
                  <Text variant="bodyMedium" style={styles.noticeText}>
                    {t('dashboard.platformAdminNotice.title')}
                  </Text>
                </View>
                <Text variant="bodySmall" style={styles.noticeHint}>
                  {t('dashboard.platformAdminNotice.hint')}
                </Text>
              </View>
            )}

            {/* 通用查看功能 - 所有用户都可以 */}
            <View style={styles.actionsGrid}>
              <Button
                mode="outlined"
                icon="clipboard-list"
                onPress={() => navigation.navigate('BatchList', {})}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.batchList')}
              </Button>
              <Button
                mode="outlined"
                icon="check-circle"
                onPress={() => navigation.navigate('QualityInspectionList', {})}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.qualityRecords')}
              </Button>
              <Button
                mode="outlined"
                icon="package-variant"
                onPress={() => navigation.navigate('MaterialBatchManagement')}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.materialManagement')}
              </Button>
              {isScreenEnabled('EquipmentMonitoring') && (
                <Button
                  mode="outlined"
                  icon="monitor-dashboard"
                  onPress={() => navigation.navigate('EquipmentMonitoring')}
                  style={styles.actionButton}
                >
                  {t('dashboard.quickActions.equipmentMonitoring')}
                </Button>
              )}
              {isScreenEnabled('CostAnalysisDashboard') && (
                <Button
                  mode="outlined"
                  icon="cash"
                  onPress={() => setCostAnalysisDialogVisible(true)}
                  style={styles.actionButton}
                >
                  {t('dashboard.quickActions.costAnalysis')}
                </Button>
              )}
              <Button
                mode="outlined"
                icon="chart-box"
                onPress={() => navigation.navigate('QualityAnalytics')}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.qualityStats')}
              </Button>
              {isScreenEnabled('InventoryCheck') && (
                <Button
                  mode="outlined"
                  icon="clipboard-check"
                  onPress={() => navigation.navigate('InventoryCheck')}
                  style={styles.actionButton}
                >
                  {t('dashboard.quickActions.inventoryCheck')}
                </Button>
              )}
              <Button
                mode="outlined"
                icon="package-down"
                onPress={() => navigation.navigate('MaterialConsumptionHistory', {})}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.consumptionHistory')}
              </Button>
              <Button
                mode="outlined"
                icon="alert-circle"
                onPress={() => navigation.navigate('ExceptionAlert')}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.exceptionAlert')}
              </Button>
              <Button
                mode="outlined"
                icon="qrcode-scan"
                onPress={() => navigation.navigate('Traceability')}
                style={styles.actionButton}
              >
                {t('dashboard.quickActions.traceability')}
              </Button>
              {/* 2026-07-30 客户反馈新增: 物料需求单 (只读) — 客户原话"没有物料需求单模块" */}
              <Button
                mode="outlined"
                icon="clipboard-list-outline"
                onPress={() => navigation.navigate('MaterialRequisitionList')}
                style={styles.actionButton}
                testID="quick-action-material-requisitions"
              >
                {t('dashboard.quickActions.materialRequisitions')}
              </Button>
            </View>
          </Card.Content>
        </Card>

        {/* AI智能分析 - Phase 3新增 */}
        <Card style={styles.card} mode="elevated">
          <Card.Title
            title={t('dashboard.aiAnalysis.title')}
            subtitle={t('dashboard.aiAnalysis.subtitle')}
          />
          <Card.Content>
            <View style={styles.actionsGrid}>
              {isScreenEnabled('AIReportList') && (
                <Button
                  mode="contained"
                  icon="robot"
                  onPress={() => navigation.navigate('AIReportList')}
                  style={styles.actionButton}
                  buttonColor="#9C27B0"
                >
                  {t('dashboard.aiAnalysis.aiReport')}
                </Button>
              )}
              {isScreenEnabled('CostComparison') && (
                <Button
                  mode="contained"
                  icon="compare"
                  onPress={handleCostComparisonPress}
                  style={styles.actionButton}
                  buttonColor="#FF9800"
                >
                  {t('dashboard.aiAnalysis.costComparison')}
                </Button>
              )}
              <Button
                mode="outlined"
                icon="calendar-range"
                onPress={() => navigation.navigate('TimeRangeCostAnalysis')}
                style={styles.actionButton}
              >
                {t('dashboard.aiAnalysis.timeRangeAnalysis')}
              </Button>
            </View>
          </Card.Content>
        </Card>

        {/* 最近批次 */}
        <Card style={styles.card} mode="elevated">
          <Card.Title
            title={t('dashboard.recentBatches.title')}
            right={(props) => (
              <Button
                compact
                onPress={() => navigation.navigate('BatchList', {})}
              >
                {t('dashboard.recentBatches.viewAll')}
              </Button>
            )}
          />
          <Card.Content>
            {recentBatches.length > 0 ? (
              <View>
                {recentBatches.slice(0, 3).map((batch) => (
                  <View key={batch.id} style={styles.batchItem}>
                    <View style={styles.batchInfo}>
                      <Text variant="bodyMedium" style={styles.batchNumber}>
                        {batch.batchNumber}
                      </Text>
                      <Text variant="bodySmall" style={styles.batchMeta}>
                        {batch.productType} · {batch.status === 'in_progress' ? t('batchList.status.inProgress') : batch.status === 'completed' ? t('batchList.status.completed') : batch.status}
                      </Text>
                    </View>
                    <Text variant="bodySmall" style={styles.batchQuantity}>
                      {batch.actualQuantity ?? batch.targetQuantity ?? '-'} {t('dashboard.recentBatches.unit')}
                    </Text>
                  </View>
                ))}
              </View>
            ) : (
              <Text variant="bodyMedium" style={styles.placeholder}>
                {t('dashboard.recentBatches.noData')}
              </Text>
            )}
          </Card.Content>
        </Card>
      </ScrollView>

      {/* 成本分析选择对话框 */}
      <Portal>
        <Dialog
          visible={costAnalysisDialogVisible}
          onDismiss={() => setCostAnalysisDialogVisible(false)}
        >
          <Dialog.Title>{t('dashboard.costDialog.title')}</Dialog.Title>
          <Dialog.Content>
            <Button
              mode="contained"
              icon="clipboard-list"
              onPress={() => {
                setCostAnalysisDialogVisible(false);
                navigation.navigate('BatchList', {});
              }}
              style={styles.dialogButton}
            >
              {t('dashboard.costDialog.byBatch')}
            </Button>
            <Button
              mode="contained"
              icon="calendar-range"
              onPress={() => {
                setCostAnalysisDialogVisible(false);
                navigation.navigate('TimeRangeCostAnalysis');
              }}
              style={styles.dialogButton}
            >
              {t('dashboard.costDialog.byTimeRange')}
            </Button>
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setCostAnalysisDialogVisible(false)}>
              {t('common.cancel')}
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  content: {
    padding: 16,
  },
  card: {
    marginBottom: 16,
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 16,
  },
  statItem: {
    flex: 1,
    minWidth: '45%',
    alignItems: 'center',
    padding: 12,
    backgroundColor: '#E3F2FD',
    borderRadius: 8,
  },
  statValue: {
    fontWeight: '700',
    color: '#1976D2',
  },
  statLabel: {
    color: '#757575',
    marginTop: 4,
  },
  actionsGrid: {
    gap: 12,
  },
  actionButton: {
    marginBottom: 8,
  },
  disabledButton: {
    opacity: 0.5,
  },
  dialogButton: {
    marginBottom: 12,
  },
  placeholder: {
    textAlign: 'center',
    color: '#9E9E9E',
    paddingVertical: 24,
  },
  platformAdminNotice: {
    padding: 20,
    backgroundColor: '#FFF3E0',
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#FF9800',
    marginBottom: 16,
  },
  noticeText: {
    color: '#E65100',
    marginBottom: 8,
    fontWeight: '500',
  },
  noticeHint: {
    color: '#F57C00',
  },
  loadingContainer: {
    paddingVertical: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  errorContainer: {
    alignItems: 'center',
    paddingVertical: 24,
    paddingHorizontal: 16,
  },
  errorText: {
    color: '#F44336',
    marginTop: 12,
    marginBottom: 16,
    textAlign: 'center',
  },
  retryButton: {
    borderColor: '#F44336',
  },
  batchItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  batchInfo: {
    flex: 1,
  },
  batchNumber: {
    fontWeight: '500',
    color: '#212121',
  },
  batchMeta: {
    color: '#757575',
    marginTop: 2,
  },
  batchQuantity: {
    color: '#1976D2',
    fontWeight: '500',
  },
});
