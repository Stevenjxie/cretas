import React, { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Button, Card, Chip, Text } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList } from '../../../types/navigation';
import {
  purchaseRequisitionApiClient,
  PurchaseRequisition,
  PurchaseRequisitionStatus,
  REQUISITION_STATUS_LABEL,
} from '../../../services/api/purchaseRequisitionApiClient';
import { handleError } from '../../../utils/errorHandler';

type Nav = NativeStackNavigationProp<FAManagementStackParamList, 'ChefRequisitionList'>;

const FILTERS: Array<PurchaseRequisitionStatus | 'ALL'> = ['ALL', 'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONVERTED_TO_PO', 'REJECTED'];

export function ChefRequisitionListScreen() {
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState<PurchaseRequisitionStatus | 'ALL'>('ALL');
  const [rows, setRows] = useState<PurchaseRequisition[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await purchaseRequisitionApiClient.list({
        status: filter === 'ALL' ? undefined : filter,
        page: 1,
        size: 50,
      });
      setRows(data);
    } catch (error) {
      handleError(error, { title: '报货列表加载失败' });
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="报货追踪" />
        <Appbar.Action
          testID="chef-requisition-create-btn"
          icon="plus"
          onPress={() => navigation.navigate('ChefRequisitionCreate')}
        />
      </Appbar.Header>

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.filterRow}
      >
        {FILTERS.map((item) => (
          <Chip
            key={item}
            selected={filter === item}
            onPress={() => setFilter(item)}
            style={styles.filterChip}
          >
            {item === 'ALL' ? '全部' : REQUISITION_STATUS_LABEL[item]}
          </Chip>
        ))}
      </ScrollView>

      <ScrollView refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />} contentContainerStyle={styles.content}>
        {loading && rows.length === 0 ? (
          <View style={styles.center}><ActivityIndicator /></View>
        ) : rows.length === 0 ? (
          <View style={styles.center}>
            <Text style={styles.empty}>暂无报货单</Text>
            <Button mode="contained" onPress={() => navigation.navigate('ChefRequisitionCreate')}>新建报货</Button>
          </View>
        ) : (
          rows.map((row) => (
            <Card key={row.id} style={styles.card}>
              <Card.Content>
                <View style={styles.titleRow}>
                  <Text style={styles.title}>{row.requisitionNumber}</Text>
                  <Chip compact>{REQUISITION_STATUS_LABEL[row.status] || row.status}</Chip>
                </View>
                <Text style={styles.meta}>档口：{row.requesterDeptId || '—'}</Text>
                <Text style={styles.meta}>希望到货：{row.expectedDate || '—'}</Text>
                <Text style={styles.meta}>明细行数：{(row.requestedItems || []).length}</Text>
                {row.reason ? <Text style={styles.meta}>原因：{row.reason}</Text> : null}
              </Card.Content>
            </Card>
          ))
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  filterRow: { paddingHorizontal: 12, paddingVertical: 8, gap: 8 },
  filterChip: { marginRight: 8 },
  content: { padding: 12, paddingBottom: 32 },
  center: { alignItems: 'center', paddingTop: 80, gap: 12 },
  empty: { color: '#6B7280', marginBottom: 8 },
  card: { marginBottom: 10, borderRadius: 8 },
  titleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  title: { flex: 1, fontWeight: '800', fontSize: 15 },
  meta: { fontSize: 13, color: '#4B5563', marginTop: 4 },
});

export default ChefRequisitionListScreen;
