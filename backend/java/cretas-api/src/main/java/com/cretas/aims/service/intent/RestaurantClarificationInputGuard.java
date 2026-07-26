package com.cretas.aims.service.intent;

import java.util.regex.Pattern;

/**
 * Identifies restaurant clarification replies whose meaning depends on the
 * current session. These replies must not be decided by a session-blind
 * matcher or cache before the conversation-aware recognition path gets a
 * chance to inherit the prior read-only intent.
 */
public final class RestaurantClarificationInputGuard {

    private static final Pattern CLARIFICATION_ANSWER = Pattern.compile(
            "^(?:(?:本月|这个月|当月|上月|上个月|最近7天|近7天|过去7天|"
                    + "最近30天|近30天|过去30天|今天|今日|昨天|昨日|前天|"
                    + "本周|这周|上周|今年|本年|去年|上一年)"
                    + "(?:[,，、]?(?:全部门店|所有门店|全部店|所有店))?"
                    + "|(?:全部门店|所有门店|全部店|所有店))"
                    + "(?:呢|吗|呀|啊|吧|？|\\?)?$"
    );

    private RestaurantClarificationInputGuard() {
    }

    public static boolean requiresSessionAwareRecognition(
            String userInput, String businessDomain, String sessionId) {
        if (!"RESTAURANT".equalsIgnoreCase(businessDomain)
                || sessionId == null || sessionId.isBlank()
                || userInput == null || userInput.isBlank()) {
            return false;
        }
        String compactInput = userInput.replaceAll("\\s+", "");
        return CLARIFICATION_ANSWER.matcher(compactInput).matches();
    }
}
