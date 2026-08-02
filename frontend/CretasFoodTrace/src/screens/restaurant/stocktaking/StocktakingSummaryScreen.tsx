/**
 * 盘点汇总 — 最近盘点差异统计
 */
import React, { useState, useEffect } from 'react';
import { View, ScrollView, StyleSheet, RefreshControl } from 'react-native';
import { Text, Surface, Button } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { handleError } from '../../../utils/errorHandler';
import { formatShortDateTime } from '../../../utils/formatters';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

interface SummaryData {
  totalItems: number;
  surplusCount: number;
  shortageCount: number;
  matchCount: number;
  recentRecords: Array<{
    id: string;
    stocktakingNumber: string;
    rawMaterialTypeName: string;
    rawMaterialTypeId?: string;
    differenceType: string;
    differenceQuantity: number;
    completedAt: string;
  }>;
}

export function StocktakingSummaryScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [summary, setSummary] = useState<SummaryData | null>(null);
  const { materialNames } = useRestaurantReferenceNames();

  async function loadData() {
    try {
      const data = await restaurantApiClient.getStocktakingSummary();
      const summaryArr: Array<{ differenceType: string; count: number }> = Array.isArray(data?.summary) ? data.summary : [];
      const getCount = (type: string) => summaryArr.find(r => r.differenceType === type)?.count ?? 0;
      const surplusCount = getCount('SURPLUS');
      const shortageCount = getCount('SHORTAGE');
      const matchCount = getCount('MATCH');
      setSummary({
        totalItems: surplusCount + shortageCount + matchCount,
        surplusCount,
        shortageCount,
        matchCount,
        recentRecords: (data?.recentRecords ?? []).map((r: any) => ({
          id: r.id,
          stocktakingNumber: r.stocktakingNumber ?? '',
          rawMaterialTypeName: r.rawMaterialTypeName ?? '',
          rawMaterialTypeId: r.rawMaterialTypeId,
          differenceType: r.differenceType ?? 'MATCH',
          differenceQuantity: r.differenceQuantity ?? 0,
          completedAt: r.completedAt ?? r.stocktakingDate ?? '',
        })),
      });
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => { loadData(); }, []);

  const cards = summary ? [
    { label: t('stocktaking.summary.totalItems'), value: summary.totalItems, icon: 'clipboard-list-outline' as const, color: '#1976d2' },
    { label: t('stocktaking.summary.surplus'), value: summary.surplusCount, icon: 'arrow-up-bold' as const, color: '#388e3c' },
    { label: t('stocktaking.summary.shortage'), value: summary.shortageCount, icon: 'arrow-down-bold' as const, color: '#d32f2f' },
    { label: t('stocktaking.summary.matched'), value: summary.matchCount, icon: 'check-circle-outline' as const, color: '#757575' },
  ] : [];

  const diffColor = (type: string) => type === 'SURPLUS' ? '#388e3c' : type === 'SHORTAGE' ? '#d32f2f' : '#757575';

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{t('stocktaking.summary.title')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); loadData(); }} />}
      >
        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : (
          <>
            <View style={styles.statsGrid}>
              {cards.map(c => (
                <Surface key={c.label} style={styles.statCard} elevation={1}>
                  <MaterialCommunityIcons name={c.icon} size={28} color={c.color} />
                  <Text style={[styles.statValue, { color: c.color }]}>{c.value}</Text>
                  <Text style={styles.statLabel}>{c.label}</Text>
                </Surface>
              ))}
            </View>

            <Text style={styles.sectionTitle}>{t('stocktaking.summary.recentRecords')}</Text>
            {(summary?.recentRecords ?? []).length === 0 ? (
              <View style={styles.center}>
                <Text style={styles.emptyText}>{t('stocktaking.list.empty')}</Text>
              </View>
            ) : (
              summary!.recentRecords.map(r => (
                <Surface key={r.id} style={styles.recordCard} elevation={1}>
                  <View style={styles.recordRow}>
                    <Text style={styles.recordMaterial}>{resolveRestaurantReferenceName(
                      r.rawMaterialTypeName,
                      r.rawMaterialTypeId,
                      materialNames,
                      t('common.materialNameUnavailable'),
                    )}</Text>
                    <Text style={[styles.recordDiff, { color: diffColor(r.differenceType) }]}>
                      {t(`stocktaking.difference.${r.differenceType}`)}
                      {r.differenceQuantity != null ? ` (${r.differenceQuantity > 0 ? '+' : ''}${r.differenceQuantity})` : ''}
                    </Text>
                  </View>
                  <Text style={styles.recordDate}>{r.stocktakingNumber} | {r.completedAt ? formatShortDateTime(r.completedAt) : ''}</Text>
                </Surface>
              ))
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FFAB00', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  statsGrid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', marginBottom: 20 },
  statCard: { width: '48%', backgroundColor: '#fff', borderRadius: 12, padding: 16, alignItems: 'center', marginBottom: 12 },
  statValue: { fontSize: 28, fontWeight: '700', marginTop: 6 },
  statLabel: { fontSize: 13, color: '#999', marginTop: 2 },
  sectionTitle: { fontSize: 16, fontWeight: '600', color: '#333', marginBottom: 10 },
  recordCard: { backgroundColor: '#fff', borderRadius: 10, padding: 12, marginBottom: 8 },
  recordRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 },
  recordMaterial: { fontSize: 14, fontWeight: '500', color: '#333' },
  recordDiff: { fontSize: 14, fontWeight: '600' },
  recordDate: { fontSize: 12, color: '#bbb' },
  center: { alignItems: 'center', paddingTop: 40 },
  emptyText: { fontSize: 14, color: '#999' },
});

export default StocktakingSummaryScreen;
