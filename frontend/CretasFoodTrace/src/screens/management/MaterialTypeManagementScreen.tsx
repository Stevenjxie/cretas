import React, { useState, useEffect, useRef } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import {
  Text,
  Appbar,
  FAB,
  Card,
  List,
  Chip,
  IconButton,
  Portal,
  Modal,
  TextInput,
  Button,
  ActivityIndicator,
  Searchbar,
  Menu,
  Divider,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { materialTypeApiClient, MaterialType, CreateMaterialTypeRequest } from '../../services/api/materialTypeApiClient';
import { materialSpecApiClient, DEFAULT_SPEC_CONFIG, SpecConfig } from '../../services/api/materialSpecApiClient';
import { dictionaryApiClient, DictionaryItem, UnitItem } from '../../services/api/dictionaryApiClient';
import { materialPackagingApiClient, MaterialPackagingHierarchy } from '../../services/api/materialPackagingApiClient';
import {
  DynamicForm,
  DynamicFormRef,
  FormSchema,
  schemaService,
  rawMaterialTypeSchema,
} from '../../formily';
import { useAuthStore } from '../../store/authStore';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { canManageBasicData, getPermissionDebugInfo, getFactoryId } from '../../utils/permissionHelper';

// 创建MaterialTypeManagement专用logger
const materialTypeLogger = logger.createContextLogger('MaterialTypeManagement');

/**
 * 原材料类型管理页面
 * 权限：super_admin、platform_admin
 * 功能：原材料类型CRUD、状态管理、搜索筛选
 */
export default function MaterialTypeManagementScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();
  const factoryId = getFactoryId(user);

  const [materialTypes, setMaterialTypes] = useState<MaterialType[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [modalVisible, setModalVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<MaterialType | null>(null);

  // Menu visibility states (仅包装层级仍用 Menu, 主表单字段已迁到 DynamicForm)
  const [level2UnitMenuVisible, setLevel2UnitMenuVisible] = useState(false);
  const [level3UnitMenuVisible, setLevel3UnitMenuVisible] = useState(false);

  // 包装层级状态 (一级 = formData.unit, 二三级在此; 空字符串表示未填)
  const [packaging, setPackaging] = useState<{
    level1PerLevel2: string;
    level2Unit: string;
    level2PerLevel3: string;
    level3Unit: string;
  }>({ level1PerLevel2: '', level2Unit: '', level2PerLevel3: '', level3Unit: '' });

  // 规格配置状态
  const [specConfig, setSpecConfig] = useState<SpecConfig>(DEFAULT_SPEC_CONFIG);
  const [customSpecMode, setCustomSpecMode] = useState(false);
  const [customSpecValue, setCustomSpecValue] = useState('');

  // 权限控制 - 使用统一的权限检查工具
  const canManage = canManageBasicData(user);

  // 权限检查日志
  useEffect(() => {
    const debugInfo = getPermissionDebugInfo(user);
    materialTypeLogger.debug('权限检查', {
      ...debugInfo,
      canManage,
    });
  }, [user]);

  // 字典选项 (从后端加载, 可被 Canvas 字典管理页修改)
  const [categoryOptions, setCategoryOptions] = useState<string[]>([]);
  const [unitOptions, setUnitOptions] = useState<string[]>([]);
  const [storageTypeOptions, setStorageTypeOptions] = useState<string[]>([]);
  const [dictsLoaded, setDictsLoaded] = useState(false);

  // DynamicForm 状态 — Canvas 可改字段顺序/隐藏/新增自定义字段
  const formRef = useRef<DynamicFormRef>(null);
  const [dynamicSchema, setDynamicSchema] = useState<FormSchema>(rawMaterialTypeSchema);
  const [schemaReady, setSchemaReady] = useState(false);

  // 编辑模式下若历史值不在字典中, 临时合入下拉, 避免值丢失
  const mergeHistoricValue = (options: string[], current?: string): string[] => {
    if (current && current.trim() !== '' && !options.includes(current)) {
      return [current, ...options];
    }
    return options;
  };

  // 表单状态
  const [formData, setFormData] = useState<Partial<CreateMaterialTypeRequest>>({
    code: '', // 将由后端自动生成
    name: '',
    category: '',
    unit: 'kg',
    shelfLifeDays: 0,
    storageType: '新鲜',
    notes: '',
  });

  useEffect(() => {
    loadMaterialTypes();
    loadSpecConfig();
    loadDictionaries();
  }, []);

  const loadDictionaries = async () => {
    try {
      // 1. 拉字典 + Canvas 自定义 schema
      const [categories, units, storageTypes, mergedSchemaResult] = await Promise.all([
        dictionaryApiClient.getEnums('MATERIAL_CATEGORY', factoryId),
        dictionaryApiClient.getUnits(undefined, factoryId),
        dictionaryApiClient.getEnums('MATERIAL_STORAGE_TYPE', factoryId),
        schemaService.getMergedSchema('RAW_MATERIAL_TYPE', rawMaterialTypeSchema, factoryId),
      ]);

      const categoryLabels = categories.map((c: DictionaryItem) => c.enumLabel);
      // 单位用 unitSymbol 存储 (跟历史数据保持一致)
      const unitLabels = units.map((u: UnitItem) => u.unitSymbol || u.unitCode);
      const storageLabels = storageTypes.map((s: DictionaryItem) => s.enumLabel);

      setCategoryOptions(categoryLabels);
      setUnitOptions(unitLabels);
      setStorageTypeOptions(storageLabels);

      // 2. 把字典枚举注入 schema (DynamicForm 的 Select 读 enum)
      const schemaWithEnums = injectEnumsIntoSchema(mergedSchemaResult.schema, {
        category: categoryLabels.map((l) => ({ label: l, value: l })),
        unit: unitLabels.map((l) => ({ label: l, value: l })),
        storageType: storageLabels.map((l) => ({ label: l, value: l })),
      });
      setDynamicSchema(schemaWithEnums);
      setSchemaReady(true);
      setDictsLoaded(true);

      materialTypeLogger.info('字典 + Canvas schema 加载成功', {
        categories: categoryLabels.length,
        units: unitLabels.length,
        storageTypes: storageLabels.length,
        canvasCustom: mergedSchemaResult.isCustomized,
        customFieldNames: mergedSchemaResult.customFieldNames,
      });
    } catch (error) {
      materialTypeLogger.warn('字典/Schema 加载失败, 退回默认 schema', error);
      setDynamicSchema(rawMaterialTypeSchema);
      setSchemaReady(true);
      setDictsLoaded(true);
    }
  };

  // 把 enum 注入到 schema 指定字段 (浅克隆, 不破坏原 schema)
  const injectEnumsIntoSchema = (
    base: FormSchema,
    enums: Record<string, Array<{ label: string; value: string }>>,
  ): FormSchema => {
    const properties = { ...base.properties };
    for (const [field, options] of Object.entries(enums)) {
      if (properties[field]) {
        properties[field] = { ...properties[field], type: properties[field].type || 'string', enum: options };
      }
    }
    return { ...base, properties };
  };

  const loadSpecConfig = async () => {
    try {
      materialTypeLogger.debug('加载规格配置', { factoryId });
      const response = await materialSpecApiClient.getSpecConfig(factoryId);
      materialTypeLogger.info('规格配置加载成功', { hasData: !!response?.data });
      // 确保 specConfig 不会被设置为 undefined
      setSpecConfig(response?.data || DEFAULT_SPEC_CONFIG);
    } catch (error) {
      materialTypeLogger.warn('规格配置加载失败，使用默认配置', error);
      setSpecConfig(DEFAULT_SPEC_CONFIG);
    }
  };

  const loadMaterialTypes = async () => {
    try {
      setLoading(true);
      materialTypeLogger.debug('开始加载原材料类型', { factoryId });

      const response = await materialTypeApiClient.getActiveMaterialTypes(factoryId);

      // 统一响应处理：response.data 应该是数组
      const materialData = response?.data || [];

      materialTypeLogger.info('原材料类型加载成功', {
        count: materialData.length,
        factoryId,
      });
      setMaterialTypes(materialData);
    } catch (error) {
      materialTypeLogger.error('加载原材料类型失败', error as Error, { factoryId });
      const errorMessage = error instanceof Error ? error.message : '加载原材料类型失败';
      Alert.alert('错误', errorMessage);
      setMaterialTypes([]);
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      loadMaterialTypes();
      return;
    }

    try {
      setLoading(true);
      const response = await materialTypeApiClient.searchMaterialTypes(searchQuery, factoryId);

      // 统一处理分页响应：response.data.content
      const searchData = response?.data?.content || response?.data || [];

      materialTypeLogger.info('搜索完成', {
        query: searchQuery,
        resultCount: searchData.length,
      });
      setMaterialTypes(searchData);
    } catch (error) {
      materialTypeLogger.error('搜索失败', error as Error, { query: searchQuery });
      const errorMessage = error instanceof Error ? error.message : '搜索失败';
      Alert.alert('错误', errorMessage);
      setMaterialTypes([]);
    } finally {
      setLoading(false);
    }
  };

  const handleAdd = () => {
    setEditingItem(null);
    setFormData({
      code: '', // 自动生成，不需要用户输入
      name: '',
      category: '', // 用户从字典下拉选择
      unit: 'kg',
      shelfLifeDays: 7, // 默认保质期7天
      storageType: storageTypeOptions[0] || '新鲜',
      notes: '',
    });
    setCustomSpecMode(false);
    setCustomSpecValue('');
    setPackaging({ level1PerLevel2: '', level2Unit: '', level2PerLevel3: '', level3Unit: '' });
    setModalVisible(true);
  };

  // 智能默认单位: 新建模式下, name 或 category 变化时查询相似原料的单位
  // 用 ref 避免 dictionary 加载完成前误触发, 加 400ms debounce
  useEffect(() => {
    if (!modalVisible || editingItem) return;
    const name = formData.name?.trim();
    if (!name || name.length < 2) return;

    const handle = setTimeout(async () => {
      const suggested = await dictionaryApiClient.suggestUnit(name, formData.category, factoryId);
      if (suggested) {
        setFormData((prev) => ({ ...prev, unit: suggested }));
        // 同步到 DynamicForm
        formRef.current?.setFieldValue('unit', suggested);
        materialTypeLogger.debug('智能填充单位', { name, category: formData.category, unit: suggested });
      }
    }, 400);
    return () => clearTimeout(handle);
  }, [formData.name, formData.category, modalVisible, editingItem, factoryId]);

  // Modal 打开时同步 formData 到 DynamicForm (覆盖 useMemo 锁定的 initialValues)
  useEffect(() => {
    if (modalVisible && schemaReady) {
      // 微任务延迟, 确保 DynamicForm 已挂载
      const t = setTimeout(() => {
        formRef.current?.setValues(formData);
      }, 0);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [modalVisible, schemaReady]);

  // 编辑老数据时, 若历史值不在字典 enum 中 (例旧 category="海鲜"), 注入避免丢失
  useEffect(() => {
    if (modalVisible && editingItem && schemaReady) {
      setDynamicSchema((prev) => {
        const properties = { ...prev.properties };
        const ensureEnum = (field: string, value?: string) => {
          if (!value || !value.trim()) return;
          const fieldSchema = properties[field];
          if (!fieldSchema) return;
          const current = fieldSchema.enum as Array<{ label: string; value: string }> | undefined;
          if (!current) return;
          if (!current.find((o) => o.value === value)) {
            properties[field] = {
              ...fieldSchema,
              type: fieldSchema.type || 'string',
              enum: [{ label: value, value }, ...current],
            };
          }
        };
        ensureEnum('category', editingItem.category);
        ensureEnum('unit', editingItem.unit);
        ensureEnum('storageType', editingItem.storageType);
        return { ...prev, properties };
      });
    }
  }, [modalVisible, editingItem, schemaReady]);

  const handleEdit = async (item: MaterialType) => {
    setEditingItem(item);
    setFormData({
      code: item.code,
      name: item.name,
      category: item.category || '',
      unit: item.unit,
      shelfLifeDays: item.shelfLifeDays || 0,
      storageType: item.storageType || '新鲜',
      notes: item.notes || '',
    });
    setCustomSpecMode(false);
    setCustomSpecValue('');
    // 加载现有包装层级 (无配置返回 null)
    setPackaging({ level1PerLevel2: '', level2Unit: '', level2PerLevel3: '', level3Unit: '' });
    try {
      const existing = await materialPackagingApiClient.getByMaterial(item.id, factoryId);
      if (existing) {
        setPackaging({
          level1PerLevel2: existing.level1PerLevel2 != null ? String(existing.level1PerLevel2) : '',
          level2Unit: existing.level2Unit || '',
          level2PerLevel3: existing.level2PerLevel3 != null ? String(existing.level2PerLevel3) : '',
          level3Unit: existing.level3Unit || '',
        });
      }
    } catch (err) {
      materialTypeLogger.warn('加载包装层级失败 (将以空值打开)', err);
    }
    setModalVisible(true);
  };

  const handleSave = async () => {
    // 验证必填项（编码不需要验证，由后端自动生成）
    if (!formData.name || !formData.category || !formData.unit || !formData.storageType) {
      Alert.alert('提示', '原料名称、类别、单位和储存类型不能为空');
      return;
    }

    // 包装层级前端校验 (后端有 DB CHECK + service 校验, 前端给即时反馈)
    const hasL2Unit = packaging.level2Unit.trim() !== '';
    const hasL2Qty = packaging.level1PerLevel2.trim() !== '' && Number(packaging.level1PerLevel2) > 0;
    const hasL3Unit = packaging.level3Unit.trim() !== '';
    const hasL3Qty = packaging.level2PerLevel3.trim() !== '' && Number(packaging.level2PerLevel3) > 0;
    if (hasL2Unit !== hasL2Qty) {
      Alert.alert('提示', '二级单位和换算数量必须同时填写或同时清空');
      return;
    }
    if (hasL3Unit !== hasL3Qty) {
      Alert.alert('提示', '三级单位和换算数量必须同时填写或同时清空');
      return;
    }
    if (hasL3Unit && !hasL2Unit) {
      Alert.alert('提示', '必须先配置二级单位才能配置三级');
      return;
    }

    try {
      let materialId: string;
      if (editingItem) {
        await materialTypeApiClient.updateMaterialType(
          editingItem.id,
          formData as Partial<CreateMaterialTypeRequest>,
          factoryId
        );
        materialId = editingItem.id;
        materialTypeLogger.info('原材料类型更新成功', { id: editingItem.id });
      } else {
        // 创建 - 移除code字段，让后端自动生成
        const { code, ...dataWithoutCode } = formData;
        const created = await materialTypeApiClient.createMaterialType(
          dataWithoutCode as CreateMaterialTypeRequest,
          factoryId
        );
        materialId = created.id;
        materialTypeLogger.info('原材料类型创建成功', { id: materialId, name: formData.name });
      }

      // 包装层级 upsert: 任一二级或三级配齐就保存; 否则不动 (编辑模式保留旧值)
      if (hasL2Unit || hasL3Unit) {
        await materialPackagingApiClient.upsert(materialId, {
          level1Unit: formData.unit!,
          level1PerLevel2: hasL2Unit ? Number(packaging.level1PerLevel2) : null,
          level2Unit: hasL2Unit ? packaging.level2Unit.trim() : null,
          level2PerLevel3: hasL3Unit ? Number(packaging.level2PerLevel3) : null,
          level3Unit: hasL3Unit ? packaging.level3Unit.trim() : null,
        }, factoryId);
        materialTypeLogger.info('包装层级保存成功', { materialId });
      } else if (editingItem) {
        // 编辑模式下用户清空了所有二三级 → 删除现有配置 (新建模式不需要 delete)
        try {
          await materialPackagingApiClient.delete(materialId, factoryId);
        } catch {
          // 没有现有配置时 delete 也会成功, 忽略错误
        }
      }

      Alert.alert('成功', editingItem ? '原材料类型更新成功' : '原材料类型创建成功');
      setModalVisible(false);
      loadMaterialTypes();
    } catch (error) {
      materialTypeLogger.error(editingItem ? '更新失败' : '创建失败', error as Error);
      const errorMessage = error instanceof Error ? error.message : (editingItem ? '更新失败' : '创建失败');
      Alert.alert('错误', errorMessage);
    }
  };

  const handleDelete = (item: MaterialType) => {
    Alert.alert(
      '确认删除',
      `确定要删除原材料类型"${item.name}"吗？此操作不可撤销。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: async () => {
            try {
              await materialTypeApiClient.deleteMaterialType(item.id, factoryId);
              Alert.alert('成功', '原材料类型删除成功');
              materialTypeLogger.info('原材料类型删除成功', { id: item.id });
              loadMaterialTypes();
            } catch (error) {
              materialTypeLogger.error('删除失败', error as Error, { itemId: item.id });
              const errorMessage = error instanceof Error ? error.message : '删除失败';
              Alert.alert('错误', errorMessage);
            }
          },
        },
      ]
    );
  };

  const handleToggleStatus = async (item: MaterialType) => {
    try {
      // 只更新 isActive 状态，使用 UpdateMaterialTypeRequest 接口
      const updateData = {
        isActive: !item.isActive,
      };

      materialTypeLogger.debug('切换原材料状态', {
        id: item.id,
        name: item.name,
        currentStatus: item.isActive,
        newStatus: !item.isActive,
      });

      await materialTypeApiClient.updateMaterialType(
        item.id,
        updateData,
        factoryId
      );
      Alert.alert('成功', item.isActive ? '已停用' : '已启用');
      materialTypeLogger.info('原材料状态切换成功', {
        id: item.id,
        name: item.name,
        newStatus: !item.isActive,
      });
      loadMaterialTypes();
    } catch (error) {
      materialTypeLogger.error('切换状态失败', error as Error, { itemId: item.id });
      const errorMessage = error instanceof Error ? error.message : '操作失败';
      Alert.alert('错误', errorMessage);
    }
  };

  if (!canManage) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="原材料类型管理" />
        </Appbar.Header>
        <View style={styles.noPermission}>
          <List.Icon icon="lock" color="#999" />
          <Text style={styles.noPermissionText}>您没有权限访问此页面</Text>
          <Text style={styles.noPermissionHint}>仅限工厂超管、权限管理员和部门管理员</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="原材料类型管理" />
        <Appbar.Action icon="refresh" onPress={loadMaterialTypes} />
      </Appbar.Header>

      {/* Content */}
      <ScrollView style={styles.content}>
        {/* Search */}
        <Searchbar
          placeholder="搜索原料编码、名称、类别"
          onChangeText={setSearchQuery}
          value={searchQuery}
          onSubmitEditing={handleSearch}
          style={styles.searchbar}
        />

        {/* Stats Card */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{materialTypes.length}</Text>
                <Text style={styles.statLabel}>总数</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {materialTypes.filter(m => m.isActive).length}
                </Text>
                <Text style={styles.statLabel}>启用中</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {new Set(materialTypes.map(m => m.category)).size}
                </Text>
                <Text style={styles.statLabel}>分类数</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Material Type List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : materialTypes.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="package-variant" color="#999" />
              <Text style={styles.emptyText}>暂无原材料类型</Text>
              <Text style={styles.emptyHint}>点击右下角"+"按钮添加原材料类型</Text>
            </Card.Content>
          </Card>
        ) : (
          materialTypes.map((item) => (
            <Card key={item.id} style={styles.itemCard} mode="elevated">
              <Card.Content>
                {/* Header Row */}
                <View style={styles.itemHeader}>
                  <View style={styles.leftHeader}>
                    <View style={styles.iconContainer}>
                      <List.Icon
                        icon="package-variant"
                        color="#fff"
                        style={styles.iconStyle}
                      />
                    </View>
                    <View style={styles.titleContainer}>
                      <Text style={styles.itemName}>{item.name}</Text>
                      <Text style={styles.itemCode}>{item.code}</Text>
                    </View>
                  </View>
                  <Chip
                    icon={item.isActive ? 'check-circle' : 'close-circle'}
                    mode="flat"
                    style={[
                      styles.statusChip,
                      { backgroundColor: item.isActive ? '#E8F5E9' : '#FFEBEE' },
                    ]}
                    textStyle={{
                      fontSize: 12,
                      fontWeight: '500',
                      color: item.isActive ? '#2E7D32' : '#C62828',
                    }}
                  >
                    {item.isActive ? '启用中' : '已停用'}
                  </Chip>
                </View>

                {/* Info Tags */}
                <View style={styles.tagsRow}>
                  {item.category && item.category.trim() !== '' && (
                    <Chip mode="outlined" style={styles.tagChip}>
                      {item.category}
                    </Chip>
                  )}
                  {item.storageType && item.storageType.trim() !== '' && (
                    <Chip mode="outlined" style={styles.tagChip}>
                      {item.storageType}
                    </Chip>
                  )}
                  {item.unit && item.unit.trim() !== '' && (
                    <Chip mode="outlined" style={styles.tagChip}>
                      {item.unit}
                    </Chip>
                  )}
                  {item.shelfLifeDays && item.shelfLifeDays > 0 && (
                    <Chip mode="outlined" style={styles.tagChip}>
                      {item.shelfLifeDays}天
                    </Chip>
                  )}
                </View>

                {/* Action Buttons */}
                <View style={styles.actionRow}>
                  <Button
                    mode="outlined"
                    icon={item.isActive ? 'eye' : 'eye-off'}
                    onPress={() => handleToggleStatus(item)}
                    style={styles.actionButton}
                  >
                    {item.isActive ? '停用' : '启用'}
                  </Button>
                  <Button
                    mode="outlined"
                    icon="pencil"
                    onPress={() => handleEdit(item)}
                    style={styles.actionButton}
                  >
                    编辑
                  </Button>
                  <Button
                    mode="outlined"
                    icon="delete"
                    onPress={() => handleDelete(item)}
                    style={styles.actionButton}
                    textColor="#F44336"
                  >
                    删除
                  </Button>
                </View>
              </Card.Content>
            </Card>
          ))
        )}

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* Add/Edit Modal */}
      <Portal>
        <Modal
          visible={modalVisible}
          onDismiss={() => setModalVisible(false)}
          contentContainerStyle={styles.modalContent}
        >
          <Text style={styles.modalTitle}>
            {editingItem ? '编辑原材料类型' : '添加原材料类型'}
          </Text>

          <ScrollView style={styles.modalScrollView} contentContainerStyle={{ paddingBottom: 16 }}>
            {/* 编辑时显示编码（只读），创建时自动生成不显示 */}
            {editingItem && (
              <TextInput
                label="原料编码"
                value={formData.code}
                mode="outlined"
                style={styles.input}
                disabled
                right={<TextInput.Icon icon="lock" />}
              />
            )}


            {/* 主表单字段 (Schema 驱动, Canvas 可改) */}
            {schemaReady ? (
              <DynamicForm
                ref={formRef}
                schema={dynamicSchema}
                initialValues={formData}
                showSubmitButton={false}
                scrollable={false}
                onValuesChange={(vals) => setFormData((prev) => ({ ...prev, ...vals }))}
              />
            ) : (
              <View style={{ padding: 16, alignItems: 'center' }}>
                <ActivityIndicator />
                <Text style={{ marginTop: 8, color: '#666' }}>加载表单 schema 中...</Text>
              </View>
            )}

            {/* 包装层级 (一级 = 单位字段, 二/三级可选) */}
            <View style={styles.sectionHeader}>
              <Text style={styles.sectionTitle}>包装层级（可选）</Text>
              <Text style={styles.sectionHint}>
                例: 三文鱼 一级 kg, 10 kg / 箱 (二级), 12 箱 / 柜 (三级)
              </Text>
            </View>

            <TextInput
              label={`一级单位 (基础)`}
              value={formData.unit || ''}
              mode="outlined"
              style={styles.input}
              disabled
              right={<TextInput.Icon icon="lock" />}
            />

            <View style={styles.packagingRow}>
              <TextInput
                label="一级数量 / 二级"
                value={packaging.level1PerLevel2}
                onChangeText={(text) => setPackaging({ ...packaging, level1PerLevel2: text })}
                mode="outlined"
                style={[styles.input, styles.packagingQty]}
                keyboardType="numeric"
                placeholder="10"
              />
              <Menu
                visible={level2UnitMenuVisible}
                onDismiss={() => setLevel2UnitMenuVisible(false)}
                anchor={
                  <TextInput
                    label="二级单位"
                    value={packaging.level2Unit}
                    mode="outlined"
                    style={[styles.input, styles.packagingUnit]}
                    editable={false}
                    placeholder="箱"
                    right={
                      packaging.level2Unit ? (
                        <TextInput.Icon icon="close" onPress={() => setPackaging({ ...packaging, level2Unit: '', level1PerLevel2: '' })} />
                      ) : (
                        <TextInput.Icon icon="menu-down" onPress={() => setLevel2UnitMenuVisible(true)} />
                      )
                    }
                    onPressIn={() => setLevel2UnitMenuVisible(true)}
                  />
                }
              >
                {mergeHistoricValue(unitOptions, packaging.level2Unit).map((u) => (
                  <Menu.Item
                    key={u}
                    onPress={() => {
                      setPackaging({ ...packaging, level2Unit: u });
                      setLevel2UnitMenuVisible(false);
                    }}
                    title={u}
                  />
                ))}
              </Menu>
            </View>

            <View style={styles.packagingRow}>
              <TextInput
                label="二级数量 / 三级"
                value={packaging.level2PerLevel3}
                onChangeText={(text) => setPackaging({ ...packaging, level2PerLevel3: text })}
                mode="outlined"
                style={[styles.input, styles.packagingQty]}
                keyboardType="numeric"
                placeholder="12"
                disabled={!packaging.level2Unit}
              />
              <Menu
                visible={level3UnitMenuVisible}
                onDismiss={() => setLevel3UnitMenuVisible(false)}
                anchor={
                  <TextInput
                    label="三级单位"
                    value={packaging.level3Unit}
                    mode="outlined"
                    style={[styles.input, styles.packagingUnit]}
                    editable={false}
                    disabled={!packaging.level2Unit}
                    placeholder="柜"
                    right={
                      packaging.level3Unit ? (
                        <TextInput.Icon icon="close" onPress={() => setPackaging({ ...packaging, level3Unit: '', level2PerLevel3: '' })} />
                      ) : (
                        <TextInput.Icon icon="menu-down" onPress={() => packaging.level2Unit && setLevel3UnitMenuVisible(true)} />
                      )
                    }
                    onPressIn={() => packaging.level2Unit && setLevel3UnitMenuVisible(true)}
                  />
                }
              >
                {mergeHistoricValue(unitOptions, packaging.level3Unit).map((u) => (
                  <Menu.Item
                    key={u}
                    onPress={() => {
                      setPackaging({ ...packaging, level3Unit: u });
                      setLevel3UnitMenuVisible(false);
                    }}
                    title={u}
                  />
                ))}
              </Menu>
            </View>

          </ScrollView>

          {/* 底部按钮 */}
          <View style={styles.modalActions}>
            <Button
              mode="outlined"
              onPress={() => setModalVisible(false)}
              style={styles.modalButton}
            >
              取消
            </Button>
            <Button
              mode="contained"
              onPress={handleSave}
              style={styles.modalButton}
            >
              {editingItem ? '更新' : '创建'}
            </Button>
          </View>
        </Modal>
      </Portal>

      {/* FAB */}
      {canManage && (
        <FAB
          icon="plus"
          style={styles.fab}
          onPress={handleAdd}
          label="添加原材料类型"
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  content: {
    flex: 1,
  },
  noPermission: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  noPermissionText: {
    fontSize: 18,
    color: '#999',
    marginTop: 16,
  },
  noPermissionHint: {
    fontSize: 14,
    color: '#bbb',
    marginTop: 8,
  },
  searchbar: {
    margin: 16,
    marginBottom: 8,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  loadingText: {
    marginTop: 16,
    fontSize: 16,
    color: '#666',
  },
  statsCard: {
    margin: 16,
    marginBottom: 8,
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#2196F3',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  emptyCard: {
    margin: 16,
  },
  emptyContent: {
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 16,
    color: '#999',
    marginTop: 8,
  },
  emptyHint: {
    fontSize: 14,
    color: '#bbb',
    marginTop: 8,
  },
  itemCard: {
    margin: 16,
    marginBottom: 12,
    borderRadius: 12,
    elevation: 2,
  },
  itemHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  leftHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  iconContainer: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: '#2196F3',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  iconStyle: {
    margin: 0,
    width: 28,
    height: 28,
  },
  titleContainer: {
    flex: 1,
  },
  itemName: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#212121',
    marginBottom: 2,
  },
  itemCode: {
    fontSize: 13,
    color: '#757575',
  },
  statusChip: {
    height: 32,
    paddingHorizontal: 4,
  },
  tagsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 16,
  },
  tagChip: {
    height: 32,
  },
  actionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 8,
    marginTop: 8,
  },
  actionButton: {
    flex: 1,
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
  modalContent: {
    backgroundColor: 'white',
    padding: 20,
    margin: 20,
    borderRadius: 8,
    maxHeight: 800,
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 16,
  },
  modalScrollView: {
    flexGrow: 0,
    flexShrink: 1,
  },
  input: {
    marginBottom: 16,
  },
  modalActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    paddingTop: 16,
    marginTop: 8,
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
  modalButton: {
    minWidth: 100,
  },
  bottomPadding: {
    height: 100,
  },
  sectionHeader: {
    marginTop: 8,
    marginBottom: 8,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#333',
  },
  sectionHint: {
    fontSize: 12,
    color: '#888',
    marginTop: 2,
  },
  packagingRow: {
    flexDirection: 'row',
    gap: 8,
  },
  packagingQty: {
    flex: 1.2,
  },
  packagingUnit: {
    flex: 1,
  },
});
