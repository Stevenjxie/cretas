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
     * 当天已经发到的最大单号 —— 发号用这个，⛔ 不要用上面那个 count。
     *
     * <h3>🔴 为什么必须是 nativeQuery (2026-08-18 prod 实测)</h3>
     * 实体上有 {@code @Where(clause = "deleted_at IS NULL")}，它会<b>静默作用到 JPQL</b>。
     * 于是「今天发过几个号」被算成「今天还活着几张单」——
     * F006 当天 6 张单里有 1 张被软删，count 数到 5 → 发号 {@code MR20260818-0006}
     * → 撞上<b>已经存在的</b> 0006 → 唯一约束冲突 → 用户看到
     * 「数据已存在，请勿重复提交」，而他一次都没重复点。
     * <b>那天剩下的时间里一张领料单都建不出来</b>（每次都发同一个号）。
     *
     * <p>native 查询不受 {@code @Where} 影响，能看到软删除行 —— 单号一旦发出去就不能再发，
     * 哪怕那张单后来被删了。
     */
    @Query(value = "SELECT MAX(requisition_no) FROM factory_material_requisitions "
            + "WHERE factory_id = :factoryId AND requisition_no LIKE :pattern",
            nativeQuery = true)
    String findMaxRequisitionNo(@Param("factoryId") String factoryId, @Param("pattern") String pattern);

    /**
     * P1-5 车间仓 20:00 清仓扫描: 查所有状态为 ISSUED/IN_USE 且创建时间早于今天的 FMR
     * (跨天未关单, 可能需要提醒主管处理).
     */
    List<FactoryMaterialRequisition> findByStatusInAndCreatedAtBeforeAndDeletedAtIsNull(
            List<Status> statuses, java.time.LocalDateTime before);
}
