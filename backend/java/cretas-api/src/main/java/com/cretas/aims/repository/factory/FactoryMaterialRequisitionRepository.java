package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryMaterialRequisitionRepository extends JpaRepository<FactoryMaterialRequisition, String> {

    Optional<FactoryMaterialRequisition> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    // 防呆 Rule 2 (fool-proof-design): 支持按人类可读的需求单号查询 (前端不该要求仓管员手填 UUID).
    Optional<FactoryMaterialRequisition> findByFactoryIdAndRequisitionNoAndDeletedAtIsNull(String factoryId, String requisitionNo);

    Page<FactoryMaterialRequisition> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<FactoryMaterialRequisition> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, Status status, Pageable pageable);

    List<FactoryMaterialRequisition> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(String factoryId, String productionPlanId);

    @Query("SELECT COUNT(r) FROM FactoryMaterialRequisition r WHERE r.factoryId = :factoryId AND r.requisitionNo LIKE CONCAT(:prefix, '%')")
    long countByFactoryIdAndRequisitionNoPrefix(@Param("factoryId") String factoryId, @Param("prefix") String prefix);

    /**
     * P1-5 车间仓 20:00 清仓扫描: 查所有状态为 ISSUED/IN_USE 且创建时间早于今天的 FMR
     * (跨天未关单, 可能需要提醒主管处理).
     */
    List<FactoryMaterialRequisition> findByStatusInAndCreatedAtBeforeAndDeletedAtIsNull(
            List<Status> statuses, java.time.LocalDateTime before);
}
