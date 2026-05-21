package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopBlockingGate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository — Sprint 9 P2.E {@link SsopBlockingGate} (生产阻产 audit).
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByFactoryIdAndResolvedAtIsNullOrderByBlockedAtDesc} — 未解决的 audit</li>
 *   <li>{@link #findByFactoryIdAndBlockedAtBetween} — 月度 audit</li>
 *   <li>{@link #existsByProductionPlanIdAndResolvedAtIsNull} — gate 幂等 (同 plan 未解决就不重复创)</li>
 * </ul>
 */
@Repository
public interface SsopBlockingGateRepository extends JpaRepository<SsopBlockingGate, Long> {

    /** 工厂未解决的 audit (按 blocked_at 倒序). */
    List<SsopBlockingGate> findByFactoryIdAndResolvedAtIsNullOrderByBlockedAtDesc(String factoryId);

    /** 月度 audit report 用. */
    List<SsopBlockingGate> findByFactoryIdAndBlockedAtBetween(
            String factoryId, LocalDateTime startTime, LocalDateTime endTime);

    /** Gate 幂等 — 同 production plan 未解决的阻产 audit 已存在则不重复创. */
    boolean existsByProductionPlanIdAndResolvedAtIsNull(String productionPlanId);
}
