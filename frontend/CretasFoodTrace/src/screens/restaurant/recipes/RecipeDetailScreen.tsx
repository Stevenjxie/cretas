/**
 * 配方详情 — 查看某菜品的完整 BOM 配料
 */
import React, { useState, useEffect } from 'react';
import { View, ScrollView, StyleSheet, Alert } from 'react-native';
import { Text, Surface, Button, useTheme, Divider } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { RRecipeStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { Recipe } from '../../../types/restaurant';
import { handleError } from '../../../utils/errorHandler';
import { resolveRestaurantReferenceName, useRestaurantReferenceNames } from '../hooks/useRestaurantReferenceNames';

type Nav = NativeStackNavigationProp<RRecipeStackParamList>;
type Route = RouteProp<RRecipeStackParamList, 'RecipeDetail'>;

export function RecipeDetailScreen() {
  const { t } = useTranslation('restaurant');
  const theme = useTheme();
  const navigation = useNavigation<Nav>();
  const route = useRoute<Route>();
  const { productTypeId, dishName } = route.params;
  const [loading, setLoading] = useState(true);
  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const { materialNames } = useRestaurantReferenceNames();

  const getMaterialName = (recipe: Recipe) => resolveRestaurantReferenceName(
    recipe.rawMaterialTypeName,
    recipe.rawMaterialTypeId,
    materialNames,
    t('common.materialNameUnavailable'),
  );

  useEffect(() => {
    loadData();
  }, [productTypeId]);

  async function loadData() {
    try {
      const data = await restaurantApiClient.getRecipesByDish(productTypeId);
      setRecipes(data);
    } catch (error) {
      handleError(error, { title: t('common.loadFailed') });
    } finally {
      setLoading(false);
    }
  }

  async function handleDelete(recipeId: string) {
    Alert.alert('确认停用', '停用后该配方将不再生效', [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('common.confirm'), style: 'destructive',
        onPress: async () => {
          try {
            await restaurantApiClient.deleteRecipe(recipeId);
            loadData();
          } catch (error) {
            handleError(error, { title: t('common.operationFailed') });
          }
        },
      },
    ]);
  }

  const mainIngredients = recipes.filter(r => r.isMainIngredient);
  const auxIngredients = recipes.filter(r => !r.isMainIngredient);

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>{t('common.back')}</Button>
        <Text style={styles.headerTitle}>{dishName || t('recipe.detail.title')}</Text>
        <Button icon="pencil" textColor="#fff" onPress={() => navigation.navigate('RecipeEdit' as any, { productTypeId, dishName })}>
          {t('recipe.edit.titleEdit')}
        </Button>
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {loading ? (
          <View style={styles.center}><Text>...</Text></View>
        ) : recipes.length === 0 ? (
          <View style={styles.center}>
            <MaterialCommunityIcons name="food-variant-off" size={48} color="#C0C4CC" />
            <Text style={styles.emptyText}>{t('recipe.list.empty')}</Text>
          </View>
        ) : (
          <>
            {/* Main ingredients */}
            <Text style={styles.sectionTitle}>{t('recipe.list.main')} ({mainIngredients.length})</Text>
            {mainIngredients.map(r => (
              <Surface key={r.id} style={styles.card} elevation={1}>
                <View style={styles.row}>
                  <MaterialCommunityIcons name="star" size={18} color="#FF6B35" />
                  <Text style={styles.materialName}>{getMaterialName(r)}</Text>
                </View>
                <View style={styles.detailGrid}>
                  <DetailItem label={t('recipe.detail.standardQty')} value={`${r.standardQuantity} ${r.unit}`} />
                  {r.actualQuantity != null && <DetailItem label={t('recipe.detail.actualQty')} value={`${r.actualQuantity} ${r.unit}`} />}
                  {r.netYieldRate != null && <DetailItem label={t('recipe.detail.yieldRate')} value={`${(r.netYieldRate * 100).toFixed(0)}%`} />}
                </View>
                {r.notes && <Text style={styles.notes}>{r.notes}</Text>}
                <Button compact textColor="#999" onPress={() => handleDelete(r.id)}>{t('recipe.list.inactive')}</Button>
              </Surface>
            ))}

            {auxIngredients.length > 0 && (
              <>
                <Divider style={{ marginVertical: 12 }} />
                <Text style={styles.sectionTitle}>{t('recipe.list.auxiliary')} ({auxIngredients.length})</Text>
                {auxIngredients.map(r => (
                  <Surface key={r.id} style={styles.card} elevation={1}>
                    <View style={styles.row}>
                      <MaterialCommunityIcons name="circle-small" size={18} color="#999" />
                      <Text style={styles.materialName}>{getMaterialName(r)}</Text>
                    </View>
                    <View style={styles.detailGrid}>
                      <DetailItem label={t('recipe.detail.standardQty')} value={`${r.standardQuantity} ${r.unit}`} />
                      {r.netYieldRate != null && <DetailItem label={t('recipe.detail.yieldRate')} value={`${(r.netYieldRate * 100).toFixed(0)}%`} />}
                    </View>
                    <Button compact textColor="#999" onPress={() => handleDelete(r.id)}>{t('recipe.list.inactive')}</Button>
                  </Surface>
                ))}
              </>
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.detailItem}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text style={styles.detailValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF6B35', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  sectionTitle: { fontSize: 16, fontWeight: '600', color: '#333', marginBottom: 8 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, marginBottom: 10 },
  row: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  materialName: { fontSize: 15, fontWeight: '600', marginLeft: 6, flex: 1, color: '#333' },
  detailGrid: { flexDirection: 'row', flexWrap: 'wrap' },
  detailItem: { width: '50%', marginBottom: 6 },
  detailLabel: { fontSize: 12, color: '#999' },
  detailValue: { fontSize: 14, fontWeight: '500', color: '#333' },
  notes: { fontSize: 13, color: '#666', marginTop: 4 },
  center: { alignItems: 'center', paddingTop: 60 },
  emptyText: { marginTop: 12, fontSize: 14, color: '#999' },
});

export default RecipeDetailScreen;
