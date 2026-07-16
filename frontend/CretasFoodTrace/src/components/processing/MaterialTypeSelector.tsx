import React, { useState, useEffect } from 'react';
import { View, StyleSheet, Modal, FlatList, TouchableOpacity, Alert } from 'react-native';
import { TextInput, List, Divider, Button, Text, Searchbar, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { materialQuickAPI, MaterialType } from '../../services/api/materialQuickApiClient';
import { useAuthStore } from '../../store/authStore';
import { handleError, getErrorMsg } from '../../utils/errorHandler';

interface MaterialTypeSelectorProps {
  value: string;
  onSelect: (materialType: string, materialTypeId?: string) => void;
  label?: string;
  placeholder?: string;
  error?: string;
}

/**
 * 原料类型选择器
 * 从数据库获取原料类型列表
 */
export const MaterialTypeSelector: React.FC<MaterialTypeSelectorProps> = ({
  value,
  onSelect,
  label = '原料类型',
  placeholder = '选择原料类型',
  error,
}) => {
  const { user } = useAuthStore();
  const factoryId = user?.factoryId || user?.factoryUser?.factoryId;
  
  const [modalVisible, setModalVisible] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [loading, setLoading] = useState(false);

  // 快捷添加功能状态
  const [showAddForm, setShowAddForm] = useState(false);
  const [newMaterialName, setNewMaterialName] = useState('');
  const [newMaterialCategory, setNewMaterialCategory] = useState('鱼类');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (modalVisible) {
      fetchMaterialTypes();
    }
  }, [modalVisible]);

  const fetchMaterialTypes = async () => {
    try {
      setLoading(true);
      const result = await materialQuickAPI.getMaterialTypes(factoryId);
      console.log('✅ Material types loaded:', result.length);
      setMaterials(result);
    } catch (error) {
      console.error('❌ Failed to fetch material types:', error);
      Alert.alert('错误', getErrorMsg(error) || '加载原材料类型失败');
      setMaterials([]);
    } finally {
      setLoading(false);
    }
  };

  const filteredMaterials = materials.filter(m =>
    m.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // 重置Modal状态到初始状态
  const resetModalState = () => {
    setShowAddForm(false);           // 显示列表而非表单
    setSearchQuery('');               // 清空搜索
    // 清空添加表单
    setNewMaterialName('');
    setNewMaterialCategory('鱼类');  // 重置为默认值
  };

  // Modal关闭处理
  const handleModalClose = () => {
    setModalVisible(false);
    resetModalState();  // 关闭时重置
  };

  // Modal打开处理
  const handleModalOpen = () => {
    resetModalState();      // 打开时也重置（确保干净状态）
    setModalVisible(true);
  };

  const handleSelect = (type: string, typeId?: string) => {
    onSelect(type, typeId);
    handleModalClose();  // 使用统一的关闭函数
  };

  const handleCreateMaterial = async () => {
    if (!newMaterialName.trim()) {
      Alert.alert('验证错误', '请输入原料名称');
      return;
    }

    Alert.alert('请先配置16位编码', '快捷新建不能跳过 L1、L2、L3。请到“原料类型管理”完成建档后再选择。');
  };

  return (
    <View>
      <TouchableOpacity
        onPress={() => {
          console.log('🔍 Opening material type selector');
          handleModalOpen();
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
        onRequestClose={handleModalClose}
      >
        <View style={styles.modalContainer}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text variant="titleLarge">选择原料类型</Text>
              <Button onPress={handleModalClose}>取消</Button>
            </View>

            <Searchbar
              placeholder="搜索原料..."
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
                data={filteredMaterials}
                keyExtractor={(item) => item.id}
                renderItem={({ item }) => (
                  <>
                    <List.Item
                      title={item.name}
                      description={item.category}
                      onPress={() => handleSelect(item.name, item.id)}
                      right={props => value === item.name ? <List.Icon {...props} icon="check" color="#2196F3" /> : null}
                    />
                    <Divider />
                  </>
                )}
                ListEmptyComponent={
                  <View style={styles.emptyContainer}>
                    <Text variant="bodyMedium" style={styles.emptyText}>
                      {searchQuery ? '未找到匹配的原料类型' : '暂无原料类型'}
                    </Text>
                  </View>
                }
                ListFooterComponent={
                  !showAddForm ? (
                    // 添加按钮
                    <TouchableOpacity
                      style={styles.addButton}
                      onPress={() => setShowAddForm(true)}
                    >
                      <Text style={styles.addButtonText}>➕ 找不到？点击添加新原料类型</Text>
                    </TouchableOpacity>
                  ) : (
                    // 创建表单
                    <View style={styles.addForm}>
                      <Text variant="titleMedium" style={styles.formTitle}>添加新原料</Text>

                      <TextInput
                        label="原料名称 *"
                        value={newMaterialName}
                        onChangeText={setNewMaterialName}
                        mode="outlined"
                        placeholder="例如: 黄鱼"
                        style={styles.formInput}
                        autoFocus
                      />

                      <Text variant="bodySmall" style={styles.formLabel}>分类</Text>
                      <SegmentedButtons
                        value={newMaterialCategory}
                        onValueChange={setNewMaterialCategory}
                        buttons={[
                          { value: '鱼类', label: '鱼类' },
                          { value: '虾蟹类', label: '虾蟹' },
                          { value: '贝类', label: '贝类' },
                          { value: '头足类', label: '头足' },
                          { value: '其他', label: '其他' },
                        ]}
                        style={styles.formSegment}
                      />

                      <View style={styles.formActions}>
                        <Button
                          mode="outlined"
                          onPress={() => setShowAddForm(false)}
                          disabled={creating}
                        >
                          取消
                        </Button>
                        <Button
                          mode="contained"
                          onPress={handleCreateMaterial}
                          loading={creating}
                          disabled={creating}
                        >
                          保存
                        </Button>
                      </View>
                    </View>
                  )
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
  },
  addButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
    marginTop: 8,
    marginBottom: 8,
    marginHorizontal: 16,
    backgroundColor: '#E3F2FD',
    borderRadius: 8,
  },
  addButtonText: {
    color: '#2196F3',
    fontSize: 14,
    fontWeight: '500',
  },
  addForm: {
    padding: 16,
    backgroundColor: '#F5F5F5',
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
    marginTop: 8,
  },
  formTitle: {
    marginBottom: 16,
    fontWeight: '600',
  },
  formLabel: {
    color: '#757575',
    marginBottom: 8,
    marginTop: 8,
  },
  formInput: {
    marginBottom: 12,
  },
  formSegment: {
    marginBottom: 16,
  },
  formActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    marginTop: 8,
  },
});

export default MaterialTypeSelector;
