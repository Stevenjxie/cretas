import React, { useState } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl, TouchableOpacity, FlatList } from 'react-native';
import { Text, Appbar, Divider, ActivityIndicator, IconButton, Menu, SegmentedButtons, Surface } from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { ProcessingScreenProps } from '../../types/navigation';
import { BatchStatusBadge, BatchStatus } from '../../components/processing';
import { processingApiClient, ProcessingBatch } from '../../services/api/processingApiClient';
import { materialConsumptionApiClient, MaterialConsumption, BatchConsumptionSummary } from '../../services/api/materialConsumptionApiClient';
import { BatchYieldDTO, yieldReportApi } from '../../services/api/yieldReportApi';
import { handleError } from '../../utils/errorHandler';
import { displayProductName } from '../../utils/formatters';
import { NeoCard, NeoButton, ScreenWrapper, StatusBadge } from '../../components/ui';
import { appAlert } from '../../components/ui/AppDialog';
import { useAuthStore } from '../../store/authStore';
import { theme } from '../../theme';

type BatchDetailScreenProps = ProcessingScreenProps<'BatchDetail'>;

interface ErrorState {
  message: string;
  canRetry: boolean;
}

export default function BatchDetailScreen() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<BatchDetailScreenProps['navigation']>();
  const route = useRoute<BatchDetailScreenProps['route']>();
  const { batchId, readonly } = route.params;

  // 扩展类型以包含后端可能返回的额外字段
  interface ExtendedBatch extends ProcessingBatch {
    completedAt?: string;
    rawMaterials?: Array<{ materialType?: string; type?: string; quantity: number; unit?: string }>;
  }
  const [batch, setBatch] = useState<ExtendedBatch | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [qualityMenuVisible, setQualityMenuVisible] = useState(false);
  const [error, setError] = useState<ErrorState | null>(null);
  const [yieldData, setYieldData] = useState<BatchYieldDTO | null>(null);
  const [yieldLoadFailed, setYieldLoadFailed] = useState(false);

  // 角色判断
  const currentRole = useAuthStore(state => state.getUserRole());
  const isOperator = currentRole === 'operator';

  // 撤回状态
  const REVERSAL_REASONS = [
    { label: '客户取消订单', value: '客户取消订单' },
    { label: '原材料质量问题', value: '原材料质量问题' },
    { label: '工艺/配方变更', value: '工艺/配方变更' },
    { label: '生产排程冲突', value: '生产排程冲突' },
    { label: '其他原因', value: '其他原因' },
  ] as const;
  const [reversalReason, setReversalReason] = useState('');
  const [reversalMenuVisible, setReversalMenuVisible] = useState(false);
  const [reversalSubmitting, setReversalSubmitting] = useState(false);

  // Tab 状态
  const [activeTab, setActiveTab] = useState<'detail' | 'consumption'>('detail');
  const [consumptions, setConsumptions] = useState<MaterialConsumption[]>([]);
  const [consumptionLoading, setConsumptionLoading] = useState(false);
  const [consumptionStats, setConsumptionStats] = useState<{ totalQuantity: number; totalCost: number } | null>(null);
  const [consumptionSummary, setConsumptionSummary] = useState<BatchConsumptionSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);

  useFocusEffect(
    React.useCallback(() => {
      fetchBatchDetail();
      if (activeTab === 'consumption') {
        fetchConsumptions();
        fetchConsumptionSummary();
      }
    }, [batchId, activeTab])
  );

  // 当切换到消耗 Tab 时加载数据
  React.useEffect(() => {
    if (activeTab === 'consumption') {
      if (consumptions.length === 0) {
        fetchConsumptions();
      }
      if (!consumptionSummary) {
        fetchConsumptionSummary();
      }
    }
  }, [activeTab]);

  const fetchBatchDetail = async () => {
    try {
      setLoading(true);
      setError(null);
      setYieldLoadFailed(false);
      const [batchResponse, yieldResponse] = await Promise.allSettled([
        processingApiClient.getBatchById(batchId),
        yieldReportApi.getYield(Number(batchId)),
      ]);

      if (batchResponse.status === 'rejected') {
        throw batchResponse.reason;
      }

      const result = batchResponse.value.data;
      setBatch(result as ExtendedBatch);

      if (yieldResponse.status === 'fulfilled' && yieldResponse.value.success) {
        setYieldData(yieldResponse.value.data);
      } else {
        setYieldData(null);
        setYieldLoadFailed(true);
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      setError({
        message: error instanceof Error ? error.message : t('batchDetail.loadFailed'),
        canRetry: true,
      });
      setBatch(null);
      setYieldData(null);
    } finally {
      setLoading(false);
    }
  };

  const fetchConsumptions = async () => {
    try {
      setConsumptionLoading(true);
      const response = await materialConsumptionApiClient.getConsumptionsByBatch(batchId);
      if (response.success && response.data) {
        setConsumptions(response.data);
        // 计算统计
        const totalQuantity = response.data.reduce((sum, item) => sum + item.quantity, 0);
        const totalCost = response.data.reduce((sum, item) => sum + item.totalCost, 0);
        setConsumptionStats({ totalQuantity, totalCost });
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
    } finally {
      setConsumptionLoading(false);
    }
  };

  const fetchConsumptionSummary = async () => {
    try {
      setSummaryLoading(true);
      const response = await materialConsumptionApiClient.getBatchConsumptionSummary(batchId);
      if (response.success && response.data) {
        setConsumptionSummary(response.data);
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
    } finally {
      setSummaryLoading(false);
    }
  };

  const handleReversal = async () => {
    if (!reversalReason) {
      appAlert('请先选择撤回原因', '选择后点击确认撤回');
      return;
    }
    appAlert(
      `确认撤回批次 ${batch?.batchNumber ?? ''}？`,
      `原因：${reversalReason}\n\n撤回后此批次将回到草稿状态，已报工记录会保留。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认撤回',
          style: 'destructive',
          onPress: async () => {
            try {
              setReversalSubmitting(true);
              await yieldReportApi.postBatchReversal(Number(batchId), reversalReason);
              appAlert('撤回成功', '批次已回到草稿状态');
              setReversalReason('');
              await fetchBatchDetail();
            } catch (err) {
              const status = isAxiosError(err) ? err.response?.status : undefined;
              const msg = isAxiosError(err)
                ? ((err.response?.data as { message?: string })?.message ?? err.message)
                : (err instanceof Error ? err.message : '撤回失败');
              if (status === 409) {
                appAlert(
                  '无法撤回',
                  `${msg}\n\n请先处理相关依赖后重试。`,
                  [
                    { text: '查看详情', onPress: () => fetchBatchDetail() },
                    { text: '关闭', style: 'cancel' },
                  ],
                );
              } else {
                appAlert('撤回失败', msg);
              }
            } finally {
              setReversalSubmitting(false);
            }
          },
        },
      ],
    );
  };

  const getAchievementColor = (rate: number): string => {
    if (rate >= 95) return '#4CAF50';
    if (rate >= 85) return '#FF9800';
    return '#F44336';
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    await fetchBatchDetail();
    if (activeTab === 'consumption') {
      await Promise.all([fetchConsumptions(), fetchConsumptionSummary()]);
    }
    setRefreshing(false);
  };

  const formatCurrency = (amount: number) => `¥${amount.toFixed(2)}`;

  const formatYieldRate = (rate?: number | null) => (
    rate == null ? '—' : `${(rate * 100).toFixed(2)}%`
  );

  const formatQuantity = (quantity?: number | null, unit?: string | null) => {
    if (quantity == null) return '—';
    return `${quantity} ${unit ?? ''}`.trim();
  };

  const currentYieldRate = yieldData?.asOfYieldRate ?? yieldData?.cumulativeYieldRate ?? null;
  const isRollingYield = yieldData?.inProgress === true || yieldData?.complete === false;

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  if (loading && !batch) {
    return (
      <ScreenWrapper>
        <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={t('batchDetail.title')} />
        </Appbar.Header>
        <View style={styles.centerContainer}><ActivityIndicator size="large" color={theme.colors.primary} /></View>
      </ScreenWrapper>
    );
  }

  if (!batch) {
    return (
      <ScreenWrapper>
        <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="批次详情" />
        </Appbar.Header>
        <View style={styles.centerContainer}>
          {error ? (
            <>
              <Text style={styles.errorText}>{error.message}</Text>
              {error.canRetry && <NeoButton onPress={fetchBatchDetail}>重试</NeoButton>}
            </>
          ) : (
            <Text>未找到批次信息</Text>
          )}
        </View>
      </ScreenWrapper>
    );
  }

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="批次详情" titleStyle={{ fontWeight: '600' }} />
        {!readonly && <Appbar.Action icon="pencil" onPress={() => navigation.navigate('EditBatch', { batchId })} />}
      </Appbar.Header>

      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {/* Header Card */}
        <NeoCard style={styles.card} padding="m">
          <View style={styles.headerRow}>
            <View>
              <Text style={styles.label}>批次号</Text>
              <Text variant="headlineSmall" style={styles.batchNumber}>{batch.batchNumber}</Text>
            </View>
            <BatchStatusBadge status={batch.status as BatchStatus} size="medium" />
          </View>
        </NeoCard>

        {/* Tab Bar */}
        <SegmentedButtons
          value={activeTab}
          onValueChange={(value) => setActiveTab(value as 'detail' | 'consumption')}
          buttons={[
            { value: 'detail', label: '详情', icon: 'information-outline' },
            { value: 'consumption', label: '消耗记录', icon: 'package-down' },
          ]}
          style={styles.tabBar}
        />

        {activeTab === 'consumption' ? (
          /* 消耗记录 Tab */
          <>
            {/* BOM达成率卡片 */}
            {summaryLoading ? (
              <Surface style={styles.bomCard} elevation={1}>
                <View style={styles.bomLoadingContainer}>
                  <ActivityIndicator size="small" color={theme.colors.primary} />
                  <Text style={styles.bomLoadingText}>加载BOM达成率...</Text>
                </View>
              </Surface>
            ) : consumptionSummary ? (
              <Surface style={styles.bomCard} elevation={1} testID="bom-achievement-card">
                <View style={styles.bomHeader}>
                  <Text style={styles.bomTitle}>BOM达成率</Text>
                  <View
                    testID="bom-achievement-rate"
                    style={[
                      styles.bomRateBadge,
                      { backgroundColor: getAchievementColor(consumptionSummary.overallAchievementRate) + '18' },
                    ]}
                  >
                    <Text
                      style={[
                        styles.bomRateText,
                        { color: getAchievementColor(consumptionSummary.overallAchievementRate) },
                      ]}
                    >
                      {consumptionSummary.overallAchievementRate.toFixed(1)}%
                    </Text>
                  </View>
                </View>

                {/* 总量概览 */}
                <View style={styles.bomOverviewRow}>
                  <View style={styles.bomOverviewItem}>
                    <Text style={styles.bomOverviewLabel}>计划总量</Text>
                    <Text style={styles.bomOverviewValue}>
                      {consumptionSummary.totalPlannedQuantity.toFixed(2)} kg
                    </Text>
                  </View>
                  <View style={styles.bomOverviewDivider} />
                  <View style={styles.bomOverviewItem}>
                    <Text style={styles.bomOverviewLabel}>实际总量</Text>
                    <Text style={styles.bomOverviewValue}>
                      {consumptionSummary.totalActualQuantity.toFixed(2)} kg
                    </Text>
                  </View>
                </View>

                {/* 物料明细 */}
                {consumptionSummary.materials.length > 0 && (
                  <View style={styles.bomMaterialList}>
                    <Text style={styles.bomMaterialListTitle}>物料明细</Text>
                    {consumptionSummary.materials.map((material, index) => (
                      <View key={index} style={styles.bomMaterialItem}>
                        <View style={styles.bomMaterialHeader}>
                          <Text style={styles.bomMaterialName}>{material.materialTypeName}</Text>
                          <Text
                            style={[
                              styles.bomMaterialRate,
                              { color: getAchievementColor(material.achievementRate) },
                            ]}
                          >
                            {material.achievementRate.toFixed(1)}%
                          </Text>
                        </View>
                        <View style={styles.bomMaterialDetails}>
                          <Text style={styles.bomMaterialDetail}>
                            计划: {material.plannedQuantity.toFixed(2)} kg
                          </Text>
                          <Text style={styles.bomMaterialDetail}>
                            实际: {material.actualQuantity.toFixed(2)} kg
                          </Text>
                          <Text
                            style={[
                              styles.bomMaterialDetail,
                              {
                                color: material.variance >= 0 ? '#4CAF50' : '#F44336',
                              },
                            ]}
                          >
                            差异: {material.variance >= 0 ? '+' : ''}{material.variance.toFixed(2)} kg
                          </Text>
                        </View>
                      </View>
                    ))}
                  </View>
                )}
              </Surface>
            ) : null}

            {/* 消耗统计 */}
            {consumptionStats && (
              <Surface style={styles.statsCard} elevation={1}>
                <View style={styles.statsRow}>
                  <View style={styles.statItem}>
                    <Text style={styles.statValue}>{consumptions.length}</Text>
                    <Text style={styles.statLabel}>消耗次数</Text>
                  </View>
                  <View style={styles.statDivider} />
                  <View style={styles.statItem}>
                    <Text style={styles.statValue}>{consumptionStats.totalQuantity.toFixed(2)} kg</Text>
                    <Text style={styles.statLabel}>总消耗量</Text>
                  </View>
                  <View style={styles.statDivider} />
                  <View style={styles.statItem}>
                    <Text style={[styles.statValue, styles.costValue]}>{formatCurrency(consumptionStats.totalCost)}</Text>
                    <Text style={styles.statLabel}>总成本</Text>
                  </View>
                </View>
              </Surface>
            )}

            {/* 消耗记录列表 */}
            {consumptionLoading ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="small" color={theme.colors.primary} />
                <Text style={styles.loadingText}>加载中...</Text>
              </View>
            ) : consumptions.length === 0 ? (
              <NeoCard style={styles.card} padding="m">
                <View style={styles.emptyContainer}>
                  <IconButton icon="package-variant" size={40} iconColor={theme.colors.onSurfaceVariant} />
                  <Text style={styles.emptyText}>暂无消耗记录</Text>
                  <Text style={styles.emptyHint}>生产过程中的原材料消耗会显示在这里</Text>
                </View>
              </NeoCard>
            ) : (
              consumptions.map((item) => (
                <NeoCard key={item.id} style={styles.card} padding="m">
                  <View style={styles.consumptionHeader}>
                    <View>
                      <Text style={styles.consumptionBatch}>{item.batchNumber ?? item.batchId}</Text>
                      <Text style={styles.consumptionMaterial}>{item.materialTypeName ?? '原材料'}</Text>
                    </View>
                    <Text style={styles.consumptionCost}>{formatCurrency(item.totalCost)}</Text>
                  </View>
                  <View style={styles.consumptionDetails}>
                    <View style={styles.consumptionRow}>
                      <Text style={styles.consumptionLabel}>消耗数量</Text>
                      <Text style={styles.consumptionValue}>{item.quantity} kg</Text>
                    </View>
                    <View style={styles.consumptionRow}>
                      <Text style={styles.consumptionLabel}>单价</Text>
                      <Text style={styles.consumptionValue}>{formatCurrency(item.unitPrice)}/kg</Text>
                    </View>
                    <View style={styles.consumptionRow}>
                      <Text style={styles.consumptionLabel}>消耗时间</Text>
                      <Text style={styles.consumptionValue}>{formatDate(item.consumptionTime)}</Text>
                    </View>
                    {item.notes && (
                      <View style={styles.consumptionRow}>
                        <Text style={styles.consumptionLabel}>备注</Text>
                        <Text style={styles.consumptionValue} numberOfLines={2}>{item.notes}</Text>
                      </View>
                    )}
                  </View>
                </NeoCard>
              ))
            )}
          </>
        ) : (
          /* 详情 Tab */
          <>
        {/* Basic Info */}
        <NeoCard style={styles.card} padding="m">
          <Text variant="titleMedium" style={styles.sectionTitle}>基本信息</Text>
          
          <View style={styles.infoGrid}>
             <View style={styles.infoItem}>
                <Text style={styles.label}>产品类型</Text>
                <Text style={styles.value}>{displayProductName(batch.productType, t('batchList.labels.pending'))}</Text>
             </View>
             <View style={styles.infoItem}>
                <Text style={styles.label}>负责人</Text>
                <Text style={styles.value}>{typeof batch.supervisor === 'object' ? ((batch.supervisor as { fullName?: string; username?: string })?.fullName || (batch.supervisor as { username?: string })?.username) : batch.supervisor || '未指定'}</Text>
             </View>
             <View style={styles.infoItem}>
                <Text style={styles.label}>目标产量</Text>
                <Text style={styles.value}>{batch.targetQuantity} kg</Text>
             </View>
             <View style={styles.infoItem}>
                <Text style={styles.label}>实际产量</Text>
                <Text style={[styles.value, batch.actualQuantity ? styles.highlight : {}]}>{batch.actualQuantity || '-'} kg</Text>
             </View>
          </View>

          <Divider style={styles.divider} />
          
          <View style={styles.rowBetween}>
             <Text style={styles.label}>创建时间</Text>
             <Text style={styles.valueSmall}>{new Date(batch.createdAt).toLocaleString('zh-CN')}</Text>
          </View>
          {batch.completedAt && (
             <View style={[styles.rowBetween, { marginTop: 8 }]}>
                <Text style={styles.label}>完成时间</Text>
                <Text style={styles.valueSmall}>{new Date(batch.completedAt).toLocaleString('zh-CN')}</Text>
             </View>
          )}
        </NeoCard>

        <NeoCard style={styles.card} padding="m" testID="batch-detail-yield-card">
          <View style={styles.yieldHeader}>
            <View style={styles.yieldTitleWrap}>
              <Text variant="titleMedium" style={styles.sectionTitle}>当前出成率</Text>
              <Text style={styles.yieldSubTitle}>
                {displayProductName(batch.productType, t('batchList.labels.pending'))} · {batch.batchNumber}
              </Text>
            </View>
            <StatusBadge
              status={isRollingYield ? '滚动中参考' : '最终值'}
              variant={isRollingYield ? 'warning' : 'success'}
            />
          </View>

          {yieldLoadFailed ? (
            <View style={styles.yieldUnavailable} testID="batch-detail-yield-error">
              <Text style={styles.yieldUnavailableText}>出成率暂时加载失败，请下拉刷新重试。</Text>
            </View>
          ) : (
            <>
              <Text style={styles.yieldRateValue} testID="batch-detail-yield-rate">
                {formatYieldRate(currentYieldRate)}
              </Text>
              <Text style={styles.yieldFormula} testID="batch-detail-yield-formula">
                已录末道产出 ÷ 已录首道投入
              </Text>

              <View style={styles.yieldMetricRow}>
                <View style={styles.yieldMetricItem}>
                  <Text style={styles.label}>首道投入</Text>
                  <Text style={styles.value}>
                    {formatQuantity(yieldData?.firstStepInput, yieldData?.firstStepInputUnit)}
                  </Text>
                </View>
                <View style={styles.yieldMetricItem}>
                  <Text style={styles.label}>末道产出</Text>
                  <Text style={[styles.value, yieldData?.lastStepOutput != null ? styles.highlight : {}]}>
                    {formatQuantity(yieldData?.lastStepOutput, yieldData?.lastStepOutputUnit)}
                  </Text>
                </View>
              </View>

              {isRollingYield ? (
                <Text style={styles.yieldHint} testID="batch-detail-yield-open-hint">
                  未关单也会显示当前出成率；滚动订单继续报工后这里会更新，完工入库后才锁定最终值。
                </Text>
              ) : (
                <Text style={styles.yieldLockedNote} testID="batch-detail-yield-locked-note">
                  批次已完工，出成率已锁定为最终值。
                </Text>
              )}
            </>
          )}
        </NeoCard>

        {/* Materials */}
        {batch.rawMaterials && batch.rawMaterials.length > 0 && (
          <NeoCard style={styles.card} padding="m">
            <Text variant="titleMedium" style={styles.sectionTitle}>原料信息</Text>
            {batch.rawMaterials.map((material: any, index: number) => (
              <View key={index} style={styles.materialRow}>
                <Text style={styles.value}>{material.materialType || material.type}</Text>
                <StatusBadge status={`${material.quantity} ${material.unit || 'kg'}`} variant="info" />
              </View>
            ))}
          </NeoCard>
        )}

        {/* Actions */}
        <NeoCard style={styles.card} padding="m">
          <Text variant="titleMedium" style={styles.sectionTitle}>快捷操作</Text>
          <View style={styles.actionGrid}>
            <Menu
              visible={qualityMenuVisible}
              onDismiss={() => setQualityMenuVisible(false)}
              anchor={
                <NeoButton variant="outline" style={styles.actionButton} onPress={() => setQualityMenuVisible(true)} icon="clipboard-check">
                  质检记录
                </NeoButton>
              }
            >
              <Menu.Item onPress={() => { setQualityMenuVisible(false); navigation.navigate('CreateQualityRecord', { batchId: batch!.id.toString(), inspectionType: 'raw_material' }); }} title="原材料检验" />
              <Menu.Item onPress={() => { setQualityMenuVisible(false); navigation.navigate('CreateQualityRecord', { batchId: batch!.id.toString(), inspectionType: 'process' }); }} title="过程检验" />
              <Menu.Item onPress={() => { setQualityMenuVisible(false); navigation.navigate('CreateQualityRecord', { batchId: batch!.id.toString(), inspectionType: 'final_product' }); }} title="成品检验" />
            </Menu>
            
            <NeoButton
                variant="outline"
                style={styles.actionButton}
                onPress={() => navigation.navigate('CostAnalysisDashboard', { batchId: batch.id.toString() })}
                icon="cash"
                testID="batch-detail-cost-analysis-btn"
            >
                成本分析
            </NeoButton>

            <NeoButton
                variant="outline"
                style={styles.actionButton}
                onPress={() => navigation.navigate('AIAnalysis', { batchId: batch.id.toString() })}
                icon="brain"
            >
                AI效率分析
            </NeoButton>

            <NeoButton
                variant="outline"
                style={styles.actionButton}
                onPress={() => navigation.navigate('LabelScan', { workstationId: 'WS-001', batchNumber: batch.batchNumber })}
                icon="label-variant-outline"
            >
                标签扫描
            </NeoButton>
          </View>
        </NeoCard>

        {/* SP2 撤回整单 — 仅主管/组长可见 */}
        {!isOperator && (
          <NeoCard style={styles.card} padding="m">
            <Text variant="titleMedium" style={styles.sectionTitle}>撤回整单</Text>
            <Text style={styles.reversalDesc}>
              批次号：{batch.batchNumber}　撤回后批次回草稿，已报工记录保留。
            </Text>
            <Menu
              visible={reversalMenuVisible}
              onDismiss={() => setReversalMenuVisible(false)}
              anchor={
                <NeoButton
                  variant="outline"
                  style={[styles.actionButton, styles.reversalPickerBtn]}
                  onPress={() => setReversalMenuVisible(true)}
                  icon="chevron-down"
                  disabled={reversalSubmitting}
                >
                  {reversalReason || '选择撤回原因（必填）'}
                </NeoButton>
              }
            >
              {REVERSAL_REASONS.map((r) => (
                <Menu.Item
                  key={r.value}
                  title={r.label}
                  onPress={() => {
                    setReversalReason(r.value);
                    setReversalMenuVisible(false);
                  }}
                />
              ))}
            </Menu>
            <NeoButton
              variant="primary"
              style={[styles.actionButton, styles.reversalBtn]}
              onPress={() => { void handleReversal(); }}
              disabled={!reversalReason || reversalSubmitting}
              loading={reversalSubmitting}
            >
              确认撤回
            </NeoButton>
          </NeoCard>
        )}
          </>
        )}

      </ScrollView>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  centerContainer: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
  },
  content: {
      padding: 16,
      paddingBottom: 40,
  },
  card: {
      marginBottom: 16,
  },
  headerRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
  },
  batchNumber: {
      fontWeight: '700',
      color: theme.colors.onSurface,
  },
  sectionTitle: {
      fontWeight: '600',
      marginBottom: 16,
      color: theme.colors.onSurface,
  },
  infoGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
  },
  infoItem: {
      width: '50%',
      marginBottom: 16,
  },
  label: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
      marginBottom: 2,
  },
  value: {
      fontSize: 14,
      color: theme.colors.onSurface,
      fontWeight: '500',
  },
  valueSmall: {
      fontSize: 13,
      color: theme.colors.onSurface,
  },
  highlight: {
      color: theme.colors.primary,
      fontWeight: '700',
  },
  divider: {
      marginVertical: 12,
  },
  rowBetween: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
  },
  materialRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: 8,
      borderBottomWidth: 1,
      borderBottomColor: theme.colors.outlineVariant,
  },
  actionGrid: {
      gap: 12,
  },
  actionButton: {
      width: '100%',
  },
  errorText: {
      color: theme.colors.error,
      marginBottom: 16,
  },
  yieldHeader: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      justifyContent: 'space-between',
      gap: 12,
      marginBottom: 12,
  },
  yieldTitleWrap: {
      flex: 1,
  },
  yieldSubTitle: {
      fontSize: 13,
      color: theme.colors.onSurfaceVariant,
      marginTop: -10,
  },
  yieldRateValue: {
      fontSize: 28,
      fontWeight: '700',
      color: '#E8732E',
      marginTop: 2,
  },
  yieldFormula: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
      marginTop: 2,
      marginBottom: 12,
  },
  yieldMetricRow: {
      flexDirection: 'row',
      gap: 12,
      marginTop: 4,
  },
  yieldMetricItem: {
      flex: 1,
      backgroundColor: theme.colors.surfaceVariant,
      borderRadius: 8,
      padding: 12,
      minHeight: 64,
  },
  yieldHint: {
      fontSize: 13,
      color: '#E6A23C',
      backgroundColor: '#FDF6EC',
      borderRadius: 8,
      padding: 12,
      marginTop: 12,
      lineHeight: 18,
  },
  yieldLockedNote: {
      fontSize: 13,
      color: theme.custom.colors.success,
      marginTop: 12,
      lineHeight: 18,
  },
  yieldUnavailable: {
      backgroundColor: theme.colors.errorContainer,
      borderRadius: 8,
      padding: 12,
  },
  yieldUnavailableText: {
      color: theme.colors.onErrorContainer,
      fontSize: 13,
      lineHeight: 18,
  },
  // Tab styles
  tabBar: {
      marginBottom: 16,
  },
  // Stats card styles
  statsCard: {
      backgroundColor: '#FFF',
      borderRadius: 12,
      padding: 16,
      marginBottom: 16,
  },
  statsRow: {
      flexDirection: 'row',
      justifyContent: 'space-around',
      alignItems: 'center',
  },
  statItem: {
      alignItems: 'center',
      flex: 1,
  },
  statValue: {
      fontSize: 18,
      fontWeight: '600',
      color: theme.colors.onSurface,
      marginBottom: 4,
  },
  statLabel: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
  },
  statDivider: {
      width: 1,
      height: 32,
      backgroundColor: theme.colors.outlineVariant,
  },
  costValue: {
      color: '#E65100',
  },
  // Consumption list styles
  loadingContainer: {
      padding: 32,
      alignItems: 'center',
  },
  loadingText: {
      marginTop: 8,
      color: theme.colors.onSurfaceVariant,
  },
  emptyContainer: {
      alignItems: 'center',
      paddingVertical: 24,
  },
  emptyText: {
      color: theme.colors.onSurfaceVariant,
      marginTop: 8,
  },
  emptyHint: {
      color: theme.colors.onSurfaceVariant,
      fontSize: 12,
      marginTop: 4,
  },
  consumptionHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
      marginBottom: 12,
  },
  consumptionBatch: {
      fontSize: 15,
      fontWeight: '600',
      color: theme.colors.onSurface,
  },
  consumptionMaterial: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
      marginTop: 2,
  },
  consumptionCost: {
      fontSize: 16,
      fontWeight: '600',
      color: '#E65100',
  },
  consumptionDetails: {
      gap: 8,
  },
  consumptionRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
  },
  consumptionLabel: {
      fontSize: 13,
      color: theme.colors.onSurfaceVariant,
  },
  consumptionValue: {
      fontSize: 13,
      fontWeight: '500',
      color: theme.colors.onSurface,
  },
  // BOM Achievement Card styles
  bomCard: {
      backgroundColor: '#FFF',
      borderRadius: 12,
      padding: 16,
      marginBottom: 16,
  },
  bomLoadingContainer: {
      flexDirection: 'row',
      justifyContent: 'center',
      alignItems: 'center',
      paddingVertical: 12,
  },
  bomLoadingText: {
      marginLeft: 8,
      fontSize: 13,
      color: theme.colors.onSurfaceVariant,
  },
  bomHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 12,
  },
  bomTitle: {
      fontSize: 15,
      fontWeight: '600',
      color: theme.colors.onSurface,
  },
  bomRateBadge: {
      paddingHorizontal: 12,
      paddingVertical: 4,
      borderRadius: 12,
  },
  bomRateText: {
      fontSize: 16,
      fontWeight: '700',
  },
  bomOverviewRow: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: theme.colors.surfaceVariant,
      borderRadius: 8,
      paddingVertical: 10,
      paddingHorizontal: 12,
      marginBottom: 12,
  },
  bomOverviewItem: {
      flex: 1,
      alignItems: 'center',
  },
  bomOverviewDivider: {
      width: 1,
      height: 28,
      backgroundColor: theme.colors.outlineVariant,
  },
  bomOverviewLabel: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
      marginBottom: 2,
  },
  bomOverviewValue: {
      fontSize: 14,
      fontWeight: '600',
      color: theme.colors.onSurface,
  },
  bomMaterialList: {
      gap: 8,
  },
  bomMaterialListTitle: {
      fontSize: 13,
      fontWeight: '500',
      color: theme.colors.onSurfaceVariant,
      marginBottom: 4,
  },
  bomMaterialItem: {
      backgroundColor: theme.colors.surfaceVariant,
      borderRadius: 8,
      padding: 10,
  },
  bomMaterialHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 6,
  },
  bomMaterialName: {
      fontSize: 14,
      fontWeight: '500',
      color: theme.colors.onSurface,
  },
  bomMaterialRate: {
      fontSize: 14,
      fontWeight: '600',
  },
  bomMaterialDetails: {
      flexDirection: 'row',
      justifyContent: 'space-between',
  },
  bomMaterialDetail: {
      fontSize: 12,
      color: theme.colors.onSurfaceVariant,
  },
  // SP2 撤回整单
  reversalDesc: { fontSize: 13, color: '#606266', marginBottom: 12, lineHeight: 18 },
  reversalPickerBtn: { marginBottom: 12 },
  reversalBtn: { marginTop: 4 },
});
