/**
 * 今日批次列表页面
 * 显示今日所有生产批次
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  FlatList,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { FAHomeStackParamList } from '../../../types/navigation';
import { processingApiClient, ProcessingBatch } from '../../../services/api/processingApiClient';

type NavigationProp = NativeStackNavigationProp<FAHomeStackParamList, 'TodayBatches'>;

export function TodayBatchesScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('home');

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [batches, setBatches] = useState<ProcessingBatch[]>([]);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      setError(null);
      const response = await processingApiClient.getBatches({
        page: 1,
        size: 50,
      });
      if (response.success && response.data) {
        // Filter for today's batches client-side
        const today = new Date().toISOString().split('T')[0];
        const todayBatches = (response.data.content || []).filter((batch) => {
          const batchDate = batch.createdAt?.split('T')[0];
          return batchDate === today;
        });
        setBatches(todayBatches);
      }
    } catch (err) {
      console.error('加载批次数据失败:', err);
      setError(t('todayBatches.loadFailed'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  const getStatusColor = (status: string): string => {
    const colors: Record<string, string> = {
      pending: '#ed8936',
      in_progress: '#667eea',
      completed: '#48bb78',
      cancelled: '#a0aec0',
    };
    return colors[status] || '#a0aec0';
  };

  const getStatusLabel = (status: string): string => {
    const labels: Record<string, string> = {
      pending: t('todayBatches.status.pending'),
      in_progress: t('todayBatches.status.inProgress'),
      completed: t('todayBatches.status.completed'),
      cancelled: t('todayBatches.status.cancelled'),
    };
    return labels[status] || status;
  };

  const renderBatchItem = ({ item }: { item: ProcessingBatch }) => (
    <TouchableOpacity
      testID={`today-batches-card-${item.id}`}
      style={styles.batchCard}
      onPress={() => navigation.navigate('BatchDetail', { batchId: String(item.id) })}
      activeOpacity={0.7}
    >
      <View style={styles.batchHeader}>
        <Text style={styles.batchNumber}>{item.batchNumber}</Text>
        <View style={[styles.statusBadge, { backgroundColor: getStatusColor(item.status) + '20' }]}>
          <Text style={[styles.statusText, { color: getStatusColor(item.status) }]}>
            {getStatusLabel(item.status)}
          </Text>
        </View>
      </View>
      <Text style={styles.productType}>{item.productType}</Text>
      <View style={styles.batchFooter}>
        <View style={styles.quantityInfo}>
          <Text style={styles.quantityLabel}>{t('todayBatches.planned')}:</Text>
          <Text style={styles.quantityValue}>{item.targetQuantity} kg</Text>
        </View>
        {item.actualQuantity !== undefined && (
          <View style={styles.quantityInfo}>
            <Text style={styles.quantityLabel}>{t('todayBatches.actual')}:</Text>
            <Text style={styles.quantityValue}>{item.actualQuantity} kg</Text>
          </View>
        )}
        <Icon source="chevron-right" size={20} color="#ccc" />
      </View>
    </TouchableOpacity>
  );

  if (loading) {
    return (
      <SafeAreaView testID="today-batches-screen" style={styles.container}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>{t('loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView testID="today-batches-screen" style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('todayBatches.title')}</Text>
        <View style={styles.headerRight}>
          <Text testID="today-batches-count" style={styles.batchCount}>{batches.length} {t('todayBatches.batchUnit')}</Text>
        </View>
      </View>

      {/* 错误提示 */}
      {error && (
        <View style={styles.errorBanner}>
          <Icon source="alert-circle" size={20} color="#e53e3e" />
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      <FlatList
        data={batches}
        keyExtractor={(item) => String(item.id)}
        renderItem={renderBatchItem}
        contentContainerStyle={styles.listContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Icon source="package-variant" size={48} color="#ccc" />
            <Text style={styles.emptyText}>{t('todayBatches.empty')}</Text>
          </View>
        }
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  backBtn: {
    width: 40,
    height: 40,
    justifyContent: 'center',
    alignItems: 'center',
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1a202c',
  },
  headerRight: {
    width: 60,
    alignItems: 'flex-end',
  },
  batchCount: {
    fontSize: 14,
    color: '#667eea',
    fontWeight: '500',
  },
  errorBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fed7d7',
    marginHorizontal: 16,
    marginTop: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 8,
  },
  errorText: {
    marginLeft: 8,
    fontSize: 14,
    color: '#c53030',
  },
  listContent: {
    padding: 16,
  },
  batchCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '500',
  },
  productType: {
    fontSize: 14,
    color: '#666',
    marginBottom: 12,
  },
  batchFooter: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  quantityInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 16,
  },
  quantityLabel: {
    fontSize: 13,
    color: '#999',
    marginRight: 4,
  },
  quantityValue: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyText: {
    marginTop: 12,
    fontSize: 14,
    color: '#999',
  },
});

export default TodayBatchesScreen;
