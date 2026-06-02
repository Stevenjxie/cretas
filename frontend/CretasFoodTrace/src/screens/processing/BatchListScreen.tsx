import React, { useState, useCallback, useMemo } from 'react';
import { View, StyleSheet, FlatList, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Appbar, Searchbar, IconButton, SegmentedButtons } from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect, CommonActions } from '@react-navigation/native';
import { ProcessingScreenProps } from '../../types/navigation';
import { BatchStatusBadge, BatchStatus } from '../../components/processing';
import { processingApiClient as processingAPI, BatchResponse } from '../../services/api/processingApiClient';
import { handleError } from '../../utils/errorHandler';
import { displayProductName } from '../../utils/formatters';
import { NeoCard, NeoButton, ScreenWrapper, StatusBadge } from '../../components/ui';
import { theme } from '../../theme';
import { useTranslation } from 'react-i18next';

type BatchListScreenProps = ProcessingScreenProps<'BatchList'>;

interface ErrorState {
  message: string;
  canRetry: boolean;
}

// Supervisor类型定义：后端返回的supervisor可能是string或对象
interface SupervisorUser {
  fullName?: string;
  username?: string;
  id?: number;
}

type SupervisorData = string | SupervisorUser;

// 辅助函数：获取supervisor显示名称
const getSupervisorName = (supervisor: SupervisorData | undefined, t: (key: string) => string): string => {
  if (!supervisor) return t('batchList.labels.notAssigned');
  if (typeof supervisor === 'string') return supervisor;
  return supervisor.fullName || supervisor.username || t('batchList.labels.notAssigned');
};

export default function BatchListScreen() {
  const navigation = useNavigation<BatchListScreenProps['navigation']>();
  const route = useRoute<BatchListScreenProps['route']>();
  const { t } = useTranslation('processing');

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedStatus, setSelectedStatus] = useState<string>('all');
  const [batches, setBatches] = useState<BatchResponse[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorState | null>(null);

  useFocusEffect(
    React.useCallback(() => {
      fetchBatches();
    }, [selectedStatus])
  );

  const fetchBatches = async () => {
    try {
      setLoading(true);
      setError(null);

      const params: any = {};
      if (selectedStatus !== 'all') params.status = selectedStatus;
      if (searchQuery) params.search = searchQuery;

      const result = await processingAPI.getBatches(params);
      let batchList: BatchResponse[] = [];
      if (result.data?.content) batchList = result.data.content;
      else if (Array.isArray(result.data)) batchList = result.data;
      else if (Array.isArray(result)) batchList = result;

      setBatches(batchList);
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      setError({
        message: error instanceof Error ? error.message : t('batchList.messages.loadFailed'),
        canRetry: true,
      });
      setBatches([]);
    } finally {
      setLoading(false);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    await fetchBatches();
    setRefreshing(false);
  };

  const filteredBatches = useMemo(() => batches.filter(batch => {
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      const supervisorName = getSupervisorName(batch.supervisor as SupervisorData, t);
      return (
        batch.batchNumber?.toLowerCase().includes(query) ||
        batch.productType?.toLowerCase().includes(query) ||
        supervisorName.toLowerCase().includes(query)
      );
    }
    return true;
  }), [batches, searchQuery, t]);

  const renderBatchCard = useCallback(({ item }: { item: BatchResponse }) => (
    <TouchableOpacity
      onPress={() => {
        navigation.navigate('BatchDetail', { batchId: item.id.toString() });
      }}
      activeOpacity={0.7}
    >
      <NeoCard style={styles.batchCard} padding="m">
        <View style={styles.cardHeader}>
          <View>
            <Text variant="titleMedium" style={styles.batchNumber}>{item.batchNumber}</Text>
            <Text variant="bodySmall" style={styles.timestamp}>{new Date(item.createdAt).toLocaleDateString('zh-CN')}</Text>
          </View>
          <BatchStatusBadge status={item.status as BatchStatus} size="small" />
        </View>

        <View style={styles.cardBody}>
          <View style={styles.row}>
            <View style={styles.col}>
              <Text style={styles.label}>{t('batchList.labels.product')}</Text>
              <Text style={styles.value}>{displayProductName(item.productType, t('batchList.labels.pending'))}</Text>
            </View>
            <View style={styles.col}>
              <Text style={styles.label}>{t('batchList.labels.supervisor')}</Text>
              <Text style={styles.value}>
                {getSupervisorName(item.supervisor as SupervisorData, t)}
              </Text>
            </View>
          </View>

          <View style={styles.progressRow}>
             <View style={styles.col}>
                <Text style={styles.label}>{t('batchList.labels.targetQuantity')}</Text>
                <Text style={styles.value}>{item.targetQuantity} kg</Text>
             </View>
             <View style={styles.col}>
                <Text style={styles.label}>{t('batchList.labels.actualQuantity')}</Text>
                <Text style={[styles.value, item.actualQuantity ? styles.highlight : {}]}>
                    {item.actualQuantity || 0} kg
                </Text>
             </View>
          </View>
        </View>
      </NeoCard>
    </TouchableOpacity>
  ), [navigation, t]);

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('batchList.title')} titleStyle={{ fontWeight: '600' }} />
      </Appbar.Header>

      <View style={styles.searchContainer}>
        <Searchbar
          placeholder={t('batchList.searchPlaceholder')}
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
          onSubmitEditing={fetchBatches}
          elevation={0}
        />
      </View>

      <SegmentedButtons
        value={selectedStatus}
        onValueChange={setSelectedStatus}
        buttons={[
          { value: 'all', label: t('batchList.filter.all') },
          { value: 'in_progress', label: t('batchList.filter.inProgress') },
          { value: 'completed', label: t('batchList.filter.completed') },
        ]}
        style={styles.segmentedButtons}
        density="small"
      />

      <FlatList
        data={filteredBatches}
        renderItem={renderBatchCard}
        keyExtractor={item => item.id.toString()}
        contentContainerStyle={styles.listContent}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            {error ? (
              <>
                <IconButton icon="alert-circle-outline" size={48} iconColor={theme.colors.error} />
                <Text style={styles.errorText}>{error.message}</Text>
                {error.canRetry && (
                  <NeoButton variant="outline" onPress={fetchBatches} style={styles.retryButton}>{t('common.retry')}</NeoButton>
                )}
              </>
            ) : (
              <>
                <Text style={styles.emptyText}>
                  {searchQuery ? t('batchList.empty.noMatch') : loading ? t('batchList.empty.loading') : t('batchList.empty.noData')}
                </Text>
                {!loading && !searchQuery && (
                  <NeoButton
                    variant="primary"
                    onPress={() => navigation.dispatch(CommonActions.navigate('ProductionPlanManagement'))}
                    style={styles.emptyButton}
                  >
                    {t('batchList.empty.goToProductionPlan')}
                  </NeoButton>
                )}
              </>
            )}
          </View>
        }
      />
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  searchContainer: {
    padding: 16,
    backgroundColor: theme.colors.surface,
    paddingBottom: 8,
  },
  searchBar: {
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: theme.custom.borderRadius.m,
    height: 44,
  },
  searchInput: {
    minHeight: 0,
  },
  segmentedButtons: {
    margin: 16,
    marginTop: 0,
  },
  listContent: {
    padding: 16,
    paddingTop: 0,
  },
  batchCard: {
    marginBottom: 12,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.outlineVariant,
    paddingBottom: 12,
  },
  batchNumber: {
    fontWeight: '700',
    color: theme.colors.text,
    fontSize: 16,
  },
  timestamp: {
    color: theme.colors.textTertiary,
    marginTop: 2,
  },
  cardBody: {
    gap: 12,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  progressRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      marginTop: 4,
      paddingTop: 12,
      borderTopWidth: 1,
      borderTopColor: theme.colors.outlineVariant, // Dashed effect simulated by solid line for now
  },
  col: {
    flex: 1,
  },
  label: {
    color: theme.colors.textSecondary,
    fontSize: 12,
    marginBottom: 2,
  },
  value: {
    color: theme.colors.text,
    fontWeight: '500',
    fontSize: 14,
  },
  highlight: {
    color: theme.colors.primary,
    fontWeight: '700',
  },
  footer: {
      marginTop: 12,
      flexDirection: 'row',
      justifyContent: 'flex-end'
  },
  emptyContainer: {
    alignItems: 'center',
    padding: 48,
  },
  emptyText: {
    color: theme.colors.textSecondary,
    marginTop: 16,
  },
  errorText: {
    color: theme.colors.error,
    marginTop: 16,
    marginBottom: 16,
  },
  retryButton: {
    minWidth: 120,
  },
  emptyButton: {
    marginTop: 24,
  },
});
