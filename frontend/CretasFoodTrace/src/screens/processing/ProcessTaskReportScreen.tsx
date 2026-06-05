import React, { useState, useCallback } from 'react';
import { View, StyleSheet, ScrollView, Alert, KeyboardAvoidingView, Platform } from 'react-native';
import { Text, Appbar, Card, TextInput, ActivityIndicator, SegmentedButtons, Chip } from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';
import { ProcessingScreenProps } from '../../types/navigation';
import { processTaskApiClient, ProcessTaskItem, SubmitProcessReportPayload } from '../../services/api/processTaskApiClient';
import { attachmentApi, AttachmentFileCategory } from '../../services/api/attachmentApi';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';

type Props = ProcessingScreenProps<'ProcessTaskReport'>;

interface EvidenceAsset {
  uri: string;
  fileName: string;
  mimeType: string;
  size: number;
  category: AttachmentFileCategory;
}

interface ParsedReportFields {
  inputQuantity?: number;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  reportDate?: string;
  productionStartTime?: string;
  productionEndTime?: string;
  sampleBoxes?: number;
  remainingBoxes?: number;
  trimWeightKg?: number;
  upstreamInputKg?: number;
  upstreamOutputKg?: number;
  grossWeightKg?: number;
  tareWeightKg?: number;
  netWeightKg?: number;
  byproductText?: string;
  laborSegmentsText?: string;
}

function todayStr(): string {
  const d = new Date();
  const month = `${d.getMonth() + 1}`.padStart(2, '0');
  const day = `${d.getDate()}`.padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

function parseOptionalNumber(raw: string, label: string): number | undefined {
  const value = raw.trim();
  if (!value) return undefined;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) {
    throw new Error(`${label}必须是大于等于 0 的数字`);
  }
  return parsed;
}

function parseOptionalInteger(raw: string, label: string): number | undefined {
  const parsed = parseOptionalNumber(raw, label);
  if (parsed === undefined) return undefined;
  if (!Number.isInteger(parsed)) {
    throw new Error(`${label}必须是整数`);
  }
  return parsed;
}

function normalizeOptionalDate(raw: string): string | undefined {
  const value = raw.trim();
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new Error('报工日期格式应为 YYYY-MM-DD');
  }
  return value;
}

function normalizeOptionalTime(raw: string, label: string): string | undefined {
  const value = raw.trim();
  if (!value) return undefined;
  if (!/^\d{2}:\d{2}$/.test(value)) {
    throw new Error(`${label}格式应为 HH:mm`);
  }
  return value;
}

function trimOptionalText(raw: string): string | undefined {
  const value = raw.trim();
  return value ? value : undefined;
}

function formatCompactNumber(value: number): string {
  return Number(value.toFixed(2)).toString();
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}

function extractReportId(response: unknown): string | null {
  const root = asRecord(response);
  const data = asRecord(root?.data);
  const reportId = data?.reportId ?? root?.reportId;
  if (typeof reportId === 'number' || typeof reportId === 'string') return String(reportId);
  return null;
}

function guessExt(uri: string, mime?: string): string {
  if (mime?.startsWith('image/')) return mime.replace('image/', '');
  if (mime?.startsWith('video/')) return mime.replace('video/', '') === 'quicktime' ? 'mov' : mime.replace('video/', '');
  const dot = uri.lastIndexOf('.');
  return dot > 0 ? uri.substring(dot + 1) : 'jpg';
}

function guessMime(uri: string): string {
  const ext = uri.toLowerCase().split('.').pop() ?? '';
  const map: Record<string, string> = {
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    webp: 'image/webp',
    gif: 'image/gif',
    mp4: 'video/mp4',
    mov: 'video/quicktime',
  };
  return map[ext] ?? 'image/jpeg';
}

export default function ProcessTaskReportScreen() {
  const navigation = useNavigation<Props['navigation']>();
  const route = useRoute<Props['route']>();
  const { taskId, processName, unit } = route.params;

  const [task, setTask] = useState<ProcessTaskItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [quantity, setQuantity] = useState('');
  const [inputQty, setInputQty] = useState('');
  const [grossWeight, setGrossWeight] = useState('');
  const [tareWeight, setTareWeight] = useState('');
  const [netWeight, setNetWeight] = useState('');
  const [sampleBoxes, setSampleBoxes] = useState('');
  const [remainingBoxes, setRemainingBoxes] = useState('');
  const [trimWeightKg, setTrimWeightKg] = useState('');
  const [byproductText, setByproductText] = useState('');
  const [upstreamInputKg, setUpstreamInputKg] = useState('');
  const [upstreamOutputKg, setUpstreamOutputKg] = useState('');
  const [laborSegmentsText, setLaborSegmentsText] = useState('');
  const [totalWorkers, setTotalWorkers] = useState('');
  const [totalWorkMinutes, setTotalWorkMinutes] = useState('');
  const [reportDate, setReportDate] = useState(todayStr());
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [notes, setNotes] = useState('');
  const [reportMode, setReportMode] = useState<'MODE_1' | 'MODE_2' | 'MODE_3'>('MODE_1');
  const [batchNumber, setBatchNumber] = useState('');
  const [evidenceAssets, setEvidenceAssets] = useState<EvidenceAsset[]>([]);

  const loadTask = useCallback(async () => {
    try {
      const res = await processTaskApiClient.getTaskById(taskId) as { data?: ProcessTaskItem };
      if (res?.data) setTask(res.data);
    } catch {
      Alert.alert('错误', '加载任务信息失败');
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useFocusEffect(
    useCallback(() => {
      loadTask();
    }, [loadTask])
  );

  const isSupplemental = task?.status === 'SUPPLEMENTING' || task?.status === 'COMPLETED' || task?.status === 'CLOSED';

  const buildParsedFields = (): ParsedReportFields => {
    const gross = parseOptionalNumber(grossWeight, '毛重');
    const tare = parseOptionalNumber(tareWeight, '皮重');
    const manualNet = parseOptionalNumber(netWeight, '净重');
    const calculatedNet = gross !== undefined && tare !== undefined ? gross - tare : undefined;
    if (calculatedNet !== undefined && calculatedNet < 0) {
      throw new Error('毛重不能小于皮重');
    }
    return {
      inputQuantity: parseOptionalNumber(inputQty, '投入数量'),
      totalWorkers: parseOptionalInteger(totalWorkers, '人数'),
      totalWorkMinutes: parseOptionalInteger(totalWorkMinutes, '工时分钟'),
      reportDate: normalizeOptionalDate(reportDate),
      productionStartTime: normalizeOptionalTime(startTime, '开始时间'),
      productionEndTime: normalizeOptionalTime(endTime, '结束时间'),
      sampleBoxes: parseOptionalInteger(sampleBoxes, '留样盒数'),
      remainingBoxes: parseOptionalInteger(remainingBoxes, '剩余盒数'),
      trimWeightKg: parseOptionalNumber(trimWeightKg, '料头/损耗重量'),
      upstreamInputKg: parseOptionalNumber(upstreamInputKg, '上游投入重量'),
      upstreamOutputKg: parseOptionalNumber(upstreamOutputKg, '上游产出重量'),
      grossWeightKg: gross,
      tareWeightKg: tare,
      netWeightKg: manualNet ?? calculatedNet,
      byproductText: trimOptionalText(byproductText),
      laborSegmentsText: trimOptionalText(laborSegmentsText),
    };
  };

  const buildCustomFields = (parsed: ParsedReportFields): Record<string, unknown> => {
    const fields: Record<string, unknown> = {
      evidenceWorkflow: 'TEXT_INPUT_WITH_MEDIA_EVIDENCE',
    };
    const batch = trimOptionalText(batchNumber);
    if (batch) fields.batchNumber = batch;
    if (parsed.sampleBoxes !== undefined) fields.sampleBoxes = parsed.sampleBoxes;
    if (parsed.remainingBoxes !== undefined) fields.remainingBoxes = parsed.remainingBoxes;
    if (parsed.trimWeightKg !== undefined) fields.trimWeightKg = parsed.trimWeightKg;
    if (parsed.byproductText) fields.byproductText = parsed.byproductText;
    if (parsed.upstreamInputKg !== undefined) fields.upstreamInputKg = parsed.upstreamInputKg;
    if (parsed.upstreamOutputKg !== undefined) fields.upstreamOutputKg = parsed.upstreamOutputKg;
    if (parsed.grossWeightKg !== undefined) fields.grossWeightKg = parsed.grossWeightKg;
    if (parsed.tareWeightKg !== undefined) fields.tareWeightKg = parsed.tareWeightKg;
    if (parsed.netWeightKg !== undefined) fields.netWeightKg = parsed.netWeightKg;
    if (parsed.laborSegmentsText) fields.laborSegmentsText = parsed.laborSegmentsText;
    return fields;
  };

  const addEvidenceFromAsset = useCallback(async (asset: ImagePicker.ImagePickerAsset) => {
    let size = asset.fileSize ?? 0;
    if (!size) {
      const info = await FileSystem.getInfoAsync(asset.uri, { size: true });
      size = info.exists && 'size' in info ? (info.size as number) : 0;
    }
    const mimeType = asset.mimeType ?? guessMime(asset.uri);
    const category: AttachmentFileCategory = mimeType.startsWith('video/') ? 'VIDEO' : 'PHOTO';
    const fileName = asset.fileName ?? `process_report_${Date.now()}.${guessExt(asset.uri, mimeType)}`;
    setEvidenceAssets((prev) => [...prev, { uri: asset.uri, fileName, mimeType, size, category }]);
  }, []);

  const takeEvidencePhoto = useCallback(async () => {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相机权限', '请在系统设置中开启相机权限后再拍照。');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.75,
    });
    if (!result.canceled && result.assets[0]) {
      await addEvidenceFromAsset(result.assets[0]);
    }
  }, [addEvidenceFromAsset]);

  const pickEvidenceMedia = useCallback(async () => {
    const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相册权限', '请在系统设置中开启相册权限后再上传照片或视频。');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.All,
      quality: 0.75,
      allowsMultipleSelection: true,
    });
    if (!result.canceled) {
      for (const asset of result.assets) {
        await addEvidenceFromAsset(asset);
      }
    }
  }, [addEvidenceFromAsset]);

  const removeEvidence = (index: number) => {
    setEvidenceAssets((prev) => prev.filter((_, i) => i !== index));
  };

  const uploadEvidence = async (reportId: string) => {
    let uploaded = 0;
    const errors: string[] = [];
    for (const asset of evidenceAssets) {
      try {
        await attachmentApi.uploadAndRegister(
          { uri: asset.uri, name: asset.fileName, type: asset.mimeType, size: asset.size },
          'PRODUCTION_REPORT',
          reportId,
          { businessTag: 'PROCESS_REPORT_EVIDENCE', fileCategory: asset.category },
        );
        uploaded += 1;
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '上传失败';
        errors.push(`${asset.fileName}: ${msg}`);
      }
    }
    return { uploaded, errors };
  };

  const handleSubmit = async () => {
    const qty = parseFloat(quantity);
    if (isNaN(qty) || qty <= 0) {
      Alert.alert('提示', '请输入有效的产出数量');
      return;
    }
    let parsed: ParsedReportFields;
    try {
      parsed = buildParsedFields();
    } catch (err) {
      Alert.alert('提示', err instanceof Error ? err.message : '请检查报工数据');
      return;
    }

    const remaining = task ? task.plannedQuantity - task.completedQuantity - task.pendingQuantity : 0;
    const needsOverConfirm = !isSupplemental && remaining > 0 && qty > remaining * 1.5;
    const submitOrConfirmOver = () => {
      if (!needsOverConfirm) {
        void doSubmit(qty, parsed);
        return;
      }
      Alert.alert(
        '超量确认',
        `报工量 ${qty} 超过剩余量 ${remaining} 的150%，确定提交吗？`,
        [
          { text: '取消', style: 'cancel' },
          { text: '仍然提交', style: 'destructive', onPress: () => doSubmit(qty, parsed) },
        ]
      );
    };

    if (evidenceAssets.length === 0) {
      Alert.alert(
        '缺少现场证据',
        '这次报工没有照片或视频。现场报工建议至少上传 1 张照片，方便主管核对。',
        [
          { text: '返回补拍', style: 'cancel' },
          { text: '仍然提交', onPress: submitOrConfirmOver },
        ],
      );
      return;
    }

    submitOrConfirmOver();
  };

  const doSubmit = async (qty: number, parsed: ParsedReportFields) => {
    if (reportMode === 'MODE_2' && !batchNumber.trim()) {
      Alert.alert('提示', '按批次报工需填写批次号');
      return;
    }
    setSubmitting(true);
    try {
      const commonPayload: SubmitProcessReportPayload = {
        processTaskId: taskId,
        batchId: task?.batchId ?? task?.productionBatchId,
        workProcessTaskId: task?.workProcessTaskId,
        outputQuantity: qty,
        inputQuantity: parsed.inputQuantity,
        inputUnit: task?.inputUnit ?? task?.unit,
        outputUnit: task?.outputUnit ?? task?.plannedUnit ?? task?.unit,
        totalWorkers: parsed.totalWorkers,
        totalWorkMinutes: parsed.totalWorkMinutes,
        reportDate: parsed.reportDate,
        productionStartTime: parsed.productionStartTime,
        productionEndTime: parsed.productionEndTime,
        notes: notes || undefined,
        batchNumber: batchNumber.trim() || undefined,
        customFields: buildCustomFields(parsed),
      };
      let response: unknown;
      if (isSupplemental) {
        response = await processTaskApiClient.submitSupplement(commonPayload);
      } else {
        response = await processTaskApiClient.submitNormalReport({
          ...commonPayload,
          reportMode,
        });
      }

      const reportId = extractReportId(response);
      const uploadResult = reportId && evidenceAssets.length > 0
        ? await uploadEvidence(reportId)
        : { uploaded: 0, errors: evidenceAssets.length > 0 ? ['报工已提交，但后端未返回 reportId，证据未上传'] : [] };

      const evidenceMessage = evidenceAssets.length > 0
        ? `\n现场证据: ${uploadResult.uploaded}/${evidenceAssets.length}`
        : '\n现场证据: 未上传';
      const errorMessage = uploadResult.errors.length > 0 ? `\n${uploadResult.errors.join('\n')}` : '';
      Alert.alert(
        '成功',
        `${isSupplemental ? '补报' : '报工'}已提交，等待审批${evidenceMessage}${errorMessage}`,
        [{ text: '确定', onPress: () => navigation.goBack() }],
      );
    } catch (err) {
      Alert.alert('错误', err instanceof Error ? err.message : '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
        <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="报工" /></Appbar.Header>
        <ActivityIndicator style={{ flex: 1 }} size="large" />
      </ScreenWrapper>
    );
  }

  const remaining = task ? Math.max(0, task.plannedQuantity - task.completedQuantity - task.pendingQuantity) : 0;
  const grossPreview = grossWeight.trim() ? Number(grossWeight) : undefined;
  const tarePreview = tareWeight.trim() ? Number(tareWeight) : undefined;
  const netPreview = grossPreview !== undefined && tarePreview !== undefined
    && Number.isFinite(grossPreview) && Number.isFinite(tarePreview)
    ? grossPreview - tarePreview
    : undefined;

  return (
    <ScreenWrapper testID="process-task-report" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction testID="report-back" onPress={() => navigation.goBack()} />
        <Appbar.Content
          title={isSupplemental ? '补报' : '报工'}
          subtitle={processName}
          titleStyle={{ fontWeight: '600' }}
        />
      </Appbar.Header>

      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.scrollContent}>
          {/* Task Context */}
          {task && (
            <Card style={styles.card}>
              <Card.Content>
                <Text variant="titleMedium" style={styles.sectionTitle}>
                  {task.processName} — {task.productTypeName || ''}
                </Text>
                <View style={styles.contextGrid}>
                  <View style={styles.contextItem}>
                    <Text style={styles.contextValue}>{task.plannedQuantity}</Text>
                    <Text style={styles.contextLabel}>计划量</Text>
                  </View>
                  <View style={styles.contextItem}>
                    <Text style={[styles.contextValue, { color: '#67c23a' }]}>{task.completedQuantity}</Text>
                    <Text style={styles.contextLabel}>已完成</Text>
                  </View>
                  <View style={styles.contextItem}>
                    <Text style={[styles.contextValue, { color: '#e6a23c' }]}>{task.pendingQuantity}</Text>
                    <Text style={styles.contextLabel}>待审批</Text>
                  </View>
                  <View style={styles.contextItem}>
                    <Text style={[styles.contextValue, { color: remaining > 0 ? '#1890ff' : '#67c23a' }]}>
                      {remaining}
                    </Text>
                    <Text style={styles.contextLabel}>剩余</Text>
                  </View>
                </View>
              </Card.Content>
            </Card>
          )}

          {isSupplemental && (
            <Card style={[styles.card, { backgroundColor: '#fef3e6' }]}>
              <Card.Content>
                <Text style={{ color: '#e6a23c', fontWeight: '600' }}>补报模式</Text>
                <Text style={{ color: '#b88230', fontSize: 13, marginTop: 4 }}>
                  当前任务已完成/关闭，提交的报工将标记为补报，需要主管审批后才会计入完成量。
                </Text>
              </Card.Content>
            </Card>
          )}

          {/* Input Form */}
          <Card style={styles.card}>
            <Card.Content>
              <Text variant="titleMedium" style={styles.sectionTitle}>填写报工</Text>

              {!isSupplemental && (
                <View style={{ marginBottom: 12 }}>
                  <Text style={{ fontSize: 13, color: '#666', marginBottom: 6 }}>报工模式</Text>
                  <SegmentedButtons
                    value={reportMode}
                    onValueChange={(v) => setReportMode(v as 'MODE_1' | 'MODE_2' | 'MODE_3')}
                    buttons={[
                      { value: 'MODE_1', label: '按工序' },
                      { value: 'MODE_2', label: '按批次' },
                      { value: 'MODE_3', label: '按人头' },
                    ]}
                  />
                </View>
              )}

              <TextInput
                testID="report-batch-number-input"
                label={reportMode === 'MODE_2' ? '生产批次号 (必填)' : '生产批次号 (建议填写)'}
                value={batchNumber}
                onChangeText={setBatchNumber}
                mode="outlined"
                style={[styles.input, { marginBottom: 12 }]}
              />

              <TextInput
                testID="report-quantity-input"
                label={`产出数量 (${unit || 'kg'})`}
                value={quantity}
                onChangeText={setQuantity}
                keyboardType="decimal-pad"
                mode="outlined"
                style={styles.input}
                right={<TextInput.Affix text={unit || 'kg'} />}
              />

              {remaining > 0 && !isSupplemental && (
                <View style={styles.quickButtons}>
                  <Text style={styles.quickLabel}>快捷填入:</Text>
                  {[remaining, Math.round(remaining / 2)].filter(v => v > 0).map(v => (
                    <NeoButton
                      key={v}
                      testID={`report-quick-fill-${v}`}
                      variant="outline"
                      size="small"
                      onPress={() => setQuantity(String(v))}
                      style={styles.quickBtn}
                    >
                      {v}
                    </NeoButton>
                  ))}
                </View>
              )}

              <TextInput
                testID="report-input-quantity-input"
                label={`投入数量 (${unit || 'kg'}, 可选)`}
                value={inputQty}
                onChangeText={setInputQty}
                keyboardType="decimal-pad"
                mode="outlined"
                style={[styles.input, { marginTop: 12 }]}
                right={<TextInput.Affix text={unit || 'kg'} />}
              />

              <Text style={styles.groupTitle}>称重净重</Text>
              <View style={styles.twoCol}>
                <TextInput
                  testID="report-gross-weight-input"
                  label="毛重 kg"
                  value={grossWeight}
                  onChangeText={setGrossWeight}
                  keyboardType="decimal-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
                <TextInput
                  testID="report-tare-weight-input"
                  label="皮重 kg"
                  value={tareWeight}
                  onChangeText={setTareWeight}
                  keyboardType="decimal-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
              </View>
              <TextInput
                testID="report-net-weight-input"
                label="净重 kg (可手填覆盖)"
                value={netWeight}
                onChangeText={setNetWeight}
                keyboardType="decimal-pad"
                mode="outlined"
                style={[styles.input, { marginTop: 12 }]}
              />
              {netPreview !== undefined && netPreview >= 0 && !netWeight.trim() && (
                <Text style={styles.helpText}>自动净重: {formatCompactNumber(netPreview)} kg</Text>
              )}

              <Text style={styles.groupTitle}>入库与结存</Text>
              <View style={styles.twoCol}>
                <TextInput
                  testID="report-sample-boxes-input"
                  label="留样盒数"
                  value={sampleBoxes}
                  onChangeText={setSampleBoxes}
                  keyboardType="number-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
                <TextInput
                  testID="report-remaining-boxes-input"
                  label="剩余盒数"
                  value={remainingBoxes}
                  onChangeText={setRemainingBoxes}
                  keyboardType="number-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
              </View>

              <Text style={styles.groupTitle}>工序补充</Text>
              <View style={styles.twoCol}>
                <TextInput
                  testID="report-upstream-input-input"
                  label="上游投入 kg"
                  value={upstreamInputKg}
                  onChangeText={setUpstreamInputKg}
                  keyboardType="decimal-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
                <TextInput
                  testID="report-upstream-output-input"
                  label="上游产出 kg"
                  value={upstreamOutputKg}
                  onChangeText={setUpstreamOutputKg}
                  keyboardType="decimal-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
              </View>
              <TextInput
                testID="report-trim-weight-input"
                label="料头/损耗 kg"
                value={trimWeightKg}
                onChangeText={setTrimWeightKg}
                keyboardType="decimal-pad"
                mode="outlined"
                style={[styles.input, { marginTop: 12 }]}
              />
              <TextInput
                testID="report-byproduct-input"
                label="副产物说明 (如肥油100kg、骨头289kg)"
                value={byproductText}
                onChangeText={setByproductText}
                mode="outlined"
                multiline
                numberOfLines={2}
                style={[styles.input, { marginTop: 12 }]}
              />

              <View style={styles.twoCol}>
                <TextInput
                  testID="report-workers-input"
                  label="人数"
                  value={totalWorkers}
                  onChangeText={setTotalWorkers}
                  keyboardType="number-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
                <TextInput
                  testID="report-minutes-input"
                  label="工时分钟"
                  value={totalWorkMinutes}
                  onChangeText={setTotalWorkMinutes}
                  keyboardType="number-pad"
                  mode="outlined"
                  style={[styles.input, styles.colInput]}
                />
              </View>

              <TextInput
                testID="report-labor-segments-input"
                label="多时段人工 (如 7-8点11人；8-10点16人)"
                value={laborSegmentsText}
                onChangeText={setLaborSegmentsText}
                mode="outlined"
                multiline
                numberOfLines={3}
                style={[styles.input, { marginTop: 12 }]}
              />

              <TextInput
                testID="report-date-input"
                label="报工日期"
                value={reportDate}
                onChangeText={setReportDate}
                mode="outlined"
                placeholder="YYYY-MM-DD"
                style={[styles.input, { marginTop: 12 }]}
              />

              <View style={styles.twoCol}>
                <TextInput
                  testID="report-start-time-input"
                  label="开始时间"
                  value={startTime}
                  onChangeText={setStartTime}
                  mode="outlined"
                  placeholder="HH:mm"
                  style={[styles.input, styles.colInput]}
                />
                <TextInput
                  testID="report-end-time-input"
                  label="结束时间"
                  value={endTime}
                  onChangeText={setEndTime}
                  mode="outlined"
                  placeholder="HH:mm"
                  style={[styles.input, styles.colInput]}
                />
              </View>

              <TextInput
                testID="report-notes-input"
                label="备注 (选填)"
                value={notes}
                onChangeText={setNotes}
                mode="outlined"
                multiline
                numberOfLines={3}
                style={[styles.input, { marginTop: 12 }]}
              />
            </Card.Content>
          </Card>

          <Card style={styles.card}>
            <Card.Content>
              <Text variant="titleMedium" style={styles.sectionTitle}>现场证据</Text>
              <View style={styles.evidenceActions}>
                <NeoButton
                  testID="report-add-photo-btn"
                  variant="outline"
                  size="small"
                  onPress={takeEvidencePhoto}
                  style={styles.evidenceBtn}
                >
                  拍照
                </NeoButton>
                <NeoButton
                  testID="report-add-media-btn"
                  variant="outline"
                  size="small"
                  onPress={pickEvidenceMedia}
                  style={styles.evidenceBtn}
                >
                  照片/视频
                </NeoButton>
              </View>
              {evidenceAssets.length === 0 ? (
                <Text style={styles.evidenceHint}>建议至少上传 1 张现场照片；如有核对视频，可以从相册选择视频。</Text>
              ) : (
                <View style={styles.evidenceList}>
                  {evidenceAssets.map((asset, index) => (
                    <Chip
                      key={`${asset.fileName}-${index}`}
                      testID={`report-evidence-chip-${index}`}
                      onClose={() => removeEvidence(index)}
                      style={styles.evidenceChip}
                    >
                      {asset.category === 'VIDEO' ? '视频' : '照片'} {index + 1}
                    </Chip>
                  ))}
                </View>
              )}
            </Card.Content>
          </Card>

          <NeoButton
            testID="report-submit-btn"
            variant="primary"
            onPress={handleSubmit}
            loading={submitting}
            disabled={submitting || !quantity}
            style={styles.submitBtn}
          >
            {isSupplemental ? '提交补报' : '提交报工'}
          </NeoButton>
        </ScrollView>
      </KeyboardAvoidingView>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  scrollContent: { padding: 16, paddingBottom: 32 },
  card: { marginBottom: 12, borderRadius: 12, backgroundColor: '#fff', elevation: 2 },
  sectionTitle: { fontWeight: '600', marginBottom: 12, color: '#333' },
  contextGrid: { flexDirection: 'row', justifyContent: 'space-around' },
  contextItem: { alignItems: 'center' },
  contextValue: { fontSize: 24, fontWeight: '700', color: '#333' },
  contextLabel: { fontSize: 15, color: '#666', marginTop: 2 },
  input: { backgroundColor: '#fff', fontSize: 20 },
  groupTitle: { fontSize: 15, fontWeight: '700', color: '#333', marginTop: 18, marginBottom: 2 },
  helpText: { color: '#1f7a3f', fontSize: 14, marginTop: 6 },
  twoCol: { flexDirection: 'row', gap: 10, marginTop: 12 },
  colInput: { flex: 1, minWidth: 0 },
  quickButtons: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 10 },
  quickLabel: { fontSize: 14, color: '#666' },
  quickBtn: { minWidth: 64, minHeight: 48 },
  evidenceActions: { flexDirection: 'row', gap: 10 },
  evidenceBtn: { flex: 1, minHeight: 46 },
  evidenceHint: { color: '#666', fontSize: 14, lineHeight: 20, marginTop: 10 },
  evidenceList: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 },
  evidenceChip: { backgroundColor: '#f0f9ff' },
  submitBtn: { marginTop: 12, height: 52 },
});
