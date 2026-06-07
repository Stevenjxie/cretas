import React, { useState } from 'react';
import { View, StyleSheet, ScrollView, TouchableOpacity, Alert } from 'react-native';
import { Text, List, Divider, useTheme, Avatar, IconButton, Menu, ActivityIndicator } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../store/authStore';
import { NeoCard, NeoButton, ScreenWrapper, StatusBadge } from '../../components/ui';
import { theme } from '../../theme';

type ManagementNavigationProp = NativeStackNavigationProp<any>;

export default function ManagementScreen() {
  const { t } = useTranslation('management');
  const navigation = useNavigation<ManagementNavigationProp>();
  const { user } = useAuthStore();
  const [exportMenuVisible, setExportMenuVisible] = useState(false);
  const [exporting, setExporting] = useState(false);

  // ✅ 修复: 支持平台管理员和工厂管理员
  const isAdmin =
    user?.userType === 'platform' || // 平台管理员有所有权限
    user?.factoryUser?.role === 'factory_super_admin' ||
    user?.factoryUser?.role === 'permission_admin';

  const managementSections = [
    {
      title: t('sections.productionConfig.title'),
      icon: 'cog-outline',
      items: [
        { id: 'product-types', title: t('sections.productionConfig.productTypes.title'), desc: t('sections.productionConfig.productTypes.desc'), icon: 'fish', route: 'ProductTypeManagement' },
        { id: 'material-types', title: t('sections.productionConfig.materialTypes.title'), desc: t('sections.productionConfig.materialTypes.desc'), icon: 'food-drumstick', route: 'MaterialTypeManagement' },
        // T125: 转换率配置入口已移除 — 后端 API/fallback 仍保留 (F001等BomExpansionService依赖)
        { id: 'work-types', title: t('sections.productionConfig.workTypes.title'), desc: t('sections.productionConfig.workTypes.desc'), icon: 'account-hard-hat', route: 'WorkTypeManagement', adminOnly: true },
        { id: 'work-processes', title: t('sections.productionConfig.workProcesses.title', { defaultValue: '工序管理' }), desc: t('sections.productionConfig.workProcesses.desc', { defaultValue: '定义生产工序步骤 (拆包/分割/卤制等)' }), icon: 'cog-transfer-outline', route: 'WorkProcessList' },
        { id: 'product-work-processes', title: t('sections.productionConfig.productWorkProcesses.title', { defaultValue: '产品工序配置' }), desc: t('sections.productionConfig.productWorkProcesses.desc', { defaultValue: '给产品绑定工序流程, 生产计划自动带入' }), icon: 'format-list-numbered', route: 'ProductWorkProcessConfig' },
        { id: 'disposal-records', title: t('sections.productionConfig.disposalRecords.title'), desc: t('sections.productionConfig.disposalRecords.desc'), icon: 'delete-forever', route: 'DisposalRecordManagement', adminOnly: true },
      ],
    },
    {
      title: t('sections.systemManagement.title'),
      icon: 'shield-account-outline',
      items: [
        { id: 'departments', title: t('sections.systemManagement.departments.title'), desc: t('sections.systemManagement.departments.desc'), icon: 'office-building', route: 'DepartmentManagement', adminOnly: true },
        { id: 'users', title: t('sections.systemManagement.users.title'), desc: t('sections.systemManagement.users.desc'), icon: 'account-cog', route: 'UserManagement', adminOnly: true },
        { id: 'whitelist', title: t('sections.systemManagement.whitelist.title'), desc: t('sections.systemManagement.whitelist.desc'), icon: 'shield-check', route: 'WhitelistManagement', adminOnly: true },
        { id: 'work-sessions', title: t('sections.systemManagement.workSessions.title'), desc: t('sections.systemManagement.workSessions.desc'), icon: 'clock-outline', route: 'WorkSessionManagement', adminOnly: true },
      ],
    },
    {
        title: t('sections.businessPartners.title'),
        icon: 'handshake-outline',
        items: [
            { id: 'suppliers', title: t('sections.businessPartners.suppliers.title'), desc: t('sections.businessPartners.suppliers.desc'), icon: 'truck-delivery', route: 'SupplierManagement' },
            { id: 'customers', title: t('sections.businessPartners.customers.title'), desc: t('sections.businessPartners.customers.desc'), icon: 'store', route: 'CustomerManagement' },
            { id: 'shipments', title: t('sections.businessPartners.shipments.title'), desc: t('sections.businessPartners.shipments.desc'), icon: 'truck-fast', route: 'ShipmentManagement' },
        ]
    },
    {
        title: t('sections.factoryConfig.title'),
        icon: 'factory',
        items: [
             { id: 'factory-settings', title: t('sections.factoryConfig.factorySettings.title'), desc: t('sections.factoryConfig.factorySettings.desc'), icon: 'cog', route: 'FactorySettings', adminOnly: true },
             { id: 'intent-config', title: t('sections.factoryConfig.intentConfig.title', { defaultValue: 'AI意图配置' }), desc: t('sections.factoryConfig.intentConfig.desc', { defaultValue: '管理AI识别关键词和规则' }), icon: 'brain', route: 'IntentConfig', adminOnly: true },
        ]
    }
  ];

  const handleNavigate = (route: string) => route && navigation.navigate(route);

  // 数据导出处理
  const handleExport = async (exportType: string) => {
    setExportMenuVisible(false);
    setExporting(true);

    try {
      // TODO: 实现真实的导出API调用
      // 目前显示提示信息
      Alert.alert(
        t('export.title'),
        `${t('export.preparing')} ${getExportTypeName(exportType)} ${t('export.notAvailable')}`,
        [{ text: t('common.confirm') }]
      );

      // 模拟导出延迟
      await new Promise(resolve => setTimeout(resolve, 1000));

      console.log(`导出 ${exportType} 数据`);
    } catch (error) {
      console.error('导出失败:', error);
      Alert.alert(t('export.failed'), t('export.failedMessage'));
    } finally {
      setExporting(false);
    }
  };

  const getExportTypeName = (type: string): string => {
    const names: Record<string, string> = {
      'all': t('export.allConfig'),
      'products': t('export.productTypes'),
      'materials': t('export.materialTypes'),
      'users': t('export.users'),
      'departments': t('export.departments'),
    };
    return names[type] || type;
  };

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <View style={styles.header}>
        <View style={styles.headerContent}>
          <View style={styles.headerTextContainer}>
            <Text style={styles.headerTitle}>{t('title')}</Text>
            <Text style={styles.headerSubtitle}>{t('subtitle')}</Text>
          </View>

          {/* 数据导出按钮 */}
          {isAdmin && (
            <Menu
              visible={exportMenuVisible}
              onDismiss={() => setExportMenuVisible(false)}
              anchor={
                <IconButton
                  icon={exporting ? 'loading' : 'download'}
                  size={24}
                  iconColor={theme.colors.primary}
                  onPress={() => setExportMenuVisible(true)}
                  disabled={exporting}
                  style={styles.exportButton}
                />
              }
            >
              <Menu.Item
                leadingIcon="file-excel"
                onPress={() => handleExport('all')}
                title={t('export.allConfig')}
              />
              <Divider />
              <Menu.Item
                leadingIcon="food"
                onPress={() => handleExport('products')}
                title={t('export.productTypes')}
              />
              <Menu.Item
                leadingIcon="food-drumstick"
                onPress={() => handleExport('materials')}
                title={t('export.materialTypes')}
              />
              <Divider />
              <Menu.Item
                leadingIcon="account-group"
                onPress={() => handleExport('users')}
                title={t('export.users')}
              />
              <Menu.Item
                leadingIcon="office-building"
                onPress={() => handleExport('departments')}
                title={t('export.departments')}
              />
            </Menu>
          )}
        </View>
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {managementSections.map((section, index) => {
          const visibleItems = section.items.filter(item => !item.adminOnly || isAdmin);
          if (visibleItems.length === 0) return null;

          return (
            <NeoCard key={index} style={styles.sectionCard} padding="m">
              <View style={styles.sectionHeader}>
                <Avatar.Icon size={32} icon={section.icon} style={styles.sectionIcon} color={theme.colors.primary} />
                <Text style={styles.sectionTitle}>{section.title}</Text>
              </View>
              
              <View style={styles.grid}>
                  {visibleItems.map((item) => (
                      <TouchableOpacity key={item.id} style={styles.gridItem} onPress={() => handleNavigate(item.route)}>
                          <View style={styles.itemIconContainer}>
                              <Avatar.Icon size={40} icon={item.icon} style={styles.itemIcon} color={theme.colors.primary} />
                          </View>
                          <Text style={styles.itemTitle}>{item.title}</Text>
                          <Text style={styles.itemDesc} numberOfLines={1}>{item.desc}</Text>
                      </TouchableOpacity>
                  ))}
              </View>
            </NeoCard>
          );
        })}

        <NeoCard style={styles.infoCard} padding="m" variant="flat">
            <View style={styles.infoHeader}>
                <Avatar.Icon size={24} icon="information" style={{ backgroundColor: 'transparent' }} color={theme.colors.primary} />
                <Text style={styles.infoTitle}>{t('tips.title')}</Text>
            </View>
            <Text style={styles.infoText}>• {t('tips.configFirst')}</Text>
            <Text style={styles.infoText}>• {t('tips.adminOnly')}</Text>
        </NeoCard>
      </ScrollView>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  header: {
    padding: 24,
    paddingBottom: 16,
    backgroundColor: theme.colors.background,
  },
  headerContent: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  headerTextContainer: {
    flex: 1,
  },
  headerTitle: {
    fontSize: 28,
    fontWeight: '700',
    color: theme.colors.text,
    marginBottom: 4,
  },
  headerSubtitle: {
    fontSize: 16,
    color: theme.colors.textSecondary,
  },
  exportButton: {
    margin: 0,
    marginTop: -8,
  },
  scrollContent: {
    padding: 16,
    paddingTop: 0,
    paddingBottom: 40,
  },
  sectionCard: {
    marginBottom: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  sectionIcon: {
    backgroundColor: theme.colors.surfaceVariant,
    marginRight: 12,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: theme.colors.text,
  },
  grid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 12,
  },
  gridItem: {
      width: '48%', // slightly less than 50% to account for gap
      backgroundColor: theme.colors.surfaceVariant,
      padding: 16,
      borderRadius: 12,
      alignItems: 'center',
  },
  itemIconContainer: {
      marginBottom: 12,
  },
  itemIcon: {
      backgroundColor: 'white',
  },
  itemTitle: {
      fontWeight: '600',
      fontSize: 14,
      marginBottom: 4,
      textAlign: 'center',
      color: theme.colors.text,
  },
  itemDesc: {
      fontSize: 12,
      color: theme.colors.textSecondary,
      textAlign: 'center',
  },
  infoCard: {
      backgroundColor: theme.colors.surfaceVariant,
  },
  infoHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      marginBottom: 8,
  },
  infoTitle: {
      fontWeight: '600',
      marginLeft: 8,
      color: theme.colors.text,
  },
  infoText: {
      fontSize: 13,
      color: theme.colors.textSecondary,
      marginBottom: 4,
      marginLeft: 32,
  },
});
