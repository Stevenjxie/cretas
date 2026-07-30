package com.cretas.aims.entity.config;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "canvas_ddl_log",
    indexes = {
        @Index(name = "idx_cdl_factory_version", columnList = "factory_id, config_version")
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CanvasDDLLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "config_version")
    private Integer configVersion;

    @Column(name = "ddl_statement", columnDefinition = "TEXT", nullable = false)
    private String ddlStatement;

    @Column(name = "target_table", length = 100)
    private String targetTable;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
