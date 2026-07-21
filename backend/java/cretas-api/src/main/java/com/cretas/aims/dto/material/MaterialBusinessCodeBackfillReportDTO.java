package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory-scoped audit/backfill result for historical material business codes.
 *
 * <p>{@code displayCode} is derived output, not another persisted database column: it equals the
 * assigned business code when present and otherwise falls back to the immutable legacy code.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialBusinessCodeBackfillReportDTO {

    private String factoryId;
    private boolean dryRun;
    private int total;
    private int alreadyMapped;
    private int eligible;
    private int mapped;
    private int skipped;

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String materialId;
        private String legacyClassificationCode;
        private String l3SegmentCode;
        private String businessCode;
        private String displayCode;
        private String status;
        private String reason;
        private String prefixSource;
    }
}
