package com.cretas.aims.util;

import com.cretas.aims.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 补录时效锁 — 防呆硬规则 (C-074/C-075/X-10, 六扇门客户原话"大前天不允许补")。
 *
 * <p>业务日期补录窗口: T(今天) / T-1(昨天) / T-2(前天) 允许补录; T-3 及更早一律拒绝。
 * 窗口天数由 {@code cretas.backdate.max-days} 控制, 默认 2 (= 允许到 T-2)。
 *
 * <p>使用方:
 * <pre>
 *   backdateWindowValidator.assertWithinWindow(businessDate, "补录入库");
 * </pre>
 *
 * <p>拒绝行为 (防呆 4 位一体):
 * <ul>
 *   <li>HTTP 409 + errorCode {@code BACKDATE_WINDOW_EXCEEDED}</li>
 *   <li>message 含具体日期与最早可补边界</li>
 *   <li>actionHint 告知联系主管处理</li>
 * </ul>
 *
 * <p>日期为 null / 今天 / 未来 → 不拦截 (未来日期合法性不属本 validator scope)。
 */
@Component
public class BackdateWindowValidator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 最多允许补录的天数: 2 = T-2(前天); 1 = T-1(昨天); 0 = 仅今天。
     * 配置键: {@code cretas.backdate.max-days}, 默认 2。
     */
    @Value("${cretas.backdate.max-days:2}")
    private int maxDays;

    /**
     * 断言 businessDate 在允许的补录窗口内。
     *
     * @param businessDate 业务日期 (可为 null; null = 不校验, 走当日逻辑)
     * @param context      错误信息中的业务场景说明, 如 "报工" / "原料入库"
     * @throws BusinessException 409 BACKDATE_WINDOW_EXCEEDED 当日期超出窗口
     */
    public void assertWithinWindow(LocalDate businessDate, String context) {
        assertWithinWindow(businessDate, context, LocalDate.now());
    }

    /**
     * 可注入 today 的测试友好重载 (生产代码请用 assertWithinWindow(date, context))。
     */
    public void assertWithinWindow(LocalDate businessDate, String context, LocalDate today) {
        if (businessDate == null) {
            return; // null = 系统取当天, 不拦
        }
        if (!businessDate.isBefore(today)) {
            return; // 今天或未来 — 不拦 (未来合法性不属本 scope)
        }
        LocalDate earliest = today.minusDays(maxDays);
        if (businessDate.isBefore(earliest)) {
            String dateStr = businessDate.format(DATE_FMT);
            String earliestStr = earliest.format(DATE_FMT);
            throw new BusinessException(409,
                    context + "业务日期 " + dateStr + " 超出补录窗口"
                            + "（最早可补 " + earliestStr + "，共 " + maxDays + " 天）"
                            + "，请联系主管处理")
                    .withCode("BACKDATE_WINDOW_EXCEEDED")
                    .withHint("如需补录更早日期，请联系主管在 ERP 后台处理")
                    .withHintTarget("businessDate");
        }
    }

    /** 仅供测试覆盖: 获取当前配置的 maxDays 值。 */
    public int getMaxDays() {
        return maxDays;
    }
}
