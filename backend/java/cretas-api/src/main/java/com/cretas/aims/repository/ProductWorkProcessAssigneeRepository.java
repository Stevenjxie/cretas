package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductWorkProcessAssignee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * T121: 工序多人负责 — repository.
 *
 * <p>{@code @Where(deleted_at IS NULL)} on the entity ensures soft-deleted rows are filtered
 * automatically in all Spring Data queries.
 */
@Repository
public interface ProductWorkProcessAssigneeRepository
        extends JpaRepository<ProductWorkProcessAssignee, Long> {

    /** All active (non-soft-deleted) assignees for a given product_work_process. */
    List<ProductWorkProcessAssignee> findByProductWorkProcessId(Long productWorkProcessId);

    /** Used for upsert: check if (pwpId, workerId) already exists (active). */
    boolean existsByProductWorkProcessIdAndWorkerId(Long productWorkProcessId, Long workerId);
}
