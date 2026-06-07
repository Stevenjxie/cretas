/**
 * Factory Admin 管理中心
 * 包含: 员工管理、设备管理等9宫格入口
 */
import React from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { FAManagementStackParamList } from '../../../types/navigation';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { isRestaurant } from '../../../utils/factoryType';

type NavigationProp = NativeStackNavigationProp<FAManagementStackParamList, 'FAManagement'>;

interface GridItemProps {
  icon: string;
  title: string;
  color: string;
  onPress: () => void;
  testID?: string;
}

function GridItem({ icon, title, color, onPress, testID }: GridItemProps) {
  return (
    <TouchableOpacity testID={testID} style={styles.gridItem} onPress={onPress}>
      <View style={[styles.gridIcon, { backgroundColor: color + '15' }]}>
        <Icon source={icon} size={28} color={color} />
      </View>
      <Text style={styles.gridTitle}>{title}</Text>
    </TouchableOpacity>
  );
}

export function FAManagementScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('home');
  const user = useAuthStore(state => state.user);
  const isRestaurantMode = isRestaurant(user);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView style={styles.scrollView}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>{t('management.title')}</Text>
          <Text style={styles.subtitle}>{t('management.subtitle')}</Text>
        </View>

        {/* 核心管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.coreManagement')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="account-group"
              title={t('management.employeeManagement')}
              color="#667eea"
              onPress={() => navigation.navigate('EmployeeList')}
            />
            {!isRestaurantMode && (
            <GridItem
              icon="devices"
              title={t('management.deviceCenter', '设备中心')}
              color="#52c41a"
              onPress={() => navigation.navigate('UnifiedDeviceManagement')}
            />
            )}
            {/* 设备分析已移除 (2026-06-04): /equipment/analysis/* 后端未实现 (404) */}
            {!isRestaurantMode && (
            <GridItem
              icon="clipboard-check"
              title={t('management.workReport', '生产报工')}
              color="#ff7043"
              onPress={() => navigation.navigate('WorkReportApproval')}
            />
            )}
            {!isRestaurantMode && useFactoryFeatureStore.getState().isProcessMode() && (
            <GridItem
              icon="format-list-checks"
              title="工序任务"
              color="#0891B2"
              testID="fa-process-task-btn"
              onPress={() => navigation.navigate('ProcessTaskList' as never)}
            />
            )}
            <GridItem
              icon="domain"
              title={t('management.departmentManagement')}
              color="#fa8c16"
              onPress={() => navigation.navigate('DepartmentManagement')}
            />
          </View>
        </View>

        {/* 餐饮运营 (restaurant mode only) */}
        {isRestaurantMode && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.restaurantOps', '餐饮运营')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="food"
              title={t('management.recipeManagement', '配方管理')}
              color="#FF6B35"
              onPress={() => navigation.navigate('RecipeList')}
            />
            <GridItem
              icon="clipboard-arrow-down"
              title={t('management.requisitionCreate', '新建领料')}
              color="#1B65A8"
              onPress={() => navigation.navigate('RequisitionCreate')}
            />
            <GridItem
              icon="clipboard-check"
              title={t('management.requisitionApproval', '领料审批')}
              color="#388e3c"
              onPress={() => navigation.navigate('RequisitionApproval')}
            />
            <GridItem
              icon="delete-alert"
              title={t('management.wastageRecords', '损耗记录')}
              color="#FF5630"
              onPress={() => navigation.navigate('WastageList')}
            />
            <GridItem
              icon="clipboard-text-outline"
              title={t('management.stocktaking', '盘点管理')}
              color="#FFAB00"
              onPress={() => navigation.navigate('StocktakingList')}
            />
            <GridItem
              icon="clipboard-edit-outline"
              title={t('management.chefRequisition', '报货追踪')}
              color="#1B65A8"
              testID="fa-chef-requisition-btn"
              onPress={() => navigation.navigate('ChefRequisitionList')}
            />
            <GridItem
              icon="truck-check"
              title={t('management.supplierDelivery', '待验收入库')}
              color="#409eff"
              testID="fa-supplier-delivery-btn"
              onPress={() => navigation.navigate('SupplierDeliveryList')}
            />
          </View>
        </View>
        )}

        {/* 业务管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.businessManagement')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="cube-outline"
              title={isRestaurantMode ? t('management.dishManagement', '菜品管理') : t('management.productTypeManagement')}
              color="#1890ff"
              onPress={() => navigation.navigate('ProductTypeManagement')}
            />
            <GridItem
              icon="package-variant"
              title={isRestaurantMode ? t('management.ingredientTypeManagement', '食材类型') : t('management.materialTypeManagement')}
              color="#eb2f96"
              onPress={() => navigation.navigate('MaterialTypeManagement')}
            />
            {/* T125: 転换率配置入口已移除 — 后端 API/fallback 仍保留 (F001等BomExpansionService依赖) */}
            <GridItem
              icon="cog-transfer-outline"
              title={t('management.workProcesses', '工序管理')}
              color="#5b8def"
              onPress={() => navigation.navigate('WorkProcessList')}
            />
            <GridItem
              icon="format-list-numbered"
              title={t('management.productWorkProcesses', '产品工序配置')}
              color="#36cfc9"
              onPress={() => navigation.navigate('ProductWorkProcessConfig')}
            />
          </View>
        </View>

        {/* 供应链管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.supplyChainManagement')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="truck-delivery"
              title={t('management.supplierManagement')}
              color="#13c2c2"
              onPress={() => navigation.navigate('SupplierManagement')}
            />
            <GridItem
              icon="store"
              title={t('management.customerManagement')}
              color="#f5222d"
              onPress={() => navigation.navigate('CustomerManagement')}
            />
            <GridItem
              icon="truck"
              title={t('management.shipmentManagement')}
              color="#faad14"
              onPress={() => navigation.navigate('ShipmentManagement')}
            />
          </View>
        </View>

        {/* 进销存管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{isRestaurantMode ? t('management.storeInventory', '门店进销存') : t('management.inventoryManagement', '进销存管理')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="cart-arrow-down"
              title={t('management.purchaseOrders', '采购订单')}
              color="#409eff"
              onPress={() => navigation.navigate('PurchaseOrderList')}
            />
            <GridItem
              icon="cart-arrow-up"
              title={t('management.salesOrders', '销售订单')}
              color="#67c23a"
              onPress={() => navigation.navigate('SalesOrderList')}
            />
            <GridItem
              icon="package-variant-closed"
              title={isRestaurantMode ? t('management.semiFinishedGoods', '半成品库存') : t('management.finishedGoods', '成品库存')}
              color="#722ed1"
              onPress={() => navigation.navigate('FinishedGoodsList')}
            />
            <GridItem
              icon="swap-horizontal-bold"
              title={t('management.transfer', '调拨管理')}
              color="#fa8c16"
              onPress={() => navigation.navigate('TransferList')}
            />
            <GridItem
              icon="cash-multiple"
              title={t('management.arAp', '应收应付')}
              color="#eb2f96"
              onPress={() => navigation.navigate('ArApOverview')}
            />
            <GridItem
              icon="tag-text"
              title={t('management.priceList', '价格表')}
              color="#13c2c2"
              onPress={() => navigation.navigate('PriceList')}
            />
            <GridItem
              icon="keyboard-return"
              title={t('management.returns', '退货管理')}
              color="#f56c6c"
              onPress={() => navigation.navigate('ReturnOrderList')}
            />
            <GridItem
              icon="cash-check"
              title="录入收款"
              color="#52c41a"
              onPress={() => navigation.navigate('PaymentRecord' as never)}
            />
            <GridItem
              icon="flask-outline"
              title="研发需求"
              color="#722ed1"
              onPress={() => navigation.navigate('RdRequestCreate' as never)}
            />
          </View>
        </View>

        {/* 系统配置分区已移除 (2026-06-04): 表单配置/页面编辑器/表单模版/规则配置/
            AI智能初始化/编码规则/质检项配置/SOP配置/AI意图查看 均为管理员在 web 后台
            使用的配置工具, 不适合手机端 (路由仍在 navigator 注册, 仅从菜单隐藏). */}

        {/* 其他 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.other')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="delete-outline"
              title={t('management.disposalRecordManagement')}
              color="#8c8c8c"
              onPress={() => navigation.navigate('DisposalRecordManagement')}
            />
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 20,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
  },
  section: {
    marginTop: 16,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
    marginBottom: 12,
    marginLeft: 4,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 8,
  },
  gridItem: {
    width: '33.33%',
    alignItems: 'center',
    paddingVertical: 16,
  },
  gridIcon: {
    width: 56,
    height: 56,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
  },
  gridTitle: {
    fontSize: 13,
    color: '#333',
  },
});

export default FAManagementScreen;
