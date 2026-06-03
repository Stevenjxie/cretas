/**
 * G4 — 餐饮 AI 经营体检报告 API client.
 *
 * Wraps GET /api/smartbi/restaurant/{factoryId}/health-check-report.
 *
 * Unlike the generic `pythonFetch` (which throws a generic
 * "Python service error: 403" on non-2xx and discards the JSON body), this
 * client preserves the backend's `{ success, message }` payload on error so
 * the view can surface the honest backend message in a sticky toast
 * (fool-proof-design Rule 4-位一体).
 */
import {
  PYTHON_SMARTBI_URL,
  PYTHON_LLM_TIMEOUT_MS,
  getPythonAuthHeaders,
  transformKeys,
} from './common';

export interface RxAction {
  id: string;
  title: string;
  description: string;
  owner: string;
  timeframe: string;
  priority: 'P0' | 'P1' | 'P2';
  effort: 'low' | 'medium' | 'high';
  expectedImpact: string;
}

export interface DiagnosisItem {
  metricKey: string;
  metricNameZh: string;
  actualValue: number;
  benchmarkMedian: number | null;
  benchmarkRange: [number, number] | null;
  status: string;
  severity: 'critical' | 'warning' | 'info';
  deltaPp: number;
  deltaPct: number;
  descriptionZh: string;
  suggestionZh: string[];
  rxActions: RxAction[];
  subSectorNotes: string[];
  playbookId: string | null;
  estimated?: boolean;
}

export interface HealthCheckSummary {
  criticalCount: number;
  warningCount: number;
  infoCount: number;
  checkedCount: number;
  coverageNote: string;
  coverage: Record<string, string>;
}

export interface HealthCheckReport {
  reportMeta: {
    factoryId: string;
    period: string;
    snapshotAt: string;
    subSector: string;
    uploadId: number | null;
    cacheHit: boolean;
  };
  summary: HealthCheckSummary;
  diagnoses: DiagnosisItem[];
}

export interface HealthCheckResponse {
  success: boolean;
  data: HealthCheckReport | null;
  message: string;
}

export interface HealthCheckParams {
  month?: string;
  subSector?: string;
  tableCount?: number;
}

/**
 * Fetch the health-check report. Resolves with the `{success,data,message}`
 * envelope for BOTH success and business-error (200 success:false) cases.
 * On HTTP error (403/503/500) it still resolves with the parsed body when the
 * backend returned JSON, so the caller can show the real message; if the body
 * is unparseable it throws.
 */
export async function fetchHealthCheckReport(
  factoryId: string,
  params: HealthCheckParams = {},
): Promise<HealthCheckResponse> {
  const qs = new URLSearchParams();
  if (params.month) qs.set('month', params.month);
  if (params.subSector) qs.set('sub_sector', params.subSector);
  if (params.tableCount != null) qs.set('table_count', String(params.tableCount));
  const query = qs.toString();
  const path = `/api/smartbi/restaurant/${encodeURIComponent(factoryId)}/health-check-report${
    query ? `?${query}` : ''
  }`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), PYTHON_LLM_TIMEOUT_MS);
  try {
    const response = await fetch(`${PYTHON_SMARTBI_URL}${path}`, {
      method: 'GET',
      credentials: 'include',
      signal: controller.signal,
      headers: { ...getPythonAuthHeaders() },
    });

    let body: any = null;
    try {
      body = await response.json();
    } catch {
      body = null;
    }

    if (body == null) {
      throw new Error(`体检报告请求失败: ${response.status} ${response.statusText}`);
    }

    // snake_case → camelCase (mirrors pythonFetch transform).
    return transformKeys(body) as HealthCheckResponse;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('体检报告请求超时, 请稍后重试');
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}
