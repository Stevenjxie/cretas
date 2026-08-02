/**
 * 领料审批 — 审批待处理的领料单
 */
import React, { useState, useCallback, useEffect } from 'react';
import { View, ScrollView, StyleSheet, RefreshControl, Alert } from 'react-native';
import { Text, Surface, Button, TextInput } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RRequisitionStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { MaterialRequisition } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { formatShortDateTime } from '../../../utils/formatters';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Nav = NativeStackNavigationProp<RRequisitionStackParamList>;

export function RequisitionApprovalScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [pendingList, setPendingList] = useState<MaterialRequisition[]>([]);
  // Track per-item actual quantity input
  const [actualQtyMap, setActualQtyMap] = useState<Record<string, string>>({});
  const [rejectReasonMap, setRejectReasonMap] = useState<Record<string, string>>({});
  const [loadError, setLoadError] = useState(false);
  const { materialNames } = useRestaurantReferenceNames();

  const loadData = useCallback(async () => {
    try {
      setLoadError(false);
      const { data } = await restaurantApiClient.getRequisitions({ status: 'SUBMITTED', page: 1, size: 50 });
      setPendingList(data);
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
      setLoadError(true);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [t]);

  useEffect(() => { loadData(); }, [loadData]);

  async function handleApprove(req: MaterialRequisition) {
    const qty = actualQtyMap[req.id];
    if (!qty) {
      Alert.alert('', t('requisition.approval.enterActualQty', 'Please enter actual quantity before approving'));
      return;
    }
    try {
      await restaurantApiClient.approveRequisition(req.id, parseFloat(qty));
      Alert.alert('', t('requisition.approval.approved'));
      loadData();
    } catch (error) {
      handleError(error, { title: t('common.operationFailed') });
    }
  }

  async function handleReject(req: MaterialRequisition) {
    const reason = rejectReasonMap[req.id];
    if (!reason) {
      Alert.alert('', t('requisition.approval.enterRejectReason', 'Please enter rejection reason'));
      return;
    }
    try {
      await restaurantApiClient.rejectRequisition(req.id, reason);
      Alert.alert('', t('requisition.approval.rejected'));
      loadData();
    } catch (error) {
      handleError(error, { title: t('common.operationFailed') });
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{t('requisition.approval.title')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); loadData(); }} />}
      >
        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : loadError ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#EF4444" />
            <Text style={styles.emptyText}>{t('common.loadFailed')}</Text>
            <Button mode="contained" compact buttonColor="#1B65A8" onPress={() => { setLoading(true); loadData(); }} style={{ marginTop: 12 }}>
              {t('common.refresh')}
            </Button>
          </View>
        ) : pendingList.length === 0 ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="check-decagram" size={48} color="#C0C4CC" />
            <Text style={styles.emptyText}>{t('requisition.approval.empty', { defaultValue: '暂无待审批领料单' })}</Text>
          </View>
        ) : (
          pendingList.map(req => (
            <Surface key={req.id} style={styles.card} elevation={1}>
              <View style={styles.cardHeader}>
                <Text style={styles.reqNumber}>{req.requisitionNumber}</Text>
                <View style={[styles.badge, { backgroundColor: '#fff3e0' }]}>
                  <Text style={{ color: '#f57c00', fontSize: 12 }}>{t('requisition.status.SUBMITTED')}</Text>
                </View>
              </View>

              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('requisition.detail.material')}</Text>
                <Text style={styles.infoValue}>{resolveRestaurantReferenceName(
                  req.rawMaterialTypeName,
                  req.rawMaterialTypeId,
                  materialNames,
                  t('common.materialNameUnavailable'),
                )}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('requisition.detail.requestedQty')}</Text>
                <Text style={[styles.infoValue, { fontWeight: '700' }]}>
                  {req.requestedQuantity == null
                    ? t('common.notProvided')
                    : `${req.requestedQuantity} ${req.unit || ''}`.trim()}
                </Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('requisition.detail.reqDate')}</Text>
                <Text style={styles.infoValue}>{req.requisitionDate ? formatShortDateTime(req.requisitionDate) : ''}</Text>
              </View>
              {req.notes && (
                <View style={styles.infoRow}>
                  <Text style={styles.infoLabel}>{t('recipe.detail.notes')}</Text>
                  <Text style={styles.infoValue}>{req.notes}</Text>
                </View>
              )}

              {/* Approve section */}
              <View style={styles.actionSection}>
                <TextInput
                  mode="outlined"
                  label={t('requisition.approval.actualQuantity')}
                  value={actualQtyMap[req.id] || ''}
                  onChangeText={v => setActualQtyMap(prev => ({ ...prev, [req.id]: v }))}
                  keyboardType="decimal-pad"
                  dense
                  style={styles.actionInput}
                />
                <Button mode="contained" compact buttonColor="#388e3c" onPress={() => handleApprove(req)}>
                  {t('requisition.approval.approve')}
                </Button>
              </View>

              {/* Reject section */}
              <View style={styles.actionSection}>
                <TextInput
                  mode="outlined"
                  label={t('requisition.approval.rejectReason')}
                  value={rejectReasonMap[req.id] || ''}
                  onChangeText={v => setRejectReasonMap(prev => ({ ...prev, [req.id]: v }))}
                  dense
                  style={styles.actionInput}
                />
                <Button mode="outlined" compact textColor="#d32f2f" onPress={() => handleReject(req)}>
                  {t('requisition.approval.reject')}
                </Button>
              </View>
            </Surface>
          ))
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#1B65A8', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, marginBottom: 16 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  reqNumber: { fontSize: 15, fontWeight: '600', color: '#333' },
  badge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  infoLabel: { fontSize: 13, color: '#999' },
  infoValue: { fontSize: 13, fontWeight: '500', color: '#333' },
  actionSection: { flexDirection: 'row', alignItems: 'center', marginTop: 10, gap: 8 },
  actionInput: { flex: 1, backgroundColor: '#fff' },
  center: { alignItems: 'center', paddingTop: 60 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
});

export default RequisitionApprovalScreen;
