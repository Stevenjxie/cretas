package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkReportResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy 只读端点的替代实现（退役第 2 步）。
 *
 * <p>legacy `GET /work-reporting/reports` 与 `/reports/{id}` 在 `process-work-reporting`
 * 上没有对应，所以这一步不是改指向、是补端点。这里钉两件事：
 * <ol>
 *   <li><b>四个查询分支照搬</b> —— 合并简化会改变「没传参时」的口径；</li>
 *   <li><b>跨租户挡板没丢</b> —— 换出口时最容易顺手丢掉的就是这种守卫。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessReportQueryTest {

    private static final String FACTORY = "F006";
    private static final LocalDate D1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 17);

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
        Page<ProductionReport> empty = new PageImpl<>(List.of());
        when(reportRepository.findByFactoryIdAndDeletedAtIsNull(any(), any())).thenReturn(empty);
        when(reportRepository.findByFactoryIdAndReportTypeAndDeletedAtIsNull(any(), any(), any()))
                .thenReturn(empty);
        when(reportRepository.findByFactoryIdAndReportDateBetweenAndDeletedAtIsNull(any(), any(), any(), any()))
                .thenReturn(empty);
        when(reportRepository.findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                any(), any(), any(), any(), any())).thenReturn(empty);
    }

    private ProductionReport report(String factoryId) {
        ProductionReport r = new ProductionReport();
        r.setId(23814L);
        r.setFactoryId(factoryId);
        r.setReportType("PROGRESS");
        r.setReportDate(D2);
        return r;
    }

    @Test
    @DisplayName("🔴 四个查询分支各走各的 —— ⛔ 不许合并简化, 那会改变没传参时的口径")
    void picksTheRightRepositoryBranch() {
        service.listReports(FACTORY, "PROGRESS", D1, D2, 1, 20);
        verify(reportRepository).findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq("PROGRESS"), eq(D1), eq(D2), any());

        service.listReports(FACTORY, "PROGRESS", null, null, 1, 20);
        verify(reportRepository).findByFactoryIdAndReportTypeAndDeletedAtIsNull(
                eq(FACTORY), eq("PROGRESS"), any());

        service.listReports(FACTORY, null, D1, D2, 1, 20);
        verify(reportRepository).findByFactoryIdAndReportDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(D1), eq(D2), any());

        service.listReports(FACTORY, null, null, null, 1, 20);
        verify(reportRepository).findByFactoryIdAndDeletedAtIsNull(eq(FACTORY), any());
    }

    @Test
    @DisplayName("分页从 1 起算, 且按 reportDate 倒序 —— 与 legacy 同口径")
    void pagingMatchesLegacy() {
        service.listReports(FACTORY, null, null, null, 1, 20);

        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findByFactoryIdAndDeletedAtIsNull(eq(FACTORY), cap.capture());
        assertThat(cap.getValue().getPageNumber()).as("传 1 应落到第 0 页").isZero();
        assertThat(cap.getValue().getPageSize()).isEqualTo(20);
        assertThat(cap.getValue().getSort().getOrderFor("reportDate")).isNotNull();
        assertThat(cap.getValue().getSort().getOrderFor("reportDate").isDescending()).isTrue();
    }

    @Test
    @DisplayName("详情返回映射后的 DTO")
    void detailReturnsMappedDto() {
        when(reportRepository.findById(23814L)).thenReturn(Optional.of(report(FACTORY)));

        WorkReportResponse r = service.getReportDetail(FACTORY, 23814L);

        assertThat(r).isNotNull();
        assertThat(r.getId()).isEqualTo(23814L);
    }

    @Test
    @DisplayName("🔴 ⛔ 跨租户必须挡住 —— 换出口时最容易顺手丢掉的就是这种守卫")
    void detailRefusesCrossTenant() {
        when(reportRepository.findById(23814L)).thenReturn(Optional.of(report("F001")));

        assertThatThrownBy(() -> service.getReportDetail(FACTORY, 23814L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前工厂");
    }

    @Test
    @DisplayName("⛔ 阴性对照: 查不到就抛 NotFound, 不返回一个空壳 DTO")
    void detailThrowsWhenMissing() {
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReportDetail(FACTORY, 99999L))
                .isInstanceOf(RuntimeException.class);
        verify(reportRepository, never()).save(any());
    }
}
