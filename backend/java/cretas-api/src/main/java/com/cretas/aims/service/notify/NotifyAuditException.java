package com.cretas.aims.service.notify;

/**
 * Exception thrown when NotifyLog audit write fails — Phase 3 post-review fix.
 *
 * <p><b>Background</b>: Phase 3 review High #2/#3 caught silent swallow in every sender's
 * {@code writeLog()} catch block — if the DB save throws, log.error was written but the
 * exception was suppressed. Result: senders return {@code NotifyStatus.SENT} while 0 audit
 * rows hit the DB. Operations team has no way to detect this drift.
 *
 * <p><b>Fix</b>: All 5 senders ({@link com.cretas.aims.service.notify.impl.InAppSender},
 * {@link com.cretas.aims.service.notify.impl.EmailSender},
 * {@link com.cretas.aims.service.notify.impl.WeChatSender},
 * {@link com.cretas.aims.service.notify.impl.DingTalkSender},
 * {@link com.cretas.aims.service.notify.impl.SmsSender}) now {@code throw} this exception
 * instead of swallowing. {@link NotifySenderRegistry#sendAll} catches it at the channel
 * boundary, marks that channel FAILED with a clear ops-targeted error, and continues fan-out
 * to other channels (a logging-layer failure should not domino into total notification loss).
 *
 * <p><b>User-facing message</b> follows the {@code .claude/rules/fool-proof-design.md} 4-体
 * pattern: specific cause + next action ("请联系运维").
 *
 * @since 2026-05-19 (Phase 3 post-review fix)
 */
public class NotifyAuditException extends RuntimeException {

    public NotifyAuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
