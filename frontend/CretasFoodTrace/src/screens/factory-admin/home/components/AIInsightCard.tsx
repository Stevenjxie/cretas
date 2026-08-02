/**
 * AIInsightCard - AI insight card with metrics display
 */
import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { ShakingCard } from './ShakingCard';
import type { HomeModule } from '../../../../types/decoration';
import type { AIInsight } from '../types';

interface AIInsightCardProps {
  module: HomeModule;
  index: number;
  isEditMode: boolean;
  isRestaurantMode: boolean;
  aiInsight: AIInsight;
  onLongPress: () => void;
  onToggleVisibility: (moduleId: string) => void;
  onPress?: () => void;
}

export function AIInsightCard({
  module,
  index,
  isEditMode,
  isRestaurantMode,
  aiInsight,
  onLongPress,
  onToggleVisibility,
  onPress,
}: AIInsightCardProps) {
  const { t } = useTranslation('home');

  return (
    <ShakingCard isShaking={isEditMode} delay={index * 50} style={{ margin: 16 }}>
      <TouchableOpacity
        testID="fa-home-ai-card"
        style={styles.aiCard}
        onLongPress={onLongPress}
        onPress={isEditMode ? undefined : onPress}
        delayLongPress={500}
        activeOpacity={isEditMode ? 1 : 0.95}
      >
        {isEditMode && (
          <TouchableOpacity
            style={styles.aiEditBadge}
            onPress={() => onToggleVisibility(module.id)}
          >
            <Icon source="minus-circle" size={22} color="#fff" />
          </TouchableOpacity>
        )}
        <View style={styles.aiHeader}>
          <View style={styles.aiTitleRow}>
            <Icon source="robot" size={20} color="#fff" />
            <Text testID="fa-home-ai-title" style={styles.aiTitle}>
              {isRestaurantMode ? t('ai.restaurantOverview') : t('ai.title', module.name)}
            </Text>
          </View>
          <View
            style={[
              styles.aiStatusBadge,
              aiInsight.status === 'success' ? styles.aiStatusSuccess : styles.aiStatusLoading,
            ]}
          >
            <Text style={styles.aiStatusText}>
              {aiInsight.status === 'success'
                ? t(isRestaurantMode ? 'ai.dataUpdated' : 'ai.analyzed')
                : t('ai.analyzing_status')}
            </Text>
          </View>
        </View>

        <Text style={styles.aiMessage}>{aiInsight.message}</Text>

        {!isRestaurantMode && (
          <View testID="fa-home-ai-metrics" style={styles.aiMetrics}>
            {(!module.config?.metricsToShow ||
              module.config.metricsToShow.includes('qualityRate')) && (
              <>
                <View style={styles.aiMetricItem}>
                  <Text style={styles.aiMetricValue}>
                    {Number(aiInsight.metrics.qualityRate ?? 0).toFixed(1)}%
                  </Text>
                  <Text style={styles.aiMetricLabel}>{t('ai.metrics.qualityRate')}</Text>
                </View>
                <View style={styles.aiMetricDivider} />
              </>
            )}
            {(!module.config?.metricsToShow ||
              module.config.metricsToShow.includes('unitCost')) && (
              <>
                <View style={styles.aiMetricItem}>
                  <Text style={styles.aiMetricValue}>
                    ¥{Number(aiInsight.metrics.unitCost ?? 0).toFixed(1)}
                  </Text>
                  <Text style={styles.aiMetricLabel}>{t('ai.metrics.unitCost')}</Text>
                </View>
                <View style={styles.aiMetricDivider} />
              </>
            )}
            {(!module.config?.metricsToShow ||
              module.config.metricsToShow.includes('avgCycle')) && (
              <View style={styles.aiMetricItem}>
                <Text style={styles.aiMetricValue}>
                  {Number(aiInsight.metrics.avgCycle ?? 0).toFixed(1)}h
                </Text>
                <Text style={styles.aiMetricLabel}>{t('ai.metrics.avgCycle')}</Text>
              </View>
            )}
          </View>
        )}
      </TouchableOpacity>
    </ShakingCard>
  );
}

const styles = StyleSheet.create({
  aiCard: {
    padding: 16,
    borderRadius: 16,
    backgroundColor: '#667eea',
  },
  aiHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  aiTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  aiTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#fff',
    marginLeft: 8,
  },
  aiStatusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  aiStatusSuccess: {
    backgroundColor: 'rgba(255,255,255,0.2)',
  },
  aiStatusLoading: {
    backgroundColor: 'rgba(255,255,255,0.15)',
  },
  aiStatusText: {
    fontSize: 12,
    color: '#fff',
  },
  aiMessage: {
    fontSize: 15,
    color: 'rgba(255,255,255,0.95)',
    lineHeight: 22,
    marginBottom: 16,
  },
  aiMetrics: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255,255,255,0.15)',
    borderRadius: 12,
    paddingVertical: 12,
  },
  aiMetricItem: {
    flex: 1,
    alignItems: 'center',
  },
  aiMetricValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#fff',
  },
  aiMetricLabel: {
    fontSize: 12,
    color: 'rgba(255,255,255,0.8)',
    marginTop: 4,
  },
  aiMetricDivider: {
    width: 1,
    height: '100%',
    backgroundColor: 'rgba(255,255,255,0.2)',
  },
  aiEditBadge: {
    position: 'absolute',
    top: 8,
    right: 8,
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: 'rgba(0,0,0,0.3)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
  },
});
