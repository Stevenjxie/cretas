package com.cretas.aims.entity.smartbi.postgres;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * SmartBI 查询模板 (smart_bi_query_templates, smartbi_prod_db).
 *
 * <p>前端 查询模板管理页 (QueryTemplateManager.vue) 通过 /{factoryId}/smart-bi/query-templates
 * 做 CRUD。此前后端缺该控制器 → 整页对所有工厂 404 "加载模板失败" (预存 bug, 2026-06-14 补)。
 */
@Entity
@Table(name = "smart_bi_query_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartBiQueryTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "name", nullable = false)
    private String name;

    /** 财务分析 / 销售分析 / 生产分析 / 自定义 (前端分组用). */
    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "description")
    private String description;

    @Column(name = "query_template", nullable = false, columnDefinition = "TEXT")
    private String queryTemplate;

    /** 参数列表, 前端以 JSON 字符串存取 (TemplateParam[]). */
    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
