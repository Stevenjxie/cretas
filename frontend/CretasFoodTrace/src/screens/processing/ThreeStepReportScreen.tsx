import React, { useState, useCallback, useEffect } from 'react';
import {
  View, StyleSheet, ScrollView, Alert, TouchableOpacity,
  KeyboardAvoidingView, Platform,
} from 'react-native';
import { Text, Appbar, Card, TextInput, ActivityIndicator, Chip, Divider } from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';
import { ProcessingScreenProps } from '../../types/navigation';
import {
  processTaskApiClient,
  ProcessTaskItem,
  SubmitProcessReportPayload,
} from '../../services/api/processTaskApiClient';
import { attachmentApi, AttachmentFileCategory } from '../../services/api/attachmentApi';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import { TutorialOverlay } from '../../components/common/TutorialOverlay';
import { useTutorialStore, TUTORIAL_THREE_STEP, useTutorialTarget, useTutorial } from '../../store/tutorialStore';
import { theme } from '../../theme';
import { apiClient } from '../../services/api/apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

type Props = ProcessingScreenProps<'ThreeStepReport'>;

interface ScannedWorker {
  id: number;
  name: string;
}

interface EvidenceAsset {
  uri: string;
  fileName: string;
  mimeType: string;
  size: number;
  category: AttachmentFileCategory;
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

const STATUS_COLORS: Record<string, string> = {
  IN_PROGRESS: '#1890ff',
  PENDING: '#909399',
  SUPPLEMENTING: '#e6a23c',
};

export default function ThreeStepReportScreen() {
  const navigation = useNavigation<Props['navigation']>();

  // Step tracking: 1=scan worker, 2=select task, 3=input quantity
  const [step, setStep] = useState(1);

  // Step 1 state
  const [scannerVisible, setScannerVisible] = useState(false);
  const [worker, setWorker] = useState<ScannedWorker | null>(null);
  const [scanLoading, setScanLoading] = useState(false);

  // Step 2 state
  const [tasks, setTasks] = useState<ProcessTaskItem[]>([]);
  const [tasksLoading, setTasksLoading] = useState(false);
  const [selectedTask, setSelectedTask] = useState<ProcessTaskItem | null>(null);

  // Step 3 state
  const [quantity, setQuantity] = useState('');
  const [inputQty, setInputQty] = useState('');
  const [workersCount, setWorkersCount] = useState('');
  const [workMinutes, setWorkMinutes] = useState('');
  const [reportDate, setReportDate] = useState(todayStr());
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [evidenceAssets, setEvidenceAssets] = useState<EvidenceAsset[]>([]);

  // Tutorial
  const tgtStepIndicator = useTutorialTarget('tsr-step-indicator');
  const tgtScanArea = useTutorialTarget('tsr-scan-area');
  const tut = useTutorial(TUTORIAL_THREE_STEP, () => {
    tgtStepIndicator.measure(); tgtScanArea.measure();
  });

  // Load active tasks when entering step 2
  const loadTasks = useCallback(async () => {
    setTasksLoading(true);
    try {
      const res = await processTaskApiClient.getActiveTasks() as {
        data?: ProcessTaskItem[] | { content?: ProcessTaskItem[] };
      };
      const list = Array.isArray(res?.data) ? res.data :
        (res?.data as { content?: ProcessTaskItem[] })?.content || [];
      // PENDING tasks are valid here: a group leader can start reporting directly from the first on-site event.
      setTasks(list.filter(t => t.status === 'PENDING' || t.status === 'IN_PROGRESS' || t.status === 'SUPPLEMENTING'));
    } catch {
      Alert.alert('错误', '加载工序任务失败');
    } finally {
      setTasksLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadTasks();
    }, [loadTasks])
  );

  // Step 1: Handle employee QR scan
  // Parse scanned code: supports "CRETAS:EMP:{id}:{factoryId}" (NFC tag) or plain employee code like "001"
  const parseScannedCode = (code: string): { type: 'nfc'; id: number } | { type: 'employeeCode'; code: string } | null => {
    const nfcMatch = code.match(/^CRETAS:EMP:(\d+):/);
    if (nfcMatch?.[1]) return { type: 'nfc', id: parseInt(nfcMatch[1], 10) };
    if (code.trim()) return { type: 'employeeCode', code: code.trim() };
    return null;
  };

  const handleWorkerScan = useCallback(async (raw: string) => {
    setScannerVisible(false);
    setScanLoading(true);
    try {
      const parsed = parseScannedCode(raw);
      if (!parsed) { Alert.alert('未识别', '无法识别此工牌，请重试'); return; }

      const factoryId = requireFactoryId();

      if (parsed.type === 'nfc') {
        // NFC tag already contains employeeId — use directly
        setWorker({ id: parsed.id, name: `员工#${parsed.id}` });
        setStep(2);
        return;
      }

      // Look up by employee code (e.g. "001") via existing API
      const res = await apiClient.get(`/api/mobile/${factoryId}/users/by-employee-code/${encodeURIComponent(parsed.code)}`) as {
        success?: boolean;
        data?: { id: number; fullName?: string; username?: string; employeeCode?: string };
      };
      if (res?.success && res.data) {
        setWorker({ id: res.data.id, name: res.data.fullName || res.data.username || `员工#${res.data.id}` });
        setStep(2);
      } else {
        // Fallback: treat as numeric employee ID
        const id = parseInt(parsed.code, 10);
        if (!isNaN(id) && id > 0) {
          setWorker({ id, name: `员工#${id}` });
          setStep(2);
        } else {
          Alert.alert('未找到员工', `工号 "${parsed.code}" 未在系统中找到`);
        }
      }
    } catch {
      // Fallback: try as numeric ID
      const id = parseInt(raw, 10);
      if (!isNaN(id) && id > 0) {
        setWorker({ id, name: `员工#${id}` });
        setStep(2);
      } else {
        Alert.alert('查询失败', '请检查工牌二维码是否正确');
      }
    } finally {
      setScanLoading(false);
    }
  }, []);

  // Step 2: Select a task
  const handleSelectTask = (task: ProcessTaskItem) => {
    setSelectedTask(task);
    setStep(3);
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

  // Step 3: Submit report
  const handleSubmit = async () => {
    if (!selectedTask) return;

    const qty = parseFloat(quantity);
    if (isNaN(qty) || qty <= 0) {
      Alert.alert('提示', '请输入有效的产出数量');
      return;
    }

    try {
      parseOptionalNumber(inputQty, '投入数量');
      parseOptionalInteger(workersCount, '人数');
      parseOptionalInteger(workMinutes, '工时分钟');
      normalizeOptionalDate(reportDate);
      normalizeOptionalTime(startTime, '开始时间');
      normalizeOptionalTime(endTime, '结束时间');
    } catch (err) {
      Alert.alert('提示', err instanceof Error ? err.message : '请检查报工数据');
      return;
    }

    const remaining = Math.max(0, selectedTask.plannedQuantity - selectedTask.completedQuantity - selectedTask.pendingQuantity);
    const needsOverConfirm = remaining > 0 && qty > remaining * 1.5;
    const submitOrConfirmOver = () => {
      if (!needsOverConfirm) {
        void doSubmit(qty);
        return;
      }
      Alert.alert(
        '超量确认',
        `报工量 ${qty} 超过剩余量 ${remaining} 的150%，确定提交吗？`,
        [
          { text: '取消', style: 'cancel' },
          { text: '仍然提交', style: 'destructive', onPress: () => doSubmit(qty) },
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

  const doSubmit = async (qty: number) => {
    if (!selectedTask) return;
    setSubmitting(true);
    try {
      if (worker) {
        await processTaskApiClient.processCheckin({
          employeeId: worker.id,
          processName: selectedTask.processName,
          processCategory: selectedTask.processCategory,
          checkinMethod: 'QR_SCAN',
          processTaskId: selectedTask.id,
        });
      }

      const isSupplemental = selectedTask.status === 'SUPPLEMENTING' || selectedTask.status === 'COMPLETED' || selectedTask.status === 'CLOSED';
      const reporterName = worker?.name || '主管自己';
      const parsedWorkers = parseOptionalInteger(workersCount, '人数') ?? (worker ? 1 : undefined);
      const reportData: SubmitProcessReportPayload = {
        processTaskId: selectedTask.id,
        outputQuantity: qty,
        inputQuantity: parseOptionalNumber(inputQty, '投入数量'),
        totalWorkers: parsedWorkers,
        totalWorkMinutes: parseOptionalInteger(workMinutes, '工时分钟'),
        reportDate: normalizeOptionalDate(reportDate),
        productionStartTime: normalizeOptionalTime(startTime, '开始时间'),
        productionEndTime: normalizeOptionalTime(endTime, '结束时间'),
        reporterName,
        targetWorkerId: worker?.id,
        notes: notes || undefined,
      };

      let response: unknown;
      if (isSupplemental) {
        response = await processTaskApiClient.submitSupplement(reportData);
      } else {
        response = await processTaskApiClient.submitNormalReport(reportData);
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
        '报工成功',
        `${reporterName} — ${selectedTask.processName}\n产出: ${qty} ${selectedTask.unit || 'kg'}${evidenceMessage}${errorMessage}`,
        [{
          text: '继续报工', onPress: () => {
            // Reset to step 1 for next worker
            setWorker(null);
            setSelectedTask(null);
            setQuantity('');
            setInputQty('');
            setWorkersCount('');
            setWorkMinutes('');
            setStartTime('');
            setEndTime('');
            setNotes('');
            setEvidenceAssets([]);
            setStep(1);
            loadTasks();
          },
        }, {
          text: '返回', onPress: () => navigation.goBack(),
        }]
      );
    } catch (err) {
      Alert.alert('提交失败', err instanceof Error ? err.message : '请重试');
    } finally {
      setSubmitting(false);
    }
  };

  // Skip step 1 (supervisor reports for themselves)
  const handleSkipScan = () => {
    setWorker(null);
    setStep(2);
  };

  const remaining = selectedTask
    ? Math.max(0, selectedTask.plannedQuantity - selectedTask.completedQuantity - selectedTask.pendingQuantity)
    : 0;

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction testID="three-step-back" onPress={() => {
          if (step > 1) setStep(step - 1);
          else navigation.goBack();
        }} />
        <Appbar.Content
          title="报工"
          subtitle={`第 ${step} 步 / 共 3 步`}
          titleStyle={{ fontWeight: '600' }}
        />
      </Appbar.Header>

      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerStyle={styles.scrollContent}>

          {/* Step Indicator */}
          <View ref={tgtStepIndicator.ref} onLayout={tgtStepIndicator.onLayout} style={styles.stepIndicator}>
            {[1, 2, 3].map(s => (
              <React.Fragment key={s}>
                <TouchableOpacity
                  onPress={() => { if (s < step) setStep(s); }}
                  disabled={s >= step}
                  style={[styles.stepDot, s <= step && styles.stepDotActive, s === step && styles.stepDotCurrent]}
                >
                  <Text style={[styles.stepDotText, s <= step && styles.stepDotTextActive]}>
                    {s === 1 ? '扫人' : s === 2 ? '工序' : '报量'}
                  </Text>
                </TouchableOpacity>
                {s < 3 && <View style={[styles.stepLine, s < step && styles.stepLineActive]} />}
              </React.Fragment>
            ))}
          </View>

          {/* ==================== STEP 1: Scan Worker ==================== */}
          {step === 1 && (
            <Card ref={tgtScanArea.ref} onLayout={tgtScanArea.onLayout} style={styles.card}>
              <Card.Content>
                <Text variant="titleLarge" style={styles.stepTitle}>扫描员工工牌</Text>
                <Text style={styles.stepDesc}>请扫描报工员工的二维码工牌</Text>

                {scanLoading ? (
                  <ActivityIndicator size="large" style={{ marginVertical: 40 }} />
                ) : (
                  <View style={styles.scanArea}>
                    <NeoButton
                      testID="scan-worker-btn"
                      variant="primary"
                      onPress={() => setScannerVisible(true)}
                      style={styles.scanBtn}
                    >
                      扫描工牌
                    </NeoButton>
                    <TouchableOpacity onPress={handleSkipScan} style={styles.skipLink}>
                      <Text style={styles.skipText}>主管自己报工 (跳过)</Text>
                    </TouchableOpacity>
                  </View>
                )}
              </Card.Content>
            </Card>
          )}

          {/* ==================== STEP 2: Select Task ==================== */}
          {step === 2 && (
            <>
              {/* Worker badge (collapsible) */}
              {worker && (
                <Card style={[styles.card, { backgroundColor: '#f0f9ff' }]}>
                  <Card.Content style={styles.workerBadge}>
                    <Text style={styles.workerBadgeLabel}>报工员工</Text>
                    <Text style={styles.workerBadgeName}>{worker.name}</Text>
                    <TouchableOpacity onPress={() => setStep(1)}>
                      <Text style={styles.changeLink}>更换</Text>
                    </TouchableOpacity>
                  </Card.Content>
                </Card>
              )}

              <Card style={styles.card}>
                <Card.Content>
                  <Text variant="titleLarge" style={styles.stepTitle}>选择工序</Text>
                  <Text style={styles.stepDesc}>选择今天要报工的工序任务</Text>

                  {tasksLoading ? (
                    <ActivityIndicator size="large" style={{ marginVertical: 40 }} />
                  ) : tasks.length === 0 ? (
                    <Text style={styles.emptyText}>今天没有进行中的工序任务</Text>
                  ) : (
                    tasks.map(task => {
                      const prog = task.plannedQuantity > 0
                        ? Math.min((task.completedQuantity / task.plannedQuantity) * 100, 100)
                        : 0;
                      return (
                        <TouchableOpacity
                          key={task.id}
                          testID={`task-card-${task.id}`}
                          style={styles.taskCard}
                          onPress={() => handleSelectTask(task)}
                          activeOpacity={0.7}
                        >
                          <View style={styles.taskCardHeader}>
                            <Text style={styles.taskName}>{task.processName || '未命名工序'}</Text>
                            <Chip
                              compact
                              style={{ backgroundColor: (STATUS_COLORS[task.status] || '#909399') + '20' }}
                              textStyle={{ color: STATUS_COLORS[task.status] || '#909399', fontSize: 11 }}
                            >
                              {task.status === 'IN_PROGRESS' ? '进行中' : task.status === 'SUPPLEMENTING' ? '补报中' : task.status}
                            </Chip>
                          </View>
                          {task.productTypeName && (
                            <Text style={styles.taskProduct}>{task.productTypeName}</Text>
                          )}
                          <View style={styles.taskStats}>
                            <Text style={styles.taskStat}>计划: {task.plannedQuantity} {task.unit}</Text>
                            <Text style={[styles.taskStat, { color: '#67c23a' }]}>完成: {task.completedQuantity}</Text>
                            {task.pendingQuantity > 0 && (
                              <Text style={[styles.taskStat, { color: '#e6a23c' }]}>待审: {task.pendingQuantity}</Text>
                            )}
                          </View>
                          <View style={styles.taskProgress}>
                            <View style={styles.taskProgressTrack}>
                              <View style={[styles.taskProgressFill, { width: `${prog}%` }]} />
                            </View>
                            <Text style={styles.taskProgressText}>{prog.toFixed(0)}%</Text>
                          </View>
                        </TouchableOpacity>
                      );
                    })
                  )}
                </Card.Content>
              </Card>
            </>
          )}

          {/* ==================== STEP 3: Input Quantity ==================== */}
          {step === 3 && selectedTask && (
            <>
              {/* Collapsed summary of step 1 + 2 */}
              <Card style={[styles.card, { backgroundColor: '#f8f9fa' }]}>
                <Card.Content>
                  <View style={styles.summaryRow}>
                    <Text style={styles.summaryLabel}>员工</Text>
                    <Text style={styles.summaryValue}>{worker?.name || '主管自己'}</Text>
                  </View>
                  <Divider style={{ marginVertical: 6 }} />
                  <View style={styles.summaryRow}>
                    <Text style={styles.summaryLabel}>工序</Text>
                    <Text style={styles.summaryValue}>{selectedTask.processName}</Text>
                  </View>
                  {selectedTask.productTypeName && (
                    <>
                      <Divider style={{ marginVertical: 6 }} />
                      <View style={styles.summaryRow}>
                        <Text style={styles.summaryLabel}>产品</Text>
                        <Text style={styles.summaryValue}>{selectedTask.productTypeName}</Text>
                      </View>
                    </>
                  )}
                  <Divider style={{ marginVertical: 6 }} />
                  <View style={styles.summaryRow}>
                    <Text style={styles.summaryLabel}>剩余</Text>
                    <Text style={[styles.summaryValue, { color: remaining > 0 ? '#1890ff' : '#67c23a' }]}>
                      {remaining} {selectedTask.unit || 'kg'}
                    </Text>
                  </View>
                </Card.Content>
              </Card>

              {/* Quantity Input */}
              <Card style={styles.card}>
                <Card.Content>
                  <Text variant="titleLarge" style={styles.stepTitle}>报产量</Text>

                  <TextInput
                    testID="three-step-output-qty"
                    label={`产出数量 (${selectedTask.unit || 'kg'})`}
                    value={quantity}
                    onChangeText={setQuantity}
                    keyboardType="decimal-pad"
                    mode="outlined"
                    style={styles.input}
                    right={<TextInput.Affix text={selectedTask.unit || 'kg'} />}
                  />

                  {remaining > 0 && (
                    <View style={styles.quickButtons}>
                      <Text style={styles.quickLabel}>快捷:</Text>
                      {[remaining, Math.round(remaining / 2)].filter(v => v > 0).map(v => (
                        <NeoButton
                          key={v}
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
                    testID="three-step-input-qty"
                    label="投入数量 (可选)"
                    value={inputQty}
                    onChangeText={setInputQty}
                    keyboardType="decimal-pad"
                    mode="outlined"
                    style={[styles.input, { marginTop: 12 }]}
                  />

                  <View style={styles.twoCol}>
                    <TextInput
                      testID="three-step-workers"
                      label="人数"
                      value={workersCount}
                      onChangeText={setWorkersCount}
                      keyboardType="number-pad"
                      mode="outlined"
                      style={[styles.input, styles.colInput]}
                    />
                    <TextInput
                      testID="three-step-minutes"
                      label="工时分钟"
                      value={workMinutes}
                      onChangeText={setWorkMinutes}
                      keyboardType="number-pad"
                      mode="outlined"
                      style={[styles.input, styles.colInput]}
                    />
                  </View>

                  <TextInput
                    testID="three-step-report-date"
                    label="报工日期"
                    value={reportDate}
                    onChangeText={setReportDate}
                    mode="outlined"
                    placeholder="YYYY-MM-DD"
                    style={[styles.input, { marginTop: 12 }]}
                  />

                  <View style={styles.twoCol}>
                    <TextInput
                      testID="three-step-start-time"
                      label="开始时间"
                      value={startTime}
                      onChangeText={setStartTime}
                      mode="outlined"
                      placeholder="HH:mm"
                      style={[styles.input, styles.colInput]}
                    />
                    <TextInput
                      testID="three-step-end-time"
                      label="结束时间"
                      value={endTime}
                      onChangeText={setEndTime}
                      mode="outlined"
                      placeholder="HH:mm"
                      style={[styles.input, styles.colInput]}
                    />
                  </View>

                  <TextInput
                    testID="three-step-notes"
                    label="备注 (选填)"
                    value={notes}
                    onChangeText={setNotes}
                    mode="outlined"
                    multiline
                    numberOfLines={2}
                    style={[styles.input, { marginTop: 12 }]}
                  />
                </Card.Content>
              </Card>

              <Card style={styles.card}>
                <Card.Content>
                  <Text variant="titleLarge" style={styles.stepTitle}>现场证据</Text>
                  <View style={styles.evidenceActions}>
                    <NeoButton
                      testID="three-step-add-photo"
                      variant="outline"
                      size="small"
                      onPress={takeEvidencePhoto}
                      style={styles.evidenceBtn}
                    >
                      拍照
                    </NeoButton>
                    <NeoButton
                      testID="three-step-add-media"
                      variant="outline"
                      size="small"
                      onPress={pickEvidenceMedia}
                      style={styles.evidenceBtn}
                    >
                      照片/视频
                    </NeoButton>
                  </View>
                  {evidenceAssets.length === 0 ? (
                    <Text style={styles.evidenceHint}>建议上传现场照片；视频可用于核对关键工序。</Text>
                  ) : (
                    <View style={styles.evidenceList}>
                      {evidenceAssets.map((asset, index) => (
                        <Chip
                          key={`${asset.fileName}-${index}`}
                          testID={`three-step-evidence-chip-${index}`}
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
                testID="three-step-submit"
                variant="primary"
                onPress={handleSubmit}
                loading={submitting}
                disabled={submitting || !quantity}
                style={styles.submitBtn}
              >
                提交报工
              </NeoButton>
            </>
          )}
        </ScrollView>
      </KeyboardAvoidingView>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleWorkerScan}
      />
      <TutorialOverlay
        visible={tut.visible}
        steps={TUTORIAL_THREE_STEP.steps}
        currentStep={tut.step}
        onNext={tut.onNext}
        onSkip={tut.onSkip}
      />
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  scrollContent: { padding: 16, paddingBottom: 32 },

  // Step indicator
  stepIndicator: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', marginBottom: 16, paddingHorizontal: 20 },
  stepDot: {
    paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20,
    backgroundColor: '#e8e8e8', minWidth: 64, alignItems: 'center',
  },
  stepDotActive: { backgroundColor: theme.colors.primary + '20' },
  stepDotCurrent: { backgroundColor: theme.colors.primary, elevation: 2 },
  stepDotText: { fontSize: 13, fontWeight: '600', color: '#999' },
  stepDotTextActive: { color: theme.colors.primary },
  stepLine: { flex: 1, height: 2, backgroundColor: '#e8e8e8', marginHorizontal: 4 },
  stepLineActive: { backgroundColor: theme.colors.primary },

  // Cards
  card: { marginBottom: 12, borderRadius: 12, backgroundColor: '#fff', elevation: 2 },
  stepTitle: { fontWeight: '700', color: '#333', marginBottom: 4, fontSize: 22 },
  stepDesc: { fontSize: 16, color: '#888', marginBottom: 16 },

  // Step 1
  scanArea: { alignItems: 'center', paddingVertical: 24 },
  scanBtn: { width: '80%', height: 64 },
  skipLink: { marginTop: 16 },
  skipText: { color: '#888', fontSize: 14, textDecorationLine: 'underline' },

  // Worker badge
  workerBadge: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  workerBadgeLabel: { fontSize: 13, color: '#666' },
  workerBadgeName: { fontSize: 16, fontWeight: '700', color: '#1890ff', flex: 1 },
  changeLink: { color: '#1890ff', fontSize: 13 },

  // Step 2 — task cards
  taskCard: {
    backgroundColor: '#fafafa', borderRadius: 10, padding: 14,
    marginBottom: 10, borderWidth: 1, borderColor: '#eee',
  },
  taskCardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  taskName: { fontSize: 20, fontWeight: '700', color: '#333', flex: 1 },
  taskProduct: { fontSize: 15, color: '#666', marginTop: 2 },
  taskStats: { flexDirection: 'row', gap: 12, marginTop: 8 },
  taskStat: { fontSize: 15, color: '#666' },
  taskProgress: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 8 },
  taskProgressTrack: { flex: 1, height: 6, backgroundColor: '#e8e8e8', borderRadius: 3, overflow: 'hidden' },
  taskProgressFill: { height: '100%', backgroundColor: theme.colors.primary, borderRadius: 3 },
  taskProgressText: { fontSize: 12, color: '#999', width: 32, textAlign: 'right' },
  emptyText: { color: '#999', textAlign: 'center', paddingVertical: 40, fontSize: 15 },

  // Step 3 — summary + form
  summaryRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 4 },
  summaryLabel: { fontSize: 13, color: '#888' },
  summaryValue: { fontSize: 15, fontWeight: '600', color: '#333' },
  input: { backgroundColor: '#fff', fontSize: 18 },
  twoCol: { flexDirection: 'row', gap: 10, marginTop: 12 },
  colInput: { flex: 1, minWidth: 0 },
  quickButtons: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 10 },
  quickLabel: { fontSize: 13, color: '#666' },
  quickBtn: { minWidth: 64, minHeight: 44 },
  evidenceActions: { flexDirection: 'row', gap: 10, marginTop: 8 },
  evidenceBtn: { flex: 1, minHeight: 46 },
  evidenceHint: { color: '#666', fontSize: 14, lineHeight: 20, marginTop: 10 },
  evidenceList: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 },
  evidenceChip: { backgroundColor: '#f0f9ff' },
  submitBtn: { marginTop: 12, height: 56 },
});
