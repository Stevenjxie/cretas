package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantCommission;
import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 餐饮营销员月度阶梯提成服务（#59 Phase 2）。
 *
 * <p>邓总锁定方案：计提周期 = <b>月度累计</b>。营销员当月每发生一次计业绩到访
 * （{@code RestaurantVisit} visit_number &gt;= 2），累计业绩 += 本次营收；累计额跨过
 * {@code CommissionRule.tierConfig} 配置的档位（邓总 UI 自填 15万/30万/50万 → 三档费率）时档位上移。
 * 本次提成 = 本次营收 × 当前累计额所处档位费率（{@code CommissionService.resolveTier} 单一事实源）。</p>
 *
 * <p>由 {@code RestaurantCommissionEventListener} 在 {@code RestaurantVisitAttributedEvent}
 * AFTER_COMMIT 时触发结算。幂等：同一次到访（visitId）重复 fire 不重复建。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
public interface RestaurantCommissionService {

    /**
     * 对一次计业绩到访结算提成（月度累计阶梯）。
     *
     * <ul>
     *   <li>repId 为 null（散客无营销员）→ 优雅跳过，返 empty。</li>
     *   <li>periodKey = visitAt 的 'YYYY-MM'。</li>
     *   <li>upsert 营销员当月汇总：cumulativeRevenue += visitRevenue，重算 currentTier，
     *       attributedVisitCount += 1（@Version 乐观锁）。</li>
     *   <li>findApplicableRule(factoryId, repId, null, visitDate)；无规则 → 优雅跳过，返 empty。</li>
     *   <li>tierConfig 存在 → 按累计额解析档位费率；无 tierConfig → flat percentage。</li>
     *   <li>commissionAmount = visitRevenue × rate / 100（ROUND_HALF_UP scale 2）。</li>
     *   <li>幂等：visitId 已结算过 → 返已存在记录，不重复建。</li>
     * </ul>
     *
     * @return 创建或已存在的提成记录；repId 缺失 / 无规则 / 营收非正 → empty
     */
    Optional<RestaurantCommission> settleForVisit(String factoryId, String visitId, Long repId,
                                                  BigDecimal visitRevenue, LocalDateTime visitAt);

    /** 某营销员某月累计汇总（不存在返 empty）。periodKey = 'YYYY-MM'。 */
    Optional<RestaurantRepCommissionSummary> getRepSummary(String factoryId, Long repId, String periodKey);

    /** 标记提成已发放（幂等；CANCELLED 不可发放）。 */
    RestaurantCommission markPaid(String factoryId, String id);
}
