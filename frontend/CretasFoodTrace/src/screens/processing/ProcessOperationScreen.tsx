/**
 * 工序操作 — 统一入口
 *
 * 合并签到/签退 + 报产量:
 * 1. 选工序任务
 * 2. 显示操作面板: 签到 / 签退 / 报产量
 */
import React, { useState, useCallback, useEffect } from 'react';
import {
  View, Text, StyleSheet, ScrollView, TouchableOpacity,
  Alert, ActivityIndicator, KeyboardAvoidingView, Platform,
} from 'react-native';
import { Appbar, TextInput, Chip } from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { TutorialOverlay } from '../../components/common/TutorialOverlay';
import { useTutorialStore, TUTORIAL_PROCESS_OPERATION, TUTORIAL_ENABLED, useTutorialTarget } from '../../store/tutorialStore';
import { processTaskApiClient, ProcessTaskItem } from '../../services/api/processTaskApiClient';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import { ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import { apiClient } from '../../services/api/apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type { ProcessingStackParamList } from '../../types/navigation';

interface ScannedWorker { id: number; name: string; }
type NavigationProp = NativeStackNavigationProp<ProcessingStackParamList>;
interface EmployeeLookupResponse {
  success: boolean;
  data?: {
    id: number;
    fullName?: string;
    username?: string;
  };
}
const SUPERVISOR_SELF: ScannedWorker = { id: -1, name: '主管自己' };

export default function ProcessOperationScreen() {
  const navigation = useNavigation<NavigationProp>();

  const [tasks, setTasks] = useState<ProcessTaskItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedTask, setSelectedTask] = useState<ProcessTaskItem | null>(null);

  // Tutorial
  const activeTutorial = useTutorialStore(s => s.activeTutorial);
  const activeStep = useTutorialStore(s => s.activeStep);
  const completedOp = useTutorialStore(s => s.completedTutorials[TUTORIAL_PROCESS_OPERATION.id]);
  const showTutorial = activeTutorial === TUTORIAL_PROCESS_OPERATION.id;

  // Spotlight targets
  const tgtTaskList = useTutorialTarget('po-task-list');
  const tgtAttendance = useTutorialTarget('po-attendance');
  const tgtReport = useTutorialTarget('po-report');

  useEffect(() => {
    if (TUTORIAL_ENABLED && !loading && !completedOp && activeTutorial === null) {
      const timer = setTimeout(() => {
        const s = useTutorialStore.getState();
        if (!s.activeTutorial && !s.completedTutorials[TUTORIAL_PROCESS_OPERATION.id]) {
          s.startTutorial(TUTORIAL_PROCESS_OPERATION.id);
        }
      }, 800);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [loading, completedOp]);

  // Re-measure when tutorial starts
  useEffect(() => {
    if (showTutorial) {
      setTimeout(() => {
        tgtTaskList.measure();
        tgtAttendance.measure();
        tgtReport.measure();
      }, 200);
    }
  }, [showTutorial]);

  // Scanner
  const [scannerVisible, setScannerVisible] = useState(false);
  const [scanMode, setScanMode] = useState<'checkin' | 'checkout' | 'report' | 'locate'>('checkin');
  const [worker, setWorker] = useState<ScannedWorker | null>(null);

  // Workers assigned to selected task (for picker)
  const [taskWorkers, setTaskWorkers] = useState<ScannedWorker[]>([]);
  const [workersLoading, setWorkersLoading] = useState(false);

  // Report form
  const [quantity, setQuantity] = useState('');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadTasks = useCallback(async () => {
    setLoading(true);
    try {
      const res = await processTaskApiClient.getActiveTasks();
      const data = res.data;
      const list = Array.isArray(data) ? data : data?.content || [];
      setTasks(list.filter((t: ProcessTaskItem) => t.status === 'IN_PROGRESS' || t.status === 'SUPPLEMENTING' || t.status === 'PENDING'));
    } catch { /* silent */ }
    finally { setLoading(false); }
  }, []);

  useFocusEffect(useCallback(() => { loadTasks(); }, [loadTasks]));

  // Load workers assigned to / checked into a task
  const loadTaskWorkers = useCallback(async (taskId: string) => {
    setWorkersLoading(true);
    setTaskWorkers([]);
    try {
      // Try getActiveCheckins first — these are workers actually on the floor
      const checkinsRes = await processTaskApiClient.getActiveCheckins();
      const checkins = Array.isArray(checkinsRes?.data) ? checkinsRes.data : [];
      const taskCheckins = checkins.filter((c) => String(c.processTaskId) === String(taskId));
      if (taskCheckins.length > 0) {
        const seen = new Set<number>();
        const workers: ScannedWorker[] = [];
        for (const c of taskCheckins) {
          if (!seen.has(c.employeeId)) {
            seen.add(c.employeeId);
            workers.push({ id: c.employeeId, name: c.employeeName || c.workerName || `员工#${c.employeeId}` });
          }
        }
        setTaskWorkers(workers);
      } else {
        // Fallback: try getWorkersByTask
        const res = await processTaskApiClient.getWorkersByTask(taskId);
        const list = Array.isArray(res?.data) ? res.data : [];
        setTaskWorkers(list.map((w: any) => ({ id: w.id || w.employeeId, name: w.name || w.fullName || `员工#${w.id}` })));
      }
    } catch { /* silent — worker list is optional */ }
    finally { setWorkersLoading(false); }
  }, []);

  // When a task is selected, load its workers
  useEffect(() => {
    if (selectedTask) loadTaskWorkers(String(selectedTask.id));
  }, [selectedTask, loadTaskWorkers]);

  // Parse scanned code — shared resolver
  const resolveEmployee = useCallback(async (raw: string): Promise<ScannedWorker | null> => {
    const factoryId = requireFactoryId();
    const nfcMatch = raw.match(/^CRETAS:EMP:(\d+):/);
    if (nfcMatch?.[1]) {
      return { id: parseInt(nfcMatch[1], 10), name: `员工#${nfcMatch[1]}` };
    }
    const res = await apiClient.get<EmployeeLookupResponse>(`/api/mobile/${factoryId}/users/by-employee-code/${encodeURIComponent(raw.trim())}`);
    if (res?.success && res.data) {
      return { id: res.data.id, name: res.data.fullName || res.data.username || `员工#${res.data.id}` };
    }
    const id = parseInt(raw, 10);
    if (!isNaN(id) && id > 0) return { id, name: `员工#${id}` };
    return null;
  }, []);

  const handleScan = useCallback(async (raw: string) => {
    setScannerVisible(false);
    try {
      const emp = await resolveEmployee(raw);
      if (!emp) { Alert.alert('未识别', '无法识别此工牌'); return; }

      if (scanMode === 'locate') {
        // Scan-to-locate: find which task this employee is checked into
        const checkinsRes = await processTaskApiClient.getActiveCheckins();
        const checkins = Array.isArray(checkinsRes?.data) ? checkinsRes.data : [];
        const match = checkins.find((c) => c.employeeId === emp.id);
        if (match) {
          const task = tasks.find(t => String(t.id) === String(match.processTaskId));
          if (task) {
            setSelectedTask(task);
            setWorker({ id: emp.id, name: match.employeeName || match.workerName || emp.name });
            return;
          }
        }
        Alert.alert('未找到', `${emp.name} 没有进行中的签到记录，请先签到`);
      } else if (scanMode === 'checkin') {
        await processTaskApiClient.processCheckin({
          employeeId: emp.id,
          processName: selectedTask?.processName,
          processCategory: selectedTask?.processCategory,
          checkinMethod: 'QR_SCAN',
          processTaskId: selectedTask?.id,
        });
        Alert.alert('签到成功', `${emp.name} 已签到「${selectedTask?.processName || '工序'}」`);
        if (selectedTask) loadTaskWorkers(String(selectedTask.id)); // refresh worker list
      } else if (scanMode === 'checkout') {
        const checkinsRes = await processTaskApiClient.getActiveCheckins();
        const checkins = Array.isArray(checkinsRes?.data) ? checkinsRes.data : [];
        const match = checkins.find((c) => c.employeeId === emp.id && String(c.processTaskId) === String(selectedTask?.id));
        if (match) {
          await processTaskApiClient.processCheckout(match.id);
          Alert.alert('签退成功', `${emp.name} 已签退「${selectedTask?.processName || '工序'}」`);
        } else {
          Alert.alert('未找到签到记录', `${emp.name} 未签到此工序`);
        }
      } else if (scanMode === 'report') {
        setWorker(emp);
      }
    } catch (e) {
      Alert.alert('操作失败', e instanceof Error ? e.message : '请重试');
    }
  }, [scanMode, selectedTask, tasks, resolveEmployee, loadTaskWorkers]);

  // R8: 主操作 — 导航到三阶段报工屏 (栈A: YieldStepReportScreen)
  // D2: 若当前 ProcessTask 没有关联的 WorkProcessTask, 明确提示用户, 不静默降级
  const handleNavigateToYieldReport = () => {
    if (!selectedTask) return;

    const wptId = selectedTask.workProcessTaskId;
    const batchId = selectedTask.batchId;

    if (!wptId || !batchId) {
      // D2: 显式提示 + 允许用户选择回退到旧路径
      Alert.alert(
        '提示',
        `该工序任务未关联工序实例 (workProcessTaskId=${wptId ?? '未知'})，无法进入三阶段报工。\n` +
        '将使用旧版快速报工，只能提交产出数量（无 T-3 时效锁和超收防呆）。',
        [
          { text: '取消', style: 'cancel' },
          { text: '使用旧版报工', onPress: () => setShowLegacyReportForm(true) },
        ],
      );
      return;
    }

    navigation.navigate('YieldStepReport', {
      batchId,
      batchNumber: selectedTask.batchNumber ?? String(batchId),
      assignedWorkProcessTaskId: wptId,
      assignedProcessOrder: selectedTask.processOrder ?? undefined,
    });
  };

  // R8: 次级入口 — 旧版快速报工 (submitNormalReport, 保留向后兼容)
  // 注: 后端端点已标注 @Deprecated, 此路径仅在 D2 明确提示后用户主动选择时触发
  const [showLegacyReportForm, setShowLegacyReportForm] = useState(false);

  const handleSubmitReport = async () => {
    if (!selectedTask) return;
    const qty = parseFloat(quantity);
    if (isNaN(qty) || qty <= 0) { Alert.alert('提示', '请输入有效的产出数量'); return; }

    setSubmitting(true);
    try {
      const data: any = {
        processTaskId: selectedTask.id,
        outputQuantity: qty,
        notes: notes || undefined,
      };
      if (worker && worker.id !== SUPERVISOR_SELF.id) {
        data.reporterName = worker.name;
        data.targetWorkerId = worker.id;
      }
      await processTaskApiClient.submitNormalReport(data);
      Alert.alert('报工成功', `${selectedTask.processName} — ${qty} ${selectedTask.unit || 'kg'}`, [
        { text: '继续', onPress: () => { setQuantity(''); setNotes(''); setWorker(null); setShowLegacyReportForm(false); } },
        { text: '返回', onPress: () => setSelectedTask(null) },
      ]);
    } catch (e) {
      Alert.alert('提交失败', e instanceof Error ? e.message : '请重试');
    } finally { setSubmitting(false); }
  };

  const remaining = selectedTask
    ? Math.max(0, selectedTask.plannedQuantity - selectedTask.completedQuantity - selectedTask.pendingQuantity)
    : 0;

  // ==================== STEP 1: Select Task ====================
  if (!selectedTask) {
    return (
      <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
        <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="工序操作" titleStyle={{ fontWeight: '600' }} />
          <Appbar.Action icon="qrcode-scan" onPress={() => { setScanMode('locate'); setScannerVisible(true); }} />
        </Appbar.Header>
        <ScrollView contentContainerStyle={styles.scrollContent}>
          <Text style={styles.hint}>选择要操作的工序</Text>
          {loading ? <ActivityIndicator size="large" style={{ marginTop: 40 }} /> :
            tasks.length === 0 ? <Text style={styles.empty}>暂无进行中的工序任务</Text> :
            tasks.map((task, idx) => {
              const prog = task.plannedQuantity > 0 ? Math.min((task.completedQuantity / task.plannedQuantity) * 100, 100) : 0;
              const card = (
                <TouchableOpacity key={task.id} style={styles.taskCard} onPress={() => setSelectedTask(task)} activeOpacity={0.7}>
                  <View style={styles.taskHeader}>
                    <Text style={styles.taskName}>{task.processName || '工序'}</Text>
                    <Chip compact style={{ backgroundColor: '#1890ff20' }} textStyle={{ color: '#1890ff', fontSize: 11 }}>
                      {task.status === 'IN_PROGRESS' ? '进行中' : task.status === 'PENDING' ? '待开始' : task.status}
                    </Chip>
                  </View>
                  {task.productTypeName && <Text style={styles.taskProduct}>{task.productTypeName}</Text>}
                  <View style={styles.taskStats}>
                    <Text style={styles.taskStat}>计划: {task.plannedQuantity} {task.unit}</Text>
                    <Text style={[styles.taskStat, { color: '#67c23a' }]}>完成: {task.completedQuantity}</Text>
                  </View>
                  <View style={styles.progressRow}>
                    <View style={styles.progressTrack}>
                      <View style={[styles.progressFill, { width: `${prog}%` }]} />
                    </View>
                    <Text style={styles.progressText}>{prog.toFixed(0)}%</Text>
                  </View>
                </TouchableOpacity>
              );
              // Spotlight wraps only the first card
              return idx === 0 ? (
                <View key={task.id} ref={tgtTaskList.ref} onLayout={tgtTaskList.onLayout} collapsable={false}>
                  {card}
                </View>
              ) : card;
            })
          }
        </ScrollView>
        <TutorialOverlay
          visible={showTutorial}
          steps={TUTORIAL_PROCESS_OPERATION.steps}
          currentStep={activeStep}
          onNext={() => useTutorialStore.getState().nextStep(TUTORIAL_PROCESS_OPERATION.steps.length)}
          onSkip={() => useTutorialStore.getState().skipTutorial()}
        />
      </ScreenWrapper>
    );
  }

  // ==================== STEP 2: Operation Panel ====================
  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => setSelectedTask(null)} />
        <Appbar.Content title={selectedTask.processName || '工序操作'} titleStyle={{ fontWeight: '600' }} />
      </Appbar.Header>

      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerStyle={styles.scrollContent}>
          {/* Task Summary */}
          <View style={styles.summaryCard}>
            <Text style={styles.summaryTitle}>{selectedTask.processName}</Text>
            {selectedTask.productTypeName && <Text style={styles.summaryProduct}>{selectedTask.productTypeName}</Text>}
            <View style={styles.summaryStats}>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryNum}>{selectedTask.plannedQuantity}</Text>
                <Text style={styles.summaryLabel}>计划 ({selectedTask.unit})</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: '#67c23a' }]}>{selectedTask.completedQuantity}</Text>
                <Text style={styles.summaryLabel}>已完成</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: '#1890ff' }]}>{remaining}</Text>
                <Text style={styles.summaryLabel}>剩余</Text>
              </View>
            </View>
          </View>

          {/* Attendance Buttons */}
          <View ref={tgtAttendance.ref} onLayout={tgtAttendance.onLayout} style={styles.attendanceSection}>
            <Text style={styles.sectionTitle}>员工签到/签退</Text>
            <View style={styles.attendanceRow}>
              <TouchableOpacity
                style={[styles.attendanceBtn, { backgroundColor: '#059669' }]}
                onPress={() => { setScanMode('checkin'); setScannerVisible(true); }}
              >
                <MaterialCommunityIcons name="login" size={22} color="#fff" />
                <Text style={styles.attendanceBtnText}>扫码签到</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.attendanceBtn, { backgroundColor: '#DC2626' }]}
                onPress={() => { setScanMode('checkout'); setScannerVisible(true); }}
              >
                <MaterialCommunityIcons name="logout" size={22} color="#fff" />
                <Text style={styles.attendanceBtnText}>扫码签退</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Report Form */}
          <View ref={tgtReport.ref} onLayout={tgtReport.onLayout} style={styles.reportSection}>
            <Text style={styles.sectionTitle}>报产量</Text>

            {/* R8 主操作: 三阶段报工 (栈A) */}
            {!showLegacyReportForm ? (
              <View>
                <TouchableOpacity
                  style={styles.submitBtn}
                  onPress={handleNavigateToYieldReport}
                >
                  <MaterialCommunityIcons name="clipboard-check-outline" size={20} color="#fff" style={{ marginRight: 6 }} />
                  <Text style={styles.submitBtnText}>进入三阶段报工</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.legacyReportBtn}
                  onPress={() => {
                    Alert.alert(
                      '旧版快速报工',
                      '旧版快速报工仅提交产出数量，无时效锁和超收防呆。建议使用三阶段报工确保数据完整。继续？',
                      [
                        { text: '取消', style: 'cancel' },
                        { text: '使用旧版', onPress: () => setShowLegacyReportForm(true) },
                      ],
                    );
                  }}
                >
                  <Text style={styles.legacyReportBtnText}>旧版快速报工</Text>
                </TouchableOpacity>
              </View>
            ) : (
              /* R8 次级入口: 旧版快速报工 (D4: 后端端点已标 @Deprecated, 永久保留) */
              <View>
                <View style={styles.legacyWarning}>
                  <MaterialCommunityIcons name="alert-outline" size={16} color="#faad14" />
                  <Text style={styles.legacyWarningText}>旧版快速报工 — 无时效锁，无超收防呆</Text>
                  <TouchableOpacity onPress={() => setShowLegacyReportForm(false)}>
                    <Text style={{ color: theme.colors.primary, fontSize: 13 }}>切换回三阶段</Text>
                  </TouchableOpacity>
                </View>

                {worker ? (
                  <View style={styles.workerBadge}>
                    <MaterialCommunityIcons name="account-check" size={18} color="#1890ff" />
                    <Text style={styles.workerLabel}>报工员工:</Text>
                    <Text style={styles.workerName}>{worker.name}</Text>
                    <TouchableOpacity onPress={() => setWorker(null)}>
                      <Text style={{ color: '#999', fontSize: 12 }}>更换</Text>
                    </TouchableOpacity>
                  </View>
                ) : (
                  <View>
                    {workersLoading ? (
                      <ActivityIndicator size="small" style={{ marginVertical: 8 }} />
                    ) : taskWorkers.length > 0 ? (
                      <View style={styles.workerPickerWrap}>
                        <Text style={styles.workerPickerLabel}>选择员工 (已签到)</Text>
                        <View style={styles.workerPickerGrid}>
                          {taskWorkers.map(w => (
                            <TouchableOpacity key={w.id} style={styles.workerPickerBtn} onPress={() => setWorker(w)}>
                              <MaterialCommunityIcons name="account" size={16} color="#1890ff" />
                              <Text style={styles.workerPickerName} numberOfLines={1}>{w.name}</Text>
                            </TouchableOpacity>
                          ))}
                        </View>
                      </View>
                    ) : (
                      <Text style={styles.noWorkerHint}>暂无已签到员工，可扫码或主管自己报工</Text>
                    )}
                    <View style={styles.workerActionsRow}>
                      <TouchableOpacity
                        style={styles.scanWorkerBtn}
                        onPress={() => { setScanMode('report'); setScannerVisible(true); }}
                      >
                        <MaterialCommunityIcons name="qrcode-scan" size={16} color={theme.colors.primary} />
                        <Text style={styles.scanWorkerText}>扫码工牌</Text>
                      </TouchableOpacity>
                      <TouchableOpacity style={styles.selfReportBtn} onPress={() => setWorker(SUPERVISOR_SELF)}>
                        <Text style={styles.selfReportText}>主管自己报</Text>
                      </TouchableOpacity>
                    </View>
                  </View>
                )}

                <TextInput
                  label={`产出数量 (${selectedTask.unit || 'kg'})`}
                  value={quantity}
                  onChangeText={setQuantity}
                  keyboardType="decimal-pad"
                  mode="outlined"
                  style={styles.input}
                  right={<TextInput.Affix text={selectedTask.unit || 'kg'} />}
                />

                {remaining > 0 && (
                  <View style={styles.quickBtns}>
                    <Text style={{ fontSize: 13, color: '#666' }}>快捷:</Text>
                    {[remaining, Math.round(remaining / 2)].filter(v => v > 0).map(v => (
                      <TouchableOpacity key={v} style={styles.quickBtn} onPress={() => setQuantity(String(v))}>
                        <Text style={styles.quickBtnText}>{v}</Text>
                      </TouchableOpacity>
                    ))}
                  </View>
                )}

                <TextInput
                  label="备注 (选填)"
                  value={notes}
                  onChangeText={setNotes}
                  mode="outlined"
                  multiline
                  numberOfLines={2}
                  style={[styles.input, { marginTop: 10 }]}
                />

                <TouchableOpacity
                  style={[styles.submitBtn, (!quantity || submitting) && { opacity: 0.5 }]}
                  onPress={handleSubmitReport}
                  disabled={!quantity || submitting}
                >
                  {submitting ? <ActivityIndicator color="#fff" /> :
                    <Text style={styles.submitBtnText}>提交报工</Text>
                  }
                </TouchableOpacity>
              </View>
            )}
          </View>
        </ScrollView>
      </KeyboardAvoidingView>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleScan}
      />

      <TutorialOverlay
        visible={showTutorial}
        steps={TUTORIAL_PROCESS_OPERATION.steps}
        currentStep={activeStep}
        onNext={() => useTutorialStore.getState().nextStep(TUTORIAL_PROCESS_OPERATION.steps.length)}
        onSkip={() => useTutorialStore.getState().skipTutorial()}
      />
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  scrollContent: { padding: 16, paddingBottom: 32 },
  hint: { fontSize: 15, color: '#888', marginBottom: 12 },
  empty: { fontSize: 15, color: '#999', textAlign: 'center', marginTop: 40 },

  // Task card (step 1)
  taskCard: { backgroundColor: '#fff', borderRadius: 12, padding: 14, marginBottom: 10, elevation: 2 },
  taskHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  taskName: { fontSize: 18, fontWeight: '700', color: '#333', flex: 1 },
  taskProduct: { fontSize: 14, color: '#666', marginTop: 2 },
  taskStats: { flexDirection: 'row', gap: 12, marginTop: 8 },
  taskStat: { fontSize: 14, color: '#666' },
  progressRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 8 },
  progressTrack: { flex: 1, height: 6, backgroundColor: '#e8e8e8', borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: theme.colors.primary, borderRadius: 3 },
  progressText: { fontSize: 12, color: '#999', width: 32, textAlign: 'right' },

  // Summary (step 2)
  summaryCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 16, elevation: 2 },
  summaryTitle: { fontSize: 20, fontWeight: '700', color: '#333' },
  summaryProduct: { fontSize: 14, color: '#666', marginTop: 2 },
  summaryStats: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 14 },
  summaryItem: { alignItems: 'center' },
  summaryNum: { fontSize: 24, fontWeight: '700', color: '#333' },
  summaryLabel: { fontSize: 13, color: '#999', marginTop: 2 },

  // Attendance
  attendanceSection: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 16, elevation: 2 },
  sectionTitle: { fontSize: 16, fontWeight: '600', color: '#333', marginBottom: 12 },
  attendanceRow: { flexDirection: 'row', gap: 12 },
  attendanceBtn: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, paddingVertical: 14, borderRadius: 10 },
  attendanceBtnText: { color: '#fff', fontSize: 16, fontWeight: '600' },

  // Report
  reportSection: { backgroundColor: '#fff', borderRadius: 12, padding: 16, elevation: 2 },
  workerBadge: { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#f0f9ff', padding: 10, borderRadius: 8, marginBottom: 12 },
  workerLabel: { fontSize: 13, color: '#666' },
  workerName: { fontSize: 15, fontWeight: '600', color: '#1890ff', flex: 1 },
  workerPickerWrap: { marginBottom: 12 },
  workerPickerLabel: { fontSize: 13, color: '#666', marginBottom: 8 },
  workerPickerGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  workerPickerBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 8, borderRadius: 20, backgroundColor: '#f0f9ff', borderWidth: 1, borderColor: '#bae0ff' },
  workerPickerName: { fontSize: 13, color: '#1890ff', fontWeight: '500', maxWidth: 80 },
  noWorkerHint: { fontSize: 13, color: '#999', marginBottom: 8 },
  workerActionsRow: { flexDirection: 'row', gap: 10, marginBottom: 12 },
  scanWorkerBtn: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8, borderWidth: 1, borderColor: '#e8e8e8' },
  scanWorkerText: { fontSize: 13, color: '#666' },
  selfReportBtn: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8, backgroundColor: '#f5f5f5' },
  selfReportText: { fontSize: 13, color: '#999' },
  input: { backgroundColor: '#fff', fontSize: 18 },
  quickBtns: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 8 },
  quickBtn: { paddingHorizontal: 16, paddingVertical: 6, borderRadius: 16, borderWidth: 1, borderColor: theme.colors.primary },
  quickBtnText: { color: theme.colors.primary, fontSize: 14, fontWeight: '600' },
  submitBtn: { backgroundColor: theme.colors.primary, paddingVertical: 14, borderRadius: 10, alignItems: 'center', marginTop: 16, flexDirection: 'row', justifyContent: 'center' },
  submitBtnText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  // R8: 旧版快速报工次级入口
  legacyReportBtn: { marginTop: 12, paddingVertical: 10, borderRadius: 8, borderWidth: 1, borderColor: '#d9d9d9', alignItems: 'center', backgroundColor: '#fafafa' },
  legacyReportBtnText: { color: '#888', fontSize: 14 },
  legacyWarning: { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#fffbe6', borderWidth: 1, borderColor: '#ffe58f', borderRadius: 8, padding: 10, marginBottom: 12 },
  legacyWarningText: { flex: 1, color: '#856404', fontSize: 13 },
});
