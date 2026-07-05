/**
 * 仓储工作台首页
 * 功能: 出入库任务概览、今日统计、库存预警、温控监控
 * 参照: /docs/prd/prototype/warehouse/index.html
 *
 * API集成:
 * - dashboardAPI - 获取仪表板概览统计
 * - shipmentApiClient - 获取出货任务列表
 * - materialBatchApiClient - 获取入库批次和库存预警
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import {
  Text,
  Card,
  Surface,
  Icon,
  IconButton,
  Chip,
  SegmentedButtons,
  ActivityIndicator,
  useTheme,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { WHHomeStackParamList } from '../../../types/navigation';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { dashboardAPI } from '../../../services/api/dashboardApiClient';
import { shipmentApiClient, ShipmentRecord } from '../../../services/api/shipmentApiClient';
import { materialBatchApiClient, MaterialBatch } from '../../../services/api/materialBatchApiClient';
import { handleError, getErrorMsg } from '../../../utils/errorHandler';
import { logger } from '../../../utils/logger';
import { formatDate, formatNumberWithCommas } from '../../../utils/formatters';
import { WorkflowVisualizer } from '../../../components/workflow';
import { getBucketPrimaryStatus } from '../../../types/workflow';
import type { WorkflowModule } from '../../../types/workflow';

const WH_WORKFLOW_MODULES: WorkflowModule[] = ['inventory'];

// 主题色
const THEME_COLOR = '#4CAF50';

// 类型定义
interface OutboundTask {
  id: string;
  orderId: string;
  customer: string;
  product: string;
  quantity: number;
  unit: string;
  status: 'waiting' | 'packing' | 'packed' | 'shipped';
  dispatchTime: string;
  urgent: boolean;
}

interface InboundTask {
  id: string;
  batchId: string;
  supplier: string;
  material: string;
  quantity: number;
  unit: string;
  status: 'pending' | 'arrived' | 'inspecting' | 'completed';
  arrivalTime: string;
}

interface AlertItem {
  id: string;
  materialName: string;
  materialType: string;
  currentStock: number;
  safetyStock: number;
  unit: string;
  isExpiring: boolean;
}

interface TempZone {
  id: string;
  name: string;
  currentTemp: number;
  unit: string;
  status: 'normal' | 'warning' | 'error';
}

type NavigationProp = NativeStackNavigationProp<WHHomeStackParamList>;

export default function WHHomeScreen() {
  const { t } = useTranslation('warehouse');
  const navigation = useNavigation<NavigationProp>();
  const theme = useTheme();
  const { user } = useAuthStore();
  const { isScreenEnabled } = useFactoryFeatureStore();

  // 状态
  const [activeTab, setActiveTab] = useState<'outbound' | 'inbound'>('outbound');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 数据
  const [outboundTasks, setOutboundTasks] = useState<OutboundTask[]>([]);
  const [inboundTasks, setInboundTasks] = useState<InboundTask[]>([]);
  const [alerts, setAlerts] = useState<AlertItem[]>([]);
  const [tempZones, setTempZones] = useState<TempZone[]>([]);
  const [stats, setStats] = useState({
    todayInbound: 0,
    todayOutbound: 0,
    pendingOutbound: 0,
    alertCount: 0,
  });

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setError(null);
      logger.info('[WHHomeScreen] 开始加载仓储首页数据');

      // 并行加载所有数据
      const [
        shipmentsResult,
        batchesResult,
        lowStockResult,
        expiringResult,
        dashboardResult,
      ] = await Promise.allSettled([
        // 1. 获取出货任务 (待发货/已发货)
        // /shipments 后端 0-based 分页; 传 page:1 会漏掉第 0 页 (待发货任务全在 page 0)
        shipmentApiClient.getShipments({ status: 'pending', page: 0, size: 10 }),
        // 2. 获取入库批次 (今日创建的)
        materialBatchApiClient.getMaterialBatches({ page: 1, size: 10 }),
        // 3. 获取低库存预警
        materialBatchApiClient.getLowStockBatches(),
        // 4. 获取即将过期批次
        materialBatchApiClient.getExpiringBatches(7),
        // 5. 获取仪表板概览
        dashboardAPI.getDashboardOverview('today'),
      ]);

      // 处理出货任务
      if (shipmentsResult.status === 'fulfilled' && shipmentsResult.value?.data) {
        const shipments = shipmentsResult.value.data;
        const mappedTasks: OutboundTask[] = shipments.map((s: ShipmentRecord) => ({
          id: s.id,
          orderId: s.shipmentNumber || s.orderNumber || s.id,
          customer: s.customerId || t('messages.unknownCustomer'),
          product: s.productName || '商品',
          quantity: s.quantity || 0,
          unit: s.unit || 'kg',
          status: mapShipmentStatus(s.status),
          dispatchTime: s.shipmentDate ? `${formatDate(s.shipmentDate)} 发出` : t('messages.toBeArranged'),
          urgent: false, // 后端没有urgent字段，默认false
        }));
        setOutboundTasks(mappedTasks);
        logger.info(`[WHHomeScreen] 加载出货任务成功: ${mappedTasks.length}条`);
      } else {
        logger.warn('[WHHomeScreen] 加载出货任务失败，使用空列表');
        setOutboundTasks([]);
      }

      // 处理入库批次
      if (batchesResult.status === 'fulfilled') {
        const batchData = batchesResult.value as { data?: { content?: MaterialBatch[] } } | undefined;
        const batches = batchData?.data?.content ?? [];
        const mappedBatches: InboundTask[] = (Array.isArray(batches) ? batches : []).map((b: MaterialBatch) => ({
          id: b.id,
          batchId: b.batchNumber || b.id,
          supplier: b.supplierName || b.supplierId || t('messages.unknownSupplier'),
          material: b.materialName || t('messages.unknownMaterial'),
          quantity: b.inboundQuantity || 0,
          unit: 'kg',
          status: mapBatchStatus(b.status),
          arrivalTime: b.inboundDate ? `${t('home.inboundTask.inboundTime')}: ${formatDate(b.inboundDate)}` : t('home.status.pending'),
        }));
        setInboundTasks(mappedBatches.slice(0, 5)); // 只显示前5条
        logger.info(`[WHHomeScreen] 加载入库批次成功: ${mappedBatches.length}条`);
      } else {
        logger.warn('[WHHomeScreen] 加载入库批次失败，使用空列表');
        setInboundTasks([]);
      }

      // 处理库存预警
      const alertItems: AlertItem[] = [];
      if (lowStockResult.status === 'fulfilled') {
        const lowStockBatches = lowStockResult.value?.data ?? [];
        lowStockBatches.forEach((b: MaterialBatch) => {
          alertItems.push({
            id: b.id,
            materialName: b.materialName || b.batchNumber || t('messages.unknownMaterial'),
            materialType: b.storageType || 'fresh',
            currentStock: b.remainingQuantity || 0,
            safetyStock: 100, // 安全库存暂时硬编码
            unit: 'kg',
            isExpiring: false,
          });
        });
      }
      if (expiringResult.status === 'fulfilled') {
        const expiringBatches = expiringResult.value?.data ?? [];
        expiringBatches.forEach((b: MaterialBatch) => {
          alertItems.push({
            id: b.id,
            materialName: b.materialName || b.batchNumber || t('messages.unknownMaterial'),
            materialType: b.storageType || 'fresh',
            currentStock: b.remainingQuantity || 0,
            safetyStock: 100,
            unit: 'kg',
            isExpiring: true,
          });
        });
      }
      setAlerts(alertItems.slice(0, 5)); // 只显示前5个预警
      logger.info(`[WHHomeScreen] 加载库存预警成功: ${alertItems.length}条`);

      // 处理仪表板统计
      if (dashboardResult.status === 'fulfilled' && dashboardResult.value?.data) {
        const overview = dashboardResult.value.data;
        setStats({
          todayInbound: overview.todayStats?.materialReceived || 0,
          todayOutbound: overview.todayStats?.ordersCompleted || 0,
          pendingOutbound: overview.summary?.activeBatches || 0,
          alertCount: overview.summary?.activeAlerts || alertItems.length,
        });
        logger.info('[WHHomeScreen] 加载仪表板统计成功');
      } else {
        // 使用默认统计
        setStats({
          todayInbound: 0,
          todayOutbound: 0,
          pendingOutbound: outboundTasks.length,
          alertCount: alertItems.length,
        });
      }

      // 温控监控 - 后端暂无温控API，显示空状态
      setTempZones([]);

    } catch (err) {
      logger.error('[WHHomeScreen] 加载数据失败:', err);
      setError('加载失败');
      handleError(err, { title: t('messages.loadFailed') });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // 状态映射函数
  const mapShipmentStatus = (status: string | undefined): 'waiting' | 'packing' | 'packed' | 'shipped' => {
    switch (status?.toLowerCase()) {
      case 'pending': return 'waiting';
      case 'shipped': return 'shipped';
      case 'delivered': return 'shipped';
      default: return 'waiting';
    }
  };

  const mapBatchStatus = (status: string | undefined): 'pending' | 'arrived' | 'inspecting' | 'completed' => {
    switch (status?.toLowerCase()) {
      case 'available': return 'completed';
      case 'reserved': return 'inspecting';
      case 'depleted': return 'completed';
      case 'expired': return 'completed';
      default: return 'pending';
    }
  };

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 渲染状态标签
  const renderStatusBadge = (status: string, type: 'outbound' | 'inbound') => {
    let label = '';
    let color = '';

    if (type === 'outbound') {
      switch (status) {
        case 'waiting':
          label = t('home.status.waiting');
          color = '#FF9800';
          break;
        case 'packing':
          label = t('home.status.packing');
          color = '#2196F3';
          break;
        case 'packed':
          label = t('home.status.packed');
          color = '#4CAF50';
          break;
        case 'shipped':
          label = t('home.status.shipped');
          color = '#9E9E9E';
          break;
      }
    } else {
      switch (status) {
        case 'pending':
          label = t('home.status.pending');
          color = '#FF9800';
          break;
        case 'arrived':
          label = t('home.status.arrived');
          color = '#2196F3';
          break;
        case 'inspecting':
          label = t('home.status.inspecting');
          color = '#9C27B0';
          break;
        case 'completed':
          label = t('home.status.completed');
          color = '#4CAF50';
          break;
      }
    }

    return (
      <Chip
        mode="flat"
        style={{ backgroundColor: color + '20' }}
        textStyle={{ color, fontSize: 12 }}
        compact
      >
        {label}
      </Chip>
    );
  };

  // 渲染出货任务卡片
  const renderOutboundCard = (task: OutboundTask) => (
    <TouchableOpacity
      key={task.id}
      onPress={() => navigation.navigate('OutboundDetail', { orderId: task.id })}
    >
      <Card style={[styles.taskCard, task.urgent && styles.urgentCard]}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleSmall" style={styles.orderId}>
              {task.orderId}
            </Text>
            {renderStatusBadge(task.status, 'outbound')}
          </View>

          <View style={styles.cardInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.customer')}</Text>
              <Text style={styles.infoValue}>{task.customer}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.product')}</Text>
              <Text style={styles.infoValue}>{task.product}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.quantity')}</Text>
              <Text style={styles.infoValue}>
                {task.quantity} {task.unit}
              </Text>
            </View>
          </View>

          <View style={styles.cardFooter}>
            <Text
              style={[
                styles.dispatchTime,
                task.urgent && styles.urgentText,
              ]}
            >
              {task.urgent && t('home.outboundTask.urgent')}
              {task.dispatchTime}
            </Text>
            <TouchableOpacity
              style={styles.actionBtn}
              onPress={(e) => {
                e.stopPropagation();
                // 跳转到打包页面
              }}
            >
              <Text style={styles.actionText}>
                {task.status === 'packing' ? t('home.outboundTask.finishPacking') : t('home.outboundTask.startPacking')}
              </Text>
            </TouchableOpacity>
          </View>
        </Card.Content>
      </Card>
    </TouchableOpacity>
  );

  // 渲染入库任务卡片
  const renderInboundCard = (task: InboundTask) => (
    <TouchableOpacity
      key={task.id}
      onPress={() => navigation.navigate('InboundDetail', { batchId: task.id })}
    >
      <Card style={styles.taskCard}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleSmall" style={styles.orderId}>
              {task.batchId}
            </Text>
            {renderStatusBadge(task.status, 'inbound')}
          </View>

          <View style={styles.cardInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.supplier')}</Text>
              <Text style={styles.infoValue}>{task.supplier}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.goods')}</Text>
              <Text style={styles.infoValue}>{task.material}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.expectedQuantity')}</Text>
              <Text style={styles.infoValue}>
                {task.quantity} {task.unit}
              </Text>
            </View>
          </View>

          <View style={styles.cardFooter}>
            <Text
              style={[
                styles.dispatchTime,
                task.status === 'arrived' && styles.successText,
              ]}
            >
              {task.arrivalTime}
            </Text>
            <TouchableOpacity style={styles.actionBtn}>
              <Text style={styles.actionText}>
                {task.status === 'arrived' ? t('home.inboundTask.finishInbound') : t('home.inboundTask.confirmInbound')}
              </Text>
            </TouchableOpacity>
          </View>
        </Card.Content>
      </Card>
    </TouchableOpacity>
  );

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={THEME_COLOR} />
      </View>
    );
  }

  // 错误状态
  if (error && outboundTasks.length === 0 && inboundTasks.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
          <Text style={{ color: '#606266', marginTop: 12, fontSize: 14 }}>加载失败，请检查网络</Text>
          <TouchableOpacity
            style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#10b981', borderRadius: 6 }}
            onPress={() => loadData()}
          >
            <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>重试</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Surface style={styles.header}>
        <View style={styles.headerContent}>
          <Text variant="headlineSmall" style={styles.headerTitle}>
            {t('home.title')}
          </Text>
          <Text variant="bodySmall" style={styles.headerSubtitle}>
            {t('home.headerSubtitle', { inbound: inboundTasks.length, pending: stats.pendingOutbound })}
          </Text>
        </View>
        <IconButton
          icon="bell-outline"
          size={24}
          onPress={() => {}}
        />
      </Surface>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[THEME_COLOR]}
          />
        }
      >
        {/* U-NAV-1 业务流程图导航 — Sprint 2 Track G (WH: inventory) */}
        <View style={{ marginHorizontal: 16, marginTop: 12 }}>
          <WorkflowVisualizer
            modules={WH_WORKFLOW_MODULES}
            factoryId={user?.factoryId}
            aiTriggerEnabled
            onNodePress={(module, nodeId) =>
              navigation.navigate('WHInventoryList', { statusFilter: getBucketPrimaryStatus(module, nodeId) })
            }
            onNodeLongPress={(_module, ctx) =>
              navigation.navigate('AIChat', { entryContext: ctx })
            }
            onAITrigger={(_module, ctx) =>
              navigation.navigate('AIChat', { entryContext: ctx })
            }
          />
        </View>

        {/* 领料调拨入口 */}
        <TouchableOpacity
          style={{ marginHorizontal: 16, marginBottom: 12, backgroundColor: '#fff', borderRadius: 12, padding: 16, flexDirection: 'row', alignItems: 'center', elevation: 2 }}
          onPress={() => navigation.navigate('TransferList' as never)}
        >
          <View style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: '#FFF7ED', alignItems: 'center', justifyContent: 'center', marginRight: 12 }}>
            <Icon source="package-down" size={24} color="#EA580C" />
          </View>
          <View style={{ flex: 1 }}>
            <Text variant="titleSmall" style={{ fontWeight: '600', color: '#333' }}>领料调拨</Text>
            <Text style={{ fontSize: 12, color: '#999', marginTop: 2 }}>查看和审批生产领料申请</Text>
          </View>
          <Icon source="chevron-right" size={24} color="#ccc" />
        </TouchableOpacity>

        {/* 任务Tab切换 */}
        {(isScreenEnabled('OutboundManagement') || isScreenEnabled('InboundManagement')) && (
        <View style={styles.tabContainer}>
          <SegmentedButtons
            value={activeTab}
            onValueChange={(value) => setActiveTab(value as 'outbound' | 'inbound')}
            buttons={[
              ...(isScreenEnabled('OutboundManagement') ? [{
                value: 'outbound' as const,
                label: `${t('home.tabs.outbound')} (${outboundTasks.length})`,
              }] : []),
              ...(isScreenEnabled('InboundManagement') ? [{
                value: 'inbound' as const,
                label: `${t('home.tabs.inbound')} (${inboundTasks.length})`,
              }] : []),
            ]}
            style={styles.segmentedButtons}
          />
        </View>
        )}

        {/* 任务列表 */}
        {((activeTab === 'outbound' && isScreenEnabled('OutboundManagement')) ||
          (activeTab === 'inbound' && isScreenEnabled('InboundManagement'))) && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {activeTab === 'outbound'
                ? t('home.sections.todayOutbound')
                : t('home.sections.todayInbound')}
            </Text>
            <TouchableOpacity>
              <Text style={styles.sectionMore}>{t('home.sections.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {activeTab === 'outbound'
            ? outboundTasks.map(renderOutboundCard)
            : inboundTasks.map(renderInboundCard)}
        </View>
        )}

        {/* 今日统计 */}
        <View style={styles.statsGrid}>
          <Surface style={styles.statCard}>
            <View style={[styles.statIcon, { backgroundColor: '#E3F2FD' }]}>
              <Text style={{ fontSize: 20 }}>📦</Text>
            </View>
            <Text style={styles.statTitle}>{t('home.stats.todayInbound')}</Text>
            <Text style={styles.statValue}>{stats.todayInbound}</Text>
            <Text style={styles.statUnit}>{t('home.units.kg')}</Text>
          </Surface>

          <Surface style={styles.statCard}>
            <View style={[styles.statIcon, { backgroundColor: '#E8F5E9' }]}>
              <Text style={{ fontSize: 20 }}>🚚</Text>
            </View>
            <Text style={styles.statTitle}>{t('home.stats.todayOutbound')}</Text>
            <Text style={styles.statValue}>{formatNumberWithCommas(stats.todayOutbound)}</Text>
            <Text style={styles.statUnit}>{t('home.units.kg')}</Text>
          </Surface>

          <Surface style={styles.statCard}>
            <View style={[styles.statIcon, { backgroundColor: '#FFF3E0' }]}>
              <Text style={{ fontSize: 20 }}>⏳</Text>
            </View>
            <Text style={styles.statTitle}>{t('home.stats.pendingOutbound')}</Text>
            <Text style={styles.statValue}>{stats.pendingOutbound}</Text>
            <Text style={styles.statUnit}>{t('home.units.orders')}</Text>
          </Surface>

          <Surface style={styles.statCard}>
            <View style={[styles.statIcon, { backgroundColor: '#FFEBEE' }]}>
              <Text style={{ fontSize: 20 }}>⚠️</Text>
            </View>
            <Text style={styles.statTitle}>{t('home.stats.alertCount')}</Text>
            <Text style={styles.statValue}>{stats.alertCount}</Text>
            <Text style={styles.statUnit}>{t('home.units.items')}</Text>
          </Surface>
        </View>

        {/* 库存预警 */}
        {isScreenEnabled('AlertHandling') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('home.alerts.title')}
            </Text>
            <TouchableOpacity>
              <Text style={styles.sectionMore}>{t('home.sections.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {alerts.map((alert) => (
            <TouchableOpacity
              key={alert.id}
              onPress={() => navigation.navigate('AlertHandle', { alertId: alert.id })}
            >
              <Surface style={styles.alertCard}>
                <View
                  style={[
                    styles.alertIcon,
                    {
                      backgroundColor: alert.isExpiring ? '#FFEBEE' : '#FFF3E0',
                    },
                  ]}
                >
                  <Text style={{ fontSize: 16 }}>
                    {alert.isExpiring ? '🔴' : '🟡'}
                  </Text>
                </View>
                <View style={styles.alertContent}>
                  <Text style={styles.alertName}>{alert.materialName}</Text>
                  <Text style={styles.alertDetail}>
                    {t('home.alerts.safetyStock')}: {alert.safetyStock}
                    {alert.unit}
                    {alert.isExpiring && ` | ${t('home.alerts.expiring')}`}
                  </Text>
                </View>
                <Text style={styles.alertValue}>
                  {alert.currentStock}
                  {alert.unit}
                </Text>
              </Surface>
            </TouchableOpacity>
          ))}
        </View>
        )}

        {/* 温控监控 */}
        {isScreenEnabled('TempMonitoring') && (
        <TouchableOpacity
          onPress={() => navigation.navigate('TempMonitor')}
        >
          <Surface style={styles.tempMonitorCard}>
            <View style={styles.tempMonitorHeader}>
              <Text variant="titleMedium" style={styles.sectionTitle}>
                {t('home.tempMonitor.title')}
              </Text>
              <View style={styles.tempStatus}>
                <View style={styles.statusDot} />
                <Text style={styles.statusText}>{t('home.tempMonitor.online')}</Text>
              </View>
            </View>

            <View style={styles.tempZones}>
              {tempZones.length === 0 && (
                <Text style={{ color: '#999', fontSize: 13, textAlign: 'center', paddingVertical: 16 }}>
                  {t('home.tempMonitor.noData', { defaultValue: '暂无温控设备数据' })}
                </Text>
              )}
              {tempZones.map((zone) => (
                <Surface key={zone.id} style={styles.tempZoneCard}>
                  <View style={styles.tempZoneHeader}>
                    <Text style={styles.tempZoneName}>{zone.name}</Text>
                    <Chip
                      mode="flat"
                      style={{
                        backgroundColor:
                          zone.status === 'normal'
                            ? '#E8F5E9'
                            : zone.status === 'warning'
                            ? '#FFF3E0'
                            : '#FFEBEE',
                      }}
                      textStyle={{
                        color:
                          zone.status === 'normal'
                            ? '#4CAF50'
                            : zone.status === 'warning'
                            ? '#FF9800'
                            : '#F44336',
                        fontSize: 11,
                      }}
                      compact
                    >
                      {zone.status === 'normal' ? t('home.tempMonitor.normal') : zone.status === 'warning' ? t('home.tempMonitor.warning') : t('home.tempMonitor.error')}
                    </Chip>
                  </View>
                  <View style={styles.tempValue}>
                    <Text style={styles.tempNumber}>{zone.currentTemp}</Text>
                    <Text style={styles.tempUnit}>{zone.unit}</Text>
                  </View>
                </Surface>
              ))}
            </View>
          </Surface>
        </TouchableOpacity>
        )}

        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: THEME_COLOR,
  },
  headerContent: {
    flex: 1,
  },
  headerTitle: {
    color: '#fff',
    fontWeight: 'bold',
  },
  headerSubtitle: {
    color: 'rgba(255,255,255,0.8)',
    marginTop: 2,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  tabContainer: {
    marginBottom: 16,
  },
  segmentedButtons: {
    backgroundColor: '#fff',
  },
  section: {
    marginBottom: 20,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontWeight: '600',
    color: '#333',
  },
  sectionMore: {
    color: THEME_COLOR,
    fontSize: 14,
  },
  taskCard: {
    marginBottom: 12,
    backgroundColor: '#fff',
    borderRadius: 12,
  },
  urgentCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#F44336',
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  orderId: {
    fontWeight: '600',
    color: '#333',
  },
  cardInfo: {
    marginBottom: 12,
  },
  infoRow: {
    flexDirection: 'row',
    marginBottom: 6,
  },
  infoLabel: {
    width: 60,
    color: '#999',
    fontSize: 13,
  },
  infoValue: {
    flex: 1,
    color: '#333',
    fontSize: 13,
  },
  cardFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
    paddingTop: 12,
  },
  dispatchTime: {
    color: '#666',
    fontSize: 13,
  },
  urgentText: {
    color: '#F44336',
    fontWeight: '600',
  },
  successText: {
    color: '#4CAF50',
  },
  actionBtn: {
    backgroundColor: THEME_COLOR,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  actionText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '500',
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginBottom: 20,
    gap: 12,
  },
  statCard: {
    width: '47%',
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#fff',
    alignItems: 'center',
  },
  statIcon: {
    width: 40,
    height: 40,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  statTitle: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
  },
  statUnit: {
    fontSize: 12,
    color: '#999',
  },
  alertCard: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    borderRadius: 10,
    backgroundColor: '#fff',
    marginBottom: 10,
  },
  alertIcon: {
    width: 36,
    height: 36,
    borderRadius: 18,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  alertContent: {
    flex: 1,
  },
  alertName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginBottom: 2,
  },
  alertDetail: {
    fontSize: 12,
    color: '#999',
  },
  alertValue: {
    fontSize: 16,
    fontWeight: '600',
    color: '#F44336',
  },
  tempMonitorCard: {
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#fff',
    marginBottom: 20,
  },
  tempMonitorHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  tempStatus: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#4CAF50',
    marginRight: 6,
  },
  statusText: {
    fontSize: 13,
    color: '#4CAF50',
  },
  tempZones: {
    flexDirection: 'row',
    gap: 12,
  },
  tempZoneCard: {
    flex: 1,
    padding: 12,
    borderRadius: 10,
    backgroundColor: '#f9f9f9',
  },
  tempZoneHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  tempZoneName: {
    fontSize: 12,
    color: '#666',
  },
  tempValue: {
    flexDirection: 'row',
    alignItems: 'flex-end',
  },
  tempNumber: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#333',
  },
  tempUnit: {
    fontSize: 14,
    color: '#666',
    marginBottom: 4,
    marginLeft: 2,
  },
});
