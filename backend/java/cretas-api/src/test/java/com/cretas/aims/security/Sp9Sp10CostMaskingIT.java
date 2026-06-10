package com.cretas.aims.security;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborVarianceItemDTO;
import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;
import com.cretas.aims.dto.rd.ThreePriceComparisonDTO.VarianceAlertEntry;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 六扇门审计 Tier0 #05 — 成本脱敏收口回归守卫 (SP9 人效/人工 + SP10 三价对比).
 *
 * <p><b>背景</b>: 审计抓到 SP9 {@link LaborEfficiencyCompareDTO} /
 * {@link LaborVarianceItemDTO} 与 SP10 {@link ThreePriceComparisonDTO} 的成本绝对值字段
 * 零 {@code @PriceSensitive} → 无 {@code procurement:price:view} 权限的运营角色
 * (warehouse_manager / warehouse_worker / quality_inspector / operator / viewer)
 * 可读到人工成本 / 三价绝对值。脱敏机制此前从未被这两个 DTO 证明生效。
 *
 * <p>本测试镜像 {@link PriceFieldResponseAdviceTest} / {@link BomDomainPriceFieldAdviceTest}
 * 的直测 advice 模式: 构造真实响应体 → 经 advice + mock 权限 → 断言:
 * <ul>
 *   <li>无价格权限角色 (sales/运营): 成本绝对值字段全部 nulled;</li>
 *   <li>财务/有权限角色: 成本字段原样保留;</li>
 *   <li>相对指标 (偏差率 % / 工时 / 人次 / 达成率 / 标签 / 物理规格): 任何角色始终可见。</li>
 * </ul>
 *
 * <p>脱敏门复用现有 {@link PriceFieldResponseAdvice#PRICE_VIEW_PERMISSION}
 * ({@code procurement:price:view}) — 与 BOM 成本字段一致, 不自创谓词。
 * 注意: 真实 RBAC 中 sales_manager 在 {@code PRICE_VIEW_ROLES} 白名单内 (有该权限),
 * 真正被脱敏保护的是 warehouse/QC/operator/viewer 等运营角色; 本测试用
 * {@code canViewPrice=false} 代表这一类无权限角色。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("成本脱敏收口 — SP9 人工 + SP10 三价 (六扇门审计 Tier0 #05)")
class Sp9Sp10CostMaskingIT {

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockHttpServletRequest httpRequest;
    private ServerHttpRequest serverRequest;

    @BeforeEach
    void setup() {
        httpRequest = new MockHttpServletRequest();
        serverRequest = new ServletServerHttpRequest(httpRequest);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void teardown() {
        PriceSensitiveContext.clear();
    }

    // ───────── Helpers ─────────

    /**
     * 两门齐设: canViewPrice ({@code procurement:price:view}) 控制 @PriceSensitive,
     * canViewFinance ({@code finance:read_write}) 控制 SmartBI 上传列 (此处不涉及, 但与
     * canViewPrice 同值以避免无谓 stubbing 影响)。
     */
    private void asUser(Long userId, boolean canViewPrice) {
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION))).thenReturn(canViewPrice);
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION))).thenReturn(canViewPrice);
    }

    private Object run(Object body) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, serverRequest, null);
    }

    private LaborEfficiencyCompareDTO sampleLaborCompare() {
        LaborVarianceItemDTO step = LaborVarianceItemDTO.builder()
                .processName("滚揉")
                .processOrder(1)
                .totalWorkMinutes(120)
                .totalWorkers(3)
                .laborCost(new BigDecimal("180.00"))
                .laborCostPerBox(new BigDecimal("0.36"))
                .achievementRate(new BigDecimal("92.50"))
                .achievementAlert("OK")
                .build();

        return LaborEfficiencyCompareDTO.builder()
                .batchId(1924L)
                .batchNumber("ZS-1924")
                .productName("叮咚猪舌")
                .productTypeId("PT-F006-001")
                .gramsPerUnit(new BigDecimal("200.00"))
                .quotedLaborCostPerKg(new BigDecimal("8.00"))
                .actualLaborCostPerKg(new BigDecimal("9.20"))
                .quotedLaborCostPerBox(new BigDecimal("1.60"))
                .actualLaborCostPerBox(new BigDecimal("1.84"))
                .varianceRate(new BigDecimal("15.00"))
                .varianceStatus("WARNING")
                .stepDetails(Collections.singletonList(step))
                .build();
    }

    private ThreePriceComparisonDTO sampleThreePrice() {
        VarianceAlertEntry alert = VarianceAlertEntry.builder()
                .stage("PRE_TO_MID")
                .variancePct(new BigDecimal("12.50"))
                .alert(true)
                .build();

        return ThreePriceComparisonDTO.builder()
                .preQuote(new BigDecimal("32.00"))
                .midQuote(new BigDecimal("36.00"))
                .actualCost(new BigDecimal("38.40"))
                .varianceAlerts(Collections.singletonList(alert))
                .build();
    }

    // ───────── SP9: 人工双口径对比 ─────────

    @Test
    @DisplayName("SP9 无价格权限 (运营角色): 人工成本绝对值全部脱敏, 偏差率/工时/达成率保留")
    void sp9_noPricePermission_laborCostsStripped() {
        asUser(10L, false);

        // SP9 端点返回 List<LaborEfficiencyCompareDTO>, 包 ApiResponse 走完整 walk
        LaborEfficiencyCompareDTO dto = sampleLaborCompare();
        run(ApiResponse.success(Collections.singletonList(dto)));

        // 成本绝对值 → nulled
        assertNull(dto.getQuotedLaborCostPerKg(), "quotedLaborCostPerKg 脱敏");
        assertNull(dto.getActualLaborCostPerKg(), "actualLaborCostPerKg 脱敏");
        assertNull(dto.getQuotedLaborCostPerBox(), "quotedLaborCostPerBox 脱敏");
        assertNull(dto.getActualLaborCostPerBox(), "actualLaborCostPerBox 脱敏");

        // 嵌套工序成本绝对值 → nulled
        LaborVarianceItemDTO step = dto.getStepDetails().get(0);
        assertNull(step.getLaborCost(), "step.laborCost 脱敏");
        assertNull(step.getLaborCostPerBox(), "step.laborCostPerBox 脱敏");

        // 相对指标 / 工作量 / 标签 / 物理规格 → 保留
        assertEquals(new BigDecimal("15.00"), dto.getVarianceRate(), "偏差率 % 保留");
        assertEquals("WARNING", dto.getVarianceStatus(), "偏差状态标签保留");
        assertEquals(new BigDecimal("200.00"), dto.getGramsPerUnit(), "标准克重(物理规格) 保留");
        assertEquals("叮咚猪舌", dto.getProductName(), "产品名保留");
        assertEquals(Integer.valueOf(120), step.getTotalWorkMinutes(), "工时(分钟) 保留");
        assertEquals(Integer.valueOf(3), step.getTotalWorkers(), "人次保留");
        assertEquals(new BigDecimal("92.50"), step.getAchievementRate(), "达成率 % 保留");
        assertEquals("OK", step.getAchievementAlert(), "达成率告警标签保留");
        assertEquals("滚揉", step.getProcessName(), "工序名保留");
    }

    @Test
    @DisplayName("SP9 有价格权限 (财务/采购): 人工成本绝对值原样可见")
    void sp9_withPricePermission_laborCostsVisible() {
        asUser(20L, true);

        LaborEfficiencyCompareDTO dto = sampleLaborCompare();
        run(ApiResponse.success(Collections.singletonList(dto)));

        assertEquals(new BigDecimal("8.00"), dto.getQuotedLaborCostPerKg());
        assertEquals(new BigDecimal("9.20"), dto.getActualLaborCostPerKg());
        assertEquals(new BigDecimal("1.60"), dto.getQuotedLaborCostPerBox());
        assertEquals(new BigDecimal("1.84"), dto.getActualLaborCostPerBox());
        LaborVarianceItemDTO step = dto.getStepDetails().get(0);
        assertEquals(new BigDecimal("180.00"), step.getLaborCost());
        assertEquals(new BigDecimal("0.36"), step.getLaborCostPerBox());
    }

    // ───────── SP10: 三价对比 ─────────

    @Test
    @DisplayName("SP10 无价格权限 (运营角色): 三价绝对值全部脱敏, 偏差率/告警保留")
    void sp10_noPricePermission_quotesStripped() {
        asUser(10L, false);

        // SP10 端点返回 Map.of("success", true, "data", dto) — advice walk data 字段
        ThreePriceComparisonDTO dto = sampleThreePrice();
        run(Map.of("success", true, "data", dto));

        assertNull(dto.getPreQuote(), "preQuote 脱敏");
        assertNull(dto.getMidQuote(), "midQuote 脱敏");
        assertNull(dto.getActualCost(), "actualCost 脱敏");

        // 偏差率 % + 超限布尔 → 保留
        VarianceAlertEntry alert = dto.getVarianceAlerts().get(0);
        assertEquals("PRE_TO_MID", alert.getStage(), "阶段标签保留");
        assertEquals(new BigDecimal("12.50"), alert.getVariancePct(), "偏差率 % 保留");
        assertEquals(true, alert.isAlert(), "超限布尔保留");
    }

    @Test
    @DisplayName("SP10 有价格权限 (财务/采购): 三价绝对值原样可见")
    void sp10_withPricePermission_quotesVisible() {
        asUser(20L, true);

        ThreePriceComparisonDTO dto = sampleThreePrice();
        run(Map.of("success", true, "data", dto));

        assertEquals(new BigDecimal("32.00"), dto.getPreQuote());
        assertEquals(new BigDecimal("36.00"), dto.getMidQuote());
        assertEquals(new BigDecimal("38.40"), dto.getActualCost());
    }

    // ───────── 非敏感字段始终可见 (双角色交叉确认) ─────────

    @Test
    @DisplayName("非敏感比率/标签字段对无权限角色也始终可见 (不过度脱敏)")
    void nonSensitiveFields_alwaysVisible_evenWithoutPermission() {
        asUser(10L, false);

        LaborEfficiencyCompareDTO labor = sampleLaborCompare();
        ThreePriceComparisonDTO three = sampleThreePrice();
        run(ApiResponse.success(Collections.singletonList(labor)));
        run(Map.of("success", true, "data", three));

        // SP9 相对/工作量字段
        assertNotNull(labor.getVarianceRate());
        assertNotNull(labor.getVarianceStatus());
        assertNotNull(labor.getStepDetails().get(0).getAchievementRate());
        // SP10 相对字段
        assertNotNull(three.getVarianceAlerts().get(0).getVariancePct());
    }
}
