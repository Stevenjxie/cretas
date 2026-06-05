import React, { useCallback, useState } from 'react';
import { Alert, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Surface, Text } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { RouteProp, useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import type { SupplierDeliveryLine, SupplierDeliveryNote } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';

type Nav = NativeStackNavigationProp<FAManagementStackParamList, 'SupplierDeliveryDetail'>;
type DetailRoute = RouteProp<FAManagementStackParamList, 'SupplierDeliveryDetail'>;

export function SupplierDeliveryDetailScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<DetailRoute>();
  const { noteId } = route.params;
  const [note, setNote] = useState<SupplierDeliveryNote | null>(null);
  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await restaurantApiClient.getSupplierDeliveryNote(noteId);
      setNote(data);
    } catch (error) {
      handleError(error, { title: '送货单加载失败' });
    } finally {
      setLoading(false);
    }
  }, [noteId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const confirm = () => {
    if (!note) return;
    const lines = (note.lines || []).map(formatLine).join('\n');
    Alert.alert(
      '确认验收入库',
      `送货单：${note.noteNumber || note.id}\n供应商：${note.supplierName || note.supplierId || '未绑定'}\n将生成 ${(note.lines || []).length} 个库存批次：\n${lines}\n仓库：${note.warehouseId || 'WH-LOG'}`,
      [
        { text: '取消', style: 'cancel' },
        { text: '确认入库', onPress: () => { void doConfirm(); } },
      ],
    );
  };

  const doConfirm = async () => {
    setConfirming(true);
    try {
      const updated = await restaurantApiClient.confirmSupplierDelivery(noteId);
      setNote(updated);
      Alert.alert('验收入库成功', '已生成真实库存批次。');
    } catch (error) {
      handleError(error, { title: '验收入库失败' });
      void load();
    } finally {
      setConfirming(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>返回</Button>
        <Text style={styles.headerTitle}>送货单详情</Text>
        <View style={{ width: 72 }} />
      </View>

      <ScrollView refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />} contentContainerStyle={styles.content}>
        {!note ? (
          <Text style={styles.empty}>加载中...</Text>
        ) : (
          <>
            <Surface style={styles.card} elevation={1}>
              <Text style={styles.title}>{note.noteNumber || note.id}</Text>
              <Text style={styles.meta}>供应商：{note.supplierName || note.supplierId || '未绑定'}</Text>
              <Text style={styles.meta}>送货日期：{note.deliveryDate}</Text>
              <Text style={styles.meta}>单据状态：{note.status}</Text>
              <Text style={styles.meta}>过账状态：{note.postingStatus || 'UNPOSTED'}</Text>
              {note.receiveRecordId ? <Text style={styles.meta}>入库单：{note.receiveRecordId}</Text> : null}
              {note.postingError ? <Text style={styles.error}>{note.postingError}</Text> : null}
            </Surface>

            {(note.lines || []).map(line => (
              <Surface key={line.id || `${line.rawMaterialTypeId}-${line.ingredientName}`} style={styles.card} elevation={1}>
                <Text style={styles.lineName}>{line.ingredientName}</Text>
                <Text style={styles.meta}>食材 ID：{line.rawMaterialTypeId || '未匹配'}</Text>
                <Text style={styles.meta}>数量：{line.quantity ?? '-'} {line.unit || ''}</Text>
                <Text style={styles.meta}>单价：{line.unitPrice == null ? '***' : line.unitPrice}</Text>
                <Text style={styles.meta}>质检：{line.qcResult || '-'}</Text>
                {line.materialBatchId ? <Text style={styles.batch}>批次：{line.materialBatchId}</Text> : null}
              </Surface>
            ))}

            {note.status === 'DRAFT' ? (
              <Button mode="contained" buttonColor="#2563EB" loading={confirming} disabled={confirming} onPress={confirm}>
                确认验收入库
              </Button>
            ) : (
              <Text style={styles.done}>该送货单已处理</Text>
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function formatLine(line: SupplierDeliveryLine) {
  return `${line.ingredientName} ${line.quantity ?? '-'}${line.unit || ''}`;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#2563EB', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8 },
  headerTitle: { fontSize: 18, color: '#fff', fontWeight: '700' },
  content: { padding: 16, paddingBottom: 40 },
  card: { backgroundColor: '#fff', borderRadius: 10, padding: 14, marginBottom: 12 },
  title: { fontSize: 17, fontWeight: '700', color: '#111827', marginBottom: 8 },
  lineName: { fontSize: 15, fontWeight: '700', color: '#1F2937', marginBottom: 6 },
  meta: { fontSize: 13, color: '#4B5563', marginTop: 4 },
  batch: { fontSize: 13, color: '#047857', marginTop: 6 },
  error: { fontSize: 13, color: '#B91C1C', marginTop: 8 },
  empty: { textAlign: 'center', color: '#6B7280', marginTop: 40 },
  done: { textAlign: 'center', color: '#047857', marginTop: 8 },
});

export default SupplierDeliveryDetailScreen;
