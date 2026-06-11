package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 原材料类型实体类
 *
 * <p>本实体类用于管理原材料类型的基础信息，是原材料管理模块的核心实体之一。</p>
 *
 * <h3>数据库表信息</h3>
 * <ul>
 *   <li><b>表名</b>：raw_material_types</li>
 *   <li><b>唯一约束</b>：factory_id + code（同一工厂内编码唯一）</li>
 *   <li><b>索引</b>：factory_id、is_active</li>
 * </ul>
 *
 * <h3>字段说明</h3>
 * <table border="1">
 *   <tr><th>字段</th><th>数据库列</th><th>说明</th></tr>
 *   <tr><td>id</td><td>id</td><td>UUID主键</td></tr>
 *   <tr><td>factoryId</td><td>factory_id</td><td>工厂ID（外键）</td></tr>
 *   <tr><td>code</td><td>code</td><td>原材料编码（如：DY、HHY）</td></tr>
 *   <tr><td>name</td><td>name</td><td>原材料名称（如：带鱼、黄花鱼）</td></tr>
 *   <tr><td>category</td><td>category</td><td>类别（如：海水鱼、淡水鱼、虾类）</td></tr>
 *   <tr><td>unit</td><td>unit</td><td>计量单位（默认：kg）</td></tr>
 *   <tr><td>unitPrice</td><td>unit_price</td><td>单价</td></tr>
 *   <tr><td>storageType</td><td>storage_type</td><td>存储方式（fresh/frozen/dry）</td></tr>
 *   <tr><td>shelfLifeDays</td><td>shelf_life_days</td><td>保质期（天）</td></tr>
 *   <tr><td>minStock</td><td>min_stock</td><td>最低库存警戒线</td></tr>
 *   <tr><td>maxStock</td><td>max_stock</td><td>最高库存上限</td></tr>
 *   <tr><td>isActive</td><td>is_active</td><td>是否激活</td></tr>
 *   <tr><td>notes</td><td>notes</td><td>备注说明</td></tr>
 *   <tr><td>createdBy</td><td>created_by</td><td>创建者ID</td></tr>
 * </table>
 *
 * <h3>关联关系</h3>
 * <ul>
 *   <li><b>Factory</b>：多对一，所属工厂</li>
 *   <li><b>User</b>：多对一，创建者</li>
 *   <li><b>MaterialBatch</b>：一对多，该类型下的所有批次</li>
 *   <li><b>MaterialProductConversion</b>：一对多，原材料转换记录</li>
 * </ul>
 *
 * <h3>继承说明</h3>
 * <p>继承自 {@link BaseEntity}，自动获得以下功能：</p>
 * <ul>
 *   <li>createdAt：创建时间（自动设置）</li>
 *   <li>updatedAt：更新时间（自动更新）</li>
 *   <li>deletedAt：软删除时间戳</li>
 *   <li>软删除机制：删除时设置deletedAt，查询时自动过滤</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建新的原材料类型
 * RawMaterialType material = new RawMaterialType();
 * material.setId(UUID.randomUUID().toString());
 * material.setFactoryId("F001");
 * material.setCode("DY");
 * material.setName("带鱼");
 * material.setCategory("海水鱼");
 * material.setUnit("kg");
 * material.setStorageType("frozen");
 * material.setIsActive(true);
 * material.setCreatedBy(1);
 * </pre>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 * @see BaseEntity 基础实体类
 * @see MaterialBatch 原材料批次
 * @see Factory 工厂实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "createdByUser", "materialBatches", "conversions"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "raw_material_types",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "code"})
       },
       indexes = {
           @Index(name = "idx_material_factory", columnList = "factory_id"),
           @Index(name = "idx_material_is_active", columnList = "is_active")
       }
)
@Where(clause = "deleted_at IS NULL")
public class RawMaterialType extends BaseEntity {

    // ========== 主键 ==========

    /**
     * 主键ID - UUID字符串格式
     * <p>使用UUID作为主键，确保全局唯一性，便于分布式系统</p>
     */
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    // ========== 基础信息 ==========

    /**
     * 工厂ID
     * <p>外键关联 factories 表，用于数据隔离</p>
     * <p>同一工厂内的原材料类型编码必须唯一</p>
     */
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    /**
     * 原材料编码
     * <p>工厂内唯一的简短编码，用于快速识别</p>
     * <p>示例：DY（带鱼）、HHY（黄花鱼）、JWX（基围虾）</p>
     */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /**
     * 原材料名称
     * <p>原材料的完整名称</p>
     * <p>示例：带鱼、黄花鱼、基围虾、扇贝</p>
     */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * 原材料类别
     * <p>用于分类管理原材料</p>
     * <p>示例：海水鱼、淡水鱼、虾类、贝类、肉类、蔬菜</p>
     */
    @Column(name = "category", length = 50)
    private String category;

    /**
     * 计量单位
     * <p>原材料的计量单位，默认为 kg</p>
     * <p>示例：kg、g、箱、包、桶</p>
     */
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    // ========== 价格和库存 ==========

    /**
     * 单价
     * <p>原材料的参考单价，用于成本估算</p>
     * <p>精度：10位整数，2位小数</p>
     *
     * <p>Stripped to {@code null} for roles lacking {@code procurement:price:view}
     * (PR #455 BUG-2 sweep — sister to {@link com.cretas.aims.entity.bom.BomItem#unitPrice}).
     */
    @PriceSensitive
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /**
     * 存储方式
     * <p>指定原材料的存储条件</p>
     * <ul>
     *   <li>fresh - 鲜品（冷藏）</li>
     *   <li>frozen - 冻品（冷冻）</li>
     *   <li>dry - 干货（常温）</li>
     * </ul>
     */
    @Column(name = "storage_type", length = 20)
    private String storageType;

    /**
     * 保质期（天）
     * <p>原材料的默认保质期，用于计算批次过期日期</p>
     */
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    /**
     * 最低库存警戒线
     * <p>当库存低于此值时，触发低库存预警</p>
     */
    @Column(name = "min_stock", precision = 10, scale = 2)
    private BigDecimal minStock;

    /**
     * 最高库存上限
     * <p>库存管理的参考上限值</p>
     */
    @Column(name = "max_stock", precision = 10, scale = 2)
    private BigDecimal maxStock;

    @Column(name = "moving_avg_price", precision = 12, scale = 4)
    private BigDecimal movingAvgPrice;

    // ========== SP4-A8: 税率 + 含税单价 ==========

    /**
     * SP4-A8: 采购税率枚举 (9%农产品 / 13%一般货物).
     * <p>设置后, 含税单价 → 未税单价自动换算并更新 {@link #unitPrice}.</p>
     * <p>null = 未配置税率 (不换算).</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_rate", length = 10)
    private TaxRate taxRate;

    /**
     * SP4-A8: 含税单价 (仓管录入时的发票价).
     * <p>Service 层在 taxRate 非 null 时自动换算 → {@link #unitPrice} (未税).</p>
     * <p>Price-sensitive: 同 {@link #unitPrice}.</p>
     */
    @PriceSensitive
    @Column(name = "tax_included_unit_price", precision = 15, scale = 4)
    private BigDecimal taxIncludedUnitPrice;

    // ========== P8: 包材关联固定客户 (catalog 行99-100, 非必填) ==========

    /**
     * P8: 包材可关联的固定客户ID (nullable).
     * <p>包材专属字段 — 如"吸塑盒"专供某客户, 建档时可关联 customer.id.
     * 非包材物料此字段留 null, 不报错.</p>
     * <p>外键逻辑软引用 customers 表 id (不加 FK 约束, 避免级联删除).</p>
     */
    @Column(name = "associated_customer_id", length = 191)
    private String associatedCustomerId;

    // ========== W-ABA-1 抄码品标记 (六扇门 F006 卤制品工厂刚需) ==========

    /**
     * 是否抄码品
     * <p>抄码品 = 每箱重量不一的原料 (如牛肉/猪肉/鸭肉).
     * true 时采购单创建隐藏箱数, 入库按实际称重逐箱录入.</p>
     */
    @Column(name = "is_abaca_packaging", nullable = false)
    private Boolean isAbacaPackaging = false;

    /**
     * 抄码品箱重区间描述, UI 提示用 (示例: "约 10-15kg/箱").
     */
    @Column(name = "abaca_unit_per_box", length = 20)
    private String abacaUnitPerBox;

    /**
     * 抄码品默认计量单位 (kg / g), 入库录称重默认带这个单位.
     */
    @Column(name = "abaca_default_unit", length = 20)
    private String abacaDefaultUnit;

    // ========== 状态和备注 ==========

    /**
     * 是否激活
     * <p>控制原材料类型是否可用</p>
     * <ul>
     *   <li>true - 激活状态，可以创建新批次</li>
     *   <li>false - 停用状态，不出现在下拉列表中</li>
     * </ul>
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 备注说明
     * <p>原材料的详细描述或备注信息</p>
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * 创建者ID
     * <p>外键关联 users 表，记录创建此原材料类型的用户</p>
     * <p>类型为 Long，与 User.id 保持一致</p>
     */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ========== SP8: 16位分段编码主编码 ==========

    /**
     * SP8: 16位编码前三位主编码 (类型段), 如 001=原料/002=包材/003=辅料.
     * 历史数据可为空; 新建物料时由 Service 自动提取 code 前三位或从级联选择结果写入.
     */
    @Column(name = "primary_code", length = 3)
    private String primaryCode;

    // ========== 包材规格 (packaging spec) ==========

    /**
     * 包材每个成品单位所需数量 (仅 category=PACKAGING 有意义).
     *
     * <p>语义: 生产 1 个最小成品单位 (outputQuantityPerUnit) 需要此包材多少个/袋/盒。
     * 示例:
     * <ul>
     *   <li>吸塑盒 "每产品1个" → {@code packQtyPerProduct = 1.000000}</li>
     *   <li>气调膜 "每产品1片" → {@code packQtyPerProduct = 1.000000}</li>
     *   <li>外箱 "每箱20盒成品" → {@code packQtyPerProduct = 0.050000} (1/20)</li>
     * </ul>
     *
     * <p>BOM 自动推算:
     * {@code standardQuantity = packQtyPerProduct}
     * (无需再乘 outputQuantityPerUnit — 因为 BOM standardQuantity 已经是"每成品单位"口径).
     *
     * <p>null = 未配置规格, 需手填 BOM standardQuantity.
     *
     * <p>DB: pack_qty_per_product NUMERIC(15,6) NULL
     */
    @Column(name = "pack_qty_per_product", precision = 15, scale = 6)
    private java.math.BigDecimal packQtyPerProduct;

    // ========== 关联关系 ==========

    /**
     * 所属工厂
     * <p>多对一关系，延迟加载</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    /**
     * 创建者用户
     * <p>多对一关系，延迟加载</p>
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    /**
     * 该类型下的所有批次
     * <p>一对多关系，级联所有操作</p>
     */
    @JsonIgnore
    @OneToMany(mappedBy = "materialType", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialBatch> materialBatches = new ArrayList<>();

    /**
     * 原材料转换记录
     * <p>一对多关系，记录原材料到产品的转换</p>
     */
    @JsonIgnore
    @OneToMany(mappedBy = "materialType", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialProductConversion> conversions = new ArrayList<>();
}
