package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.SaltedWarehouseDeduction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 盐化仓扣量记录 Repository (SP7 F11).
 */
public interface SaltedWarehouseDeductionRepository extends JpaRepository<SaltedWarehouseDeduction, String> {

    Optional<SaltedWarehouseDeduction> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** 盐化报表: 按工厂+日期区间查询 */
    @Query("SELECT d FROM SaltedWarehouseDeduction d " +
           "WHERE d.factoryId = :factoryId " +
           "  AND d.deductionDate >= :startDate " +
           "  AND d.deductionDate <= :endDate " +
           "  AND d.deletedAt IS NULL " +
           "ORDER BY d.deductionDate DESC, d.createdAt DESC")
    Page<SaltedWarehouseDeduction> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /** 按仓库+日期区间 (单仓报表视角) */
    @Query("SELECT d FROM SaltedWarehouseDeduction d " +
           "WHERE d.factoryId = :factoryId " +
           "  AND d.warehouseId = :warehouseId " +
           "  AND d.deductionDate >= :startDate " +
           "  AND d.deductionDate <= :endDate " +
           "  AND d.deletedAt IS NULL " +
           "ORDER BY d.deductionDate DESC, d.createdAt DESC")
    List<SaltedWarehouseDeduction> findByWarehouseAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("warehouseId") String warehouseId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 汇总: 指定区间盐化总出量 */
    @Query("SELECT COALESCE(SUM(d.quantity), 0) FROM SaltedWarehouseDeduction d " +
           "WHERE d.factoryId = :factoryId " +
           "  AND d.warehouseId = :warehouseId " +
           "  AND d.deductionDate >= :startDate " +
           "  AND d.deductionDate <= :endDate " +
           "  AND d.deletedAt IS NULL")
    BigDecimal sumQuantityByWarehouseAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("warehouseId") String warehouseId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 幂等查重: 同供单号下是否已有记录 (5min 内防重复提交) */
    boolean existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(String factoryId, String orderNumber);
}
