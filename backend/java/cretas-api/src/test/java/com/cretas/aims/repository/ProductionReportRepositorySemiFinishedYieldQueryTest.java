package com.cretas.aims.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("半成品出成率 SQL 口径")
class ProductionReportRepositorySemiFinishedYieldQueryTest {

    @Test
    @DisplayName("只聚合已小结、未删除、非成品且投入产出为正数的全历史行")
    void queryCarriesEveryValidityGuardWithoutDateWindow() throws Exception {
        Method method = ProductionReportRepository.class.getMethod(
                "aggregateSettledSemiFinishedYield", String.class, String.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        String sql = query.value().toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql).contains("from process_sheet_rows");
        assertThat(sql).contains("interim_settled_at is not null");
        assertThat(sql).contains("deleted_at is null");
        assertThat(sql).contains("row_status <> 'draft'");
        assertThat(sql).contains("row_payload ->> 'producttypeid' = :semifinishedskuid");
        assertThat(sql).contains("row_payload ->> 'finished' = 'false'");
        assertThat(sql).contains("input_quantity > 0");
        assertThat(sql).contains("output_quantity > 0");
        assertThat(sql).contains("sum(input_kg)");
        assertThat(sql).contains("sum(output_kg)");
        assertThat(sql).doesNotContain("avg(");
        assertThat(sql).doesNotContain("report_date");
        assertThat(sql).doesNotContain("limit ");
    }
}
