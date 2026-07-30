package com.cretas.aims.entity.config;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "factory_configurations",
    indexes = {
        @Index(name = "idx_fc_factory_status", columnList = "factory_id, status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "idx_fc_factory_version", columnNames = {"factory_id", "config_version"})
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "config_version", nullable = false)
    @Builder.Default
    private Integer configVersion = 1;

    @Column(name = "status", length = 16, nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "rollback_version")
    private Integer rollbackVersion;

    // Round 4 Fix P0-1: audit workflow columns (existing from V20260410_12 migration)
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    // Round 4 Fix P1-17: Optimistic locking to prevent concurrent-edit conflicts.
    // Two admins saving simultaneously now get a controlled StaleObjectStateException
    // instead of a random ConstraintViolationException on the unique factory_id+version index.
    @jakarta.persistence.Version
    @Column(name = "row_version")
    @Builder.Default
    private Long rowVersion = 0L;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
