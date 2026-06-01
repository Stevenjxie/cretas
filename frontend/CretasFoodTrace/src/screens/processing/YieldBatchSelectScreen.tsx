import React, { useCallback, useEffect, useState } from 'react';
import { View, Text, ScrollView, StyleSheet, Alert, ActivityIndicator, TouchableOpacity } from 'react-native';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { NeoCard } from '../../components/ui/NeoCard';
import EmptyStateCard from '../../components/common/EmptyStateCard';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import { processingApiClient, ProcessingBatch } from '../../services/api/processingApiClient';
import { handleError } from '../../utils/errorHandler';

type NavT = NativeStackNavigationProp<Record<string, object | undefined>>;

const YieldBatchSelectScreen: React.FC = () => {
  const navigation = useNavigation<NavT>();
  const [loading, setLoading] = useState(true);
  const [batches, setBatches] = useState<ProcessingBatch[]>([]);
  const [scannerVisible, setScannerVisible] = useState(false);

  const loadBatches = useCallback(async () => {
    setLoading(true);
    try {
      const res = await processingApiClient.getBatches({ status: 'IN_PROGRESS', page: 1, size: 50 });
      if (res.success && res.data) {
        setBatches(res.data.content ?? []);
      } else {
        setBatches([]);
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const msg = error instanceof Error ? error.message : '加载批次失败';
      Alert.alert('加载失败', msg);
      setBatches([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadBatches();
    }, [loadBatches]),
  );

  const goReport = useCallback(
    (batch: ProcessingBatch) => {
      navigation.navigate('YieldStepReport', { batchId: batch.id, batchNumber: batch.batchNumber });
    },
    [navigation],
  );

  const handleScan = useCallback(
    async (code: string) => {
      setScannerVisible(false);
      try {
        const res = await processingApiClient.scanBatchByCode(code);
        if (res.success && res.data) {
          goReport(res.data);
        } else {
          Alert.alert('未找到批次', res.message || '请检查条码是否正确');
        }
      } catch (error) {
        handleError(error, { showAlert: false, logError: true });
        const msg = error instanceof Error ? error.message : '扫码查询失败';
        Alert.alert('查询失败', msg);
      }
    },
    [goReport],
  );

  return (
    <ScreenWrapper>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>选择报工批次</Text>

        <TouchableOpacity style={styles.scanBtn} onPress={() => setScannerVisible(true)} testID="yield-scan-batch-btn">
          <Text style={styles.scanBtnText}>扫码选批次</Text>
        </TouchableOpacity>

        {loading ? (
          <View style={styles.centered}>
            <ActivityIndicator size="large" color="#E8732E" />
            <Text style={styles.loadingText}>加载在产批次...</Text>
          </View>
        ) : batches.length === 0 ? (
          <EmptyStateCard
            icon="clipboard-list-outline"
            title="暂无在产批次"
            description="只有'生产中'的批次可逐道报工, 请先开始生产或联系主管"
            actionLabel="刷新"
            onAction={loadBatches}
          />
        ) : (
          batches.map((b) => (
            <NeoCard key={b.id} variant="elevated" onPress={() => goReport(b)} style={styles.batchCard}>
              <Text style={styles.batchNo}>{b.batchNumber}</Text>
              <Text style={styles.batchProduct}>{b.productType || '—'}</Text>
              <Text style={styles.batchMeta}>
                计划 {b.targetQuantity ?? '—'}  ·  状态 {b.status}
              </Text>
            </NeoCard>
          ))
        )}
      </ScrollView>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleScan}
      />
    </ScreenWrapper>
  );
};

const styles = StyleSheet.create({
  content: { padding: 16 },
  title: { fontSize: 22, fontWeight: '700', color: '#1A1A1A', marginBottom: 16 },
  scanBtn: { backgroundColor: '#E8732E', borderRadius: 10, paddingVertical: 16, alignItems: 'center', marginBottom: 16 },
  scanBtnText: { color: '#FFFFFF', fontSize: 17, fontWeight: '600' },
  centered: { alignItems: 'center', paddingVertical: 48 },
  loadingText: { marginTop: 12, fontSize: 15, color: '#909399' },
  batchCard: { marginBottom: 12 },
  batchNo: { fontSize: 17, fontWeight: '600', color: '#1A1A1A' },
  batchProduct: { fontSize: 15, color: '#606266', marginTop: 4 },
  batchMeta: { fontSize: 13, color: '#909399', marginTop: 6 },
});

export default YieldBatchSelectScreen;
