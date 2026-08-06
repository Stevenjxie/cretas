package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP4 T1 — factoryNumber / originPlace round-trip through {@link MaterialBatchMapper}.
 *
 * <p>Four-point DTO rule:
 * <ol>
 *   <li>Entity field declared</li>
 *   <li>toEntity (create) maps from CreateRequest</li>
 *   <li>updateEntity null-guards (does not overwrite with null)</li>
 *   <li>toDTO maps entity → DTO output</li>
 * </ol>
 */
class MaterialBatchMapperSp4Test {

    private final MaterialBatchMapper mapper = new MaterialBatchMapper();

    private CreateMaterialBatchRequest baseCreateRequest() {
        CreateMaterialBatchRequest req = new CreateMaterialBatchRequest();
        req.setMaterialTypeId("mt-1");
        req.setSupplierId("sup-1");
        req.setReceiptDate(LocalDate.of(2026, 6, 9));
        req.setReceiptQuantity(new BigDecimal("100"));
        req.setQuantityUnit("kg");
        req.setTotalWeight(new BigDecimal("100"));
        req.setTotalValue(new BigDecimal("1000"));
        return req;
    }

    // ── toEntity (create) ──────────────────────────────────────────────────

    @Test
    void create_setsFactoryNumber_whenProvided() {
        CreateMaterialBatchRequest req = baseCreateRequest();
        req.setFactoryNumber("GY-2026-001");

        MaterialBatch batch = mapper.toEntity(req, "F006", 1L);

        assertEquals("GY-2026-001", batch.getFactoryNumber());
    }

    @Test
    void create_setsOriginPlace_whenProvided() {
        CreateMaterialBatchRequest req = baseCreateRequest();
        req.setOriginPlace("山东省青岛市");

        MaterialBatch batch = mapper.toEntity(req, "F006", 1L);

        assertEquals("山东省青岛市", batch.getOriginPlace());
    }

    @Test
    void create_leavesFactoryNumberNull_whenOmitted() {
        MaterialBatch batch = mapper.toEntity(baseCreateRequest(), "F006", 1L);
        assertNull(batch.getFactoryNumber());
    }

    @Test
    void create_leavesOriginPlaceNull_whenOmitted() {
        MaterialBatch batch = mapper.toEntity(baseCreateRequest(), "F006", 1L);
        assertNull(batch.getOriginPlace());
    }

    // ── toDTO ──────────────────────────────────────────────────────────────

    @Test
    void toDTO_exposesFactoryNumber() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setFactoryNumber("GY-2026-001");

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertEquals("GY-2026-001", dto.getFactoryNumber());
    }

    @Test
    void toDTO_exposesOriginPlace() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setOriginPlace("山东省青岛市");

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertEquals("山东省青岛市", dto.getOriginPlace());
    }

    @Test
    void toDTO_factoryNumberNull_passesThrough() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertNull(dto.getFactoryNumber());
    }

    /**
     * 🔴 2026-08-06 实测事故: 来料追溯字段(V20261029_61)加了实体列、加了 keyword 查询、
     * 也加了前端表格列 —— <b>唯独漏了 toDTO 这一层</b>。表现是
     * 「按合同号<b>搜得到</b>那条批次, 搜出来那一列却显示 <code>-</code>」,
     * 前端毫无报错。这正是本文件顶上「Four-point DTO rule」第 4 点存在的理由。
     *
     * <p>判据: 一个字段横跨 实体 / 查询 / DTO / 界面 四处, 漏一处就是
     * 「写得进去、查得到、看不见」——三者都要各自有断言。</p>
     */
    @Test
    void toDTO_carriesTraceabilityFields() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setContractNumber("SAN-16572");
        batch.setSupplierBatchNumber("20251029");

        MaterialBatchDTO dto = mapper.toDTO(batch);

        assertEquals("SAN-16572", dto.getContractNumber(),
                "合同号没进 DTO → 列表按合同号搜得到, 那一列却显示 -");
        assertEquals("20251029", dto.getSupplierBatchNumber(),
                "供应商批次号没进 DTO → 同上");
    }

    // ── updateEntity null-guard ────────────────────────────────────────────

    @Test
    void update_setsFactoryNumber_whenProvided() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setFactoryNumber("OLD-001");

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setFactoryNumber("NEW-002");
        mapper.updateEntity(batch, req);

        assertEquals("NEW-002", batch.getFactoryNumber());
    }

    @Test
    void update_leavesFactoryNumberUnchanged_whenOmitted() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setFactoryNumber("KEEP-001");

        mapper.updateEntity(batch, new UpdateMaterialBatchRequest());

        assertEquals("KEEP-001", batch.getFactoryNumber(), "factoryNumber must not be overwritten by null update");
    }

    @Test
    void update_setsOriginPlace_whenProvided() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setOriginPlace("河南省郑州市");
        mapper.updateEntity(batch, req);

        assertEquals("河南省郑州市", batch.getOriginPlace());
    }

    @Test
    void update_leavesOriginPlaceUnchanged_whenOmitted() {
        MaterialBatch batch = new MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setOriginPlace("山东省青岛市");

        mapper.updateEntity(batch, new UpdateMaterialBatchRequest());

        assertEquals("山东省青岛市", batch.getOriginPlace(), "originPlace must not be overwritten by null update");
    }
}
