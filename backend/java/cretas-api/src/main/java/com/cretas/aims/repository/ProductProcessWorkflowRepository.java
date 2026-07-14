package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

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

    List<ProductProcessWorkflow> findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(
            String factoryId,
            String productTypeId);

    List<ProductProcessWorkflow> findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(String factoryId);

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
}
