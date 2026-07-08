package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import org.hibernate.annotations.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "product_work_processes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "product_type_id", "work_process_id"}),
    indexes = {
        @Index(name = "idx_pwp_product", columnList = "factory_id, product_type_id")
    }
)
public class ProductWorkProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 50)
    private String productTypeId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "process_order")
    @Builder.Default
    private Integer processOrder = 0;

    @Column(name = "unit_override", length = 20)
    private String unitOverride;

    @Column(name = "estimated_minutes_override")
    private Integer estimatedMinutesOverride;

    @Column(name = "responsible_worker_id")
    private Long responsibleWorkerId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 是否需要报工 (六扇门 Wave2 — 可配置报工粒度)。
     *
     * <p>DEFAULT true = 逐道报 (现有所有工厂/产品行为不变, 向后兼容)。
     * false = 该工序保留配置但 spawn 时跳过, 不生成 {@code work_process_task}
     * (六扇门只在领料/产出两点报工, 中间工序免报但工序记录仍在供溯源)。</p>
     */
    @Column(name = "reporting_required", nullable = false)
    @Builder.Default
    private Boolean reportingRequired = true;

    /**
     * 是否「半成品注入工序」(张权 R4 — 从中段起步选库里已有半成品/成品直接投料)。
     *
     * <p>DEFAULT false = 普通工序。true = 该工序逐道录入时可从仓库选已有的半成品(SFI)/成品(FG)
     * 直接作为投料来源, 跳过前面的工序, 从这一道接着生产 (生产第二个产品时不用重配前段工序)。
     *
     * <p>逐道录入 SFI/FG picker 的显示条件 = 本 flag=true (config-driven) OR 现有 archetype
     * 兜底 (熟制/气调/焯水/滚揉/去舌苔) —— 后者保证历史工序零回归, 见 ProcessDataTable.vue showSfi。</p>
     */
    @Column(name = "allow_semi_finished_injection", nullable = false)
    @Builder.Default
    private Boolean allowSemiFinishedInjection = false;

    /**
     * 是否允许本道工序从多个上游批次混批投料。
     *
     * <p>DEFAULT false = 单一上游批次; true = 报工 UI 可添加多个上游来源批。
     */
    @Column(name = "allow_multiple_upstream_sources", nullable = false)
    @Builder.Default
    private Boolean allowMultipleUpstreamSources = false;

    // ── 工序成本配置 (报工自动继承; 防呆: 操作员不手填会计类别/明细) ──────────────
    /** 该工序默认成本类别 RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER; 报工未传 costCategory 时继承 (CALC-003) */
    @Column(name = "default_cost_category", length = 20)
    private String defaultCostCategory;

    /** 该工序默认包装明细模板 [{name,cost}] (膜/气体/标签/其他); 报工未传 packagingDetail 时继承 (AUDIT-002) */
    @Type(JsonType.class)
    @Column(name = "packaging_template", columnDefinition = "jsonb")
    private List<Map<String, Object>> packagingTemplate;

    /** 该工序辅料分摊方式 BY_OUTPUT/FIXED_RATIO; 报工未传 auxAllocMethod 时继承 (AUDIT-004) */
    @Column(name = "aux_alloc_method", length = 20)
    private String auxAllocMethod;

    // ── 段2(B) 辅料标准单价双锚点投料-产出对账 (抓多投/误差) ──────────────────────
    /** 标准出成率 (配方率, 小数 0.85=85%); 投料-产出对账基准 (实际报工率 vs 标准 → 抓多投/误差) */
    @Column(name = "standard_yield_rate", precision = 8, scale = 4)
    private BigDecimal standardYieldRate;

    /** 辅料标准单价 (元/kg); 离线按配方算好, 非实时 BOM; 未配=null 视为 0 不崩 */
    @Column(name = "aux_unit_price", precision = 12, scale = 4)
    private BigDecimal auxUnitPrice;

    /** 元/kg 乘哪侧 kg: INPUT|OUTPUT (保水工序 output>input 必须显式) */
    @Column(name = "aux_basis", length = 10)
    private String auxBasis;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
