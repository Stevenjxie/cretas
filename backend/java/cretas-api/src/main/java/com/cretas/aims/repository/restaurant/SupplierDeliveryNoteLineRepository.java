package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 供应商送货单行项数据访问 (G7 Tier A)。
 *
 * @since 2026-06-03 (G7)
 */
@Repository
public interface SupplierDeliveryNoteLineRepository extends JpaRepository<SupplierDeliveryNoteLine, Long> {

    List<SupplierDeliveryNoteLine> findByNote_Id(String noteId);

    void deleteByNote_Id(String noteId);
}
