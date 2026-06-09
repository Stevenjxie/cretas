package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.finance.VoucherSubjectMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherSubjectMappingRepository extends JpaRepository<VoucherSubjectMapping, String> {

    List<VoucherSubjectMapping> findByFactoryIdAndDeletedAtIsNullOrderBySettlementTypeAsc(String factoryId);

    Optional<VoucherSubjectMapping> findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
            String factoryId, SettlementType settlementType, String businessType);

    /** 查 __default__ 种子数据 (用于 per-factory 初始化) */
    List<VoucherSubjectMapping> findByFactoryIdAndDeletedAtIsNull(String factoryId);

    boolean existsByFactoryIdAndDeletedAtIsNull(String factoryId);
}
