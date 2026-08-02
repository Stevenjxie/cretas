/**
 * 损耗新建 — 记录食材损耗
 */
import React, { useState } from 'react';
import { View, ScrollView, StyleSheet, Alert, TouchableOpacity } from 'react-native';
import { Text, TextInput, Button, Chip } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { WastageType } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { MaterialSelectModal, MaterialSelectResult } from '../../../components/MaterialSelectModal';

const WASTAGE_TYPES: WastageType[] = ['EXPIRED', 'DAMAGED', 'SPOILED', 'PROCESSING', 'OTHER'];

export function WastageCreateScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation();
  const [submitting, setSubmitting] = useState(false);
  const [materialPickerVisible, setMaterialPickerVisible] = useState(false);
  const [materialName, setMaterialName] = useState('');
  const [form, setForm] = useState({
    type: 'EXPIRED' as WastageType,
    rawMaterialTypeId: '',
    quantity: '',
    unit: 'kg',
    estimatedCost: '',
    notes: '',
  });

  const updateField = (key: string, value: any) => setForm(prev => ({ ...prev, [key]: value }));

  const handleMaterialSelect = (material: MaterialSelectResult) => {
    setMaterialName(material.materialName);
    setForm(prev => ({
      ...prev,
      rawMaterialTypeId: material.materialTypeId,
      unit: material.defaultUnit || prev.unit,
    }));
  };

  async function handleSubmit(asDraft: boolean) {
    if (!form.rawMaterialTypeId || !form.quantity) {
      const missing: string[] = [];
      if (!form.rawMaterialTypeId) missing.push(t('wastage.create.selectMaterial'));
      if (!form.quantity) missing.push(t('wastage.create.quantity'));
      Alert.alert('', missing.join(', '));
      return;
    }
    const qty = parseFloat(form.quantity);
    if (isNaN(qty) || qty <= 0) {
      Alert.alert('', t('wastage.create.quantity') + ': ' + t('common.operationFailed'));
      return;
    }
    const cost = form.estimatedCost ? parseFloat(form.estimatedCost) : undefined;
    if (cost !== undefined && (isNaN(cost) || cost < 0)) {
      Alert.alert('', t('wastage.create.cost') + ': ' + t('common.operationFailed'));
      return;
    }
    setSubmitting(true);
    try {
      const record = await restaurantApiClient.createWastage({
        type: form.type,
        rawMaterialTypeId: form.rawMaterialTypeId,
        quantity: qty,
        unit: form.unit,
        estimatedCost: cost,
        notes: form.notes || undefined,
      });
      if (!asDraft && record?.id) {
        await restaurantApiClient.submitWastage(record.id);
      }
      Alert.alert('', t('wastage.create.created'), [
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
        <Text style={styles.headerTitle}>{t('wastage.create.title')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.label}>{t('wastage.create.type')}</Text>
        <View style={styles.chipRow}>
          {WASTAGE_TYPES.map(wt => (
            <Chip
              key={wt}
              selected={form.type === wt}
              onPress={() => updateField('type', wt)}
              style={[styles.chip, form.type === wt && styles.chipSelected]}
              textStyle={form.type === wt ? styles.chipTextSelected : undefined}
            >
              {t(`wastage.type.${wt}`)}
            </Chip>
          ))}
        </View>

        <Text style={styles.label}>{t('wastage.create.selectMaterial')} *</Text>
        <TouchableOpacity style={styles.materialSelector} onPress={() => setMaterialPickerVisible(true)}>
          <Text style={materialName ? styles.materialValue : styles.materialPlaceholder}>
            {materialName || t('wastage.create.selectMaterial')}
          </Text>
          <Text style={styles.materialChevron}>›</Text>
        </TouchableOpacity>

        <View style={styles.row}>
          <View style={{ flex: 1, marginRight: 8 }}>
            <Text style={styles.label}>{t('wastage.create.quantity')} *</Text>
            <TextInput mode="outlined" value={form.quantity} onChangeText={v => updateField('quantity', v)} keyboardType="decimal-pad" style={styles.input} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.label}>{t('wastage.create.unit')}</Text>
            <TextInput mode="outlined" value={form.unit} onChangeText={v => updateField('unit', v)} style={styles.input} />
          </View>
        </View>

        <Text style={styles.label}>{t('wastage.create.cost')}</Text>
        <TextInput mode="outlined" value={form.estimatedCost} onChangeText={v => updateField('estimatedCost', v)} keyboardType="decimal-pad" placeholder="0.00" style={styles.input} />

        <Text style={styles.label}>{t('wastage.create.notes')}</Text>
        <TextInput mode="outlined" value={form.notes} onChangeText={v => updateField('notes', v)} multiline numberOfLines={3} style={styles.input} />

        <View style={styles.btnRow}>
          <Button mode="outlined" onPress={() => handleSubmit(true)} disabled={submitting} style={styles.btn}>
            {t('wastage.create.saveDraft')}
          </Button>
          <Button mode="contained" onPress={() => handleSubmit(false)} loading={submitting} disabled={submitting} buttonColor="#FF5630" style={styles.btn}>
            {t('wastage.create.submitNow')}
          </Button>
        </View>
      </ScrollView>

      <MaterialSelectModal
        visible={materialPickerVisible}
        onDismiss={() => setMaterialPickerVisible(false)}
        onSelect={handleMaterialSelect}
        title={t('wastage.create.selectMaterial')}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF5630', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  label: { fontSize: 14, fontWeight: '500', color: '#333', marginBottom: 4, marginTop: 12 },
  input: { backgroundColor: '#fff' },
  materialSelector: {
    minHeight: 52,
    borderWidth: 1,
    borderColor: '#79747e',
    borderRadius: 4,
    backgroundColor: '#fff',
    paddingHorizontal: 14,
    flexDirection: 'row',
    alignItems: 'center',
  },
  materialValue: { flex: 1, fontSize: 16, color: '#1f2937' },
  materialPlaceholder: { flex: 1, fontSize: 16, color: '#8a8f98' },
  materialChevron: { fontSize: 28, color: '#8a8f98' },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  chip: { marginBottom: 0 },
  chipSelected: { backgroundColor: '#FF5630' },
  chipTextSelected: { color: '#fff' },
  row: { flexDirection: 'row' },
  btnRow: { flexDirection: 'row', marginTop: 24, gap: 12 },
  btn: { flex: 1, borderRadius: 8 },
});

export default WastageCreateScreen;
