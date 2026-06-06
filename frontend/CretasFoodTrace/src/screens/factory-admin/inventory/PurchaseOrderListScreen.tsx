import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { View, StyleSheet, FlatList, Alert, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, FAB, Portal, Modal, TextInput, Button, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { purchaseApiClient, PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { useAuthStore } from '../../../store/authStore';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  SUBMITTED: { label: '已提交', color: '#e6a23c' },
  APPROVED: { label: '已审批', color: '#409eff' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
  PARTIAL_RECEIVED: { label: '部分收货', color: '#e6a23c' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
};

export default function PurchaseOrderListScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [selectedOrder, setSelectedOrder] = useState<PurchaseOrder | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  const loadOrders = useCallback(async () => {
    try {
      const params = { page: 1, size: 50 };
      let res;
      if (statusFilter !== 'all') {
        res = await purchaseApiClient.getOrdersByStatus(
          statusFilter as PurchaseOrder['status'],
          params
        );
      } else {
        res = await purchaseApiClient.getOrders(params);
      }
      if (res.success && res.data) {
        setOrders(res.data.content || []);
      }
    } catch (error) {
      Alert.alert('错误', '加载采购单失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [statusFilter]);

  useEffect(() => { loadOrders(); }, [loadOrders]);

  const onRefresh = () => { setRefreshing(true); loadOrders(); };

  // U-FOOTER-1: sticky footer summary, filter mirrors list filter
  const summaryRequest = useMemo(
    () => ({ filterConditions: statusFilter !== 'all' ? { status: statusFilter } : {} }),
    [statusFilter],
  );
  const { summary, refresh: refreshSummary } = useListSummary('purchaseOrder', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  const handleAction = async (orderId: string, action: string) => {
    try {
      let res;
      switch (action) {
        case 'submit': res = await purchaseApiClient.submitOrder(orderId); break;
        case 'approve': res = await purchaseApiClient.approveOrder(orderId); break;
        case 'reject': res = await purchaseApiClient.rejectOrder(orderId); break;
        case 'cancel': res = await purchaseApiClient.cancelOrder(orderId); break;
      }
      if (res?.success) {
        Alert.alert('成功', '操作成功');
        loadOrders();
      }
    } catch { Alert.alert('错误', '操作失败'); }
  };

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => navigation.navigate('PurchaseOrderDetail', { orderId: e.id }),
    submit: (e: RowContext) => handleAction(e.id, 'submit'),
    approve: (e: RowContext) => handleAction(e.id, 'approve'),
    reject: (e: RowContext) => handleAction(e.id, 'reject'),
    cancel: (e: RowContext) => handleAction(e.id, 'cancel'),
    'print-pdf': (e: RowContext) => { void safePrint('purchase-order', e.id); },
    copy: (e: RowContext) => Alert.alert('复制', `复制单据 ${e.id} (待接 API)`),
  }), [navigation]);

  const sheetCtx: RowContext = selectedOrder
    ? { status: selectedOrder.status, id: selectedOrder.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('purchaseOrder', sheetCtx, { handlers });

  const openSheet = (order: PurchaseOrder) => { setSelectedOrder(order); setActionSheetVisible(true); };

  const renderOrder = ({ item }: { item: PurchaseOrder }) => {
    const status = STATUS_MAP[item.status] || { label: item.status, color: '#909399' };
    return (
      <Card style={styles.card}
        onPress={() => navigation.navigate('PurchaseOrderDetail', { orderId: item.id })}
        onLongPress={() => openSheet(item)}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleMedium" style={styles.orderNumber}>{item.orderNumber}</Text>
            <Chip style={[styles.chip, { backgroundColor: status.color + '20' }]} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>供应商</Text>
            <Text style={styles.value}>{item.supplierName || item.supplierId}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>金额</Text>
            <Text style={[styles.value, styles.amount]}>¥{formatNumberWithCommas(item.totalAmount)}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>下单日期</Text>
            <Text style={styles.value}>{item.orderDate}</Text>
          </View>
          {item.status === 'DRAFT' && (
            <View style={styles.actions}>
              <Button mode="contained" compact onPress={() => handleAction(item.id, 'submit')} style={styles.actionBtn}>提交</Button>
              <Button mode="outlined" compact onPress={() => handleAction(item.id, 'cancel')} style={styles.actionBtn}>取消</Button>
            </View>
          )}
          {item.status === 'SUBMITTED' && (
            <View style={styles.actions}>
              <Button mode="contained" compact onPress={() => handleAction(item.id, 'approve')} style={styles.actionBtn}>审批</Button>
              <Button mode="outlined" compact textColor="#f56c6c" onPress={() => handleAction(item.id, 'reject')} style={styles.actionBtn}>驳回</Button>
            </View>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="采购订单" />
        <Appbar.Action icon="robot-outline" onPress={() => navigation.dispatch(CommonActions.navigate('FAAITab', { screen: 'AIChat', params: { entityType: 'PURCHASE', initialMessage: '我要创建采购订单' } }))} />
        <Appbar.Action
          testID="pm-procurement-confirm-btn"
          icon="truck-check"
          onPress={() => (navigation as Nav & { navigate: (name: string) => void }).navigate('ProcurementDeliveryConfirm')}
        />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <View style={styles.filterRow}>
        <SegmentedButtons
          value={statusFilter}
          onValueChange={setStatusFilter}
          buttons={[
            { value: 'all', label: '全部' },
            { value: 'DRAFT', label: '草稿' },
            { value: 'APPROVED', label: '已审批' },
            { value: 'COMPLETED', label: '已完成' },
          ]}
          style={styles.segmented}
        />
      </View>

      <View style={styles.listWrap}>
        {loading ? (
          <ActivityIndicator style={styles.loader} size="large" />
        ) : (
          <FlatList
            data={orders}
            keyExtractor={item => item.id}
            renderItem={renderOrder}
            contentContainerStyle={styles.list}
            refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            ListEmptyComponent={<Text style={styles.empty}>暂无采购单</Text>}
          />
        )}
      </View>

      <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: {
              entityType: 'PURCHASE',
              initialMessage: `分析当前采购单列表${formatSummaryForAI(summary, { filter: { status: statusFilter } })}`,
            },
          }))
        }
      />

      <FAB
        icon="plus"
        style={styles.fab}
        onPress={() => navigation.navigate('PurchaseOrderCreate')}
        label="新建采购单"
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedOrder ? `采购单 ${selectedOrder.orderNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedOrder) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'PURCHASE', initialMessage: `${selectedOrder.orderNumber}: ` },
          }));
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  filterRow: { paddingHorizontal: 12, paddingVertical: 8, backgroundColor: '#fff' },
  segmented: { },
  listWrap: { flex: 1 },
  loader: { flex: 1, justifyContent: 'center' },
  list: { padding: 12, paddingBottom: 80 },
  card: { marginBottom: 10, borderRadius: 10 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  orderNumber: { fontWeight: '700' },
  chip: { height: 26 },
  cardRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  label: { color: '#909399', fontSize: 13 },
  value: { fontSize: 13, color: '#333' },
  amount: { fontWeight: '600', color: '#e6a23c' },
  actions: { flexDirection: 'row', gap: 8, marginTop: 10, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#f0f0f0' },
  actionBtn: { flex: 1 },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
});
