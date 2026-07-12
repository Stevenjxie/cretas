package com.cretas.aims.logistics.service.importjob;

import com.alibaba.excel.EasyExcel;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.dto.resource.StoreMasterUpdateRequest;
import com.cretas.aims.logistics.entity.LogisticsStoreMaster;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.StoreMasterSource;
import com.cretas.aims.logistics.repository.LogisticsDailyAvailabilityRepository;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsStoreMasterRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import com.cretas.aims.logistics.service.impl.LogisticsResourceServiceImpl;
import com.cretas.aims.logistics.service.importjob.impl.LogisticsOrderImportServiceImpl;
import com.cretas.aims.logistics.service.routing.AmapClient;
import com.cretas.aims.repository.VehicleRepository;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门店主数据 (store master) — 坐标"解析一次, 逐日复用"集成测试
 * ({@link LogisticsOrderImportServiceImpl#resolveOrderCoordinates}, 通过
 * {@code commit(...)} 触发)。
 *
 * <p>Mirrors {@link LogisticsOrderImportServiceImplTest} conventions: real H2 PG-compat DB
 * for repos, mocked {@link AmapClient} (never hit real Amap API in tests).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("LogisticsStoreMaster — resolve-once/reuse-forever integration via commit()")
class LogisticsStoreMasterTest {

    private static final String F1 = "F-LOG-STOREMASTER-1";

    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsStoreMasterRepository storeMasterRepo;

    private final ExcelUtil excelUtil = new ExcelUtil();
    private final AmapClient amapClient = mock(AmapClient.class);
    private LogisticsOrderImportServiceImpl service;

    private LogisticsOrderImportServiceImpl service() {
        if (service == null) {
            service = new LogisticsOrderImportServiceImpl(batchRepo, orderRepo, excelUtil, amapClient, storeMasterRepo);
        }
        return service;
    }

    // ==================== helpers ====================

    private LogisticsOrderImportRow rowNoCoord(String storeCode, String storeName, String address) {
        LogisticsOrderImportRow r = new LogisticsOrderImportRow();
        r.setBusinessDate("2026-07-12");
        r.setStoreCode(storeCode);
        r.setStoreName(storeName);
        r.setAddress(address);
        r.setPieces("10");
        r.setBoxes("2");
        r.setWeightKg("50.5");
        r.setVolumeCbm("1.2");
        r.setAreaCode("AREA-A");
        return r;
    }

    private LogisticsOrderImportRow rowWithCoord(String storeCode, String storeName, String address,
            String lon, String lat) {
        LogisticsOrderImportRow r = rowNoCoord(storeCode, storeName, address);
        r.setLongitude(lon);
        r.setLatitude(lat);
        return r;
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

    private String commitAndGetJobId(List<LogisticsOrderImportRow> rows) {
        PreviewResultDto preview = service().preview(F1, buildFile(rows), 1L);
        service().commit(F1, preview.getJobId());
        return preview.getJobId();
    }

    // ==================== 1) reuse from existing store master ====================

    @Test
    @DisplayName("门店已在主数据中(有坐标) → 订单直接复用坐标, 不调用 geocode, RESOLVED")
    void reusesCoordinatesFromExistingStoreMaster() {
        storeMasterRepo.save(LogisticsStoreMaster.builder()
                .factoryId(F1)
                .storeName("永辉园区店")
                .address("苏州市工业园区旧地址")
                .longitude(new BigDecimal("120.700000"))
                .latitude(new BigDecimal("31.300000"))
                .locationStatus(LocationStatus.RESOLVED)
                .source(StoreMasterSource.GEOCODED)
                .build());

        String jobId = commitAndGetJobId(List.of(
                rowNoCoord("SM-1", "永辉园区店", "苏州市工业园区新地址(与主数据不同, 应仍用主数据坐标)")));

        var order = orderRepo.findByFactoryIdAndBatchId(F1, jobId).get(0);
        assertThat(order.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
        assertThat(order.getLongitude()).isEqualByComparingTo("120.700000");
        assertThat(order.getLatitude()).isEqualByComparingTo("31.300000");
        verify(amapClient, never()).geocode(anyString());
    }

    // ==================== 2) new store → geocode once, upsert store master ====================

    @Test
    @DisplayName("门店首次出现(不在主数据) → geocode 一次, 订单 RESOLVED, 门店主数据 upsert source=GEOCODED")
    void newStoreGeocodesOnceAndSeedsStoreMaster() {
        when(amapClient.geocode(eq("苏州市工业园区新店地址")))
                .thenReturn(Optional.of(new double[] {120.65, 31.32}));

        String jobId = commitAndGetJobId(List.of(rowNoCoord("SM-2", "新开的门店", "苏州市工业园区新店地址")));

        var order = orderRepo.findByFactoryIdAndBatchId(F1, jobId).get(0);
        assertThat(order.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
        verify(amapClient, times(1)).geocode(anyString());

        var master = storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(F1, "新开的门店").orElseThrow();
        assertThat(master.getLongitude()).isEqualByComparingTo("120.65");
        assertThat(master.getLatitude()).isEqualByComparingTo("31.32");
        assertThat(master.getSource()).isEqualTo(StoreMasterSource.GEOCODED);
        assertThat(master.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
    }

    // ==================== 3) row already has coords → seeds store master, no geocode ====================

    @Test
    @DisplayName("导入行自带坐标 → 门店主数据 upsert source=IMPORT, 不调用 geocode")
    void rowWithProvidedCoordsSeedsStoreMasterWithoutGeocode() {
        String jobId = commitAndGetJobId(List.of(
                rowWithCoord("SM-3", "带坐标的门店", "苏州市工业园区带坐标地址", "120.80", "31.40")));

        var order = orderRepo.findByFactoryIdAndBatchId(F1, jobId).get(0);
        assertThat(order.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);
        verify(amapClient, never()).geocode(anyString());

        var master = storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(F1, "带坐标的门店").orElseThrow();
        assertThat(master.getLongitude()).isEqualByComparingTo("120.80");
        assertThat(master.getLatitude()).isEqualByComparingTo("31.40");
        assertThat(master.getSource()).isEqualTo(StoreMasterSource.IMPORT);
    }

    // ==================== 4) geocode fails → stays UNRESOLVED, no fabricated master row ====================

    @Test
    @DisplayName("geocode 失败 → 订单保持 UNRESOLVED, 不创建/污染门店主数据坐标")
    void geocodeFailureLeavesOrderUnresolvedAndNoFabricatedMaster() {
        when(amapClient.geocode(anyString())).thenReturn(Optional.empty());

        String jobId = commitAndGetJobId(List.of(rowNoCoord("SM-4", "无法识别的门店", "无法识别的地址")));

        var order = orderRepo.findByFactoryIdAndBatchId(F1, jobId).get(0);
        assertThat(order.getLocationStatus()).isEqualTo(LocationStatus.UNRESOLVED);
        assertThat(order.getLongitude()).isNull();
        assertThat(order.getLatitude()).isNull();

        assertThat(storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(F1, "无法识别的门店")).isEmpty();
    }

    // ==================== 5) reuse does not consume the geocode cap ====================

    @Test
    @DisplayName("N 家门店全在主数据中(N > GEOCODE_ON_COMMIT_CAP=50) → 0 次 geocode 调用")
    void reuseDoesNotConsumeGeocodeCap() {
        int n = 60; // > GEOCODE_ON_COMMIT_CAP (50)
        List<LogisticsOrderImportRow> rows = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String storeName = "批量门店" + i;
            storeMasterRepo.save(LogisticsStoreMaster.builder()
                    .factoryId(F1)
                    .storeName(storeName)
                    .longitude(new BigDecimal("120.6" + String.format("%02d", i % 10)))
                    .latitude(new BigDecimal("31.3" + String.format("%02d", i % 10)))
                    .locationStatus(LocationStatus.RESOLVED)
                    .source(StoreMasterSource.GEOCODED)
                    .build());
            rows.add(rowNoCoord("SM-BULK-" + i, storeName, "苏州市工业园区" + i + "号"));
        }

        String jobId = commitAndGetJobId(rows);

        var orders = orderRepo.findByFactoryIdAndBatchId(F1, jobId);
        assertThat(orders).hasSize(n);
        assertThat(orders).allMatch(o -> o.getLocationStatus() == LocationStatus.RESOLVED);
        verify(amapClient, never()).geocode(anyString());
    }

    // ==================== 6) manual correction via updateStoreMaster-equivalent semantics ====================

    @Test
    @DisplayName("已存在 MANUAL 修正的门店主数据 → 自动路径(GEOCODED/IMPORT)绝不覆盖其坐标")
    void manualCorrectionIsNotOverwrittenByAutomaticPaths() {
        storeMasterRepo.save(LogisticsStoreMaster.builder()
                .factoryId(F1)
                .storeName("已人工修正门店")
                .longitude(new BigDecimal("121.111111"))
                .latitude(new BigDecimal("32.222222"))
                .locationStatus(LocationStatus.RESOLVED)
                .source(StoreMasterSource.MANUAL)
                .build());

        // 该订单自带(不同的)坐标 —— 会触发 IMPORT 路径尝试 upsert，但因既有记录是 MANUAL 应被跳过
        commitAndGetJobId(List.of(
                rowWithCoord("SM-6", "已人工修正门店", "某地址", "999.999999", "888.888888")));

        var master = storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(F1, "已人工修正门店").orElseThrow();
        assertThat(master.getSource()).isEqualTo(StoreMasterSource.MANUAL);
        assertThat(master.getLongitude()).isEqualByComparingTo("121.111111");
        assertThat(master.getLatitude()).isEqualByComparingTo("32.222222");
    }

    // ==================== 7) PUT correction (service layer) ====================

    @Test
    @DisplayName("PUT 修正门店坐标(LogisticsResourceServiceImpl) → source=MANUAL, RESOLVED, version 递增")
    void putCorrectionSetsManualSourceAndBumpsVersion() {
        LogisticsStoreMaster saved = storeMasterRepo.saveAndFlush(LogisticsStoreMaster.builder()
                .factoryId(F1)
                .storeName("待修正门店")
                .locationStatus(LocationStatus.UNRESOLVED)
                .source(StoreMasterSource.GEOCODED)
                .build());
        assertThat(saved.getVersion()).isEqualTo(0L);

        var resourceService = new LogisticsResourceServiceImpl(
                mock(VehicleRepository.class),
                mock(LogisticsVehicleProfileRepository.class),
                mock(LogisticsDriverRepository.class),
                mock(LogisticsVehicleDriverRepository.class),
                mock(LogisticsDailyAvailabilityRepository.class),
                storeMasterRepo);

        StoreMasterUpdateRequest request = new StoreMasterUpdateRequest();
        request.setLongitude(new BigDecimal("120.123456"));
        request.setLatitude(new BigDecimal("31.654321"));
        request.setVersion(0L);

        var dto = resourceService.updateStoreMaster(F1, saved.getId(), request);
        storeMasterRepo.flush(); // Hibernate only increments @Version in-memory once the UPDATE actually flushes

        assertThat(dto.getSource()).isEqualTo(StoreMasterSource.MANUAL);
        assertThat(dto.getLocationStatus()).isEqualTo(LocationStatus.RESOLVED);

        var afterCommit = storeMasterRepo.findByIdAndFactoryId(saved.getId(), F1).orElseThrow();
        assertThat(afterCommit.getSource()).isEqualTo(StoreMasterSource.MANUAL);
        assertThat(afterCommit.getVersion()).isEqualTo(1L); // bumped by the optimistic-lock UPDATE
    }
}
