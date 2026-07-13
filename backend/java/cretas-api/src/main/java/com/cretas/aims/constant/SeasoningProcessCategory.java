package com.cretas.aims.constant;

/**
 * 工序类别中标记为调味相关的值（复用 {@code WorkProcess.processCategory}）。
 *
 * <p>用于识别「熟制」「注射」等需要挂载调料配方参数（锅序比例 / 注射量）的工序类别，
 * 避免业务代码中出现裸字符串 {@code "熟制"} / {@code "注射"}。
 *
 * @since 调料配方按工序 (2026-07-13)
 */
public final class SeasoningProcessCategory {

    private SeasoningProcessCategory() {
        // utility class
    }

    /** 熟制类工序（如卤制、蒸煮）— 支持「后续锅调料比例」参数。 */
    public static final String COOKING = "熟制";

    /** 注射类工序（如盐水注射）— 支持「注射量(kg)」参数。 */
    public static final String INJECTION = "注射";
}
