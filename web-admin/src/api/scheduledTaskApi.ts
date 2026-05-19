/**
 * Canvas-Cron 定时任务 API client (Phase 5 frontend).
 *
 * Backend: backend/java/cretas-api/.../controller/ScheduledTaskController.java
 * Path: /api/mobile/scheduled-tasks/*  (NO {factoryId} segment — tasks can be
 *       global cross-factory OR per-factory; factory scope set via request body
 *       or query param).
 *
 * @since Phase 5 (2026-05-19)
 */
import request from './request'
import type { PageResponse } from '@/types/api'

// ----------------------------------------------------------------------------
// Types — mirror backend Java entities (camelCase via Lombok @Data + Jackson).
// ----------------------------------------------------------------------------

/**
 * Status of a single task execution.
 * Mirrors {@code com.cretas.aims.entity.cron.TaskRunStatus}.
 */
export enum TaskRunStatus {
  SUCCESS = 'SUCCESS',
  FAILED = 'FAILED',
  RUNNING = 'RUNNING',
  SKIPPED = 'SKIPPED',
}

/**
 * Mirrors {@code com.cretas.aims.entity.cron.ScheduledTask}.
 * - factoryId NULL = global task (e.g. cache eviction)
 * - factoryId set  = per-factory task (e.g. F006 月度库存报表)
 */
export interface ScheduledTask {
  id?: string                           // UUID; absent on create
  factoryId?: string | null             // null = global
  taskCode: string                      // unique within global OR per-factory scope
  taskName?: string                     // human-readable label
  cronExpression: string                // Spring 6-field cron (sec min hr day mon dow)
  handlerBeanName: string               // Spring bean implementing TaskHandler
  enabled: boolean
  lastRunAt?: string | null             // ISO datetime
  lastRunStatus?: TaskRunStatus | null
  lastRunError?: string | null
  createdAt?: string
  updatedAt?: string
}

/**
 * Mirrors {@code com.cretas.aims.entity.cron.ScheduledTaskRunLog}.
 */
export interface ScheduledTaskRunLog {
  id: string
  taskId: string
  factoryId?: string | null
  startedAt: string                     // ISO datetime
  finishedAt?: string | null
  durationMs?: number | null
  status: TaskRunStatus
  errorMsg?: string | null
}

// ----------------------------------------------------------------------------
// Endpoints
// ----------------------------------------------------------------------------

/**
 * List tasks. Optional filters server-side filtered.
 * GET /api/mobile/scheduled-tasks?factoryId=&enabled=
 */
export const listScheduledTasks = (params?: { factoryId?: string; enabled?: boolean }) =>
  request.get<ScheduledTask[]>('/scheduled-tasks', { params })

/**
 * Create a task. Server validates cron syntax, handler bean existence, and
 * partial-unique constraint (task_code unique within global OR per-factory).
 * POST /api/mobile/scheduled-tasks
 */
export const createScheduledTask = (task: ScheduledTask) =>
  request.post<ScheduledTask>('/scheduled-tasks', task)

/**
 * Partial update. Cron / enabled / handler change triggers scheduler reload.
 * PUT /api/mobile/scheduled-tasks/{id}
 */
export const updateScheduledTask = (id: string, patch: Partial<ScheduledTask>) =>
  request.put<ScheduledTask>(`/scheduled-tasks/${id}`, patch)

/**
 * Toggle enabled flag (separate endpoint per backend design — calls reload()).
 * POST /api/mobile/scheduled-tasks/{id}/toggle?enabled=true|false
 */
export const toggleScheduledTask = (id: string, enabled: boolean) =>
  request.post<ScheduledTask>(`/scheduled-tasks/${id}/toggle`, null, { params: { enabled } })

/**
 * Manually trigger one run (bypasses ShedLock — single-instance manual override).
 * Returns the run log row.
 * POST /api/mobile/scheduled-tasks/{id}/run-now
 */
export const runScheduledTaskNow = (id: string) =>
  request.post<ScheduledTaskRunLog>(`/scheduled-tasks/${id}/run-now`)

/**
 * Soft-delete (deleted_at set; logs preserved).
 * DELETE /api/mobile/scheduled-tasks/{id}
 */
export const deleteScheduledTask = (id: string) =>
  request.delete(`/scheduled-tasks/${id}`)

/**
 * Paginated run history for a task.
 * GET /api/mobile/scheduled-tasks/{id}/logs?page=&size=
 *
 * Backend returns Spring Page shape: { content, totalElements, totalPages, number, size }.
 */
export const listScheduledTaskLogs = (
  id: string,
  page = 0,
  size = 20,
) => request.get<PageResponse<ScheduledTaskRunLog>>(`/scheduled-tasks/${id}/logs`, {
  params: { page, size },
})

/**
 * List Spring beans implementing {@code TaskHandler} — used to populate the
 * "handler" dropdown when creating a task. Added in post-review fix
 * (previously hard-coded a non-existent bean name).
 * GET /api/mobile/scheduled-tasks/handlers
 */
export const listTaskHandlers = () =>
  request.get<string[]>('/scheduled-tasks/handlers')

/**
 * Force DynamicScheduler to re-read DB (for multi-instance sync / debug).
 * POST /api/mobile/scheduled-tasks/refresh
 */
export const refreshScheduledTasks = () =>
  request.post<void>('/scheduled-tasks/refresh')
