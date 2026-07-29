/**
 * 盘点记录 — 只读详情 (2026-07-30 客户反馈修复配套页面)
 *
 * 展示已提交 (PENDING_APPROVAL / APPROVED / APPLIED / REJECTED) 的盘点任务:
 * 品项账面/实盘/差异 + 驳回原因(若有)。仓管员看这一屏就能确认"我提交的东西
 * 现在是什么状态"，不需要再猜。
 *
 * 未完成状态 (INITIATED / COUNTING) 不会进到这一屏 —— WHStocktakeListScreen
 * 会直接把它们导去 StocktakeEntry 续录。
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, StyleSheet, ScrollView, ActivityIndicator } from 'react-native';
import { Text, TouchableRipple } from 'react-native-paper';
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
} from '../../../services/api/stocktakeApiClient';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, 'WHStocktakeDetail'>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const AMBER = '#FA8C16';
const RED = '#FF4D4F';

const STATUS_LABEL: Record<StocktakeDTO['status'], string> = {
  INITIATED: '已发起',
  COUNTING: '盘点中',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批',
  APPLIED: '已生效',
  REJECTED: '已驳回',
};

export function WHStocktakeDetailScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RouteType>();
  const { stocktakeId } = route.params;

  const [loading, setLoading] = useState(true);
  const [stocktake, setStocktake] = useState<StocktakeDTO | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await stocktakeApiClient.getDetail(stocktakeId);
      if (!res.success || !res.data) throw new Error('加载失败');
      setStocktake(res.data);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      appAlert(
        '加载盘点详情失败',
        e.response?.data?.message ?? '请检查网络后重试',
        [{ text: '重试', onPress: load }, { text: '返回', style: 'cancel', onPress: () => navigation.goBack() }],
      );
    } finally {
      setLoading(false);
    }
  }, [stocktakeId, navigation]);

  useEffect(() => {
    load();
  }, [load]);

  const Header = (
    <View style={styles.header}>
      <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless testID="stocktake-detail-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <Text style={styles.headerTitle}>盘点详情</Text>
      <View style={styles.headerRight} />
    </View>
  );

  if (loading || !stocktake) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        {Header}
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载盘点详情...</Text>
        </View>
      </SafeAreaView>
    );
  }

  const items = stocktake.items ?? [];
  const diffItems = items.filter((it) => (it.actualQty ?? it.systemQty) !== it.systemQty);
  const statusColor =
    stocktake.status === 'REJECTED' ? RED :
    stocktake.status === 'PENDING_APPROVAL' ? AMBER :
    GREEN;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      {Header}
      <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 32 }}>
        <NeoCard style={styles.summaryCard}>
          <View style={styles.summaryTopRow}>
            <Text style={styles.summaryTitle}>{stocktake.stocktakeNo}</Text>
            <View style={[styles.statusBadge, { backgroundColor: `${statusColor}1A` }]}>
              <Text style={[styles.statusText, { color: statusColor }]}>
                {STATUS_LABEL[stocktake.status] ?? stocktake.status}
              </Text>
            </View>
          </View>
          <Text style={styles.summaryMeta}>盘点月份：{stocktake.periodMonth}</Text>
          {stocktake.submittedAt && (
            <Text style={styles.summaryMeta}>提交时间：{stocktake.submittedAt}</Text>
          )}
        </NeoCard>

        {stocktake.status === 'REJECTED' && stocktake.rejectReason && (
          <NeoCard style={styles.rejectCard}>
            <View style={styles.rejectRow}>
              <MaterialCommunityIcons name="alert-circle" size={20} color={RED} />
              <Text style={styles.rejectTitle}>驳回原因</Text>
            </View>
            <Text style={styles.rejectText}>{stocktake.rejectReason}</Text>
          </NeoCard>
        )}

        <NeoCard style={styles.diffListCard}>
          <Text style={styles.diffListTitle}>
            品项明细（共 {items.length} 项，差异 {diffItems.length} 项）
          </Text>
          {items.length === 0 && (
            <Text style={styles.emptyText}>暂无品项数据</Text>
          )}
          {items.map((it) => {
            const actual = it.actualQty ?? null;
            const diff = actual !== null ? actual - it.systemQty : null;
            const isDiff = diff !== null && diff !== 0;
            return (
              <View key={it.id} style={styles.itemRow} testID={`stocktake-detail-item-${it.id}`}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.itemName}>{it.materialBatchId ?? '—'}</Text>
                  <Text style={styles.itemQtyText}>
                    账面 {it.systemQty}{actual !== null ? ` → 实盘 ${actual}` : '（未录入）'}
                  </Text>
                </View>
                {isDiff && (
                  <Text style={[styles.diffQty, { color: diff! < 0 ? RED : AMBER }]}>
                    {diff! > 0 ? '+' : ''}{diff!.toFixed(2)}
                  </Text>
                )}
              </View>
            );
          })}
        </NeoCard>

        {stocktake.status === 'PENDING_APPROVAL' && (
          <NeoCard style={styles.noteCard}>
            <View style={styles.rejectRow}>
              <MaterialCommunityIcons name="information-outline" size={20} color={PRIMARY} />
              <Text style={styles.noteText}>已提交财务审批，请等待审批结果，暂时无需再操作。</Text>
            </View>
          </NeoCard>
        )}
      </ScrollView>

      {stocktake.status === 'REJECTED' && (
        <View style={styles.bottomBar}>
          <NeoButton
            variant="primary"
            size="large"
            onPress={() => navigation.navigate('WHInventoryCheck')}
            style={{ flex: 1 }}
            testID="stocktake-detail-restart"
          >
            重新发起盘点
          </NeoButton>
        </View>
      )}
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
  backBtn: { padding: 8, borderRadius: 20, minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  headerTitle: { flex: 1, textAlign: 'center', fontSize: 18, fontWeight: '700', color: '#fff' },
  headerRight: { width: 40 },
  content: { flex: 1 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  emptyText: { fontSize: 14, color: '#999', paddingVertical: 12 },
  summaryCard: { margin: 16, marginBottom: 8, padding: 20 },
  summaryTopRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  summaryTitle: { fontSize: 18, fontWeight: '700', color: '#111' },
  summaryMeta: { fontSize: 14, color: '#777', marginTop: 6 },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8 },
  statusText: { fontSize: 13, fontWeight: '700' },
  rejectCard: { marginHorizontal: 16, marginBottom: 8, padding: 16, backgroundColor: '#FFF1F0' },
  rejectRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  rejectTitle: { fontSize: 15, fontWeight: '700', color: RED },
  rejectText: { fontSize: 14, color: '#333', marginTop: 8, lineHeight: 20 },
  diffListCard: { marginHorizontal: 16, marginBottom: 8, padding: 16 },
  diffListTitle: { fontSize: 16, fontWeight: '700', color: '#111', marginBottom: 12 },
  itemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  itemName: { fontSize: 15, fontWeight: '600', color: '#222' },
  itemQtyText: { fontSize: 13, color: '#888', marginTop: 2 },
  diffQty: { fontSize: 16, fontWeight: '800', marginLeft: 12 },
  noteCard: { marginHorizontal: 16, marginBottom: 8, padding: 16, backgroundColor: '#E6F7FF' },
  noteText: { fontSize: 14, color: '#333', flex: 1, lineHeight: 20 },
  bottomBar: {
    backgroundColor: '#fff',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
  },
});

export default WHStocktakeDetailScreen;
