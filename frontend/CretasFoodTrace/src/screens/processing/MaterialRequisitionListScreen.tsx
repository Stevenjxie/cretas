/**
 * 物料需求单列表 (只读) — 2026-07-30 客户反馈新增入口
 *
 * 客户原话 (f006_production_mgr, 2026-07-29): "没有物料需求单模块"。
 *
 * 排查结论 (详见 materialRequisitionApiClient.ts 顶部注释): 后端 + web-admin
 * 早已有完整的物料需求单模块 (生成/备料/调拨/签收/关单/取消 7 个写操作 + 完整
 * 状态机)，RN 端此前从未实现任何界面。完整搬运整套写操作工作流到移动端不是
 * 一夜能做完的工作，本次先落地【只读查看】：生产经理至少能在手机上看到需求单
 * 状态和明细，不再是完全空白。生成/审批/流转仍需通过管理后台完成 —— 页面顶部
 * 有醒目提示，不假装这是完整功能 (禁止降级处理：不假装、不遮盖能力边界)。
 */

import React, { useState, useCallback } from 'react';
import { View, StyleSheet, FlatList, RefreshControl, ActivityIndicator } from 'react-native';
import { Text, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ProcessingStackParamList } from '../../types/navigation';
import { NeoButton } from '../../components/ui/NeoButton';
import { NeoCard } from '../../components/ui/NeoCard';
import {
  materialRequisitionApiClient,
  MaterialRequisitionDTO,
  MaterialRequisitionStatus,
} from '../../services/api/materialRequisitionApiClient';

type NavProp = NativeStackNavigationProp<ProcessingStackParamList>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const AMBER = '#FA8C16';
const GREY = '#8C8C8C';
const RED = '#FF4D4F';

const STATUS_META: Record<MaterialRequisitionStatus, { label: string; color: string; bg: string }> = {
  DRAFT: { label: '草稿', color: GREY, bg: '#f5f5f5' },
  PENDING: { label: '待备料', color: PRIMARY, bg: '#E6F7FF' },
  PICKING: { label: '备料中', color: PRIMARY, bg: '#E6F7FF' },
  TRANSFERRED: { label: '已调拨', color: AMBER, bg: '#FFF7E6' },
  ISSUED: { label: '已签收', color: AMBER, bg: '#FFF7E6' },
  IN_USE: { label: '生产中', color: AMBER, bg: '#FFF7E6' },
  CLOSED: { label: '已关单', color: GREEN, bg: '#F6FFED' },
  CANCELLED: { label: '已取消', color: RED, bg: '#FFF1F0' },
};

export function MaterialRequisitionListScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [list, setList] = useState<MaterialRequisitionDTO[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setLoadError(null);
    try {
      const res = await materialRequisitionApiClient.list({ page: 0, size: 50 });
      setList(res.data?.content ?? []);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setLoadError(e.response?.data?.message ?? '加载物料需求单失败，请检查网络后重试');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const Header = (
    <View style={styles.header}>
      <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless testID="material-req-list-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <Text style={styles.headerTitle}>物料需求单</Text>
      <View style={{ width: 44 }} />
    </View>
  );

  const CapabilityNotice = (
    <NeoCard style={styles.noticeCard}>
      <View style={styles.noticeRow}>
        <MaterialCommunityIcons name="information-outline" size={20} color={PRIMARY} />
        <Text style={styles.noticeTitle}>当前仅支持查看</Text>
      </View>
      <Text style={styles.noticeText}>
        生成需求单、备料、调拨、签收、关单等操作暂未在手机端上线，请使用管理后台完成。
      </Text>
    </NeoCard>
  );

  if (loading && list.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        {Header}
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载物料需求单...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (loadError && list.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        {Header}
        <View style={styles.centered}>
          <MaterialCommunityIcons name="wifi-off" size={48} color="#ccc" />
          <Text style={styles.emptyText}>{loadError}</Text>
          <NeoButton variant="primary" onPress={() => load()} style={{ marginTop: 16 }} testID="material-req-list-retry">
            重试
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {Header}
      <FlatList
        testID="material-req-list-scroll"
        data={list}
        keyExtractor={(item) => item.id}
        ListHeaderComponent={CapabilityNotice}
        contentContainerStyle={{ padding: 16, paddingBottom: 32 }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} />}
        ListEmptyComponent={
          <View style={styles.centered}>
            <MaterialCommunityIcons name="clipboard-list-outline" size={64} color="#ccc" />
            <Text style={styles.emptyText}>暂无物料需求单</Text>
            <Text style={styles.emptySubText}>生产计划生成需求单后，会显示在这里</Text>
          </View>
        }
        renderItem={({ item }) => {
          const meta = STATUS_META[item.status] ?? { label: item.status, color: GREY, bg: '#f5f5f5' };
          return (
            <NeoCard
              style={styles.itemCard}
              onPress={() => navigation.navigate('MaterialRequisitionDetail', { requisitionId: item.id })}
              testID={`material-req-list-item-${item.id}`}
            >
              <View style={styles.itemRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.itemName}>{item.productName ?? item.requisitionNo}</Text>
                  <Text style={styles.itemMeta}>
                    {item.requisitionNo}
                    {item.productionPlanNumber ? ` · 计划 ${item.productionPlanNumber}` : ''}
                  </Text>
                  {item.requiredDate && (
                    <Text style={styles.itemMeta}>需求日期：{item.requiredDate}</Text>
                  )}
                </View>
                <View style={[styles.statusBadge, { backgroundColor: meta.bg }]}>
                  <Text style={[styles.statusText, { color: meta.color }]}>{meta.label}</Text>
                </View>
              </View>
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
  noticeCard: { marginBottom: 12, padding: 16, backgroundColor: '#E6F7FF' },
  noticeRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 },
  noticeTitle: { fontSize: 15, fontWeight: '700', color: PRIMARY },
  noticeText: { fontSize: 13, color: '#333', lineHeight: 20 },
  itemCard: { marginBottom: 12, padding: 16 },
  itemRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  itemName: { fontSize: 17, fontWeight: '700', color: '#111' },
  itemMeta: { fontSize: 13, color: '#888', marginTop: 4 },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, marginLeft: 8 },
  statusText: { fontSize: 13, fontWeight: '700' },
});

export default MaterialRequisitionListScreen;
