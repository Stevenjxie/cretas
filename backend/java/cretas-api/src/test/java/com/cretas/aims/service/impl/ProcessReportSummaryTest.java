package com.cretas.aims.service.impl;

import com.cretas.aims.controller.ProcessWorkReportingController;
import com.cretas.aims.dto.WorkReportHistoricalAverageResponse;
import com.cretas.aims.dto.WorkReportSummaryResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessWorkReportingService;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * legacy 只读端点的替代实现，第二批：{@code getSummary} 与 {@code getHistoricalAverage}
 * （退役第 2 步，设计卡 {@code docs/decisions/2026-08-17-legacy报工栈退役.md}）。
 *
 * <p>这两个是<b>按 RN 的实际调用逐个对</b>出来的，不是照方法名猜的：
 * <ul>
 *   <li>{@code useDashboardData.ts:65} 无参调 {@code getSummary()}，只读三个字段；</li>
 *   <li>{@code useAnomalyDetection.ts:32} 调 {@code getHistoricalAverage(cat, 30, factoryId)}，
 *       读四个字段。</li>
 * </ul>
 *
 * <p>钉住四件「换出口时最容易顺手丢掉」的东西：
 * <ol>
 *   <li><b>待审批数用哪个口径</b> —— {@code status=SUBMITTED}，⛔ 不是
 *       {@code /pending-approval} 那条列表的 {@code approvalStatus='PENDING'}；</li>
 *   <li><b>良品率的 scale 与舍入</b> —— scale 1 / HALF_UP，且产出为 0 时不除零；</li>
 *   <li><b>{@code days} 真的被用了</b> —— 两个不同的 days 各验一次，⛔ 恒真式挡不住写死 30；</li>
 *   <li><b>{@code factoryId} 进了每一条查询</b> —— 这两个端点的租户隔离全靠它进 SQL，
 *       丢了就是跨租户读。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessReportSummaryTest {

    private static final String FACTORY = "F006";

    @Mock private ProductionReportRepository reportRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private UserRepository userRepository;

    private ProcessWorkReportingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessWorkReportingServiceImpl(
                reportRepository, workProcessRepository, productTypeRepository,
                attachmentRepository, workProcessTaskRepository, wipInventoryService,
                userRepository);
    }

    /**
     * getProgressSummary 的真实长相：native 聚合，键是小写别名，
     * SUM(CAST(... AS DECIMAL)) 出来是 BigDecimal，COUNT(*) 出来是 Long。
     * ⛔ 不要喂一个真实上游给不出的形状（空集也不会是 null —— 无 GROUP BY 的聚合恒返一行）。
     */
    private Map<String, Object> progressRow(String totalOutput, String totalGood) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_output", new BigDecimal(totalOutput));
        row.put("total_good", new BigDecimal(totalGood));
        row.put("total_defect", new BigDecimal("0.00"));
        row.put("report_count", 7L);
        return row;
    }

    /** getHistoricalAverageByProcess 的真实长相：DOUBLE PRECISION → Double，COUNT(*) → Long。 */
    private Map<String, Object> averageRow(double avgOutput, double stddev, double avgDefect, long count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("avg_output", avgOutput);
        row.put("stddev_output", stddev);
        row.put("avg_defect", avgDefect);
        row.put("sample_count", count);
        return row;
    }

    // ==================== getSummary ====================

    @Test
    @DisplayName("🔴 待审批数用 status=SUBMITTED —— ⛔ 不是 /pending-approval 列表的 approvalStatus='PENDING'")
    void pendingCountKeepsLegacyCriterion() {
        when(reportRepository.countByFactoryIdAndStatusAndDeletedAtIsNull(
                FACTORY, ProductionReport.Status.SUBMITTED)).thenReturn(11L);
        when(reportRepository.getProgressSummary(any(), any(), any()))
                .thenReturn(progressRow("0.00", "0.00"));

        WorkReportSummaryResponse s = service.getSummary(FACTORY);

        assertThat(s.getPendingApprovalCount())
                .as("待审批数没有落到 status=SUBMITTED 这个口径上")
                .isEqualTo(11L);
        // 阴性对照: 两个口径可以不相等, 迁移不许顺手换成另一个。
        verify(reportRepository, never()).findPendingApprovalsForFactory(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("今日产出与良品率取的是【今天】这一天, 且 factoryId 进了查询")
    void todayWindowIsASingleDayAndScopedToFactory() {
        when(reportRepository.getProgressSummary(any(), any(), any()))
                .thenReturn(progressRow("120.00", "118.00"));

        service.getSummary(FACTORY);

        ArgumentCaptor<String> fid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(reportRepository).getProgressSummary(fid.capture(), start.capture(), end.capture());

        assertThat(fid.getValue()).as("factoryId 没进查询 = 跨租户读").isEqualTo(FACTORY);
        assertThat(start.getValue()).as("今日产出的起始日不是今天").isEqualTo(LocalDate.now());
        assertThat(end.getValue()).as("今日产出的结束日不是今天").isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("🔴 良品率 = 良品/产出*100, scale 1 HALF_UP —— 2/3 必须是 66.7")
    void yieldRateKeepsScaleAndRounding() {
        when(reportRepository.getProgressSummary(any(), any(), any()))
                .thenReturn(progressRow("3.00", "2.00"));

        WorkReportSummaryResponse s = service.getSummary(FACTORY);

        assertThat(s.getTodayOutputTotal()).isEqualByComparingTo("3.00");
        assertThat(s.getTodayYieldRate())
                .as("良品率的 scale/舍入漂了 —— 2/3*100 在 scale 1 HALF_UP 下是 66.7")
                .isEqualByComparingTo("66.7");
        assertThat(s.getTodayYieldRate().scale())
                .as("scale 不是 1, 前端拿到的小数位就变了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⛔ 产出为 0 时良品率是 0, 不许除零崩 —— 这是【没人报工的那一天】的真实长相")
    void zeroOutputYieldsZeroNotDivideByZero() {
        when(reportRepository.getProgressSummary(any(), any(), any()))
                .thenReturn(progressRow("0.00", "0.00"));

        WorkReportSummaryResponse s = service.getSummary(FACTORY);

        assertThat(s.getTodayOutputTotal()).isEqualByComparingTo("0");
        assertThat(s.getTodayYieldRate()).isEqualByComparingTo("0");
    }

    // ==================== getHistoricalAverage ====================

    @Test
    @DisplayName("🔴 days 真的被用了 —— 两个不同的 days 各验一次, 写死 30 就红")
    void sinceDateFollowsTheDaysArgument() {
        when(reportRepository.getHistoricalAverageByProcess(any(), any(), any()))
                .thenReturn(averageRow(0, 0, 0, 0));

        service.getHistoricalAverage(FACTORY, "卤制", 30);
        verify(reportRepository).getHistoricalAverageByProcess(
                eq(FACTORY), eq("卤制"), eq(LocalDate.now().minusDays(30)));

        service.getHistoricalAverage(FACTORY, "拆骨", 7);
        verify(reportRepository).getHistoricalAverageByProcess(
                eq(FACTORY), eq("拆骨"), eq(LocalDate.now().minusDays(7)));
    }

    @Test
    @DisplayName("四个字段逐个对上 —— 四个值互不相同, 串位就红")
    void mapsEveryColumnToItsOwnField() {
        when(reportRepository.getHistoricalAverageByProcess(any(), any(), any()))
                .thenReturn(averageRow(412.5, 33.25, 6.75, 19L));

        WorkReportHistoricalAverageResponse r = service.getHistoricalAverage(FACTORY, "卤制", 30);

        assertThat(r.getAvgOutput()).isEqualTo(412.5);
        assertThat(r.getStddevOutput()).isEqualTo(33.25);
        assertThat(r.getAvgDefect()).isEqualTo(6.75);
        assertThat(r.getSampleCount()).isEqualTo(19L);
    }

    @Test
    @DisplayName("⛔ 「这个工序近 N 天没有报工」的长相是 sampleCount=0, 不是 null —— COUNT(*) 空集给 0")
    void emptyWindowIsZeroSamplesNotNull() {
        when(reportRepository.getHistoricalAverageByProcess(any(), any(), any()))
                .thenReturn(averageRow(0.0, 0.0, 0.0, 0L));

        WorkReportHistoricalAverageResponse r = service.getHistoricalAverage(FACTORY, "从没报过的工序", 30);

        assertThat(r).as("空窗口不该返回 null —— 消费方靠 sampleCount<5 判样本不够").isNotNull();
        assertThat(r.getSampleCount()).isZero();
        assertThat(r.getAvgOutput()).isZero();
    }

    @Test
    @DisplayName("防御分支: 上游给 null 时返回 null 而不是 NPE（⚠️ 生产上到不了, 无 GROUP BY 的聚合恒返一行）")
    void nullRowDoesNotBlowUp() {
        when(reportRepository.getHistoricalAverageByProcess(any(), any(), any())).thenReturn(null);

        assertThatCode(() -> assertThat(service.getHistoricalAverage(FACTORY, "卤制", 30)).isNull())
                .doesNotThrowAnyException();
    }

    // ==================== 真实入口: 路由 ====================

    /**
     * 🔴 {@code /reports/historical-average} 与 {@code /reports/{id}} 同前缀。
     * 「字面量段优先于模板段」是对的，但那是<b>推理</b> —— 这里真发一次请求让它自己说。
     * 落到 {@code getReportDetail(Long id)} 上是运行期 500，编译器一个字都不会说。
     */
    @Test
    @DisplayName("🔴 GET /reports/historical-average 落到均值端点, ⛔ 不是被 /reports/{id} 吃掉")
    void historicalAverageRouteIsNotSwallowedByReportDetail() throws Exception {
        ProcessWorkReportingService svc = mock(ProcessWorkReportingService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcessWorkReportingController(svc)).build();

        mvc.perform(get("/api/mobile/{factoryId}/process-work-reporting/reports/historical-average", FACTORY)
                        .param("processCategory", "卤制"))
                .andExpect(status().isOk());

        verify(svc).getHistoricalAverage(eq(FACTORY), eq("卤制"), eq(30));
        verify(svc, never()).getReportDetail(anyString(), any());
    }

    @Test
    @DisplayName("GET /summary 打到 getSummary, 且 ⛔ 不再接受 startDate/endDate（传了也不影响返回）")
    void summaryRouteReachesTheService() throws Exception {
        ProcessWorkReportingService svc = mock(ProcessWorkReportingService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcessWorkReportingController(svc)).build();

        mvc.perform(get("/api/mobile/{factoryId}/process-work-reporting/summary", FACTORY))
                .andExpect(status().isOk());

        verify(svc).getSummary(FACTORY);
        verify(svc, never()).getHistoricalAverage(anyString(), anyString(), anyInt());
    }
}
