package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyChannel;

import java.util.List;
import java.util.Map;

/**
 * 通知发送请求 — Phase 3 Canvas-Notify Step T4.
 *
 * <p>Fan-out shape: 1 request × N recipients × M channels = N*M sends.
 * Sister 实施时 NotifyDispatcher 拆分到 per-channel per-recipient 子任务,
 * 每完成一个写一条 {@link com.cretas.aims.entity.notify.NotifyLog}.
 *
 * @param factoryId 工厂 id (multi-tenant 隔离)
 * @param recipientUserIds 收件用户 id 列表
 * @param channels 要发送的渠道列表 (subset of NotifyTemplate.channels)
 * @param templateCode 模板 code, sender 查 NotifyTemplate 拿 title/body
 * @param params 用于 {{var}} 替换的实际参数, 例 {"amount": 1000, "poNumber": "PO-001"}
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
public record NotifyRequest(
        String factoryId,
        List<Long> recipientUserIds,
        List<NotifyChannel> channels,
        String templateCode,
        Map<String, Object> params
) {}
