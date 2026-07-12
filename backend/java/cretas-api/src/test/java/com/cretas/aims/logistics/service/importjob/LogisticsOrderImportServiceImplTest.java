package com.cretas.aims.logistics.service.importjob;

import com.alibaba.excel.EasyExcel;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;
import com.cretas.aims.logistics.dto.importjob.OrderBatchDto;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsStoreMasterRepository;
import com.cretas.aims.logistics.service.importjob.impl.LogisticsOrderImportServiceImpl;
import com.cretas.aims.logistics.service.routing.AmapClient;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 — service-level test for {@link LogisticsOrderImportServiceImpl} against a real
 * H2 PG-compat DB (mirrors {@link com.cretas.aims.logistics.repository.LogisticsOrderBatchRepositoryTest}
 * conventions) + real EasyExcel round trips (mirrors
 * {@code StocktakeBulkImportServiceImplTest} — write real xlsx bytes with the same
 * {@link LogisticsOrderImportRow} class used for reading, so header-name matching in
 * {@code ExcelUtil.importFromExcel} is exercised end-to-end, not mocked away).
 *
 * <p>Covers spec §8.2 / handoff §16.1 required cases:
 * <ol>
 *   <li>valid file → preview writes PREVIEWED batch + valid delivery orders</li>
 *   <li>each validation error type (missing field / non-numeric / negative / duplicate
 *       store code / lon-xor-lat / full-row duplicate) → precise row+column errors</li>
 *   <li>duplicate-fingerprint preview reuses the existing PREVIEWED batch (no duplicate rows)</li>
 *   <li>commit flips PREVIEWED→COMMITTED; re-commit is idempotent (no double write)</li>
 *   <li>commit on unknown/foreign jobId → 404</li>
 *   <li>updateLocation sets RESOLVED + rejects lon-without-lat</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("LogisticsOrderImportServiceImpl — preview/commit against real H2 PG-compat DB")
class LogisticsOrderImportServiceImplTest {

    private static final String F1 = "F-LOG-IMPORT-1";

    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsStoreMasterRepository storeMasterRepo;

    private final ExcelUtil excelUtil = new ExcelUtil(); // real impl, exercises real EasyExcel read/write
    // mocked — never hit the real Amap API in tests (per python-java-port.md style mocking convention)
    private final AmapClient amapClient = mock(AmapClient.class);
    private LogisticsOrderImportServiceImpl service;

    private LogisticsOrderImportServiceImpl service() {
        if (service == null) {
            service = new LogisticsOrderImportServiceImpl(batchRepo, orderRepo, excelUtil, amapClient, storeMasterRepo);
        }
        return service;
    }

    // ==================== helpers ====================

    private LogisticsOrderImportRow row(String businessDate, String storeCode, String storeName,
            String address, String pieces, String boxes, String weightKg, String volumeCbm,
            String windowStart, String windowEnd, String lon, String lat, String areaCode) {
        LogisticsOrderImportRow r = new LogisticsOrderImportRow();
        r.setBusinessDate(businessDate);
        r.setStoreCode(storeCode);
        r.setStoreName(storeName);
        r.setAddress(address);
        r.setPieces(pieces);
        r.setBoxes(boxes);
        r.setWeightKg(weightKg);
        r.setVolumeCbm(volumeCbm);
        r.setWindowStart(windowStart);
        r.setWindowEnd(windowEnd);
        r.setLongitude(lon);
        r.setLatitude(lat);
        r.setAreaCode(areaCode);
        return r;
    }

    private LogisticsOrderImportRow validRow(String storeCode, String storeName) {
        return row("2026-07-11", storeCode, storeName, "苏州市工业园区" + storeCode + "号",
                "10", "2", "50.5", "1.2", "08:00", "12:00", "120.6", "31.3", "AREA-A");
    }

    private MultipartFile buildFile(List<LogisticsOrderImportRow> rows) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            EasyExcel.write(out, LogisticsOrderImportRow.class).sheet("订单").doWrite(rows);
            return new MockMultipartFile("file", "orders.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== template ====================

    @Test
    @DisplayName("downloadTemplate — 非空 xlsx bytes")
    void downloadTemplateProducesBytes() {
        byte[] bytes = service().downloadTemplate();
        assertThat(bytes).isNotEmpty();
    }

    // ==================== preview: happy path ====================

    @Test
    @DisplayName("preview — 全部有效行 → PREVIEWED 批次 + 落库配送订单")
    void previewAllValidWritesOrders() {
        List<LogisticsOrderImportRow> rows = List.of(validRow("S001", "门店1"), validRow("S002", "门店2"));
        PreviewResultDto result = service().preview(F1, buildFile(rows), 1L);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getValidRows()).isEqualTo(2);
        assertThat(result.getErrorRows()).isZero();
        assertThat(result.getRowErrors()).isEmpty();
        assertThat(result.getBusinessDate()).isEqualTo("2026-07-11");
        assertThat(result.getJobId()).isNotBlank();

        var batch = batchRepo.findById(result.getJobId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(OrderBatchStatus.PREVIEWED);
        assertThat(batch.getValidRows()).isEqualTo(2);

        var orders = orderRepo.findByFactoryIdAndBatchId(F1, result.getJobId());
        assertThat(orders).hasSize(2);
        assertThat(orders).extracting("storeCode").containsExactlyInAnyOrder("S001", "S002");
        assertThat(orders).allMatch(o -> o.getLocationStatus() == LocationStatus.RESOLVED);
    }

    // ==================== preview: validation errors ====================

    @Test
    @DisplayName("preview — 缺必填字段 (门店名称为空) → 精确行号+列名错误, 不落库")
    void previewMissingRequiredField() {
        LogisticsOrderImportRow bad = validRow("S010", "");
        PreviewResultDto result = service().preview(F1, buildFile(List.of(bad)), 1L);

        assertThat(result.getValidRows()).isZero();
        assertThat(result.getErrorRows()).isEqualTo(1);
        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getRowNumber()).isEqualTo(1);
            assertThat(e.getColumn()).isEqualTo("门店名称");
            assertThat(e.getMessage()).contains("必填");
        });
        assertThat(orderRepo.findByFactoryIdAndBatchId(F1, result.getJobId())).isEmpty();
    }

    @Test
    @DisplayName("preview — 非数字件数 → 精确错误, 该行不计入 validRows")
    void previewNonNumericPieces() {
        LogisticsOrderImportRow bad = row("2026-07-11", "S011", "门店11", "地址11",
                "abc", "2", "50.5", "1.2", null, null, null, null, null);
        PreviewResultDto result = service().preview(F1, buildFile(List.of(bad)), 1L);

        assertThat(result.getErrorRows()).isEqualTo(1);
        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getColumn()).isEqualTo("件数");
            assertThat(e.getMessage()).contains("非数字");
        });
    }

    @Test
    @DisplayName("preview — 负数重量 → 必须为正数错误")
    void previewNegativeWeight() {
        LogisticsOrderImportRow bad = row("2026-07-11", "S012", "门店12", "地址12",
                "5", "1", "-10", "1.0", null, null, null, null, null);
        PreviewResultDto result = service().preview(F1, buildFile(List.of(bad)), 1L);

        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getColumn()).isEqualTo("重量kg");
            assertThat(e.getMessage()).contains("正数");
        });
    }

    @Test
    @DisplayName("preview — 同批订单号重复 → 第二次出现标记错误, 第一次仍有效")
    void previewDuplicateStoreCode() {
        List<LogisticsOrderImportRow> rows = List.of(validRow("S020", "门店A"), validRow("S020", "门店A-重复"));
        PreviewResultDto result = service().preview(F1, buildFile(rows), 1L);

        assertThat(result.getValidRows()).isEqualTo(1);
        assertThat(result.getErrorRows()).isEqualTo(1);
        assertThat(result.getRows().get(0).isValid()).isTrue();
        assertThat(result.getRows().get(1).isValid()).isFalse();
        assertThat(result.getRows().get(1).getErrors()).anySatisfy(e -> {
            assertThat(e.getRowNumber()).isEqualTo(2);
            assertThat(e.getColumn()).isEqualTo("订单号");
            assertThat(e.getMessage()).contains("重复");
        });
    }

    @Test
    @DisplayName("preview — 整行完全重复 (不同订单号但其余列相同不会误判; 这里用完全相同两行)")
    void previewFullRowDuplicate() {
        LogisticsOrderImportRow r1 = validRow("S030", "门店30");
        LogisticsOrderImportRow r2 = validRow("S030", "门店30");
        PreviewResultDto result = service().preview(F1, buildFile(List.of(r1, r2)), 1L);

        assertThat(result.getRows().get(1).getErrors()).anySatisfy(e -> {
            assertThat(e.getColumn()).isEqualTo("整行");
            assertThat(e.getMessage()).contains("完全重复");
        });
    }

    @Test
    @DisplayName("preview — 只填经度不填纬度 → XOR 错误")
    void previewLongitudeWithoutLatitude() {
        LogisticsOrderImportRow bad = row("2026-07-11", "S040", "门店40", "地址40",
                "5", "1", "10", "1.0", null, null, "120.5", null, null);
        PreviewResultDto result = service().preview(F1, buildFile(List.of(bad)), 1L);

        assertThat(result.getRowErrors()).anySatisfy(e -> {
            assertThat(e.getColumn()).isEqualTo("纬度");
            assertThat(e.getMessage()).contains("同时提供");
        });
    }

    @Test
    @DisplayName("preview — 空文件（零数据行）→ 400")
    void previewEmptyFileRejected() {
        assertThatThrownBy(() -> service().preview(F1, buildFile(List.of()), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不包含任何数据行");
    }

    @Test
    @DisplayName("preview — 全部行业务日期缺失 → 400 (无法确定批次归属日)")
    void previewAllRowsMissingBusinessDate() {
        LogisticsOrderImportRow bad = row(null, "S050", "门店50", "地址50",
                "5", "1", "10", "1.0", null, null, null, null, null);
        assertThatThrownBy(() -> service().preview(F1, buildFile(List.of(bad)), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务日期");
    }

    // ==================== preview: idempotency ====================

    @Test
    @DisplayName("preview — 相同文件内容重复上传 → 复用既有 PREVIEWED 批次, 不产生第二个批次/重复订单")
    void previewDuplicateFingerprintReusesBatch() throws Exception {
        List<LogisticsOrderImportRow> rows = List.of(validRow("S060", "门店60"), validRow("S061", "门店61"));
        MultipartFile file1 = buildFile(rows);
        PreviewResultDto first = service().preview(F1, file1, 1L);

        // 字节完全一致的第二次上传 (相同内容 → 相同 sha256 指纹)
        MultipartFile file2 = new MockMultipartFile("file", "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ((MockMultipartFile) file1).getBytes());
        PreviewResultDto second = service().preview(F1, file2, 1L);

        assertThat(second.getJobId()).isEqualTo(first.getJobId());
        assertThat(batchRepo.findByFactoryIdOrderByBusinessDateDesc(F1)).hasSize(1);
        assertThat(orderRepo.findByFactoryIdAndBatchId(F1, first.getJobId())).hasSize(2);
    }

    @Test
    @DisplayName("preview — 已 COMMITTED 批次再次收到相同内容上传 → 只读复用, 不重写其订单")
    void previewOnCommittedBatchDoesNotRewriteOrders() throws Exception {
        List<LogisticsOrderImportRow> rows = List.of(validRow("S070", "门店70"));
        MultipartFile file1 = buildFile(rows);
        PreviewResultDto first = service().preview(F1, file1, 1L);
        service().commit(F1, first.getJobId());

        var beforeOrders = orderRepo.findByFactoryIdAndBatchId(F1, first.getJobId());
        assertThat(beforeOrders).hasSize(1);
        String orderIdBefore = beforeOrders.get(0).getId();

        MultipartFile file2 = new MockMultipartFile("file", "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ((MockMultipartFile) file1).getBytes());
        PreviewResultDto second = service().preview(F1, file2, 1L);

        assertThat(second.getJobId()).isEqualTo(first.getJobId());
        var afterOrders = orderRepo.findByFactoryIdAndBatchId(F1, first.getJobId());
        assertThat(afterOrders).hasSize(1);
        assertThat(afterOrders.get(0).getId()).isEqualTo(orderIdBefore); // 未被替换/新建
    }

    // ==================== commit ====================

    @Test
    @DisplayName("commit — PREVIEWED → COMMITTED")
    void commitFlipsStatus() {
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(validRow("S080", "门店80"))), 1L);
        OrderBatchDto committed = service().commit(F1, preview.getJobId());

        assertThat(committed.getStatus()).isEqualTo(OrderBatchStatus.COMMITTED);
        assertThat(batchRepo.findById(preview.getJobId()).orElseThrow().getStatus())
                .isEqualTo(OrderBatchStatus.COMMITTED);
    }

    @Test
    @DisplayName("commit — 幂等: 重复提交同一 jobId 不重复写入, 返回同一批次")
    void commitIsIdempotent() {
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(validRow("S090", "门店90"))), 1L);
        OrderBatchDto first = service().commit(F1, preview.getJobId());
        OrderBatchDto second = service().commit(F1, preview.getJobId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(OrderBatchStatus.COMMITTED);
        assertThat(orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId())).hasSize(1); // 无重复写入
    }

    @Test
    @DisplayName("commit — 未知 jobId → 404")
    void commitUnknownJobId404() {
        assertThatThrownBy(() -> service().commit(F1, "nonexistent-job-id"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("commit — 跨租户 jobId (属于另一工厂) → 404, 不泄露")
    void commitCrossTenantJobId404() {
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(validRow("S095", "门店95"))), 1L);
        assertThatThrownBy(() -> service().commit("F-OTHER-FACTORY", preview.getJobId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== commit: auto-geocode (Phase 4 — Amap integration) ====================

    @Test
    @DisplayName("commit — 有地址无坐标的订单 → 自动 geocode 成功后 RESOLVED + 落库经纬度")
    void commitGeocodesUnresolvedOrderWithAddress() {
        LogisticsOrderImportRow noCoord = row("2026-07-11", "S200", "门店200", "苏州市工业园区200号",
                "5", "1", "10", "1.0", null, null, null, null, null); // 无经纬度, 有地址
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(noCoord)), 1L);
        var beforeOrder = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);
        assertThat(beforeOrder.getLocationStatus()).isEqualTo(LocationStatus.UNRESOLVED);

        when(amapClient.geocode(eq("苏州市工业园区200号")))
                .thenReturn(Optional.of(new double[] {120.65, 31.32}));

        service().commit(F1, preview.getJobId());

        var afterOrder = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);
        assertThat(afterOrder.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
        assertThat(afterOrder.getLongitude()).isEqualByComparingTo("120.65");
        assertThat(afterOrder.getLatitude()).isEqualByComparingTo("31.32");
        verify(amapClient, times(1)).geocode(anyString());
    }

    @Test
    @DisplayName("commit — geocode 查询失败/无结果 → 诚实保留 UNRESOLVED, 绝不伪造坐标")
    void commitLeavesUnresolvedWhenGeocodeFails() {
        LogisticsOrderImportRow noCoord = row("2026-07-11", "S201", "门店201", "无法识别的地址201",
                "5", "1", "10", "1.0", null, null, null, null, null);
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(noCoord)), 1L);

        when(amapClient.geocode(anyString())).thenReturn(Optional.empty());

        service().commit(F1, preview.getJobId());

        var afterOrder = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);
        assertThat(afterOrder.getLocationStatus()).isEqualTo(LocationStatus.UNRESOLVED);
        assertThat(afterOrder.getLongitude()).isNull();
        assertThat(afterOrder.getLatitude()).isNull();
    }

    @Test
    @DisplayName("commit — 订单已有坐标 → 不调用 geocode (跳过已解析订单)")
    void commitSkipsGeocodeForAlreadyResolvedOrder() {
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(validRow("S202", "门店202"))), 1L);
        var beforeOrder = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);
        assertThat(beforeOrder.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED); // validRow 自带经纬度

        service().commit(F1, preview.getJobId());

        verify(amapClient, never()).geocode(anyString());
    }

    // ==================== updateLocation ====================

    @Test
    @DisplayName("updateLocation — 同时提供经纬度 → RESOLVED")
    void updateLocationResolves() {
        LogisticsOrderImportRow unresolvedRow = row("2026-07-11", "S100", "门店100", "地址100",
                "5", "1", "10", "1.0", null, null, null, null, null); // no lon/lat
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(unresolvedRow)), 1L);
        var order = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);
        assertThat(order.getLocationStatus()).isEqualTo(LocationStatus.UNRESOLVED);

        DeliveryOrderDto updated = service().updateLocation(F1, order.getId(),
                new java.math.BigDecimal("120.6"), new java.math.BigDecimal("31.3"));

        assertThat(updated.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
        assertThat(updated.getLongitude()).isEqualByComparingTo("120.6");
        assertThat(updated.getLatitude()).isEqualByComparingTo("31.3");
    }

    @Test
    @DisplayName("updateLocation — 只提供经度 → 400")
    void updateLocationRequiresBoth() {
        PreviewResultDto preview = service().preview(F1, buildFile(List.of(validRow("S110", "门店110"))), 1L);
        var order = orderRepo.findByFactoryIdAndBatchId(F1, preview.getJobId()).get(0);

        assertThatThrownBy(() -> service().updateLocation(F1, order.getId(), new java.math.BigDecimal("120.6"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("经度和纬度必须同时提供");
    }

    // ==================== list/get ====================

    @Test
    @DisplayName("listBatches / getBatch / listOrders — 分页 + 租户隔离")
    void listAndGet() {
        service().preview(F1, buildFile(List.of(validRow("S120", "门店120"))), 1L);
        PreviewResultDto p2 = service().preview(F1, buildFile(List.of(validRow("S121", "门店121"))), 1L);

        Page<OrderBatchDto> batches = service().listBatches(F1, PageRequest.of(0, 20));
        assertThat(batches.getTotalElements()).isEqualTo(2);

        OrderBatchDto fetched = service().getBatch(F1, p2.getJobId());
        assertThat(fetched.getId()).isEqualTo(p2.getJobId());

        Page<DeliveryOrderDto> orders = service().listOrders(F1, p2.getJobId(), PageRequest.of(0, 20));
        assertThat(orders.getTotalElements()).isEqualTo(1);

        assertThatThrownBy(() -> service().getBatch("F-OTHER", p2.getJobId()))
                .isInstanceOf(BusinessException.class);
    }
}
