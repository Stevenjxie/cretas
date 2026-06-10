/**
 * 出入库统计页面 — SP11 进销存台账对接
 *
 * F-049~052 修复: 删掉硬编码 mock 数组, 改为调用真实后端
 *   GET /api/mobile/{factoryId}/inventory/ledger
 *
 * 防呆原则:
 * - 加载/错误/空数据三态齐全, 禁止降级返回假数据
 * - 错误明确显示后端 message, 不静默吞掉
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
  RefreshControl,
} from "react-native";
import { Text, Button } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHInventoryStackParamList } from "../../../types/navigation";
import { formatNumberWithCommas } from "../../../utils/formatters";
import {
  inventoryLedgerApiClient,
  InventoryLedgerLineDTO,
  InventoryLedgerDTO,
} from "../../../services/api/inventoryLedgerApiClient";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

type TimeFilter = "today" | "week" | "month" | "custom";

// ============================================================
// Date helpers
// ============================================================

function toISODate(d: Date): string {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function getDateRange(filter: TimeFilter): { startDate: string; endDate: string } {
  const today = new Date();
  const end = toISODate(today);

  if (filter === 'today') {
    return { startDate: end, endDate: end };
  }
  if (filter === 'week') {
    const start = new Date(today);
    start.setDate(today.getDate() - 6);
    return { startDate: toISODate(start), endDate: end };
  }
  if (filter === 'month') {
    const start = new Date(today.getFullYear(), today.getMonth(), 1);
    return { startDate: toISODate(start), endDate: end };
  }
  // custom — caller manages date range separately; fallback = today
  return { startDate: end, endDate: end };
}

// ============================================================
// Summary helpers
// ============================================================

function sumField(lines: InventoryLedgerLineDTO[], field: keyof InventoryLedgerLineDTO): number {
  return lines.reduce((acc, line) => {
    const v = line[field];
    return acc + (typeof v === 'number' ? v : 0);
  }, 0);
}

// ============================================================
// Component
// ============================================================

export function WHIOStatisticsScreen() {
  const { t } = useTranslation('warehouse');
  const navigation = useNavigation<NavigationProp>();

  const [activeFilter, setActiveFilter] = useState<TimeFilter>("week");
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ledger, setLedger] = useState<InventoryLedgerDTO | null>(null);

  // ============================================================
  // Data fetch
  // ============================================================

  const fetchLedger = useCallback(async (filter: TimeFilter, isRefresh = false) => {
    if (isRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);

    try {
      const { startDate, endDate } = getDateRange(filter);
      const data = await inventoryLedgerApiClient.getLedger({ startDate, endDate });
      setLedger(data);
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : '进销存台账查询失败，请稍后重试';
      setError(msg);
      setLedger(null);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    fetchLedger(activeFilter);
  }, [activeFilter, fetchLedger]);

  const handleFilterChange = (filter: TimeFilter) => {
    setActiveFilter(filter);
  };

  const handleRefresh = () => {
    fetchLedger(activeFilter, true);
  };

  // ============================================================
  // Derived data from real API
  // ============================================================

  const lines: InventoryLedgerLineDTO[] = ledger?.lines ?? [];

  const totalInbound = sumField(lines, 'inboundQty');
  const totalOutboundProduction = sumField(lines, 'outboundProductionQty');
  const totalOutboundSales = sumField(lines, 'outboundSalesQty');
  const totalOutbound = totalOutboundProduction + totalOutboundSales;
  const totalClosing = sumField(lines, 'closingQty');
  const totalAdjust = sumField(lines, 'adjustQty');

  // ============================================================
  // Export stub — real xlsx export is web-admin's responsibility;
  // RN shows summary only.
  // ============================================================

  const handleExport = () => {
    Alert.alert(
      t('inbound.create.alert'),
      '台账导出请使用 Web 管理后台',
    );
  };

  // ============================================================
  // Render helpers
  // ============================================================

  const fmtQty = (v: number | null): string => {
    if (v === null || v === undefined) return '—';
    return formatNumberWithCommas(Number(v.toFixed(3)));
  };

  // ============================================================
  // Loading / Error states
  // ============================================================

  if (loading && !refreshing) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>{t('ioStatistics.title')}</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.centerState}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.centerStateText}>加载进销存台账中…</Text>
        </View>
      </SafeAreaView>
    );
  }

  // ============================================================
  // Main render
  // ============================================================

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
          <Text style={styles.headerTitle}>{t('ioStatistics.title')}</Text>
          {ledger && (
            <Text style={styles.headerSubtitle}>
              {ledger.startDate} ~ {ledger.endDate}
            </Text>
          )}
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView
        style={styles.content}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={handleRefresh}
            colors={["#4CAF50"]}
            tintColor="#4CAF50"
          />
        }
      >
        {/* 时间筛选 */}
        <View style={styles.filterTabs}>
          {[
            { key: "today" as TimeFilter, label: t('conversion.period.today') },
            { key: "week" as TimeFilter, label: t('conversion.period.week') },
            { key: "month" as TimeFilter, label: t('conversion.period.month') },
          ].map((filter) => (
            <TouchableOpacity
              key={filter.key}
              style={[
                styles.filterTab,
                activeFilter === filter.key && styles.filterTabActive,
              ]}
              onPress={() => handleFilterChange(filter.key)}
            >
              <Text
                style={[
                  styles.filterTabText,
                  activeFilter === filter.key && styles.filterTabTextActive,
                ]}
              >
                {filter.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* 错误态 */}
        {error && (
          <View style={styles.errorCard}>
            <MaterialCommunityIcons name="alert-circle-outline" size={20} color="#f44336" />
            <Text style={styles.errorText}>{error}</Text>
            <TouchableOpacity onPress={handleRefresh} style={styles.retryBtn}>
              <Text style={styles.retryBtnText}>重试</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* 空数据态 */}
        {!error && !loading && lines.length === 0 && (
          <View style={styles.emptyCard}>
            <MaterialCommunityIcons name="package-variant-closed" size={40} color="#bbb" />
            <Text style={styles.emptyText}>
              {ledger ? '该时间段内暂无出入库记录' : '暂无数据'}
            </Text>
          </View>
        )}

        {/* 总览数据 */}
        {lines.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>
              {activeFilter === 'today' ? '今日汇总' :
               activeFilter === 'week' ? '近7天汇总' : '本月汇总'}
            </Text>
            <View style={styles.overviewRow}>
              <View style={[styles.overviewCard, styles.overviewCardInbound]}>
                <View style={styles.overviewIconContainer}>
                  <Text style={styles.overviewIcon}>IN</Text>
                </View>
                <View style={styles.overviewContent}>
                  <Text style={styles.overviewLabel}>{t('ioStatistics.totalInbound')}</Text>
                  <Text style={styles.overviewValue}>
                    {fmtQty(totalInbound)} kg
                  </Text>
                </View>
              </View>
              <View style={[styles.overviewCard, styles.overviewCardOutbound]}>
                <View
                  style={[
                    styles.overviewIconContainer,
                    styles.overviewIconOutbound,
                  ]}
                >
                  <Text style={styles.overviewIconOut}>OUT</Text>
                </View>
                <View style={styles.overviewContent}>
                  <Text style={styles.overviewLabel}>{t('ioStatistics.totalOutbound')}</Text>
                  <Text style={styles.overviewValue}>
                    {fmtQty(totalOutbound)} kg
                  </Text>
                </View>
              </View>
            </View>

            {/* 期末结存 + 盘调 */}
            <View style={styles.summaryRow}>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryLabel}>期末结存</Text>
                <Text style={styles.summaryValue}>{fmtQty(totalClosing)} kg</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryLabel}>领用出库</Text>
                <Text style={styles.summaryValue}>{fmtQty(totalOutboundProduction)} kg</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryLabel}>销售出库</Text>
                <Text style={styles.summaryValue}>{fmtQty(totalOutboundSales)} kg</Text>
              </View>
              {totalAdjust !== 0 && (
                <View style={styles.summaryItem}>
                  <Text style={styles.summaryLabel}>盘盈/损</Text>
                  <Text style={[
                    styles.summaryValue,
                    totalAdjust < 0 ? styles.textDanger : styles.textSuccess,
                  ]}>
                    {totalAdjust > 0 ? '+' : ''}{fmtQty(totalAdjust)} kg
                  </Text>
                </View>
              )}
            </View>
          </View>
        )}

        {/* 物料明细列表 */}
        {lines.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>物料明细（{lines.length} 种）</Text>
            {lines.map((line, index) => {
              const net = (line.inboundQty ?? 0) - (line.outboundProductionQty ?? 0) - (line.outboundSalesQty ?? 0);
              return (
                <View key={line.materialTypeId || String(index)} style={styles.lineItem}>
                  <View style={styles.lineHeader}>
                    <View style={styles.lineNameBlock}>
                      <Text style={styles.lineName} numberOfLines={1}>
                        {line.materialName || '未知物料'}
                      </Text>
                      {line.materialCode && (
                        <Text style={styles.lineCode}>{line.materialCode}</Text>
                      )}
                    </View>
                    <Text
                      style={[
                        styles.lineNet,
                        net >= 0 ? styles.balancePositive : styles.balanceNegative,
                      ]}
                    >
                      净: {net >= 0 ? '+' : ''}{fmtQty(net)}
                      {line.unit ? ` ${line.unit}` : ' kg'}
                    </Text>
                  </View>

                  <View style={styles.lineDetails}>
                    <View style={styles.lineDetailCol}>
                      <Text style={styles.lineDetailLabel}>期初</Text>
                      <Text style={styles.lineDetailValue}>
                        {fmtQty(line.openingQty)}{line.unit ? ` ${line.unit}` : ''}
                      </Text>
                    </View>
                    <View style={styles.lineDetailCol}>
                      <Text style={styles.lineDetailLabel}>入库</Text>
                      <Text style={[styles.lineDetailValue, styles.textSuccess]}>
                        +{fmtQty(line.inboundQty)}{line.unit ? ` ${line.unit}` : ''}
                      </Text>
                    </View>
                    <View style={styles.lineDetailCol}>
                      <Text style={styles.lineDetailLabel}>领用</Text>
                      <Text style={[styles.lineDetailValue, styles.textOrange]}>
                        -{fmtQty(line.outboundProductionQty)}{line.unit ? ` ${line.unit}` : ''}
                      </Text>
                    </View>
                    <View style={styles.lineDetailCol}>
                      <Text style={styles.lineDetailLabel}>期末</Text>
                      <Text style={[styles.lineDetailValue, { fontWeight: '700' }]}>
                        {fmtQty(line.closingQty)}{line.unit ? ` ${line.unit}` : ''}
                      </Text>
                    </View>
                  </View>

                  {/* 销售出库 + 盘调 (when non-zero) */}
                  {((line.outboundSalesQty ?? 0) > 0 || (line.adjustQty ?? 0) !== 0 ||
                    (line.transferInQty ?? 0) !== 0 || (line.transferOutQty ?? 0) !== 0) && (
                    <View style={styles.lineExtra}>
                      {(line.outboundSalesQty ?? 0) > 0 && (
                        <Text style={styles.lineExtraText}>
                          销售出库: {fmtQty(line.outboundSalesQty)}{line.unit ? ` ${line.unit}` : ''}
                        </Text>
                      )}
                      {(line.transferInQty ?? 0) !== 0 && (
                        <Text style={styles.lineExtraText}>
                          调拨入: {fmtQty(line.transferInQty)}{line.unit ? ` ${line.unit}` : ''}
                        </Text>
                      )}
                      {(line.transferOutQty ?? 0) !== 0 && (
                        <Text style={styles.lineExtraText}>
                          调拨出: {fmtQty(line.transferOutQty)}{line.unit ? ` ${line.unit}` : ''}
                        </Text>
                      )}
                      {(line.adjustQty ?? 0) !== 0 && (
                        <Text style={[
                          styles.lineExtraText,
                          (line.adjustQty ?? 0) < 0 ? styles.textDanger : styles.textSuccess,
                        ]}>
                          盘点调整: {(line.adjustQty ?? 0) > 0 ? '+' : ''}{fmtQty(line.adjustQty)}{line.unit ? ` ${line.unit}` : ''}
                        </Text>
                      )}
                    </View>
                  )}
                </View>
              );
            })}
          </View>
        )}

        {/* 操作按钮 */}
        <View style={styles.actionButtons}>
          <Button
            mode="outlined"
            onPress={handleExport}
            style={styles.actionBtnSecondary}
            labelStyle={{ color: "#666" }}
          >
            {t('conversion.exportReport')}
          </Button>
          <Button
            mode="outlined"
            onPress={handleRefresh}
            style={styles.actionBtnSecondary}
            labelStyle={{ color: "#4CAF50" }}
            disabled={loading || refreshing}
          >
            刷新
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
    fontSize: 11,
    color: "rgba(255,255,255,0.85)",
    marginTop: 2,
  },
  headerRight: {
    width: 28,
  },
  content: {
    flex: 1,
  },
  // ---- Filter tabs ----
  filterTabs: {
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 8,
  },
  filterTab: {
    paddingHorizontal: 14,
    paddingVertical: 7,
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
  // ---- Center states ----
  centerState: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 12,
  },
  centerStateText: {
    fontSize: 14,
    color: "#999",
  },
  // ---- Error / Empty ----
  errorCard: {
    marginHorizontal: 16,
    marginTop: 12,
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    borderLeftWidth: 3,
    borderLeftColor: "#f44336",
  },
  errorText: {
    flex: 1,
    fontSize: 13,
    color: "#f44336",
    lineHeight: 18,
  },
  retryBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: "#f44336",
    borderRadius: 6,
  },
  retryBtnText: {
    color: "#fff",
    fontSize: 12,
    fontWeight: "500",
  },
  emptyCard: {
    marginHorizontal: 16,
    marginTop: 32,
    alignItems: "center",
    gap: 12,
  },
  emptyText: {
    fontSize: 14,
    color: "#bbb",
  },
  // ---- Section ----
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
    textTransform: "uppercase",
    letterSpacing: 0.5,
  },
  // ---- Overview cards ----
  overviewRow: {
    flexDirection: "row",
    gap: 12,
    marginBottom: 12,
  },
  overviewCard: {
    flex: 1,
    flexDirection: "row",
    borderRadius: 8,
    padding: 12,
    alignItems: "center",
  },
  overviewCardInbound: {
    backgroundColor: "#e8f5e9",
  },
  overviewCardOutbound: {
    backgroundColor: "#fff3e0",
  },
  overviewIconContainer: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: "#4CAF50",
    alignItems: "center",
    justifyContent: "center",
    marginRight: 10,
  },
  overviewIconOutbound: {
    backgroundColor: "#f57c00",
  },
  overviewIcon: {
    fontSize: 11,
    fontWeight: "bold",
    color: "#fff",
  },
  overviewIconOut: {
    fontSize: 9,
    fontWeight: "bold",
    color: "#fff",
  },
  overviewContent: {
    flex: 1,
  },
  overviewLabel: {
    fontSize: 11,
    color: "#666",
  },
  overviewValue: {
    fontSize: 15,
    fontWeight: "bold",
    color: "#333",
    marginTop: 2,
  },
  // ---- Summary row ----
  summaryRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
    marginTop: 4,
  },
  summaryItem: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    minWidth: "45%",
    flex: 1,
  },
  summaryLabel: {
    fontSize: 11,
    color: "#999",
    marginBottom: 2,
  },
  summaryValue: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  // ---- Line items ----
  lineItem: {
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#f0f0f0",
  },
  lineHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    marginBottom: 8,
  },
  lineNameBlock: {
    flex: 1,
    marginRight: 12,
  },
  lineName: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  lineCode: {
    fontSize: 11,
    color: "#aaa",
    marginTop: 1,
  },
  lineNet: {
    fontSize: 13,
    fontWeight: "600",
  },
  lineDetails: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  lineDetailCol: {
    alignItems: "center",
    flex: 1,
  },
  lineDetailLabel: {
    fontSize: 10,
    color: "#aaa",
    marginBottom: 2,
  },
  lineDetailValue: {
    fontSize: 12,
    color: "#333",
  },
  lineExtra: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
    marginTop: 6,
    paddingTop: 6,
    borderTopWidth: 1,
    borderTopColor: "#f5f5f5",
  },
  lineExtraText: {
    fontSize: 11,
    color: "#999",
  },
  // ---- Action buttons ----
  actionButtons: {
    flexDirection: "row",
    paddingHorizontal: 16,
    marginTop: 16,
    gap: 12,
  },
  actionBtnSecondary: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#ddd",
  },
  // ---- Colour utils ----
  balancePositive: {
    color: "#4CAF50",
  },
  balanceNegative: {
    color: "#f44336",
  },
  textSuccess: {
    color: "#4CAF50",
  },
  textOrange: {
    color: "#f57c00",
  },
  textDanger: {
    color: "#f44336",
  },
});

export default WHIOStatisticsScreen;
