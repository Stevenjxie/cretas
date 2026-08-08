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

    /**
     * 下一个 revisionNumber 的取号依据 —— ⛔ 必须是 native, 必须把**软删行也数进来**。
     *
     * 2026-08-08 事故: 这里原本是 JPQL。实体上有 {@code @Where(deleted_at IS NULL)},
     * 于是软删的 revision 对 JPQL 不可见 —— 而唯一约束
     * {@code uk_ppwr_workflow_revision UNIQUE (workflow_id, revision_number)} 建在**物理表**上,
     * 软删那行仍然占着它的号。结果: 某 workflow 的 4 号被软删后, max 只看得见 3 → 取号 4 →
     * 每次都撞唯一约束。**不是偶发, 是那条 workflow 从此再也存不了草稿**(真机必现, 两次不同追踪码)。
     *
     * 判据: 取号/判重要跟**约束所在的那张表**对齐。约束不排除软删, 取号就不能排除软删 ——
     * 两边口径必须同源, 否则一定在某个时刻打架。
     */
    @Query(value = """
            select coalesce(max(revision_number), 0)
              from product_process_workflow_revisions
             where workflow_id = :workflowId
            """, nativeQuery = true)
    Integer findMaxRevisionNumber(@Param("workflowId") Long workflowId);
}
