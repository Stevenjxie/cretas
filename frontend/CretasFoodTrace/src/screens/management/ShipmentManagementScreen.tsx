import React, { useState, useEffect, useMemo } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { StickyFooterSummary } from '../../components/list';
import { useListSummary } from '../../hooks/useListSummary';
import { formatSummaryForAI } from '../../utils/aiSummaryContext';
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
import { useNavigation, CommonActions } from '@react-navigation/native';
import { shipmentApiClient, ShipmentRecord, CreateShipmentRequest, ShipmentStats } from '../../services/api/shipmentApiClient';
import { customerApiClient, Customer } from '../../services/api/customerApiClient';
import { useAuthStore } from '../../store/authStore';
import { logger } from '../../utils/logger';
import { getErrorMsg } from '../../utils/errorHandler';

const shipmentLogger = logger.createContextLogger('ShipmentManagement');

/**
 * 出货管理页面
 * 权限：factory_super_admin、platform_admin
 * 功能：出货记录CRUD、状态管理、物流追踪
 */
export default function ShipmentManagementScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();

  const [shipments, setShipments] = useState<ShipmentRecord[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [stats, setStats] = useState<ShipmentStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('all');
  const [modalVisible, setModalVisible] = useState(false);
  const [editingShipment, setEditingShipment] = useState<ShipmentRecord | null>(null);
  const [customerMenuVisible, setCustomerMenuVisible] = useState(false);
  const [statusMenuVisible, setStatusMenuVisible] = useState(false);

  // U-FOOTER-1
  const summaryRequest = useMemo(
    () => ({ filterConditions: filterStatus !== 'all' ? { status: filterStatus } : {} }),
    [filterStatus],
  );
  const { summary, refresh: refreshSummary } = useListSummary('shipment', summaryRequest);

  // 权限控制
  const userType = user?.userType || 'factory';
  const roleCode = user?.factoryUser?.role || user?.roleCode || 'viewer';
  const isPlatformAdmin = userType === 'platform';
  const isSuperAdmin = roleCode === 'factory_super_admin';
  const isPermissionAdmin = roleCode === 'permission_admin';
  const isDepartmentAdmin = roleCode === 'department_admin';
  const canManage = isPlatformAdmin || isSuperAdmin || isPermissionAdmin || isDepartmentAdmin;

  // 表单数据
  const [formData, setFormData] = useState<Partial<CreateShipmentRequest>>({
    customerId: '',
    orderNumber: '',
    productName: '',
    quantity: 0,
    unit: 'kg',
    unitPrice: 0,
    deliveryAddress: '',
    logisticsCompany: '',
    trackingNumber: '',
    notes: '',
  });

  // 状态选项
  const statusOptions = [
    { label: '待发货', value: 'pending' },
    { label: '已发货', value: 'shipped' },
    { label: '已送达', value: 'delivered' },
    { label: '已退货', value: 'returned' },
  ];

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      const [shipmentsRes, customersRes, statsRes] = await Promise.all([
        shipmentApiClient.getShipments({
          factoryId: user?.factoryId,
          page: 0,
          size: 100,
        }),
        customerApiClient.getCustomers({
          factoryId: user?.factoryId,
          page: 1, // 后端客户列表是 1-based 分页 (page>=1), 传 0 会 400 "页码必须大于0"
          size: 100,
        }),
        shipmentApiClient.getShipmentStats(user?.factoryId),
      ]);

      // Defensive: handle various response shapes
      const shipmentList = Array.isArray(shipmentsRes) ? shipmentsRes
        : Array.isArray(shipmentsRes?.data) ? shipmentsRes.data
        : [];
      setShipments(shipmentList);
      setCustomers(customersRes.data || []);
      setStats(statsRes);
      shipmentLogger.info('出货数据加载成功', {
        shipmentCount: shipmentList.length,
        customerCount: customersRes.data?.length || 0,
      });
    } catch (error) {
      shipmentLogger.error('加载出货数据失败', error as Error);
      Alert.alert('错误', '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = () => {
    // Search is now handled reactively via filteredShipments derivation below.
    // This function kept for onSubmitEditing binding — no-op since filtering is live.
  };

  const handleAdd = () => {
    setEditingShipment(null);
    setFormData({
      customerId: '',
      orderNumber: '',
      productName: '',
      quantity: 0,
      unit: 'kg',
      unitPrice: 0,
      deliveryAddress: '',
      logisticsCompany: '',
      trackingNumber: '',
      notes: '',
    });
    setModalVisible(true);
  };

  const handleEdit = (shipment: ShipmentRecord) => {
    setEditingShipment(shipment);
    setFormData({
      customerId: shipment.customerId,
      orderNumber: shipment.orderNumber || '',
      productName: shipment.productName,
      quantity: shipment.quantity,
      unit: shipment.unit,
      unitPrice: shipment.unitPrice || 0,
      deliveryAddress: shipment.deliveryAddress,
      logisticsCompany: shipment.logisticsCompany || '',
      trackingNumber: shipment.trackingNumber || '',
      notes: shipment.notes || '',
    });
    setModalVisible(true);
  };

  const handleSave = async () => {
    if (!formData.productName || !formData.customerId || !formData.deliveryAddress) {
      Alert.alert('提示', '产品名称、客户和配送地址不能为空');
      return;
    }

    try {
      if (editingShipment) {
        await shipmentApiClient.updateShipment(
          editingShipment.id,
          formData,
          user?.factoryId
        );
        Alert.alert('成功', '出货记录已更新');
      } else {
        await shipmentApiClient.createShipment(
          formData as CreateShipmentRequest,
          user?.factoryId
        );
        Alert.alert('成功', '出货记录创建成功');
      }

      shipmentLogger.info(editingShipment ? '出货记录更新成功' : '出货记录创建成功', {
        productName: formData.productName,
      });
      setModalVisible(false);
      loadData();
    } catch (error) {
      shipmentLogger.error('保存出货记录失败', error as Error);
      Alert.alert('错误', getErrorMsg(error) || '操作失败');
    }
  };

  const handleDelete = (id: string, shipmentNumber: string) => {
    Alert.alert(
      '确认删除',
      `确定要删除出货单 "${shipmentNumber}" 吗？`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: async () => {
            try {
              await shipmentApiClient.deleteShipment(id, user?.factoryId);
              shipmentLogger.info('出货记录删除成功', { id, shipmentNumber });
              Alert.alert('成功', '出货记录已删除');
              loadData();
            } catch (error) {
              shipmentLogger.error('删除出货记录失败', error as Error);
              Alert.alert('错误', '删除失败');
            }
          },
        },
      ]
    );
  };

  const handleUpdateStatus = async (id: string, status: ShipmentRecord['status']) => {
    try {
      await shipmentApiClient.updateStatus(id, status, user?.factoryId);
      shipmentLogger.info('出货状态已更新', { id, status });
      Alert.alert('成功', '状态已更新');
      loadData();
    } catch (error) {
      shipmentLogger.error('更新出货状态失败', error as Error);
      Alert.alert('错误', '操作失败');
    }
  };

  const getStatusLabel = (status: string) => {
    switch (status) {
      case 'pending': return '待发货';
      case 'shipped': return '已发货';
      case 'delivered': return '已送达';
      case 'returned': return '已退货';
      default: return status;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'pending': return { bg: '#FFF3E0', text: '#E65100' };
      case 'shipped': return { bg: '#E3F2FD', text: '#1565C0' };
      case 'delivered': return { bg: '#E8F5E9', text: '#2E7D32' };
      case 'returned': return { bg: '#FFEBEE', text: '#C62828' };
      default: return { bg: '#F5F5F5', text: '#666' };
    }
  };

  const getCustomerName = (customerId: string) => {
    const customer = customers.find(c => c.id === customerId);
    return customer?.name || customerId;
  };

  // 筛选出货记录（状态 + 搜索）
  const filteredShipments = shipments.filter(s => {
    if (filterStatus !== 'all' && s.status !== filterStatus) return false;
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      if (
        !s.shipmentNumber?.toLowerCase().includes(q) &&
        !s.productName?.toLowerCase().includes(q) &&
        !s.trackingNumber?.toLowerCase().includes(q)
      ) return false;
    }
    return true;
  });

  if (!canManage) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="出货管理" />
        </Appbar.Header>
        <View style={styles.noPermission}>
          <List.Icon icon="lock" color="#999" />
          <Text style={styles.noPermissionText}>您没有权限访问此页面</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="出货管理" />
        <Appbar.Action icon="robot-outline" onPress={() => navigation.dispatch(CommonActions.navigate('FAAITab', { screen: 'AIChat', params: { entityType: 'SHIPMENT', initialMessage: '我要创建发货单' } }))} />
        <Appbar.Action icon="refresh" onPress={loadData} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Search */}
        <Searchbar
          placeholder="搜索出货单号、产品、物流单号"
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
                { value: 'pending', label: '待发货' },
                { value: 'shipped', label: '已发货' },
                { value: 'delivered', label: '已送达' },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        {stats && (
          <Card style={styles.statsCard}>
            <Card.Content>
              <View style={styles.statsRow}>
                <View style={styles.statItem}>
                  <Text style={styles.statValue}>{stats.total}</Text>
                  <Text style={styles.statLabel}>总数</Text>
                </View>
                <View style={styles.statItem}>
                  <Text style={[styles.statValue, { color: '#E65100' }]}>{stats.pending}</Text>
                  <Text style={styles.statLabel}>待发货</Text>
                </View>
                <View style={styles.statItem}>
                  <Text style={[styles.statValue, { color: '#1565C0' }]}>{stats.shipped}</Text>
                  <Text style={styles.statLabel}>运输中</Text>
                </View>
                <View style={styles.statItem}>
                  <Text style={[styles.statValue, { color: '#2E7D32' }]}>{stats.delivered}</Text>
                  <Text style={styles.statLabel}>已送达</Text>
                </View>
              </View>
            </Card.Content>
          </Card>
        )}

        {/* Shipments List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : filteredShipments.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="truck-outline" color="#999" />
              <Text style={styles.emptyText}>暂无出货记录</Text>
              <Text style={styles.emptyHint}>点击右下角"+"按钮创建出货单</Text>
            </Card.Content>
          </Card>
        ) : (
          filteredShipments.map((shipment) => {
            const statusColor = getStatusColor(shipment.status);
            return (
              <Card key={shipment.id} style={styles.shipmentCard}>
                <Card.Content>
                  {/* Header */}
                  <View style={styles.shipmentHeader}>
                    <View style={styles.shipmentTitleRow}>
                      <View>
                        <Text style={styles.shipmentNumber}>{shipment.shipmentNumber}</Text>
                        <Text style={styles.productName}>{shipment.productName}</Text>
                      </View>
                      <Chip
                        mode="flat"
                        compact
                        style={[styles.statusChip, { backgroundColor: statusColor.bg }]}
                        textStyle={{ color: statusColor.text, fontSize: 11 }}
                      >
                        {getStatusLabel(shipment.status)}
                      </Chip>
                    </View>
                  </View>

                  {/* Info */}
                  <View style={styles.shipmentInfo}>
                    <View style={styles.infoRow}>
                      <List.Icon icon="account" style={styles.infoIcon} />
                      <Text style={styles.infoText}>客户: {getCustomerName(shipment.customerId)}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <List.Icon icon="package-variant" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{shipment.quantity} {shipment.unit}</Text>
                    </View>
                    {shipment.totalAmount && (
                      <View style={styles.infoRow}>
                        <List.Icon icon="currency-cny" style={styles.infoIcon} />
                        <Text style={styles.infoText}>¥{Number(shipment.totalAmount ?? 0).toFixed(2)}</Text>
                      </View>
                    )}
                    <View style={styles.infoRow}>
                      <List.Icon icon="map-marker" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{shipment.deliveryAddress}</Text>
                    </View>
                    {shipment.trackingNumber && (
                      <View style={styles.infoRow}>
                        <List.Icon icon="truck" style={styles.infoIcon} />
                        <Text style={styles.infoText}>
                          {shipment.logisticsCompany}: {shipment.trackingNumber}
                        </Text>
                      </View>
                    )}
                    <View style={styles.infoRow}>
                      <List.Icon icon="calendar" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{shipment.shipmentDate}</Text>
                    </View>
                  </View>

                  {/* Actions */}
                  <View style={styles.actionRow}>
                    <Button
                      mode="outlined"
                      icon="pencil"
                      onPress={() => handleEdit(shipment)}
                      style={styles.actionButton}
                      compact
                    >
                      编辑
                    </Button>
                    {shipment.status === 'pending' && (
                      <Button
                        mode="outlined"
                        icon="truck"
                        onPress={() => handleUpdateStatus(shipment.id, 'shipped')}
                        style={styles.actionButton}
                        compact
                      >
                        发货
                      </Button>
                    )}
                    {shipment.status === 'shipped' && (
                      <Button
                        mode="outlined"
                        icon="check"
                        onPress={() => handleUpdateStatus(shipment.id, 'delivered')}
                        style={styles.actionButton}
                        compact
                      >
                        送达
                      </Button>
                    )}
                    <Button
                      mode="outlined"
                      icon="delete"
                      onPress={() => handleDelete(shipment.id, shipment.shipmentNumber)}
                      style={styles.actionButton}
                      compact
                      textColor="#C62828"
                    >
                      删除
                    </Button>
                  </View>
                </Card.Content>
              </Card>
            );
          })
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
            {editingShipment ? '编辑出货单' : '创建出货单'}
          </Text>

          <ScrollView style={styles.modalScrollView}>
            {/* Customer */}
            <View style={styles.input}>
              <Text style={styles.selectLabel}>客户 *</Text>
              <Menu
                visible={customerMenuVisible}
                onDismiss={() => setCustomerMenuVisible(false)}
                anchor={
                  <Button
                    mode="outlined"
                    onPress={() => setCustomerMenuVisible(true)}
                    icon="menu-down"
                    contentStyle={{ justifyContent: 'space-between' }}
                    style={styles.selectButton}
                  >
                    {customers.find(c => c.id === formData.customerId)?.name || '请选择客户'}
                  </Button>
                }
              >
                {customers.map(customer => (
                  <Menu.Item
                    key={customer.id}
                    onPress={() => {
                      setFormData({ ...formData, customerId: customer.id, deliveryAddress: customer.shippingAddress || '' });
                      setCustomerMenuVisible(false);
                    }}
                    title={customer.name}
                  />
                ))}
              </Menu>
            </View>

            {/* Product Name */}
            <TextInput
              label="产品名称 *"
              value={formData.productName}
              onChangeText={(text) => setFormData({ ...formData, productName: text })}
              mode="outlined"
              style={styles.input}
            />

            {/* Quantity */}
            <View style={styles.row}>
              <TextInput
                label="数量 *"
                value={formData.quantity?.toString() || ''}
                onChangeText={(text) => setFormData({ ...formData, quantity: parseFloat(text) || 0 })}
                mode="outlined"
                style={[styles.input, { flex: 1 }]}
                keyboardType="numeric"
              />
              <TextInput
                label="单位"
                value={formData.unit}
                onChangeText={(text) => setFormData({ ...formData, unit: text })}
                mode="outlined"
                style={[styles.input, { flex: 1, marginLeft: 8 }]}
              />
            </View>

            {/* Unit Price */}
            <TextInput
              label="单价"
              value={formData.unitPrice?.toString() || ''}
              onChangeText={(text) => setFormData({ ...formData, unitPrice: parseFloat(text) || 0 })}
              mode="outlined"
              style={styles.input}
              keyboardType="numeric"
            />

            {/* Delivery Address */}
            <TextInput
              label="配送地址 *"
              value={formData.deliveryAddress}
              onChangeText={(text) => setFormData({ ...formData, deliveryAddress: text })}
              mode="outlined"
              style={styles.input}
              multiline
              numberOfLines={2}
            />

            {/* Logistics */}
            <TextInput
              label="物流公司"
              value={formData.logisticsCompany}
              onChangeText={(text) => setFormData({ ...formData, logisticsCompany: text })}
              mode="outlined"
              style={styles.input}
            />

            <TextInput
              label="物流单号"
              value={formData.trackingNumber}
              onChangeText={(text) => setFormData({ ...formData, trackingNumber: text })}
              mode="outlined"
              style={styles.input}
            />

            {/* Notes */}
            <TextInput
              label="备注"
              value={formData.notes}
              onChangeText={(text) => setFormData({ ...formData, notes: text })}
              mode="outlined"
              style={styles.input}
              multiline
              numberOfLines={2}
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
              {editingShipment ? '保存' : '创建'}
            </Button>
          </View>
        </Modal>
      </Portal>

      <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab' as never, {
            screen: 'AIChat',
            params: {
              entityType: 'SHIPMENT',
              initialMessage: `分析当前出货${formatSummaryForAI(summary, { filter: { status: filterStatus } })}`,
            },
          } as never))
        }
      />

      {/* FAB */}
      {canManage && (
        <FAB
          icon="plus"
          style={styles.fab}
          onPress={handleAdd}
          label="创建出货单"
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
    fontSize: 20,
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
  shipmentCard: {
    margin: 16,
    marginBottom: 8,
  },
  shipmentHeader: {
    marginBottom: 12,
  },
  shipmentTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  shipmentNumber: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  productName: {
    fontSize: 14,
    color: '#666',
    marginTop: 2,
  },
  statusChip: {
    height: 24,
  },
  shipmentInfo: {
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
    flexWrap: 'wrap',
  },
  actionButton: {
    flex: 1,
    minWidth: 80,
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
  row: {
    flexDirection: 'row',
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
