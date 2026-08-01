import { useState, useEffect, useCallback } from 'react';
import { Alert } from 'react-native';
import { workReportingApiClient } from '../services/api/workReportingApiClient';
import { useDraftReportStore } from '../store/draftReportStore';
import { useFieldVisibilityStore } from '../store/fieldVisibilityStore';
import { useAuthStore } from '../store/authStore';
import { getErrorMsg, isNetworkError } from '../utils/errorHandler';
import type {
  ReportType,
  FormSchema,
  WorkReportSubmitRequest,
  WorkReportResponse,
} from '../types/workReporting';

/**
 * 报工工作流 hook
 * 管理表单schema加载、提交、离线草稿
 */
export function useReportWorkflow(reportType: ReportType) {
  const [schema, setSchema] = useState<FormSchema | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  const { addDraft } = useDraftReportStore();
  const { isFieldVisible } = useFieldVisibilityStore();
  const user = useAuthStore((s) => s.user);

  const entityType = reportType === 'PROGRESS'
    ? 'PRODUCTION_PROGRESS_REPORT'
    : 'PRODUCTION_HOURS_REPORT';

  useEffect(() => {
    loadSchema();
  }, [reportType]);

  const loadSchema = useCallback(async () => {
    setLoading(true);
    try {
      const response = await workReportingApiClient.getSchema(entityType);
      if (response.success && response.data?.schemaJson) {
        const parsed: FormSchema = JSON.parse(response.data.schemaJson);
        setSchema(parsed);
      }
    } catch (error) {
      // Use default schema if server fails
      console.warn('Failed to load schema, using defaults:', error);
    } finally {
      setLoading(false);
    }
  }, [entityType]);

  const submitReport = useCallback(async (data: WorkReportSubmitRequest): Promise<WorkReportResponse | null> => {
    if (!user?.id) {
      Alert.alert('错误', '请先登录');
      return null;
    }

    setSubmitting(true);
    try {
      const response = await workReportingApiClient.submitReport(data, user.id);
      if (response.success) {
        return response.data;
      } else {
        Alert.alert('提交失败', response.message);
        return null;
      }
    } catch (error: unknown) {
      if (isNetworkError(error)) {
        saveDraft(data);
        Alert.alert('提交失败', `${getErrorMsg(error)}\n已保存为草稿`);
      } else {
        Alert.alert('提交失败', getErrorMsg(error));
      }
      return null;
    } finally {
      setSubmitting(false);
    }
  }, [user]);

  const saveDraft = useCallback((data: WorkReportSubmitRequest) => {
    addDraft({
      batchId: data.batchId,
      batchNumber: undefined,
      productName: data.productName || data.processCategory,
      outputQuantity: data.outputQuantity || 0,
      goodQuantity: data.goodQuantity || 0,
      defectQuantity: data.defectQuantity || 0,
      notes: `[${reportType}] ${data.reportDate}`,
      factoryId: user?.factoryId || '',
    });
  }, [reportType, user, addDraft]);

  return {
    schema,
    loading,
    submitting,
    submitReport,
    saveDraft,
    isFieldVisible: (fieldKey: string) => isFieldVisible(entityType, fieldKey),
    reloadSchema: loadSchema,
  };
}
