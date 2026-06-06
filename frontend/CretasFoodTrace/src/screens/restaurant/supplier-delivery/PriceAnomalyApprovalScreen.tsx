import React, { useCallback, useState } from 'react';
import { Alert, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Button, Card, Chip, Text, TextInput } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList, WHInboundStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import type { SupplierDeliveryNote } from '../../../types/restaurant';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';

type Nav = NativeStackNavigationProp<FAManagementStackParamList & WHInboundStackParamList, 'PriceAnomalyApproval'>;

const BOSS_ROLES = new Set(['factory_super_admin', 'restaurant_manager', 'platform_admin']);

export function PriceAnomalyApprovalScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const roleCode = user?.userType === 'platform' ? user.platformUser?.role : user?.factoryUser?.role;
  const canApprove = BOSS_ROLES.has(roleCode || '');

  const [loading, setLoading] = useState(false);
  const [notes, setNotes] = useState<SupplierDeliveryNote[]>([]);
  const [commentMap, setCommentMap] = useState<Record<string, string>>({});
  const [actingId, setActingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await restaurantApiClient.getPendingPriceAnomalyApprovals({ page: 1, size: 50 });
      setNotes(data);
    } catch (error) {
      handleError(error, { title: '待审批列表加载失败' });
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const approve = (note: SupplierDeliveryNote) => {
    Alert.alert(
      '批准价格异常',
      `送货单 ${note.noteNumber || note.id} 批准后可由仓管确认入库。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '批准',
          onPress: () => { void doApprove(note.id); },
        },
      ],
    );
  };

  const doApprove = async (noteId: string) => {
    setActingId(noteId);
    try {
      await restaurantApiClient.approvePriceAnomaly(noteId, commentMap[noteId]?.trim() || undefined);
      Alert.alert('已批准', '仓管现在可以确认验收入库。');
      await load();
    } catch (error) {
      handleError(error, { title: '批准失败' });
    } finally {
      setActingId(null);
    }
  };

  const reject = (note: SupplierDeliveryNote) => {
    const comment = commentMap[note.id]?.trim();
    if (!comment) {
      Alert.alert('请填写驳回意见', '驳回价格异常时必须写明原因，方便采购/仓管处理。');
      return;
    }
    Alert.alert(
      '驳回价格异常',
      `送货单 ${note.noteNumber || note.id} 驳回后不能入库。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '驳回',
          style: 'destructive',
          onPress: () => { void doReject(note.id, comment); },
        },
      ],
    );
  };

  const doReject = async (noteId: string, comment: string) => {
    setActingId(noteId);
    try {
      await restaurantApiClient.rejectPriceAnomaly(noteId, comment);
      Alert.alert('已驳回', '该送货单已禁止入库，请通知采购/仓管处理。');
      await load();
    } catch (error) {
      handleError(error, { title: '驳回失败' });
    } finally {
      setActingId(null);
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="价格异常待审批" subtitle={canApprove ? '老板/店长审批' : '仅查看'} />
      </Appbar.Header>

      <ScrollView
        refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
        contentContainerStyle={styles.content}
      >
        {!canApprove ? (
          <Card style={styles.banner}>
            <Card.Content>
              <Text style={styles.bannerText}>当前账号无审批权限。请联系老板或店长处理价格异常。</Text>
            </Card.Content>
          </Card>
        ) : null}

        {loading && notes.length === 0 ? (
          <View style={styles.center}>
            <ActivityIndicator />
            <Text style={styles.hint}>正在加载待审批送货单...</Text>
          </View>
        ) : notes.length === 0 ? (
          <View style={styles.center}>
            <Text style={styles.emptyTitle}>暂无待审批</Text>
            <Text style={styles.hint}>所有价格异常送货单都已处理。</Text>
          </View>
        ) : (
          notes.map((note) => {
            const anomalyCount = (note.lines || []).filter((l) => l.priceAnomalyFlag).length;
            const busy = actingId === note.id;
            return (
              <Card key={note.id} style={styles.card}>
                <Card.Content>
                  <View style={styles.titleRow}>
                    <Text style={styles.title}>{note.noteNumber || note.id}</Text>
                    <Chip compact textStyle={styles.chipText}>待审批</Chip>
                  </View>
                  <Text style={styles.meta}>供应商：{note.supplierName || note.supplierId || '未绑定'}</Text>
                  <Text style={styles.meta}>送货日期：{note.deliveryDate}</Text>
                  <Text style={styles.meta}>异常行数：{anomalyCount}</Text>
                  <Button
                    compact
                    mode="text"
                    onPress={() => navigation.navigate('SupplierDeliveryDetail', { noteId: note.id })}
                  >
                    查看送货单详情
                  </Button>
                  {canApprove ? (
                    <>
                      <TextInput
                        label="审批意见（驳回必填）"
                        mode="outlined"
                        value={commentMap[note.id] || ''}
                        onChangeText={(value) => setCommentMap((curr) => ({ ...curr, [note.id]: value }))}
                        style={styles.field}
                      />
                      <View style={styles.actions}>
                        <Button mode="contained" loading={busy} disabled={busy} onPress={() => approve(note)}>
                          批准
                        </Button>
                        <Button mode="outlined" textColor="#B91C1C" loading={busy} disabled={busy} onPress={() => reject(note)}>
                          驳回
                        </Button>
                      </View>
                    </>
                  ) : null}
                </Card.Content>
              </Card>
            );
          })
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  content: { padding: 12, paddingBottom: 32 },
  center: { alignItems: 'center', paddingTop: 80 },
  hint: { marginTop: 8, color: '#6B7280', fontSize: 13 },
  emptyTitle: { fontSize: 16, fontWeight: '700', color: '#374151' },
  banner: { marginBottom: 12, backgroundColor: '#FFFBEB', borderColor: '#F59E0B', borderWidth: 1 },
  bannerText: { color: '#92400E', lineHeight: 20 },
  card: { marginBottom: 12, borderRadius: 8, backgroundColor: '#FFFFFF' },
  titleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  title: { flex: 1, fontSize: 16, fontWeight: '800', color: '#111827' },
  chipText: { fontSize: 11 },
  meta: { fontSize: 13, color: '#4B5563', marginTop: 4 },
  field: { marginTop: 10, backgroundColor: '#FFFFFF' },
  actions: { flexDirection: 'row', gap: 10, marginTop: 10 },
});

export default PriceAnomalyApprovalScreen;
