/**
 * 库存盘点（发起）页面
 *
 * W1 红线 #02 重做（2026-06-11）:
 *   旧实现直接调用 materialBatchApiClient.updateBatch 改 remainingQuantity 调整库存，
 *   绕过盘点流程与财务审批 —— 违反"仓管员无库存自主权"铁律（张权 F006）。
 *
 *   新实现 = 盘点发起入口（launcher）:
 *     仓管员只能"发起盘点任务" → 进入逐品录入屏（StocktakeEntry）
 *     → 录入实盘数 → 提交 → 状态变"待财务审批"
 *     → 差异由财务审批后才通过 MaterialBatchAdjustment 留痕生效。
 *   仓管员全程无法直接写库存数量。
 *
 * 对应后端: POST /api/mobile/{factoryId}/stocktakes (FactoryStocktakeService.initiate)
 *   - 月底约束: 只能在每月 N 日后发起（后端 409）
 *   - 防重复: 同仓同月已有进行中盘点 → 409 DUPLICATE_STOCKTAKE
 *   两类业务规则前置说明 + 失败时原样显示后端 message（防呆 4 位一体）。
 */

import React, { useState, useEffect, useCallback } from "react";
import { View, ScrollView, StyleSheet, ActivityIndicator } from "react-native";
import {
  Text,
  Surface,
  TouchableRipple,
  Divider,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { NeoButton } from "../../../components/ui/NeoButton";
import { AppDialogHost, appAlert } from "../../../components/ui/AppDialog";
import {
  stocktakeApiClient,
  FactoryWarehouseDTO,
} from "../../../services/api/stocktakeApiClient";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

const PRIMARY = "#1890FF";
const GREEN = "#52C41A";

/** 当前月份 YYYY-MM */
function currentPeriodMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

const WAREHOUSE_TYPE_LABEL: Record<string, string> = {
  RAW: "原料仓",
  WIP: "在制品仓",
  FINISHED: "成品仓",
  SALTED: "盐化仓",
  TEMP: "暂存仓",
  QC: "质检仓",
  LINESIDE: "线边仓",
  RETURNS: "退货仓",
  OUTSOURCE: "外协仓",
  SCRAP: "废品仓",
  TRANSFER: "调拨在途仓",
  LOGISTICS: "物流仓",
  WORKSHOP: "车间仓",
};

export function WHInventoryCheckScreen() {
  const navigation = useNavigation<NavigationProp>();

  const [loading, setLoading] = useState(true);
  const [warehouses, setWarehouses] = useState<FactoryWarehouseDTO[]>([]);
  const [selectedWarehouseId, setSelectedWarehouseId] = useState<string | null>(null);
  const [initiating, setInitiating] = useState(false);
  const periodMonth = currentPeriodMonth();

  // 加载工厂仓库列表（排除调拨在途仓 — 后端 assertCanStocktake 也会拦）
  const loadWarehouses = useCallback(async () => {
    setLoading(true);
    try {
      const res = await stocktakeApiClient.listWarehouses();
      const list = (res.data ?? []).filter((w) => w.type !== "TRANSFER");
      setWarehouses(list);
      if (list.length === 1 && list[0]) setSelectedWarehouseId(list[0].id);
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      appAlert(
        "加载仓库列表失败",
        e.response?.data?.message ?? "请检查网络后重试",
        [
          { text: "重试", onPress: loadWarehouses },
          { text: "返回", style: "cancel", onPress: () => navigation.goBack() },
        ],
      );
    } finally {
      setLoading(false);
    }
  }, [navigation]);

  useEffect(() => {
    loadWarehouses();
  }, [loadWarehouses]);

  // 发起盘点任务 → 进入逐品录入屏
  const handleInitiate = useCallback(async () => {
    if (!selectedWarehouseId) {
      appAlert("请选择仓库", "请先选择要盘点的仓库，再发起盘点任务");
      return;
    }
    setInitiating(true);
    try {
      const res = await stocktakeApiClient.initiate({
        warehouseId: selectedWarehouseId,
        periodMonth,
      });
      if (!res.success || !res.data) {
        throw new Error(res.message ?? "发起失败");
      }
      navigation.navigate("StocktakeEntry", { stocktakeId: res.data.id });
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      // 防呆: 原样显示后端 message（月底约束 / 重复发起等业务规则）
      appAlert(
        "无法发起盘点",
        e.response?.data?.message ?? "请检查网络后重试",
      );
    } finally {
      setInitiating(false);
    }
  }, [selectedWarehouseId, periodMonth, navigation]);

  const Header = (
    <View style={styles.header}>
      <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableRipple>
      <View style={styles.headerCenter}>
        <Text style={styles.headerTitle}>发起盘点</Text>
        <Text style={styles.headerSubtitle}>盘点月份 {periodMonth}</Text>
      </View>
      <View style={styles.headerRight} />
    </View>
  );

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <AppDialogHost />
        {Header}
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载仓库列表...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      <AppDialogHost />
      {Header}

      <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }}>
        {/* 流程说明（防呆 Rule 2: 明确告诉操作员能做什么） */}
        <Surface style={styles.noticeCard} elevation={1}>
          <View style={styles.noticeRow}>
            <MaterialCommunityIcons name="information-outline" size={20} color={PRIMARY} />
            <Text style={styles.noticeTitle}>盘点流程</Text>
          </View>
          <Text style={styles.noticeText}>
            选择仓库后发起盘点 → 逐品录入实盘数量 → 提交。{"\n"}
            盘点差异将自动报给财务审批，审批通过后库存才会调整。{"\n"}
            您无法直接修改库存数量。
          </Text>
        </Surface>

        {/* 仓库选择 */}
        <Surface style={styles.section} elevation={1}>
          <Text style={styles.sectionTitle}>选择盘点仓库</Text>
          {warehouses.length === 0 ? (
            <View style={styles.emptyBox}>
              <MaterialCommunityIcons name="warehouse" size={40} color="#ccc" />
              <Text style={styles.emptyText}>暂无可盘点的仓库</Text>
            </View>
          ) : (
            warehouses.map((wh, idx) => {
              const selected = wh.id === selectedWarehouseId;
              return (
                <View key={wh.id}>
                  {idx > 0 && <Divider />}
                  <TouchableRipple onPress={() => setSelectedWarehouseId(wh.id)}>
                    <View style={styles.whRow}>
                      <View style={styles.whInfo}>
                        <Text style={styles.whName}>{wh.name}</Text>
                        <Text style={styles.whMeta}>
                          {wh.code}
                          {wh.type ? ` · ${WAREHOUSE_TYPE_LABEL[wh.type] ?? wh.type}` : ""}
                        </Text>
                      </View>
                      <MaterialCommunityIcons
                        name={selected ? "radiobox-marked" : "radiobox-blank"}
                        size={24}
                        color={selected ? PRIMARY : "#ccc"}
                      />
                    </View>
                  </TouchableRipple>
                </View>
              );
            })
          )}
        </Surface>
      </ScrollView>

      {/* 底部操作 */}
      <View style={styles.bottomBar}>
        <NeoButton
          variant="primary"
          size="large"
          onPress={handleInitiate}
          loading={initiating}
          disabled={initiating || !selectedWarehouseId}
          style={{ flex: 1 }}
        >
          {initiating ? "发起中..." : "发起盘点"}
        </NeoButton>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#F4F6F9" },
  header: {
    backgroundColor: PRIMARY,
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20 },
  headerCenter: { flex: 1, alignItems: "center" },
  headerTitle: { fontSize: 18, fontWeight: "700", color: "#fff" },
  headerSubtitle: { fontSize: 12, color: "rgba(255,255,255,0.8)", marginTop: 2 },
  headerRight: { width: 40 },
  content: { flex: 1 },
  centered: { flex: 1, justifyContent: "center", alignItems: "center", padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: "#666" },
  noticeCard: {
    backgroundColor: "#E6F7FF",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  noticeRow: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 8 },
  noticeTitle: { fontSize: 15, fontWeight: "700", color: PRIMARY },
  noticeText: { fontSize: 14, color: "#333", lineHeight: 22 },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: { fontSize: 14, fontWeight: "600", color: "#999", marginBottom: 8 },
  whRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 14,
  },
  whInfo: { flex: 1 },
  whName: { fontSize: 16, fontWeight: "600", color: "#222" },
  whMeta: { fontSize: 13, color: "#999", marginTop: 2 },
  emptyBox: { alignItems: "center", paddingVertical: 32 },
  emptyText: { marginTop: 8, fontSize: 14, color: "#999" },
  bottomBar: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: "#fff",
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    borderTopWidth: 1,
    borderTopColor: "#e8e8e8",
  },
});

export default WHInventoryCheckScreen;
