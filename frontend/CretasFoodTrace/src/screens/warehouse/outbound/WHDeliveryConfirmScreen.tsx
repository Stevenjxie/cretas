/**
 * 发货确认 (真发货 DLV-*) — 填实际发货数量 → 后端扣减成品库存 + 转 SHIPPED.
 *
 * 防呆 (六扇门 F006 仓管场景):
 *  - Rule 1 上限预显: 每行实发数量 input :max = 计划数量, 超限 disable 提交, 不事后报错.
 *  - Rule 2 上下文: 顶部带 单号 / 客户 / 销售单 / 计划数量, 每行带品名, 仓管一眼看清发什么.
 *  - 4 位一体: 确认失败原样透传后端 message (如批次未分配 409), sticky 显示, 不吞成"操作失败".
 *  - Alert 陷阱: 确认写操作由页面按钮直接触发, **不** 藏在 Alert.alert 回调里 (Expo web 上失效).
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, ScrollView, StyleSheet, TouchableOpacity } from 'react-native';
import { Text, Surface, Button, TextInput, Snackbar, ActivityIndicator } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { isAxiosError } from 'axios';
import { WHOutboundStackParamList } from '../../../types/navigation';
import {
  warehouseDeliveryApiClient,
  WarehouseDeliveryRecord,
  WarehouseDeliveryItem,
} from '../../../services/api/warehouseDeliveryApiClient';
import { handleError } from '../../../utils/errorHandler';
import { validateQty, canSubmitRows, buildActualQuantities } from './warehouseDeliveryConfirm.logic';

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList, 'WHDeliveryConfirm'>;
type ScreenRoute = RouteProp<WHOutboundStackParamList, 'WHDeliveryConfirm'>;

interface EditableItem {
  id: string;
  productName: string;
  plannedQty: number;
  unit: string;
  /** 用户输入的实发数量 (字符串, 允许编辑中间态). */
  actualQtyText: string;
}

function itemDisplayName(it: WarehouseDeliveryItem): string {
  return it.productName || `产品-${it.productTypeId || ''}`;
}

export function WHDeliveryConfirmScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<ScreenRoute>();
  const { deliveryId, deliveryNumber } = route.params;

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [record, setRecord] = useState<WarehouseDeliveryRecord | null>(null);
  const [items, setItems] = useState<EditableItem[]>([]);
  const [snackbar, setSnackbar] = useState<{ visible: boolean; message: string; error: boolean }>({
    visible: false,
    message: '',
    error: false,
  });

  const loadDetail = useCallback(async () => {
    setLoading(true);
    try {
      const res = await warehouseDeliveryApiClient.getDeliveryDetail(deliveryId);
      if (!res.success || !res.data) {
        handleError(new Error(res.message || '加载发货单明细失败'), { title: '加载失败' });
        return;
      }
      setRecord(res.data);
      const detailItems = Array.isArray(res.data.items) ? res.data.items : [];
      setItems(
        detailItems.map((it) => {
          const planned = Number(it.deliveredQuantity) || 0;
          return {
            id: String(it.id ?? ''),
            productName: itemDisplayName(it),
            plannedQty: planned,
            unit: it.unit || 'kg',
            actualQtyText: String(planned), // 默认实发 = 计划
          };
        }),
      );
    } catch (error) {
      handleError(error, { title: '加载发货单明细失败' });
    } finally {
      setLoading(false);
    }
  }, [deliveryId]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  const setItemQty = (id: string, text: string) => {
    setItems((prev) => prev.map((it) => (it.id === id ? { ...it, actualQtyText: text } : it)));
  };

  const firstError = items
    .map((it) => validateQty(it.actualQtyText, it.plannedQty))
    .find((e) => e != null);
  const hasError = firstError != null;
  const anyChanged = items.some((it) => Number(it.actualQtyText) !== it.plannedQty);
  const canSubmit = !loading && !submitting && canSubmitRows(items);

  // 确认写操作 — 页面按钮直接调用, 不经 Alert 回调 (Expo web 上 Alert 回调失效).
  const submitConfirm = async () => {
    if (!canSubmit) return;
    const actualQuantities = buildActualQuantities(items);
    setSubmitting(true);
    try {
      const res = await warehouseDeliveryApiClient.confirmDelivery(deliveryId, actualQuantities);
      if (res.success) {
        setSnackbar({ visible: true, message: '发货确认成功，成品库存已扣减', error: false });
        // 让用户看到成功提示后返回列表 (列表 focus 时自动刷新, 该单从待确认队列消失)
        setTimeout(() => navigation.goBack(), 900);
      } else {
        setSnackbar({ visible: true, message: res.message || '发货确认失败', error: true });
      }
    } catch (error) {
      // 4 位一体: 原样透传后端 message (如 "发货行 51 未完成批次分配，无法确认发货" 409).
      let message = '发货确认失败，请重试';
      if (isAxiosError(error)) {
        const data = error.response?.data as { message?: string; actionHint?: string } | undefined;
        if (data?.message) {
          message = data.actionHint ? `${data.message}（${data.actionHint}）` : data.message;
        }
      }
      setSnackbar({ visible: true, message, error: true });
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Header title="发货确认" onBack={() => navigation.goBack()} />
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载发货单明细...</Text>
        </View>
      </SafeAreaView>
    );
  }

  const customerName = record?.customerName || record?.customerId || '未知客户';
  const orderNumber = record?.orderNumber || record?.salesOrderId || '无关联订单';

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Header title="发货确认" onBack={() => navigation.goBack()} />

      <ScrollView style={styles.content} keyboardShouldPersistTaps="handled">
        {/* Rule 2: 上下文 — 单号 / 客户 / 销售单 */}
        <Surface style={styles.card} elevation={1}>
          <Text style={styles.cardTitle}>{record?.deliveryNumber || deliveryNumber || `DLV-${deliveryId}`}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>客户</Text>
            <Text style={styles.infoValue}>{customerName}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>销售单</Text>
            <Text style={styles.infoValue}>{orderNumber}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>计划日期</Text>
            <Text style={styles.infoValue}>{record?.deliveryDate || '-'}</Text>
          </View>
          {!!record?.logisticsCompany && (
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>物流公司</Text>
              <Text style={styles.infoValue}>{record.logisticsCompany}</Text>
            </View>
          )}
        </Surface>

        {/* 提示: 默认按计划, 改动需重新分配批次 */}
        <Surface style={styles.warnCard} elevation={1}>
          <MaterialCommunityIcons name="alert-outline" size={18} color="#f57c00" />
          <Text style={styles.warnText}>
            请填写每行实际发货数量（默认 = 计划数量）。确认后系统扣减成品库存。
            {anyChanged ? ' 已修改数量 —— 如批次已分配，需先到"发货记录"重新分配保证总量匹配。' : ''}
          </Text>
        </Surface>

        {/* Rule 1: 每行实发数量, 计划作上限 */}
        <View style={styles.itemsWrap}>
          {items.length === 0 ? (
            <Surface style={styles.card} elevation={1}>
              <Text style={styles.emptyText}>此发货单无明细行</Text>
            </Surface>
          ) : (
            items.map((it) => {
              const err = validateQty(it.actualQtyText, it.plannedQty);
              return (
                <Surface key={it.id} style={styles.itemCard} elevation={1}>
                  <Text style={styles.itemName}>{it.productName}</Text>
                  <View style={styles.itemQtyRow}>
                    <View style={styles.plannedBox}>
                      <Text style={styles.plannedLabel}>计划数量</Text>
                      <Text style={styles.plannedValue}>
                        {it.plannedQty} {it.unit}
                      </Text>
                    </View>
                    <View style={styles.actualBox}>
                      <TextInput
                        mode="outlined"
                        label={`实发 (最多 ${it.plannedQty})`}
                        value={it.actualQtyText}
                        onChangeText={(t) => setItemQty(it.id, t)}
                        keyboardType="numeric"
                        dense
                        error={err != null}
                        right={<TextInput.Affix text={it.unit} />}
                        style={styles.qtyInput}
                        testID={`wh-actual-qty-${it.id}`}
                      />
                      {!!err && <Text style={styles.errText}>{err}</Text>}
                    </View>
                  </View>
                </Surface>
              );
            })
          )}
        </View>

        <View style={{ height: 100 }} />
      </ScrollView>

      {/* 底部确认按钮 — 直接触发写操作, 不藏 Alert 回调 */}
      <Surface style={styles.footer} elevation={4}>
        <Button
          mode="contained"
          onPress={submitConfirm}
          disabled={!canSubmit}
          loading={submitting}
          style={styles.confirmButton}
          contentStyle={styles.confirmButtonContent}
          labelStyle={styles.confirmButtonLabel}
          buttonColor="#4CAF50"
          testID="wh-confirm-delivery-btn"
        >
          {submitting ? '确认中...' : '确认并扣库存'}
        </Button>
        {hasError && <Text style={styles.footerHint}>请修正数量后再确认（{firstError}）</Text>}
      </Surface>

      <Snackbar
        visible={snackbar.visible}
        onDismiss={() => setSnackbar((s) => ({ ...s, visible: false }))}
        duration={snackbar.error ? 8000 : 2000}
        action={snackbar.error ? { label: '关闭', onPress: () => setSnackbar((s) => ({ ...s, visible: false })) } : undefined}
        style={snackbar.error ? styles.snackbarError : styles.snackbarSuccess}
      >
        {snackbar.message}
      </Snackbar>
    </SafeAreaView>
  );
}

function Header({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <View style={styles.header}>
      <TouchableOpacity style={styles.backButton} onPress={onBack} testID="wh-confirm-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableOpacity>
      <Text style={styles.headerTitle}>{title}</Text>
      <View style={{ width: 24 }} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: {
    backgroundColor: '#4CAF50',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  backButton: { padding: 2 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  content: { flex: 1 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, margin: 16, marginBottom: 0 },
  cardTitle: { fontSize: 16, fontWeight: 'bold', color: '#333', marginBottom: 12 },
  infoRow: { flexDirection: 'row', marginBottom: 8 },
  infoLabel: { width: 72, fontSize: 13, color: '#999' },
  infoValue: { flex: 1, fontSize: 13, color: '#333', fontWeight: '500' },
  warnCard: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#fff3e0',
    marginHorizontal: 16,
    marginTop: 16,
    padding: 12,
    borderRadius: 8,
    gap: 8,
  },
  warnText: { flex: 1, fontSize: 12, color: '#e65100', lineHeight: 18 },
  itemsWrap: { paddingHorizontal: 16, paddingTop: 16 },
  itemCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 12 },
  itemName: { fontSize: 15, fontWeight: '600', color: '#333', marginBottom: 12 },
  itemQtyRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  plannedBox: { flex: 1 },
  plannedLabel: { fontSize: 12, color: '#999' },
  plannedValue: { fontSize: 18, fontWeight: 'bold', color: '#333', marginTop: 4 },
  actualBox: { flex: 1.4 },
  qtyInput: { backgroundColor: '#fff' },
  errText: { fontSize: 11, color: '#f44336', marginTop: 2 },
  emptyText: { fontSize: 14, color: '#999', textAlign: 'center' },
  footer: {
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 20,
  },
  confirmButton: { borderRadius: 8 },
  confirmButtonContent: { height: 48 },
  confirmButtonLabel: { fontSize: 16, fontWeight: '600' },
  footerHint: { fontSize: 12, color: '#f44336', textAlign: 'center', marginTop: 8 },
  snackbarError: { backgroundColor: '#c62828' },
  snackbarSuccess: { backgroundColor: '#2e7d32' },
});

export default WHDeliveryConfirmScreen;
