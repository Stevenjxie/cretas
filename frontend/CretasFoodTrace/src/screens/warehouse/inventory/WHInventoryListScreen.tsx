/**
 * 库存管理列表页面
 * 对应原型: warehouse/inventory.html
 *
 * API集成:
 * - materialBatchApiClient - 获取库存统计和批次列表
 */

import React, { useState, useCallback, useEffect, useMemo } from "react";
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
  Searchbar,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useFocusEffect, CommonActions } from "@react-navigation/native";
import { StickyFooterSummary } from "../../../components/list";
import { useListSummary } from "../../../hooks/useListSummary";
import { formatSummaryForAI } from "../../../utils/aiSummaryContext";

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";
import { logger } from "../../../utils/logger";
import { formatNumberWithCommas, formatDate } from "../../../utils/formatters";
import { Alert } from "react-native";
import { RowActionBottomSheet } from "../../../components/list";
import { useRowActions, type RowContext } from "../../../hooks/useRowActions";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

// 物料类型
type MaterialType = "fresh" | "frozen" | "dry";

interface InventoryItem {
  id: string;
  name: string;
  type: MaterialType;
  quantity: number;
  unit: string;
  batchCount: number;
  location: string;
  warning?: string;
  warningType?: "expire" | "low" | "normal";
  updatedAt: string;
}

interface QuickAction {
  key: string;
  label: string;
  icon: MCIconName;
  color: string;
  screen: keyof WHInventoryStackParamList;
}

// 将后端批次状态映射为仓储物料类型
const mapBatchToMaterialType = (batch: MaterialBatch): MaterialType => {
  // 根据 storageType 或 status 判断类型
  const status = batch.status?.toLowerCase() ?? '';
  const storageType = batch.storageType?.toLowerCase() ?? '';

  if (storageType === 'frozen' || status === 'frozen') {
    return 'frozen';
  } else if (storageType === 'dry' || storageType.includes('干')) {
    return 'dry';
  }
  return 'fresh';
};

// 计算预警类型
const getWarningType = (batch: MaterialBatch): "expire" | "low" | "normal" => {
  const remaining = batch.remainingQuantity ?? 0;
  const total = batch.inboundQuantity ?? 0;

  // 检查是否即将过期 (7天内)
  if (batch.expiryDate) {
    const expDate = new Date(batch.expiryDate);
    const now = new Date();
    const daysUntilExpire = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
    if (daysUntilExpire <= 7) {
      return 'expire';
    }
  }

  // 检查库存是否过低 (低于30%)
  if (total > 0 && remaining / total < 0.3) {
    return 'low';
  }

  return 'normal';
};

// 获取预警文本
const getWarningText = (warningType: "expire" | "low" | "normal", batch: MaterialBatch): string => {
  if (warningType === 'expire') {
    if (batch.expiryDate) {
      const expDate = new Date(batch.expiryDate);
      const now = new Date();
      const daysUntilExpire = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
      return daysUntilExpire <= 0 ? '已过期' : `${daysUntilExpire}天后过期`;
    }
    return '即将过期';
  } else if (warningType === 'low') {
    return '库存不足';
  }
  return '正常';
};

export function WHInventoryListScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedType, setSelectedType] = useState<string>("all");
  const [inventoryList, setInventoryList] = useState<InventoryItem[]>([]);
  const [inventoryStats, setInventoryStats] = useState<{
    totalValue: number;
    totalBatches: number;
    availableBatches: number;
    expiringBatchesCount: number;
    inventoryByType?: Record<string, number>;
  } | null>(null);
  const [selectedItem, setSelectedItem] = useState<InventoryItem | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  /** Map warningType ('normal'/'low'/'expire') to STATUS_ACTIONS_MAP key. */
  const deriveInventoryStatus = (item: InventoryItem): string => {
    if (item.warningType === 'expire') return 'EXPIRE';
    if (item.warningType === 'low') return 'LOW';
    return 'NORMAL';
  };

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => navigation.navigate('WHInventoryDetail', { inventoryId: e.id }),
    transfer: () => Alert.alert('调拨', '跳转 WHInventoryTransfer (待接)'),
    'view-price-history': (e: RowContext) => Alert.alert('价格历史', `物料 ${e.id}`),
  }), [navigation]);

  const sheetCtx: RowContext = selectedItem
    ? { status: deriveInventoryStatus(selectedItem), id: selectedItem.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('inventory', sheetCtx, { handlers });

  const openSheet = (item: InventoryItem) => { setSelectedItem(item); setActionSheetVisible(true); };

  // Define type config and quick actions inside component to access t()
  const typeConfig: Record<MaterialType, { label: string; color: string; bgColor: string }> = {
    fresh: { label: t('inventory.filter.fresh'), color: "#4CAF50", bgColor: "#e8f5e9" },
    frozen: { label: t('inventory.filter.frozen'), color: "#2196F3", bgColor: "#e3f2fd" },
    dry: { label: t('inventory.filter.dry'), color: "#FF9800", bgColor: "#fff3e0" },
  };

  const quickActions: QuickAction[] = [
    { key: "check", label: t('inventory.quickActions.check'), icon: "clipboard-check-outline", color: "#4CAF50", screen: "WHInventoryCheck" },
    { key: "transfer", label: t('inventory.quickActions.transfer'), icon: "swap-horizontal", color: "#2196F3", screen: "WHInventoryTransfer" },
    { key: "location", label: t('inventory.quickActions.location'), icon: "map-marker", color: "#9C27B0", screen: "WHLocationManage" },
    { key: "expire", label: t('inventory.quickActions.expire'), icon: "clock-alert-outline", color: "#FF5722", screen: "WHExpireHandle" },
    { key: "transit", label: "\u4e2d\u8f6c\u786e\u8ba4", icon: "truck-check-outline", color: "#00695C", screen: "WHTransitLedger" },
    { key: "warnings", label: "库存预警", icon: "bell-alert-outline", color: "#E91E63", screen: "WHInventoryWarnings" },
  ];

  // 加载库存数据
  const loadData = useCallback(async () => {
    try {
      logger.info('WHInventoryListScreen', '开始加载库存数据...');

      // 并行获取批次列表和库存统计
      const [batchesResult, statsResult] = await Promise.allSettled([
        materialBatchApiClient.getMaterialBatches({ page: 1, size: 50 }),
        materialBatchApiClient.getInventoryStatistics(),
      ]);

      // 处理批次列表
      if (batchesResult.status === 'fulfilled') {
        const response = batchesResult.value as { success?: boolean; data?: { content?: MaterialBatch[] } };
        if (response.success) {
          const batches = response.data?.content ?? [];
          logger.info('WHInventoryListScreen', `获取到 ${batches.length} 个批次`);

          // 转换为库存项目格式
          const items: InventoryItem[] = batches.map((batch: MaterialBatch) => {
            const warningType = getWarningType(batch);
            return {
              id: batch.id ?? batch.batchNumber ?? String(Math.random()),
              name: batch.materialName ?? batch.materialCategory ?? '未知物料',
              type: mapBatchToMaterialType(batch),
              quantity: batch.remainingQuantity ?? batch.inboundQuantity ?? 0,
              unit: 'kg', // 默认单位
              batchCount: 1, // 每个批次算一个
              location: batch.storageLocation ?? '默认库位',
              warning: getWarningText(warningType, batch),
              warningType: warningType,
              updatedAt: batch.updatedAt
                ? formatDate(new Date(batch.updatedAt))
                : formatDate(new Date()),
            };
          });

          setInventoryList(items);
        } else {
          logger.warn('WHInventoryListScreen', '获取批次列表失败');
          setInventoryList([]);
        }
      } else {
        logger.warn('WHInventoryListScreen', '获取批次列表失败');
        setInventoryList([]);
      }

      // 处理库存统计
      if (statsResult.status === 'fulfilled') {
        const statsResponse = statsResult.value as { success?: boolean; data?: { totalValue: number; totalBatches: number; availableBatches: number; expiringBatchesCount: number; inventoryByType?: Record<string, number> } };
        if (statsResponse.success && statsResponse.data) {
          setInventoryStats(statsResponse.data);
        }
      }

    } catch (error) {
      logger.error('WHInventoryListScreen', '加载库存数据失败', error);
      handleError(error, { title: '加载库存数据失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // 重新进入页面时刷新数据（盘点/调拨/入库后返回能看到最新库存）
  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // U-FOOTER-1
  const summaryRequest = useMemo(() => ({ filterConditions: {} }), []);
  const { summary, refresh: refreshSummary } = useListSummary('inventory', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  // 筛选数据
  const filteredList = inventoryList.filter((item) => {
    if (selectedType !== "all" && item.type !== selectedType) return false;
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return item.name.toLowerCase().includes(query);
    }
    return true;
  });

  // 统计数据
  const stats = {
    total: inventoryStats?.totalBatches ?? inventoryList.length,
    fresh: inventoryList.filter((i) => i.type === "fresh").length,
    frozen: inventoryList.filter((i) => i.type === "frozen").length,
    dry: inventoryList.filter((i) => i.type === "dry").length,
    totalWeight: inventoryList.reduce((sum, i) => sum + i.quantity, 0),
    warningCount: inventoryStats?.expiringBatchesCount ?? inventoryList.filter((i) => i.warningType !== "normal").length,
    totalValue: inventoryStats?.totalValue ?? 0,
  };

  const handleItemPress = (item: InventoryItem) => {
    navigation.navigate("WHInventoryDetail", { inventoryId: item.id });
  };

  const handleQuickAction = (action: QuickAction) => {
    (navigation.navigate as (screen: string) => void)(action.screen);
  };

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>库存管理</Text>
        <Text style={styles.headerSubtitle}>
          在库 {stats.total} 种 | 总量 {formatNumberWithCommas(stats.totalWeight)} kg
        </Text>
      </View>

      {loading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      ) : (
      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 快捷操作 */}
        <View style={styles.quickActions}>
          {quickActions.map((action) => (
            <TouchableOpacity
              key={action.key}
              style={styles.quickActionItem}
              onPress={() => handleQuickAction(action)}
              activeOpacity={0.7}
              testID={`wh-quick-action-${action.key}`}
            >
              <View
                style={[
                  styles.quickActionIcon,
                  { backgroundColor: `${action.color}15` },
                ]}
              >
                <MaterialCommunityIcons
                  name={action.icon}
                  size={24}
                  color={action.color}
                />
              </View>
              <Text style={styles.quickActionLabel}>{action.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* 搜索栏 */}
        <Searchbar
          placeholder="搜索物料名称/批次号"
          value={searchQuery}
          onChangeText={setSearchQuery}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
        />

        {/* 筛选标签 */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.filterContainer}
        >
          <Chip
            selected={selectedType === "all"}
            onPress={() => setSelectedType("all")}
            style={[
              styles.filterChip,
              selectedType === "all" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "all" ? styles.filterChipTextActive : undefined}
          >
            全部({stats.total})
          </Chip>
          <Chip
            selected={selectedType === "fresh"}
            onPress={() => setSelectedType("fresh")}
            style={[
              styles.filterChip,
              selectedType === "fresh" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "fresh" ? styles.filterChipTextActive : undefined}
          >
            鲜品({stats.fresh})
          </Chip>
          <Chip
            selected={selectedType === "frozen"}
            onPress={() => setSelectedType("frozen")}
            style={[
              styles.filterChip,
              selectedType === "frozen" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "frozen" ? styles.filterChipTextActive : undefined}
          >
            冻品({stats.frozen})
          </Chip>
          <Chip
            selected={selectedType === "dry"}
            onPress={() => setSelectedType("dry")}
            style={[
              styles.filterChip,
              selectedType === "dry" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "dry" ? styles.filterChipTextActive : undefined}
          >
            干货({stats.dry})
          </Chip>
        </ScrollView>

        {/* 库存列表 */}
        <View style={styles.listContainer}>
          {filteredList.map((item) => {
            const typeConf = typeConfig[item.type];
            return (
              <TouchableOpacity
                key={item.id}
                onPress={() => handleItemPress(item)}
                onLongPress={() => openSheet(item)}
                activeOpacity={0.7}
              >
                <Surface style={styles.inventoryCard} elevation={1}>
                  <View style={styles.cardHeader}>
                    <Text style={styles.materialName}>{item.name}</Text>
                    <View
                      style={[
                        styles.typeBadge,
                        { backgroundColor: typeConf.bgColor },
                      ]}
                    >
                      <Text style={[styles.typeText, { color: typeConf.color }]}>
                        {typeConf.label}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.cardContent}>
                    <View style={styles.mainInfo}>
                      <Text style={styles.quantityValue}>{item.quantity}</Text>
                      <Text style={styles.unitText}>{item.unit}</Text>
                    </View>
                    <View style={styles.metaInfo}>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>批次</Text>
                        <Text style={styles.metaValue}>{item.batchCount}个</Text>
                      </View>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>库位</Text>
                        <Text style={styles.metaValue}>{item.location}</Text>
                      </View>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>
                          {item.warningType === "normal" ? "状态" : "预警"}
                        </Text>
                        <Text
                          style={[
                            styles.metaValue,
                            item.warningType === "expire" && styles.warningText,
                            item.warningType === "low" && styles.lowText,
                            item.warningType === "normal" && styles.normalText,
                          ]}
                        >
                          {item.warning || "正常"}
                        </Text>
                      </View>
                    </View>
                  </View>

                  <View style={styles.cardFooter}>
                    <Text style={styles.updateText}>
                      更新: {item.updatedAt}
                    </Text>
                    <View style={styles.actionLink}>
                      <Text style={styles.actionText}>查看详情</Text>
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

        {/* 库存概览 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>库存概览</Text>
          <View style={styles.statsGrid}>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.total}</Text>
              <Text style={styles.statsLabel}>物料种类</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>
                {formatNumberWithCommas(stats.totalWeight)}
              </Text>
              <Text style={styles.statsLabel}>总库存(kg)</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>
                ¥{stats.totalValue > 1000 ? `${(stats.totalValue / 1000).toFixed(0)}K` : stats.totalValue.toFixed(0)}
              </Text>
              <Text style={styles.statsLabel}>库存价值</Text>
            </View>
            <View style={styles.statsItem}>
              <Text style={[styles.statsValue, { color: "#f44336" }]}>
                {stats.warningCount}
              </Text>
              <Text style={styles.statsLabel}>预警数</Text>
            </View>
          </View>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>
      )}

      <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab' as never, {
            screen: 'AIChat',
            params: { entityType: 'MATERIAL', initialMessage: `分析当前库存 (低库存预警 / 总价值 / 周转率)${formatSummaryForAI(summary)}` },
          } as never))
        }
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedItem ? `${selectedItem.name}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedItem) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'INVENTORY', initialMessage: `${selectedItem.name}: ` },
          }));
        }}
      />
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
    paddingTop: 100,
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
  quickActions: {
    flexDirection: "row",
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    paddingVertical: 16,
  },
  quickActionItem: {
    flex: 1,
    alignItems: "center",
  },
  quickActionIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 8,
  },
  quickActionLabel: {
    fontSize: 12,
    color: "#666",
  },
  searchBar: {
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 8,
    backgroundColor: "#fff",
  },
  searchInput: {
    fontSize: 14,
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
  inventoryCard: {
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  cardHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  materialName: {
    fontSize: 16,
    fontWeight: "600",
    color: "#333",
  },
  typeBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  typeText: {
    fontSize: 12,
    fontWeight: "500",
  },
  cardContent: {
    flexDirection: "row",
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
    paddingTop: 12,
  },
  mainInfo: {
    flexDirection: "row",
    alignItems: "baseline",
    width: 100,
  },
  quantityValue: {
    fontSize: 28,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  unitText: {
    fontSize: 14,
    color: "#666",
    marginLeft: 4,
  },
  metaInfo: {
    flex: 1,
    marginLeft: 16,
  },
  metaItem: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 4,
  },
  metaLabel: {
    fontSize: 12,
    color: "#999",
  },
  metaValue: {
    fontSize: 12,
    color: "#333",
  },
  warningText: {
    color: "#f44336",
    fontWeight: "600",
  },
  lowText: {
    color: "#ff9800",
    fontWeight: "600",
  },
  normalText: {
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
  updateText: {
    fontSize: 12,
    color: "#999",
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

export default WHInventoryListScreen;
