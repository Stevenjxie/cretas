import { get } from './request';
import type { ApiResponse } from '@/types/api';

/** 可点开的告警明细 —— businessEntityId 就是批次号/单据号。 */
export interface AiValueAlertDetail {
  businessEntityType: string | null;
  businessEntityId: string | null;
  severity: string | null;
  status: string | null;
  message: string | null;
}

/**
 * AI 价值汇总。
 *
 * 注意两个刻意为 null / 缺席的东西，**不要在前端补上**：
 * - `costInYuan` 恒为 null：系统没有 token 单价配置，编一个费率会得到看起来
 *   精确的假数字。要显示就显示 `costUnavailableReason`。
 * - 没有「省了多少钱」字段：缺反事实与因果，改用告警三段计数替代。
 */
export interface AiValueSummary {
  windowDays: number;
  aiCalls: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  alertsTotal: number;
  alertsByStatus: Record<string, number>;
  alertDetails: AiValueAlertDetail[];
  costInYuan: null;
  costUnavailableReason: string;
}

/**
 * 取 AI 价值汇总。
 *
 * @param days 统计最近多少天；不传取默认 30，超出 1..365 由后端夹紧
 */
export function fetchAiValueSummary(
  factoryId: string,
  days?: number
): Promise<ApiResponse<AiValueSummary>> {
  return get(`/api/mobile/${factoryId}/ai/value-summary`, days == null ? {} : { days });
}
