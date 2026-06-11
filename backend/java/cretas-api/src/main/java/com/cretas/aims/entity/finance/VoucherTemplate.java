package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.VoucherType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.util.ArrayList;
import java.util.List;

/**
 * 凭证生成模板 (Sprint 4 Wave 2 Chat J — C-VOUCHER-TPL-1 foundation).
 *
 * <p>Sprint 3 E 凭证 generator 当前写死科目代码 (e.g. 1122 应收账款 / 6001 主营业务收入).
 * 模板系统让财务管理员可编辑科目映射 — generator 优先查 template, 无 active template 时
 * fall back 到 hardcoded path (backward compatibility, 老 7 generator 仍 work)。
 *
 * <p>{@link #entriesJson} 用 JSONB 存借贷分录数组,每个 entry 形状:
 * <pre>
 * {
 *   "sortOrder": 0,
 *   "subjectCode": "1122",
 *   "subjectName": "应收账款",
 *   "direction":   "DEBIT" | "CREDIT",
 *   "amountExpression": "#order.totalAmount",
 *   "description": "..."
 * }
 * </pre>
 *
 * <p>{@code amountExpression} 用 SpEL 求值,可引用业务实体字段 (e.g. {@code #order.totalAmount},
 * {@code #payroll.netAmount})。VoucherGenerator 用现有 SpEL parser (已在
 * ApprovalWorkflowExecutorImpl 用过) 求值。
 *
 * <p>{@code isDefault=TRUE} 同 (factory_id, voucher_type) 最多一条 (unique partial 索引强制)。
 * Generator 选 template 顺序: factory+voucherType active default → 任意 active → null (走 hardcoded)。
 *
 * <p><b>Status</b>: Foundation (entity + repo only). Full service / generator refactor / Vue
 * editor 在 follow-up chat。
 *
 * @since 2026-05-24
 */
@Entity
@Table(name = "voucher_templates")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherTemplate extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "voucher_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private VoucherType voucherType;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSONB 数组形态; 见类 javadoc 中的形状描述. */
    @Type(JsonBinaryType.class)
    @Column(name = "entries_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<TemplateEntry> entries = new ArrayList<>();

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 单条借贷分录定义 (entries_json 数组元素).
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateEntry {
        private Integer sortOrder;
        private String  subjectCode;
        private String  subjectName;
        private Direction direction;
        /** SpEL expression; 求值结果须可转 BigDecimal. */
        private String  amountExpression;
        private String  description;
        /**
         * SP11 价税分离: 当此分录 amountExpression 求值为 0 时省略该行 (不渲染进凭证).
         *
         * <p>用途: 含税默认模板 (SALES_RECEIPT/PURCHASE_PAYMENT) 的销项/进项税行
         * 在零税订单 (taxAmount=0) 时省略, 退化为两行, 与 generator hardcoded buildEntries
         * 的零税两行行为完全一致 (向后兼容, 不在客户金蝶账套留下 ¥0.00 税行)。
         *
         * <p>null/false → 不省略 (默认, 老模板行为不变)。仅税行标 true。
         */
        private Boolean omitWhenZero;
    }

    public enum Direction { DEBIT, CREDIT }
}
