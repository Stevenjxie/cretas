package com.cretas.aims.entity.restaurant.enums;

/**
 * 餐饮散客生命周期阶段。
 *
 * <p>对应 #59 邓总营销员归属模型：散客首次登记不计业绩 → 营销员维护 → 第二次复购
 * 才计业绩（归属维护的营销员）→ 重点客户（来 3 次+）必须进包厢。</p>
 *
 * <ul>
 *   <li>NEW — 首次登记（visit_count = 0，尚无任何到访记录）</li>
 *   <li>ACTIVE — 已有 1 次到访（首次到访不计业绩）</li>
 *   <li>RECURRING — 第 2 次复购（开始计业绩，归属维护营销员）</li>
 *   <li>VIP — 重点客户（来 3 次+，必须安排包厢）</li>
 *   <li>AT_RISK — 即将流失（超过流失阈值天数未到访，computed-on-read）</li>
 *   <li>CHURNED — 已流失（超过流失上限天数未到访，computed-on-read）</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
public enum RestaurantGuestLifecycle {
    NEW,
    ACTIVE,
    RECURRING,
    VIP,
    AT_RISK,
    CHURNED
}
