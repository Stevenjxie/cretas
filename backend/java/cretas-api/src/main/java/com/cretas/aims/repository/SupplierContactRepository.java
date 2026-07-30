package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 供应商联系人仓储。
 *
 * <p>⚠️ 多租户: 每个方法都带 factoryId —— 只按 supplierId 查会跨租户泄漏
 * (supplier id 是 UUID 猜不到, 但越权 id 遍历仍是真实攻击面)。
 */
@Repository
public interface SupplierContactRepository extends JpaRepository<SupplierContact, String> {

    @Query("SELECT c FROM SupplierContact c WHERE c.factoryId = :factoryId "
            + "AND c.supplierId = :supplierId "
            + "ORDER BY c.isPrimary DESC, c.sortOrder ASC, c.createdAt ASC")
    List<SupplierContact> findBySupplier(@Param("factoryId") String factoryId,
                                         @Param("supplierId") String supplierId);

    @Query("SELECT c FROM SupplierContact c WHERE c.factoryId = :factoryId "
            + "AND c.supplierId IN :supplierIds "
            + "ORDER BY c.isPrimary DESC, c.sortOrder ASC, c.createdAt ASC")
    List<SupplierContact> findBySupplierIds(@Param("factoryId") String factoryId,
                                            @Param("supplierIds") List<String> supplierIds);

    @Query("SELECT c FROM SupplierContact c WHERE c.factoryId = :factoryId "
            + "AND c.id = :id")
    Optional<SupplierContact> findByIdAndFactoryId(@Param("id") String id,
                                                   @Param("factoryId") String factoryId);

    @Query("SELECT c FROM SupplierContact c WHERE c.factoryId = :factoryId "
            + "AND c.supplierId = :supplierId AND c.isPrimary = true")
    Optional<SupplierContact> findPrimary(@Param("factoryId") String factoryId,
                                          @Param("supplierId") String supplierId);

    @Query("SELECT COUNT(c) FROM SupplierContact c WHERE c.factoryId = :factoryId "
            + "AND c.supplierId = :supplierId")
    long countBySupplier(@Param("factoryId") String factoryId,
                         @Param("supplierId") String supplierId);
}
