package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopProcedure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 9 P2.E {@link SsopProcedure} (清洁标准模板).
 *
 * <p>BaseEntity 软删除自动过滤 (deleted_at IS NULL via @Where).
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByFactoryIdAndProcedureCode} — code unique check (创建去重)</li>
 *   <li>{@link #findByFactoryIdAndActiveTrue} — Scheduler 用 (扫所有 active)</li>
 *   <li>{@link #findByFactoryIdAndFrequencyAndActiveTrue} — Daily scheduler 仅取 DAILY</li>
 * </ul>
 */
@Repository
public interface SsopProcedureRepository extends JpaRepository<SsopProcedure, Long> {

    /** Code unique check (dedup). */
    Optional<SsopProcedure> findByFactoryIdAndProcedureCode(String factoryId, String procedureCode);

    /** 工厂所有 active 模板. */
    List<SsopProcedure> findByFactoryIdAndActiveTrue(String factoryId);

    /** Scheduler: 工厂指定频率 (DAILY) 的所有 active 模板. */
    List<SsopProcedure> findByFactoryIdAndFrequencyAndActiveTrue(String factoryId, String frequency);

    /** 月度 audit: 工厂内全部 active 模板 (含 frequency=DAILY/SHIFT/WEEKLY/MONTHLY). */
    long countByFactoryIdAndActiveTrue(String factoryId);
}
