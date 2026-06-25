package com.cretas.aims.entity.alerts;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * AlertType &lt;-&gt; String JPA 容错转换器.
 *
 * <p>替代 {@code @Enumerated(EnumType.STRING)}: DB 里若存在未知 alert_type (脏数据 /
 * 新类型未补枚举), 读取时返回 {@code null} 并 WARN, 而非 Hibernate 默认行为
 * (抛 IllegalArgumentException → 阻断整个应用启动).
 *
 * <p><b>事故背景 (2026-06-25)</b>: prod {@code alert_rules} 有 3 行手动 INSERT 的脏数据
 * (PAYMENT_OVERDUE / YIELD_LOW / WASTAGE_HIGH, 均不在枚举内). 新启动期某 bean init
 * findAll 加载 alert 规则 → Hibernate 解析 alert_type 抛异常 → APPLICATION FAILED TO START.
 * 一行坏数据不该让整个后端起不来 —— 故改用容错转换器, 未知值跳过 (alertType=null,
 * 下游按 {@code r.getAlertType() == X} 过滤天然不匹配 → 该规则静默失效, 不阻断).
 */
@Slf4j
@Converter
public class AlertTypeConverter implements AttributeConverter<AlertType, String> {

    @Override
    public String convertToDatabaseColumn(AlertType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public AlertType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return AlertType.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.warn("[AlertTypeConverter] 未知 alert_type='{}' (DB 脏数据/新类型未补枚举) — "
                    + "该规则 alertType 置 null 跳过, 不阻断启动. 修复: 补枚举或清理脏数据.", dbData);
            return null;
        }
    }
}
