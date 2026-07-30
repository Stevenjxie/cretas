/**
 * 盘点记录列表 (2026-07-30 客户反馈修复)
 *
 * 客户原话 (f006_warehouse_mgr, 2026-07-29): "仓库盘点无途径查看今日提交记录，
 * 故只能生成盘点，后无法在 App 内提交审核"。
 *
 * 根因排查发现:
 *   1. "我的" > 常用功能 > "盘点记录" 菜单项文案与实际跳转不符 —— 一直跳到
 *      "发起盘点" (WHInventoryCheckScreen) 表单，而不是任何记录/历史视图。
 *   2. 后端 GET /api/mobile/{factoryId}/stocktakes 早已就绪 (分页+状态过滤)，
 *      RN 侧 stocktakeApiClient.list() 也已实现并单测，但从未被任何屏幕消费。
 *   3. 一旦用户中途退出录入屏 (StocktakeEntry)，没有任何列表能找回那条
 *      "盘点中"的任务 —— 只能重新"发起盘点"，而后端月度防重复规则会返回
 *      409 (DUPLICATE_STOCKTAKE)，用户就卡住了。
 *
 * 本屏零后端改动，纯消费既有 API：
 *   - 未完成 (INITIATED/COUNTING) → 点击直接续录 (StocktakeEntry)
 *   - 已提交 (PENDING_APPROVAL/APPROVED/APPLIED/REJECTED) → 点击进只读详情
 *   - 空状态 / 发起盘点 入口，避免 fool-proof Rule 5 死胡同
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, StyleSheet, FlatList, RefreshControl, ActivityIndicator } from 'react-native';
import { Text, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WHInventoryStackParamList } from '../../../types/navigation';
import { NeoButton } from '../../../components/ui/NeoButton';
import { NeoCard } from '../../../components/ui/NeoCard';
import { AppDialogHost } from '../../../components/ui/AppDialog';
import {
  stocktakeApiClient,
  StocktakeDTO,
  FactoryWarehouseDTO,
} from '../../../services/api/stocktakeApiClient';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const AMBER = '#FA8C16';
const RED = '#FF4D4F';
const GREY = '#8C8C8C';

/** 未完成状态 —— 点击应回到录入屏续录，而非只读详情 */
const UNFINISHED_STATUSES: StocktakeDTO['status'][] = ['INITIATED', 'COUNTING'];

const STATUS_META: Record<StocktakeDTO['status'], { label: string; color: string; bg: string }> = {
  INITIATED: { label: '已发起 · 未录入', color: PRIMARY, bg: '#E6F7FF' },
  COUNTING: { label: '盘点中 · 未提交', color: PRIMARY, bg: '#E6F7FF' },
  PENDING_APPROVAL: { label: '待审批', color: AMBER, bg: '#FFF7E6' },
  APPROVED: { label: '已审批', color: GREEN, bg: '#F6FFED' },
  APPLIED: { label: '已生效', color: GREEN, bg: '#F6FFED' },
  REJECTED: { label: '已驳回', color: RED, bg: '#FFF1F0' },
};

export function WHStocktakeListScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [stocktakes, setStocktakes] = useState<StocktakeDTO[]>([]);
  const [warehouseMap, setWarehouseMap] = useState<Record<string, FactoryWarehouseDTO>>({});
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setLoadError(null);
    try {
      const [listRes, whRes] = await Promise.all([
        stocktakeApiClient.list({ page: 0, size: 50 }),
        stocktakeApiClient.listWarehouses(),
      ]);
      setStocktakes(listRes.data?.content ?? []);
      const map: Record<string, FactoryWarehouseDTO> = {};
      (whRes.data ?? []).forEach((w) => { map[w.id] = w; });
      setWarehouseMap(map);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setLoadError(e.response?.data?.message ?? '加载盘点记录失败，请检查网络后重试');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // 每次进入本屏都刷新 —— 用户很可能是"刚提交完想确认一下"回来看的
  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  useEffect(() => {
    // 初次挂载兜底 (useFocusEffect 在部分测试环境不触发)
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleItemPress = useCallback((item: StocktakeDTO) => {
    if (UNFINISHED_STATUSES.includes(item.status)) {
      // 未完成 —— 直接续录，不让用户重新选品 (fool-proof Rule 5)
      navigation.navigate('StocktakeEntry', { stocktakeId: item.id });
    } else {
      navigation.navigate('WHStocktakeDetail', { stocktakeId: item.id });
    }
  }, [navigation]);

  const handleStartNew = useCallback(() => {
    navigation.navigate('WHInventoryCheck');
  }, [navigation]);

  const Header = (
    <View style={styles.header}>
      <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless testID="stocktake-list-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <Text style={styles.headerTitle}>盘点记录</Text>
      <TouchableRipple onPress={handleStartNew} style={styles.backBtn} borderless testID="stocktake-list-new">
        <MaterialCommunityIcons name="plus" size={24} color="#fff" />
      </TouchableRipple>
    </View>
  );

  if (loading && stocktakes.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        {Header}
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载盘点记录...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (loadError && stocktakes.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        {Header}
        <View style={styles.centered}>
          <MaterialCommunityIcons name="wifi-off" size={48} color="#ccc" />
          <Text style={styles.emptyText}>{loadError}</Text>
          <NeoButton variant="primary" onPress={() => load()} style={{ marginTop: 16 }} testID="stocktake-list-retry">
            重试
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  if (stocktakes.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        {Header}
        <View style={styles.centered}>
          <MaterialCommunityIcons name="clipboard-text-outline" size={64} color="#ccc" />
          <Text style={styles.emptyText}>还没有盘点记录</Text>
          <Text style={styles.emptySubText}>发起一次盘点后，记录会显示在这里</Text>
          <NeoButton variant="primary" onPress={handleStartNew} style={{ marginTop: 16 }} testID="stocktake-list-empty-start">
            发起盘点
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      {Header}
      <FlatList
        testID="stocktake-list-scroll"
        data={stocktakes}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ padding: 16, paddingBottom: 32 }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} />}
        renderItem={({ item }) => {
          const meta = STATUS_META[item.status] ?? { label: item.status, color: GREY, bg: '#f5f5f5' };
          const wh = warehouseMap[item.warehouseId];
          const unfinished = UNFINISHED_STATUSES.includes(item.status);
          return (
            <NeoCard
              style={styles.itemCard}
              onPress={() => handleItemPress(item)}
              testID={`stocktake-list-item-${item.id}`}
            >
              <View style={styles.itemRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.itemWarehouse}>{wh?.name ?? '未知仓库'}</Text>
                  <Text style={styles.itemMeta}>
                    {item.periodMonth} 盘点 · {item.stocktakeNo}
                  </Text>
                  {item.status === 'REJECTED' && item.rejectReason && (
                    <Text style={styles.rejectReason} numberOfLines={2}>
                      驳回原因：{item.rejectReason}
                    </Text>
                  )}
                </View>
                <View style={[styles.statusBadge, { backgroundColor: meta.bg }]}>
                  <Text style={[styles.statusText, { color: meta.color }]}>{meta.label}</Text>
                </View>
              </View>
              {unfinished && (
                <View style={styles.resumeRow}>
                  <MaterialCommunityIcons name="pencil-outline" size={16} color={PRIMARY} />
                  <Text style={styles.resumeText}>点击继续录入</Text>
                </View>
              )}
            </NeoCard>
          );
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  header: {
    backgroundColor: PRIMARY,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20, minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  headerTitle: { fontSize: 18, fontWeight: '700', color: '#fff' },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  emptyText: { marginTop: 12, fontSize: 16, color: '#999', textAlign: 'center' },
  emptySubText: { marginTop: 4, fontSize: 13, color: '#bbb', textAlign: 'center' },
  itemCard: { marginBottom: 12, padding: 16 },
  itemRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  itemWarehouse: { fontSize: 18, fontWeight: '700', color: '#111' },
  itemMeta: { fontSize: 13, color: '#888', marginTop: 4 },
  rejectReason: { fontSize: 13, color: RED, marginTop: 6 },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, marginLeft: 8 },
  statusText: { fontSize: 13, fontWeight: '700' },
  resumeRow: { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 10 },
  resumeText: { fontSize: 13, color: PRIMARY, fontWeight: '600' },
});

export default WHStocktakeListScreen;
