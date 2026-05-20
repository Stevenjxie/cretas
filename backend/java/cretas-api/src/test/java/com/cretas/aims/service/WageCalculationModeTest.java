package com.cretas.aims.service;

import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.PieceRateRule;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.HourlyRateRuleRepository;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.PieceRateRuleRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sprint 6 Track W4-B — Unit test for {@link WageCalculationService#calculateMonthly}.
 *
 * <p>验收 spec §W4-B DOD:
 * <ul>
 *   <li>3 mode unit test pass (PIECE_RATE / HOURLY / MIXED)</li>
 *   <li>Edge case: mode change mid-month (HourlyRateRule effectiveFrom 切换)</li>
 *   <li>BigDecimal 精度 2 decimal ROUND_HALF_UP</li>
 *   <li>idempotent upsert on (factoryId, employeeId, periodMonth)</li>
 * </ul>
 *
 * <p>测试场景基于 F006 卤制品 customer:
 * <ul>
 *   <li>员工 A (按件): 完成 100 件 @ ¥5/件 = ¥500</li>
 *   <li>员工 B (按时): 160 工时 (其中 16 加班) @ ¥30/h, OT × 1.5 = 4800 + 720 = ¥5520</li>
 *   <li>员工 C (混合): 1 天 8h × ¥30 + 100 件 × ¥5 = ¥240 + ¥500 = ¥740</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WageCalculationService.calculateMonthly — Sprint 6 W4-B (按时/混合 mode)")
class WageCalculationModeTest {

    @Mock private PieceRateRuleRepository pieceRateRuleRepository;
    @Mock private PayrollRecordRepository payrollRecordRepository;
    @Mock private WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    @Mock private UserRepository userRepository;

    @Mock private WagePolicyRepository wagePolicyRepository;
    @Mock private HourlyRateRuleRepository hourlyRateRuleRepository;
    @Mock private WageCalculationRepository wageCalculationRepository;

    private WageCalculationService wageCalculationService;
    private WagePolicyService wagePolicyService;

    private static final String FACTORY_ID = "F006";
    private static final Long EMPLOYEE_ID = 42L;
    private static final YearMonth MONTH = YearMonth.of(2026, 5);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 5, 31);

    private User worker;

    @BeforeEach
    void setUp() {
        // Compose service manually (constructor + optional field injection)
        wageCalculationService = new WageCalculationService(
                pieceRateRuleRepository,
                payrollRecordRepository,
                workerDailyEfficiencyRepository,
                userRepository);
        wagePolicyService = new WagePolicyService(wagePolicyRepository, hourlyRateRuleRepository);
        ReflectionTestUtils.setField(wageCalculationService, "wagePolicyService", wagePolicyService);
        ReflectionTestUtils.setField(wageCalculationService, "wageCalculationRepository", wageCalculationRepository);
        ReflectionTestUtils.setField(wageCalculationService, "hourlyRateRuleRepository", hourlyRateRuleRepository);

        worker = new User();
        worker.setId(EMPLOYEE_ID);
        worker.setUsername("worker_42");
        worker.setFullName("张三");
    }

    private void mockUserLookup() {
        when(userRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(worker));
    }

    private void mockSaveCalc() {
        when(wageCalculationRepository.save(any(WageCalculation.class)))
                .thenAnswer(inv -> {
                    WageCalculation w = inv.getArgument(0);
                    if (w.getId() == null) w.setId(1L);
                    return w;
                });
    }

    private void mockNoExistingCalc() {
        when(wageCalculationRepository.findByFactoryIdAndEmployeeIdAndPeriodMonth(
                anyString(), anyLong(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("Mode = PIECE_RATE — 按件 (向后兼容 PR #57 E)")
    class PieceRateMode {
        @Test
        @DisplayName("100 件 @ ¥5/件 阶梯 → ¥500, hourlyAmount=0")
        void shouldCalculatePieceRateOnly_whenModeIsPieceRate() {
            mockUserLookup();
            mockSaveCalc();
            mockNoExistingCalc();
            // arrange: factory default = PIECE_RATE
            WagePolicy defaultPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).mode(WageMode.PIECE_RATE).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.empty());
            when(wagePolicyRepository.findFactoryDefault(FACTORY_ID))
                    .thenReturn(Optional.of(defaultPolicy));

            // efficiency: 1 天 100 件 @ "卤猪蹄200g"
            WorkerDailyEfficiency e1 = newEfficiency(EMPLOYEE_ID, MONTH_START, 100, 480, "卤猪蹄200g");
            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START, MONTH_END))
                    .thenReturn(List.of(e1));

            // piece rate rule: tier1 = unlimited @ ¥5/件
            PieceRateRule rule = PieceRateRule.builder()
                    .id(1L).factoryId(FACTORY_ID).processStageType("卤猪蹄200g")
                    .tier1Threshold(0).tier1Rate(new BigDecimal("5.0000")).maxTierCount(1).isActive(true)
                    .build();
            when(pieceRateRuleRepository.findEffectiveRulesByProcessStage(eq(FACTORY_ID), eq("卤猪蹄200g"), any(LocalDate.class)))
                    .thenReturn(List.of(rule));

            // act
            WageCalculation calc = wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            // assert
            assertEquals(WageMode.PIECE_RATE, calc.getMode());
            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getHourlyAmount()), "PIECE_RATE → hourlyAmount=0");
            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getOvertimeAmount()));
            assertEquals(0, new BigDecimal("500.00").compareTo(calc.getPieceRateAmount()), "100×5=500");
            assertEquals(0, new BigDecimal("500.00").compareTo(calc.getTotalAmount()), "totalAmount=¥500");
            assertEquals(100, calc.getTotalPieceCount());
            assertEquals(1L, calc.getPieceRuleId());
            assertNull(calc.getHourlyRuleId());
        }
    }

    @Nested
    @DisplayName("Mode = HOURLY — 按时")
    class HourlyMode {
        @Test
        @DisplayName("20 天 × 8h 标准 + 16h 加班 @ ¥30/h × 1.5 = ¥4800 + ¥720 = ¥5520")
        void shouldCalculateHourlyWithOvertime_whenModeIsHourly() {
            mockUserLookup();
            mockSaveCalc();
            mockNoExistingCalc();
            // arrange: per-employee policy = HOURLY
            WagePolicy empPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .mode(WageMode.HOURLY).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.of(empPolicy));

            // 20 天 × 8h 正常 + 第 20 天加 16h 加班 → 160 + 16 = 176 h
            List<WorkerDailyEfficiency> effs = new ArrayList<>();
            for (int day = 1; day <= 19; day++) {
                effs.add(newEfficiency(EMPLOYEE_ID, MONTH_START.withDayOfMonth(day), 0, 480, null));
            }
            // 第 20 天: 8 + 16 = 24 工时 (480 + 960 = 1440 minutes)
            effs.add(newEfficiency(EMPLOYEE_ID, MONTH_START.withDayOfMonth(20), 0, 1440, null));

            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START, MONTH_END))
                    .thenReturn(effs);

            // HourlyRateRule: ¥30/h, OT × 1.5
            HourlyRateRule hrule = HourlyRateRule.builder()
                    .id(50L).factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(new BigDecimal("30.00"))
                    .overtimeMultiplier(new BigDecimal("1.5"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build();
            when(hourlyRateRuleRepository.findEffectiveRules(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START))
                    .thenReturn(List.of(hrule));

            // act
            WageCalculation calc = wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            // assert
            assertEquals(WageMode.HOURLY, calc.getMode());
            // standardHours = 20 days × 8 = 160; totalHours = 176; overtime = 16
            assertEquals(0, new BigDecimal("176.00").compareTo(calc.getTotalHours()), "176 total hours");
            assertEquals(0, new BigDecimal("16.00").compareTo(calc.getOvertimeHours()), "16 OT hours");
            // hourlyAmount = (176 - 16) × 30 = 4800
            assertEquals(0, new BigDecimal("4800.00").compareTo(calc.getHourlyAmount()), "regular 160h × ¥30 = ¥4800");
            // overtimeAmount = 16 × 30 × 1.5 = 720
            assertEquals(0, new BigDecimal("720.00").compareTo(calc.getOvertimeAmount()), "16h × ¥30 × 1.5 = ¥720");
            assertEquals(0, new BigDecimal("5520.00").compareTo(calc.getTotalAmount()), "totalAmount=¥5520");
            assertEquals(50L, calc.getHourlyRuleId());
            assertNull(calc.getPieceRuleId(), "HOURLY mode 不应触发 PieceRateRule lookup");
            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getPieceRateAmount()));
        }

        @Test
        @DisplayName("缺 HourlyRateRule → hourlyAmount=0 + notes 标记 (degraded but no crash)")
        void shouldDegradeGracefully_whenHourlyRateRuleMissing() {
            mockUserLookup();
            mockSaveCalc();
            mockNoExistingCalc();
            WagePolicy empPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .mode(WageMode.HOURLY).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.of(empPolicy));

            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START, MONTH_END))
                    .thenReturn(List.of(newEfficiency(EMPLOYEE_ID, MONTH_START, 0, 480, null)));

            // 无 HourlyRateRule
            when(hourlyRateRuleRepository.findEffectiveRules(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START))
                    .thenReturn(List.of());

            WageCalculation calc = wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getHourlyAmount()));
            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getTotalAmount()));
            assertNotNull(calc.getNotes(), "缺规则时 notes 应有 warning");
            assertTrue(calc.getNotes().contains("缺 HourlyRateRule"));
        }
    }

    @Nested
    @DisplayName("Mode = MIXED — 混合 (HOURLY + PIECE_RATE 合)")
    class MixedMode {
        @Test
        @DisplayName("100 件 + 8h × ¥30 (无加班) = ¥500 + ¥240 = ¥740")
        void shouldSumHourlyAndPieceRate_whenModeIsMixed() {
            mockUserLookup();
            mockSaveCalc();
            mockNoExistingCalc();
            WagePolicy empPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .mode(WageMode.MIXED).mixedFormulaHint("基础工时 + 计件提成")
                    .isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.of(empPolicy));

            // 1 天 8h + 100 件
            WorkerDailyEfficiency e1 = newEfficiency(EMPLOYEE_ID, MONTH_START, 100, 480, "卤猪蹄200g");
            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START, MONTH_END))
                    .thenReturn(List.of(e1));

            // HourlyRateRule
            HourlyRateRule hrule = HourlyRateRule.builder()
                    .id(60L).factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(new BigDecimal("30.00"))
                    .overtimeMultiplier(new BigDecimal("1.5"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build();
            when(hourlyRateRuleRepository.findEffectiveRules(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START))
                    .thenReturn(List.of(hrule));

            // PieceRateRule
            PieceRateRule prule = PieceRateRule.builder()
                    .id(2L).factoryId(FACTORY_ID).processStageType("卤猪蹄200g")
                    .tier1Threshold(0).tier1Rate(new BigDecimal("5.0000")).maxTierCount(1).isActive(true)
                    .build();
            when(pieceRateRuleRepository.findEffectiveRulesByProcessStage(eq(FACTORY_ID), eq("卤猪蹄200g"), any(LocalDate.class)))
                    .thenReturn(List.of(prule));

            // act
            WageCalculation calc = wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            // assert: standardHours = 1day × 8 = 8; totalHours = 8; OT = 0
            assertEquals(WageMode.MIXED, calc.getMode());
            assertEquals(0, new BigDecimal("240.00").compareTo(calc.getHourlyAmount()), "8h × ¥30 = ¥240");
            assertEquals(0, BigDecimal.ZERO.compareTo(calc.getOvertimeAmount()));
            assertEquals(0, new BigDecimal("500.00").compareTo(calc.getPieceRateAmount()), "100×¥5 = ¥500");
            assertEquals(0, new BigDecimal("740.00").compareTo(calc.getTotalAmount()), "MIXED total = 240+500 = ¥740");
            assertEquals(60L, calc.getHourlyRuleId());
            assertEquals(2L, calc.getPieceRuleId());
        }
    }

    @Nested
    @DisplayName("Edge case — mode resolve + idempotency + month transition")
    class EdgeCases {
        @Test
        @DisplayName("idempotent upsert: 重复 calculateMonthly → update existing, 不创建新行")
        void shouldUpsertExisting_whenAlreadyCalculated() {
            mockUserLookup();
            mockSaveCalc();
            // arrange: PIECE_RATE 默认
            WagePolicy defaultPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).mode(WageMode.PIECE_RATE).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.empty());
            when(wagePolicyRepository.findFactoryDefault(FACTORY_ID))
                    .thenReturn(Optional.of(defaultPolicy));

            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(any(), anyLong(), any(), any()))
                    .thenReturn(List.of());

            // existing
            WageCalculation existing = WageCalculation.builder()
                    .id(999L).factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .periodMonth(MONTH_START).mode(WageMode.PIECE_RATE)
                    .pieceRateAmount(new BigDecimal("100.00")).build();
            when(wageCalculationRepository.findByFactoryIdAndEmployeeIdAndPeriodMonth(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START))
                    .thenReturn(Optional.of(existing));

            // act
            wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            // assert: should keep id=999 (upsert), 不创建新行
            ArgumentCaptor<WageCalculation> captor = ArgumentCaptor.forClass(WageCalculation.class);
            verify(wageCalculationRepository).save(captor.capture());
            assertEquals(999L, captor.getValue().getId(), "应 upsert existing row, 保 id=999");
        }

        @Test
        @DisplayName("mode resolution 顺序: per-employee 优先 over factory default")
        void shouldPreferPerEmployeePolicy_overFactoryDefault() {
            WagePolicy empPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .mode(WageMode.HOURLY).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.of(empPolicy));
            // factory default 不应被查 (短路)

            WageMode mode = wagePolicyService.resolveModeForEmployee(FACTORY_ID, EMPLOYEE_ID);

            assertEquals(WageMode.HOURLY, mode, "per-employee policy 优先");
            verify(wagePolicyRepository, never()).findFactoryDefault(any());
        }

        @Test
        @DisplayName("mode resolution 顺序: 无 policy → PIECE_RATE 兜底 (PR #57 E 向后兼容)")
        void shouldFallbackToPieceRate_whenNoPolicy() {
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.empty());
            when(wagePolicyRepository.findFactoryDefault(FACTORY_ID))
                    .thenReturn(Optional.empty());

            WageMode mode = wagePolicyService.resolveModeForEmployee(FACTORY_ID, EMPLOYEE_ID);

            assertEquals(WageMode.PIECE_RATE, mode, "兜底 = PIECE_RATE");
        }

        @Test
        @DisplayName("mode change mid-month: HourlyRateRule effectiveFrom 月初 → 全月用新 rate")
        void shouldUseEffectiveHourlyRule_forMonthStart() {
            mockUserLookup();
            mockSaveCalc();
            mockNoExistingCalc();
            WagePolicy empPolicy = WagePolicy.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .mode(WageMode.HOURLY).isActive(true).build();
            when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(
                    FACTORY_ID, EMPLOYEE_ID)).thenReturn(Optional.of(empPolicy));

            when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START, MONTH_END))
                    .thenReturn(List.of(newEfficiency(EMPLOYEE_ID, MONTH_START, 0, 480, null)));

            // 两条 rule: 旧 (effectiveTo=2026-04-30) + 新 (effectiveFrom=2026-05-01)
            HourlyRateRule newRule = HourlyRateRule.builder()
                    .id(71L).factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(new BigDecimal("40.00"))
                    .overtimeMultiplier(new BigDecimal("1.5"))
                    .effectiveFrom(LocalDate.of(2026, 5, 1)).isActive(true).build();
            // findEffectiveRules ORDER BY effectiveFrom DESC → newRule 首条
            when(hourlyRateRuleRepository.findEffectiveRules(
                    FACTORY_ID, EMPLOYEE_ID, MONTH_START))
                    .thenReturn(List.of(newRule));

            WageCalculation calc = wageCalculationService.calculateMonthly(FACTORY_ID, MONTH, EMPLOYEE_ID);

            // 1 天 × 8h × ¥40 = ¥320 (用新 rate, 不是旧 rate)
            assertEquals(0, new BigDecimal("320.00").compareTo(calc.getHourlyAmount()),
                    "应用 effectiveFrom=2026-05-01 新 rate ¥40, 全月 ¥320");
            assertEquals(71L, calc.getHourlyRuleId(), "应用新 rule id=71");
        }
    }

    @Nested
    @DisplayName("WageCalculation.getTotalAmount() derived getter — mode 分支")
    class DerivedGetter {
        @Test
        void pieceRateMode_totalAmount_equalsPieceRateOnly() {
            WageCalculation c = WageCalculation.builder()
                    .mode(WageMode.PIECE_RATE)
                    .hourlyAmount(new BigDecimal("100"))
                    .overtimeAmount(new BigDecimal("50"))
                    .pieceRateAmount(new BigDecimal("500"))
                    .build();
            assertEquals(0, new BigDecimal("500").compareTo(c.getTotalAmount()));
        }

        @Test
        void hourlyMode_totalAmount_equalsHourlyPlusOvertime() {
            WageCalculation c = WageCalculation.builder()
                    .mode(WageMode.HOURLY)
                    .hourlyAmount(new BigDecimal("4800"))
                    .overtimeAmount(new BigDecimal("720"))
                    .pieceRateAmount(new BigDecimal("500"))  // should be ignored
                    .build();
            assertEquals(0, new BigDecimal("5520").compareTo(c.getTotalAmount()));
        }

        @Test
        void mixedMode_totalAmount_equalsAllThreeSum() {
            WageCalculation c = WageCalculation.builder()
                    .mode(WageMode.MIXED)
                    .hourlyAmount(new BigDecimal("240"))
                    .overtimeAmount(new BigDecimal("100"))
                    .pieceRateAmount(new BigDecimal("500"))
                    .build();
            assertEquals(0, new BigDecimal("840").compareTo(c.getTotalAmount()));
        }

        @Test
        void nullMode_totalAmount_returnsZero() {
            WageCalculation c = WageCalculation.builder().build();
            assertEquals(0, BigDecimal.ZERO.compareTo(c.getTotalAmount()));
        }
    }

    @Nested
    @DisplayName("WagePolicyService 防呆 (per fool-proof-design.md)")
    class FoolProofGuards {
        @Test
        @DisplayName("Rule 1: HourlyRateRule.baseHourlyRate 超 ¥500/h → 抛 IllegalArgumentException")
        void shouldRejectHourlyRateExceeding500() {
            HourlyRateRule bad = HourlyRateRule.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(new BigDecimal("501.00"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> wagePolicyService.saveHourlyRateRule(FACTORY_ID, bad));
            assertTrue(ex.getMessage().contains("时薪超过上限"));
            assertTrue(ex.getMessage().contains("500"));
        }

        @Test
        @DisplayName("Rule 1: baseHourlyRate ≤ 0 → 抛 IllegalArgumentException")
        void shouldRejectZeroOrNegativeHourlyRate() {
            HourlyRateRule bad = HourlyRateRule.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(BigDecimal.ZERO)
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
            assertThrows(IllegalArgumentException.class,
                    () -> wagePolicyService.saveHourlyRateRule(FACTORY_ID, bad));
        }

        @Test
        @DisplayName("effectiveTo < effectiveFrom → 抛 IllegalArgumentException")
        void shouldRejectInvertedEffectivePeriod() {
            HourlyRateRule bad = HourlyRateRule.builder()
                    .factoryId(FACTORY_ID).employeeId(EMPLOYEE_ID)
                    .baseHourlyRate(new BigDecimal("30"))
                    .effectiveFrom(LocalDate.of(2026, 5, 1))
                    .effectiveTo(LocalDate.of(2026, 4, 1))  // 倒置
                    .build();
            assertThrows(IllegalArgumentException.class,
                    () -> wagePolicyService.saveHourlyRateRule(FACTORY_ID, bad));
        }
    }

    // ============ helpers ============

    private WorkerDailyEfficiency newEfficiency(Long workerId, LocalDate date,
                                                 int pieceCount, int workMinutes, String stage) {
        return WorkerDailyEfficiency.builder()
                .factoryId(FACTORY_ID)
                .workerId(workerId)
                .workerName("张三")
                .workDate(date)
                .processStageType(stage)
                .productTypeId(stage)
                .totalPieceCount(pieceCount)
                .qualifiedCount(pieceCount)
                .defectCount(0)
                .effectiveWorkMinutes(workMinutes)
                .build();
    }
}
