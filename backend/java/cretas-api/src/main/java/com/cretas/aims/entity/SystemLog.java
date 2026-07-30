package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;
/**
 * 系统日志实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Entity
@Table(name = "system_logs",
       indexes = {
           @Index(name = "idx_log_factory", columnList = "factory_id"),
           @Index(name = "idx_log_type", columnList = "log_type"),
           @Index(name = "idx_log_created_at", columnList = "created_at")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 日志类型: INFO, WARNING, ERROR, AUDIT
     */
    @Column(name = "log_type", nullable = false, length = 20)
    private String logType;

    /**
     * 日志级别: DEBUG, INFO, WARN, ERROR, FATAL
     */
    @Column(name = "log_level", nullable = false, length = 10)
    private String logLevel;

    /**
     * 模块名称
     */
    @Column(name = "module", length = 50)
    private String module;

    /**
     * 操作类型
     */
    @Column(name = "action", length = 50)
    private String action;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 用户名
     */
    @Column(name = "username", length = 50)
    private String username;

    /**
     * IP地址
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /**
     * 用户代理
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * 请求方法
     */
    @Column(name = "request_method", length = 10)
    private String requestMethod;

    /**
     * 请求URL
     */
    @Column(name = "request_url", length = 500)
    private String requestUrl;

    /**
     * 请求参数
     */
    @Column(name = "request_params", columnDefinition = "TEXT")
    private String requestParams;

    /**
     * 响应状态
     */
    @Column(name = "response_status")
    private Integer responseStatus;

    /**
     * 响应内容
     */
    @Column(name = "response_data", columnDefinition = "TEXT")
    private String responseData;

    /**
     * 错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 堆栈跟踪
     */
    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    /**
     * 执行时间（毫秒）
     */
    @Column(name = "execution_time")
    private Long executionTime;

    /**
     * 日志消息
     */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
