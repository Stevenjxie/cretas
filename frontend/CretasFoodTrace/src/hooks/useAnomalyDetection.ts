import { useState, useEffect, useRef } from 'react';
// legacy 报工栈退役第 3 步：改指向 process-work-reporting。
// 底账与顺序见 docs/decisions/2026-08-17-legacy报工栈退役.md。
import { processTaskApiClient } from '../services/api/processTaskApiClient';
import type { HistoricalAverage } from '../services/api/processTaskApiClient';

type HistoricalStats = HistoricalAverage;

interface AnomalyWarning {
  field: 'output' | 'defect';
  message: string;
  severity: 'warning' | 'info';
}

export function useAnomalyDetection(factoryId: string | undefined) {
  const [stats, setStats] = useState<HistoricalStats | null>(null);
  const [warnings, setWarnings] = useState<AnomalyWarning[]>([]);
  const [loading, setLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchStats = async (processCategory: string) => {
    if (!factoryId || !processCategory.trim()) {
      setStats(null);
      setWarnings([]);
      return;
    }

    setLoading(true);
    try {
      const res = await processTaskApiClient.getHistoricalAverage(
        processCategory,
        30,
        factoryId
      );
      if (res?.success && res.data) {
        setStats(res.data);
      } else {
        setStats(null);
      }
    } catch {
      setStats(null);
    } finally {
      setLoading(false);
    }
  };

  const onProcessCategoryChange = (processCategory: string) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      fetchStats(processCategory);
    }, 500);
  };

  const checkAnomaly = (outputQuantity: string, defectQuantity: string) => {
    if (!stats || stats.sampleCount < 5) {
      setWarnings([]);
      return;
    }

    const newWarnings: AnomalyWarning[] = [];
    const output = parseFloat(outputQuantity);
    const defect = parseFloat(defectQuantity);

    if (!isNaN(output) && output > 0) {
      const lowerBound = stats.avgOutput - 2 * stats.stddevOutput;
      const upperBound = stats.avgOutput + 2 * stats.stddevOutput;
      if (output > upperBound) {
        newWarnings.push({
          field: 'output',
          message: `产量(${output})远高于近30天平均值(${Math.round(stats.avgOutput)})，请确认`,
          severity: 'warning',
        });
      } else if (output < lowerBound && lowerBound > 0) {
        newWarnings.push({
          field: 'output',
          message: `产量(${output})远低于近30天平均值(${Math.round(stats.avgOutput)})，请确认`,
          severity: 'warning',
        });
      }
    }

    if (!isNaN(defect) && defect > 0 && stats.avgDefect > 0) {
      if (defect > stats.avgDefect * 3) {
        newWarnings.push({
          field: 'defect',
          message: `不良品数(${defect})远高于平均值(${Math.round(stats.avgDefect)})，请确认`,
          severity: 'warning',
        });
      }
    }

    setWarnings(newWarnings);
  };

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  return { stats, warnings, loading, onProcessCategoryChange, checkAnomaly };
}
