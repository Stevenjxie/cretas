/**
 * OA 待办详情屏 (R3)
 *
 * 大额单（needDetail=true）的审批入口：
 *   1. 按 type 拉取域详情
 *   2. 展示完整数据（防呆 Rule 2）
 *   3. 底部 通过 / 驳回 固定操作栏
 *   4. 驳回走 dropdown + 其他→textarea（防呆 Rule 3）
 *   5. 409 → 已被处理 + 返回（防呆 Rule 4）
 *
 * @since 2026-06-12 (OA 待办 R3)
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  Modal,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Divider,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import {
  TodoType,
  REJECT_REASON_OPTIONS,
  RejectReasonOption,
  todoApprovalApiClient,
} from '../../services/api/myTodoApiClient';
import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';
import { apiClient } from '../../services/api/apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import type { OATodoStackParamList } from '../../types/navigation';

// ──────────────────────────────────────────────────────────────────────────────
// Route types
// ──────────────────────────────────────────────────────────────────────────────

type NavProp = NativeStackNavigationProp<OATodoStackParamList, 'TodoDetail'>;
type RouteType = RouteProp<OATodoStackParamList, 'TodoDetail'>;

// ──────────────────────────────────────────────────────────────────────────────
// Detail data (loose shape — each domain differs)
// ──────────────────────────────────────────────────────────────────────────────

type DetailData = Record<string, unknown>;

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

function formatAmount(v: unknown): string {
  if (v == null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(String(v));
  if (isNaN(n)) return String(v);
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatValue(v: unknown): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

/** 字段标签中文化 */
const FIELD_LABELS: Record<string, string> = {
  orderNumber: '订单号',
  purchaseOrderNumber: '采购单号',
  supplierName: '供应商',
  customerName: '客户',
  totalAmount: '总金额',
  amount: '金额',
  status: '状态',
  submittedBy: '申请人',
  submittedAt: '提交时间',
  createdAt: '创建时间',
  notes: '备注',
  periodMonth: '盘点周期',
  stocktakeNo: '盘点单号',
  warehouseId: '仓库ID',
  counterparty: '对方',
  deliveryNoteNumber: '送货单号',
  anomalyType: '异常类型',
  expectedPrice: '期望价格',
  actualPrice: '实际价格',
  requestNumber: '付款申请号',
  purchaseOrderId: '采购单ID',
  settlementTypeDisplayName: '结算方式',
  approvedAt: '审批时间',
};

function labelFor(key: string): string {
  return FIELD_LABELS[key] ?? key;
}

/** 按 type 获取详情 API 路径 */
function getDetailPath(type: TodoType, refId: string, factoryId: string): string {
  switch (type) {
    case 'PURCHASE_FINANCE_REVIEW':
      return `/api/mobile/${factoryId}/purchase/orders/${refId}`;
    case 'SALES_FINANCE_REVIEW':
      return `/api/mobile/${factoryId}/sales/orders/${refId}`;
    case 'PRICE_ANOMALY':
      return `/api/mobile/${factoryId}/supplier-delivery-notes/${refId}`;
    case 'STOCKTAKE_APPROVAL':
      return `/api/mobile/${factoryId}/stocktakes/${refId}`;
    case 'PAYMENT_DISBURSE':
      return `/api/mobile/${factoryId}/payment-requests/${refId}`;
    default:
      return '';
  }
}

// Key fields to highlight at top (防呆 Rule 2: 大额单必须展示完整 context)
const TOP_KEYS_BY_TYPE: Record<TodoType, string[]> = {
  PURCHASE_FINANCE_REVIEW: ['orderNumber', 'supplierName', 'totalAmount', 'submittedBy', 'notes'],
  SALES_FINANCE_REVIEW: ['orderNumber', 'customerName', 'totalAmount', 'submittedBy', 'notes'],
  PRICE_ANOMALY: ['deliveryNoteNumber', 'supplierName', 'anomalyType', 'expectedPrice', 'actualPrice'],
  STOCKTAKE_APPROVAL: ['stocktakeNo', 'periodMonth', 'submittedBy', 'notes'],
  PAYMENT_DISBURSE: ['requestNumber', 'purchaseOrderNumber', 'supplierName', 'amount', 'settlementTypeDisplayName'],
};

// Fields to skip (too verbose or shown elsewhere)
const SKIP_KEYS = new Set(['id', 'factoryId', 'items', 'createdBy', 'approvedBy', 'updatedAt']);

// ──────────────────────────────────────────────────────────────────────────────
// RejectModal (same pattern as list screen)
// ──────────────────────────────────────────────────────────────────────────────

interface RejectModalProps {
  visible: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

function RejectModal({ visible, onCancel, onConfirm }: RejectModalProps) {
  const [selected, setSelected] = useState<RejectReasonOption | null>(null);
  const [custom, setCustom] = useState('');

  function handleConfirm() {
    if (!selected) {
      appAlert('请选择驳回原因');
      return;
    }
    const reason = selected === '其他' ? custom.trim() : selected;
    if (selected === '其他' && !reason) {
      appAlert('请填写驳回原因', '选择"其他"时需填写具体原因');
      return;
    }
    onConfirm(reason);
    setSelected(null);
    setCustom('');
  }

  function handleCancel() {
    setSelected(null);
    setCustom('');
    onCancel();
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={handleCancel}>
      <View style={modalStyles.overlay}>
        <View style={modalStyles.sheet}>
          <Text style={modalStyles.sheetTitle}>选择驳回原因</Text>
          <Divider style={{ marginBottom: 12 }} />
          <ScrollView style={modalStyles.reasonList}>
            {REJECT_REASON_OPTIONS.map((opt) => (
              <TouchableOpacity
                key={opt}
                style={[
                  modalStyles.reasonItem,
                  selected === opt && modalStyles.reasonItemSelected,
                ]}
                onPress={() => setSelected(opt)}
              >
                <View style={modalStyles.radioOuter}>
                  {selected === opt && <View style={modalStyles.radioInner} />}
                </View>
                <Text style={modalStyles.reasonText}>{opt}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
          {selected === '其他' && (
            <TextInput
              mode="outlined"
              label="请详细说明原因"
              value={custom}
              onChangeText={setCustom}
              multiline
              numberOfLines={3}
              style={modalStyles.textarea}
            />
          )}
          <View style={modalStyles.buttonRow}>
            <Button mode="outlined" onPress={handleCancel} style={modalStyles.btnHalf}>
              取消
            </Button>
            <Button
              mode="contained"
              onPress={handleConfirm}
              buttonColor="#C62828"
              style={modalStyles.btnHalf}
            >
              确认驳回
            </Button>
          </View>
        </View>
      </View>
    </Modal>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Detail Row (simple key-value display)
// ──────────────────────────────────────────────────────────────────────────────

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.detailRow}>
      <Text variant="bodySmall" style={styles.detailLabel}>{label}</Text>
      <Text variant="bodyMedium" style={styles.detailValue}>{value}</Text>
    </View>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Main Screen
// ──────────────────────────────────────────────────────────────────────────────

export default function TodoDetailScreen() {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RouteType>();
  const { refId, type, title } = route.params;

  const [detail, setDetail] = useState<DetailData | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actioning, setActioning] = useState(false);
  const [rejectVisible, setRejectVisible] = useState(false);

  const factoryId = getCurrentFactoryId();

  // ─── Fetch detail ──────────────────────────────────────────────────────────

  const fetchDetail = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const path = getDetailPath(type as TodoType, refId, factoryId ?? '');
      if (!path) {
        setLoadError(`不支持的待办类型: ${type}`);
        return;
      }
      const resp = await apiClient.get<{ success: boolean; data: DetailData; message?: string }>(path);
      if (resp.success) {
        setDetail(resp.data);
      } else {
        setLoadError(resp.message ?? '加载详情失败');
      }
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message ?? '网络错误，请稍后重试';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, [refId, type, factoryId]);

  useEffect(() => {
    void fetchDetail();
  }, [fetchDetail]);

  // ─── Approve ──────────────────────────────────────────────────────────────

  async function handleApprove() {
    appAlert(
      `确认通过审批`,
      `标题: ${title}\n单号: ${(detail?.orderNumber ?? detail?.stocktakeNo ?? detail?.requestNumber ?? refId) as string}`,
      [
        { text: '取消', style: 'cancel' },
        { text: '确认通过', onPress: doApprove },
      ],
    );
  }

  async function doApprove() {
    setActioning(true);
    try {
      let resp: { success: boolean; message?: string };
      const todoType = type as TodoType;
      switch (todoType) {
        case 'PURCHASE_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.purchaseFinanceApprove(refId);
          break;
        case 'SALES_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.salesFinanceApprove(refId);
          break;
        case 'PRICE_ANOMALY':
          resp = await todoApprovalApiClient.priceAnomalyApprove(refId);
          break;
        case 'STOCKTAKE_APPROVAL':
          resp = await todoApprovalApiClient.stocktakeApprove(refId);
          break;
        case 'PAYMENT_DISBURSE':
          resp = await todoApprovalApiClient.paymentMarkPaid(refId);
          break;
        default:
          appAlert('不支持的操作', `未知类型: ${type}`);
          setActioning(false);
          return;
      }

      if (resp.success) {
        appAlert('操作成功', resp.message ?? '审批已通过', [
          { text: '返回列表', onPress: () => navigation.goBack() },
        ]);
      } else {
        appAlert('操作失败', resp.message ?? '请稍后重试');
      }
    } catch (err: unknown) {
      const axiosErr = err as { response?: { status?: number; data?: { message?: string } } };
      const status = axiosErr?.response?.status;
      const msg = axiosErr?.response?.data?.message;
      if (status === 409) {
        appAlert('已被处理', msg ?? '该待办已被其他人处理', [
          { text: '返回列表', onPress: () => navigation.goBack() },
        ]);
      } else {
        appAlert('操作失败', msg ?? '网络错误，请稍后重试');
      }
    } finally {
      setActioning(false);
    }
  }

  // ─── Reject ──────────────────────────────────────────────────────────────

  async function doReject(reason: string) {
    setRejectVisible(false);
    setActioning(true);
    try {
      let resp: { success: boolean; message?: string };
      const todoType = type as TodoType;
      switch (todoType) {
        case 'PURCHASE_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.purchaseFinanceReject(refId, reason);
          break;
        case 'SALES_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.salesFinanceReject(refId, reason);
          break;
        case 'PRICE_ANOMALY':
          resp = await todoApprovalApiClient.priceAnomalyReject(refId, reason);
          break;
        case 'STOCKTAKE_APPROVAL':
          resp = await todoApprovalApiClient.stocktakeReject(refId, reason);
          break;
        case 'PAYMENT_DISBURSE':
          appAlert('不支持驳回', '付款确认操作不支持驳回');
          setActioning(false);
          return;
        default:
          appAlert('不支持的操作', `未知类型: ${type}`);
          setActioning(false);
          return;
      }

      if (resp.success) {
        appAlert('操作成功', resp.message ?? '已驳回', [
          { text: '返回列表', onPress: () => navigation.goBack() },
        ]);
      } else {
        appAlert('操作失败', resp.message ?? '请稍后重试');
      }
    } catch (err: unknown) {
      const axiosErr = err as { response?: { status?: number; data?: { message?: string } } };
      const status = axiosErr?.response?.status;
      const msg = axiosErr?.response?.data?.message;
      if (status === 409) {
        appAlert('已被处理', msg ?? '该待办已被其他人处理', [
          { text: '返回列表', onPress: () => navigation.goBack() },
        ]);
      } else {
        appAlert('操作失败', msg ?? '网络错误，请稍后重试');
      }
    } finally {
      setActioning(false);
    }
  }

  // ─── Render detail fields ─────────────────────────────────────────────────

  function renderDetailFields() {
    if (!detail) return null;
    const todoType = type as TodoType;
    const topKeys = TOP_KEYS_BY_TYPE[todoType] ?? [];
    const allKeys = Object.keys(detail).filter((k) => !SKIP_KEYS.has(k));
    const remainingKeys = allKeys.filter((k) => !topKeys.includes(k));

    return (
      <>
        {/* Highlighted top fields (防呆 Rule 2) */}
        {topKeys.filter((k) => k in detail).map((key) => {
          const val = detail[key];
          const isAmount = key.toLowerCase().includes('amount') || key.toLowerCase().includes('price');
          const display = isAmount ? formatAmount(val) : formatValue(val);
          return (
            <DetailRow key={key} label={labelFor(key)} value={display} />
          );
        })}

        {remainingKeys.length > 0 && (
          <>
            <Divider style={styles.sectionDivider} />
            <Text variant="labelSmall" style={styles.sectionLabel}>更多信息</Text>
            {remainingKeys.map((key) => {
              const val = detail[key];
              const isAmount = key.toLowerCase().includes('amount') || key.toLowerCase().includes('price');
              const display = isAmount ? formatAmount(val) : formatValue(val);
              return (
                <DetailRow key={key} label={labelFor(key)} value={display} />
              );
            })}
          </>
        )}
      </>
    );
  }

  // ─── Loading state ────────────────────────────────────────────────────────

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={title} />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载详情...</Text>
        </View>
        <AppDialogHost />
      </SafeAreaView>
    );
  }

  // ─── Error state ─────────────────────────────────────────────────────────

  if (loadError) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={title} />
          <Appbar.Action icon="refresh" onPress={fetchDetail} />
        </Appbar.Header>
        <View style={styles.center}>
          <Text style={styles.errorText}>{loadError}</Text>
          <Button mode="contained" onPress={fetchDetail} style={{ marginTop: 16 }}>
            重试
          </Button>
        </View>
        <AppDialogHost />
      </SafeAreaView>
    );
  }

  const isPAYMENT = type === 'PAYMENT_DISBURSE';

  // ─── Main ────────────────────────────────────────────────────────────────

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={title} subtitle="审批详情" />
      </Appbar.Header>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        {renderDetailFields()}
      </ScrollView>

      {/* 固定底部操作栏 */}
      <View style={styles.bottomBar}>
        {!isPAYMENT && (
          <Button
            mode="outlined"
            onPress={() => setRejectVisible(true)}
            textColor="#C62828"
            style={[styles.bottomBtn, styles.rejectBtn]}
            disabled={actioning}
          >
            驳回
          </Button>
        )}
        <Button
          mode="contained"
          onPress={handleApprove}
          loading={actioning}
          disabled={actioning}
          style={[styles.bottomBtn, styles.approveBtn, isPAYMENT && styles.fullWidthBtn]}
        >
          {isPAYMENT ? '确认付款' : '通过'}
        </Button>
      </View>

      {/* 驳回原因 Modal */}
      <RejectModal
        visible={rejectVisible}
        onCancel={() => setRejectVisible(false)}
        onConfirm={doReject}
      />

      <AppDialogHost />
    </SafeAreaView>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Styles
// ──────────────────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  loadingText: { marginTop: 8, color: '#666' },
  errorText: { color: '#C62828', textAlign: 'center' },

  scroll: { flex: 1 },
  scrollContent: { padding: 16, paddingBottom: 16 },

  detailRow: {
    flexDirection: 'row',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#E0E0E0',
  },
  detailLabel: { width: 100, color: '#888', flexShrink: 0 },
  detailValue: { flex: 1, color: '#212121' },

  sectionDivider: { marginVertical: 12 },
  sectionLabel: { color: '#888', marginBottom: 6 },

  bottomBar: {
    flexDirection: 'row',
    padding: 16,
    paddingBottom: 16,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#E0E0E0',
    gap: 10,
  },
  bottomBtn: { flex: 1, borderRadius: 8 },
  rejectBtn: { borderColor: '#C62828' },
  approveBtn: { backgroundColor: '#1B65A8' },
  fullWidthBtn: { flex: 1 },
});

const modalStyles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    padding: 20,
    maxHeight: '80%',
  },
  sheetTitle: { fontSize: 16, fontWeight: '700', color: '#212121', marginBottom: 8 },
  reasonList: { maxHeight: 260 },
  reasonItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 8,
    borderRadius: 8,
    marginBottom: 4,
  },
  reasonItemSelected: { backgroundColor: '#E3F2FD' },
  radioOuter: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: '#1B65A8',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  radioInner: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#1B65A8',
  },
  reasonText: { fontSize: 14, color: '#333', flex: 1 },
  textarea: { marginTop: 8 },
  buttonRow: {
    flexDirection: 'row',
    marginTop: 16,
    gap: 10,
  },
  btnHalf: { flex: 1 },
});
