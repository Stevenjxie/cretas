import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { RouteProp, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ActivityIndicator, Button } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import { LabelQcTaskDetail, LabelQcTaskStatus } from '../../types/labelQc';
import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QILabelQcSubmitted'>;

const STATUS_COPY: Record<LabelQcTaskStatus, { title: string; body: string; color: string }> = {
  DRAFT: { title: '草稿待上传', body: '照片尚未全部提交，请返回后重试。', color: QI_COLORS.warning },
  UPLOADING: { title: '照片上传中', body: '请保持网络连接，上传完成后会自动进入初筛。', color: QI_COLORS.secondary },
  QUEUED: { title: '等待 AI 初筛', body: '任务已安全保存，稍后会自动处理。', color: QI_COLORS.secondary },
  ANALYZING: { title: 'AI 正在初筛', body: '正在查找疑似缺白标或缺彩标区域。', color: QI_COLORS.secondary },
  NEEDS_REVIEW: { title: '等待人工审核', body: 'AI 初筛已完成，最终结果由质量审核员确认。', color: QI_COLORS.warning },
  REVIEWED: { title: '人工审核已完成', body: '审核结果已经保存，可在手机端查看最终标注。', color: QI_COLORS.success },
  ANALYSIS_FAILED: { title: 'AI 初筛失败', body: '照片仍已保存，人工审核员可以直接检查或重新分析。', color: QI_COLORS.danger },
};

export default function QILabelQcSubmittedScreen() {
  const route = useRoute<RouteProps>();
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const factoryId = useAuthStore((state) => state.user?.factoryId);
  const [detail, setDetail] = useState<LabelQcTaskDetail | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  const load = async () => {
    if (!factoryId) return;
    try {
      setRefreshError(null);
      setDetail(await labelQcApi.getTask(route.params.taskId, factoryId));
    } catch {
      setRefreshError('状态刷新失败，任务已提交，可稍后在 Web 端查看。');
    }
  };

  useEffect(() => {
    const initialTimer = setTimeout(() => {
      void load();
    }, 0);
    const timer = setInterval(() => {
      if (!detail || ['QUEUED', 'ANALYZING'].includes(detail.task.status)) {
        void load();
      }
    }, 5000);
    return () => {
      clearTimeout(initialTimer);
      clearInterval(timer);
    };
  }, [factoryId, route.params.taskId, detail?.task.status]);

  const status = detail?.task.status ?? 'QUEUED';
  const copy = STATUS_COPY[status];
  const context = useMemo(
    () => [
      ['SKU', `${route.params.skuCode} · ${route.params.skuName}`],
      ['批次号', route.params.batchNumber],
      ['生产日期', route.params.productionDate],
      ['任务编号', route.params.taskId],
    ],
    [route.params],
  );

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      testID="qi-label-qc-submitted-screen"
    >
      <View style={[styles.statusIcon, { backgroundColor: `${copy.color}1A` }]}>
        {['QUEUED', 'ANALYZING'].includes(status) ? (
          <ActivityIndicator size={44} color={copy.color} />
        ) : (
          <Ionicons
            name={status === 'REVIEWED' ? 'checkmark-circle' : 'person-circle-outline'}
            size={54}
            color={copy.color}
          />
        )}
      </View>
      <Text style={styles.title}>{copy.title}</Text>
      <Text style={styles.body}>{copy.body}</Text>

      <View style={styles.guardrail}>
        <Ionicons name="information-circle-outline" size={22} color="#174A7E" />
        <Text style={styles.guardrailText}>
          AI 未发现异常也不代表自动合格；所有照片都需要人工最终确认。
        </Text>
      </View>

      <View style={styles.contextCard}>
        {context.map(([label, value]) => (
          <View key={label} style={styles.contextRow}>
            <Text style={styles.contextLabel}>{label}</Text>
            <Text style={styles.contextValue} selectable={label === '任务编号'}>
              {value}
            </Text>
          </View>
        ))}
        {detail && (
          <>
            <View style={styles.divider} />
            <View style={styles.contextRow}>
              <Text style={styles.contextLabel}>照片</Text>
              <Text style={styles.contextValue}>{detail.task.photoCount} 张</Text>
            </View>
            <View style={styles.contextRow}>
              <Text style={styles.contextLabel}>AI 疑似区域</Text>
              <Text style={[styles.contextValue, detail.task.aiCandidateCount > 0 && styles.warningValue]}>
                {detail.task.aiCandidateCount} 处（待人工确认）
              </Text>
            </View>
          </>
        )}
      </View>

      {refreshError && (
        <View style={styles.errorCard}>
          <Text style={styles.errorText}>{refreshError}</Text>
          <Button
            mode="text"
            onPress={load}
            testID="qi-label-qc-submitted-refresh-button"
          >
            重新刷新
          </Button>
        </View>
      )}

      {['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(status) && (
        <Button
          mode="contained"
          buttonColor={QI_COLORS.primary}
          contentStyle={styles.buttonContent}
          labelStyle={styles.buttonLabel}
          icon={status === 'REVIEWED' ? 'eye-outline' : 'gesture-tap'}
          onPress={() =>
            navigation.navigate('QILabelQcReview', {
              taskId: route.params.taskId,
            })
          }
          testID="qi-label-qc-submitted-review-button"
        >
          {status === 'REVIEWED' ? '查看人工审核结果' : '开始逐张人工审核'}
        </Button>
      )}
      <Button
        mode={['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(status) ? 'outlined' : 'contained'}
        buttonColor={['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(status) ? undefined : QI_COLORS.primary}
        textColor={['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(status) ? QI_COLORS.primary : undefined}
        contentStyle={styles.buttonContent}
        labelStyle={styles.buttonLabel}
        style={['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(status) ? styles.secondaryButton : undefined}
        onPress={() => navigation.replace('QILabelQcCreate')}
        testID="qi-label-qc-submitted-new-task-button"
      >
        再拍一批
      </Button>
      <Button
        mode="outlined"
        textColor={QI_COLORS.primary}
        contentStyle={styles.buttonContent}
        style={styles.secondaryButton}
        onPress={() => navigation.navigate('QIHomeTab')}
        testID="qi-label-qc-submitted-home-button"
      >
        返回质检首页
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  content: {
    width: '100%',
    maxWidth: 620,
    alignSelf: 'center',
    padding: 20,
    alignItems: 'stretch',
  },
  statusIcon: {
    width: 86,
    height: 86,
    borderRadius: 43,
    alignSelf: 'center',
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 20,
  },
  title: { marginTop: 18, fontSize: 24, fontWeight: '800', color: QI_COLORS.text, textAlign: 'center' },
  body: { marginTop: 8, fontSize: 15, lineHeight: 22, color: QI_COLORS.textSecondary, textAlign: 'center' },
  guardrail: {
    flexDirection: 'row',
    backgroundColor: '#EAF3FF',
    borderRadius: 12,
    padding: 14,
    marginTop: 24,
  },
  guardrailText: { flex: 1, marginLeft: 10, fontSize: 13, lineHeight: 20, color: '#365D80' },
  contextCard: { backgroundColor: QI_COLORS.card, borderRadius: 14, padding: 16, marginVertical: 18 },
  contextRow: { flexDirection: 'row', alignItems: 'flex-start', paddingVertical: 8 },
  contextLabel: { width: 78, fontSize: 13, color: QI_COLORS.textSecondary },
  contextValue: { flex: 1, fontSize: 14, fontWeight: '600', color: QI_COLORS.text, textAlign: 'right' },
  warningValue: { color: '#A86100' },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: QI_COLORS.border, marginVertical: 4 },
  errorCard: { backgroundColor: '#FFF0F0', borderRadius: 12, padding: 12, marginBottom: 16 },
  errorText: { fontSize: 13, color: '#7D3030', lineHeight: 19 },
  buttonContent: { minHeight: 50 },
  buttonLabel: { fontSize: 16, fontWeight: '700' },
  secondaryButton: { marginTop: 12, borderColor: QI_COLORS.primary },
});
