import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import {
  Text,
  Appbar,
  FAB,
  Card,
  Chip,
  Portal,
  Modal,
  TextInput,
  Button,
  ActivityIndicator,
  List,
  Searchbar,
  SegmentedButtons,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { supplierApiClient, Supplier, CreateSupplierRequest } from '../../services/api/supplierApiClient';
import { useAuthStore } from '../../store/authStore';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { hasModulePermission } from '../../utils/permissionHelper';

// 创建SupplierManagement专用logger
const supplierLogger = logger.createContextLogger('SupplierManagement');

/**
 * 供应商管理页面
 *
 * 权限口径 (APP-RBAC-002, 2026-08-02):
 *   读 — SupplierController 的 GET 接口没有 @RequirePermission, 任何已登录的工厂用户都能读,
 *        因此本页不做页面级角色白名单; 能导航到这里就能看列表。
 *   写 — SupplierController 的写接口标 @RequirePermission({"procurement:read_write","finance:read_write"}),
 *        前端 canWrite 与之逐字对齐; 后端仍是最终真值。
 * 修复前: canManage 只认平台/超管/权限管理员/部门管理员, 采购主管点开供应商 Tab 直接被整页拒绝。
 */
export default function SupplierManagementScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('management');
  const { user } = useAuthStore();

  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('all');
  const [modalVisible, setModalVisible] = useState(false);
  const [editingSupplier, setEditingSupplier] = useState<Supplier | null>(null);

  // 权限控制: 读写分离, 与后端注解对齐 (见文件头注释)
  const canWrite =
    hasModulePermission(user, 'procurement', 'write') ||
    hasModulePermission(user, 'finance', 'write');

  // 表单数据
  const [formData, setFormData] = useState<Partial<CreateSupplierRequest>>({
    supplierCode: '',
    name: '',
    contactPerson: '',
    phone: '',
    email: '',
    address: '',
    businessType: '',
    creditLevel: 'B',
    creditLimit: 0,
    deliveryArea: '',
    paymentTerms: '月结30天',
  });

  useEffect(() => {
    loadSuppliers();
  }, []);

  const loadSuppliers = async () => {
    try {
      setLoading(true);
      const factoryId = user?.factoryId || user?.factoryUser?.factoryId || 'F001';
      
      if (!factoryId) {
        Alert.alert(t('common.error'), '无法获取工厂ID');
        setSuppliers([]);
        return;
      }

      const response = await supplierApiClient.getSuppliers({
        factoryId,
        page: 1, // 后端从1开始分页
        size: 100,
      });

      if (response && response.data) {
        const supplierCount = Array.isArray(response.data) ? response.data.length : 0;
        setSuppliers(Array.isArray(response.data) ? response.data : []);
        supplierLogger.info('供应商列表加载成功', {
          supplierCount,
          factoryId,
        });
      } else {
        setSuppliers([]);
        supplierLogger.warn('供应商API返回空数据', { factoryId });
      }
    } catch (error) {
      supplierLogger.error('加载供应商列表失败', error, { factoryId: user?.factoryId });
      const errorMessage = getErrorMsg(error) || (error instanceof Error ? error.message : '加载供应商列表失败');
      Alert.alert('错误', errorMessage);
      setSuppliers([]);
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      loadSuppliers();
      return;
    }

    try {
      setLoading(true);
      supplierLogger.debug('搜索供应商', { keyword: searchQuery, factoryId: user?.factoryId });
      const results = await supplierApiClient.searchSuppliers({
        keyword: searchQuery,
        factoryId: user?.factoryId,
      });
      setSuppliers(results);
      supplierLogger.info('搜索完成', { resultCount: results.length, keyword: searchQuery });
    } catch (error) {
      supplierLogger.error('搜索失败', error, { keyword: searchQuery });
      Alert.alert('错误', '搜索失败');
    } finally {
      setLoading(false);
    }
  };

  const handleAdd = () => {
    setEditingSupplier(null);
    setFormData({
      supplierCode: '',
      name: '',
      contactPerson: '',
      phone: '',
      email: '',
      address: '',
      businessType: '',
      creditLevel: 'B',
      creditLimit: 0,
      deliveryArea: '',
      paymentTerms: '月结30天',
    });
    setModalVisible(true);
  };

  const handleEdit = (supplier: Supplier) => {
    setEditingSupplier(supplier);
    setFormData({
      supplierCode: supplier.supplierCode,
      name: supplier.name,
      contactPerson: supplier.contactPerson || '',
      phone: supplier.phone || '',
      email: supplier.email || '',
      address: supplier.address || '',
      businessType: supplier.businessType || '',
      creditLevel: supplier.creditLevel || 'B',
      creditLimit: supplier.creditLimit || 0,
      deliveryArea: supplier.deliveryArea || '',
      paymentTerms: supplier.paymentTerms || '月结30天',
    });
    setModalVisible(true);
  };

  const handleSave = async () => {
    // 验证必填项
    if (!formData.name || !formData.phone) {
      Alert.alert(t('common.confirm'), t('supplierManagement.messages.requiredFields'));
      return;
    }

    try {
      if (editingSupplier) {
        // 更新供应商
        await supplierApiClient.updateSupplier(
          editingSupplier.id,
          formData as Partial<CreateSupplierRequest>,
          user?.factoryId
        );
        supplierLogger.info('供应商信息已更新', {
          supplierId: editingSupplier.id,
          supplierName: formData.name,
        });
        Alert.alert(t('common.success'), t('supplierManagement.messages.updateSuccess'));
      } else {
        // 创建供应商
        await supplierApiClient.createSupplier(
          formData as CreateSupplierRequest,
          user?.factoryId
        );
        supplierLogger.info('供应商创建成功', {
          supplierCode: formData.supplierCode,
          supplierName: formData.name,
        });
        Alert.alert(t('common.success'), t('supplierManagement.messages.createSuccess'));
      }

      setModalVisible(false);
      loadSuppliers();
    } catch (error) {
      supplierLogger.error('保存供应商失败', error, {
        isEdit: !!editingSupplier,
        supplierCode: formData.supplierCode,
      });
      Alert.alert(t('common.error'), getErrorMsg(error) || '操作失败');
    }
  };

  const handleDelete = (supplierId: string, supplierName: string) => {
    Alert.alert(
      '确认删除',
      `确定要删除供应商 "${supplierName}" 吗？此操作不可撤销。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: async () => {
            try {
              await supplierApiClient.deleteSupplier(supplierId, user?.factoryId);
              supplierLogger.info('供应商已删除', { supplierId, supplierName });
              Alert.alert('成功', '供应商已删除');
              loadSuppliers();
            } catch (error) {
              supplierLogger.error('删除供应商失败', error, { supplierId, supplierName });
              Alert.alert('错误', getErrorMsg(error) || '删除失败');
            }
          },
        },
      ]
    );
  };

  const handleToggleStatus = async (supplierId: string, currentStatus: boolean) => {
    try {
      await supplierApiClient.toggleSupplierStatus(
        supplierId,
        !currentStatus,
        user?.factoryId
      );
      const newStatus = !currentStatus;
      supplierLogger.info('供应商状态已切换', {
        supplierId,
        newStatus: newStatus ? '启用' : '停用',
      });
      Alert.alert('成功', currentStatus ? '供应商已停用' : '供应商已启用');
      loadSuppliers();
    } catch (error) {
      supplierLogger.error('切换状态失败', error, { supplierId, currentStatus });
      Alert.alert('错误', getErrorMsg(error) || '操作失败');
    }
  };

  // 筛选供应商
  const filteredSuppliers = suppliers.filter(s => {
    if (filterStatus === 'active' && !s.isActive) return false;
    if (filterStatus === 'inactive' && s.isActive) return false;
    return true;
  });

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="供应商管理" />
        <Appbar.Action icon="refresh" onPress={loadSuppliers} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Search */}
        <Searchbar
          placeholder="搜索供应商名称、编码、联系人"
          onChangeText={setSearchQuery}
          value={searchQuery}
          onSubmitEditing={handleSearch}
          style={styles.searchbar}
        />

        {/* Filter */}
        <Card style={styles.filterCard}>
          <Card.Content>
            <SegmentedButtons
              value={filterStatus}
              onValueChange={setFilterStatus}
              buttons={[
                { value: 'all', label: '全部' },
                { value: 'active', label: '已启用' },
                { value: 'inactive', label: '已停用' },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{suppliers.length}</Text>
                <Text style={styles.statLabel}>总数</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {suppliers.filter(s => s.isActive).length}
                </Text>
                <Text style={styles.statLabel}>启用</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {suppliers.filter(s => !s.isActive).length}
                </Text>
                <Text style={styles.statLabel}>停用</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Suppliers List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : filteredSuppliers.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="store-outline" color="#999" />
              <Text style={styles.emptyText}>暂无供应商</Text>
              <Text style={styles.emptyHint}>点击右下角"+"按钮创建供应商</Text>
            </Card.Content>
          </Card>
        ) : (
          filteredSuppliers.map((supplier) => (
            <Card key={supplier.id} style={styles.supplierCard}>
              <Card.Content>
                {/* Header */}
                <View style={styles.supplierHeader}>
                  <View style={styles.supplierTitleRow}>
                    <View>
                      <Text style={styles.supplierName}>{supplier.name}</Text>
                      <Text style={styles.supplierCode}>编码: {supplier.supplierCode}</Text>
                    </View>
                    <Chip
                      mode="flat"
                      compact
                      style={[
                        styles.statusChip,
                        { backgroundColor: supplier.isActive ? '#E8F5E9' : '#FFEBEE' }
                      ]}
                      textStyle={{
                        color: supplier.isActive ? '#2E7D32' : '#C62828',
                        fontSize: 11
                      }}
                    >
                      {supplier.isActive ? '启用' : '停用'}
                    </Chip>
                  </View>
                </View>

                {/* Info */}
                <View style={styles.supplierInfo}>
                  {supplier.contactPerson && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="account" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{supplier.contactPerson}</Text>
                    </View>
                  )}
                  {supplier.phone && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="phone" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{supplier.phone}</Text>
                    </View>
                  )}
                  {supplier.businessType && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="briefcase" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{supplier.businessType}</Text>
                    </View>
                  )}
                  {supplier.address && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="map-marker" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{supplier.address}</Text>
                    </View>
                  )}
                </View>

                {/* Actions */}
                {/* Actions — 只读角色不渲染任何 mutation 入口 */}
                {canWrite && (
                  <View style={styles.actionRow}>
                    <Button
                      mode="outlined"
                      icon="pencil"
                      onPress={() => handleEdit(supplier)}
                      style={styles.actionButton}
                      compact
                    >
                      编辑
                    </Button>
                    <Button
                      mode="outlined"
                      icon={supplier.isActive ? 'pause' : 'play'}
                      onPress={() => handleToggleStatus(supplier.id, supplier.isActive)}
                      style={styles.actionButton}
                      compact
                    >
                      {supplier.isActive ? '停用' : '启用'}
                    </Button>
                    <Button
                      mode="outlined"
                      icon="delete"
                      onPress={() => handleDelete(supplier.id, supplier.name)}
                      style={styles.actionButton}
                      compact
                      textColor="#C62828"
                    >
                      删除
                    </Button>
                  </View>
                )}
              </Card.Content>
            </Card>
          ))
        )}

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* Create/Edit Modal */}
      <Portal>
        <Modal
          visible={modalVisible}
          onDismiss={() => setModalVisible(false)}
          contentContainerStyle={styles.modalContent}
        >
          <Text style={styles.modalTitle}>
            {editingSupplier ? '编辑供应商' : '创建供应商'}
          </Text>

          <ScrollView style={styles.modalScrollView}>

            {/* Supplier Code */}
            {
              editingSupplier && <TextInput
                label="供应商编码 *"
                value={formData.supplierCode}
                onChangeText={(text) => setFormData({ ...formData, supplierCode: text })}
                mode="outlined"
                style={styles.input}
                disabled={!!editingSupplier}
                placeholder="例如：SUP001"
              />
            }

            {/* Name */}
            <TextInput
              label="供应商名称 *"
              value={formData.name}
              onChangeText={(text) => setFormData({ ...formData, name: text })}
              mode="outlined"
              style={styles.input}
              placeholder="例如：XX食品供应商"
            />

            {/* Contact Person */}
            <TextInput
              label="联系人"
              value={formData.contactPerson}
              onChangeText={(text) => setFormData({ ...formData, contactPerson: text })}
              mode="outlined"
              style={styles.input}
              placeholder="例如：张三"
            />

            {/* Contact Phone */}
            <TextInput
              label="联系电话 *"
              value={formData.phone}
              onChangeText={(text) => setFormData({ ...formData, phone: text })}
              mode="outlined"
              style={styles.input}
              keyboardType="phone-pad"
              placeholder="例如：13800138000"
            />

            {/* Email */}
            <TextInput
              label="邮箱"
              value={formData.email}
              onChangeText={(text) => setFormData({ ...formData, email: text })}
              mode="outlined"
              style={styles.input}
              keyboardType="email-address"
              placeholder="例如：supplier@example.com"
            />

            {/* Address */}
            <TextInput
              label="地址"
              value={formData.address}
              onChangeText={(text) => setFormData({ ...formData, address: text })}
              mode="outlined"
              style={styles.input}
              multiline
              numberOfLines={2}
              placeholder="详细地址"
            />

            {/* Business Type */}
            <TextInput
              label="业务类型"
              value={formData.businessType}
              onChangeText={(text) => setFormData({ ...formData, businessType: text })}
              mode="outlined"
              style={styles.input}
              placeholder="例如：水产品供应"
            />
          </ScrollView>

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
              {editingSupplier ? '保存' : '创建'}
            </Button>
          </View>
        </Modal>
      </Portal>

      {/* FAB */}
      {canWrite && (
        <FAB
          icon="plus"
          style={styles.fab}
          onPress={handleAdd}
          label="创建供应商"
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
  filterCard: {
    margin: 16,
    marginBottom: 8,
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
    fontSize: 24,
    fontWeight: 'bold',
    color: '#2196F3',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  loadingContainer: {
    padding: 40,
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
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
  supplierCard: {
    margin: 16,
    marginBottom: 8,
  },
  supplierHeader: {
    marginBottom: 12,
  },
  supplierTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  supplierName: {
    fontSize: 18,
    fontWeight: 'bold',
  },
  supplierCode: {
    fontSize: 13,
    color: '#666',
    marginTop: 2,
  },
  statusChip: {
    height: 32,
  },
  supplierInfo: {
    marginBottom: 12,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  infoIcon: {
    margin: 0,
    marginRight: 4,
    width: 28,
  },
  infoText: {
    fontSize: 13,
    color: '#666',
    flex: 1,
  },
  actionRow: {
    flexDirection: 'row',
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
    marginBottom: 20,
  },
  input: {
    marginBottom: 16,
  },
  modalScrollView: {
    flexGrow: 0,
    flexShrink: 1,
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
    height: 80,
  },
});
