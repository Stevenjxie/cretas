/**
 * HR Tab 导航器
 *
 * 5 Tab 结构：首页 | 人员 | 考勤 | 白名单 | 我的
 */

import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Platform, View, StyleSheet } from 'react-native';

import HRHomeStackNavigator from './hr/HRHomeStackNavigator';
import HRStaffStackNavigator from './hr/HRStaffStackNavigator';
import HRAttendanceStackNavigator from './hr/HRAttendanceStackNavigator';
import HRWhitelistStackNavigator from './hr/HRWhitelistStackNavigator';
import HRProfileStackNavigator from './hr/HRProfileStackNavigator';

import { HR_THEME, type HRTabParamList } from '../types/hrNavigation';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import { useAuthStore } from '../store/authStore';
import { canAccessWhitelist } from '../utils/permissionHelper';

const Tab = createBottomTabNavigator<HRTabParamList>();

export default function HRTabNavigator() {
  const { isScreenEnabled } = useFactoryFeatureStore();
  const { user } = useAuthStore();

  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: HR_THEME.primary,
        tabBarInactiveTintColor: HR_THEME.textMuted,
        tabBarStyle: {
          backgroundColor: HR_THEME.cardBackground,
          borderTopColor: HR_THEME.border,
          borderTopWidth: 1,
          height: Platform.OS === 'ios' ? 85 : 65,
          paddingBottom: Platform.OS === 'ios' ? 25 : 8,
          paddingTop: 8,
        },
        tabBarLabelStyle: {
          fontSize: 11,
          fontWeight: '500',
        },
      }}
    >
      <Tab.Screen
        name="HomeTab"
        component={HRHomeStackNavigator}
        options={{
          tabBarLabel: '首页',
          tabBarIcon: ({ color, size, focused }) => (
            <View style={focused ? styles.activeTab : undefined}>
              <MaterialCommunityIcons
                name={focused ? 'view-dashboard' : 'view-dashboard-outline'}
                size={size}
                color={color}
              />
            </View>
          ),
        }}
      />
      <Tab.Screen
        name="StaffTab"
        component={HRStaffStackNavigator}
        options={{
          tabBarLabel: '人员',
          tabBarIcon: ({ color, size, focused }) => (
            <View style={focused ? styles.activeTab : undefined}>
              <MaterialCommunityIcons
                name={focused ? 'account-group' : 'account-group-outline'}
                size={size}
                color={color}
              />
            </View>
          ),
        }}
      />
      {isScreenEnabled('AttendanceManagement') && (
      <Tab.Screen
        name="AttendanceTab"
        component={HRAttendanceStackNavigator}
        options={{
          tabBarLabel: '考勤',
          tabBarIcon: ({ color, size, focused }) => (
            <View style={focused ? styles.activeTab : undefined}>
              <MaterialCommunityIcons
                name={focused ? 'calendar-check' : 'calendar-check-outline'}
                size={size}
                color={color}
              />
            </View>
          ),
        }}
      />
      )}
      {/* APP-RBAC-003: 工厂开关 AND 后端角色准入 — 只看开关会给 hr_admin 展示一个必然 403 的 Tab */}
      {isScreenEnabled('WhitelistManagement') && canAccessWhitelist(user) && (
      <Tab.Screen
        name="WhitelistTab"
        component={HRWhitelistStackNavigator}
        options={{
          tabBarLabel: '白名单',
          tabBarIcon: ({ color, size, focused }) => (
            <View style={focused ? styles.activeTab : undefined}>
              <MaterialCommunityIcons
                name={focused ? 'shield-check' : 'shield-check-outline'}
                size={size}
                color={color}
              />
            </View>
          ),
        }}
      />
      )}
      <Tab.Screen
        name="ProfileTab"
        component={HRProfileStackNavigator}
        options={{
          tabBarLabel: '我的',
          tabBarIcon: ({ color, size, focused }) => (
            <View style={focused ? styles.activeTab : undefined}>
              <MaterialCommunityIcons
                name={focused ? 'account-circle' : 'account-circle-outline'}
                size={size}
                color={color}
              />
            </View>
          ),
        }}
      />
    </Tab.Navigator>
  );
}

const styles = StyleSheet.create({
  activeTab: {
    backgroundColor: HR_THEME.primary + '15',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 4,
  },
});
