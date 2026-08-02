/**
 * 分析概览页面
 * Quality Inspector - Analysis Overview Screen
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  Dimensions,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import {
  QI_COLORS,
  QualityInspectorStackParamList,
  GRADE_COLORS,
} from '../../types/qualityInspector';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { useAuthStore } from '../../store/authStore';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;

const { width: SCREEN_WIDTH } = Dimensions.get('window');

/**
 * 页面模型。
 *
 * APP-CONTRACT-004 (2026-08-02): 这个页面此前声明的是一套后端从未产出过的结构
 * (overview/gradeDistribution/categoryScores/recentIssues), 形状校验必然失败,
 * 页面恒显示"数据格式异常，请稍后重试"。现在改成: 声明后端**真实**返回的形态,
 * 在 adaptDashboard() 里显式转换; 后端确实没有数据源的字段一律 null, 由渲染层
 * 显示"暂无数据"而不是编一个数字出来。
 *
 * 数据源说明 (后端 ProcessingServiceImpl#getQualityDashboard, 范围恒为**本月**):
 *   totalInspections / passRate / gradeDistribution / trends → 有
 *   avgScore(综合评分)、categoryScores(外观/气味/规格/重量/包装分项得分) → 质检表里
 *     压根没有对应字段, 后端也没有这两个键 → null, 页面不展示。
 */
interface AnalysisData {
  overview: {
    totalInspections: number;
    passRate: number;
    /** null = 后端无此数据源 */
    avgScore: number | null;
    trendDirection: 'up' | 'down' | 'stable';
  };
  gradeDistribution: {
    A: number;
    B: number;
    C: number;
    D: number;
  } | null;
  /** null = 后端无此数据源 (质检表无分项评分字段) */
  categoryScores: {
    appearance: number;
    smell: number;
    specification: number;
    weight: number;
    packaging: number;
  } | null;
}

interface QualityDashboardResponse {
  totalInspections?: number;
  passRate?: number | string;
  avgPassRate?: number | string;
  gradeDistribution?: { A?: number; B?: number; C?: number; D?: number };
  trends?: { date?: string; passRate?: number | string }[];
}

function num(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** 用趋势序列首尾的合格率判断方向; 不足两个有效点时算持平 */
function deriveTrendDirection(trends?: QualityDashboardResponse['trends']): 'up' | 'down' | 'stable' {
  const points = (trends ?? [])
    .map((t) => Number(t?.passRate))
    .filter((v) => Number.isFinite(v) && v > 0);
  const first = points[0];
  const last = points[points.length - 1];
  if (points.length < 2 || first === undefined || last === undefined) return 'stable';
  const delta = last - first;
  if (delta > 0.5) return 'up';
  if (delta < -0.5) return 'down';
  return 'stable';
}

function adaptDashboard(result: unknown): AnalysisData | null {
  if (!result || typeof result !== 'object') return null;
  const r = result as QualityDashboardResponse;
  // totalInspections 是后端恒输出的键 — 它缺失才说明响应真的不是这个端点的
  if (r.totalInspections === undefined) return null;
  const g = r.gradeDistribution;
  return {
    overview: {
      totalInspections: num(r.totalInspections),
      passRate: num(r.passRate ?? r.avgPassRate),
      avgScore: null,
      trendDirection: deriveTrendDirection(r.trends),
    },
    gradeDistribution: g
      ? { A: num(g.A), B: num(g.B), C: num(g.C), D: num(g.D) }
      : null,
    categoryScores: null,
  };
}

export default function QIAnalysisScreen() {
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;
  const { t } = useTranslation('quality');

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [data, setData] = useState<AnalysisData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (factoryId) {
      qualityInspectorApi.setFactoryId(factoryId);
      loadData();
    } else {
      setError(t('analysis.noFactory', '未设置工厂ID，无法加载数据'));
      setLoading(false);
    }
  }, [factoryId]);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await qualityInspectorApi.getAnalysisData();
      const adapted = adaptDashboard(result);
      if (adapted) {
        setData(adapted);
      } else {
        console.warn('API returned unexpected data format');
        setError(t('analysis.dataFormatError', '数据格式异常，请稍后重试'));
        setData(null);
      }
    } catch (err) {
      console.error('加载分析数据失败:', err);
      setError(t('analysis.loadError', '加载分析数据失败，请下拉刷新重试'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, []);

  const handleViewTrend = () => {
    navigation.navigate('QITrend');
  };

  const handleGenerateReport = () => {
    navigation.navigate('QIReport');
  };

  const renderGradeBar = (grade: 'A' | 'B' | 'C' | 'D', count: number, total: number) => {
    const percentage = total > 0 ? (count / total) * 100 : 0;
    return (
      <View key={grade} style={styles.gradeRow}>
        <View style={[styles.gradeLabel, { backgroundColor: GRADE_COLORS[grade] }]}>
          <Text style={styles.gradeLabelText}>{grade}</Text>
        </View>
        <View style={styles.gradeBarContainer}>
          <View
            style={[
              styles.gradeBar,
              { width: `${percentage}%`, backgroundColor: GRADE_COLORS[grade] },
            ]}
          />
        </View>
        <Text style={styles.gradeCount}>{count}批</Text>
        <Text style={styles.gradePercent}>{percentage.toFixed(1)}%</Text>
      </View>
    );
  };

  const renderCategoryScore = (
    category: string,
    icon: IoniconsName,
    score: number,
    maxScore: number = 20
  ) => {
    const percentage = (score / maxScore) * 100;
    return (
      <View style={styles.categoryItem}>
        <View style={styles.categoryHeader}>
          <Ionicons name={icon} size={18} color={QI_COLORS.primary} />
          <Text style={styles.categoryName}>{category}</Text>
          <Text style={styles.categoryScore}>
            {score.toFixed(1)}<Text style={styles.categoryMax}>/{maxScore}</Text>
          </Text>
        </View>
        <View style={styles.categoryBarBg}>
          <View style={[styles.categoryBar, { width: `${percentage}%` }]} />
        </View>
      </View>
    );
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={QI_COLORS.primary} />
        <Text style={styles.loadingText}>{t('analysis.loading')}</Text>
      </View>
    );
  }

  if (error && !data) {
    return (
      <ScrollView
        style={styles.container}
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20, alignItems: 'center', justifyContent: 'center', flex: 1 }]}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[QI_COLORS.primary]} />
        }
      >
        <Ionicons name="alert-circle-outline" size={48} color="#999" />
        <Text style={{ fontSize: 16, color: '#666', marginTop: 12, textAlign: 'center' }}>{error}</Text>
        <TouchableOpacity
          style={{ marginTop: 16, paddingHorizontal: 24, paddingVertical: 10, backgroundColor: QI_COLORS.primary, borderRadius: 8 }}
          onPress={loadData}
        >
          <Text style={{ color: '#fff', fontSize: 14 }}>{t('analysis.retry', '重试')}</Text>
        </TouchableOpacity>
      </ScrollView>
    );
  }

  const gradeDistribution = data?.gradeDistribution ?? null;
  const totalGrades = data?.gradeDistribution
    ? (data.gradeDistribution.A ?? 0) +
      (data.gradeDistribution.B ?? 0) +
      (data.gradeDistribution.C ?? 0) +
      (data.gradeDistribution.D ?? 0)
    : 0;

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20 }]}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          colors={[QI_COLORS.primary]}
        />
      }
    >
      {/* APP-CONTRACT-004: 后端 /reports/dashboard/quality 不接受时间参数, 恒返回本月。
          之前这里放了周/月/季三个可点的 tab, 点了没有任何效果 —— 换成如实标注范围。 */}
      <View style={styles.rangeBar}>
        <Text style={styles.rangeText}>{t('analysis.rangeThisMonth', '统计范围：本月')}</Text>
      </View>

      {/* 概览卡片 */}
      <View style={styles.overviewCard}>
        <View style={styles.overviewItem}>
          <Text style={styles.overviewValue}>{data?.overview?.totalInspections ?? 0}</Text>
          <Text style={styles.overviewLabel}>{t('analysis.inspectionBatches')}</Text>
        </View>
        <View style={styles.overviewDivider} />
        <View style={styles.overviewItem}>
          <View style={styles.overviewValueRow}>
            <Text style={styles.overviewValue}>{(data?.overview?.passRate ?? 0).toFixed(1)}%</Text>
            {data?.overview?.trendDirection === 'up' && (
              <Ionicons name="trending-up" size={20} color={QI_COLORS.success} />
            )}
            {data?.overview?.trendDirection === 'down' && (
              <Ionicons name="trending-down" size={20} color={QI_COLORS.danger} />
            )}
          </View>
          <Text style={styles.overviewLabel}>{t('analysis.passRate')}</Text>
        </View>
        <View style={styles.overviewDivider} />
        <View style={styles.overviewItem}>
          <Text style={styles.overviewValue}>
            {data?.overview?.avgScore != null ? data.overview.avgScore.toFixed(1) : '—'}
          </Text>
          <Text style={styles.overviewLabel}>{t('analysis.avgScore')}</Text>
        </View>
      </View>

      {/* 等级分布 */}
      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>{t('analysis.gradeDistribution')}</Text>
          <TouchableOpacity onPress={handleViewTrend}>
            <Text style={styles.sectionAction}>{t('analysis.viewTrend')}</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.gradeCard}>
          {gradeDistribution
            ? (['A', 'B', 'C', 'D'] as const).map((grade) =>
                renderGradeBar(grade, gradeDistribution[grade] ?? 0, totalGrades)
              )
            : <Text style={styles.emptyHint}>{t('analysis.noGradeData', '暂无等级分布数据')}</Text>}
        </View>
      </View>

      {/* 分类评分 — 质检表无分项评分字段, 后端不产出该键; 有数据源之前不渲染一排 0.0 */}
      {data?.categoryScores && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('analysis.categoryAvgScore')}</Text>
          <View style={styles.categoryCard}>
            {renderCategoryScore(t('analysis.appearance'), 'eye-outline', data.categoryScores.appearance)}
            {renderCategoryScore(t('analysis.smell'), 'flower-outline', data.categoryScores.smell)}
            {renderCategoryScore(t('analysis.specification'), 'resize-outline', data.categoryScores.specification)}
            {renderCategoryScore(t('analysis.weight'), 'scale-outline', data.categoryScores.weight)}
            {renderCategoryScore(t('analysis.packaging'), 'cube-outline', data.categoryScores.packaging)}
          </View>
        </View>
      )}

      {/* APP-CONTRACT-004: 原「常见问题」区块读 recentIssues, 后端从来没有这个键,
          质检表也没有问题分类字段 —— 渲染的永远是一张空卡。有真实数据源之前先摘掉。 */}

      {/* 生成报告按钮 */}
      <TouchableOpacity style={styles.reportBtn} onPress={handleGenerateReport}>
        <Ionicons name="document-text-outline" size={20} color="#fff" />
        <Text style={styles.reportBtnText}>{t('analysis.generateReport')}</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  content: {
    padding: 16,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.background,
  },
  loadingText: {
    marginTop: 12,
    color: QI_COLORS.textSecondary,
    fontSize: 14,
  },

  // 统计范围标注 (后端恒为本月)
  rangeBar: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    paddingVertical: 10,
    paddingHorizontal: 12,
    marginBottom: 16,
  },
  rangeText: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
  },
  emptyHint: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    textAlign: 'center',
    paddingVertical: 16,
  },

  // 概览卡片
  overviewCard: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderRadius: 16,
    padding: 20,
    marginBottom: 20,
  },
  overviewItem: {
    flex: 1,
    alignItems: 'center',
  },
  overviewDivider: {
    width: 1,
    backgroundColor: QI_COLORS.border,
  },
  overviewValueRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  overviewValue: {
    fontSize: 24,
    fontWeight: '700',
    color: QI_COLORS.text,
  },
  overviewLabel: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginTop: 4,
  },

  // 区块
  section: {
    marginBottom: 20,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  sectionAction: {
    fontSize: 14,
    color: QI_COLORS.primary,
  },

  // 等级分布
  gradeCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
  },
  gradeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  gradeLabel: {
    width: 28,
    height: 28,
    borderRadius: 6,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  gradeLabelText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  gradeBarContainer: {
    flex: 1,
    height: 8,
    backgroundColor: QI_COLORS.border,
    borderRadius: 4,
    marginRight: 12,
    overflow: 'hidden',
  },
  gradeBar: {
    height: '100%',
    borderRadius: 4,
  },
  gradeCount: {
    width: 40,
    fontSize: 13,
    color: QI_COLORS.text,
    textAlign: 'right',
  },
  gradePercent: {
    width: 50,
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    textAlign: 'right',
  },

  // 分类评分
  categoryCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
  },
  categoryItem: {
    marginBottom: 16,
  },
  categoryHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  categoryName: {
    flex: 1,
    fontSize: 14,
    color: QI_COLORS.text,
    marginLeft: 8,
  },
  categoryScore: {
    fontSize: 16,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  categoryMax: {
    fontSize: 12,
    fontWeight: '400',
    color: QI_COLORS.textSecondary,
  },
  categoryBarBg: {
    height: 6,
    backgroundColor: QI_COLORS.border,
    borderRadius: 3,
    overflow: 'hidden',
  },
  categoryBar: {
    height: '100%',
    backgroundColor: QI_COLORS.primary,
    borderRadius: 3,
  },

  // 常见问题
  issuesCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    overflow: 'hidden',
  },
  issueItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  issueLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  issueBadge: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: '#FFEBEE',
    justifyContent: 'center',
    alignItems: 'center',
  },
  issueBadgeText: {
    fontSize: 14,
    fontWeight: '600',
    color: QI_COLORS.danger,
  },
  issueCategory: {
    fontSize: 14,
    fontWeight: '500',
    color: QI_COLORS.text,
  },
  issueDesc: {
    fontSize: 12,
    color: QI_COLORS.textSecondary,
    marginTop: 2,
  },

  // 生成报告按钮
  reportBtn: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.secondary,
    borderRadius: 12,
    paddingVertical: 16,
    gap: 8,
    marginTop: 8,
  },
  reportBtnText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
