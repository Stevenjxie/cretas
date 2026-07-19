// @ts-nocheck

import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockAnalyzeEmployee = jest.fn();

jest.mock('../../../services/api/employeeAIApiClient', () => ({
  employeeAIApiClient: {
    analyzeEmployee: (...args: unknown[]) => mockAnalyzeEmployee(...args),
  },
}));

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
  useRoute: () => ({ params: { staffId: 17 } }),
  useFocusEffect: (effect: () => void) => {
    const ReactModule = require('react');
    ReactModule.useEffect(effect, [effect]);
  },
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));

jest.mock('@expo/vector-icons', () => ({
  MaterialCommunityIcons: ({ name }: any) => {
    const { Text } = require('react-native');
    return <Text>{name}</Text>;
  },
}));

jest.mock('react-native-paper', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  const Card = ({ children, ...props }: any) => <View {...props}>{children}</View>;
  Card.Content = ({ children, ...props }: any) => <View {...props}>{children}</View>;
  return {
    Text,
    Card,
    ActivityIndicator: (props: any) => <View {...props} />,
    Button: ({ children, onPress, ...props }: any) => (
      <TouchableOpacity onPress={onPress} {...props}><Text>{children}</Text></TouchableOpacity>
    ),
    ProgressBar: (props: any) => <View testID="score-progress" {...props} />,
  };
});

jest.mock('../../../components/common/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => {
    const { Text } = require('react-native');
    return <Text>{content}</Text>;
  },
}));

// The broad historical `react-native` moduleNameMapper also catches
// react-native-safe-area-context. Supply the named export on that resolved mock.
const resolvedSafeAreaModule = require('react-native-safe-area-context');
if (!resolvedSafeAreaModule.SafeAreaView) {
  const { View } = require('react-native');
  resolvedSafeAreaModule.SafeAreaView = ({ children, ...props }: any) => (
    <View {...props}>{children}</View>
  );
}

const StaffAIAnalysisScreen = require('../../../screens/hr/staff/StaffAIAnalysisScreen').default;

const truthfulAnalysis = {
  employeeId: 17,
  employeeName: '王员工',
  department: '加工部',
  position: '操作员',
  tenureMonths: null,
  periodStart: '2026-06-19',
  periodEnd: '2026-07-19',
  dataPoints: 14,
  overallScore: null,
  overallGrade: null,
  scoreChange: null,
  departmentRankPercent: null,
  attendance: {
    score: null,
    attendanceRate: null,
    recordCount: 3,
    attendanceDays: 2,
    lateCount: 1,
    earlyLeaveCount: 0,
    absentDays: 1,
    clockedWorkMinutes: 780,
    departmentAvgRate: null,
    insight: null,
    insightType: null,
  },
  workHours: {
    score: null,
    totalMinutes: 120,
    sessionCount: 2,
    avgDailyHours: null,
    overtimeHours: null,
    efficiency: null,
    workTypeCount: null,
    departmentAvgHours: null,
    insight: null,
    insightType: null,
  },
  production: {
    score: null,
    batchCount: 3,
    batchWorkSessionCount: 5,
    completedBatchWorkSessionCount: 2,
    batchWorkMinutes: 180,
    totalInspections: 4,
    passedInspections: 3,
    outputQuantity: null,
    qualityRate: 75,
    productivityRate: null,
    departmentAvgProductivity: null,
    topProductLine: null,
    insight: null,
    insightType: null,
  },
  skills: [],
  suggestions: [],
  trends: [],
  aiInsight: '仅基于十四条原始记录得出的事实洞察',
  sessionId: 'employee-session',
  analyzedAt: '2026-07-19T10:00:00',
  tokensUsed: 123,
  notComputableMetrics: ['overallScore'],
};

describe('StaffAIAnalysisScreen truthful rendering', () => {
  beforeEach(() => {
    mockAnalyzeEmployee.mockReset();
    mockAnalyzeEmployee.mockResolvedValue(truthfulAnalysis);
  });

  it('keeps null scores uncomputed and never copies one score into five dimensions', async () => {
    const screen = render(<StaffAIAnalysisScreen />);

    await waitFor(() => expect(screen.getByText('AI事实洞察')).toBeTruthy());

    expect(screen.getByText('—')).toBeTruthy();
    expect(screen.getAllByText('不可计算')).toHaveLength(4);
    expect(screen.queryAllByTestId('score-progress')).toHaveLength(0);
    expect(screen.queryByText('staff.ai.scores.teamwork')).toBeNull();
    expect(screen.queryByText('0')).toBeNull();
    expect(screen.getByText('批次工作会话')).toBeTruthy();
    expect(screen.getByText('已完成批次工作会话')).toBeTruthy();
  });

  it('renders the backend AI insight verbatim', async () => {
    const screen = render(<StaffAIAnalysisScreen />);

    await waitFor(() => {
      expect(screen.getByText('仅基于十四条原始记录得出的事实洞察')).toBeTruthy();
    });
  });

  it('uses exactly one additional API request for reanalysis', async () => {
    const screen = render(<StaffAIAnalysisScreen />);
    await waitFor(() => expect(mockAnalyzeEmployee).toHaveBeenCalledTimes(1));

    await act(async () => {
      fireEvent.press(screen.getByTestId('reanalyze-button'));
    });

    await waitFor(() => expect(mockAnalyzeEmployee).toHaveBeenCalledTimes(2));
    expect(mockAnalyzeEmployee).toHaveBeenLastCalledWith(17, { days: 90 });
  });
});
