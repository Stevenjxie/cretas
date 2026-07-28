import { useState, useRef, useCallback } from 'react';
import { Alert } from 'react-native';
import { speechRecognitionService } from '../services/voice/SpeechRecognitionService';
import { apiClient } from '../services/api/apiClient';
import NetInfo from '@react-native-community/netinfo';

type VoiceStatus = 'idle' | 'recording' | 'processing' | 'done' | 'error';

interface VoiceReportFields {
  processCategory?: string;
  productName?: string;
  outputQuantity?: string;
  goodQuantity?: string;
  defectQuantity?: string;
  operationVolume?: string;
}

interface UseReportVoiceInputReturn {
  status: VoiceStatus;
  startRecording: () => Promise<void>;
  stopRecording: () => Promise<VoiceReportFields | null>;
  cancel: () => void;
}

const SYSTEM_PROMPT = `你是一个工厂报工助手。从用户的语音中提取以下报工字段，返回JSON格式：
{
  "processCategory": "工序名称（如：切割、包装、分拣）",
  "productName": "产品名称",
  "outputQuantity": "产品数量（数字）",
  "goodQuantity": "良品数（数字）",
  "defectQuantity": "不良品数（数字）",
  "operationVolume": "操作量（数字）"
}
只返回JSON，不要解释。未提到的字段设为null。`;

export function useReportVoiceInput(): UseReportVoiceInputReturn {
  const [status, setStatus] = useState<VoiceStatus>('idle');
  const cancelledRef = useRef(false);

  const startRecording = useCallback(async () => {
    const netState = await NetInfo.fetch();
    if (!netState.isConnected) {
      Alert.alert('提示', '语音功能需要网络连接');
      return;
    }

    cancelledRef.current = false;
    try {
      const hasPermission = await speechRecognitionService.requestPermissions();
      if (!hasPermission) {
        Alert.alert('提示', '需要麦克风权限才能使用语音输入');
        return;
      }
      setStatus('recording');
      await speechRecognitionService.startListening();
    } catch (error) {
      console.warn('Voice recording start failed:', error);
      setStatus('error');
    }
  }, []);

  const stopRecording = useCallback(async (): Promise<VoiceReportFields | null> => {
    if (cancelledRef.current) return null;

    try {
      setStatus('processing');
      const result = await speechRecognitionService.stopListening();

      if (!result?.text?.trim()) {
        setStatus('idle');
        Alert.alert('提示', '未识别到语音内容，请重试');
        return null;
      }

      // Send recognized text to AI for field extraction.
      // 2026-07-29 修复: 这里原本发 {message, systemPrompt, temperature} 并读
      // data.reply —— 后端 GenericChatRequest 只认 messages:[{role,content}],
      // GenericChatResponse 只有 content。请求端 messages 为 null 会让
      // convertMessages 抛「消息列表不能为空」, 响应端也取不到值 ——
      // 也就是说这条语音报工链路**从来没通过**, 用户按住说完必然走到「解析失败」。
      // 单测把 {data:{reply}} 这个后端从未返回过的形状 mock 死了, 所以一直是绿的。
      const aiResponse = await apiClient.post('/api/mobile/ai/chat', {
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: result.text },
        ],
        temperature: 0.1,
      });

      if (cancelledRef.current) return null;

      const aiData = aiResponse as Record<string, unknown> | undefined;
      const dataObj = (aiData?.data ?? {}) as Record<string, unknown>;
      const responseText = String(dataObj.content ?? '');
      // Extract JSON from response
      const jsonMatch = responseText.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        const parsed = JSON.parse(jsonMatch[0]);
        const fields: VoiceReportFields = {};
        if (parsed.processCategory) fields.processCategory = String(parsed.processCategory);
        if (parsed.productName) fields.productName = String(parsed.productName);
        if (parsed.outputQuantity != null) fields.outputQuantity = String(parsed.outputQuantity);
        if (parsed.goodQuantity != null) fields.goodQuantity = String(parsed.goodQuantity);
        if (parsed.defectQuantity != null) fields.defectQuantity = String(parsed.defectQuantity);
        if (parsed.operationVolume != null) fields.operationVolume = String(parsed.operationVolume);
        setStatus('done');
        setTimeout(() => setStatus('idle'), 2000);
        return fields;
      }

      setStatus('idle');
      Alert.alert('提示', '语音解析失败，请重试或手动输入');
      return null;
    } catch (error) {
      console.warn('Voice processing failed:', error);
      setStatus('error');
      setTimeout(() => setStatus('idle'), 2000);
      return null;
    }
  }, []);

  const cancel = useCallback(() => {
    cancelledRef.current = true;
    speechRecognitionService.cancel().catch(() => {});
    setStatus('idle');
  }, []);

  return { status, startRecording, stopRecording, cancel };
}
