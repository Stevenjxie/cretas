/**
 * 盘点录入屏 (SP7 R-C)
 *
 * UX Flow F4 设计回应: 单品一屏
 *   上 = 品名 + 编码大字
 *   中 = 账面数量（创建时可见）
 *   下 = 实际数量大键盘 (≥24px)
 *   差异提交后才显
 *   全部录完 → 提交 → "待财务审批"
 *
 * UX 强制项：TouchableRipple / ≥44pt / 数字 ≥24px / 唯一大按钮 / 错误双句+保数据+重试
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  ActivityIndicator,
  TextInput as RNTextInput,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {
  Text,
  TouchableRipple,
  Divider,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WHInventoryStackParamList } from '../../../types/navigation';
import { NeoButton } from '../../../components/ui/NeoButton';
import { NeoCard } from '../../../components/ui/NeoCard';
import { AppDialogHost, appAlert } from '../../../components/ui/AppDialog';
import {
  stocktakeApiClient,
  StocktakeDTO,
  StocktakeItemDTO,
  StocktakeItemUpdateDTO,
} from '../../../services/api/stocktakeApiClient';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, 'StocktakeEntry'>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const RED = '#FF4D4F';
const AMBER = '#FA8C16';

function statusLabel(status: StocktakeDTO['status']): string {
  const MAP: Record<string, string> = {
    INITIATED: '已发起',
    COUNTING: '盘点中',
    PENDING_APPROVAL: '待审批',
    APPROVED: '已审批',
    APPLIED: '已生效',
    REJECTED: '已驳回',
  };
  return MAP[status] ?? status;
}

/** 每品 + 已录状态 */
interface ItemEntry {
  item: StocktakeItemDTO;
  /** 用户输入的实盘文本（允许小数） */
  inputText: string;
  /** 解析后的数值，null = 未填 */
  actualQty: number | null;
  /** 是否已保存到后端 */
  saved: boolean;
  saving: boolean;
}

export function StocktakeEntryScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RouteType>();
  const { stocktakeId } = route.params;

  const [loading, setLoading] = useState(true);
  const [stocktake, setStocktake] = useState<StocktakeDTO | null>(null);
  const [entries, setEntries] = useState<ItemEntry[]>([]);
  /** 当前正在录入的品项索引（单品一屏） */
  const [currentIndex, setCurrentIndex] = useState(0);
  /** 差异汇总是否已显示（全部录完后） */
  const [diffVisible, setDiffVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const inputRef = useRef<RNTextInput>(null);

  // ── 加载盘点详情 ──────────────────────────────
  const loadDetail = useCallback(async () => {
    setLoading(true);
    try {
      const res = await stocktakeApiClient.getDetail(stocktakeId);
      if (!res.success || !res.data) throw new Error('加载失败');
      setStocktake(res.data);
      const initialEntries: ItemEntry[] = (res.data.items ?? []).map((item) => ({
        item,
        inputText: '',
        actualQty: null,
        saved: false,
        saving: false,
      }));
      setEntries(initialEntries);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      appAlert(
        '加载盘点任务失败',
        e.response?.data?.message ?? '请检查网络后重试',
        [{ text: '重试', onPress: loadDetail }, { text: '返回', style: 'cancel', onPress: () => navigation.goBack() }],
      );
    } finally {
      setLoading(false);
    }
  }, [stocktakeId, navigation]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  // 自动聚焦输入框
  useEffect(() => {
    if (!loading && entries.length > 0) {
      setTimeout(() => inputRef.current?.focus(), 300);
    }
  }, [loading, currentIndex, entries.length]);

  // ── 当前品项 ──────────────────────────────────
  const currentEntry = entries[currentIndex] as ItemEntry | undefined;
  const totalCount = entries.length;
  const filledCount = entries.filter((e) => e.actualQty !== null).length;
  const allFilled = filledCount === totalCount && totalCount > 0;

  // ── 更新输入 ──────────────────────────────────
  const handleInputChange = useCallback((text: string) => {
    setEntries((prev) => {
      const copy = [...prev];
      const cur = copy[currentIndex];
      if (!cur) return prev;
      copy[currentIndex] = { ...cur, inputText: text, actualQty: text ? parseFloat(text) || null : null };
      return copy;
    });
  }, [currentIndex]);

  // ── 保存当前品项到后端（PUT /items 幂等） ──────
  const saveCurrentItem = useCallback(async (): Promise<boolean> => {
    const entry = entries[currentIndex] as ItemEntry | undefined;
    if (!entry || entry.actualQty === null || !stocktake) return false;
    const updates: StocktakeItemUpdateDTO[] = [{
      itemId: entry.item.id,
      actualQty: entry.actualQty,
    }];
    setEntries((prev) => {
      const copy = [...prev];
      const cur = copy[currentIndex];
      if (!cur) return prev;
      copy[currentIndex] = { ...cur, saving: true };
      return copy;
    });
    try {
      await stocktakeApiClient.updateItems(stocktake.id, updates);
      setEntries((prev) => {
        const copy = [...prev];
        const cur = copy[currentIndex];
        if (!cur) return prev;
        copy[currentIndex] = { ...cur, saving: false, saved: true };
        return copy;
      });
      return true;
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setEntries((prev) => {
        const copy = [...prev];
        const cur = copy[currentIndex];
        if (!cur) return prev;
        copy[currentIndex] = { ...cur, saving: false };
        return copy;
      });
      appAlert('保存失败，数据已保留', e.response?.data?.message ?? '请检查网络后重试，您的数据不会丢失');
      return false;
    }
  }, [currentIndex, entries, stocktake]);

  // ── 下一品 ────────────────────────────────────
  const handleNext = useCallback(async () => {
    if (!currentEntry) return;
    if (currentEntry.actualQty === null) {
      appAlert('请填写实盘数量', '请先填写本品的实盘数量，再进行下一品');
      return;
    }
    const ok = await saveCurrentItem();
    if (!ok) return;
    if (currentIndex < totalCount - 1) {
      setCurrentIndex((i) => i + 1);
    } else {
      // 全部完成 → 显示差异汇总
      setDiffVisible(true);
    }
  }, [currentEntry, currentIndex, saveCurrentItem, totalCount]);

  // ── 上一品 ────────────────────────────────────
  const handlePrev = useCallback(() => {
    if (currentIndex > 0) setCurrentIndex((i) => i - 1);
  }, [currentIndex]);

  // ── 提交盘点 ──────────────────────────────────
  const handleSubmit = useCallback(async () => {
    if (!stocktake) return;
    const pending = entries.filter((e) => e.actualQty === null);
    if (pending.length > 0) {
      appAlert('还有未录品项', `${pending.length} 个品项未填写实盘数量，请先完成所有录入`);
      return;
    }
    const diffItems = entries.filter((e) => e.actualQty !== null && e.actualQty !== e.item.systemQty);
    const diffDesc = diffItems.length > 0
      ? `${diffItems.length} 件存在差异，将报给财务审批`
      : '所有品项账实相符';
    appAlert(
      '确认提交盘点？',
      `${diffDesc}。提交后状态将变为"待财务审批"，您无法修改。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认提交',
          onPress: async () => {
            setSubmitting(true);
            try {
              await stocktakeApiClient.submit(stocktake.id);
              appAlert(
                '盘点已提交',
                '盘点任务已提交财务审批，请等待审批结果。',
                [{ text: '确定', onPress: () => navigation.goBack() }],
              );
            } catch (err) {
              const e = err as { response?: { data?: { message?: string } } };
              appAlert('提交失败，数据已保留', e.response?.data?.message ?? '请检查网络后重试');
            } finally {
              setSubmitting(false);
            }
          },
        },
      ],
    );
  }, [stocktake, entries, navigation]);

  // ── Loading ───────────────────────────────────
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载盘点任务...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!stocktake || entries.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.centered}>
          <MaterialCommunityIcons name="package-variant" size={64} color="#ccc" />
          <Text style={styles.emptyText}>该盘点任务暂无品项</Text>
          <NeoButton variant="outline" onPress={() => navigation.goBack()} style={{ marginTop: 16 }}>
            返回
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  // ── 差异汇总视图（全部录完后） ───────────────
  if (diffVisible) {
    const diffEntries = entries.filter((e) => e.actualQty !== null && e.actualQty !== e.item.systemQty);
    const surplusEntries = entries.filter((e) => e.actualQty !== null && e.actualQty > e.item.systemQty);
    const shortageEntries = entries.filter((e) => e.actualQty !== null && e.actualQty < e.item.systemQty);

    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => setDiffVisible(false)} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>差异汇总</Text>
          <View style={styles.headerRight} />
        </View>
        <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }}>
          <NeoCard style={styles.summaryCard}>
            <Text style={styles.summaryTitle}>盘点结果：{stocktake.stocktakeNo}</Text>
            <Text style={styles.summaryPeriod}>盘点月份：{stocktake.periodMonth}</Text>
            <Divider style={{ marginVertical: 12 }} />
            <View style={styles.summaryRow}>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryNum}>{totalCount}</Text>
                <Text style={styles.summaryLabel}>总品项</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: GREEN }]}>{totalCount - diffEntries.length}</Text>
                <Text style={styles.summaryLabel}>账实相符</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: RED }]}>{shortageEntries.length}</Text>
                <Text style={styles.summaryLabel}>盘亏</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: AMBER }]}>{surplusEntries.length}</Text>
                <Text style={styles.summaryLabel}>盘盈</Text>
              </View>
            </View>
          </NeoCard>

          {diffEntries.length > 0 && (
            <NeoCard style={styles.diffListCard}>
              <Text style={styles.diffListTitle}>差异明细（共 {diffEntries.length} 项）</Text>
              {shortageEntries.length > 0 && (
                <Text style={[styles.warningNote, { color: RED, marginBottom: 8 }]}>
                  注：盘亏将记录您的工号作为责任方，请确认数据正确
                </Text>
              )}
              {diffEntries.map((e) => {
                const diff = (e.actualQty ?? 0) - (e.item.systemQty ?? 0);
                const isShort = diff < 0;
                return (
                  <View key={e.item.id} style={styles.diffRow}>
                    <View style={styles.diffLeft}>
                      <Text style={styles.diffName}>{e.item.materialBatchId ?? '—'}</Text>
                      <Text style={styles.diffBatch}>账面 {e.item.systemQty} → 实盘 {e.actualQty}</Text>
                    </View>
                    <Text style={[styles.diffQty, { color: isShort ? RED : AMBER }]}>
                      {diff > 0 ? '+' : ''}{diff.toFixed(2)}
                    </Text>
                  </View>
                );
              })}
            </NeoCard>
          )}

          {diffEntries.length === 0 && (
            <NeoCard style={styles.diffListCard}>
              <View style={styles.centered}>
                <MaterialCommunityIcons name="check-circle-outline" size={48} color={GREEN} />
                <Text style={{ color: GREEN, fontWeight: '600', marginTop: 8 }}>所有品项账实相符！</Text>
              </View>
            </NeoCard>
          )}
        </ScrollView>

        <View style={styles.bottomBar}>
          <NeoButton
            variant="outline"
            onPress={() => setDiffVisible(false)}
            style={{ flex: 1 }}
          >
            返回修改
          </NeoButton>
          <NeoButton
            variant="primary"
            onPress={handleSubmit}
            loading={submitting}
            disabled={submitting}
            style={{ flex: 2 }}
          >
            {submitting ? '提交中...' : '提交待财务审批'}
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  // ── 单品录入视图 ──────────────────────────────
  const entry = entries[currentIndex] as ItemEntry | undefined;
  // Guard: entries may be empty during load transition
  if (!entry) return <SafeAreaView style={styles.container} edges={['top']}><AppDialogHost /></SafeAreaView>;
  const systemQty = entry.item.systemQty ?? 0;
  const actualQty = entry.actualQty;
  const hasDiff = actualQty !== null && actualQty !== systemQty;
  const canGoNext = actualQty !== null;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      {/* Header */}
      <View style={styles.header}>
        <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableRipple>
        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <Text style={styles.headerSubtitle}>{currentIndex + 1} / {totalCount}</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      {/* 进度条 */}
      <View style={styles.progressContainer}>
        <View style={[styles.progressFill, { width: `${(filledCount / totalCount) * 100}%` as unknown as number }]} />
      </View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }}>

          {/* 品名 + 编码大字 */}
          <NeoCard style={styles.nameCard}>
            <Text style={styles.itemName} numberOfLines={2}>
              {entry.item.materialBatchId ?? '未知批次'}
            </Text>
            {entry.item.rawMaterialTypeId && (
              <Text style={styles.itemCode}>编码：{entry.item.rawMaterialTypeId}</Text>
            )}
          </NeoCard>

          {/* 账面数量 */}
          <NeoCard style={styles.systemQtyCard}>
            <Text style={styles.systemQtyLabel}>账面数量</Text>
            <Text style={styles.systemQtyValue}>{systemQty}</Text>
          </NeoCard>

          {/* 实际数量大键盘 */}
          <NeoCard style={styles.inputCard}>
            <Text style={styles.inputLabel}>实盘数量（填写实际在库数量）</Text>
            <RNTextInput
              ref={inputRef}
              style={[
                styles.bigInput,
                hasDiff && { color: actualQty! > systemQty ? AMBER : RED },
              ]}
              value={entry.inputText}
              onChangeText={handleInputChange}
              keyboardType="numeric"
              placeholder="请输入实际数量"
              placeholderTextColor="#bbb"
              returnKeyType="done"
              onSubmitEditing={handleNext}
            />
            {/* 差异提交后才显 — 此处在录入时不显示差异 */}
            {entry.saving && (
              <View style={styles.savingRow}>
                <ActivityIndicator size="small" color={PRIMARY} />
                <Text style={styles.savingText}>保存中...</Text>
              </View>
            )}
            {entry.saved && (
              <View style={styles.savingRow}>
                <MaterialCommunityIcons name="check" size={16} color={GREEN} />
                <Text style={[styles.savingText, { color: GREEN }]}>已保存</Text>
              </View>
            )}
          </NeoCard>

        </ScrollView>
      </KeyboardAvoidingView>

      {/* 底部导航 */}
      <View style={styles.bottomBar}>
        <TouchableRipple
          onPress={handlePrev}
          disabled={currentIndex === 0}
          style={[styles.navBtn, currentIndex === 0 && styles.navBtnDisabled]}
          borderless
        >
          <View style={styles.navBtnInner}>
            <MaterialCommunityIcons name="chevron-left" size={24} color={currentIndex === 0 ? '#ccc' : '#333'} />
            <Text style={[styles.navBtnText, currentIndex === 0 && { color: '#ccc' }]}>上一品</Text>
          </View>
        </TouchableRipple>

        <NeoButton
          variant="primary"
          onPress={handleNext}
          disabled={!canGoNext || entry.saving}
          style={styles.nextBtn}
          size="large"
        >
          {currentIndex < totalCount - 1 ? '下一品' : '完成录入'}
        </NeoButton>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  header: {
    backgroundColor: PRIMARY,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20 },
  headerCenter: { flex: 1, alignItems: 'center' },
  headerTitle: { fontSize: 18, fontWeight: '700', color: '#fff' },
  headerSubtitle: { fontSize: 12, color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  headerRight: { width: 40 },
  progressContainer: {
    height: 4,
    backgroundColor: '#d9d9d9',
  },
  progressFill: {
    height: 4,
    backgroundColor: GREEN,
  },
  content: { flex: 1 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  emptyText: { marginTop: 12, fontSize: 16, color: '#999' },
  nameCard: {
    margin: 16,
    marginBottom: 8,
    padding: 20,
  },
  itemName: {
    fontSize: 28,
    fontWeight: '800',
    color: '#111',
    lineHeight: 36,
  },
  itemCode: {
    fontSize: 15,
    color: '#666',
    marginTop: 8,
    fontWeight: '500',
  },
  systemQtyCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    padding: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  systemQtyLabel: { fontSize: 16, color: '#555', fontWeight: '500' },
  systemQtyValue: { fontSize: 28, fontWeight: '800', color: '#333' },
  inputCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    padding: 16,
  },
  inputLabel: { fontSize: 15, color: '#555', fontWeight: '500', marginBottom: 12 },
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
  savingRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8, gap: 6 },
  savingText: { fontSize: 13, color: '#999' },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#fff',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
  },
  navBtn: {
    minHeight: 44,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    backgroundColor: '#f5f5f5',
    justifyContent: 'center',
  },
  navBtnDisabled: { opacity: 0.4 },
  navBtnInner: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  navBtnText: { fontSize: 15, fontWeight: '600', color: '#333' },
  nextBtn: { flex: 1 },
  // Diff summary
  summaryCard: { margin: 16, marginBottom: 8, padding: 20 },
  summaryTitle: { fontSize: 18, fontWeight: '700', color: '#111' },
  summaryPeriod: { fontSize: 14, color: '#777', marginTop: 4 },
  summaryRow: { flexDirection: 'row', justifyContent: 'space-around' },
  summaryItem: { alignItems: 'center' },
  summaryNum: { fontSize: 28, fontWeight: '800', color: '#111' },
  summaryLabel: { fontSize: 13, color: '#777', marginTop: 4 },
  diffListCard: { marginHorizontal: 16, marginBottom: 8, padding: 16 },
  diffListTitle: { fontSize: 16, fontWeight: '700', color: '#111', marginBottom: 12 },
  warningNote: { fontSize: 13, lineHeight: 18 },
  diffRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  diffLeft: { flex: 1 },
  diffName: { fontSize: 15, fontWeight: '600', color: '#222' },
  diffBatch: { fontSize: 12, color: '#888', marginTop: 2 },
  diffQty: { fontSize: 18, fontWeight: '800', marginLeft: 12 },
});

export default StocktakeEntryScreen;
