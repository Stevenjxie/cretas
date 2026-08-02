/**
 * 领料详情 — 查看单条领料记录
 */
import React, { useState, useEffect } from 'react';
import { View, ScrollView, StyleSheet } from 'react-native';
import { Text, Surface, Button, Divider } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
import { useTranslation } from 'react-i18next';
import { RRequisitionStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { MaterialRequisition, RequisitionStatus } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { formatShortDateTime } from '../../../utils/formatters';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Route = RouteProp<RRequisitionStackParamList, 'RequisitionDetail'>;

const STATUS_CONFIG: Record<RequisitionStatus, { color: string; bg: string; icon: MCIconName }> = {
  DRAFT: { color: '#757575', bg: '#f5f5f5', icon: 'file-edit-outline' },
  SUBMITTED: { color: '#f57c00', bg: '#fff3e0', icon: 'clock-outline' },
  APPROVED: { color: '#388e3c', bg: '#e8f5e9', icon: 'check-circle-outline' },
  REJECTED: { color: '#d32f2f', bg: '#ffebee', icon: 'close-circle-outline' },
};

export function RequisitionDetailScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation();
  const route = useRoute<Route>();
  const { requisitionId } = route.params;
  const [loading, setLoading] = useState(true);
  const [record, setRecord] = useState<MaterialRequisition | null>(null);
  const { materialNames } = useRestaurantReferenceNames();

  useEffect(() => {
    loadData();
  }, [requisitionId]);

  async function loadData() {
    try {
      const data = await restaurantApiClient.getRequisition(requisitionId);
      setRecord(data);
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
    } finally {
      setLoading(false);
    }
  }

  async function handleSubmit() {
    if (!record) return;
    try {
      await restaurantApiClient.submitRequisition(record.id);
      loadData();
    } catch (error) {
      handleError(error, { title: t('common.operationFailed') });
    }
  }

  if (loading) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.center}><Text>...</Text></View>
      </SafeAreaView>
    );
  }

  if (!record) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.header}>
          <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
          <Text style={styles.headerTitle}>{t('requisition.detail.title')}</Text>
          <View style={{ width: 60 }} />
        </View>
        <View style={styles.center}>
          <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#C0C4CC" />
          <Text style={styles.emptyText}>{t('common.loadFailed')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  const sc = STATUS_CONFIG[record.status] || STATUS_CONFIG.DRAFT;

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{t('requisition.detail.title')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {/* Status banner */}
        <Surface style={[styles.statusBanner, { backgroundColor: sc.bg }]} elevation={0}>
          <MaterialCommunityIcons name={sc.icon} size={28} color={sc.color} />
          <Text style={[styles.statusText, { color: sc.color }]}>{t(`requisition.status.${record.status}`)}</Text>
        </Surface>

        {/* Details */}
        <Surface style={styles.card} elevation={1}>
          <DetailRow label={t('requisition.detail.reqNumber')} value={record.requisitionNumber} />
          <DetailRow label={t('requisition.detail.reqDate')} value={record.requisitionDate ? formatShortDateTime(record.requisitionDate) : '-'} />
          <Divider style={{ marginVertical: 8 }} />
          <DetailRow label={t('requisition.detail.material')} value={resolveRestaurantReferenceName(
            record.rawMaterialTypeName,
            record.rawMaterialTypeId,
            materialNames,
            t('common.materialNameUnavailable'),
          )} />
          <DetailRow label={t('requisition.create.type')} value={record.type === 'PRODUCTION' ? t('requisition.create.typeProduction') : t('requisition.create.typeManual')} />
          <DetailRow
            label={t('requisition.detail.requestedQty')}
            value={record.requestedQuantity == null
              ? t('common.notProvided')
              : `${record.requestedQuantity} ${record.unit || ''}`.trim()}
            bold
          />
          {record.actualQuantity != null && (
            <DetailRow label={t('requisition.detail.actualQty')} value={`${record.actualQuantity} ${record.unit}`} bold />
          )}
          <Divider style={{ marginVertical: 8 }} />
          {record.requestedByName && <DetailRow label={t('requisition.detail.applicant')} value={record.requestedByName} />}
          {record.approvedByName && <DetailRow label={t('requisition.detail.approver')} value={record.approvedByName} />}
          {record.approvedAt && <DetailRow label="审批时间" value={formatShortDateTime(record.approvedAt)} />}
          {record.notes && <DetailRow label={t('recipe.detail.notes')} value={record.notes} />}
        </Surface>

        {/* Actions */}
        {record.status === 'DRAFT' && (
          <Button mode="contained" onPress={handleSubmit} buttonColor="#1B65A8" style={styles.actionBtn}>
            {t('requisition.create.submitNow')}
          </Button>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function DetailRow({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <View style={styles.detailRow}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text style={[styles.detailValue, bold && { fontWeight: '700', fontSize: 15 }]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#1B65A8', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  statusBanner: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: 16, borderRadius: 12, marginBottom: 16, gap: 8 },
  statusText: { fontSize: 18, fontWeight: '700' },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 16 },
  detailRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 5 },
  detailLabel: { fontSize: 14, color: '#999' },
  detailValue: { fontSize: 14, fontWeight: '500', color: '#333', maxWidth: '60%', textAlign: 'right' },
  actionBtn: { marginTop: 8, borderRadius: 8 },
  center: { alignItems: 'center', paddingTop: 60, flex: 1 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
});

export default RequisitionDetailScreen;
