package com.cretas.aims.repository.rules;

import com.cretas.aims.entity.rules.RuleExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * RuleExecutionLog repository.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §3
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Repository
public interface RuleExecutionLogRepository extends JpaRepository<RuleExecutionLog, UUID> {

    /**
     * Audit query for "show me last N runs of rule X".
     * Used by GET /api/mobile/{factoryId}/rules/{id}/logs.
     */
    Page<RuleExecutionLog> findByFactoryIdAndRuleIdOrderByExecutedAtDesc(
            String factoryId, UUID ruleId, Pageable pageable);
}
