package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 供应商地址仓储。多租户: 每个方法都带 factoryId。 */
@Repository
public interface SupplierAddressRepository extends JpaRepository<SupplierAddress, String> {

    @Query("SELECT a FROM SupplierAddress a WHERE a.factoryId = :factoryId "
            + "AND a.supplierId = :supplierId "
            + "ORDER BY a.isPrimary DESC, a.sortOrder ASC, a.createdAt ASC")
    List<SupplierAddress> findBySupplier(@Param("factoryId") String factoryId,
                                         @Param("supplierId") String supplierId);

    @Query("SELECT a FROM SupplierAddress a WHERE a.factoryId = :factoryId "
            + "AND a.id = :id")
    Optional<SupplierAddress> findByIdAndFactoryId(@Param("id") String id,
                                                   @Param("factoryId") String factoryId);

    @Query("SELECT a FROM SupplierAddress a WHERE a.factoryId = :factoryId "
            + "AND a.supplierId = :supplierId AND a.isPrimary = true")
    Optional<SupplierAddress> findPrimary(@Param("factoryId") String factoryId,
                                          @Param("supplierId") String supplierId);

    @Query("SELECT COUNT(a) FROM SupplierAddress a WHERE a.factoryId = :factoryId "
            + "AND a.supplierId = :supplierId")
    long countBySupplier(@Param("factoryId") String factoryId,
                         @Param("supplierId") String supplierId);
}
