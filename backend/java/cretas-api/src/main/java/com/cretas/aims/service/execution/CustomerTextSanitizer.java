package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 客户可见文本内部泄漏消毒 (Sheet 7/22 V7) — Controller 出口单点兜底。
 *
 * <p>LLM 意图分类/rerank 的推理文本 ("在提供的可用意图列表中, 只有
 * PRODUCT_XXX…") 偶发经某些 early-return 路径直出客户端, orchestrator 内部
 * 6.9 步的消毒覆盖不到全部构造点。Controller 返回前统一兜底: 同时含意图
 * 元讨论词与 CODE 形状 token 的消息不可能是合法业务答案。
 */
@Slf4j
public final class CustomerTextSanitizer {

    private static final Pattern INTERNAL_CODE_TOKEN =
            Pattern.compile("[A-Z]{2,}_[A-Z0-9_]{2,}");

    private static final String SAFE_REPLY =
            "这个问题我还没有把握直接回答。请换一种问法，"
                    + "或说明想看的指标（如营收/销量/毛利）和时间范围，我再帮您查。";

    private CustomerTextSanitizer() {
    }

    public static IntentExecuteResponse sanitize(IntentExecuteResponse response) {
        if (response == null) {
            return null;
        }
        scrub(response.getMessage(), response::setMessage);
        scrub(response.getFormattedText(), response::setFormattedText);
        return response;
    }

    private static void scrub(String text, java.util.function.Consumer<String> setter) {
        if (text == null) {
            return;
        }
        boolean metaTalk = text.contains("意图列表") || text.contains("可用意图")
                || text.contains("候选意图") || text.contains("intent_code")
                || text.contains("该意图覆盖");
        if (metaTalk && INTERNAL_CODE_TOKEN.matcher(text).find()) {
            log.warn("[sanitize] internal intent reasoning leaked, replaced. head={}",
                    text.substring(0, Math.min(80, text.length())));
            setter.accept(SAFE_REPLY);
        }
    }
}
