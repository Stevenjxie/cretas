// @ts-nocheck
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

let mockUser: any;
let mockRole: string | null;
let mockRunActive = false;
const mockExecuteIntentStream = jest.fn();
const mockStartRun = jest.fn();
const mockReplayRun = jest.fn();

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector?: (state: any) => unknown) => {
    const state = {
      user: mockUser,
      getUserRole: () => mockRole,
    };
    return selector ? selector(state) : state;
  },
}));

jest.mock('../../../services/api/restaurantAgentRuns', () => ({
  currentMonthRestaurantAgentWindow: () => ({ startDate: '2026-07-01', endDate: '2026-07-19' }),
  isRestaurantAgentRunActive: () => mockRunActive,
  startGrossMarginDeclineRun: (...args: unknown[]) => mockStartRun(...args),
  replayGrossMarginDeclineRun: (...args: unknown[]) => mockReplayRun(...args),
}));

jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: { executeIntentStream: (...args: unknown[]) => mockExecuteIntentStream(...args) },
}));

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({
    dispatch: jest.fn(),
    getParent: jest.fn(() => null),
  }),
  useRoute: () => ({ params: undefined }),
  CommonActions: { navigate: jest.fn((name) => ({ name })) },
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh-CN' },
  }),
}));

jest.mock('i18next', () => ({ __esModule: true, default: { language: 'zh-CN' } }));
jest.mock('expo-linear-gradient', () => ({
  LinearGradient: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('@expo/vector-icons', () => ({
  MaterialCommunityIcons: (props: any) => {
    const { Text } = require('react-native');
    return <Text>{props.name}</Text>;
  },
}));
jest.mock('../../../services/ai', () => ({
  aiService: {},
  detectAnalysisMode: () => ({ mode: 'quick' }),
}));
jest.mock('../../../components/ai/AIModeIndicator', () => ({ AIModeIndicator: () => null }));
jest.mock('../../../components/ai/FeedbackWidget', () => ({ FeedbackWidget: () => null }));
jest.mock('../../../components/ai/RichContentRenderer', () => ({
  RichContentRenderer: () => null,
  detectRichData: () => null,
}));
jest.mock('../../../components/ai/QuickActionCardGrid', () => ({ QuickActionCardGrid: () => null }));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: {
    onWriteSuccess: jest.fn(),
    onNeedMoreInfo: jest.fn(),
    onError: jest.fn(),
  },
}));

// The shared Jest setup eagerly caches its react-native-paper mock before this
// test module runs. Patch that cached object before importing the screen so its
// legacy IconButton does not require the native Paper runtime.
const paperMock = jest.requireMock('react-native-paper');
paperMock.IconButton = (props: any) => {
  const { Text } = require('react-native');
  return <Text>{props.icon}</Text>;
};
const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

const RUN_ID = '11111111-1111-4111-8111-111111111111';
const runEvent = (sequence: number, eventType: string) => ({
  schemaVersion: '1.0',
  runId: RUN_ID,
  sequence,
  eventType,
  stepId: null,
  toolName: null,
  payload: {},
});

function restaurantUser(factoryId = 'REST-1') {
  return {
    id: 1,
    userType: 'factory',
    factoryUser: {
      factoryId,
      factoryType: 'RESTAURANT',
      role: 'restaurant_owner',
      permissions: [],
    },
  };
}

describe('AIChatScreen restaurant agent run', () => {
  beforeEach(() => {
    mockUser = restaurantUser();
    mockRole = 'restaurant_owner';
    mockRunActive = false;
    mockExecuteIntentStream.mockReset();
    mockStartRun.mockReset();
    mockReplayRun.mockReset();
  });

  it('renders no Run entry when OFF, non-restaurant, or outside the price-role allowlist', () => {
    const off = render(<AIChatScreen />);
    expect(off.queryByTestId('restaurant-agent-run-card')).toBeNull();
    off.unmount();

    mockRunActive = true;
    mockUser = { ...restaurantUser(), factoryUser: { ...restaurantUser().factoryUser, factoryType: 'FACTORY' } };
    const factory = render(<AIChatScreen />);
    expect(factory.queryByTestId('restaurant-agent-run-card')).toBeNull();
    factory.unmount();

    mockUser = restaurantUser();
    mockRole = 'viewer';
    const viewer = render(<AIChatScreen />);
    expect(viewer.queryByTestId('restaurant-agent-run-card')).toBeNull();
    expect(mockStartRun).not.toHaveBeenCalled();
  });

  it('shows the explicit local-month window and de-duplicates SSE plus replay', async () => {
    mockRunActive = true;
    mockRole = ' Restaurant_Owner ';
    mockStartRun.mockImplementation(async (_factoryId, _body, callbacks) => {
      callbacks.onEvent(runEvent(1, 'RUN_STARTED'));
      return {
        stopReceiving: jest.fn(),
        completion: Promise.resolve({ runId: RUN_ID, lastSequence: 1, stoppedReceiving: false }),
      };
    });
    mockReplayRun.mockResolvedValue({
      schemaVersion: '1.0',
      runId: RUN_ID,
      state: 'COMPLETED',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [runEvent(1, 'RUN_STARTED'), runEvent(2, 'RUN_COMPLETED')],
      terminalOutcome: {
        status: 'COMPLETE',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        claims: [],
        blockers: [],
        observations: ['margin_decline_attributed'],
        attributionSupported: true,
      },
      failureCode: null,
    });
    const screen = render(<AIChatScreen />);
    expect(screen.getByText('本月：2026-07-01 至 2026-07-19')).toBeTruthy();

    await act(async () => {
      fireEvent.press(screen.getByTestId('restaurant-agent-start'));
    });
    await waitFor(() => expect(screen.getByTestId('restaurant-agent-outcome')).toBeTruthy());

    expect(mockStartRun).toHaveBeenCalledWith(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      expect.any(Object),
    );
    expect(mockReplayRun).toHaveBeenCalledWith('REST-1', RUN_ID, 1);
    expect(screen.getAllByText('#1 运行已创建')).toHaveLength(1);
    expect(screen.getByText('#2 运行完成')).toBeTruthy();
    expect(screen.getByText(/不代表完整 EvidenceEnvelope/)).toBeTruthy();
  });

  it('fails free chat explicitly when factory identity is missing and never uses F001', async () => {
    mockUser = restaurantUser('');
    mockRole = 'restaurant_owner';
    const screen = render(<AIChatScreen />);
    fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
    fireEvent.changeText(screen.getByTestId('ai-chat-input'), '查询今天经营情况');
    await act(async () => {
      fireEvent.press(screen.getByTestId('ai-chat-send-btn'));
    });

    await waitFor(() => expect(screen.getByText('当前账号缺少工厂标识，请重新登录')).toBeTruthy());
    expect(mockExecuteIntentStream).not.toHaveBeenCalled();
  });
});
