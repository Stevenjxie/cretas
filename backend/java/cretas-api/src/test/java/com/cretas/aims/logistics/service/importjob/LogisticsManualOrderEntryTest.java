package com.cretas.aims.logistics.service.importjob;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.importjob.ManualOrderCreateRequest;
import com.cretas.aims.logistics.dto.importjob.ManualOrderRow;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.service.importjob.impl.LogisticsOrderImportServiceImpl;
import com.cretas.aims.logistics.service.routing.AmapClient;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Manual (non-file) order entry — {@link LogisticsOrderImportServiceImpl#previewManual}.
 *
 * <p>Verifies the manual JSON entry path reuses the exact same row validation + batch/order
 * creation semantics as the xlsx/csv {@code preview} path (see
 * {@code LogisticsOrderImportServiceImplTest} for the file-upload equivalents of these cases —
 * both paths share {@code buildPreviewFromRawRows}, so behavior must match).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("LogisticsOrderImportServiceImpl#previewManual — manual (non-file) order entry")
class LogisticsManualOrderEntryTest {

    private static final String F1 = "F-LOG-MANUAL-1";

    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;

    private final ExcelUtil excelUtil = new ExcelUtil();
    private final AmapClient amapClient = mock(AmapClient.class); // never hit real Amap API in tests
    private LogisticsOrderImportServiceImpl service;

    private LogisticsOrderImportServiceImpl service() {
        if (service == null) {
            service = new LogisticsOrderImportServiceImpl(batchRepo, orderRepo, excelUtil, amapClient);
        }
        return service;
    }

    private ManualOrderRow validRow(String storeCode, String storeName) {
        ManualOrderRow r = new ManualOrderRow();
        r.setStoreCode(storeCode);
        r.setStoreName(storeName);
        r.setAddress("苏州市工业园区" + storeCode + "号");
        r.setPieces("10");
        r.setBoxes("2");
        r.setWeightKg("50.5");
        r.setVolumeCbm("1.2");
        r.setWindowStart("08:00");
        r.setWindowEnd("12:00");
        r.setLongitude("120.6");
        r.setLatitude("31.3");
        r.setAreaCode("AREA-A");
        return r;
    }

    private ManualOrderCreateRequest request(String businessDate, ManualOrderRow... rows) {
        ManualOrderCreateRequest req = new ManualOrderCreateRequest();
        req.setBusinessDate(businessDate);
        req.setRows(new ArrayList<>(List.of(rows)));
        return req;
    }

    // ==================== (a) valid rows → PENDING/PREVIEWED batch ====================

    @Test
    @DisplayName("previewManual — 全部有效行 → PREVIEWED 批次落库, validRows == 输入行数, jobId 非空")
    void previewManualAllValidCreatesPreviewedBatch() {
        ManualOrderCreateRequest req = request("2026-07-12",
                validRow("M001", "门店1"), validRow("M002", "门店2"));

        PreviewResultDto result = service().previewManual(F1, req, 1L);

        assertThat(result.getJobId()).isNotBlank();
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getValidRows()).isEqualTo(2);
        assertThat(result.getErrorRows()).isZero();
        assertThat(result.getBusinessDate()).isEqualTo("2026-07-12");
        assertThat(result.getSourceFilename()).isEqualTo("手动录入");

        var batch = batchRepo.findById(result.getJobId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(OrderBatchStatus.PREVIEWED);
        assertThat(batch.getValidRows()).isEqualTo(2);

        var orders = orderRepo.findByFactoryIdAndBatchId(F1, result.getJobId());
        assertThat(orders).hasSize(2);
        assertThat(orders).extracting("storeCode").containsExactlyInAnyOrder("M001", "M002");
    }

    // ==================== (b) missing required field → rowErrors, only valid rows written ====================

    @Test
    @DisplayName("previewManual — 缺门店名称/地址 → rowErrors 含正确 rowNumber+column, 批次仍创建但只落库有效行")
    void previewManualMissingRequiredFieldCapturedInRowErrors() {
        ManualOrderRow good = validRow("M010", "门店10");
        ManualOrderRow badName = validRow("M011", "");           // 门店名称缺失
        badName.setAddress("苏州市工业园区M011号");
        ManualOrderRow badAddress = validRow("M012", "门店12");
        badAddress.setAddress("");                                 // 配送地址缺失

        ManualOrderCreateRequest req = request("2026-07-12", good, badName, badAddress);

        PreviewResultDto result = service().previewManual(F1, req, 1L);

        assertThat(result.getTotalRows()).isEqualTo(3);
        assertThat(result.getValidRows()).isEqualTo(1);
        assertThat(result.getErrorRows()).isEqualTo(2);

        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getRowNumber()).isEqualTo(2); // 1-based, matches form row order
            assertThat(e.getColumn()).isEqualTo("门店名称");
            assertThat(e.getMessage()).contains("必填");
        });
        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getRowNumber()).isEqualTo(3);
            assertThat(e.getColumn()).isEqualTo("配送地址");
            assertThat(e.getMessage()).contains("必填");
        });

        // batch still created — only the 1 valid row persisted (same semantics as xlsx path)
        var batch = batchRepo.findById(result.getJobId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(OrderBatchStatus.PREVIEWED);
        var orders = orderRepo.findByFactoryIdAndBatchId(F1, result.getJobId());
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getStoreCode()).isEqualTo("M010");
    }

    // ==================== (c) empty rows list → BusinessException 400 ====================

    @Test
    @DisplayName("previewManual — 空行列表 → BusinessException 400 (至少录入一行订单)")
    void previewManualEmptyRowsRejected() {
        ManualOrderCreateRequest req = request("2026-07-12");

        assertThatThrownBy(() -> service().previewManual(F1, req, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少录入一行订单");
    }

    @Test
    @DisplayName("previewManual — rows 为 null → BusinessException 400")
    void previewManualNullRowsRejected() {
        ManualOrderCreateRequest req = new ManualOrderCreateRequest();
        req.setBusinessDate("2026-07-12");

        assertThatThrownBy(() -> service().previewManual(F1, req, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少录入一行订单");
    }

    // ==================== manual path reuses batch/commit flow ====================

    @Test
    @DisplayName("previewManual — jobId 可直接传给既有 commit 完成提交流程")
    void previewManualJobIdWorksWithExistingCommit() {
        ManualOrderCreateRequest req = request("2026-07-12", validRow("M020", "门店20"));
        PreviewResultDto preview = service().previewManual(F1, req, 1L);

        var committed = service().commit(F1, preview.getJobId());

        assertThat(committed.getStatus()).isEqualTo(OrderBatchStatus.COMMITTED);
    }
}
