package com.cretas.aims.service.impl;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP4-T5: 物料批次标签生成 + 扫码查询.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP4-T5 LabelService: generateMaterialBatchLabel + scanLabel")
class LabelServiceSp4T5Test {

    @Mock
    LabelRepository labelRepository;

    @Mock
    MaterialBatchRepository materialBatchRepository;

    @InjectMocks
    LabelServiceImpl labelService;

    private MaterialBatch batch;
    private static final String FACTORY_ID = "F006";
    private static final String BATCH_ID = "MB-2026-001";

    @BeforeEach
    void setUp() {
        batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("YL-001");
        batch.setFactoryNumber("FN-LIUSHANMEN-01");
        batch.setOriginPlace("昆山市张家港路");
        batch.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
    }

    // ──────────────── generateMaterialBatchLabel ────────────────

    @Test
    @DisplayName("T5-G1: 正常生成 — 批次存在, 无已有标签 → 创建并返回 ACTIVE 标签")
    void generateLabel_happyPath() {
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 42L);

        assertNotNull(result);
        assertEquals("MATERIAL", result.getBatchType());
        assertEquals(BATCH_ID, result.getBatchId());
        assertEquals(FACTORY_ID, result.getFactoryId());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(0, result.getPrintCount());
        assertEquals(42L, result.getCreatedBy());
    }

    @Test
    @DisplayName("T5-G2: 批次不存在 → 抛 RuntimeException")
    void generateLabel_batchNotFound() {
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L));
    }

    @Test
    @DisplayName("T5-G3: 批次已有 ACTIVE 标签 → 409 (重复生成防呆)")
    void generateLabel_duplicateActiveLabel_throws409() {
        Label existingLabel = new Label();
        existingLabel.setId("existing-label-id");
        existingLabel.setBatchId(BATCH_ID);
        existingLabel.setStatus("ACTIVE");

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of(existingLabel));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L));
        assertTrue(ex.getMessage().contains("已存在") || ex.getMessage().contains("already"),
                "Exception message should indicate duplicate label: " + ex.getMessage());
    }

    // ──────────────── scanLabel ────────────────

    @Test
    @DisplayName("T5-S1: 扫码成功 — ACTIVE 标签 → 返回完整批次信息")
    void scanLabel_activeLabel_returnsFullResponse() {
        String labelCode = "QR-F006-20261002120000-0001";
        Label label = new Label();
        label.setId("label-id-001");
        label.setFactoryId(FACTORY_ID);
        label.setLabelCode(labelCode);
        label.setBatchType("MATERIAL");
        label.setBatchId(BATCH_ID);
        label.setStatus("ACTIVE");
        label.setTraceCode("TRACE-F006-20261002-000001");
        label.setCreatedAt(LocalDateTime.of(2026, 10, 2, 10, 0));

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(label));
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));

        MaterialBatchLabelScanResponse resp = labelService.scanLabel(FACTORY_ID, labelCode);

        assertNotNull(resp);
        assertEquals("label-id-001", resp.getLabelId());
        assertEquals(labelCode, resp.getLabelCode());
        assertEquals("ACTIVE", resp.getLabelStatus());
        assertEquals(BATCH_ID, resp.getBatchId());
        assertEquals("YL-001", resp.getBatchNumber());
        assertEquals("FN-LIUSHANMEN-01", resp.getFactoryNumber());
        assertEquals("昆山市张家港路", resp.getOriginPlace());
        assertEquals("TRACE-F006-20261002-000001", resp.getTraceCode());
    }

    @Test
    @DisplayName("T5-S2: 标签不存在 → 抛 RuntimeException")
    void scanLabel_notFound() {
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull("UNKNOWN-CODE"))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, "UNKNOWN-CODE"));
    }

    @Test
    @DisplayName("T5-S3: VOIDED 标签 → 抛 RuntimeException (禁止使用废标签)")
    void scanLabel_voidedLabel_throws() {
        String labelCode = "VOIDED-CODE";
        Label voidedLabel = new Label();
        voidedLabel.setId("voided-id");
        voidedLabel.setFactoryId(FACTORY_ID);
        voidedLabel.setLabelCode(labelCode);
        voidedLabel.setBatchId(BATCH_ID);
        voidedLabel.setStatus("VOIDED");

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(voidedLabel));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, labelCode));
        assertTrue(ex.getMessage().contains("VOIDED") || ex.getMessage().contains("作废"),
                "Exception message should indicate voided status: " + ex.getMessage());
    }

    // ──────────────── SP4-A3: SP8 primaryCode 前缀 ────────────────

    @Test
    @DisplayName("T5-A3-1: SP8 primaryCode='001' → 标签前缀取前2字符 '00' (generateLabelCode substring(0,2))")
    void generateLabel_withSp8PrimaryCode_usesPrefixFromMaterialType() {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setPrimaryCode("001");
        batch.setMaterialType(materialType);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result);
        assertNotNull(result.getLabelCode(), "labelCode should be generated");
        // generateLabelCode internally does labelType.substring(0, 2)
        // SP8 primaryCode "001" → substring(0,2) → "00" as prefix in label code
        assertTrue(result.getLabelCode().startsWith("00"),
                "label code should start with '00' (primaryCode '001' substring(0,2)), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-A3-2: primaryCode=null + 类别'原料' → fallback 类别前缀 'YL' (非旧硬编码 MA)")
    void generateLabel_nullPrimaryCode_fallsBackToCategoryPrefix() {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setPrimaryCode(null); // null → fallback 走类别前缀
        materialType.setCategory("原料");   // 原料 → YL
        batch.setMaterialType(materialType);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result.getLabelCode(), "labelCode should be generated");
        // 修(Gate2 靶6): primaryCode 空时用 SP4 类别前缀, 原料→YL, 不再硬编码 MA
        assertTrue(result.getLabelCode().startsWith("YL"),
                "label code should start with 'YL' (类别前缀 fallback), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-A3-3: materialType=null → fallback 前缀 'WL' (其他/未知类别, 非旧 MA)")
    void generateLabel_noMaterialType_fallsBackToWL() {
        // batch has no materialType set (null) → 类别未知 → WL
        batch.setMaterialType(null);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result.getLabelCode());
        assertTrue(result.getLabelCode().startsWith("WL"),
                "label code should start with 'WL' (fallback when materialType null), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-S4: 标签跨工厂访问 → 抛 RuntimeException")
    void scanLabel_crossFactoryAccess_throws() {
        String labelCode = "QR-OTHER-FACTORY";
        Label label = new Label();
        label.setId("label-id-other");
        label.setFactoryId("F999"); // different factory
        label.setLabelCode(labelCode);
        label.setStatus("ACTIVE");
        label.setBatchId(BATCH_ID);

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(label));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, labelCode));
        assertTrue(ex.getMessage().contains("工厂") || ex.getMessage().contains("factory") ||
                        ex.getMessage().contains("权限") || ex.getMessage().contains("access"),
                "Exception should indicate factory mismatch: " + ex.getMessage());
    }
}
