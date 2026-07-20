interface InlineRestaurantChart {
  chartType?: string;
  type?: string;
  title?: string;
  option?: Record<string, unknown>;
  xAxis?: Record<string, unknown> | unknown[];
  yAxis?: Record<string, unknown> | unknown[];
  series?: Array<Record<string, unknown>>;
  data?: unknown;
  [key: string]: unknown;
}

/** Convert an inline restaurant chart into an ECharts option without dropping fields. */
export function buildJavaIntentChartOption(chart: InlineRestaurantChart): Record<string, unknown> {
  if (chart.option && typeof chart.option === 'object') {
    return { ...chart.option };
  }

  const chartType = String(chart.chartType || chart.type || 'bar');
  const option: Record<string, unknown> = {
    title: { text: chart.title || '', left: 'center' },
    tooltip: { trigger: chartType === 'pie' ? 'item' : 'axis' },
  };

  if (chartType === 'pie') {
    const sourceSeries = Array.isArray(chart.series) ? chart.series[0] : undefined;
    option.legend = { top: 'bottom' };
    option.series = [{
      ...(sourceSeries || {}),
      type: 'pie',
      radius: sourceSeries?.radius || '60%',
      data: sourceSeries?.data || chart.data || [],
    }];
    return option;
  }

  option.xAxis = Array.isArray(chart.xAxis)
    ? { type: 'category', data: chart.xAxis }
    : { type: 'category', ...(chart.xAxis || {}) };
  option.yAxis = chart.yAxis || { type: 'value' };
  option.series = (chart.series || []).map(series => ({
    ...series,
    type: series.type || chartType,
    data: Array.isArray(series.data) ? series.data : [],
  }));
  return option;
}
