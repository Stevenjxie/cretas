package com.cretas.aims.service.productimport;

import com.cretas.aims.dto.producttype.importing.SkuImportPreviewDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.dto.producttype.ProductPackagingSpecDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkuImportServiceImplTest {

    @Mock ProductTypeRepository repository;
    @Mock ProductPackagingSpecService packagingSpecService;
    @Mock ProductSpecificationConversionSyncService conversionSyncService;
    @Mock UnitContractService unitContractService;
    private SkuImportServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(unitContractService.normalize(org.mockito.ArgumentMatchers.eq("F006"),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    UnitDimension dimension = ("kg".equals(code) || "g".equals(code))
                            ? UnitDimension.MASS : UnitDimension.COUNT;
                    return new UnitNormalizationResult(code, code,
                            new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 3));
                });
        lenient().when(unitContractService.areEquivalent(org.mockito.ArgumentMatchers.eq("F006"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> SkuImportServiceImpl.normalizeUnit(invocation.getArgument(1))
                        .equals(SkuImportServiceImpl.normalizeUnit(invocation.getArgument(2))));
        service = new SkuImportServiceImpl(repository, new ObjectMapper(), packagingSpecService,
                conversionSyncService, unitContractService);
    }

    @Test
    void templateContainsFourAnnotatedSheetsAndContentMarkedExamples() throws Exception {
        byte[] bytes = service.createTemplate();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheet("成品")).isNotNull();
            assertThat(workbook.getSheet("半成品")).isNotNull();
            assertThat(workbook.getSheet("客户自带原料加工")).isNotNull();
            assertThat(workbook.getSheet("纯代工")).isNotNull();
            assertThat(workbook.getSheet("成品").getRow(0).getCell(1).getStringCellValue()).endsWith("*");
            assertThat(workbook.getSheet("成品").getRow(0).getCell(1).getCellComment()).isNotNull();
            assertThat(workbook.getSheet("成品").getRow(0).getCell(5).getStringCellValue()).isEqualTo("包装单位1");
            assertThat(workbook.getSheet("成品").getRow(0).getCell(7).getStringCellValue()).isEqualTo("包装单位2");
            assertThat(workbook.getSheet("成品").getRow(1).getCell(0).getStringCellValue()).isEqualTo("示例");
        }
    }

    @Test
    void previewNormalizesFullWidthSpacesAndConfirmPersistsImageAtomically() throws Exception {
        when(repository.existsByFactoryIdAndCode("F006", "FG-001")).thenReturn(false);
        MockMultipartFile file = validWorkbook("　fg- 001　", "　测试 产品　", "盒", "FG-001.png");
        String mappings = "[{\"skuCode\":\"FG-001\",\"fileName\":\"FG-001.png\","
                + "\"url\":\"https://media.example.com/F006/images/product-images/2026/07/16/a.png\"}]";

        SkuImportPreviewDTO preview = service.preview("F006", 9L, file, mappings);

        assertThat(preview.getInvalidRows()).isZero();
        assertThat(preview.getValidRows()).isEqualTo(1);
        assertThat(preview.getRows()).filteredOn(row -> "VALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> {
                    assertThat(row.getSkuCode()).isEqualTo("FG-001");
                    assertThat(row.getName()).isEqualTo("测试 产品");
                    assertThat(row.getSpecification())
                            .isEqualTo("200g/盒 50盒/箱 10kg/箱 200盒/框 40kg/框");
                    assertThat(row.getImageUrl()).contains("/F006/images/product-images/");
                });

        var result = service.confirm("F006", 9L, preview.getPreviewToken());
        assertThat(result.getCreatedCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductType>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllAndFlush(captor.capture());
        ProductType saved = captor.getValue().get(0);
        assertThat(saved.getCode()).isEqualTo("FG-001");
        assertThat(saved.getImageUrl()).contains("/F006/images/product-images/");
        assertThat(saved.getLevel1Unit()).isEqualTo("箱");
        assertThat(saved.getBoxConversionCoefficient()).isEqualByComparingTo("50");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPackagingSpecDTO>> specsCaptor = ArgumentCaptor.forClass(List.class);
        verify(packagingSpecService).replace(org.mockito.ArgumentMatchers.eq(saved), specsCaptor.capture());
        assertThat(specsCaptor.getValue()).hasSize(2);
        assertThat(specsCaptor.getValue()).extracting(ProductPackagingSpecDTO::packageUnit)
                .containsExactly("箱", "框");
        assertThat(specsCaptor.getValue()).extracting(ProductPackagingSpecDTO::defaultSpec)
                .containsExactly(true, false);
        verify(conversionSyncService).synchronize(saved);
    }

    @Test
    void previewWithMissingSheetReportsErrorAndNeverWrites() throws Exception {
        byte[] bytes = service.createTemplate();
        byte[] incomplete;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.removeSheetAt(workbook.getSheetIndex("纯代工"));
            workbook.write(out);
            incomplete = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "sku.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", incomplete);

        SkuImportPreviewDTO preview = service.preview("F006", 9L, file, null);

        assertThat(preview.getInvalidRows()).isGreaterThan(0);
        assertThat(preview.getErrors()).anyMatch(error -> "MISSING_SHEET".equals(error.getCode()));
        assertThatThrownBy(() -> service.confirm("F006", 9L, preview.getPreviewToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在错误");
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void anyInvalidRowBlocksConfirmInsteadOfImportingValidSubset() throws Exception {
        MockMultipartFile base = validWorkbook("FG-003", "有效成品", "盒");
        byte[] mixed;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base.getBytes()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("半成品").createRow(2);
            row.createCell(0).setCellValue("");
            row.createCell(1).setCellValue("SEMI-INVALID");
            row.createCell(2).setCellValue("");
            row.createCell(3).setCellValue("只");
            workbook.write(out);
            mixed = out.toByteArray();
        }
        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                new MockMultipartFile("file", "sku.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", mixed), null);

        assertThat(preview.getValidRows()).isZero();
        assertThat(preview.getInvalidRows()).isEqualTo(1);
        assertThatThrownBy(() -> service.confirm("F006", 9L, preview.getPreviewToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在错误");
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void urlOnlyImageMappingMayOmitFileNameAndIsStoredWithoutFetching() throws Exception {
        String mappings = "[{\"skuCode\":\"FG-004\",\"url\":\"https://cdn.example.com/products/fg-004.png\"}]";

        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                validWorkbook("FG-004", "外链图片成品", "盒"), mappings);

        assertThat(preview.getInvalidRows()).isZero();
        assertThat(preview.getRows()).filteredOn(row -> "VALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> {
                assertThat(row.getImageUrl()).isEqualTo("https://cdn.example.com/products/fg-004.png");
                assertThat(row.getMatchedImageName()).isNull();
                });
    }

    @Test
    void structuredSpecificationMismatchAndImageFilenameMismatchAreBothReported() throws Exception {
        String mappings = "[{\"skuCode\":\"FG-005\",\"fileName\":\"right.png\","
                + "\"url\":\"https://media.example.com/F006/images/product-images/2026/07/16/a.png\"}]";
        MockMultipartFile base = validWorkbook("FG-005", "不一致成品", "盒", "wrong.png");
        byte[] changed;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base.getBytes()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getSheet("成品").getRow(1).getCell(11).setCellValue("100g/盒 50盒/箱 5kg/箱");
            workbook.write(out);
            changed = out.toByteArray();
        }

        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                new MockMultipartFile("file", "sku.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", changed), mappings);

        assertThat(preview.getRows()).filteredOn(row -> "INVALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> assertThat(row.getErrors())
                        .extracting(error -> error.getCode())
                        .contains("SPEC_MISMATCH", "IMAGE_FILENAME_MISMATCH"));
    }

    @Test
    void packagingAliasesNormalizeAndSameUnitWithDifferentFactorIsAllowed() throws Exception {
        assertThat(SkuImportServiceImpl.normalizeUnit(" box ")).isEqualTo("箱");
        assertThat(SkuImportServiceImpl.normalizeUnit("case")).isEqualTo("箱");
        assertThat(SkuImportServiceImpl.normalizeUnit("carton")).isEqualTo("箱");
        assertThat(SkuImportServiceImpl.normalizeUnit("个")).isEqualTo("件");

        MockMultipartFile base = validWorkbook("FG-006", "重复包装", "盒");
        byte[] changed;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base.getBytes()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("成品").getRow(1);
            row.getCell(6).setCellValue("12");
            row.getCell(7).setCellValue("box");
            row.getCell(8).setCellValue("24");
            row.getCell(11).setCellValue("200g/盒 12盒/箱 2.4kg/箱 24盒/箱 4.8kg/箱");
            workbook.write(out);
            changed = out.toByteArray();
        }
        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                new MockMultipartFile("file", "sku.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", changed), null);

        assertThat(preview.getInvalidRows()).isZero();
        assertThat(preview.getRows()).filteredOn(row -> "VALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> assertThat(row.getSpecification())
                        .isEqualTo("200g/盒 12盒/箱 2.4kg/箱 24盒/箱 4.8kg/箱"));
    }

    @Test
    void identicalPackageUnitAndFactorAreRejectedAsDuplicate() throws Exception {
        MockMultipartFile base = validWorkbook("FG-008", "完全重复包装", "盒");
        byte[] changed;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base.getBytes()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("成品").getRow(1);
            row.getCell(6).setCellValue("12");
            row.getCell(7).setCellValue("box");
            row.getCell(8).setCellValue("12");
            workbook.write(out);
            changed = out.toByteArray();
        }

        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                new MockMultipartFile("file", "sku.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", changed), null);

        assertThat(preview.getRows()).filteredOn(row -> "INVALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> assertThat(row.getErrors())
                        .extracting(error -> error.getCode()).contains("DUPLICATE_PACKAGING"));
    }

    @Test
    void packagingUnitCannotEqualBaseAndQuantityMustBePositiveInteger() throws Exception {
        MockMultipartFile base = validWorkbook("FG-007", "包装冲突", "盒");
        byte[] changed;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base.getBytes()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("成品").getRow(1);
            row.getCell(5).setCellValue("盒");
            row.getCell(8).setCellValue("1.5");
            workbook.write(out);
            changed = out.toByteArray();
        }

        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                new MockMultipartFile("file", "sku.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", changed), null);

        assertThat(preview.getRows()).filteredOn(row -> "INVALID".equals(row.getStatus()))
                .singleElement().satisfies(row -> assertThat(row.getErrors())
                        .extracting(error -> error.getCode())
                        .contains("SAME_AS_BASE_UNIT", "INTEGER_REQUIRED"));
    }

    @Test
    void confirmRevalidatesConcurrentDuplicateAndConsumesToken() throws Exception {
        when(repository.existsByFactoryIdAndCode("F006", "FG-002")).thenReturn(false, true);
        SkuImportPreviewDTO preview = service.preview("F006", 9L,
                validWorkbook("FG-002", "并发测试", "盒"), null);

        assertThatThrownBy(() -> service.confirm("F006", 9L, preview.getPreviewToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(repository, never()).saveAllAndFlush(anyList());
        assertThatThrownBy(() -> service.confirm("F006", 9L, preview.getPreviewToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期或已确认");
    }

    private MockMultipartFile validWorkbook(String code, String name, String unit) throws Exception {
        return validWorkbook(code, name, unit, "");
    }

    private MockMultipartFile validWorkbook(String code, String name, String unit, String imageFileName) throws Exception {
        byte[] bytes = service.createTemplate();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("成品").getRow(1);
            row.getCell(0).setCellValue("");
            row.getCell(1).setCellValue(code);
            row.getCell(2).setCellValue(name);
            row.getCell(3).setCellValue(unit);
            row.getCell(4).setCellValue("200");
            row.getCell(12).setCellValue(imageFileName);
            workbook.write(out);
            return new MockMultipartFile("file", "sku.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
