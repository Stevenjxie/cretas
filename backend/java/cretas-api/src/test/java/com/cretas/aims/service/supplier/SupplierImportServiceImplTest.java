package com.cretas.aims.service.supplier;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierImportReceipt;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.SupplierImportConfirmRequest;
import com.cretas.aims.dto.supplier.SupplierImportPreviewDTO;
import com.cretas.aims.repository.SupplierImportReceiptRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierImportServiceImplTest {
    @Mock ExcelUtil excelUtil;
    @Mock SupplierRepository supplierRepository;
    @Mock SupplierImportReceiptRepository receiptRepository;
    @Mock SupplierService supplierService;
    SupplierImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierImportServiceImpl(excelUtil, supplierRepository, receiptRepository, supplierService);
        when(supplierRepository.findByFactoryId("F006")).thenReturn(List.of());
    }

    @Test
    void smartModeRecognizesAliasesAndSeparatesValidErrorDuplicateAndBlankRows() {
        List<String> headers = List.of("供货商", "负责人", "手机", "联系地址", "纳税人识别号");
        Map<String, String> valid = row(headers, "甲供应商", "张三", "13812345678", "上海市浦东新区1号", "TAX-1");
        Map<String, String> duplicate = row(headers, "甲供应商", "李四", "021-61234567", "上海市浦东新区2号", "TAX-2");
        Map<String, String> invalid = row(headers, "乙供应商", "王五", "123", "---", "TAX-3");
        Map<String, String> blank = row(headers, "", "", "", "", "");
        when(excelUtil.readFirstSheetAsRows(any(InputStream.class), eq(1000)))
                .thenReturn(new ExcelUtil.RawSheet(headers, List.of(valid, duplicate, invalid, blank)));

        var preview = service.preview("F006", new byte[]{1, 2, 3}, "SMART", null);

        assertThat(preview.getCounts()).containsEntry("valid", 1).containsEntry("duplicate", 1)
                .containsEntry("error", 1).containsEntry("ignored", 1);
        assertThat(preview.getMappings()).extracting("targetField")
                .contains("name", "contactPerson", "phone", "address", "taxNumber");
        assertThat(preview.getRows().get(2).getErrors()).containsKeys("phone", "address");
    }

    @Test
    void databaseDuplicateIsScopedToCurrentFactory() {
        Supplier existing = new Supplier();
        existing.setName("甲供应商"); existing.setTaxNumber("TAX-1");
        when(supplierRepository.findByFactoryId("F006")).thenReturn(List.of(existing));
        List<String> headers = List.of("供应商名称", "联系人", "联系电话", "地址", "税号");
        when(excelUtil.readFirstSheetAsRows(any(InputStream.class), eq(1000)))
                .thenReturn(new ExcelUtil.RawSheet(headers,
                        List.of(row(headers, "甲供应商", "张三", "13812345678", "上海市1号", "TAX-1"))));

        var preview = service.preview("F006", new byte[]{9}, "STANDARD", null);

        assertThat(preview.getRows()).singleElement().extracting("classification").isEqualTo("DUPLICATE");
    }

    @Test
    void confirmClaimsIdempotencyReceiptBeforeCreatingSuppliers() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("a".repeat(64)); request.setIdempotencyKey("import-1");
        SupplierImportPreviewDTO.SupplierRowData row = new SupplierImportPreviewDTO.SupplierRowData();
        row.setName("甲供应商"); row.setContactPerson("张三"); row.setPhone("13812345678");
        row.setAddress("上海市1号"); request.setRows(List.of(row));
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-1")).thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SupplierImportReceipt receipt = invocation.getArgument(0); receipt.setId("receipt-1"); return receipt;
        });
        when(supplierService.createSupplier(eq("F006"), any(), eq(1309L)))
                .thenReturn(SupplierDTO.builder().id("supplier-1").name("甲供应商").build());
        when(receiptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm("F006", request, 1309L);

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getReplayed()).isFalse();
        var order = inOrder(receiptRepository, supplierService);
        order.verify(receiptRepository).saveAndFlush(any());
        order.verify(supplierService).createSupplier(eq("F006"), any(), eq(1309L));
    }

    private Map<String, String> row(List<String> headers, String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), values[i]);
        return row;
    }
}
