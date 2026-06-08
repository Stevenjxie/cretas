package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * T145 — verifies 箱数 (box_count) wiring through {@link MaterialBatchMapper}.
 *
 * <p>箱数 is a 粗略统计/display-only field. These tests assert it round-trips
 * create → entity → DTO, edits via update, stays null when omitted, and — by
 * construction — never touches any kg/weight/price calculation (the mapper's
 * unitPrice/weight math reads receiptQuantity/totalWeight/totalValue only).</p>
 */
class MaterialBatchMapperBoxCountTest {

    private final MaterialBatchMapper mapper = new MaterialBatchMapper();

    private CreateMaterialBatchRequest baseCreateRequest() {
        CreateMaterialBatchRequest req = new CreateMaterialBatchRequest();
        req.setMaterialTypeId("mt-1");
        req.setSupplierId("sup-1");
        req.setReceiptDate(LocalDate.of(2026, 6, 8));
        req.setReceiptQuantity(new BigDecimal("200"));
        req.setQuantityUnit("kg");
        req.setTotalWeight(new BigDecimal("200"));
        req.setTotalValue(new BigDecimal("2000"));
        return req;
    }

    @Test
    void create_setsBoxCount_whenProvided() {
        CreateMaterialBatchRequest req = baseCreateRequest();
        req.setBoxCount(8);

        MaterialBatch batch = mapper.toEntity(req, "F001", 1L);

        assertEquals(8, batch.getBoxCount());
        // kg-based fields unaffected by box count.
        assertEquals(0, new BigDecimal("200").compareTo(batch.getReceiptQuantity()));
        assertEquals("kg", batch.getQuantityUnit());
    }

    @Test
    void create_leavesBoxCountNull_whenOmitted() {
        MaterialBatch batch = mapper.toEntity(baseCreateRequest(), "F001", 1L);
        assertNull(batch.getBoxCount(), "箱数 must stay null when not provided (never derived from kg)");
    }

    @Test
    void toDTO_exposesBoxCount() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("200"));
        batch.setBoxCount(8);

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertEquals(8, dto.getBoxCount());
    }

    @Test
    void toDTO_boxCountNull_passesThrough() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("200"));

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertNull(dto.getBoxCount());
    }

    @Test
    void update_setsBoxCount_whenProvided() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("200"));
        batch.setBoxCount(5);

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setBoxCount(9);
        mapper.updateEntity(batch, req);

        assertEquals(9, batch.getBoxCount());
    }

    @Test
    void update_leavesBoxCountUnchanged_whenOmitted() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("200"));
        batch.setBoxCount(5);

        // partial update with no boxCount — existing value must persist.
        mapper.updateEntity(batch, new UpdateMaterialBatchRequest());

        assertEquals(5, batch.getBoxCount());
    }
}
