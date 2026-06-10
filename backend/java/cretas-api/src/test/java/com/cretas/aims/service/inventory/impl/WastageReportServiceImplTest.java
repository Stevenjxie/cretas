package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateWastageReportRequest;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.WastageReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

/**
 * WastageReportServiceImpl 单元测试 (SP7 T3).
 *
 * <p>覆盖 16 个关键场景 (W2 角色码修复后扩展):
 * <ol>
 *   <li>照片强制: photoUrls null → 422</li>
 *   <li>照片强制: photoUrls "[]" → 422</li>
 *   <li>create 正常: 有照片 → DRAFT 创建成功</li>
 *   <li>WAREHOUSE 轨 + operator 角色 approve → 403</li>
 *   <li>FACTORY 轨 + finance_manager 角色 approve → 403 (错轨)</li>
 *   <li>WAREHOUSE 轨 finance_manager 角色 approve → 原子写 adjustment + 扣减库存</li>
 *   <li>approve 幂等: 非 PENDING_APPROVAL → 409</li>
 *   <li>approve 库存不足 → 422</li>
 *   <li>FACTORY 轨 production_manager 角色 approve → APPLIED</li>
 *   <li>factory_super_admin 可审批 WAREHOUSE 轨 → APPLIED</li>
 *   <li>factory_super_admin 可审批 FACTORY 轨 → APPLIED</li>
 *   <li>listPending: finance_manager → 仅 WAREHOUSE 轨</li>
 *   <li>listPending: production_manager → 仅 FACTORY 轨</li>
 *   <li>listPending: factory_super_admin → WAREHOUSE + FACTORY 双轨</li>
 *   <li>listPending: operator → 空 Page</li>
 *   <li>submit 设置 approverRole 为小写真实码 (非旧大写虚构码)</li>
 * </ol>
 */
@DisplayName("WastageReportServiceImpl 单元测试 (SP7)")
@ExtendWith(MockitoExtension.class)
class WastageReportServiceImplTest {

    @Mock private WastageReportRepository wastageReportRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialBatchAdjustmentRepository adjustmentRepo;

    @InjectMocks private WastageReportServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String BATCH_ID = "BATCH-123";

    // -------------------------------------------------------
    // 1. 照片强制: null → 422
    // -------------------------------------------------------
    @Test
    @DisplayName("T1: photoUrls null → 422 必须上传至少一张照片")
    void create_noPhotos_null_throws422() {
        CreateWastageReportRequest req = buildReq(WastageReport.TrackType.WAREHOUSE);
        req.setPhotoUrls(null);

        assertThatThrownBy(() -> service.create(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    // -------------------------------------------------------
    // 2. 照片强制: "[]" → 422
    // -------------------------------------------------------
    @Test
    @DisplayName("T2: photoUrls '[]' → 422 空数组不允许")
    void create_noPhotos_emptyArray_throws422() {
        CreateWastageReportRequest req = buildReq(WastageReport.TrackType.WAREHOUSE);
        req.setPhotoUrls("[]");

        assertThatThrownBy(() -> service.create(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片");
    }

    // -------------------------------------------------------
    // 3. create 正常: 有照片 → DRAFT
    // -------------------------------------------------------
    @Test
    @DisplayName("T3: 有照片 + 有效数据 → 创建 DRAFT 报损单")
    void create_validRequest_returnsDraft() {
        CreateWastageReportRequest req = buildReq(WastageReport.TrackType.WAREHOUSE);
        req.setPhotoUrls("[\"https://oss.example.com/photo1.jpg\"]");
        req.setWarehouseId("WH-001"); // WAREHOUSE 轨必填

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setMaterialTypeId("MT-001");
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(wastageReportRepo.save(any())).thenAnswer(inv -> {
            WastageReport r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID().toString());
            return r;
        });

        WastageReportDTO result = service.create(FACTORY_ID, req, USER_ID);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("DRAFT");
        verify(wastageReportRepo).save(any());
    }

    // -------------------------------------------------------
    // 4. WAREHOUSE 轨 + operator 角色 approve → 403
    // -------------------------------------------------------
    @Test
    @DisplayName("T4: WAREHOUSE 轨 + operator 角色 approve → 403")
    void approve_warehouseTrack_wrongRole_throws403() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL);
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.approve(report.getId(), FACTORY_ID, USER_ID, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // -------------------------------------------------------
    // 5. FACTORY 轨 + finance_manager 角色 approve → 403 (错轨)
    // -------------------------------------------------------
    @Test
    @DisplayName("T5: FACTORY 轨 + finance_manager 角色 approve → 403 (finance_manager 只能审 WAREHOUSE 轨)")
    void approve_factoryTrack_wrongRole_throws403() {
        WastageReport report = buildReport(WastageReport.TrackType.FACTORY, WastageReport.Status.PENDING_APPROVAL);
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.approve(report.getId(), FACTORY_ID, USER_ID, "finance_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // -------------------------------------------------------
    // 6. WAREHOUSE 轨 finance_manager 角色 approve → 原子写 adjustment + 扣减库存
    // -------------------------------------------------------
    @Test
    @DisplayName("T6: WAREHOUSE 轨 finance_manager approve → 写 adjustment + 扣减库存 + 状态 APPLIED")
    void approve_warehouseTrack_finance_writesAdjustmentAndUpdatesStock() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL);
        report.setWastageQty(new BigDecimal("10.0000"));
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepo().save(any())).thenReturn(batch);
        when(wastageReportRepo.save(any())).thenReturn(report);

        service.approve(report.getId(), FACTORY_ID, USER_ID, "finance_manager");

        // Verify adjustment written with negative quantity
        ArgumentCaptor<MaterialBatchAdjustment> adjCaptor = ArgumentCaptor.forClass(MaterialBatchAdjustment.class);
        verify(adjustmentRepo).save(adjCaptor.capture());
        MaterialBatchAdjustment adj = adjCaptor.getValue();
        assertThat(adj.getAdjustmentType()).isEqualTo("WASTAGE");
        assertThat(adj.getAdjustmentQuantity()).isNegative();
        assertThat(adj.getQuantityAfter().compareTo(new BigDecimal("90.00"))).isEqualTo(0);

        // Verify batch decremented
        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batchRepo()).save(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getReceiptQuantity().compareTo(new BigDecimal("90.00"))).isEqualTo(0);

        // Verify status → APPLIED (entity enum, not DTO string)
        ArgumentCaptor<WastageReport> rptCaptor = ArgumentCaptor.forClass(WastageReport.class);
        verify(wastageReportRepo, atLeastOnce()).save(rptCaptor.capture());
        // The last save should set APPLIED
        WastageReport lastSaved = rptCaptor.getAllValues().stream()
                .filter(r -> r.getStatus() == WastageReport.Status.APPLIED)
                .findFirst().orElse(null);
        assertThat(lastSaved).isNotNull();
    }

    // -------------------------------------------------------
    // 7. approve 错误状态 → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("T7: approve 状态非 PENDING_APPROVAL → 409")
    void approve_wrongStatus_throws409() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.DRAFT);
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.approve(report.getId(), FACTORY_ID, USER_ID, "finance_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(409);
    }

    // -------------------------------------------------------
    // 8. approve 库存不足 → 422
    // -------------------------------------------------------
    @Test
    @DisplayName("T8: 报损数量超过库存 → 422")
    void approve_exceedsStock_throws422() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL);
        report.setWastageQty(new BigDecimal("200.0000")); // more than stock 100
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approve(report.getId(), FACTORY_ID, USER_ID, "finance_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    // -------------------------------------------------------
    // 9. FACTORY 轨 production_manager 角色 approve → APPLIED
    // -------------------------------------------------------
    @Test
    @DisplayName("T9: FACTORY 轨 production_manager approve → 状态 APPLIED")
    void approve_factoryTrack_productionManager_succeeds() {
        WastageReport report = buildReport(WastageReport.TrackType.FACTORY, WastageReport.Status.PENDING_APPROVAL);
        report.setWastageQty(new BigDecimal("5.0000"));
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setReceiptQuantity(new BigDecimal("50.00"));
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(wastageReportRepo.save(any())).thenReturn(report);

        service.approve(report.getId(), FACTORY_ID, USER_ID, "production_manager");

        ArgumentCaptor<WastageReport> captor = ArgumentCaptor.forClass(WastageReport.class);
        verify(wastageReportRepo, atLeastOnce()).save(captor.capture());
        boolean hasApplied = captor.getAllValues().stream()
                .anyMatch(r -> r.getStatus() == WastageReport.Status.APPLIED);
        assertThat(hasApplied).isTrue();
    }

    // -------------------------------------------------------
    // 10. factory_super_admin 可审批 WAREHOUSE 轨 → APPLIED
    // -------------------------------------------------------
    @Test
    @DisplayName("T10: factory_super_admin 可审批 WAREHOUSE 轨 → APPLIED")
    void approve_superAdmin_warehouseTrack_succeeds() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL);
        report.setWastageQty(new BigDecimal("5.0000"));
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setReceiptQuantity(new BigDecimal("50.00"));
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(wastageReportRepo.save(any())).thenReturn(report);

        service.approve(report.getId(), FACTORY_ID, USER_ID, "factory_super_admin");

        ArgumentCaptor<WastageReport> captor = ArgumentCaptor.forClass(WastageReport.class);
        verify(wastageReportRepo, atLeastOnce()).save(captor.capture());
        boolean hasApplied = captor.getAllValues().stream()
                .anyMatch(r -> r.getStatus() == WastageReport.Status.APPLIED);
        assertThat(hasApplied).isTrue();
    }

    // -------------------------------------------------------
    // 11. factory_super_admin 可审批 FACTORY 轨 → APPLIED
    // -------------------------------------------------------
    @Test
    @DisplayName("T11: factory_super_admin 可审批 FACTORY 轨 → APPLIED")
    void approve_superAdmin_factoryTrack_succeeds() {
        WastageReport report = buildReport(WastageReport.TrackType.FACTORY, WastageReport.Status.PENDING_APPROVAL);
        report.setWastageQty(new BigDecimal("5.0000"));
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));

        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setReceiptQuantity(new BigDecimal("50.00"));
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(wastageReportRepo.save(any())).thenReturn(report);

        service.approve(report.getId(), FACTORY_ID, USER_ID, "factory_super_admin");

        ArgumentCaptor<WastageReport> captor = ArgumentCaptor.forClass(WastageReport.class);
        verify(wastageReportRepo, atLeastOnce()).save(captor.capture());
        boolean hasApplied = captor.getAllValues().stream()
                .anyMatch(r -> r.getStatus() == WastageReport.Status.APPLIED);
        assertThat(hasApplied).isTrue();
    }

    // -------------------------------------------------------
    // 12. listPending: finance_manager → 仅 WAREHOUSE 轨
    // -------------------------------------------------------
    @Test
    @DisplayName("T12: listPending finance_manager → 调用 findPendingByTrackTypes([WAREHOUSE])")
    void listPending_financeManager_queriesWarehouseOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        when(wastageReportRepo.findPendingByTrackTypes(
                eq(FACTORY_ID),
                eq(List.of(WastageReport.TrackType.WAREHOUSE)),
                eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        service.listPending(FACTORY_ID, "finance_manager", pageable);

        verify(wastageReportRepo).findPendingByTrackTypes(
                eq(FACTORY_ID),
                eq(List.of(WastageReport.TrackType.WAREHOUSE)),
                eq(pageable));
    }

    // -------------------------------------------------------
    // 13. listPending: production_manager → 仅 FACTORY 轨
    // -------------------------------------------------------
    @Test
    @DisplayName("T13: listPending production_manager → 调用 findPendingByTrackTypes([FACTORY])")
    void listPending_productionManager_queriesFactoryOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        when(wastageReportRepo.findPendingByTrackTypes(
                eq(FACTORY_ID),
                eq(List.of(WastageReport.TrackType.FACTORY)),
                eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        service.listPending(FACTORY_ID, "production_manager", pageable);

        verify(wastageReportRepo).findPendingByTrackTypes(
                eq(FACTORY_ID),
                eq(List.of(WastageReport.TrackType.FACTORY)),
                eq(pageable));
    }

    // -------------------------------------------------------
    // 14. listPending: factory_super_admin → WAREHOUSE + FACTORY 双轨
    // -------------------------------------------------------
    @Test
    @DisplayName("T14: listPending factory_super_admin → 调用 findPendingByTrackTypes([WAREHOUSE, FACTORY])")
    void listPending_superAdmin_queriesBothTracks() {
        Pageable pageable = PageRequest.of(0, 20);
        List<WastageReport.TrackType> bothTracks = List.of(
                WastageReport.TrackType.WAREHOUSE, WastageReport.TrackType.FACTORY);
        when(wastageReportRepo.findPendingByTrackTypes(eq(FACTORY_ID), eq(bothTracks), eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        service.listPending(FACTORY_ID, "factory_super_admin", pageable);

        verify(wastageReportRepo).findPendingByTrackTypes(eq(FACTORY_ID), eq(bothTracks), eq(pageable));
    }

    // -------------------------------------------------------
    // 15. listPending: operator → 空 Page，不调 repo
    // -------------------------------------------------------
    @Test
    @DisplayName("T15: listPending operator → 空 Page，不调 findPendingByTrackTypes")
    void listPending_operator_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);

        Page<WastageReportDTO> result = service.listPending(FACTORY_ID, "operator", pageable);

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        verify(wastageReportRepo, never()).findPendingByTrackTypes(any(), any(), any());
    }

    // -------------------------------------------------------
    // 16. submit 设置 approverRole 为小写真实码
    // -------------------------------------------------------
    @Test
    @DisplayName("T16: submit WAREHOUSE 轨 → approverRole 存 'finance_manager' 非旧 'FINANCE'")
    void submit_setsRealLowercaseApproverRole() {
        WastageReport report = buildReport(WastageReport.TrackType.WAREHOUSE, WastageReport.Status.DRAFT);
        when(wastageReportRepo.findById(any())).thenReturn(Optional.of(report));
        when(wastageReportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submit(report.getId(), FACTORY_ID, USER_ID);

        ArgumentCaptor<WastageReport> captor = ArgumentCaptor.forClass(WastageReport.class);
        verify(wastageReportRepo).save(captor.capture());
        assertThat(captor.getValue().getApproverRole()).isEqualTo("finance_manager");
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private MaterialBatchRepository batchRepo() {
        return materialBatchRepo;
    }

    private CreateWastageReportRequest buildReq(WastageReport.TrackType trackType) {
        CreateWastageReportRequest req = new CreateWastageReportRequest();
        req.setTrackType(trackType);
        req.setMaterialBatchId(BATCH_ID);
        req.setWastageQty(new BigDecimal("10.0000"));
        req.setWastageReason(WastageReport.WastageReason.EXPIRED);
        req.setPhotoUrls("[\"url\"]");
        return req;
    }

    private WastageReport buildReport(WastageReport.TrackType trackType, WastageReport.Status status) {
        WastageReport r = new WastageReport();
        r.setId(UUID.randomUUID().toString());
        r.setFactoryId(FACTORY_ID);
        r.setReportNo("WR-20260601-001");
        r.setTrackType(trackType);
        r.setMaterialBatchId(BATCH_ID);
        r.setWastageQty(new BigDecimal("10.0000"));
        r.setWastageReason(WastageReport.WastageReason.EXPIRED);
        r.setPhotoUrls("[\"url\"]");
        r.setStatus(status);
        return r;
    }
}
