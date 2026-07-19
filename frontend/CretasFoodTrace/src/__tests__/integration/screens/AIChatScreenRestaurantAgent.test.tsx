// @ts-nocheck
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

let mockUser: any;
let mockRole: string | null;
let mockRunActive = false;
const mockExecuteIntentStream = jest.fn();
const mockStartRun = jest.fn();
const mockReplayRun = jest.fn();
const mockCancelRun = jest.fn();
const mockLoadCheckpoint = jest.fn();
const mockSaveCheckpoint = jest.fn();
const mockClearCheckpoint = jest.fn();

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
  cancelGrossMarginDeclineRun: (...args: unknown[]) => mockCancelRun(...args),
  loadRestaurantAgentRunCheckpoint: (...args: unknown[]) => mockLoadCheckpoint(...args),
  saveRestaurantAgentRunCheckpoint: (...args: unknown[]) => mockSaveCheckpoint(...args),
  clearRestaurantAgentRunCheckpoint: (...args: unknown[]) => mockClearCheckpoint(...args),
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

function restaurantUser(factoryId = 'REST-1', id = 1) {
  return {
    id,
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
    mockCancelRun.mockReset();
    mockLoadCheckpoint.mockReset().mockResolvedValue(null);
    mockSaveCheckpoint.mockReset().mockResolvedValue(undefined);
    mockClearCheckpoint.mockReset().mockResolvedValue(undefined);
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
        actionProposals: [],
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
    expect(screen.getByTestId('restaurant-agent-outcome-note')).toBeTruthy();
  });

  it('persists cancellation explicitly and suppresses repeated taps', async () => {
    mockRunActive = true;
    let callbacksRef: any;
    let resolveCompletion: (value: any) => void = () => undefined;
    const stopReceiving = jest.fn();
    mockStartRun.mockImplementation(async (_factoryId, _body, callbacks) => {
      callbacksRef = callbacks;
      callbacks.onEvent(runEvent(1, 'RUN_STARTED'));
      return {
        stopReceiving,
        completion: new Promise((resolve) => { resolveCompletion = resolve; }),
      };
    });
    mockCancelRun.mockResolvedValue({
      schemaVersion: '1.0',
      runId: RUN_ID,
      state: 'RUNNING',
      result: 'REQUESTED',
      nextEventSequence: 2,
    });
    mockReplayRun.mockResolvedValue({
      schemaVersion: '1.0',
      runId: RUN_ID,
      state: 'CANCELLED',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 3,
      events: [],
      terminalOutcome: null,
      failureCode: null,
    });

    const screen = render(<AIChatScreen />);
    await act(async () => { fireEvent.press(screen.getByTestId('restaurant-agent-start')); });
    await waitFor(() => expect(screen.getByTestId('restaurant-agent-cancel')).toBeTruthy());

    await act(async () => {
      fireEvent.press(screen.getByTestId('restaurant-agent-cancel'));
      fireEvent.press(screen.getByTestId('restaurant-agent-cancel'));
    });
    expect(mockCancelRun).toHaveBeenCalledTimes(1);
    expect(mockCancelRun).toHaveBeenCalledWith('REST-1', RUN_ID);
    expect(stopReceiving).not.toHaveBeenCalled();

    await act(async () => {
      callbacksRef.onEvent(runEvent(2, 'CANCEL_REQUESTED'));
      callbacksRef.onEvent(runEvent(3, 'RUN_CANCELLED'));
      resolveCompletion({ runId: RUN_ID, lastSequence: 3, stoppedReceiving: false });
    });
    await waitFor(() => expect(mockReplayRun).toHaveBeenCalledWith('REST-1', RUN_ID, 3));
  });

  it('renders clarification, expandable bounded evidence, and read-only proposals', async () => {
    mockRunActive = true;
    const evidenceEvent = {
      ...runEvent(2, 'EVIDENCE_RECORDED'),
      toolName: 'restaurant.store_margin_summary',
      payload: {
        evidenceId: 'ev-store-1',
        evidenceStatus: 'AVAILABLE',
        factReferences: [{
          factId: 'fact-store-1',
          metric: 'store_gross_margin',
          value: '18.5',
          unit: 'percent',
          dimensions: { storeId: 'S-1' },
          provenanceRefs: ['src-1'],
        }],
        provenance: [{
          refId: 'src-1',
          sourceType: 'SQL_QUERY',
          asset: 'restaurant_store_margin_summary',
          queryId: 'query-store-margin',
          sourceVersion: 'v1',
        }],
        warningCodes: ['DRILLDOWN_REFERENCE_TRUNCATED'],
        drilldownTruncated: true,
      },
    };
    const clarificationEvent = {
      ...runEvent(3, 'CLARIFICATION'),
      payload: { clarificationCode: 'PROVIDE_DISH_AND_STORE_COST_EVIDENCE' },
    };
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
      state: 'PARTIAL',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 4,
      events: [evidenceEvent, clarificationEvent, runEvent(4, 'RUN_COMPLETED')],
      terminalOutcome: {
        status: 'PARTIAL',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        claims: [{
          statementCode: 'STORE_MARGIN_DECLINED',
          metric: 'store_gross_margin',
          value: '18.5',
          unit: 'percent',
          evidenceId: 'ev-store-1',
          factId: 'fact-store-1',
        }],
        blockers: ['DISH_COST_EVIDENCE_MISSING'],
        observations: [],
        actionProposals: [{
          proposalCode: 'proposal-1',
          actionCode: 'REVIEW_DISH_COST_DATA',
          rationaleCodes: ['DISH_COST_EVIDENCE_MISSING'],
          evidenceReferences: [{ evidenceId: 'ev-store-1', factId: 'fact-store-1' }],
          executionMode: 'READ_ONLY_PROPOSAL',
        }],
        attributionSupported: false,
      },
      failureCode: null,
    });

    const screen = render(<AIChatScreen />);
    await act(async () => { fireEvent.press(screen.getByTestId('restaurant-agent-start')); });
    await waitFor(() => expect(screen.getByTestId('restaurant-agent-clarification')).toBeTruthy());
    expect(screen.getByTestId('restaurant-agent-proposal-proposal-1')).toBeTruthy();
    expect(screen.getByTestId('restaurant-agent-action-readonly')).toBeTruthy();

    fireEvent.press(screen.getByTestId('restaurant-agent-claim-ref-ev-store-1-fact-store-1'));
    expect(screen.getAllByText(/store_gross_margin 18\.5percent/)).toHaveLength(2);
    expect(screen.getByText(/restaurant_store_margin_summary/)).toBeTruthy();
    expect(screen.getByTestId('restaurant-agent-evidence-truncated')).toBeTruthy();
  });

  it('restores a durable running checkpoint without starting a duplicate run', async () => {
    mockRunActive = true;
    mockLoadCheckpoint.mockResolvedValue({ runId: RUN_ID, lastSequence: 2 });
    mockReplayRun.mockResolvedValue({
      schemaVersion: '1.0',
      runId: RUN_ID,
      state: 'RUNNING',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 3,
      events: [runEvent(3, 'EVIDENCE_GAP')],
      terminalOutcome: null,
      failureCode: null,
    });

    const screen = render(<AIChatScreen />);

    await waitFor(() => expect(mockReplayRun).toHaveBeenCalledWith('REST-1', RUN_ID, 0));
    expect(mockLoadCheckpoint).toHaveBeenCalledWith('REST-1', '1');
    expect(mockStartRun).not.toHaveBeenCalled();
    expect(screen.getByTestId('restaurant-agent-cancel')).toBeTruthy();
    expect(screen.getByTestId('restaurant-agent-resume')).toBeTruthy();
  });

  it('stops and clears the old run before loading a same-factory new owner', async () => {
    mockRunActive = true;
    let callbacksRef: any;
    const stopReceiving = jest.fn();
    mockStartRun.mockImplementation(async (_factoryId, _body, callbacks) => {
      callbacksRef = callbacks;
      callbacks.onEvent(runEvent(1, 'RUN_STARTED'));
      return { stopReceiving, completion: new Promise(() => undefined) };
    });

    const screen = render(<AIChatScreen />);
    await act(async () => { fireEvent.press(screen.getByTestId('restaurant-agent-start')); });
    await waitFor(() => expect(screen.getByText(`Run ${RUN_ID}`)).toBeTruthy());
    expect(callbacksRef).toBeTruthy();

    mockUser = restaurantUser('REST-1', 2);
    screen.rerender(<AIChatScreen />);

    await waitFor(() => expect(stopReceiving).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mockLoadCheckpoint).toHaveBeenCalledWith('REST-1', '2'));
    expect(screen.queryByText(`Run ${RUN_ID}`)).toBeNull();
    expect(screen.queryByTestId('restaurant-agent-cancel')).toBeNull();
    expect(screen.getByTestId('restaurant-agent-start')).toBeTruthy();
  });

  it('clears the old factory checkpoint view before loading the new factory', async () => {
    mockRunActive = true;
    mockLoadCheckpoint.mockImplementation(async (factoryId: string) => (
      factoryId === 'REST-1' ? { runId: RUN_ID, lastSequence: 2 } : null
    ));
    mockReplayRun.mockResolvedValue({
      schemaVersion: '1.0', runId: RUN_ID, state: 'RUNNING',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION', nextEventSequence: 2,
      events: [runEvent(2, 'EVIDENCE_GAP')], terminalOutcome: null, failureCode: null,
    });
    const screen = render(<AIChatScreen />);
    await waitFor(() => expect(screen.getByText(`Run ${RUN_ID}`)).toBeTruthy());

    mockUser = restaurantUser('REST-2', 1);
    screen.rerender(<AIChatScreen />);

    await waitFor(() => expect(mockLoadCheckpoint).toHaveBeenCalledWith('REST-2', '1'));
    expect(screen.queryByText(`Run ${RUN_ID}`)).toBeNull();
    expect(screen.getByTestId('restaurant-agent-start')).toBeTruthy();
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
