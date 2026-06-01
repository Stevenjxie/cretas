import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useAuthStore } from '../../store/authStore';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { NeoCard } from '../../components/ui/NeoCard';
import { NeoButton } from '../../components/ui/NeoButton';
import EmptyStateCard from '../../components/ui/EmptyStateCard';
import { BarcodeScannerModal } from '../../components/common/BarcodeScannerModal';
import { yieldReportApi, ReportableBatch } from '../../services/api/yieldReportApi';

// Task 7 会把这个屏注册进 ProcessingStackParamList;此处用局部类型避免 useNavigation<any>
type YieldStackParamList = {
  YieldBatchSelect: undefined;
  YieldStepReport: { batchId: string; batchNumber: string };
};
type NavProp = NativeStackNavigationProp<YieldStackParamList, 'YieldBatchSelect'>;

export const YieldBatchSelectScreen: React.FC = () => {
  const navigation = useNavigation<NavProp>();
  const factoryId = useAuthStore((s) => s.user?.factoryId);

  const [batches, setBatches] = useState<ReportableBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scannerVisible, setScannerVisible] = useState(false);

  const loadBatches = useCallback(async () => {
    if (!factoryId) {
      setError('未获取到工厂信息,请重新登录');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const data = await yieldReportApi.getReportableBatches(factoryId);
      setBatches(data.filter((b) => b.status !== 'COMPLETED'));
    } catch (e) {
      const msg = e instanceof Error ? e.message : '加载批次失败';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [factoryId]);

  useEffect(() => {
    loadBatches();
  }, [loadBatches]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadBatches();
    setRefreshing(false);
  }, [loadBatches]);

  const goReport = useCallback(
    (batch: ReportableBatch) => {
      navigation.navigate('YieldStepReport', {
        batchId: batch.batchId,
        batchNumber: batch.batchNumber,
      });
    },
    [navigation]
  );

  const handleScan = useCallback(
    (data: string) => {
      setScannerVisible(false);
      const matched = batches.find(
        (b) => b.batchNumber === data || b.batchId === data
      );
      if (matched) {
        goReport(matched);
      } else {
        setError(`未找到批次号 ${data} 对应的可报工批次`);
      }
    },
    [batches, goReport]
  );

  const renderItem = useCallback(
    ({ item }: { item: ReportableBatch }) => (
      <TouchableOpacity activeOpacity={0.7} onPress={() => goReport(item)}>
        <NeoCard style={styles.batchCard}>
          <View style={styles.batchHeader}>
            <Text style={styles.batchNumber}>{item.batchNumber}</Text>
            <Text style={styles.batchStatus}>{item.status}</Text>
          </View>
          <Text style={styles.productName}>{item.productName}</Text>
          <View style={styles.progressRow}>
            <Text style={styles.progressText}>
              进度 {item.reportedSteps}/{item.totalSteps} 道工序
            </Text>
          </View>
        </NeoCard>
      </TouchableOpacity>
    ),
    [goReport]
  );

  if (loading && batches.length === 0) {
    return (
      <ScreenWrapper>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载可报工批次...</Text>
        </View>
      </ScreenWrapper>
    );
  }

  if (error && batches.length === 0) {
    return (
      <ScreenWrapper>
        <EmptyStateCard
          icon="alert-circle-outline"
          title="无法加载批次"
          description={error}
          actionLabel="重试"
          onAction={loadBatches}
        />
      </ScreenWrapper>
    );
  }

  if (batches.length === 0) {
    return (
      <ScreenWrapper>
        <EmptyStateCard
          icon="cube-outline"
          title="暂无可报工批次"
          description="当前没有待报工的生产批次,下拉可刷新"
          actionLabel="扫码选批次"
          onAction={() => setScannerVisible(true)}
        />
        <BarcodeScannerModal
          visible={scannerVisible}
          onClose={() => setScannerVisible(false)}
          onScan={handleScan}
        />
      </ScreenWrapper>
    );
  }

  return (
    <ScreenWrapper>
      <View style={styles.toolbar}>
        <Text style={styles.heading}>选择报工批次</Text>
        <NeoButton
          variant="secondary"
          size="sm"
          onPress={() => setScannerVisible(true)}
        >
          扫码
        </NeoButton>
      </View>
      <FlatList
        data={batches}
        keyExtractor={(item) => item.batchId}
        renderItem={renderItem}
        contentContainerStyle={styles.listContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
      />
      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleScan}
      />
    </ScreenWrapper>
  );
};

const styles = StyleSheet.create({
  center: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  toolbar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  heading: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1a1a1a',
  },
  listContent: {
    paddingHorizontal: 16,
    paddingBottom: 24,
  },
  batchCard: {
    marginBottom: 12,
    padding: 16,
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '700',
    color: '#1a1a1a',
  },
  batchStatus: {
    fontSize: 12,
    color: '#0a7',
    fontWeight: '600',
  },
  productName: {
    fontSize: 14,
    color: '#444',
    marginBottom: 8,
  },
  progressRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  progressText: {
    fontSize: 13,
    color: '#666',
  },
});

export default YieldBatchSelectScreen;
