import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { View, StyleSheet, FlatList, Alert, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { useNavigation, CommonActions, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { salesApiClient, SalesOrder } from '../../../services/api/salesApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

type ApiActionError = {
  message?: string;
  response?: {
    data?: {
      message?: string;
      actionHint?: string;
    };
  };
};

const getActionErrorMessage = (error: unknown) => {
  const apiError = error as ApiActionError;
  const message = apiError.response?.data?.message || apiError.message || '操作失败';
  const actionHint = apiError.response?.data?.actionHint;
  return actionHint ? `${message}\n${actionHint}` : message;
};

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  CONFIRMED: { label: '已确认', color: '#409eff' },
  PENDING_FINANCE_REVIEW: { label: '待财务审核', color: '#e6a23c' },
  FINANCE_APPROVED: { label: '财务已批准', color: '#67c23a' },
  FINANCE_REJECTED: { label: '财务已驳回', color: '#f56c6c' },
  PROCESSING: { label: '处理中', color: '#e6a23c' },
  PARTIAL_DELIVERED: { label: '部分发货', color: '#f56c6c' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
};

export default function SalesOrderListScreen() {
  const navigation = useNavigation<Nav>();
  const [orders, setOrders] = useState<SalesOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [selectedOrder, setSelectedOrder] = useState<SalesOrder | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  const loadOrders = useCallback(async () => {
    try {
      const params: any = { page: 1, size: 50 };
      if (statusFilter !== 'all') params.status = statusFilter;
      const res = await salesApiClient.getOrders(params);
      if (res.success && res.data) {
        setOrders(res.data.content || []);
      }
    } catch { Alert.alert('错误', '加载销售单失败'); }
    finally { setLoading(false); setRefreshing(false); }
  }, [statusFilter]);

  useEffect(() => { loadOrders(); }, [loadOrders]);
  useFocusEffect(useCallback(() => { loadOrders(); }, [loadOrders]));
  const onRefresh = () => { setRefreshing(true); loadOrders(); };

  // U-FOOTER-1 (Track I): sticky footer summary, filter mirrors list filter
  const summaryRequest = useMemo(
    () => ({ filterConditions: statusFilter !== 'all' ? { status: statusFilter } : {} }),
    [statusFilter],
  );
  const { summary, refresh: refreshSummary } = useListSummary('salesOrder', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  const handleAction = async (orderId: string, action: string) => {
    try {
      let res;
      if (action === 'confirm') res = await salesApiClient.confirmOrder(orderId);
      else if (action === 'cancel') res = await salesApiClient.cancelOrder(orderId);
      if (res?.success) {
        const status = res.data?.status;
        const message = action === 'confirm' && status === 'FINANCE_APPROVED'
          ? '订单未触发审批阈值，已免审通过'
          : action === 'confirm' && status === 'PENDING_FINANCE_REVIEW'
            ? '订单已进入财务审核'
            : '操作成功';
        Alert.alert('成功', message);
        loadOrders();
      }
    } catch (error) {
      Alert.alert('错误', getActionErrorMessage(error));
      loadOrders();
    }
  };

  // UX-A2 (Track H): row-action bottom sheet
  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => navigation.navigate('SalesOrderDetail', { orderId: e.id }),
    submit: (e: RowContext) => handleAction(e.id, 'confirm'),
    cancel: (e: RowContext) => handleAction(e.id, 'cancel'),
    'print-pdf': (e: RowContext) => { void safePrint('sales-order', e.id); },
    copy: (e: RowContext) => Alert.alert('复制', `复制单据 ${e.id} (待接 API)`),
  }), [navigation]);

  const sheetCtx: RowContext = selectedOrder
    ? { status: selectedOrder.status, id: selectedOrder.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('salesOrder', sheetCtx, { handlers });

  const openSheet = (order: SalesOrder) => { setSelectedOrder(order); setActionSheetVisible(true); };

  const renderOrder = ({ item }: { item: SalesOrder }) => {
    const status = STATUS_MAP[item.status] || { label: item.status, color: '#909399' };
    // Sprint3-G S-LOCK-1: 行内 锁/备/缺. 缺 > 0 红, 销售员一眼判断催生产 / 紧急采购.
    const lockedQty = Number(item.lockedQty || 0);
    const reservedQty = Number(item.reservedQty || 0);
    const shortageQty = Number(item.shortageQty || 0);
    const hasShortage = shortageQty > 0;
    return (
      <Card style={styles.card}
        testID={`sales-order-card-${item.id}`}
        onPress={() => navigation.navigate('SalesOrderDetail', { orderId: item.id })}
        onLongPress={() => openSheet(item)}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleMedium" style={styles.orderNumber}>{item.orderNumber}</Text>
            <Chip style={[styles.chip, { backgroundColor: status.color + '20' }]} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>客户</Text>
            <Text style={styles.value}>{item.customerName || item.customerId}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>金额</Text>
            <Text style={[styles.value, styles.amount]}>¥{formatNumberWithCommas(item.totalAmount)}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>下单日期</Text>
            <Text style={styles.value}>{item.orderDate}</Text>
          </View>
          <View style={styles.lockReserveShortageRow}>
            <View style={[styles.lrsChip, styles.lrsLock]}>
              <Text style={styles.lrsText}>锁:{lockedQty}</Text>
            </View>
            <View style={[styles.lrsChip, styles.lrsReserve]}>
              <Text style={styles.lrsText}>备:{reservedQty}</Text>
            </View>
            <View style={[styles.lrsChip, hasShortage ? styles.lrsShortage : styles.lrsZero]}>
              <Text style={[styles.lrsText, hasShortage && styles.lrsTextShortage]}>缺:{shortageQty}</Text>
            </View>
          </View>
          {item.status === 'DRAFT' && (
            <View style={styles.actions}>
              <Button mode="contained" compact testID={`sales-order-confirm-${item.id}`} onPress={() => handleAction(item.id, 'confirm')} style={styles.actionBtn}>确认</Button>
              <Button mode="outlined" compact testID={`sales-order-cancel-${item.id}`} onPress={() => handleAction(item.id, 'cancel')} style={styles.actionBtn}>取消</Button>
            </View>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container} testID="sales-order-list-screen">
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="销售订单" />
        <Appbar.Action icon="plus" testID="sales-order-create-action" onPress={() => navigation.navigate('SalesOrderCreate')} />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <View style={styles.filterRow}>
        <SegmentedButtons
          value={statusFilter}
          onValueChange={setStatusFilter}
          buttons={[
            { value: 'all', label: '全部' },
            { value: 'DRAFT', label: '草稿' },
            { value: 'CONFIRMED', label: '已确认' },
            { value: 'COMPLETED', label: '已完成' },
          ]}
        />
      </View>

      <View style={styles.listWrap}>
        {loading ? (
          <ActivityIndicator style={styles.loader} size="large" />
        ) : (
          <FlatList
            testID="sales-order-list"
            data={orders}
            keyExtractor={item => item.id}
            renderItem={renderOrder}
            contentContainerStyle={styles.list}
            refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            ListEmptyComponent={<Text style={styles.empty}>暂无销售单</Text>}
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
              entityType: 'SALES_ORDER',
              initialMessage: `分析当前销售单列表${formatSummaryForAI(summary, { filter: { status: statusFilter } })}`,
            },
          }))
        }
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedOrder ? `销售单 ${selectedOrder.orderNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedOrder) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'SALES_ORDER', initialMessage: `${selectedOrder.orderNumber}: ` },
          }));
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  filterRow: { paddingHorizontal: 12, paddingVertical: 8, backgroundColor: '#fff' },
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
  amount: { fontWeight: '600', color: '#67c23a' },
  actions: { flexDirection: 'row', gap: 8, marginTop: 10, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#f0f0f0' },
  actionBtn: { flex: 1 },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
  // Sprint3-G S-LOCK-1: 锁/备/缺 chip — 行内显示, 缺 > 0 红色高亮
  lockReserveShortageRow: { flexDirection: 'row', gap: 6, marginTop: 8, paddingTop: 6, borderTopWidth: 1, borderTopColor: '#f0f0f0' },
  lrsChip: { flex: 1, paddingVertical: 3, paddingHorizontal: 8, borderRadius: 4, borderWidth: 1, alignItems: 'center' },
  lrsLock: { backgroundColor: '#f0f4ff', borderColor: '#cfd8e8' },
  lrsReserve: { backgroundColor: '#f0f9eb', borderColor: '#c2e7b0' },
  lrsZero: { backgroundColor: '#f4f4f5', borderColor: '#e9e9eb' },
  lrsShortage: { backgroundColor: '#fef0f0', borderColor: '#fbc4c4' },
  lrsText: { fontSize: 12, color: '#606266' },
  lrsTextShortage: { color: '#f56c6c', fontWeight: '600' },
});
