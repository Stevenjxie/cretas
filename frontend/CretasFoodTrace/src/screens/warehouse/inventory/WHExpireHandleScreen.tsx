/**
 * 过期处理页面
 * 对应原型: warehouse/expire-handle.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
} from "react-native";
import { Text, Surface, Button, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

interface ExpireBatch {
  id: string;
  batchNumber: string;
  materialName: string;
  materialType: string;
  quantity: number;
  expireDate: string;
  daysLeft: number;
  location: string;
  status: "expired" | "warning" | "normal";
}

type FilterTab = "all" | "warning" | "expired";

// 获取存储类型显示名称
const getStorageTypeLabel = (type?: string): string => {
  switch (type) {
    case 'fresh': return '鲜品';
    case 'frozen': return '冻品';
    case 'dry': return '干货';
    default: return '其他';
  }
};

// 计算批次过期状态
const calculateExpireStatus = (expiryDate?: string): { status: 'expired' | 'warning' | 'normal'; daysLeft: number } => {
  if (!expiryDate) {
    return { status: 'normal', daysLeft: 999 };
  }
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const expiry = new Date(expiryDate);
  expiry.setHours(0, 0, 0, 0);
  const diffTime = expiry.getTime() - today.getTime();
  const daysLeft = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

  if (daysLeft <= 0) {
    return { status: 'expired', daysLeft };
  } else if (daysLeft <= 3) {
    return { status: 'warning', daysLeft };
  }
  return { status: 'normal', daysLeft };
};

export function WHExpireHandleScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);
  const [activeFilter, setActiveFilter] = useState<FilterTab>("all");
  const [batches, setBatches] = useState<ExpireBatch[]>([]);

  // 加载过期批次数据
  const loadExpireBatches = useCallback(async () => {
    try {
      // 获取所有可用批次
      const response = await materialBatchApiClient.getMaterialBatches({
        status: 'available',
        size: 100
      }) as { data?: { content?: MaterialBatch[] } | MaterialBatch[] };

      const allBatches: MaterialBatch[] = (response.data as { content?: MaterialBatch[] })?.content || response.data as MaterialBatch[] || [];

      // 筛选即将过期或已过期的批次（7天内）
      const expireBatches: ExpireBatch[] = allBatches
        .filter((batch: MaterialBatch) => {
          if (!batch.expiryDate) return false;
          const { daysLeft } = calculateExpireStatus(batch.expiryDate);
          return daysLeft <= 7; // 7天内过期的批次
        })
        .map((batch: MaterialBatch) => {
          const { status, daysLeft } = calculateExpireStatus(batch.expiryDate);
          return {
            id: batch.id,
            batchNumber: batch.batchNumber,
            materialName: batch.materialName || '未知物料',
            materialType: getStorageTypeLabel(batch.storageType),
            quantity: batch.remainingQuantity || 0,
            expireDate: batch.expiryDate || '-',
            daysLeft,
            location: batch.storageLocation || '未知库位',
            status,
          };
        })
        .sort((a: ExpireBatch, b: ExpireBatch) => a.daysLeft - b.daysLeft); // 按剩余天数排序

      setBatches(expireBatches);
    } catch (error) {
      handleError(error, { title: '加载过期批次失败' });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadExpireBatches();
  }, [loadExpireBatches]);

  const filteredBatches = batches.filter((batch) => {
    if (activeFilter === "all") return true;
    if (activeFilter === "expired") return batch.status === "expired";
    if (activeFilter === "warning")
      return batch.status === "warning" || batch.status === "normal";
    return true;
  });

  const expiredCount = batches.filter((b) => b.status === "expired").length;
  const warningCount = batches.filter(
    (b) => b.status === "warning" || b.status === "normal"
  ).length;

  // 报损处理 — 导航到报损单填报页（WastageReport），由仓管填照片+数量后走审批流
  // ⚠️ 原实现调用 materialBatchApiClient.updateBatch({status:'depleted'})，
  //    但 UpdateMaterialBatchRequest 无 status 字段，后端静默丢弃，弹"成功"却没实际变更
  //    （lying UI，违反"禁止降级处理"铁律）。
  //    正确路径：POST /wastage-reports → 财务/厂长审批 → APPLIED 后由后端原子扣减库存。
  const handleReportDamage = (batch: ExpireBatch) => {
    navigation.navigate("WastageReport", { batchId: batch.id });
  };

  // 转冻品处理
  const handleConvertToFrozen = async (batch: ExpireBatch) => {
    Alert.alert("转冻品", `将 ${batch.batchNumber} 转为冻品？`, [
      { text: "取消", style: "cancel" },
      {
        text: "确定",
        onPress: async () => {
          setProcessing(true);
          try {
            // 调用转冻品API
            const today = new Date().toISOString().split('T')[0] || '';
            await materialBatchApiClient.convertToFrozen(batch.id, {
              convertedBy: 1, // 默认操作人
              convertedDate: today,
              storageLocation: batch.location || 'B区-冷冻库',
            });
            Alert.alert("成功", "已转为冻品，保质期延长30天");
            loadExpireBatches(); // 刷新列表
          } catch (error) {
            handleError(error, { title: '转冻品失败' });
          } finally {
            setProcessing(false);
          }
        },
      },
    ]);
  };

  // 优先出库标记
  const handlePriorityOutbound = async (batch: ExpireBatch) => {
    setProcessing(true);
    try {
      await materialBatchApiClient.updateBatch(batch.id, {
        notes: `优先出库标记 ${new Date().toISOString().split('T')[0]}`,
      });
      Alert.alert("成功", `已将批次 ${batch.batchNumber} 标记为优先出库`);
    } catch (error) {
      handleError(error, { title: '标记优先出库失败' });
    } finally {
      setProcessing(false);
    }
  };

  const getExpireTagText = (batch: ExpireBatch) => {
    if (batch.status === "expired") return "已过期";
    if (batch.daysLeft <= 3) return `${batch.daysLeft}天后`;
    return `${batch.daysLeft}天后`;
  };

  // 加载状态
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>过期处理</Text>
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

  const renderExpireCard = (batch: ExpireBatch) => (
    <View
      key={batch.id}
      style={[
        styles.expireCard,
        batch.status === "expired" && styles.expireCardExpired,
        batch.status === "warning" && styles.expireCardWarning,
      ]}
    >
      <View style={styles.expireHeader}>
        <Text style={styles.expireBatch}>{batch.batchNumber}</Text>
        <View
          style={[
            styles.expireTag,
            batch.status === "expired" && styles.expireTagDanger,
            batch.status === "warning" && styles.expireTagWarning,
          ]}
        >
          <Text
            style={[
              styles.expireTagText,
              batch.status === "expired" && styles.expireTagTextDanger,
              batch.status === "warning" && styles.expireTagTextWarning,
            ]}
          >
            {getExpireTagText(batch)}
          </Text>
        </View>
      </View>

      <View style={styles.expireContent}>
        <View style={styles.expireRow}>
          <Text style={styles.expireLabel}>物料</Text>
          <Text style={styles.expireValue}>
            {batch.materialName} ({batch.materialType})
          </Text>
        </View>
        <View style={styles.expireRow}>
          <Text style={styles.expireLabel}>数量</Text>
          <Text style={styles.expireValue}>{batch.quantity} kg</Text>
        </View>
        <View style={styles.expireRow}>
          <Text style={styles.expireLabel}>过期时间</Text>
          <Text
            style={[
              styles.expireValue,
              batch.status === "expired" && styles.textDanger,
              batch.status === "warning" && styles.textWarning,
            ]}
          >
            {batch.expireDate}
            {batch.status === "expired" && batch.daysLeft === 0 && " (今天)"}
            {batch.status === "expired" && batch.daysLeft < 0 && ` (已过期${Math.abs(batch.daysLeft)}天)`}
          </Text>
        </View>
        <View style={styles.expireRow}>
          <Text style={styles.expireLabel}>库位</Text>
          <Text style={styles.expireValue}>{batch.location}</Text>
        </View>
      </View>

      <View style={styles.expireActions}>
        {batch.status === "expired" ? (
          <>
            <TouchableOpacity
              style={[styles.actionBtn, styles.actionBtnDanger]}
              onPress={() => handleReportDamage(batch)}
            >
              <Text style={styles.actionBtnDangerText}>报损处理</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.actionBtn, styles.actionBtnWarning]}
              onPress={() => handleConvertToFrozen(batch)}
            >
              <Text style={styles.actionBtnWarningText}>转冻品</Text>
            </TouchableOpacity>
          </>
        ) : (
          <>
            <TouchableOpacity
              style={[styles.actionBtn, styles.actionBtnSecondary]}
              onPress={() => handlePriorityOutbound(batch)}
            >
              <Text style={styles.actionBtnSecondaryText}>优先出库</Text>
            </TouchableOpacity>
            {batch.status === "warning" ? (
              <TouchableOpacity
                style={[styles.actionBtn, styles.actionBtnWarning]}
                onPress={() => handleConvertToFrozen(batch)}
              >
                <Text style={styles.actionBtnWarningText}>转冻品</Text>
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                style={[styles.actionBtn, styles.actionBtnOutline]}
                onPress={() =>
                  navigation.navigate("WHBatchDetail", {
                    batchId: batch.id,
                    batchNumber: batch.batchNumber,
                  })
                }
              >
                <Text style={styles.actionBtnOutlineText}>查看详情</Text>
              </TouchableOpacity>
            )}
          </>
        )}
      </View>
    </View>
  );

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
          <Text style={styles.headerTitle}>过期处理</Text>
          <Text style={styles.headerSubtitle}>
            即将过期 {warningCount} 批 | 已过期 {expiredCount} 批
          </Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 筛选标签 */}
        <View style={styles.filterTabs}>
          <TouchableOpacity
            style={[
              styles.filterTab,
              activeFilter === "all" && styles.filterTabActive,
            ]}
            onPress={() => setActiveFilter("all")}
          >
            <Text
              style={[
                styles.filterTabText,
                activeFilter === "all" && styles.filterTabTextActive,
              ]}
            >
              全部({batches.length})
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterTab,
              activeFilter === "warning" && styles.filterTabActive,
            ]}
            onPress={() => setActiveFilter("warning")}
          >
            <Text
              style={[
                styles.filterTabText,
                activeFilter === "warning" && styles.filterTabTextActive,
              ]}
            >
              即将过期({warningCount})
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterTab,
              activeFilter === "expired" && styles.filterTabActive,
            ]}
            onPress={() => setActiveFilter("expired")}
          >
            <Text
              style={[
                styles.filterTabText,
                activeFilter === "expired" && styles.filterTabTextActive,
              ]}
            >
              已过期({expiredCount})
            </Text>
          </TouchableOpacity>
        </View>

        {/* 已过期批次 */}
        {(activeFilter === "all" || activeFilter === "expired") &&
          expiredCount > 0 && (
            <View style={styles.section}>
              <Text style={[styles.sectionTitle, styles.sectionTitleDanger]}>
                已过期 (需立即处理)
              </Text>
              {filteredBatches
                .filter((b) => b.status === "expired")
                .map(renderExpireCard)}
            </View>
          )}

        {/* 即将过期批次 */}
        {(activeFilter === "all" || activeFilter === "warning") && (
          <View style={styles.section}>
            <Text style={[styles.sectionTitle, styles.sectionTitleWarning]}>
              即将过期 (3天内)
            </Text>
            {filteredBatches
              .filter((b) => b.status !== "expired")
              .map(renderExpireCard)}
          </View>
        )}

        {/* 处理说明 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>处理方式说明</Text>

          <View style={styles.infoItem}>
            <View style={styles.infoIcon}>
              <Text style={styles.infoIconText}>1</Text>
            </View>
            <View style={styles.infoContent}>
              <Text style={styles.infoTitle}>优先出库</Text>
              <Text style={styles.infoDesc}>
                标记为FIFO优先消耗，系统自动推荐此批次
              </Text>
            </View>
          </View>

          <View style={styles.infoItem}>
            <View style={styles.infoIcon}>
              <Text style={styles.infoIconText}>2</Text>
            </View>
            <View style={styles.infoContent}>
              <Text style={styles.infoTitle}>转冻品</Text>
              <Text style={styles.infoDesc}>
                将鲜品转为冻品，延长保质期30天
              </Text>
            </View>
          </View>

          <View style={styles.infoItem}>
            <View style={styles.infoIcon}>
              <Text style={styles.infoIconText}>3</Text>
            </View>
            <View style={styles.infoContent}>
              <Text style={styles.infoTitle}>报损处理</Text>
              <Text style={styles.infoDesc}>已过期物料需进行报损登记</Text>
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
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 8,
  },
  filterTab: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    backgroundColor: "#fff",
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
  sectionTitleDanger: {
    color: "#f44336",
  },
  sectionTitleWarning: {
    color: "#f57c00",
  },
  expireCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
    borderLeftWidth: 3,
    borderLeftColor: "#e0e0e0",
  },
  expireCardExpired: {
    borderLeftColor: "#f44336",
    backgroundColor: "#ffebee",
  },
  expireCardWarning: {
    borderLeftColor: "#f57c00",
    backgroundColor: "#fff8e1",
  },
  expireHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 10,
  },
  expireBatch: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  expireTag: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
    backgroundColor: "#f5f5f5",
  },
  expireTagDanger: {
    backgroundColor: "#ffcdd2",
  },
  expireTagWarning: {
    backgroundColor: "#ffe0b2",
  },
  expireTagText: {
    fontSize: 11,
    fontWeight: "500",
    color: "#666",
  },
  expireTagTextDanger: {
    color: "#d32f2f",
  },
  expireTagTextWarning: {
    color: "#f57c00",
  },
  expireContent: {
    gap: 6,
    marginBottom: 12,
  },
  expireRow: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  expireLabel: {
    fontSize: 13,
    color: "#999",
  },
  expireValue: {
    fontSize: 13,
    color: "#333",
  },
  textDanger: {
    color: "#f44336",
  },
  textWarning: {
    color: "#f57c00",
  },
  expireActions: {
    flexDirection: "row",
    gap: 8,
  },
  actionBtn: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 6,
    alignItems: "center",
  },
  actionBtnDanger: {
    backgroundColor: "#ffcdd2",
  },
  actionBtnDangerText: {
    color: "#d32f2f",
    fontSize: 13,
    fontWeight: "500",
  },
  actionBtnWarning: {
    backgroundColor: "#ffe0b2",
  },
  actionBtnWarningText: {
    color: "#f57c00",
    fontSize: 13,
    fontWeight: "500",
  },
  actionBtnSecondary: {
    backgroundColor: "#e8f5e9",
  },
  actionBtnSecondaryText: {
    color: "#4CAF50",
    fontSize: 13,
    fontWeight: "500",
  },
  actionBtnOutline: {
    backgroundColor: "#f5f5f5",
    borderWidth: 1,
    borderColor: "#ddd",
  },
  actionBtnOutlineText: {
    color: "#666",
    fontSize: 13,
    fontWeight: "500",
  },
  infoItem: {
    flexDirection: "row",
    alignItems: "flex-start",
    marginBottom: 12,
  },
  infoIcon: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: "#4CAF50",
    alignItems: "center",
    justifyContent: "center",
    marginRight: 12,
  },
  infoIconText: {
    color: "#fff",
    fontSize: 12,
    fontWeight: "600",
  },
  infoContent: {
    flex: 1,
  },
  infoTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
    marginBottom: 2,
  },
  infoDesc: {
    fontSize: 12,
    color: "#999",
    lineHeight: 18,
  },
});

export default WHExpireHandleScreen;
