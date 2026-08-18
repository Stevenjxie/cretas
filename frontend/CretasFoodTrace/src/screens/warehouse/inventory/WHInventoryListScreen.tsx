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
import {
  materialPackagingApiClient,
  type MaterialPackagingHierarchy,
} from "../../../services/api/materialPackagingApiClient";
import {
  convertByHierarchy,
  bucketByDimension,
  formatQuantity,
  canonicalUnit,
} from "../../../utils/packagingUnitConversion";
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
  /** 用来关联原料字典的规格层级 (material_packaging_hierarchy.material_type_id) */
  materialTypeId?: string;
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
  /**
   * 原料字典的规格层级, key = materialTypeId。
   * 这是「1箱=8盒=800克」的**唯一权威**——全局单位表里计数单位的换算因子全是假的 1:1,
   * 不能用它换算 (详见 utils/packagingUnitConversion.ts 顶部)。
   */
  const [hierarchyMap, setHierarchyMap] = useState<Record<string, MaterialPackagingHierarchy>>({});
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
    // 🔴 2026-08-17: 原文案是「跳转 WHInventoryTransfer (待接)」—— 把内部屏幕名直接弹给了仓管,
    // 而它指向的那个屏是原型(只改 storageLocation, 数量不参与)。改成人话 + 说清去哪做。
    transfer: () => Alert.alert('调拨', '手机端暂不支持调拨, 请在网页端「库存 - 调拨」办理。'),
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
    // 🔴 2026-08-17 摘掉「调拨」入口 —— WHInventoryTransferScreen 是**原型屏**(文件头注释自称
    // 「对应原型: warehouse/inventory-transfer.html」), 它:
    //   · 调入库位下拉是**写死的三条示例**(A区-冷藏库-02/03、B区-冷冻库-01), 与真实库位
    //     (WH-RAW / WH-WKS / WH-FG …) 无关;
    //   · 22 处硬编码 kg, 把 片/卷/盒 的批次也印成 kg;
    //   · executeTransfer 只调 materialBatchApiClient.updateBatch({ storageLocation }) ——
    //     把批次库位改写成 "A-02" 这种不存在的码, **用户输入的调拨数量完全不参与**,
    //     也不产生任何调拨单, 根本不走后端的 TransferServiceImpl。
    // 用户点完会看到「调拨成功」, 而实际上什么都没调走。⛔ 少一个入口, 好过一个说谎的入口。
    //
    // 要接回来: RN 侧已经有 `transferApiClient.createTransfer()` 打真实端点
    // `POST /api/mobile/{factoryId}/transfers`(实测可用, 见 HANDOFF-2026-08-17b 第五节),
    // 缺的是真实仓库列表 + 用批次自己的单位 + 数量语义。那是一次独立改造, 不在本次范围。
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
      const [batchesResult, statsResult, packagingResult] = await Promise.allSettled([
        materialBatchApiClient.getMaterialBatches({ page: 1, size: 50 }),
        materialBatchApiClient.getInventoryStatistics(),
        materialPackagingApiClient.list(),
      ]);

      // 规格层级取不到不阻断列表 —— 只是换算显示不出来, 卡片会显示「未设规格」。
      if (packagingResult.status === 'fulfilled') {
        const map: Record<string, MaterialPackagingHierarchy> = {};
        for (const h of packagingResult.value ?? []) {
          if (h?.materialTypeId) map[h.materialTypeId] = h;
        }
        setHierarchyMap(map);
        logger.info('WHInventoryListScreen', `规格层级 ${Object.keys(map).length} 条`);
      } else {
        logger.warn('WHInventoryListScreen', '规格层级获取失败, 换算将显示为未设规格');
        setHierarchyMap({});
      }

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
              materialTypeId: batch.materialTypeId,
              quantity: batch.remainingQuantity ?? batch.inboundQuantity ?? 0,
              // 口径修正 (2026-08-02): 原来这里硬编码 'kg'。prod 实测 F006 首页 50 条批次的
              // 真实单位是 kg 38 / box 3 / slice 2 / roll 1 / case 1 / 无 unit 5 ——
              // 12/50 根本不是 kg, 却被当 kg 显示并求和。带上真实单位, 缺失就直说。
              unit: batch.unit || '未标注',
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
  //
  // 🔴 口径修正 (2026-08-02)。这里原先把三个**不同范围**的数混在一起显示, 而且都标错了:
  //   1. total 取的是 inventoryStats.totalBatches —— 那是**批次数**(prod F006 = 296),
  //      却被渲染成「在库 296 种」「物料种类 296」。同屏底部 sticky bar 写的
  //      「共 296 批」才是对的, 同一个数在一屏里一处叫「种」一处叫「批」。
  //   2. totalWeight 是 inventoryList 求和, 而 inventoryList 只有
  //      getMaterialBatches({page:1,size:50}) 的 **50 条**(totalElements=296),
  //      即"总量"只统计了 1/6 的数据。
  //   3. 那 50 条单位不一致(实测 kg 38 / box 3 / slice 2 / roll 1 / case 1 / 无 5),
  //      却一律当 kg 相加并标 kg —— 与 box 被当 kg 算导致投料偏大 25% 是同一类错误。
  //
  // 修法: 分清「全量(服务端)」与「已加载(本页)」两个范围, 各自只说自己能证明的事;
  // 数量按单位分组, 绝不跨单位相加。
  const loadedCount = inventoryList.length;
  const totalBatches = inventoryStats?.totalBatches ?? loadedCount;

  /** 已加载批次按单位分组求和 —— 只在同一单位内相加。 */
  const loadedQuantityByUnit = inventoryList.reduce<Record<string, number>>((acc, i) => {
    acc[i.unit] = (acc[i.unit] ?? 0) + i.quantity;
    return acc;
  }, {});
  const loadedUnitKinds = Object.keys(loadedQuantityByUnit);
  const loadedKgQuantity = loadedQuantityByUnit['kg'] ?? 0;
  const nonKgBatchCount = inventoryList.filter((i) => i.unit !== 'kg').length;

  const stats = {
    // 批次总数 (全量, 服务端)
    totalBatches,
    // 本页已加载条数 —— 类型分项都只覆盖这些, 所以「全部」也必须用它, 否则
    // 全部(296) 与 鲜品+冻品+干货(50) 当场对不上。
    loaded: loadedCount,
    fresh: inventoryList.filter((i) => i.type === "fresh").length,
    frozen: inventoryList.filter((i) => i.type === "frozen").length,
    dry: inventoryList.filter((i) => i.type === "dry").length,
    loadedKgQuantity,
    nonKgBatchCount,
    loadedUnitKinds: loadedUnitKinds.length,
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
        {/* 口径修正: 296 是**批次数**不是物料种类; 且列表只加载了其中一页, 明说清楚。 */}
        <Text style={styles.headerSubtitle}>
          在库 {stats.totalBatches} 批
          {stats.loaded < stats.totalBatches ? ` | 已加载 ${stats.loaded}` : ''}
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
            {/* 口径修正: 类型分项只覆盖已加载的这一页, 「全部」必须同源,
                否则 全部(296) 与 鲜品+冻品+干货(50) 当场对不上。 */}
            全部({stats.loaded})
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
                      <Text style={styles.quantityValue}>{formatQuantity(item.quantity)}</Text>
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

                  {/*
                    多单位换算 (2026-08-02): 仓管员不用心算「10000 盒是多少箱」。
                    换算只认原料字典的规格层级; 没配就明说「未设规格」并给入口,
                    绝不退回按 1:1 猜 —— 那正是 725,908 那个假数的成因。
                  */}
                  {(() => {
                    const h = item.materialTypeId ? hierarchyMap[item.materialTypeId] : undefined;
                    const conv = convertByHierarchy(item.quantity, item.unit, h);
                    if (!conv) {
                      return (
                        <TouchableOpacity
                          style={styles.convertRow}
                          onPress={() => navigation.navigate('WHInventoryDetail', { inventoryId: item.id })}
                        >
                          <Text style={styles.convertMissing}>⚠ 未设规格 · 无法折算</Text>
                          <Text style={styles.convertLink}>去设置 ›</Text>
                        </TouchableOpacity>
                      );
                    }
                    const others = conv.levels.filter((l) => canonicalUnit(l.unit) !== canonicalUnit(item.unit));
                    if (others.length === 0) return null;
                    return (
                      <View style={styles.convertRow}>
                        <Text style={styles.convertText}>
                          {others.map((l) => `≈ ${formatQuantity(l.quantity)} ${l.unit}`).join('   ')}
                        </Text>
                      </View>
                    );
                  })()}

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
          {/*
            计数单位分列 —— 盒/箱/片/卷 之间没有通用换算 (全局单位表里那些 1:1 是假的),
            所以按单位各自成组显示, 而不是加成一个没有业务含义的总数。
          */}
          {bucketByDimension(inventoryList).filter((b) => b.kind === 'count').length > 0 && (
            <View style={styles.countUnitRow}>
              {bucketByDimension(inventoryList)
                .filter((b) => b.kind === 'count')
                .map((b) => (
                  <View key={b.unit} style={styles.countUnitChip}>
                    <Text style={styles.countUnitValue}>{formatQuantity(b.quantity)}</Text>
                    {/* ⛔ 这里不能用 b.unit —— 那是 canonicalUnit() 折出来的英文归一键
                        (盒 → box), 渲染它等于我们自己把中文翻成英文给用户看。
                        b.unit 留给上面的 React key 用。 */}
                    <Text style={styles.countUnitLabel}>{b.displayLabel} · {b.batchCount}批</Text>
                  </View>
                ))}
            </View>
          )}
          <View style={styles.statsGrid}>
            {/* 口径修正: 这个数是批次数, 不是物料种类 —— 底部 sticky bar 的「共 N 批」同源。 */}
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.totalBatches}</Text>
              <Text style={styles.statsLabel}>批次总数</Text>
            </View>
            {/* 口径修正: 重量族折 kg 合并, 计数族按单位各自成组 —— 绝不跨量纲相加。 */}
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>
                {formatQuantity(
                  bucketByDimension(inventoryList).find((b) => b.kind === 'weight')?.quantity ?? 0
                )}
              </Text>
              <Text style={styles.statsLabel}>已加载食材(kg)</Text>
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
        /*
          口径修正 (2026-08-02): 后端 list-summary 的「可用数量」是
          SUM(receipt-used-reserved) **跨单位硬加** (prod F006 实测 725,908.175 =
          70万克 + 1万个盒 + 1万张膜 + 310个箱), 它自己返回的 unit 是**空字符串** ——
          等于承认给不出单位。这里就用这个自证信号过滤: 数量型(number)但没有单位的统计
          不予展示, 因为跨量纲求和没有业务含义。
          带单位的(共 N 批 / 总价值 ¥)照常显示。
          ⚠️ 后端那条 SQL 本身也该按单位分组, 属独立项。
        */
        stats={(summary?.stats ?? []).filter(
          (st) => !(st.format === 'number' && !String(st.unit ?? '').trim())
        )}
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
  countUnitRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  countUnitChip: {
    backgroundColor: '#f4f7fb',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    minWidth: 96,
  },
  countUnitValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#2d3748',
  },
  countUnitLabel: {
    fontSize: 12,
    color: '#718096',
    marginTop: 2,
  },
  convertRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 6,
    minHeight: 44,           // 触摸目标 >=44pt (ux-flow 内联规则)
    paddingVertical: 4,
  },
  convertText: {
    fontSize: 13,
    color: '#666',
  },
  convertMissing: {
    fontSize: 13,
    color: '#ed8936',
  },
  convertLink: {
    fontSize: 13,
    color: '#1565c0',
    fontWeight: '600',
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
