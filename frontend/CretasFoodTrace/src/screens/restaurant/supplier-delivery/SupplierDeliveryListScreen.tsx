import React, { useCallback, useMemo, useState } from 'react';
import { Image, RefreshControl, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import { Chip, FAB, Searchbar, Surface, Text } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList, WHInboundStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import type { SupplierDeliveryNote, SupplierDeliveryStatus } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { useAuthStore } from '../../../store/authStore';
import { roleCanViewPrice } from '../../../config/rowActionsConfig';

type Nav = NativeStackNavigationProp<FAManagementStackParamList & WHInboundStackParamList, 'SupplierDeliveryList'>;

const STATUS_LABEL: Record<SupplierDeliveryStatus, string> = {
  DRAFT: '待验收',
  CONFIRMED: '已入库',
  REJECTED: '已拒绝',
};

const POSTING_LABEL: Record<string, string> = {
  UNPOSTED: '未过账',
  POSTING: '过账中',
  POSTED: '已生成库存',
  FAILED: '过账失败',
};

export function SupplierDeliveryListScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((state) => state.user);
  const roleCode = user?.userType === 'platform' ? user.platformUser?.role : user?.factoryUser?.role;
  const canViewAmounts = roleCanViewPrice(roleCode);
  const [status, setStatus] = useState<SupplierDeliveryStatus>('DRAFT');
  const [notes, setNotes] = useState<SupplierDeliveryNote[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await restaurantApiClient.getSupplierDeliveryNotes({ status, page: 1, size: 50 });
      setNotes(data);
    } catch (error) {
      handleError(error, { title: '送货单加载失败' });
    } finally {
      setLoading(false);
    }
  }, [status]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const filteredNotes = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) return notes;
    return notes.filter((note) => [
      note.noteNumber,
      note.supplierName,
      note.supplierId,
      note.deliveryDate,
      note.postingError,
    ].some((value) => (value || '').toLowerCase().includes(query)));
  }, [notes, searchQuery]);

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>送货验收入库</Text>
        <Text style={styles.headerSub}>供应商送货到店后，仓管确认生成真实库存批次</Text>
      </View>

      <ScrollView refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.tabs}>
          {(['DRAFT', 'CONFIRMED', 'REJECTED'] as SupplierDeliveryStatus[]).map((item) => (
            <Chip key={item} selected={status === item} onPress={() => setStatus(item)} style={styles.tab}>
              {STATUS_LABEL[item]}
            </Chip>
          ))}
        </ScrollView>

        <View style={styles.list}>
          <Searchbar
            placeholder="搜供应商、单号或日期"
            value={searchQuery}
            onChangeText={setSearchQuery}
            style={styles.searchbar}
          />

          {filteredNotes.length === 0 ? (
            <View style={styles.empty}>
              <MaterialCommunityIcons name="truck-delivery-outline" size={48} color="#C0C4CC" />
              <Text style={styles.emptyText}>暂无{STATUS_LABEL[status]}送货单</Text>
              {status === 'DRAFT' ? <Text style={styles.emptyHint}>供应商到店后点右下角新增，先保存草稿再验收入库。</Text> : null}
            </View>
          ) : filteredNotes.map((note) => (
            <TouchableOpacity
              key={note.id}
              activeOpacity={0.86}
              onPress={() => navigation.navigate('SupplierDeliveryDetail', { noteId: note.id })}
            >
              <Surface style={styles.card} elevation={1}>
                <View style={styles.cardHeader}>
                  <Text style={styles.noteNo}>{note.noteNumber || note.id}</Text>
                  <Text style={[styles.badge, note.postingStatus === 'FAILED' && styles.badgeError]}>
                    {POSTING_LABEL[note.postingStatus || 'UNPOSTED'] || note.postingStatus || '未过账'}
                  </Text>
                </View>
                <Text style={styles.supplier}>{note.supplierName || note.supplierId || '未绑定供应商'}</Text>
                <Text style={styles.meta}>{note.deliveryDate} · {STATUS_LABEL[note.status] || note.status}</Text>
                <Text style={styles.meta}>金额：{formatAmount(note.totalAmount, canViewAmounts)}</Text>
                {note.photoOssUrl ? (
                  <View style={styles.photoRow}>
                    <Image source={{ uri: note.photoOssUrl }} style={styles.photoThumb} />
                    <Text style={styles.photoText}>已留存现场照片</Text>
                  </View>
                ) : null}
                {note.receiveRecordId ? <Text style={styles.batch}>入库单：{note.receiveRecordId}</Text> : null}
                {note.postingError ? <Text style={styles.error}>{note.postingError}</Text> : null}
              </Surface>
            </TouchableOpacity>
          ))}
        </View>
      </ScrollView>

      <FAB icon="plus" style={styles.fab} onPress={() => navigation.navigate('SupplierDeliveryCreate')} />
    </SafeAreaView>
  );
}

function formatAmount(value: number | null | undefined, canViewAmounts: boolean): string {
  if (!canViewAmounts) return '无权限';
  if (value == null || !Number.isFinite(value)) return '未计算';
  return `¥${Number(value).toFixed(2)}`;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  header: { backgroundColor: '#1890FF', paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16 },
  headerTitle: { fontSize: 20, fontWeight: '700', color: '#FFFFFF' },
  headerSub: { fontSize: 12, color: 'rgba(255,255,255,0.86)', marginTop: 4 },
  tabs: { paddingHorizontal: 16, paddingVertical: 10, flexGrow: 0 },
  tab: { marginRight: 8 },
  list: { paddingHorizontal: 16, paddingBottom: 90 },
  searchbar: { marginBottom: 12, borderRadius: 8, backgroundColor: '#FFFFFF' },
  card: { backgroundColor: '#FFFFFF', borderRadius: 8, padding: 14, marginBottom: 12 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  noteNo: { flex: 1, fontSize: 15, fontWeight: '700', color: '#1F2937' },
  badge: { fontSize: 11, color: '#1890FF', backgroundColor: '#E6F7FF', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4 },
  badgeError: { color: '#B91C1C', backgroundColor: '#FEE2E2' },
  supplier: { fontSize: 14, color: '#374151', marginTop: 8 },
  meta: { fontSize: 12, color: '#6B7280', marginTop: 6 },
  photoRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 8 },
  photoThumb: { width: 44, height: 44, borderRadius: 6, backgroundColor: '#E5E7EB' },
  photoText: { fontSize: 12, color: '#047857' },
  batch: { fontSize: 12, color: '#047857', marginTop: 6 },
  error: { color: '#B91C1C', fontSize: 12, marginTop: 8 },
  empty: { alignItems: 'center', paddingTop: 80 },
  emptyText: { color: '#6B7280', marginTop: 12 },
  emptyHint: { color: '#9CA3AF', fontSize: 12, marginTop: 6, textAlign: 'center', lineHeight: 18 },
  fab: { position: 'absolute', right: 16, bottom: 24, backgroundColor: '#1890FF' },
});

export default SupplierDeliveryListScreen;
