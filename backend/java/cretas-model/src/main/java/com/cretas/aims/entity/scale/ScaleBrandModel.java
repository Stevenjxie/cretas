package com.cretas.aims.entity.scale;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 秤品牌型号实体
 * 存储电子秤品牌和型号信息，便于设备配置选择
 *
 * @author Cretas Team
 * @since 2026-01-04
 */
@Entity
@Table(name = "scale_brand_models")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ScaleBrandModel extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 品牌编码
     * 如: KELI, YAOHUA, XICE, YIZHENG
     */
    @Column(name = "brand_code", length = 50, nullable = false)
    private String brandCode;

    /**
     * 品牌名称
     * 如: 柯力, 耀华, 矽策, 英展
     */
    @Column(name = "brand_name", length = 100, nullable = false)
    private String brandName;

    /**
     * 品牌英文名
     * 如: Keli, Yaohua, Xice
     */
    @Column(name = "brand_name_en", length = 100)
    private String brandNameEn;

    /**
     * 型号编码
     * 如: D2008, XK3190, XC709S
     */
    @Column(name = "model_code", length = 50, nullable = false)
    private String modelCode;

    /**
     * 型号名称
     * 如: 柯力D2008仪表
     */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /**
     * 支持的协议ID列表 (JSON数组)
     * 如: ["uuid1", "uuid2"]
     */
    @Column(name = "supported_protocol_ids", columnDefinition = "JSON")
    private String supportedProtocolIds;

    /**
     * 默认协议ID
     */
    @Column(name = "default_protocol_id", length = 36)
    private String defaultProtocolId;

    /**
     * 是否有串口
     */
    @Column(name = "has_serial_port")
    @Builder.Default
    private Boolean hasSerialPort = true;

    /**
     * 是否内置WiFi
     */
    @Column(name = "has_wifi")
    @Builder.Default
    private Boolean hasWifi = false;

    /**
     * 是否有以太网
     */
    @Column(name = "has_ethernet")
    @Builder.Default
    private Boolean hasEthernet = false;

    /**
     * 是否有蓝牙
     */
    @Column(name = "has_bluetooth")
    @Builder.Default
    private Boolean hasBluetooth = false;

    /**
     * 是否有USB接口
     */
    @Column(name = "has_usb")
    @Builder.Default
    private Boolean hasUsb = false;

    /**
     * 量程范围
     * 如: "30kg-150kg"
     */
    @Column(name = "weight_range", length = 50)
    private String weightRange;

    /**
     * 精度
     * 如: "±0.01kg"
     */
    @Column(name = "accuracy", length = 20)
    private String accuracy;

    /**
     * 秤类型
     * DESKTOP: 桌面秤, PLATFORM: 台秤, FLOOR: 地磅
     */
    @Column(name = "scale_type", length = 20)
    @Enumerated(EnumType.STRING)
    private ScaleType scaleType;

    /**
     * IP防护等级
     * 如: IP54, IP65, IP68, IP69K
     */
    @Column(name = "ip_rating", length = 10)
    private String ipRating;

    /**
     * 材质
     * 如: 304不锈钢, 碳钢等
     */
    @Column(name = "material", length = 100)
    private String material;

    /**
     * 制造商名称
     */
    @Column(name = "manufacturer", length = 200)
    private String manufacturer;

    /**
     * 制造商网站
     */
    @Column(name = "manufacturer_website", length = 500)
    private String manufacturerWebsite;

    /**
     * 价格区间
     * 如: "¥1,200-1,800"
     */
    @Column(name = "price_range", length = 50)
    private String priceRange;

    /**
     * 推荐分数 (0-100)
     */
    @Column(name = "recommendation_score")
    @Builder.Default
    private Integer recommendationScore = 0;

    /**
     * 推荐理由
     */
    @Column(name = "recommendation_reason", length = 500)
    private String recommendationReason;

    /**
     * 文档链接
     */
    @Column(name = "documentation_url", length = 500)
    private String documentationUrl;

    /**
     * 图片URL
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * 是否推荐
     */
    @Column(name = "is_recommended")
    @Builder.Default
    private Boolean isRecommended = false;

    /**
     * 是否经过验证
     */
    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    /**
     * 备注
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 排序权重
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    // ==================== 枚举类型 ====================

    /**
     * 秤类型枚举
     */
    public enum ScaleType {
        DESKTOP,    // 桌面秤 (1-30kg)
        PLATFORM,   // 台秤 (30-500kg)
        FLOOR       // 地磅 (500kg-100T)
    }
}
