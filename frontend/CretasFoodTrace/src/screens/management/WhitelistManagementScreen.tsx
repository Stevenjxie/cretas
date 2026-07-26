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
  Divider,
  SegmentedButtons,
  Menu,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { whitelistApiClient, WhitelistDTO, CreateWhitelistRequest } from '../../services/api/whitelistApiClient';
import { useAuthStore } from '../../store/authStore';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { ROLE_OPTIONS } from '../../types/hrNavigation';

// 创建WhitelistManagement专用logger
const whitelistLogger = logger.createContextLogger('WhitelistManagement');

/**
 * 白名单管理页面
 * 权限：factory_super_admin、platform_admin
 * 功能：批量添加允许注册的手机号、删除白名单、查看状态
 */
export default function WhitelistManagementScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();

  const [whitelist, setWhitelist] = useState<WhitelistDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [filterStatus, setFilterStatus] = useState('all');

  // Menu visibility states
  const [roleMenuVisible, setRoleMenuVisible] = useState(false);
  const [departmentMenuVisible, setDepartmentMenuVisible] = useState(false);

  // 权限控制
  const userType = user?.userType || 'factory';
  const roleCode = user?.factoryUser?.role || user?.roleCode || 'viewer';
  const isPlatformAdmin = userType === 'platform';
  const isSuperAdmin = roleCode === 'factory_super_admin';
  const isPermissionAdmin = roleCode === 'permission_admin';
  const canManage = isPlatformAdmin || isSuperAdmin || isPermissionAdmin;

  // 批量添加表单
  const [batchFormData, setBatchFormData] = useState({
    phoneNumbers: '', // 多行文本，每行一个手机号
    defaultRole: 'operator',
    defaultDepartment: 'processing',
  });

  const roleOptions = ROLE_OPTIONS;

  const departmentOptions = [
    { label: '加工部', value: 'processing' },
    { label: '物流部', value: 'logistics' },
    { label: '质检部', value: 'quality' },
  ];

  useEffect(() => {
    loadWhitelist();
  }, []);

  const loadWhitelist = async () => {
    try {
      setLoading(true);
      const response = await whitelistApiClient.getWhitelist({
        factoryId: user?.factoryId,
        page: 1, // 后端要求 page >= 1
        size: 100,
      });

      // 📊 调试日志：查看API响应结构
      whitelistLogger.debug('API响应结构', {
        hasContent: !!response.content,
        isContentArray: Array.isArray(response.content),
        contentLength: response.content ? response.content.length : 0,
      });

      // ✅ 正确的数据访问：response.content (PageResponse)
      if (response.content) {
        setWhitelist(response.content);
        whitelistLogger.info('白名单列表加载成功', {
          factoryId: user?.factoryId,
          count: response.content.length,
        });
      } else {
        whitelistLogger.warn('API返回空数据', { response });
        setWhitelist([]);
      }
    } catch (error) {
      whitelistLogger.error('加载白名单失败', error as Error, {
        factoryId: user?.factoryId,
      });
      Alert.alert('错误', getErrorMsg(error) || '加载白名单失败');
    } finally {
      setLoading(false);
    }
  };

  const handleBatchAdd = () => {
    setBatchFormData({
      phoneNumbers: '',
      defaultRole: 'operator',
      defaultDepartment: 'processing',
    });
    setModalVisible(true);
  };

  const handleSaveBatch = async () => {
    const phoneLines = batchFormData.phoneNumbers
      .split('\n')
      .map(line => line.trim())
      .filter(line => line.length > 0);

    if (phoneLines.length === 0) {
      Alert.alert('提示', '请输入至少一个手机号');
      return;
    }

    // 与后端白名单契约保持一致：仅支持中国大陆 11 位手机号
    const invalidPhones = phoneLines.filter(phone => {
      return !phone.match(/^1[3-9]\d{9}$/);
    });

    if (invalidPhones.length > 0) {
      Alert.alert('提示', `以下手机号格式不正确：\n${invalidPhones.join('\n')}`);
      return;
    }

    try {
      const whitelists: CreateWhitelistRequest[] = phoneLines.map(phone => ({
        phoneNumber: phone,
        realName: '', // 批量邀请不预设姓名，员工开户时填写
        role: batchFormData.defaultRole,
        department: batchFormData.defaultDepartment,
      }));
      const result = await whitelistApiClient.batchAddWhitelist(
        whitelistApiClient.createBatchRequest(
          whitelists,
          batchFormData.defaultRole,
          batchFormData.defaultDepartment
        ),
        user?.factoryId
      );

      Alert.alert(
        '批量添加完成',
        `成功：${result.successCount}条\n失败：${result.failedCount}条${
          result.failedEntries?.length
            ? '\n\n错误：\n' + result.failedEntries.map(item => `${item.phoneNumber}：${item.reason}`).join('\n')
            : ''
        }`
      );

      setModalVisible(false);
      loadWhitelist();

      whitelistLogger.info('批量添加白名单成功', {
        factoryId: user?.factoryId,
        successCount: result.successCount,
        failedCount: result.failedCount,
        totalCount: phoneLines.length,
      });
    } catch (error) {
      whitelistLogger.error('批量添加白名单失败', error as Error, {
        factoryId: user?.factoryId,
        phoneCount: phoneLines.length,
      });
      Alert.alert('错误', getErrorMsg(error) || '批量添加失败');
    }
  };

  const handleDelete = (id: number, phoneNumber: string) => {
    Alert.alert(
      '确认删除',
      `确定要删除白名单 "${phoneNumber}" 吗？`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: async () => {
            try {
              await whitelistApiClient.deleteWhitelist(id, user?.factoryId);
              Alert.alert('成功', '白名单已删除');
              loadWhitelist();
              whitelistLogger.info('删除白名单成功', {
                factoryId: user?.factoryId,
                whitelistId: id,
                phoneNumber,
              });
            } catch (error) {
              whitelistLogger.error('删除白名单失败', error as Error, {
                factoryId: user?.factoryId,
                whitelistId: id,
                phoneNumber,
              });
              Alert.alert('错误', getErrorMsg(error) || '删除失败');
            }
          },
        },
      ]
    );
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'ACTIVE': return '#4CAF50';
      case 'PENDING': return '#FF9800';
      case 'EXPIRED': return '#9E9E9E';
      case 'LIMIT_REACHED': return '#F44336';
      default: return '#999';
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'ACTIVE': return '已使用';
      case 'PENDING': return '待使用';
      case 'EXPIRED': return '已过期';
      case 'LIMIT_REACHED': return '已达限';
      default: return status;
    }
  };

  const getRoleName = (role: string) => {
    switch (role) {
      case 'operator': return '操作员';
      case 'department_admin': return '部门管理员';
      case 'factory_super_admin': return '工厂超管';
      default: return role;
    }
  };

  const getDepartmentName = (dept?: string) => {
    switch (dept) {
      case 'processing': return '加工部';
      case 'logistics': return '物流部';
      case 'quality': return '质检部';
      default: return dept || '未分配';
    }
  };

  // 筛选白名单
  const filteredWhitelist = whitelist.filter(item => {
    if (filterStatus !== 'all' && item.status !== filterStatus) {
      return false;
    }
    return true;
  });

  if (!canManage) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="白名单管理" />
        </Appbar.Header>
        <View style={styles.noPermission}>
          <List.Icon icon="lock" color="#999" />
          <Text style={styles.noPermissionText}>您没有权限访问此页面</Text>
          <Text style={styles.noPermissionHint}>仅限工厂超管和平台管理员</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="白名单管理" />
        <Appbar.Action icon="refresh" onPress={loadWhitelist} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Info Card */}
        <Card style={styles.infoCard}>
          <Card.Content>
            <View style={styles.infoHeader}>
              <List.Icon icon="information" color="#2196F3" />
              <Text style={styles.infoTitle}>白名单说明</Text>
            </View>
            <Text style={styles.infoText}>
              • 只有白名单中的手机号才能注册用户{'\n'}
              • 批量添加时每行一个手机号{'\n'}
              • 用户注册后白名单状态自动变为"已使用"
            </Text>
          </Card.Content>
        </Card>

        {/* Filter */}
        <Card style={styles.filterCard}>
          <Card.Content>
            <SegmentedButtons
              value={filterStatus}
              onValueChange={setFilterStatus}
              buttons={[
                { value: 'all', label: '全部' },
                { value: 'PENDING', label: '待使用' },
                { value: 'ACTIVE', label: '已使用' },
                { value: 'EXPIRED', label: '已过期' },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{whitelist.length}</Text>
                <Text style={styles.statLabel}>总数</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {whitelist.filter(w => w.status === 'PENDING').length}
                </Text>
                <Text style={styles.statLabel}>待使用</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {whitelist.filter(w => w.status === 'ACTIVE').length}
                </Text>
                <Text style={styles.statLabel}>已使用</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Whitelist */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : filteredWhitelist.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="shield-check-outline" color="#999" />
              <Text style={styles.emptyText}>暂无白名单</Text>
              <Text style={styles.emptyHint}>点击右下角"+"按钮批量添加</Text>
            </Card.Content>
          </Card>
        ) : (
          filteredWhitelist.map((item) => (
            <Card key={item.id} style={styles.whitelistCard}>
              <Card.Content>
                {/* Header */}
                <View style={styles.itemHeader}>
                  <View style={styles.itemTitleRow}>
                    <Text style={styles.phoneNumber}>{item.phoneNumber}</Text>
                    <Chip
                      mode="flat"
                      compact
                      style={[
                        styles.statusChip,
                        { backgroundColor: `${getStatusColor(item.status)}20` }
                      ]}
                      textStyle={{ color: getStatusColor(item.status), fontSize: 11 }}
                    >
                      {getStatusText(item.status)}
                    </Chip>
                  </View>
                </View>

                {/* Info */}
                <View style={styles.itemInfo}>
                  <View style={styles.infoRow}>
                    <List.Icon icon="account" style={styles.infoIcon} />
                    <Text style={styles.infoText}>{item.realName || item.name || '开户时填写姓名'}</Text>
                  </View>
                  <View style={styles.infoRow}>
                    <List.Icon icon="shield-account" style={styles.infoIcon} />
                    <Text style={styles.infoText}>{getRoleName(item.role)}</Text>
                  </View>
                  {item.department && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="office-building" style={styles.infoIcon} />
                      <Text style={styles.infoText}>{getDepartmentName(item.department)}</Text>
                    </View>
                  )}
                  <View style={styles.infoRow}>
                    <List.Icon icon="counter" style={styles.infoIcon} />
                    <Text style={styles.infoText}>
                      使用次数: {item.usedCount}/{item.maxUsageCount || '∞'}
                    </Text>
                  </View>
                </View>

                {/* Actions */}
                {!item.accountCreated && (
                  <View style={styles.actionRow}>
                    <Button
                      mode="outlined"
                      icon="delete"
                      onPress={() => handleDelete(item.id, item.phoneNumber)}
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

      {/* Batch Add Modal */}
      <Portal>
        <Modal
          visible={modalVisible}
          onDismiss={() => setModalVisible(false)}
          contentContainerStyle={styles.modalContent}
        >
          <Text style={styles.modalTitle}>批量添加白名单</Text>

          <Text style={styles.helpText}>
            每行一个中国大陆手机号，例如：{'\n'}
            • 13800138000{'\n'}
            • 13900139000
          </Text>

          <ScrollView style={styles.modalScrollView}>

            {/* Phone Numbers */}
            <TextInput
              label="手机号列表 *"
              value={batchFormData.phoneNumbers}
              onChangeText={(text) => setBatchFormData({ ...batchFormData, phoneNumbers: text })}
              mode="outlined"
              style={styles.textArea}
              multiline
              numberOfLines={8}
              placeholder="每行一个手机号&#10;13800138000&#10;13900139000&#10;13700137000"
            />

            {/* Default Role */}
            <View style={styles.selectContainer}>
              <Text style={styles.selectLabel}>默认角色 *</Text>
              <Menu
                visible={roleMenuVisible}
                onDismiss={() => setRoleMenuVisible(false)}
                anchor={
                  <Button
                    mode="outlined"
                    onPress={() => setRoleMenuVisible(true)}
                    icon="menu-down"
                    contentStyle={{ justifyContent: 'space-between' }}
                    style={styles.selectButton}
                  >
                    {roleOptions.find(o => o.value === batchFormData.defaultRole)?.label || '请选择'}
                  </Button>
                }
              >
                {roleOptions.map(opt => (
                  <Menu.Item
                    key={opt.value}
                    onPress={() => {
                      setBatchFormData({ ...batchFormData, defaultRole: opt.value });
                      setRoleMenuVisible(false);
                    }}
                    title={opt.label}
                  />
                ))}
              </Menu>
            </View>

            {/* Default Department */}
            <View style={styles.selectContainer}>
              <Text style={styles.selectLabel}>默认部门 *</Text>
              <Menu
                visible={departmentMenuVisible}
                onDismiss={() => setDepartmentMenuVisible(false)}
                anchor={
                  <Button
                    mode="outlined"
                    onPress={() => setDepartmentMenuVisible(true)}
                    icon="menu-down"
                    contentStyle={{ justifyContent: 'space-between' }}
                    style={styles.selectButton}
                  >
                    {departmentOptions.find(o => o.value === batchFormData.defaultDepartment)?.label || '请选择'}
                  </Button>
                }
              >
                {departmentOptions.map(opt => (
                  <Menu.Item
                    key={opt.value}
                    onPress={() => {
                      setBatchFormData({ ...batchFormData, defaultDepartment: opt.value });
                      setDepartmentMenuVisible(false);
                    }}
                    title={opt.label}
                  />
                ))}
              </Menu>
            </View>

            <Card style={styles.tipCard}>
              <Card.Content>
                <Text style={styles.tipText}>
                  💡 批量邀请不预设姓名，员工使用受邀手机号开户时填写真实姓名
                </Text>
              </Card.Content>
            </Card>
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
              onPress={handleSaveBatch}
              style={styles.modalButton}
            >
              批量添加
            </Button>
          </View>
        </Modal>
      </Portal>

      {/* FAB */}
      {canManage && (
        <FAB
          icon="plus"
          style={styles.fab}
          onPress={handleBatchAdd}
          label="批量添加"
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
  infoCard: {
    margin: 16,
    marginBottom: 8,
    backgroundColor: '#E3F2FD',
  },
  infoHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  infoTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginLeft: 0,
  },
  infoText: {
    fontSize: 13,
    lineHeight: 20,
    color: '#666',
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
  whitelistCard: {
    margin: 16,
    marginBottom: 8,
  },
  itemHeader: {
    marginBottom: 12,
  },
  itemTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  phoneNumber: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  statusChip: {
    height: 32,
  },
  itemInfo: {
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
    marginBottom: 12,
  },
  helpText: {
    fontSize: 13,
    color: '#666',
    marginBottom: 16,
    backgroundColor: '#FFF8E1',
    padding: 12,
    borderRadius: 4,
  },
  textArea: {
    marginBottom: 16,
    minHeight: 150,
  },
  selectContainer: {
    marginBottom: 16,
  },
  selectLabel: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  selectButton: {
    justifyContent: 'flex-start',
  },
  tipCard: {
    marginBottom: 16,
    backgroundColor: '#E8F5E9',
  },
  tipText: {
    fontSize: 12,
    color: '#2E7D32',
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
