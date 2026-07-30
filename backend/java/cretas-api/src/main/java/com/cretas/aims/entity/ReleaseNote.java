package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * U-FEED-1 (Sprint 4 Wave 2 Chat L) — system-wide release notes / 升级日志.
 *
 * Cross-tenant by design: a release note is published once and seen by every
 * factory. dismissedBy is tracked client-side via localStorage; backend only
 * stores the canonical feed.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "release_notes",
        indexes = {
                @Index(name = "idx_release_notes_published_at", columnList = "published_at"),
                @Index(name = "idx_release_notes_severity", columnList = "severity")
        })
public class ReleaseNote extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** Markdown body. Frontend renders via `marked`. */
    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    /** info / improvement / breaking. Drives the toast color. */
    @Column(name = "severity", length = 32, nullable = false)
    private String severity = "info";

    @Column(name = "published_at", nullable = false)
    private LocalDate publishedAt;

    /** Optional cap: rotate stale notes out of the feed automatically. */
    @Column(name = "expires_at")
    private LocalDate expiresAt;
}
