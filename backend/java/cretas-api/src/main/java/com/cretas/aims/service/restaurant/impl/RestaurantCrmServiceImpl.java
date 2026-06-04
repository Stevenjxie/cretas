package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.RestaurantVisit;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.cretas.aims.event.RestaurantVisitAttributedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.RestaurantGuestRepository;
import com.cretas.aims.repository.restaurant.RestaurantVisitRepository;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 餐饮 CRM 生命周期 + 营销员归属服务实现（#59 Phase 1）。
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantCrmServiceImpl implements RestaurantCrmService {

    private final RestaurantGuestRepository guestRepository;
    private final RestaurantVisitRepository visitRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 即将流失默认阈值天数。 */
    private static final int DEFAULT_AT_RISK_DAYS = 30;
    /** 已流失阈值天数（computed-on-read，仅用于详情展示标注）。 */
    private static final int CHURNED_DAYS = 90;

    // ==================== 登记 ====================

    @Override
    @Transactional
    public RestaurantGuest registerGuest(String factoryId, RestaurantGuest guest, Long userId) {
        if (guest == null) {
            throw new BusinessException(400, "客户信息不能为空");
        }
        String phone = guest.getPhone() != null ? guest.getPhone().trim() : null;

        // 防呆 Rule 4：同 factoryId + phone 幂等去重
        if (StringUtils.hasText(phone)) {
            Optional<RestaurantGuest> existing = guestRepository.findByFactoryIdAndPhone(factoryId, phone);
            if (existing.isPresent()) {
                throw new BusinessException(409,
                        "该手机号已登记：" + maskPhone(phone) + "（" + safeName(existing.get()) + "）")
                        .withCode("DUPLICATE")
                        .withHint("该客户已存在，是否直接查看其档案 / 记录到访？")
                        .withSeverity("warning")
                        .withHintTarget(existing.get().getId());
            }
        }

        guest.setId(null);
        guest.setFactoryId(factoryId);
        guest.setPhone(phone);
        guest.setVisitCount(0);
        guest.setLifecycleStage(RestaurantGuestLifecycle.NEW);
        guest.setCreatedBy(userId);
        // 散客可能尚无营销员维护；绑定时间随 repId 设置
        if (guest.getRepId() != null && guest.getRepBoundAt() == null) {
            guest.setRepBoundAt(LocalDateTime.now());
        }
        RestaurantGuest saved = guestRepository.save(guest);
        log.info("登记散客: factoryId={}, name={}, phone={}, repId={}",
                factoryId, safeName(saved), maskPhone(phone), saved.getRepId());
        return saved;
    }

    // ==================== 到访 ====================

    @Override
    @Transactional
    public RestaurantVisit recordVisit(String factoryId, String guestId, BigDecimal spendAmount,
                                       String channel, String tableId, String notes, Long userId) {
        return recordVisitAt(factoryId, guestId, LocalDateTime.now(), spendAmount, channel, tableId, notes, userId);
    }

    /**
     * 内部：以显式 visit_at 记录到访（便于去重判断 + 测试确定性）。
     */
    @Transactional
    public RestaurantVisit recordVisitAt(String factoryId, String guestId, LocalDateTime visitAt,
                                         BigDecimal spendAmount, String channel, String tableId,
                                         String notes, Long userId) {
        RestaurantGuest guest = guestRepository.findByIdAndFactoryId(guestId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("散客", "id", guestId));

        // 防呆 Rule 4：同 guest + visit_at 去重（避免重复点"记录到访"建多条）
        List<RestaurantVisit> history = visitRepository.findByGuestIdOrderByVisitAtDesc(guestId);
        boolean dup = history.stream()
                .anyMatch(v -> v.getVisitAt() != null && v.getVisitAt().equals(visitAt));
        if (dup) {
            throw new BusinessException(409, "该客户在此时间已有到访记录，请勿重复录入")
                    .withCode("DUPLICATE")
                    .withHint("如需登记新一次到访，请确认到访时间不同")
                    .withSeverity("warning");
        }

        long priorCount = visitRepository.countByGuestId(guestId);
        int visitNumber = (int) priorCount + 1;
        boolean qualifying = visitNumber >= 2;  // 首次不计业绩，第 2 次复购起计

        // repId 快照：到访时刻的 guest.rep_id（换绑后不回写历史）
        Long repSnapshot = guest.getRepId();

        RestaurantVisit visit = new RestaurantVisit();
        visit.setId(null);
        visit.setFactoryId(factoryId);
        visit.setGuestId(guestId);
        visit.setRepId(repSnapshot);
        visit.setVisitNumber(visitNumber);
        visit.setVisitAt(visitAt);
        visit.setSpendAmount(spendAmount);
        visit.setChannel(normalizeChannel(channel));
        visit.setTableId(tableId);
        visit.setIsQualifying(qualifying);
        visit.setNotes(notes);
        visit.setCreatedBy(userId);
        RestaurantVisit savedVisit = visitRepository.save(visit);

        // 更新 guest 统计 + 推进生命周期
        guest.setVisitCount(visitNumber);
        if (guest.getFirstVisitAt() == null) {
            guest.setFirstVisitAt(visitAt);
        }
        guest.setLastVisitAt(visitAt);
        guest.setLifecycleStage(advanceLifecycle(visitNumber));
        guestRepository.save(guest);

        // 计业绩 → 发布事件（Phase 2 提成监听器另建，本期只发布）
        if (qualifying) {
            try {
                eventPublisher.publishEvent(new RestaurantVisitAttributedEvent(
                        this, factoryId, guestId, savedVisit.getId(), repSnapshot,
                        spendAmount != null ? spendAmount : BigDecimal.ZERO));
            } catch (Exception e) {
                log.warn("发布 RestaurantVisitAttributedEvent 失败 (不影响到访记录): {}", e.getMessage());
            }
        }

        log.info("记录到访: factoryId={}, guestId={}, visitNumber={}, qualifying={}, repSnapshot={}, stage={}",
                factoryId, guestId, visitNumber, qualifying, repSnapshot, guest.getLifecycleStage());
        return savedVisit;
    }

    @Override
    public Map<String, Object> getVisitLimits(String factoryId, String guestId) {
        RestaurantGuest guest = guestRepository.findByIdAndFactoryId(guestId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("散客", "id", guestId));
        int visitCount = guest.getVisitCount() != null ? guest.getVisitCount() : 0;
        int nextVisitNumber = visitCount + 1;
        boolean nextQualifying = nextVisitNumber >= 2;
        boolean repBound = guest.getRepId() != null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guestId", guestId);
        m.put("guestName", safeName(guest));
        m.put("phoneLast4", phoneLast4(guest.getPhone()));
        m.put("visitCount", visitCount);
        m.put("nextVisitNumber", nextVisitNumber);
        m.put("nextQualifying", nextQualifying);
        m.put("repId", guest.getRepId());
        m.put("repBound", repBound);
        m.put("lifecycleStage", guest.getLifecycleStage());
        // 防呆 Rule 2：未绑营销员告警
        if (!repBound) {
            m.put("repWarning", "该客户尚未绑定营销员，复购业绩将无人归属，建议先绑定营销员");
        }
        // 防呆 Rule 5：VIP 无包厢配置 → 应安排包厢
        m.put("vipBoxRoomRequired", computeVipBoxRoomRequired(guest));
        // 防呆 Rule 1：预显本次性质（首次不计 / 复购计业绩）
        m.put("preShowMessage", String.format("该客户已到访 %d 次，本次为第 %d 次%s",
                visitCount, nextVisitNumber, nextQualifying ? "（复购，计业绩）" : "（首次，不计业绩）"));
        return m;
    }

    // ==================== 权限/营销员 ====================

    @Override
    @Transactional
    public RestaurantGuest updatePerks(String factoryId, String guestId, Map<String, Object> perkConfig) {
        RestaurantGuest guest = guestRepository.findByIdAndFactoryId(guestId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("散客", "id", guestId));
        validatePerkConfig(perkConfig);
        guest.setPerkConfig(perkConfig);
        return guestRepository.save(guest);
    }

    @Override
    @Transactional
    public RestaurantGuest bindRep(String factoryId, String guestId, Long repId) {
        RestaurantGuest guest = guestRepository.findByIdAndFactoryId(guestId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("散客", "id", guestId));
        // 只改当前归属，不回写历史到访的 rep_id 快照（业绩归属不变）
        guest.setRepId(repId);
        guest.setRepBoundAt(LocalDateTime.now());
        RestaurantGuest saved = guestRepository.save(guest);
        log.info("绑定营销员: factoryId={}, guestId={}, repId={}", factoryId, guestId, repId);
        return saved;
    }

    // ==================== 查询 ====================

    @Override
    public Map<String, Object> getGuestDetail(String factoryId, String guestId, boolean unmaskPhone) {
        RestaurantGuest guest = guestRepository.findByIdAndFactoryId(guestId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("散客", "id", guestId));
        List<RestaurantVisit> visits = visitRepository.findByGuestIdOrderByVisitAtDesc(guestId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", guest.getId());
        m.put("name", safeName(guest));
        m.put("phone", unmaskPhone ? guest.getPhone() : maskPhone(guest.getPhone()));
        m.put("repId", guest.getRepId());
        m.put("repBoundAt", guest.getRepBoundAt());
        m.put("visitCount", guest.getVisitCount());
        m.put("firstVisitAt", guest.getFirstVisitAt());
        m.put("lastVisitAt", guest.getLastVisitAt());
        m.put("perkConfig", guest.getPerkConfig());
        // computed-on-read 流失态标注（不持久化）
        m.put("lifecycleStage", computeDisplayLifecycle(guest));
        m.put("vipBoxRoomRequired", computeVipBoxRoomRequired(guest));
        m.put("notes", guest.getNotes());
        m.put("visits", visits);
        return m;
    }

    @Override
    public List<RestaurantGuest> getVipGuests(String factoryId) {
        return guestRepository.findVipGuests(factoryId);
    }

    @Override
    public List<RestaurantGuest> getAtRiskGuests(String factoryId, int thresholdDays) {
        int days = thresholdDays > 0 ? thresholdDays : DEFAULT_AT_RISK_DAYS;
        LocalDateTime thresholdDate = LocalDateTime.now().minusDays(days);
        return guestRepository.findAtRiskGuests(factoryId, thresholdDate);
    }

    // ==================== 内部辅助 ====================

    /** 生命周期推进：1 → ACTIVE，2 → RECURRING，3+ → VIP。 */
    private RestaurantGuestLifecycle advanceLifecycle(int visitNumber) {
        if (visitNumber >= 3) return RestaurantGuestLifecycle.VIP;
        if (visitNumber == 2) return RestaurantGuestLifecycle.RECURRING;
        return RestaurantGuestLifecycle.ACTIVE;
    }

    /** 详情展示用：基于 last_visit_at computed-on-read 流失/即将流失态。 */
    private RestaurantGuestLifecycle computeDisplayLifecycle(RestaurantGuest g) {
        if (g.getLastVisitAt() == null) {
            return g.getLifecycleStage();
        }
        long idleDays = Duration.between(g.getLastVisitAt(), LocalDateTime.now()).toDays();
        if (idleDays >= CHURNED_DAYS) return RestaurantGuestLifecycle.CHURNED;
        if (idleDays >= DEFAULT_AT_RISK_DAYS) return RestaurantGuestLifecycle.AT_RISK;
        return g.getLifecycleStage();
    }

    /** 防呆 Rule 5：VIP（来 3 次+）但权限配置无包厢 → 提示应安排包厢。 */
    private boolean computeVipBoxRoomRequired(RestaurantGuest g) {
        boolean isVip = g.getVisitCount() != null && g.getVisitCount() >= 3;
        if (!isVip) return false;
        Map<String, Object> perk = g.getPerkConfig();
        Object box = perk != null ? perk.get("boxRoom") : null;
        boolean hasBox = box instanceof Boolean && (Boolean) box;
        return !hasBox;  // VIP 但未配包厢 → required = true（橙色徽章提示）
    }

    /** 防呆 Rule 3：权限配置约束校验（折扣 1-100，啤酒 0-6）。 */
    private void validatePerkConfig(Map<String, Object> perk) {
        if (perk == null) return;
        Object discount = perk.get("discountPct");
        if (discount instanceof Number) {
            int d = ((Number) discount).intValue();
            if (d < 1 || d > 100) {
                throw new BusinessException(400, "折扣百分比必须在 1-100 之间 (当前 " + d + ")")
                        .withCode("RULE_VIOLATION")
                        .withSeverity("warning")
                        .withHintTarget("discountPct");
            }
        }
        Object beer = perk.get("beerBottles");
        if (beer instanceof Number) {
            int b = ((Number) beer).intValue();
            if (b < 0 || b > 6) {
                throw new BusinessException(400, "赠送啤酒瓶数必须在 0-6 之间 (当前 " + b + ")")
                        .withCode("RULE_VIOLATION")
                        .withSeverity("warning")
                        .withHintTarget("beerBottles");
            }
        }
    }

    /** 渠道归一化（容忍 null/空，非法值保留原文供调试）。 */
    private String normalizeChannel(String channel) {
        if (!StringUtils.hasText(channel)) return null;
        return channel.trim().toUpperCase(Locale.ROOT);
    }

    private String safeName(RestaurantGuest g) {
        return g != null && StringUtils.hasText(g.getName()) ? g.getName() : "未具名客户";
    }

    /** 手机号脱敏为前 3 后 4（如 138****1234）。 */
    static String maskPhone(String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        if (p.length() <= 4) return p;
        if (p.length() <= 7) return "****" + p.substring(p.length() - 4);
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    /** 仅后 4 位（用于 limits 展示）。 */
    static String phoneLast4(String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        return p.length() <= 4 ? p : p.substring(p.length() - 4);
    }
}
