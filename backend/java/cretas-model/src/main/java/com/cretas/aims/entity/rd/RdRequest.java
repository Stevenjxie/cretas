package com.cretas.aims.entity.rd;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 研发需求 — 业务员提交客户样品需求
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "rd_requests",
        indexes = {
                @Index(name = "idx_rdr_factory", columnList = "factory_id"),
                @Index(name = "idx_rdr_status", columnList = "status")
        })
@Where(clause = "deleted_at IS NULL")
public class RdRequest extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() { if (id == null) id = UUID.randomUUID().toString(); }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "request_number", nullable = false, length = 50)
    private String requestNumber;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_contact", length = 200)
    private String customerContact;

    @Column(name = "requirements", columnDefinition = "TEXT")
    private String requirements;

    /** HIGH / MEDIUM / LOW */
    @Column(name = "urgency", length = 20)
    private String urgency;

    @Column(name = "assigned_to")
    private Long assignedTo;

    /** SUBMITTED / ASSIGNED / IN_PROGRESS / COMPLETED / CANCELLED */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;
}
