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
import { formatDateTime } from '../../utils/formatters';
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
  const fixed = n.toFixed(2);
  const [rawInt = '0', decimals = '00'] = fixed.split('.');
  const signed = rawInt.startsWith('-');
  const digits = signed ? rawInt.slice(1) : rawInt;
  const intPart = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `\u00a5${signed ? '-' : ''}${intPart}.${decimals}`;
}

function formatValue(v: unknown): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function isUuidLike(value: unknown): boolean {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function isWarehouseIdLike(value: unknown): boolean {
  return typeof value === 'string' && /^f\d{3}-.+-wh-\d+$/i.test(value);
}

function isNumericActor(value: unknown): boolean {
  return typeof value === 'number'
    || (typeof value === 'string' && /^\d+$/.test(value));
}

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待处理',
  PENDING_APPROVAL: '待审批',
  PENDING_FINANCE_REVIEW: '待财务审核',
  APPROVED: '已通过',
  FINANCE_APPROVED: '财务已审',
  REJECTED: '已驳回',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  POSTED: '已过账',
  POSTED_TO_FINISHED_GOODS: '已入成品库',
};

const PAYMENT_STATUS_LABELS: Record<string, string> = {
  UNPAID: '未收款',
  PARTIAL: '部分收款',
  PAID: '已收款',
  REFUNDED: '已退款',
};

function isDateTimeKey(key: string): boolean {
  return key.endsWith('At') || key.endsWith('Time');
}

function formatDisplayValue(key: string, value: unknown): string {
  if (key === 'warehouseId' && isWarehouseIdLike(value)) {
    return '盘点仓库需核对';
  }
  if (key === 'counterpartyId' && isUuidLike(value)) {
    return '客户/供应商需核对';
  }
  if ((key === 'submittedBy' || key === 'initiatedBy') && (isNumericActor(value) || isUuidLike(value))) {
    return '发起人需核对';
  }
  if (key.toLowerCase().includes('amount') || key.toLowerCase().includes('price')) {
    return formatAmount(value);
  }
  if (key === 'defaultTaxRate' && value != null && value !== '') {
    return `${String(value)}%`;
  }
  if (key === 'defaultInvoiceType' && typeof value === 'string') {
    return ({ SPECIAL: '专票', NORMAL: '普票', NONE: '不开票' } as Record<string, string>)[value] ?? value;
  }
  if (key === 'status' && typeof value === 'string') {
    return STATUS_LABELS[value] ?? value;
  }
  if (key === 'paymentStatus' && typeof value === 'string') {
    return PAYMENT_STATUS_LABELS[value] ?? value;
  }
  if (isDateTimeKey(key) && (typeof value === 'string' || typeof value === 'number')) {
    const formatted = formatDateTime(value);
    return formatted || formatValue(value);
  }
  return formatValue(value);
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
  warehouseId: '盘点仓库',
  initiatedBy: '发起人',
  initiatedAt: '发起时间',
  rejectReason: '驳回原因',
  appliedAt: '生效时间',
  counterparty: '对方',
  counterpartyId: '对方',
  returnNumber: '退货单号',
  returnType: '退货类型',
  reason: '退货原因',
  deliveryNoteNumber: '送货单号',
  anomalyType: '异常类型',
  expectedPrice: '期望价格',
  actualPrice: '实际价格',
  requestNumber: '付款申请号',
  reportNo: '报损单号',
  trackType: '报损轨道',
  materialBatchId: '物料批次',
  rawMaterialTypeId: '原料类型',
  wastageQty: '报损数量',
  wastageReason: '报损原因',
  reasonDetail: '原因说明',
  photoUrls: '现场照片',
  purchaseOrderId: '采购单ID',
  settlementTypeDisplayName: '结算方式',
  approvedAt: '审批时间',
  orderDate: '下单日期',
  requiredDeliveryDate: '要求交付日期',
  discountAmount: '优惠金额',
  taxAmount: '税额',
  financeReviewNotes: '审核说明',
  confirmedAt: '确认时间',
  remark: '备注',
  defaultTaxRate: '默认税率',
  defaultInvoiceType: '默认发票类型',
  payableAmount: '应收金额',
  paymentStatus: '收款状态',
  lockedQty: '锁定数量',
  reservedQty: '已预留数量',
  shortageQty: '缺口数量',
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
      return `/api/mobile/${factoryId}/warehouse/supplier-delivery-notes/${refId}`;
    case 'STOCKTAKE_APPROVAL':
      return `/api/mobile/${factoryId}/stocktakes/${refId}`;
    case 'RETURN_FINANCE_REVIEW':
      return `/api/mobile/${factoryId}/return-orders/${refId}`;
    case 'WASTAGE_APPROVAL':
      return `/api/mobile/${factoryId}/wastage-reports/${refId}`;
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
  RETURN_FINANCE_REVIEW: ['returnNumber', 'returnType', 'counterpartyId', 'totalAmount', 'reason'],
  WASTAGE_APPROVAL: ['reportNo', 'trackType', 'warehouseId', 'materialBatchId', 'wastageQty', 'wastageReason', 'reasonDetail'],
  PAYMENT_DISBURSE: ['requestNumber', 'purchaseOrderNumber', 'supplierName', 'amount', 'settlementTypeDisplayName'],
};

// Fields to skip (too verbose or shown elsewhere)
const SKIP_KEYS = new Set([
  'id',
  'factoryId',
  'items',
  'createdBy',
  'approvedBy',
  'updatedAt',
  'deletedAt',
  'deleted',
  'workflowInstanceId',
  'vflag',
  'version',
  'customerId',
  'supplierId',
  'purchaseOrderId',
  'quoteId',
]);

function isEmptyDetailValue(value: unknown): boolean {
  return value == null || value === '' || (Array.isArray(value) && value.length === 0);
}

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
            {REJECT_REASON_OPTIONS.map((opt, index) => (
              <TouchableOpacity
                key={opt}
                testID={`oa-detail-reject-reason-${index}`}
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
              testID="oa-detail-reject-custom-reason"
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
            <Button mode="outlined" onPress={handleCancel} style={modalStyles.btnHalf} testID="oa-detail-reject-cancel">
              取消
            </Button>
            <Button
              mode="contained"
              onPress={handleConfirm}
              buttonColor="#C62828"
              style={modalStyles.btnHalf}
              testID="oa-detail-reject-confirm"
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function formatQty(value: unknown): string {
  if (value == null || value === '') return '-';
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? String(n) : String(value);
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
        case 'RETURN_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.returnFinanceApprove(refId);
          break;
        case 'WASTAGE_APPROVAL':
          resp = await todoApprovalApiClient.wastageApprove(refId);
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
        case 'RETURN_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.returnFinanceReject(refId, reason);
          break;
        case 'WASTAGE_APPROVAL':
          resp = await todoApprovalApiClient.wastageReject(refId, reason);
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
    const visibleRemainingKeys = remainingKeys.filter((key) => !isEmptyDetailValue(detail[key]));

    return (
      <>
        {/* Highlighted top fields (防呆 Rule 2) */}
        {topKeys.filter((k) => k in detail).map((key) => {
          const val = detail[key];
          const display = formatDisplayValue(key, val);
          return (
            <DetailRow key={key} label={labelFor(key)} value={display} />
          );
        })}

        {visibleRemainingKeys.length > 0 && (
          <>
            <Divider style={styles.sectionDivider} />
            <Text variant="labelSmall" style={styles.sectionLabel}>更多信息</Text>
            {visibleRemainingKeys.map((key) => {
              const val = detail[key];
              const display = formatDisplayValue(key, val);
              return (
                <DetailRow key={key} label={labelFor(key)} value={display} />
              );
            })}
          </>
        )}
      </>
    );
  }

  function renderStocktakeItems() {
    if ((type as TodoType) !== 'STOCKTAKE_APPROVAL' || !detail) return null;
    const items = Array.isArray(detail.items) ? detail.items.filter(isRecord) : [];
    if (items.length === 0) return null;

    return (
      <>
        <Divider style={styles.sectionDivider} />
        <Text variant="labelSmall" style={styles.sectionLabel}>盘点明细差异</Text>
        {items.map((item, index) => {
          const systemQty = Number(item.systemQty ?? 0);
          const actualRaw = item.actualQty;
          const actualQty = actualRaw == null ? null : Number(actualRaw);
          const diffRaw = item.differenceQty;
          const diffQty = diffRaw == null && actualQty != null
            ? actualQty - systemQty
            : Number(diffRaw ?? 0);
          const hasDiff = Number.isFinite(diffQty) && diffQty !== 0;
          const batch = item.materialBatchId ?? item.rawMaterialTypeId ?? `#${index + 1}`;

          return (
            <View key={String(item.id ?? batch ?? index)} style={styles.stocktakeItem} testID={`oa-detail-stocktake-item-${index}`}>
              <Text variant="labelLarge" style={styles.stocktakeBatch}>批次 {String(batch)}</Text>
              <View style={styles.stocktakeGrid}>
                <DetailRow label="账面" value={formatQty(item.systemQty)} />
                <DetailRow label="实盘" value={formatQty(item.actualQty)} />
                <DetailRow
                  label="差异"
                  value={hasDiff ? formatQty(diffQty) : '0'}
                />
                {item.differenceType != null && (
                  <DetailRow label="类型" value={String(item.differenceType)} />
                )}
                {item.notes != null && item.notes !== '' && (
                  <DetailRow label="备注" value={String(item.notes)} />
                )}
              </View>
            </View>
          );
        })}
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
          <Appbar.Action icon="refresh" onPress={fetchDetail} testID="oa-detail-error-refresh" />
        </Appbar.Header>
        <View style={styles.center}>
          <Text style={styles.errorText}>{loadError}</Text>
          <Button mode="contained" onPress={fetchDetail} style={{ marginTop: 16 }} testID="oa-detail-error-retry">
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
        testID="oa-detail-scroll"
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        {renderDetailFields()}
        {renderStocktakeItems()}
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
            testID="oa-detail-reject"
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
          testID="oa-detail-approve"
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
  scrollContent: { padding: 16, paddingBottom: 112 },

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
  stocktakeItem: {
    backgroundColor: '#fff',
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#D7DEE8',
    padding: 12,
    marginBottom: 10,
  },
  stocktakeBatch: { color: '#212121', marginBottom: 6 },
  stocktakeGrid: { gap: 0 },

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
