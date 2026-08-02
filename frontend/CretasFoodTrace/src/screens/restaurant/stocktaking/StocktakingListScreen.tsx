/**
 * 盘点列表 — 查看所有盘点记录
 */
import React, { useState, useCallback, useEffect } from 'react';
import { View, ScrollView, StyleSheet, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Surface, Chip, Searchbar, FAB, Button } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RStocktakingStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { StocktakingRecord, StocktakingStatus } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { formatShortDateTime } from '../../../utils/formatters';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Nav = NativeStackNavigationProp<RStocktakingStackParamList>;

const STATUS_COLORS: Record<StocktakingStatus, { color: string; bg: string }> = {
  IN_PROGRESS: { color: '#1976d2', bg: '#e3f2fd' },
  COMPLETED: { color: '#388e3c', bg: '#e8f5e9' },
  CANCELLED: { color: '#757575', bg: '#f5f5f5' },
};

const DIFF_COLORS: Record<string, string> = {
  SURPLUS: '#388e3c',
  SHORTAGE: '#d32f2f',
  MATCH: '#757575',
};

export function StocktakingListScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | StocktakingStatus>('all');
  const [records, setRecords] = useState<StocktakingRecord[]>([]);
  const [loadError, setLoadError] = useState(false);
  const { materialNames } = useRestaurantReferenceNames();

  const loadData = useCallback(async () => {
    try {
      setLoadError(false);
      const status = statusFilter === 'all' ? undefined : statusFilter;
      const { data } = await restaurantApiClient.getStocktakingRecords({ page: 1, size: 50, status });
      setRecords(data);
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
      setLoadError(true);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [statusFilter, t]);

  useEffect(() => { loadData(); }, [loadData]);

  const filtered = records.filter(r => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return resolveRestaurantReferenceName(r.rawMaterialTypeName, r.rawMaterialTypeId, materialNames, '')
             .toLowerCase().includes(q) ||
           (r.stocktakingNumber ?? '').toLowerCase().includes(q);
  });

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <View style={styles.headerRow}>
          <View>
            <Text style={styles.headerTitle}>{t('stocktaking.list.title')}</Text>
            <Text style={styles.headerSub}>{records.length} {t('common.items')}</Text>
          </View>
          <Button icon="chart-bar" textColor="#fff" onPress={() => navigation.navigate('StocktakingSummary')}>
            {t('stocktaking.summary.title')}
          </Button>
        </View>
      </View>

      <ScrollView refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); loadData(); }} />}>
        <View style={styles.searchRow}>
          <Searchbar placeholder={t('stocktaking.list.searchPlaceholder')} value={searchQuery} onChangeText={setSearchQuery} style={styles.searchbar} />
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
          {(['all', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const).map(s => (
            <Chip key={s} selected={statusFilter === s} onPress={() => setStatusFilter(s)} style={styles.chip}>
              {s === 'all' ? t('common.all') : t(`stocktaking.status.${s}`)}
            </Chip>
          ))}
        </ScrollView>

        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : loadError ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#EF4444" />
            <Text style={styles.emptyText}>{t('common.loadFailed')}</Text>
            <TouchableOpacity onPress={() => { setLoading(true); loadData(); }} style={{ marginTop: 12, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#FFAB00', borderRadius: 8 }}>
              <Text style={{ color: '#fff', fontWeight: '600' }}>{t('common.refresh')}</Text>
            </TouchableOpacity>
          </View>
        ) : filtered.length === 0 ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="clipboard-check-outline" size={48} color="#C0C4CC" />
            <Text style={styles.emptyText}>{t('stocktaking.list.empty')}</Text>
          </View>
        ) : (
          <View style={styles.list}>
            {filtered.map(r => {
              const sc = STATUS_COLORS[r.status] || STATUS_COLORS.IN_PROGRESS;
              const canExecute = r.status === 'IN_PROGRESS';
              return (
                <TouchableOpacity
                  key={r.id}
                  onPress={() => canExecute
                    ? navigation.navigate('StocktakingExecute', { recordId: r.id })
                    : undefined}
                  activeOpacity={canExecute ? 0.7 : 1}
                >
                  <Surface style={styles.card} elevation={1}>
                    <View style={styles.cardHeader}>
                      <MaterialCommunityIcons name="clipboard-text-outline" size={20} color="#FFAB00" />
                      <Text style={styles.stkNumber}>{r.stocktakingNumber}</Text>
                      <View style={[styles.badge, { backgroundColor: sc.bg }]}>
                        <Text style={{ color: sc.color, fontSize: 12 }}>{t(`stocktaking.status.${r.status}`)}</Text>
                      </View>
                    </View>
                    <View style={styles.cardBody}>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>{t('stocktaking.list.material')}</Text>
                        <Text style={styles.infoValue}>{resolveRestaurantReferenceName(
                          r.rawMaterialTypeName,
                          r.rawMaterialTypeId,
                          materialNames,
                          t('common.materialNameUnavailable'),
                        )}</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>{t('stocktaking.list.systemQty')}</Text>
                        <Text style={styles.infoValue}>{r.systemQuantity} {r.unit}</Text>
                      </View>
                      {r.actualQuantity != null && (
                        <View style={styles.infoRow}>
                          <Text style={styles.infoLabel}>{t('stocktaking.list.actualQty')}</Text>
                          <Text style={styles.infoValue}>{r.actualQuantity} {r.unit}</Text>
                        </View>
                      )}
                      {r.differenceType && (
                        <View style={styles.infoRow}>
                          <Text style={styles.infoLabel}>{t('stocktaking.list.difference')}</Text>
                          <Text style={[styles.infoValue, { color: DIFF_COLORS[r.differenceType] || '#333' }]}>
                            {t(`stocktaking.difference.${r.differenceType}`)} {r.differenceQuantity != null ? `(${r.differenceQuantity > 0 ? '+' : ''}${r.differenceQuantity})` : ''}
                          </Text>
                        </View>
                      )}
                    </View>
                    <Text style={styles.dateText}>{r.stocktakingDate ? formatShortDateTime(r.stocktakingDate) : ''}</Text>
                  </Surface>
                </TouchableOpacity>
              );
            })}
          </View>
        )}
      </ScrollView>

      <FAB testID="stocktaking-create-fab" icon="plus" style={styles.fab} label={t('stocktaking.list.emptyAction')} onPress={() => navigation.navigate('StocktakingExecute', {})} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FFAB00', paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16 },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSub: { fontSize: 13, color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  searchRow: { paddingHorizontal: 16, paddingTop: 12 },
  searchbar: { backgroundColor: '#fff', borderRadius: 8 },
  chips: { paddingHorizontal: 16, paddingVertical: 8, flexGrow: 0 },
  chip: { marginRight: 8 },
  list: { paddingHorizontal: 16, paddingBottom: 80 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, marginBottom: 12 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  stkNumber: { fontSize: 14, fontWeight: '600', marginLeft: 8, flex: 1, color: '#333' },
  badge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  cardBody: {},
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  infoLabel: { fontSize: 13, color: '#999' },
  infoValue: { fontSize: 13, fontWeight: '500', color: '#333' },
  dateText: { fontSize: 12, color: '#bbb', marginTop: 6, textAlign: 'right' },
  center: { alignItems: 'center', paddingTop: 60 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
  fab: { position: 'absolute', right: 16, bottom: 24, backgroundColor: '#FFAB00' },
});

export default StocktakingListScreen;
