package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowRevisionRepository
        extends JpaRepository<ProductProcessWorkflowRevision, Long> {

    Optional<ProductProcessWorkflowRevision> findByIdAndFactoryId(Long id, String factoryId);

    Optional<ProductProcessWorkflowRevision> findByWorkflowIdAndRevisionHash(
            Long workflowId, String revisionHash);

    List<ProductProcessWorkflowRevision> findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
            String factoryId, String productTypeId);

    @Query("""
            select coalesce(max(revision.revisionNumber), 0)
              from ProductProcessWorkflowRevision revision
             where revision.workflowId = :workflowId
            """)
    Integer findMaxRevisionNumber(@Param("workflowId") Long workflowId);
}
