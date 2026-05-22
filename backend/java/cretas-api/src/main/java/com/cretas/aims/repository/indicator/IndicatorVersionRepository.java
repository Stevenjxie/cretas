package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 指标历史快照 Repository — append-only audit log.
 *
 * <p>每次重算落库一行 (period_start / period_end / value / alert_level).
 * 用于趋势图 + 月报 + 合规审计 + version rollback (取过去某 snapshot 作 baseline).
 */
@Repository
public interface IndicatorVersionRepository extends JpaRepository<IndicatorVersion, String> {

    /** 按 indicator 时序倒序 (最新快照在最前)。 */
    List<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId);

    /** 分页历史 — Canvas Tab 版本管理列表用。 */
    Page<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId, Pageable pageable);

    /** 指定时间窗内的快照 — 趋势图用。 */
    @Query("SELECT v FROM IndicatorVersion v " +
            "WHERE v.indicatorId = :indicatorId " +
            "AND v.computedAt BETWEEN :from AND :to " +
            "ORDER BY v.computedAt ASC")
    List<IndicatorVersion> findInWindow(
            @Param("indicatorId") String indicatorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 最新一条 (current value)。 */
    Optional<IndicatorVersion> findFirstByIndicatorIdOrderByComputedAtDesc(String indicatorId);
}
