package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.RestaurantVisit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 餐饮 CRM 生命周期 + 营销员归属服务（#59 Phase 1）。
 *
 * <p>邓总模型（原话）：散客首次登记<b>不计业绩</b> → 营销员维护（进包厢/9折/赠果盘/2瓶啤酒权限）
 * → <b>第二次复购才计业绩</b>（归属维护的营销员）→ 重点客户（来 3 次+）<b>必须进包厢</b>；
 * 自助查「即将流失」「重点客户」。</p>
 *
 * <p>⚠️ Phase 2（阶梯提成计算）DEFERRED：本服务在 {@code recordVisit} 计业绩时<b>发布</b>
 * {@link com.cretas.aims.event.RestaurantVisitAttributedEvent}，但<b>不建</b>提成监听器
 * （邓总精确阶梯费率尚未确定）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
public interface RestaurantCrmService {

    // ==================== 登记 ====================

    /**
     * 登记散客（幂等：同 factoryId + phone 已存在则抛 409）。
     *
     * @param factoryId 工厂 ID
     * @param guest     待登记客户（name / phone / perkConfig 等）
     * @param userId    登记人
     * @return 已保存客户（lifecycle = NEW，visitCount = 0）
     */
    RestaurantGuest registerGuest(String factoryId, RestaurantGuest guest, Long userId);

    // ==================== 到访 ====================

    /**
     * 记录一次到访：
     * <ul>
     *   <li>visit_count + 1，visit_number = 新的 visit_count</li>
     *   <li>is_qualifying = (visit_number &gt;= 2)（首次不计业绩，第 2 次起计）</li>
     *   <li>自动推进生命周期：1 → ACTIVE，2 → RECURRING，3+ → VIP</li>
     *   <li>repId 快照写入到访记录（到访时刻的 guest.rep_id，非后续换绑值）</li>
     *   <li>计业绩时发布 {@link com.cretas.aims.event.RestaurantVisitAttributedEvent}</li>
     * </ul>
     *
     * <p>去重（防呆 Rule 4）：同 guest_id + visit_at 已有记录 → 抛 409。</p>
     *
     * @param factoryId    工厂 ID
     * @param guestId      客户 ID
     * @param spendAmount  本次消费金额（可空）
     * @param channel      到访渠道（WALK_IN / RESERVATION / DELIVERY，可空）
     * @param tableId      桌台/包厢（可空）
     * @param notes        备注（可空）
     * @param userId       录单人
     * @return 已保存到访记录
     */
    RestaurantVisit recordVisit(String factoryId, String guestId, BigDecimal spendAmount,
                                String channel, String tableId, String notes, Long userId);

    /**
     * 到访前预显边界（防呆 Rule 1 + Rule 2）：返回该客户已到访次数、本次第几次、是否计业绩、
     * 客户名/手机后4位/绑定营销员、是否未绑营销员告警等，供前端 dialog 打开时即显。
     *
     * @return Map：guestName / phoneMasked / visitCount / nextVisitNumber / nextQualifying /
     *              repId / repBound / lifecycleStage / vipBoxRoomRequired 等
     */
    Map<String, Object> getVisitLimits(String factoryId, String guestId);

    // ==================== 权限/营销员 ====================

    /** 更新营销员权限配置（进包厢/折扣/赠果盘/啤酒瓶数）。 */
    RestaurantGuest updatePerks(String factoryId, String guestId, Map<String, Object> perkConfig);

    /**
     * 绑定/换绑营销员。
     *
     * <p>⚠️ 只改 guest.rep_id（当前归属），<b>不</b>回写历史到访的 rep_id 快照，
     * 保证过去到访的业绩归属不变。</p>
     */
    RestaurantGuest bindRep(String factoryId, String guestId, Long repId);

    // ==================== 查询 ====================

    /** 客户详情（含到访历史）。 */
    Map<String, Object> getGuestDetail(String factoryId, String guestId, boolean unmaskPhone);

    /** 重点客户（到访 3 次+）。 */
    List<RestaurantGuest> getVipGuests(String factoryId);

    /**
     * 即将流失客户（默认 30 天未到访视为即将流失，90 天为已流失）。
     *
     * @param thresholdDays 即将流失阈值天数（默认 30）
     */
    List<RestaurantGuest> getAtRiskGuests(String factoryId, int thresholdDays);
}
