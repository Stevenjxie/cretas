package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Query(value = """
            SELECT AVG(x.unit_price)
              FROM (
                    SELECT l.unit_price
                      FROM supplier_delivery_note_lines l
                      JOIN supplier_delivery_notes n ON n.id = l.note_id
                     WHERE l.factory_id = :factoryId
                       AND l.raw_material_type_id = :rawMaterialTypeId
                       AND l.unit_price IS NOT NULL
                       AND n.status = 'CONFIRMED'
                       AND n.deleted_at IS NULL
                       AND n.delivery_date < :deliveryDate
                     ORDER BY n.delivery_date DESC, l.created_at DESC
                     LIMIT 5
                   ) x
            """, nativeQuery = true)
    BigDecimal averageRecentConfirmedPriceByMaterial(
            @Param("factoryId") String factoryId,
            @Param("rawMaterialTypeId") String rawMaterialTypeId,
            @Param("deliveryDate") LocalDate deliveryDate);

    @Query(value = """
            SELECT AVG(x.unit_price)
              FROM (
                    SELECT l.unit_price
                      FROM supplier_delivery_note_lines l
                      JOIN supplier_delivery_notes n ON n.id = l.note_id
                     WHERE l.factory_id = :factoryId
                       AND LOWER(TRIM(l.ingredient_name)) = LOWER(TRIM(:ingredientName))
                       AND l.unit_price IS NOT NULL
                       AND n.status = 'CONFIRMED'
                       AND n.deleted_at IS NULL
                       AND n.delivery_date < :deliveryDate
                     ORDER BY n.delivery_date DESC, l.created_at DESC
                     LIMIT 5
                   ) x
            """, nativeQuery = true)
    BigDecimal averageRecentConfirmedPriceByIngredientName(
            @Param("factoryId") String factoryId,
            @Param("ingredientName") String ingredientName,
            @Param("deliveryDate") LocalDate deliveryDate);
}
