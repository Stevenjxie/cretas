import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import { RouteProp, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Modal,
  Portal,
  Searchbar,
  Surface,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';

import { MaterialSelectModal, MaterialSelectResult } from '../../../components/MaterialSelectModal';
import {
  CustomerMaterialArrivalTask,
  warehouseReceivingApiClient,
} from '../../../services/api/warehouseReceivingApiClient';
import {
  FactoryWarehouseDTO,
  stocktakeApiClient,
} from '../../../services/api/stocktakeApiClient';
import { useAuthStore } from '../../../store/authStore';
import { WHInboundStackParamList } from '../../../types/navigation';
import { handleError } from '../../../utils/errorHandler';
import {
  buildReceiptPayload,
  createReceiptIdempotencyKey,
  UNORDERED_INBOUND_REASON_LABEL,
} from './unorderedInboundReceiving';

type Nav = NativeStackNavigationProp<WHInboundStackParamList, 'WHUnorderedInboundReceive'>;
type Route = RouteProp<WHInboundStackParamList, 'WHUnorderedInboundReceive'>;

export default function WHUnorderedInboundReceiveScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<Route>();
  const factoryId = useAuthStore((state) => state.user?.factoryId);

  const [task, setTask] = useState<CustomerMaterialArrivalTask | null>(null);
  const [warehouses, setWarehouses] = useState<FactoryWarehouseDTO[]>([]);
  const [selectedMaterial, setSelectedMaterial] = useState<MaterialSelectResult | null>(null);
  const [selectedWarehouse, setSelectedWarehouse] = useState<FactoryWarehouseDTO | null>(null);
  const [quantityText, setQuantityText] = useState('');
  const [externalBatchNumber, setExternalBatchNumber] = useState('');
  const [notes, setNotes] = useState('');
  const [completeNotice, setCompleteNotice] = useState(false);
  const [materialModalVisible, setMaterialModalVisible] = useState(false);
  const [warehouseModalVisible, setWarehouseModalVisible] = useState(false);
  const [warehouseSearch, setWarehouseSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const idempotencyKeyRef = useRef<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [taskResponse, warehouseResponse] = await Promise.all([
        warehouseReceivingApiClient.listCustomerMaterialArrivalTasks(
          { arrivalNoticeId: route.params.noticeId },
          factoryId,
        ),
        stocktakeApiClient.listWarehouses(factoryId),
      ]);
      const currentTask = taskResponse.data.find(
        (item) => item.sourceId === route.params.noticeId || item.taskId === route.params.noticeId,
      );
      if (!currentTask) {
        throw new Error('该预告已结束或已被其他人处理，请返回刷新待办');
      }
      setTask(currentTask);
      const availableWarehouses = warehouseResponse.data || [];
      setWarehouses(availableWarehouses);
      setSelectedWarehouse((current) => {
        if (current) return current;
        return availableWarehouses.find((warehouse) => warehouse.id === currentTask.warehouseId)
          || (availableWarehouses.length === 1 ? availableWarehouses[0] ?? null : null);
      });
    } catch (error) {
      handleError(error, { title: '加载无订单入库待办失败' });
    } finally {
      setLoading(false);
    }
  }, [factoryId, route.params.noticeId]);

  useEffect(() => {
    void load();
  }, [load]);

  const filteredWarehouses = useMemo(() => {
    const query = warehouseSearch.trim().toLowerCase();
    if (!query) return warehouses;
    return warehouses.filter((warehouse) =>
      warehouse.name.toLowerCase().includes(query)
      || warehouse.code.toLowerCase().includes(query),
    );
  }, [warehouseSearch, warehouses]);

  const submit = useCallback(async () => {
    if (!task) return;
    try {
      if (!idempotencyKeyRef.current) {
        idempotencyKeyRef.current = createReceiptIdempotencyKey(task.sourceId);
      }
      const payload = buildReceiptPayload(
        {
          noticeId: task.sourceId,
          materialTypeId: selectedMaterial?.materialTypeId || '',
          warehouseId: selectedWarehouse?.id || '',
          quantityText,
          unit: selectedMaterial?.defaultUnit || '',
          externalBatchNumber,
          notes,
          completeNotice,
        },
        idempotencyKeyRef.current,
      );

      setSubmitting(true);
      const response = await warehouseReceivingApiClient.receiveCustomerMaterialArrival(
        task.sourceId,
        payload,
        factoryId,
      );
      idempotencyKeyRef.current = null;
      const batchNumber = response.data.batchNumber ? `\n批次：${response.data.batchNumber}` : '';
      Alert.alert(
        '入库成功',
        completeNotice
          ? `本次入库已记录，预告已结束。${batchNumber}`
          : `本次入库已记录，预告继续保留给下一车或其他负责人。${batchNumber}`,
        [{ text: '返回待办', onPress: () => navigation.goBack() }],
      );
    } catch (error) {
      handleError(error, { title: '无订单入库失败' });
    } finally {
      setSubmitting(false);
    }
  }, [
    completeNotice,
    externalBatchNumber,
    factoryId,
    navigation,
    notes,
    quantityText,
    selectedMaterial,
    selectedWarehouse,
    task,
  ]);

  const confirmSubmit = useCallback(() => {
    if (!task) return;
    try {
      buildReceiptPayload(
        {
          noticeId: task.sourceId,
          materialTypeId: selectedMaterial?.materialTypeId || '',
          warehouseId: selectedWarehouse?.id || '',
          quantityText,
          unit: selectedMaterial?.defaultUnit || '',
          externalBatchNumber,
          notes,
          completeNotice,
        },
        'preview',
      );
    } catch (error) {
      Alert.alert('请补全信息', error instanceof Error ? error.message : '请检查入库信息');
      return;
    }

    Alert.alert(
      completeNotice ? '确认入库并结束预告？' : '确认本次部分入库？',
      [
        `预告：${task.sourceNumber}`,
        `客户：${task.customerName || '未指定客户'}`,
        `原料：${selectedMaterial?.materialName}`,
        `仓库：${selectedWarehouse?.name}`,
        `数量：${quantityText.trim()} ${selectedMaterial?.defaultUnit}`,
        completeNotice ? '结果：货已全部到齐，结束预告' : '结果：还有下一车，保留待办',
      ].join('\n'),
      [
        { text: '返回核对', style: 'cancel' },
        { text: completeNotice ? '确认并结束' : '确认入库', onPress: () => void submit() },
      ],
    );
  }, [
    completeNotice,
    externalBatchNumber,
    notes,
    quantityText,
    selectedMaterial,
    selectedWarehouse,
    submit,
    task,
  ]);

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="无订单入库" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.mutedText}>加载预告与仓库资料中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!task) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="无订单入库" />
        </Appbar.Header>
        <View style={styles.center}>
          <MaterialCommunityIcons name="clipboard-check-outline" size={56} color="#6B7280" />
          <Text style={styles.emptyTitle}>该预告当前不可收货</Text>
          <Text style={styles.mutedText}>可能已被其他人结束，请返回刷新待办。</Text>
          <Button mode="contained" onPress={() => navigation.goBack()} style={styles.retryButton}>
            返回待办
          </Button>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction disabled={submitting} onPress={() => navigation.goBack()} />
        <Appbar.Content title="无订单入库" subtitle={task.sourceNumber} />
      </Appbar.Header>

      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Card style={styles.summaryCard}>
          <Card.Content>
            <View style={styles.summaryHeader}>
              <Text variant="titleMedium" style={styles.summaryTitle}>{task.customerName || '未指定客户'}</Text>
              <Text style={styles.statusText}>{task.statusLabel}</Text>
            </View>
            <Text style={styles.summaryLine}>
              入库原因：{UNORDERED_INBOUND_REASON_LABEL[task.inboundReason] || task.inboundReason}
            </Text>
            {task.expectedArrivalAt && (
              <Text style={styles.summaryLine}>预计到货：{task.expectedArrivalAt.replace('T', ' ')}</Text>
            )}
            {task.activeReceiptCount > 0 && (
              <Text style={styles.handoffHint}>
                已有 {task.activeReceiptCount} 次收货记录，可以继续补录本次到货。
              </Text>
            )}
          </Card.Content>
        </Card>

        <Text style={styles.sectionTitle}>本次实际入库</Text>
        <Text style={styles.fieldLabel}>实际原料 *</Text>
        <TouchableOpacity
          accessibilityRole="button"
          accessibilityLabel="选择实际原料"
          onPress={() => setMaterialModalVisible(true)}
          style={styles.selector}
        >
          <View style={styles.selectorTextBlock}>
            <Text style={selectedMaterial ? styles.selectorValue : styles.selectorPlaceholder}>
              {selectedMaterial?.materialName || '搜索并选择原料'}
            </Text>
            {selectedMaterial && (
              <Text style={styles.selectorMeta}>单位：{selectedMaterial.defaultUnit}</Text>
            )}
          </View>
          <MaterialCommunityIcons name="chevron-right" size={22} color="#6B7280" />
        </TouchableOpacity>

        <Text style={styles.fieldLabel}>入库仓库 *</Text>
        <TouchableOpacity
          accessibilityRole="button"
          accessibilityLabel="选择入库仓库"
          onPress={() => setWarehouseModalVisible(true)}
          style={styles.selector}
        >
          <View style={styles.selectorTextBlock}>
            <Text style={selectedWarehouse ? styles.selectorValue : styles.selectorPlaceholder}>
              {selectedWarehouse?.name || '搜索并选择仓库'}
            </Text>
            {selectedWarehouse && <Text style={styles.selectorMeta}>{selectedWarehouse.code}</Text>}
          </View>
          <MaterialCommunityIcons name="chevron-right" size={22} color="#6B7280" />
        </TouchableOpacity>

        <TextInput
          mode="outlined"
          label="实收数量 *"
          value={quantityText}
          onChangeText={setQuantityText}
          keyboardType="decimal-pad"
          placeholder="最小 0.01，最多两位小数"
          right={<TextInput.Affix text={selectedMaterial?.defaultUnit || '请先选原料'} />}
          style={styles.input}
        />
        <TextInput
          mode="outlined"
          label="客户批次号（选填）"
          value={externalBatchNumber}
          onChangeText={setExternalBatchNumber}
          maxLength={100}
          style={styles.input}
        />
        <TextInput
          mode="outlined"
          label="本次备注（选填）"
          value={notes}
          onChangeText={setNotes}
          maxLength={500}
          multiline
          style={styles.input}
        />

        <Text style={styles.sectionTitle}>到货是否结束 *</Text>
        <TouchableOpacity
          accessibilityRole="radio"
          accessibilityState={{ checked: !completeNotice }}
          onPress={() => setCompleteNotice(false)}
          style={[styles.choiceCard, !completeNotice && styles.choiceCardSelected]}
        >
          <MaterialCommunityIcons
            name={!completeNotice ? 'radiobox-marked' : 'radiobox-blank'}
            size={24}
            color={!completeNotice ? '#1890FF' : '#6B7280'}
          />
          <View style={styles.choiceContent}>
            <Text style={styles.choiceTitle}>还有下一车，保留待办</Text>
            <Text style={styles.choiceHint}>本次先入库，仓管或管理员之后继续处理同一预告。</Text>
          </View>
        </TouchableOpacity>
        <TouchableOpacity
          accessibilityRole="radio"
          accessibilityState={{ checked: completeNotice }}
          onPress={() => setCompleteNotice(true)}
          style={[styles.choiceCard, completeNotice && styles.choiceCardSelected]}
        >
          <MaterialCommunityIcons
            name={completeNotice ? 'radiobox-marked' : 'radiobox-blank'}
            size={24}
            color={completeNotice ? '#1890FF' : '#6B7280'}
          />
          <View style={styles.choiceContent}>
            <Text style={styles.choiceTitle}>货已全部到齐，结束预告</Text>
            <Text style={styles.choiceHint}>结束后该预告从待收货任务中移除。</Text>
          </View>
        </TouchableOpacity>

        <Button
          mode="contained"
          icon="check-circle-outline"
          loading={submitting}
          disabled={submitting}
          onPress={confirmSubmit}
          style={styles.submitButton}
          contentStyle={styles.submitButtonContent}
        >
          核对并提交入库
        </Button>
        <Text style={styles.noQcHint}>本流程直接记录入库事实，不进入生产前质检。</Text>
      </ScrollView>

      <MaterialSelectModal
        visible={materialModalVisible}
        onDismiss={() => setMaterialModalVisible(false)}
        onSelect={setSelectedMaterial}
        title="选择实际入库原料"
      />

      <Portal>
        <Modal
          visible={warehouseModalVisible}
          onDismiss={() => setWarehouseModalVisible(false)}
          contentContainerStyle={styles.modal}
        >
          <View style={styles.modalHeader}>
            <Text variant="titleLarge">选择入库仓库</Text>
            <TouchableOpacity onPress={() => setWarehouseModalVisible(false)} hitSlop={12}>
              <MaterialCommunityIcons name="close" size={24} color="#6B7280" />
            </TouchableOpacity>
          </View>
          <Searchbar
            placeholder="搜索仓库名称或编码"
            value={warehouseSearch}
            onChangeText={setWarehouseSearch}
            style={styles.searchbar}
          />
          <FlatList
            data={filteredWarehouses}
            keyExtractor={(item) => item.id}
            ListEmptyComponent={<Text style={styles.emptyWarehouse}>没有匹配的仓库</Text>}
            renderItem={({ item }) => (
              <TouchableOpacity
                onPress={() => {
                  setSelectedWarehouse(item);
                  setWarehouseModalVisible(false);
                }}
              >
                <Surface style={styles.warehouseRow} elevation={0}>
                  <View>
                    <Text style={styles.warehouseName}>{item.name}</Text>
                    <Text style={styles.selectorMeta}>{item.code}</Text>
                  </View>
                  <MaterialCommunityIcons name="chevron-right" size={22} color="#6B7280" />
                </Surface>
              </TouchableOpacity>
            )}
          />
        </Modal>
      </Portal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  content: { padding: 16, paddingBottom: 32 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24 },
  mutedText: { color: '#6B7280', textAlign: 'center' },
  emptyTitle: { color: '#1F2937', fontSize: 17, fontWeight: '700' },
  retryButton: { marginTop: 8 },
  summaryCard: { backgroundColor: '#FFFFFF', marginBottom: 20 },
  summaryHeader: { flexDirection: 'row', justifyContent: 'space-between', gap: 8, marginBottom: 8 },
  summaryTitle: { color: '#1F2937', flex: 1, fontWeight: '700' },
  statusText: { color: '#D97706', fontWeight: '600' },
  summaryLine: { color: '#6B7280', lineHeight: 22 },
  handoffHint: { color: '#1565C0', backgroundColor: '#E3F2FD', padding: 10, borderRadius: 8, marginTop: 10 },
  sectionTitle: { color: '#1F2937', fontSize: 16, fontWeight: '700', marginBottom: 10, marginTop: 4 },
  fieldLabel: { color: '#374151', fontSize: 14, fontWeight: '600', marginBottom: 6 },
  selector: {
    minHeight: 56,
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#D1D5DB',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 10,
    marginBottom: 14,
    flexDirection: 'row',
    alignItems: 'center',
  },
  selectorTextBlock: { flex: 1 },
  selectorValue: { color: '#1F2937', fontSize: 15, fontWeight: '600' },
  selectorPlaceholder: { color: '#9CA3AF', fontSize: 15 },
  selectorMeta: { color: '#6B7280', fontSize: 12, marginTop: 3 },
  input: { backgroundColor: '#FFFFFF', marginBottom: 14 },
  choiceCard: {
    minHeight: 68,
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 10,
    padding: 14,
    marginBottom: 10,
  },
  choiceCardSelected: { borderColor: '#1890FF', backgroundColor: '#EFF6FF' },
  choiceContent: { flex: 1, marginLeft: 10 },
  choiceTitle: { color: '#1F2937', fontSize: 15, fontWeight: '700' },
  choiceHint: { color: '#6B7280', fontSize: 13, lineHeight: 19, marginTop: 3 },
  submitButton: { marginTop: 18, borderRadius: 8 },
  submitButtonContent: { minHeight: 50 },
  noQcHint: { color: '#6B7280', fontSize: 12, textAlign: 'center', marginTop: 10 },
  modal: { backgroundColor: '#FFFFFF', margin: 16, borderRadius: 16, maxHeight: '82%', paddingBottom: 8 },
  modalHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 16 },
  searchbar: { marginHorizontal: 12, marginBottom: 8, backgroundColor: '#F5F5F5' },
  warehouseRow: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
    backgroundColor: '#FFFFFF',
    borderTopWidth: 1,
    borderTopColor: '#E5E7EB',
  },
  warehouseName: { color: '#1F2937', fontSize: 15, fontWeight: '600' },
  emptyWarehouse: { color: '#6B7280', textAlign: 'center', padding: 32 },
});
