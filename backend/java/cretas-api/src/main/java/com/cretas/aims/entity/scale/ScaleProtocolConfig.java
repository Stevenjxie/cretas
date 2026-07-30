package com.cretas.aims.entity.scale;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 秤协议配置实体
 * 存储不同品牌/型号电子秤的通信协议配置
 *
 * @author Cretas Team
 * @since 2026-01-04
 */
@Entity
@Table(name = "scale_protocol_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ScaleProtocolConfig extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID (null 表示全局通用协议)
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 协议编码 (唯一标识)
     * 格式: BRAND_MODEL_TYPE, 如 KELI_D2008_ASCII
     */
    @Column(name = "protocol_code", length = 50, unique = true, nullable = false)
    private String protocolCode;

    /**
     * 协议名称
     */
    @Column(name = "protocol_name", length = 100, nullable = false)
    private String protocolName;

    /**
     * 连接类型
     */
    @Column(name = "connection_type", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private ConnectionType connectionType;

    /**
     * 串口配置 (JSON)
     * 格式: {"baudRate":9600, "dataBits":8, "stopBits":1, "parity":"NONE"}
     */
    @Column(name = "serial_config", columnDefinition = "JSON")
    private String serialConfig;

    /**
     * API配置 (JSON)
     * 格式: {"baseUrl":"", "authType":"BEARER", "headers":{}}
     */
    @Column(name = "api_config", columnDefinition = "JSON")
    private String apiConfig;

    /**
     * 数据帧格式定义 (JSON)
     * 包含帧结构、字段解析规则等
     */
    @Column(name = "frame_format", columnDefinition = "JSON", nullable = false)
    private String frameFormat;

    /**
     * Drools 规则组名称 (可选，用于复杂解析)
     */
    @Column(name = "parsing_rule_group", length = 50)
    private String parsingRuleGroup;

    /**
     * 校验和类型
     */
    @Column(name = "checksum_type", length = 10)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ChecksumType checksumType = ChecksumType.NONE;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 是否经过真机验证
     */
    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    /**
     * 是否为内置协议 (内置协议不可删除)
     */
    @Column(name = "is_builtin")
    @Builder.Default
    private Boolean isBuiltin = false;

    /**
     * 备注说明
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 读取模式
     */
    @Column(name = "read_mode", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ReadMode readMode = ReadMode.CONTINUOUS;

    /**
     * 稳定阈值 (毫秒)
     * 数据稳定后需要保持的时间
     */
    @Column(name = "stable_threshold_ms")
    @Builder.Default
    private Integer stableThresholdMs = 500;

    /**
     * Modbus 配置 (JSON)
     * 格式: {"slaveId":1, "functionCode":3, "registerAddress":0, "registerCount":2}
     */
    @Column(name = "modbus_config", columnDefinition = "JSON")
    private String modbusConfig;

    /**
     * 文档链接
     */
    @Column(name = "documentation_url", length = 500)
    private String documentationUrl;

    /**
     * 样本数据 (16进制字符串)
     * 用于协议测试和验证
     */
    @Column(name = "sample_data_hex", length = 200)
    private String sampleDataHex;

    // ==================== 枚举类型 ====================

    /**
     * 读取模式枚举
     */
    public enum ReadMode {
        CONTINUOUS,     // 连续输出模式 (秤主动发送)
        POLL,           // 轮询模式 (主动请求)
        ON_CHANGE       // 变化时上报
    }

    /**
     * 连接类型枚举
     */
    public enum ConnectionType {
        RS232,          // 串口 RS232
        RS485,          // 串口 RS485
        HTTP_API,       // HTTP REST API
        MQTT,           // MQTT 消息
        MODBUS_RTU,     // Modbus RTU
        MODBUS_TCP,     // Modbus TCP
        TCP_SOCKET      // TCP Socket
    }

    /**
     * 校验和类型枚举
     */
    public enum ChecksumType {
        NONE,           // 无校验
        XOR,            // 异或校验
        CRC16,          // CRC16校验
        CRC32,          // CRC32校验
        SUM,            // 累加和校验
        MODBUS_CRC      // Modbus CRC16校验
    }
}
