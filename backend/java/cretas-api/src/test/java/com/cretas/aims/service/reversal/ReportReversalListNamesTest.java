package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * BUG-4 撤回列表显姓名 — 单元测试。
 *
 * <p>listReversals 返回值的每行必须携带 submittedByName / approvedByName。
 * 用户加载必须通过一次 findAllById(IN query) 完成，禁止 N+1。
 * 用户不存在时 name=null (诚实不补假名)。
 */
class ReportReversalListNamesTest {

    private ReportReversalLogRepository reversalLogRepo;
    private UserRepository userRepository;
    private ReportReversalServiceImpl service;

    @BeforeEach
    void setUp() {
        reversalLogRepo = mock(ReportReversalLogRepository.class);
        userRepository = mock(UserRepository.class);

        service = new ReportReversalServiceImpl(
                reversalLogRepo,
                mock(ProductionBatchRepository.class),
                mock(ProductionReportRepository.class),
                mock(SemiFinishedInventoryRepository.class),
                mock(SemiFinishedInventoryTransactionRepository.class),
                mock(FinishedGoodsBatchRepository.class),
                userRepository,
                mock(com.cretas.aims.repository.workprocess.WorkProcessTaskRepository.class)
        );
    }

    private ReportReversalLog log(Long id, Long submittedBy, Long approvedBy) {
        return ReportReversalLog.builder()
                .id(id)
                .factoryId("F006")
                .batchId(100L)
                .submittedBy(submittedBy)
                .approvedBy(approvedBy)
                .build();
    }

    private User user(Long id, String fullName) {
        User u = new User();
        u.setId(id);
        u.setFullName(fullName);
        return u;
    }

    @Test
    @DisplayName("listReversals 返回带 submittedByName + approvedByName (一次 IN-query, 无 N+1)")
    void listReversals_enrichesWithNames_singleBatchQuery() {
        ReportReversalLog log1 = log(1L, 10L, 20L);
        ReportReversalLog log2 = log(2L, 30L, null); // approvedBy=null

        when(reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(List.of(log1, log2));
        // 只有两个非 null userId: 10, 20, 30 → batch load
        when(userRepository.findAllById(anyList())).thenReturn(List.of(
                user(10L, "张三"),
                user(20L, "李四"),
                user(30L, "王五")
        ));

        List<ReportReversalLog> result = service.listReversals("F006", null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSubmittedByName()).as("log1 submittedByName").isEqualTo("张三");
        assertThat(result.get(0).getApprovedByName()).as("log1 approvedByName").isEqualTo("李四");
        assertThat(result.get(1).getSubmittedByName()).as("log2 submittedByName").isEqualTo("王五");
        assertThat(result.get(1).getApprovedByName()).as("log2 approvedByName (approvedBy=null)").isNull();

        // 一次 findAllById (IN query), 禁止 N+1
        verify(userRepository, times(1)).findAllById(anyList());
    }

    @Test
    @DisplayName("用户不存在时 name=null (诚实), 不抛异常")
    void listReversals_missingUser_nameIsNull() {
        ReportReversalLog log1 = log(1L, 99L, null); // userId=99 不在库中

        when(reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(List.of(log1));
        when(userRepository.findAllById(anyList())).thenReturn(List.of()); // 空返回

        List<ReportReversalLog> result = service.listReversals("F006", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubmittedByName()).as("用户不存在时 name 应为 null").isNull();
    }

    @Test
    @DisplayName("空列表时不调用 userRepository (零查询)")
    void listReversals_emptyList_noUserQuery() {
        when(reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(List.of());

        List<ReportReversalLog> result = service.listReversals("F006", null);

        assertThat(result).isEmpty();
        verify(userRepository, never()).findAllById(anyList());
    }
}
