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
  Menu,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { customerApiClient, Customer, CreateCustomerRequest } from '../../services/api/customerApiClient';
import { useAuthStore } from '../../store/authStore';
import { hasModulePermission } from '../../utils/permissionHelper';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';

// 创建CustomerManagement专用logger
const customerLogger = logger.createContextLogger('CustomerManagement');

/**
 * 客户管理页面
 *
 * 权限口径 (APP-RBAC-001, 2026-08-02):
 *   读 — CustomerController 的 GET 接口没有 @RequirePermission, 任何已登录的工厂用户都能读,
 *        因此本页不做页面级角色白名单; 能导航到这里就能看列表。
 *   写 — CustomerController 的写接口标 @RequirePermission({"sales:read_write","finance:read_write"}),
 *        前端 canWrite 与之逐字对齐; 后端仍是最终真值。
 * 修复前: canManage 只认平台/超管/权限管理员/部门管理员, 销售主管点开客户 Tab 直接被整页拒绝。
 */
export default function CustomerManagementScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('management');
  const { user } = useAuthStore();

  const [customers, setCustomers] = useState<Customer[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('all');
  const [modalVisible, setModalVisible] = useState(false);
  const [editingCustomer, setEditingCustomer] = useState<Customer | null>(null);

  // Menu visibility states
  const [customerTypeMenuVisible, setCustomerTypeMenuVisible] = useState(false);
  const [industryMenuVisible, setIndustryMenuVisible] = useState(false);

  // 权限控制: 读写分离, 与后端注解对齐 (见文件头注释)
  const canWrite =
    hasModulePermission(user, 'sales', 'write') ||
    hasModulePermission(user, 'finance', 'write');

  // 表单数据
  const [formData, setFormData] = useState<Partial<CreateCustomerRequest>>({
    customerCode: '',
    name: '',
    contactPerson: '',
    phone: '',
    email: '',
    shippingAddress: '',
    businessType: '',
    customerType: 'distributor',
    industry: 'restaurant',
  });

  // 客户类型选项
  const customerTypeOptions = [
    { label: t('customerManagement.customerTypes.distributor'), value: 'distributor' },
    { label: t('customerManagement.customerTypes.retailer'), value: 'retailer' },
    { label: t('customerManagement.customerTypes.direct'), value: 'direct' },
    { label: t('customerManagement.customerTypes.other'), value: 'other' },
  ];

  // 行业选项
  const industryOptions = [
    { label: t('customerManagement.industries.restaurant'), value: 'restaurant' },
    { label: t('customerManagement.industries.supermarket'), value: 'supermarket' },
    { label: t('customerManagement.industries.ecommerce'), value: 'ecommerce' },
    { label: t('customerManagement.industries.foodProcessing'), value: 'food_processing' },
    { label: t('customerManagement.industries.other'), value: 'other' },
  ];

  useEffect(() => {
    loadCustomers();
  }, []);

  const loadCustomers = async () => {
    try {
      setLoading(true);
      const response = await customerApiClient.getCustomers({
        factoryId: user?.factoryId,
        page: 1, // 后端要求 page >= 1
        size: 100,
      });

      if (response.data && Array.isArray(response.data)) {
        customerLogger.info('客户列表加载成功', {
          customerCount: response.data.length,
          factoryId: user?.factoryId,
        });
        setCustomers(response.data);
      } else {
        customerLogger.warn('客户列表数据格式异常', {
          responseData: response.data,
          isArray: Array.isArray(response.data),
        });
        setCustomers([]);
        Alert.alert(t('common.error'), t('common.error'));
      }
    } catch (error) {
      customerLogger.error('加载客户列表失败', error as Error, {
        factoryId: user?.factoryId,
      });
      Alert.alert(t('common.error'), getErrorMsg(error) || t('common.error'));
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      loadCustomers();
      return;
    }

    try {
      setLoading(true);
      const results = await customerApiClient.searchCustomers({
        keyword: searchQuery,
        factoryId: user?.factoryId,
      });
      if (Array.isArray(results)) {
        customerLogger.info('客户搜索完成', {
          keyword: searchQuery,
          resultCount: results.length,
        });
        setCustomers(results);
      } else {
        customerLogger.warn('搜索结果数据格式异常', {
          results,
          isArray: Array.isArray(results),
        });
        setCustomers([]);
        Alert.alert(t('common.error'), t('common.error'));
      }
    } catch (error) {
      customerLogger.error('搜索客户失败', error as Error, {
        keyword: searchQuery,
        factoryId: user?.factoryId,
      });
      Alert.alert(t('common.error'), t('common.error'));
    } finally {
      setLoading(false);
    }
  };

  const handleAdd = () => {
    setEditingCustomer(null);
    setFormData({
      customerCode: '',
      name: '',
      contactPerson: '',
      phone: '',
      email: '',
      shippingAddress: '',
      businessType: '',
      customerType: 'distributor',
      industry: 'restaurant',
    });
    setModalVisible(true);
  };

  const handleEdit = (customer: Customer) => {
    setEditingCustomer(customer);
    setFormData({
      customerCode: customer.customerCode,
      name: customer.name,
      contactPerson: customer.contactPerson || '',
      phone: customer.phone || '',
      email: customer.email || '',
      shippingAddress: customer.shippingAddress || '',
      businessType: customer.businessType || '',
      customerType: customer.customerType || 'distributor',
      industry: customer.industry || '',
    });
    setModalVisible(true);
  };

  const handleSave = async () => {
    // 验证必填项 — backend requires name, contactPerson, phone, shippingAddress (@NotBlank)
    if (!formData.name?.trim() || !formData.phone?.trim()) {
      Alert.alert(t('common.hint'), t('customerManagement.messages.requiredFields'));
      return;
    }
    if (!formData.contactPerson?.trim() || !formData.shippingAddress?.trim()) {
      Alert.alert(t('common.hint'), t('customerManagement.messages.requiredFields'));
      return;
    }

    try {
      if (editingCustomer) {
        // 更新客户
        await customerApiClient.updateCustomer(
          editingCustomer.id,
          formData as Partial<CreateCustomerRequest>,
          user?.factoryId
        );
        Alert.alert(t('common.success'), t('customerManagement.messages.updateSuccess'));
      } else {
        // 创建客户 — omit customerCode (backend auto-generates), map customerType→type
        const { customerCode: _code, customerType, ...createFields } = formData;
        await customerApiClient.createCustomer(
          { ...createFields, customerType } as unknown as CreateCustomerRequest,
          user?.factoryId
        );
        Alert.alert(t('common.success'), t('customerManagement.messages.createSuccess'));
      }

      customerLogger.info(editingCustomer ? '客户更新成功' : '客户创建成功', {
        customerCode: formData.customerCode,
        customerName: formData.name,
      });
      setModalVisible(false);
      loadCustomers();
    } catch (error) {
      customerLogger.error('保存客户失败', error as Error, {
        isEdit: !!editingCustomer,
        customerCode: formData.customerCode,
      });
      Alert.alert(t('common.error'), getErrorMsg(error) || t('common.operationFailed'));
    }
  };

  const handleDelete = (customerId: string, customerName: string) => {
    Alert.alert(
      t('common.confirmDelete'),
      t('customerManagement.messages.deleteConfirm', { name: customerName }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t('common.delete'),
          style: 'destructive',
          onPress: async () => {
            try {
              await customerApiClient.deleteCustomer(customerId, user?.factoryId);
              customerLogger.info('客户删除成功', {
                customerId,
                customerName,
              });
              Alert.alert(t('common.success'), t('customerManagement.messages.deleteSuccess'));
              loadCustomers();
            } catch (error) {
              customerLogger.error('删除客户失败', error as Error, {
                customerId,
                customerName,
              });
              Alert.alert(t('common.error'), getErrorMsg(error) || t('common.deleteFailed'));
            }
          },
        },
      ]
    );
  };

  const handleToggleStatus = async (customerId: string, currentStatus: boolean) => {
    console.log("----", currentStatus);
    try {
      await customerApiClient.toggleCustomerStatus(
        customerId,
        !currentStatus,
        user?.factoryId
      );
      customerLogger.info('客户状态已切换', {
        customerId,
        newStatus: !currentStatus ? '启用' : '停用',
      });
      Alert.alert(t('common.success'), currentStatus ? t('customerManagement.messages.disabled') : t('customerManagement.messages.enabled'));
      loadCustomers();
    } catch (error) {
      customerLogger.error('切换客户状态失败', error as Error, {
        customerId,
        currentStatus,
      });
      Alert.alert(t('common.error'), getErrorMsg(error) || t('common.operationFailed'));
    }
  };

  const getCustomerTypeName = (type?: string) => {
    switch (type?.toLowerCase()) {
      case 'distributor': return t('customerManagement.customerTypes.distributor');
      case 'retailer':
      case 'retail': return t('customerManagement.customerTypes.retailer');
      case 'direct': return t('customerManagement.customerTypes.direct');
      case 'enterprise': return t('customerManagement.customerTypes.distributor');
      case 'other': return t('customerManagement.customerTypes.other');
      default: return type || t('common.uncategorized');
    }
  };

  const getIndustryName = (industry?: string) => {
    switch (industry) {
      case 'restaurant': return t('customerManagement.industries.restaurant');
      case 'supermarket': return t('customerManagement.industries.supermarket');
      case 'ecommerce': return t('customerManagement.industries.ecommerce');
      case 'food_processing': return t('customerManagement.industries.foodProcessing');
      case 'other': return t('customerManagement.industries.other');
      default: return industry || t('common.uncategorized');
    }
  };

  // 筛选客户
  const filteredCustomers = customers.filter(c => {
    if (filterStatus === 'active' && !c.isActive) return false;
    if (filterStatus === 'inactive' && c.isActive) return false;
    return true;
  });

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('customerManagement.title')} />
        <Appbar.Action icon="refresh" onPress={loadCustomers} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Search */}
        <Searchbar
          placeholder={t('customerManagement.searchPlaceholder')}
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
                { value: 'all', label: t('common.all') },
                { value: 'active', label: t('common.enabled') },
                { value: 'inactive', label: t('common.disabled') },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{customers.length}</Text>
                <Text style={styles.statLabel}>{t('customerManagement.stats.total')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {customers.filter(c => c.isActive).length}
                </Text>
                <Text style={styles.statLabel}>{t('customerManagement.stats.enabled')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {customers.filter(c => !c.isActive).length}
                </Text>
                <Text style={styles.statLabel}>{t('customerManagement.stats.disabled')}</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Customers List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>{t('common.loading')}</Text>
          </View>
        ) : filteredCustomers.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="account-group-outline" color="#999" />
              <Text style={styles.emptyText}>{t('customerManagement.empty.title')}</Text>
              <Text style={styles.emptyHint}>{t('customerManagement.empty.hint')}</Text>
            </Card.Content>
          </Card>
        ) : (
          filteredCustomers.map((customer) => (
            <Card key={customer.id} style={styles.customerCard}>
              <Card.Content>
                {/* Header */}
                <View style={styles.customerHeader}>
                  <View style={styles.customerTitleRow}>
                    <View>
                      <Text style={styles.customerName}>{customer.name}</Text>
                      <Text style={styles.customerCode}>{t('customerManagement.form.code')}: {customer.customerCode}</Text>
                    </View>
                    <View style={styles.chips}>
                      <Chip
                        mode="flat"
                        compact
                        style={[
                          styles.statusChip,
                          { backgroundColor: customer.isActive ? '#E8F5E9' : '#FFEBEE' }
                        ]}
                        textStyle={{
                          color: customer.isActive ? '#2E7D32' : '#C62828',
                          fontSize: 11
                        }}
                      >
                        {customer.isActive ? t('common.enabled') : t('common.disabled')}
                      </Chip>
                      {customer.customerType && (
                        <Chip
                          mode="flat"
                          compact
                          style={[styles.typeChip, { backgroundColor: '#E3F2FD' }]}
                          textStyle={{ color: '#1565C0', fontSize: 11 }}
                        >
                          {getCustomerTypeName(customer.customerType)}
                        </Chip>
                      )}
                    </View>
                  </View>
                </View>

                {/* Info */}
                <View style={styles.customerInfo}>
                  {customer.contactPerson && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="account" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{customer.contactPerson}</Text>
                    </View>
                  )}
                  {customer.phone && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="phone" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{customer.phone}</Text>
                    </View>
                  )}
                  {customer.industry && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="domain" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{getIndustryName(customer.industry)}</Text>
                    </View>
                  )}
                  {customer.shippingAddress && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="map-marker" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{customer.shippingAddress}</Text>
                    </View>
                  )}
                </View>

                {/* Actions — 只读角色不渲染任何 mutation 入口 */}
                {canWrite && (
                  <View style={styles.actionRow}>
                    <Button
                      mode="outlined"
                      icon="pencil"
                      onPress={() => handleEdit(customer)}
                      style={styles.actionButton}
                      compact
                    >
                      {t('common.edit')}
                    </Button>
                    <Button
                      mode="outlined"
                      icon={customer.isActive ? 'pause' : 'play'}
                      onPress={() => handleToggleStatus(customer.id, customer.isActive)}
                      style={styles.actionButton}
                      compact
                    >
                      {customer.isActive ? t('common.disable') : t('common.enable')}
                    </Button>
                    <Button
                      mode="outlined"
                      icon="delete"
                      onPress={() => handleDelete(customer.id, customer.name)}
                      style={styles.actionButton}
                      compact
                      textColor="#C62828"
                    >
                      {t('common.delete')}
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
            {editingCustomer ? t('customerManagement.modal.editTitle') : t('customerManagement.modal.createTitle')}
          </Text>

          <ScrollView style={styles.modalScrollView}>
            {/* Customer Code */}
            {
              editingCustomer && <TextInput
                label={`${t('customerManagement.form.code')} *`}
                value={formData.customerCode}
                onChangeText={(text) => setFormData({ ...formData, customerCode: text })}
                mode="outlined"
                style={styles.input}
                disabled={!!editingCustomer}
                placeholder={t('customerManagement.form.codePlaceholder')}
              />
            }

            {/* Name */}
            <TextInput
              label={`${t('customerManagement.form.name')} *`}
              value={formData.name}
              onChangeText={(text) => setFormData({ ...formData, name: text })}
              mode="outlined"
              style={styles.input}
              placeholder={t('customerManagement.form.namePlaceholder')}
            />

            {/* Contact Person */}
            <TextInput
              label={t('customerManagement.form.contactPerson')}
              value={formData.contactPerson}
              onChangeText={(text) => setFormData({ ...formData, contactPerson: text })}
              mode="outlined"
              style={styles.input}
              placeholder={t('customerManagement.form.contactPersonPlaceholder')}
            />

            {/* Contact Phone */}
            <TextInput
              label={`${t('customerManagement.form.phone')} *`}
              value={formData.phone}
              onChangeText={(text) => setFormData({ ...formData, phone: text })}
              mode="outlined"
              style={styles.input}
              keyboardType="phone-pad"
              placeholder={t('customerManagement.form.phonePlaceholder')}
            />

            {/* Email */}
            <TextInput
              label={t('customerManagement.form.email')}
              value={formData.email}
              onChangeText={(text) => setFormData({ ...formData, email: text })}
              mode="outlined"
              style={styles.input}
              keyboardType="email-address"
              placeholder={t('customerManagement.form.emailPlaceholder')}
            />

            {/* Address */}
            <TextInput
              label={t('customerManagement.form.address')}
              value={formData.shippingAddress}
              onChangeText={(text) => setFormData({ ...formData, shippingAddress: text })}
              mode="outlined"
              style={styles.input}
              multiline
              numberOfLines={2}
              placeholder={t('customerManagement.form.addressPlaceholder')}
            />

            {/* Business Type */}
            <TextInput
              label={t('customerManagement.form.businessType')}
              value={formData.businessType}
              onChangeText={(text) => setFormData({ ...formData, businessType: text })}
              mode="outlined"
              style={styles.input}
              placeholder={t('customerManagement.form.businessTypePlaceholder')}
            />

            {/* Customer Type */}
            <View style={styles.input}>
              <Text style={styles.selectLabel}>{t('customerManagement.form.customerType')}</Text>
              <Menu
                visible={customerTypeMenuVisible}
                onDismiss={() => setCustomerTypeMenuVisible(false)}
                anchor={
                  <Button
                    mode="outlined"
                    onPress={() => setCustomerTypeMenuVisible(true)}
                    icon="menu-down"
                    contentStyle={{ justifyContent: 'space-between' }}
                    style={styles.selectButton}
                  >
                    {customerTypeOptions.find(o => o.value === formData.customerType)?.label || t('common.pleaseSelect')}
                  </Button>
                }
              >
                {customerTypeOptions.map(opt => (
                  <Menu.Item
                    key={opt.value}
                    onPress={() => {
                      setFormData({ ...formData, customerType: opt.value });
                      setCustomerTypeMenuVisible(false);
                    }}
                    title={opt.label}
                  />
                ))}
              </Menu>
            </View>

            {/* Industry */}
            <View style={styles.input}>
              <Text style={styles.selectLabel}>{t('customerManagement.form.industry')}</Text>
              <Menu
                visible={industryMenuVisible}
                onDismiss={() => setIndustryMenuVisible(false)}
                anchor={
                  <Button
                    mode="outlined"
                    onPress={() => setIndustryMenuVisible(true)}
                    icon="menu-down"
                    contentStyle={{ justifyContent: 'space-between' }}
                    style={styles.selectButton}
                  >
                    {industryOptions.find(o => o.value === formData.industry)?.label || t('common.pleaseSelect')}
                  </Button>
                }
              >
                {industryOptions.map(opt => (
                  <Menu.Item
                    key={opt.value}
                    onPress={() => {
                      setFormData({ ...formData, industry: opt.value });
                      setIndustryMenuVisible(false);
                    }}
                    title={opt.label}
                  />
                ))}
              </Menu>
            </View>
          </ScrollView>

          <View style={styles.modalActions}>
            <Button
              mode="outlined"
              onPress={() => setModalVisible(false)}
              style={styles.modalButton}
            >
              {t('common.cancel')}
            </Button>
            <Button
              mode="contained"
              onPress={handleSave}
              style={styles.modalButton}
            >
              {editingCustomer ? t('common.save') : t('common.create')}
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
          label={t('customerManagement.createCustomer')}
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
  customerCard: {
    margin: 16,
    marginBottom: 8,
  },
  customerHeader: {
    marginBottom: 12,
  },
  customerTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  customerName: {
    fontSize: 18,
    fontWeight: 'bold',
  },
  customerCode: {
    fontSize: 13,
    color: '#666',
    marginTop: 2,
  },
  chips: {
    flexDirection: 'row',
    gap: 4,
  },
  statusChip: {
    height: 32,
  },
  typeChip: {
    height: 24,
  },
  customerInfo: {
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
    maxHeight: '85%',
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 20,
  },
  input: {
    marginBottom: 12,
  },
  selectLabel: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  selectButton: {
    justifyContent: 'flex-start',
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
