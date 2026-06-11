/**
 * SP6 采购异常决策屏 (Tier1 #36 — 异常决策屏)
 *
 * 采购员查看超收/少收异常单，做 ACCEPT_OVER/RETURN_OVER/ACCEPT_SHORT/REQUEST_RESUPPLY 决策。
 * 后端：PurchaseExceptionController GET /purchase-exceptions  POST /{id}/decide
 *
 * 角色：procurement_manager / factory_super_admin
 * 注册在：ProcurementManagerNavigator（PurchaseStack）
 *          FAManagementStackNavigator
 *
 * 防呆：
 *  Rule 2: 弹窗显示 采购单号/供应商/物料/异常类型/数量差（上下文，防错点）
 *  Rule 3: 决策原因 dropdown（4 标准决策选项，不让自由填）
 *  Rule 5: PENDING 列表为空时显示"暂无异常"next action 提示
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  FlatList,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Divider,
  RadioButton,
  Text,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { apiClient } from '../../../services/api/apiClient';
import { getFactoryId, FactoryIdStrategy } from '../../../utils/factoryIdHelper';
import { handleError } from '../../../utils/errorHandler';

// ─── Types ─────────────────────────────────────────────────────────────────

type ExceptionDecision =
  | 'ACCEPT_OVER'
  | 'RETURN_OVER'
  | 'ACCEPT_SHORT'
  | 'REQUEST_RESUPPLY';

type ExceptionStatus = 'PENDING' | 'RESOLVED';
type ExceptionType = 'OVER_DELIVERY' | 'SHORT_DELIVERY' | 'QUALITY_ISSUE' | 'WRONG_ITEM';

interface PurchaseException {
  id: string;
  exceptionNumber: string;
  purchaseOrderId: string;
  purchaseOrderNumber?: string;
  supplierId: string;
  supplierName?: string;
  materialName?: string;
  exceptionType: ExceptionType;
  orderedQuantity?: number | null;
  receivedQuantity?: number | null;
  varianceQuantity?: number | null;
  unit?: string | null;
  status: ExceptionStatus;
  decisionNote?: string | null;
  decision?: string | null;
  createdAt?: string | null;
  resolvedAt?: string | null;
  resolvedBy?: number | null;
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

// ─── Decision Options (Rule 3 防呆 — dropdown) ─────────────────────────────

const OVER_DECISIONS: { value: ExceptionDecision; label: string; desc: string }[] = [
  { value: 'ACCEPT_OVER', label: '接受超收', desc: '接收多余货品，调整采购量' },
  { value: 'RETURN_OVER', label: '退回超收', desc: '将超收部分退回供应商' },
];

const SHORT_DECISIONS: { value: ExceptionDecision; label: string; desc: string }[] = [
  { value: 'ACCEPT_SHORT', label: '接受少收', desc: '接受实际收货量，关闭差异' },
  { value: 'REQUEST_RESUPPLY', label: '要求补货', desc: '通知供应商补发缺少部分' },
];

// ─── Helpers ────────────────────────────────────────────────────────────────

const EXCEPTION_TYPE_LABEL: Record<ExceptionType, string> = {
  OVER_DELIVERY: '超收',
  SHORT_DELIVERY: '少收',
  QUALITY_ISSUE: '质量问题',
  WRONG_ITEM: '货品错误',
};

const EXCEPTION_TYPE_COLOR: Record<ExceptionType, string> = {
  OVER_DELIVERY: '#E65100',
  SHORT_DELIVERY: '#F57F17',
  QUALITY_ISSUE: '#C62828',
  WRONG_ITEM: '#6A1B9A',
};

function formatDate(s: string | null | undefined): string {
  if (!s) return '—';
  return s.slice(0, 10);
}

// ─── Component ──────────────────────────────────────────────────────────────

export default function PurchaseExceptionListScreen() {
  const navigation = useNavigation();
  const factoryId = getFactoryId(undefined, FactoryIdStrategy.REQUIRED);

  const [exceptions, setExceptions] = useState<PurchaseException[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [filterStatus, setFilterStatus] = useState<ExceptionStatus | ''>('PENDING');

  // Decision dialog state
  const [decideTarget, setDecideTarget] = useState<PurchaseException | null>(null);
  const [decision, setDecision] = useState<ExceptionDecision | ''>('');
  const [deciding, setDeciding] = useState(false);

  const fetchData = useCallback(async () => {
    try {
      const params = filterStatus ? `?status=${filterStatus}` : '';
      const res = await apiClient.get<ApiResponse<PurchaseException[]>>(
        `/api/mobile/${factoryId}/purchase-exceptions${params}`,
      );
      if (res.success) {
        setExceptions(res.data ?? []);
      } else {
        Alert.alert('加载失败', res.message || '请重试');
      }
    } catch (err) {
      handleError(err, { title: '加载采购异常列表失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId, filterStatus]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    fetchData();
  }, [fetchData]);

  // ─── Open Decision Dialog ─────────────────────────────────────────────────

  function openDecisionDialog(ex: PurchaseException) {
    // Pre-select sensible default
    const isOver = ex.exceptionType === 'OVER_DELIVERY';
    const defaultDecision: ExceptionDecision = isOver ? 'ACCEPT_OVER' : 'ACCEPT_SHORT';
    setDecision(defaultDecision);
    setDecideTarget(ex);
  }

  function closeDecisionDialog() {
    setDecideTarget(null);
    setDecision('');
  }

  async function doDecide() {
    if (!decideTarget || !decision) return;
    setDeciding(true);
    try {
      const res = await apiClient.post<ApiResponse<PurchaseException>>(
        `/api/mobile/${factoryId}/purchase-exceptions/${decideTarget.id}/decide`,
        { decision },
      );
      if (res.success) {
        Alert.alert('成功', '异常决策已提交');
        closeDecisionDialog();
        fetchData();
      } else {
        Alert.alert('操作失败', res.message || '请重试');
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      Alert.alert('决策失败', msg || '请检查权限或重试');
    } finally {
      setDeciding(false);
    }
  }

  // ─── Decision Dialog ──────────────────────────────────────────────────────

  function renderDecisionDialog() {
    if (!decideTarget) return null;
    const isOver = decideTarget.exceptionType === 'OVER_DELIVERY';
    const options = isOver ? OVER_DECISIONS : SHORT_DECISIONS;

    return (
      <View style={styles.dialogOverlay}>
        <View style={styles.dialogBox}>
          {/* Title (Rule 2: 上下文) */}
          <Text variant="titleMedium" style={styles.dialogTitle}>
            异常决策 — {decideTarget.exceptionNumber}
          </Text>

          {/* Context info */}
          <View style={styles.contextBlock}>
            <Text variant="bodySmall" style={styles.contextRow}>
              物料: {decideTarget.materialName || '—'}
            </Text>
            <Text variant="bodySmall" style={styles.contextRow}>
              采购单: {decideTarget.purchaseOrderNumber || decideTarget.purchaseOrderId}
            </Text>
            <Text variant="bodySmall" style={styles.contextRow}>
              异常: {EXCEPTION_TYPE_LABEL[decideTarget.exceptionType] || decideTarget.exceptionType}
              {decideTarget.varianceQuantity != null
                ? `  差异: ${decideTarget.varianceQuantity}${decideTarget.unit ?? ''}`
                : ''}
            </Text>
          </View>

          <Divider style={styles.divider} />

          {/* Decision options (Rule 3: 约束选择) */}
          <Text variant="labelLarge" style={styles.optionLabel}>选择处理方式</Text>
          <RadioButton.Group
            onValueChange={(v) => setDecision(v as ExceptionDecision)}
            value={decision}
          >
            {options.map((opt) => (
              <View key={opt.value} style={styles.radioRow}>
                <RadioButton value={opt.value} />
                <View style={styles.radioText}>
                  <Text variant="bodyMedium">{opt.label}</Text>
                  <Text variant="bodySmall" style={styles.radioDesc}>{opt.desc}</Text>
                </View>
              </View>
            ))}
          </RadioButton.Group>

          {/* Actions */}
          <View style={styles.dialogActions}>
            <Button
              mode="outlined"
              onPress={closeDecisionDialog}
              disabled={deciding}
              style={styles.dialogBtn}
            >
              取消
            </Button>
            <Button
              mode="contained"
              onPress={doDecide}
              loading={deciding}
              disabled={!decision || deciding}
              style={[styles.dialogBtn, { backgroundColor: '#1B65A8' }]}
            >
              确认决策
            </Button>
          </View>
        </View>
      </View>
    );
  }

  // ─── Render Item ──────────────────────────────────────────────────────────

  function renderItem({ item }: { item: PurchaseException }) {
    const typeColor = EXCEPTION_TYPE_COLOR[item.exceptionType] ?? '#666';
    const isPending = item.status === 'PENDING';
    return (
      <Card style={styles.card} elevation={1}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="labelMedium" style={styles.exceptionNumber}>
              {item.exceptionNumber}
            </Text>
            <Chip
              compact
              style={[styles.typeChip, { backgroundColor: typeColor + '22' }]}
              textStyle={{ color: typeColor, fontSize: 11 }}
            >
              {EXCEPTION_TYPE_LABEL[item.exceptionType] || item.exceptionType}
            </Chip>
          </View>

          <Text variant="titleSmall" style={styles.materialName}>
            {item.materialName || '—'}
          </Text>

          <Text variant="bodySmall" style={styles.subInfo}>
            采购单: {item.purchaseOrderNumber || item.purchaseOrderId}
          </Text>
          {item.supplierName && (
            <Text variant="bodySmall" style={styles.subInfo}>
              供应商: {item.supplierName}
            </Text>
          )}

          {item.varianceQuantity != null && (
            <Text variant="bodySmall" style={[styles.subInfo, { color: typeColor }]}>
              差异数量: {item.varianceQuantity > 0 ? '+' : ''}
              {item.varianceQuantity}{item.unit ?? ''}
              {item.orderedQuantity != null ? `（订: ${item.orderedQuantity}${item.unit ?? ''} 收: ${item.receivedQuantity ?? '—'}${item.unit ?? ''}）` : ''}
            </Text>
          )}

          <View style={styles.cardFooter}>
            <Text variant="bodySmall" style={styles.dateText}>
              创建: {formatDate(item.createdAt)}
            </Text>
            {isPending ? (
              <Button
                mode="contained"
                compact
                onPress={() => openDecisionDialog(item)}
                style={styles.decideButton}
                labelStyle={styles.decideButtonLabel}
              >
                处理异常
              </Button>
            ) : (
              <Chip compact style={styles.resolvedChip} textStyle={styles.resolvedText}>
                已处理: {item.decision || '—'}
              </Chip>
            )}
          </View>
        </Card.Content>
      </Card>
    );
  }

  // ─── Main Render ──────────────────────────────────────────────────────────

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="采购异常处理" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  const pendingCount = exceptions.filter((e) => e.status === 'PENDING').length;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="采购异常处理"
          subtitle={pendingCount > 0 ? `${pendingCount} 笔待决策` : '全部已处理'}
        />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      {/* Status filter */}
      <View style={styles.filterRow}>
        {(['PENDING', 'RESOLVED', ''] as const).map((s) => (
          <Chip
            key={s || 'ALL'}
            selected={filterStatus === s}
            onPress={() => setFilterStatus(s)}
            style={styles.filterChip}
            compact
          >
            {s === 'PENDING' ? '待处理' : s === 'RESOLVED' ? '已处理' : '全部'}
          </Chip>
        ))}
      </View>

      <FlatList
        data={exceptions}
        keyExtractor={(it) => it.id}
        renderItem={renderItem}
        contentContainerStyle={
          exceptions.length === 0 ? styles.emptyContainer : styles.listContent
        }
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyCenter}>
            <Text variant="bodyLarge" style={styles.emptyTitle}>暂无采购异常</Text>
            <Text variant="bodySmall" style={styles.emptySubtitle}>
              超收或少收的收货记录将自动生成异常单
            </Text>
          </View>
        }
      />

      {/* Decision dialog overlay */}
      {decideTarget && renderDecisionDialog()}
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

  filterRow: {
    flexDirection: 'row',
    paddingHorizontal: 12,
    paddingVertical: 8,
    gap: 8,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  filterChip: { marginRight: 4 },

  card: { marginBottom: 10, borderRadius: 10 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  exceptionNumber: { color: '#888', fontFamily: 'monospace' },
  typeChip: { height: 22 },
  materialName: { fontWeight: '600', color: '#333', marginBottom: 2 },
  subInfo: { color: '#666', marginBottom: 1 },
  divider: { marginVertical: 8 },
  cardFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 8,
  },
  dateText: { color: '#999' },
  decideButton: { backgroundColor: '#C62828', borderRadius: 6 },
  decideButtonLabel: { fontSize: 12 },
  resolvedChip: { backgroundColor: '#E8F5E9' },
  resolvedText: { color: '#2E7D32', fontSize: 11 },

  // Dialog overlay
  dialogOverlay: {
    position: 'absolute',
    top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    zIndex: 100,
  },
  dialogBox: {
    width: '100%',
    maxWidth: 400,
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 20,
    elevation: 8,
  },
  dialogTitle: { fontWeight: '700', marginBottom: 12, color: '#1B65A8' },
  contextBlock: {
    backgroundColor: '#F4F6F9',
    borderRadius: 8,
    padding: 10,
    marginBottom: 8,
  },
  contextRow: { color: '#444', marginBottom: 2 },
  optionLabel: { marginBottom: 8, color: '#333' },
  radioRow: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 8 },
  radioText: { flex: 1, paddingTop: 6 },
  radioDesc: { color: '#888', marginTop: 2 },
  dialogActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8, marginTop: 12 },
  dialogBtn: { minWidth: 80 },
});
