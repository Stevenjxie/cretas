import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, DataTable, Divider } from 'react-native-paper';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { salesApiClient, SalesOrder } from '../../../services/api/salesApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  CONFIRMED: { label: '已确认', color: '#409eff' },
  PENDING_FINANCE_REVIEW: { label: '待财务审核', color: '#e6a23c' },
  FINANCE_APPROVED: { label: '财务已批准', color: '#67c23a' },
  FINANCE_REJECTED: { label: '财务已驳回', color: '#f56c6c' },
  PROCESSING: { label: '处理中', color: '#e6a23c' },
  PARTIAL_DELIVERED: { label: '部分发货', color: '#f56c6c' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
};

export default function SalesOrderDetailScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProp<FAManagementStackParamList, 'SalesOrderDetail'>>();
  const { orderId } = route.params;

  const [order, setOrder] = useState<SalesOrder | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => { loadOrder(); }, [orderId]);

  const loadOrder = async () => {
    try {
      setLoading(true);
      const res = await salesApiClient.getOrder(orderId);
      if (res.success) setOrder(res.data);
    } catch { Alert.alert('错误', '加载失败'); }
    finally { setLoading(false); }
  };

  const handleAction = async (action: string) => {
    const labels: Record<string, string> = { confirm: '确认订单', cancel: '取消订单' };
    Alert.alert('确认', `确认${labels[action]}？`, [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: async () => {
        try {
          let res;
          if (action === 'confirm') res = await salesApiClient.confirmOrder(orderId);
          else if (action === 'cancel') res = await salesApiClient.cancelOrder(orderId);
          if (res?.success) {
            const status = res.data?.status;
            const message = action === 'confirm' && status === 'FINANCE_APPROVED'
              ? '订单未触发审批阈值，已免审通过'
              : action === 'confirm' && status === 'PENDING_FINANCE_REVIEW'
                ? '订单已进入财务审核'
                : '操作成功';
            Alert.alert('成功', message);
            loadOrder();
          }
        } catch { Alert.alert('错误', '操作失败'); }
      }},
    ]);
  };

  if (loading) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="销售单详情" /></Appbar.Header>
      <ActivityIndicator style={styles.loader} size="large" />
    </View>
  );

  if (!order) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="销售单详情" /></Appbar.Header>
      <Text style={styles.empty}>订单不存在</Text>
    </View>
  );

  const status = STATUS_MAP[order.status] || { label: order.status, color: '#909399' };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="销售单详情" />
        <Appbar.Action icon="refresh" onPress={loadOrder} />
      </Appbar.Header>

      <ScrollView style={styles.scroll}>
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.row}>
              <Text variant="titleMedium" style={styles.bold}>{order.orderNumber}</Text>
              <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
            </View>
            <Divider style={styles.divider} />
            <InfoRow label="客户" value={order.customerName || order.customerId} />
            <InfoRow label="总金额" value={`¥${formatNumberWithCommas(order.totalAmount)}`} valueStyle={styles.amountText} />
            <InfoRow label="下单日期" value={order.orderDate} />
            <InfoRow label="预计交付" value={order.expectedDeliveryDate || '-'} />
            {order.remark ? <InfoRow label="备注" value={order.remark} /> : null}
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          <Card.Title title="订单明细" titleVariant="titleSmall" />
          <Card.Content>
            <DataTable>
              <DataTable.Header>
                <DataTable.Title>产品</DataTable.Title>
                <DataTable.Title numeric>数量</DataTable.Title>
                <DataTable.Title numeric>已发</DataTable.Title>
                <DataTable.Title numeric>单价</DataTable.Title>
              </DataTable.Header>
              {(order.items || []).map((item, idx) => (
                <DataTable.Row key={idx}>
                  <DataTable.Cell>{item.productTypeName || item.productTypeId}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.quantity} {item.unit}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.deliveredQuantity}</DataTable.Cell>
                  <DataTable.Cell numeric>¥{formatNumberWithCommas(item.unitPrice)}</DataTable.Cell>
                </DataTable.Row>
              ))}
            </DataTable>
          </Card.Content>
        </Card>

        {(order.deliveryRecords || []).length > 0 && (
          <Card style={styles.card}>
            <Card.Title title="发货记录" titleVariant="titleSmall" />
            <Card.Content>
              {order.deliveryRecords!.map((rec, idx) => (
                <View key={idx} style={styles.recordItem}>
                  <View style={styles.row}>
                    <Text style={styles.recordNum}>{rec.deliveryNumber}</Text>
                    <Text style={styles.recordDate}>{rec.deliveryDate}</Text>
                  </View>
                  {(rec.items || []).map((di, j) => (
                    <Text key={j} style={styles.recordDetail}>
                      {di.productTypeName || di.productTypeId}: {di.deliveredQuantity} {di.unit}
                    </Text>
                  ))}
                </View>
              ))}
            </Card.Content>
          </Card>
        )}

        <View style={styles.actionBar}>
          {order.status === 'DRAFT' && (
            <>
              <Button mode="contained" onPress={() => handleAction('confirm')} style={styles.actionBtn}>确认订单</Button>
              <Button mode="outlined" onPress={() => handleAction('cancel')} style={styles.actionBtn}>取消</Button>
            </>
          )}
        </View>

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
  amountText: { fontWeight: '700', color: '#67c23a' },
  recordItem: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  recordNum: { fontWeight: '600', fontSize: 13 },
  recordDate: { fontSize: 12, color: '#909399' },
  recordDetail: { fontSize: 12, color: '#666', marginTop: 2, marginLeft: 8 },
  actionBar: { flexDirection: 'row', gap: 10, padding: 12 },
  actionBtn: { flex: 1 },
});
