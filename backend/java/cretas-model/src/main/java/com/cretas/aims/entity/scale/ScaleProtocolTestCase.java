package com.cretas.aims.entity.scale;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 秤协议测试用例实体
 * 存储协议解析测试用例，用于验证协议配置正确性
 *
 * @author Cretas Team
 * @since 2026-01-04
 */
@Entity
@Table(name = "scale_protocol_test_cases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ScaleProtocolTestCase extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 关联的协议ID
     */
    @Column(name = "protocol_id", length = 36, nullable = false)
    private String protocolId;

    /**
     * 测试用例名称
     */
    @Column(name = "test_name", length = 100, nullable = false)
    private String testName;

    /**
     * 测试用例描述
     */
    @Column(name = "test_description", length = 500)
    private String testDescription;

    /**
     * 输入数据 (16进制字符串)
     */
    @Column(name = "input_data_hex", length = 500)
    private String inputDataHex;

    /**
     * 输入数据 (ASCII可读格式)
     */
    @Column(name = "input_data_ascii", length = 500)
    private String inputDataAscii;

    /**
     * 期望的重量值
     */
    @Column(name = "expected_weight", precision = 12, scale = 4)
    private BigDecimal expectedWeight;

    /**
     * 期望的单位
     */
    @Column(name = "expected_unit", length = 10)
    private String expectedUnit;

    /**
     * 期望的稳定状态
     */
    @Column(name = "expected_stable")
    private Boolean expectedStable;

    /**
     * 是否为负面测试 (期望解析失败)
     */
    @Column(name = "is_negative_test")
    @Builder.Default
    private Boolean isNegativeTest = false;

    /**
     * 期望的错误代码 (负面测试时)
     */
    @Column(name = "expected_error_code", length = 50)
    private String expectedErrorCode;

    /**
     * 优先级 (1最高)
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 5;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 最后运行结果
     */
    @Column(name = "last_run_result", length = 20)
    @Enumerated(EnumType.STRING)
    private TestResult lastRunResult;

    /**
     * 最后运行时间
     */
    @Column(name = "last_run_at")
    private java.time.LocalDateTime lastRunAt;

    /**
     * 最后运行错误信息
     */
    @Column(name = "last_run_error", length = 500)
    private String lastRunError;

    // ==================== 枚举类型 ====================

    /**
     * 测试结果枚举
     */
    public enum TestResult {
        PASSED,     // 通过
        FAILED,     // 失败
        ERROR       // 执行错误
    }
}
