package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.User;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.when;

/**
 * 「报工人」这一列必须有名字。
 *
 * <p><b>2026-08-17 生产实测</b>：在「报工审批」页，报工人一列是<b>空白</b>。
 * 链上每一环都没填它：
 * <ul>
 *   <li>RN 逐道报工屏一次都没传过 {@code reporterName}
 *       （该字段在 API 类型里存在，屏幕里 0 处引用）</li>
 *   <li>后端 {@code YieldReportServiceImpl} 原样 {@code reporterName(req.getReporterName())}，不回退</li>
 *   <li>本类的 mapper 直接把这个 null 交出去</li>
 * </ul>
 * 而身份一直都在：那条报工的 {@code workerId = 1310}。
 *
 * <p>对「工人报工 → 文员核对」这条主线，文员看不到是谁报的 —— 那是这条流程的核心信息。
 *
 * <p>⛔ 修在这个唯一的 mapper 上，而不是要求每个客户端都记得传 ——
 * 「每个调用方都记得」是<b>约定</b>，反查是<b>机制</b>。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReporterNameFallbackTest {

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
        // 附件仓库不打桩: Mockito 对返回 List 的方法默认给空列表, 正是这里要的。
    }

    private ProductionReport report(long id, Long workerId, String reporterName) {
        ProductionReport r = new ProductionReport();
        r.setId(id);
        r.setFactoryId(FACTORY);
        r.setWorkProcessTaskId(1786L);
        r.setWorkerId(workerId);
        r.setReporterName(reporterName);
        return r;
    }

    private User user(long id, String fullName, String username) {
        User u = new User();
        u.setId(id);
        u.setFullName(fullName);
        u.setUsername(username);
        return u;
    }

    private Map<String, Object> firstRowFor(ProductionReport r) {
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull(FACTORY, 1786L))
                .thenReturn(List.of(r));
        List<Map<String, Object>> rows = service.getReportsByTask(FACTORY, "1786");
        assertThat(rows).as("阳性对照: 没有行的话下面断言什么都没测到").hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("🔴 App 没传 reporterName 时, 按 workerId 反查出姓名 —— ⛔ 不许交 null 给文员")
    void fallsBackToWorkerFullName() {
        when(userRepository.findByIdIn(any())).thenReturn(List.of(user(1310L, "张三", "f006_workshop")));

        assertThat(firstRowFor(report(23813L, 1310L, null)).get("reporterName"))
                .as("身份一直都在(workerId=1310), 空白是没人去解析它")
                .isEqualTo("张三");
    }

    @Test
    @DisplayName("没有 fullName 时退到 username, ⛔ 不退到 null")
    void fallsBackToUsernameWhenFullNameBlank() {
        when(userRepository.findByIdIn(any())).thenReturn(List.of(user(1310L, "  ", "f006_workshop")));

        assertThat(firstRowFor(report(23813L, 1310L, null)).get("reporterName"))
                .isEqualTo("f006_workshop");
    }

    @Test
    @DisplayName("⛔ 阴性对照: 请求带了名字就用请求的 —— 代报工时那是【被代者】的名字, 不许被反查覆盖")
    void requestSuppliedNameWins() {
        when(userRepository.findByIdIn(any())).thenReturn(List.of(user(1310L, "张三", "f006_workshop")));

        assertThat(firstRowFor(report(23813L, 1310L, "李四")).get("reporterName"))
                .as("代报工: workerId 是被代者, 但请求显式给了名字就以它为准")
                .isEqualTo("李四");
    }

    @Test
    @DisplayName("⛔ 阴性对照: workerId 也没有时诚实交 null, 不编一个名字出来")
    void nullWhenNothingKnown() {
        assertThat(firstRowFor(report(23813L, null, null)).get("reporterName")).isNull();
    }

    @Test
    @DisplayName("⛔ 查不到那个用户时交 null, 不抛异常把整页打挂")
    void nullWhenUserNotFound() {
        when(userRepository.findByIdIn(any())).thenReturn(List.of());

        assertThat(firstRowFor(report(23813L, 1310L, null)).get("reporterName")).isNull();
    }
}
