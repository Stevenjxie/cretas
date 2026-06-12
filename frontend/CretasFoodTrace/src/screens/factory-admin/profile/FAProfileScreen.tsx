/**
 * Factory Admin 个人中心
 * 包含: 用户信息、账户设置、帮助与支持、关于
 */
import React from "react";
import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Image, Alert } from "react-native";
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Icon } from "react-native-paper";
import { useTranslation } from "react-i18next";
import { FAProfileStackParamList } from "../../../types/navigation";
import { useAuthStore } from "../../../store/authStore";
import { useLanguageStore, LANGUAGE_NAMES, type SupportedLanguage } from "../../../store/languageStore";

type NavigationProp = NativeStackNavigationProp<FAProfileStackParamList, "FAProfile">;

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

export function FAProfileScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { user, logout } = useAuthStore();
  const { t } = useTranslation('profile');
  const { language, setLanguage } = useLanguageStore();

  const toggleLanguage = () => {
    const newLang: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(newLang);
  };

  const handleLogout = () => {
    // Rule 2: 退出弹窗带账号上下文
    const displayName = user?.fullName || user?.username || '';
    const roleName = getRoleDisplayName();
    const accountLabel = displayName
      ? `${displayName}（${roleName}）`
      : `@${user?.username ?? ''}（${roleName}）`;
    Alert.alert(
      t('menu.logout'),
      `确定退出 ${accountLabel} 吗？`,
      [
        { text: '取消', style: 'cancel' },
        { text: '退出', style: 'destructive', onPress: logout },
      ]
    );
  };

  // Get role display name
  const getRoleDisplayName = (): string => {
    const role = user?.factoryUser?.role;
    const roleMap: Record<string, string> = {
      factory_super_admin: t('roles.factorySuperAdmin'),
      factory_admin: t('roles.factoryAdmin'),
      permission_admin: t('roles.factoryAdmin'),
      department_admin: t('roles.factoryAdmin'),
      operator: t('roles.operator'),
      viewer: t('roles.viewer'),
    };
    return roleMap[role || ''] || t('roles.factoryAdmin');
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView style={styles.scrollView}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>{t('title')}</Text>
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
            <Text style={styles.userName}>{user?.username || t('defaultUser')}</Text>
            <Text style={styles.userRole}>{getRoleDisplayName()}</Text>
          </View>
          <Icon source="chevron-right" size={24} color="#ccc" />
        </TouchableOpacity>

        {/* 账户设置 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('sections.accountSettings')}</Text>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="account-edit"
              title={t('menu.personalInfo')}
              onPress={() => navigation.navigate("PersonalInfo")}
            />
            <MenuItem
              icon="lock-outline"
              title={t('menu.changePassword')}
              onPress={() => navigation.navigate("ChangePassword")}
            />
            <MenuItem
              icon="bell-outline"
              title={t('menu.notificationSettings')}
              onPress={() => navigation.navigate("NotificationSettings")}
            />
          </View>
        </View>

        {/* 系统设置 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('sections.systemSettings')}</Text>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="translate"
              title={t('menu.language') || '语言'}
              onPress={toggleLanguage}
              rightText={LANGUAGE_NAMES[language]}
              showArrow={false}
            />
            <MenuItem
              icon="cog-outline"
              title={t('menu.systemSettings')}
              onPress={() => navigation.navigate("SystemSettings")}
            />
            <MenuItem
              icon="download-outline"
              title={t('menu.dataExport')}
              onPress={() => navigation.navigate("DataExport", {})}
            />
          </View>
        </View>

        {/* 帮助与支持 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('sections.helpSupport')}</Text>
          <View style={styles.menuGroup}>
            <MenuItem
              icon="help-circle-outline"
              title={t('menu.helpCenter')}
              onPress={() => navigation.navigate("HelpCenter")}
            />
            <MenuItem
              icon="message-text-outline"
              title={t('menu.feedback')}
              onPress={() => navigation.navigate("Feedback")}
            />
            <MenuItem
              icon="information-outline"
              title={t('menu.about')}
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
              title={t('menu.logout')}
              onPress={handleLogout}
              showArrow={false}
              danger
            />
          </View>
        </View>

        {/* 底部间距 */}
        <View style={{ height: 32 }} />
      </ScrollView>
    </SafeAreaView>
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
    backgroundColor: "#fff",
    borderBottomWidth: 1,
    borderBottomColor: "#e0e0e0",
  },
  title: {
    fontSize: 24,
    fontWeight: "bold",
    color: "#333",
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

export default FAProfileScreen;
