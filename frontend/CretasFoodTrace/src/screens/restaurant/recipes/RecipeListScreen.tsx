/**
 * 配方列表 — 按菜品查看配方(BOM)
 */
import React, { useState, useCallback, useEffect } from 'react';
import { View, ScrollView, StyleSheet, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Surface, Chip, Searchbar, useTheme, FAB } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RRecipeStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { Recipe } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { logger } from '../../../utils/logger';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Nav = NativeStackNavigationProp<RRecipeStackParamList>;

export function RecipeListScreen() {
  const { t } = useTranslation('restaurant');
  const theme = useTheme();
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<'all' | 'active' | 'inactive'>('all');
  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const [loadError, setLoadError] = useState(false);
  const { materialNames, productNames } = useRestaurantReferenceNames(true);

  const materialName = useCallback((recipe: Recipe) => resolveRestaurantReferenceName(
    recipe.rawMaterialTypeName,
    recipe.rawMaterialTypeId,
    materialNames,
    t('common.materialNameUnavailable'),
  ), [materialNames, t]);

  const dishName = useCallback((recipe: Recipe) => resolveRestaurantReferenceName(
    recipe.productTypeName,
    recipe.productTypeId,
    productNames,
    t('common.productNameUnavailable'),
  ), [productNames, t]);

  const loadData = useCallback(async () => {
    try {
      setLoadError(false);
      const isActive = filter === 'all' ? undefined : filter === 'active';
      const { data } = await restaurantApiClient.getRecipes({ page: 1, size: 100, isActive });
      setRecipes(data);
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
      setLoadError(true);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [filter, t]);

  useEffect(() => { loadData(); }, [loadData]);

  const filtered = recipes.filter(r => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return dishName(r).toLowerCase().includes(q) || materialName(r).toLowerCase().includes(q);
  });

  // Group by dish
  const grouped = filtered.reduce<Record<string, Recipe[]>>((acc, r) => {
    const key = r.productTypeId;
    (acc[key] = acc[key] || []).push(r);
    return acc;
  }, {});

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>{t('recipe.list.title')}</Text>
        <Text style={styles.headerSub}>{recipes.length} {t('common.items')}</Text>
      </View>

      <ScrollView
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); loadData(); }} />}
      >
        <View style={styles.searchRow}>
          <Searchbar
            placeholder={t('recipe.list.searchPlaceholder')}
            value={searchQuery}
            onChangeText={setSearchQuery}
            style={styles.searchbar}
          />
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
          {(['all', 'active', 'inactive'] as const).map(f => (
            <Chip key={f} selected={filter === f} onPress={() => setFilter(f)} style={styles.chip}>
              {f === 'all' ? t('common.all') : f === 'active' ? t('recipe.list.active') : t('recipe.list.inactive')}
            </Chip>
          ))}
        </ScrollView>

        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : loadError ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#EF4444" />
            <Text style={styles.emptyText}>{t('common.loadFailed')}</Text>
            <TouchableOpacity onPress={() => { setLoading(true); loadData(); }} style={{ marginTop: 12, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#FF6B35', borderRadius: 8 }}>
              <Text style={{ color: '#fff', fontWeight: '600' }}>{t('common.refresh')}</Text>
            </TouchableOpacity>
          </View>
        ) : Object.keys(grouped).length === 0 ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="food-variant-off" size={48} color="#C0C4CC" />
            <Text style={styles.emptyText}>{t('recipe.list.empty')}</Text>
          </View>
        ) : (
          <View style={styles.list}>
            {Object.entries(grouped).map(([productTypeId, items]) => {
              const firstItem = items[0];
              if (!firstItem) return null;
              const displayDishName = dishName(firstItem);
              return (
              <TouchableOpacity
                key={productTypeId}
                onPress={() => navigation.navigate('RecipeDetail', { productTypeId, dishName: displayDishName })}
              >
                <Surface style={styles.card} elevation={1}>
                  <View style={styles.cardHeader}>
                    <MaterialCommunityIcons name="food" size={20} color={theme.colors.primary} />
                    <Text style={styles.dishName}>{displayDishName}</Text>
                    <View style={[styles.badge, { backgroundColor: items[0]?.isActive ? '#e8f5e9' : '#fafafa' }]}>
                      <Text style={{ color: items[0]?.isActive ? '#388e3c' : '#999', fontSize: 12 }}>
                        {items[0]?.isActive ? t('recipe.list.active') : t('recipe.list.inactive')}
                      </Text>
                    </View>
                  </View>
                  <View style={styles.cardBody}>
                    {items.slice(0, 3).map(r => (
                      <View key={r.id} style={styles.ingredientRow}>
                        <Text style={styles.ingredientTag}>{r.isMainIngredient ? t('recipe.list.main') : t('recipe.list.auxiliary')}</Text>
                        <Text style={styles.ingredientName} numberOfLines={1}>{materialName(r)}</Text>
                        <Text style={styles.ingredientQty}>{r.standardQuantity} {r.unit}</Text>
                      </View>
                    ))}
                    {items.length > 3 && <Text style={styles.moreText}>+{items.length - 3} more</Text>}
                  </View>
                </Surface>
              </TouchableOpacity>
              );
            })}
          </View>
        )}
      </ScrollView>

      <FAB icon="plus" style={styles.fab} onPress={() => navigation.navigate('RecipeEdit', {})} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF6B35', paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSub: { fontSize: 13, color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  searchRow: { paddingHorizontal: 16, paddingTop: 12 },
  searchbar: { backgroundColor: '#fff', borderRadius: 8 },
  chips: { paddingHorizontal: 16, paddingVertical: 8, flexGrow: 0 },
  chip: { marginRight: 8 },
  list: { paddingHorizontal: 16, paddingBottom: 80 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 12 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  dishName: { fontSize: 16, fontWeight: '600', marginLeft: 8, flex: 1 },
  badge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  cardBody: {},
  ingredientRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4 },
  ingredientTag: { fontSize: 11, color: '#fff', backgroundColor: '#FF6B35', paddingHorizontal: 6, paddingVertical: 1, borderRadius: 3, marginRight: 8, overflow: 'hidden' },
  ingredientName: { flex: 1, fontSize: 14, color: '#333' },
  ingredientQty: { fontSize: 14, color: '#666', fontWeight: '500' },
  moreText: { fontSize: 12, color: '#999', textAlign: 'right', marginTop: 4 },
  center: { alignItems: 'center', paddingTop: 60 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
  fab: { position: 'absolute', right: 16, bottom: 24, backgroundColor: '#FF6B35' },
});

export default RecipeListScreen;
