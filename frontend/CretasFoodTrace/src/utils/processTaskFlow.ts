import { ProcessTaskItem } from '../services/api/processTaskApiClient';

const PROCESS_NAME_ORDER: Array<[string, number]> = [
  ['修油', 10],
  ['水解', 10],
  ['化冻', 10],
  ['滚揉', 20],
  ['注射', 20],
  ['焯水', 30],
  ['熟制', 40],
  ['卤制', 40],
  ['气调', 50],
  ['装盒', 50],
  ['包装', 50],
];

function toNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function getBatchRank(task: ProcessTaskItem): number {
  const batchId = toNumber(task.batchId ?? task.productionBatchId);
  if (batchId != null) return -batchId;
  if (typeof task.productionRunId === 'string' && task.productionRunId.startsWith('BATCH-')) {
    const runBatchId = toNumber(task.productionRunId.replace('BATCH-', ''));
    return runBatchId != null ? -runBatchId : 0;
  }
  return Number.MAX_SAFE_INTEGER;
}

export function getBatchKey(task: ProcessTaskItem): string | null {
  const batchId = task.batchId ?? task.productionBatchId;
  if (batchId != null) return `batch:${batchId}`;
  if (typeof task.productionRunId === 'string' && task.productionRunId.startsWith('BATCH-')) {
    return task.productionRunId;
  }
  return null;
}

function getProcessOrder(task: ProcessTaskItem): number {
  const explicitOrder = toNumber(task.processOrder);
  if (explicitOrder != null) return explicitOrder;

  if (typeof task.workProcessId === 'string') {
    const suffix = task.workProcessId.match(/(\d+)$/)?.[1];
    const parsedSuffix = toNumber(suffix);
    if (parsedSuffix != null) return parsedSuffix;
  }

  const name = task.processName || '';
  const matched = PROCESS_NAME_ORDER.find(([keyword]) => name.includes(keyword));
  if (matched) return matched[1];

  const taskId = toNumber(task.workProcessTaskId);
  if (taskId != null) return taskId;

  return Number.MAX_SAFE_INTEGER;
}

function getStatusRank(status: ProcessTaskItem['status']): number {
  if (status === 'IN_PROGRESS' || status === 'SUPPLEMENTING') return 0;
  if (status === 'PENDING') return 1;
  if (status === 'COMPLETED') return 2;
  return 3;
}

function isTerminalTask(status: ProcessTaskItem['status']): boolean {
  return status === 'COMPLETED' || status === 'CLOSED';
}

export function compareTasksByWorkOrder(a: ProcessTaskItem, b: ProcessTaskItem): number {
  return (
    getBatchRank(a) - getBatchRank(b)
    || getProcessOrder(a) - getProcessOrder(b)
    || getStatusRank(a.status) - getStatusRank(b.status)
    || String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
    || String(a.id).localeCompare(String(b.id))
  );
}

export function buildReportableTaskIds(orderedTasks: ProcessTaskItem[]): Set<string> {
  const reportableIds = new Set<string>();
  const batchTasks = new Map<string, ProcessTaskItem[]>();

  for (const task of orderedTasks) {
    const key = getBatchKey(task);
    if (!key) {
      if (task.status === 'IN_PROGRESS' || task.status === 'SUPPLEMENTING') {
        reportableIds.add(task.id);
      }
      continue;
    }
    const group = batchTasks.get(key) || [];
    group.push(task);
    batchTasks.set(key, group);
  }

  for (const group of batchTasks.values()) {
    const sortedGroup = [...group].sort(compareTasksByWorkOrder);
    const nextTask = sortedGroup.find(task => !isTerminalTask(task.status));
    if (nextTask && nextTask.status !== 'CLOSED') {
      reportableIds.add(nextTask.id);
    }
  }

  return reportableIds;
}

export function pickCurrentReportTask(tasks: ProcessTaskItem[]): ProcessTaskItem | null {
  const orderedTasks = [...tasks].sort(compareTasksByWorkOrder);
  const reportableIds = buildReportableTaskIds(orderedTasks);
  return orderedTasks.find(task => reportableIds.has(task.id)) ?? null;
}

export function extractProcessTaskList(result: unknown): ProcessTaskItem[] {
  const response = result as { data?: { content?: ProcessTaskItem[] } | ProcessTaskItem[] };
  if (response?.data && !Array.isArray(response.data) && Array.isArray(response.data.content)) return response.data.content;
  if (Array.isArray(response?.data)) return response.data;
  if (Array.isArray(result)) return result as ProcessTaskItem[];
  return [];
}
