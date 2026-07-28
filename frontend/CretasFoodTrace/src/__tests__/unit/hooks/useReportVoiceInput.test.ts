// @ts-nocheck
/**
 * useReportVoiceInput hook 单元测试
 * 测试语音报工输入：录音权限、开始/停止录音、AI解析、取消
 */

import { renderHook, act } from '@testing-library/react-native';
import { Alert } from 'react-native';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

const mockSpeechRecognitionService = {
  requestPermissions: jest.fn().mockResolvedValue(true),
  startListening: jest.fn().mockResolvedValue(undefined),
  stopListening: jest.fn().mockResolvedValue({ text: '切割 产量100 良品95 不良5' }),
  cancel: jest.fn().mockResolvedValue(undefined),
};
jest.mock('../../../services/voice/SpeechRecognitionService', () => ({
  speechRecognitionService: mockSpeechRecognitionService,
}));

const mockNetInfoFetch = jest.fn().mockResolvedValue({ isConnected: true });
jest.mock('@react-native-community/netinfo', () => ({
  fetch: (...args: any[]) => mockNetInfoFetch(...args),
}));

import { useReportVoiceInput } from '../../../hooks/useReportVoiceInput';

let mock: ReturnType<typeof createApiMock>;

beforeEach(() => {
  jest.clearAllMocks();
  mock = createApiMock();
  mockSpeechRecognitionService.requestPermissions.mockResolvedValue(true);
  mockSpeechRecognitionService.startListening.mockResolvedValue(undefined);
  mockSpeechRecognitionService.stopListening.mockResolvedValue({ text: '切割 产量100 良品95 不良5' });
  mockSpeechRecognitionService.cancel.mockResolvedValue(undefined);
  mockNetInfoFetch.mockResolvedValue({ isConnected: true });
});

afterEach(() => {
  resetApiMock(mock);
});

describe('useReportVoiceInput', () => {
  // ========== Initial State ==========
  it('initial status is idle', () => {
    const { result } = renderHook(() => useReportVoiceInput());
    expect(result.current.status).toBe('idle');
  });

  it('returns startRecording, stopRecording, cancel functions', () => {
    const { result } = renderHook(() => useReportVoiceInput());
    expect(typeof result.current.startRecording).toBe('function');
    expect(typeof result.current.stopRecording).toBe('function');
    expect(typeof result.current.cancel).toBe('function');
  });

  // ========== startRecording ==========
  it('startRecording checks permissions and calls startListening, status becomes recording', async () => {
    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });
    expect(mockNetInfoFetch).toHaveBeenCalled();
    expect(mockSpeechRecognitionService.requestPermissions).toHaveBeenCalled();
    expect(mockSpeechRecognitionService.startListening).toHaveBeenCalled();
    expect(result.current.status).toBe('recording');
  });

  it('startRecording with no network shows alert and stays idle', async () => {
    mockNetInfoFetch.mockResolvedValue({ isConnected: false });
    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });
    expect(Alert.alert).toHaveBeenCalledWith('提示', '语音功能需要网络连接');
    expect(result.current.status).toBe('idle');
    expect(mockSpeechRecognitionService.requestPermissions).not.toHaveBeenCalled();
  });

  it('startRecording with no permission stays idle', async () => {
    mockSpeechRecognitionService.requestPermissions.mockResolvedValue(false);
    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });
    expect(Alert.alert).toHaveBeenCalledWith('提示', '需要麦克风权限才能使用语音输入');
    expect(result.current.status).toBe('idle');
    expect(mockSpeechRecognitionService.startListening).not.toHaveBeenCalled();
  });

  it('startRecording sets error status when startListening throws', async () => {
    mockSpeechRecognitionService.startListening.mockRejectedValue(new Error('mic error'));
    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });
    expect(result.current.status).toBe('error');
  });

  // ========== stopRecording ==========
  it('stopRecording calls stopListening, sends to AI, parses JSON and returns fields', async () => {
    const aiReply = '{"processCategory":"切割","outputQuantity":"100","goodQuantity":"95","defectQuantity":"5"}';
    mock.onPost('/api/mobile/ai/chat').reply(200, {
      data: { content: aiReply },
    });

    const { result } = renderHook(() => useReportVoiceInput());

    // Start recording first
    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = null;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(mockSpeechRecognitionService.stopListening).toHaveBeenCalled();

    // 锁请求形状: 后端 GenericChatRequest 只认 messages:[{role,content}]。
    // 这条断言是 2026-07-29 补的 —— 之前测试只 mock 响应, 请求发的是
    // {message, systemPrompt} (后端根本没有这两个字段), 于是线上必挂而测试全绿。
    const sentBody = JSON.parse(mock.history.post[0].data);
    expect(Array.isArray(sentBody.messages)).toBe(true);
    expect(sentBody.messages.map((m: any) => m.role)).toEqual(['system', 'user']);
    expect(sentBody.messages[1].content).toBe('切割 产量100 良品95 不良5');
    expect(sentBody).not.toHaveProperty('message');
    expect(sentBody).not.toHaveProperty('systemPrompt');

    expect(fields).not.toBeNull();
    expect(fields.processCategory).toBe('切割');
    expect(fields.outputQuantity).toBe('100');
    expect(fields.goodQuantity).toBe('95');
    expect(fields.defectQuantity).toBe('5');
  });

  it('stopRecording with empty text returns null and shows alert', async () => {
    mockSpeechRecognitionService.stopListening.mockResolvedValue({ text: '' });
    const { result } = renderHook(() => useReportVoiceInput());

    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = undefined;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields).toBeNull();
    expect(Alert.alert).toHaveBeenCalledWith('提示', '未识别到语音内容，请重试');
    expect(result.current.status).toBe('idle');
  });

  it('stopRecording with null text result returns null', async () => {
    mockSpeechRecognitionService.stopListening.mockResolvedValue(null);
    const { result } = renderHook(() => useReportVoiceInput());

    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = undefined;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields).toBeNull();
  });

  it('stopRecording returns null and sets error when AI call fails', async () => {
    mock.onPost('/api/mobile/ai/chat').networkError();
    const { result } = renderHook(() => useReportVoiceInput());

    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = undefined;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields).toBeNull();
    expect(result.current.status).toBe('error');
  });

  it('stopRecording returns null when AI reply has no valid JSON', async () => {
    mock.onPost('/api/mobile/ai/chat').reply(200, {
      data: { content: '无法识别内容' },
    });

    const { result } = renderHook(() => useReportVoiceInput());

    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = undefined;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields).toBeNull();
    expect(Alert.alert).toHaveBeenCalledWith('提示', '语音解析失败，请重试或手动输入');
  });

  it('stopRecording sets status to done then back to idle', async () => {
    jest.useFakeTimers();
    const aiReply = '{"processCategory":"包装","outputQuantity":"200"}';
    mock.onPost('/api/mobile/ai/chat').reply(200, {
      data: { content: aiReply },
    });

    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });
    await act(async () => {
      await result.current.stopRecording();
    });

    expect(result.current.status).toBe('done');

    act(() => {
      jest.advanceTimersByTime(2000);
    });

    expect(result.current.status).toBe('idle');
    jest.useRealTimers();
  });

  it('stopRecording only extracts fields that are present in parsed JSON', async () => {
    const aiReply = '{"processCategory":"分拣","outputQuantity":"50"}';
    mock.onPost('/api/mobile/ai/chat').reply(200, {
      data: { content: aiReply },
    });

    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });

    let fields: any = null;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields.processCategory).toBe('分拣');
    expect(fields.outputQuantity).toBe('50');
    expect(fields.goodQuantity).toBeUndefined();
    expect(fields.defectQuantity).toBeUndefined();
    expect(fields.productName).toBeUndefined();
  });

  // ========== cancel ==========
  it('cancel sets status to idle and calls service.cancel', () => {
    const { result } = renderHook(() => useReportVoiceInput());
    act(() => {
      result.current.cancel();
    });
    expect(mockSpeechRecognitionService.cancel).toHaveBeenCalled();
    expect(result.current.status).toBe('idle');
  });

  it('stopRecording returns null after cancel was called', async () => {
    mock.onPost('/api/mobile/ai/chat').reply(200, {
      data: { content: '{"processCategory":"切割"}' },
    });

    const { result } = renderHook(() => useReportVoiceInput());
    await act(async () => {
      await result.current.startRecording();
    });

    act(() => {
      result.current.cancel();
    });

    let fields: any = undefined;
    await act(async () => {
      fields = await result.current.stopRecording();
    });

    expect(fields).toBeNull();
  });
});
