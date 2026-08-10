import React, { useCallback, useEffect, useState } from 'react';
import { Alert, FlatList, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  FAB,
  Modal,
  Portal,
  SegmentedButtons,
  Text,
  TextInput,
} from 'react-native-paper';
import { CustomerSelector } from '../../components/common/CustomerSelector';
import {
  CustomerMaterialArrivalNotice,
  operationsApiClient,
} from '../../services/api/operationsApiClient';
import { getErrorMsg } from '../../utils/errorHandler';

type Filter = 'pending' | 'all';

const STATUS_LABELS: Record<CustomerMaterialArrivalNotice['status'], string> = {
  PENDING_APPROVAL: '待审批',
  OPEN: '审批通过',
  PARTIALLY_RECEIVED: '入库任务处理中',
  RECEIVED: '入库任务已完成',
  REJECTED: '已驳回',
  CANCELLED: '已撤回',
};

const EMPTY_FORM = {
  customerId: '',
  customerName: '',
  expectedArrivalAt: '',
  contactName: '',
  contactPhone: '',
  remark: '',
};

function toApiDateTime(value: string): string | undefined | null {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const match = /^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})$/.exec(trimmed);
  return match ? `${match[1]}T${match[2]}:00` : null;
}

export default function OperationsHomeScreen() {
  const [notices, setNotices] = useState<CustomerMaterialArrivalNotice[]>([]);
  const [filter, setFilter] = useState<Filter>('pending');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancellingNoticeId, setCancellingNoticeId] = useState<string | null>(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);

  const loadNotices = useCallback(async (refresh = false) => {
    refresh ? setRefreshing(true) : setLoading(true);
    try {
      const response = await operationsApiClient.listCustomerMaterialArrivals(false);
      if (!response.success) {
        throw new Error(response.message || '无订单入库申请加载失败');
      }
      setNotices(Array.isArray(response.data) ? response.data : []);
    } catch (error) {
      Alert.alert('加载失败', getErrorMsg(error) || '请检查网络后重试');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  const visibleNotices = filter === 'pending'
    ? notices.filter((notice) => notice.status === 'PENDING_APPROVAL')
    : notices;

  useEffect(() => {
    loadNotices();
  }, [loadNotices]);

  const openCreate = () => {
    setForm(EMPTY_FORM);
    setModalVisible(true);
  };

  const submit = async () => {
    if (!form.customerId) {
      Alert.alert('请选择归属客户', '仓储需要知道这批来料属于哪个客户。');
      return;
    }
    const expectedArrivalAt = toApiDateTime(form.expectedArrivalAt);
    if (expectedArrivalAt === null) {
      Alert.alert('预计到达格式不正确', '请按“年-月-日 时:分”填写，例如 2026-08-10 09:30。');
      return;
    }

    setSubmitting(true);
    try {
      const response = await operationsApiClient.createCustomerMaterialArrival({
        customerId: form.customerId,
        expectedArrivalAt,
        contactName: form.contactName.trim() || undefined,
        contactPhone: form.contactPhone.trim() || undefined,
        remark: form.remark.trim() || undefined,
      });
      if (!response.success) {
        throw new Error(response.message || '发送失败');
      }
      setModalVisible(false);
      Alert.alert('申请已提交', '审批通过前不会进入入库任务，也不会增加库存。');
      await loadNotices(true);
    } catch (error) {
      Alert.alert('发送失败', getErrorMsg(error) || '已保留填写内容，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  const confirmCancel = (notice: CustomerMaterialArrivalNotice) => {
    Alert.alert(
      '撤回入库申请',
      `确认撤回 ${notice.noticeNumber}？审批通过后将改由“入库任务与批次”处理。`,
      [
        { text: '返回', style: 'cancel' },
        {
          text: '确认撤回',
          style: 'destructive',
          onPress: async () => {
            setCancellingNoticeId(notice.id);
            try {
              const response = await operationsApiClient.cancelCustomerMaterialArrival(notice.id);
              if (!response.success) {
                throw new Error(response.message || '撤回失败');
              }
              await loadNotices(true);
            } catch (error) {
              Alert.alert('撤回失败', getErrorMsg(error) || '请刷新状态后重试');
            } finally {
              setCancellingNoticeId(null);
            }
          },
        },
      ],
    );
  };

  const renderNotice = ({ item }: { item: CustomerMaterialArrivalNotice }) => {
    const canCancel = item.status === 'PENDING_APPROVAL';
    return (
      <Card style={styles.noticeCard} testID={`operations-arrival-${item.id}`}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <View style={styles.cardTitleWrap}>
              <Text variant="titleMedium">{item.customerName || '客户名称待同步'}</Text>
              <Text style={styles.noticeNumber}>{item.noticeNumber}</Text>
            </View>
            <Chip compact>{STATUS_LABELS[item.status] || item.status}</Chip>
          </View>
          <Text style={styles.meta}>预计到达：{item.expectedArrivalAt || '未填写'}</Text>
          <Text style={styles.meta}>
            任务交接：{item.status === 'PENDING_APPROVAL' ? '审批通过后生成' : item.status === 'OPEN' ? '已进入入库任务与批次' : '请查看当前状态'}
          </Text>
          {(item.contactName || item.contactPhone) && (
            <Text style={styles.meta}>
              联系人：{[item.contactName, item.contactPhone].filter(Boolean).join(' ')}
            </Text>
          )}
          {!!item.remark && <Text style={styles.remark}>{item.remark}</Text>}
          {canCancel && (
            <Button
              mode="outlined"
              textColor="#B3261E"
              onPress={() => confirmCancel(item)}
              style={styles.cancelButton}
              testID={`operations-arrival-cancel-${item.id}`}
              loading={cancellingNoticeId === item.id}
              disabled={cancellingNoticeId !== null}
            >
              撤回申请
            </Button>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.Content title="运营协调" subtitle="无订单入库申请" />
        <Appbar.Action icon="refresh" onPress={() => loadNotices(true)} />
      </Appbar.Header>

      <Card style={styles.boundaryCard}>
        <Card.Content>
          <Text style={styles.boundaryTitle}>这里只申请，不处理入库</Text>
          <Text style={styles.boundaryText}>
            提交后先等审批。通过后才会出现在“入库任务与批次”，申请页不填实物数量。
          </Text>
        </Card.Content>
      </Card>

      <SegmentedButtons
        value={filter}
        onValueChange={(value) => setFilter(value as Filter)}
        buttons={[
          { value: 'pending', label: '待审批' },
          { value: 'all', label: '全部' },
        ]}
        style={styles.filter}
      />

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>正在加载无订单入库申请</Text>
        </View>
      ) : (
        <FlatList
          data={visibleNotices}
          keyExtractor={(item) => item.id}
          renderItem={renderNotice}
          refreshing={refreshing}
          onRefresh={() => loadNotices(true)}
          contentContainerStyle={visibleNotices.length ? styles.list : styles.emptyList}
          ListEmptyComponent={(
            <View style={styles.center}>
              <Text variant="titleMedium">{filter === 'pending' ? '暂无待审批申请' : '暂无无订单入库申请'}</Text>
              <Text style={styles.emptyHint}>需要登记无订单到货时，点击右下角发起申请。</Text>
            </View>
          )}
        />
      )}

      <FAB
        icon="plus"
        label="发起申请"
        style={styles.fab}
        onPress={openCreate}
        testID="operations-arrival-create"
      />

      <Portal>
        <Modal
          visible={modalVisible}
          onDismiss={() => setModalVisible(false)}
          contentContainerStyle={styles.modal}
        >
          <FlatList
            data={[{ key: 'form' }]}
            keyExtractor={(item) => item.key}
            keyboardShouldPersistTaps="handled"
            renderItem={() => (
              <View>
                <Text variant="headlineSmall" style={styles.modalTitle}>发起无订单入库申请</Text>
                <Text style={styles.modalHint}>这里只提交客户和预计到达；审批通过后再由仓管核对实物。</Text>
                <CustomerSelector
                  value={form.customerName}
                  onSelect={(customerId, customerName) => setForm((current) => ({
                    ...current,
                    customerId,
                    customerName,
                  }))}
                  label="归属客户 *"
                  placeholder="选择客户"
                />
                <TextInput
                  mode="outlined"
                  label="预计到达（可选）"
                  placeholder="例如：2026-08-10 09:30"
                  value={form.expectedArrivalAt}
                  onChangeText={(expectedArrivalAt) => setForm((current) => ({ ...current, expectedArrivalAt }))}
                  style={styles.input}
                />
                <TextInput
                  mode="outlined"
                  label="联系人（可选）"
                  value={form.contactName}
                  onChangeText={(contactName) => setForm((current) => ({ ...current, contactName }))}
                  maxLength={100}
                  style={styles.input}
                />
                <TextInput
                  mode="outlined"
                  label="联系电话（可选）"
                  value={form.contactPhone}
                  onChangeText={(contactPhone) => setForm((current) => ({ ...current, contactPhone }))}
                  keyboardType="phone-pad"
                  maxLength={50}
                  style={styles.input}
                />
                <TextInput
                  mode="outlined"
                  label="说明（可选）"
                  value={form.remark}
                  onChangeText={(remark) => setForm((current) => ({ ...current, remark }))}
                  multiline
                  maxLength={1000}
                  style={styles.input}
                />
                <View style={styles.actions}>
                  <Button mode="outlined" onPress={() => setModalVisible(false)} disabled={submitting}>
                    返回
                  </Button>
                  <Button mode="contained" onPress={submit} loading={submitting} disabled={submitting}>
                    提交审批
                  </Button>
                </View>
              </View>
            )}
          />
        </Modal>
      </Portal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F7F5' },
  boundaryCard: { marginHorizontal: 16, marginTop: 12, backgroundColor: '#E8F5E9' },
  boundaryTitle: { color: '#1B5E20', fontWeight: '700', marginBottom: 4 },
  boundaryText: { color: '#2E5D35', lineHeight: 20 },
  filter: { margin: 16 },
  list: { paddingHorizontal: 16, paddingBottom: 96 },
  emptyList: { flexGrow: 1, paddingHorizontal: 24, paddingBottom: 96 },
  noticeCard: { marginBottom: 12 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 },
  cardTitleWrap: { flex: 1 },
  noticeNumber: { color: '#667085', marginTop: 3, marginBottom: 12 },
  meta: { color: '#475467', marginTop: 5 },
  remark: { color: '#344054', marginTop: 10, lineHeight: 20 },
  cancelButton: { marginTop: 14, minHeight: 44 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  loadingText: { marginTop: 12, color: '#667085' },
  emptyHint: { marginTop: 8, color: '#667085', textAlign: 'center', lineHeight: 20 },
  fab: { position: 'absolute', right: 16, bottom: 16 },
  modal: { backgroundColor: '#FFFFFF', margin: 16, padding: 20, borderRadius: 16, maxHeight: '90%' },
  modalTitle: { marginBottom: 6 },
  modalHint: { color: '#667085', lineHeight: 20, marginBottom: 18 },
  input: { marginBottom: 12 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 12, marginTop: 8 },
});
