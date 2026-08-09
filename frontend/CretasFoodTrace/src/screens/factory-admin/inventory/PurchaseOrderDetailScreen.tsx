import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, DataTable, Divider } from 'react-native-paper';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { purchaseApiClient, PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { todayIso } from '../../../utils/orderDate';
import { AttachmentList, AttachmentUploadButton } from '../../../components/attachment';
import { useAuthStore } from '../../../store/authStore';
import { isMobileBusinessObserver } from '../../../utils/mobileRoleBoundaries';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  SUBMITTED: { label: '已提交', color: '#e6a23c' },
  APPROVED: { label: '已审批', color: '#409eff' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
  PARTIAL_RECEIVED: { label: '部分收货', color: '#e6a23c' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
};

export default function PurchaseOrderDetailScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((state) => state.user);
  const isOperationsReadOnly = isMobileBusinessObserver(user);
  const route = useRoute<RouteProp<FAManagementStackParamList, 'PurchaseOrderDetail'>>();
  const { orderId } = route.params;

  const [order, setOrder] = useState<PurchaseOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [attachmentRefreshKey, setAttachmentRefreshKey] = useState(0);

  useEffect(() => { loadOrder(); }, [orderId]);

  const loadOrder = async () => {
    try {
      setLoading(true);
      const res = await purchaseApiClient.getOrder(orderId);
      if (res.success) setOrder(res.data);
    } catch { Alert.alert('错误', '加载失败'); }
    finally { setLoading(false); }
  };

  const handleAction = async (action: string) => {
    const actionLabels: Record<string, string> = {
      submit: '提交审批', approve: '审批通过', reject: '驳回', cancel: '取消',
    };
    Alert.alert('确认', `确认${actionLabels[action]}？`, [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: async () => {
        try {
          let res;
          switch (action) {
            case 'submit': res = await purchaseApiClient.submitOrder(orderId); break;
            case 'approve': res = await purchaseApiClient.approveOrder(orderId); break;
            case 'reject': res = await purchaseApiClient.rejectOrder(orderId); break;
            case 'cancel': res = await purchaseApiClient.cancelOrder(orderId); break;
          }
          if (res?.success) { Alert.alert('成功', '操作成功'); loadOrder(); }
        } catch { Alert.alert('错误', '操作失败'); }
      }},
    ]);
  };

  const handleEditDraft = () => {
    if (!order) return;
    Alert.prompt(
      '编辑备注',
      '修改采购单备注（其他字段请在Web端编辑）',
      [
        { text: '取消', style: 'cancel' },
        { text: '保存', onPress: async (newRemark) => {
          try {
            const items = (order.items || []).map(item => ({
              materialTypeId: item.materialTypeId,
              quantity: item.quantity,
              unitPrice: item.unitPrice,
              unit: item.unit || 'kg',
            }));
            const res = await purchaseApiClient.updateOrder(orderId, {
              supplierId: order.supplierId,
              // 后端 @NotNull —— 改备注也走整单更新契约, 不带就 400。
              // 用单据自己的下单日期, 不是今天: 这是"改备注", 不该把历史单的下单日期改掉。
              orderDate: order.orderDate || todayIso(),
              items,
              remark: newRemark || order.remark,
            });
            if (res?.success) { Alert.alert('成功', '已更新'); loadOrder(); }
          } catch { Alert.alert('错误', '更新失败'); }
        }},
      ],
      'plain-text',
      order.remark || '',
    );
  };

  if (loading) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="采购单详情" /></Appbar.Header>
      <ActivityIndicator style={styles.loader} size="large" />
    </View>
  );

  if (!order) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="采购单详情" /></Appbar.Header>
      <Text style={styles.empty}>订单不存在</Text>
    </View>
  );

  const status = STATUS_MAP[order.status] || { label: order.status, color: '#909399' };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="采购单详情" />
        <Appbar.Action icon="refresh" onPress={loadOrder} />
      </Appbar.Header>

      <ScrollView style={styles.scroll}>
        {/* 基本信息 */}
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.row}>
              <Text variant="titleMedium" style={styles.bold}>{order.orderNumber}</Text>
              <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
            </View>
            <Divider style={styles.divider} />
            <InfoRow label="供应商" value={order.supplierName || order.supplierId} />
            <InfoRow label="总金额" value={`¥${formatNumberWithCommas(order.totalAmount)}`} valueStyle={styles.amountText} />
            <InfoRow label="下单日期" value={order.orderDate} />
            <InfoRow label="预计到货" value={order.expectedDeliveryDate || '-'} />
            <InfoRow label="审批人" value={order.approvedBy || '-'} />
            {order.remark ? <InfoRow label="备注" value={order.remark} /> : null}
          </Card.Content>
        </Card>

        {/* 订单明细 */}
        <Card style={styles.card}>
          <Card.Title title="订单明细" titleVariant="titleSmall" />
          <Card.Content>
            <DataTable>
              <DataTable.Header>
                <DataTable.Title>原料</DataTable.Title>
                <DataTable.Title numeric>数量</DataTable.Title>
                <DataTable.Title numeric>已收</DataTable.Title>
                <DataTable.Title numeric>单价</DataTable.Title>
              </DataTable.Header>
              {(order.items || []).map((item, idx) => (
                <DataTable.Row key={idx}>
                  <DataTable.Cell>{item.materialTypeName || item.materialTypeId}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.quantity} {item.unit}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.receivedQuantity}</DataTable.Cell>
                  <DataTable.Cell numeric>¥{formatNumberWithCommas(item.unitPrice)}</DataTable.Cell>
                </DataTable.Row>
              ))}
            </DataTable>
          </Card.Content>
        </Card>

        {/* 收货记录 */}
        {(order.receiveRecords || []).length > 0 && (
          <Card style={styles.card}>
            <Card.Title title="收货记录" titleVariant="titleSmall" />
            <Card.Content>
              {order.receiveRecords!.map((rec, idx) => (
                <View key={idx} style={styles.receiveItem}>
                  <View style={styles.row}>
                    <Text style={styles.receiveNum}>{rec.receiveNumber}</Text>
                    <Text style={styles.receiveDate}>{rec.receiveDate}</Text>
                  </View>
                  {(rec.items || []).map((ri, j) => (
                    <Text key={j} style={styles.receiveDetail}>
                      {ri.materialTypeName || ri.materialTypeId}: {ri.receivedQuantity} {ri.unit}
                    </Text>
                  ))}
                </View>
              ))}
            </Card.Content>
          </Card>
        )}

        {/* 附件 (C-ATT-1) */}
        <Card style={styles.card}>
          <Card.Title title="附件" titleVariant="titleSmall" />
          <Card.Content>
            <AttachmentList
              entityType="PURCHASE_ORDER"
              entityId={String(orderId)}
              refreshKey={attachmentRefreshKey}
            />
            {!isOperationsReadOnly && <AttachmentUploadButton
              entityType="PURCHASE_ORDER"
              entityId={String(orderId)}
              businessTag="PURCHASE_DOC"
              onUploaded={() => setAttachmentRefreshKey(k => k + 1)}
            />}
          </Card.Content>
        </Card>

        {/* 操作按钮 */}
        {!isOperationsReadOnly && <View style={styles.actionBar}>
          {order.status === 'DRAFT' && (
            <>
              <Button mode="contained" onPress={() => handleAction('submit')} style={styles.actionBtn}>提交审批</Button>
              <Button mode="contained-tonal" onPress={handleEditDraft} style={styles.actionBtn}>编辑</Button>
              <Button mode="outlined" onPress={() => handleAction('cancel')} style={styles.actionBtn}>取消</Button>
            </>
          )}
          {order.status === 'SUBMITTED' && (
            <>
              <Button mode="contained" onPress={() => handleAction('approve')} style={styles.actionBtn}>审批通过</Button>
              <Button mode="outlined" textColor="#f56c6c" onPress={() => handleAction('reject')} style={styles.actionBtn}>驳回</Button>
            </>
          )}
        </View>}

        <View style={{ height: 30 }} />
      </ScrollView>
    </View>
  );
}

function InfoRow({ label, value, valueStyle }: { label: string; value: string; valueStyle?: any }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={[styles.infoValue, valueStyle]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  scroll: { flex: 1 },
  loader: { flex: 1, justifyContent: 'center' },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
  card: { margin: 12, marginBottom: 0, borderRadius: 10 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  bold: { fontWeight: '700' },
  divider: { marginVertical: 10 },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  infoLabel: { color: '#909399', fontSize: 13 },
  infoValue: { fontSize: 13, color: '#333' },
  amountText: { fontWeight: '700', color: '#e6a23c' },
  receiveItem: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  receiveNum: { fontWeight: '600', fontSize: 13 },
  receiveDate: { fontSize: 12, color: '#909399' },
  receiveDetail: { fontSize: 12, color: '#666', marginTop: 2, marginLeft: 8 },
  actionBar: { flexDirection: 'row', gap: 10, padding: 12 },
  actionBtn: { flex: 1 },
});
