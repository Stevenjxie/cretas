/**
 * OA 待办 Stack Navigator
 *
 * 路由:
 *   MyTodoList  → MyTodoListScreen   (待办列表，tab 入口)
 *   TodoDetail  → TodoDetailScreen   (大额单详情+审批)
 *
 * 访问角色: finance_manager / cashier（MainNavigator 中条件渲染此 Tab）
 *
 * @since 2026-06-12 (OA 待办 R4)
 */

import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import MyTodoListScreen from '../screens/oa/MyTodoListScreen';
import TodoDetailScreen from '../screens/oa/TodoDetailScreen';
import type { OATodoStackParamList } from '../types/navigation';

const Stack = createNativeStackNavigator<OATodoStackParamList>();

export default function OATodoStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,  // Appbar.Header from react-native-paper 自管理
      }}
    >
      <Stack.Screen name="MyTodoList" component={MyTodoListScreen} />
      <Stack.Screen name="TodoDetail" component={TodoDetailScreen} />
    </Stack.Navigator>
  );
}
