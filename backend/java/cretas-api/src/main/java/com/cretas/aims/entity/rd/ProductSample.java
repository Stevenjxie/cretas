package com.cretas.aims.entity.rd;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 样品档案 — 研发人员创建并追踪样品开发过程
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "product_samples",
        indexes = {
                @Index(name = "idx_ps_factory", columnList = "factory_id"),
                @Index(name = "idx_ps_request", columnList = "rd_request_id"),
                @Index(name = "idx_ps_status", columnList = "status")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductSample extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() { if (id == null) id = UUID.randomUUID().toString(); }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "sample_code", nullable = false, length = 50)
    private String sampleCode;

    @Column(name = "rd_request_id", length = 191)
    private String rdRequestId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "specification", length = 200)
    private String specification;

    @Column(name = "grade", length = 50)
    private String grade;

    @Column(name = "main_material", length = 200)
    private String mainMaterial;

    /** DRAFT / IN_PROGRESS / TESTING / SUBMITTED / APPROVED / REJECTED */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** 进度记录 (JSON array: [{time, note, photoUrl}]) */
    @Column(name = "progress_notes", columnDefinition = "TEXT")
    private String progressNotes;

    /** 样品照片URLs (JSON array) */
    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private String photoUrls;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approval_notes", columnDefinition = "TEXT")
    private String approvalNotes;

    /** 审核通过后关联生成的 ProductType ID */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 审核通过后自动生成的 BOM ID (bom_items 的 product_type_id) */
    @Column(name = "bom_product_type_id", length = 191)
    private String bomProductTypeId;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /** 产品级别: A / B / C / D */
    @Column(name = "product_level", length = 10)
    private String productLevel;

    /** 客户名称 */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    /** 业务员 */
    @Column(name = "salesperson", length = 100)
    private String salesperson;

    /** 储存方式: 冷冻 / 冷藏 / 常温 */
    @Column(name = "storage_method", length = 50)
    private String storageMethod;

    // ════════════════════════════════════════════════════════════════
    // V3 P0 + Round 2 Agent A 截图 1 字段补齐 (Apr 7)
    // ════════════════════════════════════════════════════════════════

    /** 客户预期价格 — 客户原话: 有些客户自带价格过来 */
    @Column(name = "customer_expected_price", precision = 15, scale = 2)
    private java.math.BigDecimal customerExpectedPrice;

    /** 产品状态: 试样研发 / 不适宜合作 / 可合作 / 已转报模 / 已废弃 */
    @Column(name = "product_status", length = 50)
    private String productStatus;

    /** 客户性质: 餐饮 / 商超 / 新零售 / 团餐 / 其他 */
    @Column(name = "customer_type", length = 50)
    private String customerType;

    /** 客户最新要求 (长文本, 截图示例: "口味:咸鲜, 出成率 80% 左右, 煎出来后没异味") */
    @Column(name = "customer_latest_requirement", columnDefinition = "TEXT")
    private String customerLatestRequirement;

    /** 样品版本号: "第一次打样" / "第二次打样" / "确认版" */
    @Column(name = "sample_version", length = 50)
    private String sampleVersion;

    /** 产品卖点 (长文本) */
    @Column(name = "selling_points", columnDefinition = "TEXT")
    private String sellingPoints;

    /** 客户编码 (如 LSM20250037) — 关联 customer 主表的 code */
    @Column(name = "customer_code", length = 100)
    private String customerCode;

    /** 客户级别: A / B / C */
    @Column(name = "customer_level", length = 10)
    private String customerLevel;

    // ════════════════════════════════════════════════════════════════
    // Round 3 字段补齐 (Apr 11) — 对照客户截图 14:09 t014m09s_0047_s.jpg
    // ════════════════════════════════════════════════════════════════

    /** 成品报价 — 给客户的成品单价 */
    @Column(name = "product_quote_price", precision = 15, scale = 2)
    private java.math.BigDecimal productQuotePrice;

    /** 原料价格 — 主原料单价 */
    @Column(name = "material_price", precision = 15, scale = 2)
    private java.math.BigDecimal materialPrice;

    /** 加工费 — 产品加工费用 */
    @Column(name = "processing_fee", precision = 15, scale = 2)
    private java.math.BigDecimal processingFee;

    /** 主原料信息 (长文本, 详细描述; 区别于 mainMaterial 字段的名称) */
    @Column(name = "main_material_info", columnDefinition = "TEXT")
    private String mainMaterialInfo;

    /** 主要原料出成率 (百分比, 例如 80.00 表示 80%) */
    @Column(name = "main_material_yield_rate", precision = 5, scale = 2)
    private java.math.BigDecimal mainMaterialYieldRate;

    /** 主原料图片 URLs (JSON array, 例如: ["url1", "url2"]) */
    @Column(name = "main_material_images", columnDefinition = "TEXT")
    private String mainMaterialImages;

    // ════════════════════════════════════════════════════════════════
    // catalog 行31: 研发试样采购票务字段 — 有票/无票影响凭证科目映射
    // ════════════════════════════════════════════════════════════════

    /**
     * 研发试样采购是否有票 (是否取得正规增值税发票).
     * <ul>
     *   <li>true  = 有票: 进项税可抵扣 → 科目 2221.02 (应交税费-应交增值税-进项税额)</li>
     *   <li>false = 无票: 进项税不可抵扣 → 计入试样成本/费用 (默认科目 6602 管理费用)</li>
     *   <li>null  = 未指定 (向后兼容: 历史样品数据默认不受影响)</li>
     * </ul>
     * 对应转录原话: "澳阳的可能有票，另一家试样的没票；票务字段在研发试样阶段就需带，影响科目映射与试样成本核算"
     */
    @Column(name = "has_invoice")
    private Boolean hasInvoice;

    /**
     * 试样采购发票号码 (供应商开具).
     * 当 hasInvoice=true 时填写，用于凭证生成时关联核销。
     * null = 未填写 / 无票时不需要。
     */
    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    /**
     * 根据有票/无票状态返回进项税凭证科目代码.
     *
     * <p>有票 (hasInvoice=true)  → 2221.02 (应交税费-应交增值税-进项税额, 可抵扣)
     * <p>无票 (hasInvoice=false) → 6602    (管理费用, 不可抵扣进入费用)
     * <p>未指定 (null)           → null    (调用方自行决定, 不强制)
     *
     * @return 科目代码字符串, 或 null (未指定时)
     */
    public String resolveVatSubjectCode() {
        if (Boolean.TRUE.equals(hasInvoice)) {
            return "2221.02"; // 应交税费-应交增值税-进项税额
        } else if (Boolean.FALSE.equals(hasInvoice)) {
            return "6602";    // 管理费用(无票不可抵扣)
        }
        return null; // 未指定
    }

    /**
     * 返回进项税科目名称 (与 resolveVatSubjectCode 配套).
     *
     * @return 科目名称字符串, 或 null (未指定时)
     */
    public String resolveVatSubjectName() {
        if (Boolean.TRUE.equals(hasInvoice)) {
            return "应交税费-应交增值税-进项税额";
        } else if (Boolean.FALSE.equals(hasInvoice)) {
            return "管理费用";
        }
        return null;
    }
}
