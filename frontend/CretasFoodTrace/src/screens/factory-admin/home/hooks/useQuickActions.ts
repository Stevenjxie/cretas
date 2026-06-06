/**
 * useQuickActions - Computes quick action items based on factory/restaurant mode
 */
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAHomeStackParamList } from '../../../../types/navigation';
import { useFactoryFeatureStore } from '../../../../store/factoryFeatureStore';
import type { QuickActionItem } from '../types';

type NavigationProp = NativeStackNavigationProp<FAHomeStackParamList, 'FAHome'>;

// Screen guard mapping for quick actions
const QUICK_ACTION_SCREEN_GUARD: Record<string, string> = {
  dataReport: 'AIReport',
  staffManagement: 'EmployeeList',
  inventory: 'FinishedGoodsList',
  qualityCheck: 'QualityCheckItemConfig',
  systemConfig: 'SystemSettings',
  newPurchase: 'PurchaseOrderList',
  newSales: 'SalesOrderList',
  chefRequisition: 'ChefRequisitionList',
  supplierDelivery: 'SupplierDeliveryList',
  priceAnomalyApproval: 'PriceAnomalyApproval',
  requisitionApproval: 'RequisitionApproval',
  wastage: 'WastageList',
  stocktaking: 'StocktakingList',
};

export function useQuickActions(isRestaurantMode: boolean) {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('home');
  const { isScreenEnabled } = useFactoryFeatureStore();

  const allQuickActions = useMemo((): QuickActionItem[] => {
    const raw: QuickActionItem[] = isRestaurantMode
      ? [
          {
            id: 'chefRequisition',
            icon: 'clipboard-text-outline',
            label: t('quickActions.chefRequisition', 'Stall requisition'),
            color: '#1B65A8',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'ChefRequisitionList' });
            },
          },
          {
            id: 'supplierDelivery',
            icon: 'truck-delivery',
            label: t('quickActions.supplierDelivery', 'Delivery check'),
            color: '#409eff',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'SupplierDeliveryList' });
            },
          },
          {
            id: 'priceAnomalyApproval',
            icon: 'cash-multiple',
            label: t('quickActions.priceAnomalyApproval', 'Price anomaly approval'),
            color: '#e6a23c',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'PriceAnomalyApproval' });
            },
          },
          {
            id: 'requisitionApproval',
            icon: 'clipboard-check-outline',
            label: t('quickActions.requisitionApproval', 'Requisition approval'),
            color: '#67c23a',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'RequisitionApproval' });
            },
          },
          {
            id: 'wastage',
            icon: 'delete-alert-outline',
            label: t('quickActions.wastage', 'Wastage'),
            color: '#38b2ac',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'WastageList' });
            },
          },
          {
            id: 'stocktaking',
            icon: 'clipboard-list-outline',
            label: t('quickActions.stocktaking', 'Stocktaking'),
            color: '#ed8936',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'StocktakingList' });
            },
          },
        ]
      : [
          {
            id: 'newBatch',
            icon: 'plus-circle',
            label: t('quickActions.createPlan'),
            color: '#667eea',
            onPress: () => {
              navigation.getParent()?.navigate('FAAITab', { screen: 'CreatePlan' });
            },
          },
          {
            id: 'dataReport',
            icon: 'chart-line',
            label: t('quickActions.dataReport'),
            color: '#48bb78',
            onPress: () => {
              navigation.getParent()?.navigate('FAAITab', { screen: 'AIReport' });
            },
          },
          {
            id: 'staffManagement',
            icon: 'account-group',
            label: t('quickActions.staffManagement'),
            color: '#ed8936',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'EmployeeList' });
            },
          },
          {
            id: 'systemConfig',
            icon: 'cog',
            label: t('quickActions.systemConfig'),
            color: '#805ad5',
            onPress: () => {
              navigation.getParent()?.navigate('FAProfileTab', { screen: 'SystemSettings' });
            },
          },
          {
            id: 'inventory',
            icon: 'warehouse',
            label: t('quickActions.inventoryView', 'Inventory'),
            color: '#38b2ac',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'FinishedGoodsList' });
            },
          },
          {
            id: 'qualityCheck',
            icon: 'clipboard-check',
            label: t('quickActions.qualityManagement', 'Quality'),
            color: '#e53e3e',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'QualityCheckItemConfig' });
            },
          },
          {
            id: 'newPurchase',
            icon: 'cart-arrow-down',
            label: t('quickActions.newPurchase', 'New purchase'),
            color: '#409eff',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'PurchaseOrderList' });
            },
          },
          {
            id: 'newSales',
            icon: 'cart-arrow-up',
            label: t('quickActions.newSales', 'New sale'),
            color: '#67c23a',
            onPress: () => {
              navigation.getParent()?.navigate('FAManagementTab', { screen: 'SalesOrderList' });
            },
          },
        ];

    return raw.filter(action => {
      const screenName = QUICK_ACTION_SCREEN_GUARD[action.id];
      return !screenName || isScreenEnabled(screenName);
    });
  }, [isRestaurantMode, navigation, t, isScreenEnabled]);

  return { allQuickActions, quickActionScreenGuard: QUICK_ACTION_SCREEN_GUARD };
}
