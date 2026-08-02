/**
 * 损耗列表 — 查看所有损耗记录
 */
import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, ScrollView, StyleSheet, RefreshControl, TouchableOpacity, Alert } from 'react-native';
import { Text, Surface, Chip, Searchbar, FAB } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RWastageStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { WastageRecord, WastageStatus } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { formatShortDateTime } from '../../../utils/formatters';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Nav = NativeStackNavigationProp<RWastageStackParamList>;

const STATUS_COLORS: Record<WastageStatus, { color: string; bg: string }> = {
  DRAFT: { color: '#757575', bg: '#f5f5f5' },
  SUBMITTED: { color: '#f57c00', bg: '#fff3e0' },
  APPROVED: { color: '#388e3c', bg: '#e8f5e9' },
  REJECTED: { color: '#d32f2f', bg: '#ffebee' },
};

const TYPE_ICONS: Record<string, string> = {
  EXPIRED: 'clock-alert-outline',
  DAMAGED: 'package-variant-remove',
  SPOILED: 'bacteria-outline',
  PROCESSING_LOSS: 'cog-outline',
  PROCESSING: 'cog-outline',
  OTHER: 'help-circle-outline',
};

export function WastageListScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | WastageStatus>('all');
  const [records, setRecords] = useState<WastageRecord[]>([]);
  const [loadError, setLoadError] = useState(false);
  const { materialNames } = useRestaurantReferenceNames();
  const [selectedRecord, setSelectedRecord] = useState<WastageRecord | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => Alert.alert('查看详情', `损耗 ${e.id}`),
    // 损耗单复用 material-requisition 模板 (warehouse:read / procurement:read 准入)
    'print-pdf': (e: RowContext) => { void safePrint('material-requisition', e.id); },
  }), []);

  const sheetCtx: RowContext = selectedRecord
    ? { status: selectedRecord.status, id: selectedRecord.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('wastage', sheetCtx, { handlers });

  const openSheet = (record: WastageRecord) => { setSelectedRecord(record); setActionSheetVisible(true); };

  const loadData = useCallback(async () => {
    try {
      setLoadError(false);
      const status = statusFilter === 'all' ? undefined : statusFilter;
      const { data } = await restaurantApiClient.getWastageRecords({ page: 1, size: 50, status });
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

  // U-FOOTER-1
  const summaryRequest = useMemo(() => ({ filterConditions: {} }), []);
  const { summary, refresh: refreshSummary } = useListSummary('wastage', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing, statusFilter]);

  const filtered = records.filter(r => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return resolveRestaurantReferenceName(r.rawMaterialTypeName, r.rawMaterialTypeId, materialNames, '')
             .toLowerCase().includes(q) ||
           (r.wastageNumber ?? '').toLowerCase().includes(q);
  });

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>{t('wastage.list.title')}</Text>
        <Text style={styles.headerSub}>{records.length} {t('common.items')}</Text>
      </View>

      <ScrollView refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); loadData(); }} />}>
        <View style={styles.searchRow}>
          <Searchbar placeholder={t('wastage.list.searchPlaceholder')} value={searchQuery} onChangeText={setSearchQuery} style={styles.searchbar} />
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
          {(['all', 'DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'] as const).map(s => (
            <Chip key={s} selected={statusFilter === s} onPress={() => setStatusFilter(s)} style={styles.chip}>
              {s === 'all' ? t('common.all') : t(`wastage.status.${s}`)}
            </Chip>
          ))}
        </ScrollView>

        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : loadError ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#EF4444" />
            <Text style={styles.emptyText}>{t('common.loadFailed')}</Text>
            <TouchableOpacity onPress={() => { setLoading(true); loadData(); }} style={{ marginTop: 12, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#FF5630', borderRadius: 8 }}>
              <Text style={{ color: '#fff', fontWeight: '600' }}>{t('common.refresh')}</Text>
            </TouchableOpacity>
          </View>
        ) : filtered.length === 0 ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="trash-can-outline" size={48} color="#C0C4CC" />
            <Text style={styles.emptyText}>{t('wastage.list.empty')}</Text>
          </View>
        ) : (
          <View style={styles.list}>
            {filtered.map(r => {
              const sc = STATUS_COLORS[r.status] || STATUS_COLORS.DRAFT;
              return (
                <TouchableOpacity key={r.id} activeOpacity={0.85} onLongPress={() => openSheet(r)}>
                <Surface style={styles.card} elevation={1}>
                  <View style={styles.cardHeader}>
                    <MaterialCommunityIcons name={(TYPE_ICONS[r.type] || 'help-circle-outline') as any} size={20} color="#FF5630" />
                    <Text style={styles.wastageNumber}>{r.wastageNumber}</Text>
                    <View style={[styles.badge, { backgroundColor: sc.bg }]}>
                      <Text style={{ color: sc.color, fontSize: 12 }}>{t(`wastage.status.${r.status}`)}</Text>
                    </View>
                  </View>
                  <View style={styles.cardBody}>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('wastage.list.material')}</Text>
                      <Text style={styles.infoValue}>{resolveRestaurantReferenceName(
                        r.rawMaterialTypeName,
                        r.rawMaterialTypeId,
                        materialNames,
                        t('common.materialNameUnavailable'),
                      )}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('wastage.create.type')}</Text>
                      <Text style={styles.infoValue}>{t(`wastage.type.${r.type}`)}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('wastage.list.quantity')}</Text>
                      <Text style={styles.infoValue}>{r.quantity} {r.unit}</Text>
                    </View>
                    {r.estimatedCost != null && r.estimatedCost > 0 && (
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>{t('wastage.list.cost')}</Text>
                        <Text style={[styles.infoValue, { color: '#FF5630' }]}>¥{r.estimatedCost}</Text>
                      </View>
                    )}
                  </View>
                  <Text style={styles.dateText}>{r.wastageDate ? formatShortDateTime(r.wastageDate) : ''}</Text>
                </Surface>
                </TouchableOpacity>
              );
            })}
          </View>
        )}
      </ScrollView>

      <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab' as never, {
            screen: 'AIChat',
            params: {
              entityType: 'WASTAGE',
              initialMessage: `分析当前损耗记录${formatSummaryForAI(summary, { filter: { status: statusFilter } })}`,
            },
          } as never))
        }
      />

      <FAB testID="wastage-create-fab" icon="plus" style={styles.fab} onPress={() => navigation.navigate('WastageCreate')} />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedRecord ? `损耗 ${selectedRecord.wastageNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedRecord) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'WASTAGE', initialMessage: `${selectedRecord.wastageNumber}: ` },
          }));
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF5630', paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSub: { fontSize: 13, color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  searchRow: { paddingHorizontal: 16, paddingTop: 12 },
  searchbar: { backgroundColor: '#fff', borderRadius: 8 },
  chips: { paddingHorizontal: 16, paddingVertical: 8, flexGrow: 0 },
  chip: { marginRight: 8 },
  list: { paddingHorizontal: 16, paddingBottom: 80 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, marginBottom: 12 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  wastageNumber: { fontSize: 14, fontWeight: '600', marginLeft: 8, flex: 1, color: '#333' },
  badge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  cardBody: {},
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  infoLabel: { fontSize: 13, color: '#999' },
  infoValue: { fontSize: 13, fontWeight: '500', color: '#333' },
  dateText: { fontSize: 12, color: '#bbb', marginTop: 6, textAlign: 'right' },
  center: { alignItems: 'center', paddingTop: 60 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
  fab: { position: 'absolute', right: 16, bottom: 24, backgroundColor: '#FF5630' },
});

export default WastageListScreen;
