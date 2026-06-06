package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.PriceAnomalyApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 供应商送货单数据访问 (G7 Tier A)。
 *
 * <p>{@code @Where(deleted_at IS NULL)} 在实体上, 所有查询自动过滤软删除。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Repository
public interface SupplierDeliveryNoteRepository extends JpaRepository<SupplierDeliveryNote, String> {

    /** Rule 4 幂等: 照片 hash 查重。 */
    Optional<SupplierDeliveryNote> findByPhotoHash(String photoHash);

    Optional<SupplierDeliveryNote> findByIdAndFactoryId(String id, String factoryId);

    Page<SupplierDeliveryNote> findByFactoryIdAndStatus(
            String factoryId, DeliveryNoteStatus status, Pageable pageable);

    Page<SupplierDeliveryNote> findByFactoryId(String factoryId, Pageable pageable);

    Page<SupplierDeliveryNote> findByFactoryIdAndDeliveryDateBetween(
            String factoryId, LocalDate from, LocalDate to, Pageable pageable);

    /** Rule 1: 当月某状态计数 (UploadDialog header "本月已录 X 张")。 */
    @Query("SELECT COUNT(n) FROM SupplierDeliveryNote n " +
           "WHERE n.factoryId = :factoryId AND n.status = :status " +
           "AND n.deliveryDate >= :monthStart AND n.deliveryDate <= :monthEnd")
    long countByFactoryIdAndStatusAndMonth(
            @Param("factoryId") String factoryId,
            @Param("status") DeliveryNoteStatus status,
            @Param("monthStart") LocalDate monthStart,
            @Param("monthEnd") LocalDate monthEnd);

    @Query("SELECT n FROM SupplierDeliveryNote n " +
           "WHERE n.factoryId = :factoryId AND n.supplierId = :supplierId " +
           "AND n.status = com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus.CONFIRMED " +
           "AND n.deliveryDate BETWEEN :startDate AND :endDate " +
           "ORDER BY n.deliveryDate ASC, n.createdAt ASC")
    List<SupplierDeliveryNote> findConfirmedBySupplierAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("supplierId") String supplierId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    Page<SupplierDeliveryNote> findByFactoryIdAndPriceAnomalyApprovalStatus(
            String factoryId, PriceAnomalyApprovalStatus status, Pageable pageable);
}
