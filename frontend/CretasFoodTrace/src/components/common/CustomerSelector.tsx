import React, { useState, useEffect } from 'react';
import { View, StyleSheet, Modal, FlatList, TouchableOpacity, Alert } from 'react-native';
import { TextInput, List, Divider, Button, Text, Searchbar, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { customerApiClient, Customer } from '../../services/api/customerApiClient';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { customerMatchesQuery } from './customerSelectorSearch';

interface CustomerSelectorProps {
  value: string;
  onSelect: (customerId: string, customerName: string) => void;
  label?: string;
  placeholder?: string;
  error?: string;
}

/**
 * 客户选择器
 * 用于选择成品客户，支持快捷添加
 */
export const CustomerSelector: React.FC<CustomerSelectorProps> = ({
  value,
  onSelect,
  label,
  placeholder,
  error,
}) => {
  const { t } = useTranslation('common');
  const [modalVisible, setModalVisible] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [loading, setLoading] = useState(false);

  // 快捷添加功能状态
  const [showAddForm, setShowAddForm] = useState(false);
  const [newCustomerName, setNewCustomerName] = useState('');
  const [newContactPerson, setNewContactPerson] = useState('');
  const [newContactPhone, setNewContactPhone] = useState('');
  const [newAddress, setNewAddress] = useState('');
  const [newBusinessType, setNewBusinessType] = useState<string>('超市');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (modalVisible) {
      fetchCustomers();
    }
  }, [modalVisible]);

  const fetchCustomers = async () => {
    try {
      setLoading(true);
      const result = await customerApiClient.getCustomers({ isActive: true });
      console.log('✅ Customers loaded:', result.data.length);
      setCustomers(result.data);
    } catch (error) {
      console.error('❌ Failed to fetch customers:', error);
      setCustomers([]);
    } finally {
      setLoading(false);
    }
  };

  const filteredCustomers = customers.filter((customer) =>
    customerMatchesQuery(customer, searchQuery)
  );

  // 重置Modal状态到初始状态
  const resetModalState = () => {
    setShowAddForm(false);           // 显示列表而非表单
    setSearchQuery('');               // 清空搜索
    // 清空添加表单
    setNewCustomerName('');
    setNewContactPerson('');
    setNewContactPhone('');
    setNewAddress('');
    setNewBusinessType('超市');  // 重置为默认值
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

  const handleSelect = (customer: Customer) => {
    onSelect(customer.id, customer.name);
    handleModalClose();  // 使用统一的关闭函数
  };

  const handleCreateCustomer = async () => {
    if (!newCustomerName.trim()) {
      Alert.alert(t('form.validation.validationError'), t('selectors.customer.pleaseEnterName'));
      return;
    }

    try {
      setCreating(true);

      const newCustomer = await customerApiClient.createCustomer({
        customerCode: `CUST${Date.now()}`,
        name: newCustomerName.trim(),
        contactPerson: newContactPerson.trim() || undefined,
        phone: newContactPhone.trim() || undefined,
        shippingAddress: newAddress.trim() || undefined,
        businessType: newBusinessType,
      });

      console.log('✅ Customer created:', newCustomerName);

      // 刷新列表
      await fetchCustomers();

      // 自动选中新创建的客户
      onSelect(newCustomer.data.id, newCustomer.data.name);

      // 关闭弹窗（使用统一函数）
      handleModalClose();
    } catch (error) {
      console.error('❌ Failed to create customer:', error);
      Alert.alert(t('selectors.customer.createFailed'), getErrorMsg(error) || t('buttons.retry'));
    } finally {
      setCreating(false);
    }
  };

  return (
    <View>
      <TouchableOpacity
        onPress={() => {
          console.log('🔍 Opening customer selector');
          handleModalOpen();
        }}
        activeOpacity={1}
      >
        <View pointerEvents="none">
          <TextInput
            label={(label || t('selectors.customer.label')) + ' *'}
            placeholder={placeholder || t('selectors.customer.placeholder')}
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
              <Text variant="titleLarge">{t('selectors.customer.title')}</Text>
              <Button onPress={handleModalClose}>{t('buttons.cancel')}</Button>
            </View>

            <Searchbar
              placeholder={t('selectors.customer.search')}
              value={searchQuery}
              onChangeText={setSearchQuery}
              style={styles.searchBar}
            />

            {loading ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="large" />
                <Text style={styles.loadingText}>{t('status.loading')}</Text>
              </View>
            ) : (
              <FlatList
                data={filteredCustomers}
                keyExtractor={(item) => item.id}
                renderItem={({ item }) => (
                  <>
                    <List.Item
                      title={item.name}
                      description={`${item.code}${item.contactPerson ? ' • ' + item.contactPerson : ''}${item.phone ? ' • ' + item.phone : ''}`}
                      onPress={() => handleSelect(item)}
                      right={props => value === item.name ? <List.Icon {...props} icon="check" color="#2196F3" /> : null}
                    />
                    <Divider />
                  </>
                )}
                ListEmptyComponent={
                  <View style={styles.emptyContainer}>
                    <Text variant="bodyMedium" style={styles.emptyText}>
                      {searchQuery ? t('selectors.customer.notFound') : t('selectors.customer.empty')}
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
                      <Text style={styles.addButtonText}>➕ {t('selectors.customer.addNew')}</Text>
                    </TouchableOpacity>
                  ) : (
                    // 创建表单
                    <View style={styles.addForm}>
                      <Text variant="titleMedium" style={styles.formTitle}>{t('selectors.customer.addNewTitle')}</Text>

                      <TextInput
                        label={t('selectors.customer.name') + ' *'}
                        value={newCustomerName}
                        onChangeText={setNewCustomerName}
                        mode="outlined"
                        placeholder={t('selectors.customer.namePlaceholder')}
                        style={styles.formInput}
                        autoFocus
                      />

                      <TextInput
                        label={t('selectors.customer.contactPerson')}
                        value={newContactPerson}
                        onChangeText={setNewContactPerson}
                        mode="outlined"
                        placeholder={t('selectors.customer.contactPersonPlaceholder')}
                        style={styles.formInput}
                      />

                      <TextInput
                        label={t('selectors.customer.phone')}
                        value={newContactPhone}
                        onChangeText={setNewContactPhone}
                        mode="outlined"
                        placeholder={t('selectors.customer.phonePlaceholder')}
                        keyboardType="phone-pad"
                        style={styles.formInput}
                      />

                      <TextInput
                        label={t('selectors.customer.address')}
                        value={newAddress}
                        onChangeText={setNewAddress}
                        mode="outlined"
                        placeholder={t('selectors.customer.addressPlaceholder')}
                        style={styles.formInput}
                      />

                      <Text variant="bodySmall" style={styles.formLabel}>{t('selectors.customer.businessType')}</Text>
                      <SegmentedButtons
                        value={newBusinessType}
                        onValueChange={setNewBusinessType}
                        buttons={[
                          { value: t('selectors.customer.businessTypes.supermarket'), label: t('selectors.customer.businessTypes.supermarket') },
                          { value: t('selectors.customer.businessTypes.restaurant'), label: t('selectors.customer.businessTypes.restaurant') },
                          { value: t('selectors.customer.businessTypes.wholesale'), label: t('selectors.customer.businessTypes.wholesale') },
                          { value: t('selectors.customer.businessTypes.other'), label: t('selectors.customer.businessTypes.other') },
                        ]}
                        style={styles.formSegment}
                      />

                      <View style={styles.formActions}>
                        <Button
                          mode="outlined"
                          onPress={() => {
                            setShowAddForm(false);
                            setNewCustomerName('');
                            setNewContactPerson('');
                            setNewContactPhone('');
                            setNewAddress('');
                          }}
                          disabled={creating}
                        >
                          {t('buttons.cancel')}
                        </Button>
                        <Button
                          mode="contained"
                          onPress={handleCreateCustomer}
                          loading={creating}
                          disabled={creating}
                        >
                          {t('buttons.save')}
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

export default CustomerSelector;
