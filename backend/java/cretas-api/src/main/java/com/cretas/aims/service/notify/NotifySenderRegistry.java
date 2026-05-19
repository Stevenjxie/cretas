package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Notification sender registry & dispatcher — Phase 3 Canvas-Notify Step T6.
 *
 * <p>Spring 自动收集所有 {@link NotifySender} 实现 (via {@code List<NotifySender>} injection).
 * 给定 {@link NotifyRequest}, 按 channel fan-out 到对应 sender, 收集 {@link NotifyResult}.
 *
 * <p>使用方:
 * <ul>
 *   <li>{@code NotifySendTool.doExecute} — AI Tool 测试发送</li>
 *   <li>Phase 1 Workflow {@code NotifyNodeHandler} (待 follow-up issue) — 工作流 notify 节点</li>
 *   <li>业务事件 listener — 如审批通过自动通知 (Phase 3 follow-up)</li>
 * </ul>
 *
 * <p>fool-proof: 不支持的 channel 写 FAILED log + 返 FAILED result, 不阻塞其他 channel 的 fan-out.
 *
 * @since 2026-05-18 (Phase 3 impl)
 */
@Slf4j
@Component
public class NotifySenderRegistry {

    @Autowired
    private List<NotifySender> senders;

    /**
     * Fan-out 发送: 对每个 channel 找匹配 sender 调用 send().
     *
     * @param request 通知请求 (含 channels list)
     * @return 每个 channel 一条 NotifyResult, 顺序与 request.channels 一致
     */
    public List<NotifyResult> sendAll(NotifyRequest request) {
        if (request == null || request.channels() == null || request.channels().isEmpty()) {
            log.warn("[NotifySenderRegistry] sendAll: request.channels 为空, 返空 result list");
            return List.of();
        }

        List<NotifyResult> results = new ArrayList<>();
        for (NotifyChannel channel : request.channels()) {
            NotifySender sender = findSenderFor(channel);
            if (sender == null) {
                String errMsg = "未找到 channel=" + channel + " 的 NotifySender (检查 Spring DI 是否扫描到 impl)";
                log.error("[NotifySenderRegistry] {}", errMsg);
                results.add(new NotifyResult(channel, NotifyStatus.FAILED, errMsg));
                continue;
            }
            try {
                NotifyResult result = sender.send(request);
                results.add(result != null
                        ? result
                        : new NotifyResult(channel, NotifyStatus.FAILED, "sender.send() 返回 null"));
            } catch (NotifyAuditException e) {
                // Audit write failure (Phase 3 review High #2/#3): sender 已 log.error +
                // throw, 在此 channel 边界捕获标 FAILED, 让 errorMsg 保留 audit-specific 文案,
                // 但不阻塞其他 channel 的 fan-out (其他 channel 的 audit write 可能正常).
                log.error("[NotifySenderRegistry] channel={} audit 写入失败: {}", channel, e.getMessage(), e);
                results.add(new NotifyResult(channel, NotifyStatus.FAILED, e.getMessage()));
            } catch (Exception e) {
                String errMsg = "Sender 抛异常: " + e.getMessage();
                log.error("[NotifySenderRegistry] channel={} send 异常: {}", channel, errMsg, e);
                results.add(new NotifyResult(channel, NotifyStatus.FAILED, errMsg));
            }
        }
        return results;
    }

    /**
     * 查找支持给定 channel 的 sender.
     *
     * @param channel 通知渠道
     * @return 第一个 supports() 返 true 的 sender, 或 null
     */
    public NotifySender findSenderFor(NotifyChannel channel) {
        if (channel == null || senders == null) {
            return null;
        }
        for (NotifySender s : senders) {
            if (s.supports(channel)) {
                return s;
            }
        }
        return null;
    }
}
