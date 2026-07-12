package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDailyAvailability;
import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsDailyAvailabilityRepository extends JpaRepository<LogisticsDailyAvailability, String> {

    /** GET /daily-availability?date=... 及排线算法 — 某天全部覆盖记录 (跨资源类型)。 */
    List<LogisticsDailyAvailability> findByFactoryIdAndAvailDateAndDeletedAtIsNull(String factoryId, LocalDate availDate);

    /** upsert 查重键 — 同厂+同资源类型+同资源+同日期 唯一。 */
    Optional<LogisticsDailyAvailability> findByFactoryIdAndResourceTypeAndResourceIdAndAvailDateAndDeletedAtIsNull(
            String factoryId, AvailabilityResourceType resourceType, String resourceId, LocalDate availDate);

    /** DELETE /daily-availability/{id} — 租户隔离查询。 */
    Optional<LogisticsDailyAvailability> findByIdAndFactoryId(String id, String factoryId);
}
