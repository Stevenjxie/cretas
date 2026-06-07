import React, { useState, useEffect } from 'react';
import { View, StyleSheet, Modal, FlatList, TouchableOpacity } from 'react-native';
import { TextInput, List, Divider, Button, Text, Searchbar, ActivityIndicator, Chip } from 'react-native-paper';
import { productTypeApiClient } from '../../services/api/productTypeApiClient';
import { conversionApiClient } from '../../services/api/conversionApiClient';

interface ProductType {
  id: string;
  name: string;
  baseProductName?: string; // T123: 产品基础名 (名称分离), RN 展示优先使用, 无则 fallback 到 name
  code: string;
  category?: string;
  description?: string;
  hasConversionRate?: boolean;  // 是否已配置转换率
}

interface ProductTypeSelectorProps {
  value: string;  // 显示的产品名称
  onSelect: (productTypeId: string, productTypeName: string, productTypeCode: string) => void;
  label?: string;
  placeholder?: string;
  error?: string;
}

/**
 * 产品类型(SKU)选择器
 * 注意: 不支持快捷添加，SKU由管理员预先配置
 */
export const ProductTypeSelector: React.FC<ProductTypeSelectorProps> = ({
  value,
  onSelect,
  label = '产品类型',
  placeholder = '选择产品类型',
  error,
}) => {
  const [modalVisible, setModalVisible] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [productTypes, setProductTypes] = useState<ProductType[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (modalVisible) {
      fetchProductTypes();
    }
  }, [modalVisible]);

  const fetchProductTypes = async () => {
    try {
      setLoading(true);
      const result = await productTypeApiClient.getProductTypes({ isActive: true, limit: 100 });
      const productTypesList: ProductType[] = (Array.isArray(result.data) ? result.data : (result.data as { productTypes?: ProductType[] })?.productTypes || []).map(p => ({ ...p, code: (p as ProductType & { code?: string }).code || '' }));
      console.log('✅ Product types loaded:', productTypesList.length);

      // 批量检查每个产品的转换率配置状态
      const productTypesWithConversionStatus = await Promise.all(
        productTypesList.map(async (product) => {
          try {
            const conversionRes = await conversionApiClient.getConversionsByProduct(product.id);
            const hasConversion = conversionRes?.success &&
              Array.isArray(conversionRes?.data) &&
              conversionRes?.data.length > 0;
            return { ...product, hasConversionRate: hasConversion };
          } catch {
            // 如果查询失败，假设未配置
            return { ...product, hasConversionRate: false };
          }
        })
      );

      setProductTypes(productTypesWithConversionStatus);
    } catch (error) {
      console.error('❌ Failed to fetch product types:', error);
      setProductTypes([]);
    } finally {
      setLoading(false);
    }
  };

  const filteredProductTypes = productTypes.filter(p =>
    p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.code.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.category?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // T123: 优先使用 baseProductName (产品基础名), 无则 fallback 到 name
  const getDisplayName = (product: ProductType): string =>
    product.baseProductName || product.name;

  const handleSelect = (product: ProductType) => {
    onSelect(product.id, getDisplayName(product), product.code);
    setModalVisible(false);
    setSearchQuery('');
  };

  return (
    <View>
      <TouchableOpacity
        onPress={() => {
          console.log('🔍 Opening product type selector');
          setModalVisible(true);
        }}
        activeOpacity={1}
      >
        <View pointerEvents="none">
          <TextInput
            label={label + ' *'}
            placeholder={placeholder}
            mode="outlined"
            value={value}
            editable={false}
            error={!!error}
            right={<TextInput.Icon icon="chevron-down" />}
            style={styles.input}
          />
        </View>
      </TouchableOpacity>
      {error && <Text variant="bodySmall" style={styles.errorText}>{error}</Text>}

      <Modal
        visible={modalVisible}
        animationType="slide"
        transparent={true}
        onRequestClose={() => setModalVisible(false)}
      >
        <View style={styles.modalContainer}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text variant="titleLarge">选择{label}</Text>
              <Button onPress={() => setModalVisible(false)}>取消</Button>
            </View>

            <Searchbar
              placeholder="搜索产品/SKU..."
              value={searchQuery}
              onChangeText={setSearchQuery}
              style={styles.searchBar}
            />

            {loading ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="large" />
                <Text style={styles.loadingText}>加载中...</Text>
              </View>
            ) : (
              <FlatList
                data={filteredProductTypes}
                keyExtractor={(item) => item.id}
                renderItem={({ item }) => (
                  <>
                    <List.Item
                      title={getDisplayName(item)}
                      description={`SKU: ${item.code}${item.category ? ' • ' + item.category : ''}`}
                      onPress={() => handleSelect(item)}
                      right={props => (
                        <View style={styles.rightContainer}>
                          {/* 转换率配置状态指示器 */}
                          <Chip
                            mode="flat"
                            compact
                            icon={item.hasConversionRate ? 'check-circle' : 'alert-circle'}
                            style={[
                              styles.conversionChip,
                              item.hasConversionRate ? styles.configuredChip : styles.notConfiguredChip
                            ]}
                            textStyle={styles.chipText}
                          >
                            {item.hasConversionRate ? '已配置' : '未配置'}
                          </Chip>
                          {/* 选中图标 — T123: 比较 displayName (baseProductName || name) */}
                          {value === getDisplayName(item) && <List.Icon {...props} icon="check" color="#2196F3" />}
                        </View>
                      )}
                    />
                    <Divider />
                  </>
                )}
                ListEmptyComponent={
                  <View style={styles.emptyContainer}>
                    <Text variant="bodyMedium" style={styles.emptyText}>
                      {searchQuery ? '未找到匹配的产品类型' : '暂无产品类型'}
                    </Text>
                    <Text variant="bodySmall" style={styles.emptyHint}>
                      请联系管理员添加产品SKU
                    </Text>
                  </View>
                }
                ListFooterComponent={
                  <View style={styles.footerNote}>
                    <Text variant="bodySmall" style={styles.noteText}>
                      💡 提示: 产品SKU由管理员统一配置
                    </Text>
                  </View>
                }
              />
            )}
          </View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  input: {
    marginBottom: 4,
  },
  errorText: {
    color: '#F44336',
    marginTop: 4,
    marginLeft: 12,
    marginBottom: 8,
  },
  rightContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flexShrink: 0,
  },
  conversionChip: {
    height: 26,
    minWidth: 70,
    flexShrink: 0,
  },
  configuredChip: {
    backgroundColor: '#E8F5E9',
  },
  notConfiguredChip: {
    backgroundColor: '#FFF3E0',
  },
  chipText: {
    fontSize: 11,
  },
  modalContainer: {
    flex: 1,
    justifyContent: 'flex-end',
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
  },
  modalContent: {
    backgroundColor: '#FFF',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '80%',
    paddingBottom: 20,
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  searchBar: {
    margin: 16,
  },
  loadingContainer: {
    padding: 48,
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    color: '#757575',
  },
  emptyContainer: {
    padding: 32,
    alignItems: 'center',
  },
  emptyText: {
    color: '#9E9E9E',
    marginBottom: 8,
  },
  emptyHint: {
    color: '#BDBDBD',
    fontSize: 12,
  },
  footerNote: {
    padding: 16,
    marginTop: 8,
    marginHorizontal: 16,
    backgroundColor: '#FFF9C4',
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#FBC02D',
  },
  noteText: {
    color: '#F57F17',
    fontSize: 12,
  },
});

export default ProductTypeSelector;
