import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import WHInboundStackNavigator from './warehouse/WHInboundStackNavigator';
import WHOutboundStackNavigator from './warehouse/WHOutboundStackNavigator';
import WHInventoryStackNavigator from './warehouse/WHInventoryStackNavigator';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';

const Tab = createBottomTabNavigator();

/** 仓库员直接进入收货、出货和库存任务，不开放主管首页、分析、预警治理或管理型个人中心。 */
export default function WarehouseWorkerNavigator() {
  const insets = useSafeAreaInsets();
  const { isScreenEnabled } = useFactoryFeatureStore();

  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2E7D32',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      {isScreenEnabled('InboundManagement') && (
        <Tab.Screen
          name="WarehouseWorkerInboundTab"
          component={WHInboundStackNavigator}
          options={{ title: '收货', tabBarIcon: ({ color, size }) => <Icon source="package-down" color={color} size={size} /> }}
        />
      )}
      {isScreenEnabled('OutboundManagement') && (
        <Tab.Screen
          name="WarehouseWorkerOutboundTab"
          component={WHOutboundStackNavigator}
          options={{ title: '出货', tabBarIcon: ({ color, size }) => <Icon source="package-up" color={color} size={size} /> }}
        />
      )}
      <Tab.Screen
        name="WarehouseWorkerInventoryTab"
        component={WHInventoryStackNavigator}
        options={{ title: '库存', tabBarIcon: ({ color, size }) => <Icon source="warehouse" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="WarehouseWorkerProfileTab"
        component={MobileAccountScreen}
        options={{ title: '我的', tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} /> }}
      />
    </Tab.Navigator>
  );
}
