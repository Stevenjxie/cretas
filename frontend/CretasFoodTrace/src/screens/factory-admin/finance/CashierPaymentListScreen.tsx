/**
 * SP6 出纳付款屏 (Tier1 #36)
 *
 * 出纳查看已审批待付款列表，逐笔确认付款。
 * 使用 D-9 /approved 端点（PaymentRequestApprovedDTO），显示供应商名/PO单号/原料明细/金额/结算方式。
 *
 * 角色：finance_manager / factory_super_admin（持有 finance:read_write 权限）
 * 注册在：FAManagementStackNavigator（factory_super_admin 路径）
 *          ProcurementManagerNavigator（procurement_manager 路径，兼具财务付款职能）
 *
 * 防呆：
 *  Rule 1: 卡片显示待付金额 + 供应商 + PO单号（上限已由后端锁定，APPROVED 状态不会超付）
 *  Rule 2: 确认弹窗重复展示 申请单号/供应商/金额，防误操作
 *  Rule 4: 重复点击 mark-paid 后端返 409，前端 catch 提示已付款
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  FlatList,
  RefreshControl,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Badge,
  Button,
  Card,
  Chip,
  Divider,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { apiClient } from '../../../services/api/apiClient';
import { getFactoryId, FactoryIdStrategy } from '../../../utils/factoryIdHelper';
import { handleError } from '../../../utils/errorHandler';

// ─── Types ─────────────────────────────────────────────────────────────────

interface ItemLine {
  itemId: number;
  materialName: string;
  quantity: number | null;
  unit: string | null;
  unitPrice: number | null;  // @PriceSensitive — may be null for non-finance roles
  lineAmount: number | null;
  specification: string | null;
}

interface PaymentRequestApprovedDTO {
  id: string;
  requestNumber: string;
  purchaseOrderId: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierName: string;
  amount: number | null;  // @PriceSensitive
  settlementType: string | null;
  settlementTypeDisplayName: string | null;
  paymentMethod: string | null;
  bankName: string | null;
  bankAccount: string | null;
  approvedAt: string | null;
  approvedBy: number | null;
  remark: string | null;
  status: string;
  createdBy: number | null;
  createdAt: string | null;
  items: ItemLine[];
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

// ─── Helpers ────────────────────────────────────────────────────────────────

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '—';
  const fixed = v.toFixed(2);
  const [rawInt = '0', decimals = '00'] = fixed.split('.');
  const signed = rawInt.startsWith('-');
  const digits = signed ? rawInt.slice(1) : rawInt;
  const intPart = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `\u00a5${signed ? '-' : ''}${intPart}.${decimals}`;
}

function formatDate(s: string | null | undefined): string {
  if (!s) return '—';
  return s.slice(0, 10);
}

const SETTLEMENT_LABEL: Record<string, string> = {
  PREPAID: '预付款',
  CREDIT_FIRST: '先货后款',
  NO_INVOICE: '无票结算',
  MONTHLY: '月结',
  CREDIT_PERIOD: '信用期',
  IMMEDIATE: '即时付款',
};

// ─── Component ──────────────────────────────────────────────────────────────

export default function CashierPaymentListScreen() {
  const navigation = useNavigation();
  const factoryId = getFactoryId(undefined, FactoryIdStrategy.REQUIRED);

  const [items, setItems] = useState<PaymentRequestApprovedDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [paying, setPaying] = useState<string | null>(null);  // requestId being paid

  const fetchData = useCallback(async () => {
    try {
      const res = await apiClient.get<ApiResponse<PaymentRequestApprovedDTO[]>>(
        `/api/mobile/${factoryId}/payment-requests/approved`,
      );
      if (res.success) {
        setItems(res.data ?? []);
      } else {
        Alert.alert('加载失败', res.message || '请重试');
      }
    } catch (err) {
      handleError(err, { title: '加载出纳付款列表失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    fetchData();
  }, [fetchData]);

  // ─── Mark Paid ───────────────────────────────────────────────────────────

  function confirmMarkPaid(item: PaymentRequestApprovedDTO) {
    Alert.alert(
      `确认付款 — ${item.requestNumber}`,
      `供应商: ${item.supplierName}\n采购单: ${item.purchaseOrderNumber}\n金额: ${formatAmount(item.amount)}\n\n确认已完成付款？`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认付款',
          style: 'default',
          onPress: () => doMarkPaid(item),
        },
      ],
    );
  }

  async function doMarkPaid(item: PaymentRequestApprovedDTO) {
    setPaying(item.id);
    try {
      const res = await apiClient.put<ApiResponse<unknown>>(
        `/api/mobile/${factoryId}/payment-requests/${item.id}/mark-paid`,
        {},
      );
      if (res.success) {
        Alert.alert('成功', '付款已确认，账款已更新');
        fetchData();
      } else {
        Alert.alert('操作失败', res.message || '请重试');
      }
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number; data?: { message?: string } } })?.response?.status;
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      if (status === 409) {
        Alert.alert('重复操作', msg || '该付款申请已付款，请刷新列表');
        fetchData();
      } else {
        handleError(err, { title: '付款确认失败' });
      }
    } finally {
      setPaying(null);
    }
  }

  // ─── Render ──────────────────────────────────────────────────────────────

  function renderItem({ item }: { item: PaymentRequestApprovedDTO }) {
    const isPaying = paying === item.id;
    return (
      <Card style={styles.card} elevation={1}>
        <Card.Content>
          {/* 头部：申请单号 + 结算方式 */}
          <View style={styles.cardHeader}>
            <Text variant="labelMedium" style={styles.requestNumber}>
              {item.requestNumber}
            </Text>
            {item.settlementTypeDisplayName ? (
              <Chip compact style={styles.chip}>
                {item.settlementTypeDisplayName}
              </Chip>
            ) : null}
          </View>

          {/* 供应商 + PO单号 */}
          <Text variant="titleMedium" style={styles.supplierName}>
            {item.supplierName}
          </Text>
          <Text variant="bodySmall" style={styles.poNumber}>
            采购单: {item.purchaseOrderNumber}
          </Text>

          {/* 原料明细（防呆：让出纳核实明细与金额匹配） */}
          {item.items && item.items.length > 0 && (
            <View style={styles.itemsSection}>
              <Divider style={styles.divider} />
              {item.items.slice(0, 3).map((line) => (
                <View key={line.itemId} style={styles.lineRow}>
                  <Text variant="bodySmall" style={styles.lineLeft}>
                    {line.materialName}
                    {line.specification ? ` (${line.specification})` : ''}
                  </Text>
                  <Text variant="bodySmall" style={styles.lineRight}>
                    {line.quantity != null ? `${line.quantity}${line.unit ?? ''}` : '—'}
                    {line.lineAmount != null ? `  ${formatAmount(line.lineAmount)}` : ''}
                  </Text>
                </View>
              ))}
              {item.items.length > 3 && (
                <Text variant="bodySmall" style={styles.moreItems}>
                  +{item.items.length - 3} 项原料
                </Text>
              )}
            </View>
          )}

          <Divider style={styles.divider} />

          {/* 金额 + 操作按钮 */}
          <View style={styles.footer}>
            <View>
              <Text variant="bodySmall" style={styles.amountLabel}>待付金额</Text>
              <Text variant="titleLarge" style={styles.amount}>
                {formatAmount(item.amount)}
              </Text>
              <Text variant="bodySmall" style={styles.dateText}>
                批准时间: {formatDate(item.approvedAt)}
              </Text>
            </View>
            <Button
              mode="contained"
              onPress={() => confirmMarkPaid(item)}
              loading={isPaying}
              disabled={isPaying || paying !== null}
              style={styles.payButton}
            >
              确认付款
            </Button>
          </View>
        </Card.Content>
      </Card>
    );
  }

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="出纳付款" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="出纳付款" subtitle={`${items.length} 笔待付款`} />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <FlatList
        data={items}
        keyExtractor={(it) => it.id}
        renderItem={renderItem}
        contentContainerStyle={
          items.length === 0 ? styles.emptyContainer : styles.listContent
        }
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyCenter}>
            <Text variant="bodyLarge" style={styles.emptyTitle}>暂无待付款申请</Text>
            <Text variant="bodySmall" style={styles.emptySubtitle}>
              所有已审批的付款申请将显示在这里
            </Text>
          </View>
        }
      />
    </SafeAreaView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  loadingText: { marginTop: 8, color: '#666' },
  listContent: { padding: 12, paddingBottom: 24 },
  emptyContainer: { flexGrow: 1, padding: 24 },
  emptyCenter: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: 60 },
  emptyTitle: { color: '#666', marginBottom: 8 },
  emptySubtitle: { color: '#999', textAlign: 'center' },

  card: { marginBottom: 12, borderRadius: 10 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  requestNumber: { color: '#888', fontFamily: 'monospace' },
  chip: { height: 24 },
  supplierName: { fontWeight: '700', color: '#1B65A8', marginBottom: 2 },
  poNumber: { color: '#666', marginBottom: 6 },

  itemsSection: { marginVertical: 4 },
  divider: { marginVertical: 8 },
  lineRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 2,
  },
  lineLeft: { flex: 1, color: '#444' },
  lineRight: { color: '#666', textAlign: 'right' },
  moreItems: { color: '#999', textAlign: 'right', marginTop: 2 },

  footer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
    marginTop: 4,
  },
  amountLabel: { color: '#666' },
  amount: { color: '#C62828', fontWeight: '700' },
  dateText: { color: '#999', marginTop: 2 },
  payButton: { backgroundColor: '#1B65A8', borderRadius: 8 },
});
