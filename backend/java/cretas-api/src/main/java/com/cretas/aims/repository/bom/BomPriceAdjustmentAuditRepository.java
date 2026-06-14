package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomPriceAdjustmentAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BomPriceAdjustmentAuditRepository extends JpaRepository<BomPriceAdjustmentAudit, Long> {
}
