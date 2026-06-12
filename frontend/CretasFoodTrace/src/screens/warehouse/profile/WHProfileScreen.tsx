/**
 * 个人中心页面
 * 对应原型: warehouse/profile.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
  Platform,
} from "react-native";
import {
  Text,
  Surface,
  Avatar,
  Button,
  Divider,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, CommonActions } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHProfileStackParamList } from "../../../types/navigation";
import { useAuthStore } from "../../../store/authStore";
import { useLanguageStore, LANGUAGE_NAMES, type SupportedLanguage } from "../../../store/languageStore";
import { dashboardApiClient } from "../../../services/api/dashboardApiClient";
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHProfileStackParamList>;

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

interface MenuItem {
  key: string;
  label: string;
  icon: MCIconName;
  screen?: keyof WHProfileStackParamList;
  badge?: number;
  description?: string;
  onPress?: () => void;
}

interface MenuSection {
  title: string;
  items: MenuItem[];
}

interface TodayStat {
  label: string;
  value: number;
}

export function WHProfileScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const { user, logout } = useAuthStore();
  const { language, setLanguage } = useLanguageStore();

  const toggleLanguage = () => {
    const newLang: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(newLang);
  };

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [todayStats, setTodayStats] = useState<TodayStat[]>([
    { label: t('profile.stats.inbound'), value: 0 },
    { label: t('profile.stats.outbound'), value: 0 },
    { label: t('profile.stats.check'), value: 0 },
    { label: t('profile.stats.alert'), value: 0 },
  ]);

  // 用户信息
  const userInfo = {
    name: user?.username || "陈仓管",
    role: t('profile.role'),
    factory: user?.factoryId || "白垩纪加工厂",
    avatar: user?.username?.charAt(0).toUpperCase() || "U",
  };

  // 加载今日统计数据
  const loadTodayStats = useCallback(async () => {
    try {
      const overview = await dashboardApiClient.getDashboardOverview() as {
        todayInbound?: number;
        todayOutbound?: number;
        todayInventoryChecks?: number;
        pendingBatches?: number;
        completedBatches?: number;
        activeAlerts?: number;
        pendingAlerts?: number;
      } | null;

      if (overview) {
        setTodayStats([
          { label: t('profile.stats.inbound'), value: overview.todayInbound || overview.pendingBatches || 0 },
          { label: t('profile.stats.outbound'), value: overview.todayOutbound || overview.completedBatches || 0 },
          { label: t('profile.stats.check'), value: overview.todayInventoryChecks || 0 },
          { label: t('profile.stats.alert'), value: overview.activeAlerts || overview.pendingAlerts || 0 },
        ]);
      }
    } catch (error) {
      handleError(error, { title: t('profile.loadStatsError') });
      // 使用默认值
      setTodayStats([
        { label: t('profile.stats.inbound'), value: 0 },
        { label: t('profile.stats.outbound'), value: 0 },
        { label: t('profile.stats.check'), value: 0 },
        { label: t('profile.stats.alert'), value: 0 },
      ]);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    loadTodayStats();
  }, [loadTodayStats]);

  // 菜单配置
  const menuSections: MenuSection[] = [
    {
      title: t('profile.sections.commonFeatures'),
      items: [
        {
          key: "io-stats",
          label: t('profile.menu.ioStats'),
          icon: "chart-bar",
          screen: "WHIOStatistics",
        },
        {
          key: "operation-log",
          label: t('profile.menu.operationLog'),
          icon: "history",
          screen: "WHOperationLog",
        },
        {
          key: "check-record",
          label: t('profile.menu.checkRecord'),
          icon: "clipboard-check-outline",
          screen: "WHInventoryCheck",
        },
        {
          key: "expire-handle",
          label: t('profile.menu.expireHandle'),
          icon: "clock-alert-outline",
          badge: 4,
          screen: "WHExpireHandle",
        },
      ],
    },
    {
      title: t('profile.sections.systemFeatures'),
      items: [
        {
          key: "language",
          label: t('profile.menu.language') || '语言',
          icon: "translate",
          description: LANGUAGE_NAMES[language],
          onPress: toggleLanguage,
        },
        {
          key: "settings",
          label: t('profile.menu.settings'),
          icon: "cog-outline",
          screen: "WHSettings",
        },
        {
          key: "temp-monitor",
          label: t('profile.menu.tempMonitor'),
          icon: "thermometer",
          screen: "WHSettings",
        },
        {
          key: "scan",
          label: t('profile.menu.scan'),
          icon: "qrcode-scan",
          screen: "WHSettings",
        },
        {
          key: "recall",
          label: t('profile.menu.recall'),
          icon: "package-variant-closed",
          screen: "WHRecallManage",
        },
      ],
    },
    {
      title: t('profile.sections.helpFeedback'),
      items: [
        {
          key: "help",
          label: t('profile.menu.help'),
          icon: "help-circle-outline",
          onPress: () => Alert.alert(t('profile.dialogs.helpTitle'), t('profile.dialogs.helpMessage')),
        },
        {
          key: "feedback",
          label: t('profile.menu.feedback'),
          icon: "message-text-outline",
          onPress: () => Alert.alert(t('profile.dialogs.feedbackTitle'), t('profile.dialogs.feedbackMessage')),
        },
        {
          key: "about",
          label: t('profile.menu.about'),
          icon: "information-outline",
          description: t('profile.version'),
          onPress: () => Alert.alert(t('profile.dialogs.aboutTitle'), t('profile.dialogs.aboutMessage')),
        },
      ],
    },
  ];

  const handleLogout = () => {
    // Rule 2: 弹窗带账号上下文，仓管员文化素质较低，需明确告知退出哪个账号
    const accountLabel = userInfo.name !== '陈仓管'
      ? `${userInfo.name}（${userInfo.role}）`
      : `@${user?.username ?? ''}（${userInfo.role}）`;
    const confirmMessage = `确定退出 ${accountLabel} 吗？`;

    if (Platform.OS === 'web') {
      if (window.confirm(confirmMessage)) {
        logout();
      }
      return;
    }
    Alert.alert(t('profile.dialogs.logoutTitle'), confirmMessage, [
      { text: t('profile.dialogs.cancel'), style: "cancel" },
      {
        text: t('profile.dialogs.confirm'),
        style: "destructive",
        onPress: () => {
          logout();
        },
      },
    ]);
  };

  const handleMenuPress = (item: MenuItem) => {
    if (item.onPress) {
      item.onPress();
    } else if (item.screen) {
      navigation.dispatch(CommonActions.navigate(item.screen));
    }
  };

  // 加载状态
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>{t('profile.title')}</Text>
          <Text style={styles.headerSubtitle}>{userInfo.role}</Text>
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>{t('profile.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>{t('profile.title')}</Text>
        <Text style={styles.headerSubtitle}>{userInfo.role}</Text>
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 用户信息卡片 */}
        <Surface style={styles.profileCard} elevation={1}>
          <View style={styles.profileContent}>
            <Avatar.Text
              size={60}
              label={userInfo.avatar}
              style={styles.avatar}
            />
            <View style={styles.profileInfo}>
              <Text style={styles.userName}>{userInfo.name}</Text>
              <Text style={styles.userRole}>{userInfo.role}</Text>
              <Text style={styles.userFactory}>{userInfo.factory}</Text>
            </View>
          </View>
          <TouchableOpacity
            style={styles.editButton}
            onPress={() => navigation.navigate("WHProfileEdit")}
          >
            <Text style={styles.editButtonText}>{t('profile.edit')}</Text>
          </TouchableOpacity>
        </Surface>

        {/* 今日统计 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('profile.todayStats')}</Text>
          <View style={styles.statsRow}>
            {todayStats.map((stat, index) => (
              <View key={stat.label} style={styles.statItem}>
                <Text
                  style={[
                    styles.statValue,
                    stat.label === t('profile.stats.alert') && styles.statValueDanger,
                  ]}
                >
                  {stat.value}
                </Text>
                <Text style={styles.statLabel}>{stat.label}</Text>
              </View>
            ))}
          </View>
        </View>

        {/* 菜单列表 */}
        {menuSections.map((section) => (
          <View key={section.title} style={styles.section}>
            <Text style={styles.sectionTitle}>{section.title}</Text>
            <View style={styles.menuList}>
              {section.items.map((item, index) => (
                <React.Fragment key={item.key}>
                  <TouchableOpacity
                    style={styles.menuItem}
                    onPress={() => handleMenuPress(item)}
                    activeOpacity={0.7}
                  >
                    <MaterialCommunityIcons
                      name={item.icon}
                      size={22}
                      color="#666"
                      style={styles.menuIcon}
                    />
                    <Text style={styles.menuText}>{item.label}</Text>
                    {item.badge && (
                      <View style={styles.badge}>
                        <Text style={styles.badgeText}>{item.badge}</Text>
                      </View>
                    )}
                    {item.description && (
                      <Text style={styles.menuDesc}>{item.description}</Text>
                    )}
                    <MaterialCommunityIcons
                      name="chevron-right"
                      size={20}
                      color="#ccc"
                    />
                  </TouchableOpacity>
                  {index < section.items.length - 1 && (
                    <Divider style={styles.divider} />
                  )}
                </React.Fragment>
              ))}
            </View>
          </View>
        ))}

        {/* 退出登录 */}
        <View style={styles.logoutSection}>
          <Button
            mode="outlined"
            onPress={handleLogout}
            style={styles.logoutButton}
            labelStyle={styles.logoutButtonLabel}
            icon="logout"
          >
            {t('profile.logout')}
          </Button>
        </View>

        <View style={{ height: 30 }} />
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
  profileCard: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
    flexDirection: "row",
    alignItems: "center",
  },
  profileContent: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
  },
  avatar: {
    backgroundColor: "#4CAF50",
  },
  profileInfo: {
    marginLeft: 16,
  },
  userName: {
    fontSize: 18,
    fontWeight: "bold",
    color: "#333",
  },
  userRole: {
    fontSize: 13,
    color: "#666",
    marginTop: 4,
  },
  userFactory: {
    fontSize: 12,
    color: "#999",
    marginTop: 2,
  },
  editButton: {
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: "#4CAF50",
  },
  editButtonText: {
    fontSize: 13,
    color: "#4CAF50",
    fontWeight: "500",
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  statsRow: {
    flexDirection: "row",
  },
  statItem: {
    flex: 1,
    alignItems: "center",
  },
  statValue: {
    fontSize: 24,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  statValueDanger: {
    color: "#f44336",
  },
  statLabel: {
    fontSize: 12,
    color: "#666",
    marginTop: 4,
  },
  menuList: {
    marginTop: -4,
  },
  menuItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 14,
  },
  menuIcon: {
    marginRight: 12,
  },
  menuText: {
    flex: 1,
    fontSize: 15,
    color: "#333",
  },
  badge: {
    backgroundColor: "#f44336",
    borderRadius: 10,
    minWidth: 20,
    height: 20,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 6,
    marginRight: 8,
  },
  badgeText: {
    color: "#fff",
    fontSize: 11,
    fontWeight: "bold",
  },
  menuDesc: {
    fontSize: 13,
    color: "#999",
    marginRight: 8,
  },
  divider: {
    marginLeft: 34,
  },
  logoutSection: {
    marginHorizontal: 16,
    marginTop: 24,
  },
  logoutButton: {
    borderColor: "#f44336",
    borderRadius: 8,
  },
  logoutButtonLabel: {
    color: "#f44336",
    fontWeight: "600",
  },
});

export default WHProfileScreen;
