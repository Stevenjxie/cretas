package com.cretas.aims.entity;

import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "workflow_templates", indexes = {
    @Index(name = "idx_wft_review_status", columnList = "review_status")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class WorkflowTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_name", nullable = false, length = 200)
    private String templateName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "industry_tags", columnDefinition = "TEXT")
    private String industryTags;

    @Column(name = "workflow_json", columnDefinition = "TEXT", nullable = false)
    private String workflowJson;

    @Column(name = "node_configs_json", columnDefinition = "TEXT")
    private String nodeConfigsJson;

    @Column(name = "global_config_json", columnDefinition = "TEXT")
    private String globalConfigJson;

    @Column(name = "source_count", nullable = false)
    @Builder.Default
    private Integer sourceCount = 0;

    @Column(name = "review_status", nullable = false, length = 30)
    @Builder.Default
    private String reviewStatus = "pending_review";

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "is_seed_data", nullable = false)
    @Builder.Default
    private Boolean isSeedData = false;
}
