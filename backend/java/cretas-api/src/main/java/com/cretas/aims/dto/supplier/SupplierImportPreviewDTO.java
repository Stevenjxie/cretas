package com.cretas.aims.dto.supplier;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierImportPreviewDTO {
    private String fileDigest;
    private String mode;
    private List<ColumnMapping> mappings;
    private List<Row> rows;
    private Map<String, Integer> counts;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ColumnMapping {
        private String sourceColumn;
        private String targetField;
        private Integer confidence;
        private Boolean required;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Row {
        private Integer rowNumber;
        /** VALID | DUPLICATE | ERROR | IGNORED. */
        private String classification;
        private SupplierRowData data;
        private Map<String, String> errors;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplierRowData {
        private String supplierCode;
        private String name;
        private String contactPerson;
        private String phone;
        private String email;
        private String address;
        private String bankAccount;
        private String taxNumber;
        private String notes;
    }
}
