import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Alert,
  FlatList,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { workReportingApiClient } from '../../services/api/workReportingApiClient';
import { processingApiClient, type ProcessingBatch } from '../../services/api/processingApiClient';
import { processTaskApiClient, type ProcessTaskItem } from '../../services/api/processTaskApiClient';
import BarcodeScannerModal from '../../components/processing/BarcodeScannerModal';
import NfcCheckinModal from '../../components/processing/NfcCheckinModal';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useAuthStore } from '../../store/authStore';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';
import { isNfcModuleInstalled, isNfcAvailable } from '../../utils/nfcUtils';
import type { BatchWorkSessionResponse, CheckinWorkerDTO } from '../../types/workReporting';

type CheckinTab = 'nfc' | 'qr';

interface BatchItem {
  id: number;
  batchNumber: string;
  productName?: string;
  status?: string;
}

interface TaskItem {
  id: string;
  displayName: string;
  productName?: string;
  processName?: string;
  processCategory?: string;
  status?: string;
}

export default function NfcCheckinScreen() {
  const navigation = useNavigation();
  const insets = useSafeAreaInsets();
  const user = useAuthStore((s) => s.user);
  const getFactoryId = useAuthStore((s) => s.getFactoryId);
  const factoryId = getFactoryId();
  const isProcessMode = useFactoryFeatureStore((s) => s.isProcessMode);

  if (false && !factoryId) {
    return (
      <View style={[styles.container, { paddingTop: insets.top }]}>
        <View style={styles.emptyState}>
          <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#999" />
          <Text style={styles.emptyText}>请使用工厂账户登录后使用签到功能</Text>
        </View>
      </View>
    );
  }

  const [batches, setBatches] = useState<BatchItem[]>([]);
  const [selectedBatch, setSelectedBatch] = useState<BatchItem | null>(null);
  const [checkins, setCheckins] = useState<BatchWorkSessionResponse[]>([]);
  const [scannerVisible, setScannerVisible] = useState(false);
  const [nfcModalVisible, setNfcModalVisible] = useState(false);
  const [loading, setLoading] = useState(true);
  const [checkinLoading, setCheckinLoading] = useState(false);
  const [startingBatch, setStartingBatch] = useState(false);
  const [isFallback, setIsFallback] = useState(false);

  // PROCESS mode state
  const [processTasks, setProcessTasks] = useState<TaskItem[]>([]);
  const [selectedTask, setSelectedTask] = useState<TaskItem | null>(null);

  // NFC availability
  const [nfcSupported, setNfcSupported] = useState(false);
  const [activeTab, setActiveTab] = useState<CheckinTab>('nfc');

  // Check NFC availability on mount
  useEffect(() => {
    const checkNfc = async () => {
      if (!isNfcModuleInstalled()) {
        setNfcSupported(false);
        setActiveTab('qr');
        return;
      }
      const available = await isNfcAvailable();
      setNfcSupported(available);
      setActiveTab(available ? 'nfc' : 'qr');
    };
    checkNfc();
  }, []);

  useEffect(() => {
    if (isProcessMode()) {
      loadProcessTasks();
    } else {
      loadBatches();
    }
  }, []);

  const loadProcessTasks = useCallback(async () => {
    setLoading(true);
    try {
      const res = await processTaskApiClient.getActiveTasks(factoryId!) as { success?: boolean; data?: ProcessTaskItem[] };
      if (res?.success && Array.isArray(res.data)) {
        const tasks = res.data.map((t: ProcessTaskItem) => ({
          id: t.id,
          displayName: `${t.processName || '工序'} — ${t.productTypeName || t.productTypeId}`,
          productName: t.productTypeName,
          processName: t.processName,
          processCategory: t.processCategory,
          status: t.status,
        }));
        setProcessTasks(tasks);
        // 自动选中上次使用的工序
        const lastId = await AsyncStorage.getItem('lastProcessTaskId');
        if (lastId && !selectedTask) {
          const match = tasks.find((t: TaskItem) => t.id === lastId);
          if (match) setSelectedTask(match);
        }
      }
    } catch (error) {
      console.warn('Failed to load process tasks:', error);
    } finally {
      setLoading(false);
    }
  }, [factoryId]);

  const parseBatchList = (res: { success?: boolean; data?: { content?: ProcessingBatch[] } }): BatchItem[] => {
    if (!res?.success) return [];
    const content = res.data?.content || [];
    return content.map((b) => ({
      id: b.id,
      batchNumber: b.batchNumber,
      productName: b.productType || undefined,
      status: b.status || undefined,
    }));
  };

  const loadBatches = useCallback(async () => {
    setLoading(true);
    try {
      // 1. 查询分配给我的 IN_PROGRESS + PLANNED 批次
      const [inProgressRes, plannedRes] = await Promise.all([
        processingApiClient.getBatches({ status: 'IN_PROGRESS', supervisorId: user?.id }),
        processingApiClient.getBatches({ status: 'PLANNED', supervisorId: user?.id }),
      ]);

      const myBatches = [
        ...parseBatchList(inProgressRes),
        ...parseBatchList(plannedRes),
      ];

      if (myBatches.length > 0) {
        setBatches(myBatches);
        setIsFallback(false);
        const first = myBatches[0];
        if (myBatches.length === 1 && first && first.status === 'IN_PROGRESS') {
          selectBatch(first);
        }
      } else {
        // 2. Fallback: 查全工厂 IN_PROGRESS 批次
        const fallbackRes = await processingApiClient.getBatches({ status: 'IN_PROGRESS' });
        const fallbackBatches = parseBatchList(fallbackRes);
        setBatches(fallbackBatches);
        setIsFallback(fallbackBatches.length > 0);
        const firstFallback = fallbackBatches[0];
        if (fallbackBatches.length === 1 && firstFallback) {
          selectBatch(firstFallback);
        }
      }
    } catch (error) {
      console.warn('Failed to load batches:', error);
    } finally {
      setLoading(false);
    }
  }, [user?.id]);

  const loadCheckins = useCallback(async (batchId: number) => {
    try {
      const response = await workReportingApiClient.getCheckinList(batchId);
      if (response.success && response.data) {
        const workers = Array.isArray(response.data) ? response.data : [];
        // Map CheckinWorkerDTO to BatchWorkSessionResponse shape
        setCheckins(workers.map((w: CheckinWorkerDTO) => ({
          id: w.sessionId,
          batchId: w.batchId,
          employeeId: w.employeeId,
          checkInTime: w.checkInTime ?? undefined,
          checkOutTime: w.checkOutTime ?? undefined,
          status: w.status,
          checkinMethod: w.checkinMethod ?? undefined,
        })));
      }
    } catch (error) {
      console.warn('Failed to load checkins:', error);
    }
  }, []);

  const selectBatch = useCallback((batch: BatchItem) => {
    setSelectedBatch(batch);
    loadCheckins(batch.id);
  }, [loadCheckins]);

  const handleBatchPress = useCallback((batch: BatchItem) => {
    if (batch.status === 'PLANNED') {
      Alert.alert(
        '开始生产',
        `批次 ${batch.batchNumber} 尚未开始，需先开始生产才能签到。是否立即开始？`,
        [
          { text: '取消', style: 'cancel' },
          { text: '开始生产', onPress: () => startAndSelectBatch(batch) },
        ],
      );
    } else {
      selectBatch(batch);
    }
  }, [selectBatch]);

  const startAndSelectBatch = useCallback(async (batch: BatchItem) => {
    if (!user?.id) return;
    setStartingBatch(true);
    try {
      const res = await processingApiClient.startProduction(batch.id.toString(), user.id);
      if (res?.success) {
        const updated = { ...batch, status: 'IN_PROGRESS' };
        setBatches(prev => prev.map(b => b.id === batch.id ? updated : b));
        selectBatch(updated);
      } else {
        Alert.alert('开始失败', res?.message || '无法开始生产');
      }
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : '开始生产失败';
      Alert.alert('开始失败', msg);
    } finally {
      setStartingBatch(false);
    }
  }, [user?.id, selectBatch]);

  // --- Checkin handlers ---

  const performCheckin = useCallback(async (employeeId: number, method: 'NFC' | 'QR') => {
    if (!user?.id) return;

    if (isProcessMode()) {
      if (!selectedTask) return;
      setCheckinLoading(true);
      try {
        const response = await processTaskApiClient.processCheckin({
          employeeId,
          processName: selectedTask.processName,
          processCategory: selectedTask.processCategory,
          checkinMethod: method,
          processTaskId: selectedTask.id,
        });
        if (response.success) {
          const empName = response.data?.employeeName || `工号${employeeId}`;
          Alert.alert('签到成功', `${empName} 已签到「${selectedTask.processName || '工序'}」`);
          // 刷新签到列表（适配 ProcessCheckinRecord → BatchWorkSessionResponse 类型）
          try {
            const res = await processTaskApiClient.getActiveCheckins();
            if (res.success && Array.isArray(res.data)) {
              setCheckins(res.data.map((r: { id: number; employeeId: number; employeeName?: string; checkInTime?: string; checkOutTime?: string; status?: string; processName?: string }) => ({
                id: r.id,
                batchId: 0,
                employeeId: r.employeeId,
                employeeName: r.employeeName,
                checkInTime: r.checkInTime,
                checkOutTime: r.checkOutTime,
                status: r.status === 'CHECKED_IN' ? 'working' : 'finished',
                processName: r.processName,
              })));
            }
          } catch { /* silent */ }
        } else {
          Alert.alert('签到失败', response.message || '签到接口异常');
        }
      } catch (error: unknown) {
        const msg = error instanceof Error ? error.message : '签到失败';
        Alert.alert('签到失败', msg);
      } finally {
        setCheckinLoading(false);
      }
      return;
    }

    if (!selectedBatch) return;
    setCheckinLoading(true);
    try {
      const response = await workReportingApiClient.checkin({
        batchId: selectedBatch.id,
        employeeId,
        checkinMethod: method,
        assignedBy: user.id,
      });

      if (response.success) {
        Alert.alert('签到成功', `工号${employeeId} 已通过 ${method === 'NFC' ? 'NFC' : 'QR扫码'} 签到`);
        loadCheckins(selectedBatch.id);
      } else {
        Alert.alert('签到失败', response.message);
      }
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : '签到失败';
      Alert.alert('签到失败', msg);
    } finally {
      setCheckinLoading(false);
    }
  }, [selectedBatch, selectedTask, user, loadCheckins, isProcessMode]);

  // NFC tag read handler
  const handleNfcTagRead = useCallback((employeeId: string) => {
    setNfcModalVisible(false);
    const empId = parseInt(employeeId, 10);
    if (isNaN(empId)) {
      Alert.alert('NFC 错误', '无法解析员工编号');
      return;
    }
    performCheckin(empId, 'NFC');
  }, [performCheckin]);

  // QR scan handler
  const handleQrScan = useCallback((code: string) => {
    setScannerVisible(false);
    const employeeId = parseInt(code, 10);
    if (isNaN(employeeId)) {
      Alert.alert('扫码错误', '无法识别员工编号: ' + code);
      return;
    }
    performCheckin(employeeId, 'QR');
  }, [performCheckin]);

  const handleCheckout = useCallback(async (employeeId: number, checkinRecordId?: number) => {
    if (isProcessMode()) {
      if (!checkinRecordId) {
        Alert.alert('签退失败', '无签到记录ID');
        return;
      }
      try {
        const response = await processTaskApiClient.processCheckout(checkinRecordId);
        if (response.success) {
          Alert.alert('签退成功');
        } else {
          Alert.alert('签退失败', response.message || '签退接口异常');
        }
      } catch (error: unknown) {
        const msg = error instanceof Error ? error.message : '签退失败';
        Alert.alert('签退失败', msg);
      }
      return;
    }

    if (!selectedBatch) return;
    try {
      const response = await workReportingApiClient.checkout({
        batchId: selectedBatch.id,
        employeeId,
      });
      if (response.success) {
        Alert.alert('签退成功');
        loadCheckins(selectedBatch.id);
      } else {
        Alert.alert('签退失败', response.message);
      }
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : '签退失败';
      Alert.alert('签退失败', msg);
    }
  }, [selectedBatch, selectedTask, loadCheckins, isProcessMode]);

  // Fallback from NFC modal to QR
  const handleNfcFallbackToQR = useCallback(() => {
    setActiveTab('qr');
    setScannerVisible(true);
  }, []);

  // --- Render: Selection (Phase 1) ---

  if (!factoryId) {
    return (
      <View style={[styles.container, { paddingTop: insets.top }]}>
        <View style={styles.emptyState}>
          <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#999" />
          <Text style={styles.emptyText}>请使用工厂账号登录后使用签到功能</Text>
        </View>
      </View>
    );
  }

  const noSelection = isProcessMode() ? !selectedTask : !selectedBatch;

  if (noSelection) {
    if (isProcessMode()) {
      return (
        <View style={styles.container}>
          <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
            <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
              <MaterialCommunityIcons name="arrow-left" size={24} color="#1F2937" />
            </TouchableOpacity>
            <Text style={styles.headerTitle}>选择工序任务签到</Text>
            <View style={{ width: 40 }} />
          </View>

          {loading ? (
            <ActivityIndicator size="large" color="#4F46E5" style={{ marginTop: 100 }} />
          ) : processTasks.length === 0 ? (
            <View style={styles.emptyState}>
              <MaterialCommunityIcons name="clipboard-off-outline" size={48} color="#9CA3AF" />
              <Text style={styles.emptyText}>暂无活跃工序任务</Text>
              <Text style={styles.emptySubtext}>请联系调度员创建工序任务</Text>
            </View>
          ) : (
            <FlatList
              data={processTasks}
              keyExtractor={(item) => item.id}
              contentContainerStyle={{ padding: 16 }}
              renderItem={({ item }) => (
                <TouchableOpacity
                  style={styles.batchCard}
                  onPress={() => { setSelectedTask(item); AsyncStorage.setItem('lastProcessTaskId', item.id).catch(() => {}); }}
                >
                  <View style={styles.batchInfo}>
                    <Text style={styles.batchNumber}>{item.processName || '工序'}</Text>
                    {item.productName && (
                      <Text style={styles.batchProduct}>{item.productName}</Text>
                    )}
                  </View>
                  <View style={[styles.batchStatusBadge, styles.batchStatusActive]}>
                    <Text style={[styles.batchStatusText, styles.batchStatusActiveText]}>
                      {item.status === 'IN_PROGRESS' ? '进行中' : item.status === 'PENDING' ? '待开始' : '补报中'}
                    </Text>
                  </View>
                </TouchableOpacity>
              )}
            />
          )}
        </View>
      );
    }

    return (
      <View style={styles.container}>
        <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
          <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#1F2937" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>选择批次签到</Text>
          <View style={{ width: 40 }} />
        </View>

        {startingBatch && (
          <View style={styles.startingOverlay}>
            <ActivityIndicator size="large" color="#4F46E5" />
            <Text style={{ marginTop: 8, color: '#4F46E5' }}>正在开始生产...</Text>
          </View>
        )}
        {loading ? (
          <ActivityIndicator size="large" color="#4F46E5" style={{ marginTop: 100 }} />
        ) : batches.length === 0 ? (
          <View style={styles.emptyState}>
            <MaterialCommunityIcons name="clipboard-off-outline" size={48} color="#9CA3AF" />
            <Text style={styles.emptyText}>暂无可用批次</Text>
            <Text style={styles.emptySubtext}>请联系调度员分配生产批次</Text>
          </View>
        ) : (
          <FlatList
            data={batches}
            keyExtractor={(item) => item.id.toString()}
            contentContainerStyle={{ padding: 16 }}
            ListHeaderComponent={isFallback ? (
              <View style={styles.fallbackBanner}>
                <MaterialCommunityIcons name="information-outline" size={16} color="#92400E" />
                <Text style={styles.fallbackText}>当前显示全工厂批次（无分配给您的批次）</Text>
              </View>
            ) : null}
            renderItem={({ item }) => (
              <TouchableOpacity
                style={styles.batchCard}
                onPress={() => handleBatchPress(item)}
              >
                <View style={styles.batchInfo}>
                  <Text style={styles.batchNumber}>{item.batchNumber}</Text>
                  {item.productName && (
                    <Text style={styles.batchProduct}>{item.productName}</Text>
                  )}
                </View>
                <View style={[
                  styles.batchStatusBadge,
                  item.status === 'IN_PROGRESS' ? styles.batchStatusActive : styles.batchStatusPlanned,
                ]}>
                  <Text style={[
                    styles.batchStatusText,
                    item.status === 'IN_PROGRESS' ? styles.batchStatusActiveText : styles.batchStatusPlannedText,
                  ]}>
                    {item.status === 'IN_PROGRESS' ? '进行中' : '待开始'}
                  </Text>
                </View>
              </TouchableOpacity>
            )}
          />
        )}
      </View>
    );
  }

  // --- Render: Checkin management (Phase 2) ---

  return (
    <View style={styles.container}>
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <TouchableOpacity onPress={() => { isProcessMode() ? setSelectedTask(null) : setSelectedBatch(null); }} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color="#1F2937" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>
          {isProcessMode() ? (selectedTask?.displayName || '工序签到') : selectedBatch?.batchNumber}
        </Text>
        <View style={{ width: 40 }} />
      </View>

      {/* Stats bar */}
      <View style={styles.statsBar}>
        <View style={styles.statItem}>
          <Text style={styles.statValue}>{checkins.length}</Text>
          <Text style={styles.statLabel}>已签到</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={styles.statValue}>
            {checkins.filter((c) => c.status === 'working').length}
          </Text>
          <Text style={styles.statLabel}>工作中</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={styles.statValue}>
            {checkins.filter((c) => c.status === 'completed').length}
          </Text>
          <Text style={styles.statLabel}>已签退</Text>
        </View>
      </View>

      {/* Checkin method tabs */}
      <View style={styles.tabBar}>
        <TouchableOpacity
          style={[styles.tab, activeTab === 'nfc' && styles.activeTab]}
          onPress={() => setActiveTab('nfc')}
        >
          <MaterialCommunityIcons
            name="nfc"
            size={18}
            color={activeTab === 'nfc' ? '#4F46E5' : '#6B7280'}
          />
          <Text style={[styles.tabText, activeTab === 'nfc' && styles.activeTabText]}>
            NFC 签到
          </Text>
          {!nfcSupported && (
            <View style={styles.tabBadgeUnavailable}>
              <Text style={styles.tabBadgeText}>不可用</Text>
            </View>
          )}
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tab, activeTab === 'qr' && styles.activeTab]}
          onPress={() => setActiveTab('qr')}
        >
          <MaterialCommunityIcons
            name="qrcode-scan"
            size={18}
            color={activeTab === 'qr' ? '#4F46E5' : '#6B7280'}
          />
          <Text style={[styles.tabText, activeTab === 'qr' && styles.activeTabText]}>
            QR 扫码
          </Text>
        </TouchableOpacity>
      </View>

      {/* Scan button (based on active tab) */}
      {activeTab === 'nfc' ? (
        <TouchableOpacity
          style={[styles.scanBtn, styles.nfcScanBtn]}
          onPress={() => setNfcModalVisible(true)}
          disabled={checkinLoading}
        >
          {checkinLoading ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <>
              <MaterialCommunityIcons name="nfc-tap" size={24} color="#fff" />
              <Text style={styles.scanBtnText}>NFC 签到</Text>
            </>
          )}
        </TouchableOpacity>
      ) : (
        <TouchableOpacity
          style={styles.scanBtn}
          onPress={() => setScannerVisible(true)}
          disabled={checkinLoading}
        >
          {checkinLoading ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <>
              <MaterialCommunityIcons name="qrcode-scan" size={24} color="#fff" />
              <Text style={styles.scanBtnText}>扫码签到</Text>
            </>
          )}
        </TouchableOpacity>
      )}

      {/* Checkin list */}
      <ScrollView style={styles.checkinList}>
        {checkins.length === 0 ? (
          <Text style={styles.noCheckins}>
            暂无签到记录，请{activeTab === 'nfc' ? '使用 NFC' : '扫码'}签到
          </Text>
        ) : (
          checkins.map((session) => (
            <View key={session.id} style={styles.checkinCard}>
              <View style={styles.checkinInfo}>
                <Text style={styles.checkinName}>{session.employeeName || `工号${session.employeeId}`}</Text>
                <Text style={styles.checkinTime}>
                  {session.checkInTime ? session.checkInTime.substring(11, 16) : '--:--'}
                </Text>
                {/* Show checkin method badge */}
                {session.checkinMethod && (
                  <View style={[
                    styles.methodBadge,
                    session.checkinMethod === 'NFC' ? styles.nfcMethodBadge : styles.qrMethodBadge,
                  ]}>
                    <MaterialCommunityIcons
                      name={session.checkinMethod === 'NFC' ? 'nfc' : 'qrcode'}
                      size={10}
                      color={session.checkinMethod === 'NFC' ? '#4F46E5' : '#059669'}
                    />
                    <Text style={[
                      styles.methodBadgeText,
                      session.checkinMethod === 'NFC' ? styles.nfcMethodText : styles.qrMethodText,
                    ]}>
                      {session.checkinMethod}
                    </Text>
                  </View>
                )}
                <View style={[
                  styles.statusBadge,
                  session.status === 'working' ? styles.workingBadge : styles.completedBadge,
                ]}>
                  <Text style={styles.statusText}>
                    {session.status === 'working' ? '工作中' : '已签退'}
                  </Text>
                </View>
              </View>
              {session.status === 'working' && (
                <TouchableOpacity
                  style={styles.checkoutBtn}
                  onPress={() => handleCheckout(session.employeeId, session.id)}
                >
                  <Text style={styles.checkoutBtnText}>签退</Text>
                </TouchableOpacity>
              )}
            </View>
          ))
        )}
      </ScrollView>

      {/* NFC Checkin Modal */}
      <NfcCheckinModal
        visible={nfcModalVisible}
        onClose={() => setNfcModalVisible(false)}
        onTagRead={handleNfcTagRead}
        onFallbackToQR={handleNfcFallbackToQR}
        factoryId={factoryId}
      />

      {/* Barcode Scanner Modal (QR) */}
      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleQrScan}
      />
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
  emptyState: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  emptyText: { marginTop: 12, fontSize: 16, fontWeight: '600', color: '#9CA3AF' },
  emptySubtext: { marginTop: 4, fontSize: 14, color: '#D1D5DB' },
  fallbackBanner: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEF3C7', borderRadius: 8, padding: 10, marginBottom: 12, gap: 6 },
  fallbackText: { fontSize: 13, color: '#92400E', flex: 1 },
  startingOverlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(255,255,255,0.8)', justifyContent: 'center', alignItems: 'center', zIndex: 10 },
  batchStatusBadge: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, marginLeft: 8 },
  batchStatusText: { fontSize: 12, fontWeight: '600' },
  batchStatusActive: { backgroundColor: '#DBEAFE' },
  batchStatusActiveText: { color: '#1D4ED8' },
  batchStatusPlanned: { backgroundColor: '#FEF3C7' },
  batchStatusPlannedText: { color: '#92400E' },
  batchCard: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 10,
    borderWidth: 1, borderColor: '#E5E7EB',
  },
  batchInfo: { flex: 1 },
  batchNumber: { fontSize: 16, fontWeight: '600', color: '#1F2937' },
  batchProduct: { fontSize: 13, color: '#6B7280', marginTop: 4 },
  statsBar: {
    flexDirection: 'row', backgroundColor: '#fff', paddingVertical: 16,
    paddingHorizontal: 24, borderBottomWidth: 1, borderBottomColor: '#E5E7EB',
  },
  statItem: { flex: 1, alignItems: 'center' },
  statValue: { fontSize: 24, fontWeight: '700', color: '#4F46E5' },
  statLabel: { fontSize: 12, color: '#6B7280', marginTop: 4 },

  // Checkin method tabs
  tabBar: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#E5E7EB',
    gap: 8,
  },
  tab: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 10,
    borderRadius: 10,
    backgroundColor: '#F3F4F6',
    gap: 6,
  },
  activeTab: {
    backgroundColor: '#EEF2FF',
    borderWidth: 1,
    borderColor: '#C7D2FE',
  },
  tabText: {
    fontSize: 14,
    fontWeight: '500',
    color: '#6B7280',
  },
  activeTabText: {
    color: '#4F46E5',
    fontWeight: '600',
  },
  tabBadgeUnavailable: {
    backgroundColor: '#FEF3C7',
    borderRadius: 6,
    paddingHorizontal: 5,
    paddingVertical: 1,
  },
  tabBadgeText: {
    fontSize: 9,
    color: '#92400E',
    fontWeight: '600',
  },

  // Scan buttons
  scanBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    backgroundColor: '#4F46E5', margin: 16, borderRadius: 12, paddingVertical: 14, gap: 8,
  },
  nfcScanBtn: {
    backgroundColor: '#4338CA',
  },
  scanBtnText: { color: '#fff', fontSize: 16, fontWeight: '600' },

  checkinList: { flex: 1, paddingHorizontal: 16 },
  noCheckins: { textAlign: 'center', color: '#9CA3AF', marginTop: 40, fontSize: 14 },
  checkinCard: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    backgroundColor: '#fff', borderRadius: 10, padding: 14, marginBottom: 8,
    borderWidth: 1, borderColor: '#E5E7EB',
  },
  checkinInfo: { flexDirection: 'row', alignItems: 'center', gap: 8, flex: 1, flexWrap: 'wrap' },
  checkinName: { fontSize: 14, fontWeight: '500', color: '#1F2937' },
  checkinTime: { fontSize: 13, color: '#6B7280' },

  // Method badge (NFC / QR)
  methodBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
    gap: 3,
  },
  nfcMethodBadge: { backgroundColor: '#EEF2FF' },
  qrMethodBadge: { backgroundColor: '#ECFDF5' },
  methodBadgeText: { fontSize: 10, fontWeight: '600' },
  nfcMethodText: { color: '#4F46E5' },
  qrMethodText: { color: '#059669' },

  // Status badge
  statusBadge: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 10 },
  workingBadge: { backgroundColor: '#DBEAFE' },
  completedBadge: { backgroundColor: '#D1FAE5' },
  statusText: { fontSize: 11, fontWeight: '500', color: '#374151' },
  checkoutBtn: {
    backgroundColor: '#FEE2E2', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8,
  },
  checkoutBtnText: { color: '#DC2626', fontSize: 13, fontWeight: '500' },
});
