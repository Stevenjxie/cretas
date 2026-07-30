package com.cretas.aims.entity.cron;

/**
 * Status of a scheduled task execution (single run).
 *
 * <p>Phase 5 — Canvas-Cron skeleton. Sister chat fills usage.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — handler returned without exception.</li>
 *   <li>{@link #FAILED} — handler threw an exception; {@code error_msg} populated.</li>
 *   <li>{@link #RUNNING} — execution started; not yet finished. Stale RUNNING after
 *       JVM crash should be marked FAILED by a janitor (see follow-up).</li>
 *   <li>{@link #SKIPPED} — ShedLock acquired by another instance; this JVM did not run.
 *       Whether to record SKIPPED rows at all is sister-chat decision (default: don't,
 *       only winner records).</li>
 * </ul>
 *
 * @since Phase 5 (2026-05-18)
 */
public enum TaskRunStatus {
    SUCCESS,
    FAILED,
    RUNNING,
    SKIPPED
}
