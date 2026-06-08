import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert, TouchableOpacity, Platform } from 'react-native';
import { Text, Avatar, List, Divider, Portal, Dialog, TextInput, HelperText, ActivityIndicator, Chip, Card } from 'react-native-paper';
import { useAuthStore } from '../../store/authStore';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { useLanguageStore, LANGUAGE_NAMES, type SupportedLanguage } from '../../store/languageStore';
import { userApiClient } from '../../services/api/userApiClient';
import { platformAPI } from '../../services/api/platformApiClient';
import { NeoCard, NeoButton, ScreenWrapper, StatusBadge } from '../../components/ui';
import { theme } from '../../theme';
import { logger } from '../../utils/logger';
import { handleError } from '../../utils/errorHandler';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';

// 创建ProfileScreen专用logger
const profileLogger = logger.createContextLogger('ProfileScreen');

export default function ProfileScreen() {
  const { user, logout } = useAuthStore();
  const navigation = useNavigation();
  const { language, setLanguage } = useLanguageStore();
  const { isScreenEnabled } = useFactoryFeatureStore();

  const toggleLanguage = () => {
    const newLang: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(newLang);
  };

  const [passwordDialogVisible, setPasswordDialogVisible] = useState(false);
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const [showPasswords, setShowPasswords] = useState({ old: false, new: false, confirm: false });

  // 统计数据状态
  const [stats, setStats] = useState({
    totalFactories: 0,
    totalUsers: 0,
    totalBatches: 0,
    completedBatches: 0,
    aiUsageThisWeek: 0,
    todayProduction: 0,
  });
  const [loadingStats, setLoadingStats] = useState(false);

  const isPlatformAdmin = user?.userType === 'platform';

  // 加载统计数据
  useEffect(() => {
    loadStatistics();
  }, []);

  const loadStatistics = async () => {
    if (!isPlatformAdmin) return; // 目前仅为平台管理员加载统计

    setLoadingStats(true);
    try {
      profileLogger.debug('加载个人中心统计数据');
      const response = await platformAPI.getPlatformStatistics();

      if (response.success && response.data) {
        const data = response.data;
        const newStats = {
          totalFactories: data.totalFactories || 0,
          totalUsers: data.totalUsers || 0,
          totalBatches: data.totalBatches || 0,
          completedBatches: 0, // 后端暂未返回此字段
          aiUsageThisWeek: data.totalAIRequests || 0,
          todayProduction: 0, // 后端暂未返回此字段
        };
        setStats(newStats);
        profileLogger.info('个人中心统计数据加载成功', { stats: newStats });
      }
    } catch (error) {
      profileLogger.error('加载统计数据失败', error as Error);
      // 静默失败，不影响个人中心其他功能
    } finally {
      setLoadingStats(false);
    }
  };

  const validatePassword = () => {
    if (!oldPassword || !newPassword || !confirmPassword) return { valid: false, message: '请填写所有字段' };
    if (newPassword.length < 6) return { valid: false, message: '新密码至少6位' };
    if (newPassword === oldPassword) return { valid: false, message: '新密码不能与旧密码相同' };
    if (newPassword !== confirmPassword) return { valid: false, message: '两次密码不一致' };
    if (!(/[A-Za-z]/.test(newPassword) && /[0-9]/.test(newPassword))) return { valid: false, message: '需包含字母和数字' };
    return { valid: true };
  };

  const handleChangePassword = async () => {
    const validation = validatePassword();
    if (!validation.valid) return Alert.alert('验证失败', validation.message);

    try {
      setChangingPassword(true);
      const userId = user?.id;
      if (!userId) throw new Error('用户ID不存在');
      
      await userApiClient.changePassword(typeof userId === 'string' ? parseInt(userId, 10) : userId, { oldPassword, newPassword });

      setOldPassword(''); setNewPassword(''); setConfirmPassword('');
      setPasswordDialogVisible(false);
      Alert.alert('成功', '密码已修改，请使用新密码登录');
    } catch (error) {
      handleError(error, {
        title: '修改密码失败',
        customMessage: '请检查旧密码是否正确',
      });
    } finally {
      setChangingPassword(false);
    }
  };

  const handleLogout = () => {
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
      if (window.confirm('确定要退出吗？')) {
        doLogout();
      }
      return;
    }
    Alert.alert('退出登录', '确定要退出吗？', [
      { text: '取消', style: 'cancel' },
      { text: '退出', style: 'destructive', onPress: doLogout }
    ]);
  };

  const displayName = user?.fullName || user?.username || '未知用户';
  const roleCode = user?.userType === 'platform' ? user?.platformUser?.role : user?.factoryUser?.role;
  // A1: operators see only user-facing items; dev/admin tools are hidden.
  const isOperator = roleCode === 'operator';

  const getRoleName = (role: string | undefined) => {
    const roleMap: Record<string, string> = {
      'developer': '系统开发者', 'platform_admin': '平台管理员', 'factory_super_admin': '工厂超级管理员',
      'permission_admin': '权限管理员', 'operator': '操作员', 'viewer': '查看者'
    };
    return roleMap[role || ''] || role || '未知角色';
  };

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
            <Text style={styles.headerTitle}>个人中心</Text>
        </View>

        {/* Profile Card */}
        <NeoCard style={styles.profileCard} padding="l">
            <View style={styles.profileHeader}>
                <Avatar.Text size={72} label={displayName.substring(0, 2)} style={styles.avatar} labelStyle={styles.avatarLabel} />
                <View style={styles.profileInfo}>
                    <Text style={styles.displayName}>{displayName}</Text>
                    <Text style={styles.username}>@{user?.username}</Text>
                    <View style={styles.badges}>
                        <StatusBadge status={isPlatformAdmin ? '平台管理员' : '工厂用户'} variant={isPlatformAdmin ? 'info' : 'success'} />
                        <StatusBadge status={getRoleName(roleCode)} variant="warning" />
                    </View>
                </View>
            </View>
        </NeoCard>

        {/* 统计数据卡片（仅平台管理员） */}
        {isPlatformAdmin && (
          <NeoCard style={styles.card} padding="m">
            <View style={styles.statsHeader}>
              <Text style={styles.sectionTitle}>数据概览</Text>
              {loadingStats && <ActivityIndicator size="small" color={theme.colors.primary} />}
            </View>
            <View style={styles.statsGrid}>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#2196F3' }]}>{stats.totalFactories}</Text>
                <Text style={styles.statLabel}>工厂数</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#4CAF50' }]}>{stats.totalUsers}</Text>
                <Text style={styles.statLabel}>用户数</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#FF9800' }]}>{stats.totalBatches}</Text>
                <Text style={styles.statLabel}>总批次</Text>
              </View>
            </View>
            <Divider style={styles.divider} />
            <View style={styles.statsGrid}>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#9C27B0' }]}>{stats.aiUsageThisWeek}</Text>
                <Text style={styles.statLabel}>AI调用</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#FF6B35' }]}>{stats.todayProduction.toFixed(1)}t</Text>
                <Text style={styles.statLabel}>今日产量</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#00BCD4' }]}>
                  {stats.totalBatches > 0
                    ? ((stats.completedBatches / stats.totalBatches) * 100).toFixed(0)
                    : '0'}%
                </Text>
                <Text style={styles.statLabel}>完成率</Text>
              </View>
            </View>
          </NeoCard>
        )}

        {/* Info Sections */}
        <NeoCard style={styles.card} padding="m">
            <Text style={styles.sectionTitle}>账号信息</Text>
            <View style={styles.infoRow}>
                <List.Icon icon="account-outline" color={theme.colors.textSecondary} />
                <View style={styles.infoContent}>
                    <Text style={styles.infoLabel}>用户名</Text>
                    <Text style={styles.infoValue}>{user?.username}</Text>
                </View>
            </View>
            <Divider style={styles.divider} />
            <View style={styles.infoRow}>
                <List.Icon icon="email-outline" color={theme.colors.textSecondary} />
                <View style={styles.infoContent}>
                    <Text style={styles.infoLabel}>邮箱</Text>
                    <Text style={styles.infoValue}>{user?.email || '未设置'}</Text>
                </View>
            </View>
            <Divider style={styles.divider} />
            <View style={styles.infoRow}>
                <List.Icon icon="phone-outline" color={theme.colors.textSecondary} />
                <View style={styles.infoContent}>
                    <Text style={styles.infoLabel}>手机号</Text>
                    <Text style={styles.infoValue}>{user?.phone || '未设置'}</Text>
                </View>
            </View>
        </NeoCard>

        {/* Settings */}
        <NeoCard style={styles.card} padding="m">
            <Text style={styles.sectionTitle}>更多功能</Text>

            {/* 语言切换 */}
            <TouchableOpacity style={styles.settingItem} onPress={toggleLanguage}>
                <View style={styles.settingLeft}>
                    <List.Icon icon="translate" color="#9C27B0" />
                    <Text style={styles.settingText}>语言 / Language</Text>
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                    <Text style={{ fontSize: 14, color: theme.colors.textSecondary, marginRight: 4 }}>{LANGUAGE_NAMES[language]}</Text>
                </View>
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 修改密码 */}
            <TouchableOpacity style={styles.settingItem} onPress={() => setPasswordDialogVisible(true)}>
                <View style={styles.settingLeft}>
                    <List.Icon icon="lock-reset" color={theme.colors.primary} />
                    <Text style={styles.settingText}>修改密码</Text>
                </View>
                <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 通知设置 */}
            {isScreenEnabled('NotificationSettings') && (
              <>
                <TouchableOpacity
                  style={styles.settingItem}
                  onPress={() => {
                    profileLogger.info('打开通知设置');
                    (navigation as any).navigate('NotificationCenter');
                  }}
                >
                    <View style={styles.settingLeft}>
                        <List.Icon icon="bell-outline" color="#FF9800" />
                        <Text style={styles.settingText}>通知设置</Text>
                    </View>
                    <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
                </TouchableOpacity>
                <Divider style={styles.divider} />
              </>
            )}

            {/* 数据导出 — A1: hidden for operators */}
            {!isOperator && (
              <>
                <TouchableOpacity
                  style={styles.settingItem}
                  onPress={() => {
                    profileLogger.info('数据导出');
                    Alert.alert('数据导出', '可导出个人数据和操作记录');
                  }}
                >
                    <View style={styles.settingLeft}>
                        <List.Icon icon="download-outline" color="#4CAF50" />
                        <Text style={styles.settingText}>数据导出</Text>
                    </View>
                    <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
                </TouchableOpacity>
                <Divider style={styles.divider} />
              </>
            )}

            {/* 帮助文档 */}
            <TouchableOpacity
              style={styles.settingItem}
              onPress={() => {
                profileLogger.info('查看帮助文档');
                Alert.alert('帮助文档', '查看使用手册和常见问题解答');
              }}
            >
                <View style={styles.settingLeft}>
                    <List.Icon icon="help-circle-outline" color="#2196F3" />
                    <Text style={styles.settingText}>帮助文档</Text>
                </View>
                <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 隐私设置 */}
            <TouchableOpacity
              style={styles.settingItem}
              onPress={() => {
                profileLogger.info('隐私设置');
                Alert.alert('隐私设置', '管理数据使用和隐私权限');
              }}
            >
                <View style={styles.settingLeft}>
                    <List.Icon icon="shield-lock-outline" color="#9C27B0" />
                    <Text style={styles.settingText}>隐私设置</Text>
                </View>
                <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 意见反馈 */}
            <TouchableOpacity
              style={styles.settingItem}
              onPress={() => {
                profileLogger.info('打开意见反馈');
                Alert.alert('意见反馈', '请通过邮件或电话联系我们提交反馈');
              }}
            >
                <View style={styles.settingLeft}>
                    <List.Icon icon="message-alert-outline" color="#00BCD4" />
                    <Text style={styles.settingText}>意见反馈</Text>
                </View>
                <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 关于应用 */}
            <TouchableOpacity
              style={styles.settingItem}
              onPress={() => {
                profileLogger.info('查看关于应用');
                Alert.alert(
                  '白垩纪AI Agent',
                  '版本: 1.0.0\n\n© 2025 白垩纪科技\n\n为食品安全保驾护航',
                  [{ text: '确定' }]
                );
              }}
            >
                <View style={styles.settingLeft}>
                    <List.Icon icon="information-outline" color="#607D8B" />
                    <Text style={styles.settingText}>关于应用</Text>
                </View>
                <List.Icon icon="chevron-right" color={theme.colors.textTertiary} />
            </TouchableOpacity>
            <Divider style={styles.divider} />

            {/* 开发者工具 - 服务器连接测试 — A1: hidden for operators */}
            {!isOperator && (
              <TouchableOpacity
                style={styles.settingItem}
                onPress={() => {
                  profileLogger.info('打开服务器连接测试');
                  navigation.dispatch(CommonActions.navigate('ServerConnectivityTest'));
                }}
              >
                  <View style={styles.settingLeft}>
                      <List.Icon icon="server-network" color="#E91E63" />
                      <Text style={styles.settingText}>服务器连接测试</Text>
                  </View>
                  <Chip mode="flat" style={{ backgroundColor: '#FFF3E0' }} textStyle={{ fontSize: 10, color: '#FF9800' }}>开发</Chip>
              </TouchableOpacity>
            )}

            {/* 开发者工具 - 意图执行测试 — A1: hidden for operators */}
            {!isOperator && (
              <TouchableOpacity
                style={styles.settingItem}
                onPress={() => {
                  profileLogger.info('打开意图执行测试');
                  navigation.dispatch(CommonActions.navigate('IntentExecutionTest'));
                }}
              >
                  <View style={styles.settingLeft}>
                      <List.Icon icon="brain" color="#9C27B0" />
                      <Text style={styles.settingText}>意图执行测试</Text>
                  </View>
                  <Chip mode="flat" style={{ backgroundColor: '#F3E5F5' }} textStyle={{ fontSize: 10, color: '#9C27B0' }}>AI</Chip>
              </TouchableOpacity>
            )}
        </NeoCard>

        <NeoButton variant="danger" onPress={handleLogout} style={styles.logoutButton} icon="logout">退出登录</NeoButton>

      </ScrollView>

      {/* Password Dialog */}
      <Portal>
        <Dialog visible={passwordDialogVisible} onDismiss={() => !changingPassword && setPasswordDialogVisible(false)} style={styles.dialog}>
            <Dialog.Title>修改密码</Dialog.Title>
            <Dialog.Content>
                <TextInput label="旧密码" value={oldPassword} onChangeText={setOldPassword} secureTextEntry={!showPasswords.old} 
                    right={<TextInput.Icon icon={showPasswords.old ? "eye-off" : "eye"} onPress={() => setShowPasswords({...showPasswords, old: !showPasswords.old})} />} 
                    mode="outlined" style={styles.input} />
                <TextInput label="新密码" value={newPassword} onChangeText={setNewPassword} secureTextEntry={!showPasswords.new}
                    right={<TextInput.Icon icon={showPasswords.new ? "eye-off" : "eye"} onPress={() => setShowPasswords({...showPasswords, new: !showPasswords.new})} />}
                    mode="outlined" style={styles.input} />
                <TextInput label="确认新密码" value={confirmPassword} onChangeText={setConfirmPassword} secureTextEntry={!showPasswords.confirm}
                    right={<TextInput.Icon icon={showPasswords.confirm ? "eye-off" : "eye"} onPress={() => setShowPasswords({...showPasswords, confirm: !showPasswords.confirm})} />}
                    mode="outlined" style={styles.input} />
            </Dialog.Content>
            <Dialog.Actions>
                <NeoButton variant="ghost" onPress={() => setPasswordDialogVisible(false)} disabled={changingPassword}>取消</NeoButton>
                <NeoButton variant="primary" onPress={handleChangePassword} loading={changingPassword}>确认修改</NeoButton>
            </Dialog.Actions>
        </Dialog>
      </Portal>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  content: { padding: 16, paddingBottom: 40 },
  header: { paddingVertical: 16 },
  headerTitle: { fontSize: 28, fontWeight: '700', color: theme.colors.text },
  profileCard: { marginBottom: 16 },
  profileHeader: { flexDirection: 'row', alignItems: 'center' },
  avatar: { backgroundColor: theme.colors.primary },
  avatarLabel: { fontSize: 24, color: 'white' },
  profileInfo: { marginLeft: 16, flex: 1 },
  displayName: { fontSize: 20, fontWeight: '600', color: theme.colors.text },
  username: { fontSize: 14, color: theme.colors.textSecondary, marginBottom: 8 },
  badges: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  card: { marginBottom: 16 },
  sectionTitle: { fontSize: 16, fontWeight: '600', marginBottom: 12, color: theme.colors.text },
  infoRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4 },
  infoContent: { marginLeft: 8 },
  infoLabel: { fontSize: 12, color: theme.colors.textSecondary },
  infoValue: { fontSize: 16, color: theme.colors.text },
  divider: { marginVertical: 8 },
  settingItem: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 8 },
  settingLeft: { flexDirection: 'row', alignItems: 'center' },
  settingText: { fontSize: 16, color: theme.colors.text, marginLeft: 8 },
  logoutButton: { marginTop: 16 },
  dialog: { backgroundColor: 'white', borderRadius: 12 },
  input: { marginBottom: 12, backgroundColor: 'white' },
  // 统计数据样式
  statsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  statsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
  },
  statBox: {
    flex: 1,
    alignItems: 'center',
    padding: 12,
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
  },
  statValue: {
    fontSize: 24,
    fontWeight: '700',
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 12,
    color: theme.colors.textSecondary,
  },
});
