package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.RestaurantVisit;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.cretas.aims.event.RestaurantVisitAttributedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.RestaurantGuestRepository;
import com.cretas.aims.repository.restaurant.RestaurantVisitRepository;
import com.cretas.aims.service.restaurant.impl.RestaurantCrmServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RestaurantCrmServiceImpl} 单元测试（#59 Phase 1）。
 *
 * <p>覆盖邓总模型核心规则：</p>
 * <ol>
 *   <li>registerGuest — 同 factoryId+phone 重复 → 409（Rule 4 幂等）</li>
 *   <li>recordVisit — 首次到访 visit_number=1，is_qualifying=false（首次不计业绩），不发事件</li>
 *   <li>recordVisit — 第二次到访 visit_number=2，is_qualifying=true，发布 attributed 事件</li>
 *   <li>recordVisit — 自动推进生命周期 1→ACTIVE，2→RECURRING，3→VIP</li>
 *   <li>recordVisit — repId 快照取到访时 guest.rep_id（非后续换绑值）</li>
 *   <li>recordVisit — 同 guest_id+visit_at 重复 → 409（Rule 4 去重）</li>
 *   <li>getVipGuests — 透传 repo findVipGuests</li>
 *   <li>getAtRiskGuests — 用阈值天数算 thresholdDate</li>
 *   <li>bindRep — 只改 guest.rep_id，不回写历史到访（业绩归属不变）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantCrmServiceImpl 单元测试")
class RestaurantCrmServiceTest {

    @Mock RestaurantGuestRepository guestRepository;
    @Mock RestaurantVisitRepository visitRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks RestaurantCrmServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final Long USER = 1L;

    private RestaurantGuest guest(String id, int visitCount, Long repId, RestaurantGuestLifecycle stage) {
        RestaurantGuest g = new RestaurantGuest();
        g.setId(id);
        g.setFactoryId(FACTORY);
        g.setName("张三");
        g.setPhone("13800001234");
        g.setVisitCount(visitCount);
        g.setRepId(repId);
        g.setLifecycleStage(stage);
        return g;
    }

    // ---- 1. register dedup ----
    @Test
    @DisplayName("registerGuest 同手机号重复抛 409")
    void registerGuest_duplicatePhone_returns409() {
        RestaurantGuest input = new RestaurantGuest();
        input.setPhone("13800001234");
        input.setName("张三");
        when(guestRepository.findByFactoryIdAndPhone(FACTORY, "13800001234"))
                .thenReturn(Optional.of(guest("G-EXIST", 2, 7L, RestaurantGuestLifecycle.RECURRING)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.registerGuest(FACTORY, input, USER));
        assertEquals(409, ex.getCode());
        assertEquals("G-EXIST", ex.getHintTarget());
        verify(guestRepository, never()).save(any());
    }

    @Test
    @DisplayName("registerGuest 新客户保存为 NEW + visitCount 0")
    void registerGuest_new_savesAsNew() {
        RestaurantGuest input = new RestaurantGuest();
        input.setPhone("13900005678");
        input.setName("李四");
        when(guestRepository.findByFactoryIdAndPhone(FACTORY, "13900005678")).thenReturn(Optional.empty());
        when(guestRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        RestaurantGuest saved = service.registerGuest(FACTORY, input, USER);
        assertEquals(RestaurantGuestLifecycle.NEW, saved.getLifecycleStage());
        assertEquals(0, saved.getVisitCount());
        assertEquals(FACTORY, saved.getFactoryId());
        assertEquals(USER, saved.getCreatedBy());
        assertNull(saved.getId(), "id 由 @PrePersist 生成，service 不应预置");
    }

    // ---- 2. first visit not qualifying ----
    @Test
    @DisplayName("recordVisit 首次到访 visit_number=1 不计业绩不发事件")
    void recordVisit_firstVisit_notQualifying() {
        RestaurantGuest g = guest("G1", 0, 7L, RestaurantGuestLifecycle.NEW);
        when(guestRepository.findByIdAndFactoryId("G1", FACTORY)).thenReturn(Optional.of(g));
        when(visitRepository.countByGuestId("G1")).thenReturn(0L);
        when(visitRepository.findByGuestIdOrderByVisitAtDesc("G1")).thenReturn(List.of());
        when(visitRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        when(guestRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        RestaurantVisit v = service.recordVisit(FACTORY, "G1", new BigDecimal("288.00"),
                "WALK_IN", "A12", null, USER);

        assertEquals(1, v.getVisitNumber());
        assertFalse(v.getIsQualifying(), "首次到访不计业绩");
        assertEquals(7L, v.getRepId(), "repId 快照取到访时 guest.rep_id");
        assertEquals(1, g.getVisitCount());
        assertEquals(RestaurantGuestLifecycle.ACTIVE, g.getLifecycleStage());
        verify(eventPublisher, never()).publishEvent(any(RestaurantVisitAttributedEvent.class));
    }

    // ---- 3. second visit qualifying fires event ----
    @Test
    @DisplayName("recordVisit 第二次到访计业绩并发布 attributed 事件")
    void recordVisit_secondVisit_qualifyingFiresEvent() {
        RestaurantGuest g = guest("G2", 1, 7L, RestaurantGuestLifecycle.ACTIVE);
        when(guestRepository.findByIdAndFactoryId("G2", FACTORY)).thenReturn(Optional.of(g));
        when(visitRepository.countByGuestId("G2")).thenReturn(1L);
        when(visitRepository.findByGuestIdOrderByVisitAtDesc("G2")).thenReturn(List.of());
        when(visitRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        when(guestRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        RestaurantVisit v = service.recordVisit(FACTORY, "G2", new BigDecimal("520.00"),
                "RESERVATION", "VIP1", null, USER);

        assertEquals(2, v.getVisitNumber());
        assertTrue(v.getIsQualifying(), "第二次复购计业绩");
        assertEquals(RestaurantGuestLifecycle.RECURRING, g.getLifecycleStage());

        ArgumentCaptor<RestaurantVisitAttributedEvent> cap =
                ArgumentCaptor.forClass(RestaurantVisitAttributedEvent.class);
        verify(eventPublisher).publishEvent(cap.capture());
        RestaurantVisitAttributedEvent ev = cap.getValue();
        assertEquals(FACTORY, ev.getFactoryId());
        assertEquals("G2", ev.getGuestId());
        assertEquals(7L, ev.getRepId(), "业绩归属到访时快照营销员");
        assertEquals(new BigDecimal("520.00"), ev.getVisitRevenue());
    }

    // ---- 4. third visit -> VIP ----
    @Test
    @DisplayName("recordVisit 第三次到访推进 VIP")
    void recordVisit_thirdVisit_promotesVip() {
        RestaurantGuest g = guest("G3", 2, 7L, RestaurantGuestLifecycle.RECURRING);
        when(guestRepository.findByIdAndFactoryId("G3", FACTORY)).thenReturn(Optional.of(g));
        when(visitRepository.countByGuestId("G3")).thenReturn(2L);
        when(visitRepository.findByGuestIdOrderByVisitAtDesc("G3")).thenReturn(List.of());
        when(visitRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        when(guestRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        RestaurantVisit v = service.recordVisit(FACTORY, "G3", new BigDecimal("888.00"),
                "WALK_IN", null, null, USER);

        assertEquals(3, v.getVisitNumber());
        assertTrue(v.getIsQualifying());
        assertEquals(RestaurantGuestLifecycle.VIP, g.getLifecycleStage());
        verify(eventPublisher).publishEvent(any(RestaurantVisitAttributedEvent.class));
    }

    // ---- 6. visit dedup 409 ----
    @Test
    @DisplayName("recordVisit 同 guest+visit_at 重复抛 409")
    void recordVisit_duplicateVisitAt_returns409() {
        RestaurantGuest g = guest("G4", 1, 7L, RestaurantGuestLifecycle.ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        RestaurantVisit existing = new RestaurantVisit();
        existing.setId("V-EXIST");
        existing.setGuestId("G4");
        existing.setVisitAt(now);
        when(guestRepository.findByIdAndFactoryId("G4", FACTORY)).thenReturn(Optional.of(g));
        // 已有相同 visit_at 的到访 → 去重命中（在 countByGuestId 之前短路，故不 stub count）
        when(visitRepository.findByGuestIdOrderByVisitAtDesc("G4")).thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordVisitAt(FACTORY, "G4", now, new BigDecimal("100.00"),
                        "WALK_IN", null, null, USER));
        assertEquals(409, ex.getCode());
        verify(visitRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- 7. vip query ----
    @Test
    @DisplayName("getVipGuests 透传 repo")
    void getVipGuests_delegates() {
        List<RestaurantGuest> vips = List.of(guest("G5", 5, 7L, RestaurantGuestLifecycle.VIP));
        when(guestRepository.findVipGuests(FACTORY)).thenReturn(vips);
        assertEquals(1, service.getVipGuests(FACTORY).size());
        verify(guestRepository).findVipGuests(FACTORY);
    }

    // ---- 8. at-risk query uses threshold ----
    @Test
    @DisplayName("getAtRiskGuests 用阈值天数算 thresholdDate")
    void getAtRiskGuests_usesThreshold() {
        when(guestRepository.findAtRiskGuests(eq(FACTORY), any(LocalDateTime.class))).thenReturn(List.of());
        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        service.getAtRiskGuests(FACTORY, 30);
        verify(guestRepository).findAtRiskGuests(eq(FACTORY), cap.capture());
        LocalDateTime threshold = cap.getValue();
        // threshold 应在 ~30 天前 (容忍秒级误差)
        long days = java.time.Duration.between(threshold, LocalDateTime.now()).toDays();
        assertTrue(days >= 29 && days <= 31, "thresholdDate 应约为 30 天前，实际 " + days + " 天");
    }

    // ---- 9. bindRep doesn't change past visit attribution ----
    @Test
    @DisplayName("bindRep 只改 guest.rep_id 不回写历史到访")
    void bindRep_doesNotRewritePastVisits() {
        RestaurantGuest g = guest("G6", 2, 7L, RestaurantGuestLifecycle.RECURRING);
        when(guestRepository.findByIdAndFactoryId("G6", FACTORY)).thenReturn(Optional.of(g));
        when(guestRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        RestaurantGuest updated = service.bindRep(FACTORY, "G6", 99L);

        assertEquals(99L, updated.getRepId());
        assertNotNull(updated.getRepBoundAt());
        // 不应触碰到访记录（业绩归属用快照，换绑不影响历史）
        verify(visitRepository, never()).save(any());
        verify(visitRepository, never()).findByGuestIdOrderByVisitAtDesc(anyString());
    }

    // ---- getVisitLimits pre-show (Rule 1 + Rule 2) ----
    @Test
    @DisplayName("getVisitLimits 预显已到访次数+本次第N+1次+计业绩提示")
    void getVisitLimits_preShow() {
        RestaurantGuest g = guest("G7", 1, 7L, RestaurantGuestLifecycle.ACTIVE);
        when(guestRepository.findByIdAndFactoryId("G7", FACTORY)).thenReturn(Optional.of(g));

        var limits = service.getVisitLimits(FACTORY, "G7");
        assertEquals(1, limits.get("visitCount"));
        assertEquals(2, limits.get("nextVisitNumber"));
        assertEquals(true, limits.get("nextQualifying"), "下一次（第2次）计业绩");
        assertEquals(true, limits.get("repBound"));
        // 手机后4位脱敏
        assertEquals("1234", limits.get("phoneLast4"));
    }
}
