/**
 * 应用根导航器
 * 处理认证状态和角色路由
 */
import React, { useCallback, useEffect } from "react";
import { ActivityIndicator, View } from "react-native";
import { NavigationContainer } from "@react-navigation/native";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Provider as PaperProvider } from "react-native-paper";
import * as Notifications from "expo-notifications";
import { useAuthStore } from "../store/authStore";
import { useFactoryFeatureStore } from "../store/factoryFeatureStore";
import { getUserRole, getFactoryId } from "../types/auth";
import { theme } from "../theme";
import { usePushNotifications } from "../hooks/usePushNotifications";
import { logger } from "../utils/logger";

const pushLogger = logger.createContextLogger('PushNotifications');

// 认证页面
import EnhancedLoginScreen from "../screens/auth/EnhancedLoginScreen";
import RegisterScreen from "../screens/auth/RegisterScreen";
import ForgotPasswordScreen from "../screens/auth/ForgotPasswordScreen";

// 主导航器
import MainNavigator from "./MainNavigator";
import FactoryAdminNavigator from "./FactoryAdminNavigator";
import WorkshopSupervisorNavigator from "./WorkshopSupervisorNavigator";
import WarehouseManagerNavigator from "./WarehouseManagerNavigator";
import HRNavigator from "./HRNavigator";
import DispatcherNavigator from "./DispatcherNavigator";
import QualityInspectorNavigator from "./QualityInspectorNavigator";
import OperatorNavigator from "./OperatorNavigator";
import SalesManagerNavigator from "./SalesManagerNavigator";
import ProcurementManagerNavigator from "./ProcurementManagerNavigator";
import ViewerNavigator from "./ViewerNavigator";
import FinanceNavigator from "./FinanceNavigator";

const Stack = createNativeStackNavigator();

/**
 * 根据用户角色选择导航器
 */
function RoleBasedNavigator() {
  const { user } = useAuthStore();
  const userRole = getUserRole(user);

  // 处理收到前台通知
  const handleNotificationReceived = useCallback(
    (notification: Notifications.Notification) => {
      pushLogger.info('收到前台通知', {
        title: notification.request.content.title,
        body: notification.request.content.body,
        data: notification.request.content.data,
      });
    },
    []
  );

  // 处理用户点击通知
  const handleNotificationTapped = useCallback(
    (response: Notifications.NotificationResponse) => {
      const data = response.notification.request.content.data as Record<string, unknown>;
      pushLogger.info('用户点击通知', { data });

      // 根据通知数据进行导航
      if (data?.screen) {
        pushLogger.info('尝试导航到', { screen: data.screen });
        // 通知导航由各个 Navigator 内部处理
        // 这里记录日志用于调试
      }
    },
    []
  );

  // 集成推送通知 Hook
  usePushNotifications({
    onNotificationReceived: handleNotificationReceived,
    onNotificationTapped: handleNotificationTapped,
    autoRegisterOnLogin: true,
    autoUnregisterOnLogout: true,
  });

  // factory_super_admin 使用工厂管理员界面
  if (userRole === "factory_super_admin") {
    return <FactoryAdminNavigator />;
  }

  // hr_admin (HR管理员) 使用HR专属界面
  if (userRole === "hr_admin") {
    return <HRNavigator />;
  }

  // workshop_supervisor (车间主任) 使用车间主任专属界面
  if (userRole === "workshop_supervisor" || userRole === "department_admin") {
    return <WorkshopSupervisorNavigator />;
  }

  // warehouse_manager / warehouse_worker 使用仓储专属界面
  if (userRole === "warehouse_manager" || userRole === "warehouse_worker") {
    return <WarehouseManagerNavigator />;
  }

  // dispatcher (调度员) 使用调度专属界面
  if (userRole === "dispatcher") {
    return <DispatcherNavigator />;
  }

  // quality_inspector (质检员) 使用质检专属界面
  if (userRole === "quality_inspector") {
    return <QualityInspectorNavigator />;
  }

  // operator (操作员) 使用操作员专属界面
  if (userRole === "operator" || userRole === "yield_operator") {
    return <OperatorNavigator />;
  }

  // sales_manager (销售经理) 使用销售专属界面
  if (userRole === "sales_manager") {
    return <SalesManagerNavigator />;
  }

  // procurement_manager (采购经理) 使用采购专属界面
  if (userRole === "procurement_manager") {
    return <ProcurementManagerNavigator />;
  }

  if (userRole === "finance_manager" || userRole === "cashier") {
    return <FinanceNavigator />;
  }

  // viewer (查看员) 使用只读界面
  if (userRole === "viewer") {
    return <ViewerNavigator />;
  }

  // 其他角色使用原有界面
  return <MainNavigator />;
}

function FeatureLoadingScreen() {
  return (
    <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: '#fff' }}>
      <ActivityIndicator size="large" color="#4A7C59" />
    </View>
  );
}

export function AppNavigator() {
  const { isAuthenticated, user } = useAuthStore();
  const { loaded: featuresLoaded, loadFeatures } = useFactoryFeatureStore();

  // App启动时恢复工厂特性配置（防止persist恢复后权限绕过）
  useEffect(() => {
    if (isAuthenticated && user && !featuresLoaded) {
      const factoryId = getFactoryId(user);
      if (factoryId) {
        loadFeatures(factoryId);
      } else {
        // platform_admin has no factoryId — skip feature loading, all modules enabled by default
        useFactoryFeatureStore.setState({ loaded: true });
      }
    }
  }, [isAuthenticated, user, featuresLoaded]);

  return (
    <PaperProvider theme={theme}>
      <NavigationContainer>
        <Stack.Navigator
          screenOptions={{
            headerShown: false,
          }}
        >
          {!isAuthenticated ? (
            // 未登录 - 显示登录流程
            <>
              <Stack.Screen
                name="Login"
                component={EnhancedLoginScreen}
              />
              <Stack.Screen
                name="EnhancedLogin"
                component={EnhancedLoginScreen}
              />
              <Stack.Screen
                name="LoginScreen"
                component={EnhancedLoginScreen}
              />
              <Stack.Screen
                name="RegisterScreen"
                component={RegisterScreen}
              />
              <Stack.Screen
                name="ForgotPassword"
                component={ForgotPasswordScreen}
              />
            </>
          ) : !featuresLoaded ? (
            // 已登录但特性配置未加载 - 等待加载完成
            <Stack.Screen
              name="Loading"
              component={FeatureLoadingScreen}
            />
          ) : (
            // 已登录且特性已加载 - 根据角色显示不同界面
            <Stack.Screen
              name="Main"
              component={RoleBasedNavigator}
            />
          )}
        </Stack.Navigator>
      </NavigationContainer>
    </PaperProvider>
  );
}

export default AppNavigator;
