/**
 * QuickActionsModule - Quick action buttons grid with edit-mode support
 */
import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { ShakingCard } from './ShakingCard';
import type { HomeModule, QuickActionConfig } from '../../../../types/decoration';
import type { QuickActionItem } from '../types';

interface QuickActionsModuleProps {
  module: HomeModule;
  index: number;
  isEditMode: boolean;
  allQuickActions: QuickActionItem[];
  onLongPress: () => void;
  onToggleModuleVisibility: (moduleId: string) => void;
  onToggleQuickActionVisibility: (moduleId: string, actionId: string) => void;
  onAddQuickAction: (moduleId: string) => void;
}

export function QuickActionsModule({
  module,
  index,
  isEditMode,
  allQuickActions,
  onLongPress,
  onToggleModuleVisibility,
  onToggleQuickActionVisibility,
  onAddQuickAction,
}: QuickActionsModuleProps) {
  const { t } = useTranslation('home');

  const actionsConfig = module.config?.actions || [];
  const maxItems = module.config?.maxItems || 4;

  const visibleConfigIds = actionsConfig
    .filter((a: QuickActionConfig) => a.visible !== false)
    .map((a: QuickActionConfig) => a.id);

  let displayActions: QuickActionItem[];
  if (visibleConfigIds.length > 0) {
    const configuredActions = visibleConfigIds
      .map(id => allQuickActions.find(a => a.id === id))
      .filter(Boolean) as QuickActionItem[];
    // Persisted layouts may contain factory-only action IDs. Restaurant mode
    // must fall back to its own action set instead of rendering an empty grid.
    displayActions = configuredActions.length > 0
      ? configuredActions
      : allQuickActions.slice(0, maxItems);
  } else {
    displayActions = allQuickActions.slice(0, maxItems);
  }

  const hiddenActions = allQuickActions.filter(
    action => !displayActions.some(da => da.id === action.id),
  );

  const finalActions = displayActions.slice(0, maxItems);

  return (
    <ShakingCard isShaking={isEditMode} delay={index * 50} style={styles.quickActionsSection}>
      {isEditMode && (
        <TouchableOpacity
          style={styles.sectionEditBadge}
          onPress={() => onToggleModuleVisibility(module.id)}
        >
          <Icon source="minus-circle" size={20} color="#e53e3e" />
        </TouchableOpacity>
      )}

      <View style={styles.sectionHeader}>
        <Text testID="fa-home-quick-actions-title" style={styles.sectionTitle}>
          {t('sections.quickActions', module.name)}
        </Text>
        {isEditMode && hiddenActions.length > 0 && (
          <TouchableOpacity
            style={styles.addItemBtn}
            onPress={() => onAddQuickAction(module.id)}
          >
            <Icon source="plus" size={16} color="#667eea" />
            <Text style={styles.addItemBtnText}>{t('layout.add', 'Add')}</Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.quickActionsGrid}>
        {finalActions.map(action => (
          <TouchableOpacity
            key={action.id}
            style={styles.quickAction}
            onPress={isEditMode ? undefined : action.onPress}
            onLongPress={onLongPress}
            delayLongPress={500}
            activeOpacity={isEditMode ? 1 : 0.7}
          >
            {isEditMode && (
              <TouchableOpacity
                style={styles.actionDeleteBadge}
                onPress={() => onToggleQuickActionVisibility(module.id, action.id)}
              >
                <Icon source="minus-circle" size={16} color="#e53e3e" />
              </TouchableOpacity>
            )}
            <View style={[styles.quickActionIcon, { backgroundColor: `${action.color}15` }]}>
              <Icon source={action.icon} size={28} color={action.color} />
            </View>
            <Text style={styles.quickActionLabel}>{action.label}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </ShakingCard>
  );
}

const styles = StyleSheet.create({
  quickActionsSection: {
    paddingHorizontal: 16,
    marginTop: 24,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 12,
  },
  quickActionsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  quickAction: {
    width: '23%',
    alignItems: 'center',
  },
  quickActionIcon: {
    width: 56,
    height: 56,
    borderRadius: 16,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  quickActionLabel: {
    fontSize: 14,
    color: '#4a5568',
    textAlign: 'center',
  },
  sectionEditBadge: {
    position: 'absolute',
    top: -8,
    right: 8,
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.15,
    shadowRadius: 4,
    elevation: 3,
  },
  addItemBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 6,
    backgroundColor: '#667eea15',
    borderRadius: 16,
    gap: 4,
  },
  addItemBtnText: {
    fontSize: 13,
    color: '#667eea',
    fontWeight: '500',
  },
  actionDeleteBadge: {
    position: 'absolute',
    top: -4,
    right: -4,
    backgroundColor: '#fff',
    borderRadius: 10,
    zIndex: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.2,
    shadowRadius: 2,
    elevation: 3,
  },
});
