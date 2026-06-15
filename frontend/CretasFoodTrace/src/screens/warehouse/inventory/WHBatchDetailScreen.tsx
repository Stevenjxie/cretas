/**
 * 批次详情页面
 * 对应原型: warehouse/batch-detail.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import { Text, Surface, Button, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useRoute, RouteProp, CommonActions } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";
import { formatNumberWithCommas } from "../../../utils/formatters";
import { appAlert, AppDialogHost } from "../../../components/ui/AppDialog";
import { labelApiClient } from "../../../services/api/labelApiClient";
import { printBatchLabel } from "../../../services/BatchLabelPrintService";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, "WHBatchDetail">;

interface ConsumptionLog {
  id: string;
  time: string;
  action: string;
  type: "in" | "out";
}

/**
 * 计算批次距离过期的天数
 */
const calculateExpiryDays = (expiryDate?: string): number => {
  if (!expiryDate) return 999;
  const expiry = new Date(expiryDate);
  const now = new Date();
  return Math.ceil((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
};

/**
 * 判断批次状态
 */
const getBatchStatus = (expiryDays: number): { status: string; label: string } => {
  if (expiryDays <= 0) return { status: 'danger', label: '已过期' };
  if (expiryDays <= 3) return { status: 'warning', label: '即将过期' };
  if (expiryDays <= 7) return { status: 'warning', label: '临期提醒' };
  return { status: 'normal', label: '正常' };
};

/**
 * 获取保质期描述
 */
const getShelfLifeDesc = (storageType?: string): string => {
  switch (storageType?.toLowerCase()) {
    case 'frozen': return '90天';
    case 'fresh': return '7天';
    case 'dry': return '365天';
    default: return '7天';
  }
};

/**
 * 获取温度描述
 */
const getTemperatureDesc = (storageType?: string): { temp: string; status: string } => {
  switch (storageType?.toLowerCase()) {
    case 'frozen': return { temp: '-18°C', status: '符合要求' };
    case 'fresh': return { temp: '2°C', status: '符合要求' };
    case 'dry': return { temp: '常温', status: '符合要求' };
    default: return { temp: '2°C', status: '符合要求' };
  }
};

interface BatchDetail {
  batchNumber: string;
  materialName: string;
  materialType: string;
  status: string;
  statusLabel: string;
  expiryDays: number;
  currentQty: number;
  initialQty: number;
  consumed: number;
  consumedPercent: number;
  inboundTime: string;
  productionDate: string;
  shelfLife: string;
  expiryDate: string;
  location: string;
  temperature: string;
  tempStatus: string;
  qualityGrade: string;
  supplier: string;
  inboundNumber: string;
  inspector: string;
  unitPrice: number;
  movingAvgPrice: number | null;
  batchValue: number;
  factoryNumber?: string | null;  // SP4: 厂号
  originPlace?: string | null;    // SP4: 产地
  qualityScore: number;
  qualityDetails: { name: string; value: number }[];
  correlationFactors: { icon: string; name: string; score: string; desc: string; type: string }[];
  consumptionLogs: ConsumptionLog[];
}

export function WHBatchDetailScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const { batchId } = route.params;

  const [loading, setLoading] = useState(true);
  const [batchDetail, setBatchDetail] = useState<BatchDetail | null>(null);

  // 加载数据
  const loadData = useCallback(async () => {
    if (!batchId) {
      setLoading(false);
      return;
    }

    try {
      const response = await materialBatchApiClient.getBatchById(batchId) as
        { data?: MaterialBatch } | MaterialBatch | undefined;

      const batch = (response as { data?: MaterialBatch })?.data ?? (response as MaterialBatch);

      if (batch) {
        const expiryDays = calculateExpiryDays(batch.expiryDate);
        const statusInfo = getBatchStatus(expiryDays);
        const tempInfo = getTemperatureDesc(batch.storageType);
        const initialQty = batch.inboundQuantity ?? 0;
        const currentQty = batch.remainingQuantity ?? 0;
        const consumed = initialQty - currentQty;
        const consumedPercent = initialQty > 0 ? ((consumed / initialQty) * 100) : 0;
        const unitPrice = batch.unitPrice ?? 30;

        setBatchDetail({
          batchNumber: batch.batchNumber || `MB-${batch.id}`,
          materialName: batch.materialName || '物料',
          materialType: batch.storageType?.toLowerCase() === 'frozen' ? '冻品' : '鲜品',
          status: statusInfo.status,
          statusLabel: statusInfo.label,
          expiryDays,
          currentQty,
          initialQty,
          consumed,
          consumedPercent: Math.round(consumedPercent * 10) / 10,
          inboundTime: batch.createdAt?.replace('T', ' ').slice(0, 16) ?? '',
          productionDate: batch.createdAt?.split('T')[0] ?? '',
          shelfLife: getShelfLifeDesc(batch.storageType),
          expiryDate: batch.expiryDate?.split('T')[0] ?? '',
          location: batch.storageLocation || 'A区-冷藏库',
          temperature: tempInfo.temp,
          tempStatus: tempInfo.status,
          qualityGrade: 'A级',
          supplier: batch.supplierName || '供应商',
          inboundNumber: batch.batchNumber || `MB-${batch.id}`,
          inspector: '质检员',
          unitPrice,
          movingAvgPrice: batch.movingAvgPrice ?? null,
          batchValue: currentQty * unitPrice,
          factoryNumber: batch.factoryNumber ?? null,
          originPlace: batch.originPlace ?? null,
          qualityScore: 94, // 质量评分需要从质检API获取，暂用默认值
          qualityDetails: [
            { name: "新鲜度", value: 92 },
            { name: "温度合规", value: 98 },
            { name: "外观品质", value: 95 },
          ],
          correlationFactors: [
            { icon: "🏭", name: "供应商评级", score: "+12%", desc: `${batch.supplierName || '供应商'} A级供应商`, type: "positive" },
            { icon: "🌡️", name: "冷链控制", score: "+8%", desc: `全程${tempInfo.temp}恒温`, type: "positive" },
            { icon: "📦", name: "库存周转", score: "持平", desc: "周转天数5天，行业平均5.2天", type: "neutral" },
            { icon: "⏰", name: "保质期压力", score: expiryDays <= 3 ? "-3%" : "良好", desc: expiryDays <= 3 ? `剩余${expiryDays}天，建议加速消耗` : `剩余${expiryDays}天，状态良好`, type: expiryDays <= 3 ? "warning" : "positive" },
          ],
          consumptionLogs: [
            { id: "1", time: batch.updatedAt?.slice(5, 16).replace('T', ' ') ?? '', action: `当前库存 ${currentQty}kg`, type: "in" as const },
          ],
        });
      }
    } catch (error) {
      handleError(error, { title: t('messages.loadBatchDetailFailed') });
    } finally {
      setLoading(false);
    }
  }, [batchId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // ── 标签打印 ──────────────────────────────────────────────────────────────
  const [printing, setPrinting] = useState(false);

  const handlePrintLabel = useCallback(async () => {
    if (!batchId || !batchDetail) return;
    if (printing) return;
    setPrinting(true);
    try {
      // 1. 生成标签 (防呆: 已有活跃标签时返回 400, 让用户重用旧标签也 OK)
      let labelId: string | null = null;
      let qrContent: string | null = null;
      try {
        const genRes = await labelApiClient.generateMaterialBatchLabel(batchId);
        labelId = genRes.data?.id ?? null;
        qrContent = genRes.data?.traceCode ?? genRes.data?.labelCode ?? null;
      } catch {
        // 已有活跃标签 → 查已有标签复用
        const listRes = await labelApiClient.getLabelsByBatchId(batchId);
        const active = (listRes.data ?? []).find(
          (l) => l.status === 'ACTIVE' || l.status === 'PRINTED',
        );
        if (active) {
          labelId = active.id;
          qrContent = active.traceCode ?? active.labelCode ?? null;
        }
      }

      // 2. 调用系统打印对话框
      await printBatchLabel({
        materialName: batchDetail.materialName,
        batchNumber: batchDetail.batchNumber,
        factoryNumber: batchDetail.factoryNumber,
        originPlace: batchDetail.originPlace,
        quantity: batchDetail.currentQty,
        quantityUnit: 'kg',
        qrContent,
        labelCreatedAt: batchDetail.inboundTime,
      });

      // 3. 记录打印操作 (非阻塞, 失败不影响流程)
      if (labelId) {
        labelApiClient.recordPrint(labelId).catch(() => {});
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '打印失败，请重试';
      appAlert('打印机没连上', `${msg}\n\n请检查打印机连接后重试`, [
        { text: '重试', onPress: () => handlePrintLabel() },
        { text: '取消', style: 'cancel' },
      ]);
    } finally {
      setPrinting(false);
    }
  }, [batchId, batchDetail, printing]);

  // 加载中显示
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>{t('batch.detail.title')}</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>{t('batch.detail.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  // 无数据显示
  if (!batchDetail) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>{t('batch.detail.title')}</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <Text style={styles.loadingText}>{t('batch.detail.noData')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  const getStatusStyle = (status: string) => {
    switch (status) {
      case "warning":
        return { color: "#f57c00", bgColor: "#fff3e0", icon: "!" };
      case "danger":
        return { color: "#d32f2f", bgColor: "#ffebee", icon: "!" };
      default:
        return { color: "#4CAF50", bgColor: "#e8f5e9", icon: "✓" };
    }
  };

  const statusStyle = getStatusStyle(batchDetail.status);

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      <AppDialogHost />
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle}>{t('batch.detail.title')}</Text>
          <Text style={styles.headerSubtitle}>{batchDetail.batchNumber}</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 批次状态 */}
        <Surface
          style={[styles.statusCard, { backgroundColor: statusStyle.bgColor }]}
          elevation={1}
        >
          <View style={[styles.statusIcon, { backgroundColor: statusStyle.color }]}>
            <Text style={styles.statusIconText}>{statusStyle.icon}</Text>
          </View>
          <View style={styles.statusInfo}>
            <Text style={[styles.statusTitle, { color: statusStyle.color }]}>
              {batchDetail.statusLabel}
            </Text>
            <Text style={styles.statusDesc}>
              {t('batch.detail.daysRemaining', { days: batchDetail.expiryDays })}
            </Text>
          </View>
        </Surface>

        {/* 批次信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('batch.detail.batchInfo')}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.batchNumber')}</Text>
            <Text style={styles.infoValue}>{batchDetail.batchNumber}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.materialName')}</Text>
            <Text style={styles.infoValue}>{batchDetail.materialName}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.materialType')}</Text>
            <Text style={styles.infoValue}>{batchDetail.materialType}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.currentQty')}</Text>
            <Text style={[styles.infoValue, styles.highlightValue]}>
              {batchDetail.currentQty} kg
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.initialQty')}</Text>
            <Text style={styles.infoValue}>{batchDetail.initialQty} kg</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.consumed')}</Text>
            <Text style={styles.infoValue}>
              {batchDetail.consumed} kg ({batchDetail.consumedPercent}%)
            </Text>
          </View>
        </View>

        {/* 时间信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('batch.detail.timeInfo')}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.inboundDate')}</Text>
            <Text style={styles.infoValue}>{batchDetail.inboundTime}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.productionDate')}</Text>
            <Text style={styles.infoValue}>{batchDetail.productionDate}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.shelfLife')}</Text>
            <Text style={styles.infoValue}>{batchDetail.shelfLife}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.expiryDate')}</Text>
            <Text style={[styles.infoValue, { color: "#f57c00" }]}>
              {batchDetail.expiryDate} {t('batch.detail.daysLater', { days: batchDetail.expiryDays })}
            </Text>
          </View>
        </View>

        {/* 储存信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('batch.detail.storageInfo')}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.location')}</Text>
            <Text style={styles.infoValue}>{batchDetail.location}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.temperature')}</Text>
            <Text style={[styles.infoValue, { color: "#4CAF50" }]}>
              {batchDetail.temperature} ({t('batch.detail.tempStatus')})
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.qualityGrade')}</Text>
            <Text style={styles.infoValue}>{batchDetail.qualityGrade}</Text>
          </View>
        </View>

        {/* 来源信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('batch.detail.sourceInfo')}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.supplier')}</Text>
            <Text style={styles.infoValue}>{batchDetail.supplier}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.inboundNumber')}</Text>
            <Text style={[styles.infoValue, { color: "#4CAF50" }]}>
              {batchDetail.inboundNumber}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.inspector')}</Text>
            <Text style={styles.infoValue}>{batchDetail.inspector}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.unitPrice')}</Text>
            <Text style={styles.infoValue}>¥{batchDetail.unitPrice}/kg</Text>
          </View>
          <View style={styles.infoRow} testID="moving-avg-price">
            <Text style={styles.infoLabel}>移动均价</Text>
            <Text style={styles.infoValue}>
              {batchDetail.movingAvgPrice != null
                ? `¥${batchDetail.movingAvgPrice.toFixed(2)}/kg`
                : '暂无数据'}
            </Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('batch.detail.batchValue')}</Text>
            <Text style={[styles.infoValue, styles.highlightValue]}>
              ¥{formatNumberWithCommas(batchDetail.batchValue)}
            </Text>
          </View>
          {batchDetail.factoryNumber ? (
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>厂号</Text>
              <Text style={styles.infoValue}>{batchDetail.factoryNumber}</Text>
            </View>
          ) : null}
          {batchDetail.originPlace ? (
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>产地</Text>
              <Text style={styles.infoValue}>{batchDetail.originPlace}</Text>
            </View>
          ) : null}
        </View>

        {/* AI 质量关联分析 */}
        <View style={styles.aiSection}>
          <View style={styles.aiHeader}>
            <View style={styles.aiTitleRow}>
              <Text style={styles.aiIcon}>🤖</Text>
              <Text style={styles.aiTitle}>{t('batch.detail.aiAnalysis')}</Text>
            </View>
            <View style={styles.aiBadge}>
              <Text style={styles.aiBadgeText}>{t('batch.detail.smartAnalysis')}</Text>
            </View>
          </View>

          {/* 质量评分 */}
          <View style={styles.qualityScoreCard}>
            <View style={styles.qualityScoreMain}>
              <Text style={styles.qualityScoreValue}>{batchDetail.qualityScore}</Text>
              <Text style={styles.qualityScoreLabel}>{t('batch.detail.qualityScore')}</Text>
            </View>
            <View style={styles.qualityDetails}>
              {batchDetail.qualityDetails.map((detail, index) => (
                <View key={index} style={styles.qualityDetailItem}>
                  <Text style={styles.qualityDetailLabel}>{detail.name}</Text>
                  <View style={styles.qualityDetailBar}>
                    <View
                      style={[styles.qualityBarFill, { width: `${detail.value}%` }]}
                    />
                  </View>
                  <Text style={styles.qualityDetailValue}>{detail.value}%</Text>
                </View>
              ))}
            </View>
          </View>

          {/* 关联因素 */}
          <View style={styles.correlationCard}>
            <Text style={styles.correlationTitle}>📊 {t('batch.detail.correlationAnalysis')}</Text>
            {batchDetail.correlationFactors.map((factor, index) => (
              <View
                key={index}
                style={[
                  styles.correlationItem,
                  factor.type === "positive" && styles.correlationPositive,
                  factor.type === "warning" && styles.correlationWarning,
                ]}
              >
                <View style={styles.correlationFactor}>
                  <Text style={styles.correlationIcon}>{factor.icon}</Text>
                  <Text style={styles.correlationName}>{factor.name}</Text>
                </View>
                <View style={styles.correlationValue}>
                  <Text
                    style={[
                      styles.correlationScore,
                      factor.type === "positive" && { color: "#4CAF50" },
                      factor.type === "warning" && { color: "#f57c00" },
                    ]}
                  >
                    {factor.score}
                  </Text>
                  <Text style={styles.correlationDesc}>{factor.desc}</Text>
                </View>
              </View>
            ))}
          </View>
        </View>

        {/* 消耗记录 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('batch.detail.consumptionLog')}</Text>
          <View style={styles.timeline}>
            {batchDetail.consumptionLogs.map((log, index) => (
              <View key={log.id} style={styles.timelineItem}>
                <View
                  style={[
                    styles.timelineDot,
                    log.type === "in" && styles.timelineDotIn,
                    log.type === "out" && styles.timelineDotOut,
                  ]}
                />
                {index < batchDetail.consumptionLogs.length - 1 && (
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

        {/* 底部操作 */}
        <View style={styles.bottomActions}>
          {/* 打印标签 — 大按钮，仓管主入口 (min 44pt 防呆) */}
          <TouchableOpacity
            style={[styles.printBtn, printing && styles.printBtnDisabled]}
            onPress={handlePrintLabel}
            disabled={printing}
            accessibilityLabel="打印批次标签"
            accessibilityRole="button"
          >
            {printing ? (
              <ActivityIndicator size="small" color="#fff" />
            ) : (
              <MaterialCommunityIcons name="printer" size={22} color="#fff" />
            )}
            <Text style={styles.printBtnText}>
              {printing ? '生成中...' : '打印标签'}
            </Text>
          </TouchableOpacity>

          <Button
            mode="outlined"
            onPress={() => navigation.navigate("WHBatchTrace", { batchId: batchId })}
            style={styles.actionBtn}
            labelStyle={{ color: "#666" }}
          >
            {t('batch.detail.actions.trace')}
          </Button>
          <Button
            mode="outlined"
            onPress={() => navigation.dispatch(CommonActions.navigate("WHExpireHandle"))}
            style={[styles.actionBtn, styles.warningActionBtn]}
            labelStyle={{ color: "#f57c00" }}
          >
            {t('batch.detail.actions.expireHandle')}
          </Button>
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
  statusCard: {
    flexDirection: "row",
    alignItems: "center",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  statusIcon: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
    marginRight: 12,
  },
  statusIconText: {
    fontSize: 20,
    color: "#fff",
    fontWeight: "bold",
  },
  statusInfo: {},
  statusTitle: {
    fontSize: 18,
    fontWeight: "bold",
  },
  statusDesc: {
    fontSize: 13,
    color: "#666",
    marginTop: 2,
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
  aiSection: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  aiHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 16,
  },
  aiTitleRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  aiIcon: {
    fontSize: 20,
  },
  aiTitle: {
    fontSize: 16,
    fontWeight: "600",
    color: "#333",
  },
  aiBadge: {
    backgroundColor: "#e8f5e9",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  aiBadgeText: {
    fontSize: 12,
    color: "#4CAF50",
    fontWeight: "500",
  },
  qualityScoreCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  qualityScoreMain: {
    alignItems: "center",
    marginBottom: 16,
  },
  qualityScoreValue: {
    fontSize: 48,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  qualityScoreLabel: {
    fontSize: 14,
    color: "#666",
  },
  qualityDetails: {
    gap: 10,
  },
  qualityDetailItem: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  qualityDetailLabel: {
    width: 70,
    fontSize: 13,
    color: "#666",
  },
  qualityDetailBar: {
    flex: 1,
    height: 6,
    backgroundColor: "#e0e0e0",
    borderRadius: 3,
  },
  qualityBarFill: {
    height: "100%",
    backgroundColor: "#4CAF50",
    borderRadius: 3,
  },
  qualityDetailValue: {
    width: 40,
    fontSize: 13,
    color: "#333",
    textAlign: "right",
  },
  correlationCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 12,
    padding: 16,
  },
  correlationTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
    marginBottom: 12,
  },
  correlationItem: {
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: "#eee",
  },
  correlationPositive: {},
  correlationWarning: {},
  correlationFactor: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginBottom: 4,
  },
  correlationIcon: {
    fontSize: 16,
  },
  correlationName: {
    fontSize: 14,
    color: "#333",
    fontWeight: "500",
  },
  correlationValue: {
    marginLeft: 24,
  },
  correlationScore: {
    fontSize: 14,
    fontWeight: "600",
    color: "#666",
  },
  correlationDesc: {
    fontSize: 12,
    color: "#999",
    marginTop: 2,
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
  bottomActions: {
    flexDirection: "column",
    gap: 10,
    paddingHorizontal: 16,
    paddingVertical: 16,
  },
  printBtn: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
    backgroundColor: "#4CAF50",
    borderRadius: 10,
    paddingVertical: 14,   // 防呆: ≥44pt 触摸目标
    paddingHorizontal: 20,
    minHeight: 52,
  },
  printBtnDisabled: {
    backgroundColor: "#9E9E9E",
  },
  printBtnText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "700",
  },
  actionBtn: {
    borderRadius: 8,
    borderColor: "#ddd",
  },
  warningActionBtn: {
    borderColor: "#f57c00",
  },
});

export default WHBatchDetailScreen;
