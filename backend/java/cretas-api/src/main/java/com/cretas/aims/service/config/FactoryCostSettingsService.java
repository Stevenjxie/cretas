package com.cretas.aims.service.config;

import java.math.BigDecimal;

/**
 * 工厂成本设置 (工时单价) 读写服务.
 *
 * <p>修复 {@code ClerkProcessEntryServiceImpl.resolveLaborRate} 的 dead-end:
 * 报工 warning 指向"工厂成本设置"配置工时单价,但此前无任何写入入口。本服务 + 对应
 * Controller/前端页补齐该入口。
 */
public interface FactoryCostSettingsService {

    /** 取工厂工时单价; 未配置返 null (调用方/前端回退默认 ¥26). */
    BigDecimal getLaborHourlyRate(String factoryId);

    /** upsert 工厂工时单价 (按 factoryId 唯一); rate 必须 &gt; 0, 否则抛 400. 返回保存后的值. */
    BigDecimal upsertLaborHourlyRate(String factoryId, BigDecimal rate);
}
