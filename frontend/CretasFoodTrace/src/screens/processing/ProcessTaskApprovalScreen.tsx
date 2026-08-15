import React, { useState, useCallback } from 'react';
import { View, StyleSheet, FlatList, RefreshControl } from 'react-native';
import { Text, Appbar, ActivityIndicator, Checkbox } from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { processTaskApiClient } from '../../services/api/processTaskApiClient';
import { NeoButton, NeoCard, ScreenWrapper, AppDialogHost, appAlert, appPrompt } from '../../components/ui';
import { theme } from '../../theme';
import { getUserFriendlyMessage } from '../../utils/errorHandler';

interface ApprovalItem {
  id: number;
  processTaskId: string;
  reporterName: string;
  reportDate: string;
  outputQuantity: number;
  processCategory?: string;
  approvalStatus: string;
  isSupplemental: boolean;
  createdAt: string;
  notes?: string;
}

export default function ProcessTaskApprovalScreen() {
  const navigation = useNavigation();
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [actionLoading, setActionLoading] = useState<number | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const res = await processTaskApiClient.getPendingApprovals({ page: 1, size: 50 }) as any;
      if (res?.success && res.data?.content) {
        setItems(res.data.content);
      } else if (res?.data && Array.isArray(res.data)) {
        setItems(res.data);
      }
    } catch {
      // silent
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { fetchData(); }, [fetchData]));

  const handleApprove = async (id: number) => {
    setActionLoading(id);
    try {
      await processTaskApiClient.approveReport(id);
      setItems(prev => prev.filter(i => i.id !== id));
      setSelected(prev => { const s = new Set(prev); s.delete(id); return s; });
    } catch (err) {
      appAlert('审批失败', getUserFriendlyMessage(err));
    } finally {
      setActionLoading(null);
    }
  };

  const handleReject = (id: number) => {
    // ⛔ 原本用 Alert.prompt —— 那是 iOS 专有 API, Android 上这个按钮点了毫无反应。
    appPrompt('驳回原因', '请说明驳回这条报工的原因', async (reason) => {
      setActionLoading(id);
      try {
        await processTaskApiClient.rejectReport(id, reason);
        setItems(prev => prev.filter(i => i.id !== id));
      } catch (err) {
        appAlert('驳回失败', getUserFriendlyMessage(err));
      } finally {
        setActionLoading(null);
      }
    }, { placeholder: '例如: 数量与实际不符', multiline: true });
  };

  const handleBatchApprove = async () => {
    if (selected.size === 0) return;
    const ids = Array.from(selected);
    appAlert('批量审批', `确定通过 ${ids.length} 条报工？`, [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: async () => {
        setActionLoading(-1);
        try {
          await processTaskApiClient.batchApprove(ids);
          setItems(prev => prev.filter(i => !selected.has(i.id)));
          setSelected(new Set());
        } catch (err) {
          appAlert('批量审批失败', getUserFriendlyMessage(err));
        } finally {
          setActionLoading(null);
        }
      }},
    ]);
  };

  const toggleSelect = (id: number) => {
    setSelected(prev => {
      const s = new Set(prev);
      s.has(id) ? s.delete(id) : s.add(id);
      return s;
    });
  };

  const renderItem = ({ item }: { item: ApprovalItem }) => (
    <NeoCard style={styles.card}>
      <View style={styles.cardHeader}>
        <Checkbox
          status={selected.has(item.id) ? 'checked' : 'unchecked'}
          onPress={() => toggleSelect(item.id)}
        />
        <View style={{ flex: 1 }}>
          <Text style={styles.reporterName}>{item.reporterName || `工人#${item.id}`}</Text>
          <Text style={styles.meta}>
            {item.processCategory || '工序'} · {item.reportDate}
            {item.isSupplemental && <Text style={{ color: '#e6a23c' }}> [补报]</Text>}
          </Text>
        </View>
        <Text style={styles.quantity}>{item.outputQuantity}</Text>
      </View>
      {item.notes ? <Text style={styles.notes}>备注: {item.notes}</Text> : null}
      <View style={styles.actions}>
        <NeoButton
          variant="primary" size="small"
          onPress={() => handleApprove(item.id)}
          loading={actionLoading === item.id}
          disabled={actionLoading !== null}
          style={{ flex: 1, marginRight: 8 }}
        >通过</NeoButton>
        <NeoButton
          variant="outline" size="small"
          onPress={() => handleReject(item.id)}
          disabled={actionLoading !== null}
          style={{ flex: 1 }}
        >驳回</NeoButton>
      </View>
    </NeoCard>
  );

  if (loading) {
    return (
      <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
        <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="报工审批" /></Appbar.Header>
        <ActivityIndicator style={{ flex: 1 }} size="large" />
      </ScreenWrapper>
    );
  }

  return (
    <ScreenWrapper testID="process-approval-screen" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="报工审批" subtitle={`${items.length} 条待审批`} />
      </Appbar.Header>

      {selected.size > 0 && (
        <View style={styles.batchBar}>
          <Text style={styles.batchText}>已选 {selected.size} 条</Text>
          <NeoButton variant="primary" size="small" onPress={handleBatchApprove} loading={actionLoading === -1}>
            批量通过
          </NeoButton>
        </View>
      )}

      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        renderItem={renderItem}
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); fetchData(); }} />}
        ListEmptyComponent={
          <View style={styles.empty}>
            <Text style={{ fontSize: 16, color: '#999' }}>暂无待审批报工</Text>
          </View>
        }
      />
      <AppDialogHost />
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  list: { padding: 16, paddingBottom: 32 },
  card: { marginBottom: 12, padding: 12, borderRadius: 12 },
  cardHeader: { flexDirection: 'row', alignItems: 'center' },
  reporterName: { fontSize: 16, fontWeight: '600', color: '#333' },
  meta: { fontSize: 13, color: '#666', marginTop: 2 },
  quantity: { fontSize: 22, fontWeight: '700', color: '#1890ff', marginLeft: 12 },
  notes: { fontSize: 13, color: '#888', marginTop: 4, marginLeft: 40 },
  actions: { flexDirection: 'row', marginTop: 10, marginLeft: 40 },
  batchBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 12, backgroundColor: '#e6f7ff' },
  batchText: { fontSize: 14, fontWeight: '600', color: '#1890ff' },
  empty: { alignItems: 'center', marginTop: 80 },
});
