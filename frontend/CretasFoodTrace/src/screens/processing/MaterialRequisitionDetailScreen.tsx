/**
 * 物料需求单详情 (只读) — 2026-07-30 客户反馈新增入口配套页面
 *
 * 展示需求单基本信息 + 明细行 (需求量/已备料/已签收/已消耗/损耗/已退料)。
 * 不提供任何写操作按钮 —— 生成/备料/调拨/签收/关单/取消仍需通过管理后台完成。
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, StyleSheet, ScrollView, ActivityIndicator } from 'react-native';
import { Text, TouchableRipple, Divider } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
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
type RouteType = RouteProp<ProcessingStackParamList, 'MaterialRequisitionDetail'>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const AMBER = '#FA8C16';
const RED = '#FF4D4F';
const GREY = '#8C8C8C';

const STATUS_LABEL: Record<MaterialRequisitionStatus, string> = {
  DRAFT: '草稿',
  PENDING: '待备料',
  PICKING: '备料中',
  TRANSFERRED: '已调拨',
  ISSUED: '已签收',
  IN_USE: '生产中',
  CLOSED: '已关单',
  CANCELLED: '已取消',
};

export function MaterialRequisitionDetailScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RouteType>();
  const { requisitionId } = route.params;

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [requisition, setRequisition] = useState<MaterialRequisitionDTO | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await materialRequisitionApiClient.getDetail(requisitionId);
      setRequisition(res.data ?? null);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setLoadError(e.response?.data?.message ?? '加载需求单详情失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  }, [requisitionId]);

  useEffect(() => {
    load();
  }, [load]);

  const Header = (
    <View style={styles.header}>
      <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless testID="material-req-detail-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <Text style={styles.headerTitle}>需求单详情</Text>
      <View style={{ width: 44 }} />
    </View>
  );

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        {Header}
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载详情...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (loadError || !requisition) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        {Header}
        <View style={styles.centered}>
          <MaterialCommunityIcons name="wifi-off" size={48} color="#ccc" />
          <Text style={styles.emptyText}>{loadError ?? '未找到该需求单'}</Text>
          <NeoButton variant="primary" onPress={load} style={{ marginTop: 16 }} testID="material-req-detail-retry">
            重试
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  const statusColor =
    requisition.status === 'CANCELLED' ? RED :
    requisition.status === 'CLOSED' ? GREEN :
    requisition.status === 'PENDING' || requisition.status === 'PICKING' ? PRIMARY :
    AMBER;

  const items = requisition.items ?? [];

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {Header}
      <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 32 }}>
        <NeoCard style={styles.summaryCard}>
          <View style={styles.summaryTopRow}>
            <Text style={styles.summaryTitle}>{requisition.productName ?? requisition.requisitionNo}</Text>
            <View style={[styles.statusBadge, { backgroundColor: `${statusColor}1A` }]}>
              <Text style={[styles.statusText, { color: statusColor }]}>
                {STATUS_LABEL[requisition.status] ?? requisition.status}
              </Text>
            </View>
          </View>
          <Text style={styles.summaryMeta}>需求单号：{requisition.requisitionNo}</Text>
          {requisition.productionPlanNumber && (
            <Text style={styles.summaryMeta}>生产计划：{requisition.productionPlanNumber}</Text>
          )}
          {requisition.requiredDate && (
            <Text style={styles.summaryMeta}>需求日期：{requisition.requiredDate}</Text>
          )}
          {requisition.remarks && (
            <Text style={styles.summaryMeta}>备注：{requisition.remarks}</Text>
          )}
        </NeoCard>

        <NeoCard style={styles.itemsCard}>
          <Text style={styles.itemsTitle}>物料明细（共 {items.length} 项）</Text>
          {items.length === 0 && <Text style={styles.emptyText}>暂无明细数据</Text>}
          {items.map((it, idx) => (
            <View key={it.id}>
              {idx > 0 && <Divider style={{ marginVertical: 8 }} />}
              <Text style={styles.materialName}>{it.materialName ?? it.materialTypeId}</Text>
              <View style={styles.qtyRow}>
                <View style={styles.qtyCell}>
                  <Text style={styles.qtyLabel}>需求量</Text>
                  <Text style={styles.qtyValue}>{it.requiredQty ?? '—'}</Text>
                </View>
                <View style={styles.qtyCell}>
                  <Text style={styles.qtyLabel}>已备料</Text>
                  <Text style={styles.qtyValue}>{it.pickedQty ?? '—'}</Text>
                </View>
                <View style={styles.qtyCell}>
                  <Text style={styles.qtyLabel}>已签收</Text>
                  <Text style={styles.qtyValue}>{it.issuedQty ?? '—'}</Text>
                </View>
                <View style={styles.qtyCell}>
                  <Text style={styles.qtyLabel}>已消耗</Text>
                  <Text style={styles.qtyValue}>{it.consumedQty ?? '—'}</Text>
                </View>
              </View>
            </View>
          ))}
        </NeoCard>

        <NeoCard style={styles.noteCard}>
          <View style={styles.noteRow}>
            <MaterialCommunityIcons name="information-outline" size={20} color={PRIMARY} />
            <Text style={styles.noteText}>
              本页仅供查看。备料 / 调拨 / 签收 / 关单等操作请使用管理后台完成。
            </Text>
          </View>
        </NeoCard>
      </ScrollView>
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
  content: { flex: 1 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  emptyText: { fontSize: 14, color: '#999', textAlign: 'center', paddingVertical: 12 },
  summaryCard: { margin: 16, marginBottom: 8, padding: 20 },
  summaryTopRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  summaryTitle: { fontSize: 18, fontWeight: '700', color: '#111', flex: 1, marginRight: 8 },
  summaryMeta: { fontSize: 14, color: '#777', marginTop: 6 },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8 },
  statusText: { fontSize: 13, fontWeight: '700' },
  itemsCard: { marginHorizontal: 16, marginBottom: 8, padding: 16 },
  itemsTitle: { fontSize: 16, fontWeight: '700', color: '#111', marginBottom: 12 },
  materialName: { fontSize: 15, fontWeight: '600', color: '#222' },
  qtyRow: { flexDirection: 'row', marginTop: 8 },
  qtyCell: { flex: 1 },
  qtyLabel: { fontSize: 12, color: '#999' },
  qtyValue: { fontSize: 16, fontWeight: '700', color: '#333', marginTop: 2 },
  noteCard: { marginHorizontal: 16, marginBottom: 8, padding: 16, backgroundColor: '#E6F7FF' },
  noteRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  noteText: { fontSize: 13, color: '#333', flex: 1, lineHeight: 19 },
});

export default MaterialRequisitionDetailScreen;
