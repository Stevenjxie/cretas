package com.cretas.aims.entity.hr.enums;

/**
 * 个税专项附加扣除类型 (China 个税 6 大专项附加扣除).
 *
 * <p>每项扣除有月度固定额度上限 (MVP 用 user 输入实际值, 不强制上限):
 * <ul>
 *   <li>{@link #CHILD_EDUCATION} 子女教育 — ¥1000/月/孩 (3岁起)</li>
 *   <li>{@link #CONTINUING_EDUCATION} 继续教育 — ¥400/月 (学历) / ¥3600/年 (职业资格)</li>
 *   <li>{@link #SERIOUS_ILLNESS} 大病医疗 — 自付 ≥¥15000/年, 限额 ¥80000/年 (年度汇算扣)</li>
 *   <li>{@link #HOUSING_LOAN} 住房贷款利息 — ¥1000/月 (首套, 最长 20 年)</li>
 *   <li>{@link #HOUSING_RENT} 住房租金 — ¥800/¥1100/¥1500 (按城市档位, MVP 由 user 输入)</li>
 *   <li>{@link #ELDER_SUPPORT} 赡养老人 — 独生子女 ¥2000/月, 非独生兄弟姐妹分摊</li>
 * </ul>
 *
 * <p>计税公式: taxable_income = base - 个人社保 - 个人公积金 - 5000起征点 - 专项附加扣除总额
 *
 * @author Cretas Team — P1-40 H-WAGE 专项扣除 follow-up
 * @since 2026-05-17
 */
public enum DeductionType {
    /** 子女教育 */
    CHILD_EDUCATION,

    /** 继续教育 */
    CONTINUING_EDUCATION,

    /** 大病医疗 */
    SERIOUS_ILLNESS,

    /** 住房贷款利息 */
    HOUSING_LOAN,

    /** 住房租金 */
    HOUSING_RENT,

    /** 赡养老人 */
    ELDER_SUPPORT
}
