/**
 * 出货管理列表页面
 * 对应原型: warehouse/outbound.html
 */

import React, { useState, useCallback, useEffect } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import {
  Text,
  Surface,
  Chip,
  Button,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHOutboundStackParamList } from "../../../types/navigation";
import { shipmentApiClient, ShipmentRecord, ShipmentStats } from "../../../services/api/shipmentApiClient";
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList>;

// 出货状态类型
type OutboundStatus = "waiting" | "packing" | "ready" | "shipped" | "delivered";

interface OutboundItem {
  id: string;
  orderNumber: string;
  customer: string;
  products: string;
  quantity: number;
  status: OutboundStatus;
  dispatchTime?: string;
  isUrgent?: boolean;
  packingProgress?: string;
  logistics?: string;
  deliveredAt?: string;
}

/**
 * 将后端 ShipmentRecord.status 映射到前端 OutboundStatus
 * 后端: 'pending' | 'shipped' | 'delivered' | 'returned'
 * 前端: 'waiting' | 'packing' | 'ready' | 'shipped' | 'delivered'
 */
const mapShipmentStatus = (backendStatus: string): OutboundStatus => {
  switch (backendStatus?.toLowerCase()) {
    case 'pending':
      return 'waiting';
    case 'shipped':
      return 'shipped';
    case 'delivered':
      return 'delivered';
    case 'returned':
      return 'delivered'; // 退货也显示为已完成
    default:
      return 'waiting';
  }
};

/**
 * 将 ShipmentRecord 转换为 OutboundItem
 */
const mapShipmentToOutbound = (shipment: ShipmentRecord): OutboundItem => {
  const status = mapShipmentStatus(shipment.status);
  return {
    id: shipment.id,
    orderNumber: shipment.shipmentNumber || shipment.orderNumber || `SH-${shipment.id}`,
    customer: shipment.customerId || '未知客户',
    products: shipment.productName || '产品',
    quantity: shipment.quantity ?? 0,
    status,
    logistics: shipment.logisticsCompany
      ? `${shipment.logisticsCompany}${shipment.trackingNumber ? ' | ' + shipment.trackingNumber : ''}`
      : undefined,
    deliveredAt: status === 'delivered' ? shipment.updatedAt : undefined,
  };
};

const statusConfig: Record<
  OutboundStatus,
  { label: string; color: string; bgColor: string }
> = {
  waiting: { label: "待打包", color: "#f57c00", bgColor: "#fff3e0" },
  packing: { label: "打包中", color: "#1976d2", bgColor: "#e3f2fd" },
  ready: { label: "待发货", color: "#7b1fa2", bgColor: "#f3e5f5" },
  shipped: { label: "已发货", color: "#0097a7", bgColor: "#e0f7fa" },
  delivered: { label: "已送达", color: "#388e3c", bgColor: "#e8f5e9" },
};

export function WHOutboundListScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedStatus, setSelectedStatus] = useState<string>("all");
  const [outboundList, setOutboundList] = useState<OutboundItem[]>([]);
  const [shipmentStats, setShipmentStats] = useState<ShipmentStats | null>(null);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      const [shipmentsResult, statsResult] = await Promise.allSettled([
        // /shipments 后端是 0-based 分页 (defaultValue="0"); 传 page:1 会返回空 content
        // (仅 8 条记录全在 page 0), 导致列表空但统计头显示"待出货 N 单".
        shipmentApiClient.getShipments({ page: 0, size: 50 }),
        shipmentApiClient.getShipmentStats(),
      ]);

      // 处理出货记录
      if (shipmentsResult.status === 'fulfilled') {
        const response = shipmentsResult.value as { data?: ShipmentRecord[] };
        const shipments = response.data ?? [];
        const mapped = shipments.map(mapShipmentToOutbound);
        setOutboundList(mapped);
      }

      // 处理统计数据
      if (statsResult.status === 'fulfilled') {
        const stats = statsResult.value as ShipmentStats;
        setShipmentStats(stats);
      }
    } catch (error) {
      handleError(error, { title: '加载出货数据失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 筛选数据
  const filteredList = outboundList.filter((item) => {
    if (selectedStatus !== "all" && item.status !== selectedStatus) return false;
    return true;
  });

  // 统计数据 (优先使用API返回的统计，否则从列表计算)
  const stats = {
    total: shipmentStats?.total ?? outboundList.length,
    waiting: shipmentStats?.pending ?? outboundList.filter((i) => i.status === "waiting").length,
    packing: outboundList.filter((i) => i.status === "packing").length,
    ready: outboundList.filter((i) => i.status === "ready").length,
    shipped: shipmentStats?.shipped ?? outboundList.filter((i) => i.status === "shipped").length,
    todayWeight: outboundList.reduce((sum, i) => sum + i.quantity, 0),
    delivered: shipmentStats?.delivered ?? outboundList.filter((i) => i.status === "delivered").length,
    customerCount: new Set(outboundList.map((i) => i.customer)).size,
    onTimeRate: outboundList.length > 0
      ? Math.round(((shipmentStats?.delivered ?? outboundList.filter((i) => i.status === "delivered").length) / outboundList.length) * 100)
      : 0,
  };

  const getActionText = (status: OutboundStatus): string => {
    switch (status) {
      case "waiting":
        return "开始打包";
      case "packing":
        return "继续打包";
      case "ready":
        return "确认发货";
      case "shipped":
        return "查看物流";
      case "delivered":
        return "查看详情";
      default:
        return "查看";
    }
  };

  const handleItemPress = (item: OutboundItem) => {
    switch (item.status) {
      case "waiting":
        navigation.navigate("WHOutboundDetail", { shipmentId: item.id });
        break;
      case "packing":
        navigation.navigate("WHPacking", { orderId: item.id });
        break;
      case "ready":
        navigation.navigate("WHShippingConfirm", { orderId: item.id });
        break;
      case "shipped":
        navigation.navigate("WHTrackingDetail", { shipmentId: item.id });
        break;
      case "delivered":
        navigation.navigate("WHOutboundDetail", { shipmentId: item.id });
        break;
    }
  };

  // 加载中显示
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>出货管理</Text>
          <Text style={styles.headerSubtitle}>加载中...</Text>
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载出货数据...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header — 手工出货登记 (旧 SH-* 流程, 不扣库存). 主发货入口是"发货确认"(真 DLV-* 流程). */}
      <View style={styles.header}>
        <View style={styles.headerRow}>
          <TouchableOpacity
            onPress={() => navigation.canGoBack() ? navigation.goBack() : navigation.navigate("WHDeliveryConfirmList")}
            testID="wh-manual-back"
          >
            <MaterialCommunityIcons name="arrow-left" size={22} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>手工出货登记</Text>
          <View style={{ width: 22 }} />
        </View>
        <Text style={styles.headerSubtitle}>
          待处理 {stats.waiting + stats.packing + stats.ready} 单 | 今日出库{" "}
          {stats.todayWeight} kg
        </Text>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 概念澄清: 此页是手工登记的出货单, 不自动扣成品库存 */}
        <Surface style={styles.noticeCard} elevation={1}>
          <MaterialCommunityIcons
            name="information-outline"
            size={20}
            color="#1976d2"
          />
          <Text style={styles.noticeText}>
            手工登记的出货单 (不自动扣成品库存)。销售发货单的真发货请用"发货确认"入口。
          </Text>
        </Surface>

        {/* 快捷操作 */}
        <View style={styles.actionBar}>
          <Button
            mode="contained"
            icon="truck-delivery"
            onPress={() => navigation.navigate("WHLoading", {})}
            style={[styles.actionButton, { backgroundColor: "#4CAF50" }]}
            labelStyle={styles.actionButtonLabel}
          >
            装车管理
          </Button>
          <Button
            mode="outlined"
            icon="qrcode-scan"
            onPress={() => navigation.navigate("WHScanOperation", { type: "outbound" })}
            style={styles.actionButton}
            labelStyle={styles.actionButtonLabelOutlined}
          >
            扫码出库
          </Button>
        </View>

        {/* 筛选标签 */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.filterContainer}
        >
          <Chip
            selected={selectedStatus === "all"}
            onPress={() => setSelectedStatus("all")}
            style={[
              styles.filterChip,
              selectedStatus === "all" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "all" ? styles.filterChipTextActive : undefined}
          >
            全部({stats.total})
          </Chip>
          <Chip
            selected={selectedStatus === "waiting"}
            onPress={() => setSelectedStatus("waiting")}
            style={[
              styles.filterChip,
              selectedStatus === "waiting" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "waiting" ? styles.filterChipTextActive : undefined}
          >
            待打包({stats.waiting})
          </Chip>
          <Chip
            selected={selectedStatus === "packing"}
            onPress={() => setSelectedStatus("packing")}
            style={[
              styles.filterChip,
              selectedStatus === "packing" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "packing" ? styles.filterChipTextActive : undefined}
          >
            打包中({stats.packing})
          </Chip>
          <Chip
            selected={selectedStatus === "ready"}
            onPress={() => setSelectedStatus("ready")}
            style={[
              styles.filterChip,
              selectedStatus === "ready" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "ready" ? styles.filterChipTextActive : undefined}
          >
            待发货({stats.ready})
          </Chip>
          <Chip
            selected={selectedStatus === "shipped"}
            onPress={() => setSelectedStatus("shipped")}
            style={[
              styles.filterChip,
              selectedStatus === "shipped" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "shipped" ? styles.filterChipTextActive : undefined}
          >
            已发货({stats.shipped})
          </Chip>
        </ScrollView>

        {/* 出货列表 */}
        <View style={styles.listContainer}>
          {filteredList.map((item) => {
            const config = statusConfig[item.status];
            return (
              <TouchableOpacity
                key={item.id}
                onPress={() => handleItemPress(item)}
                activeOpacity={0.7}
              >
                <Surface
                  style={[
                    styles.orderCard,
                    item.isUrgent && styles.orderCardUrgent,
                  ]}
                  elevation={1}
                >
                  <View style={styles.cardHeader}>
                    <View style={styles.orderIdRow}>
                      <Text style={styles.orderNumber}>{item.orderNumber}</Text>
                      {item.isUrgent && (
                        <View style={styles.urgentBadge}>
                          <Text style={styles.urgentText}>紧急</Text>
                        </View>
                      )}
                    </View>
                    <View
                      style={[
                        styles.statusBadge,
                        { backgroundColor: config.bgColor },
                      ]}
                    >
                      <Text style={[styles.statusText, { color: config.color }]}>
                        {config.label}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.cardContent}>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>客户</Text>
                      <Text style={styles.infoValue}>{item.customer}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>产品</Text>
                      <Text style={styles.infoValue}>{item.products}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>数量</Text>
                      <Text style={[styles.infoValue, styles.quantityValue]}>
                        {item.quantity} kg
                      </Text>
                    </View>
                  </View>

                  <View style={styles.cardFooter}>
                    <Text
                      style={[
                        styles.timeText,
                        item.isUrgent && styles.urgentTimeText,
                      ]}
                    >
                      {item.dispatchTime
                        ? `${item.dispatchTime} 前发出`
                        : item.packingProgress
                        ? `打包进度: ${item.packingProgress}`
                        : item.logistics
                        ? item.logistics
                        : item.deliveredAt
                        ? `${item.deliveredAt} 签收`
                        : "已打包完成，等待装车"}
                    </Text>
                    <View style={styles.actionLink}>
                      <Text style={styles.actionText}>
                        {getActionText(item.status)}
                      </Text>
                      <MaterialCommunityIcons
                        name="chevron-right"
                        size={16}
                        color="#4CAF50"
                      />
                    </View>
                  </View>
                </Surface>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* 今日统计 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>今日出货统计</Text>
          <View style={styles.statsGrid}>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.total}</Text>
              <Text style={styles.statsLabel}>出货单数</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.todayWeight}</Text>
              <Text style={styles.statsLabel}>出货重量(kg)</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.customerCount}</Text>
              <Text style={styles.statsLabel}>客户数</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.onTimeRate}%</Text>
              <Text style={styles.statsLabel}>准时发货率</Text>
            </View>
          </View>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
  },
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: "#666",
  },
  header: {
    backgroundColor: "#4CAF50",
    paddingHorizontal: 16,
    paddingVertical: 16,
  },
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: "bold",
    color: "#fff",
  },
  headerSubtitle: {
    fontSize: 13,
    color: "rgba(255,255,255,0.9)",
    marginTop: 4,
  },
  content: {
    flex: 1,
  },
  noticeCard: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#e3f2fd",
    marginHorizontal: 16,
    marginTop: 16,
    padding: 12,
    borderRadius: 8,
    gap: 10,
  },
  noticeText: {
    flex: 1,
    fontSize: 13,
    color: "#1976d2",
  },
  actionBar: {
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingTop: 12,
    gap: 12,
  },
  actionButton: {
    flex: 1,
    borderRadius: 8,
  },
  actionButtonLabel: {
    color: "#fff",
    fontWeight: "600",
  },
  actionButtonLabelOutlined: {
    color: "#4CAF50",
    fontWeight: "600",
  },
  filterContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  filterChip: {
    marginRight: 8,
    backgroundColor: "#fff",
  },
  filterChipActive: {
    backgroundColor: "#4CAF50",
  },
  filterChipTextActive: {
    color: "#fff",
  },
  listContainer: {
    paddingHorizontal: 16,
  },
  orderCard: {
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  orderCardUrgent: {
    borderLeftWidth: 4,
    borderLeftColor: "#f44336",
  },
  cardHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  orderIdRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  orderNumber: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  urgentBadge: {
    backgroundColor: "#ffebee",
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  urgentText: {
    fontSize: 11,
    color: "#f44336",
    fontWeight: "600",
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: "500",
  },
  cardContent: {
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
    paddingTop: 12,
  },
  infoRow: {
    flexDirection: "row",
    marginBottom: 8,
  },
  infoLabel: {
    width: 50,
    fontSize: 13,
    color: "#999",
  },
  infoValue: {
    flex: 1,
    fontSize: 13,
    color: "#333",
  },
  quantityValue: {
    fontWeight: "600",
    color: "#4CAF50",
  },
  cardFooter: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
  },
  timeText: {
    fontSize: 12,
    color: "#999",
  },
  urgentTimeText: {
    color: "#f44336",
    fontWeight: "600",
  },
  actionLink: {
    flexDirection: "row",
    alignItems: "center",
  },
  actionText: {
    fontSize: 13,
    color: "#4CAF50",
    fontWeight: "500",
  },
  section: {
    marginHorizontal: 16,
    marginTop: 16,
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
    marginBottom: 12,
  },
  statsGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
  },
  statsItem: {
    width: "25%",
    alignItems: "center",
    paddingVertical: 8,
  },
  statsValue: {
    fontSize: 20,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  statsLabel: {
    fontSize: 11,
    color: "#666",
    marginTop: 4,
  },
});

export default WHOutboundListScreen;
