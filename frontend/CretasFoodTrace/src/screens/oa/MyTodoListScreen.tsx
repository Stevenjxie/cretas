/**
 * OA 我的待办列表屏
 *
 * 角色: finance_manager (采购财审/销售财审/价格异常/盘点审批)
 *       cashier         (付款确认)
 *
 * 防呆规则:
 *   Rule 2: 卡片显示 单号+金额+对方+申请人+时间（完整上下文）
 *   Rule 3: 驳回原因 dropdown (5-8标准 + 其他→textarea)
 *   Rule 4: 409 → "已被处理" + refetch
 *   Rule 5: 空状态 "暂无待办，已处理完毕"
 *
 * needDetail=false → 内联 通过/驳回 按钮
 * needDetail=true  → "查看详情" → TodoDetailScreen
 *
 * @since 2026-06-12 (OA 待办 R2)
 */

import React, { useCallback, useState } from 'react';
import {
  FlatList,
  Modal,
  RefreshControl,
  ScrollView,
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
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import {
  TodoItemDTO,
  TodoType,
  REJECT_REASON_OPTIONS,
  RejectReasonOption,
  todoApprovalApiClient,
} from '../../services/api/myTodoApiClient';
import { useMyTodos } from '../../hooks/useMyTodos';
import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';
import type { OATodoStackParamList } from '../../types/navigation';

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDate(s: string | null | undefined): string {
  if (!s) return '—';
  // ISO datetime → "YY-MM-DD HH:mm"
  return s.slice(0, 16).replace('T', ' ');
}

const TODO_TYPE_LABEL: Record<TodoType, string> = {
  PURCHASE_FINANCE_REVIEW: '采购财审',
  SALES_FINANCE_REVIEW: '销售财审',
  PRICE_ANOMALY: '价格异常',
  STOCKTAKE_APPROVAL: '盘点审批',
  PAYMENT_DISBURSE: '付款确认',
};

const TODO_TYPE_COLOR: Record<TodoType, string> = {
  PURCHASE_FINANCE_REVIEW: '#1B65A8',
  SALES_FINANCE_REVIEW: '#2E7D32',
  PRICE_ANOMALY: '#E65100',
  STOCKTAKE_APPROVAL: '#6A1B9A',
  PAYMENT_DISBURSE: '#C62828',
};

// ──────────────────────────────────────────────────────────────────────────────
// RejectReasonModal (防呆 Rule 3)
// ──────────────────────────────────────────────────────────────────────────────

interface RejectReasonModalProps {
  visible: boolean;
  todoTitle: string;
  todoNumber: string;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

function RejectReasonModal({
  visible,
  todoTitle,
  todoNumber,
  onCancel,
  onConfirm,
}: RejectReasonModalProps) {
  const [selected, setSelected] = useState<RejectReasonOption | null>(null);
  const [customReason, setCustomReason] = useState('');

  function handleConfirm() {
    if (!selected) {
      appAlert('请选择驳回原因', '请从下方列表选择驳回理由');
      return;
    }
    const finalReason =
      selected === '其他'
        ? customReason.trim()
        : selected;
    if (selected === '其他' && !finalReason) {
      appAlert('请填写驳回原因', '选择"其他"时需填写具体原因');
      return;
    }
    onConfirm(finalReason);
    // reset
    setSelected(null);
    setCustomReason('');
  }

  function handleCancel() {
    setSelected(null);
    setCustomReason('');
    onCancel();
  }

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={handleCancel}
    >
      <View style={modalStyles.overlay}>
        <View style={modalStyles.sheet}>
          <Text style={modalStyles.sheetTitle}>驳回 — {todoTitle}</Text>
          <Text style={modalStyles.sheetSubtitle}>{todoNumber}</Text>
          <Divider style={{ marginBottom: 12 }} />

          <Text style={modalStyles.sectionLabel}>选择驳回原因</Text>
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

          {/* 其他 → textarea（防呆 Rule 3） */}
          {selected === '其他' && (
            <TextInput
              mode="outlined"
              label="请详细说明原因"
              value={customReason}
              onChangeText={setCustomReason}
              multiline
              numberOfLines={3}
              style={modalStyles.textarea}
            />
          )}

          <View style={modalStyles.buttonRow}>
            <Button mode="outlined" onPress={handleCancel} style={modalStyles.btnCancel}>
              取消
            </Button>
            <Button
              mode="contained"
              onPress={handleConfirm}
              buttonColor="#C62828"
              style={modalStyles.btnConfirm}
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
// TodoCard
// ──────────────────────────────────────────────────────────────────────────────

interface TodoCardProps {
  item: TodoItemDTO;
  onApproveSuccess: () => void;
  onNavigateDetail: (item: TodoItemDTO) => void;
}

function TodoCard({ item, onApproveSuccess, onNavigateDetail }: TodoCardProps) {
  const [approving, setApproving] = useState(false);
  const [rejectModalVisible, setRejectModalVisible] = useState(false);
  const typeLabel = TODO_TYPE_LABEL[item.type] ?? item.type;
  const typeColor = TODO_TYPE_COLOR[item.type] ?? '#666';

  // ─── 通过审批 ─────────────────────────────────────────────────────────────

  async function handleApprove() {
    appAlert(
      `确认通过 — ${item.title}`,
      `单号: ${item.refNumber}\n金额: ${formatAmount(item.amount)}\n对方: ${item.counterparty}\n申请人: ${item.submittedBy}`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认通过',
          style: 'default',
          onPress: () => doApprove(),
        },
      ],
    );
  }

  async function doApprove() {
    setApproving(true);
    try {
      let resp: { success: boolean; message?: string };
      switch (item.type) {
        case 'PURCHASE_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.purchaseFinanceApprove(item.refId);
          break;
        case 'SALES_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.salesFinanceApprove(item.refId);
          break;
        case 'PRICE_ANOMALY':
          resp = await todoApprovalApiClient.priceAnomalyApprove(item.refId);
          break;
        case 'STOCKTAKE_APPROVAL':
          resp = await todoApprovalApiClient.stocktakeApprove(item.refId);
          break;
        case 'PAYMENT_DISBURSE':
          resp = await todoApprovalApiClient.paymentMarkPaid(item.refId);
          break;
        default:
          appAlert('未知操作', `不支持的待办类型: ${item.type}`);
          setApproving(false);
          return;
      }

      if (resp.success) {
        appAlert('操作成功', resp.message ?? '审批已通过');
        onApproveSuccess();
      } else {
        appAlert('操作失败', resp.message ?? '请稍后重试');
      }
    } catch (err: unknown) {
      const axiosErr = err as { response?: { status?: number; data?: { message?: string } } };
      const status = axiosErr?.response?.status;
      const msg = axiosErr?.response?.data?.message;

      if (status === 409) {
        // 防呆 Rule 4: 409 = 已被处理
        appAlert('已被处理', msg ?? '该待办已被其他人处理，列表将自动刷新', [
          { text: '知道了', onPress: onApproveSuccess },
        ]);
      } else {
        appAlert('操作失败', msg ?? '网络错误，请稍后重试');
      }
    } finally {
      setApproving(false);
    }
  }

  // ─── 驳回 ────────────────────────────────────────────────────────────────

  async function doReject(reason: string) {
    setRejectModalVisible(false);
    try {
      let resp: { success: boolean; message?: string };
      switch (item.type) {
        case 'PURCHASE_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.purchaseFinanceReject(item.refId, reason);
          break;
        case 'SALES_FINANCE_REVIEW':
          resp = await todoApprovalApiClient.salesFinanceReject(item.refId, reason);
          break;
        case 'PRICE_ANOMALY':
          resp = await todoApprovalApiClient.priceAnomalyReject(item.refId, reason);
          break;
        case 'STOCKTAKE_APPROVAL':
          resp = await todoApprovalApiClient.stocktakeReject(item.refId, reason);
          break;
        case 'PAYMENT_DISBURSE':
          // 付款不支持驳回（出纳只能付款）
          appAlert('不支持驳回', '付款确认操作不支持驳回，请联系财务经理');
          return;
        default:
          appAlert('未知操作', `不支持的待办类型: ${item.type}`);
          return;
      }

      if (resp.success) {
        appAlert('操作成功', resp.message ?? '已驳回');
        onApproveSuccess();
      } else {
        appAlert('操作失败', resp.message ?? '请稍后重试');
      }
    } catch (err: unknown) {
      const axiosErr = err as { response?: { status?: number; data?: { message?: string } } };
      const status = axiosErr?.response?.status;
      const msg = axiosErr?.response?.data?.message;

      if (status === 409) {
        appAlert('已被处理', msg ?? '该待办已被其他人处理，列表将自动刷新', [
          { text: '知道了', onPress: onApproveSuccess },
        ]);
      } else {
        appAlert('操作失败', msg ?? '网络错误，请稍后重试');
      }
    }
  }

  // ─── Render ──────────────────────────────────────────────────────────────

  return (
    <>
      <Card style={styles.card} elevation={1}>
        <Card.Content>
          {/* 类型标签 + 单号（防呆 Rule 2: 上下文展示） */}
          <View style={styles.cardHeader}>
            <Chip
              compact
              style={[styles.typeChip, { backgroundColor: typeColor + '1A' }]}
              textStyle={{ color: typeColor, fontSize: 11 }}
            >
              {typeLabel}
            </Chip>
            <Text variant="labelSmall" style={styles.refNumber}>
              {item.refNumber}
            </Text>
          </View>

          {/* 标题 */}
          <Text variant="titleMedium" style={styles.title}>
            {item.title}
          </Text>

          {/* 防呆 Rule 2: 金额 + 对方 + 申请人 */}
          <View style={styles.infoRow}>
            <Text variant="bodySmall" style={styles.infoLabel}>金额</Text>
            <Text variant="bodyMedium" style={styles.amountText}>
              {formatAmount(item.amount)}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text variant="bodySmall" style={styles.infoLabel}>对方</Text>
            <Text variant="bodyMedium" style={styles.infoValue}>
              {item.counterparty || '—'}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text variant="bodySmall" style={styles.infoLabel}>申请人</Text>
            <Text variant="bodyMedium" style={styles.infoValue}>
              {item.submittedBy || '—'}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text variant="bodySmall" style={styles.infoLabel}>时间</Text>
            <Text variant="bodySmall" style={styles.dateValue}>
              {formatDate(item.submittedAt)}
            </Text>
          </View>

          <Divider style={styles.divider} />

          {/* 操作按钮区 */}
          {item.needDetail ? (
            // 大额 → 强制查看详情（防呆 Rule 1: 预先显示不能一键通过大额单）
            <Button
              mode="outlined"
              onPress={() => onNavigateDetail(item)}
              icon="eye"
              style={styles.detailBtn}
            >
              查看详情后审批
            </Button>
          ) : (
            // 小额 → 内联通过/驳回
            <View style={styles.actionRow}>
              {item.type !== 'PAYMENT_DISBURSE' && (
                <Button
                  mode="outlined"
                  onPress={() => setRejectModalVisible(true)}
                  textColor="#C62828"
                  style={[styles.actionBtn, styles.rejectBtn]}
                  disabled={approving}
                >
                  驳回
                </Button>
              )}
              <Button
                mode="contained"
                onPress={handleApprove}
                loading={approving}
                disabled={approving}
                style={[styles.actionBtn, styles.approveBtn]}
              >
                {item.type === 'PAYMENT_DISBURSE' ? '确认付款' : '通过'}
              </Button>
            </View>
          )}
        </Card.Content>
      </Card>

      {/* 驳回原因 Modal（防呆 Rule 3） */}
      <RejectReasonModal
        visible={rejectModalVisible}
        todoTitle={typeLabel}
        todoNumber={item.refNumber}
        onCancel={() => setRejectModalVisible(false)}
        onConfirm={doReject}
      />
    </>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Main Screen
// ──────────────────────────────────────────────────────────────────────────────

type NavProp = NativeStackNavigationProp<OATodoStackParamList, 'MyTodoList'>;

export default function MyTodoListScreen() {
  const navigation = useNavigation<NavProp>();
  const { todos, loading, refreshing, error, refetch, onRefresh } = useMyTodos();

  const handleNavigateDetail = useCallback(
    (item: TodoItemDTO) => {
      navigation.navigate('TodoDetail', {
        refId: item.refId,
        type: item.type,
        title: item.title,
      });
    },
    [navigation],
  );

  // ─── Loading ────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="我的待办" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
        <AppDialogHost />
      </SafeAreaView>
    );
  }

  // ─── Error ────────────────────────────────────────────────────────────────

  if (error) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="我的待办" />
          <Appbar.Action icon="refresh" onPress={refetch} />
        </Appbar.Header>
        <View style={styles.center}>
          <Text style={styles.errorText}>{error}</Text>
          <Button mode="contained" onPress={refetch} style={{ marginTop: 16 }}>
            重试
          </Button>
        </View>
        <AppDialogHost />
      </SafeAreaView>
    );
  }

  // ─── Main ────────────────────────────────────────────────────────────────

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="我的待办"
          subtitle={todos.length > 0 ? `${todos.length} 项待处理` : undefined}
        />
        <Appbar.Action icon="refresh" onPress={refetch} />
      </Appbar.Header>

      <FlatList
        data={todos}
        keyExtractor={(item) => `${item.type}-${item.refId}`}
        renderItem={({ item }) => (
          <TodoCard
            item={item}
            onApproveSuccess={refetch}
            onNavigateDetail={handleNavigateDetail}
          />
        )}
        contentContainerStyle={
          todos.length === 0 ? styles.emptyContainer : styles.listContent
        }
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          // 防呆 Rule 5: 空状态
          <View style={styles.emptyCenter}>
            <Text variant="headlineSmall" style={styles.emptyIcon}>✓</Text>
            <Text variant="bodyLarge" style={styles.emptyTitle}>
              暂无待办
            </Text>
            <Text variant="bodySmall" style={styles.emptySubtitle}>
              已处理完毕，待新的审批请求
            </Text>
          </View>
        }
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

  listContent: { padding: 12, paddingBottom: 32 },
  emptyContainer: { flexGrow: 1, padding: 24 },
  emptyCenter: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: 60 },
  emptyIcon: { fontSize: 48, color: '#4CAF50', marginBottom: 12 },
  emptyTitle: { color: '#444', marginBottom: 8, fontWeight: '600' },
  emptySubtitle: { color: '#999', textAlign: 'center' },

  card: { marginBottom: 12, borderRadius: 10 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  typeChip: { height: 24 },
  refNumber: { color: '#888', fontFamily: 'monospace' },
  title: { fontWeight: '700', color: '#212121', marginBottom: 8 },

  infoRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 3 },
  infoLabel: { width: 52, color: '#888' },
  infoValue: { flex: 1, color: '#333' },
  amountText: { flex: 1, color: '#C62828', fontWeight: '700' },
  dateValue: { flex: 1, color: '#888' },

  divider: { marginVertical: 10 },

  detailBtn: { borderColor: '#1B65A8' },
  actionRow: { flexDirection: 'row', gap: 8 },
  actionBtn: { flex: 1, borderRadius: 8 },
  rejectBtn: { borderColor: '#C62828' },
  approveBtn: { backgroundColor: '#1B65A8' },
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
    maxHeight: '85%',
  },
  sheetTitle: { fontSize: 16, fontWeight: '700', color: '#212121', marginBottom: 2 },
  sheetSubtitle: { fontSize: 12, color: '#888', marginBottom: 8 },
  sectionLabel: { fontSize: 13, color: '#666', marginBottom: 8 },
  reasonList: { maxHeight: 240 },
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
  textarea: { marginTop: 8, marginBottom: 8 },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 16,
    gap: 10,
  },
  btnCancel: { flex: 1 },
  btnConfirm: { flex: 1 },
});
