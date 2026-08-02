/**
 * useDashboardData - Fetches and manages all dashboard data
 * (overview stats, alerts, work reports, AI insights)
 */
import { useState, useCallback, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import {
  dashboardAPI,
  DashboardOverviewData,
  AlertsDashboardData,
  RestaurantDashboardSummary,
} from '../../../../services/api/dashboardApiClient';
import { workReportingApiClient } from '../../../../services/api/workReportingApiClient';
import { aiApiClient } from '../../../../services/api/aiApiClient';
import type { AIInsight } from '../types';

export interface WorkReportSummary {
  pendingApprovalCount?: number;
  todayOutputTotal?: number;
  todayYieldRate?: number;
}

export interface DashboardDataResult {
  loading: boolean;
  refreshing: boolean;
  error: string | null;
  overviewData: DashboardOverviewData | null;
  alertsData: AlertsDashboardData | null;
  restaurantSummary: RestaurantDashboardSummary | null;
  workReportSummary: WorkReportSummary;
  aiInsight: AIInsight;
  onRefresh: () => void;
  loadDashboardData: () => Promise<void>;
}

export function useDashboardData(isRestaurantMode: boolean): DashboardDataResult {
  const { t } = useTranslation('home');

  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [overviewData, setOverviewData] = useState<DashboardOverviewData | null>(null);
  const [alertsData, setAlertsData] = useState<AlertsDashboardData | null>(null);
  const [restaurantSummary, setRestaurantSummary] = useState<RestaurantDashboardSummary | null>(null);
  const [workReportSummary, setWorkReportSummary] = useState<WorkReportSummary>({});
  const [aiInsight, setAIInsight] = useState<AIInsight>({
    status: 'loading',
    message: isRestaurantMode ? t('ai.restaurantLoading') : t('ai.analyzing'),
    metrics: { qualityRate: 0, unitCost: 0, avgCycle: 0 },
  });

  const loadDashboardData = useCallback(async () => {
    try {
      setError(null);

      const fetchPromises: Promise<{ success: boolean; data: unknown }>[] = isRestaurantMode
        ? [
            dashboardAPI.getRestaurantDashboardSummary(),
            dashboardAPI.getAlertsDashboard('week'),
          ]
        : [
            dashboardAPI.getDashboardOverview('today'),
            dashboardAPI.getAlertsDashboard('week'),
            workReportingApiClient.getSummary(),
          ];
      const results = await Promise.allSettled(fetchPromises);
      const overviewResult = results[0] as PromiseSettledResult<{ success: boolean; data: unknown }>;
      const alertsResult = results[1] as PromiseSettledResult<{ success: boolean; data: unknown }>;
      const workReportResult = results[2] as PromiseSettledResult<{ success: boolean; data: unknown }> | undefined;

      // Process overview / restaurant summary
      if (overviewResult.status === 'fulfilled' && overviewResult.value.success && overviewResult.value.data) {
        if (isRestaurantMode) {
          setRestaurantSummary(overviewResult.value.data as RestaurantDashboardSummary);
        } else {
          setOverviewData(overviewResult.value.data as DashboardOverviewData);
        }
      } else if (overviewResult.status === 'rejected') {
        console.warn('Dashboard overview failed:', overviewResult.reason);
      }

      // Process alerts
      if (alertsResult.status === 'fulfilled' && alertsResult.value.success && alertsResult.value.data) {
        setAlertsData(alertsResult.value.data as AlertsDashboardData);
      } else if (alertsResult.status === 'rejected') {
        console.warn('Dashboard alerts failed:', alertsResult.reason);
      }

      // Process work report data (factory mode only)
      if (
        !isRestaurantMode &&
        workReportResult?.status === 'fulfilled' &&
        workReportResult.value.success &&
        workReportResult.value.data
      ) {
        const d = workReportResult.value.data as Record<string, unknown>;
        setWorkReportSummary({
          pendingApprovalCount: Number(d.pendingApprovalCount ?? 0),
          todayOutputTotal: Number(d.todayOutputTotal ?? 0),
          todayYieldRate: Number(d.todayYieldRate ?? 0),
        });
      }

      // AI insight from real backend data + AI report summary
      const resolvedOverview =
        !isRestaurantMode && overviewResult.status === 'fulfilled'
          ? (overviewResult.value.data as Record<string, unknown>)
          : null;
      const resolvedRestaurantSummary =
        isRestaurantMode && overviewResult.status === 'fulfilled' && overviewResult.value.success
          ? (overviewResult.value.data as RestaurantDashboardSummary)
          : null;

      if (resolvedOverview) {
        const kpi = resolvedOverview.kpi as Record<string, unknown> | undefined;
        const toNum = (v: unknown, fallback: number = 0): number => {
          const n = Number(v);
          return isNaN(n) ? fallback : n;
        };
        const qualityRate = toNum(kpi?.qualityPassRate, 98.5);
        const efficiency = toNum(kpi?.productionEfficiency, 92);
        const unitCost = toNum(kpi?.unitCost);
        const avgCycle = toNum(kpi?.avgCycleHours);

        // Try fetching latest AI report summary
        let insightMessage: string = t('ai.normalProduction');
        let useLocalRule = true;
        try {
          const reportsRes = await aiApiClient.getReports({ reportType: 'custom' });
          if (reportsRes?.reports && reportsRes.reports.length > 0) {
            const latestReport = reportsRes.reports[0];
            const title = latestReport?.title;
            if (title && title.length > 10) {
              insightMessage = title;
              useLocalRule = false;
            }
          }
        } catch (aiError) {
          console.log('AI report summary fetch failed, using local rules', aiError);
        }

        // Fallback local rules
        if (useLocalRule) {
          if (qualityRate < 95) {
            insightMessage = t('ai.lowQualityRate');
          } else if (efficiency < 85) {
            insightMessage = t('ai.lowEfficiency');
          } else if (qualityRate >= 98 && efficiency >= 95) {
            insightMessage = t('ai.excellentStatus');
          }
        }

        setAIInsight({
          status: 'success',
          message: insightMessage,
          metrics: { qualityRate, unitCost, avgCycle },
        });
      } else if (resolvedRestaurantSummary) {
        setAIInsight({
          status: 'success',
          message: t('ai.restaurantSnapshot', {
            requisitions: Number(resolvedRestaurantSummary.todayRequisitionCount ?? 0),
            approvals: Number(resolvedRestaurantSummary.pendingApprovalCount ?? 0),
            wastage: Number(resolvedRestaurantSummary.thisMonthWastageCost ?? 0).toFixed(2),
          }),
          metrics: { qualityRate: 0, unitCost: 0, avgCycle: 0 },
        });
      } else {
        if (!isRestaurantMode || overviewResult.status !== 'fulfilled') {
          setError(t('error.loadFailed'));
        }
        setAIInsight({
          status: isRestaurantMode ? 'success' : 'error',
          message: isRestaurantMode ? t('ai.restaurantNoData') : t('ai.noData'),
          metrics: { qualityRate: 0, unitCost: 0, avgCycle: 0 },
        });
      }
    } catch (err) {
      console.error('Dashboard data load failed:', err);
      setError(t('error.loadFailed'));
      setAIInsight({
        status: 'error',
        message: t('ai.noData'),
        metrics: { qualityRate: 0, unitCost: 0, avgCycle: 0 },
      });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [isRestaurantMode, t]);

  // Initial load
  useEffect(() => {
    loadDashboardData();
  }, [loadDashboardData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadDashboardData();
  }, [loadDashboardData]);

  return {
    loading,
    refreshing,
    error,
    overviewData,
    alertsData,
    restaurantSummary,
    workReportSummary,
    aiInsight,
    onRefresh,
    loadDashboardData,
  };
}
