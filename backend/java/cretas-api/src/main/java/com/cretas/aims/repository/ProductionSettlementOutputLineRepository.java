package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlementOutputLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionSettlementOutputLineRepository
        extends JpaRepository<ProductionSettlementOutputLine, String> {

    List<ProductionSettlementOutputLine>
    findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
            String factoryId, String settlementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select line
              from ProductionSettlementOutputLine line
             where line.factoryId = :factoryId
               and line.settlementId = :settlementId
             order by line.productTypeId asc, line.reportedBatchNumber asc
            """)
    List<ProductionSettlementOutputLine> lockByFactoryIdAndSettlementId(
            @Param("factoryId") String factoryId,
            @Param("settlementId") String settlementId);
}
