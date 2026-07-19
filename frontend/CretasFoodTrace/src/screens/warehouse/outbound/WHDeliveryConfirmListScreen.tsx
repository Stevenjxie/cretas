/**
 * 发货确认列表 (真发货 DLV-* 流程) — 仓管员主发货界面.
 *
 * 列出销售员创建的、待仓库确认的发货单 (SalesDeliveryRecord, DLV-*).
 * 仓管点"确认并发货" → 填实际发货数量 → 后端扣减成品库存 (真库存移动) + 转 SHIPPED.
 *
 * 区别于老"手工出货登记" (SH-*, /shipments) —— 旧链路已冻结，仅保留历史查询.
 * 客户原话 (六扇门 F006 仓管场景): "你告诉他这个东西你要收多少就行了" → 防呆: 计划数量作上限, 上下文带全.
 */

import React, { useState, useCallback } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
  ActivityIndicator,
} from 'react-native';
import { Text, Surface, Button } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WHOutboundStackParamList } from '../../../types/navigation';
import {
  warehouseDeliveryApiClient,
  WarehouseDeliveryRecord,
} from '../../../services/api/warehouseDeliveryApiClient';
import { handleError } from '../../../utils/errorHandler';

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList>;

interface StatusStyle {
  label: string;
  color: string;
  bgColor: string;
}
const DEFAULT_STATUS: StatusStyle = { label: '待仓库确认', color: '#f57c00', bgColor: '#fff3e0' };
const statusConfig: Record<string, StatusStyle> = {
  DRAFT: { label: '草稿', color: '#616161', bgColor: '#f5f5f5' },
  PENDING_WAREHOUSE_CONFIRM: DEFAULT_STATUS,
  PICKED: { label: '已拣货', color: '#1976d2', bgColor: '#e3f2fd' },
};

function customerText(row: WarehouseDeliveryRecord): string {
  return row.customerName || row.customerId || '未知客户';
}

function orderText(row: WarehouseDeliveryRecord): string {
  return row.orderNumber || row.salesOrderId || '无关联订单';
}

export function WHDeliveryConfirmListScreen() {
  const navigation = useNavigation<NavigationProp>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [deliveries, setDeliveries] = useState<WarehouseDeliveryRecord[]>([]);
  const [total, setTotal] = useState(0);

  const loadData = useCallback(async () => {
    try {
      // 后端 pending 分页是 1-based
      const res = await warehouseDeliveryApiClient.getPendingDeliveries({ page: 1, size: 50 });
      if (res.success && res.data) {
        setDeliveries(res.data.content ?? []);
        setTotal(res.data.totalElements ?? (res.data.content?.length ?? 0));
      } else {
        setDeliveries([]);
        setTotal(0);
      }
    } catch (error) {
      handleError(error, { title: '加载待确认发货单失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // 从确认页返回后自动刷新 (已确认的发货单应从待确认队列消失)
  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData]),
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  const openConfirm = (row: WarehouseDeliveryRecord) => {
    navigation.navigate('WHDeliveryConfirm', {
      deliveryId: row.id,
      deliveryNumber: row.deliveryNumber,
    });
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>发货确认</Text>
          <Text style={styles.headerSubtitle}>加载中...</Text>
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载待确认发货单...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>发货确认</Text>
        <Text style={styles.headerSubtitle}>待仓库确认 {total} 单</Text>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {/* 概念说明 (对齐 web-admin ConceptDisambiguationAlert) */}
        <Surface style={styles.noticeCard} elevation={1}>
          <MaterialCommunityIcons name="information-outline" size={20} color="#1976d2" />
          <Text style={styles.noticeText}>
            销售员创建的发货单列在这里。确认实际发货数量后，系统自动扣减成品库存并生成送货单。
          </Text>
        </Surface>

        {/* 二级入口: 老手工出货登记 (SH-*, 不扣库存) */}
        <View style={styles.actionBar}>
          <Button
            mode="outlined"
            icon="clipboard-text-outline"
            onPress={() => navigation.navigate('WHOutboundList')}
            style={styles.secondaryButton}
            labelStyle={styles.secondaryButtonLabel}
            testID="wh-goto-manual-shipment"
          >
            手工出货登记 (旧)
          </Button>
        </View>

        {/* 列表 */}
        <View style={styles.listContainer}>
          {deliveries.length === 0 ? (
            <Surface style={styles.emptyCard} elevation={1}>
              <MaterialCommunityIcons name="package-variant-closed" size={40} color="#bbb" />
              <Text style={styles.emptyText}>暂无待确认发货单</Text>
              <Text style={styles.emptyHint}>销售员创建发货单后会出现在这里</Text>
            </Surface>
          ) : (
            deliveries.map((row) => {
              const config = statusConfig[String(row.status).toUpperCase()] ?? DEFAULT_STATUS;
              return (
                <TouchableOpacity key={row.id} onPress={() => openConfirm(row)} activeOpacity={0.7}>
                  <Surface style={styles.orderCard} elevation={1}>
                    <View style={styles.cardHeader}>
                      <Text style={styles.orderNumber}>{row.deliveryNumber || `DLV-${row.id}`}</Text>
                      <View style={[styles.statusBadge, { backgroundColor: config.bgColor }]}>
                        <Text style={[styles.statusText, { color: config.color }]}>{config.label}</Text>
                      </View>
                    </View>

                    <View style={styles.cardContent}>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>客户</Text>
                        <Text style={styles.infoValue}>{customerText(row)}</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>销售单</Text>
                        <Text style={styles.infoValue}>{orderText(row)}</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>计划日期</Text>
                        <Text style={styles.infoValue}>{row.deliveryDate || '-'}</Text>
                      </View>
                      {!!row.logisticsCompany && (
                        <View style={styles.infoRow}>
                          <Text style={styles.infoLabel}>物流</Text>
                          <Text style={styles.infoValue}>{row.logisticsCompany}</Text>
                        </View>
                      )}
                    </View>

                    <View style={styles.cardFooter}>
                      <Text style={styles.itemCountText}>
                        {row.items && row.items.length > 0 ? `${row.items.length} 个产品` : '点击查看明细'}
                      </Text>
                      <View style={styles.actionLink}>
                        <Text style={styles.actionText}>确认并发货</Text>
                        <MaterialCommunityIcons name="chevron-right" size={16} color="#4CAF50" />
                      </View>
                    </View>
                  </Surface>
                </TouchableOpacity>
              );
            })
          )}
        </View>

        <View style={{ height: 24 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  header: { backgroundColor: '#4CAF50', paddingHorizontal: 16, paddingVertical: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSubtitle: { fontSize: 13, color: 'rgba(255,255,255,0.9)', marginTop: 4 },
  content: { flex: 1 },
  noticeCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#e3f2fd',
    marginHorizontal: 16,
    marginTop: 16,
    padding: 12,
    borderRadius: 8,
    gap: 10,
  },
  noticeText: { flex: 1, fontSize: 13, color: '#1976d2' },
  actionBar: { paddingHorizontal: 16, paddingTop: 12 },
  secondaryButton: { borderRadius: 8, borderColor: '#bbb' },
  secondaryButtonLabel: { color: '#666', fontWeight: '600' },
  listContainer: { paddingHorizontal: 16, paddingTop: 12 },
  emptyCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 32,
    alignItems: 'center',
    marginTop: 8,
  },
  emptyText: { marginTop: 12, fontSize: 15, color: '#666', fontWeight: '600' },
  emptyHint: { marginTop: 4, fontSize: 12, color: '#999' },
  orderCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 12 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  orderNumber: { fontSize: 15, fontWeight: '600', color: '#333' },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 },
  statusText: { fontSize: 12, fontWeight: '500' },
  cardContent: { borderTopWidth: 1, borderTopColor: '#f0f0f0', paddingTop: 12 },
  infoRow: { flexDirection: 'row', marginBottom: 8 },
  infoLabel: { width: 60, fontSize: 13, color: '#999' },
  infoValue: { flex: 1, fontSize: 13, color: '#333' },
  cardFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 4,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  itemCountText: { fontSize: 12, color: '#999' },
  actionLink: { flexDirection: 'row', alignItems: 'center' },
  actionText: { fontSize: 13, color: '#4CAF50', fontWeight: '600' },
});

export default WHDeliveryConfirmListScreen;
