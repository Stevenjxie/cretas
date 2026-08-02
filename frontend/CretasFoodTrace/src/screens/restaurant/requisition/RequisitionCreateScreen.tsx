/**
 * 领料申请 — 创建领料单
 */
import React, { useState } from 'react';
import { View, ScrollView, StyleSheet, Alert, TouchableOpacity } from 'react-native';
import { Text, TextInput, Button, SegmentedButtons } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { RequisitionType } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { MaterialSelectModal, MaterialSelectResult } from '../../../components/MaterialSelectModal';
import { ProductTypeSelector } from '../../../components/common/ProductTypeSelector';

export function RequisitionCreateScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation();
  const [submitting, setSubmitting] = useState(false);
  const [materialPickerVisible, setMaterialPickerVisible] = useState(false);
  const [dishName, setDishName] = useState('');
  const [materialName, setMaterialName] = useState('');
  const [form, setForm] = useState({
    type: 'PRODUCTION' as RequisitionType,
    productTypeId: '',
    rawMaterialTypeId: '',
    requestedQuantity: '',
    unit: 'kg',
    notes: '',
  });

  const updateField = (key: string, value: any) => setForm(prev => ({ ...prev, [key]: value }));

  const handleTypeChange = (type: string) => {
    const nextType = type as RequisitionType;
    setForm(prev => ({
      ...prev,
      type: nextType,
      productTypeId: nextType === 'PRODUCTION' ? prev.productTypeId : '',
    }));
    if (nextType !== 'PRODUCTION') setDishName('');
  };

  const handleDishSelect = (productTypeId: string, productTypeName: string) => {
    setDishName(productTypeName);
    updateField('productTypeId', productTypeId);
  };

  const handleMaterialSelect = (material: MaterialSelectResult) => {
    setMaterialName(material.materialName);
    setForm(prev => ({
      ...prev,
      rawMaterialTypeId: material.materialTypeId,
      unit: material.defaultUnit || prev.unit,
    }));
  };

  async function handleSubmit(asDraft: boolean) {
    if (!form.rawMaterialTypeId || !form.requestedQuantity) {
      Alert.alert('', t('common.operationFailed'));
      return;
    }
    if (form.type === 'PRODUCTION' && !form.productTypeId) {
      Alert.alert('', t('requisition.create.selectDish') + ': ' + t('common.operationFailed'));
      return;
    }
    const qty = parseFloat(form.requestedQuantity);
    if (isNaN(qty) || qty <= 0) {
      Alert.alert('', t('requisition.create.quantity') + ': ' + t('common.operationFailed'));
      return;
    }
    setSubmitting(true);
    try {
      const record = await restaurantApiClient.createRequisition({
        type: form.type,
        productTypeId: form.type === 'PRODUCTION' && form.productTypeId ? form.productTypeId : undefined,
        rawMaterialTypeId: form.rawMaterialTypeId,
        requestedQuantity: qty,
        unit: form.unit,
        notes: form.notes || undefined,
      });
      if (!asDraft && record?.id) {
        await restaurantApiClient.submitRequisition(record.id);
      }
      Alert.alert('', t('requisition.create.created'), [
        { text: 'OK', onPress: () => navigation.goBack() },
      ]);
    } catch (error) {
      handleError(error, { title: t('common.submitFailed') });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{t('requisition.create.title')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.label}>{t('requisition.create.type')}</Text>
        <SegmentedButtons
          value={form.type}
          onValueChange={handleTypeChange}
          buttons={[
            { value: 'PRODUCTION', label: t('requisition.create.typeProduction') },
            { value: 'MANUAL', label: t('requisition.create.typeManual') },
          ]}
          style={styles.segments}
        />

        {form.type === 'PRODUCTION' && (
          <>
            <Text style={styles.label}>{t('requisition.create.selectDish')}</Text>
            <View>
              <ProductTypeSelector
                testID="requisition-dish-selector"
                showConversionStatus={false}
                value={dishName}
                onSelect={handleDishSelect}
                label={t('requisition.create.dish')}
                placeholder={t('requisition.create.selectDishPlaceholder')}
              />
            </View>
          </>
        )}

        <Text style={styles.label}>{t('requisition.create.selectMaterial')} *</Text>
        <TouchableOpacity testID="requisition-material-selector" style={styles.selector} onPress={() => setMaterialPickerVisible(true)}>
          <Text style={materialName ? styles.selectorValue : styles.selectorPlaceholder}>
            {materialName || t('requisition.create.selectMaterialPlaceholder')}
          </Text>
          <Text style={styles.selectorChevron}>›</Text>
        </TouchableOpacity>

        <View style={styles.row}>
          <View style={{ flex: 1, marginRight: 8 }}>
            <Text style={styles.label}>{t('requisition.create.quantity')} *</Text>
            <TextInput mode="outlined" value={form.requestedQuantity} onChangeText={v => updateField('requestedQuantity', v)} keyboardType="decimal-pad" style={styles.input} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.label}>{t('requisition.create.unit')}</Text>
            <TextInput mode="outlined" value={form.unit} onChangeText={v => updateField('unit', v)} style={styles.input} />
          </View>
        </View>

        <Text style={styles.label}>{t('requisition.create.notes')}</Text>
        <TextInput mode="outlined" value={form.notes} onChangeText={v => updateField('notes', v)} multiline numberOfLines={3} style={styles.input} />

        <View style={styles.btnRow}>
          <Button mode="outlined" onPress={() => handleSubmit(true)} disabled={submitting} style={styles.btn}>
            {t('requisition.create.saveDraft')}
          </Button>
          <Button mode="contained" onPress={() => handleSubmit(false)} loading={submitting} disabled={submitting} buttonColor="#1B65A8" style={styles.btn}>
            {t('requisition.create.submitNow')}
          </Button>
        </View>
      </ScrollView>

      <MaterialSelectModal
        visible={materialPickerVisible}
        onDismiss={() => setMaterialPickerVisible(false)}
        onSelect={handleMaterialSelect}
        title={t('requisition.create.selectMaterial')}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#1B65A8', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  label: { fontSize: 14, fontWeight: '500', color: '#333', marginBottom: 4, marginTop: 12 },
  input: { backgroundColor: '#fff' },
  selector: { minHeight: 52, borderWidth: 1, borderColor: '#79747e', borderRadius: 4, backgroundColor: '#fff', paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center' },
  selectorValue: { flex: 1, fontSize: 16, color: '#1F2937' },
  selectorPlaceholder: { flex: 1, fontSize: 16, color: '#8A8F98' },
  selectorChevron: { fontSize: 28, color: '#8A8F98' },
  segments: { marginTop: 4 },
  row: { flexDirection: 'row' },
  btnRow: { flexDirection: 'row', marginTop: 24, gap: 12 },
  btn: { flex: 1, borderRadius: 8 },
});

export default RequisitionCreateScreen;
