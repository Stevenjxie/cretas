import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  PressableStateCallbackType,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Icon, useTheme } from 'react-native-paper';
import { useWorkflowStats } from '../../hooks/useWorkflowStats';
import type { AppTheme } from '../../theme';
import type {
  WorkflowAIEntryContext,
  WorkflowModule,
  WorkflowNode,
} from '../../types/workflow';

interface ModuleCopy {
  title: string;
  subtitle: string;
  actionLabel: string;
  icon: string;
  color: string;
}

const MODULE_COPY: Record<WorkflowModule, ModuleCopy> = {
  sales: {
    title: '销售待审',
    subtitle: '订单审核和交付跟进',
    actionLabel: '处理销售',
    icon: 'cart-outline',
    color: '#F59E0B',
  },
  purchase: {
    title: '采购待审',
    subtitle: '采购审核和到货跟进',
    actionLabel: '处理采购',
    icon: 'clipboard-text-outline',
    color: '#10B981',
  },
  production: {
    title: '待生产任务',
    subtitle: '生产计划和执行进度',
    actionLabel: '安排生产',
    icon: 'factory',
    color: '#3B82F6',
  },
  inventory: {
    title: '库存需关注',
    subtitle: '异常批次和库存状态',
    actionLabel: '查看库存',
    icon: 'package-variant-closed',
    color: '#EF4444',
  },
  finance: {
    title: '财务待处理',
    subtitle: '开票收款和审核事项',
    actionLabel: '处理财务',
    icon: 'cash-multiple',
    color: '#8B5CF6',
  },
};

const ACTION_BUCKET_BY_MODULE: Record<WorkflowModule, string> = {
  sales: 'pending',
  purchase: 'pending',
  production: 'pending',
  inventory: 'pending',
  finance: 'pending',
};

export interface WorkflowWorkdeskProps {
  modules: WorkflowModule[];
  factoryId?: string;
  onNodePress?: (module: WorkflowModule, nodeId: string) => void;
  onAITrigger?: (ctx: WorkflowAIEntryContext) => void;
}

function formatCount(count: number): string {
  if (count <= 9999) return String(count);
  return '9999+';
}

function getNodeCount(nodes: WorkflowNode[], nodeId: string): number {
  return nodes.find(node => node.id === nodeId)?.count ?? 0;
}

function getActionNode(nodes: WorkflowNode[], module: WorkflowModule): WorkflowNode | undefined {
  const preferredBucket = ACTION_BUCKET_BY_MODULE[module];
  return nodes.find(node => node.id === preferredBucket) ?? nodes.find(node => node.count > 0);
}

export function WorkflowWorkdesk({
  modules,
  factoryId,
  onNodePress,
  onAITrigger,
}: WorkflowWorkdeskProps) {
  const theme = useTheme<AppTheme>();

  return (
    <View
      style={[
        styles.card,
        {
          backgroundColor: theme.colors.surface,
          borderColor: theme.colors.border,
          ...theme.custom.shadows.small,
        },
      ]}
      accessibilityLabel="今日待办工作台"
    >
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text style={[styles.title, { color: theme.colors.text }]}>今日待办</Text>
          <Text style={[styles.subtitle, { color: theme.colors.textSecondary }]}>
            优先处理会卡住流程的事项
          </Text>
        </View>
        {onAITrigger ? (
          <Pressable
            onPress={() => onAITrigger({ module: 'factory_home', factoryId })}
            style={({ pressed }: PressableStateCallbackType) => [
              styles.aiButton,
              {
                backgroundColor: theme.colors.primaryContainer,
                borderColor: theme.colors.primary,
              },
              pressed && styles.pressed,
            ]}
            accessibilityRole="button"
            accessibilityLabel="让 AI 排今日待办优先级"
          >
            <Icon source="message-text-outline" size={16} color={theme.colors.onPrimaryContainer} />
            <Text style={[styles.aiButtonText, { color: theme.colors.onPrimaryContainer }]}>
              AI 排优先级
            </Text>
          </Pressable>
        ) : null}
      </View>

      <View style={styles.rows}>
        {modules.map((module) => (
          <WorkflowWorkdeskRow
            key={module}
            module={module}
            factoryId={factoryId}
            onNodePress={onNodePress}
          />
        ))}
      </View>
    </View>
  );
}

interface WorkflowWorkdeskRowProps {
  module: WorkflowModule;
  factoryId?: string;
  onNodePress?: (module: WorkflowModule, nodeId: string) => void;
}

function WorkflowWorkdeskRow({
  module,
  factoryId,
  onNodePress,
}: WorkflowWorkdeskRowProps) {
  const theme = useTheme<AppTheme>();
  const { stats, loading } = useWorkflowStats(module, factoryId);
  const copy = MODULE_COPY[module];
  const nodes = stats?.nodes ?? [];
  const actionNode = getActionNode(nodes, module);
  const actionNodeId = actionNode?.id;
  const actionCount = actionNode?.count ?? 0;
  const inProgressCount = getNodeCount(nodes, 'in_progress');
  const doneCount = getNodeCount(nodes, 'done');
  const canNavigate = Boolean(actionNodeId && onNodePress);

  return (
    <Pressable
      onPress={canNavigate && actionNodeId ? () => onNodePress?.(module, actionNodeId) : undefined}
      style={({ pressed }: PressableStateCallbackType) => [
        styles.row,
        pressed && styles.pressed,
      ]}
      accessibilityRole={canNavigate ? 'button' : undefined}
      accessibilityLabel={`${copy.title}, ${actionCount} 项`}
    >
      <View style={[styles.iconWrap, { backgroundColor: `${copy.color}18` }]}>
        <Icon source={copy.icon} size={22} color={copy.color} />
      </View>

      <View style={styles.rowMain}>
        <View style={styles.rowTitleLine}>
          <Text style={[styles.rowTitle, { color: theme.colors.text }]} numberOfLines={1}>
            {copy.title}
          </Text>
          {loading ? (
            <ActivityIndicator size="small" color={theme.colors.primary} />
          ) : (
            <Text style={[styles.rowCount, { color: copy.color }]}>
              {formatCount(actionCount)}
            </Text>
          )}
        </View>
        <Text style={[styles.rowSubtitle, { color: theme.colors.textSecondary }]} numberOfLines={1}>
          {actionCount > 0 ? copy.subtitle : '暂无待处理事项'}
        </Text>
        <View style={styles.metaLine}>
          <Text style={[styles.metaText, { color: theme.colors.textSecondary }]}>
            进行中 {formatCount(inProgressCount)}
          </Text>
          <View style={[styles.dot, { backgroundColor: theme.colors.border }]} />
          <Text style={[styles.metaText, { color: theme.colors.textSecondary }]}>
            已完成 {formatCount(doneCount)}
          </Text>
        </View>
      </View>

      <View style={styles.actionArea}>
        <Text
          style={[
            styles.actionText,
            { color: actionCount > 0 ? theme.colors.primary : theme.colors.textSecondary },
          ]}
          numberOfLines={1}
        >
          {actionCount > 0 ? copy.actionLabel : '查看'}
        </Text>
        <Icon
          source="chevron-right"
          size={20}
          color={actionCount > 0 ? theme.colors.primary : theme.colors.textSecondary}
        />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    marginBottom: 8,
  },
  headerText: {
    flex: 1,
  },
  title: {
    fontSize: 17,
    fontWeight: '700',
  },
  subtitle: {
    fontSize: 12,
    marginTop: 3,
  },
  aiButton: {
    minHeight: 34,
    borderRadius: 17,
    borderWidth: 1,
    paddingHorizontal: 11,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
  },
  aiButtonText: {
    fontSize: 12,
    fontWeight: '700',
  },
  rows: {
    marginTop: 2,
  },
  row: {
    minHeight: 76,
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#E5E7EB',
  },
  pressed: {
    opacity: 0.72,
  },
  iconWrap: {
    width: 42,
    height: 42,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 11,
  },
  rowMain: {
    flex: 1,
    minWidth: 0,
  },
  rowTitleLine: {
    minHeight: 24,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  rowTitle: {
    flex: 1,
    fontSize: 15,
    fontWeight: '700',
  },
  rowCount: {
    fontSize: 20,
    fontWeight: '800',
  },
  rowSubtitle: {
    fontSize: 12,
    marginTop: 2,
  },
  metaLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 6,
  },
  metaText: {
    fontSize: 11,
  },
  dot: {
    width: 3,
    height: 3,
    borderRadius: 2,
  },
  actionArea: {
    minWidth: 78,
    marginLeft: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  actionText: {
    fontSize: 12,
    fontWeight: '700',
  },
});
