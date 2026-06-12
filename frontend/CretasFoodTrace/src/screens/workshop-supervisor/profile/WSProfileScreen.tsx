/**
 * Workshop Supervisor 个人中心
 * 包含: 用户信息、账户设置、帮助与支持、关于
 */
import React from "react";
import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Alert } from "react-native";
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Icon } from "react-native-paper";
import { useTranslation } from "react-i18next";
import { WSProfileStackParamList } from "../../../types/navigation";
import { useAuthStore } from "../../../store/authStore";
import { useLanguageStore, LANGUAGE_NAMES, type SupportedLanguage } from "../../../store/languageStore";

type NavigationProp = NativeStackNavigationProp<WSProfileStackParamList, "WSProfile">;

interface MenuItemProps {
  icon: string;
  title: string;
  onPress: () => void;
  showArrow?: boolean;
  rightText?: string;
  danger?: boolean;
}

function MenuItem({ icon, title, onPress, showArrow = true, rightText, danger = false }: MenuItemProps) {
  return (
    <TouchableOpacity style={styles.menuItem} onPress={onPress}>
      <Icon source={icon} size={22} color={danger ? "#ff4d4f" : "#666"} />
      <Text style={[styles.menuTitle, danger && styles.dangerText]}>{title}</Text>
      <View style={styles.menuRight}>
        {rightText && <Text style={styles.rightText}>{rightText}</Text>}
        {showArrow && <Icon source="chevron-right" size={20} color="#ccc" />}
      </View>
    </TouchableOpacity>
  );
}

export function WSProfileScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { user, logout } = useAuthStore();
  const { t } = useTranslation('workshop');
  const { language, setLanguage } = useLanguageStore();
  const insets = useSafeAreaInsets();

  const toggleLanguage = () => {
    const newLang: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(newLang);
  };

  const handleLogout = () => {
    // Rule 2: 退出弹窗带账号上下文
    const displayName = user?.fullName || user?.username || '';
    const roleName = t('profile.role');
    const accountLabel = displayName
      ? `${displayName}（${roleName}）`
      : `@${user?.username ?? ''}（${roleName}）`;
    Alert.alert(
      t('profile.menu.logout'),
      `确定退出 ${accountLabel} 吗？`,
      [
        { text: '取消', style: 'cancel' },
        { text: '退出', style: 'destructive', onPress: logout },
      ]
    );
  };

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView}>
        {/* Header */}
        <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
          <Text style={styles.title}>{t('profile.title')}</Text>
        </View>

        {/* 用户信息卡片 */}
        <TouchableOpacity
          style={styles.userCard}
          onPress={() => navigation.navigate("PersonalInfo")}
        >
          <View style={styles.avatar}>
            <Icon source="account" size={40} color="#fff" />
          </View>
          <View style={styles.userInfo}>
            <Text style={styles.userName}>{user?.username || t('profile.role')}</Text>
            <Text style={styles.userRole}>{t('profile.role')}</Text>
          </View>
          <Icon source="chevron-right" size={24} color="#ccc" />
        </TouchableOpacity>

        {/* 今日统计 */}
        <View style={styles.statsCard}>
          <View style={styles.statsItem}>
            <Text style={styles.statsValue}>6</Text>
            <Text style={styles.statsLabel}>{t('profile.stats.managedBatches')}</Text>
          </View>
          <View style={styles.statsDivider} />
          <View style={styles.statsItem}>
            <Text style={styles.statsValue}>8</Text>
            <Text style={styles.statsLabel}>{t('profile.stats.onDutyPersonnel')}</Text>
          </View>
          <View style={styles.statsDivider} />
          <View style={styles.statsItem}>
            <Text style={styles.statsValue}>4</Text>
            <Text style={styles.statsLabel}>{t('profile.stats.runningEquipment')}</Text>
          </View>
        </View>

        {/* 账户设置 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('profile.sections.accountSettings')}</Text>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="account-edit"
              title={t('profile.menu.personalInfo')}
              onPress={() => navigation.navigate("PersonalInfo")}
            />
            <MenuItem
              icon="lock-outline"
              title={t('profile.menu.changePassword')}
              onPress={() => navigation.navigate("ChangePassword")}
            />
            <MenuItem
              icon="bell-outline"
              title={t('profile.menu.notificationSettings')}
              onPress={() => navigation.navigate("NotificationSettings")}
            />
          </View>
        </View>

        {/* 系统设置 — Rule 2 "语言" 保留; Rule 5 "系统设置" 入口已移除:
            该路由(Settings)指向 WSProfileScreen 自身，是死胡同，没有实际内容。
            per fool-proof Rule 5: 无内容则不显示该入口，避免用户卡住。 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('profile.sections.systemSettings')}</Text>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="translate"
              title={t('profile.menu.language') || '语言'}
              onPress={toggleLanguage}
              rightText={LANGUAGE_NAMES[language]}
              showArrow={false}
            />
          </View>
        </View>

        {/* 帮助与支持 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('profile.sections.helpSupport')}</Text>
          <View style={styles.menuGroup}>
            {/* TODO: 教程功能暂时隐藏，后续优化后恢复 */}
            {/* <MenuItem
              icon="school-outline"
              title="操作教程"
              onPress={() => {
                const { useTutorialStore } = require('../../../store/tutorialStore');
                useTutorialStore.getState().resetAllTutorials();
                Alert.alert('教程已重置', '返回首页后将重新显示操作引导', [
                  { text: '去首页', onPress: () => navigation.navigate('WSHomeTab' as never) },
                ]);
              }}
              rightText="重新学习"
            /> */}
            <MenuItem
              icon="information-outline"
              title={t('profile.menu.about')}
              onPress={() => navigation.navigate("About")}
              rightText="v1.0.0"
            />
          </View>
        </View>

        {/* 退出登录 */}
        <View style={styles.menuSection}>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="logout"
              title={t('profile.menu.logout')}
              onPress={handleLogout}
              showArrow={false}
              danger
            />
          </View>
        </View>

        {/* 底部间距 */}
        <View style={{ height: 32 }} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 20,
    backgroundColor: "#667eea",
  },
  title: {
    fontSize: 24,
    fontWeight: "bold",
    color: "#fff",
  },
  userCard: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#fff",
    margin: 16,
    padding: 16,
    borderRadius: 12,
  },
  avatar: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: "#667eea",
    alignItems: "center",
    justifyContent: "center",
  },
  userInfo: {
    flex: 1,
    marginLeft: 12,
  },
  userName: {
    fontSize: 18,
    fontWeight: "600",
    color: "#333",
  },
  userRole: {
    fontSize: 14,
    color: "#666",
    marginTop: 4,
  },
  statsCard: {
    flexDirection: "row",
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginBottom: 16,
    paddingVertical: 16,
    borderRadius: 12,
  },
  statsItem: {
    flex: 1,
    alignItems: "center",
  },
  statsValue: {
    fontSize: 24,
    fontWeight: "bold",
    color: "#667eea",
  },
  statsLabel: {
    fontSize: 12,
    color: "#666",
    marginTop: 4,
  },
  statsDivider: {
    width: 1,
    backgroundColor: "#f0f0f0",
  },
  menuSection: {
    marginTop: 8,
  },
  sectionTitle: {
    fontSize: 13,
    color: "#999",
    marginLeft: 20,
    marginBottom: 8,
  },
  menuGroup: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: "hidden",
  },
  menuItem: {
    flexDirection: "row",
    alignItems: "center",
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: "#f0f0f0",
  },
  menuTitle: {
    flex: 1,
    fontSize: 16,
    color: "#333",
    marginLeft: 12,
  },
  dangerText: {
    color: "#ff4d4f",
  },
  menuRight: {
    flexDirection: "row",
    alignItems: "center",
  },
  rightText: {
    fontSize: 14,
    color: "#999",
    marginRight: 4,
  },
});

export default WSProfileScreen;
