/**
 * SP6 收货 — 采购单选择页 (无扫码直接选单流)
 *
 * 场景: 仓管员手机上直接选择待收货的采购单, 不需要扫 PDF QR.
 * 补全了 WHReceiptCreateScreen 只能通过扫码到达的缺口.
 *
 * 入口:
 *   WHInboundListScreen → "选采购单收货" 按钮 → 本页
 *   本页 → 点任意一张采购单卡片 → WHReceiptCreate
 *
 * 显示状态过滤: APPROVED / FINANCE_APPROVED / PARTIAL_RECEIVED
 *   (DRAFT/SUBMITTED/PENDING_FINANCE_REVIEW 未审批通过, 不能收货)
 *   (COMPLETED/CLOSED/CANCELLED 不需要收货)
 *
 * 防呆 Rule 1: 卡片显示订单数量 + 已收数量 + 待收数量 (上限可收多少)
 * 防呆 Rule 2: 卡片标题显示 供应商名 + 订单号 + 期望交货日 (上下文)
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  FlatList,
  RefreshControl,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Badge,
  Button,
  Card,
  Chip,
  Text,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { isAxiosError } from 'axios';

import { WHInboundStackParamList } from '../../../types/navigation';
import {
  purchaseApiClient,
  PurchaseOrder,
} from '../../../services/api/purchaseApiClient';
import { handleError } from '../../../utils/errorHandler';
import { useAuthStore } from '../../../store/authStore';

/** 加载失败态 (区别于"真的没有待收货采购单"的正常空态) */
interface LoadFailure {
  title: string;
  message: string;
  hint?: string;
  isPermission: boolean;
}

/**
 * 把三路 getOrdersByStatus 请求全部失败的原因翻成仓管员看得懂的话.
 *
 * fool-proof-design 4位一体: message 用后端原话 (PermissionInterceptor 403 body 的
 * message/actionHint 字段), 不用 axios 通用 "Request failed with status code 403".
 * 这里专门区分 403(权限) vs 其他(网络/5xx), 避免用户把"没权限"误当"工厂没有待收货单".
 */
function describeListFailure(reason: unknown): LoadFailure {
  if (isAxiosError(reason)) {
    const status = reason.response?.status;
    const data = reason.response?.data as { message?: string; actionHint?: string } | undefined;
    const backendMsg = data?.message;
    const actionHint = data?.actionHint;

    if (!reason.response) {
      return {
        title: '网络连接失败',
        message: '手机暂时连不上服务器，请检查网络后重试。',
        isPermission: false,
      };
    }
    if (status === 403) {
      return {
        title: '无采购收货权限',
        message: backendMsg || '您的账号无权限查看待收货采购单。',
        hint: actionHint || '请联系工厂管理员开通 [采购管理] 或 [仓储管理] 权限',
        isPermission: true,
      };
    }
    if (status === 401) {
      return {
        title: '登录已过期',
        message: '请重新登录后再试。',
        isPermission: false,
      };
    }
    return {
      title: '加载待收货采购单失败',
      message: backendMsg || `服务器返回错误 (${status ?? '未知'})，请稍后重试。`,
      hint: actionHint,
      isPermission: false,
    };
  }
  return {
    title: '加载待收货采购单失败',
    message: reason instanceof Error ? reason.message : '未知错误，请重试。',
    isPermission: false,
  };
}

type Nav = NativeStackNavigationProp<WHInboundStackParamList, 'WHPurchaseReceiveList'>;

/** 可收货的订单状态 */
const RECEIVABLE_STATUSES: PurchaseOrder['status'][] = [
  'APPROVED',
  'FINANCE_APPROVED',
  'PARTIAL_RECEIVED',
];

const STATUS_LABEL: Record<string, { text: string; color: string; bg: string }> = {
  APPROVED:          { text: '待收货', color: '#1565c0', bg: '#e3f2fd' },
  FINANCE_APPROVED:  { text: '财审通过', color: '#2e7d32', bg: '#e8f5e9' },
  PARTIAL_RECEIVED:  { text: '部分收货', color: '#e65100', bg: '#fff3e0' },
};

export default function WHPurchaseReceiveListScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  // 区分"请求失败"(权限/网络/服务器) vs "请求成功但确实没有待收货单" — 前者绝不能静默当空态显示.
  const [loadError, setLoadError] = useState<LoadFailure | null>(null);

  const PAGE_SIZE = 20;

  // ============================================================
  // 数据加载: 并行拉三个状态, 合并排序 (按期望交货日升序)
  // ============================================================
  const fetchOrders = useCallback(
    async (pageNum: number, append: boolean) => {
      try {
        const results = await Promise.allSettled(
          RECEIVABLE_STATUSES.map((status) =>
            purchaseApiClient.getOrdersByStatus(
              status,
              { page: pageNum, size: PAGE_SIZE },
              factoryId,
            ),
          ),
        );

        const rejectedResults = results.filter(
          (r): r is PromiseRejectedResult => r.status === 'rejected',
        );
        const fulfilledCount = results.length - rejectedResults.length;
        const firstRejected = rejectedResults[0];

        // 全部 3 个状态的请求都失败 (而不是"某状态碰巧没有单据") — 这是请求本身失败
        // (权限不足 / 网络 / 服务器错误), 不是"工厂真的没有待收货采购单". Promise.allSettled
        // 会吞掉每个 rejection 静默过滤, 之前这里直接当空结果处理 → 仓库员被 403 拒绝时
        // 页面显示"暂无待收货采购单", 看不出是权限问题 (fool-proof-design 4位一体违规:
        // 静默降级、无 next-action). 现在显式区分, loud 展示且不清空已有数据(翻页场景).
        if (fulfilledCount === 0 && firstRejected) {
          const info = describeListFailure(firstRejected.reason);
          if (append) {
            // 翻页失败: 保留已加载列表，只提示，不清空
            handleError(firstRejected.reason, {
              title: info.title,
              customMessage: info.hint ? `${info.message}\n${info.hint}` : info.message,
            });
          } else {
            setLoadError(info);
            setOrders([]);
          }
          setHasMore(false);
          return;
        }

        if (!append) {
          setLoadError(null);
        }

        const merged: PurchaseOrder[] = [];
        results.forEach((r) => {
          if (r.status === 'fulfilled' && r.value?.data?.content) {
            merged.push(...r.value.data.content);
          }
        });

        // 按期望交货日升序 (最紧迫排前), 无日期排末尾
        merged.sort((a, b) => {
          if (!a.expectedDeliveryDate && !b.expectedDeliveryDate) return 0;
          if (!a.expectedDeliveryDate) return 1;
          if (!b.expectedDeliveryDate) return -1;
          return a.expectedDeliveryDate.localeCompare(b.expectedDeliveryDate);
        });

        if (append) {
          setOrders((prev) => {
            // 去重 (同一 page 不同 status 可能有重叠)
            const existing = new Set(prev.map((o) => o.id));
            return [...prev, ...merged.filter((o) => !existing.has(o.id))];
          });
        } else {
          setOrders(merged);
        }

        // 若任意 status 还有下一页就继续
        const anyHasMore = results.some(
          (r) =>
            r.status === 'fulfilled' &&
            r.value?.data?.totalPages != null &&
            pageNum < r.value.data.totalPages,
        );
        setHasMore(anyHasMore);
      } catch (err) {
        handleError(err, { title: '加载采购单失败' });
      }
    },
    [factoryId],
  );

  useEffect(() => {
    (async () => {
      setLoading(true);
      await fetchOrders(1, false);
      setLoading(false);
    })();
  }, [fetchOrders]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    setPage(1);
    await fetchOrders(1, false);
    setRefreshing(false);
  }, [fetchOrders]);

  const onLoadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    const nextPage = page + 1;
    setLoadingMore(true);
    await fetchOrders(nextPage, true);
    setPage(nextPage);
    setLoadingMore(false);
  }, [loadingMore, hasMore, page, fetchOrders]);

  // ============================================================
  // 点击选单 → 跳到收货页
  // ============================================================
  const handleSelect = useCallback(
    (order: PurchaseOrder) => {
      navigation.navigate('WHReceiptCreate', {
        purchaseOrderId: order.id,
        orderNumber: order.orderNumber,
      });
    },
    [navigation],
  );

  // ============================================================
  // 计算订单待收量摘要 (防呆 Rule 1)
  // ============================================================
  const pendingQtySummary = useCallback((order: PurchaseOrder): string => {
    if (!order.items || order.items.length === 0) return '暂无明细';
    const lines = order.items.map((it) => {
      const planned = it.quantity || 0;
      const received = it.receivedQuantity || 0;
      const pending = Math.max(0, planned - received);
      const name = it.materialTypeName || it.materialTypeId;
      return `${name}: 待收 ${pending}${it.unit ? ' ' + it.unit : ''}`;
    });
    return lines.slice(0, 3).join(' · ') + (lines.length > 3 ? ` 等 ${lines.length} 项` : '');
  }, []);

  // ============================================================
  // 渲染单张卡片
  // ============================================================
  const renderItem = useCallback(
    ({ item: order }: { item: PurchaseOrder }) => {
      const statusConf = STATUS_LABEL[order.status] ?? {
        text: order.status,
        color: '#616161',
        bg: '#f5f5f5',
      };

      // 防呆 Rule 1: 汇总每行待收数量
      const totalPlanned = order.items?.reduce((s, i) => s + (i.quantity || 0), 0) ?? 0;
      const totalReceived = order.items?.reduce((s, i) => s + (i.receivedQuantity || 0), 0) ?? 0;
      const totalPending = Math.max(0, totalPlanned - totalReceived);

      return (
        <TouchableOpacity
          activeOpacity={0.75}
          onPress={() => handleSelect(order)}
          accessibilityLabel={`选择采购单 ${order.orderNumber} 进行收货`}
        >
          <Card style={styles.card}>
            <Card.Content>
              {/* 顶行: 订单号 + 状态 Chip */}
              <View style={styles.cardHeaderRow}>
                <Text style={styles.orderNumber}>{order.orderNumber}</Text>
                <Chip
                  compact
                  style={[styles.statusChip, { backgroundColor: statusConf.bg }]}
                  textStyle={[styles.statusText, { color: statusConf.color }]}
                >
                  {statusConf.text}
                </Chip>
              </View>

              {/* 防呆 Rule 2: 供应商 + 期望交货日 (上下文) */}
              <View style={styles.metaRow}>
                <MaterialCommunityIcons name="factory" size={14} color="#718096" />
                <Text style={styles.metaText}>
                  {order.supplierName || '未知供应商'}
                </Text>
              </View>
              {order.expectedDeliveryDate && (
                <View style={styles.metaRow}>
                  <MaterialCommunityIcons name="calendar-clock" size={14} color="#718096" />
                  <Text style={styles.metaText}>
                    期望交货: {order.expectedDeliveryDate}
                  </Text>
                </View>
              )}

              {/* 防呆 Rule 1: 待收数量汇总 */}
              {order.items && order.items.length > 0 && (
                <View style={styles.qtyRow}>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>订单合计</Text>
                    <Text style={styles.qtyStatValue}>{totalPlanned}</Text>
                  </View>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>已收</Text>
                    <Text style={[styles.qtyStatValue, { color: '#2e7d32' }]}>
                      {totalReceived}
                    </Text>
                  </View>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>待收</Text>
                    <Text
                      style={[
                        styles.qtyStatValue,
                        { color: totalPending > 0 ? '#e65100' : '#757575' },
                      ]}
                    >
                      {totalPending}
                    </Text>
                  </View>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>明细行</Text>
                    <Text style={styles.qtyStatValue}>{order.items.length}</Text>
                  </View>
                </View>
              )}

              {/* 物料简要列表 */}
              <Text style={styles.itemsHint} numberOfLines={2}>
                {pendingQtySummary(order)}
              </Text>

              <View style={styles.actionRow}>
                <Button
                  mode="contained"
                  compact
                  icon="check-circle-outline"
                  onPress={() => handleSelect(order)}
                  style={styles.receiveButton}
                  labelStyle={styles.receiveButtonLabel}
                >
                  开始收货
                </Button>
              </View>
            </Card.Content>
          </Card>
        </TouchableOpacity>
      );
    },
    [handleSelect, pendingQtySummary],
  );

  // ============================================================
  // Footer loader
  // ============================================================
  const ListFooter = useCallback(
    () =>
      loadingMore ? (
        <View style={styles.footerLoader}>
          <ActivityIndicator size="small" />
          <Text style={styles.footerText}>加载更多...</Text>
        </View>
      ) : null,
    [loadingMore],
  );

  const ListEmpty = useCallback(
    () =>
      loading ? null : (
        <View style={styles.emptyContainer}>
          <MaterialCommunityIcons
            name="package-variant-closed"
            size={56}
            color="#bdbdbd"
          />
          <Text style={styles.emptyTitle}>暂无待收货采购单</Text>
          <Text style={styles.emptyHint}>
            需状态为"审批通过"、"财审通过"或"部分收货"的采购单才会在此显示
          </Text>
          <Button
            mode="outlined"
            icon="refresh"
            onPress={onRefresh}
            style={{ marginTop: 16 }}
          >
            刷新
          </Button>
        </View>
      ),
    [loading, onRefresh],
  );

  // ============================================================
  // 加载失败态 — 与"暂无待收货采购单"视觉上明确区分 (红色警示图标 + 具体后端原因 +
  // next-action 提示), 防止仓库员把"无权限"误当"工厂没有待收货单据". (fool-proof-design
  // Rule 5 + 4位一体)
  // ============================================================
  const LoadErrorView = useCallback(() => {
    if (!loadError) return null;
    return (
      <View style={styles.errorContainer}>
        <MaterialCommunityIcons
          name={loadError.isPermission ? 'lock-alert-outline' : 'alert-circle-outline'}
          size={56}
          color="#c62828"
        />
        <Text style={styles.errorTitle}>{loadError.title}</Text>
        <Text style={styles.errorMessage}>{loadError.message}</Text>
        {loadError.hint && <Text style={styles.errorHint}>{loadError.hint}</Text>}
        <Button
          mode="contained"
          icon="refresh"
          onPress={onRefresh}
          style={{ marginTop: 16 }}
        >
          重试
        </Button>
      </View>
    );
  }, [loadError, onRefresh]);

  // ============================================================
  // Render
  // ============================================================
  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="选采购单收货" subtitle="选择一张采购单开始入库录入" />
        {orders.length > 0 && (
          <Badge style={styles.badge}>{orders.length}</Badge>
        )}
      </Appbar.Header>

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载待收货采购单中...</Text>
        </View>
      ) : loadError ? (
        <LoadErrorView />
      ) : (
        <FlatList
          data={orders}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
          onEndReached={onLoadMore}
          onEndReachedThreshold={0.3}
          ListFooterComponent={ListFooter}
          ListEmptyComponent={ListEmpty}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  loadingText: { fontSize: 14, color: '#666' },

  listContent: { padding: 12, paddingBottom: 32 },

  card: { marginBottom: 12, borderRadius: 10 },

  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  orderNumber: { fontSize: 15, fontWeight: '700', color: '#1a202c', flex: 1, marginRight: 8 },

  statusChip: { height: 24, borderRadius: 12 },
  statusText: { fontSize: 12, lineHeight: 14 },

  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    marginBottom: 3,
  },
  metaText: { fontSize: 13, color: '#718096' },

  qtyRow: {
    flexDirection: 'row',
    backgroundColor: '#f7fafc',
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 4,
    marginTop: 8,
    marginBottom: 6,
  },
  qtyStat: { flex: 1, alignItems: 'center' },
  qtyStatLabel: { fontSize: 11, color: '#718096' },
  qtyStatValue: { fontSize: 16, fontWeight: '700', color: '#2d3748', marginTop: 2 },

  itemsHint: { fontSize: 12, color: '#a0aec0', lineHeight: 18, marginBottom: 8 },

  actionRow: { flexDirection: 'row', justifyContent: 'flex-end' },
  receiveButton: { borderRadius: 20 },
  receiveButtonLabel: { fontSize: 13 },

  footerLoader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 16,
    gap: 8,
  },
  footerText: { fontSize: 13, color: '#718096' },

  emptyContainer: {
    alignItems: 'center',
    paddingTop: 60,
    paddingHorizontal: 32,
  },
  emptyTitle: { fontSize: 16, fontWeight: '600', color: '#4a5568', marginTop: 16 },
  emptyHint: {
    fontSize: 13,
    color: '#718096',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },

  // 加载失败态 (403/网络/服务器) — 红色警示, 与上方中性灰的"暂无数据"空态明确区分.
  errorContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: 60,
    paddingHorizontal: 32,
  },
  errorTitle: { fontSize: 16, fontWeight: '700', color: '#c62828', marginTop: 16 },
  errorMessage: {
    fontSize: 14,
    color: '#4a5568',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },
  errorHint: {
    fontSize: 13,
    color: '#e65100',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },

  badge: { marginRight: 12, backgroundColor: '#1976d2' },
});
