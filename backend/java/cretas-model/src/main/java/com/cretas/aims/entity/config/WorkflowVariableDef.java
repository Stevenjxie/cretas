package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Where;

/**
 * 工作流变量库元数据 (Sprint 4 W2 Chat J — C-WF-VAR-1).
 *
 * <p>用途: 告诉 PropertyPanel / AIChat Tool / 文档生成器 "这个工厂可在 SpEL
 * 表达式里引用哪些变量, 类型是什么, 样例值是什么". 配合
 * {@link com.cretas.aims.service.workflow.WorkflowVariableContext} +
 * {@link com.cretas.aims.service.workflow.SandboxedSpelEvaluator}.
 *
 * <p>{@code factoryId NULL} = 系统预设 (跨工厂共享, 15 默认变量);
 * {@code factoryId} 非 NULL = 工厂自定义扩展 (e.g. 客户特殊指标).
 *
 * @since 2026-05-17
 */
@Entity
@Table(name = "workflow_variable_def")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVariableDef extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /** NULL = 系统预设; 非 NULL = 工厂自定义. */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /** SpEL 引用名, e.g. "own.userId" / "businessEntity['priority']". */
    @Column(name = "var_name", nullable = false, length = 100)
    private String varName;

    /** STRING / NUMBER / DECIMAL / BOOLEAN / DATE / DATETIME. */
    @Column(name = "var_type", nullable = false, length = 50)
    private String varType;

    /** OWN / ORDER / CUSTOMER / BUSINESS / CUSTOM. */
    @Column(name = "category", nullable = false, length = 50)
    @Builder.Default
    private String category = "CUSTOM";

    @Column(name = "description", length = 500)
    private String description;

    /** 样例值 (string-encoded), 供 AIChat Tool 测试填充. */
    @Column(name = "sample_value", length = 500)
    private String sampleValue;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
