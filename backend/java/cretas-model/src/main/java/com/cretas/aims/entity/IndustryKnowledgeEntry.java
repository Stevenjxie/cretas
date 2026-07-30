package com.cretas.aims.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 行业知识条目实体
 *
 * 存储食品行业的专业知识，用于 RAG 检索增强。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-19
 */
@Data
@Entity
@Table(name = "industry_knowledge_entry")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndustryKnowledgeEntry {

    @Id
    @Column(length = 50)
    private String id;

    /**
     * 主题代码 (如 PRODUCT_STATUS, QUALITY_ANALYSIS)
     */
    @Column(name = "topic_code", length = 50, nullable = false)
    private String topicCode;

    /**
     * 主题名称 (如 产品状态分析, 质检分析要点)
     */
    @Column(name = "topic_name", length = 100)
    private String topicName;

    /**
     * 知识内容
     */
    @Column(name = "knowledge_content", columnDefinition = "TEXT", nullable = false)
    private String knowledgeContent;

    /**
     * 来源类型 (SYSTEM=系统内置, USER=用户添加, LEARNED=系统学习)
     */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    private Boolean isActive;

    /**
     * 版本号
     */
    @Column(name = "version")
    private Integer version;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (version == null) {
            version = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
