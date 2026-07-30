package com.cretas.aims.entity;

import lombok.*;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * T121: 工序多人负责 — join 表.
 *
 * <p>product_work_process 与 worker 之间的多对多中间实体.
 * 继承 BaseEntity 提供 created_at / updated_at / deleted_at 软删除支持.
 *
 * <p>唯一约束 {@code uq_pwp_assignee_active} 在迁移中通过 partial unique index
 * (WHERE deleted_at IS NULL) 维护 — JPA 层无需重建此约束.
 *
 * <p>用法:
 * <ul>
 *   <li>创建: 直接 {@code save(new ProductWorkProcessAssignee(pwpId, workerId))}
 *   <li>软删: {@code entity.softDelete(); save(entity)}
 *   <li>报工鉴权: 查此表获取全部 active assignee worker_id set (包含 fallback: responsible_worker_id)
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(
    name = "product_work_process_assignees",
    indexes = {
        @Index(name = "idx_pwp_assignees_pwp", columnList = "product_work_process_id")
    }
)
@Where(clause = "deleted_at IS NULL")
public class ProductWorkProcessAssignee extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_work_process_id", nullable = false)
    private Long productWorkProcessId;

    /**
     * Worker (user) ID — mirrors the type used by {@code responsible_worker_id}
     * on {@code product_work_processes}.
     */
    @Column(name = "worker_id", nullable = false)
    private Long workerId;
}
