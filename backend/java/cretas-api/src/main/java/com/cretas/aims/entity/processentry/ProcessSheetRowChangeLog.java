package com.cretas.aims.entity.processentry;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SP-G P3: 逐工序电子表格行级操作记录 (字段级 diff 审计时间线)。
 *
 * <p>镜像 {@link com.cretas.aims.entity.config.ConfigChangeLog} 的字段级审计模式
 * (before/after JSONB + diffSummary + operation + operatorId)，并增加 process_sheet_rows
 * 行定位列 (planId / processCode / clientRowId)，以便按行回放变更历史。
 *
 * <p>每次 {@code saveRow} (首存=CREATE / 覆盖=UPDATE) 或 {@code deleteRow} (=DELETE)
 * 各写一条记录。before/after 存录入 payload 的字段快照，diffSummary 列出变更的字段。
 */
@Entity
@Table(name = "process_sheet_row_change_log",
    indexes = {
        @Index(name = "idx_psrcl_row",
            columnList = "factory_id, plan_id, process_code, client_row_id")
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessSheetRowChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;

    @Column(name = "process_code", length = 32, nullable = false)
    private String processCode;

    @Column(name = "client_row_id", length = 64, nullable = false)
    private String clientRowId;

    /** CREATE / UPDATE / DELETE */
    @Column(name = "operation", length = 16, nullable = false)
    private String operation;

    @Type(JsonBinaryType.class)
    @Column(name = "before_value", columnDefinition = "jsonb")
    private Map<String, Object> beforeValue;

    @Type(JsonBinaryType.class)
    @Column(name = "after_value", columnDefinition = "jsonb")
    private Map<String, Object> afterValue;

    /** 人类可读变更摘要: "字段: 旧→新" 列表 */
    @Column(name = "diff_summary", columnDefinition = "TEXT")
    private String diffSummary;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
