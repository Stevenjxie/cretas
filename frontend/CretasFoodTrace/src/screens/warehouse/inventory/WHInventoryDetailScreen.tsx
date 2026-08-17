/**
 * 库存详情页面
 * 对应原型: warehouse/inventory-detail.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import { Text, Surface, Button, Divider, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useRoute, RouteProp, CommonActions } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";
import { formatNumberWithCommas } from "../../../utils/formatters";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, "WHInventoryDetail">;

interface BatchItem {
  id: string;
  batchNumber: string;
  quantity: number;
  inboundDate: string;
  location: string;
  expiryDays: number;
  isNew?: boolean;
  isFifo?: boolean;
}

interface OperationLog {
  id: string;
  time: string;
  action: string;
  type: "in" | "out" | "normal";
}

/**
 * 计算批次距离过期的天数
 */
const calculateExpiryDays = (expiryDate?: string): number => {
  if (!expiryDate) return 999; // 无过期日期，显示很大
  const expiry = new Date(expiryDate);
  const now = new Date();
  return Math.ceil((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
};

/**
 * 判断库存状态
 */
const getInventoryStatus = (totalQty: number, safetyStock: number): { status: string; label: string } => {
  if (totalQty >= safetyStock * 1.5) return { status: 'sufficient', label: '充足' };
  if (totalQty >= safetyStock) return { status: 'warning', label: '正常' };
  if (totalQty > 0) return { status: 'danger', label: '不足' };
  return { status: 'danger', label: '缺货' };
};

/**
 * 根据存储类型获取温度和保质期描述
 */
const getStorageInfo = (storageType?: string): { temp: string; shelfLife: string } => {
  switch (storageType?.toLowerCase()) {
    case 'frozen':
      return { temp: '-18°C (冷冻)', shelfLife: '90天' };
    case 'fresh':
      return { temp: '0°C ~ 4°C (冷藏)', shelfLife: '7天' };
    case 'dry':
      return { temp: '常温', shelfLife: '365天' };
    default:
      return { temp: '0°C ~ 4°C (冷藏)', shelfLife: '7天' };
  }
};

export function WHInventoryDetailScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const { materialId } = route.params;

  const [loading, setLoading] = useState(true);
  const [batches, setBatches] = useState<MaterialBatch[]>([]);
  const [inventoryDetail, setInventoryDetail] = useState<{
    materialName: string;
    materialType: string;
    totalQuantity: number;
    safetyStock: number;
    status: string;
    statusLabel: string;
    storageTemp: string;
    shelfLife: string;
    unitPrice: number;
    movingAvgPrice: number | null;
    totalValue: number;
    batches: BatchItem[];
    locationDistribution: { name: string; quantity: number }[];
    operationLogs: OperationLog[];
  } | null>(null);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      // 获取该物料类型的所有批次
      const response = await materialBatchApiClient.getMaterialBatches({
        materialTypeId: materialId,
        page: 1,
        size: 50,
      }) as { data?: { content?: MaterialBatch[] }; content?: MaterialBatch[] };

      const batchList = response?.data?.content ?? response?.content ?? [];
      setBatches(batchList);

      const firstBatch = batchList[0];
      if (batchList.length > 0 && firstBatch) {
        // 从批次数据构建详情
        const totalQty = batchList.reduce((sum, b) => sum + (b.remainingQuantity ?? 0), 0);
        const safetyStock = 200; // 默认安全库存
        const statusInfo = getInventoryStatus(totalQty, safetyStock);
        const storageInfo = getStorageInfo(firstBatch.storageType);
        const unitPrice = firstBatch.unitPrice ?? 30;
        const movingAvgPrice = firstBatch.movingAvgPrice ?? null;

        // 构建批次列表
        const batchItems: BatchItem[] = batchList.map((b, idx) => ({
          id: b.id,
          batchNumber: b.batchNumber || `MB-${b.id}`,
          quantity: b.remainingQuantity ?? 0,
          inboundDate: b.createdAt?.split('T')[0] ?? '',
          location: b.storageLocation || 'A区-冷藏库',
          expiryDays: calculateExpiryDays(b.expiryDate),
          isFifo: idx === 0, // 第一个批次标记为FIFO优先消耗
          isNew: calculateExpiryDays(b.expiryDate) >= 7,
        }));

        // 构建库位分布
        const locationMap = new Map<string, number>();
        batchList.forEach(b => {
          const loc = b.storageLocation || 'A区-冷藏库';
          locationMap.set(loc, (locationMap.get(loc) ?? 0) + (b.remainingQuantity ?? 0));
        });
        const locationDistribution = Array.from(locationMap.entries()).map(([name, quantity]) => ({
          name,
          quantity,
        }));

        setInventoryDetail({
          materialName: firstBatch.materialName || '物料',
          materialType: firstBatch.storageType?.toLowerCase() === 'frozen' ? '冻品' : '鲜品',
          totalQuantity: totalQty,
          safetyStock,
          status: statusInfo.status,
          statusLabel: statusInfo.label,
          storageTemp: storageInfo.temp,
          shelfLife: storageInfo.shelfLife,
          unitPrice,
          movingAvgPrice,
          totalValue: totalQty * unitPrice,
          batches: batchItems,
          locationDistribution,
          operationLogs: [], // 操作日志需要另外API获取，暂时为空
        });
      }
    } catch (error) {
      handleError(error, { title: t('messages.loadBatchDetailFailed') });
    } finally {
      setLoading(false);
    }
  }, [materialId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 加载中显示
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>{t('inventoryDetail.title')}</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>{t('inventoryDetail.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  // 无数据显示
  if (!inventoryDetail) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>{t('inventoryDetail.title')}</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <Text style={styles.loadingText}>{t('batch.detail.noData')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case "sufficient":
        return { color: "#388e3c", bgColor: "#e8f5e9" };
      case "warning":
        return { color: "#f57c00", bgColor: "#fff3e0" };
      case "danger":
        return { color: "#d32f2f", bgColor: "#ffebee" };
      default:
        return { color: "#666", bgColor: "#f5f5f5" };
    }
  };

  const getExpiryTagStyle = (days: number, isNew?: boolean) => {
    if (isNew) return { color: "#388e3c", bgColor: "#e8f5e9", label: "新入库" };
    if (days <= 3) return { color: "#f57c00", bgColor: "#fff3e0", label: `${days}天后过期` };
    return { color: "#666", bgColor: "#f5f5f5", label: `${days}天后过期` };
  };

  const statusColor = getStatusColor(inventoryDetail.status);

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
          <Text style={styles.headerTitle}>{t('inventoryDetail.title')}</Text>
          <Text style={styles.headerSubtitle}>{inventoryDetail.materialName}</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      {/* 顶部操作按钮 */}
      <View style={styles.topActions}>
        {/* 🔴 2026-08-17 摘掉「调拨」按钮 —— WHInventoryTransferScreen 是原型屏:
            库位下拉写死、单位硬编码 kg、executeTransfer 只改批次的 storageLocation 字段,
            用户输入的调拨数量完全不参与, 不产生调拨单。点完显示「调拨成功」而什么都没调走。
            成因与接回方案见 WHInventoryListScreen 里同日的那段注释。 */}
        <Button
          mode="outlined"
          onPress={() => navigation.dispatch(CommonActions.navigate("WHExpireHandle"))}
          style={[styles.topActionBtn, styles.warningBtn]}
          labelStyle={styles.warningBtnLabel}
        >
          {t('batch.detail.actions.expireHandle')}
        </Button>
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 库存概览 */}
        <Surface style={styles.overviewCard} elevation={1}>
          <View style={styles.overviewMain}>
            <Text style={styles.overviewQty}>{inventoryDetail.totalQuantity}</Text>
            <Text style={styles.overviewUnit}>kg</Text>
          </View>
          <View style={styles.overviewMeta}>
            <View style={styles.overviewItem}>
              <Text style={styles.overviewLabel}>{t('home.alerts.safetyStock')}</Text>
              <Text style={styles.overviewValue}>{inventoryDetail.safetyStock} kg</Text>
            </View>
            <View style={styles.overviewItem}>
              <Text style={styles.overviewLabel}>{t('inventory.card.status')}</Text>
              <Text style={[styles.overviewValue, { color: statusColor.color }]}>
                {inventoryDetail.statusLabel}
              </Text>
            </View>
          </View>
        </Surface>

        {/* 基本信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inventoryDetail.basicInfo')}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('inventoryDetail.materialName')}</Text>
            <Text style={styles.infoValue}>{inventoryDetail.materialName}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.materialType')}</Text>
            <Text style={styles.infoValue}>{inventoryDetail.materialType}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.temperature')}</Text>
            <Text style={styles.infoValue}>{inventoryDetail.storageTemp}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.shelfLife')}</Text>
            <Text style={styles.infoValue}>{inventoryDetail.shelfLife}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('inventoryDetail.unitPrice')}</Text>
            <Text style={styles.infoValue}>¥{inventoryDetail.unitPrice}/kg</Text>
          </View>
          <View style={styles.infoRow} testID="moving-avg-price">
            <Text style={styles.infoLabel}>移动均价</Text>
            <Text style={styles.infoValue}>
              {inventoryDetail.movingAvgPrice != null
                ? `¥${inventoryDetail.movingAvgPrice.toFixed(2)}/kg`
                : '暂无数据'}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('inventoryDetail.totalValue')}</Text>
            <Text style={[styles.infoValue, styles.highlightValue]}>
              ¥{formatNumberWithCommas(inventoryDetail.totalValue)}
            </Text>
          </View>
        </View>

        {/* 批次列表 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>
            {t('inventoryDetail.batchList')} ({inventoryDetail.batches.length})
          </Text>
          {inventoryDetail.batches.map((batch) => {
            const tagStyle = getExpiryTagStyle(batch.expiryDays, batch.isNew);
            return (
              <TouchableOpacity
                key={batch.id}
                style={styles.batchCard}
                onPress={() =>
                  navigation.navigate("WHBatchDetail", { batchId: batch.id })
                }
                activeOpacity={0.7}
              >
                <View style={styles.batchHeader}>
                  <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
                  <View style={[styles.batchTag, { backgroundColor: tagStyle.bgColor }]}>
                    <Text style={[styles.batchTagText, { color: tagStyle.color }]}>
                      {tagStyle.label}
                    </Text>
                  </View>
                </View>
                <View style={styles.batchContent}>
                  <View style={styles.batchRow}>
                    <Text style={styles.batchLabel}>{t('inventoryDetail.currentStock')}</Text>
                    <Text style={styles.batchValue}>{batch.quantity} kg</Text>
                  </View>
                  <View style={styles.batchRow}>
                    <Text style={styles.batchLabel}>{t('batch.detail.inboundDate')}</Text>
                    <Text style={styles.batchValue}>{batch.inboundDate}</Text>
                  </View>
                  <View style={styles.batchRow}>
                    <Text style={styles.batchLabel}>{t('inventoryDetail.location')}</Text>
                    <Text style={styles.batchValue}>{batch.location}</Text>
                  </View>
                </View>
                <View style={styles.batchFooter}>
                  <Text style={styles.batchHint}>
                    {batch.isFifo ? "FIFO" : batch.isNew ? t('batch.detail.daysLater', { days: batch.expiryDays }) : ""}
                  </Text>
                  <Text style={styles.batchAction}>{t('inventory.card.viewDetail')} &gt;</Text>
                </View>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* 库位分布 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inventoryDetail.location')}</Text>
          {inventoryDetail.locationDistribution.map((loc, index) => (
            <View key={index} style={styles.locationItem}>
              <Text style={styles.locationName}>{loc.name}</Text>
              <Text style={styles.locationQty}>{loc.quantity} kg</Text>
            </View>
          ))}
        </View>

        {/* 操作记录 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inventoryDetail.operationLog')}</Text>
          <View style={styles.timeline}>
            {inventoryDetail.operationLogs.map((log, index) => (
              <View key={log.id} style={styles.timelineItem}>
                <View
                  style={[
                    styles.timelineDot,
                    log.type === "in" && styles.timelineDotIn,
                    log.type === "out" && styles.timelineDotOut,
                  ]}
                />
                {index < inventoryDetail.operationLogs.length - 1 && (
                  <View style={styles.timelineLine} />
                )}
                <View style={styles.timelineContent}>
                  <Text style={styles.timelineTime}>{log.time}</Text>
                  <Text style={styles.timelineText}>{log.action}</Text>
                </View>
              </View>
            ))}
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
  topActions: {
    flexDirection: "row",
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: "#fff",
  },
  topActionBtn: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#ddd",
  },
  topActionLabel: {
    color: "#666",
  },
  warningBtn: {
    borderColor: "#f57c00",
  },
  warningBtnLabel: {
    color: "#f57c00",
  },
  content: {
    flex: 1,
  },
  overviewCard: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 20,
  },
  overviewMain: {
    flexDirection: "row",
    alignItems: "baseline",
    justifyContent: "center",
    marginBottom: 16,
  },
  overviewQty: {
    fontSize: 48,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  overviewUnit: {
    fontSize: 20,
    color: "#4CAF50",
    marginLeft: 4,
  },
  overviewMeta: {
    flexDirection: "row",
    justifyContent: "space-around",
  },
  overviewItem: {
    alignItems: "center",
  },
  overviewLabel: {
    fontSize: 13,
    color: "#999",
    marginBottom: 4,
  },
  overviewValue: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
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
  infoRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: "#f5f5f5",
  },
  infoLabel: {
    fontSize: 14,
    color: "#666",
  },
  infoValue: {
    fontSize: 14,
    color: "#333",
  },
  highlightValue: {
    color: "#4CAF50",
    fontWeight: "600",
  },
  batchCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
  },
  batchHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  batchNumber: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  batchTag: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
  },
  batchTagText: {
    fontSize: 11,
    fontWeight: "500",
  },
  batchContent: {
    gap: 4,
  },
  batchRow: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  batchLabel: {
    fontSize: 13,
    color: "#999",
  },
  batchValue: {
    fontSize: 13,
    color: "#333",
  },
  batchFooter: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 8,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: "#eee",
  },
  batchHint: {
    fontSize: 12,
    color: "#f57c00",
  },
  batchAction: {
    fontSize: 12,
    color: "#4CAF50",
  },
  locationItem: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: "#f5f5f5",
  },
  locationName: {
    fontSize: 14,
    color: "#333",
  },
  locationQty: {
    fontSize: 14,
    fontWeight: "600",
    color: "#4CAF50",
  },
  timeline: {},
  timelineItem: {
    flexDirection: "row",
    minHeight: 50,
  },
  timelineDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: "#e0e0e0",
    marginTop: 4,
  },
  timelineDotIn: {
    backgroundColor: "#4CAF50",
  },
  timelineDotOut: {
    backgroundColor: "#f44336",
  },
  timelineLine: {
    position: "absolute",
    left: 4,
    top: 18,
    bottom: 0,
    width: 2,
    backgroundColor: "#e0e0e0",
  },
  timelineContent: {
    flex: 1,
    marginLeft: 12,
    paddingBottom: 16,
  },
  timelineTime: {
    fontSize: 12,
    color: "#999",
    marginBottom: 2,
  },
  timelineText: {
    fontSize: 14,
    color: "#333",
  },
});

export default WHInventoryDetailScreen;
