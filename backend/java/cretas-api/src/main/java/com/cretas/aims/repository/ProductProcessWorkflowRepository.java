package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowRepository extends JpaRepository<ProductProcessWorkflow, Long> {

    Optional<ProductProcessWorkflow> findByIdAndFactoryId(Long id, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select workflow
              from ProductProcessWorkflow workflow
             where workflow.id = :id
               and workflow.factoryId = :factoryId
            """)
    Optional<ProductProcessWorkflow> lockByIdAndFactoryId(
            @Param("id") Long id,
            @Param("factoryId") String factoryId);

    /** raw-centric 多成品解析: 批量取 workflow (多租户防御, @Where 软删过滤自动生效)。 */
    List<ProductProcessWorkflow> findByIdInAndFactoryId(Collection<Long> ids, String factoryId);

    Optional<ProductProcessWorkflow> findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status);

    Optional<ProductProcessWorkflow> findByFactoryIdAndLastPublishIdempotencyKey(
            String factoryId,
            String lastPublishIdempotencyKey);

    /**
     * ⛔ 同 findMaxRevisionNumber:必须 native、必须把软删行数进来。
     *
     * <p>唯一索引 {@code uk_product_process_workflow_version
     * UNIQUE (factory_id, product_type_id, status, definition_version)} **没有**
     * {@code WHERE deleted_at IS NULL} 谓词(同表的 uk_product_process_workflow_active_draft /
     * uk_ppw_publish_idempotency_factory_key 都有,唯独这条漏了),所以软删行仍占着版本号;
     * 而实体上有 {@code @Where(deleted_at IS NULL)},JPQL 的 max 看不见它们。
     *
     * <p>这是 2026-08-08 revision 取号事故(见 ProductProcessWorkflowRevisionRepository)的
     * **同因兄弟**,当时全库 soft-deleted workflow 数为 0 所以尚未爆;一旦有人软删一条 workflow,
     * 下一次发布取到的版本号就会撞这条唯一索引,复现同一个 500。潜伏期修掉,不等它炸。
     */
    @Query(value = """
            select max(definition_version)
              from product_process_workflows
             where factory_id = :factoryId
               and product_type_id = :productTypeId
            """, nativeQuery = true)
    Optional<Integer> findMaxDefinitionVersion(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    @Query("""
            select workflow.id as id,
                   workflow.definitionVersion as definitionVersion,
                   workflow.status as status,
                   workflow.updatedAt as updatedAt
              from ProductProcessWorkflow workflow
             where workflow.factoryId = :factoryId
               and workflow.productTypeId = :productTypeId
             order by workflow.definitionVersion desc
            """)
    List<VersionSummaryProjection> findVersionSummaries(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    List<ProductProcessWorkflow> findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(String factoryId);

    List<ProductProcessWorkflow> findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(
            String factoryId, String productTypeId);

    /** Shared serialization point for unit-authority writes, publishing and runtime admission. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select workflow
              from ProductProcessWorkflow workflow
             where workflow.factoryId = :factoryId
             order by workflow.id
            """)
    List<ProductProcessWorkflow> lockByFactoryId(@Param("factoryId") String factoryId);

    Optional<ProductProcessWorkflow> findFirstByFactoryIdAndProductTypeIdAndDefinitionVersion(
            String factoryId,
            String productTypeId,
            Integer definitionVersion);

    /** 这个 owner 名下有没有工艺图 —— 重锚前判目标是否已被占用(activations 上有唯一键)。 */
    boolean existsByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    /**
     * 整条版本谱系换归属对象。
     *
     * <p>⛔ 必须整条搬: {@code (factoryId, productTypeId)} 就是谱系键
     * (见 {@code findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc} 等全部版本查询),
     * 只搬新发布的那一版会把谱系劈成两半, 旧版本从此在新 owner 下查不到。
     *
     * @return 搬动的行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProductProcessWorkflow workflow
               set workflow.productTypeId = :newOwnerId
             where workflow.factoryId = :factoryId
               and workflow.productTypeId = :oldOwnerId
            """)
    int reanchorLineage(
            @Param("factoryId") String factoryId,
            @Param("oldOwnerId") String oldOwnerId,
            @Param("newOwnerId") String newOwnerId);

    interface VersionSummaryProjection {
        Long getId();

        Integer getDefinitionVersion();

        ProductProcessWorkflow.Status getStatus();

        LocalDateTime getUpdatedAt();
    }
}
