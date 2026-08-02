/**
 * 配方编辑/新建 — 添加或修改菜品配方
 */
import React, { useState } from 'react';
import { View, ScrollView, StyleSheet, Alert, TouchableOpacity } from 'react-native';
import { Text, TextInput, Button, Switch, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RRecipeStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { handleError } from '../../../utils/errorHandler';
import { MaterialSelectModal, MaterialSelectResult } from '../../../components/MaterialSelectModal';
import { ProductTypeSelector } from '../../../components/common/ProductTypeSelector';

type Nav = NativeStackNavigationProp<RRecipeStackParamList>;
type Route = RouteProp<RRecipeStackParamList, 'RecipeEdit'>;

export function RecipeEditScreen() {
  const { t } = useTranslation('restaurant');
  const navigation = useNavigation<Nav>();
  const route = useRoute<Route>();
  const hasPresetDish = Boolean(route.params?.productTypeId);

  const [submitting, setSubmitting] = useState(false);
  const [materialPickerVisible, setMaterialPickerVisible] = useState(false);
  const [dishName, setDishName] = useState(route.params?.dishName || '');
  const [materialName, setMaterialName] = useState('');
  const [form, setForm] = useState({
    productTypeId: route.params?.productTypeId || '',
    rawMaterialTypeId: '',
    standardQuantity: '',
    unit: 'kg',
    netYieldRate: '',
    isMainIngredient: true,
    notes: '',
  });

  const updateField = (key: string, value: any) => setForm(prev => ({ ...prev, [key]: value }));

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

  async function handleSave() {
    if (!form.productTypeId || !form.rawMaterialTypeId || !form.standardQuantity) {
      const missing: string[] = [];
      if (!form.productTypeId) missing.push(t('recipe.edit.selectDish'));
      if (!form.rawMaterialTypeId) missing.push(t('recipe.edit.selectMaterial'));
      if (!form.standardQuantity) missing.push(t('recipe.edit.quantity'));
      Alert.alert('', missing.join(', '), [{ text: 'OK' }]);
      return;
    }
    const qty = parseFloat(form.standardQuantity);
    if (isNaN(qty) || qty <= 0) {
      Alert.alert('', t('recipe.edit.quantity') + ': ' + t('common.operationFailed'));
      return;
    }
    setSubmitting(true);
    try {
      await restaurantApiClient.createRecipe({
        productTypeId: form.productTypeId,
        rawMaterialTypeId: form.rawMaterialTypeId,
        standardQuantity: qty,
        unit: form.unit,
        netYieldRate: form.netYieldRate ? parseFloat(form.netYieldRate) / 100 : undefined,
        isMainIngredient: form.isMainIngredient,
        notes: form.notes || undefined,
      });
      Alert.alert('', t('recipe.edit.saved'), [
        { text: 'OK', onPress: () => navigation.goBack() },
      ]);
    } catch (error) {
      handleError(error, { title: t('common.operationFailed') });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{hasPresetDish ? t('recipe.edit.titleEdit') : t('recipe.edit.titleCreate')}</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.label}>{t('recipe.edit.selectDish')} *</Text>
        {hasPresetDish ? (
          <View testID="recipe-dish-fixed" style={styles.fixedSelection}>
            <Text style={styles.fixedSelectionLabel}>{dishName || t('recipe.edit.selectedDish')}</Text>
            <Text style={styles.fixedSelectionHint}>{t('recipe.edit.dishLockedHint')}</Text>
          </View>
        ) : (
          <View>
            <ProductTypeSelector
              testID="recipe-dish-selector"
              showConversionStatus={false}
              value={dishName}
              onSelect={handleDishSelect}
              label={t('recipe.edit.dish')}
              placeholder={t('recipe.edit.selectDishPlaceholder')}
            />
          </View>
        )}

        <Text style={styles.label}>{t('recipe.edit.selectMaterial')} *</Text>
        <TouchableOpacity testID="recipe-material-selector" style={styles.selector} onPress={() => setMaterialPickerVisible(true)}>
          <Text style={materialName ? styles.selectorValue : styles.selectorPlaceholder}>
            {materialName || t('recipe.edit.selectMaterialPlaceholder')}
          </Text>
          <Text style={styles.selectorChevron}>›</Text>
        </TouchableOpacity>

        <View style={styles.row}>
          <View style={{ flex: 1, marginRight: 8 }}>
            <Text style={styles.label}>{t('recipe.edit.quantity')} *</Text>
            <TextInput
              mode="outlined"
              value={form.standardQuantity}
              onChangeText={v => updateField('standardQuantity', v)}
              keyboardType="decimal-pad"
              style={styles.input}
            />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.label}>{t('recipe.edit.unit')}</Text>
            <TextInput
              mode="outlined"
              value={form.unit}
              onChangeText={v => updateField('unit', v)}
              style={styles.input}
            />
          </View>
        </View>

        <Text style={styles.label}>{t('recipe.edit.yieldRate')} (%)</Text>
        <TextInput
          mode="outlined"
          value={form.netYieldRate}
          onChangeText={v => updateField('netYieldRate', v)}
          keyboardType="decimal-pad"
          placeholder="e.g. 85"
          style={styles.input}
        />

        <View style={styles.switchRow}>
          <Text style={styles.label}>{t('recipe.edit.isMain')}</Text>
          <Switch value={form.isMainIngredient} onValueChange={v => updateField('isMainIngredient', v)} />
        </View>

        <Text style={styles.label}>{t('recipe.edit.notes')}</Text>
        <TextInput
          mode="outlined"
          value={form.notes}
          onChangeText={v => updateField('notes', v)}
          multiline
          numberOfLines={3}
          style={styles.input}
        />

        <Button
          mode="contained"
          onPress={handleSave}
          loading={submitting}
          disabled={submitting}
          buttonColor="#FF6B35"
          style={styles.saveBtn}
        >
          {t('recipe.edit.save')}
        </Button>
      </ScrollView>

      <MaterialSelectModal
        visible={materialPickerVisible}
        onDismiss={() => setMaterialPickerVisible(false)}
        onSelect={handleMaterialSelect}
        title={t('recipe.edit.selectMaterial')}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF6B35', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  label: { fontSize: 14, fontWeight: '500', color: '#333', marginBottom: 4, marginTop: 12 },
  input: { backgroundColor: '#fff' },
  fixedSelection: { minHeight: 52, borderWidth: 1, borderColor: '#E5E7EB', borderRadius: 8, backgroundColor: '#fff', paddingHorizontal: 14, paddingVertical: 9 },
  fixedSelectionLabel: { fontSize: 16, color: '#1F2937', fontWeight: '600' },
  fixedSelectionHint: { fontSize: 12, color: '#6B7280', marginTop: 2 },
  selector: { minHeight: 52, borderWidth: 1, borderColor: '#79747e', borderRadius: 4, backgroundColor: '#fff', paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center' },
  selectorValue: { flex: 1, fontSize: 16, color: '#1F2937' },
  selectorPlaceholder: { flex: 1, fontSize: 16, color: '#8A8F98' },
  selectorChevron: { fontSize: 28, color: '#8A8F98' },
  row: { flexDirection: 'row' },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 12, marginBottom: 4 },
  saveBtn: { marginTop: 24, borderRadius: 8 },
});

export default RecipeEditScreen;
