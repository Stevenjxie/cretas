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
  Alert, ActivityIndicator,
} from 'react-native';
import { Appbar, Chip } from 'react-native-paper';
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
  const [scanMode, setScanMode] = useState<'checkin' | 'checkout' | 'locate'>('checkin');

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
      }
    } catch (e) {
      Alert.alert('操作失败', e instanceof Error ? e.message : '请重试');
    }
  }, [scanMode, selectedTask, tasks, resolveEmployee]);

  // R8: 主操作 — 导航到三阶段报工屏 (栈A: YieldStepReportScreen)
  // Canonical task list guarantees both IDs. Missing linkage is an explicit data error.
  const handleNavigateToYieldReport = () => {
    if (!selectedTask) return;

    const wptId = selectedTask.workProcessTaskId;
    const batchId = selectedTask.batchId;

    if (!wptId || !batchId) {
      Alert.alert('不能报工', '当前工序任务缺少生产批次关联，请刷新后重试或联系管理员。');
      return;
    }

    navigation.navigate('YieldStepReport', {
      batchId,
      batchNumber: selectedTask.batchNumber ?? String(batchId),
      assignedWorkProcessTaskId: wptId,
      assignedProcessOrder: selectedTask.processOrder ?? undefined,
    });
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

      <View style={{ flex: 1 }}>
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

            <TouchableOpacity style={styles.submitBtn} onPress={handleNavigateToYieldReport}>
              <MaterialCommunityIcons name="clipboard-check-outline" size={20} color="#fff" style={{ marginRight: 6 }} />
              <Text style={styles.submitBtnText}>进入三阶段报工</Text>
            </TouchableOpacity>
          </View>
        </ScrollView>
      </View>

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
