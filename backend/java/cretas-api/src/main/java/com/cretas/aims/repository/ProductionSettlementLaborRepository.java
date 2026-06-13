package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlementLabor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionSettlementLaborRepository extends JpaRepository<ProductionSettlementLabor, Long> {
    List<ProductionSettlementLabor> findBySettlementIdAndDeletedAtIsNull(String settlementId);
}
