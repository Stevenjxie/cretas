package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 餐饮到访记录仓库（#59 Phase 1）。
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Repository
public interface RestaurantVisitRepository extends JpaRepository<RestaurantVisit, String> {

    /** 某客户的到访历史（最近优先）。 */
    List<RestaurantVisit> findByGuestIdOrderByVisitAtDesc(String guestId);

    /** 某客户到访记录数（用于 visit_number 计算 + 去重判断）。 */
    long countByGuestId(String guestId);
}
