/**
 * 生产计划列表 (六扇门精简版)
 *
 * 核心功能:
 * - 状态筛选 (全部/待执行/进行中/已完成)
 * - 搜索
 * - 计划卡片 (真实API数据)
 * - 新建计划入口
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  View, Text, StyleSheet, FlatList, TouchableOpacity,
  RefreshControl, ActivityIndicator, Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Searchbar } from 'react-native-paper';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { DISPATCHER_THEME } from '../../../types/dispatcher';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';

interface PlanItem {
  id: string;
  planNumber?: string;
  productTypeName?: string;
  productName?: string;
  plannedQuantity?: number;
  actualQuantity?: number;
  plannedDate?: string;
  planDate?: string;
  status?: string;
  sourceCustomerName?: string;
  processName?: string;
  sourceType?: string;
  createdAt?: string;
}

const STATUS_TABS = [
  { key: 'all', label: '全部' },
  { key: 'PLANNED', label: '待执行' },
  { key: 'IN_PROGRESS', label: '进行中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' },
];

const getStatusStyle = (status?: string) => {
  switch (status?.toUpperCase()) {
    case 'DRAFT': return { bg: '#f5f5f5', color: '#999', label: '草稿' };
    case 'PLANNED': case 'PENDING': return { bg: '#fff7e6', color: '#fa8c16', label: '待执行' };
    case 'CONFIRMED': return { bg: '#e6f7ff', color: '#1890ff', label: '已确认' };
    case 'IN_PROGRESS': return { bg: '#e6f7ff', color: '#1890ff', label: '进行中' };
    case 'COMPLETED': return { bg: '#f6ffed', color: '#52c41a', label: '已完成' };
    case 'CANCELLED': return { bg: '#fff1f0', color: '#ff4d4f', label: '已取消' };
    default: return { bg: '#f5f5f5', color: '#999', label: status || '未知' };
  }
};

export default function PlanListScreen() {
  const navigation = useNavigation<any>();
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [activeTab, setActiveTab] = useState('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [selectedPlan, setSelectedPlan] = useState<PlanItem | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => {
      const plan = plans.find(p => p.id === e.id);
      if (plan) navigation.navigate('PlanDetail', { planId: e.id, planData: plan });
    },
    cancel: (e: RowContext) => Alert.alert('取消', `取消计划 ${e.id} (待接 cancel API)`),
    'print-pdf': (e: RowContext) => { void safePrint('production-work-order', e.id); },
    copy: (e: RowContext) => Alert.alert('复制', `复制计划 ${e.id} (待接 API)`),
  }), [navigation, plans]);

  const sheetCtx: RowContext = selectedPlan
    ? { status: (selectedPlan.status || '').toUpperCase(), id: selectedPlan.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('productionPlan', sheetCtx, { handlers });

  const openSheet = (plan: PlanItem) => { setSelectedPlan(plan); setActionSheetVisible(true); };

  const loadPlans = useCallback(async (p = 1, isRefresh = false) => {
    try {
      if (!isRefresh) setLoading(true);
      const params: Record<string, unknown> = { page: p, size: 100 };

      const res = await productionPlanApiClient.getProductionPlans(params as any);
      const dataObj = (res?.data || res) as any;
      if (res?.success !== false && dataObj) {
        const list = dataObj.content || (Array.isArray(dataObj) ? dataObj : []);
        let items: PlanItem[] = Array.isArray(list) ? list : [];

        // Filter out drafts older than 1 day
        const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
        items = items.filter(i => {
          if ((i.status || '').toUpperCase() === 'DRAFT') {
            return (i.createdAt || '') >= oneDayAgo;
          }
          return true;
        });

        // Client-side status filter
        if (activeTab !== 'all') {
          const statusMap: Record<string, string[]> = {
            'PLANNED': ['PLANNED', 'PENDING'],
            'IN_PROGRESS': ['IN_PROGRESS'],
            'COMPLETED': ['COMPLETED'],
            'CANCELLED': ['CANCELLED'],
          };
          const targets = statusMap[activeTab];
          if (targets) items = items.filter(i => targets.includes((i.status || '').toUpperCase()));
        }

        // Client-side search filter
        if (search.trim()) {
          const q = search.trim().toLowerCase();
          items = items.filter(i =>
            ((i as any).planName || '').toLowerCase().includes(q) ||
            (i.productTypeName || '').toLowerCase().includes(q) ||
            (i.planNumber || '').toLowerCase().includes(q)
          );
        }

        // Sort: drafts on top (by createdAt desc), then rest by createdAt desc
        items.sort((a, b) => {
          const aIsDraft = (a.status || '').toUpperCase() === 'DRAFT' ? 0 : 1;
          const bIsDraft = (b.status || '').toUpperCase() === 'DRAFT' ? 0 : 1;
          if (aIsDraft !== bIsDraft) return aIsDraft - bIsDraft;
          return (b.createdAt || '').localeCompare(a.createdAt || '');
        });

        setPlans(items);
        setTotal(items.length);
        setPage(p);
      } else {
        if (p === 1) { setPlans([]); setTotal(0); }
      }
    } catch {
      // silent
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [activeTab, search]);

  useFocusEffect(useCallback(() => { loadPlans(1); }, [loadPlans]));

  // U-FOOTER-1
  const summaryRequest = useMemo(
    () => ({ filterConditions: activeTab !== 'all' ? { status: activeTab } : {} }),
    [activeTab],
  );
  const { summary, refresh: refreshSummary } = useListSummary('productionPlan', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  const onRefresh = () => { setRefreshing(true); loadPlans(1, true); };
  const onEndReached = () => { if (plans.length > 0 && plans.length < total) loadPlans(page + 1); };

  const renderPlanCard = useCallback(({ item }: { item: PlanItem }) => {
    const s = getStatusStyle(item.status);
    const planned = item.plannedQuantity ?? 0;
    const actual = item.actualQuantity ?? 0;
    const progress = planned > 0 ? Math.min((actual / planned) * 100, 100) : 0;

    return (
      <TouchableOpacity
        style={styles.card}
        activeOpacity={0.7}
        onPress={() => navigation.navigate('PlanDetail', { planId: item.id, planData: item })}
        onLongPress={() => openSheet(item)}
      >
        {/* Top row: plan name + status */}
        <View style={styles.cardTop}>
          <View style={{ flex: 1 }}>
            <Text style={styles.cardTitle} numberOfLines={1}>
              {item.productTypeName || item.productName || (item as any).planName || item.planNumber || '计划'}
            </Text>
            {(item.sourceCustomerName || (item as any).createdByName) ? (
              <Text style={styles.cardSub}>{item.sourceCustomerName ? `客户: ${item.sourceCustomerName}` : `创建人: ${(item as any).createdByName}`}</Text>
            ) : null}
          </View>
          <View style={[styles.statusTag, { backgroundColor: s.bg }]}>
            <Text style={[styles.statusText, { color: s.color }]}>{s.label}</Text>
          </View>
        </View>
        {item.status?.toUpperCase() === 'DRAFT' && (
          <Text style={{ fontSize: 11, color: '#fa8c16', marginBottom: 6 }}>草稿将在24小时后自动清理</Text>
        )}

        {/* Info row */}
        <View style={styles.cardInfo}>
          {planned > 0 && (
            <View style={styles.infoItem}>
              <MaterialCommunityIcons name="package-variant" size={16} color="#999" />
              <Text style={styles.infoText}>{planned} kg</Text>
            </View>
          )}
          {(item.processName || (item as any).totalBatches != null) ? (
            <View style={styles.infoItem}>
              <MaterialCommunityIcons name="cog" size={16} color="#999" />
              <Text style={styles.infoText}>{item.processName || `${(item as any).totalBatches ?? 0} 批次`}</Text>
            </View>
          ) : null}
          <View style={styles.infoItem}>
            <MaterialCommunityIcons name="calendar" size={16} color="#999" />
            <Text style={styles.infoText}>{item.plannedDate || item.planDate || (item as any).planDate || '-'}</Text>
          </View>
        </View>

        {/* Progress */}
        {item.status?.toUpperCase() === 'IN_PROGRESS' && (
          <View style={styles.progressRow}>
            <View style={styles.progressTrack}>
              <View style={[styles.progressFill, { width: `${progress}%` }]} />
            </View>
            <Text style={styles.progressText}>{progress.toFixed(0)}%</Text>
          </View>
        )}

        {/* Source type badge */}
        {item.sourceType && item.sourceType !== 'MANUAL' && (
          <View style={styles.sourceRow}>
            <Text style={styles.sourceLabel}>
              {item.sourceType === 'EXCEL_IMPORT' ? 'Excel导入' :
               item.sourceType === 'AI_CHAT' ? 'AI创建' : item.sourceType}
            </Text>
          </View>
        )}
      </TouchableOpacity>
    );
  }, [navigation]);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="chevron-left" size={28} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>生产计划</Text>
        <TouchableOpacity
          style={styles.newBtn}
          onPress={() => navigation.navigate('PlanCreate')}
        >
          <MaterialCommunityIcons name="plus" size={20} color={DISPATCHER_THEME.primary} />
          <Text style={styles.newBtnText}>新建</Text>
        </TouchableOpacity>
      </View>

      {/* Search */}
      <View style={styles.searchWrap}>
        <Searchbar
          placeholder="搜索计划编号/产品名称..."
          value={search}
          onChangeText={setSearch}
          onSubmitEditing={() => loadPlans(1)}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
          elevation={0}
        />
      </View>

      {/* Status Tabs */}
      <View style={styles.tabRow}>
        {STATUS_TABS.map(tab => (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, activeTab === tab.key && styles.tabActive]}
            onPress={() => { setActiveTab(tab.key); }}
          >
            <Text style={[styles.tabText, activeTab === tab.key && styles.tabTextActive]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        ))}
        <View style={{ flex: 1 }} />
        <Text style={styles.totalText}>共 {total} 条</Text>
      </View>

      {/* Plan List wrapped so sticky footer can sit below */}
      <View style={{ flex: 1 }}>
      {loading && plans.length === 0 ? (
        <ActivityIndicator size="large" style={{ flex: 1 }} color={DISPATCHER_THEME.primary} />
      ) : (
        <FlatList
          data={plans}
          renderItem={renderPlanCard}
          keyExtractor={item => item.id}
          contentContainerStyle={styles.listContent}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          onEndReached={onEndReached}
          onEndReachedThreshold={0.3}
          ListEmptyComponent={
            <View style={styles.empty}>
              <MaterialCommunityIcons name="clipboard-text-outline" size={56} color="#ddd" />
              <Text style={styles.emptyText}>暂无计划</Text>
              <TouchableOpacity
                style={styles.emptyBtn}
                onPress={() => navigation.navigate('PlanCreate')}
              >
                <Text style={styles.emptyBtnText}>创建第一个计划</Text>
              </TouchableOpacity>
            </View>
          }
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
              entityType: 'PRODUCTION_PLAN',
              initialMessage: `分析当前生产计划列表${formatSummaryForAI(summary, { filter: { status: activeTab } })}`,
            },
          }))
        }
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedPlan ? `生产计划 ${selectedPlan.planNumber || selectedPlan.id}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedPlan) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'PRODUCTION_PLAN', initialMessage: `${selectedPlan.planNumber || selectedPlan.id}: ` },
          }));
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },

  // Header
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 12, paddingVertical: 12, backgroundColor: '#fff',
    borderBottomWidth: 1, borderBottomColor: '#f0f0f0',
  },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 18, fontWeight: '700', color: '#333' },
  newBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 16, borderWidth: 1, borderColor: DISPATCHER_THEME.primary },
  newBtnText: { fontSize: 14, fontWeight: '600', color: DISPATCHER_THEME.primary },

  // Search
  searchWrap: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 4, backgroundColor: '#fff' },
  searchBar: { backgroundColor: '#f5f5f5', borderRadius: 10, height: 42 },
  searchInput: { fontSize: 14, minHeight: 0 },

  // Tabs
  tabRow: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 10, backgroundColor: '#fff', gap: 6 },
  tab: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 16, backgroundColor: '#f5f5f5' },
  tabActive: { backgroundColor: DISPATCHER_THEME.primary },
  tabText: { fontSize: 13, fontWeight: '500', color: '#666' },
  tabTextActive: { color: '#fff' },
  totalText: { fontSize: 12, color: '#999' },

  // List
  listContent: { padding: 16, paddingBottom: 40 },

  // Card
  card: {
    backgroundColor: '#fff', borderRadius: 14, padding: 16, marginBottom: 12,
    shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.04, shadowRadius: 8, elevation: 2,
  },
  cardTop: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 },
  cardTitle: { fontSize: 17, fontWeight: '600', color: '#333', flex: 1, marginRight: 8 },
  cardSub: { fontSize: 13, color: '#999', marginTop: 2 },
  statusTag: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 10 },
  statusText: { fontSize: 12, fontWeight: '600' },

  // Info row
  cardInfo: { flexDirection: 'row', gap: 16, marginBottom: 4 },
  infoItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  infoText: { fontSize: 13, color: '#666' },

  // Progress
  progressRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 10 },
  progressTrack: { flex: 1, height: 6, backgroundColor: '#f0f0f0', borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: DISPATCHER_THEME.primary, borderRadius: 3 },
  progressText: { fontSize: 12, color: '#999', width: 32, textAlign: 'right' },

  // Source
  sourceRow: { marginTop: 8 },
  sourceLabel: { fontSize: 11, color: DISPATCHER_THEME.primary, backgroundColor: DISPATCHER_THEME.primary + '12', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 8, alignSelf: 'flex-start' },

  // Empty
  empty: { alignItems: 'center', paddingTop: 80 },
  emptyText: { fontSize: 15, color: '#999', marginTop: 12 },
  emptyBtn: { marginTop: 16, paddingHorizontal: 20, paddingVertical: 10, backgroundColor: DISPATCHER_THEME.primary, borderRadius: 20 },
  emptyBtnText: { color: '#fff', fontSize: 14, fontWeight: '600' },
});
