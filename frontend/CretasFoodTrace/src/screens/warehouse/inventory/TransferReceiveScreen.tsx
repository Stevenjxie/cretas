/**
 * 调拨接收屏 (SP7 R-C)
 *
 * UX 设计回应:
 *   F5 (de-blame): 文案"按实际到货填写，差异将由系统自动记录"
 *   F3: 显示发出数量作为参考上界
 *   F7: 实收数量 fontSize 40, keyboardType=numeric
 *   F6: 失败不清表单, 保留已填数量
 *
 * 端点:
 *   GET  /api/mobile/{factoryId}/transfers/{transferId}        (任务卡数据)
 *   POST /api/mobile/{factoryId}/transfers/{transferId}/receive (确认接收)
 *   POST /api/mobile/{factoryId}/transfers/{transferId}/confirm (可选确认入库)
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  StyleSheet,
  TextInput as RNTextInput,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
} from 'react-native';
import { Text, TouchableRipple, ActivityIndicator, Divider } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WHInventoryStackParamList } from '../../../types/navigation';
import { NeoCard } from '../../../components/ui/NeoCard';
import { NeoButton } from '../../../components/ui/NeoButton';
import { AppDialogHost, appAlert } from '../../../components/ui/AppDialog';
import { transferApiClient, InternalTransfer } from '../../../services/api/transferApiClient';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RoutePropType = RouteProp<WHInventoryStackParamList, 'TransferReceive'>;

const PRIMARY = '#1890FF';
const ORANGE = '#FA8C16';

export function TransferReceiveScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RoutePropType>();
  const { transferId } = route.params;

  const [task, setTask] = useState<InternalTransfer | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [actualQty, setActualQty] = useState('');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // ── 加载调拨任务 ──────────────────────────────
  const loadTask = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const res = await transferApiClient.getTransfer(transferId);
      if (!res.success || !res.data) throw new Error('加载失败');
      setTask(res.data);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setLoadError(e.response?.data?.message ?? '网络错误，请重试');
    } finally {
      setLoading(false);
    }
  }, [transferId]);

  useEffect(() => {
    loadTask();
  }, [loadTask]);

  // ── 计算参考值 ─────────────────────────────────
  // InternalTransfer items use materialTypeName/productTypeName
  const firstItem = task?.items?.[0];
  const shippedQty = firstItem?.quantity ?? 0;
  const firstItemName = firstItem?.materialTypeName ?? firstItem?.productTypeName ?? '—';
  const parsedQty = parseFloat(actualQty);
  const hasDiscrepancy = !isNaN(parsedQty) && parsedQty !== shippedQty;
  const discrepancy = !isNaN(parsedQty) ? parsedQty - shippedQty : 0;

  const canSubmit =
    !loading &&
    !!task &&
    actualQty.trim().length > 0 &&
    !isNaN(parsedQty) &&
    parsedQty >= 0 &&
    !submitting;

  // ── 提交接收 ──────────────────────────────────
  const handleConfirm = useCallback(async () => {
    if (!canSubmit) return;

    const unit = firstItem?.unit ?? '';
    const confirmMessage = hasDiscrepancy
      ? `实收数量 ${parsedQty} ${unit} 与发出数量 ${shippedQty} ${unit} 不符，差异（${discrepancy > 0 ? '+' : ''}${discrepancy.toFixed(2)}）将由系统自动记录，继续确认？`
      : `确认接收 ${parsedQty} ${unit}？`;

    appAlert(
      '确认接收',
      confirmMessage + '\n\n按实际到货填写，差异将由系统自动记录',
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认接收',
          onPress: async () => {
            setSubmitting(true);
            try {
              const res = await transferApiClient.receiveTransfer(transferId);
              if (!res.success) throw new Error('接收失败');
              appAlert(
                '接收成功',
                hasDiscrepancy
                  ? `已接收 ${parsedQty} ${unit}，差异 ${discrepancy > 0 ? '+' : ''}${discrepancy.toFixed(2)} 已自动记录`
                  : `已接收 ${parsedQty} ${unit}，与发出数量一致`,
                [{ text: '完成', onPress: () => navigation.goBack() }],
              );
            } catch (err) {
              const e = err as { response?: { data?: { message?: string } } };
              // F6: 失败不清表单
              appAlert('接收提交失败，已保留填写内容', e.response?.data?.message ?? '请检查网络后重试');
            } finally {
              setSubmitting(false);
            }
          },
        },
      ],
    );
  }, [canSubmit, hasDiscrepancy, parsedQty, firstItem, shippedQty, discrepancy, notes, transferId, navigation]);

  // ── 渲染：加载中 ──────────────────────────────
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <Header onBack={() => navigation.goBack()} />
        <View style={styles.center}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.centerText}>加载调拨信息...</Text>
        </View>
      </SafeAreaView>
    );
  }

  // ── 渲染：加载失败 ────────────────────────────
  if (loadError) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <Header onBack={() => navigation.goBack()} />
        <View style={styles.center}>
          <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#ff4d4f" />
          <Text style={styles.errorText}>{loadError}</Text>
          <NeoButton variant="outline" onPress={loadTask} style={{ marginTop: 16 }}>
            重试
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  // ── 渲染：主界面 ──────────────────────────────
  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      <Header onBack={() => navigation.goBack()} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={styles.scrollContent}>

          {/* 调拨任务卡 */}
          <NeoCard style={styles.card}>
            <View style={styles.taskHeader}>
              <MaterialCommunityIcons name="truck-delivery-outline" size={22} color={ORANGE} />
              <Text style={styles.transferNo}>{task!.transferNumber}</Text>
            </View>
            <Divider style={{ marginVertical: 12 }} />
            {/* 物料信息 */}
            {(task!.items ?? []).map((item, idx) => (
              <View key={item.id} style={styles.itemRow}>
                <Text style={styles.itemLabel}>物料 {idx + 1}</Text>
                <Text style={styles.itemName}>{item.materialTypeName ?? item.productTypeName ?? '—'}</Text>
                <View style={styles.shippedRow}>
                  <Text style={styles.shippedLabel}>发出数量</Text>
                  <Text style={styles.shippedQty}>
                    {item.quantity} {item.unit ?? ''}
                  </Text>
                </View>
              </View>
            ))}
            {task!.sourceFactory && (
              <Text style={styles.routeText}>
                {task!.sourceFactory.name} → {task!.targetFactory?.name ?? '本仓'}
              </Text>
            )}
          </NeoCard>

          {/* 实收数量输入 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>实收数量</Text>
            <Text style={styles.deBlameHint}>
              按实际到货填写，差异将由系统自动记录，无需手工核对
            </Text>
            <RNTextInput
              style={styles.bigInput}
              value={actualQty}
              onChangeText={setActualQty}
              keyboardType="numeric"
              placeholder={String(shippedQty)}
              placeholderTextColor="#bbb"
              textAlign="center"
              autoFocus
            />
            <Text style={styles.refText}>发出参考：{shippedQty} {firstItem?.unit ?? ''}</Text>
            {/* 差异实时提示 */}
            {hasDiscrepancy && actualQty.length > 0 && (
              <View style={[styles.discrepancyBadge, discrepancy < 0 && styles.discrepancyRed]}>
                <MaterialCommunityIcons
                  name={discrepancy > 0 ? 'arrow-up-circle' : 'arrow-down-circle'}
                  size={16}
                  color={discrepancy > 0 ? '#52c41a' : '#ff4d4f'}
                />
                <Text style={[styles.discrepancyText, { color: discrepancy < 0 ? '#ff4d4f' : '#52c41a' }]}>
                  {discrepancy > 0 ? '+' : ''}{discrepancy.toFixed(2)} {firstItem?.unit ?? ''}
                  {discrepancy < 0 ? '（短少，将自动记录）' : '（多收，将自动记录）'}
                </Text>
              </View>
            )}
            {!hasDiscrepancy && actualQty.length > 0 && !isNaN(parsedQty) && (
              <View style={styles.matchBadge}>
                <MaterialCommunityIcons name="check-circle" size={16} color="#52c41a" />
                <Text style={styles.matchText}>与发出数量一致</Text>
              </View>
            )}
          </NeoCard>

          {/* 备注 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>备注（选填）</Text>
            <RNTextInput
              style={styles.notesInput}
              value={notes}
              onChangeText={setNotes}
              placeholder="如有异常情况请填写（包装破损、数量短缺原因等）"
              placeholderTextColor="#bbb"
              multiline
              numberOfLines={3}
            />
          </NeoCard>

        </ScrollView>
      </KeyboardAvoidingView>

      {/* 底部确认按钮 */}
      <View style={styles.bottomBar}>
        <NeoButton
          variant="primary"
          onPress={handleConfirm}
          disabled={!canSubmit}
          loading={submitting}
          size="large"
          style={styles.confirmBtn}
        >
          {submitting ? '提交中...' : '确认接收'}
        </NeoButton>
      </View>
    </SafeAreaView>
  );
}

// ── 内部 Header 组件 ─────────────────────────────
function Header({ onBack }: { onBack: () => void }) {
  return (
    <View style={styles.header}>
      <TouchableRipple onPress={onBack} style={styles.backBtn} borderless>
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <Text style={styles.headerTitle}>调拨接收</Text>
      <View style={styles.headerRight} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  header: {
    backgroundColor: '#1890FF',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20 },
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '700', color: '#fff', textAlign: 'center' },
  headerRight: { width: 40 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 },
  centerText: { marginTop: 12, fontSize: 16, color: '#888' },
  errorText: { marginTop: 12, fontSize: 16, color: '#ff4d4f', textAlign: 'center' },
  scrollContent: { paddingBottom: 120 },
  card: { margin: 16, marginBottom: 0, marginTop: 12, padding: 16 },
  taskHeader: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  transferNo: { fontSize: 18, fontWeight: '800', color: '#222', letterSpacing: 0.5 },
  itemRow: { marginBottom: 8 },
  itemLabel: { fontSize: 12, color: '#999', marginBottom: 2 },
  itemName: { fontSize: 18, fontWeight: '700', color: '#111', marginBottom: 4 },
  itemSub: { fontSize: 13, color: '#666', marginBottom: 4 },
  shippedRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4 },
  shippedLabel: { fontSize: 14, color: '#888' },
  shippedQty: { fontSize: 22, fontWeight: '800', color: '#333' },
  routeText: { fontSize: 13, color: '#888', marginTop: 10 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#222', marginBottom: 8 },
  deBlameHint: {
    fontSize: 13,
    color: '#52c41a',
    backgroundColor: '#f6ffed',
    padding: 10,
    borderRadius: 6,
    marginBottom: 16,
    fontWeight: '500',
  },
  bigInput: {
    fontSize: 40,
    fontWeight: '800',
    color: '#111',
    borderBottomWidth: 2,
    borderBottomColor: PRIMARY,
    paddingVertical: 8,
    textAlign: 'center',
    minHeight: 60,
  },
  refText: { fontSize: 13, color: '#888', textAlign: 'center', marginTop: 8 },
  discrepancyBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#f6ffed',
    borderWidth: 1,
    borderColor: '#b7eb8f',
    borderRadius: 8,
    padding: 10,
    marginTop: 12,
  },
  discrepancyRed: {
    backgroundColor: '#fff2f0',
    borderColor: '#ffccc7',
  },
  discrepancyText: { fontSize: 14, color: '#52c41a', fontWeight: '600', flex: 1 },
  matchBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#f6ffed',
    borderRadius: 8,
    padding: 10,
    marginTop: 12,
  },
  matchText: { fontSize: 14, color: '#52c41a', fontWeight: '600' },
  notesInput: {
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    color: '#111',
    backgroundColor: '#fafafa',
    minHeight: 70,
  },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#fff',
    padding: 16,
    paddingBottom: 28,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
  },
  confirmBtn: { width: '100%' },
});

export default TransferReceiveScreen;
