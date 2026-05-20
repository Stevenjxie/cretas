package com.cretas.aims.repository;

import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.enums.WageMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Sprint 6 Track W4-B: WagePolicy 仓储.
 *
 * <p>查询顺序约定: per-employee policy (employee_id=N) → factory-level default (employee_id IS NULL)
 * → 系统兜底 PIECE_RATE (向后兼容 PR #57 E).
 *
 * @since 2026-05-19
 */
@Repository
public interface WagePolicyRepository extends JpaRepository<WagePolicy, Long> {

    /**
     * 查找员工级别的 policy (优先).
     */
    Optional<WagePolicy> findByFactoryIdAndEmployeeIdAndIsActiveTrue(String factoryId, Long employeeId);

    /**
     * 查找工厂级别 default policy (employee_id IS NULL).
     */
    @Query("SELECT p FROM WagePolicy p WHERE p.factoryId = :factoryId " +
           "AND p.employeeId IS NULL AND p.isActive = TRUE")
    Optional<WagePolicy> findFactoryDefault(@Param("factoryId") String factoryId);

    /**
     * 列工厂所有 policy.
     */
    List<WagePolicy> findByFactoryIdOrderByEmployeeIdAscIdDesc(String factoryId);

    /**
     * 按 mode 统计 policy 数量 (admin UI 统计用).
     */
    long countByFactoryIdAndModeAndIsActiveTrue(String factoryId, WageMode mode);
}
