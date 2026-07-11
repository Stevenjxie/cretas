package com.cretas.aims.event;

import java.util.UUID;

/**
 * 发布于 {@code AlertEngineServiceImpl.triggerAlert} 成功落库一条
 * {@link com.cretas.aims.entity.alerts.AlertEvent} 之后 — 通知派发解耦触发器.
 *
 * <p>补齐 Phase 2 Canvas-Alerts skeleton 遗留的通知派发缺口 (原 javadoc:
 * "Sister chat (Phase 2 B-2/B-3): 通知派发", 一直未实施). 通用于所有
 * {@link com.cretas.aims.entity.alerts.AlertType}, 不仅限于餐饮经营体检.
 *
 * @param alertEventId 新创建的 AlertEvent id
 * @since 2026-07-11 (餐饮经营体检预警推送)
 */
public record AlertEventCreatedEvent(UUID alertEventId) {
}
