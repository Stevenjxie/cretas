package com.cretas.aims.dto.intent;

import lombok.Data;

/**
 * 意图识别反馈请求
 * 用户可以纠正错误的意图识别结果，系统自动学习
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-13
 */
@Data
public class IntentFeedbackRequest {

    /** 原始用户输入 */
    private String userInput;

    /** 系统识别的意图代码 */
    private String matchedIntentCode;

    /** 用户认为正确的意图代码 */
    private String correctIntentCode;

    /** 识别是否正确 */
    private Boolean isCorrect;

    /** 会话ID（可选，用于追踪） */
    private String sessionId;

    /** 用户对回答/匹配问题的补充说明（可选） */
    private String userFeedback;
}
