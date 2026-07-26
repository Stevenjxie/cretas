/**
 * 质检模块导航器
 * Quality Inspector Module Navigator
 *
 * 导航结构:
 * - 底部 Tab 导航 (5个)
 *   - 首页 Tab
 *   - 质检 Tab
 *   - 记录 Tab
 *   - 分析 Tab
 *   - 我的 Tab
 * - 每个 Tab 内有 Stack 导航
 */

import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Ionicons } from '@expo/vector-icons';
import { QI_COLORS, QualityInspectorStackParamList } from '../types/qualityInspector';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';

// Type for Ionicons names
type IoniconsName = keyof typeof Ionicons.glyphMap;

// 屏幕组件导入
import QIHomeScreen from '../screens/quality-inspector/QIHomeScreen';
import QIInspectListScreen from '../screens/quality-inspector/QIInspectListScreen';
import QIBatchSelectScreen from '../screens/quality-inspector/QIBatchSelectScreen';
import QIScanScreen from '../screens/quality-inspector/QIScanScreen';
import QIFormScreen from '../screens/quality-inspector/QIFormScreen';
import QIVoiceScreen from '../screens/quality-inspector/QIVoiceScreen';
import VoiceInspectionResultScreen from '../screens/quality-inspector/VoiceInspectionResultScreen';
import QICameraScreen from '../screens/quality-inspector/QICameraScreen';
import QILabelQcQueueScreen from '../screens/quality-inspector/QILabelQcQueueScreen';
import QILabelQcCreateScreen from '../screens/quality-inspector/QILabelQcCreateScreen';
import QILabelQcReviewScreen from '../screens/quality-inspector/QILabelQcReviewScreen';
import QILabelQcSubmittedScreen from '../screens/quality-inspector/QILabelQcSubmittedScreen';
import QIResultScreen from '../screens/quality-inspector/QIResultScreen';
import QIRecordsScreen from '../screens/quality-inspector/QIRecordsScreen';
import QIRecordDetailScreen from '../screens/quality-inspector/QIRecordDetailScreen';
import QIAnalysisScreen from '../screens/quality-inspector/QIAnalysisScreen';
import QITrendScreen from '../screens/quality-inspector/QITrendScreen';
import QIReportScreen from '../screens/quality-inspector/QIReportScreen';
import QIProfileScreen from '../screens/quality-inspector/QIProfileScreen';
import QISettingsScreen from '../screens/quality-inspector/QISettingsScreen';
import QIClockInScreen from '../screens/quality-inspector/QIClockInScreen';
import QINotificationsScreen from '../screens/quality-inspector/QINotificationsScreen';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator<QualityInspectorStackParamList>();

// ============================================
// Tab 图标组件
// ============================================

type TabIconName = 'home' | 'search' | 'document-text' | 'bar-chart' | 'person';

const TAB_ICONS: Record<string, TabIconName> = {
  QIHomeTab: 'home',
  QIInspectTab: 'search',
  QIRecordsTab: 'document-text',
  QIAnalysisTab: 'bar-chart',
  QIProfileTab: 'person',
};

const TabIcon = ({
  routeName,
  focused,
  color,
  size,
}: {
  routeName: string;
  focused: boolean;
  color: string;
  size: number;
}) => {
  const iconName = TAB_ICONS[routeName] || 'ellipse';
  const iconWithOutline: IoniconsName = focused
    ? iconName
    : (`${iconName}-outline` as IoniconsName);
  return <Ionicons name={iconWithOutline} size={size} color={color} />;
};

// ============================================
// Stack 导航器
// ============================================

/**
 * 首页 Stack
 */
function HomeStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: QI_COLORS.primary },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '600' },
      }}
    >
      <Stack.Screen
        name="QIHome"
        component={QIHomeScreen}
        options={{ title: '质检工作台' }}
      />
      <Stack.Screen
        name="QINotifications"
        component={QINotificationsScreen}
        options={{ title: '通知' }}
      />
    </Stack.Navigator>
  );
}

/**
 * 质检 Stack
 */
function InspectStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: QI_COLORS.primary },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '600' },
      }}
    >
      <Stack.Screen
        name="QIInspectList"
        component={QIInspectListScreen}
        options={{ title: '待检批次' }}
      />
      <Stack.Screen
        name="QIBatchSelect"
        component={QIBatchSelectScreen}
        options={{ title: '选择批次类型' }}
      />
      <Stack.Screen
        name="QIScan"
        component={QIScanScreen}
        options={{ title: '扫码' }}
      />
      <Stack.Screen
        name="QIForm"
        component={QIFormScreen}
        options={{ title: '质检表单' }}
      />
      <Stack.Screen
        name="QIVoice"
        component={QIVoiceScreen}
        options={{ title: '语音质检' }}
      />
      <Stack.Screen
        name="QIVoiceResult"
        component={VoiceInspectionResultScreen}
        options={{ title: '语音质检结果' }}
      />
      <Stack.Screen
        name="QICamera"
        component={QICameraScreen}
        options={{ title: '拍照质检' }}
      />
      <Stack.Screen
        name="QILabelQcQueue"
        component={QILabelQcQueueScreen}
        options={{ title: '标签拍检任务' }}
      />
      <Stack.Screen
        name="QILabelQcCreate"
        component={QILabelQcCreateScreen}
        options={{ title: '包装标签拍检' }}
      />
      <Stack.Screen
        name="QILabelQcReview"
        component={QILabelQcReviewScreen}
        options={{ title: '逐张人工审核' }}
      />
      <Stack.Screen
        name="QILabelQcSubmitted"
        component={QILabelQcSubmittedScreen}
        options={{ title: '拍检状态', headerBackVisible: false }}
      />
      <Stack.Screen
        name="QIResult"
        component={QIResultScreen}
        options={{ title: '检验结果', headerBackVisible: false }}
      />
    </Stack.Navigator>
  );
}

/**
 * 记录 Stack
 */
function RecordsStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: QI_COLORS.primary },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '600' },
      }}
    >
      <Stack.Screen
        name="QIRecords"
        component={QIRecordsScreen}
        options={{ title: '检验记录' }}
      />
      <Stack.Screen
        name="QIRecordDetail"
        component={QIRecordDetailScreen}
        options={{ title: '记录详情' }}
      />
    </Stack.Navigator>
  );
}

/**
 * 分析 Stack
 */
function AnalysisStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: QI_COLORS.primary },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '600' },
      }}
    >
      <Stack.Screen
        name="QIAnalysis"
        component={QIAnalysisScreen}
        options={{ title: '数据分析' }}
      />
      <Stack.Screen
        name="QITrend"
        component={QITrendScreen}
        options={{ title: '趋势分析' }}
      />
      <Stack.Screen
        name="QIReport"
        component={QIReportScreen}
        options={{ title: '生成报告' }}
      />
    </Stack.Navigator>
  );
}

/**
 * 我的 Stack
 */
function ProfileStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: QI_COLORS.primary },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '600' },
      }}
    >
      <Stack.Screen
        name="QIProfile"
        component={QIProfileScreen}
        options={{ title: '我的' }}
      />
      <Stack.Screen
        name="QISettings"
        component={QISettingsScreen}
        options={{ title: '设置' }}
      />
      <Stack.Screen
        name="QIClockIn"
        component={QIClockInScreen}
        options={{ title: '考勤打卡' }}
      />
    </Stack.Navigator>
  );
}

// ============================================
// 主导航器
// ============================================

/**
 * 质检模块主导航器
 */
export default function QualityInspectorNavigator() {
  const { isScreenEnabled } = useFactoryFeatureStore();

  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarIcon: ({ focused, color, size }) => (
          <TabIcon
            routeName={route.name}
            focused={focused}
            color={color}
            size={size}
          />
        ),
        tabBarActiveTintColor: QI_COLORS.primary,
        tabBarInactiveTintColor: QI_COLORS.textSecondary,
        tabBarStyle: {
          backgroundColor: QI_COLORS.card,
          borderTopColor: QI_COLORS.border,
          height: 60,
          paddingBottom: 8,
          paddingTop: 8,
        },
        tabBarLabelStyle: {
          fontSize: 11,
          fontWeight: '500',
        },
      })}
    >
      <Tab.Screen
        name="QIHomeTab"
        component={HomeStack}
        options={{ tabBarLabel: '首页', tabBarButtonTestID: 'qi-tab-home' }}
      />
      {isScreenEnabled('QualityInspection') && (
      <Tab.Screen
        name="QIInspectTab"
        component={InspectStack}
        options={{ tabBarLabel: '质检', tabBarButtonTestID: 'qi-tab-inspect' }}
      />
      )}
      <Tab.Screen
        name="QIRecordsTab"
        component={RecordsStack}
        options={{ tabBarLabel: '记录', tabBarButtonTestID: 'qi-tab-records' }}
      />
      {isScreenEnabled('QualityAnalysis') && (
      <Tab.Screen
        name="QIAnalysisTab"
        component={AnalysisStack}
        options={{ tabBarLabel: '分析', tabBarButtonTestID: 'qi-tab-analysis' }}
      />
      )}
      <Tab.Screen
        name="QIProfileTab"
        component={ProfileStack}
        options={{ tabBarLabel: '我的', tabBarButtonTestID: 'qi-tab-profile' }}
      />
    </Tab.Navigator>
  );
}
