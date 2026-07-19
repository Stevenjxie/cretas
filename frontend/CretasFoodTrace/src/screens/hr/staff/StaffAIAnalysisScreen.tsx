/**
 * 员工AI分析
 *
 * 功能:
 * - AI 智能分析员工表现
 * - 效率/质量/考勤评估
 * - 改进建议
 *
 * 对应原型: /docs/prd/prototype/hr-admin/staff-ai-analysis.html
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
} from 'react-native';
import { Text, Card, ActivityIndicator, Button, ProgressBar } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
import { useNavigation, useRoute, useFocusEffect, RouteProp } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import {
  employeeAIApiClient,
  type EmployeeAnalysisResponse,
} from '../../../services/api/employeeAIApiClient';
import { MarkdownRenderer } from '../../../components/common/MarkdownRenderer';
import { HR_THEME, type HRStackParamList } from '../../../types/hrNavigation';

type RouteParams = RouteProp<HRStackParamList, 'StaffAIAnalysis'>;

export default function StaffAIAnalysisScreen() {
  const navigation = useNavigation();
  const route = useRoute<RouteParams>();
  const { staffId } = route.params;
  const { t } = useTranslation('hr');

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<EmployeeAnalysisResponse | null>(null);

  const loadData = useCallback(async () => {
    try {
      const result = await employeeAIApiClient.analyzeEmployee(staffId, { days: 90 });
      setAnalysis(result);
    } catch (error) {
      console.error('加载AI分析失败:', error);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [staffId]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  const handleReanalyze = async () => {
    setAnalyzing(true);
    try {
      const result = await employeeAIApiClient.analyzeEmployee(staffId, { days: 90 });
      setAnalysis(result);
    } catch (error) {
      console.error('重新分析失败:', error);
    } finally {
      setAnalyzing(false);
    }
  };

  const getScoreColor = (score: number) => {
    if (score >= 80) return HR_THEME.success;
    if (score >= 60) return HR_THEME.warning;
    return HR_THEME.danger;
  };

  const renderScoreItem = (label: string, score: number | null, icon: MCIconName) => {
    if (score === null) {
      return (
        <View style={styles.scoreItem}>
          <View style={styles.scoreHeader}>
            <MaterialCommunityIcons name={icon} size={20} color={HR_THEME.primary} />
            <Text style={styles.scoreLabel}>{label}</Text>
            <Text style={[styles.scoreValue, { color: HR_THEME.textMuted }]}>不可计算</Text>
          </View>
        </View>
      );
    }

    return (
      <View style={styles.scoreItem}>
        <View style={styles.scoreHeader}>
          <MaterialCommunityIcons name={icon} size={20} color={HR_THEME.primary} />
          <Text style={styles.scoreLabel}>{label}</Text>
          <Text style={[styles.scoreValue, { color: getScoreColor(score) }]}>
            {score}
          </Text>
        </View>
        <ProgressBar
          progress={score / 100}
          color={getScoreColor(score)}
          style={styles.progressBar}
        />
      </View>
    );
  };

  const renderFact = (label: string, value: string | number) => (
    <View style={styles.factRow}>
      <Text style={styles.factLabel}>{label}</Text>
      <Text style={styles.factValue}>{value}</Text>
    </View>
  );

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={HR_THEME.primary} />
        <Text style={styles.loadingText}>{t('staff.ai.loading')}</Text>
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('staff.ai.title')}</Text>
        <TouchableOpacity
          testID="reanalyze-button"
          onPress={handleReanalyze}
          style={styles.refreshBtn}
          disabled={analyzing}
        >
          <MaterialCommunityIcons
            name="refresh"
            size={24}
            color={analyzing ? HR_THEME.textMuted : HR_THEME.primary}
          />
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {!analysis ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <MaterialCommunityIcons
                name="robot-outline"
                size={64}
                color={HR_THEME.textMuted}
              />
              <Text style={styles.emptyTitle}>{t('staff.ai.noData')}</Text>
              <Text style={styles.emptyText}>{t('staff.ai.startHint')}</Text>
              <Button
                mode="contained"
                onPress={handleReanalyze}
                loading={analyzing}
                disabled={analyzing}
                style={styles.analyzeButton}
                buttonColor={HR_THEME.primary}
                icon="robot"
              >
                {t('staff.ai.startAnalysis')}
              </Button>
            </Card.Content>
          </Card>
        ) : (
          <>
            {/* 综合评分：缺少评分规则时明确留空 */}
            <Card style={styles.overallCard}>
              <Card.Content style={styles.overallContent}>
                <View style={styles.overallScore}>
                  <Text style={styles.overallValue}>{analysis.overallScore ?? '—'}</Text>
                  <Text style={styles.overallLabel}>{t('staff.ai.overallScore')}</Text>
                  {analysis.overallScore === null ? (
                    <Text style={styles.notComputableLabel}>不可计算</Text>
                  ) : null}
                </View>
                <Text style={styles.analysisDate}>
                  {t('staff.ai.analysisDate')}: {analysis.analyzedAt}
                </Text>
              </Card.Content>
            </Card>

            {/* 仅展示后端真实存在的评分维度，不复制综合评分 */}
            <Card style={styles.sectionCard}>
              <Card.Content>
                <Text style={styles.sectionTitle}>{t('staff.ai.sections.ability')}</Text>
                {renderScoreItem('考勤评分', analysis.attendance.score, 'calendar-check')}
                {renderScoreItem('工时评分', analysis.workHours.score, 'speedometer')}
                {renderScoreItem('生产评分', analysis.production.score, 'factory')}
              </Card.Content>
            </Card>

            {/* 原始事实计数 */}
            <Card style={styles.sectionCard}>
              <Card.Content>
                <View style={styles.listHeader}>
                  <MaterialCommunityIcons name="database-check" size={20} color={HR_THEME.primary} />
                  <Text style={styles.sectionTitle}>事实数据</Text>
                </View>
                {renderFact('原始记录合计', analysis.dataPoints)}
                {renderFact('考勤记录', analysis.attendance.recordCount)}
                {renderFact('非缺勤状态记录', analysis.attendance.attendanceDays)}
                {renderFact('缺勤状态记录', analysis.attendance.absentDays)}
                {renderFact('工作会话', analysis.workHours.sessionCount)}
                {renderFact('实际工作分钟', analysis.workHours.totalMinutes)}
                {renderFact('参与批次', analysis.production.batchCount)}
                {renderFact('批次工作会话', analysis.production.batchWorkSessionCount)}
                {renderFact('已完成批次工作会话', analysis.production.completedBatchWorkSessionCount)}
                {renderFact('批次工作分钟', analysis.production.batchWorkMinutes)}
                {renderFact('质检记录', analysis.production.totalInspections)}
                {renderFact('质检通过记录', analysis.production.passedInspections)}
                {renderFact(
                  '质检通过率',
                  analysis.production.qualityRate === null
                    ? '不可计算'
                    : `${analysis.production.qualityRate}%`
                )}
              </Card.Content>
            </Card>

            {/* AI洞察直接来自后端，不由本地评分模板生成 */}
            <Card style={styles.sectionCard}>
              <Card.Content>
                <View style={styles.listHeader}>
                  <MaterialCommunityIcons name="robot" size={20} color={HR_THEME.info} />
                  <Text style={styles.sectionTitle}>AI事实洞察</Text>
                </View>
                <MarkdownRenderer content={analysis.aiInsight ?? '不可计算'} />
              </Card.Content>
            </Card>

            {analysis.suggestions.length > 0 ? (
              <Card style={styles.sectionCard}>
                <Card.Content>
                  <View style={styles.listHeader}>
                    <MaterialCommunityIcons name="lightbulb" size={20} color={HR_THEME.info} />
                    <Text style={styles.sectionTitle}>{t('staff.ai.sections.suggestions')}</Text>
                  </View>
                  {analysis.suggestions.map((suggestion, index) => (
                    <View key={`${suggestion.type}-${index}`} style={styles.listItem}>
                      <Text style={styles.listText}>
                        {suggestion.title}: {suggestion.description}
                      </Text>
                    </View>
                  ))}
                </Card.Content>
              </Card>
            ) : null}
          </>
        )}

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: HR_THEME.background,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: HR_THEME.background,
  },
  loadingText: {
    marginTop: 12,
    color: HR_THEME.textSecondary,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: HR_THEME.cardBackground,
    borderBottomWidth: 1,
    borderBottomColor: HR_THEME.border,
  },
  backBtn: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
  },
  refreshBtn: {
    padding: 4,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  emptyCard: {
    borderRadius: 12,
    backgroundColor: HR_THEME.cardBackground,
  },
  emptyContent: {
    alignItems: 'center',
    paddingVertical: 40,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
    marginTop: 16,
  },
  emptyText: {
    fontSize: 14,
    color: HR_THEME.textSecondary,
    marginTop: 8,
  },
  analyzeButton: {
    marginTop: 24,
    borderRadius: 8,
  },
  overallCard: {
    borderRadius: 12,
    marginBottom: 16,
    backgroundColor: HR_THEME.primary,
  },
  overallContent: {
    alignItems: 'center',
    paddingVertical: 24,
  },
  overallScore: {
    alignItems: 'center',
  },
  overallValue: {
    fontSize: 56,
    fontWeight: 'bold',
    color: '#fff',
  },
  overallLabel: {
    fontSize: 16,
    color: 'rgba(255,255,255,0.8)',
    marginTop: 4,
  },
  notComputableLabel: {
    color: 'rgba(255,255,255,0.9)',
    fontSize: 13,
    marginTop: 4,
  },
  analysisDate: {
    fontSize: 12,
    color: 'rgba(255,255,255,0.6)',
    marginTop: 16,
  },
  sectionCard: {
    borderRadius: 12,
    marginBottom: 16,
    backgroundColor: HR_THEME.cardBackground,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
    marginBottom: 16,
    marginLeft: 8,
  },
  scoreItem: {
    marginBottom: 16,
  },
  scoreHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  scoreLabel: {
    flex: 1,
    fontSize: 14,
    color: HR_THEME.textSecondary,
    marginLeft: 8,
  },
  scoreValue: {
    fontSize: 18,
    fontWeight: 'bold',
  },
  progressBar: {
    height: 6,
    borderRadius: 3,
    backgroundColor: HR_THEME.border,
  },
  factRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: HR_THEME.border,
  },
  factLabel: {
    color: HR_THEME.textSecondary,
    fontSize: 14,
  },
  factValue: {
    color: HR_THEME.textPrimary,
    fontSize: 14,
    fontWeight: '600',
  },
  listHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  listItem: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    marginBottom: 12,
    paddingLeft: 8,
  },
  listText: {
    flex: 1,
    fontSize: 14,
    color: HR_THEME.textPrimary,
    marginLeft: 8,
    lineHeight: 20,
  },
  bottomSpacer: {
    height: 40,
  },
});
