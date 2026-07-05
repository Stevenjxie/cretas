/**
 * 操作记录页面
 * 对应原型: warehouse/operation-log.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import { Text, Button, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHProfileStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { shipmentApiClient, ShipmentRecord } from "../../../services/api/shipmentApiClient";
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHProfileStackParamList>;

interface LogItem {
  id: string;
  type: "inbound" | "outbound" | "quality" | "transfer" | "scan" | "check" | "alert" | "dispose";
  title: string;
  description: string;
  time: string;
  location: string;
  status: "success" | "warning" | "danger";
  statusText: string;
}

interface LogGroup {
  date: string;
  dateLabel: string;
  items: LogItem[];
}

export function WHOperationLogScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [selectedTab, setSelectedTab] = useState("all");
  const [logGroups, setLogGroups] = useState<LogGroup[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);

  const tabs = [
    { key: "all", label: "全部" },
    { key: "inbound", label: "入库" },
    { key: "outbound", label: "出库" },
    { key: "check", label: "盘点" },
  ];

  // 格式化日期标签
  const formatDateLabel = (dateStr: string): { date: string; label: string } => {
    const date = new Date(dateStr);
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);

    const month = date.getMonth() + 1;
    const day = date.getDate();
    const dateKey = `${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}`;

    if (date.toDateString() === today.toDateString()) {
      return { date: dateKey, label: `今日 (${dateKey})` };
    } else if (date.toDateString() === yesterday.toDateString()) {
      return { date: dateKey, label: `昨日 (${dateKey})` };
    } else {
      return { date: dateKey, label: dateKey };
    }
  };

  // 将批次数据转换为日志项
  const batchToLogItem = (batch: MaterialBatch, index: number): LogItem => {
    const createdAt = batch.createdAt ? new Date(batch.createdAt) : new Date();
    const time = `${createdAt.getHours().toString().padStart(2, '0')}:${createdAt.getMinutes().toString().padStart(2, '0')}`;
    const batchAny = batch as unknown as Record<string, unknown>;

    return {
      id: `batch-${batch.id || index}`,
      type: "inbound",
      title: "确认入库",
      description: `${batch.batchNumber} ${batch.materialName || ''} ${batch.remainingQuantity || batchAny.initialQuantity || 0}${batchAny.unit || 'kg'}`,
      time,
      location: batch.storageLocation || "待分配",
      status: "success",
      statusText: "成功",
    };
  };

  // 将出货数据转换为日志项
  const shipmentToLogItem = (shipment: ShipmentRecord, index: number): LogItem => {
    const createdAt = shipment.createdAt ? new Date(shipment.createdAt) : new Date();
    const time = `${createdAt.getHours().toString().padStart(2, '0')}:${createdAt.getMinutes().toString().padStart(2, '0')}`;
    const shipmentAny = shipment as unknown as Record<string, unknown>;

    const statusMap: Record<string, { status: LogItem["status"]; text: string }> = {
      pending: { status: "warning", text: "待处理" },
      shipped: { status: "success", text: "已发货" },
      delivered: { status: "success", text: "已送达" },
      cancelled: { status: "danger", text: "已取消" },
    };
    const statusInfo = statusMap[shipment.status] || { status: "success", text: "成功" };

    return {
      id: `shipment-${shipment.id || index}`,
      type: "outbound",
      title: "确认出库",
      description: `${shipment.shipmentNumber} ${shipment.quantity || 0}${shipmentAny.unit || 'kg'}`,
      time,
      location: (shipmentAny.customerName || shipmentAny.destination || "未知目的地") as string,
      status: statusInfo.status,
      statusText: statusInfo.text,
    };
  };

  // 加载操作记录
  const loadOperationLogs = useCallback(async (pageNum: number = 1, append: boolean = false) => {
    try {
      if (pageNum === 1) {
        setLoading(true);
      } else {
        setLoadingMore(true);
      }

      // 并行获取入库批次和出货记录
      const [batchesResponse, shipmentsResponse] = await Promise.all([
        // 物料批次后端 1-based (PageRequest DTO page>=1); 出货记录后端 0-based → 减 1
        materialBatchApiClient.getMaterialBatches({ page: pageNum, size: 10 }),
        shipmentApiClient.getShipments({ page: pageNum - 1, size: 10 }),
      ]) as [
        { data?: { content?: MaterialBatch[] } | MaterialBatch[] },
        { data?: { content?: ShipmentRecord[] } | ShipmentRecord[] }
      ];

      const batches: MaterialBatch[] = (batchesResponse.data as { content?: MaterialBatch[] })?.content || batchesResponse.data as MaterialBatch[] || [];
      const shipments: ShipmentRecord[] = (shipmentsResponse.data as { content?: ShipmentRecord[] })?.content || shipmentsResponse.data as ShipmentRecord[] || [];

      // 转换为日志项
      const batchLogs = batches.map((b: MaterialBatch, i: number) => ({
        ...batchToLogItem(b, i),
        createdAt: b.createdAt,
      }));

      const shipmentLogs = shipments.map((s: ShipmentRecord, i: number) => ({
        ...shipmentToLogItem(s, i),
        createdAt: s.createdAt,
      }));

      // 合并并按时间排序
      const allLogs = [...batchLogs, ...shipmentLogs].sort((a, b) => {
        const dateA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const dateB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return dateB - dateA;
      });

      // 按日期分组
      const groupMap = new Map<string, LogItem[]>();

      allLogs.forEach((log) => {
        const { date, label } = formatDateLabel(log.createdAt || new Date().toISOString());
        if (!groupMap.has(date)) {
          groupMap.set(date, []);
        }
        // 移除临时的 createdAt 属性
        const { createdAt, ...logItem } = log;
        groupMap.get(date)!.push(logItem as LogItem);
      });

      // 转换为数组
      const newGroups: LogGroup[] = Array.from(groupMap.entries()).map(([date, items]) => {
        const today = new Date();
        const yesterday = new Date(today);
        yesterday.setDate(yesterday.getDate() - 1);

        let dateLabel = date;
        const dateParts = date.split('-').map(Number);
        const month = dateParts[0] ?? 1;
        const day = dateParts[1] ?? 1;
        const checkDate = new Date(today.getFullYear(), month - 1, day);

        if (checkDate.toDateString() === today.toDateString()) {
          dateLabel = `今日 (${date})`;
        } else if (checkDate.toDateString() === yesterday.toDateString()) {
          dateLabel = `昨日 (${date})`;
        }

        return { date, dateLabel, items };
      });

      if (append) {
        setLogGroups((prev) => {
          // 合并相同日期的组
          const merged = [...prev];
          newGroups.forEach((newGroup) => {
            const existingIndex = merged.findIndex((g) => g.date === newGroup.date);
            if (existingIndex >= 0 && merged[existingIndex]) {
              merged[existingIndex].items.push(...newGroup.items);
            } else {
              merged.push(newGroup);
            }
          });
          return merged;
        });
      } else {
        setLogGroups(newGroups);
      }

      // 检查是否还有更多数据
      setHasMore(batches.length >= 10 || shipments.length >= 10);

    } catch (error) {
      handleError(error, { title: '加载操作记录失败' });
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    loadOperationLogs();
  }, [loadOperationLogs]);

  // 加载更多
  const handleLoadMore = () => {
    if (!loadingMore && hasMore) {
      const nextPage = page + 1;
      setPage(nextPage);
      loadOperationLogs(nextPage, true);
    }
  };

  const getLogIcon = (type: LogItem["type"]): { icon: MCIconName; bg: string; color: string } => {
    switch (type) {
      case "inbound":
        return { icon: "package-down", bg: "#e8f5e9", color: "#4CAF50" };
      case "outbound":
        return { icon: "package-up", bg: "#e3f2fd", color: "#1976d2" };
      case "quality":
        return { icon: "clipboard-check-outline", bg: "#f3e5f5", color: "#7b1fa2" };
      case "transfer":
        return { icon: "swap-horizontal", bg: "#fff3e0", color: "#f57c00" };
      case "scan":
        return { icon: "barcode-scan", bg: "#e0f7fa", color: "#0097a7" };
      case "check":
        return { icon: "clipboard-list-outline", bg: "#fce4ec", color: "#c2185b" };
      case "alert":
        return { icon: "alert-circle-outline", bg: "#fff3e0", color: "#f57c00" };
      case "dispose":
        return { icon: "delete-outline", bg: "#ffebee", color: "#f44336" };
      default:
        return { icon: "file-document-outline", bg: "#f5f5f5", color: "#666" };
    }
  };

  const getStatusStyle = (status: LogItem["status"]) => {
    switch (status) {
      case "success":
        return { bg: "#e8f5e9", color: "#4CAF50" };
      case "warning":
        return { bg: "#fff3e0", color: "#f57c00" };
      case "danger":
        return { bg: "#ffebee", color: "#f44336" };
    }
  };

  const filterLogs = (group: LogGroup): LogItem[] => {
    if (selectedTab === "all") return group.items;
    return group.items.filter((item) => {
      if (selectedTab === "inbound") return item.type === "inbound" || item.type === "scan";
      if (selectedTab === "outbound") return item.type === "outbound";
      if (selectedTab === "check") return item.type === "check";
      return true;
    });
  };

  const renderLogItem = (item: LogItem) => {
    const iconStyle = getLogIcon(item.type);
    const statusStyle = getStatusStyle(item.status);

    return (
      <View key={item.id} style={styles.logItem}>
        <View style={[styles.logIcon, { backgroundColor: iconStyle.bg }]}>
          <MaterialCommunityIcons
            name={iconStyle.icon}
            size={18}
            color={iconStyle.color}
          />
        </View>
        <View style={styles.logContent}>
          <Text style={styles.logTitle}>{item.title}</Text>
          <Text style={styles.logDesc}>{item.description}</Text>
          <View style={styles.logMeta}>
            <Text style={styles.logTime}>{item.time}</Text>
            <Text style={styles.logLocation}>{item.location}</Text>
          </View>
        </View>
        <View style={[styles.logStatus, { backgroundColor: statusStyle.bg }]}>
          <Text style={[styles.logStatusText, { color: statusStyle.color }]}>
            {item.statusText}
          </Text>
        </View>
      </View>
    );
  };

  // 加载状态
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => navigation.goBack()}
          >
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>操作记录</Text>
            <Text style={styles.headerSubtitle}>加载中...</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle}>操作记录</Text>
          <Text style={styles.headerSubtitle}>我的操作历史</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 筛选标签 */}
        <View style={styles.filterTabs}>
          {tabs.map((tab) => (
            <TouchableOpacity
              key={tab.key}
              style={[
                styles.filterTab,
                selectedTab === tab.key && styles.filterTabActive,
              ]}
              onPress={() => setSelectedTab(tab.key)}
            >
              <Text
                style={[
                  styles.filterTabText,
                  selectedTab === tab.key && styles.filterTabTextActive,
                ]}
              >
                {tab.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* 日志列表 */}
        {logGroups.map((group) => {
          const filteredItems = filterLogs(group);
          if (filteredItems.length === 0) return null;

          return (
            <View key={group.date} style={styles.section}>
              <Text style={styles.sectionTitle}>{group.dateLabel}</Text>
              {filteredItems.map(renderLogItem)}
            </View>
          );
        })}

        {/* 加载更多 */}
        {hasMore && (
          <View style={styles.loadMore}>
            <Button
              mode="outlined"
              onPress={handleLoadMore}
              style={styles.loadMoreBtn}
              labelStyle={{ color: "#666" }}
              loading={loadingMore}
              disabled={loadingMore}
            >
              {loadingMore ? '加载中...' : '加载更多'}
            </Button>
          </View>
        )}

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
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backButton: {
    padding: 4,
  },
  headerCenter: {
    flex: 1,
    alignItems: "center",
    marginRight: 28,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: "600",
    color: "#fff",
  },
  headerSubtitle: {
    fontSize: 12,
    color: "rgba(255,255,255,0.8)",
    marginTop: 2,
  },
  headerRight: {
    width: 28,
  },
  content: {
    flex: 1,
  },
  filterTabs: {
    flexDirection: "row",
    backgroundColor: "#fff",
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 8,
  },
  filterTab: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: "#f5f5f5",
  },
  filterTabActive: {
    backgroundColor: "#4CAF50",
  },
  filterTabText: {
    fontSize: 13,
    color: "#666",
  },
  filterTabTextActive: {
    color: "#fff",
    fontWeight: "500",
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  logItem: {
    flexDirection: "row",
    alignItems: "flex-start",
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#f0f0f0",
  },
  logIcon: {
    width: 36,
    height: 36,
    borderRadius: 8,
    alignItems: "center",
    justifyContent: "center",
    marginRight: 12,
  },
  logContent: {
    flex: 1,
  },
  logTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  logDesc: {
    fontSize: 13,
    color: "#666",
    marginTop: 2,
  },
  logMeta: {
    flexDirection: "row",
    marginTop: 6,
    gap: 12,
  },
  logTime: {
    fontSize: 12,
    color: "#999",
  },
  logLocation: {
    fontSize: 12,
    color: "#999",
  },
  logStatus: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 4,
    marginLeft: 8,
  },
  logStatusText: {
    fontSize: 11,
    fontWeight: "500",
  },
  loadMore: {
    paddingHorizontal: 16,
    marginTop: 16,
  },
  loadMoreBtn: {
    borderRadius: 8,
    borderColor: "#ddd",
  },
});

export default WHOperationLogScreen;
