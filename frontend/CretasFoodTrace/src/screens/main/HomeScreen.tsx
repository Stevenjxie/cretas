import React, { useMemo } from 'react';
import { View, StyleSheet, ScrollView, Alert, TouchableOpacity } from 'react-native';
import { Text, Avatar, Icon } from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../store/authStore';
import { ModuleConfig } from '../../types/navigation';
import { ModuleCard } from './components/ModuleCard';
import { QuickStatsPanel } from './components/QuickStatsPanel';
import { MainTabParamList } from '../../types/navigation';
import { UserPermissions, ROLE_METADATA } from '../../types/auth';
import { ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import { hasProductionCapability } from '../../utils/factoryType';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';

type HomeScreenNavigationProp = NativeStackNavigationProp<MainTabParamList, 'HomeTab'>;

/**
 * Home Screen (Neo Minimal Style)
 * 
 * Main dashboard with clean layout and standard components.
 */
export default function HomeScreen() {
  const navigation = useNavigation<HomeScreenNavigationProp>();
  const { user } = useAuthStore();
  const { t } = useTranslation('home');
  const { isModuleEnabled } = useFactoryFeatureStore();

  // Module Configuration
  const modules: ModuleConfig[] = useMemo(() => {
    if (!user) return [];
    const userPermissions = (user.userType === 'platform'
      ? user.platformUser?.permissions
      : user.factoryUser?.permissions) || [];

    const allModules: ModuleConfig[] = [
      {
        id: 'processing',
        name: t('modules.processing.name'),
        icon: 'cube-outline',
        description: t('modules.processing.description'),
        status: 'available',
        progress: 27,
        requiredPermissions: ['processing_access'],
        route: 'ProcessingTab',
        color: '#1890FF', // Neo Blue
      },
      {
        id: 'logistics',
        name: t('modules.logistics.name'),
        icon: 'truck-delivery',
        description: t('modules.logistics.description'),
        status: 'available',
        requiredPermissions: ['logistics_access'],
        route: 'LogisticsTab',
        color: '#FAAD14', // Neo Orange
      },
      {
        id: 'trace',
        name: t('modules.trace.name'),
        icon: 'qrcode-scan',
        description: t('modules.trace.description'),
        status: 'available',
        requiredPermissions: ['trace_access'],
        route: 'ProcessingTab',
        color: '#722ED1', // Neo Purple
      },
      {
        id: 'admin',
        name: t('modules.admin.name'),
        icon: 'account-cog',
        description: t('modules.admin.description'),
        status: 'available',
        requiredPermissions: ['admin_access'],
        route: 'ManagementTab',
        color: '#13C2C2', // Neo Cyan
      },
      {
        id: 'settings',
        name: t('modules.settings.name'),
        icon: 'cog',
        description: t('modules.settings.description'),
        status: 'available',
        requiredPermissions: [],
        color: '#595959', // Neo Gray
      },
    ];

    return allModules.filter(module => {
      if (!isModuleEnabled(module.id)) return false;
      if (module.id === 'processing' && !hasProductionCapability(user)) return false;
      if (module.requiredPermissions.length === 0) return true;
      return module.requiredPermissions.some(perm => {
        if (Array.isArray(userPermissions)) {
          return userPermissions.includes(perm);
        }
        if (typeof userPermissions === 'object' && userPermissions !== null && !Array.isArray(userPermissions)) {
          const permsObj = userPermissions as Partial<UserPermissions>;
          if (permsObj.modules && permsObj.modules[perm as keyof typeof permsObj.modules] === true) return true;
          if (Array.isArray(permsObj.features) && permsObj.features.includes(perm)) return true;
        }
        return false;
      });
    });
  }, [user, t, isModuleEnabled]);

  const handleModulePress = (module: ModuleConfig) => {
    if (module.status === 'locked') {
      Alert.alert(
        t('alerts.noAccess.title'),
        t('alerts.noAccess.message', { moduleName: module.name })
      );
      return;
    }

    if (module.route) {
      navigation.dispatch(CommonActions.navigate(module.route));
    } else if (module.id === 'settings') {
      Alert.alert(t('alerts.settingsInProgress.title'), t('alerts.settingsInProgress.message'));
    }
  };

  // User Display Info
  const displayName = user?.fullName || user?.username || 'User';
  const roleText = user?.userType === 'platform'
    ? t('roles.platformAdmin')
    : user?.userType === 'factory'
      ? getRoleDisplayName(user.factoryUser?.role || 'viewer', t)
      : 'User';

  return (
    <ScreenWrapper backgroundColor={theme.colors.background} edges={['top']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.contentContainer}
        showsVerticalScrollIndicator={false}
      >
        {/* Custom Header */}
        <View style={styles.header}>
          <View style={styles.userInfo}>
            <Avatar.Text 
              size={48} 
              label={displayName.charAt(0).toUpperCase()} 
              style={{ backgroundColor: theme.colors.primaryContainer }}
              color={theme.colors.onPrimaryContainer}
            />
            <View style={styles.userText}>
              <Text variant="titleMedium" style={styles.greeting}>{t('greeting')}, {displayName}</Text>
              <Text variant="bodySmall" style={styles.role}>{roleText}</Text>
            </View>
          </View>
          <TouchableOpacity
            onPress={() => Alert.alert(t('alerts.notification.title'), t('alerts.notification.noNew'))}
            style={styles.notificationButton}
          >
            <Icon source="bell-outline" size={24} color={theme.colors.onSurface} />
          </TouchableOpacity>
        </View>

        {/* Quick Stats */}
        {user && (
          <View style={styles.section}>
            <QuickStatsPanel user={user} />
          </View>
        )}

        {/* Modules Grid */}
        <View style={styles.section}>
          <Text variant="titleLarge" style={styles.sectionTitle}>{t('sections.modules')}</Text>
          <View style={styles.modulesGrid}>
            {modules.map(module => (
              <ModuleCard
                key={module.id}
                module={module}
                onPress={() => handleModulePress(module)}
              />
            ))}
          </View>
        </View>

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </ScreenWrapper>
  );
}

/**
 * APP-UX-006 (2026-08-02): 原来这里是一张只有 6 个角色的私有表, 表外的角色
 * (财务主管/出纳/销售主管/采购主管/生产经理…) 直接 `return role` —— 首页顶部
 * 于是显示内部英文角色码。改成: 由角色码推出 i18n key, **兜底取权威表
 * ROLE_METADATA 的中文名**, 这样以后新增角色即使漏了翻译也不会再泄漏角色码。
 */
function roleI18nKey(role: string): string {
  return `roles.${role.replace(/_([a-z])/g, (_m, c: string) => c.toUpperCase())}`;
}

function getRoleDisplayName(
  role: string,
  t: (key: string, options?: { defaultValue?: string }) => string
): string {
  return t(roleI18nKey(role), {
    defaultValue: ROLE_METADATA[role]?.displayName || role,
  });
}

const styles = StyleSheet.create({
  scrollView: {
    flex: 1,
  },
  contentContainer: {
    padding: 16,
    paddingTop: 24, // Extra padding for top
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 32,
  },
  userInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  userText: {
    justifyContent: 'center',
  },
  greeting: {
    fontWeight: '700',
    color: theme.colors.text,
    fontSize: 20,
  },
  role: {
    color: theme.colors.onSurfaceVariant,
    marginTop: 2,
  },
  notificationButton: {
    padding: 8,
    backgroundColor: theme.colors.surface,
    borderRadius: theme.custom.borderRadius.round,
    ...theme.custom.shadows.small,
  },
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    fontWeight: '700',
    marginBottom: 16,
    color: theme.colors.text,
    fontSize: 18,
  },
  modulesGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
  },
  bottomSpacer: {
    height: 40,
  },
});
