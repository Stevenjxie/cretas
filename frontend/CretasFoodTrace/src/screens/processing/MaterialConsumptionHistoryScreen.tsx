import React, { useState, useCallback, useMemo } from 'react';
import {
  View,
  StyleSheet,
  FlatList,
  RefreshControl,
  TouchableOpacity,
} from 'react-native';
import {
  Text,
  Appbar,
  Chip,
  Surface,
  Searchbar,
  Menu,
  ActivityIndicator,
  IconButton,
  Button,
} from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { ProcessingScreenProps } from '../../types/navigation';
import {
  materialConsumptionApiClient,
  type MaterialConsumption,
} from '../../services/api/materialConsumptionApiClient';
import { useAuthStore } from '../../store/authStore';
import { handleError } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { normalizedSearchValue } from '../../utils/searchValue';

const consumptionLogger = logger.createContextLogger('MaterialConsumptionHistory');

type MaterialConsumptionHistoryScreenProps = ProcessingScreenProps<'MaterialConsumptionHistory'>;

/**
 * 原材料消耗记录列表页面
 *
 * 功能:
 * - 展示原材料消耗记录列表
 * - 搜索和筛选
 * - 按时间范围筛选
 * - 导航到消耗详情
 */
export default function MaterialConsumptionHistoryScreen() {
  const navigation = useNavigation<MaterialConsumptionHistoryScreenProps['navigation']>();
  const route = useRoute<MaterialConsumptionHistoryScreenProps['route']>();
  const { productionBatchId } = route.params || {};
  const { t } = useTranslation('processing');

  // Get user context
  const { user } = useAuthStore();
  // Get factoryId from user object
  const factoryId = user?.factoryId || user?.factoryUser?.factoryId;

  // Data state
  const [consumptions, setConsumptions] = useState<MaterialConsumption[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<{ message: string; canRetry: boolean } | null>(null);

  // Filter state
  const [searchQuery, setSearchQuery] = useState('');
  const [filterMenuVisible, setFilterMenuVisible] = useState(false);
  const [timeFilter, setTimeFilter] = useState<'all' | 'today' | 'week' | 'month'>('all');

  // Stats state
  const [stats, setStats] = useState<{ totalQuantity: number; totalCost: number; consumptionCount: number } | null>(null);

  useFocusEffect(
    useCallback(() => {
      fetchConsumptions();
      fetchStats();
    }, [productionBatchId, timeFilter])
  );

  const getDateRange = () => {
    const now = new Date();
    let startDate: string | undefined;
    let endDate: string | undefined;

    switch (timeFilter) {
      case 'today':
        startDate = now.toISOString().split('T')[0];
        endDate = startDate;
        break;
      case 'week':
        const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
        startDate = weekAgo.toISOString().split('T')[0];
        endDate = now.toISOString().split('T')[0];
        break;
      case 'month':
        const monthAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
        startDate = monthAgo.toISOString().split('T')[0];
        endDate = now.toISOString().split('T')[0];
        break;
      default:
        break;
    }

    return { startDate, endDate };
  };

  const fetchConsumptions = async () => {
    setLoading(true);
    setError(null);

    try {
      consumptionLogger.debug('获取消耗记录列表', { factoryId, productionBatchId, timeFilter });

      const { startDate, endDate } = getDateRange();
      const response = await materialConsumptionApiClient.getConsumptions({
        factoryId,
        productionBatchId,
        startDate,
        endDate,
      });

      if (response.success && response.data) {
        // Backend returns paginated response with content array
        const records = Array.isArray(response.data)
          ? response.data
          : (response.data && typeof response.data === 'object' && 'content' in response.data) ? (response.data as { content: MaterialConsumption[] }).content : [];
        setConsumptions(records);
        consumptionLogger.info('消耗记录列表加载成功', {
          factoryId,
          recordCount: records.length,
        });
      } else {
        setConsumptions([]);
      }
    } catch (error) {
      consumptionLogger.error('获取消耗记录列表失败', error as Error, {
        factoryId,
        productionBatchId,
      });

      handleError(error, {
        showAlert: false,
        logError: true,
      });

      setError({
        message: error instanceof Error ? error.message : '无法加载消耗记录，请稍后重试',
        canRetry: true,
      });

      setConsumptions([]);
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async () => {
    try {
      const { startDate, endDate } = getDateRange();
      const response = await materialConsumptionApiClient.getConsumptionStats({
        factoryId,
        productionBatchId,
        startDate,
        endDate,
      });

      if (response.success && response.data) {
        setStats(response.data);
      }
    } catch (error) {
      consumptionLogger.error('获取消耗统计失败', error as Error);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    await Promise.all([fetchConsumptions(), fetchStats()]);
    setRefreshing(false);
  };

  const formatCurrency = (amount: number) => {
    return `¥${amount.toFixed(2)}`;
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  // Filter data based on search query
  const filteredConsumptions = useMemo(() => consumptions.filter((item) => {
    const searchLower = searchQuery.toLowerCase();
    const batchIdMatch = item.batchId?.toLowerCase().includes(searchLower);
    const productionBatchMatch = normalizedSearchValue(item.productionBatchId).includes(searchLower);
    const materialTypeMatch = item.materialTypeName?.toLowerCase().includes(searchLower);

    return batchIdMatch || productionBatchMatch || materialTypeMatch;
  }), [consumptions, searchQuery]);

  // Render stats summary
  const renderStats = () => {
    if (!stats) return null;

    return (
      <Surface style={styles.statsCard} elevation={1}>
        <View style={styles.statsRow}>
          <View style={styles.statItem}>
            <Text style={styles.statValue}>{stats.consumptionCount}</Text>
            <Text style={styles.statLabel}>{t('consumptionHistory.stats.count')}</Text>
          </View>
          <View style={styles.statDivider} />
          <View style={styles.statItem}>
            <Text style={styles.statValue}>{stats.totalQuantity.toFixed(2)}</Text>
            <Text style={styles.statLabel}>{t('consumptionHistory.stats.totalQuantity')}</Text>
          </View>
          <View style={styles.statDivider} />
          <View style={styles.statItem}>
            <Text style={[styles.statValue, styles.costValue]}>
              {formatCurrency(stats.totalCost)}
            </Text>
            <Text style={styles.statLabel}>{t('consumptionHistory.stats.totalCost')}</Text>
          </View>
        </View>
      </Surface>
    );
  };

  // Render item
  const renderItem = useCallback(({ item }: { item: MaterialConsumption }) => (
    <Surface style={styles.card} elevation={1}>
      <View style={styles.cardHeader}>
        <View style={styles.cardHeaderLeft}>
          <Text variant="titleMedium" style={styles.batchNumber}>
            {item.batchNumber ?? item.batchId}
          </Text>
          <Text variant="bodySmall" style={styles.materialType}>
            {item.materialTypeName ?? '原材料'}
          </Text>
        </View>
        <View style={styles.cardHeaderRight}>
          <Text style={styles.costAmount}>{formatCurrency(item.totalCost)}</Text>
        </View>
      </View>

      <View style={styles.cardBody}>
        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>{t('consumptionHistory.card.quantity')}</Text>
          <Text style={styles.infoValue}>{item.quantity} kg</Text>
        </View>

        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>{t('consumptionHistory.card.unitPrice')}</Text>
          <Text style={styles.infoValue}>{formatCurrency(item.unitPrice)}/kg</Text>
        </View>

        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>{t('consumptionHistory.card.productionBatch')}</Text>
          <Text style={styles.infoValue}>{item.productionBatchId ?? '-'}</Text>
        </View>

        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>{t('consumptionHistory.card.time')}</Text>
          <Text style={styles.infoValue}>{formatDate(item.consumptionTime)}</Text>
        </View>

        {item.recorderName && (
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('consumptionHistory.card.operator')}</Text>
            <Text style={styles.infoValue}>{item.recorderName}</Text>
          </View>
        )}

        {item.notes && (
          <View style={styles.notesRow}>
            <Text style={styles.infoLabel}>{t('consumptionHistory.card.notes')}</Text>
            <Text style={styles.notesValue} numberOfLines={2}>
              {item.notes}
            </Text>
          </View>
        )}
      </View>
    </Surface>
  ), [formatCurrency, formatDate, t]);

  // Empty state
  const renderEmpty = () => (
    <View style={styles.emptyContainer}>
      {error ? (
        <>
          <IconButton icon="alert-circle-outline" size={48} iconColor="#F44336" />
          <Text variant="bodyLarge" style={styles.errorText}>
            {error.message}
          </Text>
          {error.canRetry && (
            <Button
              mode="outlined"
              icon="refresh"
              onPress={fetchConsumptions}
              style={styles.retryButton}
            >
              {t('consumptionHistory.empty.retry')}
            </Button>
          )}
        </>
      ) : (
        <>
          <IconButton icon="package-variant" size={48} iconColor="#9E9E9E" />
          <Text variant="bodyLarge" style={styles.emptyText}>
            {t('consumptionHistory.empty.title')}
          </Text>
          <Text variant="bodySmall" style={styles.emptyHint}>
            {t('consumptionHistory.empty.hint')}
          </Text>
        </>
      )}
    </View>
  );

  const getFilterLabel = () => {
    switch (timeFilter) {
      case 'today':
        return t('consumptionHistory.filter.today');
      case 'week':
        return t('consumptionHistory.filter.week');
      case 'month':
        return t('consumptionHistory.filter.month');
      default:
        return t('consumptionHistory.filter.all');
    }
  };

  return (
    <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('consumptionHistory.title')} />
        <Appbar.Action icon="refresh" onPress={handleRefresh} />
        <Menu
          visible={filterMenuVisible}
          onDismiss={() => setFilterMenuVisible(false)}
          anchor={
            <Appbar.Action
              icon="filter-variant"
              onPress={() => setFilterMenuVisible(true)}
            />
          }
        >
          <Menu.Item
            onPress={() => {
              setTimeFilter('all');
              setFilterMenuVisible(false);
            }}
            title={t('consumptionHistory.filter.all')}
            leadingIcon={timeFilter === 'all' ? 'check' : undefined}
          />
          <Menu.Item
            onPress={() => {
              setTimeFilter('today');
              setFilterMenuVisible(false);
            }}
            title={t('consumptionHistory.filter.today')}
            leadingIcon={timeFilter === 'today' ? 'check' : undefined}
          />
          <Menu.Item
            onPress={() => {
              setTimeFilter('week');
              setFilterMenuVisible(false);
            }}
            title={t('consumptionHistory.filter.week')}
            leadingIcon={timeFilter === 'week' ? 'check' : undefined}
          />
          <Menu.Item
            onPress={() => {
              setTimeFilter('month');
              setFilterMenuVisible(false);
            }}
            title={t('consumptionHistory.filter.month')}
            leadingIcon={timeFilter === 'month' ? 'check' : undefined}
          />
        </Menu>
      </Appbar.Header>

      <Searchbar
        placeholder={t('consumptionHistory.searchPlaceholder')}
        onChangeText={setSearchQuery}
        value={searchQuery}
        style={styles.searchbar}
      />

      {/* Filter indicator */}
      {timeFilter !== 'all' && (
        <View style={styles.filterIndicator}>
          <Chip
            icon="clock-outline"
            onClose={() => setTimeFilter('all')}
            style={styles.filterChip}
          >
            {getFilterLabel()}
          </Chip>
        </View>
      )}

      {loading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>{t('common.loading')}</Text>
        </View>
      ) : (
        <FlatList
          data={filteredConsumptions}
          renderItem={renderItem}
          keyExtractor={(item) => item.id.toString()}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
          }
          ListHeaderComponent={renderStats}
          ListEmptyComponent={renderEmpty}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  searchbar: {
    margin: 16,
    marginBottom: 8,
  },
  filterIndicator: {
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
  filterChip: {
    alignSelf: 'flex-start',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  listContent: {
    padding: 16,
    paddingTop: 8,
    paddingBottom: 100,
  },
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
    fontSize: 20,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  costValue: {
    color: '#E65100',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
  },
  statDivider: {
    width: 1,
    height: 32,
    backgroundColor: '#E0E0E0',
  },
  card: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
  },
  cardHeaderLeft: {
    flex: 1,
  },
  cardHeaderRight: {},
  batchNumber: {
    fontWeight: '600',
    color: '#212121',
  },
  materialType: {
    color: '#666',
    marginTop: 2,
  },
  costAmount: {
    fontSize: 18,
    fontWeight: '600',
    color: '#E65100',
  },
  cardBody: {
    gap: 8,
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  infoLabel: {
    fontSize: 13,
    color: '#666',
  },
  infoValue: {
    fontSize: 13,
    fontWeight: '500',
    color: '#212121',
  },
  notesRow: {
    gap: 4,
  },
  notesValue: {
    fontSize: 12,
    color: '#666',
    lineHeight: 18,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 64,
  },
  emptyText: {
    color: '#9E9E9E',
    marginBottom: 8,
  },
  emptyHint: {
    color: '#BDBDBD',
    textAlign: 'center',
  },
  errorText: {
    color: '#F44336',
    textAlign: 'center',
    marginTop: 12,
    marginBottom: 16,
  },
  retryButton: {
    borderColor: '#F44336',
    marginTop: 8,
  },
});
