package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
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

    /**
     * Current saved revision of every editable Workflow DRAFT in one factory.
     * Internal autosaves that are no longer current are intentionally excluded.
     */
    @Query("""
            select revision
              from ProductProcessWorkflowRevision revision
              join ProductProcessWorkflow workflow
                on workflow.currentRevisionId = revision.id
             where revision.factoryId = :factoryId
               and revision.status = :revisionStatus
               and workflow.factoryId = :factoryId
               and workflow.status = :workflowStatus
               and workflow.deletedAt is null
             order by revision.createdAt desc
            """)
    List<ProductProcessWorkflowRevision> findCurrentFactoryDraftRevisionsByStatus(
            @Param("factoryId") String factoryId,
            @Param("revisionStatus") ProductProcessWorkflowRevision.Status revisionStatus,
            @Param("workflowStatus") ProductProcessWorkflow.Status workflowStatus);

    default List<ProductProcessWorkflowRevision> findCurrentFactoryDraftRevisions(String factoryId) {
        return findCurrentFactoryDraftRevisionsByStatus(factoryId,
                ProductProcessWorkflowRevision.Status.DRAFT,
                ProductProcessWorkflow.Status.DRAFT);
    }

    @Query("""
            select coalesce(max(revision.revisionNumber), 0)
              from ProductProcessWorkflowRevision revision
             where revision.workflowId = :workflowId
            """)
    Integer findMaxRevisionNumber(@Param("workflowId") Long workflowId);
}
