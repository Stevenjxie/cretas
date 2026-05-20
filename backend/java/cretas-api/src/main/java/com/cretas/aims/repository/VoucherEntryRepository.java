package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.finance.VoucherEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface VoucherEntryRepository extends JpaRepository<VoucherEntry, String> {

    List<VoucherEntry> findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc(String voucherId);

    void deleteByVoucherId(String voucherId);

    /**
     * Sprint 6 W4-A: 按 auxiliaryEntityId 聚合一段时间内的分录, 计算借/贷/净余额/分录数.
     *
     * <p>过滤条件:
     * <ul>
     *   <li>factory_id = :factoryId (跨 voucher 表 JOIN)</li>
     *   <li>auxiliary_type = :auxiliaryType (e.g. CUSTOMER)</li>
     *   <li>voucher.voucherDate BETWEEN :startDate AND :endDate (业务日期, 非 createdAt)</li>
     *   <li>voucher.status != VOID (排除作废, DRAFT/POSTED 都纳入余额)</li>
     *   <li>voucher.deletedAt IS NULL (@Where 已强制, 此处显式以备 ad-hoc 调用)</li>
     *   <li>entry.auxiliaryEntityId IS NOT NULL (定义上 paired, 防御性)</li>
     * </ul>
     *
     * <p>JPQL 用 SELECT NEW 直接构造 DTO, 避免 Object[] 投射不安全 cast.
     *
     * <p>返回按 entryCount DESC 排序 (活跃实体在前), 同 count 时按 entityId 字典序.
     */
    @Query("SELECT new com.cretas.aims.dto.finance.AuxiliaryAggregateRow(" +
            "  e.auxiliaryType, e.auxiliaryEntityId, " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COALESCE(SUM(e.debit), 0) - COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND e.auxiliaryType = :auxiliaryType " +
            "  AND e.auxiliaryEntityId IS NOT NULL " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.auxiliaryType, e.auxiliaryEntityId " +
            "ORDER BY COUNT(e) DESC, e.auxiliaryEntityId ASC")
    List<com.cretas.aims.dto.finance.AuxiliaryAggregateRow> aggregateByAuxiliary(
            @Param("factoryId") String factoryId,
            @Param("auxiliaryType") AuxiliaryType auxiliaryType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
