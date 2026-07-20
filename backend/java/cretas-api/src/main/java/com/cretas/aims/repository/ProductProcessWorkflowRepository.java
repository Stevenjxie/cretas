package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("""
            select max(workflow.definitionVersion)
              from ProductProcessWorkflow workflow
             where workflow.factoryId = :factoryId
               and workflow.productTypeId = :productTypeId
            """)
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

    interface VersionSummaryProjection {
        Long getId();

        Integer getDefinitionVersion();

        ProductProcessWorkflow.Status getStatus();

        LocalDateTime getUpdatedAt();
    }
}
