/**
 * Factory Admin Home Dashboard
 * Composed from extracted hooks and sub-components.
 *
 * Hooks: useDashboardData, useEditMode, useStatCards, useQuickActions
 * Components: WelcomeHeader, AIInsightCard, StatCardGrid,
 *             QuickActionsModule, DevToolsModule, EditToolbar, AddItemSheet
 */
import React, { useEffect, useMemo, useCallback } from 'react';
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
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { FAHomeStackParamList } from '../../../types/navigation';
import { useAuthStore } from '../../../store/authStore';
import { useHomeLayoutStore } from '../../../store/homeLayoutStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { isRestaurant } from '../../../utils/factoryType';
import type { HomeModule, StatCardConfig, QuickActionConfig } from '../../../types/decoration';
import { DEFAULT_HOME_LAYOUT } from '../../../types/decoration';
import { useShallow } from 'zustand/react/shallow';

import { useDashboardData } from './hooks/useDashboardData';
import { useEditMode } from './hooks/useEditMode';
import { useStatCards } from './hooks/useStatCards';
import { useQuickActions } from './hooks/useQuickActions';
import {
  WelcomeHeader,
  AIInsightCard,
  StatCardGrid,
  QuickActionsModule,
  DevToolsModule,
  EditToolbar,
  AddItemSheet,
} from './components';
import { WorkflowWorkdesk } from '../../../components/workflow';
import { getBucketPrimaryStatus } from '../../../types/workflow';
import type { WorkflowModule, WorkflowAIEntryContext } from '../../../types/workflow';

type NavigationProp = NativeStackNavigationProp<FAHomeStackParamList, 'FAHome'>;

const FA_WORKFLOW_MODULES: WorkflowModule[] = [
  'sales',
  'purchase',
  'production',
  'inventory',
  'finance',
];

function navigateToModuleList(
  navigation: NavigationProp,
  module: WorkflowModule,
  bucketId: string,
) {
  // FU Chat 3: bucket (pending/in_progress/done) → 单值 status enum (lossy).
  // 各 List screen filter 是单值, bucket 多状态时此处取 primary; 完整 bucket
  // 需用户在 list 里手动展开 status 下拉切换.
  const statusFilter = getBucketPrimaryStatus(module, bucketId);
  const params = { statusFilter };
  switch (module) {
    case 'sales':
      navigation.navigate('SalesOrderList', params);
      return;
    case 'purchase':
      navigation.navigate('PurchaseOrderList', params);
      return;
    case 'production':
      navigation.navigate('ProductionPlanManagement', params);
      return;
    case 'inventory':
      navigation.navigate('MaterialBatch', params);
      return;
    case 'finance':
      navigation.navigate('FinanceAnalysis', params);
      return;
  }
}

function navigateToAIChat(
  navigation: NavigationProp,
  entryContext: WorkflowAIEntryContext,
) {
  navigation.navigate('AIChat', { entryContext });
}

export function FAHomeScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { user } = useAuthStore();
  const { t } = useTranslation('home');
  const isRestaurantMode = isRestaurant(user);

  // --- Layout store ---
  const { layout, draftModules, storeIsLoading } = useHomeLayoutStore(
    useShallow((state) => ({
      layout: state.layout,
      draftModules: state.draftModules,
      storeIsLoading: state.isLoading,
    })),
  );
  const fetchLayout = useHomeLayoutStore(state => state.fetchLayout);
  const toggleStatCardVisibility = useHomeLayoutStore(state => state.toggleStatCardVisibility);
  const toggleQuickActionVisibility = useHomeLayoutStore(state => state.toggleQuickActionVisibility);
  const addStatCard = useHomeLayoutStore(state => state.addStatCard);
  const addQuickAction = useHomeLayoutStore(state => state.addQuickAction);

  // --- Hooks ---
  const {
    loading,
    refreshing,
    error,
    overviewData,
    alertsData,
    restaurantSummary,
    workReportSummary,
    aiInsight,
    onRefresh,
    loadDashboardData,
  } = useDashboardData(isRestaurantMode);

  const editMode = useEditMode(
    useCallback(() => navigation.navigate('HomeLayoutEditor'), [navigation]),
    draftModules,
  );

  const { getStatCards } = useStatCards({
    isRestaurantMode,
    overviewData,
    alertsData,
    restaurantSummary,
    workReportSummary,
  });

  const { allQuickActions } = useQuickActions(isRestaurantMode);

  // --- Derived layout data ---
  const layoutModules = layout?.modules || DEFAULT_HOME_LAYOUT;

  const visibleModules = useMemo(() => {
    const modules = editMode.isEditMode ? draftModules : layoutModules;
    return modules.filter(m => m.visible).sort((a, b) => a.order - b.order);
  }, [editMode.isEditMode, draftModules, layoutModules]);

  const currentModules = editMode.isEditMode ? draftModules : visibleModules;

  // --- Load layout config ---
  useEffect(() => {
    const factoryId = user?.factoryId;
    if (factoryId && !layout) {
      fetchLayout(factoryId);
    }
  }, [user?.factoryId, layout, fetchLayout]);

  // --- Module name helper ---
  const getModuleName = (moduleType: string) => {
    const names: Record<string, string> = {
      welcome: t('sections.welcome', 'Welcome'),
      ai_insight: t('ai.title'),
      stats_grid: t('sections.todayOverview'),
      quick_actions: t('sections.quickActions'),
      dev_tools: t('sections.devTools'),
    };
    return names[moduleType] || moduleType;
  };

  // --- Add sheet handlers ---
  const handleOpenAddStatCard = useCallback(
    (moduleId: string) => {
      editMode.setEditingModuleId(moduleId);
      editMode.setShowAddStatCardSheet(true);
    },
    [editMode],
  );

  const handleOpenAddQuickAction = useCallback(
    (moduleId: string) => {
      editMode.setEditingModuleId(moduleId);
      editMode.setShowAddQuickActionSheet(true);
    },
    [editMode],
  );

  // --- Build add-sheet items ---
  const addModuleItems = useMemo(
    () =>
      draftModules
        .filter(m => !m.visible)
        .map(m => ({ id: m.id, label: m.name || getModuleName(m.type) })),
    [draftModules, t],
  );

  const addStatCardItems = useMemo(() => {
    if (!editMode.editingModuleId) return [];
    const module = draftModules.find(m => m.id === editMode.editingModuleId);
    const cardsConfig = module?.config?.cards || [];
    const allCards = getStatCards();
    return allCards
      .filter(card => {
        const config = cardsConfig.find((c: StatCardConfig) => c.id === card.id);
        return config && config.visible === false;
      })
      .map(card => ({ id: card.id, label: card.label, icon: card.icon, color: card.color }));
  }, [editMode.editingModuleId, draftModules, getStatCards]);

  const addQuickActionItems = useMemo(() => {
    if (!editMode.editingModuleId) return [];
    const module = draftModules.find(m => m.id === editMode.editingModuleId);
    const actionsConfig = module?.config?.actions || [];
    const visibleConfigIds = actionsConfig
      .filter((a: QuickActionConfig) => a.visible !== false)
      .map((a: QuickActionConfig) => a.id);
    return allQuickActions
      .filter(action => !visibleConfigIds.includes(action.id))
      .map(action => ({ id: action.id, label: action.label, icon: action.icon, color: action.color }));
  }, [editMode.editingModuleId, draftModules, allQuickActions]);

  // --- Module renderer ---
  const renderModuleByType = (module: HomeModule, index: number) => {
    switch (module.type) {
      case 'ai_insight':
        return (
          <AIInsightCard
            key={module.id}
            module={module}
            index={index}
            isEditMode={editMode.isEditMode}
            isRestaurantMode={isRestaurantMode}
            aiInsight={aiInsight}
            onLongPress={editMode.handleLongPressEdit}
            onToggleVisibility={editMode.toggleModuleVisibility}
          />
        );
      case 'stats_grid':
        return (
          <StatCardGrid
            key={module.id}
            module={module}
            index={index}
            isEditMode={editMode.isEditMode}
            allStatCards={getStatCards()}
            onLongPress={editMode.handleLongPressEdit}
            onToggleModuleVisibility={editMode.toggleModuleVisibility}
            onToggleStatCardVisibility={toggleStatCardVisibility}
            onAddStatCard={handleOpenAddStatCard}
          />
        );
      case 'quick_actions':
        return (
          <QuickActionsModule
            key={module.id}
            module={module}
            index={index}
            isEditMode={editMode.isEditMode}
            allQuickActions={allQuickActions}
            onLongPress={editMode.handleLongPressEdit}
            onToggleModuleVisibility={editMode.toggleModuleVisibility}
            onToggleQuickActionVisibility={toggleQuickActionVisibility}
            onAddQuickAction={handleOpenAddQuickAction}
          />
        );
      case 'dev_tools':
        return (
          <DevToolsModule
            key={module.id}
            module={module}
            index={index}
            isEditMode={editMode.isEditMode}
            onToggleModuleVisibility={editMode.toggleModuleVisibility}
          />
        );
      case 'welcome':
        return null;
      default:
        return null;
    }
  };

  // --- Loading state ---
  if (loading) {
    return (
      <SafeAreaView testID="fa-home-root" style={styles.container}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>{t('loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  // --- Error state ---
  if (error && !overviewData && !restaurantSummary) {
    return (
      <SafeAreaView testID="fa-home-root" style={styles.container}>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
          <Text style={[styles.loadingText, { color: '#606266', marginTop: 12 }]}>{error}</Text>
          <TouchableOpacity
            style={styles.retryBtn}
            onPress={() => loadDashboardData()}
          >
            <Text style={styles.retryBtnText}>{t('common.retry', 'Retry')}</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView testID="fa-home-root" style={styles.container}>
      <ScrollView
        style={styles.scrollView}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={['#667eea']}
            tintColor="#667eea"
          />
        }
      >
        {/* Welcome header */}
        <WelcomeHeader
          username={user?.username}
          activeAlerts={alertsData?.summary?.activeAlerts ?? 0}
          onEditLayout={() => navigation.navigate('HomeLayoutEditor')}
        />

        {/* Compact actionable workflow entry for the FA home first screen. */}
        <View style={styles.workflowSection}>
          <WorkflowWorkdesk
            modules={FA_WORKFLOW_MODULES}
            factoryId={user?.factoryId}
            onNodePress={(module, nodeId) =>
              navigateToModuleList(navigation, module, nodeId)
            }
            onAITrigger={(ctx) => navigateToAIChat(navigation, ctx)}
          />
        </View>

        {/* Error banner */}
        {error && (
          <View style={styles.errorBanner}>
            <Icon source="alert-circle" size={20} color="#e53e3e" />
            <Text style={styles.errorText}>{error}</Text>
          </View>
        )}

        {/* Dynamic module list */}
        {currentModules
          .filter(m => m.visible)
          .sort((a, b) => a.order - b.order)
          .map((module, index) => renderModuleByType(module, index))}

        {/* Bottom spacer */}
        <View style={{ height: editMode.isEditMode ? 100 : 32 }} />
      </ScrollView>

      {/* Edit mode floating toolbar */}
      {editMode.isEditMode && (
        <EditToolbar
          onCancel={editMode.exitEditMode}
          onAddModule={editMode.handleAddModule}
          onSave={() => editMode.handleSaveLayout(user?.factoryId)}
          onAdvancedEdit={editMode.handleAdvancedEdit}
        />
      )}

      {/* Add module sheet */}
      {editMode.showAddModuleSheet && (
        <AddItemSheet
          title={t('layout.availableModules')}
          items={addModuleItems}
          onSelect={(id) => {
            editMode.toggleModuleVisibility(id);
            editMode.setShowAddModuleSheet(false);
          }}
          onClose={() => editMode.setShowAddModuleSheet(false)}
        />
      )}

      {/* Add stat card sheet */}
      {editMode.showAddStatCardSheet && editMode.editingModuleId && (
        <AddItemSheet
          title={t('layout.addStatCard', 'Add stat card')}
          items={addStatCardItems}
          onSelect={(cardId) => {
            addStatCard(editMode.editingModuleId!, cardId);
            editMode.setShowAddStatCardSheet(false);
            editMode.setEditingModuleId(null);
          }}
          onClose={() => {
            editMode.setShowAddStatCardSheet(false);
            editMode.setEditingModuleId(null);
          }}
        />
      )}

      {/* Add quick action sheet */}
      {editMode.showAddQuickActionSheet && editMode.editingModuleId && (
        <AddItemSheet
          title={t('layout.addQuickAction', 'Add quick action')}
          items={addQuickActionItems}
          onSelect={(actionId) => {
            addQuickAction(editMode.editingModuleId!, actionId);
            editMode.setShowAddQuickActionSheet(false);
            editMode.setEditingModuleId(null);
          }}
          onClose={() => {
            editMode.setShowAddQuickActionSheet(false);
            editMode.setEditingModuleId(null);
          }}
        />
      )}
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
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  retryBtn: {
    marginTop: 16,
    paddingHorizontal: 20,
    paddingVertical: 8,
    backgroundColor: '#667eea',
    borderRadius: 6,
  },
  retryBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '500',
  },
  workflowSection: {
    marginHorizontal: 16,
    marginTop: 12,
  },
  errorBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fed7d7',
    marginHorizontal: 16,
    marginTop: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 8,
  },
  errorText: {
    marginLeft: 8,
    fontSize: 14,
    color: '#c53030',
  },
});

export default FAHomeScreen;
