/**
 * 调度员个人中心屏幕
 *
 * 功能:
 * - 个人信息展示（头像、姓名、工号、角色、工龄）
 * - 今日工作统计（待审批、已处理、调动、计划）
 * - 绩效指标（计划完成率、OTD、人员调配效率、响应时间）
 * - 功能菜单导航
 * - 账号管理
 * - 系统设置
 * - 退出登录
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
  Platform,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { useNavigation } from '@react-navigation/native';
import { Ionicons, MaterialCommunityIcons, Feather } from '@expo/vector-icons';
import { useLanguageStore, LANGUAGE_NAMES, type SupportedLanguage } from '../../../store/languageStore';
import { useAuthStore } from '../../../store/authStore';
import { CommonActions } from '@react-navigation/native';

// 主题颜色
const DISPATCHER_THEME = {
  primary: '#722ed1',
  secondary: '#a18cd1',
  accent: '#fbc2eb',
  success: '#52c41a',
  warning: '#fa8c16',
  danger: '#ff4d4f',
  info: '#1890ff',
  background: '#f5f5f5',
  cardBackground: '#ffffff',
};

// 用户信息类型
interface UserProfile {
  name: string;
  code: string;
  role: string;
  department: string;
  hireDate: string;
  tenure: string;
  avatar: string;
}

// 统计数据类型
interface TodayStats {
  pendingApprovals: number;
  processed: number;
  transfers: number;
  plans: number;
}

// 绩效指标类型
interface PerformanceMetric {
  label: string;
  value: string;
  percentage?: number;
  change: string;
  changeType: 'up' | 'down';
  gradientColors: string[];
  note?: string;
}

// Icon name type aliases
type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// 菜单项类型
interface MenuItem {
  id: string;
  title: string;
  icon: IoniconsName;
  iconType: 'ionicons' | 'material' | 'feather';
  badge?: string;
  badgeColor?: string;
  screen?: string;
}

// Defaults removed — all data loaded from API in component

const functionMenuItems: MenuItem[] = [
  {
    id: 'approval',
    title: '我的审批记录',
    icon: 'checkmark-done-outline',
    iconType: 'ionicons',
    screen: 'ApprovalList',
  },
  {
    id: 'statistics',
    title: '调度统计',
    icon: 'stats-chart-outline',
    iconType: 'ionicons',
    screen: 'Statistics',
  },
  {
    id: 'personnel',
    title: '人员管理',
    icon: 'people-outline',
    iconType: 'ionicons',
    screen: 'PersonnelList',
  },
  {
    id: 'schedule',
    title: '排班日历',
    icon: 'calendar-outline',
    iconType: 'ionicons',
    screen: 'PersonnelSchedule',
  },
  {
    id: 'attendance',
    title: '考勤打卡',
    icon: 'time-outline',
    iconType: 'ionicons',
    screen: 'Attendance',
  },
];

const accountMenuItems: MenuItem[] = [
  {
    id: 'skills',
    title: '技能认证管理',
    icon: 'star-outline',
    iconType: 'ionicons',
  },
  {
    id: 'employeeCode',
    title: '工号绑定设置',
    icon: 'card-outline',
    iconType: 'ionicons',
  },
];

const systemMenuItems: MenuItem[] = [
  {
    id: 'language',
    title: '语言切换',
    icon: 'language-outline',
    iconType: 'ionicons',
  },
  {
    id: 'schedulingSettings',
    title: '排产设置',
    icon: 'settings-outline',
    iconType: 'ionicons',
    screen: 'SchedulingSettings',
  },
  {
    id: 'notifications',
    title: '消息通知',
    icon: 'notifications-outline',
    iconType: 'ionicons',
  },
  {
    id: 'password',
    title: '修改密码',
    icon: 'lock-closed-outline',
    iconType: 'ionicons',
  },
  {
    id: 'about',
    title: '关于系统',
    icon: 'information-circle-outline',
    iconType: 'ionicons',
  },
];

export default function DSProfileScreen() {
  const navigation = useNavigation();
  const [refreshing, setRefreshing] = useState(false);
  const { user } = useAuthStore();
  const [todayStats, setTodayStats] = useState<TodayStats>({ pendingApprovals: 0, processed: 0, transfers: 0, plans: 0 });
  const [metrics, setMetrics] = useState<PerformanceMetric[]>([]);
  const { language, setLanguage } = useLanguageStore();
  const logout = useAuthStore((state) => state.logout);

  // Real profile from auth store
  const profile: UserProfile = {
    name: user?.fullName || user?.username || '调度员',
    code: (user as any)?.employeeCode || '-',
    role: '调度员',
    department: (user as any)?.department || '生产调度部',
    hireDate: (user as any)?.hireDate || '-',
    tenure: '',
    avatar: (user?.fullName || '调').charAt(0),
  };

  const toggleLanguage = () => {
    const newLang: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(newLang);
  };

  // Load real stats from production-plans
  const loadStats = useCallback(async () => {
    try {
      const { productionPlanApiClient } = await import('../../../services/api/productionPlanApiClient');
      const res = await productionPlanApiClient.getProductionPlans({ page: 1, size: 200 } as any);
      const dataObj = (res?.data || res) as any;
      const plans = dataObj?.content || (Array.isArray(dataObj) ? dataObj : []);
      if (!Array.isArray(plans)) return;

      let pending = 0, inProgress = 0, completed = 0, cancelled = 0;
      for (const p of plans) {
        const s = (p.status || '').toUpperCase();
        if (s === 'PENDING' || s === 'PLANNED') pending++;
        else if (s === 'IN_PROGRESS') inProgress++;
        else if (s === 'COMPLETED') completed++;
        else if (s === 'CANCELLED') cancelled++;
      }
      const total = plans.length;
      const completionRate = total > 0 ? Math.round((completed / total) * 100) : 0;
      const activeRate = total > 0 ? Math.round(((completed + inProgress) / total) * 100) : 0;

      setTodayStats({
        pendingApprovals: pending,
        processed: completed,
        transfers: inProgress,
        plans: total,
      });

      setMetrics([
        {
          label: '计划完成率',
          value: `${completionRate}%`,
          percentage: completionRate,
          change: `${completed}/${total}`,
          changeType: 'up',
          gradientColors: ['#52c41a', '#95de64'],
        },
        {
          label: '执行中占比',
          value: `${activeRate}%`,
          percentage: activeRate,
          change: `${inProgress} 进行中`,
          changeType: 'up',
          gradientColors: ['#1890ff', '#69c0ff'],
        },
        {
          label: '待执行计划',
          value: `${pending}`,
          percentage: total > 0 ? Math.round((pending / total) * 100) : 0,
          change: `共 ${total} 个计划`,
          changeType: 'up',
          gradientColors: ['#a18cd1', '#fbc2eb'],
        },
        {
          label: '取消率',
          value: `${total > 0 ? Math.round((cancelled / total) * 100) : 0}%`,
          percentage: total > 0 ? Math.round((cancelled / total) * 100) : 0,
          change: `${cancelled} 已取消`,
          changeType: cancelled > 5 ? 'up' : 'down',
          gradientColors: ['#722ed1', '#a18cd1'],
        },
      ]);
    } catch { /* silent */ }
  }, []);

  useEffect(() => { loadStats(); }, [loadStats]);

  // 下拉刷新
  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadStats();
    setRefreshing(false);
  }, [loadStats]);

  // 退出登录 (Rule 2: 弹窗带账号上下文)
  const handleLogout = () => {
    const accountLabel = profile.name !== '调度员'
      ? `${profile.name}（调度员）`
      : `@${user?.username ?? ''}（调度员）`;
    const confirmMessage = `确定退出 ${accountLabel} 吗？`;

    const doLogout = () => {
      logout();
      navigation.dispatch(
        CommonActions.reset({
          index: 0,
          routes: [{ name: 'Auth' }],
        })
      );
    };

    if (Platform.OS === 'web') {
      if (window.confirm(confirmMessage)) {
        doLogout();
      }
      return;
    }
    Alert.alert(
      '退出登录',
      confirmMessage,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确定',
          style: 'destructive',
          onPress: doLogout,
        },
      ]
    );
  };

  // 菜单项点击 - 处理跨 Tab 导航
  const handleMenuPress = (item: MenuItem) => {
    if (item.id === 'language') {
      toggleLanguage();
      return;
    }
    if (item.screen) {
      // 跨 Tab 导航映射
      const crossTabNavigation: Record<string, { tab: string; screen: string }> = {
        ApprovalList: { tab: 'PlanTab', screen: 'ApprovalList' },
        Statistics: { tab: 'ProfileTab', screen: 'DSStatistics' },
        PersonnelList: { tab: 'PersonnelTab', screen: 'PersonnelList' },
        PersonnelSchedule: { tab: 'PersonnelTab', screen: 'PersonnelSchedule' },
        Attendance: { tab: 'HomeTab', screen: 'Attendance' },
      };

      // 当前 Stack 内的页面，直接导航
      const localScreens = ['SchedulingSettings', 'DSStatistics'];
      if (localScreens.includes(item.screen)) {
        (navigation as { navigate: (screen: string) => void }).navigate(item.screen);
        return;
      }

      const navConfig = crossTabNavigation[item.screen];
      if (navConfig) {
        (navigation as { navigate: (tab: string, params: { screen: string }) => void })
          .navigate(navConfig.tab, { screen: navConfig.screen });
      } else {
        (navigation as { navigate: (screen: string) => void }).navigate(item.screen);
      }
    } else {
      Alert.alert(item.title, '功能开发中...');
    }
  };

  // 渲染统计项
  const renderStatItem = (
    value: number,
    label: string,
    color: string
  ) => (
    <View style={styles.statItem}>
      <Text style={[styles.statValue, { color }]}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );

  // 渲染绩效指标
  const renderMetric = (metric: PerformanceMetric, index: number) => (
    <View key={index} style={styles.metricItem}>
      <View style={styles.metricHeader}>
        <Text style={styles.metricLabel}>{metric.label}</Text>
        <View style={styles.metricValueContainer}>
          <Text style={styles.metricValue}>{metric.value}</Text>
          <Text style={[
            styles.metricChange,
            { color: metric.changeType === 'up' ? DISPATCHER_THEME.success : DISPATCHER_THEME.danger }
          ]}>
            {metric.change}
          </Text>
        </View>
      </View>
      {metric.percentage !== undefined && (
        <View style={styles.progressBar}>
          <LinearGradient
            colors={metric.gradientColors as [string, string]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 0 }}
            style={[styles.progressFill, { width: `${metric.percentage}%` }]}
          />
        </View>
      )}
      {metric.note && (
        <Text style={styles.metricNote}>{metric.note}</Text>
      )}
    </View>
  );

  // 渲染菜单项
  const renderMenuItem = (item: MenuItem) => (
    <TouchableOpacity
      key={item.id}
      style={styles.menuItem}
      onPress={() => handleMenuPress(item)}
      activeOpacity={0.7}
    >
      <View style={styles.menuItemLeft}>
        <Ionicons
          name={item.icon}
          size={22}
          color={DISPATCHER_THEME.primary}
        />
        <Text style={styles.menuItemText}>{item.title}</Text>
      </View>
      <View style={styles.menuItemRight}>
        {item.badge && (
          <Text style={[styles.menuBadge, { color: item.badgeColor }]}>
            {item.badge}
          </Text>
        )}
        {item.id === 'language' && (
          <Text style={styles.languageText}>{LANGUAGE_NAMES[language]}</Text>
        )}
        {item.id !== 'language' && <Ionicons name="chevron-forward" size={18} color="#ccc" />}
      </View>
    </TouchableOpacity>
  );

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[DISPATCHER_THEME.primary]}
            tintColor={DISPATCHER_THEME.primary}
          />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 个人信息头部 */}
        <LinearGradient
          colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.headerGradient}
        >
          <View style={styles.profileHeader}>
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>{profile.avatar}</Text>
            </View>
            <View style={styles.nameRow}>
              <Text style={styles.profileName}>{profile.name}</Text>
              <View style={styles.codeBadge}>
                <Text style={styles.codeText}>({profile.code})</Text>
              </View>
            </View>
            <Text style={styles.profileRole}>
              {profile.role} · {profile.department}
            </Text>
            <Text style={styles.profileTenure}>
              入职: {profile.hireDate} · 工龄: {profile.tenure}
            </Text>
          </View>
        </LinearGradient>

        <View style={styles.content}>
          {/* 今日工作统计 */}
          <View style={styles.statsContainer}>
            {renderStatItem(todayStats.pendingApprovals, '待审批', DISPATCHER_THEME.warning)}
            {renderStatItem(todayStats.processed, '已处理', DISPATCHER_THEME.success)}
            {renderStatItem(todayStats.transfers, '调动', DISPATCHER_THEME.primary)}
            {renderStatItem(todayStats.plans, '计划', DISPATCHER_THEME.info)}
          </View>

          {/* 绩效指标 */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>本月绩效</Text>
            <TouchableOpacity>
              <Text style={styles.sectionLink}>查看详情 &gt;</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.metricsCard}>
            {metrics.map((metric, index) => renderMetric(metric, index))}
          </View>

          {/* 功能菜单 */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>功能菜单</Text>
          </View>
          <View style={styles.menuCard}>
            {functionMenuItems.map(renderMenuItem)}
          </View>

          {/* 账号管理 */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>账号管理</Text>
          </View>
          <View style={styles.menuCard}>
            {accountMenuItems.map(renderMenuItem)}
          </View>

          {/* 系统设置 */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>系统设置</Text>
          </View>
          <View style={styles.menuCard}>
            {systemMenuItems.map(renderMenuItem)}
          </View>

          {/* 退出登录按钮 */}
          <TouchableOpacity
            style={styles.logoutButton}
            onPress={handleLogout}
            activeOpacity={0.8}
          >
            <Text style={styles.logoutButtonText}>退出登录</Text>
          </TouchableOpacity>

          {/* 版本信息 */}
          <Text style={styles.versionText}>白垩纪AI Agent v1.0.0</Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: DISPATCHER_THEME.background,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: 100,
  },
  headerGradient: {
    paddingTop: 20,
    paddingBottom: 40,
  },
  profileHeader: {
    alignItems: 'center',
    paddingHorizontal: 16,
  },
  avatar: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: 'rgba(255, 255, 255, 0.3)',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 12,
    borderWidth: 3,
    borderColor: 'rgba(255, 255, 255, 0.5)',
  },
  avatarText: {
    fontSize: 28,
    fontWeight: '600',
    color: '#fff',
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 4,
  },
  profileName: {
    fontSize: 22,
    fontWeight: '700',
    color: '#fff',
  },
  codeBadge: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  codeText: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.9)',
  },
  profileRole: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.9)',
    marginBottom: 4,
  },
  profileTenure: {
    fontSize: 12,
    color: 'rgba(255, 255, 255, 0.7)',
  },
  content: {
    marginTop: -24,
    paddingHorizontal: 16,
  },
  statsContainer: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    borderRadius: 12,
    paddingVertical: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: '700',
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 20,
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  sectionLink: {
    fontSize: 12,
    color: DISPATCHER_THEME.secondary,
  },
  metricsCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.08,
    shadowRadius: 4,
    elevation: 2,
  },
  metricItem: {
    marginBottom: 16,
  },
  metricHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  metricLabel: {
    fontSize: 13,
    color: '#666',
  },
  metricValueContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  metricValue: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  metricChange: {
    fontSize: 11,
  },
  progressBar: {
    height: 6,
    backgroundColor: '#f0f0f0',
    borderRadius: 3,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 3,
  },
  metricNote: {
    fontSize: 11,
    color: '#999',
    marginTop: 4,
  },
  menuCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.08,
    shadowRadius: 4,
    elevation: 2,
  },
  menuItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  menuItemLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  menuItemText: {
    fontSize: 15,
    color: '#333',
  },
  menuItemRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  menuBadge: {
    fontSize: 11,
  },
  languageText: {
    fontSize: 13,
    color: DISPATCHER_THEME.secondary,
    marginRight: 4,
  },
  logoutButton: {
    backgroundColor: '#fff',
    borderRadius: 12,
    paddingVertical: 14,
    marginTop: 24,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: DISPATCHER_THEME.danger,
  },
  logoutButtonText: {
    fontSize: 16,
    fontWeight: '500',
    color: DISPATCHER_THEME.danger,
  },
  versionText: {
    textAlign: 'center',
    fontSize: 12,
    color: '#999',
    marginTop: 16,
    marginBottom: 20,
  },
});
