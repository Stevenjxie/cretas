package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
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
 * 餐饮散客仓库（#59 Phase 1）。
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Repository
public interface RestaurantGuestRepository extends JpaRepository<RestaurantGuest, String> {

    // ==================== 基础查询 ====================

    Optional<RestaurantGuest> findByIdAndFactoryId(String id, String factoryId);

    Optional<RestaurantGuest> findByFactoryIdAndPhone(String factoryId, String phone);

    Page<RestaurantGuest> findByFactoryIdOrderByLastVisitAtDesc(String factoryId, Pageable pageable);

    List<RestaurantGuest> findByFactoryIdAndRepId(String factoryId, Long repId);

    /**
     * 组合筛选：营销员 / 生命周期阶段 可选。
     *
     * <p>PG 兼容：parameter-side IS NULL 必须 CAST（见 .claude/rules/database-entity-sync.md）。</p>
     */
    @Query("SELECT g FROM RestaurantGuest g WHERE g.factoryId = :factoryId " +
            "AND (CAST(:repId AS long) IS NULL OR g.repId = :repId) " +
            "AND (CAST(:stage AS string) IS NULL OR g.lifecycleStage = :stage) " +
            "ORDER BY g.lastVisitAt DESC NULLS LAST, g.createdAt DESC")
    Page<RestaurantGuest> findByFilters(
            @Param("factoryId") String factoryId,
            @Param("repId") Long repId,
            @Param("stage") RestaurantGuestLifecycle stage,
            Pageable pageable);

    /**
     * 重点客户（VIP）：到访 3 次及以上，按到访次数降序。
     */
    @Query("SELECT g FROM RestaurantGuest g WHERE g.factoryId = :factoryId " +
            "AND g.visitCount >= 3 " +
            "ORDER BY g.visitCount DESC, g.lastVisitAt DESC NULLS LAST")
    List<RestaurantGuest> findVipGuests(@Param("factoryId") String factoryId);

    /**
     * 即将流失客户：最近到访早于阈值时间，且尚未流失（CHURNED）。
     *
     * <p>computed-on-read：流失/即将流失阶段不持久化，由本查询基于 last_visit_at 实时判定。
     * 仅纳入已有到访（last_visit_at 非空）且非 CHURNED 的客户。</p>
     */
    @Query("SELECT g FROM RestaurantGuest g WHERE g.factoryId = :factoryId " +
            "AND g.lastVisitAt IS NOT NULL " +
            "AND g.lastVisitAt < :thresholdDate " +
            "AND g.lifecycleStage <> com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle.CHURNED " +
            "ORDER BY g.lastVisitAt ASC")
    List<RestaurantGuest> findAtRiskGuests(
            @Param("factoryId") String factoryId,
            @Param("thresholdDate") LocalDateTime thresholdDate);
}
