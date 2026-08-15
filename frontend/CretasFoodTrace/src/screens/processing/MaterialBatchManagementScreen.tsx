import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  Chip,
  ActivityIndicator,
  List,
  Searchbar,
  SegmentedButtons,
  ProgressBar,
  FAB,
  IconButton,
  Portal,
  Dialog,
  Button,
  TextInput,
} from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { materialBatchApiClient, MaterialBatch } from '../../services/api/materialBatchApiClient';
import { useAuthStore } from '../../store/authStore';
import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { APP_CONFIG, API_BASE_URL } from '../../constants/config';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { getUserPermissions, hasPermission } from '../../types/auth';

// 创建MaterialBatchManagement专用logger
const materialBatchLogger = logger.createContextLogger('MaterialBatchManagement');

/**
 * 原材料批次管理页面
 * P3-库存: 集成CRUD、导出API
 *
 * 功能：
 * - 批次列表、FIFO查询、过期预警、低库存预警
 * - CRUD操作：创建、编辑、删除批次
 * - 库存导出：Excel格式
 * - 转冻品/撤销转冻品
 *
 * 注：预留/释放/消耗操作已移至生产计划模块
 */
export default function MaterialBatchManagementScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();
  const userPermissions = getUserPermissions(user);
  const canManageInventory = hasPermission(user, 'warehouse:write')
    || userPermissions.includes('warehouse:read_write')
    || userPermissions.includes('inventory:read_write');

  const [batches, setBatches] = useState<MaterialBatch[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterTab, setFilterTab] = useState('all');
  const [handlingExpired, setHandlingExpired] = useState(false);

  // P3-库存: CRUD状态
  const [selectedBatch, setSelectedBatch] = useState<MaterialBatch | null>(null);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [exporting, setExporting] = useState(false);

  // 转冻品状态
  const [convertingToFrozen, setConvertingToFrozen] = useState(false);
  const [undoingFrozen, setUndoingFrozen] = useState(false);
  const [showUndoDialog, setShowUndoDialog] = useState(false);
  const [undoReason, setUndoReason] = useState('');

  useEffect(() => {
    loadBatches();
  }, [filterTab]);

  const loadBatches = async () => {
    try {
      setLoading(true);
      let response;

      materialBatchLogger.debug('加载批次列表', { filterTab });

      switch (filterTab) {
        case 'expiring':
          // API: 即将过期批次（7天内）
          response = await materialBatchApiClient.getExpiringBatches(7, user?.factoryId);
          setBatches(Array.isArray(response.data) ? response.data : (response.data as { content?: MaterialBatch[] })?.content || []);
          break;
        case 'expired':
          // API: 已过期批次
          response = await materialBatchApiClient.getExpiredBatches(user?.factoryId);
          setBatches(Array.isArray(response.data) ? response.data : (response.data as { content?: MaterialBatch[] })?.content || []);
          break;
        case 'low_stock':
          // API: 低库存批次
          response = await materialBatchApiClient.getLowStockBatches(user?.factoryId);
          setBatches(Array.isArray(response.data) ? response.data : (response.data as { content?: MaterialBatch[] })?.content || []);
          break;
        default:
          // API: 所有批次
          response = await materialBatchApiClient.getMaterialBatches({
            factoryId: user?.factoryId,
            page: 1, // 后端要求 page >= 1
            size: 100,
          });
          setBatches(Array.isArray(response.data) ? response.data : (response.data as { content?: MaterialBatch[] })?.content || []);
      }

      materialBatchLogger.info('批次列表加载成功', { count: batches.length });
    } catch (error) {
      materialBatchLogger.error('加载批次列表失败', error, { filterTab });
      handleError(error, {
        showAlert: true,
        title: '加载失败',
        customMessage: '加载批次列表失败',
      });

      // 清空数据（不降级）
      setBatches([]);
    } finally {
      setLoading(false);
    }
  };

  // 存储类型相关函数 (从原料类型获取)
  const getStorageTypeText = (type: string) => {
    switch (type?.toLowerCase()) {
      case 'fresh': return '鲜品';
      case 'frozen': return '冻品';
      case 'dry': return '干货';
      default: return '';
    }
  };

  const getStorageTypeColor = (type: string) => {
    switch (type?.toLowerCase()) {
      case 'fresh': return '#8BC34A';   // 浅绿色
      case 'frozen': return '#2196F3';  // 蓝色
      case 'dry': return '#FF9800';     // 橙色
      default: return '#999999';
    }
  };

  // 获取可用状态 (兼容旧数据: FRESH/FROZEN 视为 AVAILABLE)
  const getAvailabilityStatus = (status: string) => {
    const lowerStatus = status?.toLowerCase();
    // FRESH/FROZEN 在 status 中时，视为 AVAILABLE (兼容旧数据)
    if (lowerStatus === 'fresh' || lowerStatus === 'frozen') {
      return 'available';
    }
    return lowerStatus;
  };

  const getStatusColor = (status: string) => {
    switch (getAvailabilityStatus(status)) {
      case 'available': return '#4CAF50';
      case 'reserved': return '#FF9800';
      case 'depleted': return '#9E9E9E';
      case 'used_up': return '#9E9E9E';
      case 'expired': return '#F44336';
      case 'inspecting': return '#2196F3';
      case 'scrapped': return '#795548';
      default: return '#999999';
    }
  };

  const getStatusText = (status: string) => {
    switch (getAvailabilityStatus(status)) {
      case 'available': return '可用';
      case 'reserved': return '预留';
      case 'depleted': return '耗尽';
      case 'used_up': return '用完';
      case 'expired': return '过期';
      case 'inspecting': return '质检中';
      case 'scrapped': return '报废';
      default: return status || '未知';
    }
  };

  const getQualityColor = (grade?: string) => {
    switch (grade) {
      case 'A': return '#4CAF50';
      case 'B': return '#FF9800';
      case 'C': return '#F44336';
      default: return '#2196F3';
    }
  };

  const calculateDaysUntilExpiry = (expiryDate?: string) => {
    if (!expiryDate) return null;
    const now = new Date();
    const expiry = new Date(expiryDate);
    const diffTime = expiry.getTime() - now.getTime();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    return diffDays;
  };

  const getExpiryWarning = (days: number | null) => {
    if (days === null) return null;
    if (days < 0) return { text: '已过期', color: '#F44336', level: 'expired' };
    if (days === 0) return { text: '今天过期', color: '#F44336', level: 'critical' };
    if (days === 1) return { text: '明天过期', color: '#F44336', level: 'critical' };
    if (days <= 3) return { text: `${days}天后过期`, color: '#F44336', level: 'urgent' };
    if (days <= 7) return { text: `${days}天后过期`, color: '#FF9800', level: 'warning' };
    return { text: `${days}天后过期`, color: '#666666', level: 'normal' };
  };

  /**
   * P3-库存: 导出库存数据到Excel
   */
  const handleExportInventory = async () => {
    try {
      setExporting(true);
      materialBatchLogger.info('开始导出库存数据');

      const factoryId = user?.factoryId || user?.factoryUser?.factoryId;
      if (!factoryId) {
        Alert.alert('错误', '无法获取工厂信息');
        return;
      }

      // 调用导出API
      const apiUrl = `${API_BASE_URL}/api/mobile/${factoryId}/material-batches/export`;
      const timestamp = new Date().getTime();
      const fileName = `inventory_${timestamp}.xlsx`;
      const fileUri = `${FileSystem.documentDirectory}${fileName}`;

      materialBatchLogger.debug('开始下载文件', { fileUri });
      const downloadResult = await FileSystem.downloadAsync(apiUrl, fileUri);

      if (downloadResult.status !== 200) {
        throw new Error(`下载失败，HTTP状态码: ${downloadResult.status}`);
      }

      materialBatchLogger.info('导出完成', { uri: downloadResult.uri });

      // 获取文件信息
      const fileInfo = await FileSystem.getInfoAsync(downloadResult.uri);
      const fileSize = fileInfo.exists && !fileInfo.isDirectory ? (fileInfo as { size?: number }).size || 0 : 0;
      const isAvailable = await Sharing.isAvailableAsync();

      if (isAvailable) {
        Alert.alert(
          '导出成功',
          `库存数据已导出\n\n文件大小：${(fileSize / 1024).toFixed(2)} KB`,
          [
            { text: '稍后查看', style: 'cancel' },
            {
              text: '分享文件',
              onPress: async () => {
                try {
                  await Sharing.shareAsync(downloadResult.uri);
                } catch (error) {
                  materialBatchLogger.error('分享文件失败', error);
                  Alert.alert('分享失败', '无法分享文件');
                }
              },
            },
          ]
        );
      } else {
        Alert.alert('导出成功', `文件已保存到：${downloadResult.uri}`);
      }
    } catch (error) {
      materialBatchLogger.error('导出库存数据失败', error);
      handleError(error, {
        showAlert: true,
        title: '导出失败',
        customMessage: '导出库存数据失败，请稍后重试',
      });
    } finally {
      setExporting(false);
    }
  };

  /**
   * P3-库存: 删除批次
   */
  const handleDeleteBatch = async () => {
    if (!selectedBatch) return;

    try {
      setLoading(true);
      materialBatchLogger.info('删除批次', { batchId: selectedBatch.id });

      await materialBatchApiClient.deleteBatch(selectedBatch.id, user?.factoryId);
      materialBatchLogger.info('批次删除成功', { batchId: selectedBatch.id });

      Alert.alert('删除成功', `批次 ${selectedBatch.batchNumber} 已删除`);

      setShowDeleteDialog(false);
      setSelectedBatch(null);

      // 刷新列表
      await loadBatches();
    } catch (error) {
      materialBatchLogger.error('删除批次失败', error, { batchId: selectedBatch.id });
      Alert.alert('删除失败', getErrorMsg(error) || '删除批次失败，请重试');
    } finally {
      setLoading(false);
    }
  };

  /**
   * 打开删除对话框
   */
  const openDeleteDialog = (batch: MaterialBatch) => {
    setSelectedBatch(batch);
    setShowDeleteDialog(true);
  };

  // P1-006: 转冻品功能
  const handleConvertToFrozen = async (batch: MaterialBatch) => {
    try {
      setConvertingToFrozen(true);
      materialBatchLogger.info('转为冻品', { batchId: batch.id });

      // 获取当前日期
      const today = new Date().toISOString().split('T')[0] as string;

      // 调用API
      await materialBatchApiClient.convertToFrozen(
        String(batch.id),
        {
          convertedBy: user?.id ?? 0,
          convertedDate: today,
          storageLocation: batch.storageLocation || '冷冻库',
          notes: `批次 ${batch.batchNumber} 转为冻品`,
        },
        user?.factoryId ?? ''
      );

      materialBatchLogger.info('转冻品成功', { batchId: batch.id });
      Alert.alert(
        '转换成功',
        `批次 ${batch.batchNumber} 已成功转为冻品\n保质期已延长，存储位置已更新`
      );

      // 刷新列表
      await loadBatches();
    } catch (error) {
      materialBatchLogger.error('转冻品失败', error, { batchId: batch.id });
      Alert.alert(
        '转换失败',
        getErrorMsg(error) || '转冻品失败，请重试'
      );
    } finally {
      setConvertingToFrozen(false);
    }
  };

  // 撤销转冻品功能
  const handleUndoFrozen = async (batch: MaterialBatch) => {
    try {
      setUndoingFrozen(true);
      materialBatchLogger.info('撤销冻品转换', { batchId: batch.id, reason: undoReason });

      // 调用API
      await materialBatchApiClient.undoFrozen(
        batch.id,
        {
          operatorId: user?.id ?? 0,
          reason: undoReason || '误操作撤销',
        },
        user?.factoryId
      );

      materialBatchLogger.info('撤销冻品成功', { batchId: batch.id });
      Alert.alert(
        '撤销成功',
        `批次 ${batch.batchNumber} 已恢复为鲜品状态`
      );

      // 关闭弹窗并重置
      setShowUndoDialog(false);
      setUndoReason('');
      setSelectedBatch(null);

      // 刷新列表
      await loadBatches();
    } catch (error) {
      materialBatchLogger.error('撤销冻品失败', error, { batchId: batch.id });
      const errorMsg = getErrorMsg(error) || '撤销失败';
      Alert.alert('撤销失败', errorMsg);
    } finally {
      setUndoingFrozen(false);
    }
  };

  // FIFO推荐：返回应优先使用的批次
  const getFIFORecommendation = () => {
    const availableBatches = batches.filter(b =>
      b.status === 'available' && b.remainingQuantity > 0
    );

    if (availableBatches.length === 0) return null;

    // 按入库日期排序，最早的优先
    const sortedByDate = [...availableBatches].sort((a, b) =>
      new Date(a.inboundDate).getTime() - new Date(b.inboundDate).getTime()
    );

    return sortedByDate[0];
  };

  const fifoRecommended = getFIFORecommendation();

  const calculateUsagePercentage = (batch: MaterialBatch) => {
    const inbound = batch.inboundQuantity ?? 0;
    const used = batch.usedQuantity ?? 0;
    if (inbound === 0) return 0;
    return (used / inbound) * 100;
  };

  const calculateRemainingPercentage = (batch: MaterialBatch) => {
    const inbound = batch.inboundQuantity ?? 0;
    const remaining = batch.remainingQuantity ?? 0;
    if (inbound === 0) return 0;
    return (remaining / inbound) * 100;
  };

  // 筛选批次
  const filteredBatches = batches.filter(batch => {
    if (!searchQuery.trim()) return true;
    const query = searchQuery.toLowerCase();
    return (
      batch.batchNumber.toLowerCase().includes(query) ||
      batch.materialTypeId?.toLowerCase().includes(query) ||
      batch.storageLocation?.toLowerCase().includes(query)
    );
  });

  return (
    <View style={styles.container}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="原材料批次管理" />
        <Appbar.Action icon="robot-outline" onPress={() => navigation.dispatch(CommonActions.navigate('FAAITab', { screen: 'AIChat', params: { entityType: 'MATERIAL' } }))} />
        <Appbar.Action icon="download" onPress={handleExportInventory} />
        <Appbar.Action icon="refresh" onPress={loadBatches} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Search */}
        <Searchbar
          placeholder="搜索批次号、原料类型、储存位置"
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.searchbar}
        />

        {/* Tab Filter */}
        <Card style={styles.filterCard}>
          <Card.Content>
            <SegmentedButtons
              value={filterTab}
              onValueChange={setFilterTab}
              buttons={[
                { value: 'all', label: '全部' },
                { value: 'expiring', label: '即将过期' },
                { value: 'expired', label: '已过期' },
                { value: 'low_stock', label: '低库存' },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{batches.length}</Text>
                <Text style={styles.statLabel}>批次总数</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {batches.filter(b => b.status === 'available').length}
                </Text>
                <Text style={styles.statLabel}>可用</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {batches.filter(b => {
                    const days = calculateDaysUntilExpiry(b.expiryDate);
                    return days !== null && days <= 7 && days >= 0;
                  }).length}
                </Text>
                <Text style={styles.statLabel}>预警</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Handle Expired Batches Button */}
        {!canManageInventory && (
          <Card style={styles.readOnlyNotice}>
            <Card.Content>
              <Text style={styles.readOnlyNoticeTitle}>原材料库存为只读</Text>
              <Text style={styles.readOnlyNoticeText}>批次编辑、删除和入库请交由仓库角色处理。</Text>
            </Card.Content>
          </Card>
        )}

        {canManageInventory && filterTab === 'expired' && batches.length > 0 && (
          <Card style={styles.actionCard}>
            <Card.Content>
              <Text variant="bodyMedium" style={styles.actionDescription}>
                发现 {batches.length} 个过期批次。您可以批量处理这些批次，系统将自动更新其状态。
              </Text>
              <Button
                mode="contained"
                icon="alert-circle-check"
                onPress={async () => {
                  Alert.alert(
                    '批量处理过期批次',
                    `确定要处理 ${batches.length} 个过期批次吗？\n\n系统将自动标记这些批次为已过期状态。`,
                    [
                      { text: '取消', style: 'cancel' },
                      {
                        text: '确认处理',
                        onPress: async () => {
                          try {
                            setHandlingExpired(true);
                            materialBatchLogger.info('处理过期批次', { count: batches.length });

                            // API integration - POST /material-batches/handle-expired
                            const response = await materialBatchApiClient.handleExpiredBatches(undefined, user?.factoryId);

                            materialBatchLogger.info('过期批次处理成功', { count: batches.length });

                            Alert.alert(
                              '处理成功',
                              `已成功处理 ${batches.length} 个过期批次`,
                              [
                                {
                                  text: '确定',
                                  onPress: () => {
                                    // 刷新列表
                                    loadBatches();
                                  },
                                },
                              ]
                            );
                          } catch (error) {
                            materialBatchLogger.error('处理过期批次失败', error);
                            Alert.alert('处理失败', getErrorMsg(error) || '无法处理过期批次，请稍后重试');
                          } finally {
                            setHandlingExpired(false);
                          }
                        },
                      },
                    ]
                  );
                }}
                loading={handlingExpired}
                disabled={handlingExpired}
                style={styles.actionButton}
                buttonColor="#FF9800"
              >
                {handlingExpired ? '处理中...' : '批量处理过期批次'}
              </Button>
            </Card.Content>
          </Card>
        )}

        {/* FIFO Recommendation - P1-006 */}
        {fifoRecommended && (
          <Card style={styles.fifoCard}>
            <Card.Content>
              <View style={styles.fifoHeader}>
                <List.Icon icon="arrow-right-bold-circle" color="#2196F3" />
                <Text style={styles.fifoTitle}>FIFO推荐</Text>
                <Chip mode="flat" style={styles.fifoChip}>优先使用</Chip>
              </View>
              <Text style={styles.fifoDescription}>
                根据先进先出(FIFO)原则，建议优先使用以下批次：
              </Text>
              <View style={styles.fifoRecommendation}>
                <Text style={styles.fifoBatchNumber}>{fifoRecommended.batchNumber}</Text>
                <Text style={styles.fifoMaterialType}>{fifoRecommended.materialTypeId}</Text>
                <View style={styles.fifoDetails}>
                  <Text style={styles.fifoDetailText}>
                    入库日期: {new Date(fifoRecommended.inboundDate).toLocaleDateString()}
                  </Text>
                  <Text style={styles.fifoDetailText}>
                    剩余: {fifoRecommended.remainingQuantity.toFixed(2)} kg
                  </Text>
                </View>
              </View>
            </Card.Content>
          </Card>
        )}

        {/* Batches List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : filteredBatches.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="package-variant" color="#999" />
              <Text style={styles.emptyText}>暂无批次数据</Text>
              <Text style={styles.emptyHint}>
                {filterTab === 'expiring' ? '✅ 没有即将过期的批次' :
                 filterTab === 'expired' ? '✅ 没有已过期的批次' :
                 filterTab === 'low_stock' ? '✅ 没有低库存批次' :
                 '暂无原材料批次'}
              </Text>
            </Card.Content>
          </Card>
        ) : (
          filteredBatches.map((batch) => {
            const daysUntilExpiry = calculateDaysUntilExpiry(batch.expiryDate);
            const expiryWarning = getExpiryWarning(daysUntilExpiry);
            const usagePercent = calculateUsagePercentage(batch);
            const remainingPercent = calculateRemainingPercentage(batch);

            const isFIFORecommended = fifoRecommended?.id === batch.id;

            return (
              <Card
                key={batch.id}
                style={[
                  styles.batchCard,
                  isFIFORecommended && styles.fifoRecommendedCard
                ]}
              >
                <Card.Content>
                  {/* Header */}
                  <View style={styles.batchHeader}>
                    <View style={styles.batchTitleRow}>
                      <View style={styles.batchTitleLeft}>
                        <View style={styles.batchNumberRow}>
                          <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
                          {isFIFORecommended && (
                            <Chip
                              mode="flat"
                              compact
                              icon="arrow-right-bold"
                              style={styles.fifoRecommendedBadge}
                              textStyle={styles.fifoRecommendedBadgeText}
                            >
                              建议优先
                            </Chip>
                          )}
                        </View>
                        <Text style={styles.materialType}>
                          {batch.materialName || batch.materialTypeId || '未知原料'}
                        </Text>
                        {batch.supplierName && (
                          <Text style={styles.supplierText}>
                            供应商: {batch.supplierName}
                          </Text>
                        )}
                      </View>
                      <View style={styles.chips}>
                        {/* 存储类型 Badge (来自原料类型) */}
                        {batch.storageType && getStorageTypeText(batch.storageType) && (
                          <View style={[
                            styles.storageTypeBadge,
                            { backgroundColor: `${getStorageTypeColor(batch.storageType)}20` }
                          ]}>
                            <Text style={[
                              styles.storageTypeBadgeText,
                              { color: getStorageTypeColor(batch.storageType) }
                            ]}>
                              {getStorageTypeText(batch.storageType)}
                            </Text>
                          </View>
                        )}
                        {/* 可用状态 Badge */}
                        <View style={[
                          styles.statusBadge,
                          { backgroundColor: `${getStatusColor(batch.status)}20` }
                        ]}>
                          <Text style={[
                            styles.statusBadgeText,
                            { color: getStatusColor(batch.status) }
                          ]}>
                            {getStatusText(batch.status)}
                          </Text>
                        </View>
                        {batch.qualityGrade && (
                          <View style={[
                            styles.qualityBadge,
                            { backgroundColor: `${getQualityColor(batch.qualityGrade)}20` }
                          ]}>
                            <Text style={[
                              styles.qualityBadgeText,
                              { color: getQualityColor(batch.qualityGrade) }
                            ]}>
                              {batch.qualityGrade}级
                            </Text>
                          </View>
                        )}
                      </View>
                    </View>

                    {/* P3-库存: Edit/Delete Actions (简化版) */}
                    {canManageInventory && <View style={styles.actionButtons}>
                      <IconButton
                        icon="pencil"
                        size={20}
                        onPress={() => navigation.dispatch(CommonActions.navigate('EditBatch', { batchId: batch.id }))}
                        style={styles.actionIcon}
                      />
                      <IconButton
                        icon="delete"
                        size={20}
                        onPress={() => openDeleteDialog(batch)}
                        style={styles.actionIcon}
                        iconColor="#F44336"
                      />
                    </View>}
                  </View>

                  {/* Quantity Info */}
                  <View style={styles.quantitySection}>
                    <View style={styles.quantityRow}>
                      <Text style={styles.quantityLabel}>剩余/总量：</Text>
                      <Text style={styles.quantityValue}>
                        {(batch.remainingQuantity ?? 0).toFixed(2)} / {(batch.inboundQuantity ?? 0).toFixed(2)} kg
                      </Text>
                    </View>
                    <ProgressBar
                      progress={remainingPercent / 100}
                      color={remainingPercent > 20 ? '#4CAF50' : '#F44336'}
                      style={styles.progressBar}
                    />
                    <View style={styles.quantityDetails}>
                      <Text style={styles.quantityDetailText}>
                        已用: {(batch.usedQuantity ?? 0).toFixed(2)} kg ({usagePercent.toFixed(1)}%)
                      </Text>
                      {(batch.reservedQuantity ?? 0) > 0 && (
                        <Text style={styles.quantityDetailText}>
                          预留: {(batch.reservedQuantity ?? 0).toFixed(2)} kg
                        </Text>
                      )}
                    </View>
                  </View>

                  {/* Info */}
                  <View style={styles.batchInfo}>
                    <View style={styles.infoRow}>
                      <List.Icon icon="calendar-import" style={styles.infoIcon} />
                      <Text style={styles.infoText}>
                        入库: {new Date(batch.inboundDate).toLocaleDateString()}
                      </Text>
                    </View>
                    {batch.expiryDate && (
                      <View style={styles.infoRow}>
                        <List.Icon icon="calendar-alert" style={styles.infoIcon} />
                        <Text style={styles.infoText}>
                          到期: {new Date(batch.expiryDate).toLocaleDateString()}
                        </Text>
                        {expiryWarning && (
                          <View style={[
                            styles.expiryBadge,
                            { backgroundColor: `${expiryWarning.color}20` }
                          ]}>
                            <Text style={[
                              styles.expiryBadgeText,
                              { color: expiryWarning.color }
                            ]}>
                              {expiryWarning.text}
                            </Text>
                          </View>
                        )}
                      </View>
                    )}
                    {batch.storageLocation && (
                      <View style={styles.infoRow}>
                        <List.Icon icon="map-marker" style={styles.infoIcon} />
                        <Text style={styles.infoText}>{batch.storageLocation}</Text>
                      </View>
                    )}
                    <View style={styles.infoRow}>
                      <List.Icon icon="currency-cny" style={styles.infoIcon} />
                      <Text style={styles.infoText}>
                        单价: ¥{(batch.unitPrice || 0).toFixed(2)}/kg |
                        总价: ¥{(batch.totalCost || 0).toFixed(2)}
                      </Text>
                    </View>
                  </View>

                  {/* Fresh to Frozen Conversion - P1-006 */}
                  {canManageInventory && expiryWarning && (expiryWarning.level === 'critical' || expiryWarning.level === 'urgent') && (
                    <View style={styles.conversionSection}>
                      <Button
                        mode="outlined"
                        icon="snowflake"
                        onPress={() => {
                          Alert.alert(
                            '转为冻品',
                            `确定将批次 ${batch.batchNumber} 转为冻品吗？\n这将延长保质期并更新库存状态。`,
                            [
                              { text: '取消', style: 'cancel' },
                              {
                                text: '确认转换',
                                onPress: async () => {
                                  await handleConvertToFrozen(batch);
                                }
                              }
                            ]
                          );
                        }}
                        style={styles.conversionButton}
                        buttonColor="#E3F2FD"
                        textColor="#1976D2"
                      >
                        转为冻品
                      </Button>
                    </View>
                  )}

                  {/* Undo Frozen - P1-007 */}
                  {canManageInventory && batch.status === 'frozen' && (
                    <View style={styles.conversionSection}>
                      <Button
                        mode="outlined"
                        icon="undo"
                        onPress={() => {
                          setSelectedBatch(batch);
                          setUndoReason('');
                          setShowUndoDialog(true);
                        }}
                        style={styles.conversionButton}
                        buttonColor="#FFF3E0"
                        textColor="#F57C00"
                      >
                        撤销转冻品 (10分钟内)
                      </Button>
                    </View>
                  )}
                </Card.Content>
              </Card>
            );
          })
        )}

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* P3-库存: Delete/BatchOps Dialogs (创建和编辑跳转到专用页面) */}
      <Portal>
        {/* Delete Confirmation Dialog */}
        <Dialog visible={showDeleteDialog} onDismiss={() => setShowDeleteDialog(false)}>
          <Dialog.Title>确认删除</Dialog.Title>
          <Dialog.Content>
            <Text variant="bodyMedium">
              确定要删除批次 <Text style={{ fontWeight: 'bold' }}>{selectedBatch?.batchNumber}</Text> 吗？
            </Text>
            <Text variant="bodySmall" style={{ marginTop: 8, color: '#666' }}>
              此操作不可撤销。
            </Text>
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setShowDeleteDialog(false)}>取消</Button>
            <Button
              onPress={handleDeleteBatch}
              loading={loading}
              disabled={loading}
              textColor="#F44336"
            >
              删除
            </Button>
          </Dialog.Actions>
        </Dialog>

        {/* Undo Frozen Dialog - P1-007 */}
        <Dialog visible={showUndoDialog} onDismiss={() => setShowUndoDialog(false)}>
          <Dialog.Title>撤销转冻品</Dialog.Title>
          <Dialog.Content>
            <Text variant="bodyMedium" style={{ marginBottom: 16 }}>
              批次号: <Text style={{ fontWeight: 'bold' }}>{selectedBatch?.batchNumber}</Text>
            </Text>
            <Text variant="bodySmall" style={{ marginBottom: 16, color: '#FF6F00' }}>
              ⚠️ 注意：只能撤销10分钟内的转冻品操作
            </Text>
            <TextInput
              label="撤销原因 *"
              value={undoReason}
              onChangeText={setUndoReason}
              mode="outlined"
              style={styles.dialogInput}
              placeholder="例如：误操作、批次选错、需要继续加工等"
              multiline
              numberOfLines={3}
            />
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setShowUndoDialog(false)}>取消</Button>
            <Button
              onPress={() => selectedBatch && handleUndoFrozen(selectedBatch)}
              loading={undoingFrozen}
              disabled={!undoReason || undoReason.length < 2 || undoingFrozen}
            >
              确认撤销
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>

      {/* 原本这里有个「AI智能入库」FAB, 指向 MaterialReceiptAI —— 那个屏提交必 409
          (普通批次页面已关闭无来源入库与续入)。入库现在只从仓储角色的收货流程进
          (WHUnorderedInboundReceive 等), 而那在另一棵导航树里, 工厂侧无从跳转,
          所以这里不留一个点了必失败的按钮。 */}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  content: {
    flex: 1,
  },
  searchbar: {
    margin: 16,
    marginBottom: 8,
  },
  filterCard: {
    margin: 16,
    marginBottom: 8,
  },
  statsCard: {
    margin: 16,
    marginBottom: 8,
  },
  readOnlyNotice: {
    marginHorizontal: 16,
    marginBottom: 8,
    backgroundColor: '#E3F2FD',
  },
  readOnlyNoticeTitle: {
    color: '#0D47A1',
    fontWeight: '700',
    marginBottom: 4,
  },
  readOnlyNoticeText: {
    color: '#1565C0',
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  actionCard: {
    margin: 16,
    marginBottom: 8,
    backgroundColor: '#FFF3E0',
  },
  actionDescription: {
    color: '#666',
    marginBottom: 12,
  },
  actionButton: {
    marginTop: 4,
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#2196F3',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  loadingContainer: {
    padding: 40,
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  emptyCard: {
    margin: 16,
  },
  emptyContent: {
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 16,
    color: '#999',
    marginTop: 8,
  },
  emptyHint: {
    fontSize: 14,
    color: '#bbb',
    marginTop: 8,
  },
  batchCard: {
    margin: 16,
    marginBottom: 8,
  },
  batchHeader: {
    marginBottom: 12,
  },
  batchTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  batchTitleLeft: {
    flex: 1,
    maxWidth: '60%',
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  materialType: {
    fontSize: 13,
    color: '#666',
    marginTop: 2,
  },
  supplierText: {
    fontSize: 12,
    color: '#999999',
    marginTop: 2,
  },
  chips: {
    flexDirection: 'row',
    gap: 4,
    flexWrap: 'wrap',
    flexShrink: 0,
  },
  storageTypeBadge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 10,
    marginRight: 6,
  },
  storageTypeBadgeText: {
    fontSize: 11,
    fontWeight: '500',
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    alignSelf: 'flex-start',
  },
  statusBadgeText: {
    fontSize: 12,
    fontWeight: '500',
  },
  qualityBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    alignSelf: 'flex-start',
  },
  qualityBadgeText: {
    fontSize: 12,
    fontWeight: '500',
  },
  quantitySection: {
    marginBottom: 12,
    padding: 12,
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
  },
  quantityRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  quantityLabel: {
    fontSize: 13,
    color: '#666',
  },
  quantityValue: {
    fontSize: 13,
    fontWeight: 'bold',
    color: '#333',
  },
  progressBar: {
    height: 8,
    borderRadius: 4,
    marginBottom: 8,
  },
  quantityDetails: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  quantityDetailText: {
    fontSize: 11,
    color: '#999',
  },
  batchInfo: {
    marginTop: 4,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  infoIcon: {
    margin: 0,
    marginRight: 4,
    width: 28,
  },
  infoText: {
    fontSize: 13,
    color: '#666',
    flex: 1,
    flexShrink: 1,
  },
  expiryBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
    marginLeft: 8,
    flexShrink: 0,
  },
  expiryBadgeText: {
    fontSize: 11,
    fontWeight: '500',
  },
  conversionSection: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
  },
  conversionButton: {
    borderColor: '#1976D2',
    borderWidth: 1,
  },
  bottomPadding: {
    height: 80,
  },
  // P3-库存: CRUD操作样式
  actionButtons: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 8,
  },
  actionIcon: {
    margin: 0,
  },
  dialogInput: {
    marginBottom: 12,
  },
  fab: {
    position: 'absolute',
    right: 16,
    bottom: 16,
    backgroundColor: '#2196F3',
  },
  batchNumberRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  fifoCard: {
    margin: 16,
    marginBottom: 8,
    backgroundColor: '#E3F2FD',
  },
  fifoHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  fifoTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1976D2',
    flex: 1,
    marginLeft: 8,
  },
  fifoChip: {
    backgroundColor: '#2196F3',
  },
  fifoDescription: {
    fontSize: 14,
    color: '#666',
    marginBottom: 12,
  },
  fifoRecommendation: {
    backgroundColor: '#FFF',
    padding: 12,
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#2196F3',
  },
  fifoBatchNumber: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#212121',
    marginBottom: 4,
  },
  fifoMaterialType: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
  },
  fifoDetails: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  fifoDetailText: {
    fontSize: 13,
    color: '#999',
  },
  fifoRecommendedCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#2196F3',
  },
  fifoRecommendedBadge: {
    backgroundColor: '#2196F3',
  },
  fifoRecommendedBadgeText: {
    color: '#FFF',
    fontSize: 11,
  },
});
