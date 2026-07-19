package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyShipmentMutationToolRemovalContractTest {

    private static final List<String> REMOVED_CLASSES = List.of(
            "ShipmentCancelTool",
            "ShipmentCompleteTool",
            "ShipmentConfirmTool",
            "ShipmentCreateTool",
            "ShipmentDeleteTool",
            "ShipmentNotifyWarehouseTool",
            "ShipmentStatusUpdateTool",
            "ShipmentUpdateTool"
    );

    private static final List<String> REMOVED_TOOL_NAMES = List.of(
            "shipment_cancel",
            "shipment_complete",
            "shipment_confirm",
            "shipment_create",
            "shipment_delete",
            "shipment_notify_warehouse",
            "shipment_status_update",
            "shipment_update"
    );

    @Test
    void oldMutationExecutorsAndDescriptorsAreGone() throws Exception {
        Path sourceDir = Path.of("src/main/java/com/cretas/aims/ai/tool/impl/shipment");
        String descriptors = Files.readString(
                Path.of("src/main/resources/ai/tool/gateway/tool-descriptors.yaml"),
                StandardCharsets.UTF_8);

        for (String className : REMOVED_CLASSES) {
            assertThat(sourceDir.resolve(className + ".java")).doesNotExist();
            assertThat(descriptors).doesNotContain("impl.shipment." + className);
        }
        for (String toolName : REMOVED_TOOL_NAMES) {
            assertThat(descriptors).doesNotContain("toolName: \"" + toolName + "\"");
        }
    }

    @Test
    void skillsUseTheInventoryAwareSalesDeliveryTool() throws Exception {
        String skillRegistry = Files.readString(
                Path.of("src/main/java/com/cretas/aims/service/skill/impl/SkillRegistryImpl.java"),
                StandardCharsets.UTF_8);

        assertThat(skillRegistry).contains("sales_create_delivery");
        assertThat(skillRegistry).doesNotContain("shipment-lifecycle");
        for (String toolName : REMOVED_TOOL_NAMES) {
            assertThat(skillRegistry).doesNotContain("\"" + toolName + "\"");
        }
    }

    @Test
    void migrationRebindsCreateDisablesUnsafeMutationsAndLeavesHistoryRowsUntouched() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/flyway/V20261028_80__freeze_legacy_shipment_ai_writes.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(migration).contains("tool_name = 'sales_create_delivery'");
        assertThat(migration).contains("is_active = false");
        assertThat(migration).contains("delete from public.tool_embeddings");
        assertThat(migration).doesNotContain("delete from public.shipment_records");
        assertThat(migration).doesNotContain("drop table");
    }
}
