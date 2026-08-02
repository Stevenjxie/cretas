/**
 * AI 分析中心
 * 包含: 成本分析、数据报表、AI对话、质检分析、新建计划
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { FAAIStackParamList } from '../../../types/navigation';
import { aiApiClient, AIQuotaInfo } from '../../../services/api/aiApiClient';
import { useAuthStore } from '../../../store/authStore';
import { isRestaurant } from '../../../utils/factoryType';

type NavigationProp = NativeStackNavigationProp<FAAIStackParamList, 'AIAnalysisCenter'>;

interface MenuItemProps {
  icon: string;
  title: string;
  subtitle: string;
  color: string;
  onPress: () => void;
}

function MenuItem({ icon, title, subtitle, color, onPress }: MenuItemProps) {
  return (
    <TouchableOpacity style={styles.menuItem} onPress={onPress} activeOpacity={0.7}>
      <View style={[styles.menuIcon, { backgroundColor: color + '20' }]}>
        <Icon source={icon} size={24} color={color} />
      </View>
      <View style={styles.menuText}>
        <Text style={styles.menuTitle}>{title}</Text>
        <Text style={styles.menuSubtitle}>{subtitle}</Text>
      </View>
      <Icon source="chevron-right" size={24} color="#ccc" />
    </TouchableOpacity>
  );
}

export function AIAnalysisCenterScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t, i18n } = useTranslation('home');
  const { user } = useAuthStore();
  const isRestaurantMode = isRestaurant(user);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [quotaInfo, setQuotaInfo] = useState<AIQuotaInfo | null>(null);

  const loadQuota = useCallback(async () => {
    try {
      const quota = await aiApiClient.getQuotaInfo();
      setQuotaInfo(quota);
    } catch (err) {
      console.error(t('aiAnalysis.loadQuotaFailed') || 'Load quota failed:', err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [t]);

  useEffect(() => {
    loadQuota();
  }, [loadQuota]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadQuota();
  }, [loadQuota]);

  const getQuotaStatusColor = (status?: string): string => {
    switch (status) {
      case 'active': return '#48bb78';
      case 'warning': return '#ed8936';
      case 'exhausted': return '#e53e3e';
      default: return '#667eea';
    }
  };

  const askRestaurantAI = (message: string) => navigation.navigate('AIChat', { initialMessage: message });

  const quickItems = isRestaurantMode ? [
    { icon: 'cash-register', color: '#E85D04', label: t('aiAnalysis.restaurantToday'), onPress: () => askRestaurantAI('对比今天与昨天的营收、订单量和客单价，说明变化、数据依据和需要关注的问题') },
    { icon: 'store-search', color: '#2563EB', label: t('aiAnalysis.restaurantStores'), onPress: () => askRestaurantAI('比较最近7天各门店的营收、订单量和客单价，找出异常门店并说明依据') },
    { icon: 'food', color: '#16A34A', label: t('aiAnalysis.restaurantDishes'), onPress: () => askRestaurantAI('分析最近7天菜品销量和毛利表现，指出最值得关注的菜品并给出可执行建议') },
    { icon: 'chart-donut', color: '#DC2626', label: t('aiAnalysis.restaurantWastage'), onPress: () => askRestaurantAI('分析本月食材损耗，按食材和门店定位主要问题，并给出今天可以执行的改进动作') },
  ] : [
    { icon: 'chart-line', color: '#667eea', label: t('aiAnalysis.costTrend'), onPress: () => navigation.navigate('AICostAnalysis') },
    { icon: 'clipboard-check-outline', color: '#1890ff', label: t('aiAnalysis.qualityAnalysis'), onPress: () => navigation.navigate('QualityAnalysis') },
    { icon: 'robot', color: '#fa8c16', label: t('aiAnalysis.aiChat'), onPress: () => navigation.navigate('AIChat') },
    { icon: 'calendar-plus', color: '#eb2f96', label: t('aiAnalysis.createPlan'), onPress: () => navigation.navigate('CreatePlan') },
  ];

  const featureItems: MenuItemProps[] = isRestaurantMode ? [
    { icon: 'robot', title: t('aiAnalysis.restaurantChat'), subtitle: t('aiAnalysis.restaurantChatDesc'), color: '#E85D04', onPress: () => navigation.navigate('AIChat') },
    { icon: 'store-marker', title: t('aiAnalysis.restaurantStoreFeature'), subtitle: t('aiAnalysis.restaurantStoreFeatureDesc'), color: '#2563EB', onPress: () => askRestaurantAI('比较最近7天各门店经营表现，列出异常门店、数据依据和建议动作') },
    { icon: 'food-variant', title: t('aiAnalysis.restaurantDishFeature'), subtitle: t('aiAnalysis.restaurantDishFeatureDesc'), color: '#16A34A', onPress: () => askRestaurantAI('分析最近7天菜品销量、营收和毛利，指出机会菜品与风险菜品') },
    { icon: 'package-variant', title: t('aiAnalysis.restaurantInventoryFeature'), subtitle: t('aiAnalysis.restaurantInventoryFeatureDesc'), color: '#7C3AED', onPress: () => askRestaurantAI('分析当前食材库存风险，列出可能缺货或积压的食材、影响和处理建议') },
  ] : [
    { icon: 'chart-line', title: t('aiAnalysis.costAnalysis'), subtitle: t('aiAnalysis.costAnalysisDesc'), color: '#667eea', onPress: () => navigation.navigate('AICostAnalysis') },
    { icon: 'file-document-outline', title: t('aiAnalysis.dataReport'), subtitle: t('aiAnalysis.dataReportDesc'), color: '#52c41a', onPress: () => navigation.navigate('AIReport') },
    { icon: 'robot', title: t('aiAnalysis.aiChat'), subtitle: t('aiAnalysis.aiChatDesc'), color: '#fa8c16', onPress: () => navigation.navigate('AIChat') },
    { icon: 'clipboard-check-outline', title: t('aiAnalysis.qualityAnalysis'), subtitle: t('aiAnalysis.qualityAnalysisDesc'), color: '#1890ff', onPress: () => navigation.navigate('QualityAnalysis') },
    { icon: 'calendar-plus', title: t('aiAnalysis.createPlan'), subtitle: t('aiAnalysis.createPlanDesc'), color: '#eb2f96', onPress: () => navigation.navigate('CreatePlan') },
    { icon: 'lightbulb-on-outline', title: t('aiAnalysis.intentSuggestions') || '意图优化建议', subtitle: t('aiAnalysis.intentSuggestionsDesc') || '审批AI学习的新意图和关键词', color: '#13c2c2', onPress: () => navigation.navigate('IntentSuggestionsList') },
  ];

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView
        style={styles.scrollView}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
      >
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>{t(isRestaurantMode ? 'aiAnalysis.restaurantTitle' : 'aiAnalysis.title')}</Text>
          <Text style={styles.subtitle}>{t(isRestaurantMode ? 'aiAnalysis.restaurantSubtitle' : 'aiAnalysis.subtitle')}</Text>
        </View>

        {/* AI 配额卡片 */}
        <View style={styles.quotaCard}>
          <View style={styles.quotaHeader}>
            <Icon source="robot" size={24} color="#667eea" />
            <Text style={styles.quotaTitle}>{t('aiAnalysis.quota')}</Text>
          </View>
          {loading ? (
            <ActivityIndicator size="small" color="#667eea" style={{ marginVertical: 16 }} />
          ) : quotaInfo ? (
            <>
              <View style={styles.quotaProgress}>
                <View style={styles.quotaBar}>
                  <View
                    style={[
                      styles.quotaFill,
                      {
                        width: `${quotaInfo.usagePercentage}%`,
                        backgroundColor: getQuotaStatusColor(quotaInfo.status),
                      },
                    ]}
                  />
                </View>
                <Text style={styles.quotaText}>
                  {quotaInfo.remainingQuota} / {quotaInfo.weeklyQuota} {t('aiAnalysis.quotaRemaining')}
                </Text>
              </View>
              <Text style={styles.quotaReset}>
                {t('aiAnalysis.resetTime')}: {quotaInfo.resetDate ? new Date(quotaInfo.resetDate).toLocaleDateString(i18n.language) : t('aiAnalysis.nextMonday')}
              </Text>
            </>
          ) : (
            <Text style={styles.quotaError}>{t('aiAnalysis.quotaLoadFailed')}</Text>
          )}
        </View>

        {/* 快捷分析 */}
        <View style={styles.quickActions}>
          <Text style={styles.sectionTitle}>{t('aiAnalysis.quickAnalysis')}</Text>
          <View style={styles.quickGrid}>
            {quickItems.map(item => (
              <TouchableOpacity key={item.label} style={styles.quickItem} onPress={item.onPress}>
                <View style={[styles.quickIcon, { backgroundColor: item.color + '20' }]}>
                  <Icon source={item.icon} size={28} color={item.color} />
                </View>
                <Text style={styles.quickLabel}>{item.label}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* 功能菜单 */}
        <View style={styles.menuSection}>
          <Text style={styles.sectionTitle}>{t('aiAnalysis.allFeatures')}</Text>
          <View style={styles.menuCard}>
            {featureItems.map(item => <MenuItem key={item.title} {...item} />)}
          </View>
        </View>

        <View style={{ height: 32 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 20,
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#1a202c',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
  },
  quotaCard: {
    margin: 16,
    padding: 16,
    backgroundColor: '#fff',
    borderRadius: 12,
  },
  quotaHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  quotaTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginLeft: 8,
  },
  quotaProgress: {
    marginBottom: 8,
  },
  quotaBar: {
    height: 8,
    backgroundColor: '#e2e8f0',
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 8,
  },
  quotaFill: {
    height: '100%',
    borderRadius: 4,
  },
  quotaText: {
    fontSize: 14,
    color: '#666',
  },
  quotaReset: {
    fontSize: 12,
    color: '#999',
  },
  quotaError: {
    fontSize: 14,
    color: '#e53e3e',
    marginVertical: 8,
  },
  quickActions: {
    marginHorizontal: 16,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 12,
  },
  quickGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  quickItem: {
    width: '47%',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
  },
  quickIcon: {
    width: 56,
    height: 56,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
  },
  quickLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#1a202c',
  },
  menuSection: {
    marginHorizontal: 16,
    marginTop: 20,
  },
  menuCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    overflow: 'hidden',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  menuIcon: {
    width: 44,
    height: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  menuText: {
    flex: 1,
    marginLeft: 12,
  },
  menuTitle: {
    fontSize: 16,
    fontWeight: '500',
    color: '#1a202c',
  },
  menuSubtitle: {
    fontSize: 13,
    color: '#999',
    marginTop: 2,
  },
});

export default AIAnalysisCenterScreen;
