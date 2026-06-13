import React, { useState, useCallback } from 'react';
import {
  View, Text, ScrollView, TouchableOpacity, TextInput, Alert, StyleSheet,
  ActivityIndicator, Platform,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WSBatchesStackParamList } from '../../types/navigation';
import { processingApiClient } from '../../services/api/processingApiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import { OfflineIndicator } from '../../components/common/OfflineIndicator';
import { useDraftReportStore } from '../../store/draftReportStore';
import { useFieldVisibilityStore } from '../../store/fieldVisibilityStore';
import { handleError } from '../../utils/errorHandler';
import { hasProductionCapability } from '../../utils/factoryType';
import { useAuthStore } from '../../store/authStore';

interface BatchInfo {
  id: number;
  batchNumber: string;
  productName: string;
  status: string;
  plannedQuantity: number;
  actualQuantity: number;
  equipmentName: string;
  supervisorName: string;
}

interface ReportForm {
  outputQuantity: string;
  goodQuantity: string;
  defectQuantity: string;
  inputQuantity: string;
  notes: string;
}

type NavProp = NativeStackNavigationProp<WSBatchesStackParamList, 'ScanReport'>;

const ScanReportScreen: React.FC = () => {
  const navigation = useNavigation<NavProp>();
  // 专跳逐道报工屏的导航句柄 (不污染现有 NavProp; YieldBatchSelect 不在 WSBatchesStackParamList)
  const yieldNav = useNavigation<NativeStackNavigationProp<Record<string, object | undefined>>>();
  const user = useAuthStore((s) => s.user);
  const factoryId = getCurrentFactoryId();
  const { addDraft } = useDraftReportStore();
  const { isFieldVisible } = useFieldVisibilityStore();

  const [scannerVisible, setScannerVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [batchInfo, setBatchInfo] = useState<BatchInfo | null>(null);
  const [form, setForm] = useState<ReportForm>({
    outputQuantity: '', goodQuantity: '', defectQuantity: '', inputQuantity: '', notes: '',
  });

  const handleScan = useCallback(async (code: string) => {
    setScannerVisible(false);
    setLoading(true);
    try {
      const response = await processingApiClient.scanBatchByCode(code);
      if (response.success && response.data) {
        const d = response.data;
        setBatchInfo({
          id: d.id,
          batchNumber: d.batchNumber,
          productName: d.productType,
          status: d.status,
          plannedQuantity: d.targetQuantity,
          actualQuantity: d.actualQuantity ?? 0,
          equipmentName: '',
          supervisorName: typeof d.supervisor === 'object' ? (d.supervisor?.fullName ?? '') : (d.supervisor ?? ''),
        });
      } else {
        Alert.alert('未找到批次', response.message || '请检查条码是否正确');
      }
    } catch (error: unknown) {
      handleError(error, { showAlert: false, logError: true });
      const msg = error instanceof Error ? error.message : '扫码查询失败';
      Alert.alert('查询失败', msg);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleSubmit = useCallback(async () => {
    if (!batchInfo) return;
    const output = parseInt(form.outputQuantity, 10);
    const good = parseInt(form.goodQuantity, 10);
    if (isNaN(output) || output <= 0) {
      Alert.alert('请输入有效的产出数量');
      return;
    }

    const inputQty = parseFloat(form.inputQuantity);
    const reportPayload: {
      actualQuantity: number;
      goodQuantity?: number;
      defectQuantity?: number;
      notes?: string;
      inputQuantity?: number;
    } = {
      actualQuantity: output,
      goodQuantity: isNaN(good) ? output : good,
      defectQuantity: parseInt(form.defectQuantity, 10) || 0,
      notes: form.notes,
    };
    if (!isNaN(inputQty) && inputQty > 0) {
      reportPayload.inputQuantity = inputQty;
    }

    setSubmitting(true);
    try {
      await processingApiClient.submitWorkReport(batchInfo.id, reportPayload);
      navigation.replace('ScanReportSuccess', {
        batchNumber: batchInfo.batchNumber,
        outputQuantity: output,
        goodQuantity: isNaN(good) ? output : good,
        defectQuantity: parseInt(form.defectQuantity, 10) || 0,
      });
    } catch (error: unknown) {
      handleError(error, { showAlert: false, logError: true });
      // Save as offline draft on network failure
      addDraft({
        batchId: batchInfo.id,
        batchNumber: batchInfo.batchNumber,
        productName: batchInfo.productName,
        outputQuantity: output,
        goodQuantity: isNaN(good) ? output : good,
        defectQuantity: parseInt(form.defectQuantity, 10) || 0,
        notes: form.notes,
        factoryId,
      });
      Alert.alert(
        '已保存为离线草稿',
        '网络恢复后将自动同步',
        [{ text: '确定' }]
      );
    } finally {
      setSubmitting(false);
    }
  }, [batchInfo, form, factoryId, navigation, addDraft]);

  // Yield rate calculation
  const outputQty = parseFloat(form.outputQuantity);
  const goodQty = parseFloat(form.goodQuantity);
  const showYieldRate = !isNaN(outputQty) && outputQty > 0 && !isNaN(goodQty) && goodQty > 0;
  const yieldRate = showYieldRate ? (goodQty / outputQty) * 100 : 0;
  const yieldRateColor = yieldRate < 85 ? '#F44336' : yieldRate < 95 ? '#FF9800' : '#4CAF50';

  return (
    <SafeAreaView style={styles.safe} edges={['top']} testID="scan-report-screen">
      <OfflineIndicator />
      <ScrollView style={styles.container} contentContainerStyle={styles.content}>
        <Text style={styles.title}>扫码报工</Text>

        {/* 逐道工序报工入口 (业态门控: 仅工厂/中央厨房有工序, 餐饮不显) */}
        {hasProductionCapability(user) && (
          <TouchableOpacity
            style={styles.yieldEntryButton}
            onPress={() => yieldNav.navigate('YieldBatchSelect')}
            testID="yield-entry-button"
          >
            <Text style={styles.yieldEntryText}>逐道工序报工 ▶</Text>
          </TouchableOpacity>
        )}

        {!batchInfo && !loading && (
          <View style={styles.scanPrompt}>
            <Text style={styles.scanIconText}>SCAN</Text>
            <Text style={styles.scanText}>扫描批次条码开始报工</Text>
            <TouchableOpacity style={styles.scanButton} onPress={() => setScannerVisible(true)} testID="scan-button">
              <Text style={styles.scanButtonText}>开始扫码</Text>
            </TouchableOpacity>
          </View>
        )}

        {loading && (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color="#4F46E5" />
            <Text style={styles.loadingText}>查询批次信息...</Text>
          </View>
        )}

        {batchInfo && (
          <>
            <View style={styles.batchCard}>
              <Text style={styles.cardTitle}>批次信息</Text>
              <View style={styles.infoRow}>
                <Text style={styles.label}>批次号</Text>
                <Text style={styles.value}>{batchInfo.batchNumber}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.label}>产品</Text>
                <Text style={styles.value}>{batchInfo.productName || '-'}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.label}>状态</Text>
                <Text style={styles.value}>{batchInfo.status}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.label}>计划产量</Text>
                <Text style={styles.value}>{batchInfo.plannedQuantity != null ? String(batchInfo.plannedQuantity) : '-'}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.label}>设备</Text>
                <Text style={styles.value}>{batchInfo.equipmentName || '-'}</Text>
              </View>
            </View>

            <View style={styles.formCard}>
              <Text style={styles.cardTitle}>报工数据</Text>
              <View style={styles.inputGroup}>
                <Text style={styles.inputLabel}>产出数量 *</Text>
                <TextInput style={styles.input} keyboardType="numeric" placeholder="输入产出数量"
                  testID="report-output-quantity"
                  value={form.outputQuantity} onChangeText={v => setForm(p => ({...p, outputQuantity: v}))} />
              </View>
              {isFieldVisible('PROCESSING_BATCH', 'inputQuantity') !== false && (
              <View style={styles.inputGroup}>
                <Text style={styles.inputLabel}>投入量 (kg)</Text>
                <TextInput style={styles.input} keyboardType="decimal-pad" placeholder="本工序投入原料量"
                  testID="report-input-quantity"
                  value={form.inputQuantity} onChangeText={v => setForm(p => ({...p, inputQuantity: v}))} />
              </View>
              )}
              {isFieldVisible('PROCESSING_BATCH', 'goodQuantity') && (
              <View style={styles.inputGroup}>
                <Text style={styles.inputLabel}>合格数量</Text>
                <TextInput style={styles.input} keyboardType="numeric" placeholder="默认等于产出数量"
                  testID="report-good-quantity"
                  value={form.goodQuantity} onChangeText={v => setForm(p => ({...p, goodQuantity: v}))} />
              </View>
              )}
              {isFieldVisible('PROCESSING_BATCH', 'defectQuantity') && (
              <View style={styles.inputGroup}>
                <Text style={styles.inputLabel}>缺陷数量</Text>
                <TextInput style={styles.input} keyboardType="numeric" placeholder="0"
                  value={form.defectQuantity} onChangeText={v => setForm(p => ({...p, defectQuantity: v}))} />
              </View>
              )}
              {/* Yield rate real-time display */}
              {showYieldRate && (
                <View style={styles.yieldRateContainer} testID="yield-rate-display">
                  <View style={[styles.yieldRateDot, { backgroundColor: yieldRateColor }]} />
                  <Text style={styles.yieldRateLabel}>良品率: </Text>
                  <Text style={[styles.yieldRateValue, { color: yieldRateColor }]} testID="yield-rate-value">
                    {yieldRate.toFixed(1)}%
                  </Text>
                </View>
              )}

              <View style={styles.inputGroup}>
                <Text style={styles.inputLabel}>备注</Text>
                <TextInput style={[styles.input, styles.textArea]} multiline numberOfLines={3}
                  placeholder="备注信息（可选）"
                  value={form.notes} onChangeText={v => setForm(p => ({...p, notes: v}))} />
              </View>
            </View>

            <TouchableOpacity style={[styles.submitButton, submitting && styles.submitDisabled]}
              onPress={handleSubmit} disabled={submitting} testID="report-submit-btn">
              {submitting ? <ActivityIndicator color="#fff" /> :
                <Text style={styles.submitText}>提交报工</Text>}
            </TouchableOpacity>

            <TouchableOpacity style={styles.rescanButton} onPress={() => { setBatchInfo(null); setScannerVisible(true); }}>
              <Text style={styles.rescanText}>重新扫码</Text>
            </TouchableOpacity>
          </>
        )}
      </ScrollView>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleScan}
      />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#f5f5f5' },
  container: { flex: 1 },
  content: { padding: 16 },
  title: { fontSize: 24, fontWeight: '700', color: '#1a1a1a', marginBottom: 20 },
  scanPrompt: { alignItems: 'center', paddingVertical: 60, backgroundColor: '#fff', borderRadius: 12, marginBottom: 16 },
  scanIconText: { fontSize: 32, fontWeight: '700', color: '#4F46E5', marginBottom: 12 },
  scanText: { fontSize: 16, color: '#666', marginBottom: 20 },
  scanButton: { backgroundColor: '#4F46E5', paddingHorizontal: 32, paddingVertical: 14, borderRadius: 10 },
  scanButtonText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  loadingContainer: { alignItems: 'center', paddingVertical: 60 },
  loadingText: { marginTop: 12, fontSize: 15, color: '#666' },
  batchCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 16 },
  formCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 16 },
  cardTitle: { fontSize: 17, fontWeight: '600', color: '#1a1a1a', marginBottom: 12 },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 8, borderBottomWidth: 0.5, borderBottomColor: '#eee' },
  label: { fontSize: 14, color: '#666' },
  value: { fontSize: 14, color: '#1a1a1a', fontWeight: '500' },
  inputGroup: { marginBottom: 14 },
  inputLabel: { fontSize: 14, color: '#333', marginBottom: 6, fontWeight: '500' },
  input: { borderWidth: 1, borderColor: '#ddd', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15, backgroundColor: '#fafafa' },
  textArea: { height: 80, textAlignVertical: 'top' },
  yieldRateContainer: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#f8f9fa', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10, marginBottom: 14 },
  yieldRateDot: { width: 8, height: 8, borderRadius: 4, marginRight: 8 },
  yieldRateLabel: { fontSize: 14, color: '#333', fontWeight: '500' },
  yieldRateValue: { fontSize: 16, fontWeight: '700' },
  submitButton: { backgroundColor: '#4F46E5', paddingVertical: 15, borderRadius: 10, alignItems: 'center', marginBottom: 12 },
  submitDisabled: { opacity: 0.6 },
  submitText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  rescanButton: { paddingVertical: 12, alignItems: 'center' },
  rescanText: { color: '#4F46E5', fontSize: 16 },
  yieldEntryButton: {
    backgroundColor: '#E8732E', borderRadius: 10, paddingVertical: 16,
    alignItems: 'center', marginBottom: 16,
  },
  yieldEntryText: { color: '#FFFFFF', fontSize: 17, fontWeight: '600' },
});

export default ScanReportScreen;
