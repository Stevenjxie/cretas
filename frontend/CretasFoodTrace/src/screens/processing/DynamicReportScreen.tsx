import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  View,
  Text,
  ScrollView,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Alert,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useReportWorkflow } from '../../hooks/useReportWorkflow';
import { processingApiClient, type ProcessingBatch } from '../../services/api/processingApiClient';
import { workReportingApiClient } from '../../services/api/workReportingApiClient';
import { productTypeApiClient } from '../../services/api/productTypeApiClient';
import type { ProcessingStageOption, ProductType } from '../../services/api/productTypeApiClient';
import { useAuthStore } from '../../store/authStore';
import { useDraftReportStore } from '../../store/draftReportStore';
import { useLastReportStore } from '../../store/lastReportStore';
import { useAnomalyDetection } from '../../hooks/useAnomalyDetection';
import { useReportVoiceInput } from '../../hooks/useReportVoiceInput';
import SearchableDropdown from '../../components/report/SearchableDropdown';
import type { DropdownOption } from '../../components/report/SearchableDropdown';
import type { WSHomeStackParamList } from '../../types/navigation';
import type { WorkReportSubmitRequest, HourEntry } from '../../types/workReporting';
import { formatDate } from '../../utils/formatters';
import { isValidTime, normalizeTimeInput } from '../../utils/timeInput';

type DynamicReportRoute = RouteProp<WSHomeStackParamList, 'DynamicReport'>;

interface ActiveBatch {
  id: number;
  batchNumber: string;
  productName: string;
  status: string;
  isCheckedIn?: boolean;
}

export default function DynamicReportScreen() {
  const navigation = useNavigation();
  const route = useRoute<DynamicReportRoute>();
  const insets = useSafeAreaInsets();
  const { reportType } = route.params;
  const { user } = useAuthStore();
  const factoryId = user?.factoryId || user?.factoryUser?.factoryId;
  const workerId = user?.id;

  const { schema, loading, submitting, submitReport, isFieldVisible } = useReportWorkflow(reportType);

  // Batch context state
  const [activeBatches, setActiveBatches] = useState<ActiveBatch[]>([]);
  const [selectedBatch, setSelectedBatch] = useState<ActiveBatch | null>(null);
  const [batchLoading, setBatchLoading] = useState(false);

  // Form state
  const [processCategory, setProcessCategory] = useState('');
  const [productName, setProductName] = useState('');
  const [outputQuantity, setOutputQuantity] = useState('');
  const [goodQuantity, setGoodQuantity] = useState('');
  const [defectQuantity, setDefectQuantity] = useState('');
  const [operationVolume, setOperationVolume] = useState('');
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [notes, setNotes] = useState('');
  const [hourEntry, setHourEntry] = useState<HourEntry>({});

  // Feature 1: Dropdown options
  const [processOptions, setProcessOptions] = useState<DropdownOption[]>([]);
  const [productOptions, setProductOptions] = useState<DropdownOption[]>([]);
  const [optionsLoading, setOptionsLoading] = useState(false);

  // Feature 2: Auto-calculation state
  const [autoCalcField, setAutoCalcField] = useState<'good' | 'defect' | null>(null);
  const [calcWarning, setCalcWarning] = useState('');

  // Feature 3: Last report prefill
  const [showLastReportBanner, setShowLastReportBanner] = useState(false);

  // Feature 5: Anomaly detection
  const { warnings: anomalyWarnings, onProcessCategoryChange, checkAnomaly } = useAnomalyDetection(factoryId);

  // Feature 4: Voice input
  const { status: voiceStatus, startRecording, stopRecording, cancel: cancelVoice } = useReportVoiceInput();

  // Feature 7: Hours mode collapsed
  const [hoursExpanded, setHoursExpanded] = useState(false);

  const isProgress = reportType === 'PROGRESS';
  const title = isProgress ? '实时生产进度上报' : '生产工时上报';
  const today = new Date().toISOString().split('T')[0] ?? '';

  // Feature 1: Load dropdown options on mount
  useEffect(() => {
    loadDropdownOptions();
  }, []);

  const loadDropdownOptions = async () => {
    setOptionsLoading(true);
    try {
      if (isProgress) {
        const stages = await productTypeApiClient.getProcessingStages(factoryId ?? undefined);
        const opts: DropdownOption[] = stages.map((s: ProcessingStageOption) => ({
          label: s.label,
          value: s.value,
          description: s.description,
        }));
        setProcessOptions(opts);
      } else {
        const products = await productTypeApiClient.getActiveProductTypes(factoryId ?? undefined);
        const opts: DropdownOption[] = products.map((p: ProductType) => ({
          label: p.name,
          value: p.name,
          description: p.category || undefined,
        }));
        setProductOptions(opts);
      }
    } catch {
      // Options are optional, can fall back to manual input
    } finally {
      setOptionsLoading(false);
    }
  };

  // Feature 3: Prefill from last report on mount
  useEffect(() => {
    if (!factoryId || !workerId) return;
    const lastReport = useLastReportStore.getState().getLastReport(reportType, factoryId, workerId);
    if (lastReport) {
      // Only prefill if not already filled by batch selection
      if (!selectedBatch) {
        if (lastReport.processCategory) setProcessCategory(lastReport.processCategory);
        if (lastReport.productName) setProductName(lastReport.productName);
        if (lastReport.operationVolume) setOperationVolume(lastReport.operationVolume);
        if (lastReport.hourEntry) setHourEntry(lastReport.hourEntry);
        setShowLastReportBanner(true);
      }
    }
  }, [factoryId, workerId]);

  const clearLastReportPrefill = () => {
    setProcessCategory('');
    setProductName('');
    setOperationVolume('');
    setHourEntry({});
    setShowLastReportBanner(false);
  };

  // Load active batches on mount (Feature 6: enhanced with checkin data)
  useEffect(() => {
    loadActiveBatches();
  }, []);

  const loadActiveBatches = async () => {
    setBatchLoading(true);
    try {
      const res = await processingApiClient.getBatches({
        factoryId: factoryId ?? undefined,
        status: 'IN_PROGRESS',
        page: 1, size: 50,
      });
      if (res?.success) {
        const content: ProcessingBatch[] = res.data?.content || [];
        let batchList: ActiveBatch[] = content.map((b) => ({
          id: b.id,
          batchNumber: b.batchNumber,
          productName: b.productType || '',
          status: b.status,
        }));

        // Feature 6: Check user's today checkins to highlight/auto-select
        if (workerId) {
          try {
            const checkinRes = await workReportingApiClient.getTodayCheckins(workerId);
            if (checkinRes?.success && Array.isArray(checkinRes.data)) {
              const checkedInBatchIds = new Set(
                checkinRes.data
                  .filter((s: { status?: string }) => s.status === 'WORKING' || s.status === 'working')
                  .map((s: { batchId?: number }) => s.batchId)
              );
              batchList = batchList.map((b) => ({
                ...b,
                isCheckedIn: checkedInBatchIds.has(b.id),
              }));

              // Auto-select if user is checked into exactly 1 batch
              const checkedInBatches = batchList.filter((b) => b.isCheckedIn);
              if (checkedInBatches.length === 1 && checkedInBatches[0]) {
                handleSelectBatch(checkedInBatches[0]);
                setActiveBatches(batchList);
                setBatchLoading(false);
                return;
              }
            }
          } catch {
            // Checkin check is optional
          }
        }

        setActiveBatches(batchList);
        // Auto-select if only one batch
        const first = batchList[0];
        if (batchList.length === 1 && first) {
          handleSelectBatch(first);
        }
      }
    } catch (error) {
      console.warn('Failed to load batches for context:', error);
    } finally {
      setBatchLoading(false);
    }
  };

  const handleSelectBatch = async (batch: ActiveBatch) => {
    setSelectedBatch(batch);
    setShowLastReportBanner(false);
    if (batch.productName) {
      setProductName(batch.productName);
      setProcessCategory(batch.productName);
    }
    try {
      const checkinRes = await workReportingApiClient.getCheckinList(batch.id);
      if (checkinRes?.success && Array.isArray(checkinRes.data)) {
        const workingCount = checkinRes.data.filter(
          (c: { status?: string }) => c.status === 'working'
        ).length;
        if (workingCount > 0) {
          setHourEntry(prev => ({ ...prev, fullTimeWorkers: workingCount }));
        }
        const checkinTimes = checkinRes.data
          .map((c) => c.checkInTime)
          .filter((t): t is string => !!t)
          .sort();
        if (checkinTimes.length > 0) {
          const earliest = checkinTimes[0]!;
          const timeStr = earliest.includes('T') ? earliest.split('T')[1]?.substring(0, 5) : earliest.substring(11, 16);
          if (timeStr) setStartTime(timeStr);
        }
        const now = new Date();
        setEndTime(`${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`);
      }
    } catch {
      // Checkin data is optional
    }
  };

  const clearBatchSelection = () => {
    setSelectedBatch(null);
    setProductName('');
    setProcessCategory('');
    setStartTime('');
    setEndTime('');
    setHourEntry({});
  };

  // Feature 2: Auto-calculate good/defect quantities
  const handleOutputChange = (val: string) => {
    setOutputQuantity(val);
    autoCalcQuantities(val, goodQuantity, defectQuantity, 'output');
    checkAnomaly(val, defectQuantity);
  };

  const handleGoodChange = (val: string) => {
    setGoodQuantity(val);
    setAutoCalcField(null);
    autoCalcQuantities(outputQuantity, val, defectQuantity, 'good');
  };

  const handleDefectChange = (val: string) => {
    setDefectQuantity(val);
    setAutoCalcField(null);
    autoCalcQuantities(outputQuantity, goodQuantity, val, 'defect');
    checkAnomaly(outputQuantity, val);
  };

  const autoCalcQuantities = (output: string, good: string, defect: string, changedField: string) => {
    const total = parseFloat(output);
    const goodVal = parseFloat(good);
    const defectVal = parseFloat(defect);
    setCalcWarning('');

    if (isNaN(total) || total <= 0) return;

    if (changedField === 'output' || changedField === 'defect') {
      // If output and defect are filled → calc good
      if (!isNaN(defectVal)) {
        const calc = total - defectVal;
        if (calc < 0) {
          setCalcWarning('不良品数大于总数量');
          setGoodQuantity('0');
        } else {
          setGoodQuantity(String(calc));
        }
        setAutoCalcField('good');
      }
    }
    if (changedField === 'good') {
      // If output and good are filled → calc defect
      if (!isNaN(goodVal)) {
        const calc = total - goodVal;
        if (calc < 0) {
          setCalcWarning('良品数大于总数量');
          setDefectQuantity('0');
        } else {
          setDefectQuantity(String(calc));
        }
        setAutoCalcField('defect');
      }
    }
  };

  // Feature 5: Trigger anomaly check when process category changes
  const handleProcessCategorySelect = (val: string) => {
    setProcessCategory(val);
    onProcessCategoryChange(val);
  };

  // Feature 4: Voice input handler
  const handleVoiceInput = async () => {
    if (voiceStatus === 'recording') {
      const fields = await stopRecording();
      if (fields) {
        if (fields.processCategory) setProcessCategory(fields.processCategory);
        if (fields.productName) setProductName(fields.productName);
        if (fields.outputQuantity) {
          setOutputQuantity(fields.outputQuantity);
          if (fields.defectQuantity) {
            setDefectQuantity(fields.defectQuantity);
            const total = parseFloat(fields.outputQuantity);
            const defect = parseFloat(fields.defectQuantity);
            if (!isNaN(total) && !isNaN(defect)) {
              setGoodQuantity(String(Math.max(0, total - defect)));
              setAutoCalcField('good');
            }
          }
          if (fields.goodQuantity && !fields.defectQuantity) {
            setGoodQuantity(fields.goodQuantity);
          }
        }
        if (fields.operationVolume) setOperationVolume(fields.operationVolume);
      }
    } else if (voiceStatus === 'idle') {
      await startRecording();
    }
  };

  // Feature 7: Compute total workers/hours from collapsed or expanded mode
  const totalWorkers = useMemo(() => {
    return (hourEntry.fullTimeWorkers || 0) + (hourEntry.hourlyWorkers || 0) + (hourEntry.dailyWorkers || 0);
  }, [hourEntry]);

  const totalHours = useMemo(() => {
    return (hourEntry.fullTimeHours || 0) + (hourEntry.hourlyHours || 0) + (hourEntry.dailyHours || 0);
  }, [hourEntry]);

  const handleSubmit = useCallback(async () => {
    if (isProgress && !processCategory) {
      Alert.alert('提示', '请填写生产类目/工序');
      return;
    }
    if (!isProgress && !productName) {
      Alert.alert('提示', '请填写商品名称');
      return;
    }
    if (isProgress && (!outputQuantity || Number(outputQuantity) <= 0)) {
      Alert.alert('提示', '请输入大于 0 的产量');
      return;
    }
    if (!isProgress && totalHours <= 0) {
      Alert.alert('提示', '请填写大于 0 的工时');
      return;
    }
    if (!isProgress && ((startTime && !isValidTime(startTime)) || (endTime && !isValidTime(endTime)))) {
      Alert.alert('提示', '时间请按 00:00–23:59 填写');
      return;
    }

    const data: WorkReportSubmitRequest = {
      reportType,
      reportDate: today,
      batchId: selectedBatch?.id,
      processCategory: isProgress ? processCategory : undefined,
      productName: !isProgress ? productName : undefined,
      outputQuantity: outputQuantity ? parseFloat(outputQuantity) : undefined,
      goodQuantity: goodQuantity ? parseFloat(goodQuantity) : undefined,
      defectQuantity: defectQuantity ? parseFloat(defectQuantity) : undefined,
      operationVolume: operationVolume ? parseFloat(operationVolume) : undefined,
      productionStartTime: startTime || undefined,
      productionEndTime: endTime || undefined,
      hourEntries: !isProgress ? [hourEntry] : undefined,
      totalWorkers: !isProgress ? totalWorkers : undefined,
      totalWorkMinutes: !isProgress ? Math.round(totalHours * 60) : undefined,
    };

    const result = await submitReport(data);
    if (result) {
      // Feature 3: Save as last report
      if (factoryId && workerId) {
        useLastReportStore.getState().saveLastReport(reportType, factoryId, workerId, {
          processCategory,
          productName,
          operationVolume,
          hourEntry,
        });
      }
      Alert.alert('成功', '报工提交成功', [
        { text: '确定', onPress: () => navigation.goBack() },
      ]);
    }
  }, [reportType, processCategory, productName, outputQuantity, goodQuantity,
      defectQuantity, operationVolume, startTime, endTime, hourEntry, totalWorkers, totalHours, selectedBatch, submitReport, navigation, factoryId, workerId]);

  const handleSaveDraft = useCallback(() => {
    const { addDraft } = useDraftReportStore.getState();
    addDraft({
      batchId: selectedBatch?.id,
      batchNumber: selectedBatch?.batchNumber || '',
      productName: productName || processCategory || '',
      outputQuantity: outputQuantity ? parseFloat(outputQuantity) : 0,
      goodQuantity: goodQuantity ? parseFloat(goodQuantity) : 0,
      defectQuantity: defectQuantity ? parseFloat(defectQuantity) : 0,
      notes: notes || '',
      factoryId: factoryId || '',
      draftType: isProgress ? 'PROGRESS' : 'HOURS',
      processCategory: processCategory || undefined,
      startTime: startTime || undefined,
      endTime: endTime || undefined,
      hourEntries: !isProgress ? (hourEntry as Record<string, unknown>) : undefined,
      operationVolume: operationVolume ? parseFloat(operationVolume) : undefined,
    });
    Alert.alert('已保存', '草稿已保存，可在草稿管理中查看', [
      { text: '确定', onPress: () => navigation.goBack() },
    ]);
  }, [selectedBatch, productName, processCategory, outputQuantity, goodQuantity,
      defectQuantity, notes, factoryId, isProgress, startTime, endTime, hourEntry, operationVolume, navigation]);

  if (loading) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#4F46E5" style={{ marginTop: 100 }} />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* Header with voice button (Feature 4) */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <TouchableOpacity testID="header-back-btn" onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color="#1F2937" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{title}</Text>
        <TouchableOpacity
          testID="voice-input-btn"
          onPress={handleVoiceInput}
          style={[styles.voiceBtn, voiceStatus === 'recording' && styles.voiceBtnActive]}
        >
          <MaterialCommunityIcons
            name={voiceStatus === 'recording' ? 'microphone' : 'microphone-outline'}
            size={22}
            color={voiceStatus === 'recording' ? '#fff' : '#4F46E5'}
          />
        </TouchableOpacity>
      </View>

      {/* Voice status indicator */}
      {voiceStatus !== 'idle' && (
        <View style={styles.voiceStatusBar}>
          {voiceStatus === 'recording' && (
            <>
              <View style={styles.recordingDot} />
              <Text style={styles.voiceStatusText}>正在录音...松手结束</Text>
            </>
          )}
          {voiceStatus === 'processing' && (
            <>
              <ActivityIndicator size="small" color="#4F46E5" />
              <Text style={styles.voiceStatusText}>正在识别...</Text>
            </>
          )}
          {voiceStatus === 'done' && (
            <>
              <MaterialCommunityIcons name="check-circle" size={16} color="#059669" />
              <Text style={[styles.voiceStatusText, { color: '#059669' }]}>语音填写完成</Text>
            </>
          )}
        </View>
      )}

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={{ flex: 1 }}
      >
        <ScrollView style={styles.form} contentContainerStyle={styles.formContent}>
          {/* Feature 3: Last report banner */}
          {showLastReportBanner && (
            <View style={styles.lastReportBanner}>
              <MaterialCommunityIcons name="history" size={16} color="#1D4ED8" />
              <Text style={styles.lastReportText}>已填充上次报工数据</Text>
              <TouchableOpacity onPress={clearLastReportPrefill}>
                <Text style={styles.lastReportClear}>清除</Text>
              </TouchableOpacity>
            </View>
          )}

          {/* Batch selector (Feature 6: enhanced with checkin highlighting) */}
          {activeBatches.length > 0 ? (
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>关联批次</Text>
              {selectedBatch ? (
                <View style={styles.selectedBatchChip}>
                  <MaterialCommunityIcons name="package-variant" size={16} color="#4F46E5" />
                  <Text style={styles.selectedBatchText}>
                    {selectedBatch.batchNumber} - {selectedBatch.productName || '未知产品'}
                  </Text>
                  <TouchableOpacity onPress={clearBatchSelection} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
                    <MaterialCommunityIcons name="close-circle" size={18} color="#9CA3AF" />
                  </TouchableOpacity>
                </View>
              ) : (
                <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.batchChipScroll}>
                  {activeBatches.map(batch => (
                    <TouchableOpacity
                      key={batch.id}
                      style={[styles.batchChip, batch.isCheckedIn && styles.batchChipCheckedIn]}
                      onPress={() => handleSelectBatch(batch)}
                    >
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                        <Text style={styles.batchChipText}>{batch.batchNumber}</Text>
                        {batch.isCheckedIn && (
                          <MaterialCommunityIcons name="account-check" size={14} color="#059669" />
                        )}
                      </View>
                      <Text style={styles.batchChipSub}>{batch.productName || '—'}</Text>
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              )}
            </View>
          ) : !batchLoading ? (
            <View style={styles.noBatchBanner}>
              <MaterialCommunityIcons name="information-outline" size={16} color="#92400E" />
              <Text style={styles.noBatchText}>无进行中批次，使用手动模式</Text>
            </View>
          ) : null}

          {/* Date */}
          <View style={styles.fieldGroup}>
            <Text style={styles.label}>报工日期</Text>
            <View style={styles.readOnlyField}>
              <Text style={styles.readOnlyText}>{formatDate(today)}</Text>
            </View>
          </View>

          {/* Feature 1: Progress — Process Category dropdown */}
          {isProgress && (
            <SearchableDropdown
              label="生产类目/工序"
              required
              placeholder="选择或输入工序"
              options={processOptions}
              value={processCategory}
              onSelect={handleProcessCategorySelect}
              loading={optionsLoading}
              testID="process-category-dropdown"
            />
          )}

          {/* Feature 1: Hours — Product Name dropdown */}
          {!isProgress && (
            <SearchableDropdown
              label="商品名称"
              required
              placeholder="选择或输入商品"
              options={productOptions}
              value={productName}
              onSelect={setProductName}
              loading={optionsLoading}
              testID="product-name-dropdown"
            />
          )}

          {/* Feature 2: Output Quantity with auto-calc */}
          {isProgress && isFieldVisible('quantity') && (
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>产品数量</Text>
              <TextInput
                style={styles.input}
                placeholder="输入数量"
                value={outputQuantity}
                onChangeText={handleOutputChange}
                keyboardType="numeric"
              />
            </View>
          )}

          {/* Feature 5: Anomaly warnings */}
          {isProgress && anomalyWarnings.length > 0 && (
            <View style={styles.anomalyBanner}>
              {anomalyWarnings.map((w, i) => (
                <View key={i} style={styles.anomalyRow}>
                  <MaterialCommunityIcons name="alert-outline" size={16} color="#D97706" />
                  <Text style={styles.anomalyText}>{w.message}</Text>
                </View>
              ))}
            </View>
          )}

          {/* Feature 2: Good/Defect with auto-calculation */}
          {isProgress && (
            <>
              <View style={styles.row}>
                <View style={[styles.fieldGroup, { flex: 1, marginRight: 8 }]}>
                  <Text style={styles.label}>
                    良品数
                    {autoCalcField === 'good' && <Text style={styles.autoCalcLabel}> 自动计算</Text>}
                  </Text>
                  <TextInput
                    style={[styles.input, autoCalcField === 'good' && styles.autoCalcInput]}
                    placeholder="良品"
                    value={goodQuantity}
                    onChangeText={handleGoodChange}
                    keyboardType="numeric"
                  />
                </View>
                <View style={[styles.fieldGroup, { flex: 1, marginLeft: 8 }]}>
                  <Text style={styles.label}>
                    不良品数
                    {autoCalcField === 'defect' && <Text style={styles.autoCalcLabel}> 自动计算</Text>}
                  </Text>
                  <TextInput
                    style={[styles.input, autoCalcField === 'defect' && styles.autoCalcInput]}
                    placeholder="不良"
                    value={defectQuantity}
                    onChangeText={handleDefectChange}
                    keyboardType="numeric"
                  />
                </View>
              </View>
              {calcWarning ? (
                <Text style={styles.calcWarningText}>{calcWarning}</Text>
              ) : null}
            </>
          )}

          {/* Feature 7: Hours mode — simplified with expand toggle */}
          {!isProgress && (
            <>
              <View style={styles.hoursHeaderRow}>
                <Text style={styles.label}>工时明细</Text>
                <TouchableOpacity
                  onPress={() => setHoursExpanded(!hoursExpanded)}
                  style={styles.expandToggle}
                >
                  <Text style={styles.expandToggleText}>
                    {hoursExpanded ? '收起详情' : '展开详情'}
                  </Text>
                  <MaterialCommunityIcons
                    name={hoursExpanded ? 'chevron-up' : 'chevron-down'}
                    size={18}
                    color="#4F46E5"
                  />
                </TouchableOpacity>
              </View>

              <View style={styles.hoursCard}>
                {!hoursExpanded ? (
                  /* Collapsed: simple 2 fields */
                  <>
                    <View style={styles.hoursRow}>
                      <Text style={styles.hoursLabel}>总人数</Text>
                      <TextInput
                        style={[styles.hoursInput, { flex: 2 }]}
                        placeholder="总人数"
                        keyboardType="numeric"
                        value={hourEntry.fullTimeWorkers?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, fullTimeWorkers: v ? parseInt(v) : undefined })}
                      />
                    </View>
                    <View style={[styles.hoursRow, { marginBottom: 0 }]}>
                      <Text style={styles.hoursLabel}>总工时</Text>
                      <TextInput
                        style={[styles.hoursInput, { flex: 2 }]}
                        placeholder="总工时(h)"
                        keyboardType="numeric"
                        value={hourEntry.fullTimeHours?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, fullTimeHours: v ? parseFloat(v) : undefined })}
                      />
                    </View>
                  </>
                ) : (
                  /* Expanded: original 3 rows */
                  <>
                    <View style={styles.hoursRow}>
                      <Text style={styles.hoursLabel}>正式工</Text>
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="人数"
                        keyboardType="numeric"
                        value={hourEntry.fullTimeWorkers?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, fullTimeWorkers: v ? parseInt(v) : undefined })}
                      />
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="工时(h)"
                        keyboardType="numeric"
                        value={hourEntry.fullTimeHours?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, fullTimeHours: v ? parseFloat(v) : undefined })}
                      />
                    </View>
                    <View style={styles.hoursRow}>
                      <Text style={styles.hoursLabel}>小时工</Text>
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="人数"
                        keyboardType="numeric"
                        value={hourEntry.hourlyWorkers?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, hourlyWorkers: v ? parseInt(v) : undefined })}
                      />
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="工时(h)"
                        keyboardType="numeric"
                        value={hourEntry.hourlyHours?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, hourlyHours: v ? parseFloat(v) : undefined })}
                      />
                    </View>
                    <View style={[styles.hoursRow, { marginBottom: 0 }]}>
                      <Text style={styles.hoursLabel}>日结工</Text>
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="人数"
                        keyboardType="numeric"
                        value={hourEntry.dailyWorkers?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, dailyWorkers: v ? parseInt(v) : undefined })}
                      />
                      <TextInput
                        style={styles.hoursInput}
                        placeholder="工时(h)"
                        keyboardType="numeric"
                        value={hourEntry.dailyHours?.toString() || ''}
                        onChangeText={(v) => setHourEntry({ ...hourEntry, dailyHours: v ? parseFloat(v) : undefined })}
                      />
                    </View>
                  </>
                )}
              </View>

              {/* Summary when expanded */}
              {hoursExpanded && totalWorkers > 0 && (
                <View style={styles.hoursSummary}>
                  <Text style={styles.hoursSummaryText}>
                    合计: {totalWorkers}人 / {totalHours}h
                  </Text>
                </View>
              )}
            </>
          )}

          {/* Hours: Time Range */}
          {!isProgress && (
            <View style={styles.row}>
              <View style={[styles.fieldGroup, { flex: 1, marginRight: 8 }]}>
                <Text style={styles.label}>开始时间</Text>
                <TextInput
                  style={styles.input}
                  placeholder="HH:mm"
                  value={startTime}
                  onChangeText={(value) => setStartTime(normalizeTimeInput(value))}
                  keyboardType="number-pad"
                  maxLength={5}
                  testID="hours-start-time"
                />
              </View>
              <View style={[styles.fieldGroup, { flex: 1, marginLeft: 8 }]}>
                <Text style={styles.label}>结束时间</Text>
                <TextInput
                  style={styles.input}
                  placeholder="HH:mm"
                  value={endTime}
                  onChangeText={(value) => setEndTime(normalizeTimeInput(value))}
                  keyboardType="number-pad"
                  maxLength={5}
                  testID="hours-end-time"
                />
              </View>
            </View>
          )}

          {/* Hours: Operation Volume */}
          {!isProgress && (
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>操作量</Text>
              <TextInput
                style={styles.input}
                placeholder="输入操作量"
                value={operationVolume}
                onChangeText={setOperationVolume}
                keyboardType="numeric"
              />
            </View>
          )}

          {/* Notes */}
          <View style={styles.fieldGroup}>
            <Text style={styles.label}>备注</Text>
            <TextInput
              style={[styles.input, styles.textArea]}
              placeholder="备注信息（选填）"
              value={notes}
              onChangeText={setNotes}
              multiline
              numberOfLines={3}
            />
          </View>

          {/* Submit */}
          <TouchableOpacity
            style={[styles.submitBtn, submitting && styles.submitBtnDisabled]}
            onPress={handleSubmit}
            disabled={submitting}
          >
            {submitting ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.submitBtnText}>提交报工</Text>
            )}
          </TouchableOpacity>

          {/* Save Draft */}
          <TouchableOpacity
            style={styles.saveDraftBtn}
            onPress={handleSaveDraft}
          >
            <MaterialCommunityIcons name="content-save-outline" size={18} color="#4F46E5" />
            <Text style={styles.saveDraftBtnText}>保存草稿</Text>
          </TouchableOpacity>
        </ScrollView>
      </KeyboardAvoidingView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12, backgroundColor: '#fff',
    borderBottomWidth: 1, borderBottomColor: '#E5E7EB',
  },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 17, fontWeight: '600', color: '#1F2937' },
  form: { flex: 1 },
  formContent: { padding: 16, paddingBottom: 40 },
  fieldGroup: { marginBottom: 16 },
  label: { fontSize: 14, fontWeight: '500', color: '#374151', marginBottom: 6 },
  required: { color: '#EF4444' },
  input: {
    backgroundColor: '#fff', borderWidth: 1, borderColor: '#D1D5DB', borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 10, fontSize: 15, color: '#1F2937',
  },
  textArea: { height: 80, textAlignVertical: 'top' },
  readOnlyField: {
    backgroundColor: '#F3F4F6', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10,
  },
  readOnlyText: { fontSize: 15, color: '#6B7280' },
  row: { flexDirection: 'row', marginBottom: 16 },
  // Feature 2: Auto-calc styles
  autoCalcLabel: { fontSize: 11, color: '#3B82F6', fontWeight: '400' },
  autoCalcInput: { backgroundColor: '#EFF6FF', borderColor: '#93C5FD' },
  calcWarningText: { fontSize: 12, color: '#EF4444', marginTop: -12, marginBottom: 12, paddingHorizontal: 4 },
  // Feature 3: Last report banner
  lastReportBanner: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: '#DBEAFE',
    borderRadius: 8, padding: 10, marginBottom: 12, gap: 6,
  },
  lastReportText: { flex: 1, fontSize: 13, color: '#1D4ED8' },
  lastReportClear: { fontSize: 13, color: '#1D4ED8', fontWeight: '600' },
  // Feature 4: Voice
  voiceBtn: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center',
    backgroundColor: '#EEF2FF',
  },
  voiceBtnActive: { backgroundColor: '#EF4444' },
  voiceStatusBar: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    backgroundColor: '#EEF2FF', paddingVertical: 6, gap: 6,
  },
  recordingDot: {
    width: 8, height: 8, borderRadius: 4, backgroundColor: '#EF4444',
  },
  voiceStatusText: { fontSize: 13, color: '#4F46E5' },
  // Feature 5: Anomaly warning
  anomalyBanner: {
    backgroundColor: '#FEF3C7', borderRadius: 8, padding: 10, marginBottom: 12,
    borderWidth: 1, borderColor: '#FDE68A',
  },
  anomalyRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 },
  anomalyText: { fontSize: 13, color: '#92400E', flex: 1 },
  // Feature 6: Batch checkin highlight
  batchChipCheckedIn: { borderColor: '#059669', borderWidth: 2, backgroundColor: '#ECFDF5' },
  // Feature 7: Hours expand
  hoursHeaderRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8,
  },
  expandToggle: { flexDirection: 'row', alignItems: 'center', gap: 2 },
  expandToggleText: { fontSize: 13, color: '#4F46E5' },
  hoursSummary: {
    backgroundColor: '#EEF2FF', borderRadius: 6, padding: 8, marginBottom: 16, alignItems: 'center',
  },
  hoursSummaryText: { fontSize: 13, color: '#4F46E5', fontWeight: '500' },
  // Hours card
  hoursCard: {
    backgroundColor: '#fff', borderRadius: 12, padding: 12,
    borderWidth: 1, borderColor: '#E5E7EB', marginBottom: 16,
  },
  hoursRow: {
    flexDirection: 'row', alignItems: 'center', marginBottom: 10,
  },
  hoursLabel: { width: 60, fontSize: 13, fontWeight: '500', color: '#4B5563' },
  hoursInput: {
    flex: 1, marginLeft: 8, backgroundColor: '#F9FAFB', borderWidth: 1,
    borderColor: '#D1D5DB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 8,
    fontSize: 14, textAlign: 'center',
  },
  submitBtn: {
    backgroundColor: '#4F46E5', borderRadius: 12, paddingVertical: 14,
    alignItems: 'center', marginTop: 8,
  },
  submitBtnDisabled: { opacity: 0.6 },
  submitBtnText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  saveDraftBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    borderRadius: 12, paddingVertical: 12, marginTop: 10,
    borderWidth: 1, borderColor: '#C7D2FE', backgroundColor: '#EEF2FF', gap: 6,
  },
  saveDraftBtnText: { color: '#4F46E5', fontSize: 15, fontWeight: '500' },
  // Batch selector
  batchChipScroll: { flexDirection: 'row' },
  batchChip: {
    backgroundColor: '#F3F4F6', borderRadius: 10, paddingHorizontal: 14, paddingVertical: 10,
    marginRight: 8, borderWidth: 1, borderColor: '#E5E7EB', minWidth: 100,
  },
  batchChipText: { fontSize: 14, fontWeight: '600', color: '#4F46E5' },
  batchChipSub: { fontSize: 12, color: '#6B7280', marginTop: 2 },
  selectedBatchChip: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: '#EEF2FF',
    borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10,
    borderWidth: 1, borderColor: '#C7D2FE', gap: 8,
  },
  selectedBatchText: { flex: 1, fontSize: 14, fontWeight: '600', color: '#4F46E5' },
  noBatchBanner: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEF3C7',
    borderRadius: 8, padding: 10, marginBottom: 16, gap: 6,
  },
  noBatchText: { fontSize: 13, color: '#92400E', flex: 1 },
});
